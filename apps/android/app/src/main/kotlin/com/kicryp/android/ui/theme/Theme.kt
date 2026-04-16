package com.kicryp.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Brand Colors
val KiCrypGreen = Color(0xFF7ED957)     // From logo background
val KiCrypBlue = Color(0xFF4A9FD4)      // Robot body blue
val KiCrypOrange = Color(0xFFF5A623)    // Robot accents

// Semantic Colors
val ProfitGreen = Color(0xFF00C853)    // Bright green for profits
val LossRed = Color(0xFFFF5252)        // Red for losses
val NeutralBlue = Color(0xFF2196F3)    // Blue for neutral states
val WarningYellow = Color(0xFFFFD740)  // Yellow for warnings

// Background Colors
val DarkBackground = Color(0xFF0A0A0A)      // Pure dark
val DarkSurface = Color(0xFF141414)         // Card backgrounds
val DarkSurfaceVariant = Color(0xFF1E1E1E)  // Elevated surfaces
val DarkSurfaceElevated = Color(0xFF252525) // Higher elevation

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB3B3B3)
val TextTertiary = Color(0xFF808080)
val TextDisabled = Color(0xFF4D4D4D)

// Status Colors
val StatusOnline = Color(0xFF4CAF50)
val StatusDegraded = Color(0xFFFF9800)
val StatusOffline = Color(0xFFF44336)

// Ping Status Colors
val PingExcellent = Color(0xFF4CAF50)   // < 100ms
val PingGood = Color(0xFFFFEB3B)        // < 500ms
val PingPoor = Color(0xFFF44336)        // > 500ms

// Chart Colors
val ChartLine = Color(0xFF2196F3)
val ChartFill = Color(0x332196F3)
val ChartGrid = Color(0xFF2D2D2D)

// Pie Chart Colors
val PieColors = listOf(
    Color(0xFF2196F3),
    Color(0xFF4CAF50),
    Color(0xFFFF9800),
    Color(0xFFE91E63),
    Color(0xFF9C27B0),
    Color(0xFF00BCD4),
    Color(0xFFFFEB3B),
    Color(0xFF795548)
)

private val DarkColorScheme = darkColorScheme(
    primary = KiCrypBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003258),
    onPrimaryContainer = Color(0xFFD1E4FF),
    
    secondary = KiCrypGreen,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1B5E20),
    onSecondaryContainer = Color(0xFFC8E6C9),
    
    tertiary = KiCrypOrange,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF5D4037),
    onTertiaryContainer = Color(0xFFFFE0B2),
    
    error = LossRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    
    background = DarkBackground,
    onBackground = TextPrimary,
    
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF2D2D2D)
)

@Composable
fun KiCrypTheme(
    darkTheme: Boolean = true, // Always dark
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
