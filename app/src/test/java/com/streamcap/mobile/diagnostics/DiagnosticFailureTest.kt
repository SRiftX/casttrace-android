package com.streamcap.mobile.diagnostics

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFailureTest {
    @Test
    fun classifiesNetworkFailuresAndKeepsRootCause() {
        val error = IllegalStateException("room check", SocketTimeoutException("read timed out"))

        val description = DiagnosticFailure.describe(error)

        assertTrue(description.contains("网络请求超时"))
        assertTrue(description.contains("IllegalStateException"))
        assertTrue(description.contains("SocketTimeoutException"))
    }

    @Test
    fun hidesUrlsAndBoundsCauseMessages() {
        val error = UnknownHostException("https://cdn.example/live?signature=private")

        val description = DiagnosticFailure.describe(error)

        assertTrue(description.contains("DNS/域名解析失败"))
        assertFalse(description.contains("cdn.example"))
        assertFalse(description.contains("private"))
    }

    @Test
    fun classifiesMissingRoomResponses() {
        val description = DiagnosticFailure.describe(IllegalStateException("HTTP 404: room not found"))

        assertTrue(description.contains("直播间不存在"))
        assertTrue(description.contains("HTTP 404"))
    }
}
