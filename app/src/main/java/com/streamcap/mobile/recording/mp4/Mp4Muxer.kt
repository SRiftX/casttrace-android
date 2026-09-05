package com.streamcap.mobile.recording.mp4

import android.media.MediaExtractor
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 使用 Android 系统 MediaExtractor/MediaMuxer 将 TS 重新封装为真正的 MP4。 */
object Mp4Muxer {
    /** 封装结果同时返回成品媒体时长，数据库应优先使用这个值。 */
    data class TrackInfo(
        val audioTracks: Int,
        val videoTracks: Int,
        val durationSeconds: Long = 0,
    )

    suspend fun convert(input: File, output: File): TrackInfo = withContext(Dispatchers.IO) {
        require(input.exists() && input.length() > 0) { "待封装的 TS 文件为空" }
        output.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var audioTracks = 0
        var videoTracks = 0
        try {
            extractor.setDataSource(input.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = IntArray(extractor.trackCount) { -1 }
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                    extractor.selectTrack(index)
                    trackMap[index] = muxer.addTrack(format)
                    if (mime.startsWith("audio/")) audioTracks++ else videoTracks++
                }
            }
            check(trackMap.any { it >= 0 }) { "TS 文件中没有可封装的音视频轨道" }
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocateDirect(512 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            // HLS 换线路时，新的 TS 片段经常从 0 重新开始 PTS。直接写入会让
            // MediaMuxer 认为媒体已经倒退，最终 MP4 的时长可能只有前半段。
            // 每条轨道独立归零，并在检测到回退时补偿一个连续的时间偏移。
            val firstPts = LongArray(extractor.trackCount) { Long.MIN_VALUE }
            val lastPts = LongArray(extractor.trackCount) { Long.MIN_VALUE }
            val timestampOffset = LongArray(extractor.trackCount)
            val continuityStep = LongArray(extractor.trackCount) { 33_333L }
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) continuityStep[index] = 23_220L
            }
            var maxPresentationTimeUs = 0L
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val rawPts = extractor.sampleTime
                if (rawPts < 0L) {
                    extractor.advance()
                    continue
                }
                if (firstPts[track] == Long.MIN_VALUE) firstPts[track] = rawPts
                var pts = rawPts - firstPts[track] + timestampOffset[track]
                val previous = lastPts[track]
                if (previous != Long.MIN_VALUE && pts <= previous) {
                    if (previous - pts >= TIMELINE_RESET_THRESHOLD_US) {
                        // 只有秒级回退才是 CDN/编码器重置。普通 B 帧乱序不能累加到后续所有帧。
                        timestampOffset[track] += previous - pts + continuityStep[track]
                        pts = rawPts - firstPts[track] + timestampOffset[track]
                    } else {
                        pts = previous + 1L
                    }
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = pts
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(trackMap[track], buffer, info)
                lastPts[track] = pts
                maxPresentationTimeUs = maxOf(maxPresentationTimeUs, pts)
                extractor.advance()
            }
            muxer.stop()
            val metadataDuration = durationSeconds(output)
            val timestampDuration = ((maxPresentationTimeUs + 999_999L) / 1_000_000L)
            TrackInfo(audioTracks, videoTracks, maxOf(metadataDuration, timestampDuration))
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
            if (!output.exists() || output.length() == 0L) output.delete()
        }
    }

    /** 读取成品中的音视频轨道与真实媒体时长，用于发布后列表显示和缺少音轨提示。 */
    fun inspect(file: File): TrackInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioTracks = 0
            var videoTracks = 0
            var durationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                when {
                    format.getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") -> audioTracks++
                    format.getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("video/") -> videoTracks++
                }
                durationUs = maxOf(durationUs, format.getLongOrNull(android.media.MediaFormat.KEY_DURATION) ?: 0L)
            }
            TrackInfo(audioTracks, videoTracks, (durationUs + 999_999L) / 1_000_000L)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** 从最终 MP4 的轨道元数据读取实际时长，避免使用下载运行时间冒充媒体时长。 */
    private fun durationSeconds(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val durationUs = (0 until extractor.trackCount)
                .mapNotNull { index ->
                    extractor.getTrackFormat(index)
                        .getLongOrNull(android.media.MediaFormat.KEY_DURATION)
                }
                .maxOrNull()
                ?: 0L
            ((durationUs + 999_999L) / 1_000_000L).coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun android.media.MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private const val TIMELINE_RESET_THRESHOLD_US = 2_000_000L
}
