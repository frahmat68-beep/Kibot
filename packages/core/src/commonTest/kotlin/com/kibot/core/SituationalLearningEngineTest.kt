package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.ExecutionAnomalySignature
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.TradingHorizon
import com.kibot.shared.models.WeeklyAdaptationPlan
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class SituationalLearningEngineTest {
    private val orchestrator = StrategyOrchestrator()
    private val learningEngine = SituationalLearningEngine()

    @Test
    fun `creates bounded update recommendations when weekly review degrades`() {
        val engine = SituationalLearningEngine(
            SituationalLearningConfig(maxRecommendationsPerCycle = 2),
        )
        val cycle = healthyCycle()
        val summary = WeeklyLearningSummary(
            botId = BotId("main"),
            periodStart = LocalDate(2026, 3, 9),
            periodEnd = LocalDate(2026, 3, 15),
            tradeCount = 18,
            falseEntryRate = 0.34,
            noTradeQualityScore = 0.58,
            avoidedBadTradesIndicator = 0.42,
            capitalUtilizationPct = 0.40,
            productiveUtilizationPct = 0.27,
            missedOpportunityRate = 0.29,
            tacticalExpectancy = 0.22,
            swingExpectancy = 0.10,
            adaptationPlan = WeeklyAdaptationPlan(),
        )

        val decision = engine.evaluate(
            botId = BotId("main"),
            deviceId = DeviceId("android-main"),
            now = Clock.System.now(),
            cycle = cycle,
            weeklySummary = summary,
            aiBlockedReason = null,
            aiUsedNetwork = false,
        )

        val codes = decision.updateRecommendations.map { it.reasonCode }
        assertTrue("tighten_entry_gate" in codes)
        assertTrue("improve_capital_deployment" in codes)
    }

    @Test
    fun `adds ai budget hint without requiring update recommendation`() {
        val decision = learningEngine.evaluate(
            botId = BotId("main"),
            deviceId = DeviceId("android-main"),
            now = Clock.System.now(),
            cycle = healthyCycle(),
            weeklySummary = null,
            aiBlockedReason = "daily_budget",
            aiUsedNetwork = false,
        )

        val codes = decision.learningHints.map { it.hintCode }
        assertTrue("learning_sample_thin" in codes)
        assertTrue("ai_budget_guard" in codes)
    }

    @Test
    fun `turns a grade anomaly signature into blueprint hint`() {
        val summary = WeeklyLearningSummary(
            botId = BotId("main"),
            periodStart = LocalDate(2026, 3, 9),
            periodEnd = LocalDate(2026, 3, 15),
            tradeCount = 12,
            falseEntryRate = 0.12,
            noTradeQualityScore = 0.68,
            avoidedBadTradesIndicator = 0.52,
            capitalUtilizationPct = 0.41,
            productiveUtilizationPct = 0.34,
            missedOpportunityRate = 0.10,
            tacticalExpectancy = 0.24,
            swingExpectancy = 0.18,
            adaptationPlan = WeeklyAdaptationPlan(),
            executionSignatures = listOf(
                ExecutionAnomalySignature(
                    observedAt = Clock.System.now(),
                    pairId = PairId("ont_idr"),
                    setupType = SetupType.HEALTHY_SHORT_TERM_PULLBACK,
                    anomalyGrade = "A-GRADE_ANOMALY",
                    vwapDistancePct = 0.38,
                    orderBookImbalance = 0.82,
                    cvdDivergenceScore = 0.76,
                    tickFrequencyPerMinute = 14.0,
                    realizedPnlPct = 18.4,
                    expectedNetEdgePct = 0.62,
                    confidenceScore = 0.91,
                ),
            ),
        )

        val decision = learningEngine.evaluate(
            botId = BotId("main"),
            deviceId = DeviceId("android-main"),
            now = Clock.System.now(),
            cycle = healthyCycle(),
            weeklySummary = summary,
            aiBlockedReason = null,
            aiUsedNetwork = false,
        )

        val codes = decision.learningHints.map { it.hintCode }
        assertTrue("ont_a_grade_anomaly" in codes)
        assertTrue(decision.updateRecommendations.any { it.reasonCode == "ont_a_grade_blueprint" })
    }

    private fun healthyCycle() = orchestrator.analyze(
        botId = BotId("main"),
        balances = listOf(
            BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0)),
        ),
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
            quote("btc_idr", 1_000_000_000.0, 0.10, 0.08, 0.65, 0.62, 90_000_000.0),
            quote("sol_idr", 2_500_000.0, 0.14, 0.10, 0.68, 0.64, 70_000_000.0),
            quote("xrp_idr", 9_800.0, 0.12, 0.09, 0.61, 0.60, 55_000_000.0),
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
        orderBookStabilityScore = 0.86,
        tradeCount24h = 300,
        bidDepthTop5Idr = DecimalValue.fromDouble(900_000.0),
        askDepthTop5Idr = DecimalValue.fromDouble(900_000.0),
        shortTermReturnPct = 0.9,
        mediumTermReturnPct = 1.3,
        realizedVolatilityPct = 1.6,
        recentTradeActivityScore = 0.82,
        volatilityQualityScore = 0.74,
        trendQualityScore = trendScore,
        historicalExpectancyScore = expectancyScore,
        fillQualityScore = 0.84,
        holdabilityScore = if (pair == "btc_idr") 0.72 else 0.66,
        capturedAt = Clock.System.now(),
    )
}
