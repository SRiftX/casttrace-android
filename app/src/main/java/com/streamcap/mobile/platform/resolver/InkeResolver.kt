package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONArray

class InkeResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "inke"
    override fun supports(url: String) = url.hasHost("inke.cn")
    override suspend fun resolveRoom(url: String): LiveRoomInfo { val d = load(url); return LiveRoomInfo(url, "映客直播", d.anchor, null) }
    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) = if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE
    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) = load(room.normalizedUrl).sources
    private suspend fun load(url: String): Data {
        val parsed = url.toHttpUrl()
        val uid = parsed.queryParameter("uid")?.takeIf(String::isNotBlank) ?: error("映客地址缺少 uid")
        val id = parsed.queryParameter("id")?.takeIf(String::isNotBlank) ?: error("映客地址缺少直播 id")
        val api = "https://webapi.busi.inke.cn/web/live_share_pc".toHttpUrl().newBuilder()
            .addQueryParameter("uid", uid).addQueryParameter("id", id)
            .addQueryParameter("_t", (System.currentTimeMillis() / 1000).toString()).build().toString()
        val data = JSONObject(client.getText(api).body).optJSONObject("data") ?: error("映客未返回房间数据")
        val anchor = data.optJSONObject("media_info")?.optString("nick").orEmpty().ifBlank { "映客 $uid" }
        if (data.optInt("status") != 1) return Data(anchor, emptyList())
        val address = data.optJSONArray("live_addr")?.optJSONObject(0)
        var sources = listOfNotNull(
            address?.optString("hls_stream_addr")?.takeIf { it.startsWith("https://") }?.let { StreamSource(it, StreamType.HLS, "原画", requiresFfmpeg = true) },
            address?.optString("stream_addr")?.takeIf { it.startsWith("https://") }?.let { StreamSource(it, StreamType.FLV, "原画") },
        )
        // The current share endpoint can report a live room but omit live_addr.
        // The site's public directories still carry its stream. Match both IDs
        // so an old share can never silently record a different broadcast.
        if (sources.isEmpty()) {
            for (directory in listOf("Live_top_pc", "Live_hot_pc")) {
                val root = JSONObject(client.getText("https://webapi.busi.inke.cn/web/$directory").body)
                sources = directorySources(root.optJSONObject("data")?.opt("list"), uid, id)
                if (sources.isNotEmpty()) break
            }
        }
        return Data(anchor, sources)
    }
    companion object {
        internal fun directorySources(value: Any?, uid: String, liveId: String): List<StreamSource> {
            return when (value) {
                is JSONArray -> (0 until value.length()).flatMap { directorySources(value.opt(it), uid, liveId) }
                is JSONObject -> {
                    if (value.optString("uid") == uid && value.optString("live_id") == liveId) {
                        listOf("stream_addr", "hls_stream_addr").mapNotNull { key ->
                            val url = secureStreamUrl(value.optString(key))
                            val type = streamTypeOf(url)
                            url.takeIf { it.startsWith("https://") && type in setOf(StreamType.FLV, StreamType.HLS) }
                                ?.let { StreamSource(it, type, "原画", requiresFfmpeg = type == StreamType.HLS) }
                        }.distinctBy { it.url }
                    } else value.keys().asSequence().flatMap { directorySources(value.opt(it), uid, liveId) }.toList()
                }
                else -> emptyList()
            }
        }
    }
    private data class Data(val anchor: String, val sources: List<StreamSource>)
}
