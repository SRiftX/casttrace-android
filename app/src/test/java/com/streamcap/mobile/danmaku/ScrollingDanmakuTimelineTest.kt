package com.streamcap.mobile.danmaku

import org.junit.Assert.*
import org.junit.Test

class ScrollingDanmakuTimelineTest {
    @Test fun scrollsRightToLeftWithPlaybackPositionAndCanSeekBack() {
        val timeline = ScrollingDanmakuTimeline(listOf(TimedBullet("chat", 1000, 300f)), 1000f, 3)
        val bullet = timeline.bullets.single()
        assertEquals(1000f, timeline.x(bullet, 1000), .01f)
        assertTrue(timeline.x(bullet, 4000) < timeline.x(bullet, 2000))
        assertTrue(timeline.visibleAt(999).isEmpty())
        assertEquals(listOf(bullet), timeline.visibleAt(4000))
        assertTrue(timeline.visibleAt(bullet.endMs).isEmpty())
        assertEquals(listOf(bullet), timeline.visibleAt(4000))
    }

    @Test fun avoidsLaneCollisionsAndKeepsDisplayCongestionBounded() {
        val events = (0..30).map { TimedBullet("message $it", it * 100L, if (it % 2 == 0) 800f else 100f) }
        val timeline = ScrollingDanmakuTimeline(events, 1000f, 3)
        assertTrue(timeline.bullets.size < events.size)
        timeline.bullets.groupBy { it.lane }.values.forEach { lane ->
            lane.zipWithNext().forEach { (first, second) ->
                assertTrue(timeline.x(first, second.startMs) + first.width + 32f <= 1000.1f)
            }
        }
    }

    @Test fun pauseAndSpeedUseMediaTimeWithoutSeparateAnimationClock() {
        val timeline = ScrollingDanmakuTimeline(listOf(TimedBullet("chat", 0, 500f)), 1000f, 2)
        val bullet = timeline.bullets.single()
        assertEquals(timeline.x(bullet, 2000), timeline.x(bullet, 2000), 0f)
        assertEquals(2f * (timeline.x(bullet, 0) - timeline.x(bullet, 1000)),
            timeline.x(bullet, 0) - timeline.x(bullet, 2000), .01f)
    }

    @Test fun sparseChatUsesRandomDistinctLanesAndSeeksKeepTheLayoutStable() {
        val events = (0..30).map { TimedBullet("chat $it", it * 10_000L, 100f) }
        val first = ScrollingDanmakuTimeline(events, 1000f, 4)
        val second = ScrollingDanmakuTimeline(events, 1000f, 4)
        assertEquals(first.bullets, second.bullets)
        assertEquals(4, first.bullets.map { it.lane }.toSet().size)
        first.bullets.zipWithNext().forEach { (a, b) -> assertNotEquals(a.lane, b.lane) }
    }

    @Test fun denseChatUsesMoreThanThreeRowsWithoutOverlapping() {
        val timeline = ScrollingDanmakuTimeline((0..150).map { TimedBullet("chat $it", it * 100L, 250f) }, 1000f, 12)
        assertEquals(12, timeline.bullets.map { it.lane }.toSet().size)
        assertTrue(timeline.visibleAt(6000).map { it.lane }.toSet().size > 3)
        timeline.bullets.groupBy { it.lane }.values.forEach { lane ->
            lane.zipWithNext().forEach { (a, b) -> assertTrue(timeline.x(a, b.startMs) + a.width + 32 <= 1000.1f) }
        }
    }
}
