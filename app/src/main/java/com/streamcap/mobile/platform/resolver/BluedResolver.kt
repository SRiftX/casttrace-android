package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLDecoder

class BluedResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "blued"
    override fun supports(url: String) = url.hasHost("blued.cn")
    override suspend fun resolveRoom(url: String): LiveRoomInfo { val d = load(url); return LiveRoomInfo(url, "Blued", d.anchor, null) }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.UNKNOWN else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl).sources
    private suspend fun load(url: String): Data {
        val body = client.getText(url, mapOf("User-Agent" to DESKTOP_USER_AGENT)).body
        val encoded = Regex("decodeURIComponent\\(\\s*\\\"([^\\\"]*)\\\"\\s*\\)", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: error("Blued 页面没有直播数据")
        val root = JSONObject(URLDecoder.decode(encoded, Charsets.UTF_8.name()))
        val user = root.optJSONObject("userInfo") ?: error("Blued 用户数据缺失")
        val anchor = user.optString("name", "Blued")
        if (!user.optBoolean("onLive")) return Data(anchor, emptyList())
        val stream = root.optJSONObject("liveInfo")?.optString("liveUrl").orEmpty()
        val source = stream.takeIf { it.startsWith("https://") }?.let {
            StreamSource(it, StreamType.HLS, "原画", headers = mapOf(
                "User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://app.blued.cn/",
                "Origin" to "https://app.blued.cn"), requiresFfmpeg = true)
        }
        return Data(anchor, listOfNotNull(source))
    }
    private data class Data(val anchor: String, val sources: List<StreamSource>)
}
