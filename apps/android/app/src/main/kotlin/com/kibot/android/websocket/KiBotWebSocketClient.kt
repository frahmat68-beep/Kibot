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
    private var wsUrl: String = ServerConfig().getUrl()
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
    private val pendingDesiredStates = mutableMapOf<String, Boolean>()
    
    private var lastPingTime = 0L
    private var isConnecting = false

    fun connect() {
        if (isConnecting || _connectionStatus.value == ConnectionStatus.CONNECTED) {
            Log.i(TAG, "⚠️ Already connected or connecting, skipping")
            return
        }

        isConnecting = true
        _connectionStatus.value = ConnectionStatus.CONNECTING
        Log.i(TAG, "🔌 Initiating WebSocket connection to: $wsUrl")
        
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(wsUrl)
                    .build()
                Log.i(TAG, "📤 Creating WebSocket with URL: $wsUrl")
                webSocket = client.newWebSocket(request, KiBotWebSocketListener())
                Log.i(TAG, "📤 WebSocket request sent")
            } catch (e: Exception) {
                Log.e(TAG, "🚨 Connection error: ${e.message}", e)
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

    fun reconnect(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        val shouldReconnect = normalized != wsUrl || _connectionStatus.value != ConnectionStatus.CONNECTED
        wsUrl = normalized
        if (!shouldReconnect) return
        disconnect()
        connect()
    }

    fun subscribe() {
        val message = SubscribeMessage(
            channels = listOf("state", "trades", "heartbeat")
        )
        sendMessage(gson.toJson(message))
    }

    fun toggleBot(botName: String, enable: Boolean) {
        try {
            val normalizedBotName = botName.trim().lowercase()
            val commandName = if (enable) "/start" else "/stop"
            
            val command = mapOf(
                "command" to commandName,
                "argument" to normalizedBotName,
                "idempotencyKey" to "android_${System.currentTimeMillis()}",
                "issuedAtEpochMs" to System.currentTimeMillis()
            )
            val jsonCommand = gson.toJson(command)
            pendingDesiredStates[normalizedBotName] = enable
            val sent = sendMessage(jsonCommand)
            Log.d(TAG, "Toggle bot: $botName -> ${if (enable) "START" else "STOP"}, Sent: $jsonCommand")
            scope.launch {
                if (!sent) {
                    _errors.emit("Command gagal dikirim. WebSocket belum terhubung.")
                } else {
                    delay(400)
                    requestFullState()
                    delay(1600)
                    requestFullState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending toggle command: ${e.message}", e)
        }
    }

    fun requestFullState() {
        val request = mapOf("type" to "request", "data" to "full_state")
        sendMessage(gson.toJson(request))
    }

    private fun sendMessage(json: String): Boolean {
        return webSocket?.send(json)?.also {
            Log.d(TAG, "Sent: $json")
        } ?: run {
            Log.w(TAG, "WebSocket not connected, message not sent")
            false
        }
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
            
            // Handle server's CommandCenterWsEnvelope format: {"snapshot": {...}}
            if (json.has("snapshot")) {
                parseCommandCenterSnapshot(json.getAsJsonObject("snapshot"))
                return
            }
            if (json.has("reply")) {
                val reply = json.getAsJsonObject("reply")
                val replySnapshot = reply.getAsJsonObject("updatedSnapshot")
                val replyBotId = replySnapshot?.get("botId")?.asString?.lowercase()
                val currentConnectedBotId = _botState.value.connectedBotId.lowercase()
                if (replySnapshot != null && (
                        currentConnectedBotId == "unknown" ||
                            replyBotId == null ||
                            replyBotId == currentConnectedBotId
                    )
                ) {
                    parseCommandCenterSnapshot(replySnapshot)
                }
                reply.get("message")?.asString?.takeIf { it.isNotBlank() }?.let { message ->
                    updateBotState { currentState ->
                        currentState.copy(
                            statusMessage = message,
                            lastUpdate = System.currentTimeMillis()
                        )
                    }
                }
                reply.get("echoCommand")?.asString?.lowercase()?.let { commandName ->
                    val targetBot = replyBotId
                    if (targetBot != null) {
                        when (commandName) {
                            "/start" -> pendingDesiredStates[targetBot] = true
                            "/stop", "/pause_kidax", "/veto_all" -> pendingDesiredStates[targetBot] = false
                        }
                    }
                }
                return
            }
            
            // Handle legacy format with "type" field
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
    
    /**
     * Parse CommandCenterLiveSnapshot from server WebSocket
     * Server sends: {"snapshot": {totalValueIdr: "Rp110.486", holdingsDetailed: [...], ...}}
     */
    private fun parseCommandCenterSnapshot(snapshot: JsonObject) {
        try {
            val currentState = _botState.value

            // Parse balance from "totalValueIdr" (format: "Rp110.486" or "Rp1.234.567")
            val totalValueIdr = snapshot.get("totalValueIdr")?.asString ?: "Rp0"
            val balance = parseRupiahToDouble(totalValueIdr)
            
            // Parse PnL today from "pnlTodayIdr" (format: "-Rp55" or "+Rp1.234")
            val pnlTodayIdr = snapshot.get("pnlTodayIdr")?.asString ?: "Rp0"
            val pnlToday = parseRupiahToDouble(pnlTodayIdr)
            
            // Parse daily return percentage from "pnlTodayPctLabel" (format: "-0.1%" or "+29.9%")
            val pnlTodayPctLabel = snapshot.get("pnlTodayPctLabel")?.asString ?: "0%"
            val pnlTodayPercent = parsePercentToDouble(pnlTodayPctLabel)
            
            // "totalReturn" in the app now mirrors 1D return for consistency with the dashboard/widget.
            val totalReturnPctLabel = snapshot.get("totalReturnPctLabel")?.asString 
                ?: snapshot.get("cumulativeReturnPctLabel")?.asString 
                ?: pnlTodayPctLabel
            val totalReturn = parsePercentToDouble(totalReturnPctLabel)
            
            // Parse holdings from "holdingsDetailed"
            val positions = mutableListOf<Position>()
            snapshot.getAsJsonArray("holdingsDetailed")?.forEach { holdingElement ->
                val holding = holdingElement.asJsonObject
                val assetCode = holding.get("assetCode")?.asString ?: ""
                val quantityLabel = holding.get("quantityLabel")?.asString ?: "0"
                val entryPriceLabel = holding.get("entryPriceLabel")?.asString ?: "Rp0"
                val currentPriceLabel = holding.get("currentPriceLabel")?.asString ?: "Rp0"
                val pnlIdrLabel = holding.get("pnlIdrLabel")?.asString ?: "Rp0"
                val pnlPctLabel = holding.get("pnlPctLabel")?.asString ?: "0%"
                
                val amount = quantityLabel.split(" ").firstOrNull()?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                val entryPrice = parseRupiahToDouble(entryPriceLabel)
                val currentPrice = parseRupiahToDouble(currentPriceLabel)
                val pnl = parseRupiahToDouble(pnlIdrLabel)
                val pnlPercent = parsePercentToDouble(pnlPctLabel)
                
                if (assetCode.isNotEmpty() && amount > 0) {
                    positions.add(Position(
                        pair = "${assetCode.lowercase()}_idr",
                        amount = amount,
                        buyPrice = entryPrice,
                        currentPrice = currentPrice,
                        pnl = pnl,
                        pnlPercent = pnlPercent
                    ))
                }
            }
            
            // Parse recent trades from "recentOrders"
            val trades = mutableListOf<TradeData>()
            snapshot.getAsJsonArray("recentOrders")?.take(20)?.forEach { orderElement ->
                val order = orderElement.asJsonObject
                val pair = order.get("pair")?.asString ?: ""
                val side = order.get("side")?.asString?.lowercase() ?: "buy"
                val detail = order.get("detail")?.asString ?: ""
                val timestampMs = order.get("timestampEpochMs")?.asLong ?: System.currentTimeMillis()
                val pnlLabel = order.get("pnlIdrLabel")?.asString
                val pnlPctLabel = order.get("pnlPctLabel")?.asString
                
                // Parse detail for price/amount (format: "203 @ Rp107")
                val priceMatch = Regex("""(\d+(?:[.,]\d+)?)\s*@\s*Rp?([\d.,]+)""").find(detail)
                val amount = priceMatch?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                val price = priceMatch?.groupValues?.get(2)?.replace(".", "")?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                
                if (pair.isNotEmpty()) {
                    trades.add(TradeData(
                        id = "${pair}_${timestampMs}",
                        pair = pair,
                        side = side,
                        price = price,
                        amount = amount,
                        total = price * amount,
                        timestamp = timestampMs,
                        profitLoss = pnlLabel?.let { parseRupiahToDouble(it) }
                    ))
                }
            }
            
            // Parse heartbeat/bot status
            val effectiveState = snapshot.get("effectiveState")?.asString ?: "STOPPED"
            val syncHealth = snapshot.get("syncHealth")?.asString ?: "DEGRADED"
            val exchangePingMs = snapshot.get("exchangePingValueMs")?.asLong ?: 0L
            val liveExecutionEnabled = snapshot.get("liveExecutionEnabled")?.asBoolean ?: false
            val aiProviderSummary = snapshot.get("aiProviderSummary")?.asString ?: "AI summary belum siap."
            val healthSummary = snapshot.get("healthSummary")?.asString ?: "Menunggu status server."
            val statusMessage = snapshot.get("statusMessage")?.asString ?: "Server monitor sedang booting."
            val connectedBotId = snapshot.get("botId")?.asString?.lowercase() ?: currentState.connectedBotId
            val syncHealthStatus = syncHealth.uppercase()

            val aiStatus = deriveAiStatus(aiProviderSummary)

            val connectedEnabled = pendingDesiredStates[connectedBotId]
                ?: when (connectedBotId) {
                    "kidax" -> liveExecutionEnabled && effectiveState != "STOPPED"
                    else -> effectiveState != "STOPPED"
                }

            val connectedService = ServiceStatus(
                status = deriveStatus(effectiveState, syncHealthStatus),
                ping = exchangePingMs,
                aiStatus = aiStatus,
                enabled = connectedEnabled,
                holdings = if (connectedBotId == "kidax") {
                    positions.map { Holding(it.pair.split("_").first().uppercase(), it.amount, it.currentPrice, it.pnl) }
                } else {
                    emptyList()
                }
            )

            val kinancePingMs = snapshot.get("kinancePingMs")?.asLong
                ?: snapshot.get("kinanceLatencyMs")?.asLong
                ?: 0L

            val kidaxNodeStatus = snapshot.get("kidaxNodeStatus")?.asString ?: currentState.heartbeat.kidax.status
            val kinanceNodeStatus = snapshot.get("kinanceNodeStatus")?.asString ?: currentState.heartbeat.kinance.status
            val kibotNodeStatus = snapshot.get("kibotNodeStatus")?.asString ?: currentState.heartbeat.kibot.status

            val kidaxStatus = when (connectedBotId) {
                "kidax" -> connectedService.copy(status = kidaxNodeStatus)
                else -> currentState.heartbeat.kidax.copy(status = kidaxNodeStatus)
            }
            val kinanceStatus = when (connectedBotId) {
                "kinance" -> connectedService.copy(holdings = emptyList(), status = kinanceNodeStatus)
                else -> currentState.heartbeat.kinance.copy(
                    ping = kinancePingMs,
                    status = kinanceNodeStatus,
                    holdings = emptyList()
                )
            }
            val kibotStatus = when (connectedBotId) {
                "kibot" -> connectedService.copy(holdings = emptyList(), status = kibotNodeStatus)
                else -> currentState.heartbeat.kibot.copy(
                    ping = 0L,
                    status = kibotNodeStatus,
                    holdings = emptyList()
                )
            }
            
            // Parse return summaries
            val return7dPct = parsePercentToDouble(snapshot.get("return7dPctLabel")?.asString ?: "0%")
            val return30dPct = parsePercentToDouble(snapshot.get("return30dPctLabel")?.asString ?: "0%")
            
            // Parse net worth history for charts
            val netWorthHistory = mutableListOf<NetWorthPoint>()
            snapshot.getAsJsonArray("netWorthHistory")?.forEach { point ->
                val pointObj = point.asJsonObject
                val timestamp = pointObj.get("timestamp")?.asLong ?: System.currentTimeMillis()
                val value = parseRupiahToDouble(pointObj.get("value")?.asString ?: "Rp0")
                netWorthHistory.add(NetWorthPoint(timestamp, value))
            }
            
            // If no history provided, add current balance as baseline
            if (netWorthHistory.isEmpty()) {
                netWorthHistory.add(NetWorthPoint(System.currentTimeMillis(), balance))
            }
            
            // Parse asset allocation details for pie chart
            val assetAllocations = mutableListOf<AssetAllocation>()
            snapshot.getAsJsonArray("assetAllocationDetailed")?.forEach { item ->
                val obj = item.asJsonObject
                val coin = obj.get("coin")?.asString ?: ""
                val percentageLabel = obj.get("percentageLabel")?.asString ?: "0%"
                val valueLabel = obj.get("valueLabel")?.asString ?: "Rp0"
                
                if (coin.isNotEmpty()) {
                    assetAllocations.add(AssetAllocation(
                        coin = coin,
                        percentage = parsePercentToDouble(percentageLabel),
                        value = parseRupiahToDouble(valueLabel)
                    ))
                }
            }
            
            // If no allocations provided, derive from positions + free cash
            if (assetAllocations.isEmpty() && (positions.isNotEmpty() || balance > 0)) {
                val cryptoValue = positions.sumOf { it.currentPrice * it.amount }
                val freeIdrValue = balance - cryptoValue  // Free cash = Total balance - crypto holdings
                val totalPortfolio = balance  // Total = crypto + cash
                
                // Add free cash (IDR) first
                if (freeIdrValue > 0 && totalPortfolio > 0) {
                    val cashPct = (freeIdrValue / totalPortfolio) * 100
                    assetAllocations.add(AssetAllocation(
                        coin = "IDR",
                        percentage = cashPct,
                        value = freeIdrValue
                    ))
                }
                
                // Add crypto positions
                positions.forEach { pos ->
                    val value = pos.currentPrice * pos.amount
                    val pct = if (totalPortfolio > 0) (value / totalPortfolio) * 100 else 0.0
                    assetAllocations.add(AssetAllocation(
                        coin = pos.pair.split("_").first().uppercase(),
                        percentage = pct,
                        value = value
                    ))
                }
            } else if (assetAllocations.isNotEmpty()) {
                // Allocations exist from server, but ensure cash/IDR is included
                val hasCash = assetAllocations.any { it.coin.uppercase() == "IDR" || it.coin.uppercase() == "CASH" }
                if (!hasCash && balance > 0) {
                    val cryptoValue = positions.sumOf { it.currentPrice * it.amount }
                    val freeIdrValue = balance - cryptoValue
                    if (freeIdrValue > 0) {
                        val totalPortfolio = balance
                        val cashPct = (freeIdrValue / totalPortfolio) * 100
                        assetAllocations.add(0, AssetAllocation(
                            coin = "IDR",
                            percentage = cashPct,
                            value = freeIdrValue
                        ))
                    }
                }
            }
            
            val authoritativeBundle =
                balance > 0.0 ||
                    positions.isNotEmpty() ||
                    trades.isNotEmpty() ||
                    assetAllocations.isNotEmpty() ||
                    netWorthHistory.size > 1
            val bootLikeSnapshot =
                effectiveState in listOf("STOPPED", "DEGRADED") ||
                    syncHealthStatus in listOf("DEGRADED", "BROKEN") ||
                    aiProviderSummary.contains("OFFLINE", ignoreCase = true) ||
                    healthSummary.contains("sync", ignoreCase = true) ||
                    healthSummary.contains("lease", ignoreCase = true) ||
                    statusMessage.contains("boot", ignoreCase = true)
            val preserveBundle = bootLikeSnapshot && !authoritativeBundle
            val kidaxPrimarySnapshot = connectedBotId == "kidax"
            val keepExistingFinancials = preserveBundle || !kidaxPrimarySnapshot
            val nextBalance = if (keepExistingFinancials) currentState.balance else balance
            val nextPnlToday = if (keepExistingFinancials) currentState.pnlToday else pnlToday
            val nextTotalReturn = if (keepExistingFinancials) currentState.totalReturn else totalReturn
            val nextPositions = if (keepExistingFinancials) currentState.positions else positions
            val nextTrades = if (keepExistingFinancials) currentState.trades else trades
            val nextHistory = if (keepExistingFinancials) currentState.netWorthHistory else netWorthHistory
            val nextAllocations = if (keepExistingFinancials) currentState.assetAllocation else assetAllocations

            updateBotState { currentState ->
                currentState.copy(
                    balance = nextBalance,
                    totalReturn = nextTotalReturn,
                    pnlToday = nextPnlToday,
                    positions = nextPositions,
                    trades = nextTrades,
                    heartbeat = HeartbeatData(
                        kidax = kidaxStatus,
                        kinance = kinanceStatus,
                        kibot = kibotStatus
                    ),
                    returnSummary = ReturnSummary(
                        day1 = pnlTodayPercent,  // Fix: use daily percent here
                        day7 = return7dPct,
                        day30 = return30dPct
                    ),
                    netWorthHistory = nextHistory,
                    assetAllocation = nextAllocations,
                    effectiveState = effectiveState,
                    syncHealth = syncHealth,
                    aiProviderSummary = aiProviderSummary,
                    healthSummary = healthSummary,
                    statusMessage = statusMessage,
                    connectedBotId = connectedBotId,
                    isConnected = true,
                    lastUpdate = System.currentTimeMillis()
                )
            }
            
            Log.d(TAG, "Parsed snapshot: balance=$balance, totalReturn=$totalReturn%, pnlToday=$pnlToday (${pnlTodayPercent}%), " +
                "positions=${positions.size}, trades=${trades.size}, allocations=${assetAllocations.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CommandCenter snapshot: ${e.message}", e)
        }
    }
    
    /**
     * Parse Indonesian Rupiah format to Double
     * Examples: "Rp110.486" -> 110486.0, "-Rp55" -> -55.0, "+Rp1.234.567" -> 1234567.0
     */
    private fun parseRupiahToDouble(value: String): Double {
        val isNegative = value.startsWith("-")
        val cleaned = value
            .replace("Rp", "")
            .replace("+", "")
            .replace("-", "")
            .replace(".", "")  // Remove thousand separators
            .replace(",", ".")  // Convert decimal separator
            .trim()
        val result = cleaned.toDoubleOrNull() ?: 0.0
        return if (isNegative) -result else result
    }
    
    /**
     * Parse percentage format to Double
     * Examples: "-0.1%" -> -0.1, "+29.9%" -> 29.9
     */
    private fun parsePercentToDouble(value: String): Double {
        val cleaned = value
            .replace("%", "")
            .replace(",", ".")
            .trim()
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private inline fun updateBotState(update: (BotState) -> BotState) {
        _botState.value = update(_botState.value)
    }

    private fun deriveStatus(currentEffectiveState: String, currentSyncHealth: String): String = when {
        currentEffectiveState == "STOPPED" -> "offline"
        currentSyncHealth == "BROKEN" -> "degraded"
        currentEffectiveState == "DEGRADED" || currentSyncHealth == "DEGRADED" -> "degraded"
        else -> "online"
    }

    private fun deriveAiStatus(aiProviderSummary: String): String = when {
        aiProviderSummary.contains("OFFLINE", ignoreCase = true) -> "idle"
        aiProviderSummary.contains("LIMITED", ignoreCase = true) -> "limited"
        else -> "active"
    }

    private inner class KiBotWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.i(TAG, "✅ WebSocket CONNECTED to $wsUrl")
            isConnecting = false
            _connectionStatus.value = ConnectionStatus.CONNECTED
            updateBotState { it.copy(isConnected = true) }
            
            // Subscribe to channels
            subscribe()
            
            // Request initial state
            requestFullState()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.i(TAG, "📥 Received message: ${text.take(150)}...")
            parseMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.i(TAG, "📥 Received binary: ${bytes.size} bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "⚠️ WebSocket closing: $code - $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "❌ WebSocket closed: $code - $reason")
            this@KiBotWebSocketClient.webSocket = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            updateBotState { it.copy(isConnected = false) }
            
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.e(TAG, "🚨 WebSocket FAILURE: ${t.message}", t)
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
