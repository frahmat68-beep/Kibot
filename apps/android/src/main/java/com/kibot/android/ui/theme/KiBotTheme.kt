package com.kibot.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPalette = lightColorScheme(
    primary = Color(0xFF1C5D99),
    onPrimary = Color.White,
    secondary = Color(0xFF0B7A75),
    tertiary = Color(0xFF9A4D1F),
    background = Color(0xFFF3F5F9),
    surface = Color(0xFFF3F5F9),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8EDF7),
    surfaceContainerLowest = Color(0xFFDFE6F2),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF9BC1FF),
    onPrimary = Color(0xFF06264E),
    secondary = Color(0xFF73D2C9),
    tertiary = Color(0xFFFFB58F),
    background = Color(0xFF0B1220),
    surface = Color(0xFF0B1220),
    surfaceContainer = Color(0xFF111A2D),
    surfaceContainerHigh = Color(0xFF162238),
    surfaceContainerLowest = Color(0xFF0E1627),
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
