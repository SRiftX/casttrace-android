package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup

class TwitCastingResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "twitcasting"
    override fun supports(url: String) = url.hasHost("twitcasting.tv")

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val room = load(url, false)
        return LiveRoomInfo(url, "TwitCasting", room.anchor, room.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, false).live) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, true).sources.selectQuality(room.quality)

    private suspend fun load(url: String, includeStreams: Boolean): RoomData {
        require(supports(url)) { "不是 TwitCasting 频道地址" }
        val channel = java.net.URI(url).path.trim('/').substringBefore('/')
        require(channel.matches(Regex("[A-Za-z0-9_:-]{1,100}"))) { "TwitCasting 地址缺少频道名" }
        val headers = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://twitcasting.tv/")
        val page = Jsoup.parse(client.getText(url, headers).body)
        val anchor = page.selectFirst("meta[property=og:site_name]")?.attr("content")
            ?.takeIf(String::isNotBlank) ?: channel
        val title = page.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf(String::isNotBlank) ?: page.title().takeIf(String::isNotBlank)
        val live = page.selectFirst("[data-is-onlive]")?.attr("data-is-onlive") == "true"
        if (!live || !includeStreams) return RoomData(anchor, title, live, emptyList())
        val data = JSONObject(client.getText(
            "https://twitcasting.tv/streamserver.php?target=$channel&mode=client&player=pc_web", headers,
        ).body)
        val streams = data.optJSONObject("tc-hls")?.optJSONObject("streams")
            ?: return RoomData(anchor, title, live, emptyList())
        val sources = listOf("high" to "原画", "medium" to "高清", "low" to "流畅")
            .mapNotNull { (key, quality) -> streams.optString(key).takeIf { it.startsWith("https://") }
                ?.let { StreamSource(it, StreamType.HLS, quality, headers = headers, requiresFfmpeg = true) } }
        return RoomData(anchor, title, live, sources)
    }

    private data class RoomData(val anchor: String, val title: String?, val live: Boolean, val sources: List<StreamSource>)
}
