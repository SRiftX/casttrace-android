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
import okio.ByteString
import org.brotli.dec.BrotliInputStream
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream

class BilibiliDanmakuClient(private val httpClient: OkHttpClient) : DanmakuClient {
    private var socket: WebSocket? = null

    override suspend fun connect(context: DanmakuConnectContext) = callbackFlow {
        val auth = withContext(Dispatchers.IO) { resolveAuth(context) }
        val request = Request.Builder().url(auth.wsUrl)
            .header("User-Agent", USER_AGENT).header("Origin", "https://live.bilibili.com")
            .header("Referer", "https://live.bilibili.com/").header("Cookie", auth.cookie).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val body = JSONObject().apply {
                    put("uid", auth.uid); put("roomid", auth.roomId); put("protover", 3)
                    put("platform", "web"); put("type", 2); put("key", auth.token); put("buvid", auth.buvid)
                }.toString().toByteArray(Charsets.UTF_8)
                webSocket.send(packet(7, body))
                trySend(DanmakuFrame.Status("connected-real"))
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    parsePackets(bytes.toByteArray(), context, SystemClock.elapsedRealtime()) {
                        if (trySend(it).isFailure) close(IOException("弹幕接收队列已满"))
                    }
                } catch (failure: BilibiliAuthException) { close(failure) }
                catch (_: Exception) { trySend(DanmakuFrame.Status("parse-error")) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(IOException("B站弹幕连接关闭 $code")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IOException("B站弹幕连接失败 HTTP ${response?.code ?: 0}"))
            }
        }
        val ws = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newWebSocket(request, listener)
        socket = ws
        val heartbeat = launch {
            while (true) { delay(30_000); ws.send(packet(2, "[object Object]".toByteArray())) }
        }
        awaitClose { heartbeat.cancel(); ws.cancel(); if (socket === ws) socket = null }
    }

    override suspend fun close(reason: String) { socket?.cancel(); socket = null }

    private fun resolveAuth(context: DanmakuConnectContext): Auth {
        val input = Regex("live\\.bilibili\\.com/(\\d+)", RegexOption.IGNORE_CASE).find(context.roomUrl)?.groupValues?.get(1)
            ?: error("B站房间地址缺少数字房间号")
        val savedCookie = context.cookie.orEmpty()
        val buvid = cookieValue(savedCookie, "buvid3") ?: get("https://api.bilibili.com/x/frontend/finger/spi", savedCookie)
            .optJSONObject("data")?.optString("b_3")?.takeIf(String::isNotBlank) ?: error("B站访客标识获取失败")
        val cookie = if (cookieValue(savedCookie, "buvid3") != null) savedCookie else
            listOf("buvid3=$buvid", savedCookie).filter(String::isNotBlank).joinToString("; ")
        val init = get("https://api.live.bilibili.com/room/v1/Room/room_init?id=$input", cookie)
        check(init.optInt("code", -1) == 0) { "B站房间解析失败 ${init.optInt("code", -1)}" }
        val roomId = init.optJSONObject("data")?.optLong("room_id")?.takeIf { it > 0 } ?: error("B站房间号无效")
        val nav = get("https://api.bilibili.com/x/web-interface/nav", cookie)
        val navData = nav.optJSONObject("data") ?: error("B站签名信息获取失败")
        val uid = if (navData.optBoolean("isLogin") && cookieValue(cookie, "SESSDATA") != null) navData.optLong("mid") else 0L
        val keys = navData.optJSONObject("wbi_img") ?: error("B站签名参数获取失败")
        val query = BilibiliWbi.sign(mapOf("id" to roomId.toString(), "type" to "0", "web_location" to "444.8"),
            keys.getString("img_url").substringAfterLast('/').substringBefore('.'),
            keys.getString("sub_url").substringAfterLast('/').substringBefore('.'), System.currentTimeMillis() / 1000)
        val info = get("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?$query", cookie)
        check(info.optInt("code", -1) == 0) { "B站弹幕接口拒绝 ${info.optInt("code", -1)}，请检查网络或平台 Cookie" }
        val data = info.optJSONObject("data") ?: error("B站没有返回弹幕鉴权")
        val token = data.optString("token").takeIf(String::isNotBlank) ?: error("B站弹幕 token 为空")
        val server = data.optJSONArray("host_list")?.optJSONObject(0) ?: error("B站弹幕服务器为空")
        val host = server.optString("host")
        check(host.endsWith(".bilibili.com")) { "B站弹幕服务器地址无效" }
        val port = server.optInt("wss_port", 443).also { check(it in 1..65535) }
        return Auth(roomId, uid, token, "wss://$host:$port/sub", cookie, buvid)
    }

    private fun get(url: String, cookie: String): JSONObject = httpClient.newCall(Request.Builder().url(url)
        .header("User-Agent", USER_AGENT).header("Referer", "https://live.bilibili.com/")
        .apply { cookie.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }.build()).execute().use { response ->
        check(response.isSuccessful) { "B站接口 HTTP ${response.code}" }
        JSONObject(response.body?.string().orEmpty())
    }

    private data class Auth(val roomId: Long, val uid: Long, val token: String, val wsUrl: String, val cookie: String, val buvid: String)
    private class BilibiliAuthException(code: Int) : IOException("B站弹幕鉴权失败 $code，请检查平台 Cookie")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private const val MAX_FRAME = 4 * 1024 * 1024
        private val EPOCH = UUID.randomUUID().toString()
        private fun cookieValue(cookie: String, name: String) = cookie.split(';').map(String::trim)
            .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')?.takeIf(String::isNotBlank)

        internal fun packet(operation: Int, body: ByteArray, version: Int = 1): ByteString {
            val buffer = ByteBuffer.allocate(16 + body.size).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(16 + body.size).putShort(16).putShort(version.toShort()).putInt(operation).putInt(1).put(body)
            return ByteString.of(*buffer.array())
        }

        internal fun parsePackets(raw: ByteArray, context: DanmakuConnectContext, elapsedMs: Long,
                                  depth: Int = 0, emit: (DanmakuFrame) -> Unit) {
            require(raw.size <= MAX_FRAME && depth <= 2) { "B站弹幕帧超过限制" }
            var offset = 0
            while (offset + 16 <= raw.size) {
                val header = ByteBuffer.wrap(raw, offset, 16).order(ByteOrder.BIG_ENDIAN)
                val length = header.int
                val headerLength = header.short.toInt() and 0xffff
                val version = header.short.toInt() and 0xffff
                val operation = header.int
                require(headerLength >= 16 && length >= headerLength && length <= raw.size - offset) { "B站弹幕包长度错误" }
                val body = raw.copyOfRange(offset + headerLength, offset + length)
                when {
                    version == 2 || version == 3 -> {
                        val input = ByteArrayInputStream(body)
                        val decoded = (if (version == 2) InflaterInputStream(input) else BrotliInputStream(input)).use(::readLimited)
                        parsePackets(decoded, context, elapsedMs, depth + 1, emit)
                    }
                    operation == 8 -> {
                        val code = JSONObject(body.toString(Charsets.UTF_8)).optInt("code", -1)
                        if (code != 0) throw BilibiliAuthException(code)
                        emit(DanmakuFrame.Status("subscribed-real"))
                    }
                    operation == 5 && version in 0..1 -> parseJson(body, context, elapsedMs, emit)
                }
                offset += length
            }
            require(offset == raw.size) { "B站弹幕包不完整" }
        }

        private fun parseJson(body: ByteArray, context: DanmakuConnectContext, elapsedMs: Long, emit: (DanmakuFrame) -> Unit) {
            val json = JSONObject(body.toString(Charsets.UTF_8).trimEnd('\u0000'))
            BilibiliPaidEvents.parse(json, context, elapsedMs)?.let {
                emit(DanmakuFrame.Event(it)); return
            }
            if (json.optString("cmd").substringBefore(':') != "DANMU_MSG") return
            val info = json.optJSONArray("info") ?: return
            val text = info.optString(1).takeIf(String::isNotBlank) ?: return
            val user = info.optJSONArray(2)
            val uid = user?.optString(0)?.takeIf(String::isNotBlank)
            val name = user?.optString(1)?.takeIf(String::isNotBlank)
            val sourceTime = info.optJSONArray(0)?.optLong(4)?.takeIf { it > 0 }?.let { if (it < 1_000_000_000_000L) it * 1000 else it }
            // The source protocol has no guaranteed unique message ID. Avoid dropping identical repeated comments.
            emit(DanmakuFrame.Event(DanmakuEvent(
                sessionId = context.sessionId, platform = "bilibili", eventId = null,
                receivedElapsedMs = elapsedMs, receivedWallMs = System.currentTimeMillis(), clockEpochId = EPOCH,
                sourceTimeMs = sourceTime, userId = uid, userName = name, text = text.take(16_384),
                user = DanmakuUser(uid, name),
                rawType = "DANMU_MSG", parserVersion = "bilibili-websocket-1",
            )))
        }

        private fun readLimited(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= MAX_FRAME - output.size()) { "B站解压弹幕过大" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
