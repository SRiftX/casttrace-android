package com.streamcap.mobile.ui.recordings

import android.content.Intent
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.streamcap.mobile.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val viewModel: RecordingsViewModel = viewModel(
        factory = ViewModelFactory { RecordingsViewModel(container.repository, container) },
    )
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val fileMessage by viewModel.fileMessage.collectAsStateWithLifecycle()
    var exportSource by rememberSaveable { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val source = exportSource
        exportSource = null
        if (destination != null && source != null) viewModel.export(source, destination)
    }
    var selectedFilter by remember { mutableStateOf(RecordingFilter.ALL) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleteSuccess by remember { mutableStateOf<String?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    val roomNames = recordings.map { it.roomName }.distinct().sorted()
    val filteredRecordings = recordings.filter { recording ->
        val matchesStatus = when (selectedFilter) {
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
        matchesStatus && (selectedRoom == null || recording.roomName == selectedRoom)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            if (multiSelectMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                            IconButton(onClick = { selectedIds = emptySet(); multiSelectMode = false }) {
                            Icon(Icons.Outlined.Close, contentDescription = "退出多选")
                        }
                        Text("已选 ${selectedIds.size} 项", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .clickable { selectedIds = filteredRecordings.map { it.id }.toSet() }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "全选当前筛选结果")
                        }
                        IconButton(onClick = {
                        val targets = recordings.filter { it.id in selectedIds }
                        val uris = targets.flatMap { it.outputUris.ifEmpty { listOfNotNull(it.outputUri) } }.map(Uri::parse)
                        runCatching { context.startActivity(Intent.createChooser(RecordingStorage(context).shareIntent(uris), "分享录制文件")) }
                            .onFailure { deleteError = "分享失败：${it.message}" }
                        }) { Icon(Icons.Outlined.Share, contentDescription = "分享已选文件") }
                        IconButton(onClick = {
                        val targets = recordings.filter { it.id in selectedIds }
                        viewModel.deleteMany(targets) { error ->
                            if (error == null) deleteSuccess = "已删除 ${targets.size} 个录制会话"
                            else deleteError = error
                        }
                        selectedIds = emptySet()
                        }) { Icon(Icons.Outlined.Delete, contentDescription = "删除已选文件") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("录制文件", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("${filteredRecordings.size} / ${recordings.size} 个录制会话",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { multiSelectMode = true }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = "进入多选")
                    }
                }
            }
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
            if (roomNames.size > 1) {
                Text("直播间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 4.dp)) {
                    item {
                        FilterChip(selected = selectedRoom == null, onClick = { selectedRoom = null }, label = { Text("全部直播间") })
                    }
                    items(roomNames) { room ->
                        FilterChip(selected = selectedRoom == room, onClick = { selectedRoom = room }, label = { Text(room, maxLines = 1) })
                    }
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
                RecordingRow(recording, onOpenRecording,
                    selectionMode = multiSelectMode,
                    selected = recording.id in selectedIds,
                    onSelect = { checked -> selectedIds = if (checked) selectedIds + recording.id else selectedIds - recording.id },
                    onRename = { source, name -> viewModel.rename(recording.id, source, name) },
                    onExport = { source, name ->
                        exportSource = source
                        exportPicker.launch(name)
                    },
                    onDelete = { target -> viewModel.delete(target) { error ->
                        if (error != null) deleteError = error
                        else deleteSuccess = "录制文件已删除"
                    } },
                )
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
    deleteSuccess?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteSuccess = null },
            title = { Text("删除成功") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { deleteSuccess = null }) { Text("确定") } },
        )
    }
    exportProgress?.let { progress ->
        AlertDialog(onDismissRequest = {}, title = { Text(progress) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {
                if (progress.startsWith("正在导出")) TextButton(onClick = viewModel::cancelExport) { Text("取消导出") }
            })
    }
    fileMessage?.let { message ->
        AlertDialog(onDismissRequest = viewModel::dismissFileMessage, title = { Text("文件操作") },
            text = { Text(message) }, confirmButton = {
                TextButton(onClick = viewModel::dismissFileMessage) { Text("确定") }
            })
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingSession,
    onOpenRecording: (Long) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onExport: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (RecordingSession) -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }
    var showExportParts by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var renamePart by remember { mutableStateOf(0) }
    var renameBase by remember { mutableStateOf("") }
    val published = recording.outputUris.ifEmpty { listOfNotNull(recording.outputUri) }.distinct()
    val context = LocalContext.current
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
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = onSelect)
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
                recording.roomTitle?.takeIf(String::isNotBlank)?.let { title ->
                    Text(title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                val shareUri = recording.outputUri ?: recording.outputUris.firstOrNull()
                Box {
                    IconButton(onClick = { showActions = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多文件操作")
                    }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                        DropdownMenuItem(text = { Text("分享") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            enabled = shareUri != null, onClick = {
                                showActions = false
                                scope.launch {
                                    try {
                                        val intent = withContext(Dispatchers.IO) {
                                            RecordingStorage(context).shareIntent(android.net.Uri.parse(requireNotNull(shareUri)))
                                        }
                                        context.startActivity(Intent.createChooser(intent, "分享录制文件"))
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        android.widget.Toast.makeText(context, "分享失败：${error.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            })
                        DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            enabled = canDelete && published.isNotEmpty(), onClick = {
                                showActions = false; renamePart = 0; renameBase = ""; showRename = true
                            })
                        DropdownMenuItem(text = { Text("导出") }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            enabled = canDelete && published.isNotEmpty(), onClick = { showActions = false; showExportParts = true })
                        DropdownMenuItem(text = { Text("复制路径") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            enabled = shareUri != null, onClick = {
                                showActions = false
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("录制文件路径", shareUri))
                                android.widget.Toast.makeText(context, "路径已复制", android.widget.Toast.LENGTH_SHORT).show()
                            })
                        DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            enabled = canDelete, onClick = { showActions = false; showDelete = true })
                    }
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
    if (showExportParts) {
        AlertDialog(onDismissRequest = { showExportParts = false }, title = { Text("选择导出分段") },
            text = {
                LazyColumn {
                    items(published.size) { index ->
                        TextButton(onClick = {
                            showExportParts = false
                            val base = recording.roomName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
                            onExport(published[index], "${base}_${recording.id}_${index + 1}.${recording.containerFormat}")
                        }) { Text("第 ${index + 1} 段") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showExportParts = false }) { Text("取消") } })
    }
    if (showRename) {
        AlertDialog(onDismissRequest = { showRename = false }, title = { Text("重命名录制文件") },
            text = {
                Column {
                    LazyRow {
                        items(published.size) { index ->
                            FilterChip(selected = renamePart == index, onClick = { renamePart = index },
                                label = { Text("第 ${index + 1} 段") })
                        }
                    }
                    OutlinedTextField(value = renameBase, onValueChange = { renameBase = it },
                        label = { Text("新文件名（不含扩展名）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            }, confirmButton = {
                TextButton(enabled = renameBase.isNotBlank() && renamePart in published.indices, onClick = {
                    showRename = false
                    onRename(published[renamePart], renameBase)
                }) { Text("重命名") }
            }, dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } })
    }
}

private fun TaskStatus.displayName(error: String?): String = when {
    this != TaskStatus.FAILED -> displayName()
    error?.contains("空间", true) == true -> "空间不足"
    error?.contains("进程", true) == true || error?.contains("中断", true) == true -> "进程中断"
    error?.contains("文件", true) == true || error?.contains("封装", true) == true -> "文件损坏"
    error?.contains("网络", true) == true || error?.contains("连接", true) == true ||
        error?.contains("socket", true) == true || error?.contains("HTTP", true) == true ||
        error?.contains("线路", true) == true || error?.contains("直播地址", true) == true -> "网络中断"
    error?.contains("权限", true) == true || error?.contains("发布", true) == true ||
        error?.contains("封装", true) == true || error?.contains("索引", true) == true -> "文件损坏"
    else -> displayName()
}
