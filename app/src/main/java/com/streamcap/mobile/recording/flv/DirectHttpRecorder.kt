package com.streamcap.mobile.recording.flv

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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.net.URI

class DirectHttpRecorder(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) : Recorder {
    private data class FlvCopyResult(val totalBytes: Long, val partStartedAt: Long?)

    private data class TimestampState(
        var videoOffset: Long = 0L,
        var audioOffset: Long = 0L,
        var lastVideo: Long = Long.MIN_VALUE,
        var lastAudio: Long = Long.MIN_VALUE,
        var lastVideoArrivalMillis: Long? = null,
        var lastAudioArrivalMillis: Long? = null,
        var firstMedia: Long = Long.MIN_VALUE,
        var lastMedia: Long = Long.MIN_VALUE,
        val recentPackets: LinkedHashSet<String> = linkedSetOf(),
    )
    private data class RunningRecording(
        val progress: MutableStateFlow<RecordingProgress>,
        var call: Call? = null,
        var job: Job? = null,
        @Volatile var stopped: Boolean = false,
    )

    private val recordings = ConcurrentHashMap<String, RunningRecording>()

    override suspend fun start(request: RecordingRequest): RecordingHandle {
        require(request.streamType == StreamType.FLV || request.streamType == StreamType.UNKNOWN) {
            "HLS 需要分片录制器，当前直链录制器只处理 FLV/HTTP 媒体流"
        }
        val handle = RecordingHandle(UUID.randomUUID().toString())
        val running = RunningRecording(MutableStateFlow(RecordingProgress(RecorderState.STARTING)))
        recordings[handle.id] = running
        running.job = scope.launch(Dispatchers.IO) {
            var written = 0L
            var lastReportAt = 0L
            var lastReportedFileCount = 0
            var reconnectCount = 0
            val reconnectWindow = ReconnectWindow()
            var firstResponse = true
            var streamUrl = request.url
            var streamHeaders = request.headers
            val outputFiles = mutableListOf(request.destination)
            val completedFiles = mutableListOf<File>()
            val timestampState = TimestampState()
            fun mediaDurationSeconds(): Long =
                if (timestampState.firstMedia == Long.MIN_VALUE) 0L
                else (timestampState.lastMedia - timestampState.firstMedia).coerceAtLeast(0L) / 1_000L
            var partStartedAt: Long? = null
            var lastMediaAt = 0L
            var mediaPackets = 0L
            var lastHttpStatus = 0
            fun host(url: String): String = runCatching { URI(url).host ?: "unknown" }.getOrDefault("unknown")
            fun event(type: String, oldUrl: String = streamUrl, newUrl: String = streamUrl) {
                request.eventLogger?.invoke(type, mapOf(
                    "retry" to reconnectCount.toString(), "oldHost" to host(oldUrl),
                    "newHost" to host(newUrl), "httpStatus" to lastHttpStatus.toString(),
                    "lastMediaAt" to lastMediaAt.toString(), "bytes" to written.toString(),
                    "part" to outputFiles.size.toString(),
                    "result" to when {
                        type != "STREAM_REFRESH_RESULT" -> "-"
                        newUrl.isBlank() -> "no_new_source"
                        newUrl == oldUrl -> "same_url_failed"
                        else -> "switched_source"
                    },
                ))
            }
            try {
                while (!running.stopped) {
                    val beforeMediaPackets = mediaPackets
                    try {
                        val requestBuilder = Request.Builder().url(secureStreamUrl(streamUrl))
                        streamHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
                        val call = client.newCall(requestBuilder.build())
                        running.call = call
                        call.execute().use { response ->
                            lastHttpStatus = response.code
                            if (!response.isSuccessful) event("STREAM_HTTP_ERROR")
                            check(response.isSuccessful) { "直播源响应 ${response.code}" }
                            val body = checkNotNull(response.body) { "直播源没有返回媒体数据" }
                            body.byteStream().buffered().use { input ->
                                val report: () -> Unit = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportAt >= 1_000 || outputFiles.size != lastReportedFileCount) {
                                        running.progress.value = RecordingProgress(
                                            state = RecorderState.RECORDING,
                                            bytesWritten = written,
                                            durationSeconds = mediaDurationSeconds(),
                                            outputFiles = outputFiles.toList(),
                                            completedOutputFiles = completedFiles.toList(),
                                        )
                                        lastReportAt = now
                                        lastReportedFileCount = outputFiles.size
                                    }
                                }
                                if (request.streamType == StreamType.FLV) {
                                    written = copyFlv(
                                        input = input,
                                        firstFile = request.destination,
                                        // Segmentation must remain enabled after a reconnect. The
                                        // previous code only applied it to the first HTTP response,
                                        // allowing a long recording to grow into one unbounded file.
                                        segmentSeconds = request.segmentSeconds,
                                        files = outputFiles,
                                        totalBefore = written,
                                        append = !firstResponse,
                                        timestampState = timestampState,
                                        partStartedAt = partStartedAt,
                                        completedFiles = completedFiles,
                                        onProgress = { total, partStart, media ->
                                            if (media) {
                                                lastMediaAt = System.currentTimeMillis()
                                                mediaPackets++
                                            }
                                            written = total; partStartedAt = partStart
                                            if (media) report()
                                        },
                                    ).also { result ->
                                        written = result.totalBytes
                                        partStartedAt = result.partStartedAt
                                    }.totalBytes
                                } else {
                                    request.destination.outputStream().buffered().use { output ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            output.write(buffer, 0, read)
                                            written += read
                                            lastMediaAt = System.currentTimeMillis()
                                            mediaPackets++
                                            report()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException || running.stopped || request.refreshSource == null) throw error
                        event("STREAM_HTTP_ERROR")
                        // Preserve complete tags already written and renew the URL
                        // inside this recording rather than publishing a short session.
                    }
                    if (running.stopped) break
                    event("STREAM_READ_EOF")
                    // copyFlv closes the writer on every disconnect. Keep this
                    // unpublished tail for recovery and resume appending on the
                    // next connection; only normal segment boundaries publish it.
                    // Huya's HS CDN closes short chunked FLV responses after
                    // delivering valid media. Reopening the same CDN is a
                    // seamless continuation, not a user-visible outage.
                    val receivedMedia = mediaPackets > beforeMediaPackets
                    running.progress.value = RecordingProgress(
                        state = if (receivedMedia) RecorderState.RECORDING else RecorderState.RECONNECTING,
                        bytesWritten = written,
                        durationSeconds = mediaDurationSeconds(),
                        outputFiles = outputFiles.toList(),
                        completedOutputFiles = completedFiles.toList(),
                    )
                    firstResponse = written == 0L
                    reconnectCount = if (receivedMedia) 0 else reconnectCount + 1
                    if (!reconnectWindow.shouldContinue(receivedMedia, written > 13L, System.nanoTime())) {
                        event("STREAM_RETRY_EXHAUSTED")
                        throw StreamRetryExhaustedException("直播源连续断开，未收到新的媒体数据")
                    }
                    // Productive short FLV responses are normal on Huya. Reopen
                    // the same signed URL first; refresh only after an empty or
                    // rejected response, avoiding a resolver request per EOF.
                    val huyaShortResponse = host(streamUrl).endsWith(".huya.com") && receivedMedia &&
                        lastHttpStatus !in setOf(401, 403)
                    if (!running.stopped && request.refreshSource != null && !huyaShortResponse) {
                        event("STREAM_REFRESH_STARTED")
                        val previousUrl = streamUrl
                        // A signed CDN URL that returned 401/403 is unusable even
                        // when it delivered media earlier. Tell the selector to
                        // rotate hosts instead of retaining the failed CDN.
                        val sourceStillProductive = receivedMedia && lastHttpStatus !in setOf(401, 403)
                        val refreshed = try { request.refreshSource.invoke(sourceStillProductive) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (ended: com.streamcap.mobile.recording.engine.LiveStreamEndedException) { throw ended }
                        catch (_: Exception) { null }
                        if (refreshed != null) {
                            event("STREAM_REFRESH_RESULT", previousUrl, refreshed.url)
                            streamUrl = refreshed.url
                            streamHeaders = refreshed.headers
                        } else {
                            event("STREAM_REFRESH_RESULT", previousUrl, "")
                        }
                    }
                    if (!running.stopped) {
                        event("STREAM_RETRY_WAIT")
                        delay(RECONNECT_DELAY_MILLIS)
                    }
                }
                running.progress.value = RecordingProgress(
                    state = if (running.stopped) RecorderState.STOPPED else RecorderState.COMPLETED,
                    bytesWritten = written,
                    durationSeconds = mediaDurationSeconds(),
                    outputFiles = outputFiles,
                    completedOutputFiles = completedFiles.toList(),
                )
            } catch (cancelled: CancellationException) {
                running.progress.value = running.progress.value.copy(
                    state = RecorderState.STOPPED,
                    bytesWritten = written,
                    outputFiles = outputFiles.toList(),
                    completedOutputFiles = completedFiles.toList(),
                )
            } catch (error: Throwable) {
                if (running.progress.value.state != RecorderState.STOPPED) {
                    running.progress.value = RecordingProgress(
                        state = when {
                            running.stopped -> RecorderState.STOPPED
                            error is com.streamcap.mobile.recording.engine.LiveStreamEndedException -> RecorderState.COMPLETED
                            else -> RecorderState.FAILED
                        },
                        bytesWritten = written,
                        durationSeconds = mediaDurationSeconds(),
                        errorMessage = if (error is com.streamcap.mobile.recording.engine.LiveStreamEndedException) null else error.message ?: "录制失败",
                        outputFiles = outputFiles,
                        completedOutputFiles = completedFiles.toList(),
                        liveEnded = error is com.streamcap.mobile.recording.engine.LiveStreamEndedException,
                        retryExhausted = error is StreamRetryExhaustedException,
                    )
                }
            }
        }
        return handle
    }

    override suspend fun stop(handle: RecordingHandle, reason: StopReason) {
        recordings[handle.id]?.let { running ->
            running.stopped = true
            // Publish STOPPED only after the writer has closed in the worker.
            running.call?.cancel()
            running.job?.cancel()
        }
    }

    override fun observe(handle: RecordingHandle): Flow<RecordingProgress> {
        val running = requireNotNull(recordings[handle.id]) { "录制句柄不存在" }
        var terminalObserved = false
        return running.progress.onEach { terminalObserved = it.state in TERMINAL_STATES }
            .onCompletion { if (terminalObserved) recordings.remove(handle.id, running) }
    }

    private fun copyFlv(
        input: InputStream,
        firstFile: File,
        segmentSeconds: Int?,
        files: MutableList<File>,
        totalBefore: Long,
        append: Boolean,
        timestampState: TimestampState,
        partStartedAt: Long?,
        completedFiles: MutableList<File>,
        onProgress: (Long, Long?, Boolean) -> Unit,
    ): FlvCopyResult {
        val prefix = input.readExactOrNull(13) ?: return FlvCopyResult(totalBefore, partStartedAt)
        val hasHeader = prefix.copyOfRange(0, 3).contentEquals(FLV_SIGNATURE)
        require(!append && hasHeader || append) { "直播源返回的内容不是有效 FLV 文件" }
        val header = if (hasHeader) prefix else FLV_SIGNATURE
        val tagInput = if (hasHeader) input else BufferedInputStream(SequenceInputStream(ByteArrayInputStream(prefix), input))
        val currentFile = files.lastOrNull() ?: firstFile
        var output = BufferedOutputStream(FileOutputStream(currentFile, append))
        if (!append) output.write(header)
        var total = if (append) totalBefore else totalBefore + header.size
        var currentPartStartedAt = partStartedAt
        var part = files.size
        var seenVideo = false
        var seenAudio = false
        val initTags = linkedMapOf<String, ByteArray>()
        try {
            while (true) {
                val tagHeader = tagInput.readExactOrNull(11) ?: break
                val dataSize = ((tagHeader[1].toInt() and 0xff) shl 16) or
                    ((tagHeader[2].toInt() and 0xff) shl 8) or (tagHeader[3].toInt() and 0xff)
                val timestamp = ((tagHeader[7].toLong() and 0xff) shl 24) or
                    ((tagHeader[4].toLong() and 0xff) shl 16) or
                    ((tagHeader[5].toLong() and 0xff) shl 8) or (tagHeader[6].toLong() and 0xff)
                val bodyAndPreviousSize = tagInput.readExact(dataSize + 4)
                val type = tagHeader[0].toInt() and 0xff
                val isConfiguration = dataSize > 1 && bodyAndPreviousSize[1].toInt() == 0 &&
                    (type == 9 || (type == 8 && (bodyAndPreviousSize[0].toInt() and 0xf0) == 0xa0))
                val isMediaTag = (type == 8 || type == 9) && !isConfiguration
                if (isMediaTag) {
                    // Reconnected CDNs can replay their last GOP. A timestamp
                    // rollback alone must not turn identical cached frames into
                    // extra video. Include the original timestamp so static frames
                    // at different times are still preserved.
                    // This runs for every audio/video tag. A cryptographic digest
                    // here saturates older devices during high-bitrate Huya streams.
                    val fingerprint = "$type:$timestamp:${bodyAndPreviousSize.contentHashCode()}"
                    if (!timestampState.recentPackets.add(fingerprint)) continue
                    if (timestampState.recentPackets.size > 10_000) {
                        val iterator = timestampState.recentPackets.iterator()
                        iterator.next()
                        iterator.remove()
                    }
                }
                var normalizedTimestamp = timestamp
                if (isConfiguration) {
                    // Codec initialization packets commonly use timestamp zero,
                    // including on reconnect. They are not a media clock reset.
                    normalizedTimestamp = (if (type == 9) timestampState.lastVideo else timestampState.lastAudio)
                        .takeIf { it != Long.MIN_VALUE } ?: 0L
                }
                if (isMediaTag) {
                    val rawTimestamp = timestamp
                    val isVideo = type == 9
                    val lastTimestamp = if (isVideo) timestampState.lastVideo else timestampState.lastAudio
                    val arrivalMillis = System.nanoTime() / 1_000_000L
                    val previousArrival = if (isVideo) timestampState.lastVideoArrivalMillis else timestampState.lastAudioArrivalMillis
                    val offset = flvTimelineOffset(rawTimestamp,
                        if (isVideo) timestampState.videoOffset else timestampState.audioOffset,
                        lastTimestamp, append && !(if (isVideo) seenVideo else seenAudio),
                        previousArrival?.let { arrivalMillis - it })
                    if (isVideo) timestampState.lastVideoArrivalMillis = arrivalMillis
                    else timestampState.lastAudioArrivalMillis = arrivalMillis
                    if (isVideo) { timestampState.videoOffset = offset; seenVideo = true }
                    else { timestampState.audioOffset = offset; seenAudio = true }
                    normalizedTimestamp = rawTimestamp + offset
                    if (isVideo) timestampState.lastVideo = maxOf(timestampState.lastVideo, normalizedTimestamp)
                    else timestampState.lastAudio = maxOf(timestampState.lastAudio, normalizedTimestamp)
                    if (timestampState.firstMedia == Long.MIN_VALUE) timestampState.firstMedia = normalizedTimestamp
                    timestampState.lastMedia = maxOf(timestampState.lastMedia, normalizedTimestamp)
                    // FLV 时间戳是 32 位低位 + 8 位扩展，重连后重新从 0 开始时补偿为连续时间轴。
                }
                tagHeader[4] = (normalizedTimestamp ushr 16).toByte()
                tagHeader[5] = (normalizedTimestamp ushr 8).toByte()
                tagHeader[6] = normalizedTimestamp.toByte()
                tagHeader[7] = (normalizedTimestamp ushr 24).toByte()
                val tag = tagHeader + bodyAndPreviousSize
                val isVideoKeyFrame = type == 9 && dataSize > 0 && !isConfiguration &&
                    ((bodyAndPreviousSize[0].toInt() ushr 4) and 0x0f) == 1
                if (currentPartStartedAt == null && isMediaTag) currentPartStartedAt = normalizedTimestamp
                when {
                    type == 18 -> initTags["meta"] = tag
                    type == 8 && dataSize > 1 && (bodyAndPreviousSize[0].toInt() and 0xf0) == 0xa0 && bodyAndPreviousSize[1].toInt() == 0 -> initTags["audio"] = tag
                    type == 9 && dataSize > 1 && (bodyAndPreviousSize[0].toInt() and 0x0f) == 7 && bodyAndPreviousSize[1].toInt() == 0 -> initTags["video"] = tag
                }
                val splitAt = segmentSeconds?.times(1_000L)
                val partBaseline = currentPartStartedAt
                if (splitAt != null && splitAt > 0 && partBaseline != null &&
                    normalizedTimestamp - partBaseline >= splitAt && isVideoKeyFrame
                ) {
                    output.flush()
                    output.close()
                    files.lastOrNull()?.let { completedFiles += it }
                    part++
                    val next = siblingPart(firstFile, part)
                    files += next
                    output = BufferedOutputStream(FileOutputStream(next))
                    output.write(header)
                    initTags.values.forEach(output::write)
                    currentPartStartedAt = normalizedTimestamp
                }
                output.write(tag)
                total += tag.size
                onProgress(total, currentPartStartedAt, isMediaTag)
            }
        } catch (incomplete: EOFException) {
            if (total <= if (append) totalBefore else totalBefore + header.size) throw incomplete
            // 连接在最后一个 Tag 中途结束时，丢弃这个未完成 Tag，保留此前完整数据。
        } finally {
            output.flush()
            output.close()
        }
        return FlvCopyResult(total, currentPartStartedAt)
    }

    private fun InputStream.readExact(size: Int): ByteArray =
        readExactOrNull(size) ?: throw EOFException("FLV 数据在 Tag 中途结束")

    private fun InputStream.readExactOrNull(size: Int): ByteArray? {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) return if (offset == 0) null else throw EOFException("媒体数据不完整")
            offset += read
        }
        return result
    }

    private fun siblingPart(first: File, index: Int): File {
        val marker = ".flv.part"
        val name = if (first.name.endsWith(marker)) {
            first.name.removeSuffix(marker) + "_${index.toString().padStart(3, '0')}" + marker
        } else {
            val dot = first.name.lastIndexOf('.')
            if (dot >= 0) first.name.substring(0, dot) + "_${index.toString().padStart(3, '0')}" + first.name.substring(dot)
            else "${first.name}_${index.toString().padStart(3, '0')}"
        }
        return File(first.parentFile, name)
    }

    companion object {
        private val TERMINAL_STATES = setOf(RecorderState.COMPLETED, RecorderState.STOPPED, RecorderState.FAILED)
        private val FLV_SIGNATURE = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte())
        private const val RECONNECT_DELAY_MILLIS = 1_500L
    }
}
