package com.streamcap.mobile.danmaku

/** Monetary amounts retain the platform's native units; quantity lives in attributes. */
internal object PaidEventFactory {
    fun event(context: DanmakuConnectContext, platform: String, rawType: String,
              elapsedMs: Long, text: String, userId: String? = null, userName: String? = null,
              id: String? = null, type: DanmakuEventType = DanmakuEventType.GIFT,
              amount: Long? = null, currency: String? = null, sourceTimeMs: Long? = null,
              attributes: Map<String, String> = emptyMap()): DanmakuEvent =
        ChatEventFactory.event(context, platform, text, id?.let { "$rawType:$it" }, userId, userName,
            rawType, sourceTimeMs, elapsedMs).event.copy(
            eventType = type, amount = amount?.takeIf { it >= 0 }, currency = currency,
            attributes = attributes, parserVersion = "$platform-paid-1",
        )

    fun time(value: Long?): Long? = value?.takeIf { it > 0 }?.let {
        if (it < 1_000_000_000_000L) it * 1000 else it
    }

    fun product(a: Long?, b: Long?): Long? = if (a == null || b == null || a < 0 || b < 0) null
        else runCatching { Math.multiplyExact(a, b) }.getOrNull()
}
