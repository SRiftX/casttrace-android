package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup

/** ShowRoom has a documented JSON room and streaming URL pair. */
class ShowroomResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "showroom"
    override fun supports(url: String) = url.hasHost("showroom-live.com")

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url)
        return LiveRoomInfo(data.url, "ShowRoom", data.anchor, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?): List<StreamSource> {
        val data = load(room.normalizedUrl)
        // Some broadcasts advertise concrete low variants that reject requests.
        // Keep the adaptive playlist available after the requested quality.
        return (data.sources.selectQuality(room.quality) + data.sources.filter { it.url in data.masterUrls } +
            data.sources.selectQuality("原画"))
            .distinctBy { it.url }
    }

    private suspend fun load(input: String): ShowroomData {
        require(supports(input)) { "不是 ShowRoom 直播间地址" }
        val roomId = Regex("room_id=(\\d+)").find(input)?.groupValues?.get(1)
            ?: input.substringBefore('?').trimEnd('/').substringAfterLast('/').toLongOrNull()?.toString()
            ?: Jsoup.parse(client.getText(input).body).selectFirst("a[href*=/room/profile?room_id=]")
                ?.attr("href")?.let { Regex("room_id=(\\d+)").find(it)?.groupValues?.get(1) }
            ?: error("ShowRoom 地址缺少 room_id")
        val headers = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://www.showroom-live.com/")
        val info = JSONObject(client.getText("https://www.showroom-live.com/api/live/live_info?room_id=$roomId", headers).body)
        val anchor = info.optString("room_name").ifBlank { "ShowRoom $roomId" }
        val title = info.optString("room_name").takeIf(String::isNotBlank)
        if (info.optInt("live_status") != 2) return ShowroomData(input, anchor, title, emptyList())
        val streaming = JSONObject(client.getText("https://www.showroom-live.com/api/live/streaming_url?room_id=$roomId&abr_available=1", headers).body)
        val sources = (0 until (streaming.optJSONArray("streaming_url_list")?.length() ?: 0))
            .mapNotNull { streaming.optJSONArray("streaming_url_list")?.optJSONObject(it) }
            .filter { it.optString("type") in setOf("hls_all", "hls") }
            .mapNotNull { item ->
                val quality = when (item.optInt("quality")) {
                    1000 -> "原画"
                    200 -> "高清"
                    100 -> "流畅"
                    else -> "原画"
                }
                item.optString("url").takeIf { it.startsWith("https://") }?.let {
                    StreamSource(it, StreamType.HLS, quality, requiresFfmpeg = true, headers = headers)
                }
            }.distinctBy { it.url }
        val masterUrls = (0 until (streaming.optJSONArray("streaming_url_list")?.length() ?: 0))
            .mapNotNull { streaming.optJSONArray("streaming_url_list")?.optJSONObject(it) }
            .filter { it.optString("type") == "hls_all" }.map { it.optString("url") }.toSet()
        return ShowroomData(input, anchor, title, sources, masterUrls)
    }

    private data class ShowroomData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>,
        val masterUrls: Set<String> = emptySet())
}
