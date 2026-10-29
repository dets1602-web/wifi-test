package ru.wifitest

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Токены цветов — см. DESIGN.md. Цвета в коде экранов только отсюда. */
data class AppColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val line: Color,
)

val LightColors = AppColors(
    bg = Color(0xFFF4F6F8),
    surface = Color(0xFFFFFFFF),
    text = Color(0xFF15191E),
    muted = Color(0xFF56606B),
    primary = Color(0xFF1F6FD1),
    onPrimary = Color(0xFFFFFFFF),
    good = Color(0xFF1E7A47),
    warn = Color(0xFF9A6300),
    bad = Color(0xFFB3261E),
    line = Color(0xFFE2E6EA),
)

val DarkColors = AppColors(
    bg = Color(0xFF111417),
    surface = Color(0xFF1B1F24),
    text = Color(0xFFE9EDF1),
    muted = Color(0xFFA3ADB8),
    primary = Color(0xFF6AA8F5),
    onPrimary = Color(0xFF0B1A2E),
    good = Color(0xFF5CC38A),
    warn = Color(0xFFF2C14E),
    bad = Color(0xFFF2867E),
    line = Color(0xFF2C3239),
)

val LocalColors = staticCompositionLocalOf { LightColors }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalColors provides c) { content() }
}

fun AppColors.of(level: Level): Color = when (level) {
    Level.GOOD -> good
    Level.WARN -> warn
    Level.BAD -> bad
}
