package com.streamcap.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalConfiguration
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun DanmakuSettingsPanel(
    appearance: DanmakuAppearance,
    onDismiss: () -> Unit,
    onChange: (DanmakuAppearance) -> Unit,
) {
    var draft by remember { mutableStateOf(appearance) }
    fun update(value: DanmakuAppearance) { draft = value.normalized(); onChange(draft) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 64).coerceIn(160, 460).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(Modifier.heightIn(max = maxHeight).widthIn(max = 420.dp).padding(20.dp)) {
                Text("弹幕设置", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
                    Text("大小  ${draft.fontSizeSp.roundToInt()} sp")
                    Slider(draft.fontSizeSp, { update(draft.copy(fontSizeSp = it)) }, valueRange = 12f..32f, steps = 19,
                        modifier = Modifier.semantics { contentDescription = "弹幕大小" })
                    Text("透明度  ${(draft.opacity * 100).roundToInt()}%")
                    Slider(draft.opacity, { update(draft.copy(opacity = it)) }, valueRange = .2f..1f,
                        modifier = Modifier.semantics { contentDescription = "弹幕透明度" })
                    Text("滚动速度  ${String.format(Locale.US, "%.2f", draft.speed)}x")
                    Slider(draft.speed, { update(draft.copy(speed = it)) }, valueRange = .5f..2f, steps = 5,
                        modifier = Modifier.semantics { contentDescription = "弹幕滚动速度" })
                    Text("显示区域  ${(draft.area * 100).roundToInt()}%")
                    Slider(draft.area, { update(draft.copy(area = it)) }, valueRange = .25f..1f,
                        modifier = Modifier.semantics { contentDescription = "弹幕显示区域" })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("自动轨道", Modifier.weight(1f))
                        Switch(draft.autoLanes, { update(draft.copy(autoLanes = it)) },
                            modifier = Modifier.semantics { contentDescription = "自动弹幕轨道" })
                    }
                    Text("轨道上限  ${draft.laneLimit}")
                    Slider(draft.lanes.toFloat(), { update(draft.copy(lanes = it.roundToInt())) }, valueRange = 1f..12f, steps = 10,
                        enabled = !draft.autoLanes,
                        modifier = Modifier.semantics { contentDescription = "弹幕轨道数量" })
                    Text("颜色")
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("白色" to 0xFFFFFF, "黄色" to 0xFFE066, "青色" to 0x66E5FF,
                            "粉色" to 0xFF92BD, "绿色" to 0x8BE69B, "橙色" to 0xFFAD66).forEach { (name, rgb) ->
                            IconButton(onClick = { update(draft.copy(colorRgb = rgb)) },
                                modifier = Modifier.semantics { contentDescription = "弹幕颜色$name" }) {
                                Box(Modifier.size(28.dp).background(Color(0xFF000000.toInt() or rgb), CircleShape)) {
                                    if (draft.colorRgb == rgb) Icon(Icons.Outlined.Check, null, tint = Color.Black)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { update(DanmakuAppearance()) }) { Text("恢复默认") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}
