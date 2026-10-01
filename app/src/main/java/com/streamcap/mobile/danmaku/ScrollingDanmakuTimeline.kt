package com.streamcap.mobile.danmaku

import kotlin.math.ceil
import kotlin.random.Random

data class TimedBullet(val text: String, val timeMs: Long, val width: Float)
data class ScrollingBullet(val text: String, val startMs: Long, val endMs: Long, val lane: Int, val width: Float)

/** Equal speed across lanes prevents a short trailing message catching a long one. */
class ScrollingDanmakuTimeline(
    events: List<TimedBullet>,
    val viewportWidth: Float,
    laneCount: Int,
    gap: Float = 32f,
    travelMs: Long = 6_500,
) {
    val speed = viewportWidth.coerceAtLeast(1f) / travelMs.coerceAtLeast(1)
    val bullets: List<ScrollingBullet>
    private val maxLifetime: Long

    init {
        val nextFree = LongArray(laneCount.coerceIn(1, 12))
        // Stable randomness preserves the same layout after seeking or reopening.
        val random = Random(0x4354)
        var previousLane = -1
        bullets = buildList {
            events.filter { it.timeMs >= 0 && it.width.isFinite() && it.width > 0 }.sortedBy { it.timeMs }.forEach { event ->
                val available = nextFree.indices.filter { nextFree[it] <= event.timeMs }
                val candidates = available.filter { it != previousLane }.ifEmpty { available }
                val lane = if (candidates.isNotEmpty()) candidates[random.nextInt(candidates.size)]
                    else nextFree.indices.minBy { nextFree[it] }
                val start = maxOf(event.timeMs, nextFree[lane])
                // Display congestion may skip a cue; original JSONL remains intact.
                if (start - event.timeMs > 1_200) return@forEach
                val width = event.width.coerceAtMost(viewportWidth * 1.5f)
                val duration = ceil((viewportWidth + width) / speed).toLong()
                add(ScrollingBullet(event.text, start, start + duration, lane, width))
                previousLane = lane
                nextFree[lane] = start + ceil((width + gap.coerceAtLeast(0f)) / speed).toLong()
            }
        }.sortedBy { it.startMs }
        maxLifetime = bullets.maxOfOrNull { it.endMs - it.startMs } ?: 0
    }

    fun x(bullet: ScrollingBullet, positionMs: Long) = viewportWidth - (positionMs - bullet.startMs) * speed

    fun visibleAt(positionMs: Long): List<ScrollingBullet> {
        val earliest = positionMs - maxLifetime
        var low = 0
        var high = bullets.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (bullets[mid].startMs < earliest) low = mid + 1 else high = mid
        }
        return buildList {
            for (index in low until bullets.size) {
                val cue = bullets[index]
                if (cue.startMs > positionMs) break
                if (positionMs < cue.endMs) add(cue)
            }
        }
    }
}
