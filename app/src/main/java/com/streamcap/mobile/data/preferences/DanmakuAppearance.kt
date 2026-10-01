package com.streamcap.mobile.data.preferences

data class DanmakuAppearance(
    val fontSizeSp: Float = 16f,
    val opacity: Float = .85f,
    val speed: Float = 1f,
    val colorRgb: Int = 0xFFFFFF,
    val lanes: Int = 12,
    val area: Float = .65f,
    val autoLanes: Boolean = true,
) {
    val laneLimit: Int get() = if (autoLanes) 12 else lanes
    fun normalized() = copy(
        fontSizeSp = fontSizeSp.takeIf { it.isFinite() }?.coerceIn(12f, 32f) ?: 16f,
        opacity = opacity.takeIf { it.isFinite() }?.coerceIn(.2f, 1f) ?: .85f,
        speed = speed.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f,
        colorRgb = colorRgb and 0xFFFFFF,
        lanes = lanes.coerceIn(1, 12),
        area = area.takeIf { it.isFinite() }?.coerceIn(.25f, 1f) ?: .65f,
    )
}
