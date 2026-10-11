package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class LangLiveResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "langlive"
    override fun supports(url: String) = url.hasHost("lang.live")
    override suspend fun resolveRoom(url: String): LiveRoomInfo { val d = load(url); return LiveRoomInfo(url, platformId, d.anchor, d.title) }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.UNKNOWN else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl).sources
    private suspend fun load(url: String): Data {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "浪Live 地址缺少有效房间号" }
        val api = "https://api.lang.live/langweb/v1/room/liveinfo".toHttpUrl().newBuilder()
            .addQueryParameter("room_id", id).build().toString()
        val root = JSONObject(client.getText(api, HEADERS).body)
        val info = root.optJSONObject("data")?.optJSONObject("live_info") ?: error("浪Live 接口没有返回房间信息")
        val live = info.optInt("live_status") == 1
        val sources = if (!live) emptyList() else listOfNotNull(
            info.optString("liveurl_hls").takeIf { it.startsWith("https://") }?.let { StreamSource(it, StreamType.HLS, "原画", headers = HEADERS, requiresFfmpeg = true) },
            info.optString("liveurl").takeIf { it.startsWith("https://") && it.contains(".flv", true) }?.let { StreamSource(it, StreamType.FLV, "原画", headers = HEADERS) },
        )
        return Data(info.optString("nickname", platformId), null, sources)
    }
    private data class Data(val anchor: String, val title: String?, val sources: List<StreamSource>)
    companion object { private val HEADERS = mapOf("Origin" to "https://www.lang.live", "Referer" to "https://www.lang.live/", "User-Agent" to DESKTOP_USER_AGENT) }
}
