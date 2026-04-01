package com.kibot.commandcenter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CommandCenterDark = darkColorScheme(
    primary = Color(0xFF85D6FF),
    secondary = Color(0xFF8E7CFF),
    tertiary = Color(0xFF53E0B3),
    background = Color(0xFF07111F),
    surface = Color(0xFF0E182D),
    surfaceVariant = Color(0xFF1A2740),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color(0xFFEAF2FF),
    onSurface = Color(0xFFEAF2FF),
    onSurfaceVariant = Color(0xFFB7C3DD),
)

@Composable
fun CommandCenterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CommandCenterDark,
        typography = CommandCenterTypography,
        content = content,
    )
}
