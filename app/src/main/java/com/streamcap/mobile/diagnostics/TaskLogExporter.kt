package com.streamcap.mobile.diagnostics

import com.streamcap.mobile.data.database.TaskLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TaskLogExporter {
    fun buildErrorReport(
        logs: List<TaskLogEntity>,
        appVersion: String,
        deviceInfo: String,
    ): String = buildString {
        appendLine("StreamCap Android 错误日志")
        appendLine("应用版本: $appVersion")
        appendLine("设备信息: $deviceInfo")
        appendLine("导出时间: ${formatTime(System.currentTimeMillis())}")
        appendLine("错误数量: ${logs.count { it.level.equals("ERROR", ignoreCase = true) }}")
        appendLine("=".repeat(72))

        val errors = logs.filter { it.level.equals("ERROR", ignoreCase = true) }
        if (errors.isEmpty()) {
            appendLine("暂无错误日志")
        } else {
            errors.sortedByDescending { it.createdAt }.forEach { log ->
                appendLine("[${formatTime(log.createdAt)}] ${log.eventType}")
                appendLine("roomId=${log.roomId ?: "-"} sessionId=${log.sessionId ?: "-"}")
                appendLine(sanitize(log.message))
                appendLine("-".repeat(72))
            }
        }
    }

    fun sanitize(message: String): String {
        var sanitized = message
        SENSITIVE_ASSIGNMENT.replace(sanitized) { match -> "${match.groupValues[1]}=[REDACTED]" }
            .also { sanitized = it }
        SENSITIVE_QUERY.replace(sanitized) { match -> "${match.groupValues[1]}[REDACTED]" }
            .also { sanitized = it }
        return sanitized
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    private val SENSITIVE_ASSIGNMENT = Regex(
        "(?i)\\b(cookie|authorization|password|passwd|token|access_token|refresh_token)\\s*[:=]\\s*([^\\s,;]+)",
    )
    private val SENSITIVE_QUERY = Regex(
        "(?i)([?&](?:token|sign|signature|auth|cookie|session|key)=)[^&\\s]+",
    )
}
