package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject

class ChzzkResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "chzzk"
    override fun supports(url: String) = url.hasHost("chzzk.naver.com")
    override val supportsCredentials = true
    override suspend fun resolveRoom(url: String): LiveRoomInfo = resolveRoom(url, null)
    override suspend fun resolveRoom(url: String, credential: PlatformCredential?): LiveRoomInfo {
        val data = load(url, credential)
        return LiveRoomInfo(data.url, "CHZZK", data.anchor, data.title)
    }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl, credential).sources

    private suspend fun load(input: String, credential: PlatformCredential?): ChzzkData {
        require(supports(input)) { "不是 CHZZK 频道地址" }
        val channelId = input.substringBefore('?').trimEnd('/').substringAfterLast('/')
        require(channelId.matches(Regex("[0-9a-fA-F-]{20,}"))) { "CHZZK 地址缺少频道 ID" }
        val headers = HEADERS + credential?.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
        val root = JSONObject(client.getText("https://api.chzzk.naver.com/service/v3/channels/$channelId/live-detail", headers).body)
        val content = root.optJSONObject("content") ?: error("CHZZK 接口没有返回频道信息")
        val anchor = content.optJSONObject("channel")?.optString("channelName").orEmpty().ifBlank { "CHZZK $channelId" }
        if (content.optString("status") != "OPEN") return ChzzkData(input, anchor, null, emptyList())
        val playback = content.optString("livePlaybackJson").takeIf(String::isNotBlank)?.let(::JSONObject)
            ?: error("CHZZK 没有返回播放信息")
        val media = playback.optJSONArray("media") ?: return ChzzkData(input, anchor, null, emptyList())
        val sources = (0 until media.length()).mapNotNull { index ->
            media.optJSONObject(index)?.optString("path")?.takeIf { it.startsWith("https://") }
                ?.let { StreamSource(it, StreamType.HLS, "原画", headers = headers, requiresFfmpeg = true) }
        }.distinctBy { it.url }
        return ChzzkData(input, anchor, content.optString("liveTitle").takeIf(String::isNotBlank), sources)
    }
    private data class ChzzkData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)
    companion object { private val HEADERS = mapOf("Accept" to "application/json, text/plain, */*", "Origin" to "https://chzzk.naver.com", "Referer" to "https://chzzk.naver.com/", "User-Agent" to DESKTOP_USER_AGENT) }
}
