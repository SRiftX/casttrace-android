package com.streamcap.mobile.danmaku

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class HuyaDanmakuClient(private val httpClient: OkHttpClient) : DanmakuClient {
    private var socket: WebSocket? = null

    override suspend fun connect(context: DanmakuConnectContext) = callbackFlow {
        val join = withContext(Dispatchers.IO) { resolveJoin(context.roomUrl) }
        val request = Request.Builder().url("wss://cdnws.api.huya.com/")
            .header("User-Agent", USER_AGENT).header("Origin", "https://www.huya.com")
            .apply { context.cookie?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }.build()
        val listener = object : WebSocketListener() {
            private var windowStartedAt = 0L
            private var parsedThisWindow = 0

            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(DanmakuFrame.Status("connected-real"))
                // Huya's browser-compatible handshake is a command-1 join.
                // The room API supplies the actual presenter/top/sub channel IDs.
                webSocket.send(command(1, TarsWire.encode(mapOf(
                    0 to join.uid, 1 to true, 2 to "", 3 to "", 4 to join.topSid,
                    5 to join.subSid, 6 to 0, 7 to 0,
                ))).toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val now = SystemClock.elapsedRealtime()
                if (now - windowStartedAt >= 1_000L) {
                    windowStartedAt = now
                    parsedThisWindow = 0
                }
                if (++parsedThisWindow > 20) return
                try {
                    val outer = TarsWire.decode(bytes.toByteArray())
                    val cmd = (outer[0] as? Number)?.toInt() ?: return
                    val body = outer[1] as? ByteArray ?: return
                    if (cmd == 2) trySend(DanmakuFrame.Status("subscribed-real"))
                    if (cmd == 17) trySend(DanmakuFrame.Status("subscribed-real"))
                    if (cmd == 7 || cmd == 22) {
                        parsePush(body, context)?.let {
                            if (trySend(DanmakuFrame.Event(it)).isFailure) close(IOException("弹幕接收队列已满"))
                        }
                    }
                } catch (_: Exception) {
                    trySend(DanmakuFrame.Status("parse-error"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(IOException("弹幕连接关闭 $code")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IOException("弹幕连接失败 HTTP ${response?.code ?: 0}"))
            }
        }
        val ws = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
            .build().newWebSocket(request, listener)
        socket = ws
        val heartbeat = launch {
            while (true) {
                delay(30_000)
                ws.send(command(20, TarsWire.encode(mapOf(0 to 0, 2 to "", 3 to 0))).toByteString())
            }
        }
        awaitClose { heartbeat.cancel(); ws.cancel(); if (socket === ws) socket = null }
    }

    override suspend fun close(reason: String) { socket?.cancel(); socket = null }

    private data class Join(val uid: Long, val topSid: Long, val subSid: Long)

    private fun resolveJoin(roomUrl: String): Join {
        var room = roomUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (room.toLongOrNull() == null) {
            val html = get(roomUrl)
            room = Regex("\"ProfileRoom\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)
                ?: error("虎牙房间号解析失败")
        }
        val data = JSONObject(get("https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$room&showSecret=1"))
            .optJSONObject("data") ?: error("虎牙房间信息为空")
        check(data.optString("realLiveStatus") == "ON") { "虎牙直播间已经下播" }
        val info = data.optJSONObject("profileInfo") ?: error("虎牙主播身份解析失败")
        val uid = info.optLong("uid").takeIf { it > 0 } ?: error("虎牙主播身份解析失败")
        val stream = data.optJSONObject("stream")?.optJSONArray("baseSteamInfoList")?.optJSONObject(0)
        val top = stream?.optLong("lChannelId")?.takeIf { it > 0 } ?: uid
        val sub = stream?.optLong("lSubChannelId")?.takeIf { it > 0 } ?: top
        return Join(uid, top, sub)
    }

    private fun get(url: String): String = httpClient.newCall(Request.Builder().url(url)
        .header("User-Agent", USER_AGENT).build()).execute().use {
        check(it.isSuccessful) { "虎牙房间 HTTP ${it.code}" }; it.body?.string().orEmpty()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private val EPOCH = UUID.randomUUID().toString()
        internal fun command(type: Int, data: ByteArray) = TarsWire.encode(mapOf(0 to type, 1 to data, 2 to 0, 3 to ""))

        internal fun parsePush(body: ByteArray, context: DanmakuConnectContext): DanmakuEvent? {
            val push = TarsWire.decode(body)
            if ((push[1] as? Number)?.toLong() != 1400L) return null
            val notice = TarsWire.decode(push[2] as? ByteArray ?: return null)
            val content = (notice[3] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val user = notice[0] as? Map<*, *>
            return DanmakuEvent(context.sessionId, "huya",
                (push[5] as? Number)?.toLong()?.takeIf { it != 0L }?.toString(),
                SystemClock.elapsedRealtime(), System.currentTimeMillis(), EPOCH,
                userId = user?.get(0)?.toString(), userName = user?.get(2) as? String,
                text = content.take(16_384), rawType = "MessageNotice", parserVersion = "huya-tars-1")
        }
    }
}
