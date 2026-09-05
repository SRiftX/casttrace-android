package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import okhttp3.OkHttpClient
import org.json.JSONObject

class BilibiliResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "bilibili"
    override fun supports(url: String) = url.contains("live.bilibili.com", true)

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val room = roomData(url)
        return LiveRoomInfo(room.url, "B站直播", room.anchor, room.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (roomData(room.normalizedUrl).live) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?): List<StreamSource> {
        val info = roomData(room.normalizedUrl)
        if (!info.live) return emptyList()
        val qn = when (room.quality.qualityCode()) {
            "BD" -> "400"; "UHD" -> "250"; "HD" -> "150"; "SD", "LD" -> "80"; else -> "10000"
        }
        val cookie = credential?.cookie.orEmpty()
        val headers = HEADERS + if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        val sources = mutableListOf<StreamSource>()
        runCatching {
            JSONObject(client.getText(httpUrl(PLAY_URL, mapOf("cid" to info.id, "qn" to qn, "platform" to "web")), headers).body)
        }.getOrNull()?.optJSONObject("data")?.optJSONArray("durl")?.let { list ->
            for (index in 0 until list.length()) {
                val url = list.optJSONObject(index)?.optString("url").orEmpty()
                if (url.startsWith("http")) sources += StreamSource(url, streamTypeOf(url), room.quality, headers = headers)
            }
        }
        val modern = JSONObject(
            client.getText(
                httpUrl(
                    PLAY_INFO_URL,
                    mapOf(
                        "room_id" to info.id, "protocol" to "0,1", "format" to "0,1,2",
                        "codec" to "0,1", "qn" to qn, "platform" to "web", "ptype" to "8",
                    ),
                ),
                headers,
            ).body,
        )
        val playUrl = modern.optJSONObject("data")?.optJSONObject("playurl_info")
            ?.optJSONObject("playurl")
        playUrl?.findObjects("url_info")?.forEach { codec ->
            val base = codec.optString("base_url")
            val infos = codec.optJSONArray("url_info") ?: return@forEach
            for (index in 0 until infos.length()) {
                val item = infos.optJSONObject(index) ?: continue
                val streamUrl = item.optString("host") + base + item.optString("extra")
                if (streamUrl.startsWith("http")) {
                    sources += StreamSource(streamUrl, streamTypeOf(streamUrl), room.quality, headers = headers)
                }
            }
        }
        return sources.distinctBy { it.url }
    }

    private suspend fun roomData(url: String): BiliRoom {
        val inputId = Regex("live\\.bilibili\\.com/(\\d+)").find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("B站直播间地址缺少数字房间号")
        val init = JSONObject(client.getText("https://api.live.bilibili.com/room/v1/Room/room_init?id=$inputId", HEADERS).body)
            .optJSONObject("data") ?: error("B站没有返回房间信息")
        val id = init.optLong("room_id", inputId.toLong()).toString()
        val uid = init.optLong("uid")
        val live = init.optInt("live_status") == 1
        val master = JSONObject(client.getText("https://api.live.bilibili.com/live_user/v1/Master/info?uid=$uid", HEADERS).body)
        val anchor = master.optJSONObject("data")?.optJSONObject("info")?.optString("uname")
            .orEmpty().ifBlank { "B站主播 $id" }
        val h5 = JSONObject(client.getText("https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=$id", HEADERS).body)
        val title = h5.optJSONObject("data")?.optJSONObject("room_info")?.optString("title")?.takeIf(String::isNotBlank)
        return BiliRoom(id, "https://live.bilibili.com/$id", anchor, title, live)
    }

    private data class BiliRoom(val id: String, val url: String, val anchor: String, val title: String?, val live: Boolean)

    companion object {
        private const val PLAY_URL = "https://api.live.bilibili.com/room/v1/Room/playUrl"
        private const val PLAY_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
        private val HEADERS = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Origin" to "https://live.bilibili.com",
            "Referer" to "https://live.bilibili.com/",
        )
    }
}
