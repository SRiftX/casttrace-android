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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class FeatureTourAnchor {
    HOME,
    ADD_ROOM,
    REMINDERS,
    RECORDINGS,
    SETTINGS,
    STORAGE_LOCATION,
    BACKGROUND_MONITORING,
    BATTERY_OPTIMIZATION,
    LOG_EXPORT,
    PLATFORM_SUPPORT,
    AUTOMATIC_UPDATES,
}

data class FeatureTourStep(
    val anchor: FeatureTourAnchor,
    val title: String,
    val description: String,
)

val featureTourSteps = listOf(
    FeatureTourStep(
        FeatureTourAnchor.HOME,
        "直播间首页",
        "这里集中显示已添加的直播间，可以搜索主播、查看开播状态，并管理各房间的录制。",
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
        "设置入口",
        "录制规则、保存位置、后台检测和更新选项都可以在设置中调整。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.STORAGE_LOCATION,
        "选择保存位置",
        "默认视频保存在 Movies/StreamCap。点“保存目录”可选择其他文件夹；单个直播间也能单独指定目录。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.BACKGROUND_MONITORING,
        "保持后台检测",
        "打开“后台持续检测”后，离开应用仍会检查开播状态。全局自动录制和每个房间的监控开关共同决定是否自动录制。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.BATTERY_OPTIMIZATION,
        "允许后台运行",
        "电池优化可能暂停后台检测。点此进入系统设置并允许 CastTrace 不受电池优化限制；部分厂商还需允许自启动和后台活动。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.LOG_EXPORT,
        "导出诊断日志",
        "遇到检查、下载或录制问题时，在任务日志中筛选记录并导出完整报告，方便定位问题。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.PLATFORM_SUPPORT,
        "查看平台支持",
        "平台适配页列出各平台的解析状态、设备实测结果和弹幕能力；添加直播间前可先确认支持情况。",
    ),
    FeatureTourStep(
        FeatureTourAnchor.AUTOMATIC_UPDATES,
        "自动检查更新",
        "打开此开关后，每天进入应用时检查新版。发现更新会下载并交由 Android 系统确认安装。",
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
    var calloutBounds by remember(stepIndex) { mutableStateOf<Rect?>(null) }
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
            val target = targetBounds
            val callout = calloutBounds
            if (target != null && callout != null) {
                val start = if (targetIsLow) {
                    Offset(callout.center.x, callout.bottom)
                } else {
                    Offset(callout.center.x, callout.top)
                }
                val end = if (targetIsLow) {
                    Offset(target.center.x, (target.top - 10.dp.toPx()).coerceAtLeast(0f))
                } else {
                    Offset(target.center.x, (target.bottom + 10.dp.toPx()).coerceAtMost(size.height))
                }
                val control = Offset(
                    x = (start.x + end.x) / 2f,
                    y = (start.y + end.y) / 2f + if (targetIsLow) 18.dp.toPx() else -18.dp.toPx(),
                )
                val arrowPath = Path().apply {
                    moveTo(start.x, start.y)
                    quadraticTo(control.x, control.y, end.x, end.y)
                }
                drawPath(arrowPath, Color.Black.copy(alpha = 0.8f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
                drawPath(arrowPath, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val angle = atan2(end.y - control.y, end.x - control.x)
                val headLength = 13.dp.toPx()
                val headAngle = 0.62f
                for (direction in listOf(-1f, 1f)) {
                    val wingAngle = angle + Math.PI.toFloat() + direction * headAngle
                    val wing = Offset(
                        end.x + cos(wingAngle) * headLength,
                        end.y + sin(wingAngle) * headLength,
                    )
                    drawLine(Color.Black.copy(alpha = 0.8f), end, wing, 7.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(accent, end, wing, 3.dp.toPx(), cap = StrokeCap.Round)
                }
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
            }).widthIn(max = 420.dp).fillMaxWidth()
                .onGloballyPositioned { coordinates -> calloutBounds = coordinates.boundsInRoot() },
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
