package com.streamcap.mobile.danmaku

import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.streamcap.mobile.platform.resolver.DouyinPageParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Real Douyin live-chat WebSocket client. It never fabricates events. */
class DouyinDanmakuClient(private val httpClient: OkHttpClient) : DanmakuClient {
    private var socket: WebSocket? = null

    override suspend fun connect(context: DanmakuConnectContext): Flow<DanmakuFrame> = callbackFlow {
        val ttwid = withContext(Dispatchers.IO) {
            context.cookie?.split(';')?.map(String::trim)?.firstOrNull { it.startsWith("ttwid=") }
                ?.substringAfter('=') ?: fetchTtwid()
        }
        val cookie = buildCookie(context.cookie, ttwid)
        val roomId = withContext(Dispatchers.IO) { resolveRoomId(context.roomUrl, cookie) }
        val userUniqueId = (7_300_000_000_000_000_000L + java.security.SecureRandom().nextLong().ushr(1) % 699_999_999_999_999_999L).toString()
        val url = buildUrl(roomId, userUniqueId)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://live.douyin.com")
            .header("Cookie", cookie)
            .build()
        val heartbeat = launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                socket?.send(ByteString.of(*HEARTBEAT))
            }
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@callbackFlow.trySend(DanmakuFrame.Status("connected-real"))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleFrame(bytes.toByteArray(), context, webSocket) { frame ->
                        if (this@callbackFlow.trySend(frame).isFailure) close(IllegalStateException("弹幕接收队列已满"))
                    }
                } catch (_: Exception) {
                    this@callbackFlow.trySend(DanmakuFrame.Status("parse-error"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                this@callbackFlow.trySend(DanmakuFrame.Status("protocol-text-frame"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                this@callbackFlow.trySend(DanmakuFrame.Status("closing:$code"))
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(java.io.IOException("弹幕连接关闭 $code"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Throwable messages can include the signed WebSocket URL.
                close(java.io.IOException("弹幕连接失败 HTTP ${response?.code ?: 0}"))
            }
        }
        val ws = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newWebSocket(request, listener)
        socket = ws
        awaitClose {
            heartbeat.cancel()
            ws.cancel()
            if (socket === ws) socket = null
        }
    }

    override suspend fun close(reason: String) {
        socket?.close(1000, reason.take(120))
        socket = null
    }

    private fun handleFrame(
        raw: ByteArray,
        context: DanmakuConnectContext,
        webSocket: WebSocket,
        emit: (DanmakuFrame) -> Unit,
    ) {
        check(raw.size <= 4 * 1024 * 1024) { "弹幕帧超过限制" }
        val outer = ProtoWire.fields(raw)
        val payload = ProtoWire.firstBytes(raw, 8) ?: return
        val compressed = outer.any { it.number == 5 && it.value is ByteArray &&
            (it.value as ByteArray).let { header -> ProtoWire.firstString(header, 2) == "gzip" } }
        val body = if (compressed || payload.startsWith(GZIP_MAGIC)) gunzip(payload) else payload
        val response = ProtoWire.fields(body)
        val needAck = response.firstLong(9) == 1L
        val internalExt = response.firstOrNull { it.number == 5 && it.value is ByteArray }?.value as? ByteArray
        if (needAck && internalExt != null) {
            val ack = ProtoWire.encodeVarint(2, outer.firstLong(2) ?: 0L) +
                ProtoWire.encodeString(7, "ack") + ProtoWire.encodeBytes(8, internalExt)
            webSocket.send(ByteString.of(*ack))
        }
        response.filter { it.number == 1 && it.value is ByteArray }.forEach { messageField ->
            val message = messageField.value as ByteArray
            val method = ProtoWire.firstString(message, 1) ?: return@forEach
            val eventPayload = ProtoWire.firstBytes(message, 2) ?: return@forEach
            if (!method.contains("ChatMessage", true)) return@forEach
            val common = ProtoWire.firstBytes(eventPayload, 1)
            val user = ProtoWire.firstBytes(eventPayload, 2)
            val text = ProtoWire.firstString(eventPayload, 3)?.takeIf { it.isNotBlank() }?.take(16_384) ?: return@forEach
            val id = (common?.let { ProtoWire.firstLong(it, 2) } ?: ProtoWire.firstLong(message, 3))?.let(java.lang.Long::toUnsignedString)
            emit(DanmakuFrame.Event(DanmakuEvent(
                sessionId = context.sessionId,
                platform = "douyin",
                eventId = id,
                receivedElapsedMs = SystemClock.elapsedRealtime(),
                receivedWallMs = System.currentTimeMillis(),
                clockEpochId = EPOCH_ID,
                userId = user?.let { ProtoWire.firstLong(it, 1)?.let(java.lang.Long::toUnsignedString) },
                userName = user?.let { ProtoWire.firstString(it, 3) },
                text = text,
                user = DanmakuUser(
                    user?.let { ProtoWire.firstLong(it, 1)?.let(java.lang.Long::toUnsignedString) },
                    user?.let { ProtoWire.firstString(it, 3) },
                ),
                rawType = method,
                parserVersion = "douyin-pushframe-1",
            )))
        }
    }

    private fun resolveRoomId(url: String, cookie: String?): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .apply { cookie?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }.build()
        val body = httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "抖音房间页面 HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val page = DouyinPageParser.parse(body)
        check(page.roomStatus != 4) { "直播间已经下播" }
        page.roomId?.takeIf { it.all(Char::isDigit) }?.let { return it }
        return listOf(
            Regex("\\\"roomId\\\"\\s*:\\s*\\\"(\\d+)\\\""),
            Regex("\\\"room_id\\\"\\s*:\\s*(\\d+)"),
            Regex("roomId[^0-9]{0,20}(\\d+)", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { it.find(page.sourceText)?.groupValues?.get(1) }
            ?: error("抖音页面没有返回可用 room_id")
    }

    private fun buildUrl(roomId: String, userUniqueId: String): String {
        val version = "180800"
        val sdk = "1.0.15"
        val signatureInput = "live_id=1,aid=6383,version_code=$version,webcast_sdk_version=$sdk,room_id=$roomId,sub_room_id=,sub_channel_id=,did_rule=3,user_unique_id=$userUniqueId,device_platform=web,device_type=,ac=,identity=audience"
        val host = HOSTS[(hostAttempt++ % HOSTS.size)]
        val builder = "https://$host/webcast/im/push/v2/".toHttpUrl().newBuilder()
            .addQueryParameter("aid", "6383")
            .addQueryParameter("live_id", "1")
            .addQueryParameter("app_name", "douyin_web")
            .addQueryParameter("device_platform", "web")
            .addQueryParameter("identity", "audience")
            .addQueryParameter("did_rule", "3")
            .addQueryParameter("enter_from", "web_live")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Mozilla")
            .addQueryParameter("browser_version", "131.0.0.0")
            .addQueryParameter("host", "https://live.douyin.com")
            .addQueryParameter("endpoint", "live_pc")
            .addQueryParameter("need_persist_msg_count", "15")
            .addQueryParameter("heartbeatDuration", "0")
            .addQueryParameter("update_version_code", "1.0.15")
            .addQueryParameter("room_id", roomId)
            .addQueryParameter("user_unique_id", userUniqueId)
            .addQueryParameter("version_code", version)
            .addQueryParameter("webcast_sdk_version", sdk)
            .addQueryParameter("compress", "gzip")
            .addQueryParameter("signature", XBogus.generate(signatureInput))
            .build().toString()
        return builder.replaceFirst("https://", "wss://")
    }

    private fun buildCookie(userCookie: String?, ttwid: String): String = buildString {
        userCookie?.split(';')?.map(String::trim)?.filter { !it.startsWith("ttwid=") && it.isNotBlank() }
            ?.forEach { append(it).append("; ") }
        append("ttwid=").append(ttwid)
    }

    private fun fetchTtwid(): String {
        val body = "{\"region\":\"cn\",\"aid\":6383,\"needFid\":false,\"service\":\"https://www.douyin.com\",\"union\":true,\"fid\":\"\"}"
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://ttwid.bytedance.com/ttwid/union/register/")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            response.headers.values("Set-Cookie").firstNotNullOfOrNull { cookie ->
                Regex("(?:^|;)\\s*ttwid=([^;]+)").find(cookie)?.groupValues?.get(1)
            } ?: error("抖音没有返回 ttwid")
        }
    }

    private fun gunzip(value: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(value)).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= MAX_DECOMPRESSED_BYTES) { "抖音弹幕帧解压后超过限制" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    companion object {
        private val HEARTBEAT = byteArrayOf(0x3a, 0x02, 0x68, 0x62)
        private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())
        private const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private val HOSTS = listOf("webcast100-ws-web-lq.douyin.com", "webcast100-ws-web-hl.douyin.com", "webcast100-ws-web-lf.douyin.com")
        private val EPOCH_ID = UUID.randomUUID().toString()
    }
    private var hostAttempt = 0
}

private fun List<ProtoWire.Field>.firstLong(number: Int): Long? =
    firstOrNull { it.number == number && it.value is Long }?.value as? Long

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
