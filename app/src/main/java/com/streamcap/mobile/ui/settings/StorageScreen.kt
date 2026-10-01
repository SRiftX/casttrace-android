package com.streamcap.mobile.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
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
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.storage.PreviewCache
import com.streamcap.mobile.ui.common.formatBytes
import com.streamcap.mobile.ui.common.StorageBudget
import com.streamcap.mobile.ui.common.StorageBudgetLevel
import com.streamcap.mobile.ui.common.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class StorageUsage(val saved: Long, val inaccessible: Int, val pending: Long, val cache: Long, val free: Long, val activeBytes: Long, val activeSeconds: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(container: AppContainer, onBack: () -> Unit, onVideos: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var clear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        usage = null; error = null
        try {
            usage = withContext(Dispatchers.IO) {
                val sessions = container.repository.recordings.first()
                val uris = sessions.flatMap { it.outputUris.ifEmpty { listOfNotNull(it.outputUri) } }.distinct()
                var inaccessible = 0
                val saved = uris.sumOf { raw ->
                    val size = runCatching {
                        val uri = Uri.parse(raw)
                        if (uri.scheme == "file") File(requireNotNull(uri.path)).takeIf { it.isFile }?.length()
                        else context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { value -> value >= 0 } else null
                        }
                    }.getOrNull()
                    if (size == null) inaccessible++
                    size ?: 0L
                }
                val pending = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.cacheDir, "pending-recordings")
                val bytes = if (!pending.exists()) 0L else requireNotNull(pending.listFiles()) { "无法读取待恢复目录" }
                    .filter { it.isFile }.sumOf { it.length() }
                val active = sessions.filter { it.status in setOf(com.streamcap.mobile.domain.model.TaskStatus.STARTING, com.streamcap.mobile.domain.model.TaskStatus.RECORDING, com.streamcap.mobile.domain.model.TaskStatus.RECONNECTING, com.streamcap.mobile.domain.model.TaskStatus.FINALIZING) }
                StorageUsage(saved, inaccessible, bytes, PreviewCache.previewFiles(context.cacheDir).sumOf { it.length() },
                    container.recordingStorage.recordingRoot().usableSpace, active.sumOf { it.totalBytes }, active.sumOf { it.durationSeconds })
            }
        } catch (failure: Exception) { error = "统计失败：${failure.message}，请刷新或检查目录权限" }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("存储管理") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            usage?.let {
                Text("已保存视频 · ${formatBytes(it.saved)}", style = MaterialTheme.typography.titleMedium)
                Text("仅统计视频库引用且可访问的文件，按文件地址去重；未包含手动移出视频库的文件。无法统计 ${it.inaccessible} 个文件。")
                TextButton(onClick = onVideos) { Text("查看视频并选择删除") }
                HorizontalDivider()
                Text("录制中及待恢复媒体 · ${formatBytes(it.pending)}", style = MaterialTheme.typography.titleMedium)
                Text("可能包含正在写入、等待保存和保存失败的内容。请到视频库处理恢复或选择删除，不自动清理。")
                HorizontalDivider()
                Text("预览缓存 · ${formatBytes(it.cache)}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { clear = true }, enabled = !busy) { Text("清理未使用的预览缓存") }
                Text("本机录制暂存空间可用 · ${formatBytes(it.free)}")
                val budget = StorageBudget(it.free, it.activeBytes, it.activeSeconds)
                val estimate = budget.estimatedSecondsRemaining?.let { seconds -> "预计当前录制还能写入约 ${formatDuration(seconds)}" }
                val budgetText = when (budget.level) {
                    StorageBudgetLevel.NORMAL -> estimate ?: "正在积累码率数据，暂时无法估算剩余时间"
                    StorageBudgetLevel.WARNING -> "空间即将不足 · ${estimate ?: "建议尽快处理"}"
                    StorageBudgetLevel.CRITICAL -> "空间严重不足 · 请立即暂停并保存或清理缓存"
                }
                Text(budgetText, color = if (budget.level == StorageBudgetLevel.NORMAL) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                Text("外部保存目录可能位于其他设备或云端，其剩余空间未知。封装与导出还需要额外空间。", style = MaterialTheme.typography.bodySmall)
            } ?: if (error == null) CircularProgressIndicator() else Text(error!!, color = MaterialTheme.colorScheme.error)
            result?.let { Text(it) }
            TextButton(onClick = { revision++ }, enabled = !busy) { Text("刷新统计") }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理预览缓存？") },
        text = { Text("仅删除应用生成且当前未使用的预览文件，不删除视频和待恢复媒体。") },
        confirmButton = { TextButton(onClick = {
            clear = false; busy = true
            scope.launch {
                result = try { "已释放 ${formatBytes(withContext(Dispatchers.IO) { PreviewCache.clearUnused(context.cacheDir) })}" }
                    catch (failure: Exception) { "清理未完成：${failure.message}" }
                busy = false; revision++
            }
        }) { Text("清理缓存") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
