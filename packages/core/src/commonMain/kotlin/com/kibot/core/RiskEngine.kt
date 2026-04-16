package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel
import kotlin.math.absoluteValue

data class RiskDecision(
    val allowNewEntries: Boolean,
    val hardStopTriggered: Boolean,
    val maxAllowedAdditionalPositions: Int,
    val suggestedPerPositionBudgetIdr: Double,
    val riskLadderLevel: RiskLadderLevel,
    val suggestedModeFloor: BotMode,
    val profitProtectionStatus: ProfitProtectionStatus,
    val dailyProfitLockActive: Boolean,
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
        val heat = derivePortfolioHeat(portfolio, currentEquity)
        val ladderLevel = deriveRiskLadder(dailyRisk)
        val realizedLoss = (-dailyRisk.realizedPnlIdr.toDoubleOrZero()).coerceAtLeast(0.0)
        val dailyTradeCount = dailyRisk.dailyTradeCount.coerceAtLeast(0)
        val dailyRoundTripCount = dailyRisk.dailyRoundTripCount.coerceAtLeast(0)
        val realizedLossTriggered = realizedLoss >= config.hardRealizedLossLimitIdr
        val dailyProfitLockActive = dailyNetProfitPct(dailyRisk) >= config.dailyProfitLockPct
        val hardStopTriggered =
            dailyRisk.hardStopTriggered ||
            ladderLevel == RiskLadderLevel.HARD_STOP ||
            realizedLossTriggered
        val profitProtection = profitProtectionEngine.evaluate(dailyRisk)

        if (hardStopTriggered) {
            reasons += "Emergency daily stop aktif."
        }
        if (realizedLossTriggered) {
            reasons += "Realized loss harian melewati batas rupiah."
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
        if (heat.loserHeatPct >= config.loserHeatCautionPct && heat.loserHeatPct > heat.winnerHeatPct) {
            reasons += "Loser heat portofolio lebih besar dari winner heat, jadi entry baru perlu lebih disiplin."
        }
        if (heat.top1ConcentrationPct >= config.top1DeployableConcentrationMaxPct) {
            reasons += "Satu aset sudah terlalu dominan di modal aktif."
        }
        if (openPositions >= config.maxConcurrentPositions) {
            reasons += "Batas posisi aktif sudah penuh."
        }
        if (dailyTradeCount >= config.maxDailyTradeActions) {
            reasons += "Batas trade harian sudah penuh."
        }
        if (dailyRoundTripCount >= config.maxDailyRoundTrips) {
            reasons += "Batas round-trip harian sudah penuh."
        }
        if (dailyProfitLockActive) {
            reasons += "Daily profit lock aktif, entry baru dikunci sampai reset berikutnya."
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
            realizedLossTriggered -> false
            dailyRisk.rebasePending -> false
            dailyProfitLockActive -> false
            // FIX: DEGRADED MODE - Supabase down TIDAK boleh block entry!
            // Bot tetap trade meskipun control plane unreachable
            // OLD: !health.exchangeReachable || !health.supabaseReachable -> false
            !health.exchangeReachable -> false  // Only block if EXCHANGE down
            health.syncHealth == com.kibot.shared.models.SyncHealth.BROKEN -> false
            dailyTradeCount >= config.maxDailyTradeActions -> false
            dailyRoundTripCount >= config.maxDailyRoundTrips -> false
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
        val heatPenalty = when {
            heat.loserHeatPct >= config.loserHeatHardBrakePct && heat.loserHeatPct > heat.winnerHeatPct -> 0.72
            heat.loserHeatPct >= config.loserHeatCautionPct -> 0.86
            else -> 1.0
        }
        val concentrationPenalty = when {
            heat.top2ConcentrationPct >= config.top2DeployableConcentrationMaxPct -> 0.82
            heat.top1ConcentrationPct >= config.top1DeployableConcentrationMaxPct -> 0.90
            else -> 1.0
        }
        val portfolioPenalty = minOf(heatPenalty, concentrationPenalty)
        val sizeMultiplier = (riskSizeMultiplier * profitProtection.sizeMultiplier * portfolioPenalty)
            .coerceIn(0.0, config.attackSizeMultiplier)
        val deploymentMultiplier = (riskSizeMultiplier * profitProtection.aggressionMultiplier * portfolioPenalty)
            .coerceIn(0.0, 1.0)
        val safetyAdditionalPositionsCeiling = (config.maxConcurrentPositions - openPositions).coerceAtLeast(0)
        val suggestedBudget = minOf(
            availableBudget,
            availableBudget * config.maxPerPositionBudgetPct * sizeMultiplier,
        ).coerceAtLeast(0.0)

        return RiskDecision(
            allowNewEntries = allowNewEntries,
            hardStopTriggered = hardStopTriggered,
            maxAllowedAdditionalPositions = if (allowNewEntries) safetyAdditionalPositionsCeiling else 0,
            suggestedPerPositionBudgetIdr = if (allowNewEntries) suggestedBudget else 0.0,
            riskLadderLevel = ladderLevel,
            suggestedModeFloor = deriveModeFloor(ladderLevel, allowNewEntries, profitProtection.status),
            profitProtectionStatus = profitProtection.status,
            dailyProfitLockActive = dailyProfitLockActive,
            sizeMultiplier = sizeMultiplier,
            deploymentMultiplier = deploymentMultiplier,
            reasons = reasons,
        )
    }

    private fun derivePortfolioHeat(
        portfolio: PortfolioSnapshot,
        currentEquity: Double,
    ): PortfolioHeat {
        if (currentEquity <= 0.0) return PortfolioHeat()
        val openPositions = portfolio.positions.filter { it.state != PositionState.CLOSED }
        if (openPositions.isEmpty()) return PortfolioHeat()
        val positionValues = openPositions.map { position ->
            val currentValue = (
                (position.quantity.toDoubleOrZero() * position.averageEntryPrice.toDoubleOrZero()) +
                    position.unrealizedPnlIdr.toDoubleOrZero()
                ).coerceAtLeast(0.0)
            PositionHeatValue(
                currentValueIdr = currentValue,
                unrealizedPnlIdr = position.unrealizedPnlIdr.toDoubleOrZero(),
            )
        }
        val winnerHeat = positionValues
            .sumOf { value -> value.unrealizedPnlIdr.takeIf { it > 0.0 } ?: 0.0 }
            .absoluteValue / currentEquity
        val loserHeat = positionValues
            .sumOf { value -> value.unrealizedPnlIdr.takeIf { it < 0.0 }?.absoluteValue ?: 0.0 } / currentEquity
        val sortedValues = positionValues.map { it.currentValueIdr }.sortedDescending()
        return PortfolioHeat(
            winnerHeatPct = winnerHeat,
            loserHeatPct = loserHeat,
            top1ConcentrationPct = sortedValues.firstOrNull().orZero() / currentEquity,
            top2ConcentrationPct = sortedValues.take(2).sum() / currentEquity,
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

    private fun dailyNetProfitPct(dailyRisk: DailyRiskSnapshot): Double {
        val opening = dailyRisk.openingEquityIdr.toDoubleOrZero().coerceAtLeast(1.0)
        val current = dailyRisk.currentEquityIdr.toDoubleOrZero().coerceAtLeast(0.0)
        return ((current - opening) / opening).coerceAtLeast(-1.0)
    }
}

private data class PositionHeatValue(
    val currentValueIdr: Double,
    val unrealizedPnlIdr: Double,
)

private data class PortfolioHeat(
    val winnerHeatPct: Double = 0.0,
    val loserHeatPct: Double = 0.0,
    val top1ConcentrationPct: Double = 0.0,
    val top2ConcentrationPct: Double = 0.0,
)

private fun Double?.orZero(): Double = this ?: 0.0
