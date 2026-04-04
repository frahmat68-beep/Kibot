package com.kibot.android.data

import kotlinx.serialization.Serializable

// ============== WebSocket Message Types ==============

@Serializable
data class WebSocketMessage(
    val type: String,
    val data: String? = null // JSON string of the actual data
)

@Serializable
data class SubscribeMessage(
    val type: String = "subscribe",
    val channels: List<String> = listOf("state", "trades", "heartbeat")
)

// ============== State Data ==============

@Serializable
data class StateData(
    val balance: Double = 0.0,
    val totalReturn: Double = 0.0,
    val pnlToday: Double = 0.0,
    val positions: List<Position> = emptyList(),
    val netWorthHistory: List<NetWorthPoint> = emptyList()
)

@Serializable
data class Position(
    val pair: String,
    val amount: Double,
    val buyPrice: Double,
    val currentPrice: Double,
    val pnl: Double,
    val pnlPercent: Double
)

@Serializable
data class NetWorthPoint(
    val timestamp: Long,
    val value: Double
)

// ============== Heartbeat Data ==============

@Serializable
data class HeartbeatData(
    val kidax: ServiceStatus = ServiceStatus(),
    val kinance: ServiceStatus = ServiceStatus(),
    val kibot: ServiceStatus = ServiceStatus()
)

@Serializable
data class ServiceStatus(
    val status: String = "offline", // "online", "degraded", "offline"
    val ping: Long = 0,
    val aiStatus: String = "idle", // "active", "idle"
    val enabled: Boolean = true,
    val holdings: List<Holding> = emptyList()
)

@Serializable
data class Holding(
    val coin: String,
    val amount: Double,
    val price: Double,
    val pnl: Double
)

// ============== Trade Data ==============

@Serializable
data class TradeData(
    val id: String,
    val pair: String,
    val side: String, // "buy" or "sell"
    val price: Double,
    val amount: Double,
    val total: Double,
    val timestamp: Long,
    val entryPrice: Double? = null,
    val exitPrice: Double? = null,
    val profitLoss: Double? = null
)

// ============== Portfolio Data ==============

@Serializable
data class ReturnSummary(
    val day1: Double = 0.0,
    val day7: Double = 0.0,
    val day30: Double = 0.0
)

@Serializable
data class AssetAllocation(
    val coin: String,
    val percentage: Double,
    val value: Double
)

// ============== UI State Classes ==============

data class BotState(
    val balance: Double = 0.0,
    val totalReturn: Double = 0.0,
    val pnlToday: Double = 0.0,
    val positions: List<Position> = emptyList(),
    val netWorthHistory: List<NetWorthPoint> = emptyList(),
    val returnSummary: ReturnSummary = ReturnSummary(),
    val assetAllocation: List<AssetAllocation> = emptyList(),
    val trades: List<TradeData> = emptyList(),
    val heartbeat: HeartbeatData = HeartbeatData(),
    val effectiveState: String = "STOPPED",
    val syncHealth: String = "DEGRADED",
    val aiProviderSummary: String = "AI summary belum siap.",
    val healthSummary: String = "Menunggu snapshot server.",
    val statusMessage: String = "Server monitor sedang booting.",
    val connectedBotId: String = "unknown",
    val isConnected: Boolean = false,
    val lastUpdate: Long = 0
)

enum class ConnectionStatus {
    CONNECTED, CONNECTING, DISCONNECTED, ERROR
}

// ============== Legacy Compatibility ==============

data class BotStatus(
    val balance: Balance,
    val pnl: PnL,
    val capitalSplit: CapitalSplit,
    val activeTrades: List<Trade>,
    val status: String,
    val timestamp: Long
)

data class Balance(
    val idr: Double,
    val usdt: Double,
    val total: Double
)

data class PnL(
    val daily: Double,
    val percentage: Double,
    val trend: List<Double>
)

data class CapitalSplit(
    val highConviction: Double,
    val aggressive: Double
)

data class Trade(
    val pair: String,
    val entry: Double,
    val current: Double,
    val profit: Double,
    val profitPct: Double
)

// Configuration model
data class ServerConfig(
    val host: String = "213.35.118.26",
    val port: Int = 8787
) {
    fun getUrl(): String = "ws://$host:$port/ws"
}
