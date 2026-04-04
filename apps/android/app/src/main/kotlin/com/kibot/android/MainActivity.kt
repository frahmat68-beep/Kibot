package com.kibot.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kibot.android.data.*
import com.kibot.android.ui.*
import com.kibot.android.ui.theme.*
import com.kibot.android.websocket.KiBotWebSocketClient
import com.kibot.android.widget.KiBotWidgetHelper
import kotlinx.coroutines.flow.*

// Navigation destinations
sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard)
    object Portfolio : Screen("portfolio", "Portfolio", Icons.Filled.PieChart, Icons.Outlined.PieChart)
    object Ledger : Screen("ledger", "History", Icons.Filled.Receipt, Icons.Outlined.Receipt)
}

class KiBotViewModel : ViewModel() {
    private val wsClient = KiBotWebSocketClient()
    
    val botState: StateFlow<BotState> = wsClient.botState
    val connectionStatus: StateFlow<ConnectionStatus> = wsClient.connectionStatus
    val errors: SharedFlow<String> = wsClient.errors
    
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Dashboard)
    val currentScreen: StateFlow<Screen> = _currentScreen
    
    init {
        connect()
    }
    
    fun connect() {
        wsClient.connect()
    }
    
    fun disconnect() {
        wsClient.disconnect()
    }
    
    fun toggleBot(botName: String, enable: Boolean) {
        wsClient.toggleBot(botName, enable)
    }
    
    fun refresh() {
        wsClient.requestFullState()
    }
    
    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }
    
    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            KiBotTheme {
                KiBotApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiBotApp(
    viewModel: KiBotViewModel = viewModel(),
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current
) {
    val botState by viewModel.botState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingBotToggle by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Error handling
    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }
    
    // Update widget with real-time data
    LaunchedEffect(botState) {
        KiBotWidgetHelper.updateWidgetData(
            context = context,
            botState = botState
        )
    }
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        containerColor = DarkBackground,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DarkSurfaceVariant,
                    contentColor = TextPrimary,
                    actionColor = KiBotBlue
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "KiBot",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // Connection indicator
                        ConnectionIndicator(
                            status = connectionStatus,
                            ping = botState.heartbeat.kidax.ping
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = KiBotBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = TextPrimary
            ) {
                val screens = listOf(Screen.Dashboard, Screen.Portfolio, Screen.Ledger)
                
                screens.forEach { screen ->
                    val selected = currentScreen == screen
                    
                    NavigationBarItem(
                        selected = selected,
                        onClick = { viewModel.navigateTo(screen) },
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = {
                            Text(
                                text = screen.title,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = KiBotBlue,
                            selectedTextColor = KiBotBlue,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = KiBotBlue.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn(animationSpec = tween(200)) togetherWith
                            fadeOut(animationSpec = tween(200))
                },
                label = "screenTransition"
            ) { screen ->
                when (screen) {
                    Screen.Dashboard -> {
                        DashboardScreen(
                            botState = botState,
                            isConnected = connectionStatus == ConnectionStatus.CONNECTED,
                            onToggleBot = { botName, enable ->
                                // Show confirmation dialog before toggling
                                pendingBotToggle = botName to enable
                                showConfirmDialog = true
                            },
                            onRefresh = { viewModel.refresh() }
                        )
                    }
                    Screen.Portfolio -> {
                        PortfolioScreen(
                            botState = botState
                        )
                    }
                    Screen.Ledger -> {
                        LedgerScreen(
                            trades = botState.trades
                        )
                    }
                }
            }
        }
        
        // Confirmation dialog for bot toggle
        if (showConfirmDialog && pendingBotToggle != null) {
            val (botName, enable) = pendingBotToggle!!
            AlertDialog(
                onDismissRequest = {
                    showConfirmDialog = false
                    pendingBotToggle = null
                },
                icon = {
                    Icon(
                        imageVector = if (enable) Icons.Default.PlayArrow else Icons.Default.Stop,
                        contentDescription = null,
                        tint = if (enable) ProfitGreen else LossRed
                    )
                },
                title = {
                    Text(
                        text = if (enable) "Enable $botName?" else "Disable $botName?",
                        color = TextPrimary
                    )
                },
                text = {
                    Text(
                        text = if (enable) {
                            "Bot akan mulai trading secara otomatis. Pastikan market dalam kondisi baik."
                        } else {
                            "⚠️ EMERGENCY STOP\n\nBot akan berhenti entry baru. Posisi yang sudah ada tetap dikelola trailing stop lokal.\n\nLanjutkan?"
                        },
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.toggleBot(botName, enable)
                            showConfirmDialog = false
                            pendingBotToggle = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (enable) ProfitGreen else LossRed
                        )
                    ) {
                        Text(if (enable) "Enable" else "Stop Bot")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showConfirmDialog = false
                            pendingBotToggle = null
                        }
                    ) {
                        Text("Batal", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary
            )
        }
    }
}

@Composable
private fun ConnectionIndicator(
    status: ConnectionStatus,
    ping: Long
) {
    val (color, text) = when (status) {
        ConnectionStatus.CONNECTED -> StatusOnline to "Online"
        ConnectionStatus.CONNECTING -> StatusDegraded to "Connecting..."
        ConnectionStatus.DISCONNECTED -> StatusOffline to "Offline"
        ConnectionStatus.ERROR -> StatusOffline to "Error"
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = MaterialTheme.shapes.small)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (status == ConnectionStatus.CONNECTED && ping > 0) "${ping}ms" else text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
