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
    override fun supports(url: String) = url.hasHost("live.kuaishou.com")

    override val supportsCredentials = true
    override suspend fun resolveRoom(url: String): LiveRoomInfo = resolveRoom(url, null)
    override suspend fun resolveRoom(url: String, credential: PlatformCredential?): LiveRoomInfo {
        val data = load(url, credential)
        return LiveRoomInfo(data.url, "快手直播", data.anchor, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isNotEmpty()) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)

    private suspend fun load(input: String, credential: PlatformCredential?): KwaiData {
        val response = client.getText(input, HEADERS + credential?.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty())
        val root = initialState(response.body)
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
                url = secureStreamUrl(url),
                type = streamTypeOf(secureStreamUrl(url)).takeIf { it != StreamType.UNKNOWN } ?: StreamType.FLV,
                quality = item.optString("name", "原画"),
                bitrate = item.optLong("bitrate").takeIf { it > 0 },
                headers = HEADERS,
            )
        }.distinctBy { it.url }
        return KwaiData(response.finalUrl.substringBefore('?'), anchor, title, sources)
    }

    private data class KwaiData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)

    companion object {
        internal fun initialState(page: String): JSONObject {
            val assignment = Regex("window\\.__INITIAL_STATE__\\s*=\\s*").find(page)
                ?: error("快手页面没有返回直播数据；可能触发了平台风控，请稍后重试或更换网络")
            val root = JSONObject(balancedJson(page, page.indexOf('{', assignment.range.last + 1)))
            val rooms = root.optJSONObject("liveroom")?.optJSONArray("playList")
            if (rooms != null) for (i in 0 until rooms.length()) {
                val error = rooms.optJSONObject(i)?.optJSONObject("errorType") ?: continue
                val title = error.optString("title")
                // A blocked response is not evidence that the broadcaster is offline.
                if (title.isNotBlank()) error("快手直播数据不可用：${title.take(120)}")
            }
            return root
        }

        private val HEADERS = mapOf(
            "User-Agent" to DESKTOP_USER_AGENT,
            "Referer" to "https://live.kuaishou.com/",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }
}
