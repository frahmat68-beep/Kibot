package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.PairTier
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.WeeklyLearningSummary

data class LiveRolloutConfig(
    val shadowMinRankingScore: Double = 0.82,
    val shadowMinExpectedEdgePct: Double = 0.66,
    val shadowMinBotHealthScore: Double = 0.68,
    val shadowMinOpportunityScore: Double = 0.70,
    val minimumWeeklyTradeSamples: Int = 6,
    val maxWeeklyFalseEntryRate: Double = 0.34,
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
            return blocked("guarded_live", "False entry mingguan masih terlalu tinggi untuk live execution.")
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
}
