package com.streamcap.mobile.recording.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.RecordingHandle
import com.streamcap.mobile.recording.engine.RecordingProgress
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.engine.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 使用 FFmpeg 直接读取 HLS。电脑端也是这条路径，FFmpeg 会负责 HLS 重连、PTS
 * 连续化和 MPEG-TS 封装，避免手写分片拼接造成 1-2 分钟后卡顿。
 */
class FfmpegHlsRecorder(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : Recorder {
    private data class Running(
        val progress: MutableStateFlow<RecordingProgress>,
        var session: FFmpegSession? = null,
        var job: Job? = null,
        var stopped: Boolean = false,
    )

    private val recordings = ConcurrentHashMap<String, Running>()

    override suspend fun start(request: RecordingRequest): RecordingHandle {
        require(request.streamType == StreamType.HLS) { "FFmpeg HLS 录制器只处理 m3u8" }
        val handle = RecordingHandle(UUID.randomUUID().toString())
        val running = Running(MutableStateFlow(RecordingProgress(RecorderState.STARTING)))
        recordings[handle.id] = running
        running.job = scope.launch(Dispatchers.IO) { record(request, running) }
        return handle
    }

    override suspend fun stop(handle: RecordingHandle, reason: StopReason) {
        recordings[handle.id]?.let { running ->
            running.stopped = true
            // 先取消 FFmpeg 会话，让它有机会关闭输入和刷新最后一个分段。
            // 直接取消协程会跳过文件发现逻辑，表现为“停止后仍在录制”或最后一段丢失。
            running.session?.cancel()
            running.job?.let { job ->
                val stoppedNormally = withTimeoutOrNull(STOP_WAIT_MILLIS) {
                    job.join()
                    true
                } == true
                if (!stoppedNormally) job.cancel()
            }
        }
    }

    override fun observe(handle: RecordingHandle): Flow<RecordingProgress> {
        val running = requireNotNull(recordings[handle.id]) { "录制句柄不存在" }
        var terminalObserved = false
        return running.progress.onEach { terminalObserved = it.state in TERMINAL_STATES }
            .onCompletion { if (terminalObserved) recordings.remove(handle.id, running) }
    }

    private suspend fun record(request: RecordingRequest, running: Running) {
        val outputFiles = mutableListOf<File>()
        try {
            var currentUrl = request.url
            var currentHeaders = request.headers
            var attempt = 0
            var accumulatedDuration = 0L
            var lastError: String? = null
            var reconnectAttempt = 0
            var lastStatisticsAt = 0L
            while (!running.stopped) {
                val destination = attemptDestination(request.destination, attempt)
                val attemptRequest = request.copy(url = currentUrl, headers = currentHeaders, destination = destination)
                val completion = CompletableDeferred<FFmpegSession>()
                val reportedMessages = linkedSetOf<String>()
                val logCallback = LogCallback { log ->
                    val message = log.message.trim().replace(Regex("https?://[^\\s'\"]+"), "<media-url>").take(1000)
                    if (message.contains("error", ignoreCase = true)) lastError = message
                    synchronized(reportedMessages) {
                        if (message.isNotBlank() && reportedMessages.size < 12 && reportedMessages.add(message)) {
                            request.eventLogger?.invoke("FFMPEG_INPUT_MESSAGE", mapOf("message" to message.take(1000)))
                            android.util.Log.w("FfmpegHls", message.take(1000))
                        }
                    }
                }
                val statisticsCallback = StatisticsCallback { statistics ->
                    val now = System.currentTimeMillis()
                    if (now - lastStatisticsAt < STATISTICS_INTERVAL_MILLIS && !running.stopped) return@StatisticsCallback
                    lastStatisticsAt = now
                    val files = discoverFiles(destination, request.segmentSeconds)
                    synchronized(outputFiles) {
                        files.forEach { file -> if (file !in outputFiles) outputFiles += file }
                    }
                    running.progress.value = RecordingProgress(
                        state = RecorderState.RECORDING,
                        bytesWritten = outputFiles.sumOf { it.length() },
                        // FFmpegKit 的 Statistics.time 单位是毫秒，应用状态统一使用秒。
                        durationSeconds = accumulatedDuration + (statistics.time / 1_000.0).toLong().coerceAtLeast(0L),
                        outputFiles = outputFiles.toList(),
                    )
                }
                if (running.stopped) break
                val trackInputs = if (isNaver(attemptRequest.url)) {
                    val master = httpClient.newCall(Request.Builder().url(attemptRequest.url)
                        .apply { attemptRequest.headers.forEach(::header) }.build()).execute().use {
                        check(it.isSuccessful) { "HLS 主列表 HTTP ${it.code}" }
                        requireNotNull(it.body).string()
                    }
                    independentTrackInputs(attemptRequest.url, master)
                } else null
                running.session = FfmpegRuntime.run { FFmpegKit.executeWithArgumentsAsync(
                    buildCommand(attemptRequest, trackInputs).toTypedArray(),
                    { session -> completion.complete(session) },
                    logCallback,
                    statisticsCallback,
                ) }
                running.progress.value = running.progress.value.copy(state = RecorderState.RECORDING)
                val session = completion.await()
                val attemptDuration = ((session.getLastReceivedStatistics()?.time ?: 0.0) / 1_000.0).toLong()
                accumulatedDuration += attemptDuration
                val files = discoverFiles(destination, request.segmentSeconds)
                synchronized(outputFiles) { files.forEach { file -> if (file !in outputFiles) outputFiles += file } }
                if (running.stopped) break
                val success = ReturnCode.isSuccess(session.returnCode)
                // 直播线路短暂失效时不要立即高速重启 FFmpeg，避免 CPU/IO 飙升和重复报错。
                val backoff = (RECONNECT_BASE_DELAY_MILLIS * (1L shl reconnectAttempt.coerceIn(0, 5)))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MILLIS)
                delay(backoff)
                val refreshed = runCatching { request.refreshSource?.invoke(false) }.getOrNull()
                if (refreshed == null) {
                    running.progress.value = RecordingProgress(
                        state = if (success) RecorderState.COMPLETED else RecorderState.FAILED,
                        bytesWritten = outputFiles.sumOf { it.length() },
                        durationSeconds = accumulatedDuration,
                        errorMessage = if (success) null else session.failStackTrace ?: lastError ?: "FFmpeg 录制失败",
                        outputFiles = outputFiles.toList(),
                    )
                    break
                }
                currentUrl = refreshed.url
                currentHeaders = refreshed.headers
                attempt++
                reconnectAttempt = if (success) 0 else (reconnectAttempt + 1).coerceAtMost(5)
                lastError = null
            }
            if (running.stopped) {
                running.progress.value = RecordingProgress(
                    state = RecorderState.STOPPED,
                    bytesWritten = outputFiles.sumOf { it.length() },
                    durationSeconds = accumulatedDuration,
                    outputFiles = outputFiles.toList(),
                )
            }
        } catch (cancelled: CancellationException) {
            running.progress.value = running.progress.value.copy(state = RecorderState.STOPPED)
        } catch (error: Throwable) {
            running.progress.value = RecordingProgress(
                state = RecorderState.FAILED,
                bytesWritten = outputFiles.sumOf { it.length() },
                durationSeconds = running.progress.value.durationSeconds,
                errorMessage = error.message ?: "FFmpeg 录制失败",
                outputFiles = outputFiles.toList(),
            )
        }
    }

    private fun buildCommand(request: RecordingRequest, trackInputs: Pair<String, String>?): List<String> {
        val inputHeaders = if (request.headers.isEmpty()) "" else
            request.headers.entries.joinToString("\r\n", postfix = "\r\n") { (name, value) -> "$name: $value" }
        val command = mutableListOf(
            "-y",
            "-hide_banner",
            "-loglevel", "warning",
        )
        val inputOptions = mutableListOf(
            "-rw_timeout", "15000000",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            // HLS segments are finite files. Retrying their normal EOF would
            // reopen the same fragment forever and prevent recording progress.
            "-reconnect_delay_max", "30",
            "-fflags", "+discardcorrupt+genpts",
            "-protocol_whitelist", "file,http,https,tcp,tls,crypto",
        )
        // Naver serves fMP4 video using .m4v names. Newer FFmpeg HLS builds
        // otherwise reject these valid fragments because the suffix is atypical.
        if (isNaver(request.url)) {
            inputOptions += listOf("-extension_picky", "0")
        }
        if (inputHeaders.isNotBlank()) inputOptions += listOf("-headers", inputHeaders)
        if (trackInputs == null) {
            command += inputOptions + listOf("-i", request.url, "-map", "0")
        } else {
            // Separate input reader threads prevent a slow audio fragment from
            // blocking video until its short rolling playlist has expired.
            // Stream info is in each fMP4 init. Avoid the default multi-second
            // probe of input 0 expiring input 1's three-fragment live window.
            inputOptions += listOf("-analyzeduration", "200000", "-probesize", "131072")
            command += inputOptions + listOf("-thread_queue_size", "512", "-i", trackInputs.first)
            command += inputOptions + listOf("-thread_queue_size", "512", "-i", trackInputs.second)
            command += listOf("-map", "0:v:0", "-map", "1:a:0")
        }
        command += listOf("-c", "copy", "-avoid_negative_ts", "make_non_negative")
        if (request.segmentSeconds != null && request.segmentSeconds > 0) {
            command += listOf(
                "-f", "segment",
                "-segment_time", request.segmentSeconds.toString(),
                "-segment_format", "mpegts",
                "-reset_timestamps", "1",
                siblingPattern(request.destination),
            )
        } else {
            command += listOf("-f", "mpegts", request.destination.absolutePath)
        }
        return command
    }

    private fun siblingPattern(file: File): String {
        val marker = file.name.indexOf(".ts.part")
        val name = if (marker >= 0) file.name.substring(0, marker) + "_%03d.ts.part" else file.name + "_%03d"
        return File(file.parentFile, name).absolutePath
    }

    private fun attemptDestination(first: File, attempt: Int): File {
        if (attempt == 0) return first
        val marker = first.name.indexOf(".ts.part")
        val name = if (marker >= 0) {
            first.name.substring(0, marker) + "_r${attempt.toString().padStart(3, '0')}" + first.name.substring(marker)
        } else {
            "${first.name}_r${attempt.toString().padStart(3, '0')}"
        }
        return File(first.parentFile, name)
    }

    private fun discoverFiles(first: File, segmentSeconds: Int?): List<File> {
        if (segmentSeconds == null || segmentSeconds <= 0) return listOf(first).filter { it.exists() && it.length() > 0 }
        val prefix = first.name.substringBefore(".ts.part")
        return first.parentFile?.listFiles { file ->
            file.name.startsWith("${prefix}_") && file.name.endsWith(".ts.part") && file.length() > 0
        }?.sortedBy { it.name }.orEmpty()
    }

    private companion object {
        private val TERMINAL_STATES = setOf(RecorderState.COMPLETED, RecorderState.STOPPED, RecorderState.FAILED)
        private const val STATISTICS_INTERVAL_MILLIS = 5_000L
        private const val STOP_WAIT_MILLIS = 10_000L
        private const val RECONNECT_BASE_DELAY_MILLIS = 1_000L
        private const val RECONNECT_MAX_DELAY_MILLIS = 30_000L
    }
}

private fun isNaver(url: String) = runCatching {
    java.net.URI(url).host?.endsWith(".edge.naverncp.com") == true
}.getOrDefault(false)

internal fun independentTrackInputs(url: String, master: String): Pair<String, String>? {
    val lines = master.lineSequence().map(String::trim).toList()
    val variantIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
    if (variantIndex < 0) return null
    val group = Regex("(?:^|,)AUDIO=\"([^\"]+)\"").find(lines[variantIndex].substringAfter(':'))
        ?.groupValues?.get(1) ?: return null
    val audio = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") &&
        Regex("(?:^|,)GROUP-ID=\"([^\"]+)\"").find(it.substringAfter(':'))?.groupValues?.get(1) == group }
        ?.let { Regex("(?:^|,)URI=\"([^\"]+)\"").find(it.substringAfter(':'))?.groupValues?.get(1) }
        ?: error("HLS 主列表缺少外置音轨")
    val video = lines.drop(variantIndex + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
        ?: error("HLS 主列表缺少视频线路")
    val base = url.toHttpUrl()
    return requireNotNull(base.resolve(video)).toString() to requireNotNull(base.resolve(audio)).toString()
}
