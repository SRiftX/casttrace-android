package com.streamcap.mobile.ui.home

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.TaskStatus

internal enum class RoomCardLayout { WIDE, STACKED }

internal fun roomCardLayout(widthDp: Int, heightDp: Int): RoomCardLayout =
    if (widthDp >= 900 && widthDp > heightDp) RoomCardLayout.WIDE else RoomCardLayout.STACKED

internal fun roomCardLayout(widthDp: Int, isLandscape: Boolean): RoomCardLayout =
    if (widthDp >= 900 && isLandscape) RoomCardLayout.WIDE else RoomCardLayout.STACKED

internal fun isEligibleForAutomaticMonitoring(room: LiveRoom, globalEnabled: Boolean): Boolean =
    globalEnabled && room.enabled && room.scheduledMonitoringEnabled && room.taskStatus != TaskStatus.PAUSED

internal fun eligibleAutomaticMonitoringCount(rooms: List<LiveRoom>, globalEnabled: Boolean): Int =
    if (!globalEnabled) 0 else rooms.count {
        it.enabled && it.scheduledMonitoringEnabled && it.taskStatus != TaskStatus.PAUSED
    }

internal fun pausedAutomaticMonitoringCount(rooms: List<LiveRoom>, globalEnabled: Boolean): Int =
    if (!globalEnabled) 0 else rooms.count {
        it.enabled && it.scheduledMonitoringEnabled && it.taskStatus == TaskStatus.PAUSED
    }

internal fun automaticMonitoringSummary(
    enabled: Boolean,
    backgroundEnabled: Boolean,
    monitoredCount: Int,
    pausedCount: Int,
    unmonitoredCount: Int,
): String = when {
    !enabled -> "已关闭"
    !backgroundEnabled && monitoredCount == 0 && pausedCount > 0 -> "已开启 · $pausedCount 间已暂停；需单独重新开启"
    !backgroundEnabled -> "仅打开应用时检查"
    monitoredCount == 0 && pausedCount > 0 -> "已开启 · $pausedCount 间已暂停；需单独重新开启"
    monitoredCount == 0 -> "已开启，但没有符合条件的直播间"
    else -> "满足条件 $monitoredCount 间 · 未纳入 $unmonitoredCount 间"
}

internal fun automaticMonitoringStatus(
    room: LiveRoom,
    globalEnabled: Boolean,
    backgroundEnabled: Boolean,
): String = when {
    !globalEnabled -> "全局自动录制已关闭"
    !room.enabled -> "直播间已停用"
    !room.scheduledMonitoringEnabled -> "此房间未开启自动录制"
    room.taskStatus == TaskStatus.PAUSED -> "此房间已暂停"
    backgroundEnabled -> "应用后台运行时检查"
    else -> "仅应用打开时检查"
}

internal fun isRoomCheckInProgress(room: LiveRoom): Boolean =
    room.taskStatus == TaskStatus.CHECKING
