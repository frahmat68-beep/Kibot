package com.kibot.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPalette = lightColorScheme(
    primary = Color(0xFF0E5BC8),
    onPrimary = Color.White,
    secondary = Color(0xFF00695A),
    tertiary = Color(0xFF8A3C14),
    background = Color(0xFFF5F7FB),
    surface = Color(0xFFF5F7FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF3FF),
    surfaceContainerLowest = Color(0xFFE7EEF9),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF8EB3FF),
    onPrimary = Color(0xFF032861),
    secondary = Color(0xFF67D6C2),
    tertiary = Color(0xFFFFB08E),
    background = Color(0xFF0E1118),
    surface = Color(0xFF0E1118),
    surfaceContainer = Color(0xFF171C27),
    surfaceContainerHigh = Color(0xFF1D2533),
    surfaceContainerLowest = Color(0xFF111722),
)

@Composable
fun KiBotTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        typography = KiBotTypography,
        content = content,
    )
}

