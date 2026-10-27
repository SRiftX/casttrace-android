package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class AdditionalRoom(val anchor: String, val title: String?, val sources: List<StreamSource>)

private fun additionalHeaders(credential: PlatformCredential?, referer: String) = buildMap {
    put("User-Agent", DESKTOP_USER_AGENT)
    put("Referer", referer)
    credential?.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
}

private fun additionalSource(url: String, quality: String, headers: Map<String, String>): StreamSource? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https") return null
    val type = streamTypeOf(url)
    return StreamSource(url, type, quality, headers = headers, requiresFfmpeg = type == StreamType.HLS)
}

abstract class AdditionalResolver(protected val client: OkHttpClient) : PlatformResolver {
    override val supportsCredentials = true
    protected abstract suspend fun load(url: String, credential: PlatformCredential?): AdditionalRoom
    override suspend fun resolveRoom(url: String): LiveRoomInfo = resolveRoom(url, null)
    override suspend fun resolveRoom(url: String, credential: PlatformCredential?): LiveRoomInfo {
        val room = load(url, credential)
        return LiveRoomInfo(url, platformId, room.anchor, room.title)
    }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isNotEmpty()) LiveStatus.LIVE else LiveStatus.OFFLINE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)
}

class YyResolver(client: OkHttpClient) : AdditionalResolver(client) {
    override val platformId = "yy"
    override fun supports(url: String) = url.hasHost("yy.com")
    override suspend fun load(url: String, credential: PlatformCredential?): AdditionalRoom {
        val h = additionalHeaders(credential, "https://www.yy.com/")
        val page = client.getText(url, h).body
        val cid = Regex("sid\\s*:\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: error("YY 页面没有频道 ID")
        require(cid.matches(Regex("[A-Za-z0-9]{1,30}"))) { "YY 频道 ID 无效" }
        val anchor = Regex("nick\\s*:\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: "YY"
        val sequence = System.currentTimeMillis()
        val head = JSONObject().put("seq", sequence).put("appidstr", "0").put("bidstr", "121")
            .put("cidstr", cid).put("sidstr", cid).put("uid64", 0).put("client_type", 108)
            .put("client_ver", "5.17.0").put("stream_sys_ver", 1).put("app", "yylive_web")
            .put("playersdk_ver", "5.17.0").put("thundersdk_ver", "0").put("streamsdk_ver", "5.17.0")
        val body = JSONObject().put("head", head)
            .put("client_attribute", JSONObject().put("client", "web").put("model", "web0")
                .put("os", "chrome").put("osversion", "0").put("client_type", 8).put("h265", 0))
            .put("avp_parameter", JSONObject().put("version", 1).put("client_type", 8)
                .put("service_type", 0).put("send_time", sequence / 1000).put("line_seq", -1)
                .put("gear", 4).put("ssl", 1).put("stream_format", 0))
        val endpoint = httpUrl("https://stream-manager.yy.com/v3/channel/streams", mapOf(
            "uid" to "0", "cid" to cid, "sid" to cid, "appid" to "0",
            "sequence" to sequence.toString(), "encode" to "json"))
        val data = JSONObject(client.postJson(endpoint, body.toString(), h))
        val lines = data.optJSONObject("avp_info_res")?.optJSONObject("stream_line_addr")
        val sources = mutableListOf<StreamSource>()
        lines?.keys()?.forEach { line ->
            lines.optJSONObject(line)?.optJSONObject("cdn_info")?.optString("url")
                ?.let { additionalSource(it, "原画", h) }?.let(sources::add)
        }
        return AdditionalRoom(anchor, null, sources)
    }
}

class SixRoomResolver(client: OkHttpClient) : AdditionalResolver(client) {
    override val platformId = "sixroom"
    override fun supports(url: String) = url.hasHost("6.cn")
    override suspend fun load(url: String, credential: PlatformCredential?): AdditionalRoom {
        val roomId = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        require(roomId.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "六间房地址缺少房间 ID" }
        val h = additionalHeaders(credential, "https://ios.6.cn/")
        val page = client.getText("https://v.6.cn/$roomId", h).body
        val uid = Regex("rid\\s*:\\s*'([^']+)'").find(page)?.groupValues?.get(1)
            ?: error("六间房页面没有主播 ID")
        val root = JSONObject(client.postForm("https://v.6.cn/coop/mobile/index.php?padapi=coop-mobile-inroom.php",
            mapOf("av" to "3.1", "encpass" to "", "logiuid" to "", "project" to "v6iphone",
                "rate" to "1", "rid" to "", "ruid" to uid), h))
        val content = root.optJSONObject("content") ?: error("六间房房间数据不可用")
        val title = content.optJSONObject("liveinfo")?.optString("flvtitle").orEmpty()
        val media = title.takeIf(String::isNotBlank)?.let {
            require(it.matches(Regex("[A-Za-z0-9_-]{1,150}"))) { "六间房流标识无效" }
            additionalSource("https://wlive.6rooms.com/httpflv/$it.flv", "原画", h)
        }
        return AdditionalRoom(content.optJSONObject("roominfo")?.optString("alias").orEmpty().ifBlank { uid },
            null, listOfNotNull(media))
    }
}

class BaiduLiveResolver(client: OkHttpClient) : AdditionalResolver(client) {
    override val platformId = "baidu"
    override fun supports(url: String) = url.hasHost("live.baidu.com")
    override suspend fun load(url: String, credential: PlatformCredential?): AdditionalRoom {
        val id = url.toHttpUrlOrNull()?.queryParameter("room_id")
        require(!id.isNullOrBlank() && id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "百度直播地址缺少 room_id" }
        val h = additionalHeaders(credential, "https://live.baidu.com/")
        val data = JSONObject().put("data", JSONObject().put("room_id", id)
            .put("device_id", "h5-683e85bdf741bf2492586f7ca39bf465")
            .put("source_type", 0).put("osname", "baiduboxapp"))
            .put("replay_slice", 0).put("nid", "").put("schemeParams", JSONObject().put("src_pre", "pc"))
        val endpoint = httpUrl("https://mbd.baidu.com/searchbox", mapOf(
            "cmd" to "371", "action" to "star", "service" to "bdbox", "osname" to "baiduboxapp",
            "data" to data.toString(), "ua" to "360_740_ANDROID_0", "uid" to "h5-683e85bdf741bf2492586f7ca39bf465",
            "_" to System.currentTimeMillis().toString()))
        val root = JSONObject(client.getText(endpoint, h).body).optJSONObject("data")
            ?: error("百度直播数据不可用")
        val first = root.keys().asSequence().firstOrNull() ?: error("百度直播房间数据为空")
        val room = root.optJSONObject(first) ?: error("百度直播房间数据无效")
        val video = room.optJSONObject("video")
        val sources = mutableListOf<StreamSource>()
        if (room.optString("status") == "0") {
            val list = video?.optJSONArray("url_clarity_list") ?: video?.optJSONArray("url_list")
            if (list != null) for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val raw = item.optJSONObject("urls")?.optString("flv")?.takeIf(String::isNotBlank)
                    ?: item.optJSONObject("urls")?.optString("hls")
                raw?.takeIf(String::isNotBlank)?.let { additionalSource(it, "原画", h) }?.let(sources::add)
            }
        }
        return AdditionalRoom(room.optJSONObject("host")?.optString("name").orEmpty().ifBlank { "百度直播" },
            video?.optString("title"), sources)
    }
}

class LianjieResolver(client: OkHttpClient) : AdditionalResolver(client) {
    override val platformId = "lianjie"
    override fun supports(url: String) = url.hasHost("lailianjie.com")
    override suspend fun load(url: String, credential: PlatformCredential?): AdditionalRoom {
        val id = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,60}"))) { "连接直播地址缺少房间号" }
        val h = additionalHeaders(credential, "https://www.lailianjie.com/")
        val endpoint = httpUrl("https://api.lailianjie.com/ApiServices/service/live/getRoomInfo",
            mapOf("roomNumber" to id, "_\$t" to "", "_sign" to ""))
        val room = JSONObject(client.getText(endpoint, h).body).optJSONObject("data")
            ?: error("连接直播房间数据不可用")
        val media = if (room.optInt("isonline") == 1) additionalSource(room.optString("videoUrl"), "原画", h) else null
        return AdditionalRoom(room.optString("nickname", "连接直播"), room.optString("defaultRoomTitle"), listOfNotNull(media))
    }
}

class VvxqResolver(client: OkHttpClient) : AdditionalResolver(client) {
    override val platformId = "vvxq"
    override fun supports(url: String) = url.hasHost("vvxqiu.com")
    override suspend fun load(url: String, credential: PlatformCredential?): AdditionalRoom {
        val id = url.toHttpUrlOrNull()?.queryParameter("roomId")
        require(!id.isNullOrBlank() && id.matches(Regex("[A-Za-z0-9_-]{1,60}"))) { "VV 星球地址缺少 roomId" }
        val h = additionalHeaders(credential, "https://h5webcdn-pro.vvxqiu.com/")
        val endpoint = httpUrl("https://h5p.vvxqiu.com/room/video/getRoomData.do", mapOf("roomId" to id))
        val room = JSONObject(client.getText(endpoint, h).body)
        val media = if (room.optInt("status") == 100) additionalSource(room.optString("videoUrl"), "原画", h) else null
        return AdditionalRoom(room.optString("nickName", "VV 星球"), null, listOfNotNull(media))
    }
}
