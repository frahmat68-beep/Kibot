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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as KiBotApplication
        val repository = app.container.repository

        BotForegroundService.stop(this)

        lifecycleScope.launch {
            repository.syncNow()
            while (true) {
                delay(8_000)
                repository.syncNow()
            }
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
