package com.streamcap.mobile.platform.resolver

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object RoomUrlInput {
    private val urls = Regex("https?://[^\\s<>\"'\\[\\]\\\\]+", RegexOption.IGNORE_CASE)

    fun extract(text: String): String? = urls.find(text)?.value
        ?.trimEnd('。', '，', ',', '.', ')', '）', ']', '】', '！', '!', '；', ';')

    fun normalize(text: String): String = extract(text) ?: text.trim()
}

internal fun String.hasHost(vararg domains: String): Boolean {
    val host = toHttpUrlOrNull()?.host ?: return false
    return domains.any { host == it || host.endsWith(".$it") }
}
