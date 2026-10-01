package com.streamcap.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserGuideScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用说明") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("第一次使用 CastTrace？按下面顺序完成设置。", style = MaterialTheme.typography.titleMedium)
                Text(
                    "自动录制需要同时满足全局开关和房间开关；后台持续检测只决定应用关闭后是否继续检查。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item { GuideSectionTitle("快速开始") }
            itemsIndexed(quickStartSteps) { index, step ->
                GuideStep(number = index + 1, title = step.first, body = step.second)
            }
            item { GuideSectionTitle("自动录制怎么工作") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("保存目录 → 添加直播间 → 确认平台和主播 → 开启房间开播自动录制 → 开启全局自动录制", fontWeight = FontWeight.Medium)
                        HorizontalDivider()
                        Text("后台持续检测开启：应用关闭后继续检查。关闭：只在打开应用时检查。", style = MaterialTheme.typography.bodyMedium)
                        Text("立即检查只刷新直播状态，不会直接开始录制。", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { GuideSectionTitle("遇到问题时") }
            itemsIndexed(troubleshooting) { _, item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.first, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.34f))
                        Text(item.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.66f))
                    }
                }
            }
            item { GuideSectionTitle("查看和导出视频") }
            item {
                Text(
                    "进入视频库后，使用查看视频播放已保存内容，查看详情检查文件和分段。操作菜单支持分享、选择片段导出、导出整场（全部分段）和复制路径。导出与分享都可选无弹幕原视频，或将已保存弹幕写入画面的带弹幕 MP4。带弹幕使用播放器中保存的弹幕样式，需要额外空间和编码时间；整场按分段保存。分享包含全部已保存分段，生成后选择外部接收应用；多选带弹幕要求每场都有已保存弹幕。处理中可点取消处理，原录制保留。播放器中的直播预览是在线预览，不等于已保存视频。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val quickStartSteps = listOf(
    "选择保存目录" to "在设置中打开保存目录。默认是 Movies/StreamCap；选择自定义文件夹后授予读写权限。",
    "添加直播间" to "回到主页点加号，粘贴直播地址。主播名称（可选）可以留空，然后点击确认主播并添加。",
    "确认平台和主播" to "检查解析出的平台、主播名称和地址；不正确时返回修改后重新确认。",
    "开启房间监控" to "进入房间详情，确认任务启用并打开开播自动录制。添加页的同类选项叫定时监控开播。",
    "开启全局自动录制" to "在设置 → 后台运行打开全局自动录制。关闭时只保留手动录制。",
    "选择后台检测" to "按需打开后台持续检测。开启后应用关闭仍检查；关闭后只在打开应用时检查。",
)

private val troubleshooting = listOf(
    "没有自动录制" to "检查全局自动录制、房间开播自动录制、任务启用和暂停状态；再点立即检查并等待下一次检查。",
    "检查失败" to "检查直播地址、网络和平台状态；必要时重新添加直播间。",
    "空间不足" to "暂停并保存，清理视频或更换保存目录。导出也需要额外空间。",
    "保存失败" to "重新选择并授权保存目录，确认目录可写后重试；已有片段先到视频库检查。",
)

@Composable
private fun GuideSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun GuideStep(number: Int, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
