package com.streamcap.mobile.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NetworkCell
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.BuildConfig
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.ui.ViewModelFactory

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenLogs: () -> Unit,
    onOpenPlatforms: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory { SettingsViewModel(container.preferences, container.repository) },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showFormatDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.setStorageDirectoryUri(uri.toString())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(14.dp))
        }
        item {
            SettingsSection("基础设置") {
                SettingsRow(
                    title = "程序语言",
                    value = settings.language,
                    modifier = Modifier.clickable { showLanguageDialog = true },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("录制") {
                SettingsRow(
                    title = "可用存储空间",
                    value = formatStorage(container.recordingStorage.recordingRoot().usableSpace),
                )
                HorizontalDivider()
                SettingsRow(
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    title = "保存目录",
                    value = settings.storageDirectoryUri?.let { "已选择自定义目录" } ?: "Movies/StreamCap",
                    modifier = Modifier.clickable { directoryPicker.launch(null) },
                    trailing = settings.storageDirectoryUri?.let {
                        {
                            androidx.compose.material3.IconButton(
                                onClick = { viewModel.setStorageDirectoryUri(null) },
                            ) { Icon(Icons.Outlined.Restore, contentDescription = "恢复默认目录") }
                        }
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "默认格式",
                    value = settings.defaultFormat,
                    modifier = Modifier.clickable { showFormatDialog = true },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "默认清晰度",
                    value = settings.defaultQuality,
                    modifier = Modifier.clickable { showQualityDialog = true },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("后台运行") {
                val power = context.getSystemService(PowerManager::class.java)
                val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
                SettingsRow(
                    title = "电池优化",
                    value = if (unrestricted) "不受限制" else "可能限制后台录制",
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        })
                    },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = "打开电池设置") },
                )
            }
        }
        item {
            SettingsSection("文件命名") {
                SettingsRow(
                    title = "文件名包含标题",
                    value = if (settings.includeTitleInFileName) "已包含" else "不包含",
                    trailing = {
                        Switch(
                            checked = settings.includeTitleInFileName,
                            onCheckedChange = viewModel::setIncludeTitleInFileName,
                        )
                    },
                )
                HorizontalDivider()
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("自定义文件名模板", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.fileNameTemplate,
                        onValueChange = viewModel::setFileNameTemplate,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text("可用：{platform} {anchor_name} {title} {time}")
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("快速命名规则", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "平台" to "{platform}",
                            "作者" to "{anchor_name}",
                            "时间" to "{time}",
                            "标题" to "{title}",
                        ).forEach { (label, token) ->
                            FilterChip(
                                selected = settings.fileNameTemplate.contains(token),
                                onClick = {
                                    viewModel.setFileNameTemplate(
                                        toggleTemplateToken(settings.fileNameTemplate, token),
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                SettingsRow(
                    title = "去除 emoji 符号",
                    value = if (settings.removeEmojiFromFileName) "已启用" else "保留原名",
                    trailing = {
                        Switch(
                            checked = settings.removeEmojiFromFileName,
                            onCheckedChange = viewModel::setRemoveEmojiFromFileName,
                        )
                    },
                )
            }
        }
        item {
            SettingsSection("平台") {
                SettingsRow(
                    modifier = Modifier.clickable(onClick = onOpenPlatforms),
                    title = "平台适配",
                    value = "首批：虎牙、抖音、B站、斗鱼、快手",
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("运行条件") {
                SettingsRow(
                    icon = { Icon(Icons.Outlined.NetworkCell, contentDescription = null) },
                    title = "允许移动数据",
                    value = if (settings.allowMobileData) "已允许" else "仅 Wi-Fi",
                    trailing = {
                        Switch(
                            checked = settings.allowMobileData,
                            onCheckedChange = viewModel::setAllowMobileData,
                        )
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    title = "开机恢复任务",
                    value = if (settings.resumeAfterBoot) "已启用" else "已停用",
                    trailing = {
                        Switch(
                            checked = settings.resumeAfterBoot,
                            onCheckedChange = viewModel::setResumeAfterBoot,
                        )
                    },
                )
            }
        }
        item {
            SettingsSection("诊断") {
                SettingsRow(
                    modifier = Modifier.clickable(onClick = onOpenLogs),
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    title = "任务日志",
                    value = "查看并导出错误日志",
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            Text(
                "StreamCap Android ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showFormatDialog) {
        ChoiceDialog(
            title = "默认保存格式",
            selected = settings.defaultFormat,
            options = listOf("FLV", "TS", "MP4"),
            onDismiss = { showFormatDialog = false },
            onSelect = {
                viewModel.setDefaultFormat(it)
                showFormatDialog = false
            },
        )
    }
    if (showQualityDialog) {
        ChoiceDialog(
            title = "默认清晰度",
            selected = settings.defaultQuality,
            options = listOf("原画", "1080p", "720p", "480p", "音频优先"),
            onDismiss = { showQualityDialog = false },
            onSelect = {
                viewModel.setDefaultQuality(it)
                showQualityDialog = false
            },
        )
    }
    if (showLanguageDialog) {
        ChoiceDialog(
            title = "程序语言",
            selected = settings.language,
            options = listOf("简体中文"),
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                viewModel.setLanguage(it)
                showLanguageDialog = false
            },
        )
    }
}

private fun formatStorage(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

private fun toggleTemplateToken(template: String, token: String): String {
    val changed = if (template.contains(token)) {
        template.replace(token, "")
    } else {
        listOf(template.trim('_', ' '), token).filter(String::isNotBlank).joinToString("_")
    }
    return changed
        .replace(Regex("_{2,}"), "_")
        .trim('_', ' ')
        .ifBlank { "{anchor_name}_{time}" }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Text(option, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) { Column { content() } }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            it()
            Spacer(Modifier.padding(start = 12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
