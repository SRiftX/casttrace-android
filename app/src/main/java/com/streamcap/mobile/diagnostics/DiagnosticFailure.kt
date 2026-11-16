package com.streamcap.mobile.diagnostics

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object DiagnosticFailure {
    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .toList()
        val messages = chain.mapNotNull { it.message }
        val category = when {
            messages.any { it.contains("HTTP 429", ignoreCase = true) } -> "平台请求频率受限"
            messages.any { Regex("HTTP 40[13]").containsMatchIn(it) } -> "平台拒绝请求或要求登录验证"
            messages.any { Regex("HTTP 5[0-9]{2}").containsMatchIn(it) } -> "平台服务器响应异常"
            messages.any { Regex("HTTP 404").containsMatchIn(it) } -> "页面或直播间不存在（HTTP 404）"
            messages.any { Regex("HTTP 4[0-9]{2}").containsMatchIn(it) } -> "平台拒绝或无法处理请求（HTTP 4xx）"
            chain.any { it is UnknownHostException } -> "DNS/域名解析失败"
            chain.any { it is SocketTimeoutException } -> "网络请求超时"
            chain.any { it is SSLException } -> "TLS/证书连接失败"
            chain.any { it is SecurityException } -> "权限或系统策略拒绝"
            chain.any { it is IOException } -> "网络或文件 I/O 失败"
            messages.any { it.contains("json", ignoreCase = true) || it.contains("解析", ignoreCase = true) } -> "平台响应格式异常"
            else -> "应用处理异常"
        }
        val details = chain.joinToString(" <- ") { cause ->
            val message = cause.message
                ?.replace(Regex("(?i)https?://[^\\s<>\\\"]+"), "[链接已隐藏]")
                ?.take(MAX_MESSAGE_LENGTH)
                .orEmpty()
            cause::class.java.simpleName + if (message.isBlank()) "" else ": $message"
        }
        return "$category；$details"
    }

    private const val MAX_CAUSES = 4
    private const val MAX_MESSAGE_LENGTH = 240
}
