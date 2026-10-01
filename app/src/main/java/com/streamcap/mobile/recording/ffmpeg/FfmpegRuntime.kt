package com.streamcap.mobile.recording.ffmpeg

import java.io.IOException

internal object FfmpegRuntime {
    @Volatile private var loadFailure: Error? = null

    fun <T> run(operation: () -> T): T {
        loadFailure?.let { throw unavailable(it) }
        try {
            return operation()
        } catch (failure: Error) {
            // FFmpegKit NativeLoader wraps dlopen's UnsatisfiedLinkError in Error.
            if (failure !is LinkageError && generateSequence(failure.cause) { it.cause }
                    .none { it is LinkageError }) throw failure
            loadFailure = failure
            throw unavailable(failure)
        }
    }

    private fun unavailable(cause: Error) = IOException(
        "FFmpeg 本机库不可用，录制原文件已保留。请重启应用后重试保存或播放：${cause.message}", cause,
    )
}
