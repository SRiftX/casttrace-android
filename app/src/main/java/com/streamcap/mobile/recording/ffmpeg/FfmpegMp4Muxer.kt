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
        output.parentFile?.mkdirs()
        output.delete()
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-fflags", "+genpts+discardcorrupt",
                "-i", input.absolutePath,
                "-map", "0:v?",
                "-map", "0:a?",
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-movflags", "+faststart",
                output.absolutePath,
            ),
        )
        check(ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L) {
            session.failStackTrace ?: session.output ?: "FFmpeg MP4 封装失败"
        }
        Mp4Muxer.inspect(output)
    }
}
