package com.kibot.commandcenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.kibot.commandcenter.service.CommandCenterForegroundService
import com.kibot.commandcenter.ui.CommandCenterRoot
import com.kibot.commandcenter.ui.theme.CommandCenterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        CommandCenterForegroundService.start(this)

        val app = application as CommandCenterApplication
        setContent {
            CommandCenterTheme {
                CommandCenterRoot(app.repository)
            }
        }
    }
}
