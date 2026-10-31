package com.streamcap.mobile.danmaku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Common lifecycle only; platform handshakes and message semantics stay in each adapter. */
abstract class WebSocketChatClient(protected val httpClient: OkHttpClient) : DanmakuClient {
    protected abstract suspend fun prepare(context: DanmakuConnectContext): ChatConnection
    @Volatile private var socket: WebSocket? = null

    protected interface ChatConnection {
        val request: Request
        val heartbeatMillis: Long get() = 0
        fun opened(socket: WebSocket) {}
        fun heartbeat(socket: WebSocket) {}
        fun text(socket: WebSocket, text: String, emit: (DanmakuFrame) -> Unit) {}
        fun binary(socket: WebSocket, bytes: ByteArray, emit: (DanmakuFrame) -> Unit) {}
    }

    override suspend fun connect(context: DanmakuConnectContext): Flow<DanmakuFrame> = callbackFlow {
        val connection = withContext(Dispatchers.IO) { prepare(context) }
        val emit: (DanmakuFrame) -> Unit = { frame ->
            if (trySend(frame).isFailure) close(IOException("弹幕接收队列已满"))
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runCatching { connection.opened(webSocket) }
                    .onSuccess { emit(DanmakuFrame.Status("connected-real")) }
                    .onFailure { close(it) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    require(text.length <= 1024 * 1024) { "弹幕帧过大" }
                    connection.text(webSocket, text, emit)
                } catch (_: Exception) { emit(DanmakuFrame.Status("parse-error")) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    require(bytes.size <= 4 * 1024 * 1024) { "弹幕帧过大" }
                    connection.binary(webSocket, bytes.toByteArray(), emit)
                } catch (_: Exception) { emit(DanmakuFrame.Status("parse-error")) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Never include handshake URLs, cookies or access tokens in persisted errors.
                close(IOException("弹幕连接失败 HTTP ${response?.code ?: 0}"))
            }
        }
        val ws = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS).build().newWebSocket(connection.request, listener)
        socket = ws
        val heartbeat = if (connection.heartbeatMillis > 0) launch {
            while (true) { delay(connection.heartbeatMillis); connection.heartbeat(ws) }
        } else null
        awaitClose { heartbeat?.cancel(); ws.cancel(); if (socket === ws) socket = null }
    }

    override suspend fun close(reason: String) { socket?.cancel(); socket = null }
}
