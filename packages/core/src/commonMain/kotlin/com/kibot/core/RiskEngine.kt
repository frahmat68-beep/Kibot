package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel

data class RiskDecision(
    val allowNewEntries: Boolean,
    val hardStopTriggered: Boolean,
    val maxAllowedAdditionalPositions: Int,
    val suggestedPerPositionBudgetIdr: Double,
    val riskLadderLevel: RiskLadderLevel,
    val suggestedModeFloor: BotMode,
    val profitProtectionStatus: ProfitProtectionStatus,
    val sizeMultiplier: Double,
    val deploymentMultiplier: Double,
    val reasons: List<String>,
)

class RiskEngine(
    private val config: RiskConfig = RiskConfig(),
    private val profitProtectionEngine: ProfitProtectionEngine = ProfitProtectionEngine(),
) {
    fun evaluate(
        portfolio: PortfolioSnapshot,
        dailyRisk: DailyRiskSnapshot,
        health: EngineHealthSnapshot,
    ): RiskDecision {
        val reasons = mutableListOf<String>()
        val openPositions = portfolio.positions.count { it.state != PositionState.CLOSED }
        val currentEquity = portfolio.totalEquityIdr.toDoubleOrZero()
        val ladderLevel = deriveRiskLadder(dailyRisk)
        val hardStopTriggered = dailyRisk.hardStopTriggered || ladderLevel == RiskLadderLevel.HARD_STOP
        val profitProtection = profitProtectionEngine.evaluate(dailyRisk)

        if (hardStopTriggered) {
            reasons += "Emergency daily stop aktif."
        }
        if (dailyRisk.rebasePending) {
            reasons += "Baseline harian perlu rebase manual."
        }
        if (!health.exchangeReachable || !health.supabaseReachable) {
            reasons += "Koneksi inti bot tidak sehat."
        }
        if (!health.websocketHealthy) {
            reasons += "Feed realtime sedang menurun."
        }
        if (health.rejectRatePct >= 0.20) {
            reasons += "Reject rate order memburuk."
        }
        if (health.anomalyCount >= 3) {
            reasons += "Anomali operasional meningkat."
        }
        if (openPositions >= config.maxConcurrentPositions) {
            reasons += "Batas posisi aktif sudah penuh."
        }
        if (ladderLevel == RiskLadderLevel.RESTRICTED_NEW_ENTRIES) {
            reasons += "Risk ladder membatasi entry baru kecuali peluang sangat kuat."
        }
        if (ladderLevel == RiskLadderLevel.STOP_NEW_ENTRIES) {
            reasons += "Risk ladder menghentikan entry baru sementara."
        }
        if (profitProtection.status == ProfitProtectionStatus.COOLING_AGGRESSION) {
            reasons += "Profit protection sedang mendinginkan agresivitas."
        }

        val allowNewEntries = when {
            hardStopTriggered -> false
            dailyRisk.rebasePending -> false
            !health.exchangeReachable || !health.supabaseReachable -> false
            health.syncHealth == com.kibot.shared.models.SyncHealth.BROKEN -> false
            ladderLevel in setOf(RiskLadderLevel.STOP_NEW_ENTRIES, RiskLadderLevel.HARD_STOP) -> false
            openPositions >= config.maxConcurrentPositions -> false
            else -> true
        }

        val reservePct = when {
            ladderLevel in setOf(RiskLadderLevel.DEFENSIVE_MODE, RiskLadderLevel.RESTRICTED_NEW_ENTRIES, RiskLadderLevel.STOP_NEW_ENTRIES) ->
                config.defensiveCashReservePct
            else -> config.minimumCashReservePct
        }
        val availableBudget = (currentEquity * (1.0 - reservePct)).coerceAtLeast(0.0)
        val riskSizeMultiplier = when (ladderLevel) {
            RiskLadderLevel.NORMAL,
            RiskLadderLevel.WARNING,
            -> 1.0
            RiskLadderLevel.REDUCE_SIZE -> config.reducedSizeMultiplier
            RiskLadderLevel.DEFENSIVE_MODE,
            RiskLadderLevel.RESTRICTED_NEW_ENTRIES,
            -> config.defensiveSizeMultiplier
            RiskLadderLevel.STOP_NEW_ENTRIES,
            RiskLadderLevel.HARD_STOP,
            -> 0.0
        }
        val sizeMultiplier = (riskSizeMultiplier * profitProtection.sizeMultiplier).coerceIn(0.0, config.attackSizeMultiplier)
        val deploymentMultiplier = (riskSizeMultiplier * profitProtection.aggressionMultiplier).coerceIn(0.0, 1.0)
        val desiredActiveSlots = when {
            availableBudget < config.targetMinPositionBudgetIdr -> 1
            else -> kotlin.math.floor(availableBudget / config.targetMinPositionBudgetIdr)
                .toInt()
                .coerceAtLeast(1)
                .coerceAtMost(config.maxConcurrentPositions)
        }
        val additionalSlots = (desiredActiveSlots - openPositions).coerceAtLeast(0)
        val slotAwareBudget = if (desiredActiveSlots > 0) {
            availableBudget / desiredActiveSlots
        } else {
            0.0
        }
        val suggestedBudget = minOf(
            availableBudget * config.maxPerPositionBudgetPct * sizeMultiplier,
            slotAwareBudget * (1.0 + ((sizeMultiplier - 1.0) * 0.35)),
        ).coerceAtLeast(0.0)

        return RiskDecision(
            allowNewEntries = allowNewEntries,
            hardStopTriggered = hardStopTriggered,
            maxAllowedAdditionalPositions = if (allowNewEntries) additionalSlots else 0,
            suggestedPerPositionBudgetIdr = if (allowNewEntries) suggestedBudget else 0.0,
            riskLadderLevel = ladderLevel,
            suggestedModeFloor = deriveModeFloor(ladderLevel, allowNewEntries, profitProtection.status),
            profitProtectionStatus = profitProtection.status,
            sizeMultiplier = sizeMultiplier,
            deploymentMultiplier = deploymentMultiplier,
            reasons = reasons,
        )
    }

    private fun deriveRiskLadder(dailyRisk: DailyRiskSnapshot): RiskLadderLevel {
        val drawdown = dailyRisk.drawdownPct
        return when {
            dailyRisk.hardStopTriggered || drawdown >= config.hardDailyLossLimitPct -> RiskLadderLevel.HARD_STOP
            drawdown >= config.stopNewEntriesDrawdownPct -> RiskLadderLevel.STOP_NEW_ENTRIES
            drawdown >= config.restrictedEntriesDrawdownPct -> RiskLadderLevel.RESTRICTED_NEW_ENTRIES
            drawdown >= config.defensiveDrawdownPct -> RiskLadderLevel.DEFENSIVE_MODE
            drawdown >= config.reduceSizeDrawdownPct -> RiskLadderLevel.REDUCE_SIZE
            drawdown >= config.warningDrawdownPct -> RiskLadderLevel.WARNING
            else -> dailyRisk.riskLadderLevel.takeIf { it != RiskLadderLevel.HARD_STOP } ?: RiskLadderLevel.NORMAL
        }
    }

    private fun deriveModeFloor(
        ladderLevel: RiskLadderLevel,
        allowNewEntries: Boolean,
        profitProtectionStatus: ProfitProtectionStatus,
    ): BotMode {
        if (!allowNewEntries) return BotMode.SAFE
        if (profitProtectionStatus == ProfitProtectionStatus.COOLING_AGGRESSION) return BotMode.DEFENSIVE
        return when (ladderLevel) {
            RiskLadderLevel.NORMAL,
            RiskLadderLevel.WARNING,
            RiskLadderLevel.REDUCE_SIZE,
            -> BotMode.GROWTH
            RiskLadderLevel.DEFENSIVE_MODE,
            RiskLadderLevel.RESTRICTED_NEW_ENTRIES,
            -> BotMode.DEFENSIVE
            RiskLadderLevel.STOP_NEW_ENTRIES,
            RiskLadderLevel.HARD_STOP,
            -> BotMode.SAFE
        }
    }
}
