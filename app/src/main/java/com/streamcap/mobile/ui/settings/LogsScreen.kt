package com.streamcap.mobile.ui.settings

import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.streamcap.mobile.BuildConfig
import com.streamcap.mobile.data.database.TaskLogEntity
import com.streamcap.mobile.diagnostics.TaskLogExporter
import com.streamcap.mobile.ui.common.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = hiltViewModel()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val logCount by viewModel.logCount.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    var selectedLevel by remember { mutableStateOf<String?>(null) }
    var selectedRoom by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var keyOnly by remember { mutableStateOf(true) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val report = requireNotNull(exportText) { "预览已失效，请重新生成" }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { it.write(report) }
                        ?: error("无法打开导出文件")
                }
            }
            Toast.makeText(
                context,
                if (result.isSuccess) "任务日志已导出" else "导出失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    TaskLogExporter.buildFullReport(viewModel.logsForExport(), BuildConfig.VERSION_NAME,
                                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                                }
                                exportText = report
                                preview = report.take(32_000) + if (report.length > 32_000) "\n[预览已截断，导出文件包含全部所选记录]" else ""
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "预览并导出任务日志")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            item {
                val failed = recordings.count { it.status == com.streamcap.mobile.domain.model.TaskStatus.FAILED }
                val playable = recordings.count { it.outputUri != null || it.outputUris.isNotEmpty() || it.temporaryPath?.let { path -> java.io.File(path).exists() } == true }
                val freeBytes = viewModel.recordingFreeBytes
                val storageAdvice = if (freeBytes < 512L * 1_048_576L) "存储空间不足，建议先清理录制文件" else "存储空间正常"
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("诊断摘要", style = MaterialTheme.typography.titleMedium)
                    Text("录制会话：${recordings.size} · 可预览：$playable · 异常：$failed")
                    Text("可用空间：${formatDiagnosticStorage(freeBytes)} · $storageAdvice", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (failed == 0) "当前没有录制异常" else "请查看下方异常日志；有媒体文件的异常记录仍可尝试预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("事件记录", style = MaterialTheme.typography.titleMedium)
                    Text("显示 ${logs.size} / $logCount 条 · 按时间倒序", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = keyOnly, onClick = {
                            keyOnly = !keyOnly; viewModel.setKeyLogsOnly(keyOnly)
                        }, label = { Text("关键事件") })
                        listOf(null to "全部", "ERROR" to "错误", "WARN" to "警告", "INFO" to "信息").forEach { (level, label) ->
                            FilterChip(selected = selectedLevel == level, onClick = {
                                selectedLevel = level; viewModel.setLogLevel(level)
                            }, label = { Text(label) })
                        }
                    }
                    if (rooms.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedRoom == null, onClick = {
                                selectedRoom = null; viewModel.setLogRoom(null)
                            }, label = { Text("所有直播间") })
                            rooms.forEach { room ->
                                FilterChip(selected = selectedRoom == room.id, onClick = {
                                    selectedRoom = room.id; viewModel.setLogRoom(room.id)
                                }, label = { Text(room.anchorName, maxLines = 1) })
                            }
                        }
                    }
                }
            }
            if (logs.isEmpty()) {
                item { Text("当前筛选下没有日志", modifier = Modifier.padding(vertical = 48.dp)) }
            } else {
                items(logs, key = { it.id }) { log ->
                    LogRow(log, rooms.firstOrNull { it.id == log.roomId }?.anchorName)
                    HorizontalDivider()
                }
                if (logs.size < logCount) item {
                    TextButton(onClick = viewModel::loadMoreLogs, modifier = Modifier.fillMaxWidth()) {
                        Text("加载更早记录")
                    }
                }
            }
        }
    }
    preview?.let { report ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("任务日志预览") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("导出最近最多 20000 条记录；预览只显示前 32000 字。链接和凭据已隐藏，不包含视频。")
                Text(report, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = {
                preview = null
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportLauncher.launch("casttrace-diagnostics-$timestamp.txt")
            }) { Text("选择位置并导出") } }, dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } })
    }

}

