package com.streamcap.mobile.ui.rooms

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.service.RecordingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRoomScreen(
    container: AppContainer,
    initialUrl: String?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val viewModel: AddRoomViewModel = viewModel(
        factory = ViewModelFactory { AddRoomViewModel(container.repository, container.preferences) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choice by remember { mutableStateOf<ChoiceType?>(null) }
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
        topBar = {
            TopAppBar(
                title = { Text("新增录制") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("直播地址", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "支持虎牙、抖音、B站、斗鱼、快手，以及 FLV / HLS 直链",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL") },
                placeholder = { Text("https://www.huya.com/房间号") },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                minLines = 2,
                maxLines = 3,
                isError = state.error != null,
                supportingText = state.error?.let { message -> { Text(message) } },
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("主播名称（可选）") },
                supportingText = { Text("留空时自动使用平台返回的主播名称") },
                singleLine = true,
            )

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
                        supportingText = { Text("支持 30 分钟到 10080 分钟（7 天）") },
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
                    subtitle = "在通知栏显示进度和停止按钮",
                    checked = state.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = state.url.isNotBlank() && !state.isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(start = 8.dp))
                }
                Text(if (state.isSaving) "正在解析直播间" else "解析并保存任务")
            }
            Spacer(Modifier.height(12.dp))
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
            shape = RoundedCornerShape(8.dp),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
