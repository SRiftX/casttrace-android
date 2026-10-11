package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YiqiLiveResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "yiqilive"
    override fun supports(url: String) = url.hasHost("17.live")
    override suspend fun resolveRoom(url: String): LiveRoomInfo { val d = load(url); return LiveRoomInfo(url, platformId, d.anchor, null) }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.UNKNOWN else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl).sources
    private suspend fun load(url: String): Data {
        val id = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "17Live 地址缺少有效房间号" }
        val headers = mapOf("Origin" to "https://17.live", "Referer" to "https://17.live/", "User-Agent" to DESKTOP_USER_AGENT)
        val profile = JSONObject(client.getText("https://wap-api.17app.co/api/v1/user/room/$id", headers).body)
        val name = profile.optString("displayName", platformId)
        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("https://wap-api.17app.co/api/v1/lives/$id/viewers/alive")
                .apply { headers.forEach(::header) }
                .post(JSONObject().put("liveStreamID", id).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "17Live 请求失败：HTTP ${response.code}" }
                checkNotNull(response.body).string()
            }
        }
        val root = JSONObject(body)
        val media = root.optJSONObject("pullURLsInfo")?.optJSONArray("rtmpURLs")?.optJSONObject(0)
            ?.optString("urlHighQuality").orEmpty()
        val sources = if (root.optInt("status") == 2 && media.startsWith("https://")) {
            when {
                media.contains(".flv", true) -> listOf(StreamSource(media, StreamType.FLV, "原画", headers = headers))
                media.contains(".m3u8", true) -> listOf(StreamSource(media, StreamType.HLS, "原画", headers = headers, requiresFfmpeg = true))
                else -> emptyList()
            }
        } else emptyList()
        return Data(name, sources)
    }
    private data class Data(val anchor: String, val sources: List<StreamSource>)
}
