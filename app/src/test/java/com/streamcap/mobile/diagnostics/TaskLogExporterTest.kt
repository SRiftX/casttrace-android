package com.streamcap.mobile.diagnostics

import com.streamcap.mobile.data.database.TaskLogEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLogExporterTest {
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
