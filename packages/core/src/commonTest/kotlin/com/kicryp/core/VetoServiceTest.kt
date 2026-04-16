package com.kicryp.core

import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.PairId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VetoServiceTest {
    @Test
    fun `soft audit mode allows correlated entry but still respects price band`() {
        val vetoService = VetoService()
        val candidate = pairScore("kernel_idr")
        val quote = marketQuote("kernel_idr", spreadPct = 0.78)
        val leadLagSignal = LeadLagSelectionSignal(
            leadPairId = PairId("ont_idr"),
            leadSectorFamily = "l1_l2",
            leadMomentumScore = 0.84,
            fatigue = true,
        )

        assertTrue(
            vetoService.shouldVetoEntry(
                candidate = candidate,
                quote = quote,
                leadLagSignal = leadLagSignal,
                priceBandAllowed = true,
                softAuditOnly = true,
            ).not(),
            "soft audit should not hard-veto a technically valid correlated entry",
        )
        assertTrue(
            vetoService.shouldVetoEntry(
                candidate = candidate,
                quote = quote,
                leadLagSignal = leadLagSignal,
                priceBandAllowed = false,
                softAuditOnly = true,
            ),
            "price band must remain a hard block",
        )
        assertTrue(
            vetoService.shouldVetoEntry(
                candidate = candidate,
                quote = quote,
                leadLagSignal = leadLagSignal,
                priceBandAllowed = true,
                softAuditOnly = false,
            ),
            "without soft audit override, fatigued cross-sector lead-lag should still veto",
        )
    }

    private fun pairScore(pair: String) = com.kicryp.shared.models.PairScore(
        pairId = PairId(pair),
        liquidityScore = 0.82,
        spreadScore = 0.91,
        slippageScore = 0.88,
        stabilityScore = 0.90,
        feeAdjustedEdgeScore = 0.84,
        rankingScore = 0.79,
        allowed = true,
    )

    private fun marketQuote(pair: String, spreadPct: Double) = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue("1890"),
        bestAsk = DecimalValue("1905"),
        midPrice = DecimalValue("1897.5"),
        spreadPct = spreadPct,
        quoteVolume24h = DecimalValue("125000000"),
        baseVolume24h = DecimalValue("66000"),
        estimatedSlippagePct = 0.36,
        orderBookStabilityScore = 0.88,
        recentTradeActivityScore = 0.81,
        trendQualityScore = 0.74,
        historicalExpectancyScore = 0.69,
        fillQualityScore = 0.83,
        holdabilityScore = 0.68,
        capturedAt = Instant.parse("2026-04-02T00:00:00Z"),
    )
}
