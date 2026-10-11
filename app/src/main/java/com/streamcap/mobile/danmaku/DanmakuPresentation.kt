package com.streamcap.mobile.danmaku

import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.data.preferences.DanmakuUserPosition

object DanmakuPresentation {
    fun text(event: DanmakuEvent, appearance: DanmakuAppearance): String? {
        if (event.eventType == DanmakuEventType.SYSTEM) return null
        if (event.eventType in setOf(DanmakuEventType.CHAT, DanmakuEventType.EMOTE, DanmakuEventType.UNKNOWN) && !appearance.showChat) return null
        if (event.eventType == DanmakuEventType.GIFT && !appearance.showGift) return null
        if (event.eventType == DanmakuEventType.SUPER_CHAT && !appearance.showSuperChat) return null
        if (event.eventType in setOf(DanmakuEventType.FOLLOW, DanmakuEventType.SHARE, DanmakuEventType.LIKE) && !appearance.showFollow) return null
        val body = event.text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        if (body.isEmpty()) return null
        val limit = appearance.maxCharacters
        val count = body.codePointCount(0, body.length)
        val clipped = if (count > limit) body.substring(0, body.offsetByCodePoints(0, limit - 1)) + "…" else body
        val name = (event.user?.name ?: event.userName).orEmpty().trim().take(24)
        val position = if (appearance.showUserName) appearance.userPosition else DanmakuUserPosition.HIDDEN
        val tag = if (appearance.showEventTag) when (event.eventType) {
            DanmakuEventType.GIFT -> "[礼物] "
            DanmakuEventType.SUPER_CHAT -> "[SC] "
            DanmakuEventType.GUARD -> "[守护] "
            DanmakuEventType.FOLLOW -> "[关注] "
            else -> ""
        } else ""
        return tag + when (position) {
            DanmakuUserPosition.PREFIX -> if (name.isNotEmpty()) "$name：$clipped" else clipped
            DanmakuUserPosition.SUFFIX -> if (name.isNotEmpty()) "$clipped · $name" else clipped
            DanmakuUserPosition.HIDDEN -> clipped
        }
    }
}
