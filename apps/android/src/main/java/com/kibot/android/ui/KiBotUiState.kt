package com.kibot.android.ui

import com.kibot.shared.models.BotEffectiveState

enum class EngineAction {
    RequestTakeover,
    ForceSafeTakeover,
    ReleaseControl,
    SyncNow,
}

data class PositionCardUi(
    val pair: String,
    val quantity: String,
    val pnl: String,
)

data class LogUi(
    val level: String,
    val category: String,
    val message: String,
)

data class TradeUi(
    val pair: String,
    val side: String,
    val pnl: String,
)

data class DeviceStatusUi(
    val name: String,
    val online: Boolean,
    val active: Boolean,
    val heartbeat: String,
    val health: String,
)

data class KiBotUiState(
    val isBotRunning: Boolean,
    val effectiveState: BotEffectiveState,
    val operatingMode: String,
    val edgeConfidence: String,
    val marketRegime: String,
    val riskLadderLevel: String,
    val profitProtectionStatus: String,
    val activeEngine: String,
    val standbyEngine: String,
    val syncHealth: String,
    val pnlTodayIdr: String,
    val modalSaatIniIdr: String,
    val drawdownPct: Double,
    val dailyLossLimitPct: Double,
    val riskBlocked: Boolean,
    val pairAktif: String,
    val leaseTerm: Long,
    val syncLagLabel: String,
    val statusMessage: String,
    val weeklyLearningSummary: String,
    val weeklyAdaptationSummary: String,
    val positions: List<PositionCardUi>,
    val logs: List<LogUi>,
    val trades: List<TradeUi>,
    val devices: List<DeviceStatusUi>,
) {
    companion object {
        fun preview(): KiBotUiState = KiBotUiState(
            isBotRunning = false,
            effectiveState = BotEffectiveState.STOPPED,
            operatingMode = "GROWTH",
            edgeConfidence = "MEDIUM",
            marketRegime = "HEALTHY_SIDEWAYS",
            riskLadderLevel = "NORMAL",
            profitProtectionStatus = "INACTIVE",
            activeEngine = "Android",
            standbyEngine = "Mac",
            syncHealth = "HEALTHY",
            pnlTodayIdr = "+Rp2.150",
            modalSaatIniIdr = "Rp102.150",
            drawdownPct = 0.03,
            dailyLossLimitPct = 0.25,
            riskBlocked = false,
            pairAktif = "btc_idr",
            leaseTerm = 7,
            syncLagLabel = "420 ms",
            statusMessage = "Standby mode ready. Android can become master after health checks.",
            weeklyLearningSummary = "Belum ada review mingguan.",
            weeklyAdaptationSummary = "Adaptasi mingguan akan muncul setelah data cukup.",
            positions = listOf(
                PositionCardUi("btc_idr", "0.0004 BTC", "+Rp1.250"),
                PositionCardUi("sol_idr", "0.3 SOL", "+Rp900"),
            ),
            logs = listOf(
                LogUi("INFO", "LEASE", "Lease healthy. Last heartbeat 8s ago."),
                LogUi("WARN", "HEALTH", "Battery low threshold nearing. Consider Mac takeover."),
            ),
            trades = listOf(
                TradeUi("btc_idr", "BUY", "+Rp1.000"),
                TradeUi("eth_idr", "SELL", "+Rp1.150"),
            ),
            devices = listOf(
                DeviceStatusUi("Android Poco M3", online = true, active = true, heartbeat = "8s ago", health = "Healthy"),
                DeviceStatusUi("MacBook Pro 2020", online = true, active = false, heartbeat = "11s ago", health = "Ready"),
            ),
        )
    }
}
