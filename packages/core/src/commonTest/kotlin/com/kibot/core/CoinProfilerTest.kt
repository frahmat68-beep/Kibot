package com.kibot.core

import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoinProfilerTest {
    private val profiler = CoinProfiler()

    @Test
    fun `zombie flatliner is classified and avoided`() {
        val assessment = profiler.assess(
            quote = quote(
                pair = "flat_idr",
                bid = 10.0,
                ask = 10.0,
                volume = 900_000.0,
                shortTermReturn = 0.1,
                mediumTermReturn = 0.1,
                tradeActivity = 0.04,
                tradeCount24h = 7,
                tickFrequencyPerMinute = 0.02,
                globalCorrelationScore = 0.22,
                toxicFlowScore = 0.38,
            ),
        )

        assertTrue(assessment.archetype != CoinProfiler.Archetype.HIGH_BETA, assessment.toString())
        assertTrue(
            assessment.shouldAvoid ||
                assessment.deadChartScore > 0.70 ||
                assessment.rejectionReasons.any { it.contains("Frekuensi transaksi terlalu kecil") },
            assessment.toString(),
        )
    }

    @Test
    fun `healthy high beta setup gets strong structure and kelly sizing`() {
        val assessment = profiler.assess(
            quote = quote(
                pair = "sol_idr",
                bid = 2500.0,
                ask = 2504.0,
                volume = 820_000_000.0,
                shortTermReturn = 3.8,
                mediumTermReturn = 2.2,
                tradeActivity = 0.86,
                tradeCount24h = 6_400,
                vwapDistancePct = 0.9,
                rsi14 = 61.0,
                emaFastOverSlowPct = 1.2,
                tickFrequencyPerMinute = 8.5,
                orderBookImbalance = 0.61,
                globalCorrelationScore = 0.82,
                btcContextScore = 0.78,
                sectorMomentumScore = 0.64,
            ),
            referenceQuotes = listOf(
                quote(pair = "btc_usdt", bid = 1.0, ask = 1.0, volume = 1.0, shortTermReturn = 1.2, mediumTermReturn = 0.8, tradeActivity = 0.8),
            ),
        )

        assertEquals(CoinProfiler.Archetype.HIGH_BETA, assessment.archetype)
        assertTrue(!assessment.shouldAvoid, assessment.toString())
        assertTrue(assessment.progressiveScore > 0.60, assessment.toString())
        assertTrue(assessment.kellyFraction > 0.10, assessment.toString())
    }

    @Test
    fun `extreme z score and weak smart money trigger caution`() {
        val assessment = profiler.assess(
            quote = quote(
                pair = "pepe_idr",
                bid = 120.0,
                ask = 121.0,
                volume = 140_000_000.0,
                shortTermReturn = 8.2,
                mediumTermReturn = 9.6,
                tradeActivity = 0.82,
                tradeCount24h = 2_400,
                vwapDistancePct = 5.8,
                rsi14 = 84.0,
                emaFastOverSlowPct = 3.4,
                tickFrequencyPerMinute = 4.8,
                orderBookImbalance = 0.18,
                globalCorrelationScore = 0.42,
                zScoreCurrent = 4.1,
                cvdDivergenceScore = 0.76,
                smartMoneyIndex = 0.18,
                keltnerExtensionScore = 0.86,
            ),
        )

        assertTrue(assessment.statisticalStretchScore > 0.75, assessment.toString())
        assertTrue(assessment.smartMoneyScore < 0.35, assessment.toString())
        assertTrue(assessment.rejectionReasons.any { it.contains("statistik", ignoreCase = true) }, assessment.toString())
    }

    private fun quote(
        pair: String,
        bid: Double,
        ask: Double,
        volume: Double,
        shortTermReturn: Double,
        mediumTermReturn: Double,
        tradeActivity: Double,
        tradeCount24h: Int = 120,
        vwapDistancePct: Double = 0.0,
        rsi14: Double = 50.0,
        emaFastOverSlowPct: Double = 0.0,
        tickFrequencyPerMinute: Double = 0.0,
        orderBookImbalance: Double = 0.0,
        globalCorrelationScore: Double = 0.5,
        btcContextScore: Double = 0.5,
        sectorMomentumScore: Double = 0.5,
        toxicFlowScore: Double = 0.0,
        zScoreCurrent: Double = 0.0,
        cvdDivergenceScore: Double = 0.0,
        smartMoneyIndex: Double = 0.5,
        seasonalityMultiplier: Double = 1.0,
        keltnerExtensionScore: Double = 0.0,
    ) = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue.fromDouble(bid),
        bestAsk = DecimalValue.fromDouble(ask),
        midPrice = DecimalValue.fromDouble((bid + ask) / 2.0),
        spreadPct = if (bid > 0.0 && ask >= bid) ((ask - bid) / bid) * 100.0 else 0.2,
        quoteVolume24h = DecimalValue.fromDouble(volume),
        baseVolume24h = DecimalValue.fromDouble(volume / bid.coerceAtLeast(1.0)),
        estimatedSlippagePct = 0.18,
        orderBookStabilityScore = 0.82,
        tradeCount24h = tradeCount24h,
        bidDepthTop5Idr = DecimalValue.fromDouble(volume / 120.0),
        askDepthTop5Idr = DecimalValue.fromDouble(volume / 130.0),
        shortTermReturnPct = shortTermReturn,
        mediumTermReturnPct = mediumTermReturn,
        realizedVolatilityPct = 2.8,
        recentTradeActivityScore = tradeActivity,
        volatilityQualityScore = 0.72,
        trendQualityScore = 0.74,
        historicalExpectancyScore = 0.68,
        fillQualityScore = 0.78,
        holdabilityScore = 0.66,
        capturedAt = Instant.parse("2026-03-31T14:00:00Z"),
        vwapDistancePct = vwapDistancePct,
        rsi14 = rsi14,
        emaFastOverSlowPct = emaFastOverSlowPct,
        tickFrequencyPerMinute = tickFrequencyPerMinute,
        orderBookImbalance = orderBookImbalance,
        globalCorrelationScore = globalCorrelationScore,
        sectorMomentumScore = sectorMomentumScore,
        btcContextScore = btcContextScore,
        toxicFlowScore = toxicFlowScore,
        zScoreCurrent = zScoreCurrent,
        cvdDivergenceScore = cvdDivergenceScore,
        smartMoneyIndex = smartMoneyIndex,
        seasonalityMultiplier = seasonalityMultiplier,
        keltnerExtensionScore = keltnerExtensionScore,
    )
}
