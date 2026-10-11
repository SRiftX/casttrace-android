package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup

data class MoreRoom(val anchor: String, val title: String?, val sources: List<StreamSource>)

private fun moreHeaders(credential: PlatformCredential?, referer: String) = buildMap {
    put("User-Agent", DESKTOP_USER_AGENT)
    put("Referer", referer)
    credential?.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
}

private fun moreSource(raw: String, headers: Map<String, String>): StreamSource? {
    val url = raw.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.toString() ?: return null
    val type = streamTypeOf(url)
    return StreamSource(url, type, "原画", headers = headers, requiresFfmpeg = type == StreamType.HLS)
}

abstract class MoreResolver(protected val client: OkHttpClient) : PlatformResolver {
    protected abstract suspend fun load(url: String, credential: PlatformCredential?): MoreRoom
    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val room = load(url, null)
        return LiveRoomInfo(url, platformId, room.anchor, room.title)
    }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)
}

class WeiboResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "weibo"
    override fun supports(url: String) = url.hasHost("weibo.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val h = moreHeaders(credential, "https://weibo.com/")
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        var id = Regex("/show/([A-Za-z0-9]+)").find(path)?.groupValues?.get(1)
        if (id == null) {
            val uid = Regex("/u/(\\d+)").find(path)?.groupValues?.get(1)
                ?: error("微博地址缺少直播 ID 或用户 ID")
            val feed = JSONObject(client.getText(httpUrl("https://weibo.com/ajax/statuses/mymblog",
                mapOf("uid" to uid, "page" to "1", "feature" to "0")), h).body)
            val list = feed.optJSONObject("data")?.optJSONArray("list")
            if (list != null) for (i in 0 until list.length()) {
                val info = list.optJSONObject(i)?.optJSONObject("page_info") ?: continue
                if (info.optString("object_type") == "live") {
                    id = info.optString("object_id").takeIf(String::isNotBlank)
                    break
                }
            }
        }
        if (id == null) return MoreRoom("微博", null, emptyList())
        val root = JSONObject(client.getText(httpUrl("https://weibo.com/l/pc/anchor/live", mapOf("live_id" to id)), h).body)
        val data = root.optJSONObject("data") ?: error("微博直播数据不可用，可能需要登录 Cookie")
        val item = data.optJSONObject("item")
        val pull = item?.optJSONObject("stream_info")?.optJSONObject("pull")
        val sources = if (item?.optInt("status") == 1) listOfNotNull(
            moreSource(pull?.optString("live_origin_hls_url").orEmpty(), h),
            moreSource(pull?.optString("live_origin_flv_url").orEmpty(), h)) else emptyList()
        return MoreRoom(data.optJSONObject("user_info")?.optString("name").orEmpty().ifBlank { "微博" },
            item?.optString("desc"), sources)
    }
}

class ChangliaoResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "changliao"
    override fun supports(url: String) = url.hasHost("tlclw.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "畅聊地址缺少房间 ID" }
        val h = moreHeaders(credential, "https://wap.tlclw.com/$id")
        val endpoint = httpUrl("https://wap.tlclw.com/api/ui/room/v1.0.0/live.ashx",
            mapOf("roomidx" to id, "currentUrl" to "https://wap.tlclw.com/$id"))
        val info = JSONObject(client.getText(endpoint, h).body).optJSONObject("data")?.optJSONObject("roomInfo")
            ?: error("畅聊房间数据不可用")
        val anchor = info.optString("nickname", "畅聊")
        if (info.optInt("live_stat") != 1) return MoreRoom(anchor, null, emptyList())
        val page = client.getText(url, h).body
        val configStart = page.indexOf("var config =").takeIf { it >= 0 } ?: error("畅聊页面缺少播放配置")
        val jsonStart = page.indexOf('{', configStart)
        val config = JSONObject(balancedJson(page, jsonStart))
        val liveId = info.optString("liveID")
        require(liveId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "畅聊流标识无效" }
        val sources = listOf("domainpullstream_flv" to ".flv", "domainpullstream_hls" to ".m3u8")
            .mapNotNull { (field, extension) ->
                config.optString(field).takeIf(String::isNotBlank)?.trimEnd('/')
                    ?.let { moreSource("$it/$liveId$extension", h) }
            }
        return MoreRoom(anchor, null, sources)
    }
}

class FaceitResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "faceit"
    override fun supports(url: String) = url.hasHost("faceit.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val nickname = Regex("/players/([^/]+)/stream").find(url.toHttpUrlOrNull()?.encodedPath.orEmpty())
            ?.groupValues?.get(1) ?: error("FACEIT 地址缺少玩家名")
        val h = moreHeaders(credential, "https://www.faceit.com/")
        val user = JSONObject(client.getText("https://www.faceit.com/api/users/v1/nicknames/$nickname", h).body)
        val id = user.optJSONObject("payload")?.optString("id").orEmpty()
        require(id.isNotBlank()) { "FACEIT 玩家不存在" }
        val streams = JSONObject(client.getText(httpUrl("https://www.faceit.com/api/stream/v1/streamings",
            mapOf("userId" to id)), h).body).optJSONArray("payload")
        val stream = streams?.optJSONObject(0) ?: return MoreRoom(nickname, null, emptyList())
        if (stream.optString("platform") != "twitch") error("FACEIT 当前直播源不是已支持的 Twitch")
        val channel = stream.optString("platformId")
        require(channel.matches(Regex("[A-Za-z0-9_]{1,25}"))) { "FACEIT Twitch 频道无效" }
        val twitchUrl = "https://www.twitch.tv/$channel"
        val resolver = TwitchResolver(client)
        return MoreRoom(stream.optString("userNickname", nickname), null, resolver.streamsForUrl(twitchUrl, credential))
    }
}

class RednoteResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "rednote"
    override fun supports(url: String) = url.hasHost("xiaohongshu.com", "xhslink.com", "app.xhs.cn")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val h = moreHeaders(credential, "https://app.xhs.cn/")
        val response = client.getText(url, h)
        val script = Jsoup.parse(response.body).selectFirst("script:containsData(window.__INITIAL_STATE__)")?.data()
            ?: error("小红书页面缺少直播状态，可能需要登录或分享链接已失效")
        val start = script.indexOf("window.__INITIAL_STATE__").let { script.indexOf('{', it) }
        val state = JSONObject(balancedJson(script.replace("undefined", "null"), start))
        val live = state.optJSONObject("liveStream")
        if (live?.optString("liveStatus") != "success") return MoreRoom("小红书", null, emptyList())
        val room = live.optJSONObject("roomData")?.optJSONObject("roomInfo") ?: error("小红书房间数据不可用")
        val title = room.optString("roomTitle")
        if (title.contains("回放")) return MoreRoom("小红书", title, emptyList())
        val deeplink = room.optString("deeplink").toHttpUrlOrNull()
        val media = deeplink?.queryParameter("flvUrl").orEmpty()
        val sources = listOfNotNull(moreSource(media, h))
        return MoreRoom(deeplink?.queryParameter("host_nickname").orEmpty().ifBlank { "小红书" }, title, sources)
    }
}

class FlexTvResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "flextv"
    override fun supports(url: String) = url.hasHost("flextv.co.kr", "ttinglive.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = Regex("/channels/([^/]+)").find(url.toHttpUrlOrNull()?.encodedPath.orEmpty())?.groupValues?.get(1)
            ?: error("FlexTV 地址缺少频道 ID")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "FlexTV 频道 ID 无效" }
        val h = moreHeaders(credential, "https://www.ttinglive.com/channels/$id/live")
        val page = client.getText("https://www.ttinglive.com/channels/$id/live", h).body
        val next = Jsoup.parse(page).selectFirst("script#__NEXT_DATA__")?.data()?.let(::JSONObject)
            ?: error("FlexTV 页面缺少频道数据")
        val channel = next.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("channelStream")
            ?.optJSONObject("channel") ?: error("FlexTV 频道数据不可用")
        if (channel.has("message")) {
            if (channel.optString("message").contains("로그인")) error("FlexTV 频道需要登录 Cookie")
            return MoreRoom(id, null, emptyList())
        }
        val owner = channel.optJSONObject("owner")
        val result = JSONObject(client.getText("https://api.ttinglive.com/api/channels/$id/stream?option=all", h).body)
        val list = result.optJSONArray("sources")
        val sources = mutableListOf<StreamSource>()
        if (list != null) for (i in 0 until list.length()) {
            list.optJSONObject(i)?.optString("url")?.let { moreSource(it, h) }?.let(sources::add)
        }
        return MoreRoom(owner?.optString("nickname").orEmpty().ifBlank { id }, null, sources)
    }
}

class ZhihuResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "zhihu"
    override fun supports(url: String) = url.hasHost("zhihu.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = Regex("/theater/([^/?]+)").find(url.toHttpUrlOrNull()?.encodedPath.orEmpty())?.groupValues?.get(1)
            ?: error("知乎直播需要 theater 地址")
        val h = moreHeaders(credential, "https://www.zhihu.com/")
        val page = Jsoup.parse(client.getText(url, h).body)
        val initial = page.selectFirst("script#js-initialData")?.data()?.let(::JSONObject)
            ?: error("知乎页面缺少直播数据，可能需要登录 Cookie")
        val theater = initial.optJSONObject("initialState")?.optJSONObject("theater")?.optJSONObject("theaters")
            ?.optJSONObject(id) ?: error("知乎剧场不存在")
        val drama = theater.optJSONObject("drama")
        val play = drama?.optJSONObject("playInfo")
        val sources = if (drama?.optInt("status") == 1) listOfNotNull(
            moreSource(play?.optString("hlsUrl").orEmpty(), h), moreSource(play?.optString("playUrl").orEmpty(), h))
            else emptyList()
        return MoreRoom(theater.optJSONObject("actor")?.optString("name").orEmpty().ifBlank { "知乎" },
            theater.optString("theme"), sources)
    }
}

class PiaopiaoResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "piaopiao"
    override fun supports(url: String) = url.hasHost("weimipopo.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = url.toHttpUrlOrNull()?.queryParameter("anchorUid")
            ?: error("漂漂直播地址缺少 anchorUid")
        val h = moreHeaders(credential, "https://m.pp.weimipopo.com/")
        val root = JSONObject(client.postJson("https://api.pp.weimipopo.com/live/preview",
            JSONObject().put("inviteUuid", "").put("anchorUuid", id).toString(), h))
        val data = root.optJSONObject("data") ?: error("漂漂直播数据不可用")
        val source = if (data.optBoolean("living")) moreSource(data.optString("pullUrl"), h) else null
        return MoreRoom(data.optString("name", "漂漂直播"), null, listOfNotNull(source))
    }
}

class HuajiaoResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "huajiao"
    override fun supports(url: String) = url.hasHost("huajiao.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = url.toHttpUrlOrNull()?.queryParameter("liveid")
            ?: error("花椒直播需要包含 liveid 的分享链接")
        val h = moreHeaders(credential, "https://www.huajiao.com/")
        val root = JSONObject(client.getText("https://live.huajiao.com/feed/getFeedInfo?relateid=$id", h).body)
        val data = root.optJSONObject("data") ?: error("花椒直播数据不可用或需要登录 Cookie")
        val feed = data.optJSONObject("feed")
        val author = data.optJSONObject("author")
        if (feed?.optString("creatime").isNullOrBlank()) return MoreRoom(author?.optString("nickname").orEmpty().ifBlank { "花椒" }, null, emptyList())
        val params = mapOf("time" to System.currentTimeMillis().toString(), "version" to "1.0.0",
            "sn" to feed.optString("sn"), "liveid" to feed.optString("relateid", id),
            "uid" to author?.optString("uid").orEmpty(), "encode" to "h265")
        val play = JSONObject(client.getText(httpUrl("https://live.huajiao.com/live/substream", params), h).body)
            .optJSONObject("data") ?: error("花椒播放线路不可用")
        return MoreRoom(author?.optString("nickname").orEmpty().ifBlank { "花椒" }, feed.optString("title"),
            listOfNotNull(moreSource(play.optString("h264_url"), h), moreSource(play.optString("pull_m3u8"), h)))
    }
}

class AcfunResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "acfun"
    override fun supports(url: String) = url.hasHost("live.acfun.cn")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,60}"))) { "AcFun 地址缺少主播 ID" }
        val h = moreHeaders(credential, "https://live.acfun.cn/")
        val profile = JSONObject(client.getText("https://live.acfun.cn/rest/pc-direct/user/userInfo?userId=$id", h).body)
            .optJSONObject("profile") ?: error("AcFun 主播数据不可用")
        val liveId = profile.optString("liveId")
        if (liveId.isBlank()) return MoreRoom(profile.optString("name", "AcFun"), null, emptyList())
        val did = "web_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val visitor = JSONObject(client.postForm("https://id.app.acfun.cn/rest/app/visitor/login",
            mapOf("sid" to "acfun.api.visitor"), h + ("Cookie" to "_did=$did")))
        val params = mapOf("subBiz" to "mainApp", "kpn" to "ACFUN_APP", "kpf" to "PC_WEB",
            "userId" to visitor.optString("userId"), "did" to did,
            "acfun.api.visitor_st" to visitor.optString("acfun.api.visitor_st"))
        val body = mapOf("authorId" to id, "pullStreamType" to "FLV")
        val root = JSONObject(client.postForm(httpUrl("https://api.kuaishouzt.com/rest/zt/live/web/startPlay", params), body, h))
        val data = root.optJSONObject("data") ?: error("AcFun 播放接口返回鉴权失败")
        val manifest = data.optString("videoPlayRes").takeIf(String::isNotBlank)?.let(::JSONObject)
        val reps = manifest?.optJSONArray("liveAdaptiveManifest")?.optJSONObject(0)
            ?.optJSONArray("adaptationSet")?.optJSONObject(0)?.optJSONArray("representation")
        val sources = mutableListOf<StreamSource>()
        if (reps != null) for (i in 0 until reps.length()) reps.optJSONObject(i)?.optString("url")
            ?.let { moreSource(it, h) }?.let(sources::add)
        return MoreRoom(profile.optString("name", "AcFun"), data.optString("caption"), sources)
    }
}

class LaixiuResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "laixiu"
    override fun supports(url: String) = url.hasHost("imkktv.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val parsed = url.toHttpUrlOrNull()
        val id = parsed?.queryParameter("roomId") ?: parsed?.queryParameter("anchorId")
        require(!id.isNullOrBlank()) { "来秀直播地址缺少 roomId 或 anchorId" }
        val timestamp = System.currentTimeMillis().toString()
        val imei = java.util.UUID.randomUUID().toString().replace("-", "")
        val h = moreHeaders(credential, "https://www.imkktv.com/") + mapOf(
            "mobileModel" to "web", "timestamp" to timestamp, "loginType" to "2", "versionCode" to "10003",
            "imei" to imei, "requestId" to "web${imei}${timestamp}kk792f28d6ff1f34ec702c08626d454b39pro".md5(),
            "channel" to "9", "version" to "1.0.0", "os" to "web", "platform" to "WEB")
        val data = JSONObject(client.getText(httpUrl("https://api.imkktv.com/liveroom/getShareLiveVideo",
            mapOf("roomId" to id)), h).body).optJSONObject("data") ?: error("来秀播放接口数据不可用")
        val media = if (data.optInt("playStatus") == 0) moreSource(data.optString("playUrl"), h) else null
        return MoreRoom(data.optString("nickname", "来秀"), null, listOfNotNull(media))
    }
}

class XindongreboResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "xindongrebo"
    override fun supports(url: String) = url.hasHost("xcqrkj.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val h = moreHeaders(credential, url)
        val page = client.getText(url, h).body
        val marker = page.indexOf("var user =").takeIf { it >= 0 } ?: error("心动热播页面缺少房间对象")
        val start = page.indexOf('{', marker)
        val data = JSONObject(balancedJson(page, start))
        val media = if (!page.contains("common-text-center\" style=\"display:block"))
            moreSource(data.optString("play_url"), h) else null
        return MoreRoom(data.optString("zb_nickname", "心动热播"), null, listOfNotNull(media))
    }
}

class LookResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "look"
    override fun supports(url: String) = url.hasHost("look.163.com")
    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val id = url.toHttpUrlOrNull()?.queryParameter("id") ?: error("Look 地址缺少直播间 ID")
        val h = moreHeaders(credential, "https://look.163.com/")
        val key = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val plaintext = JSONObject().put("liveRoomNo", id).toString()
        val params = lookAes(lookAes(plaintext, "0CoJUm6Qyw8W8jud"), key)
        val modulus = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec" +
            "4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813" +
            "cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
        val reversed = key.reversed().toByteArray()
        val encKey = java.math.BigInteger(1, reversed).modPow(java.math.BigInteger("010001", 16),
            java.math.BigInteger(modulus, 16)).toString(16).padStart(256, '0')
        val data = JSONObject(client.postForm("https://api.look.163.com/weapi/livestream/room/get/v3",
            mapOf("params" to params, "encSecKey" to encKey), h)).optJSONObject("data")
            ?: error("Look 直播数据不可用")
        val info = data.optJSONObject("roomInfo")
        val play = if (data.optInt("liveStatus") == 1 && info?.optInt("liveType") != 1)
            info?.optJSONObject("liveUrl") else null
        return MoreRoom(data.optJSONObject("anchor")?.optString("nickName").orEmpty().ifBlank { "Look" },
            info?.optString("title"), listOfNotNull(moreSource(play?.optString("httpPullUrl").orEmpty(), h),
                moreSource(play?.optString("hlsPullUrl").orEmpty(), h)))
    }
}

private fun lookAes(text: String, key: String): String {
    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
        javax.crypto.spec.SecretKeySpec(key.toByteArray(), "AES"),
        javax.crypto.spec.IvParameterSpec("0102030405060708".toByteArray()))
    return android.util.Base64.encodeToString(cipher.doFinal(text.toByteArray()), android.util.Base64.NO_WRAP)
}
