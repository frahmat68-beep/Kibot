package com.kibot.android.ui

import com.kibot.android.runtime.LiveLogEntry
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
    val value: String,
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
    val detail: String,
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
    val internetPingLabel: String,
    val pnlTodayIdr: String,
    val pnlTodayPctLabel: String,
    val modalSaatIniIdr: String,
    val scanUniverseCount: Int,
    val radarPairs: List<String>,
    val drawdownPct: Double,
    val dailyLossLimitPct: Double,
    val riskBlocked: Boolean,
    val pairAktif: String,
    val leaseTerm: Long,
    val syncLagLabel: String,
    val syncPathLabel: String,
    val lastUpdatedLabel: String,
    val statusMessage: String,
    val weeklyLearningSummary: String,
    val weeklyAdaptationSummary: String,
    val positions: List<PositionCardUi>,
    val liveLogEntries: List<LiveLogEntry>,
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
            internetPingLabel = "--",
            pnlTodayIdr = "+Rp0",
            pnlTodayPctLabel = "+0.0%",
            modalSaatIniIdr = "Rp0",
            scanUniverseCount = 0,
            radarPairs = emptyList(),
            drawdownPct = 0.0,
            dailyLossLimitPct = 0.25,
            riskBlocked = false,
            pairAktif = "-",
            leaseTerm = 7,
            syncLagLabel = "-",
            syncPathLabel = "Supabase",
            lastUpdatedLabel = "Baru saja",
            statusMessage = "Menunggu sync live Indodax.",
            weeklyLearningSummary = "Belum ada review mingguan.",
            weeklyAdaptationSummary = "Adaptasi mingguan akan muncul setelah data cukup.",
            positions = emptyList(),
            liveLogEntries = emptyList(),
            logs = emptyList(),
            trades = emptyList(),
            devices = emptyList(),
        )
    }
}
