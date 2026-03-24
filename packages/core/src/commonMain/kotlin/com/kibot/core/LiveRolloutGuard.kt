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
    val minimumWeeklyTradeSamples: Int = 1,
    val speculativeMinWeeklyTradeSamples: Int = 1,
    val speculativeMinRankingScore: Double = 0.56,
    val speculativeMinExpectedEdgePct: Double = 0.28,
    val speculativeMaxWeeklyFalseEntryRate: Double = 0.50,
    val speculativeAggressiveOverrideMaxWeeklyFalseEntryRate: Double = 0.70,
    val speculativeAggressiveOverrideMinRankingScore: Double = 0.62,
    val speculativeAggressiveOverrideMinExpectedEdgePct: Double = 0.30,
    val speculativeAggressiveOverrideMinBotHealthScore: Double = 0.60,
    val speculativeAggressiveOverrideMinOpportunityScore: Double = 0.58,
    val maxWeeklyFalseEntryRate: Double = 0.50,
    val aggressiveOverrideMaxWeeklyFalseEntryRate: Double = 0.70,
    val aggressiveOverrideMinRankingScore: Double = 0.68,
    val aggressiveOverrideMinExpectedEdgePct: Double = 0.36,
    val aggressiveOverrideMinBotHealthScore: Double = 0.62,
    val aggressiveOverrideMinOpportunityScore: Double = 0.58,
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
        if (cycle.riskDecision.riskLadderLevel !in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING)) {
            return blocked("shadow", "Risk ladder belum cukup sehat untuk rollout live.")
        }
        if (executionPlan.speculativePocket) {
            if (weeklySummary == null || weeklySummary.tradeCount < config.speculativeMinWeeklyTradeSamples) {
                return if (
                    executionPlan.pairRankingScore >= config.speculativeMinRankingScore + 0.04 &&
                    executionPlan.expectedNetEdgePct >= config.speculativeMinExpectedEdgePct + 0.08 &&
                    cycle.marketSnapshot.botHealthScore >= config.shadowMinBotHealthScore &&
                    cycle.marketSnapshot.marketOpportunityScore >= config.shadowMinOpportunityScore &&
                    cycle.modeSnapshot.mode in setOf(BotMode.GROWTH, BotMode.ATTACK)
                ) {
                    allowed("guarded_live", "Sleeve spekulatif boleh seed live saat momentum kecil terlihat sangat dominan.")
                } else {
                    blocked("shadow", "Sleeve spekulatif belum cukup matang untuk live penuh.")
                }
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
            if (weeklySummary.falseEntryRate > config.speculativeMaxWeeklyFalseEntryRate) {
                return if (canAggressivelyOverrideSpeculativeGate(cycle, weeklySummary)) {
                    allowed(
                        "guarded_live",
                        "False entry mingguan untuk sleeve spekulatif sedang tinggi, tapi setup sekarang cukup dominan jadi live tetap dibuka.",
                    )
                } else {
                    blocked("guarded_live", "False entry mingguan masih terlalu tinggi untuk jalur spekulatif.")
                }
            }
        }

        val hasEnoughWeeklyTrades = weeklySummary?.tradeCount?.let { it >= config.minimumWeeklyTradeSamples } == true
        if (weeklySummary == null || !hasEnoughWeeklyTrades) {
            return if (
                executionPlan.pairRankingScore >= config.shadowMinRankingScore &&
                executionPlan.expectedNetEdgePct >= config.shadowMinExpectedEdgePct &&
                cycle.marketSnapshot.botHealthScore >= config.shadowMinBotHealthScore &&
                cycle.marketSnapshot.marketOpportunityScore >= config.shadowMinOpportunityScore
            ) {
                allowed(
                    "shadow_seed",
                    if (weeklySummary == null) {
                        "Belum ada review mingguan, tapi setup sangat kuat jadi layak untuk rollout bertahap."
                    } else {
                        "Sample trade mingguan masih kecil, jadi live hanya boleh seed entry pada setup yang sangat kuat."
                    },
                )
            } else {
                blocked(
                    "shadow",
                    if (weeklySummary == null) {
                        "Belum ada review mingguan dan setup belum cukup kuat untuk rollout seed."
                    } else {
                        "Sample trade mingguan belum cukup dan setup juga belum cukup kuat untuk rollout seed."
                    },
                )
            }
        }

        if (weeklySummary.falseEntryRate > config.maxWeeklyFalseEntryRate) {
            return if (canAggressivelyOverrideFalseEntryGate(cycle, weeklySummary)) {
                allowed(
                    "guarded_live",
                    "False entry mingguan sedang tinggi, tapi setup sekarang cukup dominan jadi live tetap dibuka secara agresif.",
                )
            } else {
                blocked("guarded_live", "False entry mingguan masih terlalu tinggi untuk live execution.")
            }
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

    private fun canAggressivelyOverrideFalseEntryGate(
        cycle: StrategyCycleResult,
        weeklySummary: WeeklyLearningSummary,
    ): Boolean {
        val executionPlan = cycle.executionPlan ?: return false
        if (weeklySummary.falseEntryRate > config.aggressiveOverrideMaxWeeklyFalseEntryRate) return false
        if (cycle.modeSnapshot.mode !in setOf(BotMode.GROWTH, BotMode.ATTACK)) return false
        if (cycle.modeSnapshot.edgeConfidence == EdgeConfidence.LOW) return false
        return executionPlan.pairRankingScore >= config.aggressiveOverrideMinRankingScore &&
            executionPlan.expectedNetEdgePct >= config.aggressiveOverrideMinExpectedEdgePct &&
            cycle.marketSnapshot.botHealthScore >= config.aggressiveOverrideMinBotHealthScore &&
            cycle.marketSnapshot.marketOpportunityScore >= config.aggressiveOverrideMinOpportunityScore
    }

    private fun canAggressivelyOverrideSpeculativeGate(
        cycle: StrategyCycleResult,
        weeklySummary: WeeklyLearningSummary,
    ): Boolean {
        val executionPlan = cycle.executionPlan ?: return false
        if (!executionPlan.speculativePocket) return false
        if (weeklySummary.falseEntryRate > config.speculativeAggressiveOverrideMaxWeeklyFalseEntryRate) return false
        if (cycle.modeSnapshot.mode !in setOf(BotMode.GROWTH, BotMode.ATTACK)) return false
        if (cycle.modeSnapshot.edgeConfidence != EdgeConfidence.HIGH) return false
        return executionPlan.pairRankingScore >= config.speculativeAggressiveOverrideMinRankingScore &&
            executionPlan.expectedNetEdgePct >= config.speculativeAggressiveOverrideMinExpectedEdgePct &&
            cycle.marketSnapshot.botHealthScore >= config.speculativeAggressiveOverrideMinBotHealthScore &&
            cycle.marketSnapshot.marketOpportunityScore >= config.speculativeAggressiveOverrideMinOpportunityScore
    }
}
