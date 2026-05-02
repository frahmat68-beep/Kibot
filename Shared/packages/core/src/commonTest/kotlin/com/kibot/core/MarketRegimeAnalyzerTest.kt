package com.kibot.core

import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SyncHealth
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketRegimeAnalyzerTest {
    private val health = EngineHealthSnapshot(
        status = HealthStatus.HEALTHY,
        syncHealth = SyncHealth.HEALTHY,
        websocketHealthy = true,
        exchangeReachable = true,
        supabaseReachable = true,
        fillQualityScore = 0.9,
    )

    @Test
    fun `detects healthy uptrend when top pairs are strong`() {
        val quotes = listOf(
            quote("btc_idr", 180_000_000.0, shortTermReturnPct = 2.4, mediumTermReturnPct = 6.3, trend = 0.84, holdability = 0.81),
            quote("eth_idr", 140_000_000.0, shortTermReturnPct = 2.0, mediumTermReturnPct = 5.7, trend = 0.80, holdability = 0.78),
            quote("sol_idr", 95_000_000.0, shortTermReturnPct = 1.8, mediumTermReturnPct = 4.8, trend = 0.76, holdability = 0.72),
        )
        val scores = PairSelector().rank(quotes)

        val snapshot = MarketRegimeAnalyzer().analyze(
            quotes = quotes,
            rankedPairs = scores,
            health = health,
            performanceMomentumScore = 0.76,
        )

        assertEquals(MarketRegime.HEALTHY_UPTREND, snapshot.regime)
        assertTrue(snapshot.marketOpportunityScore > 0.70)
    }

    private fun quote(
        pair: String,
        quoteVolume: Double,
        shortTermReturnPct: Double,
        mediumTermReturnPct: Double,
        trend: Double,
        holdability: Double,
    ) = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue("100"),
        bestAsk = DecimalValue("100.2"),
        midPrice = DecimalValue("100.1"),
        spreadPct = 0.2,
        quoteVolume24h = DecimalValue.fromDouble(quoteVolume),
        baseVolume24h = DecimalValue("100"),
        estimatedSlippagePct = 0.15,
        orderBookStabilityScore = 0.9,
        shortTermReturnPct = shortTermReturnPct,
        mediumTermReturnPct = mediumTermReturnPct,
        trendQualityScore = trend,
        holdabilityScore = holdability,
        fillQualityScore = 0.85,
        recentTradeActivityScore = 0.88,
        historicalExpectancyScore = 0.72,
        volatilityQualityScore = 0.72,
        capturedAt = Instant.parse("2026-03-15T01:00:00Z"),
    )
}
