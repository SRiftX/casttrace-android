package com.streamcap.mobile.ui.settings

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private const val WIZARD_STEP_COUNT = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    step: Int,
    onStepChange: (Int) -> Unit,
    onAddRoom: () -> Unit,
    onOpenLogs: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: SettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
    var batteryExempt by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = areNotificationsAllowed(context) }

    DisposableEffect(lifecycleOwner, context) {
        fun refresh() {
            batteryExempt = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
            notificationsAllowed = areNotificationsAllowed(context)
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentStep = step.coerceIn(0, WIZARD_STEP_COUNT - 1)
    val next = { onStepChange((currentStep + 1).coerceAtMost(WIZARD_STEP_COUNT - 1)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新手设置 · ${currentStep + 1}/$WIZARD_STEP_COUNT") },
                navigationIcon = {
                    IconButton(onClick = { if (currentStep == 0) onFinish() else onStepChange(currentStep - 1) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上一步")
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                LinearProgressIndicator(
                    progress = { (currentStep + 1f) / WIZARD_STEP_COUNT },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("稍后设置") }
                    Button(
                        onClick = if (currentStep == WIZARD_STEP_COUNT - 1) onFinish else next,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (currentStep == WIZARD_STEP_COUNT - 1) "完成" else "下一步") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (currentStep) {
                0 -> {
                    Text("先添加一个直播间", style = MaterialTheme.typography.headlineSmall)
                    Text("粘贴直播间链接并确认主播信息。添加后，向导会带你继续配置后台监控。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onAddRoom, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Text("添加直播间", Modifier.padding(start = 8.dp))
                    }
                }
                1 -> {
                    Text("选择录制文件保存位置", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (settings.storageDirectoryUri == null) "当前使用默认目录 Movies/StreamCap。你也可以选择其他文件夹；首次授权后，后续录制会保存到这里。"
                        else "已设置自定义保存位置。确认这个文件夹仍可访问，并保留读写授权。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WizardStatus("当前设置", if (settings.storageDirectoryUri == null) "默认目录" else "自定义目录")
                    OutlinedButton(onClick = { directoryPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Text("选择保存文件夹", Modifier.padding(start = 8.dp))
                    }
                }
                2 -> {
                    Text("让监控在后台持续运行", style = MaterialTheme.typography.headlineSmall)
                    Text("自动录制需要全局开关和单个房间的开播自动录制都开启。应用被系统结束后，后台检测和开机恢复也会影响能否继续监控。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WizardSwitch("全局自动录制", "检测到开播后自动开始录制", settings.globalAutoRecordEnabled, viewModel::setGlobalAutoRecordEnabled)
                    HorizontalDivider()
                    WizardSwitch("后台持续检测", "应用切到后台后继续检查直播状态", settings.backgroundMonitoringEnabled, viewModel::setBackgroundMonitoringEnabled)
                    HorizontalDivider()
                    WizardSwitch("开机恢复任务", "设备重启后恢复已启用的监控任务", settings.resumeAfterBoot, viewModel::setResumeAfterBoot)
                    HorizontalDivider()
                    Text("部分 Android 厂商还需要在系统的应用启动管理中允许自启动和后台活动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { openBackgroundLaunchSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text("打开后台启动设置", Modifier.padding(start = 8.dp))
                    }
                }
                3 -> {
                    Text("检查电池与通知权限", style = MaterialTheme.typography.headlineSmall)
                    Text("电池优化可能延迟或停止后台检查。允许通知后，开播提醒和录制状态通知才能正常显示。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WizardStatus("电池优化豁免", if (batteryExempt) "已允许" else "尚未允许")
                    Button(
                        onClick = { requestBatteryExemption(context) },
                        enabled = !batteryExempt,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null)
                        Text(if (batteryExempt) "电池优化已豁免" else "设置电池优化", Modifier.padding(start = 8.dp))
                    }
                    HorizontalDivider()
                    WizardStatus("应用通知", if (notificationsAllowed) "已允许" else "尚未允许")
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                        Text("设置通知权限", Modifier.padding(start = 8.dp))
                    }
                }
                4 -> {
                    Text("准备好诊断日志", style = MaterialTheme.typography.headlineSmall)
                    Text("遇到直播状态识别、后台运行或文件保存问题时，可以导出任务日志。日志会隐藏链接和凭据，导出的文件由你选择保存位置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Text("打开任务日志并导出", Modifier.padding(start = 8.dp))
                    }
                    Text("导出完成后返回此向导继续。也可以跳过，之后在 设置 → 诊断 中导出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text("设置已就绪", style = MaterialTheme.typography.headlineSmall)
                    Text("你可以从主页添加更多直播间。请在每个房间详情中开启开播自动录制；系统省电策略仍可能影响后台检查。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WizardSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WizardStatus(title: String, status: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun areNotificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun requestBatteryExemption(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(request) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
