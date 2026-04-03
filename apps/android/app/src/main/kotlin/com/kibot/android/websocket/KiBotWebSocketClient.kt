package com.kibot.android.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kibot.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class KiBotWebSocketClient(
    private val wsUrl: String = "ws://213.35.118.26:8787/ws"
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State flows for UI
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus
    
    private val _botState = MutableStateFlow(BotState())
    val botState: StateFlow<BotState> = _botState
    
    private val _trades = MutableSharedFlow<TradeData>(replay = 50)
    val trades: SharedFlow<TradeData> = _trades
    
    private val _errors = MutableSharedFlow<String>()
    val errors: SharedFlow<String> = _errors
    
    private var lastPingTime = 0L
    private var isConnecting = false

    fun connect() {
        if (isConnecting || _connectionStatus.value == ConnectionStatus.CONNECTED) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        isConnecting = true
        _connectionStatus.value = ConnectionStatus.CONNECTING
        
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(wsUrl)
                    .build()
                webSocket = client.newWebSocket(request, KiBotWebSocketListener())
                Log.d(TAG, "Initiating connection to $wsUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                _errors.emit("Connection failed: ${e.message}")
                _connectionStatus.value = ConnectionStatus.ERROR
                isConnecting = false
                scheduleReconnect()
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        reconnectJob?.cancel()
        webSocket?.close(1000, "User requested disconnect")
        webSocket = null
        isConnecting = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        scope.coroutineContext.cancelChildren()
    }

    fun subscribe() {
        val message = SubscribeMessage(
            channels = listOf("state", "trades", "heartbeat")
        )
        sendMessage(gson.toJson(message))
    }

    fun toggleBot(botName: String, enable: Boolean) {
        val command = mapOf(
            "type" to "command",
            "action" to if (enable) "enable" else "disable",
            "target" to botName
        )
        sendMessage(gson.toJson(command))
    }

    fun requestFullState() {
        val request = mapOf("type" to "request", "data" to "full_state")
        sendMessage(gson.toJson(request))
    }

    private fun sendMessage(json: String) {
        webSocket?.send(json)?.also {
            Log.d(TAG, "Sent: $json")
        } ?: Log.w(TAG, "WebSocket not connected, message not sent")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            Log.d(TAG, "Attempting reconnect...")
            connect()
        }
    }

    private fun parseMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString ?: return
            val dataElement = json.get("data")
            
            when (type) {
                "state" -> {
                    val stateData = gson.fromJson(dataElement, StateData::class.java)
                    updateBotState { currentState ->
                        currentState.copy(
                            balance = stateData.balance,
                            totalReturn = stateData.totalReturn,
                            pnlToday = stateData.pnlToday,
                            positions = stateData.positions,
                            netWorthHistory = stateData.netWorthHistory,
                            lastUpdate = System.currentTimeMillis()
                        )
                    }
                }
                
                "heartbeat" -> {
                    val heartbeatData = gson.fromJson(dataElement, HeartbeatData::class.java)
                    updateBotState { currentState ->
                        currentState.copy(
                            heartbeat = heartbeatData,
                            isConnected = true,
                            lastUpdate = System.currentTimeMillis()
                        )
                    }
                }
                
                "trade" -> {
                    val tradeData = gson.fromJson(dataElement, TradeData::class.java)
                    scope.launch {
                        _trades.emit(tradeData)
                        updateBotState { currentState ->
                            val updatedTrades = (listOf(tradeData) + currentState.trades).take(100)
                            currentState.copy(trades = updatedTrades)
                        }
                    }
                }
                
                "portfolio" -> {
                    val returnSummary = dataElement?.asJsonObject?.get("returns")?.let {
                        gson.fromJson(it, ReturnSummary::class.java)
                    } ?: ReturnSummary()
                    
                    val allocations = dataElement?.asJsonObject?.get("allocation")?.asJsonArray?.map {
                        gson.fromJson(it, AssetAllocation::class.java)
                    } ?: emptyList()
                    
                    updateBotState { currentState ->
                        currentState.copy(
                            returnSummary = returnSummary,
                            assetAllocation = allocations
                        )
                    }
                }
                
                "error" -> {
                    val errorMsg = dataElement?.asString ?: "Unknown error"
                    scope.launch { _errors.emit(errorMsg) }
                }
                
                else -> Log.d(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}", e)
        }
    }

    private inline fun updateBotState(update: (BotState) -> BotState) {
        _botState.value = update(_botState.value)
    }

    private inner class KiBotWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.d(TAG, "WebSocket connected")
            isConnecting = false
            _connectionStatus.value = ConnectionStatus.CONNECTED
            updateBotState { it.copy(isConnected = true) }
            
            // Subscribe to channels
            subscribe()
            
            // Request initial state
            requestFullState()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received: ${text.take(200)}...")
            parseMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Received binary message: ${bytes.size} bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code - $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code - $reason")
            this@KiBotWebSocketClient.webSocket = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            updateBotState { it.copy(isConnected = false) }
            
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            isConnecting = false
            _connectionStatus.value = ConnectionStatus.ERROR
            updateBotState { it.copy(isConnected = false) }
            
            scope.launch { _errors.emit("Connection lost: ${t.message}") }
            scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "KiBotWebSocket"
    }
}

// Legacy compatibility adapter
class LegacyWebSocketAdapter(
    wsUrl: String,
    private val onStatusUpdate: (BotStatus) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = KiBotWebSocketClient(wsUrl)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        scope.launch {
            client.connectionStatus.collect { status ->
                onConnectionChange(status == ConnectionStatus.CONNECTED)
            }
        }
        
        scope.launch {
            client.botState.collect { state ->
                val legacyStatus = BotStatus(
                    balance = Balance(
                        idr = state.balance,
                        usdt = state.balance / 16000.0,
                        total = state.balance
                    ),
                    pnl = PnL(
                        daily = state.pnlToday,
                        percentage = state.totalReturn,
                        trend = state.netWorthHistory.takeLast(24).map { it.value }
                    ),
                    capitalSplit = CapitalSplit(
                        highConviction = state.balance * 0.7,
                        aggressive = state.balance * 0.3
                    ),
                    activeTrades = state.positions.map { pos ->
                        Trade(
                            pair = pos.pair,
                            entry = pos.buyPrice,
                            current = pos.currentPrice,
                            profit = pos.pnl,
                            profitPct = pos.pnlPercent
                        )
                    },
                    status = if (state.isConnected) "Trading" else "Stopped",
                    timestamp = state.lastUpdate
                )
                onStatusUpdate(legacyStatus)
            }
        }
        
        scope.launch {
            client.errors.collect { error ->
                onError(error)
            }
        }
    }
    
    fun connect() = client.connect()
    fun disconnect() = client.disconnect()
    fun requestStatus() = client.requestFullState()
    fun sendCommand(command: String) {
        when (command) {
            "start" -> client.toggleBot("all", true)
            "stop" -> client.toggleBot("all", false)
        }
    }
}
