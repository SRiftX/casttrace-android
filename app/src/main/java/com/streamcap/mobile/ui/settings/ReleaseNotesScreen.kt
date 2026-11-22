package com.streamcap.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamcap.mobile.BuildConfig

internal object ReleaseNotes {
    const val VERSION = "0.2.22"
    const val DATE = "2026-11-23"
    val changes = listOf(
        "新手导览扩展到 11 步，覆盖保存目录、后台检测、电池优化、日志、平台和自动更新。",
        "使用说明补充后台启动、电池豁免、完整平台目录、诊断日志和自动更新操作。",
        "平台适配默认显示完整目录。平台总数、实测数量和目录更新时间显示在设置与适配页中。",
        "自动更新每天检查新版，下载后由 Android 系统确认安装。",
        "直播监控诊断日志补充检查上下文，便于定位平台请求和房间状态问题。",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新说明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        ReleaseNotesContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        )
    }
}

@Composable
internal fun ReleaseNotesDialog(
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CastTrace ${BuildConfig.VERSION_NAME} 已更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReleaseNotes.changes.take(3).forEach { change ->
                    Text("• $change", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenDetails) { Text("查看完整说明") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

@Composable
private fun ReleaseNotesContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("CastTrace ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text(
                "更新日期 ${ReleaseNotes.DATE}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ReleaseNotes.changes) { change ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(change, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
