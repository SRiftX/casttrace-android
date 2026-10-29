package com.streamcap.mobile.ui.home

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import com.streamcap.mobile.ui.common.*
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.streamcap.mobile.ui.common.MotionTokens
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.service.BackgroundMonitorStatus
import com.streamcap.mobile.service.BackgroundMonitorSnapshot
import com.streamcap.mobile.ui.settings.openBackgroundLaunchSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val settings by container.preferences.settings.collectAsStateWithLifecycle(com.streamcap.mobile.data.preferences.UserSettings())
    val recordingCount = rooms.count { it.taskStatus in activeRecordingStatuses }
    val monitoredCount = eligibleAutomaticMonitoringCount(rooms, settings.globalAutoRecordEnabled)
    val pausedMonitoredCount = pausedAutomaticMonitoringCount(rooms, settings.globalAutoRecordEnabled)
    val unmonitoredCount = if (settings.globalAutoRecordEnabled) {
        (rooms.size - monitoredCount).coerceAtLeast(0)
    } else {
        rooms.size
    }
    val failedCount = rooms.count { it.taskStatus == TaskStatus.FAILED }
    val context = LocalContext.current
    var monitorSnapshot by remember { mutableStateOf(BackgroundMonitorStatus.read(context)) }
    androidx.compose.runtime.LaunchedEffect(context) {
        while (true) {
            monitorSnapshot = BackgroundMonitorStatus.read(context)
            delay(15_000L)
        }
    }
    val scope = rememberCoroutineScope()
    var selectedFilter by rememberSaveable { mutableStateOf(RoomFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var controlsExpanded by rememberSaveable { mutableStateOf(false) }
    var compactRooms by rememberSaveable { mutableStateOf(false) }
    val recordAction = rememberRecordingAction()
    // Rank on first load / membership changes, never on per-second progress or status updates.
    val orderedIds = remember(rooms.map { it.id }) {
        rooms.sortedBy { if (it.taskStatus in activeRecordingStatuses) 0 else 1 }.map { it.id }
    }
    val filteredRooms = rooms.sortedBy { orderedIds.indexOf(it.id) }.filter { room ->
        val query = searchQuery.trim()
        val matchesSearch = query.isBlank() || room.anchorName.contains(query, ignoreCase = true) ||
            room.platform.contains(query, ignoreCase = true) ||
            room.roomTitle.orEmpty().contains(query, ignoreCase = true) ||
            room.originalUrl.contains(query, ignoreCase = true)
        if (!matchesSearch) return@filter false
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
            RoomFilter.UNMONITORED -> !settings.globalAutoRecordEnabled || !room.enabled ||
                !room.scheduledMonitoringEnabled || room.taskStatus == TaskStatus.PAUSED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1360.dp).fillMaxSize().align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CastTrace", style = MaterialTheme.typography.headlineMedium)
                        AnimatedContent(
                            targetState = recordingCount,
                            transitionSpec = { fadeIn(tween(MotionTokens.Fast)) togetherWith fadeOut(tween(MotionTokens.Fast)) },
                            label = "recordingSummary",
                        ) { count ->
                            Text(
                                if (count > 0) "$count 个直播间正在写入" else "直播录制控制台",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) searchQuery = "" }) {
                        Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                            if (searchVisible) "关闭搜索" else "搜索直播间")
                    }
                    IconButton(onClick = onAddRoom) { Icon(Icons.Outlined.Add, contentDescription = "添加直播间") }
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (recordingCount > 0) RecordingRed else LiveTeal, CircleShape),
                    )
                }
            }
            if (searchVisible) item {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("搜索主播、标题、平台或地址") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Outlined.Close, "清空搜索")
                    } })
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("录制控制", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { controlsExpanded = !controlsExpanded }) {
                        Icon(if (controlsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (controlsExpanded) "收起录制控制" else "展开录制控制")
                    }
                }
            }
            if (controlsExpanded) item {
                AutoRecordCard(
                    enabled = settings.globalAutoRecordEnabled,
                    backgroundEnabled = settings.backgroundMonitoringEnabled,
                    monitoredCount = monitoredCount,
                    unmonitoredCount = unmonitoredCount,
                    totalRooms = rooms.size,
                    recordingCount = recordingCount,
                    failedCount = failedCount,
                    pausedCount = pausedMonitoredCount,
                    monitorSnapshot = monitorSnapshot,
                    onOpenBackgroundSettings = { openBackgroundLaunchSettings(context) },
                    onEnabledChange = { value -> scope.launch {
                        container.preferences.setGlobalAutoRecordEnabled(value)
                        if (value) {
                            RecordingService.monitor(context)
                            viewModel.refreshMonitoredRooms()
                        } else {
                            RecordingService.monitor(context)
                        }
                    }
                    },
                    onBackgroundChange = { value -> scope.launch {
                        container.preferences.setBackgroundMonitoringEnabled(value)
                        RecordingService.monitor(context)
                    }
                    },
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
                    TextButton(onClick = { compactRooms = !compactRooms }) {
                        Text(if (compactRooms) "卡片视图" else "紧凑视图")
                    }
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
                    val latestError by viewModel.latestErrorForRoom(room.id).collectAsStateWithLifecycle(null)
                    Box(Modifier.fillMaxWidth().wrapContentHeight().align(Alignment.TopCenter), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 960.dp).wrapContentHeight()) {
                    val activeRecording = recordings.firstOrNull { it.roomId == room.id && it.totalBytes > 0 &&
                        (it.outputUri != null || it.outputUris.isNotEmpty() || it.temporaryPath != null) }
                    if (compactRooms) Surface(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = { onOpenRoom(room.id) }),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        shadowElevation = 1.dp,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(room.anchorName, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text("${room.platform} · ${room.roomTitle ?: room.liveStatus.displayName()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            Text(room.taskStatus.displayName(), style = MaterialTheme.typography.labelMedium,
                                color = if (room.taskStatus == TaskStatus.RECORDING) RecordingRed
                                    else MaterialTheme.colorScheme.primary)
                        }
                    } else RoomTaskRow(
                        room = room,
                        activeRecording = activeRecording,
                        autoRecordingStatus = automaticMonitoringStatus(
                            room, settings.globalAutoRecordEnabled, settings.backgroundMonitoringEnabled,
                        ),
                        latestErrorMessage = latestError?.message,
                        isChecking = isRoomCheckInProgress(room),
                        onRefresh = { viewModel.refresh(room.id) },
                        onOpen = { onOpenRoom(room.id) },
                        onOpenRecording = onOpenRecording,
                        onAutoToggle = { enabled ->
                            viewModel.setScheduledMonitoringEnabled(room.id, enabled) {
                                if (enabled) {
                                    RecordingService.monitorRoomNow(context, room.id)
                                }
                            }
                        },
                        onPause = { context.startService(RecordingService.pauseIntent(context, room.id)) },
                        onResume = { recordAction { RecordingService.start(context, room.id) } },

                    )
                    }
                    }
                }
            }
        }

    }

}

