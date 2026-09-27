package com.wavetalk.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wavetalk.app.settings.ThemeMode

// ---------- Brand palette ----------
private val AmberDark = Color(0xFFB87A00)
private val Amber = Color(0xFFFFB74A)
private val Green = Color(0xFF3DDC84)
private val GreenDark = Color(0xFF1E9E5A)
private val Red = Color(0xFFFF5A5A)

private val DarkBackground = Color(0xFF0B0F14)
private val DarkSurface = Color(0xFF121821)
private val DarkSurfaceVariant = Color(0xFF1A2230)
private val DarkOnBackground = Color(0xFFE8ECF1)
private val DarkOnSurfaceVariant = Color(0xFF9AA7B5)
private val DarkOutline = Color(0xFF2A3442)

private val LightBackground = Color(0xFFFAF8F3)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFF0ECE2)
private val LightOnBackground = Color(0xFF1C2026)
private val LightOnSurfaceVariant = Color(0xFF5C6672)
private val LightOutline = Color(0xFFDDD6C8)

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1F1400),
    primaryContainer = Color(0xFF3A2C10),
    onPrimaryContainer = Amber,
    secondary = Green,
    onSecondary = Color(0xFF00220F),
    secondaryContainer = Color(0xFF0E3322),
    onSecondaryContainer = Green,
    tertiary = Color(0xFF7FB3FF),
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = Red,
    onError = Color(0xFF2A0000),
    outline = DarkOutline,
)

private val LightScheme = lightColorScheme(
    primary = AmberDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE7C2),
    onPrimaryContainer = Color(0xFF4A3000),
    secondary = GreenDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5E2),
    onSecondaryContainer = Color(0xFF0A4D2C),
    tertiary = Color(0xFF2C5FA8),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = Color(0xFFC62828),
    onError = Color.White,
    outline = LightOutline,
)

private val WaveTalkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun WaveTalkTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = WaveTalkShapes,
        content = content,
    )
}
