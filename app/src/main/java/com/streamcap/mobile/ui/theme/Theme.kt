package com.streamcap.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

enum class ThemePalette(val id: String, val label: String, val preview: Color) {
    CLASSIC("classic", "经典", Ink),
    SEA_GLASS("sea_glass", "海青", Color(0xFF006D67)),
    GROVE("grove", "松绿", Color(0xFF356D48)),
    CORAL("coral", "珊瑚", Color(0xFFA93F46)),
    SKY("sky", "晴蓝", Color(0xFF315FA3));

    companion object {
        fun fromId(id: String?): ThemePalette = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    secondary = Color(0xFF5E5CE6),
    onSecondary = Paper,
    tertiary = RecordingRed,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF636366),
    outline = Color(0xFFD1D1D6),
    error = RecordingRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    secondary = Color(0xFF68C8BF),
    tertiary = Color(0xFFFF8C83),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF48484A),
    error = Color(0xFFFF8C83),
)

@Composable
fun StreamCapTheme(palette: ThemePalette = ThemePalette.CLASSIC, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = palette.colorScheme(isSystemInDarkTheme()),
        typography = StreamCapTypography,
        shapes = StreamCapShapes,
        content = content,
    )
}

private val StreamCapShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun ThemePalette.colorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    return when (this) {
        ThemePalette.CLASSIC -> base
        ThemePalette.SEA_GLASS -> if (dark) base.copy(
            primary = Color(0xFF81D7CC), onPrimary = Color(0xFF003733),
            primaryContainer = Color(0xFF0D4B46), onPrimaryContainer = Color(0xFFB4EEE6),
            secondary = Color(0xFFA6C9C0), background = Color(0xFF101817),
            surface = Color(0xFF17211F), surfaceVariant = Color(0xFF283734),
            onSurface = Color(0xFFE5EFEC), onSurfaceVariant = Color(0xFFB7C9C4),
        ) else base.copy(
            primary = Color(0xFF006D67), onPrimary = Color.White,
            primaryContainer = Color(0xFFD6F2EB), onPrimaryContainer = Color(0xFF003C39),
            secondary = Color(0xFF46645E), background = Color(0xFFF4F8F7),
            surfaceVariant = Color(0xFFE5F0EC), onSurfaceVariant = Color(0xFF536661),
        )
        ThemePalette.GROVE -> if (dark) base.copy(
            primary = Color(0xFF9CD5A7), onPrimary = Color(0xFF143C23),
            primaryContainer = Color(0xFF285437), onPrimaryContainer = Color(0xFFC5ECCB),
            secondary = Color(0xFFB8CDB1), background = Color(0xFF121811),
            surface = Color(0xFF1B211A), surfaceVariant = Color(0xFF303B2E),
            onSurface = Color(0xFFEAF0E7), onSurfaceVariant = Color(0xFFC1CEC0),
        ) else base.copy(
            primary = Color(0xFF356D48), onPrimary = Color.White,
            primaryContainer = Color(0xFFDDF0DF), onPrimaryContainer = Color(0xFF193D26),
            secondary = Color(0xFF526A54), background = Color(0xFFF5F8F2),
            surfaceVariant = Color(0xFFEAF0E5), onSurfaceVariant = Color(0xFF596858),
        )
        ThemePalette.CORAL -> if (dark) base.copy(
            primary = Color(0xFFFFAAA9), onPrimary = Color(0xFF5D1D25),
            primaryContainer = Color(0xFF7A3037), onPrimaryContainer = Color(0xFFFFD9D6),
            secondary = Color(0xFFD5B8B0), background = Color(0xFF1B1515),
            surface = Color(0xFF241C1C), surfaceVariant = Color(0xFF3C3030),
            onSurface = Color(0xFFF2E9E7), onSurfaceVariant = Color(0xFFD5C0BC),
        ) else base.copy(
            primary = Color(0xFFA93F46), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE0DC), onPrimaryContainer = Color(0xFF67262C),
            secondary = Color(0xFF795A5C), background = Color(0xFFFBF7F5),
            surfaceVariant = Color(0xFFF4EAE7), onSurfaceVariant = Color(0xFF6E5C5A),
        )
        ThemePalette.SKY -> if (dark) base.copy(
            primary = Color(0xFFA6C8FF), onPrimary = Color(0xFF19345E),
            primaryContainer = Color(0xFF2B4B79), onPrimaryContainer = Color(0xFFD8E6FF),
            secondary = Color(0xFFB6C4D8), background = Color(0xFF12171E),
            surface = Color(0xFF1A2029), surfaceVariant = Color(0xFF2E3947),
            onSurface = Color(0xFFE8ECF3), onSurfaceVariant = Color(0xFFC1CBD9),
        ) else base.copy(
            primary = Color(0xFF315FA3), onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE8FC), onPrimaryContainer = Color(0xFF1B3D70),
            secondary = Color(0xFF52677C), background = Color(0xFFF7F9FC),
            surfaceVariant = Color(0xFFE8EEF5), onSurfaceVariant = Color(0xFF586879),
        )
    }
}
