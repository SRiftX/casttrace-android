package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

private fun headers(cookie: PlatformCredential?, referer: String) = buildMap {
    put("User-Agent", DESKTOP_USER_AGENT)
    put("Referer", referer)
    cookie?.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
}

private fun source(url: String, quality: String, headers: Map<String, String>): StreamSource? {
    val address = url.takeIf { it.toHttpUrlOrNull()?.scheme == "https" } ?: return null
    val type = streamTypeOf(address)
    return StreamSource(address, type, quality, headers = headers, requiresFfmpeg = type == StreamType.HLS)
}

data class DynamicRoom(val anchor: String, val title: String?, val live: Boolean, val sources: List<StreamSource>)

abstract class DynamicResolver(private val client: OkHttpClient) : PlatformResolver {
    protected abstract suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom
    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url, null)
        return LiveRoomInfo(url, platformId, data.anchor, data.title)
    }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).live) LiveStatus.LIVE else LiveStatus.OFFLINE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)
    protected fun http() = client
}

class TwitchResolver(client: OkHttpClient) : DynamicResolver(client) {
    override val platformId = "twitch"
    override fun supports(url: String) = url.hasHost("twitch.tv")
    internal suspend fun streamsForUrl(url: String, credential: PlatformCredential?) = load(url, credential).sources
    override suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom {
        val channel = url.toHttpUrlOrNull()?.pathSegments?.firstOrNull().orEmpty()
        require(channel.matches(Regex("[A-Za-z0-9_]{1,25}"))) { "Twitch 地址缺少频道名" }
        val h = headers(credential, "https://www.twitch.tv/$channel").toMutableMap()
        h["Client-Id"] = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        val query = "query PlaybackAccessToken_Template(\$login:String!,\$playerType:String!){streamPlaybackAccessToken(channelName:\$login,params:{platform:\"web\",playerBackend:\"mediaplayer\",playerType:\$playerType}){value signature}}"
        val payload = JSONObject().put("operationName", "PlaybackAccessToken_Template").put("query", query)
            .put("variables", JSONObject().put("login", channel).put("playerType", "site"))
        val root = JSONObject(http().postJson("https://gql.twitch.tv/gql", payload.toString(), h))
        val token = root.optJSONObject("data")?.optJSONObject("streamPlaybackAccessToken")
        val value = token?.optString("value").orEmpty()
        val sig = token?.optString("signature").orEmpty()
        if (value.isBlank() || sig.isBlank()) return DynamicRoom(channel, null, false, emptyList())
        val media = httpUrl("https://usher.ttvnw.net/api/channel/hls/$channel.m3u8", mapOf(
            "allow_source" to "true", "allow_audio_only" to "true", "p" to System.currentTimeMillis().toString(),
            "player" to "twitchweb", "playlist_include_framerate" to "true", "sig" to sig, "token" to value,
        ))
        return DynamicRoom(channel, null, true, listOfNotNull(source(media, "原画", h)))
    }
}

class YouTubeResolver(client: OkHttpClient) : DynamicResolver(client) {
    override val platformId = "youtube"
    override fun supports(url: String) = url.hasHost("youtube.com", "youtu.be")
    override suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom {
        val h = headers(credential, "https://www.youtube.com/")
        val html = http().getText(url, h).body
        val marker = "ytInitialPlayerResponse"
        val start = html.indexOf(marker).takeIf { it >= 0 }?.let { html.indexOf('{', it) } ?: -1
        require(start >= 0) { "YouTube 页面没有播放器数据，可能需要登录或地区访问" }
        val json = balancedJson(html, start)
        val root = JSONObject(json)
        val detail = root.optJSONObject("videoDetails")
        val urlValue = root.optJSONObject("streamingData")?.optString("hlsManifestUrl").orEmpty()
        val live = detail?.optBoolean("isLive") == true && urlValue.isNotBlank()
        return DynamicRoom(detail?.optString("author").orEmpty().ifBlank { "YouTube" },
            detail?.optString("title"), live, if (live) listOfNotNull(source(urlValue, "原画", h)) else emptyList())
    }
}

