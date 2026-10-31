package com.streamcap.mobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.streamcap.mobile.data.preferences.BackupPreview
import com.streamcap.mobile.data.preferences.ConfigurationBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val viewModel: ConfigurationBackupViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var summary by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = try {
                withContext(Dispatchers.IO) {
                    val text = viewModel.export()
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")) { "无法写入所选文件" }.bufferedWriter().use { it.write(text) }
                }
                "配置备份已保存；未包含视频、登录凭据、签名参数或目录授权。"
            } catch (failure: Exception) { "导出失败：${failure.message}" }
            busy = false
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 1_048_576) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray() }
                    require(bytes.size <= 1_048_576) { "备份超过 1 MiB 限制" }
                    ConfigurationBackup.parse(bytes.toString(Charsets.UTF_8))
                }
                val existing = viewModel.existingNormalizedUrls()
                val unique = parsed.rooms.distinctBy { it.normalizedUrl }
                val newCount = unique.count { it.normalizedUrl !in existing }
                summary = "新增 $newCount 个；重复或冲突 ${parsed.rooms.size - newCount} 个保留现有配置并跳过。\n全局默认将替换为 ${parsed.format} / ${parsed.quality} / ${parsed.interval} 秒。\n导入的房间保持暂停、关闭自动录制。保存目录需重新授权；签名直链需重新添加。"
                preview = parsed
            } catch (failure: Exception) { result = "读取失败：${failure.message}，现有配置未更改" }
            busy = false
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("配置备份与迁移") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("备份直播间列表、各房间录制偏好，以及全局默认格式、画质和检查间隔。")
            Text("不包含视频、Cookie、密钥、链接签名、后台自启动和网络权限。导入后先检查房间，重新选择保存目录，再手动开始录制。")
            Button(onClick = { export.launch("casttrace-config-v1.json") }, enabled = !busy) { Text("导出配置备份") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("选择备份并预览") }
            if (busy) CircularProgressIndicator()
            result?.let { Text(it) }
        }
    }
    preview?.let { candidate ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("确认导入范围") },
            text = { Text(summary) }, confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    result = try {
                        val (added, skipped) = withContext(Dispatchers.IO + NonCancellable) { viewModel.restore(candidate) }
                        "新增 $added 个，跳过 $skipped 个。导入房间已暂停；请在设置重新选择保存目录。"
                    } catch (failure: Exception) { "导入未完成：${failure.message}。请检查列表后重试，重复房间会跳过。" }
                    busy = false; preview = null
                }
            }) { Text("确认导入") } }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") } })
    }
}
