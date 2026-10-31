package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup

class DouyuResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "douyu"
    override fun supports(url: String) = url.hasHost("douyu.com")

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val room = roomData(url)
        return LiveRoomInfo(room.url, "斗鱼直播", room.anchor, room.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (roomData(room.normalizedUrl).live) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?): List<StreamSource> {
        val info = roomData(room.normalizedUrl)
        if (!info.live) return emptyList()
        val rate = when (room.quality.qualityCode()) {
            "UHD" -> "3"; "HD" -> "2"; "SD", "LD" -> "1"; else -> "0"
        }
        val whiteRoot = JSONObject(client.getText("https://www.douyu.com/wgapi/livenc/liveweb/websec/getEncryption?did=$DID", HEADERS).body)
        check(whiteRoot.optInt("error") == 0) { "斗鱼获取录制密钥失败" }
        val white = whiteRoot.getJSONObject("data")
        val timestamp = System.currentTimeMillis() / 1_000
        var secret = white.getString("rand_str")
        val key = white.getString("key")
        repeat(white.getInt("enc_time")) { secret = (secret + key).md5() }
        val isSpecial = white.optBoolean("is_special") || white.optInt("is_special") == 1
        val salt = if (isSpecial) "" else info.id + timestamp
        val auth = (secret + key + salt).md5()
        val baseFields = mapOf(
            "rate" to rate, "ver" to "219032101", "iar" to "0", "ive" to "0",
            "rid" to info.id, "hevc" to "0", "fa" to "0", "sov" to "0",
            "enc_data" to white.getString("enc_data"), "tt" to timestamp.toString(),
            "did" to DID, "auth" to auth,
        )
        val first = play(info.id, baseFields)
        val sources = mutableListOf<StreamSource>()
        fun addSource(root: JSONObject) {
            if (root.optInt("error") != 0) return
            val data = root.optJSONObject("data") ?: return
            val url = "${data.optString("rtmp_url")}/${data.optString("rtmp_live")}"
            if (url.startsWith("http")) sources += StreamSource(secureStreamUrl(url), StreamType.FLV, room.quality, headers = HEADERS)
        }
        addSource(first)
        val playData = first.optJSONObject("data")
        val currentCdn = playData?.optString("rtmp_cdn")
        playData?.optJSONArray("cdnsWithName")?.let { list ->
            for (index in 0 until list.length()) {
                val cdn = list.optJSONObject(index)?.optString("cdn").orEmpty()
                if (cdn.isNotBlank() && cdn != currentCdn) addSource(play(info.id, baseFields + ("cdn" to cdn)))
            }
        }
        return sources.distinctBy { it.url }
    }

    private suspend fun play(roomId: String, fields: Map<String, String>): JSONObject = JSONObject(
        client.postForm(
            "https://playweb.douyucdn.cn/lapi/live/getH5PlayV1/$roomId",
            fields,
            HEADERS + mapOf("Origin" to "https://www.douyu.com", "Content-Type" to "application/x-www-form-urlencoded"),
        ),
    )

    private suspend fun roomData(url: String): DouyuRoom {
        var id = Regex("douyu\\.com/(?:topic/)?(\\d+)").find(url)?.groupValues?.get(1)
            ?: Regex("[?&]rid=(\\d+)").find(url)?.groupValues?.get(1)
        if (id == null) {
            val path = url.substringAfter("douyu.com/").substringBefore('?').substringBefore('/')
            require(path.isNotBlank()) { "斗鱼直播间地址缺少房间号" }
            val html = client.getText("https://m.douyu.com/$path", HEADERS).body
            id = Regex("\\\"rid\\\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)
                ?: error("无法取得斗鱼房间号")
        }
        val apiBody = runCatching { client.getText("https://www.douyu.com/betard/$id", HEADERS).body }.getOrNull()
        val room = runCatching { JSONObject(apiBody ?: "").optJSONObject("room") }.getOrNull()
            ?: return mobileRoomData(id)
        val title = room.optString("room_name").replace("&nbsp;", " ").trim()
        return DouyuRoom(
            id = room.optString("room_id", id),
            url = "https://www.douyu.com/$id",
            anchor = room.optString("nickname").ifBlank { "斗鱼主播 $id" },
            title = if (room.optInt("videoLoop") == 1) "【轮播】$title" else title,
            live = room.optInt("show_status") == 1,
        )
    }

    private suspend fun mobileRoomData(id: String): DouyuRoom {
        val html = client.getText("https://m.douyu.com/$id", HEADERS).body
        val data = Jsoup.parse(html).selectFirst("script#vike_pageContext")?.data()
            ?: error("斗鱼没有返回房间信息")
        val room = JSONObject(data).getJSONObject("pageProps").getJSONObject("room")
            .getJSONObject("roomInfo").getJSONObject("roomInfo")
        val resolvedId = room.optString("rid").takeIf { it.matches(Regex("\\d+")) }
            ?: error("斗鱼分享页没有房间号")
        return DouyuRoom(
            id = resolvedId,
            url = "https://www.douyu.com/$id",
            anchor = room.optString("nickname").ifBlank { "斗鱼主播 $id" },
            title = room.optString("roomName").ifBlank { "斗鱼直播间 $id" },
            live = room.optInt("isLive") == 1,
        )
    }

    private data class DouyuRoom(val id: String, val url: String, val anchor: String, val title: String, val live: Boolean)

    companion object {
        private const val DID = "10000000000000000000000000001501"
        private val HEADERS = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://www.douyu.com/")
    }
}
