package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PairTier
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.TradingHorizon
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapitalDeploymentEngineTest {
    private val engine = CapitalDeploymentEngine()
    private val mode = BotModeSnapshot(
        mode = BotMode.GROWTH,
        edgeConfidence = EdgeConfidence.HIGH,
        aggressionScore = 0.72,
        riskLadderLevel = RiskLadderLevel.NORMAL,
        profitProtectionStatus = ProfitProtectionStatus.INACTIVE,
        tacticalBiasScore = 0.65,
        swingBiasScore = 0.35,
        tradingAllowed = true,
    )
    private val risk = RiskDecision(
        allowNewEntries = true,
        hardStopTriggered = false,
        maxAllowedAdditionalPositions = 2,
        suggestedPerPositionBudgetIdr = 40_000.0,
        riskLadderLevel = RiskLadderLevel.NORMAL,
        suggestedModeFloor = BotMode.GROWTH,
        profitProtectionStatus = ProfitProtectionStatus.INACTIVE,
        sizeMultiplier = 1.0,
        deploymentMultiplier = 1.0,
        reasons = emptyList(),
    )

    @Test
    fun `keeps second slot closed when follow-up candidate is weak`() {
        val plan = engine.plan(
            portfolio = portfolio(),
            rankedPairs = listOf(
                pairScore("btc_idr", ranking = 0.84, opportunity = 0.79),
                pairScore("sol_idr", ranking = 0.71, opportunity = 0.63),
            ),
            risk = risk,
            mode = mode,
        )

        assertEquals(1, plan.maxActivePositions)
        assertTrue(plan.allowNewEntries)
        assertTrue(plan.suggestedPerPositionBudgetIdr > risk.suggestedPerPositionBudgetIdr)
    }

    @Test
    fun `opens second slot only when two candidates are both strong`() {
        val plan = engine.plan(
            portfolio = portfolio(),
            rankedPairs = listOf(
                pairScore("btc_idr", ranking = 0.85, opportunity = 0.80),
                pairScore("sol_idr", ranking = 0.78, opportunity = 0.72),
            ),
            risk = risk,
            mode = mode,
        )

        assertEquals(2, plan.maxActivePositions)
        assertTrue(plan.allowNewEntries)
        assertTrue(plan.suggestedPerPositionBudgetIdr < risk.suggestedPerPositionBudgetIdr)
    }

    private fun portfolio() = PortfolioSnapshot(
        botId = BotId("main"),
        balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
        openOrders = emptyList(),
        positions = emptyList(),
        totalEquityIdr = DecimalValue("100000"),
        lastSyncedAt = Instant.parse("2026-03-19T00:00:00Z"),
    )

    private fun pairScore(pair: String, ranking: Double, opportunity: Double) = PairScore(
        pairId = PairId(pair),
        liquidityScore = 0.82,
        spreadScore = 0.88,
        slippageScore = 0.87,
        stabilityScore = 0.84,
        volumeConsistencyScore = 0.82,
        volatilityQualityScore = 0.76,
        trendQualityScore = 0.78,
        historicalExpectancyScore = 0.75,
        recentHealthScore = 0.81,
        fillQualityScore = 0.86,
        holdabilityScore = 0.70,
        feeAdjustedEdgeScore = opportunity,
        marketOpportunityScore = opportunity,
        rankingScore = ranking,
        pairTier = PairTier.TIER_A,
        preferredHorizon = TradingHorizon.TACTICAL,
        allowed = true,
        rejectionReasons = emptyList(),
    )
}
