package com.streamcap.mobile.danmaku

import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Douyu chat client. The binary protocol is deliberately kept separate from stream resolving. */
class DouyuDanmakuClient(private val httpClient: OkHttpClient) : DanmakuClient {
    private var socket: WebSocket? = null

    override suspend fun connect(context: DanmakuConnectContext): Flow<DanmakuFrame> = callbackFlow {
        val roomId = withContext(Dispatchers.IO) { resolveRoomId(context.roomUrl) }
        val request = Request.Builder().url("wss://danmuproxy.douyu.com:8502/")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://www.douyu.com")
            .apply { context.cookie?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(packet("type@=loginreq/roomid@=$roomId/username@=/uid@=0/tx@=0/esc@=0/"))
                    webSocket.send(packet("type@=joingroup/rid@=$roomId/gid@=-9999/"))
                    trySend(DanmakuFrame.Status("connected-real"))
                } catch (error: Exception) { close(error) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try { parsePackets(bytes.toByteArray(), context, SystemClock.elapsedRealtime()) {
                    if (trySend(it).isFailure) close(java.io.IOException("弹幕接收队列已满"))
                } }
                catch (_: Exception) { trySend(DanmakuFrame.Status("parse-error")) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(java.io.IOException("斗鱼弹幕连接失败 HTTP ${response?.code ?: 0}"))
            }
        }
        val ws = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newWebSocket(request, listener)
        socket = ws
        val heartbeat = launch {
            while (true) {
                delay(45_000)
                ws.send(packet("type@=mrkl/"))
            }
        }
        awaitClose { heartbeat.cancel(); ws.cancel(); if (socket === ws) socket = null }
    }

    override suspend fun close(reason: String) { socket?.cancel(); socket = null }

    private suspend fun resolveRoomId(url: String): String {
        val clean = url.substringBefore('?').trimEnd('/')
        Regex("douyu\\.com/(?:topic/)?(\\d+)", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.let { return it }
        val slug = clean.substringAfterLast('/').takeIf { it.isNotBlank() } ?: error("斗鱼房间地址无效")
        val html = httpClient.newCall(Request.Builder().url("https://m.douyu.com/$slug").header("User-Agent", USER_AGENT).build())
            .execute().use { check(it.isSuccessful) { "斗鱼房间 HTTP ${it.code}" }; it.body?.string().orEmpty() }
        return Regex("\\\"rid\\\"\\s*:\\s*(\\d+)").find(html)?.groupValues?.get(1)
            ?: error("斗鱼房间号解析失败")
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"

        internal fun packet(body: String): ByteString {
            val payload = (body + '\u0000').toByteArray(Charsets.UTF_8)
            val length = payload.size + 8
            val buffer = ByteBuffer.allocate(length + 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(length).putInt(length).putShort(689).putShort(0).put(payload)
            return ByteString.of(*buffer.array())
        }

        internal fun parsePackets(raw: ByteArray, context: DanmakuConnectContext, elapsedMs: Long,
                                  emit: (DanmakuFrame) -> Unit) {
            require(raw.size <= 4 * 1024 * 1024) { "斗鱼弹幕帧过大" }
            var offset = 0
            while (offset + 12 <= raw.size) {
                val length = ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
                require(length >= 9 && length <= raw.size - offset - 4) { "斗鱼弹幕包长度错误" }
                require(length == ByteBuffer.wrap(raw, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int) { "斗鱼重复长度不一致" }
                val body = raw.copyOfRange(offset + 12, offset + length + 4).toString(Charsets.UTF_8).trimEnd('\u0000')
                val fields = parseFields(body)
                when (fields["type"]) {
                    "loginres" -> emit(DanmakuFrame.Status("subscribed-real"))
                    "chatmsg" -> fields["txt"]?.takeIf(String::isNotBlank)?.let { text ->
                        emit(DanmakuFrame.Event(DanmakuEvent(
                            sessionId = context.sessionId, platform = "douyu", eventId = fields["cid"],
                            receivedElapsedMs = elapsedMs, receivedWallMs = System.currentTimeMillis(),
                            clockEpochId = EPOCH, userId = fields["uid"], userName = fields["nn"], text = text.take(16_384),
                            rawType = "chatmsg", parserVersion = "douyu-websocket-1",
                        )))
                    }
                }
                offset += length + 4
            }
            require(offset == raw.size) { "斗鱼弹幕包不完整" }
        }

        internal fun parseFields(body: String): Map<String, String> = body.split('/').asSequence()
            .mapNotNull { part ->
                val separator = part.indexOf("@=")
                if (separator <= 0) null else part.substring(0, separator) to decode(part.substring(separator + 2))
            }.toMap()

        private fun decode(value: String): String = value.replace("@S", "/").replace("@A", "@")
        private val EPOCH = UUID.randomUUID().toString()
    }
}
