package com.streamcap.mobile.danmaku

import kotlinx.coroutines.flow.Flow

enum class DanmakuEventType {
    CHAT, EMOTE, GIFT, SUPER_CHAT, GUARD, LIKE, FOLLOW, SHARE, SYSTEM, UNKNOWN;

    companion object {
        fun fromRawType(rawType: String?): DanmakuEventType {
            val value = rawType.orEmpty().lowercase()
            return when {
                value.contains("gift") || value.contains("礼物") -> GIFT
                value.contains("super") || value.contains("醒目") || value.contains("sc") -> SUPER_CHAT
                value.contains("guard") || value.contains("舰") || value.contains("守护") -> GUARD
                value.contains("like") || value.contains("点赞") -> LIKE
                value.contains("follow") || value.contains("关注") -> FOLLOW
                value.contains("share") || value.contains("分享") -> SHARE
                value.contains("system") || value.contains("系统") -> SYSTEM
                value.contains("emote") || value.contains("表情") -> EMOTE
                value.contains("chat") || value.contains("danmu") || value.contains("msg") -> CHAT
                else -> UNKNOWN
            }
        }
    }
}

data class DanmakuUser(
    val id: String? = null,
    val name: String? = null,
    val avatarUrl: String? = null,
    val level: Int? = null,
    val badgeName: String? = null,
    val badgeLevel: Int? = null,
    val guardLevel: Int? = null,
)

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
    val eventType: DanmakuEventType = DanmakuEventType.fromRawType(rawType),
    val user: DanmakuUser? = null,
    val amount: Long? = null,
    val currency: String? = null,
    val colorRgb: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
    val segmentId: String? = null,
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
