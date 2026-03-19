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
import com.kibot.android.ui.withLiveSnapshot
import com.kibot.android.ui.theme.KiBotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as KiBotApplication
        val repository = app.container.repository

        lifecycleScope.launch {
            repository.syncNow()
        }
        if (repository.isDesiredOn()) {
            BotForegroundService.start(this)
        }

        setContent {
            val state by repository.uiState.collectAsState()
            val live by app.container.liveStatusStore.state.collectAsState()
            KiBotTheme(
                darkTheme = isSystemInDarkTheme(),
            ) {
                KiBotRoot(
                    state = state.withLiveSnapshot(live),
                    onToggleBot = {
                        val running = repository.toggleBot()
                        if (running) {
                            BotForegroundService.start(this)
                        } else {
                            BotForegroundService.stop(this)
                        }
                    },
                    onCommand = repository::dispatchCommand,
                )
            }
        }
    }
}
