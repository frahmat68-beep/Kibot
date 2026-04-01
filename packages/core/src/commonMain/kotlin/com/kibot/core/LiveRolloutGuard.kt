package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.PairTier
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.WeeklyLearningSummary

data class LiveRolloutConfig(
    val shadowMinRankingScore: Double = 0.66,
    val shadowMinExpectedEdgePct: Double = 0.32,
    val shadowMinBotHealthScore: Double = 0.60,
    val shadowMinOpportunityScore: Double = 0.54,
    val minimumWeeklyTradeSamples: Int = 4,
    val speculativeMinWeeklyTradeSamples: Int = 4,
    val speculativeMinRankingScore: Double = 0.56,
    val speculativeMinExpectedEdgePct: Double = 0.28,
    val speculativeMaxWeeklyFalseEntryRate: Double = 0.50,
    val speculativeAggressiveOverrideMaxWeeklyFalseEntryRate: Double = 0.50,
    val speculativeAggressiveOverrideMinRankingScore: Double = 0.66,
    val speculativeAggressiveOverrideMinExpectedEdgePct: Double = 0.34,
    val speculativeAggressiveOverrideMinBotHealthScore: Double = 0.66,
    val speculativeAggressiveOverrideMinOpportunityScore: Double = 0.62,
    val maxWeeklyFalseEntryRate: Double = 0.50,
    val aggressiveOverrideMaxWeeklyFalseEntryRate: Double = 0.50,
    val aggressiveOverrideMinRankingScore: Double = 0.74,
    val aggressiveOverrideMinExpectedEdgePct: Double = 0.42,
    val aggressiveOverrideMinBotHealthScore: Double = 0.70,
    val aggressiveOverrideMinOpportunityScore: Double = 0.64,
)

data class LiveRolloutDecision(
    val allowed: Boolean,
    val phase: String,
    val reason: String,
)

class LiveRolloutGuard(
    private val config: LiveRolloutConfig = LiveRolloutConfig(),
) {
    fun evaluate(
        cycle: StrategyCycleResult,
        weeklySummary: WeeklyLearningSummary?,
    ): LiveRolloutDecision {
        val executionPlan = cycle.executionPlan
            ?: return blocked("shadow", "Belum ada execution plan yang cukup bersih.")
        if (!cycle.modeSnapshot.tradingAllowed || !cycle.riskDecision.allowNewEntries) {
            return blocked("shadow", "Risk gate atau mode bot masih menutup entry live.")
        }
        if (cycle.modeSnapshot.mode == BotMode.SAFE) {
            return blocked("shadow", "Mode SAFE tidak mengizinkan live execution.")
        }
        if (cycle.selectedSignal?.pairTier == PairTier.TIER_C) {
            return blocked("shadow", "Pair masih berada di tier terlarang.")
        }
        if (cycle.modeSnapshot.edgeConfidence == EdgeConfidence.LOW) {
            return blocked("shadow", "Edge confidence masih rendah.")
        }
        if (executionPlan.speculativePocket) {
            val speculativeFalseEntryGate = when (cycle.modeSnapshot.mode) {
                BotMode.ATTACK -> config.speculativeAggressiveOverrideMaxWeeklyFalseEntryRate
                else -> config.speculativeMaxWeeklyFalseEntryRate
            }
            if (weeklySummary == null || weeklySummary.tradeCount < config.speculativeMinWeeklyTradeSamples) {
                return blocked("shadow", "Sleeve spekulatif ditahan sampai sample mingguan cukup.")
            }
            if (weeklySummary.falseEntryRate > speculativeFalseEntryGate) {
                return blocked(
                    "guarded_live",
                    "Sleeve spekulatif ditahan karena false-entry mingguan masih terlalu tinggi.",
                )
            }
            if (cycle.modeSnapshot.edgeConfidence != EdgeConfidence.HIGH) {
                return blocked("guarded_live", "Sleeve spekulatif hanya boleh live saat edge confidence benar-benar tinggi.")
            }
            if (
                executionPlan.pairRankingScore < config.speculativeMinRankingScore ||
                executionPlan.expectedNetEdgePct < config.speculativeMinExpectedEdgePct
            ) {
                return blocked("guarded_live", "Sleeve spekulatif hanya boleh live pada setup yang sangat dominan.")
            }
        }

        val hasEnoughWeeklyTrades = weeklySummary?.tradeCount?.let { it >= config.minimumWeeklyTradeSamples } == true
        if (weeklySummary == null || !hasEnoughWeeklyTrades) {
            return blocked(
                "shadow",
                if (weeklySummary == null) {
                    "Belum ada review mingguan, jadi live tetap ditahan sampai sample cukup."
                } else {
                    "Sample trade mingguan belum cukup, jadi live tetap ditahan sampai sample cukup."
                },
            )
        }

        val falseEntryGate = when (cycle.modeSnapshot.mode) {
            BotMode.ATTACK -> config.aggressiveOverrideMaxWeeklyFalseEntryRate
            else -> config.maxWeeklyFalseEntryRate
        }
        if (weeklySummary.falseEntryRate > falseEntryGate) {
            return blocked("guarded_live", "False-entry mingguan masih terlalu tinggi untuk live entry.")
        }
        if (weeklySummary.tacticalExpectancy < -0.10 && weeklySummary.swingExpectancy < -0.10) {
            return blocked("guarded_live", "Expectancy mingguan masih buruk, jadi live tetap ditahan.")
        }

        return allowed("guarded_live", "Review mingguan dan gate runtime sama-sama cukup sehat.")
    }

    private fun allowed(phase: String, reason: String) = LiveRolloutDecision(
        allowed = true,
        phase = phase,
        reason = reason,
    )

    private fun blocked(phase: String, reason: String) = LiveRolloutDecision(
        allowed = false,
        phase = phase,
        reason = reason,
    )

    // Removed false-entry override helpers in V4.3 (gate removed).
}
