package com.streamcap.mobile.danmaku

import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import com.streamcap.mobile.platform.resolver.getText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChzzkDanmakuClient(client: OkHttpClient) : WebSocketChatClient(client) {
    override suspend fun prepare(context: DanmakuConnectContext): ChatConnection {
        val url = requireNotNull(context.roomUrl.toHttpUrlOrNull())
        require(url.host == "chzzk.naver.com")
        val channel = url.pathSegments.last().also { require(it.matches(Regex("[a-fA-F0-9]{32}"))) }
        val headers = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Origin" to "https://chzzk.naver.com",
            "Referer" to "https://chzzk.naver.com/") +
            context.cookie?.takeIf(String::isNotBlank)?.let { mapOf("Cookie" to it) }.orEmpty()
        val client = httpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        val detail = JSONObject(client.getText("https://api.chzzk.naver.com/service/v3/channels/$channel/live-detail", headers).body)
        val content = detail.optJSONObject("content") ?: error("CHZZK 没有返回聊天频道")
        check(content.optString("status") == "OPEN") { "CHZZK 直播已结束" }
        val chat = content.optString("chatChannelId").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
        val token = JSONObject(client.getText("https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=$chat&chatType=STREAMING", headers).body)
            .optJSONObject("content")?.optString("accessToken").orEmpty()
        check(token.isNotBlank()) { "CHZZK 聊天访问受限，请检查登录状态" }
        val server = chat.sumOf { it.code } % 9 + 1
        val request = Request.Builder().url("wss://kr-ss$server.chat.naver.com/chat").apply { headers.forEach(::header) }.build()
        return object : ChatConnection {
            override val request = request
            override fun opened(socket: WebSocket) {
                socket.send(JSONObject().put("ver", "2").put("svcid", "game").put("cid", chat).put("cmd", 100).put("tid", 1)
                    .put("bdy", JSONObject().put("uid", JSONObject.NULL).put("devType", 2001).put("auth", "READ").put("accTkn", token)).toString())
            }
            override fun text(socket: WebSocket, text: String, emit: (DanmakuFrame) -> Unit) {
                val data = JSONObject(text)
                when (data.optInt("cmd")) {
                    0 -> socket.send("{\"cmd\":10000,\"ver\":\"2\"}")
                    10100 -> emit(DanmakuFrame.Status("subscribed-real"))
                    93101 -> parseChat(data, context, android.os.SystemClock.elapsedRealtime()).forEach(emit)
                }
            }
        }
    }

    companion object {
        internal fun parseChat(root: JSONObject, context: DanmakuConnectContext, elapsedMs: Long): List<DanmakuFrame> {
            if (root.optInt("cmd") != 93101) return emptyList()
            val messages = root.optJSONArray("bdy") ?: return emptyList()
            return (0 until messages.length()).mapNotNull { i ->
                val item = messages.optJSONObject(i) ?: return@mapNotNull null
                if (item.optInt("msgTypeCode") != 1 || item.optString("msgStatusType") == "HIDDEN") return@mapNotNull null
                val text = item.optString("msg").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val profile = runCatching { JSONObject(item.optString("profile")) }.getOrNull()
                val time = item.optLong("msgTime").takeIf { it > 0 }
                // No stable message ID is supplied: identical messages at the same ms are legitimate.
                ChatEventFactory.event(context, "chzzk", text, userId = profile?.optString("userIdHash") ?: item.optString("uid"),
                    userName = profile?.optString("nickname"), sourceTimeMs = time, elapsedMs = elapsedMs)
            }
        }
    }
}
