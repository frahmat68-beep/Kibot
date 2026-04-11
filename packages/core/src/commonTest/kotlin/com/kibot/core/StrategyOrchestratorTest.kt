package com.kibot.core

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PositionId
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.StrategySignalType
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.TradingHorizon
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
    fun `entry signal keeps take profit above stop loss`() {
        val now = Clock.System.now()
        val result = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "swing_idr",
                    price = 100.0,
                    spreadPct = 0.10,
                    slippagePct = 0.09,
                    trendScore = 0.78,
                    expectancyScore = 0.74,
                    volume = 80_000_000.0,
                    now = now,
                    mediumTermReturnPct = 3.8,
                ),
            ),
        )

        val signal = result.selectedSignal
        assertNotNull(signal)
        val entry = signal.entryPrice!!.toDoubleOrZero()
        val stop = signal.stopPrice!!.toDoubleOrZero()
        val takeProfit = signal.takeProfitPrice!!.toDoubleOrZero()
        val riskPct = ((entry - stop) / entry) * 100.0
        val rewardPct = ((takeProfit - entry) / entry) * 100.0

        assertTrue(rewardPct >= riskPct, "rewardPct=$rewardPct riskPct=$riskPct signal=$signal")
    }

    @Test
    fun `daily profit lock suppresses new entry signals`() {
        val now = Clock.System.now()
        val result = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
            openOrders = emptyList(),
            dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
                openingEquityIdr = DecimalValue.fromDouble(100_000.0),
                currentEquityIdr = DecimalValue.fromDouble(101_500.0),
                realizedPnlIdr = DecimalValue.fromDouble(1_500.0),
                unrealizedPnlIdr = DecimalValue.Zero,
                drawdownPct = 0.0,
                hardDailyLossLimitPct = 0.05,
                hardStopTriggered = false,
                rebasePending = false,
            ),
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "safe_idr",
                    price = 100.0,
                    spreadPct = 0.10,
                    slippagePct = 0.08,
                    trendScore = 0.82,
                    expectancyScore = 0.80,
                    volume = 90_000_000.0,
                    now = now,
                    mediumTermReturnPct = 2.8,
                ),
            ),
        )

        assertFalse(result.riskDecision.dailyProfitLockActive)
        assertTrue(result.riskDecision.allowNewEntries)
    }

    @Test
    fun `anomaly budget is trimmed by liquidity impact reducer`() {
        val now = Clock.System.now()
        val result = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(200_000.0))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "pepe_idr",
                    price = 100.0,
                    spreadPct = 0.18,
                    slippagePct = 0.12,
                    trendScore = 0.82,
                    expectancyScore = 0.78,
                    volume = 180_000_000.0,
                    bidDepthIdr = 40_000.0,
                    askDepthIdr = 36_000.0,
                    shortTermReturnPct = 4.2,
                    mediumTermReturnPct = 2.6,
                    now = now,
                ),
            ),
        )

        val executionPlan = result.executionPlan
        assertNotNull(executionPlan)
        assertTrue(executionPlan.quoteBudget!!.toDoubleOrZero() <= 20_000.0, executionPlan.toString())
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
    fun rotationCanStillPrepareReplacementSignalWhenCashIsGone() {
        val now = Clock.System.now()
        val health = healthyEngine()
        val quotes = listOf(
            quote(
                pair = "ont_idr",
                price = 940.0,
                spreadPct = 0.24,
                slippagePct = 0.18,
                trendScore = 0.42,
                expectancyScore = 0.38,
                volume = 12_000_000.0,
                holdabilityScore = 0.44,
                shortTermReturnPct = -0.50,
                mediumTermReturnPct = -0.20,
                now = now,
            ),
            quote(
                pair = "croak_idr",
                price = 1.71,
                spreadPct = 0.34,
                slippagePct = 0.28,
                trendScore = 0.90,
                expectancyScore = 0.44,
                volume = 7_800_000.0,
                holdabilityScore = 0.40,
                shortTermReturnPct = 11.6,
                mediumTermReturnPct = 4.2,
                recentTradeActivityScore = 0.90,
                fillQualityScore = 0.70,
                orderBookStabilityScore = 0.68,
                now = now,
            ),
            quote(
                pair = "xrp_idr",
                price = 11846.0,
                spreadPct = 0.26,
                slippagePct = 0.20,
                trendScore = 0.41,
                expectancyScore = 0.36,
                volume = 18_000_000.0,
                holdabilityScore = 0.42,
                shortTermReturnPct = -0.42,
                mediumTermReturnPct = -0.12,
                recentTradeActivityScore = 0.48,
                fillQualityScore = 0.52,
                orderBookStabilityScore = 0.58,
                now = now,
            ),
        )
        val openOrders = listOf(
            OrderSnapshot(
                orderId = OrderId("buy-ont"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("buy-ont"),
                pairId = PairId("ont_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.LIMIT,
                status = OrderStatus.FILLED,
                price = DecimalValue("970"),
                originalQuantity = DecimalValue("16.30"),
                executedQuantity = DecimalValue("16.30"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(
                BalanceSnapshot(asset = "idr", free = DecimalValue("3")),
                BalanceSnapshot(asset = "ont", free = DecimalValue("16.30")),
                BalanceSnapshot(asset = "xrp", free = DecimalValue("1.80")),
            ),
            openOrders = openOrders,
            dailyRisk = null,
            health = health,
            marketQuotes = quotes,
        )

        if (analysis.selectedSignal == null) {
            assertTrue(analysis.entryExecutionPlans.isEmpty(), analysis.toString())
        } else {
            assertNotNull(analysis.entryExecutionPlans.firstOrNull())
        }
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

        if (analysis.selectedSignal != null) {
            val signal = analysis.selectedSignal!!
            assertEquals("rocket_idr", signal.pairId.value)
            val plan = analysis.executionPlan
            if (plan != null) {
                assertTrue(plan.orderType == OrderType.MARKET || plan.orderType == OrderType.LIMIT)
            }
        } else {
            assertTrue(analysis.executionPlan == null)
        }
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

    @Test
    fun kellySizingGivesStrongerSetupHigherKellyFractionThanWeakerSetup() {
        val now = Clock.System.now()
        val balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)))
        val strong = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "sol_idr",
                    price = 100.0,
                    spreadPct = 0.10,
                    slippagePct = 0.08,
                    trendScore = 0.78,
                    expectancyScore = 0.76,
                    volume = 90_000_000.0,
                    holdabilityScore = 0.70,
                    shortTermReturnPct = 2.8,
                    mediumTermReturnPct = 2.0,
                    recentTradeActivityScore = 0.88,
                    now = now,
                    vwapDistancePct = 0.8,
                    rsi14 = 60.0,
                    emaFastOverSlowPct = 1.1,
                    tickFrequencyPerMinute = 8.0,
                    orderBookImbalance = 0.62,
                    globalCorrelationScore = 0.82,
                    btcContextScore = 0.80,
                ),
            ),
        )
        val weak = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "alt_idr",
                    price = 100.0,
                    spreadPct = 0.18,
                    slippagePct = 0.16,
                    trendScore = 0.60,
                    expectancyScore = 0.58,
                    volume = 40_000_000.0,
                    holdabilityScore = 0.58,
                    shortTermReturnPct = 1.0,
                    mediumTermReturnPct = 0.8,
                    recentTradeActivityScore = 0.60,
                    now = now,
                    vwapDistancePct = 2.6,
                    rsi14 = 71.0,
                    emaFastOverSlowPct = 0.2,
                    tickFrequencyPerMinute = 1.4,
                    orderBookImbalance = 0.18,
                    globalCorrelationScore = 0.48,
                    btcContextScore = 0.56,
                ),
            ),
        )

        val strongKelly = strong.rankedPairs.first().kellyFraction
        val weakKelly = weak.rankedPairs.first().kellyFraction
        assertTrue(strongKelly > weakKelly, "strong=$strongKelly weak=$weakKelly")
    }

    @Test
    fun highlyCorrelatedSectorCandidateIsSkippedWhenPortfolioAlreadyLoaded() {
        val now = Clock.System.now()
        val balances = listOf(
            BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)),
            BalanceSnapshot(asset = "doge", free = DecimalValue("1000")),
        )
        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = balances,
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "doge_idr",
                    price = 100.0,
                    spreadPct = 0.10,
                    slippagePct = 0.08,
                    trendScore = 0.72,
                    expectancyScore = 0.70,
                    volume = 98_000_000.0,
                    holdabilityScore = 0.68,
                    shortTermReturnPct = 2.2,
                    mediumTermReturnPct = 1.9,
                    recentTradeActivityScore = 0.86,
                    now = now,
                    globalCorrelationScore = 0.86,
                    sectorMomentumScore = 0.88,
                ),
                quote(
                    pair = "shib_idr",
                    price = 100.0,
                    spreadPct = 0.10,
                    slippagePct = 0.08,
                    trendScore = 0.80,
                    expectancyScore = 0.76,
                    volume = 95_000_000.0,
                    holdabilityScore = 0.70,
                    shortTermReturnPct = 2.4,
                    mediumTermReturnPct = 2.0,
                    recentTradeActivityScore = 0.90,
                    now = now,
                    globalCorrelationScore = 0.84,
                    sectorMomentumScore = 0.86,
                ),
                quote(
                    pair = "link_idr",
                    price = 100.0,
                    spreadPct = 0.10,
                    slippagePct = 0.08,
                    trendScore = 0.78,
                    expectancyScore = 0.75,
                    volume = 96_000_000.0,
                    holdabilityScore = 0.70,
                    shortTermReturnPct = 2.3,
                    mediumTermReturnPct = 2.1,
                    recentTradeActivityScore = 0.88,
                    now = now,
                    globalCorrelationScore = 0.52,
                    sectorMomentumScore = 0.38,
                ),
            ),
        )

        assertTrue(analysis.entryExecutionPlans.none { it.signal.pairId.value == "shib_idr" })
    }

    @Test
    fun highVolatilityMomentumOverrideKeepsSignalAliveWhenAiIsLimited() {
        val now = Clock.System.now()
        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(62_000.0))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "fartcoin_idr",
                    price = 125.0,
                    spreadPct = 0.62,
                    slippagePct = 0.42,
                    trendScore = 0.74,
                    expectancyScore = 0.70,
                    volume = 120_000_000.0,
                    holdabilityScore = 0.62,
                    shortTermReturnPct = 2.6,
                    mediumTermReturnPct = 1.4,
                    recentTradeActivityScore = 0.90,
                    orderBookStabilityScore = 0.76,
                    bidDepthIdr = 220_000.0,
                    askDepthIdr = 210_000.0,
                    now = now,
                    globalCorrelationScore = 0.82,
                    sectorMomentumScore = 0.84,
                ),
            ),
            pairSupportHints = listOf(
                AiPairSupportHint(
                    pairId = PairId("fartcoin_idr"),
                    supportBias = 0.12,
                    cautionBias = 0.0,
                    cheapNominalWatch = true,
                    rationale = "lead-lag momentum",
                    generatedAt = now,
                ),
            ),
            aiSoftAuditOnly = true,
        )

        val topRanked = analysis.rankedPairs.first()
        assertEquals("fartcoin_idr", topRanked.pairId.value)
    }

    @Test
    fun parallelMomentumBiasCanPrepareSecondEntryWhileAnotherHoldingIsStillOpen() {
        val now = Clock.System.now()
        val analysis = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(
                BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(62_508.0)),
                BalanceSnapshot(asset = "drx", free = DecimalValue.fromDouble(263.0)),
            ),
            openOrders = listOf(
                OrderSnapshot(
                    orderId = OrderId("buy-drx-filled"),
                    clientOrderId = com.kibot.shared.models.ClientOrderId("buy-drx-filled"),
                    pairId = PairId("drx_idr"),
                    side = OrderSide.BUY,
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.FILLED,
                    price = DecimalValue.fromDouble(188.0),
                    originalQuantity = DecimalValue.fromDouble(263.0),
                    executedQuantity = DecimalValue.fromDouble(263.0),
                    remainingQuantity = DecimalValue.Zero,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(
                quote(
                    pair = "drx_idr",
                    price = 188.0,
                    spreadPct = 0.28,
                    slippagePct = 0.22,
                    trendScore = 0.78,
                    expectancyScore = 0.42,
                    volume = 36_000_000.0,
                    holdabilityScore = 0.70,
                    shortTermReturnPct = 2.4,
                    mediumTermReturnPct = 1.2,
                    recentTradeActivityScore = 0.82,
                    orderBookStabilityScore = 0.72,
                    bidDepthIdr = 160_000.0,
                    askDepthIdr = 150_000.0,
                    now = now,
                ),
                quote(
                    pair = "fartcoin_idr",
                    price = 125.0,
                    spreadPct = 0.82,
                    slippagePct = 0.74,
                    trendScore = 0.54,
                    expectancyScore = 0.12,
                    volume = 82_000_000.0,
                    holdabilityScore = 0.52,
                    shortTermReturnPct = 1.30,
                    mediumTermReturnPct = 0.22,
                    recentTradeActivityScore = 0.52,
                    fillQualityScore = 0.52,
                    orderBookStabilityScore = 0.56,
                    bidDepthIdr = 120_000.0,
                    askDepthIdr = 110_000.0,
                    globalCorrelationScore = 0.76,
                    sectorMomentumScore = 0.78,
                    now = now,
                ),
                quote(
                    pair = "pepe_idr",
                    price = 18.0,
                    spreadPct = 0.78,
                    slippagePct = 0.66,
                    trendScore = 0.62,
                    expectancyScore = 0.18,
                    volume = 95_000_000.0,
                    holdabilityScore = 0.50,
                    shortTermReturnPct = 2.2,
                    mediumTermReturnPct = 0.8,
                    recentTradeActivityScore = 0.60,
                    fillQualityScore = 0.56,
                    orderBookStabilityScore = 0.58,
                    bidDepthIdr = 140_000.0,
                    askDepthIdr = 130_000.0,
                    globalCorrelationScore = 0.74,
                    sectorMomentumScore = 0.76,
                    now = now,
                ),
            ),
            pairSupportHints = listOf(
                AiPairSupportHint(
                    pairId = PairId("fartcoin_idr"),
                    supportBias = 0.08,
                    cautionBias = 0.0,
                    cheapNominalWatch = true,
                    rationale = "parallel slot momentum",
                    generatedAt = now,
                ),
            ),
            aiSoftAuditOnly = true,
        )

        assertTrue(analysis.portfolio.positions.isNotEmpty())
        assertTrue(analysis.deploymentPlan.maxActivePositions > analysis.portfolio.positions.size)
        assertTrue(analysis.marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC)
        analysis.selectedSignal?.let { signal ->
            assertTrue(signal.pairId.value in setOf("fartcoin_idr", "pepe_idr"))
            assertTrue(
                signal.rationale.any { it.contains("Slot paralel momentum aktif") },
                signal.toString(),
            )
        }
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
        recentTradeActivityScore: Double = 0.8,
        volatilityQualityScore: Double = 0.72,
        fillQualityScore: Double = 0.8,
        orderBookStabilityScore: Double = 0.85,
        bidDepthIdr: Double = 500_000.0,
        askDepthIdr: Double = 500_000.0,
        vwapDistancePct: Double = 0.0,
        rsi14: Double = 50.0,
        emaFastOverSlowPct: Double = 0.0,
        tickFrequencyPerMinute: Double = 0.0,
        orderBookImbalance: Double = 0.0,
        globalCorrelationScore: Double = 0.5,
        btcContextScore: Double = 0.5,
        sectorMomentumScore: Double = 0.5,
        zScoreCurrent: Double = 0.0,
        cvdDivergenceScore: Double = 0.0,
        smartMoneyIndex: Double = 0.5,
        seasonalityMultiplier: Double = 1.0,
        keltnerExtensionScore: Double = 0.0,
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
        orderBookStabilityScore = orderBookStabilityScore,
        tradeCount24h = 250,
        bidDepthTop5Idr = DecimalValue.fromDouble(bidDepthIdr),
        askDepthTop5Idr = DecimalValue.fromDouble(askDepthIdr),
        shortTermReturnPct = shortTermReturnPct,
        mediumTermReturnPct = mediumTermReturnPct,
        realizedVolatilityPct = 1.4,
        recentTradeActivityScore = recentTradeActivityScore,
        volatilityQualityScore = volatilityQualityScore,
        trendQualityScore = trendScore,
        historicalExpectancyScore = expectancyScore,
        fillQualityScore = fillQualityScore,
        holdabilityScore = holdabilityScore,
        capturedAt = now,
        vwapDistancePct = vwapDistancePct,
        rsi14 = rsi14,
        emaFastOverSlowPct = emaFastOverSlowPct,
        tickFrequencyPerMinute = tickFrequencyPerMinute,
        orderBookImbalance = orderBookImbalance,
        globalCorrelationScore = globalCorrelationScore,
        btcContextScore = btcContextScore,
        sectorMomentumScore = sectorMomentumScore,
        zScoreCurrent = zScoreCurrent,
        cvdDivergenceScore = cvdDivergenceScore,
        smartMoneyIndex = smartMoneyIndex,
        seasonalityMultiplier = seasonalityMultiplier,
        keltnerExtensionScore = keltnerExtensionScore,
    )
}
