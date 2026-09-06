package com.streamcap.mobile.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.ui.common.displayName
import com.streamcap.mobile.ui.common.formatDateTime
import com.streamcap.mobile.ui.theme.LiveTeal
import com.streamcap.mobile.ui.theme.RecordingRed
import com.streamcap.mobile.ui.theme.WarningAmber
import com.streamcap.mobile.service.RecordingService

private enum class RoomFilter(val label: String) {
    ALL("全部"),
    RECORDING("录制中"),
    LIVE("直播中"),
    OFFLINE("未开播"),
    FAILED("录制错误"),
    UNMONITORED("未监控"),
}

@Composable
fun HomeScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onAddRoom: () -> Unit,
    onOpenRoom: (Long) -> Unit,
    onOpenRecording: (Long) -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(
        factory = ViewModelFactory { HomeViewModel(container.repository) },
    )
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val recordingCount = rooms.count { it.taskStatus == TaskStatus.RECORDING }
    val enabledCount = rooms.count { it.enabled }
    val failedCount = rooms.count { it.taskStatus == TaskStatus.FAILED }
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf(RoomFilter.ALL) }
    val filteredRooms = rooms.filter { room ->
        when (selectedFilter) {
            RoomFilter.ALL -> true
            RoomFilter.RECORDING -> room.taskStatus in setOf(
                TaskStatus.STARTING,
                TaskStatus.RECORDING,
                TaskStatus.RECONNECTING,
                TaskStatus.FINALIZING,
            )
            RoomFilter.LIVE -> room.liveStatus == LiveStatus.LIVE && room.taskStatus != TaskStatus.RECORDING
            RoomFilter.OFFLINE -> room.liveStatus == LiveStatus.OFFLINE
            RoomFilter.FAILED -> room.taskStatus == TaskStatus.FAILED || room.liveStatus == LiveStatus.ERROR
            RoomFilter.UNMONITORED -> !room.enabled || !room.scheduledMonitoringEnabled
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CastTrace", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            if (recordingCount > 0) "$recordingCount 个直播间正在写入" else "直播录制控制台",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (recordingCount > 0) RecordingRed else LiveTeal, CircleShape),
                    )
                }
            }

            item {
                MetricsBand(
                    recording = recordingCount,
                    monitoring = enabledCount,
                    completed = recordings.count { it.status == TaskStatus.COMPLETED },
                    failed = failedCount,
                )
            }

            item {
                StatusFilterBar(
                    selected = selectedFilter,
                    onSelected = { selectedFilter = it },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("直播间", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${filteredRooms.size} / ${rooms.size} 项", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (rooms.isEmpty()) {
                item { EmptyRooms(onAddRoom) }
            } else if (filteredRooms.isEmpty()) {
                item {
                    Text(
                        "当前筛选下没有直播间",
                        modifier = Modifier.padding(vertical = 36.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(filteredRooms, key = { it.id }) { room ->
                    RoomTaskRow(
                        room = room,
                        activeRecording = recordings.firstOrNull { it.roomId == room.id && it.totalBytes > 0 &&
                            (it.outputUri != null || it.outputUris.isNotEmpty() || it.temporaryPath != null) },
                        onOpen = { onOpenRoom(room.id) },
                        onOpenRecording = onOpenRecording,
                        onRefresh = {
                            viewModel.refresh(room.id) { status ->
                                if (status == LiveStatus.LIVE && room.scheduledMonitoringEnabled) {
                                    RecordingService.start(context, room.id)
                                }
                            }
                        },
                        onPause = { context.startService(RecordingService.pauseIntent(context, room.id)) },
                        onResume = { RecordingService.start(context, room.id) },
                        onAutoRecordChange = { enabled ->
                            viewModel.setScheduledMonitoringEnabled(room.id, enabled) {
                                if (enabled) RecordingService.monitor(context)
                                else context.startService(RecordingService.stopIntent(context, room.id))
                            }
                        },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onAddRoom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "添加直播间")
        }
    }

}

@Composable
private fun StatusFilterBar(selected: RoomFilter, onSelected: (RoomFilter) -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("状态筛选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 4.dp),
        ) {
            items(RoomFilter.entries) { filter ->
                FilterChip(
                    selected = selected == filter,
                    onClick = { onSelected(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
    }
}

@Composable
private fun MetricsBand(recording: Int, monitoring: Int, completed: Int, failed: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Metric("录制", recording, RecordingRed)
            MetricDivider()
            Metric("监控", monitoring, LiveTeal)
            MetricDivider()
            Metric("完成", completed, MaterialTheme.colorScheme.onSurface)
            MetricDivider()
            Metric("异常", failed, WarningAmber)
        }
    }
}

@Composable
private fun RowScope.Metric(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(32.dp)
            .width(1.dp),
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun EmptyRooms(onAddRoom: () -> Unit) {
    Surface(
        onClick = onAddRoom,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("还没有直播间", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "添加一个直播地址，开始检查开播状态。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RoomTaskRow(
    room: LiveRoom,
    activeRecording: RecordingSession?,
    onOpen: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    onRefresh: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onAutoRecordChange: (Boolean) -> Unit,
) {
    val statusColor = when {
        room.liveStatus == com.streamcap.mobile.domain.model.LiveStatus.LIVE && room.taskStatus != TaskStatus.RECORDING -> LiveTeal
        room.taskStatus == TaskStatus.RECORDING -> RecordingRed
        room.taskStatus == TaskStatus.PAUSED -> WarningAmber
        room.taskStatus == TaskStatus.FAILED -> WarningAmber
        room.taskStatus == TaskStatus.CHECKING || room.taskStatus == TaskStatus.STARTING || room.taskStatus == TaskStatus.RECONNECTING -> LiveTeal
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        room.taskStatus == TaskStatus.STARTING -> "正在启动录制"
        room.taskStatus == TaskStatus.RECONNECTING -> "等待重连"
        room.taskStatus == TaskStatus.FINALIZING -> "正在保存录制"
        room.taskStatus == TaskStatus.RECORDING -> "录制中"
        room.taskStatus == TaskStatus.PAUSED -> "已暂停"
        room.taskStatus == TaskStatus.FAILED && room.liveStatus == com.streamcap.mobile.domain.model.LiveStatus.LIVE -> "直播中 · 录制异常"
        room.liveStatus == com.streamcap.mobile.domain.model.LiveStatus.LIVE -> "直播中"
        room.liveStatus == com.streamcap.mobile.domain.model.LiveStatus.OFFLINE -> "未开播"
        room.taskStatus == TaskStatus.CHECKING -> "检查中"
        room.taskStatus == TaskStatus.DISABLED -> "已停用"
        else -> room.taskStatus.displayName()
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (room.roomTitle.isNullOrBlank()) 104.dp else 128.dp),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(statusColor),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onOpen)
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            room.anchorName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    room.roomTitle?.takeIf(String::isNotBlank)?.let { title ->
                        Text(title, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        "${room.platform} · ${room.quality}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "上次检查 ${formatDateTime(room.lastCheckAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = room.taskStatus != TaskStatus.CHECKING) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "检查直播状态")
                }
                if (activeRecording != null) {
                    IconButton(onClick = { onOpenRecording(activeRecording.id) }) {
                        Icon(Icons.Outlined.PlayCircle, contentDescription = "预览当前录制")
                    }
                }
                when {
                    room.taskStatus in setOf(TaskStatus.RECORDING, TaskStatus.STARTING, TaskStatus.RECONNECTING) -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Outlined.Pause, contentDescription = "暂停录制")
                        }
                    }
                    room.taskStatus == TaskStatus.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "继续录制")
                        }
                    }
                }
                Switch(
                    checked = room.scheduledMonitoringEnabled,
                    onCheckedChange = onAutoRecordChange,
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}
