package com.streamcap.mobile.recording

import com.streamcap.mobile.recording.ffmpeg.FfmpegRuntime
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FfmpegRuntimeTest {
    @Test fun wrappedNativeLoadErrorBecomesRecoverableAndKeepsOriginalCause() {
        assertEquals(42, FfmpegRuntime.run { 42 })
        val unrelated = AssertionError("unrelated failure")
        assertSame(unrelated, assertThrows(AssertionError::class.java) { FfmpegRuntime.run<Unit> { throw unrelated } })
        val native = UnsatisfiedLinkError("RELRO protection failed")
        val wrapped = Error("FFmpegKit failed to start", native)
        val first = assertThrows(IOException::class.java) { FfmpegRuntime.run<Unit> { throw wrapped } }
        assertSame(wrapped, first.cause)
        assertTrue(first.message!!.contains("原文件已保留"))
        var retried = false
        val second = assertThrows(IOException::class.java) { FfmpegRuntime.run { retried = true } }
        assertFalse(retried)
        assertSame(wrapped, second.cause)
    }
}
