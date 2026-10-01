package com.streamcap.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            context.assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted()
        }
    }
    LaunchedEffect(selected) {
        paragraphs = emptyList()
        error = null
        selected?.let { name ->
            try {
                paragraphs = withContext(Dispatchers.IO) {
                    context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
                        .split(Regex("\\r?\\n\\s*\\r?\\n"))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "许可文件读取失败，请返回重试。"
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (selected == null) "开源许可" else "许可正文") }, navigationIcon = {
            IconButton(onClick = { if (selected != null) selected = null else onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
        })
    }) { padding ->
        key(selected) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected == null) {
                    item { Text("CastTrace 使用 FFmpegKit HTTPS 7.1.6 和 FFmpeg n7.1.5（LGPLv3）。应用自身源码采用 Apache-2.0，各依赖保留原许可。") }
                    item { Text("B站弹幕解压使用 Brotli Java decoder 0.1.2（MIT），许可正文见 brotli-MIT.txt。") }
                    item { Text("允许按照相应许可证修改这些库，并为调试修改进行逆向工程。修改版可使用自己的密钥签名安装。", style = MaterialTheme.typography.bodyMedium) }
                    item { Text("当前为本地候选版本，FFmpeg 对应源码核验尚未完成，暂未开放正式分发。已收集的许可与版权声明见下方。", style = MaterialTheme.typography.bodySmall) }
                    items(files, key = { it }) { name ->
                        Surface(Modifier.fillMaxWidth().clickable { selected = name }) {
                            Text(name, Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp))
                        }
                        HorizontalDivider()
                    }
                } else {
                    item { Text(selected.orEmpty(), style = MaterialTheme.typography.titleMedium) }
                    error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    items(paragraphs) { paragraph -> Text(paragraph, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    androidx.activity.compose.BackHandler(selected != null) { selected = null }
}
