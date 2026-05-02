package com.kibot.core

import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartAnalyzerTest {
    private val analyzer = ChartAnalyzer()

    @Test
    fun `healthy momentum pair yields entry-friendly assessment`() {
        val assessment = analyzer.analyzeQuoteSnapshot(
            quote = marketQuote(
                pair = "fart_idr",
                bid = 128.0,
                ask = 128.3,
                volume = 540_000_000.0,
                slippagePct = 0.18,
                shortTermReturn = 4.8,
                mediumTermReturn = 2.4,
                trendQuality = 0.79,
                activity = 0.88,
                realizedVolatilityPct = 3.2,
                fillQuality = 0.84,
                holdability = 0.72,
                bidDepth = 380_000.0,
                askDepth = 350_000.0,
            ),
        )

        assertTrue(assessment.entryScore > 0.65)
        assertTrue(assessment.breakEvenMovePct > 0.0)
        assertTrue(assessment.softTakeProfitPct > assessment.breakEvenMovePct)
        assertEquals(ChartAnalyzer.PreferredOrderType.LIMIT_MID, assessment.preferredOrderType)
        assertTrue(assessment.vetoReasons.isEmpty())
    }

    @Test
    fun `thin pair gets vetoed for aggressive entry`() {
        val assessment = analyzer.analyzeQuoteSnapshot(
            quote = marketQuote(
                pair = "zombie_idr",
                bid = 7.0,
                ask = 7.5,
                volume = 9_500_000.0,
                spreadPct = 1.9,
                slippagePct = 2.1,
                shortTermReturn = 8.0,
                mediumTermReturn = 3.2,
                trendQuality = 0.64,
                activity = 0.42,
                bidDepth = 4_000.0,
                askDepth = 3_000.0,
            ),
        )

        assertTrue(assessment.shouldAvoidEntry || assessment.vetoReasons.isNotEmpty(), assessment.toString())
        assertTrue(assessment.vetoReasons.isNotEmpty())
    }

    @Test
    fun `stagnant one-hour position raises force-rotate urgency`() {
        val assessment = analyzer.analyzeQuoteSnapshot(
            quote = marketQuote(
                pair = "slow_idr",
                bid = 202.0,
                ask = 202.4,
                volume = 120_000_000.0,
                spreadPct = 0.28,
                slippagePct = 0.32,
                shortTermReturn = 0.10,
                mediumTermReturn = 0.18,
                trendQuality = 0.44,
                activity = 0.30,
                realizedVolatilityPct = 0.90,
                bidDepth = 36_000.0,
                askDepth = 34_000.0,
            ),
            positionAgeMinutes = 72.0,
            unrealizedPnlPct = 0.12,
        )

        assertTrue(assessment.rotationUrgencyScore >= 0.68, assessment.toString())
        assertTrue(assessment.shouldForceRotate)
    }

    @Test
    fun `cheap nominal looping chart is blocked`() {
        val assessment = analyzer.assessHistoryGuard(
            candleCount = 32,
            activeCandleCount = 18,
            distinctCloseBuckets = 4,
            rangePct = 2.6,
            lastClose = 11.0,
            dominantCloseShare = 0.58,
            directionFlipRate = 0.86,
            higherHighRatio = 0.18,
            higherLowRatio = 0.16,
            closingProgressRatio = 0.34,
            netProgressPct = 0.8,
            minCandles = 18,
            minActiveCandles = 6,
            minDistinctCloseBuckets = 4,
            cheapNominalMaxPrice = 25.0,
            cheapNominalMinDistinctCloses = 10,
            minRangePct = 0.8,
        )

        assertTrue(assessment.blocked, assessment.toString())
        assertTrue(assessment.blockedReason?.contains("cheap_nominal_chart_blocked") == true, assessment.toString())
        assertTrue(assessment.deadChartScore > assessment.progressiveScore, assessment.toString())
    }

    @Test
    fun `two level ping pong chart is treated as dead even with visible range`() {
        val assessment = analyzer.assessHistoryGuard(
            candleCount = 30,
            activeCandleCount = 22,
            distinctCloseBuckets = 6,
            rangePct = 4.2,
            lastClose = 10.0,
            dominantCloseShare = 0.54,
            directionFlipRate = 0.82,
            higherHighRatio = 0.22,
            higherLowRatio = 0.19,
            closingProgressRatio = 0.28,
            netProgressPct = 0.5,
            minCandles = 18,
            minActiveCandles = 6,
            minDistinctCloseBuckets = 4,
            cheapNominalMaxPrice = 25.0,
            cheapNominalMinDistinctCloses = 10,
            minRangePct = 0.8,
        )

        assertTrue(assessment.blocked, assessment.toString())
        assertTrue(
            assessment.blockedReason?.contains("chart_ping_pong_blocked") == true ||
                assessment.blockedReason?.contains("cheap_nominal_chart_blocked") == true,
            assessment.toString(),
        )
        assertTrue(assessment.deadChartScore >= 0.70, assessment.toString())
    }

    @Test
    fun `active breakout history is allowed`() {
        val assessment = analyzer.assessHistoryGuard(
            candleCount = 28,
            activeCandleCount = 16,
            distinctCloseBuckets = 14,
            rangePct = 6.4,
            lastClose = 128.0,
            dominantCloseShare = 0.18,
            directionFlipRate = 0.26,
            higherHighRatio = 0.72,
            higherLowRatio = 0.66,
            closingProgressRatio = 0.88,
            netProgressPct = 5.6,
            minCandles = 18,
            minActiveCandles = 6,
            minDistinctCloseBuckets = 4,
            cheapNominalMaxPrice = 25.0,
            cheapNominalMinDistinctCloses = 10,
            minRangePct = 0.8,
        )

        assertTrue(!assessment.blocked, assessment.toString())
        assertTrue(assessment.rangeOpportunityScore > 0.55, assessment.toString())
        assertTrue(assessment.progressiveScore > assessment.deadChartScore, assessment.toString())
    }

    @Test
    fun `compressed volatility with positive obi gets breakout watch rationale`() {
        val assessment = analyzer.analyzeQuoteSnapshot(
            quote = marketQuote(
                pair = "coil_idr",
                bid = 250.0,
                ask = 250.3,
                volume = 210_000_000.0,
                shortTermReturn = 0.25,
                mediumTermReturn = 0.45,
                trendQuality = 0.58,
                activity = 0.74,
                realizedVolatilityPct = 0.45,
                bidDepth = 420_000.0,
                askDepth = 380_000.0,
            ).copy(
                orderBookImbalance = 0.62,
                vwapDistancePct = 0.18,
            ),
        )

        assertTrue(assessment.entryScore > 0.45, assessment.toString())
        assertTrue(assessment.rationale.any { it.contains("terkompresi", ignoreCase = true) }, assessment.toString())
    }

    private fun marketQuote(
        pair: String,
        bid: Double,
        ask: Double,
        volume: Double,
        spreadPct: Double = 0.22,
        slippagePct: Double = 0.18,
        shortTermReturn: Double,
        mediumTermReturn: Double,
        trendQuality: Double,
        activity: Double,
        realizedVolatilityPct: Double = 2.4,
        fillQuality: Double = 0.76,
        holdability: Double = 0.66,
        bidDepth: Double = 120_000.0,
        askDepth: Double = 120_000.0,
    ) = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue.fromDouble(bid),
        bestAsk = DecimalValue.fromDouble(ask),
        midPrice = DecimalValue.fromDouble((bid + ask) / 2.0),
        spreadPct = spreadPct,
        quoteVolume24h = DecimalValue.fromDouble(volume),
        baseVolume24h = DecimalValue.fromDouble(volume / bid.coerceAtLeast(1.0)),
        estimatedSlippagePct = slippagePct,
        orderBookStabilityScore = 0.82,
        tradeCount24h = 1_200,
        bidDepthTop5Idr = DecimalValue.fromDouble(bidDepth),
        askDepthTop5Idr = DecimalValue.fromDouble(askDepth),
        shortTermReturnPct = shortTermReturn,
        mediumTermReturnPct = mediumTermReturn,
        realizedVolatilityPct = realizedVolatilityPct,
        recentTradeActivityScore = activity,
        volatilityQualityScore = 0.72,
        trendQualityScore = trendQuality,
        historicalExpectancyScore = 0.71,
        fillQualityScore = fillQuality,
        holdabilityScore = holdability,
        capturedAt = Instant.parse("2026-03-31T00:00:00Z"),
    )
}
