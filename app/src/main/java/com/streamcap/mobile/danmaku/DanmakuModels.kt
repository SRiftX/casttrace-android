package com.streamcap.mobile.danmaku

import kotlinx.coroutines.flow.Flow

data class DanmakuEvent(
    val sessionId: Long,
    val platform: String,
    val eventId: String?,
    val receivedElapsedMs: Long,
    val receivedWallMs: Long,
    val clockEpochId: String,
    val sourceTimeMs: Long? = null,
    val mediaTimeMs: Long? = null,
    val timeQuality: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val text: String,
    val rawType: String = "chat",
    val parserVersion: String = "simulated-1",
)

sealed interface DanmakuFrame {
    data class Event(val event: DanmakuEvent) : DanmakuFrame
    data class Status(val state: String) : DanmakuFrame
}

data class DanmakuConnectContext(
    val sessionId: Long,
    val platform: String,
    val roomUrl: String,
    val cookie: String? = null,
    val mediaStartedElapsedMs: Long? = null,
)

interface DanmakuClient {
    suspend fun connect(context: DanmakuConnectContext): Flow<DanmakuFrame>
    suspend fun close(reason: String)
}

class FakeDanmakuClient(private val texts: List<String>) : DanmakuClient {
    override suspend fun connect(context: DanmakuConnectContext): Flow<DanmakuFrame> = kotlinx.coroutines.flow.flow {
        emit(DanmakuFrame.Status("connected-simulation"))
        texts.forEachIndexed { index, text ->
            emit(DanmakuFrame.Event(DanmakuEvent(
                sessionId = context.sessionId, platform = context.platform, eventId = "fake-" + index,
                receivedElapsedMs = android.os.SystemClock.elapsedRealtime(), receivedWallMs = System.currentTimeMillis(),
                clockEpochId = "test", userName = "模拟用户", text = text,
            )))
        }
    }
    override suspend fun close(reason: String) = Unit
}