internal fun balancedJson(text: String, start: Int): String {
    require(start in text.indices && text[start] == '{')
    var depth = 0
    var quoted = false
    var escaped = false
    for (i in start until text.length) {
        val char = text[i]
        if (escaped) { escaped = false; continue }
        if (quoted && char == '\\') { escaped = true; continue }
        if (char == '"') quoted = !quoted
        if (!quoted) {
            if (char == '{') depth++
            if (char == '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
        }
    }
    error("播放器 JSON 不完整")
}

class TikTokResolver(client: OkHttpClient) : DynamicResolver(client) {
    override val platformId = "tiktok"
    override fun supports(url: String) = url.hasHost("tiktok.com")
    override suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom {
        val user = url.toHttpUrlOrNull()?.pathSegments?.firstOrNull()?.removePrefix("@").orEmpty()
        require(user.matches(Regex("[A-Za-z0-9._]{2,30}"))) { "TikTok 地址缺少用户名" }
        val h = headers(credential, "https://www.tiktok.com/@$user/live")
        val endpoint = httpUrl("https://www.tiktok.com/api-live/user/room", mapOf(
            "aid" to "1988", "app_language" to "en", "os" to "android", "referer" to h.getValue("Referer"),
            "sourceType" to "54", "uniqueId" to user,
        ))
        val root = JSONObject(http().getText(endpoint, h).body)
        val data = root.optJSONObject("data") ?: root.optJSONObject("LiveRoom")?.optJSONObject("liveRoomUserInfo")
            ?: error("TikTok 直播数据不可用，可能受地区或登录态限制")
        val room = data.optJSONObject("liveRoom") ?: data
        val live = room.optJSONObject("user")?.optInt("status") == 2 || room.optInt("status") == 2
        val pull = room.optJSONObject("streamData")?.optJSONObject("pull_data")
        val stream = pull?.optString("stream_data")?.takeIf(String::isNotBlank)?.let(::JSONObject)
        val sources = mutableListOf<StreamSource>()
        stream?.optJSONObject("data")?.let { variants ->
            variants.keys().forEach { key ->
                val main = variants.optJSONObject(key)?.optJSONObject("main")
                listOf("flv", "hls").forEach { format ->
                    main?.optString(format)?.let { source(it, key, h) }?.let(sources::add)
                }
            }
        }
        return DynamicRoom(user, room.optString("title"), live, sources)
    }
}

class JdResolver(client: OkHttpClient) : DynamicResolver(client) {
    override val platformId = "jd"
    override fun supports(url: String) = url.hasHost("lives.jd.com", "3.cn")
    override suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom {
        val h = headers(credential, "https://lives.jd.com/")
        val final = http().getText(url, h).finalUrl
        val parsed = final.toHttpUrlOrNull() ?: error("京东直播地址无效")
        var id = parsed.queryParameter("liveId") ?: Regex("(?:#/|/)(\\d{5,})").find(final)?.groupValues?.get(1)
        val author = parsed.queryParameter("authorId")
        if (id == null && author != null) {
            val body = JSONObject().put("authorId", author).toString()
            val result = JSONObject(http().postForm("https://api.m.jd.com/talent_head_findTalentMsg",
                mapOf("functionId" to "talent_head_findTalentMsg", "appid" to "dr_detail", "body" to body), h))
            id = result.optJSONObject("result")?.optJSONObject("livingRoomJump")?.optJSONObject("params")?.optString("id")
        }
        require(!id.isNullOrBlank()) { "京东地址缺少直播 ID" }
        val endpoint = httpUrl("https://api.m.jd.com/client.action", mapOf(
            "body" to JSONObject().put("liveId", id).toString(), "functionId" to "getImmediatePlayToM", "appid" to "h5-live"))
        val data = JSONObject(http().getText(endpoint, h).body).optJSONObject("data") ?: error("京东直播数据不可用")
        val live = data.optInt("status") == 1
        val streams = listOf("videoUrl" to "原画", "h5VideoUrl" to "高清")
            .mapNotNull { (field, quality) -> source(data.optString(field), quality, h) }
        return DynamicRoom(data.optString("nickName", "京东直播"), data.optString("title"), live, streams)
    }
}

class ShopeeResolver(client: OkHttpClient) : DynamicResolver(client) {
    override val platformId = "shopee"
    override fun supports(url: String) = url.hasHost("live.shopee.sg", "live.shopee")
    override suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom {
        val h = headers(credential, "https://live.shopee.sg/")
        val final = http().getText(url, h).finalUrl
        val id = final.toHttpUrlOrNull()?.queryParameter("session")
            ?: Regex("(?:session/|session=)(\\d+)").find(final)?.groupValues?.get(1)
        require(!id.isNullOrBlank()) { "Shopee 地址缺少直播 session" }
        val payload = JSONObject().put("extra", JSONObject()).put("quality_level_id", 0)
            .put("session_ids", org.json.JSONArray().put(id))
        val root = JSONObject(http().postJson("https://live.shopee.sg/api/v1/play_param/session", payload.toString(), h))
        val item = root.optJSONObject("data")?.optJSONArray("play_param_list")?.optJSONObject(0)
            ?: error("Shopee 直播数据不可用")
        val session = item.optJSONObject("session")
        val play = item.optJSONObject("play_param")
        val streams = mutableListOf<StreamSource>()
        play?.optJSONArray("play_url_list")?.let { list ->
            for (i in 0 until list.length()) {
                val entry = list.opt(i)
                val media = if (entry is JSONObject) entry.optString("url") else entry?.toString().orEmpty()
                source(media, "原画", h)?.let(streams::add)
            }
        }
        return DynamicRoom(session?.optString("nickname").orEmpty().ifBlank { session?.optString("username") ?: "Shopee" },
            session?.optString("title"), streams.isNotEmpty(), streams)
    }
}

class TaobaoResolver(client: OkHttpClient) : DynamicResolver(client) {
    override val platformId = "taobao"
    override fun supports(url: String) = url.hasHost("tb.cn", "tbzb.taobao.com")
    override suspend fun load(url: String, credential: PlatformCredential?): DynamicRoom {
        val cookie = credential?.cookie.orEmpty()
        val token = Regex("(?:^|;\\s*)_m_h5_tk=([^;_]+)").find(cookie)?.groupValues?.get(1)
            ?: error("淘宝直播需要登录 Cookie 中的 _m_h5_tk")
        val h = headers(credential, "https://tbzb.taobao.com/")
        val final = http().getText(url, h).finalUrl
        val id = final.toHttpUrlOrNull()?.queryParameter("liveId") ?: Regex("liveId[=/](\\d+)").find(final)?.groupValues?.get(1)
        require(!id.isNullOrBlank()) { "淘宝地址缺少 liveId" }
        val data = JSONObject().put("liveId", id).put("creatorId", JSONObject.NULL).toString()
        val time = System.currentTimeMillis().toString()
        val params = mapOf("appKey" to "12574478", "t" to time,
            "sign" to "$token&$time&12574478&$data".md5(), "data" to data, "v" to "4.0", "type" to "jsonp")
        val response = http().getText(httpUrl("https://h5api.m.taobao.com/h5/mtop.mediaplatform.live.livedetail/4.0/", params), h).body
        val root = JSONObject(response.substring(response.indexOf('{'), response.lastIndexOf('}') + 1))
        val detail = root.optJSONObject("data") ?: error("淘宝直播数据不可用或登录态失效")
        val streams = mutableListOf<StreamSource>()
        detail.optJSONArray("liveUrlList")?.let { list ->
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                listOf("hlsUrl", "flvUrl").forEach { field -> source(entry.optString(field), "原画", h)?.let(streams::add) }
            }
        }
        return DynamicRoom(detail.optString("anchorName", "淘宝直播"), detail.optString("title"),
            detail.optString("streamStatus") == "1", streams)
    }
}
