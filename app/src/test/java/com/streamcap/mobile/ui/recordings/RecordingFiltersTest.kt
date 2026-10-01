package com.streamcap.mobile.ui.recordings

import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class RecordingFiltersTest {
    private val saved = RecordingSession(
        id = 1, roomId = 10, roomName = "主播A", roomTitle = "周末游戏直播", status = TaskStatus.COMPLETED,
        startedAt = 1_700_000_000_000, endedAt = null, outputUri = "content://video", outputUris = listOf("content://video"),
        temporaryPath = null, containerFormat = "mp4", streamType = StreamType.HLS, totalBytes = 10,
        durationSeconds = 60, stopAtMillis = null, errorMessage = null,
    )

    @Test
    fun `filters combine status platform room date format and search`() {
        val filters = RecordingFilters(
            status = RecordingStatusFilter.SAVED, platform = "BetterGI", roomName = "主播A",
            startInclusive = 1_699_999_999_000, endExclusive = 1_700_000_001_000, format = "MP4",
            query = "周末游戏",
        )

        assertTrue(filters.matches(saved, "BetterGI", interrupted = false))
        assertFalse(filters.matches(saved, "其他平台", interrupted = false))
        assertFalse(filters.matches(saved.copy(roomName = "另一个主播"), "BetterGI", interrupted = false))
        assertFalse(filters.matches(saved.copy(startedAt = 1_700_000_001_000), "BetterGI", interrupted = false))
        assertFalse(filters.matches(saved.copy(containerFormat = "mkv"), "BetterGI", interrupted = false))
        assertFalse(filters.matches(saved.copy(roomTitle = "其他"), "BetterGI", interrupted = false))
    }

    @Test
    fun `search covers room name title platform and localized date text`() {
        assertTrue(RecordingFilters(query = "主播A").matches(saved, "BetterGI", false))
        assertTrue(RecordingFilters(query = "Better").matches(saved, "BetterGI", false))
        assertTrue(RecordingFilters(query = "周末游戏").matches(saved, "BetterGI", false))
        val dateText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(saved.startedAt!!))
        assertTrue(RecordingFilters(query = dateText.take(4)).matches(saved, "BetterGI", false))
        assertFalse(RecordingFilters(query = "不存在").matches(saved, "BetterGI", false))
    }

    @Test
    fun `active filter count tracks selected dimensions but not search or sort`() {
        val filters = RecordingFilters(
            status = RecordingStatusFilter.SAVED, platform = "BetterGI", roomName = "主播A",
            startInclusive = 1, endExclusive = 2, format = "MP4", query = "游戏",
        )
        assertTrue(filters.hasConditions)
        assertTrue(filters.activeCount == 5)
        assertFalse(RecordingFilters(query = "关键词").activeCount > 0)
        assertFalse(RecordingFilters().hasConditions)
    }
}
