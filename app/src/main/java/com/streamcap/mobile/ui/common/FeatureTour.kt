package com.streamcap.mobile.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

enum class FeatureTourAnchor {
    HOME,
    ADD_ROOM,
    REMINDERS,
    RECORDINGS,
    SETTINGS,
}

data class FeatureTourStep(
    val anchor: FeatureTourAnchor,
    val title: String,
    val description: String,
)

val featureTourSteps = listOf(
    FeatureTourStep(
        FeatureTourAnchor.HOME,
        "直播间管理",
        "主页集中显示已添加的直播间，可搜索、检查开播状态，并查看或控制每个房间的录制。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.ADD_ROOM,
        "添加与搜索",
        "点加号粘贴直播链接；主页的放大镜可展开搜索，快速找到已添加的主播。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.REMINDERS,
        "开播提醒",
        "可为每位主播单独开关强提醒。开播后进入直播间，还能看弹幕、切换画质和缩放画面。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.RECORDINGS,
        "视频库",
        "已保存的直播按场次和分段整理，可播放、查看详情、导出或分享录制内容。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.SETTINGS,
        "设置与平台",
        "在这里管理录制默认值、文件、后台和网络；平台页可查看设备实测记录与弹幕能力。",
    ),
)

fun Modifier.featureTourAnchor(
    anchor: FeatureTourAnchor,
    onBoundsChanged: (FeatureTourAnchor, Rect) -> Unit,
): Modifier = onGloballyPositioned { coordinates ->
    onBoundsChanged(anchor, coordinates.boundsInRoot())
}

@Composable
fun FeatureTourOverlay(
    stepIndex: Int,
    step: FeatureTourStep,
    targetBounds: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    BackHandler(onBack = onSkip)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val targetIsLow = targetBounds?.let {
            it.center.y / density.density > maxHeight.value * 0.52f
        } ?: false

        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            },
        ) {
            drawRect(Color.Black.copy(alpha = 0.68f))
            targetBounds?.let { target ->
                val margin = 8.dp.toPx()
                val edgePadding = 4.dp.toPx()
                val spotlight = Rect(
                    left = (target.left - margin).coerceAtLeast(edgePadding),
                    top = (target.top - margin).coerceAtLeast(edgePadding),
                    right = (target.right + margin).coerceAtMost(size.width - edgePadding),
                    bottom = (target.bottom + margin).coerceAtMost(size.height - edgePadding),
                )
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(spotlight.left, spotlight.top),
                    size = androidx.compose.ui.geometry.Size(spotlight.width, spotlight.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    blendMode = BlendMode.Clear,
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(spotlight.left, spotlight.top),
                    size = androidx.compose.ui.geometry.Size(spotlight.width, spotlight.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }

        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember(stepIndex) { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        )

        Surface(
            modifier = (if (targetIsLow) {
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp, start = 16.dp, end = 16.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp, start = 16.dp, end = 16.dp)
            }).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("新手指引", style = MaterialTheme.typography.labelLarge, color = accent)
                    Spacer(Modifier.weight(1f))
                    Text("${stepIndex + 1} / ${featureTourSteps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(step.title, style = MaterialTheme.typography.titleLarge)
                Text(step.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) { Text("跳过") }
                    Button(onClick = onNext) {
                        Text(if (stepIndex == featureTourSteps.lastIndex) "完成" else "下一步")
                    }
                }
            }
        }
    }
}
