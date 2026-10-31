package com.streamcap.mobile.diagnostics

import com.streamcap.mobile.data.database.TaskLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TaskLogExporter {
    fun buildFullReport(logs: List<TaskLogEntity>, appVersion: String, deviceInfo: String): String = buildString {
        appendLine("CastTrace 任务日志")
        appendLine("应用版本: $appVersion")
        appendLine("设备信息: $deviceInfo")
        appendLine("导出时间: ${formatTime(System.currentTimeMillis())}")
        appendLine("记录数量: ${logs.size}（最多最近 20000 条）")
        appendLine("=".repeat(72))
        logs.forEach { log ->
            appendLine("[${formatTime(log.createdAt)}] ${log.level} ${log.eventType} roomId=${log.roomId ?: "-"} sessionId=${log.sessionId ?: "-"}")
            appendLine(sanitize(log.message).replace(Regex("(?i)https?://[^\\s<>\"]+"), "[链接已隐藏]"))
            appendLine()
        }
    }

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
        val recordingContext = logs.filter { log ->
            log.sessionId != null || log.eventType in RECORDING_CONTEXT_EVENTS || log.eventType.startsWith("BACKGROUND_")
        }.filterNot { it.level.equals("ERROR", ignoreCase = true) }.sortedByDescending { it.createdAt }.take(200)
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
        if (recordingContext.isNotEmpty()) {
            appendLine("录制与重连上下文（最近 ${recordingContext.size} 条）")
            recordingContext.forEach { log ->
                appendLine("[${formatTime(log.createdAt)}] ${log.level} ${log.eventType}")
                appendLine("roomId=${log.roomId ?: "-"} sessionId=${log.sessionId ?: "-"}")
                appendLine(sanitize(log.message))
                appendLine("-".repeat(72))
            }
        }
    }

    fun buildPreviewReport(logs: List<TaskLogEntity>, appVersion: String, deviceInfo: String): String {
        val report = buildErrorReport(logs.take(100), appVersion, deviceInfo)
        val withoutUrls = Regex("(?i)https?://[^\\s<>\"]+").replace(report, "[链接已隐藏]")
        // Unescape nested JSON once, then redact again to cover embedded exception payloads.
        val safe = sanitize(withoutUrls.replace("\\\"", "\""))
        return safe.take(32_000) + if (safe.length > 32_000) "\n[报告已截断]" else ""
    }

    fun sanitize(message: String): String {
        var sanitized = SENSITIVE_HEADER.replace(message) { "${it.groupValues[1]}: [REDACTED]" }
        sanitized = SENSITIVE_JSON.replace(sanitized) { "\"${it.groupValues[1]}\":\"[REDACTED]\"" }
        SENSITIVE_ASSIGNMENT.replace(sanitized) { match -> "${match.groupValues[1]}=[REDACTED]" }
            .also { sanitized = it }
        SENSITIVE_QUERY.replace(sanitized) { match -> "${match.groupValues[1]}[REDACTED]" }
            .also { sanitized = it }
        return sanitized
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    private val SENSITIVE_ASSIGNMENT = Regex(
        "(?i)\\b(cookie|authorization|password|passwd|token|access_token|refresh_token|sign|signature|wsSecret|wsTime)\\s*[:=]\\s*([^\\s,;&]+)",
    )
    private val RECORDING_CONTEXT_EVENTS = setOf("LIVE_ENDED", "STREAM_REFRESH_REQUESTED", "RECONNECT_WAIT", "RECORDING_RETRY", "TIMER_STOP")
    private val SENSITIVE_HEADER = Regex("(?im)\\b(cookie|set-cookie|authorization)\\s*:\\s*[^\\r\\n]+")
    private val SENSITIVE_JSON = Regex("(?i)\"(cookie|authorization|password|passwd|token|access_token|refresh_token|sign|signature|wsSecret)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"")
    private val SENSITIVE_QUERY = Regex(
        "(?i)([?&](?:token|sign|signature|auth|cookie|session|key)=)[^&\\s]+",
    )
}
