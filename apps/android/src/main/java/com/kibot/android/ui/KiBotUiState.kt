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
    val pnl: String = "",
)

data class PortfolioTrendPointUi(
    val label: String,
    val valueIdr: Double,
)

data class PortfolioAllocationUi(
    val label: String,
    val valueLabel: String,
    val pct: Double,
    val pctLabel: String,
)

data class PortfolioSectionUi(
    val oneDayReturnLabel: String,
    val oneDayReturnPctLabel: String,
    val sevenDayReturnLabel: String,
    val sevenDayReturnPctLabel: String,
    val cashReadyLabel: String,
    val cashReadyPctLabel: String,
    val totalUnrealizedLabel: String,
    val concentrationLabel: String,
    val chartPoints: List<PortfolioTrendPointUi>,
    val allocations: List<PortfolioAllocationUi>,
    val lastUpdatedLabel: String,
) {
    companion object {
        fun preview(): PortfolioSectionUi = PortfolioSectionUi(
            oneDayReturnLabel = "+Rp0",
            oneDayReturnPctLabel = "+0.0%",
            sevenDayReturnLabel = "+Rp0",
            sevenDayReturnPctLabel = "+0.0%",
            cashReadyLabel = "Rp0",
            cashReadyPctLabel = "0%",
            totalUnrealizedLabel = "+Rp0",
            concentrationLabel = "Top 1 0%",
            chartPoints = listOf(PortfolioTrendPointUi("Hari ini", 0.0)),
            allocations = emptyList(),
            lastUpdatedLabel = "--:-- WIB",
        )
    }
}

data class LogUi(
    val level: String,
    val category: String,
    val message: String,
    val timeLabel: String = "",
)

data class TradeUi(
    val pair: String,
    val side: String,
    val status: String,
    val detail: String,
    val entryPriceLabel: String = "",
    val exitPriceLabel: String = "",
    val outcomeLabel: String = "",
    val timeLabel: String = "",
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
    val portfolio: PortfolioSectionUi,
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
            syncPathLabel = "Live Feed",
            lastUpdatedLabel = "Baru saja",
            statusMessage = "Menunggu sync live Indodax.",
            weeklyLearningSummary = "Belum ada review mingguan.",
            weeklyAdaptationSummary = "Adaptasi mingguan akan muncul setelah data cukup.",
            positions = emptyList(),
            portfolio = PortfolioSectionUi.preview(),
            liveLogEntries = emptyList(),
            logs = emptyList(),
            trades = emptyList(),
            devices = emptyList(),
        )
    }
}
