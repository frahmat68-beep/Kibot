package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.MarketOpportunitySnapshot
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotModeDeciderTest {
    @Test
    fun `selects attack when all scores are strong`() {
        val decision = BotModeDecider().decide(
            market = MarketOpportunitySnapshot(
                regime = MarketRegime.HEALTHY_UPTREND,
                marketOpportunityScore = 0.86,
                botHealthScore = 0.88,
                performanceMomentumScore = 0.81,
                edgeConfidence = EdgeConfidence.HIGH,
                tacticalBiasScore = 0.35,
                swingBiasScore = 0.80,
                opportunityAvailabilityScore = 0.82,
                microstructureHealthScore = 0.84,
            ),
            risk = RiskDecision(
                allowNewEntries = true,
                hardStopTriggered = false,
                maxAllowedAdditionalPositions = 1,
                suggestedPerPositionBudgetIdr = 50_000.0,
                riskLadderLevel = RiskLadderLevel.NORMAL,
                suggestedModeFloor = BotMode.GROWTH,
                profitProtectionStatus = ProfitProtectionStatus.INACTIVE,
                sizeMultiplier = 1.0,
                deploymentMultiplier = 1.0,
                reasons = emptyList(),
            ),
            healthDecision = EntryHealthDecision(
                tradingAllowed = true,
                shouldSuggestTakeover = false,
                reasons = emptyList(),
            ),
        )

        assertEquals(BotMode.ATTACK, decision.mode)
        assertTrue(decision.aggressionScore > 0.6)
    }

    @Test
    fun `forces safe when risk blocks entries`() {
        val decision = BotModeDecider().decide(
            market = MarketOpportunitySnapshot(
                regime = MarketRegime.HEALTHY_UPTREND,
                marketOpportunityScore = 0.90,
                botHealthScore = 0.90,
                performanceMomentumScore = 0.90,
                edgeConfidence = EdgeConfidence.HIGH,
                tacticalBiasScore = 0.20,
                swingBiasScore = 0.90,
                opportunityAvailabilityScore = 0.90,
                microstructureHealthScore = 0.90,
            ),
            risk = RiskDecision(
                allowNewEntries = false,
                hardStopTriggered = true,
                maxAllowedAdditionalPositions = 0,
                suggestedPerPositionBudgetIdr = 0.0,
                riskLadderLevel = RiskLadderLevel.HARD_STOP,
                suggestedModeFloor = BotMode.SAFE,
                profitProtectionStatus = ProfitProtectionStatus.COOLING_AGGRESSION,
                sizeMultiplier = 0.0,
                deploymentMultiplier = 0.0,
                reasons = listOf("Emergency stop aktif."),
            ),
            healthDecision = EntryHealthDecision(
                tradingAllowed = false,
                shouldSuggestTakeover = false,
                reasons = listOf("Control plane putus."),
            ),
        )

        assertEquals(BotMode.SAFE, decision.mode)
        assertEquals(0.0, decision.aggressionScore)
    }
}
