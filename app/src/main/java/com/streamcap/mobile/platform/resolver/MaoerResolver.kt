package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Public room API resolver mirrored from the extracted streamget adapter. */
class MaoerResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "maoer"
    override fun supports(url: String) = url.hasHost("fm.missevan.com", "missevan.com")

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url)
        return LiveRoomInfo(data.url, "猫耳FM直播", data.anchor, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl).sources.isEmpty()) LiveStatus.OFFLINE else LiveStatus.LIVE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl).sources.selectQuality(room.quality)

    private suspend fun load(url: String): MaoerData {
        require(supports(url)) { "不是猫耳直播间地址" }
        val roomId = url.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: error("猫耳直播间地址缺少房间号")
        require(roomId.all(Char::isDigit)) { "猫耳直播间地址缺少数字房间号" }
        val root = JSONObject(client.getText("https://fm.missevan.com/api/v2/live/$roomId").body)
        val info = root.optJSONObject("info") ?: error("猫耳接口没有返回房间信息")
        val creator = info.optJSONObject("creator")
        val room = info.optJSONObject("room")
        val broadcasting = room?.optJSONObject("status")?.optBoolean("broadcasting") == true
        if (!broadcasting) return MaoerData(url, creator?.optString("username").orEmpty(), room?.optString("name"), emptyList())
        val liveRoom = requireNotNull(room) { "猫耳没有返回直播间信息" }
        val channel = liveRoom.optJSONObject("channel") ?: error("猫耳没有返回播放线路")
        val sources = listOfNotNull(
            source(channel.optString("flv_pull_url"), StreamType.FLV),
            source(channel.optString("hls_pull_url"), StreamType.HLS),
        ).distinctBy { it.url }
        return MaoerData(url, creator?.optString("username").orEmpty(), liveRoom.optString("name"), sources)
    }

    private fun source(url: String, type: StreamType) = url.takeIf { it.startsWith("https://") }
        ?.let { StreamSource(it, type, "原画", requiresFfmpeg = type == StreamType.HLS) }

    private data class MaoerData(val url: String, val anchor: String, val title: String?, val sources: List<StreamSource>)
}
