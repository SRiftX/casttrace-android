package com.streamcap.mobile.ui.home

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMonitoringStatusTest {
    private fun room(
        enabled: Boolean = true,
        scheduled: Boolean = true,
        status: TaskStatus = TaskStatus.WAITING,
    ) = LiveRoom(
        id = 1, originalUrl = "https://example.com/live", normalizedUrl = "https://example.com/live",
        platform = "Test", anchorName = "主播", roomTitle = null, enabled = enabled, autoRecord = scheduled,
        quality = "原画", mediaType = "视频", outputFormat = "MP4", flvDirectDownload = false,
        storageDirectoryUri = null, segmentEnabled = true, segmentSeconds = 1800,
        scheduledMonitoringEnabled = scheduled, notificationsEnabled = true, checkIntervalSeconds = 60,
        liveStatus = LiveStatus.UNKNOWN, taskStatus = status, lastCheckAt = null, createdAt = 0, updatedAt = 0,
    )

    @Test
    fun `automatic monitoring count requires global room and non-paused settings`() {
        val rooms = listOf(room(), room(enabled = false), room(scheduled = false), room(status = TaskStatus.PAUSED))

        assertEquals(1, eligibleAutomaticMonitoringCount(rooms, globalEnabled = true))
        assertEquals(0, eligibleAutomaticMonitoringCount(rooms, globalEnabled = false))
    }

    @Test
    fun `room status explains why automatic monitoring is unavailable`() {
        assertEquals("全局自动录制已关闭", automaticMonitoringStatus(room(), false, true))
        assertEquals("直播间已停用", automaticMonitoringStatus(room(enabled = false), true, true))
        assertEquals("此房间未开启自动录制", automaticMonitoringStatus(room(scheduled = false), true, true))
        assertEquals("此房间已暂停", automaticMonitoringStatus(room(status = TaskStatus.PAUSED), true, true))
        assertEquals("应用后台运行时检查", automaticMonitoringStatus(room(), true, true))
        assertEquals("仅应用打开时检查", automaticMonitoringStatus(room(), true, false))
    }

    @Test
    fun `automatic monitoring summary explains paused rooms`() {
        assertEquals("已开启 · 3 间已暂停；开启时会自动恢复", automaticMonitoringSummary(
            enabled = true, backgroundEnabled = true, monitoredCount = 0, pausedCount = 3, unmonitoredCount = 3,
        ))
        assertEquals("仅打开应用时检查", automaticMonitoringSummary(
            enabled = true, backgroundEnabled = false, monitoredCount = 1, pausedCount = 0, unmonitoredCount = 2,
        ))
        assertEquals("已关闭", automaticMonitoringSummary(
            enabled = false, backgroundEnabled = true, monitoredCount = 0, pausedCount = 3, unmonitoredCount = 3,
        ))
    }

    @Test
    fun `checking state is exposed for disabling repeated manual checks`() {
        assertTrue(isRoomCheckInProgress(room(status = TaskStatus.CHECKING)))
        assertFalse(isRoomCheckInProgress(room(status = TaskStatus.WAITING)))
    }

    @Test
    fun portraitCardUsesStackedLayoutOnNarrowWidth() {
        assertEquals(RoomCardLayout.STACKED, roomCardLayout(600, 900))
        assertEquals(RoomCardLayout.STACKED, roomCardLayout(1080, 1920))
        assertEquals(RoomCardLayout.WIDE, roomCardLayout(1200, 700))
        assertEquals(RoomCardLayout.WIDE, roomCardLayout(1200, true))
        assertEquals(RoomCardLayout.STACKED, roomCardLayout(1200, false))
    }

    @Test
    fun automaticRoomSelectionRequiresBothGlobalAndRoomFlags() {
        assertTrue(isEligibleForAutomaticMonitoring(room(), globalEnabled = true))
        assertFalse(isEligibleForAutomaticMonitoring(room(scheduled = false), globalEnabled = true))
        assertFalse(isEligibleForAutomaticMonitoring(room(), globalEnabled = false))
    }
}


