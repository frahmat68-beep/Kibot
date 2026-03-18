package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.MarketOpportunitySnapshot
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel

class BotModeDecider(
    private val policy: BotModePolicy = BotModePolicy(),
) {
    fun decide(
        market: MarketOpportunitySnapshot,
        risk: RiskDecision,
        healthDecision: EntryHealthDecision,
    ): BotModeSnapshot {
        val mode = when {
            !healthDecision.tradingAllowed || !risk.allowNewEntries -> BotMode.SAFE
            risk.suggestedModeFloor == BotMode.SAFE -> BotMode.SAFE
            risk.riskLadderLevel in setOf(RiskLadderLevel.DEFENSIVE_MODE, RiskLadderLevel.RESTRICTED_NEW_ENTRIES) ->
                BotMode.DEFENSIVE
            risk.profitProtectionStatus == ProfitProtectionStatus.COOLING_AGGRESSION ->
                BotMode.DEFENSIVE
            market.regime == MarketRegime.BREAKDOWN_PANIC ->
                BotMode.SAFE
            market.edgeConfidence == EdgeConfidence.LOW ->
                BotMode.DEFENSIVE
            market.marketOpportunityScore >= policy.attackOpportunityScoreMin &&
                market.botHealthScore >= policy.attackBotHealthScoreMin &&
                market.performanceMomentumScore >= policy.attackPerformanceScoreMin &&
                market.edgeConfidence == EdgeConfidence.HIGH &&
                risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
                market.regime == MarketRegime.HEALTHY_UPTREND ->
                BotMode.ATTACK
            market.marketOpportunityScore >= policy.growthOpportunityScoreMin &&
                market.botHealthScore >= policy.growthBotHealthScoreMin ->
                BotMode.GROWTH
            else -> BotMode.DEFENSIVE
        }

        val baseAggression = when (mode) {
            BotMode.SAFE -> 0.0
            BotMode.DEFENSIVE -> 0.32
            BotMode.GROWTH -> 0.62
            BotMode.ATTACK -> 0.84
        }
        val aggressionScore = (
            baseAggression *
                risk.deploymentMultiplier *
                ((market.marketOpportunityScore * 0.5) + (market.performanceMomentumScore * 0.3) + (market.botHealthScore * 0.2))
            ).coerceIn(0.0, 1.0)

        val rationale = buildList {
            addAll(healthDecision.reasons.take(2))
            addAll(risk.reasons.take(3))
            addAll(market.rationale.take(3))
            when (mode) {
                BotMode.SAFE -> add("Bot memilih aman dulu sampai state dan peluang kembali bersih.")
                BotMode.DEFENSIVE -> add("Bot tetap hidup, tapi masuk mode selektif.")
                BotMode.GROWTH -> add("Market dan health cukup sehat untuk mode produktif normal.")
                BotMode.ATTACK -> add("Semua score utama kuat, bot boleh lebih progresif tetapi tetap bounded.")
            }
        }.distinct()

        return BotModeSnapshot(
            mode = mode,
            edgeConfidence = market.edgeConfidence,
            aggressionScore = aggressionScore,
            riskLadderLevel = risk.riskLadderLevel,
            profitProtectionStatus = risk.profitProtectionStatus,
            tacticalBiasScore = market.tacticalBiasScore,
            swingBiasScore = market.swingBiasScore,
            tradingAllowed = mode != BotMode.SAFE && healthDecision.tradingAllowed && risk.allowNewEntries,
            rationale = rationale,
        )
    }
}