@Composable
private fun AutoRecordCard(
    enabled: Boolean,
    backgroundEnabled: Boolean,
    monitoredCount: Int,
    unmonitoredCount: Int,
    totalRooms: Int,
    recordingCount: Int,
    failedCount: Int,
    pausedCount: Int,
    monitorSnapshot: BackgroundMonitorSnapshot,
    onOpenBackgroundSettings: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        if (compact) Column(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("录制监控", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("监控 $monitoredCount/$totalRooms · 录制 $recordingCount" +
                    if (failedCount > 0) " · 异常 $failedCount" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Text(automaticMonitoringSummary(enabled, backgroundEnabled, monitoredCount, pausedCount, unmonitoredCount) + " · 仅对单独开启的房间生效",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("全局自动录制", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("后台检测", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = backgroundEnabled, onCheckedChange = onBackgroundChange)
            }
            if (enabled && backgroundEnabled && monitoredCount > 0 && !monitorSnapshot.isRunning())
                TextButton(onClick = onOpenBackgroundSettings) { Text("后台设置") }
        } else Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("全局自动录制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("自动 $monitoredCount/$totalRooms", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("录制 $recordingCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (failedCount > 0) Text("异常 $failedCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    automaticMonitoringSummary(enabled, backgroundEnabled, monitoredCount, pausedCount, unmonitoredCount) + " · 仅对单独开启的房间生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (monitoredCount == 0 && pausedCount > 0) WarningAmber
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (enabled && backgroundEnabled) {
                    val running = monitorSnapshot.isRunning()
                    Text(
                        when {
                            monitoredCount == 0 -> "暂无后台检测任务"
                            monitorSnapshot.isBlocked() -> "后台启动受限 · 检测服务未运行"
                            running -> "后台检测运行中"
                            else -> "后台检测未运行 · 系统兜底检查可能延迟 15 分钟以上"
                        } + " · 上次成功检测：" +
                            (monitorSnapshot.lastCheckAt.takeIf { it > 0L }?.let(::formatDateTime) ?: "暂无"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else WarningAmber,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("全局自动录制", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("后台检测", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = backgroundEnabled, onCheckedChange = onBackgroundChange)
                }
                if (enabled && backgroundEnabled && monitoredCount > 0 && !monitorSnapshot.isRunning()) {
                    TextButton(onClick = onOpenBackgroundSettings) { Text("后台设置") }
                }
            }
        }
        }
    }
}

@Composable
private fun StatusFilterBar(selected: RoomFilter, onSelected: (RoomFilter) -> Unit) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("状态筛选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 2.dp, bottom = 2.dp),
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
        val animatedValue by animateIntAsState(value, tween(MotionTokens.Normal), label = "metric_$label")
        Text(animatedValue.toString(), style = MaterialTheme.typography.titleMedium, color = color)
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
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
    autoRecordingStatus: String,
    latestErrorMessage: String?,
    isChecking: Boolean,
    onOpen: () -> Unit,
    onOpenRecording: (Long) -> Unit,
    onAutoToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val statusColor = when {
        room.taskStatus == TaskStatus.RECORDING -> RecordingRed
        room.taskStatus == TaskStatus.PAUSED -> WarningAmber
        room.taskStatus == TaskStatus.FAILED -> WarningAmber
        room.taskStatus == TaskStatus.CHECKING || room.taskStatus == TaskStatus.STARTING || room.taskStatus == TaskStatus.RECONNECTING -> LiveTeal
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        room.taskStatus == TaskStatus.STARTING -> "正在启动录制"
        room.taskStatus == TaskStatus.RECONNECTING -> "断流重试中"
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
    val animatedColor by animateColorAsState(statusColor, tween(220), label = "recordingStatus")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Landscape cards use a fixed rhythm so the action area cannot stretch
            // the item and push the metadata out of the measured bounds.
            .heightIn(min = 164.dp),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().wrapContentHeight()) {
        // A portrait emulator can still be wider than 680dp in density-independent
        // pixels. Reserve the two-column card for true landscape widths so the
        // action row and monitoring hint remain visible in portrait.
        val wide = roomCardLayout(
            maxWidth.value.toInt(),
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
        ) == RoomCardLayout.WIDE
        if (wide) Row(Modifier.fillMaxWidth().wrapContentHeight().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 48.dp)) {
                Text(room.anchorName, style = MaterialTheme.typography.titleMedium)
                Text("${room.platform} · $statusText", color = animatedColor, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                activeRecording?.let { "已录 ${formatDuration(it.durationSeconds)} · 已保存 ${it.outputUris.ifEmpty { listOfNotNull(it.outputUri) }.distinct().size} 段" }
                    ?: "尚无视频 · 请求${room.quality}",
                style = MaterialTheme.typography.labelLarge,
            )
            RoomMonitoringHint(
                room = room, autoRecordingStatus = autoRecordingStatus, latestErrorMessage = latestErrorMessage,
                isChecking = isChecking, onRefresh = onRefresh,
            )
        }
        // Keep the action area bounded. A weight inside an unconstrained column
        // previously consumed the whole row, leaving the metadata column at zero
        // width (the screenshot showed only an oversized button).
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text("自动录制", style = MaterialTheme.typography.labelMedium)
                Switch(checked = room.scheduledMonitoringEnabled, onCheckedChange = onAutoToggle)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = room.enabled && room.taskStatus !in setOf(TaskStatus.FINALIZING, TaskStatus.CHECKING),
                    onClick = if (room.taskStatus in activeRecordingStatuses) onPause else onResume,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(when (room.taskStatus) {
                        TaskStatus.FINALIZING -> "正在保存…"
                        TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING -> "暂停并保存"
                        TaskStatus.PAUSED -> "开始新一场录制"
                        else -> "开始录制"
                    })
                }
                if (activeRecording != null) TextButton(onClick = { onOpenRecording(activeRecording.id) }) { Text("查看视频") }
                else TextButton(onClick = onOpen) { Text("详情") }
            }
        }
        }
        else Column(Modifier.fillMaxWidth().wrapContentHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 48.dp)) {
                Text(room.anchorName, style = MaterialTheme.typography.titleMedium)
                Text("${room.platform} · $statusText", color = animatedColor, style = MaterialTheme.typography.bodyMedium)
            }
            Text(activeRecording?.let { "已录 ${formatDuration(it.durationSeconds)} · 已保存 ${it.outputUris.ifEmpty { listOfNotNull(it.outputUri) }.distinct().size} 段" } ?: "尚无视频 · 请求${room.quality}", style = MaterialTheme.typography.labelLarge)
            RoomMonitoringHint(
                room = room, autoRecordingStatus = autoRecordingStatus, latestErrorMessage = latestErrorMessage,
                isChecking = isChecking, onRefresh = onRefresh,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动录制", style = MaterialTheme.typography.labelMedium)
                Switch(checked = room.scheduledMonitoringEnabled, onCheckedChange = onAutoToggle)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = room.enabled && room.taskStatus !in setOf(TaskStatus.FINALIZING, TaskStatus.CHECKING),
                    onClick = if (room.taskStatus in activeRecordingStatuses) onPause else onResume,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(when (room.taskStatus) {
                        TaskStatus.FINALIZING -> "正在保存…"
                        TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING -> "暂停并保存"
                        TaskStatus.PAUSED -> "开始新一场录制"
                        else -> "开始录制"
                    })
                }
                if (activeRecording != null) TextButton(onClick = { onOpenRecording(activeRecording.id) }) { Text("查看视频") }
                else TextButton(onClick = onOpen) { Text("详情") }
            }
        }
        }
    }
}

@Composable
private fun RoomMonitoringHint(
    room: LiveRoom,
    autoRecordingStatus: String,
    latestErrorMessage: String?,
    isChecking: Boolean,
    onRefresh: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val lastCheck = room.lastCheckAt?.let { formatDateTime(it) } ?: "尚未检查"
        val errorMessage = latestErrorMessage?.takeIf { room.liveStatus == LiveStatus.ERROR }
        val detail = when {
            errorMessage != null -> "检查失败：${errorMessage.take(42)} · $lastCheck"
            room.liveStatus == LiveStatus.ERROR -> "${room.liveStatus.displayName()} · 上次 $lastCheck"
            room.liveStatus == LiveStatus.LIVE -> "直播状态已确认 · 上次 $lastCheck"
            else -> "${room.liveStatus.displayName()} · 上次 $lastCheck"
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                autoRecordingStatus,
                style = MaterialTheme.typography.labelMedium,
                color = if (autoRecordingStatus in setOf("应用后台运行时检查", "仅应用打开时检查"))
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail, style = MaterialTheme.typography.labelSmall,
                color = if (room.liveStatus == LiveStatus.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onRefresh, enabled = !isChecking) {
            Text(if (isChecking) "检查中…" else "立即检查")
        }
    }
}
