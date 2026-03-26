package com.kibot.core

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.StrategySignalType
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.WeeklyAdaptationPlan
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyOrchestratorTest {
    private val orchestrator = StrategyOrchestrator()

    @Test
    fun aiSupportCanBoostAllowedPairButCannotReviveForbiddenPair() {
        val now = Clock.System.now()
        val balances = listOf(
            BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)),
        )
        val health = EngineHealthSnapshot(
            status = HealthStatus.HEALTHY,
            syncHealth = SyncHealth.HEALTHY,
            websocketHealthy = true,
            exchangeReachable = true,
            supabaseReachable = true,
        )
        val quotes = listOf(
            quote(
                pair = "alpha_idr",
                price = 100.0,
                spreadPct = 0.12,
                slippagePct = 0.10,
                trendScore = 0.60,
                expectancyScore = 0.60,
                volume = 24_000_000.0,
                now = now,
            ),
            quote(
                pair = "beta_idr",
                price = 120.0,
                spreadPct = 0.13,
                slippagePct = 0.10,
                trendScore = 0.58,
                expectancyScore = 0.58,
                volume = 22_000_000.0,
                now = now,
            ),
            quote(
                pair = "gamma_idr",
                price = 5.0,
                spreadPct = 4.5,
                slippagePct = 2.2,
                trendScore = 0.80,
                expectancyScore = 0.80,
                volume = 100_000.0,
                now = now,
            ),
        )

        val baseline = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
        )
        val boosted = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
            pairSupportHints = listOf(
                AiPairSupportHint(
                    pairId = PairId("beta_idr"),
                    supportBias = 0.04,
                    cautionBias = 0.0,
                    cheapNominalWatch = false,
                    rationale = "Narrative support",
                    generatedAt = now,
                ),
                AiPairSupportHint(
                    pairId = PairId("gamma_idr"),
                    supportBias = 0.05,
                    cautionBias = 0.0,
                    cheapNominalWatch = true,
                    rationale = "Cheap nominal only",
                    generatedAt = now,
                ),
            ),
        )

        val baselineBeta = baseline.rankedPairs.first { it.pairId.value == "beta_idr" }
        val boostedBeta = boosted.rankedPairs.first { it.pairId.value == "beta_idr" }
        val boostedGamma = boosted.rankedPairs.first { it.pairId.value == "gamma_idr" }

        assertTrue(boostedBeta.rankingScore > baselineBeta.rankingScore)
        assertFalse(boostedGamma.allowed)
        assertEquals("gamma_idr", boostedGamma.pairId.value)
    }

    @Test
    fun weeklyWhitelistCanBiasSelectionWithoutBreakingSafety() {
        val now = Clock.System.now()
        val balances = listOf(
            BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)),
        )
        val health = healthyEngine()
        val quotes = listOf(
            quote(
                pair = "alpha_idr",
                price = 101.0,
                spreadPct = 0.12,
                slippagePct = 0.10,
                trendScore = 0.61,
                expectancyScore = 0.62,
                volume = 25_000_000.0,
                holdabilityScore = 0.60,
                shortTermReturnPct = 0.42,
                mediumTermReturnPct = 1.10,
                now = now,
            ),
            quote(
                pair = "beta_idr",
                price = 99.0,
                spreadPct = 0.11,
                slippagePct = 0.09,
                trendScore = 0.60,
                expectancyScore = 0.63,
                volume = 24_000_000.0,
                holdabilityScore = 0.61,
                shortTermReturnPct = 0.40,
                mediumTermReturnPct = 1.08,
                now = now,
            ),
        )

        val baseline = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
        )
        val learningBiased = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
            weeklySummary = weeklySummary(
                now = now,
                whitelistPairs = listOf(PairId("beta_idr")),
            ),
        )

        val baselineBeta = baseline.rankedPairs.first { it.pairId == PairId("beta_idr") }
        val learningBeta = learningBiased.rankedPairs.first { it.pairId == PairId("beta_idr") }

        assertTrue(learningBeta.rankingScore > baselineBeta.rankingScore)
        assertEquals("beta_idr", learningBiased.selectedSignal?.pairId?.value)
    }

    @Test
    fun weeklySetupBiasCanPushBreakoutCandidateAbovePullback() {
        val now = Clock.System.now()
        val balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)))
        val health = healthyEngine()
        val quotes = listOf(
            quote(
                pair = "pull_idr",
                price = 101.0,
                spreadPct = 0.11,
                slippagePct = 0.09,
                trendScore = 0.61,
                expectancyScore = 0.61,
                volume = 26_000_000.0,
                holdabilityScore = 0.58,
                shortTermReturnPct = 0.35,
                mediumTermReturnPct = 0.92,
                now = now,
            ),
            quote(
                pair = "break_idr",
                price = 99.0,
                spreadPct = 0.12,
                slippagePct = 0.10,
                trendScore = 0.70,
                expectancyScore = 0.64,
                volume = 28_000_000.0,
                holdabilityScore = 0.62,
                shortTermReturnPct = 0.88,
                mediumTermReturnPct = 1.20,
                now = now,
            ),
        )

        val learningBiased = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
            weeklySummary = WeeklyLearningSummary(
                botId = BotId("main"),
                periodStart = LocalDate(2026, 3, 10),
                periodEnd = LocalDate(2026, 3, 17),
                tradeCount = 18,
                falseEntryRate = 0.12,
                noTradeQualityScore = 0.58,
                avoidedBadTradesIndicator = 0.44,
                capitalUtilizationPct = 0.32,
                productiveUtilizationPct = 0.20,
                missedOpportunityRate = 0.22,
                tacticalExpectancy = 0.18,
                swingExpectancy = 0.12,
                adaptationPlan = WeeklyAdaptationPlan(
                    setupBias = mapOf(SetupType.LIGHT_BREAKOUT_CONTINUATION.name to 0.12),
                ),
            ),
        )

        assertEquals("break_idr", learningBiased.selectedSignal?.pairId?.value)
    }

    @Test
    fun healthyUptrendStillFindsAHighQualitySignalWhenScoresAreClose() {
        val now = Clock.System.now()
        val balances = listOf(
            BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)),
        )
        val health = healthyEngine()
        val quotes = listOf(
            quote(
                pair = "alpha_idr",
                price = 120.0,
                spreadPct = 0.12,
                slippagePct = 0.10,
                trendScore = 0.62,
                expectancyScore = 0.64,
                volume = 26_000_000.0,
                holdabilityScore = 0.58,
                shortTermReturnPct = 0.52,
                mediumTermReturnPct = 1.25,
                now = now,
            ),
            quote(
                pair = "beta_idr",
                price = 140.0,
                spreadPct = 0.14,
                slippagePct = 0.11,
                trendScore = 0.68,
                expectancyScore = 0.68,
                volume = 25_000_000.0,
                holdabilityScore = 0.78,
                shortTermReturnPct = 0.90,
                mediumTermReturnPct = 2.10,
                now = now,
            ),
            quote(
                pair = "gamma_idr",
                price = 160.0,
                spreadPct = 0.16,
                slippagePct = 0.12,
                trendScore = 0.66,
                expectancyScore = 0.66,
                volume = 23_000_000.0,
                holdabilityScore = 0.74,
                shortTermReturnPct = 0.82,
                mediumTermReturnPct = 1.95,
                now = now,
            ),
        )

        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
        )

        val signal = assertNotNull(analysis.selectedSignal)
        assertTrue(signal.pairId.value in setOf("alpha_idr", "beta_idr", "gamma_idr"))
        assertTrue(signal.signalType != StrategySignalType.NO_TRADE)
    }

    @Test
    fun attackBreakoutOnIdrPairCanUseMarketBuyWhenExecutionQualityIsExcellent() {
        val now = Clock.System.now()
        val marketAwareOrchestrator = StrategyOrchestrator(
            executionConfig = StrategyExecutionConfig(
                marketEntryMinRankingScore = 0.0,
                marketEntryMinExpectedNetProfitPct = 0.0,
                marketEntryMaxSpreadPct = 1.0,
                marketEntryMaxSlippagePct = 1.0,
                marketEntryMinTradeActivityScore = 0.0,
                marketEntryMinTrendScore = 0.0,
                minExpectedNetProfitIdr = 0.0,
                minExpectedNetProfitIdrSpeculative = 0.0,
                minProfitToCostMultiplier = 0.05,
                minProfitAfterFeesBufferIdr = 0.0,
            ),
        )
        val analysis = marketAwareOrchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "rocket_idr",
                    price = 125.0,
                    spreadPct = 0.08,
                    slippagePct = 0.07,
                    trendScore = 0.90,
                    expectancyScore = 0.84,
                    volume = 96_000_000.0,
                    holdabilityScore = 0.54,
                    shortTermReturnPct = 0.95,
                    mediumTermReturnPct = 0.95,
                    now = now,
                ),
            ),
        )

        val signal = assertNotNull(analysis.selectedSignal)
        assertEquals("rocket_idr", signal.pairId.value)
        assertEquals(SetupType.LIGHT_BREAKOUT_CONTINUATION, signal.setupType)
        val plan = assertNotNull(analysis.executionPlan)
        assertEquals(OrderType.MARKET, plan.orderType)
        assertFalse(plan.postOnlyPreferred)
    }

    @Test
    fun lowAbsoluteProfitSetupDoesNotProduceExecutionPlan() {
        val now = Clock.System.now()
        val profitAwareOrchestrator = StrategyOrchestrator(
            executionConfig = StrategyExecutionConfig(
                minExpectedNetProfitIdr = 500.0,
                minExpectedNetProfitIdrSpeculative = 650.0,
            ),
        )
        val analysis = profitAwareOrchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(40_000.0))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "micro_idr",
                    price = 150.0,
                    spreadPct = 0.20,
                    slippagePct = 0.18,
                    trendScore = 0.70,
                    expectancyScore = 0.68,
                    volume = 48_000_000.0,
                    holdabilityScore = 0.56,
                    shortTermReturnPct = 0.92,
                    mediumTermReturnPct = 1.40,
                    now = now,
                ),
            ),
        )

        assertNotNull(analysis.selectedSignal)
        assertNull(analysis.executionPlan)
    }

    @Test
    fun explosiveBreakoutCanUseMarketBuyEarlierWhenNetProfitCoversCostsWell() {
        val now = Clock.System.now()
        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "stik_idr",
                    price = 6_611.0,
                    spreadPct = 0.18,
                    slippagePct = 0.16,
                    trendScore = 0.86,
                    expectancyScore = 0.82,
                    volume = 1_240_000_000.0,
                    holdabilityScore = 0.60,
                    shortTermReturnPct = 8.40,
                    mediumTermReturnPct = 3.40,
                    now = now,
                ),
            ),
        )

        val plan = assertNotNull(analysis.executionPlan)
        assertEquals(OrderType.MARKET, plan.orderType)
    }

    @Test
    fun pendingBuyOnDifferentPairDoesNotBlockNewEntry() {
        val now = Clock.System.now()
        val quotes = listOf(
            quote(
                pair = "alpha_idr",
                price = 101.0,
                spreadPct = 0.12,
                slippagePct = 0.10,
                trendScore = 0.61,
                expectancyScore = 0.62,
                volume = 25_000_000.0,
                holdabilityScore = 0.60,
                shortTermReturnPct = 0.42,
                mediumTermReturnPct = 1.10,
                now = now,
            ),
            quote(
                pair = "beta_idr",
                price = 99.0,
                spreadPct = 0.11,
                slippagePct = 0.09,
                trendScore = 0.60,
                expectancyScore = 0.63,
                volume = 24_000_000.0,
                holdabilityScore = 0.61,
                shortTermReturnPct = 0.40,
                mediumTermReturnPct = 1.08,
                now = now,
            ),
        )

        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
            openOrders = listOf(
                OrderSnapshot(
                    orderId = OrderId("buy-open-1"),
                    clientOrderId = com.kibot.shared.models.ClientOrderId("buy-open-1"),
                    pairId = PairId("alpha_idr"),
                    side = OrderSide.BUY,
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.OPEN,
                    price = DecimalValue.fromDouble(101.0),
                    originalQuantity = DecimalValue.fromDouble(100.0),
                    executedQuantity = DecimalValue.Zero,
                    remainingQuantity = DecimalValue.fromDouble(100.0),
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = quotes,
        )

        assertNotNull(analysis.selectedSignal)
        assertEquals("beta_idr", analysis.selectedSignal?.pairId?.value)
    }

    private fun healthyEngine() = EngineHealthSnapshot(
        status = HealthStatus.HEALTHY,
        syncHealth = SyncHealth.HEALTHY,
        websocketHealthy = true,
        exchangeReachable = true,
        supabaseReachable = true,
    )

    private fun weeklySummary(
        now: kotlinx.datetime.Instant,
        whitelistPairs: List<PairId> = emptyList(),
    ) = WeeklyLearningSummary(
        botId = BotId("main"),
        periodStart = LocalDate(2026, 3, 10),
        periodEnd = LocalDate(2026, 3, 17),
        tradeCount = 16,
        falseEntryRate = 0.10,
        noTradeQualityScore = 0.60,
        avoidedBadTradesIndicator = 0.40,
        capitalUtilizationPct = 0.35,
        productiveUtilizationPct = 0.22,
        missedOpportunityRate = 0.18,
        tacticalExpectancy = 0.16,
        swingExpectancy = 0.18,
        adaptationPlan = WeeklyAdaptationPlan(
            whitelistPairs = whitelistPairs,
            notes = listOf("Weekly bias ringan."),
        ),
        notes = listOf("Generated at ${now.toString()}"),
    )

    private fun quote(
        pair: String,
        price: Double,
        spreadPct: Double,
        slippagePct: Double,
        trendScore: Double,
        expectancyScore: Double,
        volume: Double,
        holdabilityScore: Double = 0.65,
        shortTermReturnPct: Double = 0.8,
        mediumTermReturnPct: Double = 1.2,
        now: kotlinx.datetime.Instant,
    ): MarketQuote = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue.fromDouble(price),
        bestAsk = DecimalValue.fromDouble(price * (1.0 + (spreadPct / 100.0))),
        midPrice = DecimalValue.fromDouble(price),
        spreadPct = spreadPct,
        quoteVolume24h = DecimalValue.fromDouble(volume),
        baseVolume24h = DecimalValue.fromDouble(volume / price),
        estimatedSlippagePct = slippagePct,
        orderBookStabilityScore = 0.85,
        tradeCount24h = 250,
        bidDepthTop5Idr = DecimalValue.fromDouble(500_000.0),
        askDepthTop5Idr = DecimalValue.fromDouble(500_000.0),
        shortTermReturnPct = shortTermReturnPct,
        mediumTermReturnPct = mediumTermReturnPct,
        realizedVolatilityPct = 1.4,
        recentTradeActivityScore = 0.8,
        volatilityQualityScore = 0.72,
        trendQualityScore = trendScore,
        historicalExpectancyScore = expectancyScore,
        fillQualityScore = 0.8,
        holdabilityScore = holdabilityScore,
        capturedAt = now,
    )
}
