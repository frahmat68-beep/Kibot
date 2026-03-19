package com.kibot.core

import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PairTier
import com.kibot.shared.models.TradingHorizon
import kotlin.math.abs

class PairSelector(
    private val policy: PairSelectionPolicy = PairSelectionPolicy(),
) {
    fun rank(quotes: List<MarketQuote>): List<PairScore> {
        val candidates = prefilter(quotes)
        return candidates.map(::scoreQuote).sortedWith(pairRankingComparator())
    }

    fun shortlist(quotes: List<MarketQuote>): List<PairScore> {
        return rank(quotes)
            .filter { it.allowed }
            .take(policy.shortlistSize)
    }

    private fun prefilter(quotes: List<MarketQuote>): List<MarketQuote> {
        val poolSize = policy.prefilterCandidatePoolSize.coerceAtLeast(policy.shortlistSize)
        if (quotes.size <= poolSize) return quotes

        val lenientCandidates = quotes.asSequence()
            .filter { quote ->
                quote.quoteVolume24h.toDoubleOrZero() >= policy.minDailyQuoteVolumeIdr * 0.25 &&
                    quote.spreadPct <= policy.maxSpreadPct * 1.6 &&
                    quote.estimatedSlippagePct <= policy.maxEstimatedSlippagePct * 1.6 &&
                    quote.orderBookStabilityScore >= policy.minOrderBookStabilityScore * 0.6
            }
            .sortedByDescending(::prefilterScore)
            .take(poolSize)
            .toList()

        if (lenientCandidates.isNotEmpty()) return lenientCandidates

        return quotes
            .sortedByDescending(::prefilterScore)
            .take(poolSize)
    }

    private fun scoreQuote(quote: MarketQuote): PairScore {
        val liquidityScore = normalizeRatio(
            value = quote.quoteVolume24h.toDoubleOrZero(),
            baseline = policy.minDailyQuoteVolumeIdr,
            saturationMultiplier = 5.0,
        )
        val spreadScore = inverseThresholdScore(quote.spreadPct, policy.maxSpreadPct)
        val slippageScore = inverseThresholdScore(quote.estimatedSlippagePct, policy.maxEstimatedSlippagePct)
        val stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0)
        val volumeConsistencyScore = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        val volatilityQualityScore = deriveVolatilityQuality(quote)
        val trendQualityScore = deriveTrendQuality(quote)
        val historicalExpectancyScore = quote.historicalExpectancyScore.coerceIn(0.0, 1.0)
        val fillQualityScore = quote.fillQualityScore.coerceIn(0.0, 1.0)
        val recentHealthScore = averageOf(
            stabilityScore,
            fillQualityScore,
            volumeConsistencyScore,
            spreadScore,
            slippageScore,
        )
        val holdabilityScore = deriveHoldabilityScore(quote, trendQualityScore, volatilityQualityScore)
        val rankingScore = weightedAverage(
            liquidityScore to 0.12,
            spreadScore to 0.12,
            slippageScore to 0.12,
            stabilityScore to 0.10,
            volumeConsistencyScore to 0.07,
            volatilityQualityScore to 0.08,
            trendQualityScore to 0.08,
            historicalExpectancyScore to 0.11,
            recentHealthScore to 0.08,
            fillQualityScore to 0.08,
            holdabilityScore to 0.04,
        )
        val marketOpportunityScore = averageOf(
            rankingScore,
            recentHealthScore,
            maxOf(trendQualityScore, volatilityQualityScore),
        )
        val preferredHorizon = if (
            holdabilityScore >= policy.minHoldabilityForSwing &&
            trendQualityScore >= policy.minTrendScoreForSwing
        ) {
            TradingHorizon.SWING
        } else {
            TradingHorizon.TACTICAL
        }

        val rejectionReasons = buildList {
            if (quote.quoteVolume24h.toDoubleOrZero() < policy.minDailyQuoteVolumeIdr) add("Likuiditas harian terlalu rendah.")
            if (quote.spreadPct > policy.maxSpreadPct) add("Spread terlalu lebar.")
            if (quote.estimatedSlippagePct > policy.maxEstimatedSlippagePct) add("Estimasi slippage terlalu tinggi.")
            if (stabilityScore < policy.minOrderBookStabilityScore) add("Kualitas order book belum aman.")
            if (volumeConsistencyScore < policy.minRecentTradeActivityScore) add("Aktivitas trade terlalu tipis.")
            if (fillQualityScore < policy.minFillQualityScore) add("Kualitas fill memburuk.")
            if (historicalExpectancyScore < policy.minHistoricalExpectancyScore) add("Expectancy historis belum cukup sehat.")
            if (marketOpportunityScore < policy.minFeeAdjustedEdgeScore) add("Net edge setelah biaya belum layak.")
        }

        val pairTier = when {
            rejectionReasons.isNotEmpty() -> PairTier.TIER_C
            rankingScore >= policy.minTierAScore -> PairTier.TIER_A
            rankingScore >= policy.minTierBScore -> PairTier.TIER_B
            else -> PairTier.TIER_C
        }
        val allowed = pairTier != PairTier.TIER_C && rejectionReasons.isEmpty()

        return PairScore(
            pairId = quote.pairId,
            liquidityScore = liquidityScore,
            spreadScore = spreadScore,
            slippageScore = slippageScore,
            stabilityScore = stabilityScore,
            volumeConsistencyScore = volumeConsistencyScore,
            volatilityQualityScore = volatilityQualityScore,
            trendQualityScore = trendQualityScore,
            historicalExpectancyScore = historicalExpectancyScore,
            recentHealthScore = recentHealthScore,
            fillQualityScore = fillQualityScore,
            holdabilityScore = holdabilityScore,
            feeAdjustedEdgeScore = marketOpportunityScore,
            marketOpportunityScore = marketOpportunityScore,
            rankingScore = rankingScore,
            pairTier = pairTier,
            preferredHorizon = preferredHorizon,
            allowed = allowed,
            rejectionReasons = rejectionReasons,
        )
    }

    private fun deriveVolatilityQuality(quote: MarketQuote): Double {
        val explicit = quote.volatilityQualityScore.coerceIn(0.0, 1.0)
        if (explicit != 0.5 || quote.realizedVolatilityPct > 0.0) return explicit.takeIf { quote.realizedVolatilityPct <= 0.0 } ?: centeredScore(
            quote.realizedVolatilityPct,
            policy.idealVolatilityPct,
            policy.maxAcceptedVolatilityPct,
        )
        val proxyVolatility = quote.spreadPct + quote.estimatedSlippagePct + abs(quote.shortTermReturnPct * 0.35)
        return centeredScore(proxyVolatility, policy.idealVolatilityPct, policy.maxAcceptedVolatilityPct)
    }

    private fun deriveTrendQuality(quote: MarketQuote): Double {
        val explicit = quote.trendQualityScore.coerceIn(0.0, 1.0)
        if (explicit != 0.5 || quote.shortTermReturnPct != 0.0 || quote.mediumTermReturnPct != 0.0) {
            if (quote.shortTermReturnPct == 0.0 && quote.mediumTermReturnPct == 0.0) return explicit
            val directionalBoost = ((quote.shortTermReturnPct * 0.4) + (quote.mediumTermReturnPct * 0.6)) / 6.0
            return (0.5 + directionalBoost).coerceIn(0.0, 1.0)
        }
        return explicit
    }

    private fun deriveHoldabilityScore(
        quote: MarketQuote,
        trendQualityScore: Double,
        volatilityQualityScore: Double,
    ): Double {
        val explicit = quote.holdabilityScore.coerceIn(0.0, 1.0)
        if (explicit != 0.5) return explicit
        return averageOf(
            trendQualityScore,
            volatilityQualityScore,
            quote.fillQualityScore.coerceIn(0.0, 1.0),
            quote.orderBookStabilityScore.coerceIn(0.0, 1.0),
        )
    }

    private fun normalizeRatio(value: Double, baseline: Double, saturationMultiplier: Double): Double {
        if (baseline <= 0.0) return 0.0
        return (value / (baseline * saturationMultiplier)).coerceIn(0.0, 1.0)
    }

    private fun prefilterScore(quote: MarketQuote): Double {
        val liquidityScore = normalizeRatio(
            value = quote.quoteVolume24h.toDoubleOrZero(),
            baseline = policy.minDailyQuoteVolumeIdr,
            saturationMultiplier = 5.0,
        )
        val spreadScore = inverseThresholdScore(quote.spreadPct, policy.maxSpreadPct * 1.4)
        val slippageScore = inverseThresholdScore(quote.estimatedSlippagePct, policy.maxEstimatedSlippagePct * 1.4)
        val tradeFlowScore = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        val stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0)
        return weightedAverage(
            liquidityScore to 0.32,
            spreadScore to 0.22,
            slippageScore to 0.22,
            tradeFlowScore to 0.14,
            stabilityScore to 0.10,
        )
    }

    private fun inverseThresholdScore(value: Double, maxAllowed: Double): Double {
        if (maxAllowed <= 0.0) return 0.0
        return (1.0 - (value / maxAllowed)).coerceIn(0.0, 1.0)
    }

    private fun centeredScore(value: Double, ideal: Double, maxAccepted: Double): Double {
        if (value <= 0.0 || ideal <= 0.0 || maxAccepted <= ideal) return 0.5
        val distance = abs(value - ideal)
        val bandwidth = (maxAccepted - ideal).coerceAtLeast(ideal * 0.5)
        return (1.0 - (distance / bandwidth)).coerceIn(0.0, 1.0)
    }

    private fun weightedAverage(vararg entries: Pair<Double, Double>): Double {
        val totalWeight = entries.sumOf { it.second }.coerceAtLeast(0.000001)
        return (entries.sumOf { it.first.coerceIn(0.0, 1.0) * it.second } / totalWeight).coerceIn(0.0, 1.0)
    }

    private fun averageOf(vararg values: Double): Double {
        if (values.isEmpty()) return 0.0
        return values.map { it.coerceIn(0.0, 1.0) }.average().coerceIn(0.0, 1.0)
    }

    private fun pairRankingComparator() = compareByDescending<PairScore> { it.pairTier == PairTier.TIER_A }
        .thenByDescending { it.rankingScore }
        .thenByDescending { it.marketOpportunityScore }
        .thenByDescending { it.fillQualityScore }
        .thenByDescending { it.historicalExpectancyScore }
        .thenByDescending { it.spreadScore + it.slippageScore }
}
