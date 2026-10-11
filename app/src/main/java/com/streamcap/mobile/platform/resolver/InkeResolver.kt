package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

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
        val sources = listOfNotNull(
            address?.optString("hls_stream_addr")?.takeIf { it.startsWith("https://") }?.let { StreamSource(it, StreamType.HLS, "原画", requiresFfmpeg = true) },
            address?.optString("stream_addr")?.takeIf { it.startsWith("https://") }?.let { StreamSource(it, StreamType.FLV, "原画") },
        )
        return Data(anchor, sources)
    }
    private data class Data(val anchor: String, val sources: List<StreamSource>)
}
