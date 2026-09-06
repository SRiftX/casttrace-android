package com.streamcap.mobile.recording.flv

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.RecordingHandle
import com.streamcap.mobile.recording.engine.RecordingProgress
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.engine.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

class DirectHttpRecorder(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) : Recorder {
    private data class TimestampState(
        var videoOffset: Long = 0L,
        var audioOffset: Long = 0L,
        var lastVideo: Long = Long.MIN_VALUE,
        var lastAudio: Long = Long.MIN_VALUE,
    )
    private data class RunningRecording(
        val progress: MutableStateFlow<RecordingProgress>,
        var call: Call? = null,
        var job: Job? = null,
        var stopped: Boolean = false,
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
            val startedAt = System.currentTimeMillis()
            var written = 0L
            var lastReportAt = 0L
            var lastReportedFileCount = 0
            var reconnectCount = 0
            var emptyReconnects = 0
            var firstResponse = true
            val outputFiles = mutableListOf(request.destination)
            val timestampState = TimestampState()
            try {
                while (!running.stopped) {
                    val beforeResponse = written
                    val requestBuilder = Request.Builder().url(request.url)
                    request.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
                    val call = client.newCall(requestBuilder.build())
                    running.call = call
                    call.execute().use { response ->
                        check(response.isSuccessful) { "直播源响应 ${response.code}" }
                        val body = checkNotNull(response.body) { "直播源没有返回媒体数据" }
                        body.byteStream().buffered().use { input ->
                            val report: () -> Unit = {
                                val now = System.currentTimeMillis()
                                if (now - lastReportAt >= 1_000 || outputFiles.size != lastReportedFileCount) {
                                    running.progress.value = RecordingProgress(
                                        state = RecorderState.RECORDING,
                                        bytesWritten = written,
                                        durationSeconds = (now - startedAt) / 1_000,
                                        outputFiles = outputFiles.toList(),
                                    )
                                    lastReportAt = now
                                    lastReportedFileCount = outputFiles.size
                                }
                            }
                            if (request.streamType == StreamType.FLV) {
                                written = copyFlv(
                                    input = input,
                                    firstFile = request.destination,
                                    segmentSeconds = request.segmentSeconds.takeIf { firstResponse },
                                    files = outputFiles,
                                    totalBefore = written,
                                    append = !firstResponse,
                                    timestampState = timestampState,
                                    onProgress = { total -> written = total; report() },
                                )
                            } else {
                                request.destination.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        output.write(buffer, 0, read)
                                        written += read
                                        report()
                                    }
                                }
                            }
                        }
                    }
                    firstResponse = false
                    reconnectCount++
                    if (written == beforeResponse) emptyReconnects++ else emptyReconnects = 0
                    if (emptyReconnects >= MAX_EMPTY_RECONNECTS) {
                        error("直播源连续断开，未收到新的媒体数据")
                    }
                    if (!running.stopped && reconnectCount > 0) delay(RECONNECT_DELAY_MILLIS)
                }
                running.progress.value = RecordingProgress(
                    state = if (running.stopped) RecorderState.STOPPED else RecorderState.COMPLETED,
                    bytesWritten = written,
                    durationSeconds = (System.currentTimeMillis() - startedAt) / 1_000,
                    outputFiles = outputFiles,
                )
            } catch (cancelled: CancellationException) {
                running.progress.value = running.progress.value.copy(
                    state = RecorderState.STOPPED,
                    bytesWritten = written,
                    outputFiles = outputFiles.toList(),
                )
            } catch (error: Throwable) {
                if (running.progress.value.state != RecorderState.STOPPED) {
                    running.progress.value = RecordingProgress(
                        state = RecorderState.FAILED,
                        bytesWritten = written,
                        durationSeconds = (System.currentTimeMillis() - startedAt) / 1_000,
                        errorMessage = error.message ?: "录制失败",
                        outputFiles = outputFiles,
                    )
                }
            }
        }
        return handle
    }

    override suspend fun stop(handle: RecordingHandle, reason: StopReason) {
        recordings[handle.id]?.let { running ->
            running.stopped = true
            running.progress.value = running.progress.value.copy(state = RecorderState.STOPPED)
            running.call?.cancel()
            running.job?.cancel()
        }
    }

    override fun observe(handle: RecordingHandle): Flow<RecordingProgress> =
        requireNotNull(recordings[handle.id]) { "录制句柄不存在" }.progress

    private fun copyFlv(
        input: InputStream,
        firstFile: File,
        segmentSeconds: Int?,
        files: MutableList<File>,
        totalBefore: Long,
        append: Boolean,
        timestampState: TimestampState,
        onProgress: (Long) -> Unit,
    ): Long {
        val prefix = input.readExactOrNull(13) ?: return totalBefore
        val hasHeader = prefix.copyOfRange(0, 3).contentEquals(FLV_SIGNATURE)
        require(!append && hasHeader || append) { "直播源返回的内容不是有效 FLV 文件" }
        val header = if (hasHeader) prefix else FLV_SIGNATURE
        val tagInput = if (hasHeader) input else BufferedInputStream(SequenceInputStream(ByteArrayInputStream(prefix), input))
        val currentFile = files.lastOrNull() ?: firstFile
        var output = BufferedOutputStream(FileOutputStream(currentFile, append))
        if (!append) output.write(header)
        var total = if (append) totalBefore else header.size.toLong()
        var partStartedAt: Long? = null
        var part = files.size
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
                val isMediaTag = type == 8 || type == 9
                if (isMediaTag) {
                    val rawTimestamp = timestamp
                    val isVideo = type == 9
                    var normalizedTimestamp = rawTimestamp + if (isVideo) timestampState.videoOffset else timestampState.audioOffset
                    val lastTimestamp = if (isVideo) timestampState.lastVideo else timestampState.lastAudio
                    if (lastTimestamp != Long.MIN_VALUE && lastTimestamp - normalizedTimestamp >= FLV_TIMELINE_RESET_MILLIS) {
                        if (isVideo) timestampState.videoOffset += lastTimestamp - normalizedTimestamp + 1L
                        else timestampState.audioOffset += lastTimestamp - normalizedTimestamp + 1L
                        normalizedTimestamp = rawTimestamp + if (isVideo) timestampState.videoOffset else timestampState.audioOffset
                    }
                    if (isVideo) timestampState.lastVideo = maxOf(timestampState.lastVideo, normalizedTimestamp)
                    else timestampState.lastAudio = maxOf(timestampState.lastAudio, normalizedTimestamp)
                    // FLV 时间戳是 32 位低位 + 8 位扩展，重连后重新从 0 开始时补偿为连续时间轴。
                    tagHeader[4] = (normalizedTimestamp ushr 16).toByte()
                    tagHeader[5] = (normalizedTimestamp ushr 8).toByte()
                    tagHeader[6] = normalizedTimestamp.toByte()
                    tagHeader[7] = (normalizedTimestamp ushr 24).toByte()
                }
                val tag = tagHeader + bodyAndPreviousSize
                val isVideoKeyFrame = type == 9 && dataSize > 0 &&
                    ((bodyAndPreviousSize[0].toInt() ushr 4) and 0x0f) == 1
                if (partStartedAt == null && isMediaTag) partStartedAt = timestamp
                when {
                    type == 18 -> initTags["meta"] = tag
                    type == 8 && dataSize > 1 && (bodyAndPreviousSize[0].toInt() and 0x0f) == 10 && bodyAndPreviousSize[1].toInt() == 0 -> initTags["audio"] = tag
                    type == 9 && dataSize > 1 && (bodyAndPreviousSize[0].toInt() and 0x0f) == 7 && bodyAndPreviousSize[1].toInt() == 0 -> initTags["video"] = tag
                }
                val splitAt = segmentSeconds?.times(1_000L)
                val partBaseline = partStartedAt
                if (splitAt != null && splitAt > 0 && partBaseline != null &&
                    timestamp - partBaseline >= splitAt && isVideoKeyFrame
                ) {
                    output.flush()
                    output.close()
                    part++
                    val next = siblingPart(firstFile, part)
                    files += next
                    output = BufferedOutputStream(FileOutputStream(next))
                    output.write(header)
                    initTags.values.forEach(output::write)
                    partStartedAt = timestamp
                }
                output.write(tag)
                total += tag.size
                onProgress(total)
            }
        } catch (incomplete: EOFException) {
            if (total <= if (append) totalBefore else header.size.toLong()) throw incomplete
            // 连接在最后一个 Tag 中途结束时，丢弃这个未完成 Tag，保留此前完整数据。
        } finally {
            output.flush()
            output.close()
        }
        return total
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
        private val FLV_SIGNATURE = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte())
        private const val RECONNECT_DELAY_MILLIS = 1_500L
        private const val MAX_EMPTY_RECONNECTS = 3
        private const val FLV_TIMELINE_RESET_MILLIS = 2_000L
    }
}
