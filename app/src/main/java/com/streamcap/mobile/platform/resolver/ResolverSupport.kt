package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal data class HttpText(val body: String, val finalUrl: String)

internal suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): HttpText = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(url)
    headers.forEach(builder::header)
    newCall(builder.build()).execute().use { response ->
        check(response.isSuccessful) { "请求失败：HTTP ${response.code}" }
        HttpText(
            body = checkNotNull(response.body).string(),
            finalUrl = response.request.url.toString(),
        )
    }
}

internal suspend fun OkHttpClient.postForm(
    url: String,
    fields: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
): String = withContext(Dispatchers.IO) {
    val form = FormBody.Builder().apply { fields.forEach(::add) }.build()
    val builder = Request.Builder().url(url).post(form)
    headers.forEach(builder::header)
    newCall(builder.build()).execute().use { response ->
        check(response.isSuccessful) { "请求失败：HTTP ${response.code}" }
        checkNotNull(response.body).string()
    }
}

internal fun httpUrl(base: String, parameters: Map<String, String>): String =
    base.toHttpUrl().newBuilder().apply {
        parameters.forEach(::addQueryParameter)
    }.build().toString()

internal fun String.md5(): String = MessageDigest.getInstance("MD5")
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun String.qualityCode(): String = when (lowercase()) {
    "原画", "od", "4k", "2160p" -> "OD"
    "蓝光", "bd", "1080p" -> "BD"
    "超清", "uhd", "720p" -> "UHD"
    "高清", "hd", "480p" -> "HD"
    "标清", "sd", "360p" -> "SD"
    "流畅", "ld", "音频优先" -> "LD"
    else -> "OD"
}

internal fun List<StreamSource>.selectQuality(quality: String): List<StreamSource> {
    if (isEmpty()) return this
    val rank = mapOf("OD" to 0, "BD" to 1, "UHD" to 2, "HD" to 3, "SD" to 4, "LD" to 5)
    val target = rank[quality.qualityCode()] ?: 0
    val grouped = groupBy { it.type }
    return grouped.values.mapNotNull { sources ->
        sources.sortedByDescending { it.bitrate ?: Long.MAX_VALUE }
            .getOrElse(target) { sources.last() }
    }
}

internal fun streamTypeOf(url: String): StreamType = when {
    url.substringBefore('?').endsWith(".m3u8", true) -> StreamType.HLS
    url.substringBefore('?').endsWith(".flv", true) -> StreamType.FLV
    else -> StreamType.UNKNOWN
}

internal fun JSONObject.findObjects(key: String, output: MutableList<JSONObject> = mutableListOf()): List<JSONObject> {
    if (has(key)) output += this
    keys().forEach { name ->
        when (val child = opt(name)) {
            is JSONObject -> child.findObjects(key, output)
            is JSONArray -> for (index in 0 until child.length()) {
                (child.opt(index) as? JSONObject)?.findObjects(key, output)
            }
        }
    }
    return output
}
