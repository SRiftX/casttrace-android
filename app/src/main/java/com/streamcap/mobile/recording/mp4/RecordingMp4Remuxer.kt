package com.streamcap.mobile.recording.mp4

import com.streamcap.mobile.recording.ffmpeg.FfmpegMp4Muxer
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/** One fallback policy for finalization, checkpoints and recovery. */
object RecordingMp4Remuxer {
    suspend fun convert(input: File, output: File,
        onFallback: suspend (Exception) -> Unit = {}): Mp4Muxer.TrackInfo {
        try {
            return FfmpegMp4Muxer.convert(input, output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Vendor FLV extractors may drop every sync flag. Never publish their
            // audio-only result or repeatedly feed them a retained FLV recording.
            val flv = input.inputStream().use { it.read() == 70 && it.read() == 76 && it.read() == 86 }
            if (flv) throw IOException("FLV 转 MP4 失败，原文件已保留，可稍后重试保存：${failure.message}", failure)
            onFallback(failure)
            try {
                return Mp4Muxer.convert(input, output)
            } catch (fallback: Exception) {
                fallback.addSuppressed(failure)
                throw fallback
            }
        }
    }
}
