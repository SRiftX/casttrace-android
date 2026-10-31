package com.streamcap.mobile.platform.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.jsoup.Jsoup

/** Resolves a web share page to a supported room URL without loading media. */
class ShareLinkDiscovery(
    private val client: OkHttpClient,
    private val registry: ResolverRegistry,
) {
    suspend fun resolve(text: String, inspectSupported: Boolean = false): String = withContext(Dispatchers.IO) {
        val input = RoomUrlInput.normalize(text)
        val url = input.toHttpUrlOrNull() ?: throw IllegalArgumentException("没有找到有效的 HTTP 链接")
        if (!inspectSupported && registry.supports(input)) return@withContext input
        requireSafeShareUrl(url)
        val redirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(12, TimeUnit.SECONDS).build()
        var current = url
        val visited = mutableSetOf<String>()
        repeat(5) {
            check(visited.add(current.toString())) { "分享链接发生循环跳转" }
            val response = redirectClient.newCall(Request.Builder().url(current)
                .header("User-Agent", USER_AGENT).build()).execute()
            response.use {
                if (it.code in 300..399) {
                    val next = it.header("Location")?.let(current::resolve)
                        ?: error("分享链接缺少跳转地址")
                    requireSafeShareUrl(next)
                    if (next.toString() != input && registry.supports(next.toString())) return@withContext next.toString()
                    current = next
                    return@repeat
                }
                check(it.isSuccessful) { "分享链接打不开：HTTP ${it.code}" }
                val source = it.body?.source() ?: error("分享页没有内容")
                val body = source.readUtf8(minOf(128_000L, source.request(128_001L).let { _ -> source.buffer.size }))
                val doc = Jsoup.parse(body, current.toString())
                val preferred = doc.select("meta[property=og:url], link[rel=canonical], meta[http-equiv=refresh]")
                    .mapNotNull { element ->
                        when (element.tagName()) {
                            "link" -> element.attr("abs:href")
                            else -> element.attr("content").substringAfter("url=", element.attr("content"))
                        }.takeIf(String::isNotBlank)
                    }
                val links = doc.select("a[href]").map { it.attr("abs:href") }
                val embedded = Regex("https?://[^\\s\\\"'<>\\\\]+", RegexOption.IGNORE_CASE)
                    .findAll(body.replace("\\/", "/")).map { it.value.replace("&amp;", "&") }.toList()
                    .map { it.replace("&amp;", "&") }
                for (candidate in preferred + links + embedded) {
                    val address = candidate.toHttpUrlOrNull() ?: continue
                    if (address.toString() != input && registry.supports(address.toString())) return@withContext address.toString()
                }
                error("分享页没有提供可识别的直播间地址；请在浏览器打开后复制直播间网址")
            }
        }
        error("分享链接跳转次数过多，请复制最终直播间网址")
    }

    private fun requireSafeShareUrl(url: HttpUrl) {
        require(url.scheme == "https") { "分享链接须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "分享链接格式无效" }
        val host = url.host.lowercase()
        require(host != "localhost" && !host.endsWith(".local") && !host.endsWith(".internal") &&
            !host.matches(Regex("[0-9.]+")) && !host.contains(':')) { "无法检测本地网络链接" }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
