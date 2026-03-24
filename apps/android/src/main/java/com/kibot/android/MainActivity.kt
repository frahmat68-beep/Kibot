package com.kibot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.kibot.android.runtime.BotForegroundService
import com.kibot.android.ui.KiBotRoot
import com.kibot.android.ui.theme.KiBotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as KiBotApplication
        val repository = app.container.repository

        BotForegroundService.start(this)

        lifecycleScope.launch {
            repository.syncNow()
        }

        setContent {
            val state by repository.uiState.collectAsState()
            KiBotTheme(
                darkTheme = isSystemInDarkTheme(),
            ) {
                KiBotRoot(
                    state = state,
                )
            }
        }
    }
}
