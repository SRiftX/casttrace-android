package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class KuaishouResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "kuaishou"
    override fun supports(url: String) = url.contains("live.kuaishou.com", true)

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url, null)
        return LiveRoomInfo(data.url, "快手直播", data.anchor, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isNotEmpty()) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)

    private suspend fun load(input: String, credential: PlatformCredential?): KwaiData {
        val response = client.getText(input, HEADERS + credential?.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty())
        val marker = "window.__INITIAL_STATE__="
        val start = response.body.indexOf(marker)
        require(start >= 0) { "快手页面没有返回直播数据；可能触发了平台风控，请稍后重试或更换网络" }
        val jsonStart = start + marker.length
        val endCandidates = listOf(";(function(){var s", "</script>")
            .map { token -> response.body.indexOf(token, jsonStart) }
            .filter { it > jsonStart }
        val end = endCandidates.minOrNull() ?: error("无法读取快手页面数据")
        val root = JSONObject(response.body.substring(jsonStart, end))
        val liveObject = root.findObjects("liveStream").firstOrNull()?.optJSONObject("liveStream")
        val owner = root.findObjects("author").firstOrNull()?.optJSONObject("author")
        val anchor = owner?.optString("name").orEmpty().ifBlank {
            root.findObjects("name").firstNotNullOfOrNull { it.optString("name").takeIf(String::isNotBlank) }
                ?: "快手主播"
        }
        val title = liveObject?.optString("caption")?.takeIf(String::isNotBlank)
        val representations = mutableListOf<JSONObject>()
        liveObject?.findObjects("representation")?.forEach { holder ->
            val value = holder.opt("representation")
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) value.optJSONObject(index)?.let(representations::add)
                is JSONObject -> representations += value
            }
        }
        val sources = representations.mapNotNull { item ->
            val url = item.optString("url")
            if (!url.startsWith("http")) null else StreamSource(
                url = url,
                type = streamTypeOf(url).takeIf { it != StreamType.UNKNOWN } ?: StreamType.FLV,
                quality = item.optString("name", "原画"),
                bitrate = item.optLong("bitrate").takeIf { it > 0 },
                headers = HEADERS,
            )
        }.distinctBy { it.url }
        return KwaiData(response.finalUrl.substringBefore('?'), anchor, title, sources)
    }

    private data class KwaiData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)

    companion object {
        private val HEADERS = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://live.kuaishou.com/",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }
}
