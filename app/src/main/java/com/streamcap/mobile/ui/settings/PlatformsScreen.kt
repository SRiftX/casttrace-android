package com.streamcap.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.streamcap.mobile.platform.AndroidSupportStatus
import com.streamcap.mobile.platform.PlatformCatalog
import com.streamcap.mobile.platform.PlatformCatalogEntry
import com.streamcap.mobile.app.AppContainer
import com.streamcap.mobile.ui.theme.LiveTeal
import com.streamcap.mobile.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformsScreen(container: AppContainer, onBack: () -> Unit) {
    val entries = PlatformCatalog.entries
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("平台适配") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Android 已适配虎牙、抖音、B站、斗鱼、快手及 HLS / FLV 直链；猫耳、ShowRoom、CHZZK、Picarto 已加入解析器，仍需真房间验证。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(entries, key = { it.key }) { entry -> PlatformRow(entry, container) }
        }
    }
}

@Composable
private fun PlatformRow(entry: PlatformCatalogEntry, container: AppContainer) {
    val scope = rememberCoroutineScope()
    var showCredential by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }
    val credentialSupported = entry.key in setOf("douyin", "bilibili", "kuaishou", "chzzk")
    val color = when (entry.status) {
        AndroidSupportStatus.AVAILABLE, AndroidSupportStatus.FIRST_BATCH -> LiveTeal
        AndroidSupportStatus.NEEDS_REVIEW -> WarningAmber
        AndroidSupportStatus.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant
        AndroidSupportStatus.DISCONTINUED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.domains.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(entry.status.displayName(), style = MaterialTheme.typography.labelSmall, color = color)
                if (credentialSupported) {
                    TextButton(onClick = {
                        cookie = container.credentialStore.get(entry.key)?.cookie.orEmpty()
                        showCredential = true
                    }) { Text(if (container.credentialStore.get(entry.key) == null) "设置 Cookie" else "已设置") }
                }
            }
        }
    }
    if (showCredential) {
        AlertDialog(
            onDismissRequest = { showCredential = false },
            title = { Text("${entry.name}登录 Cookie") },
            text = {
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text("Cookie") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("仅保存在本机，用于解析需要登录态的直播源")
                            Text(
                                "新手使用：1. 在浏览器登录 ${entry.name}；2. 打开开发者工具的 Network/网络，刷新直播页；3. 选择平台请求，在 Request Headers 中复制 Cookie 的值；4. 粘贴完整内容后保存。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "格式示例：sessionid=...; token=...（不要粘贴 Cookie: 这几个字）。Cookie 过期、换账号或提示鉴权失败时，重新复制并保存；不要发给他人。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (cookie.isBlank()) container.credentialStore.clear(entry.key)
                        else container.credentialStore.set(entry.key, cookie.trim())
                        showCredential = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCredential = false }) { Text("取消") } },
        )
    }
}

private fun AndroidSupportStatus.displayName(): String = when (this) {
    AndroidSupportStatus.AVAILABLE -> "当前可用"
    AndroidSupportStatus.FIRST_BATCH -> "首批适配"
    AndroidSupportStatus.PLANNED -> "计划接入"
    AndroidSupportStatus.NEEDS_REVIEW -> "待复核"
    AndroidSupportStatus.DISCONTINUED -> "已停服"
}
