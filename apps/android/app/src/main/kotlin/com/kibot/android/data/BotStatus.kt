package com.kibot.android.data

data class BotStatus(
    val balance: Balance,
    val pnl: PnL,
    val capitalSplit: CapitalSplit,
    val activeTrades: List<Trade>,
    val status: String, // "Trading" / "Stopped"
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
    val trend: List<Double> // Last 24h hourly values
)

data class CapitalSplit(
    val highConviction: Double, // 70%
    val aggressive: Double // 30%
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
    val host: String = "localhost",
    val port: Int = 8787
) {
    fun getUrl(): String = "ws://$host:$port/kibot/status"
}
