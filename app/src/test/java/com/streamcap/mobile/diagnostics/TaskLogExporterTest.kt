package com.streamcap.mobile.diagnostics

import com.streamcap.mobile.data.database.TaskLogEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogExporterTest {
    @Test fun exportIncludesNormalLiveEndWithoutCountingItAsAnError() {
        val report = TaskLogExporter.buildErrorReport(listOf(
            TaskLogEntity(level = "INFO", eventType = "LIVE_ENDED", roomId = 3, sessionId = 9, message = "主播已下播"),
            TaskLogEntity(level = "INFO", eventType = "RECONNECT_WAIT", roomId = 3, message = "等待重连"),
        ), "test", "device")
        assertTrue(report.contains("错误数量: 0"))
        assertTrue(report.contains("INFO LIVE_ENDED"))
        assertTrue(report.contains("RECONNECT_WAIT"))
    }
    @Test fun previewHidesUnknownSignedUrlsAndNestedCredentialsAndCapsSize() {
        val report = TaskLogExporter.buildPreviewReport(listOf(TaskLogEntity(level = "ERROR", eventType = "FAILED",
            message = "https://cdn.example/path?unusualSignature=privateUnknown\n{\\\"token\\\":\\\"privateNested\\\"}\n" + "x".repeat(200_000))), "test", "device")
        assertFalse(report.contains("privateUnknown"))
        assertFalse(report.contains("privateNested"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size < 131_072)
    }
    @Test fun redactsWholeHeadersAndJsonSecrets() {
        val result = TaskLogExporter.sanitize("Authorization: Bearer privateBearer\nCookie: sid=privateSid; token=privateCookie\n{\"access_token\":\"privateJson\"}\nhttps://cdn/live?wsSecret=privateSignature&room=1")
        listOf("privateBearer", "privateSid", "privateCookie", "privateJson", "privateSignature").forEach {
            assertFalse(result.contains(it))
        }
        assertTrue(result.contains("room=1"))
    }
    @Test
    fun exportsOnlyErrorLogsAndRedactsSecrets() {
        val report = TaskLogExporter.buildErrorReport(
            logs = listOf(
                TaskLogEntity(
                    id = 1,
                    level = "INFO",
                    eventType = "LIVE_CHECK",
                    message = "普通检查信息",
                    createdAt = 1_000,
                ),
                TaskLogEntity(
                    id = 2,
                    level = "ERROR",
                    eventType = "ROOM_ADD_FAILED",
                    message = "请求失败 token=secret https://example.com/live?sign=private&room=1",
                    createdAt = 2_000,
                ),
            ),
            appVersion = "0.1.0",
            deviceInfo = "test-device",
        )

        assertTrue(report.contains("ROOM_ADD_FAILED"))
        assertTrue(report.contains("token=[REDACTED]"))
        assertTrue(report.contains("sign=[REDACTED]"))
        assertFalse(report.contains("普通检查信息"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("private"))
    }
}
