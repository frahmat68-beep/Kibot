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
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.TradingHorizon
import kotlinx.datetime.Clock
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
        dailyProfitLockActive = false,
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

        assertEquals(2, plan.maxActivePositions)
        assertTrue(plan.allowNewEntries)
        assertTrue(plan.suggestedPerPositionBudgetIdr > 0.0)
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
        assertTrue(plan.suggestedPerPositionBudgetIdr <= (risk.suggestedPerPositionBudgetIdr * 1.5))
    }

    @Test
    fun `relaxes reserve slightly when one tier a candidate is clearly dominant`() {
        val plan = engine.plan(
            portfolio = portfolio(),
            rankedPairs = listOf(
                pairScore("btc_idr", ranking = 0.88, opportunity = 0.79),
                pairScore("sol_idr", ranking = 0.76, opportunity = 0.68),
            ),
            risk = risk,
            mode = mode,
        )

        assertEquals(2, plan.maxActivePositions)
        assertTrue(plan.targetCashReservePct < 0.13)
        assertTrue(plan.suggestedPerPositionBudgetIdr > 0.0)
    }

    @Test
    fun `speculative sleeve caps exposure to configured equity ceiling`() {
        val plan = engine.plan(
            portfolio = portfolio(),
            rankedPairs = listOf(
                pairScore("nxa_idr", ranking = 0.68, opportunity = 0.64, speculativePocket = true),
                pairScore("btc_idr", ranking = 0.61, opportunity = 0.56),
            ),
            risk = risk,
            mode = mode,
        )

        assertEquals(1, plan.maxActivePositions)
        assertTrue(plan.suggestedPerPositionBudgetIdr <= 35_000.0)
        assertTrue(plan.candidates.first().speculativePocket)
    }

    @Test
    fun `small equity does not spread into too many micro positions`() {
        val smallPortfolio = PortfolioSnapshot(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot("idr", DecimalValue("93614"))),
            openOrders = emptyList(),
            positions = emptyList(),
            totalEquityIdr = DecimalValue("93614"),
            lastSyncedAt = Instant.parse("2026-03-22T00:00:00Z"),
        )

        val plan = engine.plan(
            portfolio = smallPortfolio,
            rankedPairs = listOf(
                pairScore("doge_idr", ranking = 0.81, opportunity = 1.22),
                pairScore("trx_idr", ranking = 0.79, opportunity = 1.18),
                pairScore("eth_idr", ranking = 0.77, opportunity = 1.16),
                pairScore("xrp_idr", ranking = 0.76, opportunity = 1.15),
                pairScore("usdt_idr", ranking = 0.74, opportunity = 1.12),
            ),
            risk = risk,
            mode = mode,
        )

        assertTrue(plan.maxActivePositions <= 4)
        assertTrue(plan.suggestedPerPositionBudgetIdr >= 22_500.0)
    }

    @Test
    fun `stagnant full portfolio can still open rotation path for stronger candidate`() {
        val now = Clock.System.now()
        val portfolio = PortfolioSnapshot(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
            openOrders = emptyList(),
            positions = listOf(
                PositionSnapshot(
                    positionId = com.kibot.shared.models.PositionId("ont-1"),
                    pairId = PairId("ont_idr"),
                    baseAsset = "ont",
                    quoteAsset = "idr",
                    state = PositionState.OPEN,
                    quantity = DecimalValue("16.30"),
                    averageEntryPrice = DecimalValue("970"),
                    realizedPnlIdr = DecimalValue.Zero,
                    unrealizedPnlIdr = DecimalValue("-480"),
                    horizon = TradingHorizon.TACTICAL,
                    openedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (55 * 60 * 1000)),
                    updatedAt = now,
                ),
                PositionSnapshot(
                    positionId = com.kibot.shared.models.PositionId("xrp-1"),
                    pairId = PairId("xrp_idr"),
                    baseAsset = "xrp",
                    quoteAsset = "idr",
                    state = PositionState.OPEN,
                    quantity = DecimalValue("0.50"),
                    averageEntryPrice = DecimalValue("24000"),
                    realizedPnlIdr = DecimalValue.Zero,
                    unrealizedPnlIdr = DecimalValue("-320"),
                    horizon = TradingHorizon.TACTICAL,
                    openedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (48 * 60 * 1000)),
                    updatedAt = now,
                ),
            ),
            totalEquityIdr = DecimalValue("63466"),
            lastSyncedAt = now,
        )

        val plan = engine.plan(
            portfolio = portfolio,
            rankedPairs = listOf(
                pairScore("croak_idr", ranking = 0.86, opportunity = 2.40, speculativePocket = true),
                pairScore("trx_idr", ranking = 0.78, opportunity = 1.35),
                pairScore("ont_idr", ranking = 0.50, opportunity = 0.32),
                pairScore("xrp_idr", ranking = 0.48, opportunity = 0.28),
            ),
            risk = risk.copy(maxAllowedAdditionalPositions = 0),
            mode = mode.copy(mode = BotMode.ATTACK),
        )

        assertTrue(plan.allowRotation)
        assertTrue(plan.candidates.first().pairId == PairId("croak_idr"))
    }

    private fun portfolio() = PortfolioSnapshot(
        botId = BotId("main"),
        balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
        openOrders = emptyList(),
        positions = emptyList(),
        totalEquityIdr = DecimalValue("100000"),
        lastSyncedAt = Instant.parse("2026-03-19T00:00:00Z"),
    )

    private fun pairScore(pair: String, ranking: Double, opportunity: Double, speculativePocket: Boolean = false) = PairScore(
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
        speculativePocket = speculativePocket,
        allowed = true,
        rejectionReasons = emptyList(),
    )
}
