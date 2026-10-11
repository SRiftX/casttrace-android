package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup

class NeteaseCcResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "netease"
    override fun supports(url: String) = url.hasHost("cc.163.com")
    override suspend fun resolveRoom(url: String): LiveRoomInfo { val d = load(url); return LiveRoomInfo(url, "网易CC直播", d.anchor, d.title) }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl).sources.selectQuality(room.quality)
    private suspend fun load(url: String): Data {
        val page = Jsoup.parse(client.getText(url.trimEnd('/') + "/").body)
        val payload = page.selectFirst("script#__NEXT_DATA__")?.data()?.takeIf(String::isNotBlank)
            ?: error("网易CC 页面未返回直播数据")
        val room = JSONObject(payload).optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("roomInfoInitData") ?: error("网易CC 页面缺少房间信息")
        val live = room.optJSONObject("live") ?: error("网易CC 页面缺少直播状态")
        val anchor = live.optString("nickname").ifBlank { room.optString("nickname", "网易CC") }
        if (live.optInt("status") != 1) return Data(anchor, null, emptyList())
        val hls = live.optString("sharefile").takeIf { it.startsWith("https://") }
            ?.let { StreamSource(it, StreamType.HLS, "原画", requiresFfmpeg = true) }
        val resolution = live.optJSONObject("quickplay")?.optJSONObject("resolution")
        val qualities = listOf("blueray" to "原画", "ultra" to "超清", "high" to "高清", "standard" to "标清")
        val flv = qualities.flatMap { (key, name) ->
            val cdn = resolution?.optJSONObject(key)?.optJSONObject("cdn")
            cdn?.keys()?.asSequence()?.mapNotNull { host ->
                cdn.optString(host).takeIf { it.startsWith("https://") }
                    ?.let { StreamSource(it, StreamType.FLV, name) }
            }?.toList().orEmpty()
        }
        return Data(anchor, live.optString("title"), listOfNotNull(hls) + flv)
    }
    private data class Data(val anchor: String, val title: String?, val sources: List<StreamSource>)
}
