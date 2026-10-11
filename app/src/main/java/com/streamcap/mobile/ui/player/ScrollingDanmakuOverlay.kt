package com.streamcap.mobile.ui.player

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuPresentation
import com.streamcap.mobile.danmaku.ScrollingDanmakuTimeline
import com.streamcap.mobile.danmaku.TimedBullet
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import kotlinx.coroutines.isActive
import kotlin.math.min

@Composable
internal fun ScrollingDanmakuOverlay(
    events: List<DanmakuEvent>,
    videoAspectRatio: Float,
    mediaPosition: () -> Long,
    appearance: DanmakuAppearance,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val aspectRatio = videoAspectRatio.takeIf { it.isFinite() && it > 0 } ?: 16f / 9f
    val contentWidth = min(viewport.width.toFloat(), viewport.height * aspectRatio)
    val contentHeight = contentWidth / aspectRatio
    val textSize = with(density) { appearance.fontSizeSp.sp.toPx() }
    val paint = remember(textSize) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    }
    val inset = with(density) { 12.dp.toPx() }
    val lineHeight = (paint.fontMetrics.bottom - paint.fontMetrics.top) + with(density) { 8.dp.toPx() }
    val availableHeight = (contentHeight * appearance.area - inset).coerceAtLeast(lineHeight)
    val lanes = (availableHeight / lineHeight).toInt().coerceAtLeast(1).coerceAtMost(appearance.laneLimit)
    val laneSpacing = lineHeight
    val timeline = remember(events, contentWidth, lanes, paint, appearance) {
        ScrollingDanmakuTimeline(events.mapNotNull { event ->
            val time = event.mediaTimeMs ?: return@mapNotNull null
            if (appearance.density < 1f && (event.eventId ?: "${event.receivedElapsedMs}:${event.text}").hashCode().toUInt().toLong() % 10_000 >= (appearance.density * 10_000).toLong()) return@mapNotNull null
            val singleLine = DanmakuPresentation.text(event, appearance) ?: return@mapNotNull null
            val text = TextUtils.ellipsize(singleLine, paint, contentWidth * 1.5f, TextUtils.TruncateAt.END).toString()
            TimedBullet(text, time, paint.measureText(text))
        }, contentWidth.coerceAtLeast(1f), lanes, with(density) { 24.dp.toPx() },
            travelMs = (6500 / appearance.speed).toLong())
    }
    val readPosition by rememberUpdatedState(mediaPosition)
    val position = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { position.longValue = readPosition() }
        }
    }
    Canvas(modifier.onSizeChanged { viewport = it }
        .semantics { contentDescription = "滚动弹幕" }) {
        val left = (size.width - contentWidth) / 2f
        val top = (size.height - contentHeight) / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipRect(left, top, left + contentWidth, top + contentHeight)
            timeline.visibleAt(position.longValue).forEach { bullet ->
                val x = left + timeline.x(bullet, position.longValue)
                val y = top + inset + bullet.lane * laneSpacing - paint.fontMetrics.top
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = with(density) { 1.5.dp.toPx() }
                paint.color = android.graphics.Color.argb((210 * appearance.opacity).toInt(), 0, 0, 0)
                native.drawText(bullet.text, x, y, paint)
                paint.style = Paint.Style.FILL
                paint.color = ((255 * appearance.opacity).toInt() shl 24) or appearance.colorRgb
                native.drawText(bullet.text, x, y, paint)
            }
            native.restore()
        }
    }
}
