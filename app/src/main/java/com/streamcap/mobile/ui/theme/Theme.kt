package com.streamcap.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = LiveTeal,
    onSecondary = Paper,
    tertiary = RecordingRed,
    background = Porcelain,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEEFEA),
    onSurfaceVariant = MutedInk,
    outline = Hairline,
    error = RecordingRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5E6E1),
    onPrimary = DeepSurface,
    secondary = Color(0xFF68C8BF),
    tertiary = Color(0xFFFF8C83),
    background = Color(0xFF111312),
    onBackground = Color(0xFFE5E6E1),
    surface = DeepSurface,
    onSurface = Color(0xFFE5E6E1),
    surfaceVariant = Color(0xFF292C2A),
    onSurfaceVariant = Color(0xFFB9BDB7),
    outline = Color(0xFF454945),
    error = Color(0xFFFF8C83),
)

@Composable
fun StreamCapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = StreamCapTypography,
        content = content,
    )
}
