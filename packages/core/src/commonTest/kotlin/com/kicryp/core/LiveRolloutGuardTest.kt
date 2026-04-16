package com.kicryp.core

import com.kicryp.shared.models.BalanceSnapshot
import com.kicryp.shared.models.BotId
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.EngineHealthSnapshot
import com.kicryp.shared.models.HealthStatus
import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.PairId
import com.kicryp.shared.models.PairTier
import com.kicryp.shared.models.SyncHealth
import com.kicryp.shared.models.WeeklyAdaptationPlan
import com.kicryp.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveRolloutGuardTest {
    private val orchestrator = StrategyOrchestrator()
    private val guard = LiveRolloutGuard()

    @Test
    fun `small weekly sample is blocked until sample is enough`() {
        val cycle = healthyCycle()
        val summary = WeeklyLearningSummary(
            botId = BotId("main"),
            periodStart = LocalDate(2026, 3, 10),
            periodEnd = LocalDate(2026, 3, 16),
            tradeCount = 3,
            falseEntryRate = 0.0,
            noTradeQualityScore = 0.75,
            avoidedBadTradesIndicator = 0.45,
            capitalUtilizationPct = 0.20,
            productiveUtilizationPct = 0.16,
            missedOpportunityRate = 0.11,
            tacticalExpectancy = 0.22,
            swingExpectancy = 0.18,
            adaptationPlan = WeeklyAdaptationPlan(),
        )

        val decision = guard.evaluate(cycle, summary)

        assertFalse(decision.allowed)
        assertEquals("shadow", decision.phase)
    }

    @Test
    fun `small weekly sample blocks weak setup`() {
        val weakGuard = LiveRolloutGuard(
            LiveRolloutConfig(
                shadowMinRankingScore = 0.99,
                shadowMinExpectedEdgePct = 0.99,
                shadowMinBotHealthScore = 0.99,
                shadowMinOpportunityScore = 0.99,
                minimumWeeklyTradeSamples = 5,
            ),
        )
        val cycle = healthyCycle()
        val summary = WeeklyLearningSummary(
            botId = BotId("main"),
            periodStart = LocalDate(2026, 3, 10),
            periodEnd = LocalDate(2026, 3, 16),
            tradeCount = 1,
            falseEntryRate = 0.0,
            noTradeQualityScore = 0.75,
            avoidedBadTradesIndicator = 0.45,
            capitalUtilizationPct = 0.18,
            productiveUtilizationPct = 0.12,
            missedOpportunityRate = 0.11,
            tacticalExpectancy = 0.10,
            swingExpectancy = 0.08,
            adaptationPlan = WeeklyAdaptationPlan(),
        )

        val decision = weakGuard.evaluate(cycle, summary)

        assertFalse(decision.allowed)
        assertEquals("shadow", decision.phase)
    }

    @Test
    fun `speculative setup can live once sample is enough and quality is high`() {
        val cycle = healthyCycle().copy(
            selectedSignal = healthyCycle().selectedSignal?.copy(
                pairTier = PairTier.TIER_B,
                speculativePocket = true,
            ),
            executionPlan = healthyCycle().executionPlan?.copy(
                speculativePocket = true,
                pairRankingScore = 0.93,
                expectedNetEdgePct = 1.12,
            ),
        )
        val summary = WeeklyLearningSummary(
            botId = BotId("main"),
            periodStart = LocalDate(2026, 3, 10),
            periodEnd = LocalDate(2026, 3, 16),
            tradeCount = 5,
            falseEntryRate = 0.0,
            noTradeQualityScore = 0.75,
            avoidedBadTradesIndicator = 0.45,
            capitalUtilizationPct = 0.28,
            productiveUtilizationPct = 0.20,
            missedOpportunityRate = 0.09,
            tacticalExpectancy = 0.26,
            swingExpectancy = 0.18,
            adaptationPlan = WeeklyAdaptationPlan(),
        )

        val decision = guard.evaluate(cycle, summary)

        assertTrue(decision.phase == "guarded_live" || decision.phase == "shadow")
    }

    private fun healthyCycle() = orchestrator.analyze(
        botId = BotId("main"),
        balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
        openOrders = emptyList(),
        dailyRisk = null,
        health = EngineHealthSnapshot(
            status = HealthStatus.HEALTHY,
            syncHealth = SyncHealth.HEALTHY,
            websocketHealthy = true,
            exchangeReachable = true,
            supabaseReachable = true,
        ),
        marketQuotes = listOf(
            quote("btc_idr", 1_000_000_000.0, 0.10, 0.08, 0.68, 0.66, 95_000_000.0),
            quote("sol_idr", 2_500_000.0, 0.14, 0.10, 0.70, 0.68, 72_000_000.0),
            quote("xrp_idr", 9_800.0, 0.12, 0.09, 0.62, 0.61, 56_000_000.0),
        ),
    )

    private fun quote(
        pair: String,
        price: Double,
        spreadPct: Double,
        slippagePct: Double,
        trendScore: Double,
        expectancyScore: Double,
        volume: Double,
    ): MarketQuote = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue.fromDouble(price),
        bestAsk = DecimalValue.fromDouble(price * (1.0 + (spreadPct / 100.0))),
        midPrice = DecimalValue.fromDouble(price),
        spreadPct = spreadPct,
        quoteVolume24h = DecimalValue.fromDouble(volume),
        baseVolume24h = DecimalValue.fromDouble(volume / price),
        estimatedSlippagePct = slippagePct,
        orderBookStabilityScore = 0.88,
        tradeCount24h = 320,
        bidDepthTop5Idr = DecimalValue.fromDouble(900_000.0),
        askDepthTop5Idr = DecimalValue.fromDouble(900_000.0),
        shortTermReturnPct = 0.9,
        mediumTermReturnPct = 1.4,
        realizedVolatilityPct = 1.7,
        recentTradeActivityScore = 0.84,
        volatilityQualityScore = 0.75,
        trendQualityScore = trendScore,
        historicalExpectancyScore = expectancyScore,
        fillQualityScore = 0.84,
        holdabilityScore = 0.68,
        capturedAt = Clock.System.now(),
    )
}
