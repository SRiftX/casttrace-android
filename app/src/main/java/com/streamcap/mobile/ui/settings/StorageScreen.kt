package com.streamcap.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamcap.mobile.ui.common.formatBytes
import com.streamcap.mobile.ui.common.StorageBudget
import com.streamcap.mobile.ui.common.StorageBudgetLevel
import com.streamcap.mobile.ui.common.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit, onVideos: () -> Unit) {
    val viewModel: StorageViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    Scaffold(topBar = { TopAppBar(title = { Text("存储管理") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.usage?.let {
                Text("已保存视频 · ${formatBytes(it.saved)}", style = MaterialTheme.typography.titleMedium)
                Text("仅统计视频库引用且可访问的文件，按文件地址去重；未包含手动移出视频库的文件。无法统计 ${it.inaccessible} 个文件。")
                TextButton(onClick = onVideos) { Text("查看视频并选择删除") }
                HorizontalDivider()
                Text("录制中及待恢复媒体 · ${formatBytes(it.pending)}", style = MaterialTheme.typography.titleMedium)
                Text("可能包含正在写入、等待保存和保存失败的内容。请到视频库处理恢复或选择删除，不自动清理。")
                HorizontalDivider()
                Text("预览缓存 · ${formatBytes(it.cache)}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { clear = true }, enabled = !state.busy) { Text("清理未使用的预览缓存") }
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
            } ?: if (state.error == null) CircularProgressIndicator() else Text(state.error!!, color = MaterialTheme.colorScheme.error)
            state.result?.let { Text(it) }
            TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("刷新统计") }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理预览缓存？") },
        text = { Text("仅删除应用生成且当前未使用的预览文件，不删除视频和待恢复媒体。") },
        confirmButton = { TextButton(onClick = {
            clear = false
            viewModel.clearUnusedPreviewCache()
        }, enabled = !state.busy) { Text("清理缓存") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
