package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.MarketOpportunitySnapshot

class BotModeDecider(
    private val policy: BotModePolicy = BotModePolicy(),
) {
    fun decide(
        market: MarketOpportunitySnapshot,
        risk: RiskDecision,
        healthDecision: EntryHealthDecision,
    ): BotModeSnapshot {

        val mode = when {
            !risk.allowNewEntries -> BotMode.SAFE
            risk.riskLadderLevel >= 3 -> BotMode.LIMITED  // LEVEL_3 -> LIMITED
            risk.riskLadderLevel >= 2 -> BotMode.DEFENSIVE
            else -> BotMode.ATTACK
        }

        val aggressionScore = (
            0.94 *
                risk.deploymentMultiplier.coerceAtLeast(0.88) *
                ((market.marketOpportunityScore * 0.48) + (market.performanceMomentumScore * 0.34) + (market.botHealthScore * 0.18))
            ).coerceIn(0.72, 1.0)

        val rationale = buildList {
            addAll(healthDecision.reasons.take(2))
            addAll(risk.reasons.take(3))
            addAll(market.rationale.take(3))
            add("Mode dinamis berdasarkan risk ladder (L${risk.riskLadderLevel})")
            add("Entry tetap memakai gate health minimum agar tidak menembak saat feed rusak total.")
        }.distinct()

        return BotModeSnapshot(
            mode = mode,
            edgeConfidence = market.edgeConfidence,
            aggressionScore = aggressionScore,
            riskLadderLevel = risk.riskLadderLevel,
            profitProtectionStatus = risk.profitProtectionStatus,
            tacticalBiasScore = market.tacticalBiasScore,
            swingBiasScore = market.swingBiasScore,
            tradingAllowed = healthDecision.tradingAllowed && risk.allowNewEntries,
            rationale = rationale,
        )
    }
}
