package com.streamcap.mobile.ui.settings

import androidx.compose.foundation.layout.heightIn
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.BuildConfig
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.data.preferences.RecordingTemplate
import com.streamcap.mobile.ui.theme.ThemePalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onOpenLogs: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenUserGuide: () -> Unit,
    onOpenPlatforms: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory { SettingsViewModel(context, container.preferences, container.repository) },
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showFormatDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var powerUnrestricted by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, context) {
        val refresh = {
            powerUnrestricted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
            .widthIn(max = 1120.dp)
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
                    title = "录制通知",
                    value = if (notificationsEnabled) "已开启" else "未开启，建议打开",
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = "打开通知设置") },
                )
            }
        }
        item {
            SettingsSection("外观") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemePalette.entries.forEach { palette ->
                        FilterChip(
                            selected = settings.themePalette == palette.id,
                            onClick = { viewModel.setThemePalette(palette.id) },
                            label = { Text(palette.label) },
                            leadingIcon = {
                                Spacer(
                                    Modifier.size(20.dp)
                                        .background(palette.preview, CircleShape),
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("录制默认值 · 格式、画质和间隔仅用于新直播间") {
                SettingsRow(
                    title = "存储管理",
                    value = "查看视频、待恢复媒体和预览缓存",
                    modifier = Modifier.clickable(onClick = onOpenStorage),
                )
                HorizontalDivider()
                SettingsRow(
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    title = "保存目录",
                    value = settings.storageDirectoryUri?.let { "已选择自定义目录" } ?: "Movies/StreamCap · 未指定房间目录时使用",
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
                SettingsRow(
                    title = "电池优化",
                    value = if (powerUnrestricted) "已豁免电池优化" else "未豁免，可能被系统暂停",
                    modifier = Modifier.clickable {
                        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        runCatching { context.startActivity(request) }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = "打开电池设置") },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "后台启动与自启动",
                    value = if (isHuaweiDevice()) "应用启动管理：手动管理，允许自启动、关联启动和后台活动" else "部分厂商需要手动允许",
                    modifier = Modifier.clickable { openBackgroundLaunchSettings(context) },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = "打开应用设置") },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "视频与存储权限",
                    value = "允许访问录制视频和删除旧文件",
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")))
                    },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = "打开应用权限设置") },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "全局自动录制",
                    value = if (settings.globalAutoRecordEnabled) "开播后自动开始录制" else "仅手动录制",
                    trailing = {
                        Switch(
                            checked = settings.globalAutoRecordEnabled,
                            onCheckedChange = viewModel::setGlobalAutoRecordEnabled,
                        )
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "后台持续检测",
                    value = if (settings.backgroundMonitoringEnabled) "应用关闭后继续检查" else "只在打开应用时检查",
                    trailing = {
                        Switch(
                            checked = settings.backgroundMonitoringEnabled,
                            onCheckedChange = viewModel::setBackgroundMonitoringEnabled,
                        )
                    },
                )
                HorizontalDivider()
                SettingsRow(
                    title = "省电检测",
                    value = if (settings.powerSavingMonitoring) "空闲时每分钟检查；系统可能进一步延迟" else "及时检测，空闲时保持唤醒",
                    trailing = {
                        Switch(checked = settings.powerSavingMonitoring,
                            onCheckedChange = viewModel::setPowerSavingMonitoring)
                    },
                )
            }
        }
        item {
            SettingsSection("文件命名 · 下次录制时读取，不重命名已有文件") {
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
            SettingsSection("录制任务模板") {
                Text("模板只保存格式、画质、分段和检查间隔，不包含账号、密钥、视频或目录授权。", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                templates.forEach { template ->
                    HorizontalDivider()
                    SettingsRow(title = template.name, value = "${template.format} · ${template.quality} · ${template.segmentSeconds / 60} 分钟分段 · ${template.intervalSeconds} 秒检查", trailing = {
                        androidx.compose.material3.IconButton(onClick = { viewModel.deleteTemplate(template.name) }) { Icon(Icons.Outlined.Restore, contentDescription = "删除模板") }
                    })
                }
                HorizontalDivider()
                TextButton(onClick = { showTemplateDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("从当前默认值保存模板") }
            }
        }
        item {
            SettingsSection("运行条件") {
                SettingsRow(
                    icon = { Icon(Icons.Outlined.NetworkCell, contentDescription = null) },
                    title = "允许移动数据",
                    value = (if (settings.allowMobileData) "已允许" else "仅 Wi-Fi") + " · 立即影响全部录制及直播预览",
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
                HorizontalDivider()
                SettingsRow(
                    title = "默认开播检查间隔",
                    value = "${settings.checkIntervalSeconds} 秒 · 仅新直播间",
                    modifier = Modifier.clickable { showIntervalDialog = true },
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("迁移") {
                SettingsRow(title = "配置备份与迁移", value = "预览后导入，重复房间不覆盖",
                    modifier = Modifier.clickable(onClick = onOpenBackup),
                    trailing = { Icon(Icons.Outlined.ChevronRight, null) })
            }
        }
        item {
            SettingsSection("诊断") {
                SettingsRow(
                    modifier = Modifier.clickable(onClick = onOpenLogs),
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    title = "任务日志",
                    value = "预览脱敏诊断，再选择导出",
                    trailing = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("关于") {
                SettingsRow(title = "支持者赞助", value = "感谢支持，扫码赞助完全自愿",
                    modifier = Modifier.clickable { showSupportDialog = true },
                    trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                HorizontalDivider()
                SettingsRow(title = "使用说明", value = "第一次使用的操作步骤和故障排查",
                    modifier = Modifier.clickable(onClick = onOpenUserGuide),
                    trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                HorizontalDivider()
                SettingsRow(title = "开源许可", value = "FFmpeg 与第三方组件的许可和源码说明",
                    modifier = Modifier.clickable(onClick = onOpenLicenses),
                    trailing = { Icon(Icons.Outlined.ChevronRight, null) })
            }
        }
        item {
            Text(
                "CastTrace ${BuildConfig.VERSION_NAME}",
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
    if (showIntervalDialog) {
        ChoiceDialog(
            title = "默认开播检查间隔",
            selected = "${settings.checkIntervalSeconds} 秒",
            options = listOf(15, 30, 60, 120, 300, 600).map { "$it 秒" },
            onDismiss = { showIntervalDialog = false },
            onSelect = {
                viewModel.setCheckIntervalSeconds(it.removeSuffix(" 秒").toInt())
                showIntervalDialog = false
            },
        )
    }
    if (showTemplateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showTemplateDialog = false }, title = { Text("保存录制模板") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("模板名称") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { viewModel.saveTemplate(RecordingTemplate(name.trim().take(32), settings.defaultFormat, settings.defaultQuality, settings.checkIntervalSeconds)); showTemplateDialog = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showTemplateDialog = false }) { Text("取消") } })
    }
    if (showSupportDialog) SupportDialog(firstLaunch = false, onDismiss = { showSupportDialog = false })
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
            .heightIn(min = 66.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
