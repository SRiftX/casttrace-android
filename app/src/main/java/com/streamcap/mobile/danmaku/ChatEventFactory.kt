package com.streamcap.mobile.danmaku

import android.os.SystemClock
import java.util.UUID

internal object ChatEventFactory {
    private val epoch = UUID.randomUUID().toString()
    fun event(context: DanmakuConnectContext, platform: String, text: String, id: String? = null,
              userId: String? = null, userName: String? = null, rawType: String = "chat",
              sourceTimeMs: Long? = null, elapsedMs: Long = SystemClock.elapsedRealtime()): DanmakuFrame.Event =
        DanmakuFrame.Event(DanmakuEvent(
            sessionId = context.sessionId, platform = platform, eventId = id?.takeIf(String::isNotBlank),
            receivedElapsedMs = elapsedMs, receivedWallMs = System.currentTimeMillis(), clockEpochId = epoch,
            sourceTimeMs = sourceTimeMs, text = text.take(16_384),
            userId = userId?.takeIf(String::isNotBlank), userName = userName?.takeIf(String::isNotBlank),
            user = DanmakuUser(userId?.takeIf(String::isNotBlank), userName?.takeIf(String::isNotBlank)),
            rawType = rawType, eventType = DanmakuEventType.CHAT, parserVersion = "$platform-websocket-1",
        ))
}
