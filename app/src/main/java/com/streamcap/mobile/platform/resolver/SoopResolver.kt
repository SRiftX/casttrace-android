package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.PlatformCredential
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Public playback authorization; account cookies are optional for unrestricted rooms. */
class SoopResolver(client: OkHttpClient) : MoreResolver(client) {
    override val platformId = "soop"
    override fun supports(url: String) = url.hasHost("sooplive.com", "sooplive.co.kr")

    override suspend fun load(url: String, credential: PlatformCredential?): MoreRoom {
        val parsed = url.toHttpUrl()
        val id = parsed.pathSegments.firstOrNull().orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_]{1,50}"))) { "SOOP 地址缺少主播 ID" }
        val h = buildMap {
            put("User-Agent", DESKTOP_USER_AGENT)
            put("Origin", "https://play.sooplive.com")
            put("Referer", "https://play.sooplive.com/$id")
            credential?.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
        val endpoint = "https://live.sooplive.com/afreeca/player_live_api.php?bjid=$id"
        val params = mapOf("bid" to id, "bno" to "", "type" to "", "pwd" to parsed.queryParameter("pwd").orEmpty(),
            "player_type" to "html5", "stream_type" to "common", "quality" to "master", "mode" to "landing",
            "from_api" to "0", "is_revive" to "false")
        suspend fun channel(type: String) = JSONObject(client.postForm(endpoint, params + ("type" to type), h))
            .optJSONObject("CHANNEL") ?: error("SOOP 没有返回频道数据")
        val room = channel("")
        val result = room.optInt("RESULT", -1)
        check(result == 0 || result == 1) { "SOOP 播放受限（$result），请检查登录、年龄或房间密码" }
        if (room.opt("VIEWPRESET") == null || room.opt("VIEWPRESET") == JSONObject.NULL ||
            room.optString("VIEWPRESET") in setOf("", "false", "0"))
            return MoreRoom(id, room.optString("TITLE"), emptyList())
        val broadcast = room.optString("BNO")
        require(broadcast.matches(Regex("[0-9]+"))) { "SOOP 未返回当前直播编号" }
        val assign = httpUrl("https://livestream-manager.sooplive.com/broad_stream_assign.html", mapOf(
            "return_type" to "gcp_cdn", "use_cors" to "false", "cors_origin_url" to "play.sooplive.com",
            "broad_key" to "$broadcast-common-master-hls"))
        val media = JSONObject(client.getText(assign, h).body).optString("view_url")
        val aid = channel("aid").optString("AID")
        check(media.isNotBlank() && aid.isNotBlank()) { "SOOP 未返回播放授权" }
        val stream = secureStreamUrl(media).toHttpUrl().newBuilder().addQueryParameter("aid", aid).build()
        return MoreRoom(id, room.optString("TITLE"), listOf(com.streamcap.mobile.domain.model.StreamSource(
            stream.toString(), com.streamcap.mobile.domain.model.StreamType.HLS, "原画", headers = h, requiresFfmpeg = true)))
    }
}
