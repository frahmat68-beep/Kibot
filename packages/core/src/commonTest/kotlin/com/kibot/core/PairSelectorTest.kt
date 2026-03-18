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
        assertEquals(PairTier.TIER_A, shortlist.first().pairTier)
        assertEquals(TradingHorizon.SWING, shortlist.first().preferredHorizon)
        assertTrue(shortlist.first().rankingScore > 0.70)
    }
}
