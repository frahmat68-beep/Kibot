package com.kibot.core

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SyncHealth
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun quote(
        pair: String,
        price: Double,
        spreadPct: Double,
        slippagePct: Double,
        trendScore: Double,
        expectancyScore: Double,
        volume: Double,
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
        shortTermReturnPct = 0.8,
        mediumTermReturnPct = 1.2,
        realizedVolatilityPct = 1.4,
        recentTradeActivityScore = 0.8,
        volatilityQualityScore = 0.72,
        trendQualityScore = trendScore,
        historicalExpectancyScore = expectancyScore,
        fillQualityScore = 0.8,
        holdabilityScore = 0.65,
        capturedAt = now,
    )
}
