package com.streamcap.mobile.ui.roomdetail

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    val context = LocalContext.current
    val viewModel: RoomDetailViewModel = viewModel(
        factory = ViewModelFactory { RoomDetailViewModel(roomId, container.repository) },
    )
    val room by viewModel.room.collectAsStateWithLifecycle()
    LaunchedEffect(roomId) {
        viewModel.refresh { status ->
            if (status == com.streamcap.mobile.domain.model.LiveStatus.LIVE && room?.scheduledMonitoringEnabled == true) {
                RecordingService.start(context, roomId)
            }
        }
    }
    var timerHours by remember { mutableStateOf<Double?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

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
                        enabled = room?.taskStatus !in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.FINALIZING),
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
                DetailSection("任务") {
                    SettingRow(
                        title = "任务启用",
                        value = if (current.enabled) "已启用" else "已停用",
                        trailing = {
                            Switch(
                                checked = current.enabled,
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
                        value = if (current.scheduledMonitoringEnabled) "已启用" else "已停用",
                        trailing = {
                            Switch(
                                checked = current.scheduledMonitoringEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setScheduledMonitoringEnabled(enabled) {
                                        if (enabled) RecordingService.monitor(context)
                                        else context.startService(RecordingService.stopIntent(context, roomId))
                                    }
                                },
                            )
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "当前状态",
                        value = when {
                            current.taskStatus == TaskStatus.RECORDING -> "录制中"
                            current.taskStatus == TaskStatus.PAUSED -> "已暂停"
                            current.liveStatus == com.streamcap.mobile.domain.model.LiveStatus.LIVE -> "直播中（自动检测录制）"
                            current.liveStatus == com.streamcap.mobile.domain.model.LiveStatus.OFFLINE -> "未开播"
                            else -> current.taskStatus.displayName()
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "上次检查",
                        value = formatDateTime(current.lastCheckAt),
                        trailing = {
                            IconButton(
                                onClick = {
                                    viewModel.refresh { status ->
                                        if (status == com.streamcap.mobile.domain.model.LiveStatus.LIVE && current.scheduledMonitoringEnabled) {
                                            RecordingService.start(context, roomId)
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "立即检查")
                            }
                        },
                    )
                }
            }
            item {
                DetailSection("录制") {
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
                            Switch(checked = current.segmentEnabled, onCheckedChange = viewModel::setSegmentEnabled)
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "录制通知",
                        value = if (current.notificationsEnabled) "已启用" else "已停用",
                        trailing = {
                            Switch(checked = current.notificationsEnabled, onCheckedChange = viewModel::setNotificationsEnabled)
                        },
                    )
                    HorizontalDivider()
                    SettingRow(
                        title = "定时停止",
                        value = timerHours?.let(::timerLabel) ?: "未设置",
                        modifier = Modifier.clickable { showTimer = true },
                        leading = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                        trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    )
                }
            }
            item {
                if (current.taskStatus in setOf(TaskStatus.RECORDING, TaskStatus.STARTING, TaskStatus.RECONNECTING)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { context.startService(RecordingService.pauseIntent(context, roomId)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Pause, contentDescription = null)
                            Text("暂停录制", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(
                            onClick = { context.startService(RecordingService.stopIntent(context, roomId)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                            Text("停止录制", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else if (current.taskStatus == TaskStatus.PAUSED) {
                    OutlinedButton(
                        onClick = { RecordingService.start(context, roomId, timerHours?.let { System.currentTimeMillis() + (it * 3_600_000L).toLong() }) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("继续录制")
                    }
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
                TextButton(onClick = { viewModel.delete(onBack) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
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
            .height(64.dp)
            .padding(horizontal = 14.dp),
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
