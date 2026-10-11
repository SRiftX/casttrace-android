package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class KugouResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "kugou"
    override fun supports(url: String) = url.hasHost("kugou.com")
    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url, false)
        return LiveRoomInfo(url, "酷狗直播", data.anchor, null)
    }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, true).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, true).sources

    private suspend fun load(url: String, includeSources: Boolean): Data {
        val parsed = url.toHttpUrl()
        val id = parsed.queryParameter("roomId") ?: parsed.pathSegments.lastOrNull()
        require(!id.isNullOrBlank() && id.all(Char::isDigit)) { "酷狗直播地址缺少数字房间号" }
        val root = JSONObject(client.getText(
            "https://service2.fanxing.kugou.com/roomcen/room/web/cdn/getEnterRoomInfo?roomId=$id",
        ).body).optJSONObject("data") ?: error("酷狗未返回房间数据")
        val anchor = root.optJSONObject("normalRoomInfo")?.optString("nickName").orEmpty()
        require(anchor.isNotBlank()) { "酷狗音乐频道不支持录制" }
        if (!includeSources || root.optInt("liveType", -1) == -1) return Data(anchor, emptyList())
        val api = "https://fx1.service.kugou.com/video/pc/live/pull/mutiline/streamaddr"
            .toHttpUrl().newBuilder()
            .addQueryParameter("std_rid", id).addQueryParameter("std_plat", "7")
            .addQueryParameter("std_kid", "0").addQueryParameter("streamType", "1-2-4-5-8")
            .addQueryParameter("ua", "fx-flash").addQueryParameter("targetLiveTypes", "1-5-6")
            .addQueryParameter("version", "1000").addQueryParameter("supportEncryptMode", "1")
            .addQueryParameter("appid", "1010")
            .addQueryParameter("_", System.currentTimeMillis().toString()).build().toString()
        val lines = JSONObject(client.getText(api).body).optJSONObject("data")?.optJSONArray("lines")
        val sources = (0 until (lines?.length() ?: 0)).flatMap { index ->
            val profiles = lines?.optJSONObject(index)?.optJSONArray("streamProfiles")
            (0 until (profiles?.length() ?: 0)).flatMap { profile ->
                val urls = profiles?.optJSONObject(profile)?.optJSONArray("httpsFlv")
                (0 until (urls?.length() ?: 0)).mapNotNull { urls?.optString(it)?.takeIf { address -> address.startsWith("https://") } }
            }
        }.distinct().map { StreamSource(it, StreamType.FLV, "原画") }
        return Data(anchor, sources)
    }
    private data class Data(val anchor: String, val sources: List<StreamSource>)
}
