package com.streamcap.mobile.ui.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamcap.mobile.BuildConfig
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.data.database.TaskLogEntity
import com.streamcap.mobile.diagnostics.TaskLogExporter
import com.streamcap.mobile.ui.ViewModelFactory
import com.streamcap.mobile.ui.common.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(
        factory = ViewModelFactory { SettingsViewModel(container.preferences, container.repository) },
    )
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val recordings by container.repository.recordings.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val errorLogs = logs.filter { it.level.equals("ERROR", ignoreCase = true) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val report = TaskLogExporter.buildErrorReport(
                    logs = errorLogs,
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use { it.write(report) }
                        ?: error("无法打开导出文件")
                }
            }
            Toast.makeText(
                context,
                if (result.isSuccess) "错误日志已导出" else "导出失败：${result.exceptionOrNull()?.message}",
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
                            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("streamcap-errors-$timestamp.txt")
                        },
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "导出错误日志")
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
                val freeBytes = container.recordingStorage.recordingRoot().usableSpace
                val storageAdvice = if (freeBytes < 512L * 1_048_576L) "存储空间不足，建议先清理录制文件" else "存储空间正常"
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("诊断摘要", style = MaterialTheme.typography.titleMedium)
                    Text("录制会话：${recordings.size} · 可预览：$playable · 异常：$failed")
                    Text("可用空间：${formatDiagnosticStorage(freeBytes)} · $storageAdvice", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (failed == 0) "当前没有录制异常" else "请查看下方异常日志；有媒体文件的异常记录仍可尝试预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            if (logs.isEmpty()) {
                item { Text("暂无任务日志", modifier = Modifier.padding(vertical = 48.dp)) }
            } else {
                items(logs, key = { it.id }) { log ->
                    LogRow(log)
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatDiagnosticStorage(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun LogRow(log: TaskLogEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(log.eventType, style = MaterialTheme.typography.labelSmall)
            Text(formatDateTime(log.createdAt), style = MaterialTheme.typography.labelSmall)
        }
        Text(log.message, style = MaterialTheme.typography.bodyMedium)
    }
}
