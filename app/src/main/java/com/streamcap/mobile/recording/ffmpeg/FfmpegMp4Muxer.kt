package com.streamcap.mobile.recording.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.streamcap.mobile.recording.mp4.Mp4Muxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 使用 FFmpeg 将录制产生的 MPEG-TS 无损重封装为 MP4。
 * FFmpeg 对 HLS/TS 的时间戳回退、缺失 PTS 和长文件索引处理比 Android MediaExtractor 更可靠。
 */
object FfmpegMp4Muxer {
    suspend fun convert(input: File, output: File): Mp4Muxer.TrackInfo = withContext(Dispatchers.IO) {
        require(input.exists() && input.length() > 0L) { "待封装的 TS 文件为空" }
        convertPath(input.absolutePath, output)
    }

    /** Also accepts an already-open bounded pipe, so preview needs only the MP4 output. */
    suspend fun convertPath(inputPath: String, output: File): Mp4Muxer.TrackInfo = withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        // Keep the .mp4 suffix so FFmpeg can infer the muxer. A suffix such as
        // ".mp4.writing" makes FFmpeg reject the output format before writing anything.
        val staging = File(output.parentFile, ".${output.nameWithoutExtension}.writing.mp4")
        staging.delete()
        try {
            val session = FfmpegRuntime.run { FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y",
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-fflags", "+genpts+discardcorrupt",
                    "-i", inputPath,
                    "-map", "0:v?",
                    "-map", "0:a?",
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    "-movflags", "+faststart",
                    staging.absolutePath,
                ),
            ) }
            check(ReturnCode.isSuccess(session.returnCode) && staging.exists() && staging.length() > 0L) {
                session.failStackTrace ?: session.output ?: "FFmpeg MP4 封装失败"
            }
            check(Mp4Muxer.inspect(staging).videoTracks > 0) { "MP4 中没有可播放的视频轨道，原文件已保留" }
            output.delete()
            check(staging.renameTo(output)) { "无法提交 MP4 文件" }
        } catch (failure: Throwable) {
            staging.delete()
            throw failure
        }
        Mp4Muxer.inspect(output)
    }
}
