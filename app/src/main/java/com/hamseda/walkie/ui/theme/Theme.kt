package com.hamseda.walkie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * HamSeda visual language: warm ivory surfaces, deep navy typography,
 * controlled turquoise/teal accents, one restrained warm accent reserved
 * for alerts. Bright/light first; dark mode fully supported.
 */
object HamSedaColors {
    // Light
    val IvoryBackground = Color(0xFFFDFBF6)
    val PaperSurface = Color(0xFFFFFFFF)
    val NavyInk = Color(0xFF16233D)
    val NavySoft = Color(0xFF3D4C6B)
    val TealPrimary = Color(0xFF0E7C7B)
    val TealDeep = Color(0xFF0A5E5D)
    val TealSoft = Color(0xFFE0F2F1)
    val WarmAlert = Color(0xFFE8833A)
    val ErrorRed = Color(0xFFB3261E)
    val SuccessGreen = Color(0xFF2E7D32)
    val DividerLight = Color(0xFFE9E2D6)

    // Dark
    val NightBackground = Color(0xFF0F1622)
    val NightSurface = Color(0xFF182234)
    val NightInk = Color(0xFFEDEFF5)
    val NightSoft = Color(0xFF9AA7C2)
    val TealBright = Color(0xFF5BD6CB)
    val TealNight = Color(0xFF0E7C7B)
}

private val LightScheme = lightColorScheme(
    primary = HamSedaColors.TealPrimary,
    onPrimary = Color.White,
    primaryContainer = HamSedaColors.TealSoft,
    onPrimaryContainer = HamSedaColors.TealDeep,
    secondary = HamSedaColors.NavySoft,
    background = HamSedaColors.IvoryBackground,
    onBackground = HamSedaColors.NavyInk,
    surface = HamSedaColors.PaperSurface,
    onSurface = HamSedaColors.NavyInk,
    surfaceVariant = Color(0xFFF4EFE4),
    onSurfaceVariant = HamSedaColors.NavySoft,
    tertiary = HamSedaColors.WarmAlert,
    error = HamSedaColors.ErrorRed,
    outline = HamSedaColors.DividerLight,
)

private val DarkScheme = darkColorScheme(
    primary = HamSedaColors.TealBright,
    onPrimary = Color(0xFF062B2A),
    primaryContainer = Color(0xFF0B3B3A),
    onPrimaryContainer = HamSedaColors.TealBright,
    secondary = HamSedaColors.NightSoft,
    background = HamSedaColors.NightBackground,
    onBackground = HamSedaColors.NightInk,
    surface = HamSedaColors.NightSurface,
    onSurface = HamSedaColors.NightInk,
    surfaceVariant = Color(0xFF1E2A40),
    onSurfaceVariant = HamSedaColors.NightSoft,
    tertiary = HamSedaColors.WarmAlert,
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF2A3852),
)

@Composable
fun HamSedaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
