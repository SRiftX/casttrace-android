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
    override fun supports(url: String) = url.hasHost("douyin.com", "webcast.amemv.com")

    override val supportsCredentials = true
    override suspend fun resolveRoom(url: String): LiveRoomInfo = resolveRoom(url, null)
    override suspend fun resolveRoom(url: String, credential: PlatformCredential?): LiveRoomInfo {
        val data = load(url, credential)
        return LiveRoomInfo(data.url, "抖音直播", data.anchor, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isNotEmpty()) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun checkRoom(room: LiveRoom, credential: PlatformCredential?): RoomCheckResult {
        val data = load(room.normalizedUrl, credential)
        return RoomCheckResult(
            if (data.sources.isNotEmpty()) LiveStatus.LIVE else LiveStatus.OFFLINE,
            LiveRoomInfo(data.url, "抖音直播", data.anchor, data.title),
        )
    }

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)

    private suspend fun load(input: String, credential: PlatformCredential?): DouyinData {
        val headers = HEADERS + credential?.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
        val expanded = client.expandRoomShareUrl(input, "v.douyin.com",
            listOf("douyin.com", "webcast.amemv.com"), headers + ("User-Agent" to MOBILE_USER_AGENT))
        val sharePage = expanded.hasHost("webcast.amemv.com")
        val pageHeaders = if (sharePage) headers + ("User-Agent" to MOBILE_USER_AGENT) else headers
        val response = client.getText(expanded, pageHeaders)
        val page = DouyinPageParser.parse(response.body)
        val normalized = page.sourceText
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
        val urls = page.sources.map { it.copy(headers = headers) }.ifEmpty { URL_PATTERN.findAll(normalized).map { match ->
            match.value.trimEnd('\\', '"', '\'', ',', '}').replace("\\u0026", "&")
        }.filter { it.contains(".flv", true) || it.contains(".m3u8", true) }
            .distinct()
            .map { url ->
                val secureUrl = secureStreamUrl(url)
                StreamSource(secureUrl, streamTypeOf(secureUrl), qualityFromUrl(secureUrl), headers = headers)
            }
            .toList() }
        val nickname = page.anchor
            ?: "抖音主播 ${response.finalUrl.substringBefore('?').substringAfterLast('/')}"
        val title = page.title
        if (urls.isEmpty() && normalized.contains("verify-center", true)) {
            throw IllegalStateException("抖音触发了访问验证，请稍后重试或更换网络")
        }
        if (urls.isEmpty() && page.roomStatus != 4) {
            throw IllegalStateException("抖音页面未返回可识别的直播线路，无法确认是否下播，请检查登录态或稍后重试")
        }
        val canonicalUrl = page.webRoomId?.takeIf { it.all(Char::isDigit) }
            ?.let { "https://live.douyin.com/$it" } ?: response.finalUrl.substringBefore('?')
        return DouyinData(canonicalUrl, nickname, title, if (page.roomStatus == 4) emptyList() else urls)
    }

    private fun qualityFromUrl(url: String): String = when {
        url.contains("origin", true) || url.contains("uhd", true) -> "原画"
        url.contains("hd", true) -> "高清"
        url.contains("sd", true) -> "标清"
        else -> "可用线路"
    }

    private data class DouyinData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)

    companion object {
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val URL_PATTERN = Regex("https?://[^\\s\\\"'<>]+?(?:\\.flv|\\.m3u8)[^\\s\\\"'<>]*", RegexOption.IGNORE_CASE)
        private val HEADERS = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://live.douyin.com/",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }
}
