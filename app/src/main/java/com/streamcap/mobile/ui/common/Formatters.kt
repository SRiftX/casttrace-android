package com.streamcap.mobile.ui.common

import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun TaskStatus.displayName(): String = when (this) {
    TaskStatus.DISABLED -> "已停用"
    TaskStatus.WAITING -> "等待开播"
    TaskStatus.CHECKING -> "检查中"
    TaskStatus.STARTING -> "正在启动"
    TaskStatus.RECORDING -> "录制中"
    TaskStatus.RECONNECTING -> "重连中"
    TaskStatus.FINALIZING -> "正在完成文件"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "异常"
}

fun LiveStatus.displayName(): String = when (this) {
    LiveStatus.UNKNOWN -> "未知"
    LiveStatus.OFFLINE -> "未开播"
    LiveStatus.LIVE -> "直播中"
    LiveStatus.CHECKING -> "检查中"
    LiveStatus.ERROR -> "检查失败"
}

fun formatDateTime(value: Long?): String = value?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(it))
} ?: "尚未检查"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(
    seconds / 3_600,
    (seconds % 3_600) / 60,
    seconds % 60,
)
