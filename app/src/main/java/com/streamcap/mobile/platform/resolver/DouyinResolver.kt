package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import okhttp3.OkHttpClient

/**
 * 抖音网页会把当前可播放地址写入首屏数据。直接读取这份数据不依赖桌面端、远程服务或 JS 引擎，
 * 同时保留用户 Cookie，以便在抖音触发游客风控时使用用户自己的登录态。
 */
class DouyinResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "douyin"
    override fun supports(url: String) = url.contains("douyin.com", true)

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url, null)
        return LiveRoomInfo(data.url, "抖音直播", data.anchor, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isNotEmpty()) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)

    private suspend fun load(input: String, credential: PlatformCredential?): DouyinData {
        val headers = HEADERS + credential?.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
        val response = client.getText(input, headers)
        val normalized = response.body
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u003F", "?")
            .replace("\\u003D", "=")
            .replace("\\u003A", ":")
            .replace("\\u002E", ".")
            .replace("\\u0025", "%")
            .replace("\\u005F", "_")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val urls = URL_PATTERN.findAll(normalized).map { match ->
            match.value.trimEnd('\\', '"', '\'', ',', '}').replace("\\u0026", "&")
        }.filter { it.contains(".flv", true) || it.contains(".m3u8", true) }
            .distinct()
            .map { url -> StreamSource(url, streamTypeOf(url), qualityFromUrl(url), headers = headers) }
            .toList()
        val nickname = NICKNAME_PATTERN.find(normalized)?.groupValues?.get(1)
            ?.replace("\\u002F", "/")?.takeIf(String::isNotBlank)
            ?: "抖音主播 ${response.finalUrl.substringBefore('?').substringAfterLast('/')}"
        val title = TITLE_PATTERN.find(normalized)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        if (urls.isEmpty() && normalized.contains("verify-center", true)) {
            throw IllegalStateException("抖音触发了访问验证，请稍后重试或更换网络")
        }
        return DouyinData(response.finalUrl.substringBefore('?'), nickname, title, urls)
    }

    private fun qualityFromUrl(url: String): String = when {
        url.contains("origin", true) || url.contains("uhd", true) -> "原画"
        url.contains("hd", true) -> "高清"
        url.contains("sd", true) -> "标清"
        else -> "可用线路"
    }

    private data class DouyinData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)

    companion object {
        private val URL_PATTERN = Regex("https?://[^\\s\\\"'<>]+?(?:\\.flv|\\.m3u8)[^\\s\\\"'<>]*", RegexOption.IGNORE_CASE)
        private val NICKNAME_PATTERN = Regex("\\\"nickname\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        private val TITLE_PATTERN = Regex("\\\"title\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        private val HEADERS = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://live.douyin.com/",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }
}
