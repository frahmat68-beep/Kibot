package com.kibot.core

import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PairTier
import com.kibot.shared.models.TradingHorizon
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairSelectorTest {
    @Test
    fun `shortlist only keeps healthy pairs`() {
        val selector = PairSelector()
        val shortlist = selector.shortlist(
            listOf(
                MarketQuote(
                    pairId = PairId("btc_idr"),
                    bestBid = DecimalValue("100"),
                    bestAsk = DecimalValue("101"),
                    midPrice = DecimalValue("100.5"),
                    spreadPct = 0.20,
                    quoteVolume24h = DecimalValue("50000000"),
                    baseVolume24h = DecimalValue("100"),
                    estimatedSlippagePct = 0.15,
                    orderBookStabilityScore = 0.9,
                    recentTradeActivityScore = 0.85,
                    trendQualityScore = 0.78,
                    historicalExpectancyScore = 0.70,
                    fillQualityScore = 0.88,
                    holdabilityScore = 0.76,
                    capturedAt = Instant.parse("2026-03-15T01:00:00Z"),
                ),
                MarketQuote(
                    pairId = PairId("thin_pair"),
                    bestBid = DecimalValue("1"),
                    bestAsk = DecimalValue("2"),
                    midPrice = DecimalValue("1.5"),
                    spreadPct = 2.0,
                    quoteVolume24h = DecimalValue("50"),
                    baseVolume24h = DecimalValue("5"),
                    estimatedSlippagePct = 2.0,
                    orderBookStabilityScore = 0.1,
                    capturedAt = Instant.parse("2026-03-15T01:00:00Z"),
                ),
            ),
        )

        assertEquals(listOf("btc_idr"), shortlist.map { it.pairId.value })
        assertTrue(shortlist.first().allowed)
        assertTrue(shortlist.first().pairTier != PairTier.TIER_C)
        assertEquals(TradingHorizon.SWING, shortlist.first().preferredHorizon)
        assertTrue(shortlist.first().rankingScore > 0.65)
    }

    @Test
    fun `small capital override can allow deep and active lower-volume pair`() {
        val selector = PairSelector()
        val ranked = selector.rank(
            listOf(
                MarketQuote(
                    pairId = PairId("micro_idr"),
                    bestBid = DecimalValue("120"),
                    bestAsk = DecimalValue("120.3"),
                    midPrice = DecimalValue("120.15"),
                    spreadPct = 0.25,
                    quoteVolume24h = DecimalValue("8000000"),
                    baseVolume24h = DecimalValue("35000"),
                    estimatedSlippagePct = 0.18,
                    orderBookStabilityScore = 0.78,
                    tradeCount24h = 650,
                    bidDepthTop5Idr = DecimalValue("450000"),
                    askDepthTop5Idr = DecimalValue("430000"),
                    recentTradeActivityScore = 0.74,
                    trendQualityScore = 0.63,
                    historicalExpectancyScore = 0.60,
                    fillQualityScore = 0.76,
                    holdabilityScore = 0.58,
                    capturedAt = Instant.parse("2026-03-15T01:00:00Z"),
                ),
            ),
        )

        assertEquals("micro_idr", ranked.first().pairId.value)
        assertTrue(ranked.first().allowed)
        assertEquals(PairTier.TIER_B, ranked.first().pairTier)
        assertEquals(TradingHorizon.TACTICAL, ranked.first().preferredHorizon)
    }
}
