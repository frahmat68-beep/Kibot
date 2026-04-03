package com.kibot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.kibot.android.data.BotStatus
import com.kibot.android.ui.DashboardScreen
import com.kibot.android.ui.DarkBackground
import com.kibot.android.ui.SettingsScreen
import com.kibot.android.util.PreferencesManager
import com.kibot.android.websocket.KiBotWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DashboardViewModel(private val prefManager: PreferencesManager) : ViewModel() {
    private val _botStatus = MutableStateFlow<BotStatus?>(null)
    val botStatus: StateFlow<BotStatus?> = _botStatus

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage

    private var wsClient: KiBotWebSocketClient? = null

    fun connect() {
        val config = prefManager.getServerConfig()
        val wsUrl = config.getUrl()
        
        wsClient?.disconnect()
        
        wsClient = KiBotWebSocketClient(
            wsUrl = wsUrl,
            onStatusUpdate = { status ->
                _botStatus.value = status
                _isLoading.value = false
                val gson = Gson()
                prefManager.saveLastKnownStatus(gson.toJson(status))
            },
            onConnectionChange = { connected ->
                _isConnected.value = connected
                if (!connected) {
                    _isLoading.value = false
                }
            },
            onError = { error ->
                _errorMessage.value = error
            }
        )
        
        wsClient?.connect()
    }

    fun disconnect() {
        wsClient?.disconnect()
    }

    fun requestStatus() {
        _isLoading.value = true
        wsClient?.requestStatus()
    }

    fun sendCommand(command: String) {
        wsClient?.sendCommand(command)
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}

class DashboardViewModelFactory(private val prefManager: PreferencesManager) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(prefManager) as T
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(PreferencesManager(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainApp(viewModel = viewModel)
                }
            }
        }

        viewModel.connect()
    }

    override fun onDestroy() {
        viewModel.disconnect()
        super.onDestroy()
    }
}

@Composable
fun MainApp(viewModel: DashboardViewModel) {
    var currentScreen by remember { mutableStateOf("dashboard") }
    
    val botStatus by viewModel.botStatus.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current

    when (currentScreen) {
        "dashboard" -> {
            DashboardScreen(
                status = botStatus,
                isConnected = isConnected,
                isLoading = isLoading,
                onRefresh = { viewModel.requestStatus() },
                onStartTrading = {
                    viewModel.sendCommand("start")
                },
                onStopTrading = {
                    viewModel.sendCommand("stop")
                },
                onSettingsClick = {
                    currentScreen = "settings"
                }
            )
        }

        "settings" -> {
            val prefManager = remember { PreferencesManager(context) }
            val currentConfig = remember { prefManager.getServerConfig() }

            SettingsScreen(
                currentConfig = currentConfig,
                onSave = { newConfig ->
                    prefManager.saveServerConfig(newConfig)
                    viewModel.connect()
                    currentScreen = "dashboard"
                },
                onBack = {
                    currentScreen = "dashboard"
                }
            )
        }
    }
}
