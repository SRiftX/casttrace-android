package com.streamcap.mobile.danmaku

import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import com.streamcap.mobile.platform.resolver.getText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** Public broadcast subscription. No account login or message-posting endpoint is used. */
class ShowroomDanmakuClient(client: OkHttpClient) : WebSocketChatClient(client) {
    override suspend fun prepare(context: DanmakuConnectContext): ChatConnection {
        val url = requireNotNull(context.roomUrl.toHttpUrlOrNull())
        require(url.scheme == "https" && (url.host == "showroom-live.com" || url.host.endsWith(".showroom-live.com")))
        val client = httpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        val roomId = url.queryParameter("room_id")?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: url.pathSegments.lastOrNull()?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: Jsoup.parse(client.getText(context.roomUrl).body).selectFirst("a[href*=/room/profile?room_id=]")
                ?.attr("href")?.let { Regex("room_id=([0-9]+)").find(it)?.groupValues?.get(1) }
            ?: error("ShowRoom 地址缺少 room_id")
        val info = JSONObject(client.getText("https://www.showroom-live.com/api/live/live_info?room_id=$roomId").body)
        check(info.optInt("live_status") == 2) { "ShowRoom 直播已结束" }
        val host = info.optString("bcsvr_host")
        require(host.matches(Regex("[A-Za-z0-9.-]+\\.showroom-live\\.com"))) { "ShowRoom 聊天服务器无效" }
        val key = info.optString("bcsvr_key")
        require(key.isNotBlank() && key.none { it == '\t' || it == '\r' || it == '\n' }) { "ShowRoom 聊天订阅参数不可用" }
        return object : ChatConnection {
            override val request = Request.Builder().url("wss://$host:443")
                .header("User-Agent", DESKTOP_USER_AGENT).header("Origin", "https://www.showroom-live.com").build()
            private var subscribed = false
            override fun opened(socket: WebSocket) { socket.send("SUB\t$key") }
            override fun text(socket: WebSocket, text: String, emit: (DanmakuFrame) -> Unit) {
                text.lineSequence().forEach { line ->
                    val fields = line.split('\t', limit = 3)
                    if (fields.size != 3 || fields[0] != "MSG" || fields[1] != key) return@forEach
                    if (!subscribed) { subscribed = true; emit(DanmakuFrame.Status("subscribed-real")) }
                    parseChat(JSONObject(fields[2]), context, android.os.SystemClock.elapsedRealtime())?.let(emit)
                }
            }
        }
    }

    companion object {
        internal fun parseChat(item: JSONObject, context: DanmakuConnectContext, elapsedMs: Long): DanmakuFrame? {
            if (item.optInt("t") != 1) return null
            val text = item.optString("cm").takeIf(String::isNotBlank) ?: return null
            return ChatEventFactory.event(context, "showroom", text, userId = item.optString("u"),
                userName = item.optString("ac"),
                sourceTimeMs = item.optLong("created_at").takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000),
                elapsedMs = elapsedMs)
        }
    }
}
