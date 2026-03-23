package com.kibot.core

import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
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
        val eligibleQuotes = quotes.filterNot(::isDormantStablePair)
        if (eligibleQuotes.isEmpty()) return emptyList()
        val poolSize = policy.prefilterCandidatePoolSize.coerceAtLeast(policy.shortlistSize)
        if (eligibleQuotes.size <= poolSize) return eligibleQuotes

        val lenientCandidates = eligibleQuotes.asSequence()
            .filter { quote ->
                (
                    quote.quoteVolume24h.toDoubleOrZero() >= policy.minDailyQuoteVolumeIdr * 0.25 ||
                        isSmallCapitalOverrideEligible(
                            quote = quote,
                            stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0),
                            volumeConsistencyScore = quote.recentTradeActivityScore.coerceIn(0.0, 1.0),
                            fillQualityScore = quote.fillQualityScore.coerceIn(0.0, 1.0),
                        )
                    ) &&
                    quote.spreadPct <= policy.maxSpreadPct * 1.6 &&
                    quote.estimatedSlippagePct <= policy.maxEstimatedSlippagePct * 1.6 &&
                    quote.orderBookStabilityScore >= policy.minOrderBookStabilityScore * 0.6
            }
            .sortedByDescending(::prefilterScore)
            .take(poolSize)
            .toList()

        if (lenientCandidates.isNotEmpty()) return lenientCandidates

        return eligibleQuotes
            .sortedByDescending(::prefilterScore)
            .take(poolSize)
    }

    private fun scoreQuote(quote: MarketQuote): PairScore {
        val momentumAccelerationScore = deriveMomentumAccelerationScore(quote)
        val liquidityScore = normalizeRatio(
            value = quote.quoteVolume24h.toDoubleOrZero(),
            baseline = policy.minDailyQuoteVolumeIdr,
            saturationMultiplier = 5.0,
        )
        val depthScore = normalizeRatio(
            value = minOf(quote.bidDepthTop5Idr.toDoubleOrZero(), quote.askDepthTop5Idr.toDoubleOrZero()),
            baseline = policy.smallCapitalMinTop5DepthIdr,
            saturationMultiplier = 5.0,
        )
        val spreadScore = inverseThresholdScore(quote.spreadPct, policy.maxSpreadPct)
        val slippageScore = inverseThresholdScore(quote.estimatedSlippagePct, policy.maxEstimatedSlippagePct)
        val stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0)
        val tradeCountScore = quote.tradeCount24h
            .takeIf { it > 0 }
            ?.toDouble()
            ?.let {
                normalizeRatio(
                    value = it,
                    baseline = policy.smallCapitalMinTradeCount24h.toDouble(),
                    saturationMultiplier = 4.0,
                )
            }
            ?: quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        val volumeConsistencyScore = averageOf(
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0),
            tradeCountScore,
        )
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
            depthScore,
        )
        val holdabilityScore = deriveHoldabilityScore(quote, trendQualityScore, volatilityQualityScore)
        val smallCapitalEligible = isSmallCapitalOverrideEligible(
            quote = quote,
            stabilityScore = stabilityScore,
            volumeConsistencyScore = volumeConsistencyScore,
            fillQualityScore = fillQualityScore,
        )
        val speculativePocket = isSpeculativePocketEligible(
            quote = quote,
            depthScore = depthScore,
            stabilityScore = stabilityScore,
            volumeConsistencyScore = volumeConsistencyScore,
            historicalExpectancyScore = historicalExpectancyScore,
            fillQualityScore = fillQualityScore,
        )
        val stagnantPair = !speculativePocket &&
            abs(quote.shortTermReturnPct) <= policy.stagnantShortTermReturnPctMax &&
            abs(quote.mediumTermReturnPct) <= policy.stagnantMediumTermReturnPctMax &&
            quote.recentTradeActivityScore < 0.72
        val rankingScoreBase = weightedAverage(
            liquidityScore to 0.10,
            depthScore to 0.10,
            spreadScore to 0.12,
            slippageScore to 0.12,
            stabilityScore to 0.10,
            volumeConsistencyScore to 0.07,
            volatilityQualityScore to 0.08,
            trendQualityScore to 0.08,
            momentumAccelerationScore to 0.08,
            historicalExpectancyScore to 0.11,
            recentHealthScore to 0.08,
            fillQualityScore to 0.08,
            holdabilityScore to 0.06,
        )
        val rankingScore = (rankingScoreBase - if (stagnantPair) 0.12 else 0.0).coerceIn(0.0, 1.0)
        val marketOpportunityScore = averageOf(
            rankingScore,
            recentHealthScore,
            maxOf(trendQualityScore, volatilityQualityScore),
            momentumAccelerationScore,
        )
        val preferredHorizon = if (
            !smallCapitalEligible &&
            holdabilityScore >= policy.minHoldabilityForSwing &&
            trendQualityScore >= policy.minTrendScoreForSwing
        ) {
            TradingHorizon.SWING
        } else {
            TradingHorizon.TACTICAL
        }
        val grossEdgePct = deriveGrossEdgePct(
            quote = quote,
            rankingScore = rankingScore,
            recentHealthScore = recentHealthScore,
            historicalExpectancyScore = historicalExpectancyScore,
            fillQualityScore = fillQualityScore,
            trendQualityScore = trendQualityScore,
        )
        val roundTripCostPct = estimateRoundTripCostPct(
            quote = quote,
            speculativePocket = speculativePocket,
        )
        val feeAdjustedEdgePct = grossEdgePct - roundTripCostPct
        val dormantStablePair = isDormantStablePair(quote)

        val rejectionReasons = buildList {
            val minimumHistoricalExpectancyScore = if (speculativePocket) {
                policy.speculativeMinHistoricalExpectancyScore
            } else {
                policy.minHistoricalExpectancyScore
            }
            if (dormantStablePair) add("Pair datar/stable tidak dipakai untuk growth trading.")
            if (stagnantPair) add("Pergerakan pair terlalu datar untuk mode agresif.")
            if (quote.quoteVolume24h.toDoubleOrZero() < policy.minDailyQuoteVolumeIdr && !smallCapitalEligible) {
                add("Likuiditas harian terlalu rendah.")
            }
            if (quote.spreadPct > policy.maxSpreadPct) add("Spread terlalu lebar.")
            if (quote.estimatedSlippagePct > policy.maxEstimatedSlippagePct) add("Estimasi slippage terlalu tinggi.")
            if (stabilityScore < policy.minOrderBookStabilityScore) add("Kualitas order book belum aman.")
            if (volumeConsistencyScore < policy.minRecentTradeActivityScore) add("Aktivitas trade terlalu tipis.")
            if (quote.quoteVolume24h.toDoubleOrZero() < policy.minDailyQuoteVolumeIdr && depthScore < 0.55) {
                add("Depth order book belum cukup aman untuk modal kecil.")
            }
            if (fillQualityScore < policy.minFillQualityScore) add("Kualitas fill memburuk.")
            if (historicalExpectancyScore < minimumHistoricalExpectancyScore) add("Expectancy historis belum cukup sehat.")
            if (feeAdjustedEdgePct < policy.minFeeAdjustedEdgeScore) add("Net edge setelah biaya belum layak.")
        }

        val pairTier = when {
            rejectionReasons.isNotEmpty() -> PairTier.TIER_C
            speculativePocket -> PairTier.TIER_B
            smallCapitalEligible -> PairTier.TIER_B
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
            feeAdjustedEdgeScore = feeAdjustedEdgePct,
            marketOpportunityScore = marketOpportunityScore,
            rankingScore = rankingScore,
            pairTier = pairTier,
            preferredHorizon = preferredHorizon,
            speculativePocket = speculativePocket,
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

    private fun deriveGrossEdgePct(
        quote: MarketQuote,
        rankingScore: Double,
        recentHealthScore: Double,
        historicalExpectancyScore: Double,
        fillQualityScore: Double,
        trendQualityScore: Double,
    ): Double {
        val baseOpportunityPct = ((rankingScore - 0.44).coerceAtLeast(0.0) * 2.5)
        val expectancyAssistPct = maxOf(historicalExpectancyScore - 0.50, 0.0) * 0.85
        val qualityAssistPct = maxOf(fillQualityScore - 0.50, 0.0) * 0.55
        val momentumAssistPct = (
            maxOf(quote.shortTermReturnPct, 0.0) * 0.28 +
                maxOf(quote.mediumTermReturnPct, 0.0) * 0.18 +
                maxOf(trendQualityScore - 0.50, 0.0) * 0.80 +
                maxOf(recentHealthScore - 0.50, 0.0) * 0.45
            )
        val explosiveBreakoutBonusPct = when {
            quote.shortTermReturnPct >= 18.0 &&
                quote.mediumTermReturnPct >= 6.0 &&
                quote.recentTradeActivityScore >= 0.60 &&
                trendQualityScore >= 0.62 &&
                fillQualityScore >= 0.60 -> 0.88
            quote.shortTermReturnPct >= 8.0 &&
                quote.mediumTermReturnPct >= 3.0 &&
                quote.recentTradeActivityScore >= 0.54 &&
                fillQualityScore >= 0.58 -> 0.48
            quote.shortTermReturnPct >= 4.5 &&
                quote.mediumTermReturnPct >= 1.5 &&
                quote.recentTradeActivityScore >= 0.52 &&
                fillQualityScore >= 0.56 -> 0.26
            else -> 0.0
        }
        return (baseOpportunityPct + expectancyAssistPct + qualityAssistPct + momentumAssistPct + explosiveBreakoutBonusPct)
            .coerceIn(0.0, policy.strongNetEdgePct * 2.0)
    }

    private fun estimateRoundTripCostPct(
        quote: MarketQuote,
        speculativePocket: Boolean,
    ): Double {
        val feeCostPct = if (speculativePocket) {
            policy.estimatedTakerRoundTripCostPct
        } else {
            policy.estimatedMakerRoundTripCostPct
        }
        val spreadCostPct = quote.spreadPct.coerceAtLeast(0.0)
        val slippageCostPct = quote.estimatedSlippagePct.coerceAtLeast(0.0) * if (speculativePocket) 0.80 else 0.60
        val stabilityPenaltyPct = ((1.0 - quote.orderBookStabilityScore.coerceIn(0.0, 1.0)) * 0.18)
        return feeCostPct + spreadCostPct + slippageCostPct + stabilityPenaltyPct + policy.feeSafetyBufferPct
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
        val depthScore = normalizeRatio(
            value = minOf(quote.bidDepthTop5Idr.toDoubleOrZero(), quote.askDepthTop5Idr.toDoubleOrZero()),
            baseline = policy.smallCapitalMinTop5DepthIdr,
            saturationMultiplier = 5.0,
        )
        val spreadScore = inverseThresholdScore(quote.spreadPct, policy.maxSpreadPct * 1.4)
        val slippageScore = inverseThresholdScore(quote.estimatedSlippagePct, policy.maxEstimatedSlippagePct * 1.4)
        val tradeFlowScore = averageOf(
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0),
            quote.tradeCount24h
                .takeIf { it > 0 }
                ?.toDouble()
                ?.let {
                    normalizeRatio(
                        value = it,
                        baseline = policy.smallCapitalMinTradeCount24h.toDouble(),
                        saturationMultiplier = 4.0,
                    )
                }
                ?: quote.recentTradeActivityScore.coerceIn(0.0, 1.0),
        )
        val stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0)
        val momentumScore = deriveMomentumAccelerationScore(quote)
        return weightedAverage(
            liquidityScore to 0.26,
            depthScore to 0.16,
            spreadScore to 0.17,
            slippageScore to 0.17,
            tradeFlowScore to 0.14,
            momentumScore to 0.16,
            stabilityScore to 0.04,
        )
    }

    private fun deriveMomentumAccelerationScore(quote: MarketQuote): Double {
        val shortTermScore = (quote.shortTermReturnPct / 18.0).coerceIn(0.0, 1.0)
        val mediumTermScore = (quote.mediumTermReturnPct / 7.0).coerceIn(0.0, 1.0)
        return weightedAverage(
            shortTermScore to 0.42,
            mediumTermScore to 0.28,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.18,
            quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.12,
        )
    }

    private fun isSmallCapitalOverrideEligible(
        quote: MarketQuote,
        stabilityScore: Double,
        volumeConsistencyScore: Double,
        fillQualityScore: Double,
    ): Boolean {
        val top5DepthIdr = minOf(quote.bidDepthTop5Idr.toDoubleOrZero(), quote.askDepthTop5Idr.toDoubleOrZero())
        return quote.quoteVolume24h.toDoubleOrZero() >= policy.smallCapitalMinDailyQuoteVolumeIdr &&
            top5DepthIdr >= policy.smallCapitalMinTop5DepthIdr &&
            quote.tradeCount24h >= policy.smallCapitalMinTradeCount24h &&
            quote.spreadPct <= policy.smallCapitalMaxSpreadPct &&
            quote.estimatedSlippagePct <= policy.smallCapitalMaxSlippagePct &&
            stabilityScore >= maxOf(0.50, policy.minOrderBookStabilityScore) &&
            volumeConsistencyScore >= maxOf(0.52, policy.minRecentTradeActivityScore) &&
            fillQualityScore >= maxOf(0.54, policy.minFillQualityScore)
    }

    private fun isSpeculativePocketEligible(
        quote: MarketQuote,
        depthScore: Double,
        stabilityScore: Double,
        volumeConsistencyScore: Double,
        historicalExpectancyScore: Double,
        fillQualityScore: Double,
    ): Boolean {
        return quote.shortTermReturnPct in policy.speculativeMinShortTermReturnPct..policy.speculativeMaxShortTermReturnPct &&
            quote.mediumTermReturnPct >= policy.speculativeMinMediumTermReturnPct &&
            quote.recentTradeActivityScore >= policy.speculativeMinTradeActivityScore &&
            depthScore >= policy.speculativeMinDepthScore &&
            stabilityScore >= 0.56 &&
            volumeConsistencyScore >= 0.58 &&
            historicalExpectancyScore >= policy.speculativeMinHistoricalExpectancyScore &&
            fillQualityScore >= 0.56 &&
            quote.spreadPct <= policy.smallCapitalMaxSpreadPct &&
            quote.estimatedSlippagePct <= policy.smallCapitalMaxSlippagePct
    }

    private fun isDormantStablePair(quote: MarketQuote): Boolean {
        val assets = quote.pairId.assets()
        if (assets.quoteAsset != "idr") return false
        if (quote.pairId.value.lowercase() in policy.blockedBaseAssets.map { "${it}_idr" }.toSet()) return true
        return assets.baseAsset in policy.blockedBaseAssets
    }

    private data class PairParts(
        val baseAsset: String,
        val quoteAsset: String,
    )

    private fun PairId.assets(): PairParts {
        val parts = value.lowercase().split("_")
        return if (parts.size == 2) {
            PairParts(parts[0], parts[1])
        } else {
            PairParts(value.lowercase(), "idr")
        }
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
        .thenByDescending { it.speculativePocket }
        .thenByDescending { it.rankingScore }
        .thenByDescending { it.marketOpportunityScore }
        .thenByDescending { it.fillQualityScore }
        .thenByDescending { it.historicalExpectancyScore }
        .thenByDescending { it.spreadScore + it.slippageScore }
}
