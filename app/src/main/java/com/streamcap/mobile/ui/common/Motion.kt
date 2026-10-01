package com.streamcap.mobile.ui.common

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/** Shared motion timings. The system animator scale is respected for accessibility. */
object MotionTokens {
    const val Fast = 140
    const val Normal = 220
    const val Emphasis = 320
    const val Exit = 180
}

@Composable
fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
        }.getOrDefault(1f).coerceAtLeast(0f)
    }
}

fun scaledDuration(baseMillis: Int, scale: Float): Int =
    (baseMillis * scale).roundToInt().coerceAtLeast(if (scale == 0f) 0 else 1)
