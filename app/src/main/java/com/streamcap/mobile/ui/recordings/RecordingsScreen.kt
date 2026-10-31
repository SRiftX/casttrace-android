package com.streamcap.mobile.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.heightIn
import com.streamcap.mobile.ui.common.activeRecordingStatuses
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import com.streamcap.mobile.danmaku.VideoExportMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.ui.common.displayName
import com.streamcap.mobile.ui.common.formatBytes
import com.streamcap.mobile.ui.common.formatDateTime
import com.streamcap.mobile.ui.common.formatDuration
import com.streamcap.mobile.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RecordingsScreen(
    contentPadding: PaddingValues,
    onOpenRecording: (Long) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: RecordingsViewModel = hiltViewModel()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val logs by viewModel.recordingEvents.collectAsStateWithLifecycle()
    val storedViewMode by viewModel.storedViewMode.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val fileMessage by viewModel.fileMessage.collectAsStateWithLifecycle()
    val deleteAuthorization by viewModel.deleteAuthorization.collectAsStateWithLifecycle()
    var launchedDeleteAuthorization by rememberSaveable { mutableStateOf(false) }
    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        launchedDeleteAuthorization = false
        viewModel.completeDeleteAuthorization(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val deleteDirectoryPermission = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        launchedDeleteAuthorization = false
        val granted = uri != null && runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            uri == deleteAuthorization?.tree
        }.getOrDefault(false)
        viewModel.completeDeleteAuthorization(granted)
    }
    LaunchedEffect(deleteAuthorization) {
        val request = deleteAuthorization
        if (request == null) launchedDeleteAuthorization = false
        else if (!launchedDeleteAuthorization) {
            launchedDeleteAuthorization = true
            try {
                if (request.sender != null) deleteConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(request.sender).build())
                else deleteDirectoryPermission.launch(request.tree)
            } catch (_: Exception) {
                launchedDeleteAuthorization = false
                viewModel.completeDeleteAuthorization(false)
            }
        }
    }
    var exportSource by rememberSaveable { mutableStateOf<String?>(null) }
    var exportSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportMode by rememberSaveable { mutableStateOf(VideoExportMode.ORIGINAL) }
    var exportChoiceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportChoiceSource by rememberSaveable { mutableStateOf<String?>(null) }
    var exportChoiceName by rememberSaveable { mutableStateOf("") }
    var shareChoiceIds by remember { mutableStateOf<List<Long>?>(null) }
    var shareMode by rememberSaveable { mutableStateOf(VideoExportMode.ORIGINAL) }
    val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { directory ->
        val id = exportSessionId
        exportSessionId = null
        if (directory != null && id != null) viewModel.exportAll(id, directory, exportMode)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val source = exportSource
        val id = exportSessionId
        exportSource = null
        exportSessionId = null
        if (destination != null && source != null) viewModel.export(source, destination, id, exportMode)
    }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.shareReady.collect { intent ->
            try {
                context.startActivity(Intent.createChooser(intent, "分享录制文件"))
            } catch (failure: Exception) {
                snackbars.showSnackbar("无法打开分享：${failure.message}")
            }
        }
    }
    var viewMode by rememberSaveable { mutableStateOf(RecordingViewMode.COMPACT) }
    var order by rememberSaveable { mutableStateOf("最新") }
    var confirmBatch by remember { mutableStateOf<List<RecordingSession>?>(null) }
    var filters by remember { mutableStateOf(RecordingFilters()) }
    var startDatePickerVisible by remember { mutableStateOf(false) }
    var endDatePickerVisible by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var deleteSuccess by remember { mutableStateOf<String?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    shareChoiceIds?.let { ids ->
        val targets = recordings.filter { it.id in ids }
        val hasDanmaku = targets.size == ids.size && targets.all { it.danmakuPath != null && it.danmakuEventCount > 0 }
        AlertDialog(onDismissRequest = { shareChoiceIds = null }, title = { Text("视频分享") }, text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { shareMode = VideoExportMode.ORIGINAL }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(shareMode == VideoExportMode.ORIGINAL, onClick = { shareMode = VideoExportMode.ORIGINAL })
                    Text("无弹幕原视频")
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = hasDanmaku) { shareMode = VideoExportMode.WITH_DANMAKU }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(shareMode == VideoExportMode.WITH_DANMAKU, enabled = hasDanmaku,
                        onClick = { shareMode = VideoExportMode.WITH_DANMAKU })
                    Text("带弹幕 MP4")
                }
                Text(if (hasDanmaku) "分享全部已保存分段。带弹幕模式使用当前样式生成视频，完成后选择接收应用。"
                    else "分享全部已保存分段。选中的录制中有未保存弹幕的项目，只能分享原视频。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = exportProgress == null && (shareMode == VideoExportMode.ORIGINAL || hasDanmaku), onClick = {
            shareChoiceIds = null
            viewModel.share(ids, shareMode)
        }) { Text(if (shareMode == VideoExportMode.WITH_DANMAKU) "生成并分享" else "继续分享") } },
            dismissButton = { TextButton(onClick = { shareChoiceIds = null }) { Text("取消") } })
    }
    exportChoiceId?.let { id ->
        val hasDanmaku = recordings.firstOrNull { it.id == id }?.let { it.danmakuPath != null && it.danmakuEventCount > 0 } == true
        AlertDialog(onDismissRequest = { exportChoiceId = null }, title = { Text("视频导出") }, text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { exportMode = VideoExportMode.ORIGINAL }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(exportMode == VideoExportMode.ORIGINAL, onClick = { exportMode = VideoExportMode.ORIGINAL })
                    Text("无弹幕原视频")
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = hasDanmaku) { exportMode = VideoExportMode.WITH_DANMAKU }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(exportMode == VideoExportMode.WITH_DANMAKU, enabled = hasDanmaku,
                        onClick = { exportMode = VideoExportMode.WITH_DANMAKU })
                    Text("带弹幕 MP4")
                }
                Text(if (hasDanmaku) "带弹幕模式使用当前弹幕样式，需重新编码。整场导出按分段保存。" else "这场录制没有已保存弹幕。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = exportMode == VideoExportMode.ORIGINAL || hasDanmaku, onClick = {
            exportSessionId = id
            exportSource = exportChoiceSource
            exportChoiceId = null
            if (exportSource == null) exportDirectoryPicker.launch(null)
            else exportPicker.launch(if (exportMode == VideoExportMode.WITH_DANMAKU)
                "${exportChoiceName.substringBeforeLast('.')}_弹幕.mp4" else exportChoiceName)
        }) { Text("选择保存位置") } }, dismissButton = { TextButton(onClick = { exportChoiceId = null }) { Text("取消") } })
    }
    val platformByRoomId = rooms.associate { it.id to it.platform }
    val platforms = recordings.mapNotNull { platformByRoomId[it.roomId] }.distinct().sorted()
    val roomNames = recordings.map { it.roomName }.distinct().sorted()
    val formats = recordings.map { it.containerFormat.uppercase() }.distinct().sorted()
    val hasActiveConditions = filters.activeCount > 0 || filters.query.isNotBlank() || order != "最新"
    val filteredRecordings = recordings.filter { recording ->
        filters.matches(
            recording = recording,
            platformName = platformByRoomId[recording.roomId].orEmpty(),
            interrupted = logs.any { it.sessionId == recording.id && (it.eventType.contains("INTERRUPTED") || it.eventType == "STREAM_REFRESH_REQUESTED" || it.eventType == "RECORDING_PARTIAL_SAVED") },
        )
    }.let { list -> when (order) {
        "最长" -> list.sortedWith(compareByDescending<RecordingSession> { it.durationSeconds }.thenByDescending { it.id })
        "最大" -> list.sortedWith(compareByDescending<RecordingSession> { it.totalBytes }.thenByDescending { it.id })
        else -> list.sortedWith(compareByDescending<RecordingSession> { it.startedAt }.thenByDescending { it.id })
    } }
    LaunchedEffect(recordings.map { it.id }) { selectedIds = selectedIds.intersect(recordings.map { it.id }.toSet()) }
    LaunchedEffect(storedViewMode) {
        if (storedViewMode != null) viewMode = RecordingViewMode.fromStored(storedViewMode)
    }
    BackHandler(multiSelectMode) { multiSelectMode = false; selectedIds = emptySet() }
    LaunchedEffect(deleteSuccess) { deleteSuccess?.let { snackbars.showSnackbar(it); deleteSuccess = null } }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .widthIn(max = 1360.dp),
        contentPadding = PaddingValues(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.animateContentSize()) {
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
                                .clickable { selectedIds = filteredRecordings.filter { it.status !in activeRecordingStatuses }.map { it.id }.toSet() }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "全选当前筛选结果")
                        }
                        IconButton(enabled = selectedIds.isNotEmpty() && exportProgress == null, onClick = {
                            shareMode = VideoExportMode.ORIGINAL
                            shareChoiceIds = selectedIds.toList()
                        }) { Icon(Icons.Outlined.Share, contentDescription = "分享已选文件") }
                        IconButton(onClick = {
                        confirmBatch = recordings.filter { it.id in selectedIds && it.status !in activeRecordingStatuses }.takeIf { it.isNotEmpty() }
                        }) { Icon(Icons.Outlined.Delete, contentDescription = "删除已选文件") }
                        IconButton(onClick = {
                            recordings.filter { it.id in selectedIds && it.status in activeRecordingStatuses }.forEach { recording ->
                                context.startService(com.streamcap.mobile.service.RecordingService.pauseIntent(context, recording.roomId))
                            }
                        }) { Icon(Icons.Outlined.Pause, contentDescription = "暂停并保存已选录制") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("视频库", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("${filteredRecordings.size} / ${recordings.size} 场录制",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { multiSelectMode = true }) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = "进入多选")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            }
        }
        item {
            RecoverySummary(recordings, logs, onOpenRecording)
            OutlinedTextField(value = filters.query, onValueChange = { filters = filters.copy(query = it) },
                label = { Text("搜索主播、标题、平台或日期") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${filteredRecordings.size} 场录制", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (hasActiveConditions) TextButton(onClick = { filters = RecordingFilters(); order = "最新" }) { Text("重置") }
                OutlinedButton(onClick = { filtersExpanded = true }) {
                    Text(if (filters.activeCount > 0) "筛选 · ${filters.activeCount}" else "筛选与排序")
                }
            }
            if (hasActiveConditions) {
                Text(
                    filterSummary(filters, order),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("视图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RecordingViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = viewMode == mode,
                        onClick = {
                            viewMode = mode
                            viewModel.setRecordingViewMode(mode.storedValue)
                        },
                        label = { Text(mode.label) },
                    )
                }
            }
            fileMessage?.let { message ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message)
                        Row {
                            TextButton(onClick = viewModel::dismissFileMessage) { Text("关闭结果") }
                            val destination by viewModel.exportDestination.collectAsStateWithLifecycle()
                            destination?.let { uri ->
                                TextButton(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(
                                        android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri)),
                                        "vnd.android.document/directory").putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                        .onFailure { scope.launch { snackbars.showSnackbar("系统没有可打开此目录的应用，请使用文件管理器查看所选目录") } }
                                }) { Text("打开目录") }
                            }
                        }
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
                    "没有匹配的视频，可清除搜索与筛选",
                    modifier = Modifier.padding(vertical = 36.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(filteredRecordings, key = { it.id }) { recording ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.widthIn(max = if (maxWidth >= 960.dp) 920.dp else maxWidth).fillMaxWidth().animateItem().align(Alignment.TopCenter), contentAlignment = Alignment.TopCenter) {
                RecordingRow(recording, onOpenRecording,
                    detailed = viewMode == RecordingViewMode.DETAILED,
                    selectionMode = multiSelectMode,
                    hasInterruption = logs.any { it.sessionId == recording.id && (it.eventType == "STREAM_REFRESH_REQUESTED" || it.eventType == "RECORDING_PARTIAL_SAVED" || it.eventType.contains("INTERRUPTED")) },
                    evidence = logs.filter { it.sessionId == recording.id && it.eventType == "STREAM_REFRESH_REQUESTED" }.let { events ->
                        if (events.isEmpty()) null else "取流重试 ${events.size} 次 · 最近 ${formatDateTime(events.maxOf { it.createdAt })}；精确缺失区间未知"
                    },
                    sourceSelection = logs.firstOrNull { it.roomId == recording.roomId && it.eventType in setOf("QUALITY_SELECTED", "QUALITY_FALLBACK") &&
                        recording.startedAt != null && it.createdAt in (recording.startedAt - 60_000)..recording.startedAt }?.message,
                    relatedRecording = if (logs.any { it.sessionId == recording.id && it.eventType == "RECORDING_COMPLETED" && it.message.contains("准备自动续录") }) {
                        recordings.filter { it.roomId == recording.roomId && it.id != recording.id && (it.startedAt ?: 0L) >= (recording.endedAt ?: Long.MAX_VALUE) }.minByOrNull { it.startedAt ?: Long.MAX_VALUE }
                    } else null,
                    selected = recording.id in selectedIds,
                    onSelect = { checked -> if (recording.status !in activeRecordingStatuses) selectedIds = if (checked) selectedIds + recording.id else selectedIds - recording.id },
                    onRename = { source, name -> viewModel.rename(recording.id, source, name) },
                    onExportAll = {
                        exportChoiceId = recording.id
                        exportChoiceSource = null
                        exportMode = VideoExportMode.ORIGINAL
                    },
                    onShare = {
                        shareMode = VideoExportMode.ORIGINAL
                        shareChoiceIds = listOf(recording.id)
                    },
                    onGenerateDanmaku = {
                        scope.launch {
                            runCatching { viewModel.createDanmakuSimulation(recording.id) }
                                .onSuccess { snackbars.showSnackbar("已生成测试弹幕侧车文件，可在播放器中查看") }
                                .onFailure { snackbars.showSnackbar("测试弹幕未生成：${it.message}") }
                        }
                    },
                    onExport = { source, name ->
                        exportChoiceId = recording.id
                        exportChoiceSource = source
                        exportChoiceName = name
                        exportMode = VideoExportMode.ORIGINAL
                    },
                    onDelete = { target -> viewModel.delete(target) { error ->
                        if (error != null) deleteError = error
                        else deleteSuccess = "录制文件已删除"
                    } },
                )
                }
                }
            }
        }
    }
    SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(contentPadding))
    }
    exportProgress?.let { progress ->
        AlertDialog(onDismissRequest = {}, title = { Text("正在处理文件") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(progress)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = viewModel::cancelExport) { Text("取消处理") } })
    }
    confirmBatch?.let { targets ->
        AlertDialog(onDismissRequest = { confirmBatch = null }, title = { Text("删除 ${targets.size} 场录制？") },
            text = { Text("将删除所选录制的全部分段和恢复文件，无法撤销。正在录制的内容不参与批量删除。") },
            confirmButton = { TextButton(onClick = {
                confirmBatch = null
                viewModel.deleteMany(targets) { error ->
                    if (error == null) { deleteSuccess = "已删除 ${targets.size} 场录制"; selectedIds = emptySet() }
                    else deleteError = error
                }
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { confirmBatch = null }) { Text("取消") } })
    }
    if (filtersExpanded) {
        RecordingFiltersSheet(
            filters = filters,
            onFiltersChange = { filters = it },
            order = order,
            onOrderChange = { order = it },
            platforms = platforms,
            rooms = roomNames,
            formats = formats,
            onDismiss = { filtersExpanded = false },
            onReset = { filters = RecordingFilters(); order = "最新" },
            onChooseDate = { isStart -> if (isStart) startDatePickerVisible = true else endDatePickerVisible = true },
        )
    }
    if (startDatePickerVisible) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = filters.startInclusive ?: System.currentTimeMillis())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { startDatePickerVisible = false },
            confirmButton = { TextButton(onClick = {
                val selected = pickerState.selectedDateMillis
                filters = filters.copy(startInclusive = selected?.let(::startOfDayMillis))
                startDatePickerVisible = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { startDatePickerVisible = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
    }
    if (endDatePickerVisible) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = filters.endExclusive?.minus(1) ?: System.currentTimeMillis())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { endDatePickerVisible = false },
            confirmButton = { TextButton(onClick = {
                val selected = pickerState.selectedDateMillis
                filters = filters.copy(endExclusive = selected?.let(::endOfDayMillis))
                endDatePickerVisible = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { endDatePickerVisible = false }) { Text("取消") } },
        ) { DatePicker(state = pickerState) }
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
    detailed: Boolean,
    selectionMode: Boolean,
    hasInterruption: Boolean,
    evidence: String?,
    sourceSelection: String?,
    relatedRecording: RecordingSession?,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onExport: (String, String) -> Unit,
    onExportAll: () -> Unit,
    onShare: () -> Unit,
    onGenerateDanmaku: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (RecordingSession) -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }
    var showExportParts by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(recording.id) { mutableStateOf(detailed) }
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
    LaunchedEffect(detailed) { detailsExpanded = detailed }
    Surface(
        modifier = Modifier
            .widthIn(max = 920.dp)
            .fillMaxWidth()
            .clickable(enabled = canOpen || selectionMode) {
                if (selectionMode) onSelect(!selected) else onOpenRecording(recording.id)
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(if (detailed) 18.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (detailed) 14.dp else 8.dp),
        ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(selectionMode) { Checkbox(checked = selected, enabled = canDelete, onCheckedChange = onSelect) }
            RecordingCover(published.firstOrNull())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = if (detailed) 12.dp else 8.dp),
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
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatDuration(recording.durationSeconds)} · ${formatBytes(recording.totalBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDateTime(recording.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
            val statusText = when {
                recording.status == TaskStatus.FINALIZING -> "正在保存"
                recording.status in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING) -> "预览"
                recording.status == TaskStatus.FAILED && canOpen -> "已保存片段"
                else -> recording.status.displayName(recording.errorMessage)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = when {
                        recording.status == TaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                        recording.status in activeRecordingStatuses -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            recording.status == TaskStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                            recording.status in activeRecordingStatuses -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onOpenRecording(recording.id) }, enabled = canOpen) {
                    Text(if (canOpen) "查看视频" else "查看状态")
                }
                if (published.isNotEmpty() && !selectionMode) {
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        Text(if (detailsExpanded) "收起详情" else "查看详情")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (selectionMode) {
                    Text("点击卡片选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val shareUri = recording.outputUri ?: recording.outputUris.firstOrNull()
                Box {
                    IconButton(onClick = { showActions = true }, enabled = !selectionMode) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多文件操作")
                    }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                        if (recording.status == TaskStatus.FAILED && recording.temporaryPath != null &&
                            (recording.errorMessage?.startsWith("文件发布失败：") == true ||
                                recording.errorMessage?.startsWith("应用进程中断，恢复失败：") == true)) {
                            DropdownMenuItem(text = { Text("重试保存已有片段") }, onClick = {
                                showActions = false
                                com.streamcap.mobile.service.RecordingService.recover(context, recording.id)
                                android.widget.Toast.makeText(context, "已请求重试保存；请先确保空间和目录权限可用", android.widget.Toast.LENGTH_LONG).show()
                            })
                        }
                        DropdownMenuItem(text = { Text("分享") }, leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            enabled = canDelete && published.isNotEmpty(), onClick = {
                                showActions = false
                                onShare()
                            })
                        DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            enabled = canDelete && published.isNotEmpty(), onClick = {
                                showActions = false; renamePart = 0; renameBase = ""; showRename = true
                            })
                        DropdownMenuItem(text = { Text("选择片段导出") }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            enabled = canDelete && published.isNotEmpty(), onClick = { showActions = false; showExportParts = true })
                        DropdownMenuItem(text = { Text("导出整场（全部分段）") }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            enabled = canDelete && published.isNotEmpty(), onClick = { showActions = false; onExportAll() })
                        DropdownMenuItem(text = { Text("复制路径") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            enabled = shareUri != null, onClick = {
                                showActions = false
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("录制文件路径", shareUri))
                                android.widget.Toast.makeText(context, "路径已复制", android.widget.Toast.LENGTH_SHORT).show()
                            })
                        DropdownMenuItem(text = { Text("生成测试弹幕") }, leadingIcon = { Icon(Icons.Outlined.Subtitles, null) },
                            enabled = canDelete && published.isNotEmpty() && recording.danmakuPath == null,
                            onClick = { showActions = false; onGenerateDanmaku() })
                        DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            enabled = canDelete, onClick = { showActions = false; showDelete = true })
                    }
                }
            }
            if (detailsExpanded) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ) {
                    Column(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("录制详情", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("文件格式 · ${recording.containerFormat.uppercase()} · ${published.size} 段", style = MaterialTheme.typography.bodySmall)
                                ActualSpecification(published.firstOrNull())
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("启动画质", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(sourceSelection ?: "记录未知，以实际规格为准", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (hasInterruption || recording.errorMessage?.contains("中断") == true) {
                            Text("检测到中断，缺失区间未精确测量。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        evidence?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        recording.errorMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        relatedRecording?.let { next ->
                            TextButton(onClick = { onOpenRecording(next.id) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                                Text("查看后续录制 · ${formatDateTime(next.startedAt)}")
                            }
                            Text("后续录制独立保存，不代表画面无缝连续。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("保存进度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SaveTimeline(recording)
                        }
                    }
                }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除录制文件？") },
            text = { Text("将删除这场录制的全部分段及待恢复文件。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete(recording) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
    if (showExportParts) {
        AlertDialog(onDismissRequest = { showExportParts = false }, title = { Text("片段详情 · 选择片段导出") },
            text = {
                LazyColumn {
                    items(published.size) { index ->
                        TextButton(onClick = {
                            showExportParts = false
                            val base = recording.roomName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
                            onExport(published[index], "${base}_${recording.id}_${index + 1}.${recording.containerFormat}")
                        }) { SegmentDescription(published[index], index) }
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

@Composable
private fun SaveTimeline(recording: RecordingSession) {
    val stage = when {
        recording.status in activeRecordingStatuses -> 0
        recording.status == TaskStatus.FINALIZING -> 1
        recording.status == TaskStatus.FAILED -> 3
        recording.outputUri != null || recording.outputUris.isNotEmpty() -> 2
        else -> 0
    }
    val labels = listOf("录制", "封装", "已保存", if (recording.status == TaskStatus.FAILED) "失败" else "完成")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            val active = index <= stage
            Text(
                text = if (index < labels.lastIndex) "● $label" else "● $label",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    recording.status == TaskStatus.FAILED && index == labels.lastIndex -> MaterialTheme.colorScheme.error
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun TaskStatus.displayName(error: String?): String =
    if (this == TaskStatus.FAILED) "需要处理" else if (this == TaskStatus.PAUSED) "已保存" else displayName()

@Composable
private fun ActiveFilterChip(label: String, onRemove: () -> Unit) {
    FilterChip(selected = true, onClick = onRemove, label = { Text("$label  ×", maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingFiltersSheet(
    filters: RecordingFilters,
    onFiltersChange: (RecordingFilters) -> Unit,
    order: String,
    onOrderChange: (String) -> Unit,
    platforms: List<String>,
    rooms: List<String>,
    formats: List<String>,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onChooseDate: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("筛选与排序", style = MaterialTheme.typography.headlineSmall)
                    Text("组合条件缩小录制列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onReset) { Text("重置") }
            }
            FilterSection("排序方式", "选择列表排列顺序") {
                FilterChoices(listOf("最新", "最长", "最大"), order, onOrderChange)
            }
            FilterSection("录制状态", "按录制结果筛选") {
                FilterChoices(RecordingStatusFilter.entries.map { it.label }, filters.status.label) { label ->
                    onFiltersChange(filters.copy(status = RecordingStatusFilter.entries.first { it.label == label }))
                }
            }
            if (platforms.isNotEmpty()) FilterSection("平台", filters.platform ?: "全部平台") {
                FilterChoices(listOf("全部平台") + platforms, filters.platform ?: "全部平台") {
                    onFiltersChange(filters.copy(platform = it.takeUnless { it == "全部平台" }))
                }
            }
            if (rooms.isNotEmpty()) FilterSection("直播间", filters.roomName ?: "全部直播间") {
                FilterChoices(listOf("全部直播间") + rooms, filters.roomName ?: "全部直播间") {
                    onFiltersChange(filters.copy(roomName = it.takeUnless { it == "全部直播间" }))
                }
            }
            FilterSection("录制日期", dateRangeLabel(filters)) {
                FilterChoices(listOf("不限时间", "今天", "近 7 天", "近 30 天", "更早"), datePreset(filters)) { preset ->
                    val now = System.currentTimeMillis()
                    val today = startOfDayMillis(now)
                    val range = when (preset) {
                        "今天" -> today to endOfDayMillis(now)
                        "近 7 天" -> startOfDayMillis(now - 6L * 86_400_000) to endOfDayMillis(now)
                        "近 30 天" -> startOfDayMillis(now - 29L * 86_400_000) to endOfDayMillis(now)
                        "更早" -> null to today
                        else -> null to null
                    }
                    onFiltersChange(filters.copy(startInclusive = range.first, endExclusive = range.second))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onChooseDate(true) }, modifier = Modifier.weight(1f)) { Text("${filters.startInclusive?.let(::formatFilterDate) ?: "开始日期"}") }
                    OutlinedButton(onClick = { onChooseDate(false) }, modifier = Modifier.weight(1f)) { Text("${filters.endExclusive?.minus(1)?.let(::formatFilterDate) ?: "结束日期"}") }
                }
            }
            if (formats.isNotEmpty()) FilterSection("文件格式", filters.format ?: "全部格式") {
                FilterChoices(listOf("全部格式") + formats, filters.format ?: "全部格式") {
                    onFiltersChange(filters.copy(format = it.takeUnless { it == "全部格式" }))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FilterSection(title: String, summary: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun FilterChoices(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values) { value -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(value, maxLines = 1) }) }
    }
}

private fun datePreset(filters: RecordingFilters): String {
    if (filters.startInclusive == null && filters.endExclusive == null) return "不限时间"
    val now = System.currentTimeMillis()
    val today = startOfDayMillis(now)
    return when {
        filters.startInclusive == today && filters.endExclusive == endOfDayMillis(now) -> "今天"
        filters.startInclusive == startOfDayMillis(now - 6L * 86_400_000) && filters.endExclusive == endOfDayMillis(now) -> "近 7 天"
        filters.startInclusive == startOfDayMillis(now - 29L * 86_400_000) && filters.endExclusive == endOfDayMillis(now) -> "近 30 天"
        filters.startInclusive == null && filters.endExclusive == today -> "更早"
        else -> "自选日期"
    }
}

private fun dateRangeLabel(filters: RecordingFilters): String =
    if (filters.startInclusive == null && filters.endExclusive == null) "不限时间"
    else "${filters.startInclusive?.let(::formatFilterDate) ?: "更早"} – ${filters.endExclusive?.minus(1)?.let(::formatFilterDate) ?: "今天"}"

private fun filterSummary(filters: RecordingFilters, order: String): String {
    val parts = buildList {
        if (filters.query.isNotBlank()) add("搜索：${filters.query.trim()}")
        if (order != "最新") add("排序：$order")
        if (filters.status != RecordingStatusFilter.ALL) add("状态：${filters.status.label}")
        filters.platform?.let { add("平台：$it") }
        filters.roomName?.let { add("直播间：$it") }
        filters.format?.let { add("格式：$it") }
        if (filters.startInclusive != null || filters.endExclusive != null) {
            add("日期：${formatFilterDate(filters.startInclusive)} – ${formatFilterDate(filters.endExclusive?.minus(1))}")
        }
    }
    return parts.joinToString(" · ")
}

private fun formatFilterDate(timestamp: Long?): String = timestamp?.let {
    SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(it))
} ?: "不限"

private fun startOfDayMillis(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDayMillis(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDayMillis(timestamp)
    add(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis
