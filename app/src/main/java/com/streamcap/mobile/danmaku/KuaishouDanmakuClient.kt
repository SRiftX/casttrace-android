package com.streamcap.mobile.danmaku

import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import com.streamcap.mobile.platform.resolver.balancedJson
import com.streamcap.mobile.platform.resolver.getText
import com.streamcap.mobile.platform.resolver.KuaishouResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class KuaishouDanmakuClient(client: OkHttpClient) : WebSocketChatClient(client) {
    override suspend fun prepare(context: DanmakuConnectContext): ChatConnection {
        val url = requireNotNull(context.roomUrl.toHttpUrlOrNull())
        require(url.host == "live.kuaishou.com") { "快手弹幕需要直播间地址" }
        val headers = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://live.kuaishou.com/") +
            context.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
        val page = httpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().getText(context.roomUrl, headers).body
        val info = handshakeInfo(page)
        val token = info.optString("token").also { require(it.isNotBlank()) { "快手弹幕 token 为空" } }
        val liveId = info.optString("liveStreamId").also { require(it.isNotBlank()) { "快手直播标识为空" } }
        val wsUrl = info.optJSONArray("webSocketUrls")?.optString(0).orEmpty()
        require(wsUrl.startsWith("wss://")) { "快手没有返回安全弹幕线路" }
        val request = Request.Builder().url(wsUrl).apply { headers.forEach(::header) }.build()
        return object : ChatConnection {
            override val request = request
            override val heartbeatMillis = 20_000L
            override fun opened(socket: WebSocket) {
                val payload = ProtoWire.encodeString(1, token) + ProtoWire.encodeString(2, liveId) +
                    ProtoWire.encodeString(7, UUID.randomUUID().toString().replace("-", "").take(16) + "_" + System.currentTimeMillis())
                socket.send(envelope(200, payload).toByteString())
            }
            override fun heartbeat(socket: WebSocket) {
                socket.send(envelope(1, ProtoWire.encodeVarint(1, System.currentTimeMillis())).toByteString())
            }
            override fun binary(socket: WebSocket, bytes: ByteArray, emit: (DanmakuFrame) -> Unit) {
                parse(bytes, context, android.os.SystemClock.elapsedRealtime()).forEach(emit)
            }
        }
    }

    companion object {
        internal fun handshakeInfo(page: String): JSONObject {
            val marker = Regex("\"wsFeedInfo\"\\s*:").find(page)
            if (marker != null) return JSONObject(balancedJson(page, page.indexOf('{', marker.range.last + 1)))
            val room = KuaishouResolver.initialState(page).optJSONObject("liveroom")
                ?: error("快手没有返回弹幕握手参数，请检查登录状态或稍后重试")
            val playlist = room.optJSONArray("playList")
            val active = room.optInt("activeIndex", 0)
            val liveId = playlist?.optJSONObject(active)?.optJSONObject("liveStream")?.optString("id").orEmpty()
            return JSONObject().put("token", room.optString("token"))
                .put("liveStreamId", liveId)
                .put("webSocketUrls", room.optJSONArray("websocketUrls") ?: room.optJSONArray("webSocketUrls"))
        }

        internal fun envelope(type: Long, payload: ByteArray) =
            ProtoWire.encodeVarint(1, type) + ProtoWire.encodeVarint(2, 1) + ProtoWire.encodeBytes(3, payload)

        internal fun parse(bytes: ByteArray, context: DanmakuConnectContext, elapsedMs: Long): List<DanmakuFrame> {
            require(bytes.size <= 4 * 1024 * 1024)
            val type = ProtoWire.firstLong(bytes, 1)
            if (type == 300L) return listOf(DanmakuFrame.Status("subscribed-real"))
            if (type == 103L) error("快手拒绝弹幕订阅")
            if (type != 310L) return emptyList()
            var payload = ProtoWire.firstBytes(bytes, 3) ?: return emptyList()
            when (ProtoWire.firstLong(bytes, 2) ?: 1L) {
                0L, 1L -> Unit
                2L -> payload = GZIPInputStream(payload.inputStream()).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 4 * 1024 * 1024) { "快手解压弹幕帧过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                else -> error("快手弹幕压缩类型不支持")
            }
            val comments = ProtoWire.repeatedBytes(payload, 5).mapNotNull { comment ->
                val text = ProtoWire.firstString(comment, 3)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                // Hidden comments are moderation events and must not be rendered as public chat.
                if (ProtoWire.firstLong(comment, 7) == 2L) return@mapNotNull null
                val user = ProtoWire.firstBytes(comment, 2) ?: byteArrayOf()
                ChatEventFactory.event(context, "kuaishou", text, ProtoWire.firstString(comment, 1),
                    ProtoWire.firstString(user, 1), ProtoWire.firstString(user, 2), elapsedMs = elapsedMs)
            }
            val gifts = ProtoWire.repeatedBytes(payload, 9).mapNotNull {
                ProtobufGiftEvents.kuaishou(it, context, elapsedMs)?.let(DanmakuFrame::Event)
            }
            return comments + gifts
        }
    }
}
