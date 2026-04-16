package com.kibot.core

import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PairTier
import com.kibot.shared.models.TradingHorizon
import com.kibot.shared.models.BucketType
import com.kibot.core.data.CoinUniverse
import com.kibot.core.data.CoinTier
import com.kibot.core.data.CorrelationGroup
import kotlin.math.abs

class PairSelector(
    private val policy: PairSelectionPolicy = PairSelectionPolicy(),
    private val chartAnalyzer: ChartAnalyzer = ChartAnalyzer(),
    private val coinProfiler: CoinProfiler = CoinProfiler(policy),
) : SelectionStrategy {
    fun rank(quotes: List<MarketQuote>): List<PairScore> = rank(quotes, PairSelectionContext())

    fun shortlist(quotes: List<MarketQuote>): List<PairScore> = shortlist(quotes, PairSelectionContext())

    override fun rank(
        quotes: List<MarketQuote>,
        context: PairSelectionContext,
    ): List<PairScore> {
        val candidates = prefilter(quotes, context)
        return candidates.map { scoreQuote(it, quotes, context) }.sortedWith(pairRankingComparator())
    }

    override fun shortlist(
        quotes: List<MarketQuote>,
        context: PairSelectionContext,
    ): List<PairScore> {
        return rank(quotes, context)
            .filter { it.allowed }
            .take(policy.shortlistSize)
    }

    private fun prefilter(
        quotes: List<MarketQuote>,
        context: PairSelectionContext,
    ): List<MarketQuote> {
        val eligibleQuotes = quotes.filterNot(::isDormantStablePair)
        if (eligibleQuotes.isEmpty()) return emptyList()
        val poolSize = policy.prefilterCandidatePoolSize.coerceAtLeast(policy.shortlistSize)
        if (eligibleQuotes.size <= poolSize) return eligibleQuotes

        // DUAL ENGINE: Barbarian Anomaly Engine bypasses spread checks
        if (context.bypassSpreadCheck) {
            // Barbarian mode: skip all spread/slippage guardrails, only check price band
            return eligibleQuotes
                .filter { passesPriceBandGate(it, context) }
                .sortedByDescending(::prefilterScore)
                .take(poolSize)
        }

        // Normal filtering with spread checks (Macro Follower Engine or legacy mode)
        val lenientCandidates = eligibleQuotes.asSequence()
            .filter { quote ->
                val entry = CoinUniverse.byIndodax[quote.pairId.value.lowercase()]
                val minVol = entry?.minVolumeIdr?.toDouble() ?: policy.minDailyQuoteVolumeIdr
                val maxSpread = entry?.maxSpreadPct ?: policy.maxSpreadPct
                
                passesPriceBandGate(quote, context) &&
                quote.spreadPct <= context.maxSpreadPct.coerceAtLeast(0.0) &&
                (
                    quote.quoteVolume24h.toDoubleOrZero() >= minVol * 0.50 ||
                        isSmallCapitalOverrideEligible(
                            quote = quote,
                            stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0),
                            volumeConsistencyScore = quote.recentTradeActivityScore.coerceIn(0.0, 1.0),
                            fillQualityScore = quote.fillQualityScore.coerceIn(0.0, 1.0),
                        )
                    ) &&
                    quote.spreadPct <= maxSpread * 1.2 &&
                    quote.estimatedSlippagePct <= policy.maxEstimatedSlippagePct * 1.2 &&
                    quote.orderBookStabilityScore >= policy.minOrderBookStabilityScore * 0.7
            }
            .sortedByDescending(::prefilterScore)
            .take(poolSize)
            .toList()

        if (lenientCandidates.isNotEmpty()) return lenientCandidates

        return eligibleQuotes
            .filter { passesPriceBandGate(it, context) && it.spreadPct <= context.maxSpreadPct.coerceAtLeast(0.0) }
            .sortedByDescending(::prefilterScore)
            .take(poolSize)
    }

    private fun scoreQuote(
        quote: MarketQuote,
        marketUniverse: List<MarketQuote>,
        context: PairSelectionContext,
    ): PairScore {
        val chartAssessment = chartAnalyzer.analyzeQuoteSnapshot(quote)
        val profileAssessment = coinProfiler.assess(
            quote = quote,
            referenceQuotes = marketUniverse,
        )
        val chartPatternsScore = chartAssessment.netEntryScore
        val momentumAccelerationScore = deriveMomentumAccelerationScore(quote)
        val spreadGuardPct = context.maxSpreadPct.coerceAtLeast(0.0)
        val capitalBandIdr = capitalBandIdr(context)
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
        // DUAL ENGINE: Barbarian Engine bypasses spread scoring - use neutral score
        val spreadScore = if (context.bypassSpreadCheck) {
            0.70  // Neutral score - don't penalize spread
        } else {
            inverseThresholdScore(quote.spreadPct, spreadGuardPct.coerceAtLeast(policy.maxSpreadPct))
        }
        val slippageScore = if (context.bypassSpreadCheck) {
            0.70  // Neutral score - don't penalize slippage
        } else {
            inverseThresholdScore(quote.estimatedSlippagePct, policy.maxEstimatedSlippagePct)
        }
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
        // DUAL ENGINE: Barbarian Engine bypasses spread rejection
        val spreadRejected = if (context.bypassSpreadCheck) {
            false  // Never reject on spread for Barbarian
        } else {
            quote.spreadPct > spreadGuardPct
        }
        val priceBandAllowed = passesPriceBandGate(quote, context)
        val sectorLeadFamily = context.leadSectorFamily?.lowercase()
        val quoteFamily = correlationFamily(quote.pairId)
        val leadLagAffinity = when {
            !context.leadLagEnabled -> 0.0
            sectorLeadFamily != null && sectorLeadFamily == quoteFamily -> 0.18
            context.leadPairId != null && context.leadPairId.equals(quote.pairId.value, ignoreCase = true) -> 0.26
            context.leadMomentumScore >= 0.72 && quote.sectorMomentumScore >= 0.60 -> 0.08
            else -> 0.0
        }
        val sectorHotnessBias = when {
            !context.leadLagEnabled -> 0.0
            context.leadSectorHotnessScore >= 0.88 &&
                sectorLeadFamily != null &&
                sectorLeadFamily == quoteFamily -> 0.16
            context.leadSectorHotnessScore >= 0.76 &&
                sectorLeadFamily != null &&
                sectorLeadFamily == quoteFamily -> 0.11
            context.leadSectorHotnessScore >= 0.60 &&
                quote.sectorMomentumScore >= 0.58 -> 0.06
            else -> 0.0
        }
        val volumeVelocityBias = when {
            !context.leadLagEnabled -> 0.0
            context.urgentEntryMode && quote.recentTradeActivityScore >= 0.80 && quote.tradeCount24h >= 250 -> 0.10
            context.urgentEntryMode && quote.recentTradeActivityScore >= 0.70 -> 0.06
            context.leadVolumeVelocityScore >= 0.80 && quote.recentTradeActivityScore >= 0.66 -> 0.04
            else -> 0.0
        }
        val urgentLeadLagMatch = context.urgentEntryMode &&
            priceBandAllowed &&
            quote.spreadPct <= spreadGuardPct &&
            (
                leadLagAffinity >= 0.10 ||
                    sectorHotnessBias >= 0.06 ||
                    volumeVelocityBias >= 0.04
                )
        val urgentEntryBias = if (context.urgentEntryMode && priceBandAllowed) {
            val band = capitalBandIdr
            val nominalPrice = quote.midPrice.toDoubleOrZero().coerceAtLeast(0.0)
            if (band > 0.0 && nominalPrice > 0.0) {
                val affordability = (1.0 - (nominalPrice / band).coerceIn(0.0, 1.0)) * 0.10
                affordability + if (quote.spreadPct <= spreadGuardPct) 0.04 else 0.0
            } else {
                0.0
            }
        } else {
            0.0
        }
        val lowPriceBias = if (context.userBalanceIdr > 0.0 && priceBandAllowed) {
            val band = capitalBandIdr
            val nominalPrice = quote.midPrice.toDoubleOrZero().coerceAtLeast(0.0)
            if (band > 0.0 && nominalPrice > 0.0) {
                (1.0 - (nominalPrice / band).coerceIn(0.0, 1.0)) * if (context.urgentEntryMode) 0.28 else 0.18
            } else {
                0.0
            }
        } else {
            0.0
        }
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
        val earlyBreakoutEligible =
            quote.shortTermReturnPct >= policy.speculativeMinShortTermReturnPct &&
                quote.mediumTermReturnPct >= policy.speculativeMinMediumTermReturnPct &&
                momentumAccelerationScore >= 0.74 &&
                volumeConsistencyScore >= policy.speculativeMinTradeActivityScore &&
                fillQualityScore >= 0.22 &&
                quote.spreadPct <= (policy.smallCapitalMaxSpreadPct * 1.12) &&
                quote.estimatedSlippagePct <= (policy.smallCapitalMaxSlippagePct * 1.10)
        
        val pairWinRate = context.pairHistoricalWinRate[quote.pairId.value.lowercase()] ?: 0.0
        val pairLossCount = context.pairHistoricalLossCount[quote.pairId.value.lowercase()] ?: 0
        val isFakePump = quote.shortTermReturnPct > 8.0 && pairLossCount >= 2
        val pumpWinBoost = if (quote.shortTermReturnPct > 5.0 && pairWinRate > 0.5) 0.15 else 0.0

        val rankingScoreBase = weightedAverage(
            liquidityScore to 0.06,
            depthScore to 0.06,
            spreadScore to 0.07,
            slippageScore to 0.07,
            stabilityScore to 0.07,
            volumeConsistencyScore to 0.07,
            volatilityQualityScore to 0.06,
            trendQualityScore to 0.07,
            momentumAccelerationScore to 0.10,
            historicalExpectancyScore to 0.07,
            recentHealthScore to 0.06,
            fillQualityScore to 0.05,
            holdabilityScore to 0.05,
            chartPatternsScore to 0.09,
            profileAssessment.structureScore to 0.08,
            profileAssessment.microstructureScore to 0.07,
            profileAssessment.contextScore to 0.06,
            profileAssessment.executionQualityScore to 0.05,
            profileAssessment.progressiveScore to 0.05,
            (1.0 - profileAssessment.deadChartScore) to 0.03,
            (1.0 - profileAssessment.statisticalStretchScore) to 0.03,
            profileAssessment.smartMoneyScore to 0.03,
            sectorHotnessBias to 0.04,
            volumeVelocityBias to 0.04,
        )
        val rankingScore = (
            rankingScoreBase -
                if (stagnantPair) 0.12 else 0.0 +
                if (earlyBreakoutEligible) 0.08 else 0.0 -
                (profileAssessment.toxicityScore * 0.14) -
                (profileAssessment.statisticalStretchScore * 0.10) +
                leadLagAffinity +
                lowPriceBias +
                urgentEntryBias +
                pumpWinBoost
            ).coerceIn(0.0, 1.0)
        val marketOpportunityScore = averageOf(
            rankingScore,
            recentHealthScore,
            maxOf(trendQualityScore, volatilityQualityScore),
            momentumAccelerationScore,
            (1.0 - chartAssessment.exhaustionRiskScore),
            profileAssessment.contextScore,
            profileAssessment.progressiveScore,
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

        // DUAL ENGINE: Barbarian Engine bypasses most rejection reasons
        val rejectionReasons = if (context.bypassSpreadCheck && context.bypassVetoService) {
            // Barbarian mode: minimal rejections - only critical blockers
            buildList {
                if (dormantStablePair) add("Pair datar/stable tidak dipakai untuk growth trading.")
                if (!priceBandAllowed) add("Harga pair melewati band saldo per basket.")
                // Allow everything else - let the pump speak!
            }
        } else {
            // Normal mode (Macro Follower or legacy): full guardrails
            buildList {
                val minimumHistoricalExpectancyScore = if (speculativePocket) {
                    policy.speculativeMinHistoricalExpectancyScore
                } else if (earlyBreakoutEligible) {
                    minOf(policy.minHistoricalExpectancyScore, 0.18)
                } else {
                    policy.minHistoricalExpectancyScore
                }
                if (dormantStablePair) add("Pair datar/stable tidak dipakai untuk growth trading.")
                if (stagnantPair) add("Pergerakan pair terlalu datar untuk mode agresif.")
                if (spreadRejected) add("Spread melewati batas guardrail.")
                if (
                    quote.quoteVolume24h.toDoubleOrZero() < policy.minDailyQuoteVolumeIdr &&
                    !smallCapitalEligible &&
                    !earlyBreakoutEligible
                ) {
                    add("Likuiditas harian terlalu rendah.")
                }
                val maxSpread = if (earlyBreakoutEligible) policy.smallCapitalMaxSpreadPct else policy.maxSpreadPct
                val maxSlippage = if (earlyBreakoutEligible) policy.smallCapitalMaxSlippagePct else policy.maxEstimatedSlippagePct
                val minStability = if (earlyBreakoutEligible) policy.minOrderBookStabilityScore * 0.70 else policy.minOrderBookStabilityScore
                val minTradeActivity = if (earlyBreakoutEligible) policy.minRecentTradeActivityScore * 0.75 else policy.minRecentTradeActivityScore
                // Removed redundant spread check on line 308 - policy.hardSpreadVetoPct is too strict
                if (quote.spreadPct > maxSpread) add("Spread terlalu lebar.")
                if (quote.estimatedSlippagePct > maxSlippage) add("Estimasi slippage terlalu tinggi.")
                if (stabilityScore < minStability) add("Kualitas order book belum aman.")
                if (volumeConsistencyScore < minTradeActivity) add("Aktivitas trade terlalu tipis.")
                if (
                    quote.quoteVolume24h.toDoubleOrZero() < policy.minDailyQuoteVolumeIdr &&
                    depthScore < if (earlyBreakoutEligible) 0.28 else 0.55
                ) {
                    add("Depth order book belum cukup aman untuk modal kecil.")
                }
                if (!priceBandAllowed) add("Harga pair melewati band saldo per basket.")
                if (fillQualityScore < if (earlyBreakoutEligible) policy.minFillQualityScore * 0.72 else policy.minFillQualityScore) {
                    add("Kualitas fill memburuk.")
                }
                if (historicalExpectancyScore < minimumHistoricalExpectancyScore) add("Expectancy historis belum cukup sehat.")
                if (feeAdjustedEdgePct < if (earlyBreakoutEligible) policy.minFeeAdjustedEdgeScore * 0.65 else policy.minFeeAdjustedEdgeScore) {
                    add("Net edge setelah biaya belum layak.")
                }
                if (profileAssessment.deadChartScore >= 0.72 && !speculativePocket) {
                    add("Chart mati/zombie, progres harga tidak sehat.")
                }
                if (profileAssessment.progressiveScore < 0.34 && profileAssessment.contextScore < 0.40) {
                    add("Belum ada progres struktur dan konteks yang cukup kuat.")
                }
                if (profileAssessment.toxicityScore >= policy.toxicFlowCautionScore) {
                    add("Pair sedang toxic, entry harus dihindari dulu.")
                }
                if (leadLagAffinity >= 0.18) {
                    add("Lead-lag sector cocok dengan sinyal Kinance.")
                }
                if (context.urgentEntryMode) {
                    add("PEKA mode aktif: prioritas pada kandidat murah yang masih nyambung ke lead sector.")
                }
                if (profileAssessment.statisticalStretchScore >= 0.82) {
                    add("Harga sudah terlalu jauh dari pusat statistik intraday.")
                }
                if (isFakePump) {
                    add("Pump terdeteksi historis buruk (Fake Pump).")
                }
                if (!urgentLeadLagMatch) {
                    addAll(profileAssessment.rejectionReasons)
                    addAll(chartAssessment.vetoReasons)
                }
            }
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
            structureScore = profileAssessment.structureScore,
            microstructureScore = profileAssessment.microstructureScore,
            contextScore = profileAssessment.contextScore,
            toxicityScore = profileAssessment.toxicityScore,
            executionQualityScore = profileAssessment.executionQualityScore,
            progressiveScore = profileAssessment.progressiveScore,
            deadChartScore = profileAssessment.deadChartScore,
            kellyFraction = profileAssessment.kellyFraction,
            profileLabel = profileAssessment.archetype.name.lowercase(),
            statisticalStretchScore = profileAssessment.statisticalStretchScore,
            smartMoneyScore = profileAssessment.smartMoneyScore,
            bucketType = classifyBucket(quote, rankingScore),
        )
    }

    private fun passesPriceBandGate(
        quote: MarketQuote,
        context: PairSelectionContext,
    ): Boolean {
        val price = quote.midPrice.toDoubleOrZero()
        if (price <= 0.0) return false
        val balanceBand = capitalBandIdr(context)
        return balanceBand <= 0.0 || price <= balanceBand
    }

    private fun capitalBandIdr(context: PairSelectionContext): Double {
        val freeCash = context.availableCashIdr.takeIf { it > 0.0 } ?: 0.0
        val cashIsExecutable = freeCash >= context.minimumExecutableNotionalIdr
        val base = when {
            cashIsExecutable -> freeCash
            context.userBalanceIdr > 0.0 -> context.userBalanceIdr
            else -> freeCash
        }
        return base / context.basketCount.coerceAtLeast(1).toDouble()
    }

    private fun correlationFamily(pairId: PairId): String {
        val entry = CoinUniverse.byIndodax[pairId.value.lowercase()]
        if (entry != null) return entry.correlationGroup.name.lowercase()
        return pairId.value.substringBefore('_').lowercase()
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
            val directionalScore = (0.5 + (((quote.shortTermReturnPct * 0.4) + (quote.mediumTermReturnPct * 0.6)) / 6.0))
                .coerceIn(0.0, 1.0)
            val trendAlignmentBoost = when {
                quote.shortTermReturnPct > 0.0 && quote.mediumTermReturnPct > 0.0 -> 0.04
                quote.shortTermReturnPct < 0.0 && quote.mediumTermReturnPct < 0.0 -> -0.09
                else -> 0.0
            }
            return (directionalScore + trendAlignmentBoost).coerceIn(0.0, 1.0)
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
    
    private fun classifyBucket(
        quote: MarketQuote,
        finalScore: Double,
    ): BucketType {
        return when {
            // Aggressive indicators
            quote.shortTermReturnPct > 10.0 -> BucketType.AGGRESSIVE
            quote.realizedVolatilityPct > 8.0 -> BucketType.AGGRESSIVE
            quote.localAnomalyScore > 0.7 -> BucketType.AGGRESSIVE
            
            // Stable indicators
            finalScore >= 0.65 && quote.realizedVolatilityPct < 5.0 -> BucketType.STABLE
            
            // Default to stable (safer)
            else -> BucketType.STABLE
        }
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
        val profileAssessment = coinProfiler.assess(quote)
        return weightedAverage(
            liquidityScore to 0.20,
            depthScore to 0.12,
            spreadScore to 0.13,
            slippageScore to 0.13,
            tradeFlowScore to 0.12,
            momentumScore to 0.12,
            stabilityScore to 0.03,
            profileAssessment.structureScore to 0.08,
            profileAssessment.microstructureScore to 0.05,
            (1.0 - profileAssessment.deadChartScore) to 0.02,
        )
    }

    private fun deriveMomentumAccelerationScore(quote: MarketQuote): Double {
        val shortTermRaw = quote.shortTermReturnPct
        val shortTermScore = (shortTermRaw / 18.0).coerceIn(0.0, 1.0)
        val mediumTermScore = (quote.mediumTermReturnPct / 7.0).coerceIn(0.0, 1.0)
        val downsidePenalty = when {
            shortTermRaw <= -8.0 && quote.mediumTermReturnPct <= -2.0 -> 0.18
            shortTermRaw <= -4.5 && quote.mediumTermReturnPct <= -1.0 -> 0.10
            shortTermRaw <= -2.0 -> 0.05
            else -> 0.0
        }
        return weightedAverage(
            shortTermScore to 0.42,
            mediumTermScore to 0.28,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.18,
            quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.12,
        ).let { (it - downsidePenalty).coerceIn(0.0, 1.0) }
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
        val entry = CoinUniverse.byIndodax[quote.pairId.value.lowercase()]
        if (entry != null) {
            return entry.correlationGroup == CorrelationGroup.STABLECOIN
        }
        
        // Fallback policy lama agar unit test (mock pairs) tidak crash
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

    private fun pairRankingComparator() = compareByDescending<PairScore> { it.allowed }
        .thenByDescending { it.pairTier == PairTier.TIER_A }
        .thenByDescending { it.speculativePocket }
        .thenByDescending { it.feeAdjustedEdgeScore }
        .thenByDescending { it.rankingScore }
        .thenByDescending { it.marketOpportunityScore }
        .thenByDescending { it.progressiveScore }
        .thenByDescending { it.contextScore }
        .thenByDescending { 1.0 - it.toxicityScore }
        .thenByDescending { it.fillQualityScore }
        .thenByDescending { it.historicalExpectancyScore }
        .thenByDescending { it.spreadScore + it.slippageScore }
}
