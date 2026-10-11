package com.streamcap.mobile.data.preferences

enum class DanmakuUserPosition { PREFIX, SUFFIX, HIDDEN }

data class DanmakuAppearance(
    val fontSizeSp: Float = 16f,
    val opacity: Float = .85f,
    val speed: Float = 1f,
    val colorRgb: Int = 0xFFFFFF,
    val lanes: Int = 12,
    val area: Float = .65f,
    val autoLanes: Boolean = true,
    val showUserName: Boolean = true,
    val showEventTag: Boolean = true,
    val maxCharacters: Int = 70,
    val userPosition: DanmakuUserPosition = DanmakuUserPosition.PREFIX,
    val showChat: Boolean = true,
    val showGift: Boolean = true,
    val showSuperChat: Boolean = true,
    val showFollow: Boolean = true,
    val density: Float = 1f,
    val timeOffsetMs: Long = 0L,
) {
    val laneLimit: Int get() = if (autoLanes) 12 else lanes
    fun normalized() = copy(
        fontSizeSp = fontSizeSp.takeIf { it.isFinite() }?.coerceIn(12f, 32f) ?: 16f,
        opacity = opacity.takeIf { it.isFinite() }?.coerceIn(.2f, 1f) ?: .85f,
        speed = speed.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
        colorRgb = colorRgb and 0xFFFFFF,
        lanes = lanes.coerceIn(1, 12),
        area = area.takeIf { it.isFinite() }?.coerceIn(.25f, 1f) ?: .65f,
        maxCharacters = maxCharacters.coerceIn(20, 120),
        density = density.takeIf { it.isFinite() }?.coerceIn(.25f, 1f) ?: 1f,
        timeOffsetMs = timeOffsetMs.coerceIn(-30_000L, 30_000L),
    )
}
