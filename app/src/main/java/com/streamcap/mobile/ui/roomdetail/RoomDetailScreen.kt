package com.streamcap.mobile.ui.roomdetail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.streamcap.mobile.ui.common.*
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.ui.common.displayName
import com.streamcap.mobile.ui.common.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailScreen(
    roomId: Long,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val recordAction = rememberRecordingAction()
    val context = LocalContext.current
    val viewModel: RoomDetailViewModel = viewModel(
        factory = ViewModelFactory { RoomDetailViewModel(roomId, container.repository) },
    )
    val room by viewModel.room.collectAsStateWithLifecycle()
    LaunchedEffect(roomId) {
        // Inspecting a room must not clear a user's pause by issuing ACTION_START.
        viewModel.refresh()
    }
    var timerHours by rememberSaveable { mutableStateOf<Double?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showInterval by remember { mutableStateOf(false) }
    var showSegment by remember { mutableStateOf(false) }
    var showOneTimeWindow by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(room?.anchorName ?: "直播间") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDelete = true },
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除直播间")
                    }
                },
            )
        },
    ) { padding ->
        val current = room
        if (current == null) return@Scaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 1120.dp)
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text(current.roomTitle ?: current.anchorName, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${current.platform} · ${current.liveStatus.displayName()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (current.taskStatus == TaskStatus.WAITING && !current.scheduledMonitoringEnabled) "就绪 · 手动开始录制" else current.taskStatus.displayName(), color = MaterialTheme.colorScheme.primary)
                    Button(
                        enabled = current.enabled && current.taskStatus !in setOf(TaskStatus.FINALIZING, TaskStatus.CHECKING),
                        onClick = {
                            if (current.taskStatus in activeRecordingStatuses) context.startService(RecordingService.pauseIntent(context, roomId))
                            else recordAction { RecordingService.start(context, roomId, timerHours?.let { System.currentTimeMillis() + (it * 3_600_000L).toLong() }) }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(when {
                            current.taskStatus == TaskStatus.FINALIZING -> "正在保存…"
                            current.taskStatus in activeRecordingStatuses -> "暂停并保存"
                            current.taskStatus == TaskStatus.PAUSED -> "开始新一场录制"
                            else -> "开始录制"
                        })
                    }
                    Text("暂停会保存本次内容，并关闭该房间的自动录制；需要继续监测开播时，请重新打开下方开关。再次开始会创建新一场。", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                DetailSection("任务") {
                    SettingRow(
                        title = "任务启用",
                        value = if (current.enabled) "已启用" else "已停用",
                        trailing = {
                            Switch(
                                checked = current.enabled,
                                modifier = Modifier.semantics { contentDescription = "任务启用" },
                                onCheckedChange = { enabled ->
                                    viewModel.setEnabled(enabled) {
                                        if (!enabled) context.startService(RecordingService.stopIntent(context, roomId))
                                    }
                                },
                            )
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "开播自动录制",
                        value = when {
                            current.scheduledMonitoringEnabled -> "开启；房间启用且未暂停时生效"
                            else -> "关闭；关闭会保存并停止当前录制"
                        },
                        trailing = {
                            Switch(
                                checked = current.scheduledMonitoringEnabled,
                                modifier = Modifier.semantics { contentDescription = "开播自动录制" },
                                onCheckedChange = { enabled ->
                                    viewModel.setScheduledMonitoringEnabled(enabled) {
                                        if (enabled) RecordingService.monitorRoomNow(context, roomId)
                                        else context.startService(RecordingService.stopIntent(context, roomId))
                                    }
                                },
                            )
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "当前状态",
                        value = if (current.taskStatus == TaskStatus.WAITING && !current.scheduledMonitoringEnabled) "就绪 · 手动开始录制" else current.taskStatus.displayName(),
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "上次检查",
                        value = formatDateTime(current.lastCheckAt),
                        trailing = {
                            IconButton(
                                onClick = {
                                    viewModel.refresh()
                                },
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "立即检查")
                            }
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "开播检查间隔",
                        value = "${current.checkIntervalSeconds} 秒",
                        modifier = Modifier.clickable { showInterval = true },
                        trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "单次监控时段",
                        value = current.oneTimeStartAt?.let { start ->
                            "${formatDateTime(start)} 至 ${formatDateTime(current.oneTimeEndAt)}"
                        } ?: "未设置",
                        modifier = Modifier.clickable { showOneTimeWindow = true },
                        trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    )
                }
            }
            item {
                DetailSection("此房间录制设置 · 格式、画质、分段和通知下次录制生效") {
                    SettingRow(
                        title = "保存格式",
                        value = current.outputFormat,
                        modifier = Modifier.clickable { showFormat = true },
                        trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "清晰度",
                        value = current.quality,
                        modifier = Modifier.clickable { showQuality = true },
                        trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "自动分段",
                        value = if (current.segmentEnabled) "每 ${current.segmentSeconds / 60} 分钟" else "关闭",
                        trailing = {
                            Switch(modifier = Modifier.semantics { contentDescription = "自动分段" }, checked = current.segmentEnabled, onCheckedChange = viewModel::setSegmentEnabled)
                        },
                    )
                    HorizontalDivider()
                    if (current.segmentEnabled) {
                        SettingRow(title = "分段时长", value = "${current.segmentSeconds / 60} 分钟（下次录制生效）",
                            modifier = Modifier.clickable { showSegment = true },
                            trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) })
                        HorizontalDivider()
                    }
                    SettingRow(
                        title = "录制通知",
                        value = if (current.notificationsEnabled) "已启用" else "已停用",
                        trailing = {
                            Switch(modifier = Modifier.semantics { contentDescription = "录制通知" }, checked = current.notificationsEnabled, onCheckedChange = viewModel::setNotificationsEnabled)
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "定时停止",
                        value = (timerHours?.let(::timerLabel) ?: "未设置") + " · 下次手动开始生效",
                        modifier = Modifier.clickable { showTimer = true },
                        leading = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                        trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    )
                }
            }

        }
    }

    if (showTimer) {
        TimerDialog(
            selectedHours = timerHours,
            onDismiss = { showTimer = false },
            onConfirm = {
                timerHours = it
                showTimer = false
            },
        )
    }
    if (showOneTimeWindow) {
        var startText by remember(room?.oneTimeStartAt) {
            mutableStateOf(room?.oneTimeStartAt?.let(::formatWindowTime).orEmpty())
        }
        var endText by remember(room?.oneTimeEndAt) {
            mutableStateOf(room?.oneTimeEndAt?.let(::formatWindowTime).orEmpty())
        }
        var windowError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showOneTimeWindow = false },
            title = { Text("单次监控时段") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本地时间 · ${TimeZone.getDefault().id}。开始后按检查轮次监控；系统省电可能延迟后台检查。")
                    OutlinedTextField(startText, { startText = it; windowError = null }, label = { Text("开始：yyyy-MM-dd HH:mm") }, singleLine = true)
                    OutlinedTextField(endText, { endText = it; windowError = null }, label = { Text("结束：yyyy-MM-dd HH:mm") }, singleLine = true)
                    windowError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val start = parseWindowTime(startText)
                    val end = parseWindowTime(endText)
                    if (start == null || end == null || end <= start || end <= System.currentTimeMillis()) {
                        windowError = "请填写完整日期；结束时间需晚于开始时间和当前时间"
                    } else {
                        viewModel.setOneTimeWindow(start, end) { RecordingService.monitor(context) }
                        showOneTimeWindow = false
                    }
                }) { Text("保存时段") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setOneTimeWindow(null, null)
                    showOneTimeWindow = false
                }) { Text("清除时段") }
            },
        )
    }
    if (showQuality) {
        QualityDialog(
            selected = room?.quality ?: "原画",
            onDismiss = { showQuality = false },
            onSelect = {
                viewModel.setQuality(it)
                showQuality = false
            },
        )
    }
    if (showFormat) {
        SimpleChoiceDialog(
            title = "保存格式",
            selected = room?.outputFormat ?: "MP4",
            options = listOf("FLV", "TS", "MP4"),
            onDismiss = { showFormat = false },
            onSelect = {
                viewModel.setOutputFormat(it)
                showFormat = false
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除直播间？") },
            text = { Text("已经保存的录制文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    RecordingService.deleteRoom(context, roomId)
                    viewModel.delete(onBack)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
    if (showSegment) {
        SimpleChoiceDialog("分段时长", "${room?.segmentSeconds?.div(60) ?: 30} 分钟",
            listOf(1, 5, 10, 20, 30, 60).map { "$it 分钟" },
            onDismiss = { showSegment = false }, onSelect = {
                viewModel.setSegmentSeconds(it.substringBefore(' ').toInt() * 60)
                showSegment = false
            })
    }
    if (showInterval) {
        MonitorIntervalDialog(
            initial = room?.checkIntervalSeconds ?: 60,
            onDismiss = { showInterval = false },
            onConfirm = { seconds ->
                viewModel.setCheckIntervalSeconds(seconds)
                showInterval = false
            },
        )
    }
}

private fun formatWindowTime(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))

private fun parseWindowTime(value: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false }.parse(value.trim())?.time
}.getOrNull()

@Composable
private fun MonitorIntervalDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开播检查间隔") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(4) },
                label = { Text("秒（15-3600）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                value.toIntOrNull()?.coerceIn(15, 3_600)?.let(onConfirm)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) { Column(content = { content() }) }
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.padding(start = 10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerDialog(
    selectedHours: Double?,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var selection by remember { mutableStateOf(selectedHours) }
    var custom by remember { mutableStateOf("") }
    val presets = listOf(0.5, 1.0, 2.0, 3.0, 6.0, 12.0, 24.0, 48.0, 72.0, 100.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时停止") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { hours ->
                        FilterChip(
                            selected = selection == hours,
                            onClick = {
                                selection = hours
                                custom = ""
                            },
                            label = { Text(timerLabel(hours)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { value ->
                        custom = value.filter { it.isDigit() }.take(3)
                        custom.toDoubleOrNull()?.takeIf { it in 1.0..168.0 }?.let { selection = it }
                    },
                    label = { Text("自定义小时（1–168）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selection) }) { Text("设置") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(null) }) { Text("取消定时") }
                TextButton(onClick = onDismiss) { Text("返回") }
            }
        },
    )
}

private fun timerLabel(hours: Double): String = when {
    hours < 1 -> "${(hours * 60).toInt()} 分钟"
    hours % 1.0 == 0.0 -> "${hours.toInt()} 小时"
    else -> "$hours 小时"
}

@Composable
private fun QualityDialog(
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val options = listOf("原画", "1080p", "720p", "480p", "音频优先")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择清晰度") },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = { onSelect(option) })
                        Text(option, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun SimpleChoiceDialog(
    title: String,
    selected: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = { onSelect(option) })
                        Text(option, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}
