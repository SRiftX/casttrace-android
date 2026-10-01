package com.streamcap.mobile.ui.recordings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.data.database.TaskLogEntity
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService

@Composable
fun RecoverySummary(container: AppContainer, recordings: List<RecordingSession>, logs: List<TaskLogEntity>, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("ux", 0) }
    var dismissedThrough by remember { mutableLongStateOf(preferences.getLong("recoveryAcknowledged", 0)) }
    val outcomes = logs.filter { it.eventType == "INTERRUPTED_RECORDING_RECOVERED" && recordings.any { recording -> recording.id == it.sessionId } }.distinctBy { it.sessionId }
    val pending = recordings.filter { it.status == TaskStatus.FAILED && it.temporaryPath != null &&
        (it.errorMessage?.startsWith("文件发布失败：") == true || it.errorMessage?.startsWith("应用进程中断，恢复失败：") == true) }
    val unread = outcomes.filter { it.id > dismissedThrough }
    if (unread.isEmpty() && pending.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("中断恢复 · ${unread.size} 条结果 / ${pending.size} 项待处理", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
            Text("恢复结果以旧录制已保存的片段为准；重新开始录制不算恢复。", style = MaterialTheme.typography.bodySmall)
            unread.take(10).forEach { log ->
                val recording = recordings.firstOrNull { it.id == log.sessionId }
                Text("${recording?.roomName ?: "历史录制"}：${log.message}", style = MaterialTheme.typography.bodySmall)
                recording?.let {
                    Text("现有 ${it.outputUris.ifEmpty { listOfNotNull(it.outputUri) }.distinct().size} 个已保存片段")
                    TextButton(onClick = { onOpen(it.id) }) { Text("查看恢复内容") }
                }
            }
            pending.forEach { recording ->
                Text("${recording.roomName}：${recording.errorMessage}")
                Text("已有片段保留。请检查可用空间及保存目录权限，再重试。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { RecordingService.recover(context, recording.id) }) { Text("重试保存 ${recording.roomName}") }
            }
            if (unread.isNotEmpty()) TextButton(onClick = {
                dismissedThrough = outcomes.maxOf { it.id }
                preferences.edit().putLong("recoveryAcknowledged", dismissedThrough).apply()
            }) { Text("已读，保留未处理事项") }
            }
        }
    }
}
