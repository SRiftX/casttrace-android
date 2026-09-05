package com.streamcap.mobile.ui.recordings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.ui.common.displayName
import com.streamcap.mobile.ui.common.formatBytes
import com.streamcap.mobile.ui.common.formatDateTime
import com.streamcap.mobile.ui.common.formatDuration

private enum class RecordingFilter(val label: String) {
    ALL("全部"),
    RECORDING("录制中"),
    COMPLETED("已完成"),
    FAILED("录制错误"),
}

@Composable
fun RecordingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenRecording: (Long) -> Unit,
) {
    val viewModel: RecordingsViewModel = viewModel(
        factory = ViewModelFactory { RecordingsViewModel(container.repository, container) },
    )
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    var selectedFilter by remember { mutableStateOf(RecordingFilter.ALL) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val filteredRecordings = recordings.filter { recording ->
        when (selectedFilter) {
            RecordingFilter.ALL -> true
            RecordingFilter.RECORDING -> recording.status in setOf(
                TaskStatus.STARTING,
                TaskStatus.RECORDING,
                TaskStatus.RECONNECTING,
                TaskStatus.FINALIZING,
            )
            RecordingFilter.COMPLETED -> recording.status == TaskStatus.COMPLETED
            RecordingFilter.FAILED -> recording.status == TaskStatus.FAILED
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("录制文件", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "${filteredRecordings.size} / ${recordings.size} 个录制会话",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            Text("状态筛选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 4.dp),
            ) {
                items(RecordingFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) },
                    )
                }
            }
        }
        if (recordings.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 72.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.VideoFile, contentDescription = null)
                    Spacer(Modifier.height(12.dp))
                    Text("还没有录制文件", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else if (filteredRecordings.isEmpty()) {
            item {
                Text(
                    "当前筛选下没有录制会话",
                    modifier = Modifier.padding(vertical = 36.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(filteredRecordings, key = { it.id }) { recording ->
                RecordingRow(recording, onOpenRecording) { target ->
                    viewModel.delete(target) { error -> deleteError = error }
                }
            }
        }
    }
    deleteError?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteError = null },
            title = { Text("文件删除失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { deleteError = null }) { Text("确定") } },
        )
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingSession,
    onOpenRecording: (Long) -> Unit,
    onDelete: (RecordingSession) -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }
    val canOpen = recording.status in setOf(
        TaskStatus.COMPLETED,
        TaskStatus.PAUSED,
        TaskStatus.FAILED,
        TaskStatus.STARTING,
        TaskStatus.RECORDING,
        TaskStatus.RECONNECTING,
        TaskStatus.FINALIZING,
    ) && (recording.outputUri != null || recording.temporaryPath?.let { java.io.File(it).exists() } == true)
    val canDelete = recording.status !in setOf(
        TaskStatus.STARTING,
        TaskStatus.RECORDING,
        TaskStatus.RECONNECTING,
        TaskStatus.FINALIZING,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen) {
                onOpenRecording(recording.id)
            },
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (canOpen) Icons.Outlined.PlayCircle else Icons.Outlined.VideoFile,
                contentDescription = null,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    recording.roomName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${recording.containerFormat.uppercase()} · ${formatDuration(recording.durationSeconds)} · ${formatBytes(recording.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDateTime(recording.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recording.errorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (recording.status == TaskStatus.FINALIZING) {
                        "正在保存"
                    } else if (recording.status in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING)) {
                        "预览"
                    } else if (recording.status == TaskStatus.FAILED && canOpen) {
                        "已保存片段"
                    } else {
                        recording.status.displayName(recording.errorMessage)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(onClick = { showDelete = true }, enabled = canDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除录制文件")
                }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除录制文件？") },
            text = { Text("将从录制列表和本地保存目录中删除该文件。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete(recording) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

private fun TaskStatus.displayName(error: String?): String = when {
    this != TaskStatus.FAILED -> displayName()
    error?.contains("空间", true) == true -> "空间不足"
    error?.contains("进程", true) == true || error?.contains("中断", true) == true -> "进程中断"
    error?.contains("文件", true) == true || error?.contains("封装", true) == true -> "文件损坏"
    error?.contains("网络", true) == true || error?.contains("连接", true) == true || error?.contains("直播地址", true) == true -> "网络中断"
    else -> displayName()
}
