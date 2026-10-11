package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal data class HttpText(val body: String, val finalUrl: String)

/** Stop at the room URL instead of requiring the final room HTML to load. */
internal suspend fun OkHttpClient.expandRoomShareUrl(
    input: String,
    shortHost: String,
    destinationHosts: List<String>,
    headers: Map<String, String>,
): String = withContext(Dispatchers.IO) {
    var current = input.toHttpUrl()
    if (current.host != shortHost) return@withContext input
    val redirectClient = newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()
    repeat(5) {
        val request = Request.Builder().url(current).apply { headers.forEach(::header) }.build()
        val next = redirectClient.newCall(request).execute().use { response ->
            check(response.code in listOf(301, 302, 303, 307, 308)) { "分享短链未返回直播间地址，请确认链接仍然有效" }
            response.header("Location")?.let(current::resolve) ?: error("分享短链缺少跳转地址")
        }
        require(next.scheme == "https") { "分享链接跳转地址必须使用 HTTPS" }
        if (next.host != shortHost) {
            require(next.toString().hasHost(*destinationHosts.toTypedArray())) { "分享链接未指向支持的直播平台" }
            return@withContext next.toString()
        }
        current = next
    }
    error("分享链接跳转次数过多，请使用完整直播间地址")
}

internal suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): HttpText = withContext(Dispatchers.IO) {
    require(url.toHttpUrlOrNull()?.scheme == "https") { "平台解析请求必须使用 HTTPS" }
    val builder = Request.Builder().url(url)
    headers.forEach(builder::header)
    newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { response ->
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
    require(url.toHttpUrlOrNull()?.scheme == "https") { "平台解析请求必须使用 HTTPS" }
    val form = FormBody.Builder().apply { fields.forEach(::add) }.build()
    val builder = Request.Builder().url(url).post(form)
    headers.forEach(builder::header)
    newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { response ->
        check(response.isSuccessful) { "请求失败：HTTP ${response.code}" }
        checkNotNull(response.body).string()
    }
}

internal suspend fun OkHttpClient.postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
): String = withContext(Dispatchers.IO) {
    require(url.toHttpUrlOrNull()?.scheme == "https") { "平台解析请求必须使用 HTTPS" }
    val builder = Request.Builder().url(url).post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
    headers.forEach(builder::header)
    newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(builder.build()).execute().use { response ->
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
    return grouped.values.flatMap { sources ->
        fun tier(source: StreamSource): Int? = when (source.quality.lowercase()) {
            "原画", "origin", "od", "4k", "2160p" -> 0
            "蓝光", "bd", "1080p" -> 1
            "超清", "uhd", "720p" -> 2
            "高清", "hd", "480p" -> 3
            "标清", "sd", "360p" -> 4
            "流畅", "ld", "音频优先" -> 5
            else -> null
        }
        val known = sources.filter { tier(it) != null }
        if (known.isNotEmpty()) {
            val selected = known.map { tier(it)!! }.distinct()
                .let { tiers -> tiers.filter { it >= target }.minOrNull() ?: tiers.max() }
            known.filter { tier(it) == selected }.sortedByDescending { it.bitrate ?: 0L }
        } else {
            // Unknown labels are not original quality. Use distinct known bitrates
            // as the fallback ladder; duplicate CDN routes do not consume a tier.
            val rates = sources.mapNotNull { it.bitrate }.distinct().sortedDescending()
            val selected = rates.getOrNull(target) ?: rates.lastOrNull()
            if (selected == null) sources else sources.filter { it.bitrate == selected }
        }
    }
}

internal fun streamTypeOf(url: String): StreamType = when {
    url.substringBefore('?').endsWith(".m3u8", true) -> StreamType.HLS
    url.substringBefore('?').endsWith(".flv", true) -> StreamType.FLV
    else -> StreamType.UNKNOWN
}

/** Platform APIs occasionally return an http CDN URL even though the same CDN serves TLS. */
internal fun secureStreamUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    // Keep local fixture servers usable in JVM/instrumentation tests. Production
    // platform/CDN URLs are remote and are always upgraded to TLS.
    val localHost = parsed.host == "localhost" || parsed.host == "127.0.0.1" ||
        parsed.host == "10.0.2.2" || parsed.host == "kubernetes.docker.internal"
    return if (parsed.scheme == "http" && !localHost) {
        parsed.newBuilder().scheme("https").build().toString()
    } else {
        url
    }
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
