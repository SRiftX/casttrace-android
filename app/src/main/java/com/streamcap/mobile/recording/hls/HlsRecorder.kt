package com.streamcap.mobile.recording.hls

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.platform.resolver.secureStreamUrl
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.RecordingHandle
import com.streamcap.mobile.recording.engine.RecordingProgress
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.engine.StopReason
import com.streamcap.mobile.recording.engine.StreamRetryExhaustedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsRecorder(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) : Recorder {
    // Keep the long-lived FLV client untouched, but bound every HLS request so
    // a stalled CDN connection reaches the existing source-refresh/retry path.
    private val requestClient = client.newBuilder()
        .readTimeout(HLS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HLS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private data class Running(
        val progress: MutableStateFlow<RecordingProgress>,
        var job: Job? = null,
        var call: Call? = null,
        @Volatile var lastHttpStatus: Int = 0,
        @Volatile var stopped: Boolean = false,
    )

    private data class KeyInfo(val method: String, val uri: String?, val iv: ByteArray?)
    private data class Segment(val id: String, val url: String, val duration: Double, val sequence: Long, val key: KeyInfo?)
    private data class MediaPlaylist(
        val segments: List<Segment>,
        val targetDuration: Double,
        val endList: Boolean,
        val initMapUrl: String?,
    )

    private val recordings = ConcurrentHashMap<String, Running>()

    override suspend fun start(request: RecordingRequest): RecordingHandle {
        require(request.streamType == StreamType.HLS) { "HLS 录制器只能处理 m3u8 播放列表" }
        val handle = RecordingHandle(UUID.randomUUID().toString())
        val running = Running(MutableStateFlow(RecordingProgress(RecorderState.STARTING)))
        recordings[handle.id] = running
        running.job = scope.launch(Dispatchers.IO) { record(request, running) }
        return handle
    }

    override suspend fun stop(handle: RecordingHandle, reason: StopReason) {
        recordings[handle.id]?.let { running ->
            running.stopped = true
            running.call?.cancel()
            running.job?.cancel()
        }
    }

    override fun observe(handle: RecordingHandle): Flow<RecordingProgress> =
        requireNotNull(recordings[handle.id]) { "录制句柄不存在" }.progress

    private suspend fun record(request: RecordingRequest, running: Running) {
        val startedAt = System.currentTimeMillis()
        val files = mutableListOf(request.destination)
        val completedFiles = mutableListOf<File>()
        val seen = linkedSetOf<String>()
        var liveEdgeInitialized = false
        val keyCache = mutableMapOf<String, ByteArray>()
        var playlistUrl = request.url
        var headers = request.headers
        var output = BufferedOutputStream(FileOutputStream(request.destination))
        var totalBytes = 0L
        var partBytes = 0L
        var partDuration = 0.0
        var totalDuration = 0.0
        var part = 1
        var initMapWritten: String? = null
        var maxSequenceSeen = Long.MIN_VALUE
        var lastMediaAt = System.currentTimeMillis()
        var stalledRefreshes = 0
        var consecutiveErrors = 0
        var terminal: RecordingProgress? = null
        fun host(url: String): String = runCatching { URI(url).host ?: "unknown" }.getOrDefault("unknown")
        fun event(type: String, oldUrl: String = playlistUrl, newUrl: String = playlistUrl) {
            request.eventLogger?.invoke(type, mapOf(
                "retry" to consecutiveErrors.toString(), "oldHost" to host(oldUrl),
                "newHost" to host(newUrl), "httpStatus" to running.lastHttpStatus.toString(),
                "lastMediaAt" to lastMediaAt.toString(), "bytes" to totalBytes.toString(),
                "part" to part.toString(),
                "result" to when {
                    type != "STREAM_REFRESH_RESULT" -> "-"
                    newUrl.isBlank() -> "no_new_source"
                    newUrl == oldUrl -> "same_url_failed"
                    else -> "switched_source"
                },
            ))
        }
        // 线路切换后分开保存，避免不同时间轴的 TS 生硬拼接导致后段卡顿。
        suspend fun rotateOutput() {
            if (partBytes <= 0L) return
            output.flush()
            output.close()
            files.lastOrNull()?.let { completedFiles += it }
            part++
            val next = siblingPart(request.destination, part)
            files += next
            output = BufferedOutputStream(FileOutputStream(next))
            partDuration = 0.0
            partBytes = 0L
            initMapWritten = null
            maxSequenceSeen = Long.MIN_VALUE
        }
        try {
            while (!running.stopped) {
                try {
                    playlistUrl = resolveMediaPlaylist(playlistUrl, headers, running)
                    break
                } catch (error: Throwable) {
                    if (error is CancellationException || running.stopped) throw error
                    if (error is com.streamcap.mobile.recording.engine.LiveStreamEndedException) throw error
                    event("STREAM_HTTP_ERROR")
                    running.progress.value = running.progress.value.copy(state = RecorderState.RECONNECTING)
                    if (++consecutiveErrors >= 3) {
                        event("STREAM_RETRY_EXHAUSTED")
                        throw StreamRetryExhaustedException("HLS 线路连续失败", error)
                    }
                    event("STREAM_REFRESH_STARTED")
                    val previousUrl = playlistUrl
                    val refreshed = refreshSource(request) ?: throw error
                    event("STREAM_REFRESH_RESULT", previousUrl, refreshed.url)
                    headers = refreshed.headers
                    playlistUrl = refreshed.url
                    delay(END_LIST_REFRESH_DELAY_MILLIS)
                }
            }
            while (!running.stopped) {
                try {
                    // Keep a healthy media timeline. Refresh only on HTTP failure,
                    // ENDLIST or a stalled playlist (handled below); resolving a new
                    // CDN every minute can reset sequence numbers and split output.
                    val playlistText = downloadText(playlistUrl, headers, running)
                    if (playlistText.contains("#EXT-X-STREAM-INF")) {
                        playlistUrl = resolveMediaPlaylist(playlistUrl, headers, running)
                        continue
                    }
                    val playlist = parseMediaPlaylist(playlistUrl, playlistText)
                    // A live playlist normally contains a rolling backlog. Do not
                    // count that backlog as newly recorded time; start near the
                    // live edge and keep the latest segment for continuity.
                    if (!liveEdgeInitialized && !playlist.endList && playlist.segments.size > 3) {
                        playlist.segments.dropLast(1).forEach { seen += it.id }
                        liveEdgeInitialized = true
                    } else if (!liveEdgeInitialized) {
                        liveEdgeInitialized = true
                    }
                    val playlistFirstSequence = playlist.segments.firstOrNull()?.sequence
                    if (playlistFirstSequence != null &&
                        maxSequenceSeen != Long.MIN_VALUE &&
                        playlist.segments.last().sequence < maxSequenceSeen
                    ) {
                        // 部分 CDN 换签名后会把 MEDIA-SEQUENCE 重置为 0，不能把新分片误判成旧分片。
                        rotateOutput()
                        seen.clear()
                    }
                    playlist.segments.maxOfOrNull { it.sequence }?.let { maxSequenceSeen = maxOf(maxSequenceSeen, it) }
                    if (playlist.initMapUrl != null && playlist.initMapUrl != initMapWritten) {
                        val init = downloadBytes(playlist.initMapUrl, headers, running)
                        output.write(init)
                        totalBytes += init.size
                        partBytes += init.size
                        initMapWritten = playlist.initMapUrl
                    }
                    var downloaded = false
                    for (segment in playlist.segments) {
                        if (running.stopped || segment.id in seen) continue
                        if (request.segmentSeconds != null && request.segmentSeconds > 0 &&
                            partDuration > 0 && partDuration + segment.duration > request.segmentSeconds
                        ) {
                            rotateOutput()
                            playlist.initMapUrl?.let { mapUrl ->
                                val init = downloadBytes(mapUrl, headers, running)
                                output.write(init)
                                totalBytes += init.size
                                partBytes += init.size
                                initMapWritten = mapUrl
                            }
                        }
                        var bytes = downloadBytes(segment.url, headers, running)
                        check(!bytes.take(16).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith("#EXTM3U")) {
                            "HLS segment returned a playlist instead of media"
                        }
                        segment.key?.let { key ->
                            check(key.method == "AES-128") { "暂不支持 HLS 加密方式 ${key.method}" }
                            val keyUrl = requireNotNull(key.uri) { "HLS AES-128 缺少密钥地址" }
                            val secret = keyCache.getOrPut(keyUrl) { downloadBytesBlocking(keyUrl, headers) }
                            bytes = decryptAes128(bytes, secret, key.iv ?: sequenceIv(segment.sequence))
                        }
                        output.write(bytes)
                        output.flush()
                        seen += segment.id
                        partDuration += segment.duration
                        totalDuration += segment.duration
                        totalBytes += bytes.size
                        partBytes += bytes.size
                        downloaded = true
                        consecutiveErrors = 0
                        if (running.progress.value.state == RecorderState.RECONNECTING) {
                            running.progress.value = running.progress.value.copy(state = RecorderState.RECORDING)
                        }
                        lastMediaAt = System.currentTimeMillis()
                        stalledRefreshes = 0
                        running.progress.value = RecordingProgress(
                            state = RecorderState.RECORDING,
                            bytesWritten = totalBytes,
                            durationSeconds = totalDuration.roundToLong(),
                            outputFiles = files.toList(),
                            completedOutputFiles = completedFiles.toList(),
                        )
                    }
                    if (playlist.endList) {
                        event("STREAM_READ_EOF")
                        running.progress.value = running.progress.value.copy(state = RecorderState.RECONNECTING)
                        event("STREAM_REFRESH_STARTED")
                        val previousUrl = playlistUrl
                        val refreshed = refreshSource(request) ?: if (request.refreshSource == null) break
                            else error("直播播放列表结束，刷新线路失败")
                        event("STREAM_REFRESH_RESULT", previousUrl, refreshed.url)
                        delay(END_LIST_REFRESH_DELAY_MILLIS)
                        headers = refreshed.headers
                        val nextPlaylistUrl = runCatching { resolveMediaPlaylist(refreshed.url, headers, running) }
                            .getOrElse { refreshed.url }
                        playlistUrl = nextPlaylistUrl
                        if (!downloaded) {
                            stalledRefreshes++
                            if (stalledRefreshes >= MAX_STALLED_REFRESHES) {
                                error("刷新多条线路后仍然没有新媒体分片")
                            }
                        }
                        delay(END_LIST_REFRESH_DELAY_MILLIS * stalledRefreshes.coerceIn(1, 5))
                        continue
                    }
                    val keep = playlist.segments.mapTo(hashSetOf()) { it.id }
                    if (seen.size > 1_000) seen.retainAll(keep)
                    if (!downloaded && System.currentTimeMillis() - lastMediaAt >= STALL_TIMEOUT_MILLIS) {
                        event("STREAM_READ_EOF")
                        running.progress.value = running.progress.value.copy(state = RecorderState.RECONNECTING)
                        check(++stalledRefreshes < MAX_STALLED_REFRESHES) { "直播线路长时间没有新媒体分片" }
                        event("STREAM_REFRESH_STARTED")
                        val previousUrl = playlistUrl
                        val refreshed = refreshSource(request)
                        if (refreshed != null) {
                            event("STREAM_REFRESH_RESULT", previousUrl, refreshed.url)
                            headers = refreshed.headers
                            val nextPlaylistUrl = runCatching { resolveMediaPlaylist(refreshed.url, headers, running) }
                                .getOrElse { refreshed.url }
                            playlistUrl = nextPlaylistUrl
                            continue
                        }
                        event("STREAM_REFRESH_RESULT", previousUrl, "")
                        error("直播线路停止更新")
                    }
                    delay(if (downloaded) 500L else (playlist.targetDuration * 500).toLong().coerceIn(1_000, 8_000))
                } catch (error: Throwable) {
                    if (error is CancellationException || running.stopped) throw error
                    if (error is com.streamcap.mobile.recording.engine.LiveStreamEndedException) throw error
                    event("STREAM_HTTP_ERROR")
                    running.progress.value = running.progress.value.copy(state = RecorderState.RECONNECTING)
                    System.err.println("StreamCap HLS retry: ${error.message}")
                    if (++consecutiveErrors >= 6) {
                        event("STREAM_RETRY_EXHAUSTED")
                        throw StreamRetryExhaustedException("HLS 线路连续失败", error)
                    }
                    event("STREAM_REFRESH_STARTED")
                    val previousUrl = playlistUrl
                    val refreshed = refreshSource(request)
                    if (refreshed == null) {
                        event("STREAM_REFRESH_RESULT", previousUrl, "")
                        event("STREAM_RETRY_WAIT")
                        delay(END_LIST_REFRESH_DELAY_MILLIS)
                        continue
                    }
                    event("STREAM_REFRESH_RESULT", previousUrl, refreshed.url)
                    // 403、Socket closed 等线路错误只刷新签名，不结束当前录制会话。
                    delay(END_LIST_REFRESH_DELAY_MILLIS)
                    headers = refreshed.headers
                    val nextPlaylistUrl = runCatching { resolveMediaPlaylist(refreshed.url, headers, running) }
                        .getOrElse { refreshed.url }
                    playlistUrl = nextPlaylistUrl
                }
            }
            terminal = RecordingProgress(
                state = RecorderState.STOPPED.takeIf { running.stopped } ?: RecorderState.COMPLETED,
                bytesWritten = totalBytes,
                durationSeconds = totalDuration.roundToLong(),
                outputFiles = files,
                completedOutputFiles = completedFiles.toList(),
            )
        } catch (cancelled: CancellationException) {
            terminal = RecordingProgress(
                RecorderState.STOPPED,
                totalBytes,
                totalDuration.roundToLong(),
                outputFiles = files,
                completedOutputFiles = completedFiles.toList(),
            )
        } catch (error: Throwable) {
            terminal = RecordingProgress(
                when {
                    running.stopped -> RecorderState.STOPPED
                    error is com.streamcap.mobile.recording.engine.LiveStreamEndedException -> RecorderState.COMPLETED
                    else -> RecorderState.FAILED
                },
                totalBytes,
                totalDuration.roundToLong(),
                if (running.stopped || error is com.streamcap.mobile.recording.engine.LiveStreamEndedException) null else error.message ?: "HLS 录制失败",
                files,
                completedFiles.toList(),
                liveEnded = error is com.streamcap.mobile.recording.engine.LiveStreamEndedException,
                retryExhausted = error is StreamRetryExhaustedException,
            )
        } finally {
            runCatching { output.flush() }
            runCatching { output.close() }
            terminal?.let { running.progress.value = it }
        }
    }

    private suspend fun resolveMediaPlaylist(url: String, headers: Map<String, String>, running: Running): String {
        val text = downloadText(url, headers, running)
        if (!text.contains("#EXT-X-STREAM-INF")) return url
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val variants = mutableListOf<Pair<Long, String>>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val child = lines.drop(index + 1).firstOrNull { !it.startsWith('#') } ?: return@forEachIndexed
            variants += bandwidth to resolve(url, child)
        }
        return variants.maxByOrNull { it.first }?.second ?: error("HLS 主播放列表没有可用线路")
    }

    private fun parseMediaPlaylist(url: String, text: String): MediaPlaylist {
        require(!text.contains("#EXT-X-STREAM-INF")) { "Expected a media playlist, received a master playlist" }
        require(text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() == "#EXTM3U") { "直播源返回的不是有效 m3u8" }
        var sequence = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val target = Regex("#EXT-X-TARGETDURATION:([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 4.0
        val initMap = Regex("#EXT-X-MAP:.*?URI=\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.get(1)?.let { resolve(url, it) }
        var duration = 0.0
        var key: KeyInfo? = null
        val segments = mutableListOf<Segment>()
        text.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.get(1).orEmpty()
                    key = if (method == "NONE") null else KeyInfo(
                        method,
                        Regex("URI=\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1)?.let { resolve(url, it) },
                        Regex("IV=0x([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let(::hexBytes),
                    )
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    val segmentUrl = resolve(url, line)
                    // 签名 URL 会在续录时变化，去重不能把 query 参数作为分片身份。
                    // 同一媒体序号切换 CDN/签名后仍是同一个分片，不能重复写入。
                    val stableId = sequence.toString()
                    segments += Segment(stableId, segmentUrl, duration, sequence, key)
                    sequence++
                    duration = 0.0
                }
            }
        }
        return MediaPlaylist(segments, target, text.contains("#EXT-X-ENDLIST"), initMap)
    }

    private suspend fun downloadText(url: String, headers: Map<String, String>, running: Running): String =
        downloadBytes(url, headers, running).toString(Charsets.UTF_8)

    private suspend fun refreshSource(request: RecordingRequest) =
        try { request.refreshSource?.invoke(false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (ended: com.streamcap.mobile.recording.engine.LiveStreamEndedException) { throw ended }
        catch (error: Exception) {
            System.err.println("StreamCap HLS source refresh: ${error.javaClass.simpleName}: ${error.message}")
            null
        }

    private suspend fun downloadBytes(url: String, headers: Map<String, String>, running: Running): ByteArray =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply { headers.forEach(::header) }.build()
            val call = requestClient.newCall(request)
            running.call = call
            running.lastHttpStatus = 0
            call.execute().use { response ->
                running.lastHttpStatus = response.code
                check(response.isSuccessful) {
                    "下载 HLS 数据失败：HTTP ${response.code}（${response.request.url.host}${response.request.url.encodedPath}）"
                }
                checkNotNull(response.body).bytes()
            }
        }

    private fun downloadBytesBlocking(url: String, headers: Map<String, String>): ByteArray {
        val request = Request.Builder().url(url).apply { headers.forEach(::header) }.build()
        return requestClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载 HLS 密钥失败：HTTP ${response.code}" }
            checkNotNull(response.body).bytes()
        }
    }

    private fun decryptAes128(bytes: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "HLS AES-128 密钥长度错误" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.copyOf(16)))
        return cipher.doFinal(bytes)
    }

    private fun sequenceIv(sequence: Long) = ByteArray(16).also { output ->
        var value = sequence
        for (index in 15 downTo 0) {
            output[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
    }

    private fun hexBytes(hex: String): ByteArray {
        val normalized = hex.padStart(32, '0').takeLast(32)
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun resolve(base: String, child: String): String =
        secureStreamUrl(URI(base).resolve(child).toString())

    private fun siblingPart(first: File, index: Int): File {
        val marker = listOf(".ts.part", ".m3u8.part").firstOrNull { first.name.endsWith(it) }
        val suffix = "_${index.toString().padStart(3, '0')}"
        val name = if (marker != null) {
            first.name.removeSuffix(marker) + suffix + marker
        } else {
            val dot = first.name.lastIndexOf('.')
            if (dot >= 0) first.name.substring(0, dot) + suffix + first.name.substring(dot) else first.name + suffix
        }
        return File(first.parentFile, name)
    }

    companion object {
        private const val END_LIST_REFRESH_DELAY_MILLIS = 1_000L
        private const val STALL_TIMEOUT_MILLIS = 15_000L
        private const val MAX_STALLED_REFRESHES = 6
        private const val HLS_READ_TIMEOUT_SECONDS = 20L
        private const val HLS_CALL_TIMEOUT_SECONDS = 30L
    }
}