private fun formatDiagnosticStorage(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun LogRow(log: TaskLogEntity, roomName: String?) {
    val tone = when (log.level) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARN" -> Color(0xFFB76B00)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = tone.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                Text(when (log.level) { "ERROR" -> "错误"; "WARN" -> "警告"; else -> "信息" },
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = tone,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(logTitle(log.eventType), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        }
        Text(log.message, style = MaterialTheme.typography.bodyMedium)
        Text("${formatDateTime(log.createdAt)}  ·  ${roomName ?: log.roomId?.let { "房间 $it" } ?: "应用"}${log.sessionId?.let { "  ·  录制 #$it" } ?: ""}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(log.eventType, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

private fun logTitle(event: String): String = when {
    event.startsWith("REMINDER_CHECK_TIMEOUT") -> "主播提醒检查超时"
    event.startsWith("REMINDER_CHECK_FAILED") || event.startsWith("REMINDER_CHECK_ERROR") -> "主播提醒检查失败"
    event.startsWith("REMINDER_CHECK_INDETERMINATE") -> "主播提醒状态无法确认"
    event.startsWith("REMINDER_NOTIFICATION_BLOCKED") -> "开播提醒被系统通知设置阻止"
    event == "LIVE_CHECK_TIMEOUT" -> "直播间检查超时"
    event == "LIVE_CHECK_FAILED" || event == "LIVE_CHECK_ERROR" -> "直播间检查失败"
    event == "LIVE_CHECK_INDETERMINATE" -> "直播状态无法确认"
    event == "LIVE_CHECK_STALE_RECOVERED" -> "卡住的检查已回收"
    event == "LIVE_STATUS_CHANGED" -> "直播状态变化"
    event == "BACKGROUND_MONITOR_NETWORK_BLOCKED" -> "监控被网络策略暂停"
    event == "BACKGROUND_MONITOR_NETWORK_RESTORED" -> "监控网络已恢复"
    event == "REMINDER_STATUS_CHANGED" -> "主播提醒状态变化"
    event.startsWith("REMINDER_NOTIFICATION_FAILED") -> "开播提醒发送失败"
    event == "BACKGROUND_WORKER_FAILED" -> "系统后台检查任务异常"
    event == "MONITOR_LOOP_FAILED" -> "后台监控循环异常"
    event == "AUTO_MONITOR_FAILED" -> "自动监控异常"
    event == "LIVE_CHECK" -> "直播状态检查"
    event == "BACKGROUND_MONITOR_STARTED" -> "后台监控已启动"
    event == "BACKGROUND_MONITOR_RESTORE" -> "系统请求恢复后台监控"
    event == "BACKGROUND_LIVE_DETECTED" -> "后台检测到开播"
    event == "BACKGROUND_START_BLOCKED" -> "后台启动受到系统限制"
    event == "LIVE_CHECK_ROOM_MISSING" -> "检查时直播间已删除"
    event == "RECORDING_PUBLICATION_FAILED" -> "录制文件保存失败"
    event.startsWith("RECORDING_LIVE_CHECK_") -> "录制中检查直播状态异常"
    event == "RECORDING_START" -> "开始录制"
    event == "RECORDING_STOP_REASON" -> "停止写入"
    event == "RECORDING_PAUSED" -> "暂停并保存"
    event == "RECORDING_COMPLETED" -> "录制完成"
    event == "RECORDING_CHECKPOINT_SAVED" -> "分段已保存"
    event == "RECORDING_SAVE_TIMING" -> "保存耗时"
    event == "STREAM_REFRESH_REQUESTED" -> "刷新直播地址"
    event == "STREAM_REFRESH_RESULT" -> "直播地址刷新结果"
    event == "STREAM_HTTP_ERROR" -> "直播源响应异常"
    event == "STREAM_READ_EOF" -> "直播流断开"
    event == "STREAM_RETRY_WAIT" || event == "RECONNECT_WAIT" -> "等待重连"
    event == "LIVE_ENDED" -> "直播结束"
    event == "BACKGROUND_CHECK" -> "检查开播"
    else -> event.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
}
