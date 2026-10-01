package com.streamcap.mobile.ui.recordings

import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.ui.common.activeRecordingStatuses
import com.streamcap.mobile.ui.common.formatDateTime
import java.util.Date

enum class RecordingStatusFilter(val label: String) {
    ALL("全部"), RECORDING("录制中"), SAVED("已保存"), FAILED("录制错误"), INTERRUPTED("有中断"),
}

data class RecordingFilters(
    val status: RecordingStatusFilter = RecordingStatusFilter.ALL,
    val platform: String? = null,
    val roomName: String? = null,
    val startInclusive: Long? = null,
    val endExclusive: Long? = null,
    val format: String? = null,
    val query: String = "",
) {
    val activeCount: Int
        get() = listOf(
            status != RecordingStatusFilter.ALL, platform != null, roomName != null,
            startInclusive != null || endExclusive != null, format != null,
        ).count { it }

    val hasConditions: Boolean
        get() = activeCount > 0 || query.isNotBlank()

    fun matches(recording: RecordingSession, platformName: String, interrupted: Boolean): Boolean {
        val time = recording.startedAt
        val saved = (recording.status == TaskStatus.COMPLETED || recording.status == TaskStatus.PAUSED) &&
            (recording.outputUri != null || recording.outputUris.isNotEmpty())
        val statusMatches = when (status) {
            RecordingStatusFilter.ALL -> true
            RecordingStatusFilter.RECORDING -> recording.status in activeRecordingStatuses
            RecordingStatusFilter.SAVED -> saved
            RecordingStatusFilter.FAILED -> recording.status == TaskStatus.FAILED
            RecordingStatusFilter.INTERRUPTED -> interrupted
        }
        val normalizedQuery = query.trim()
        val searchMatches = normalizedQuery.isEmpty() || listOfNotNull(
            recording.roomName, recording.roomTitle, platformName, time?.let(::formatDateTime),
        ).any { it.contains(normalizedQuery, ignoreCase = true) }

        return statusMatches &&
            (platform == null || platformName == platform) &&
            (roomName == null || recording.roomName == roomName) &&
            (startInclusive == null || (time != null && time >= startInclusive)) &&
            (endExclusive == null || (time != null && time < endExclusive)) &&
            (format == null || recording.containerFormat.equals(format, ignoreCase = true)) &&
            searchMatches
    }
}
