package com.streamcap.mobile.ui.rooms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.data.preferences.RecordingTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRoomScreen(
    container: AppContainer,
    initialUrl: String?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onOpenBackup: () -> Unit = {},
) {
    val viewModel: AddRoomViewModel = viewModel(
        factory = ViewModelFactory { AddRoomViewModel(container.repository, container.preferences) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val templates by container.preferences.templates.collectAsStateWithLifecycle(emptyList())
    var advanced by rememberSaveable { mutableStateOf(false) }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var choice by remember { mutableStateOf<ChoiceType?>(null) }
    var showMore by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.setStorageDirectoryUri(uri.toString())
    }

    LaunchedEffect(initialUrl) { viewModel.setInitialUrl(initialUrl) }
    LaunchedEffect(state.savedRoomId) {
        state.savedRoomId?.let { id ->
            if (state.scheduledMonitoringEnabled) RecordingService.monitor(context)
            onSaved(id)
        }
    }

    Scaffold(
        // The outer app also owns a Scaffold. Disable the inner default system
        // inset calculation so portrait mode does not subtract the navigation
        // area twice when the fixed action bar is present.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(if (batchMode) "批量导入直播间" else "添加直播间") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { showMore = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多添加方式")
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("导入或导出直播间") },
                                onClick = { showMore = false; onOpenBackup() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Keep the primary action visible on narrow/portrait displays and when
            // the IME is open. Scaffold reserves the bar's height, while these
            // insets account for the gesture/navigation area and keyboard.
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                shadowElevation = 4.dp,
            ) {
                Button(
                    onClick = {
                        if (batchMode) {
                            if (state.batchPreview.isEmpty()) viewModel.previewBatch() else viewModel.importBatch()
                        } else viewModel.save()
                    },
                    enabled = if (batchMode) (state.batchText.isNotBlank() || state.batchPreview.isNotEmpty()) && !state.batchBusy
                        else state.url.isNotBlank() && !state.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "确认主播并添加" }
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.isSaving || state.batchBusy) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(start = 8.dp))
                    }
                    Text(when {
                        batchMode && state.batchBusy -> "正在处理"
                        batchMode && state.batchPreview.isEmpty() -> "预览导入"
                        batchMode -> "确认添加 ${state.batchPreview.count { it.status == "可添加" }} 个直播间"
                        state.isSaving -> "正在解析直播间"
                        else -> "确认主播并添加"
                    })
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        // Derive the layout from the actual constraints instead of relying only
        // on Configuration.orientation. MuMu and some foldables can report a
        // stale orientation while the app content is already portrait, which
        // previously rendered the two-column form and clipped its fields.
        val wideForm = maxWidth >= 700.dp && maxWidth > maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .widthIn(max = 1120.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (batchMode) {
                OutlinedTextField(
                    value = state.batchText,
                    onValueChange = viewModel::setBatchText,
                    label = { Text("直播间链接，一行一个") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("最多预览 200 行；仅导入房间链接，不包含账号凭据或保存目录。", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.batchPreview.forEachIndexed { index, line ->
                    Text("${index + 1}. ${line.info?.platform ?: line.url ?: line.original} · ${line.info?.anchorName ?: line.status} · ${line.status}",
                        style = MaterialTheme.typography.bodySmall)
                }
                state.batchResult?.let { result ->
                    Text("已添加 ${result.added} · 跳过 ${result.skipped} · 失败 ${result.failed.size}")
                    if (result.failed.isNotEmpty()) {
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("导入失败项", result.failed.joinToString("\n")))
                        }) { Text("复制失败项") }
                    }
                }
            } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("直播地址", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "粘贴直播间链接或分享文字，检测后确认主播",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wideForm) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                modifier = Modifier.weight(1.25f),
                label = { Text("直播链接或分享文字") },
                placeholder = { Text("粘贴分享链接或整段分享文字") },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                minLines = 1,
                maxLines = 2,
                isError = state.error != null,
                supportingText = state.error?.let { message -> { Text(message) } },
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                modifier = Modifier.weight(1f),
                label = { Text("主播名称（可选）") },
                supportingText = { Text("留空时自动使用平台返回的主播名称") },
                singleLine = true,
            )
            }
            else {
                OutlinedTextField(value = state.url, onValueChange = viewModel::setUrl, modifier = Modifier.fillMaxWidth(),
                    label = { Text("直播链接或分享文字") }, placeholder = { Text("粘贴分享链接或整段分享文字") },
                    leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 1, maxLines = 2, isError = state.error != null, supportingText = state.error?.let { message -> { Text(message) } })
                OutlinedTextField(value = state.displayName, onValueChange = viewModel::setDisplayName, modifier = Modifier.fillMaxWidth(),
                    label = { Text("主播名称（可选）") }, supportingText = { Text("留空时自动使用平台返回的主播名称") }, singleLine = true)
            }
            Text("先确认主播，再开始录制。默认 ${state.outputFormat} · ${state.quality} · 每 ${state.segmentMinutes} 分钟保存一段。",
                style = MaterialTheme.typography.bodySmall)
            if (state.sharedText != null) {
                Text("分享内容未找到可识别链接，原文已保留；可编辑地址重试。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("分享原文", state.sharedText))
                }) { Text("复制分享原文") }
            }
            state.preview?.let { info ->
                Text("识别结果：${info.platform} · ${info.anchorName}" +
                    (info.roomTitle?.let { " · $it" } ?: ""))
                if (state.previewDuplicate) Text("这个直播间已存在，不会覆盖原设置", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = viewModel::preview, enabled = !state.isSaving && state.url.isNotBlank()) { Text("检测分享链接并确认主播") }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "展开高级设置") }
            if (templates.isNotEmpty()) {
                Text("快速应用模板", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.take(4).forEach { template ->
                        androidx.compose.material3.FilterChip(selected = false, onClick = { viewModel.applyTemplate(template) }, label = { Text(template.name, maxLines = 1) })
                    }
                }
            }
            AnimatedVisibility(visible = advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FormSection("录制内容") {
                ChoiceRow("媒体类型", state.mediaType) { choice = ChoiceType.MEDIA }
                HorizontalDivider()
                ChoiceRow("保存格式", state.outputFormat) { choice = ChoiceType.FORMAT }
                HorizontalDivider()
                ChoiceRow("录制清晰度", state.quality) { choice = ChoiceType.QUALITY }
                HorizontalDivider()
                SwitchRow(
                    title = "优先 FLV 直录",
                    subtitle = "平台同时提供两种线路时优先选择 FLV",
                    checked = state.flvDirectDownload,
                    onCheckedChange = viewModel::setFlvDirectDownload,
                )
            }

            FormSection("文件保存") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { directoryPicker.launch(null) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("保存目录")
                        Text(
                            state.storageDirectoryUri?.let { "已选择自定义目录" } ?: "Movies/StreamCap",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
                if (state.storageDirectoryUri != null) {
                    TextButton(
                        onClick = { viewModel.setStorageDirectoryUri(null) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("恢复默认目录") }
                }
                HorizontalDivider()
                SwitchRow(
                    title = "自动分段",
                    subtitle = "每段单独保存为可播放文件",
                    checked = state.segmentEnabled,
                    onCheckedChange = viewModel::setSegmentEnabled,
                )
                if (state.segmentEnabled) {
                    OutlinedTextField(
                        value = state.segmentMinutes,
                        onValueChange = viewModel::setSegmentMinutes,
                        label = { Text("单段时长（分钟）") },
                        supportingText = { Text("默认 30 分钟；支持 1 到 10080 分钟（7 天）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                        singleLine = true,
                    )
                }
            }

            FormSection("任务行为") {
                SwitchRow(
                    title = "定时监控开播",
                    subtitle = "主播开播后自动开始录制",
                    checked = state.scheduledMonitoringEnabled,
                    onCheckedChange = viewModel::setScheduledMonitoringEnabled,
                )
                HorizontalDivider()
                SwitchRow(
                    title = "录制通知",
                    subtitle = "在开始录制时按需申请，显示进度和暂停保存操作",
                    checked = state.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                )
                HorizontalDivider()
                OutlinedTextField(
                    value = state.checkIntervalSeconds,
                    onValueChange = { viewModel.setCheckIntervalSeconds(it) },
                    label = { Text("开播检查间隔（秒）") },
                    supportingText = { Text("支持 15 到 3600 秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    singleLine = true,
                )
            }

            }
            Spacer(Modifier.height(8.dp))
        }
        }
        }
    }
    }

    choice?.let { type ->
        val options = when (type) {
            ChoiceType.MEDIA -> listOf("视频")
            ChoiceType.FORMAT -> listOf("FLV", "TS", "MP4")
            ChoiceType.QUALITY -> listOf("原画", "1080p", "720p", "480p", "流畅")
        }
        val selected = when (type) {
            ChoiceType.MEDIA -> state.mediaType
            ChoiceType.FORMAT -> state.outputFormat
            ChoiceType.QUALITY -> state.quality
        }
        ChoiceDialog(
            title = when (type) {
                ChoiceType.MEDIA -> "媒体类型"
                ChoiceType.FORMAT -> "保存格式"
                ChoiceType.QUALITY -> "录制清晰度"
            },
            selected = selected,
            options = options,
            onDismiss = { choice = null },
            onSelect = {
                when (type) {
                    ChoiceType.MEDIA -> viewModel.setMediaType(it)
                    ChoiceType.FORMAT -> viewModel.setOutputFormat(it)
                    ChoiceType.QUALITY -> viewModel.setQuality(it)
                }
                choice = null
            },
        )
    }
}

private enum class ChoiceType { MEDIA, FORMAT, QUALITY }

@Composable
private fun FormSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Column { content() } }
    }
}

@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun ChoiceDialog(
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
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 5.dp),
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
