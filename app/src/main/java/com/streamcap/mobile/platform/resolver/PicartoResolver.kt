package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

class PicartoResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "picarto"
    override fun supports(url: String) = url.hasHost("picarto.tv")
    override suspend fun resolveRoom(url: String): LiveRoomInfo { val data = load(url); return LiveRoomInfo(data.url, "Picarto", data.anchor, data.title) }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl).sources
    private suspend fun load(input: String): PicartoData {
        require(supports(input)) { "不是 Picarto 频道地址" }
        val channel = input.substringBefore('?').trimEnd('/').substringAfterLast('/').also {
            require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Picarto 地址缺少有效频道名" }
        }
        val data = JSONObject(client.getText("https://ptvintern.picarto.tv/api/channel/detail/$channel", HEADERS).body)
        val info = data.optJSONObject("channel") ?: error("Picarto 接口没有返回频道信息")
        val anchor = info.optString("name").ifBlank { channel }
        if (!info.optBoolean("online")) return PicartoData(input, anchor, info.optString("title"), emptyList())
        val edge = data.optJSONObject("getLoadBalancerUrl")?.optString("url")?.toHttpUrlOrNull()
            ?.takeIf { it.host.endsWith(".picarto.tv") }
            ?.newBuilder()?.scheme("https")?.encodedPath("/")?.query(null)?.fragment(null)?.build()
        val path = "stream/hls/golive+$anchor/index.m3u8"
        val urls = listOfNotNull(edge?.resolve(path)?.toString(), "https://1-edge1-us-newyork.picarto.tv/$path").distinct()
        return PicartoData(input, anchor, info.optString("title").takeIf(String::isNotBlank), urls.map {
            StreamSource(it, StreamType.HLS, "原画", requiresFfmpeg = true, headers = HEADERS)
        })
    }
    private data class PicartoData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)
    companion object { private val HEADERS = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Accept" to "application/json") }
}
