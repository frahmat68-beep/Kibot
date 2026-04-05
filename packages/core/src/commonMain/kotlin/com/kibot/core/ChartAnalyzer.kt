package com.kibot.core

import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fast chart heuristics for KiBot/KiDax/Kinance.
 *
 * The analyzer is intentionally lightweight so it can be evaluated every cycle
 * without waiting on heavyweight AI inference.
 */
class ChartAnalyzer(
    private val config: ChartAnalysisConfig = ChartAnalysisConfig(),
) {

    data class Candle(
        val timestamp: Instant,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    ) {
        val bodySize = abs(close - open)
        val upperShadow = high - max(open, close)
        val lowerShadow = min(open, close) - low
        val totalRange = high - low
        val bodyRatio = if (totalRange > 0.0) bodySize / totalRange else 0.0
    }

    data class ChartPatternScore(
        val patternType: String,
        val strength: Double,
        val confirmationVolumeMultiplier: Double,
        val rationale: String,
    )

    enum class PreferredOrderType {
        MARKET,
        LIMIT_MID,
        LIMIT_PASSIVE,
        AVOID,
    }

    data class ChartTradeAssessment(
        val pairId: PairId,
        val entryScore: Double,
        val exitUrgencyScore: Double,
        val rotationUrgencyScore: Double,
        val exhaustionRiskScore: Double,
        val atrPct: Double,
        val suggestedStopLossPct: Double,
        val suggestedTrailingStopPct: Double,
        val softTakeProfitPct: Double,
        val hardTakeProfitPct: Double,
        val peakExtensionPct: Double,
        val estimatedRoundTripCostPct: Double,
        val breakEvenBufferPct: Double,
        val preferredOrderType: PreferredOrderType,
        val vetoReasons: List<String>,
        val rationale: List<String>,
    ) {
        val breakEvenMovePct: Double
            get() = estimatedRoundTripCostPct + breakEvenBufferPct

        val netEntryScore: Double
            get() = (entryScore - (estimatedRoundTripCostPct / 5.0)).coerceIn(0.0, 1.0)

        val shouldAvoidEntry: Boolean
            get() = preferredOrderType == PreferredOrderType.AVOID || vetoReasons.isNotEmpty()

        val shouldForceRotate: Boolean
            get() = rotationUrgencyScore >= 0.68
    }

    data class ChartHistoryAssessment(
        val blocked: Boolean,
        val rangeOpportunityScore: Double,
        val progressiveScore: Double,
        val deadChartScore: Double,
        val blockedReason: String?,
        val rationale: String,
    )

    fun analyzeCandles(
        candles: List<Candle>,
        currentQuote: MarketQuote,
        maxCandles: Int = 20,
    ): List<ChartPatternScore> {
        if (candles.size < 3) return emptyList()

        val recent = candles.takeLast(maxCandles)
        return listOf(
            detectBullishEngulfing(recent),
            detectHammer(recent),
            detectBreakout(recent, currentQuote),
            detectMorningStar(recent),
            detectExhaustionSpike(recent),
        ).filter { it.strength > 0.0 }
    }

    fun analyzeQuoteSnapshot(
        quote: MarketQuote,
        positionAgeMinutes: Double? = null,
        unrealizedPnlPct: Double? = null,
        dailyTargetProgressPct: Double = 0.0,
    ): ChartTradeAssessment {
        val depthIdr = min(
            quote.bidDepthTop5Idr.toDoubleOrZero(),
            quote.askDepthTop5Idr.toDoubleOrZero(),
        )
        val liquidityScore = weightedAverage(
            normalizeRatio(quote.quoteVolume24h.toDoubleOrZero(), config.minHealthyDailyVolumeIdr, 4.0) to 0.34,
            normalizeRatio(depthIdr, config.minHealthyTop5DepthIdr, 4.0) to 0.24,
            inverseThresholdScore(quote.spreadPct, config.maxHealthySpreadPct) to 0.16,
            inverseThresholdScore(quote.estimatedSlippagePct, config.maxHealthySlippagePct) to 0.18,
            quote.orderBookStabilityScore.coerceIn(0.0, 1.0) to 0.08,
        )
        val momentumScore = weightedAverage(
            normalizePositive(quote.shortTermReturnPct, config.entryMomentumShortTermPct) to 0.34,
            normalizePositive(quote.mediumTermReturnPct, config.entryMomentumMediumTermPct) to 0.22,
            quote.trendQualityScore.coerceIn(0.0, 1.0) to 0.18,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.16,
            quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.10,
        )
        val atrPct = estimateAtrPct(quote)
        val exhaustionRiskScore = deriveExhaustionRisk(quote, atrPct, liquidityScore)
        val stagnationScore = deriveStagnationScore(
            quote = quote,
            ageMinutes = positionAgeMinutes,
            unrealizedPnlPct = unrealizedPnlPct,
        )
        val forceRotateWindow = positionAgeMinutes != null &&
            positionAgeMinutes >= config.forceRotateMinutes &&
            unrealizedPnlPct != null &&
            unrealizedPnlPct in -0.5..0.5
        val compressionOpportunityScore = weightedAverage(
            inverseThresholdScore(quote.realizedVolatilityPct, max(config.stagnationVolatilityPct, 0.8)) to 0.46,
            normalizePositive(quote.orderBookImbalance, 0.25) to 0.30,
            inverseThresholdScore(abs(quote.vwapDistancePct), 1.4) to 0.24,
        )
        val estimatedRoundTripCostPct = estimateRoundTripCostPct(quote)
        val breakEvenBufferPct = config.feeSafetyBufferPct
        val entryScore = (
            weightedAverage(
                momentumScore to 0.46,
                liquidityScore to 0.32,
                quote.historicalExpectancyScore.coerceIn(0.0, 1.0) to 0.12,
                quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.10,
            ) -
                (exhaustionRiskScore * 0.24) -
                (stagnationScore * 0.10) -
                (if (dailyTargetProgressPct >= 20.0) 0.03 else 0.0) +
                (compressionOpportunityScore * 0.06)
            ).coerceIn(0.0, 1.0)
        val exitUrgencyScore = weightedAverage(
            exhaustionRiskScore to 0.44,
            stagnationScore to 0.20,
            inverseThresholdScore(quote.trendQualityScore.coerceIn(0.0, 1.0), 1.0) to 0.16,
            inverseThresholdScore(liquidityScore, 1.0) to 0.10,
            normalizeNegative(quote.shortTermReturnPct, 2.4) to 0.10,
        )
        val rotationUrgencyScore = (
            weightedAverage(
                stagnationScore to 0.42,
                inverseThresholdScore(liquidityScore, 1.0) to 0.20,
                normalizeNegative(quote.shortTermReturnPct, 1.2) to 0.12,
                normalizeNegative(quote.mediumTermReturnPct, 0.8) to 0.08,
                normalizePositive(estimatedRoundTripCostPct, 2.0) to 0.18,
            ) +
                if (forceRotateWindow) 0.08 else 0.0
            ).coerceIn(0.0, 1.0)
        val suggestedStopLossPct = min(
            config.maxAtrStopLossPct,
            max(config.minStopLossPct, atrPct * config.atrStopLossMultiplier),
        )
        val suggestedTrailingStopPct = (
            max(config.minTrailingStopPct, atrPct * config.trailingStopAtrMultiplier) +
                (exhaustionRiskScore * 0.35)
            ).coerceIn(config.minTrailingStopPct, config.maxTrailingStopPct)
        val softTakeProfitPct = max(
            config.minSoftTakeProfitPct,
            (estimatedRoundTripCostPct + breakEvenBufferPct) * config.softTakeProfitCostMultiplier +
                (atrPct * 0.85),
        )
        val hardTakeProfitPct = max(
            softTakeProfitPct + config.hardTakeProfitExtraPct,
            softTakeProfitPct * config.hardTakeProfitMultiplier,
        )
        val peakExtensionPct = max(
            hardTakeProfitPct,
            (quote.shortTermReturnPct * 0.72).coerceAtLeast(0.0) + (atrPct * 1.35),
        )
        val preferredOrderType = resolvePreferredOrderType(
            quote = quote,
            liquidityScore = liquidityScore,
            exhaustionRiskScore = exhaustionRiskScore,
        )
        val vetoReasons = buildList {
            if (
                quote.quoteVolume24h.toDoubleOrZero() < config.absoluteMinDailyVolumeIdr &&
                (
                    (depthIdr > 0.0 && depthIdr < config.absoluteMinTop5DepthIdr) ||
                        quote.estimatedSlippagePct > config.maxHealthySlippagePct * 1.10
                    )
            ) {
                add("Volume harian terlalu kecil untuk rotasi agresif.")
            }
            if (
                depthIdr > 0.0 &&
                depthIdr < config.absoluteMinTop5DepthIdr &&
                (quote.spreadPct > config.maxHealthySpreadPct || quote.estimatedSlippagePct > config.maxHealthySlippagePct)
            ) {
                add("Depth top-5 order book terlalu tipis.")
            }
            if (quote.spreadPct > config.absoluteMaxSpreadPct) {
                add("Spread terlalu lebar untuk entry cepat.")
            }
            if (quote.estimatedSlippagePct > config.absoluteMaxSlippagePct) {
                add("Slippage terlalu besar, rawan rugi saat keluar.")
            }
            if (liquidityScore < 0.20 && exhaustionRiskScore >= 0.50) {
                add("Kombinasi chart lemah dan microstructure buruk, pair sebaiknya dihindari.")
            }
        }
        val rationale = buildList {
            add("Entry ${formatPct(entryScore)} | Exit ${formatPct(exitUrgencyScore)} | Rotate ${formatPct(rotationUrgencyScore)}.")
            add(
                "BEP net ${formatPct(estimatedRoundTripCostPct + breakEvenBufferPct)} | SL ${formatPct(suggestedStopLossPct)} | trailing ${formatPct(suggestedTrailingStopPct)}.",
            )
            add(
                "Soft TP ${formatPct(softTakeProfitPct)} | hard TP ${formatPct(hardTakeProfitPct)} | peak zone ${formatPct(peakExtensionPct)}.",
            )
            if (compressionOpportunityScore >= 0.68) add("Volatilitas sedang terkompresi dan OBI mendukung, pair layak dipantau untuk ledakan awal.")
            if (stagnationScore >= 0.55) add("Posisi cenderung stagnan, layak dipantau untuk force rotate.")
            if (exhaustionRiskScore >= 0.65) add("Momentum sudah mulai jenuh, profit lebih aman dikunci dengan trailing.")
        }

        return ChartTradeAssessment(
            pairId = quote.pairId,
            entryScore = entryScore,
            exitUrgencyScore = exitUrgencyScore,
            rotationUrgencyScore = rotationUrgencyScore,
            exhaustionRiskScore = exhaustionRiskScore,
            atrPct = atrPct,
            suggestedStopLossPct = suggestedStopLossPct,
            suggestedTrailingStopPct = suggestedTrailingStopPct,
            softTakeProfitPct = softTakeProfitPct,
            hardTakeProfitPct = hardTakeProfitPct,
            peakExtensionPct = peakExtensionPct,
            estimatedRoundTripCostPct = estimatedRoundTripCostPct,
            breakEvenBufferPct = breakEvenBufferPct,
            preferredOrderType = preferredOrderType,
            vetoReasons = vetoReasons,
            rationale = rationale,
        )
    }

    fun analyzePosition(
        candles: List<Candle>,
        currentQuote: MarketQuote,
        averageEntryPrice: Double,
        heldMinutes: Double,
    ): ChartTradeAssessment {
        val currentBid = currentQuote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: averageEntryPrice
        val pnlPct = if (averageEntryPrice > 0.0) {
            ((currentBid - averageEntryPrice) / averageEntryPrice) * 100.0
        } else {
            0.0
        }
        val base = analyzeQuoteSnapshot(
            quote = currentQuote,
            positionAgeMinutes = heldMinutes,
            unrealizedPnlPct = pnlPct,
        )
        val patterns = analyzeCandles(candles, currentQuote)
        val bullishBoost = patterns
            .filter { isBullishPattern(it.patternType) }
            .takeIf { it.isNotEmpty() }
            ?.let { computeChartPatternScore(it) * 0.10 }
            ?: 0.0
        val exhaustionHit = patterns.any { it.patternType == "exhaustion_spike" && it.strength >= 0.72 }
        val adjustedExitUrgency = (
            base.exitUrgencyScore +
                if (exhaustionHit) 0.18 else 0.0 -
                bullishBoost
            ).coerceIn(0.0, 1.0)
        val adjustedEntryScore = (base.entryScore + bullishBoost).coerceIn(0.0, 1.0)
        val adjustedRotationScore = (
            base.rotationUrgencyScore +
                if (heldMinutes >= config.forceRotateMinutes && pnlPct in -0.5..0.5) 0.24 else 0.0
            ).coerceIn(0.0, 1.0)
        return base.copy(
            entryScore = adjustedEntryScore,
            exitUrgencyScore = adjustedExitUrgency,
            rotationUrgencyScore = adjustedRotationScore,
            rationale = base.rationale + patterns.map { "${it.patternType}:${formatPct(it.strength)}" },
        )
    }

    fun assessHistoryGuard(
        candleCount: Int,
        activeCandleCount: Int,
        distinctCloseBuckets: Int,
        rangePct: Double,
        lastClose: Double,
        dominantCloseShare: Double = 0.0,
        directionFlipRate: Double = 0.0,
        higherHighRatio: Double = 0.5,
        higherLowRatio: Double = 0.5,
        closingProgressRatio: Double = 0.5,
        netProgressPct: Double = 0.0,
        minCandles: Int,
        minActiveCandles: Int,
        minDistinctCloseBuckets: Int,
        cheapNominalMaxPrice: Double,
        cheapNominalMinDistinctCloses: Int,
        minRangePct: Double,
    ): ChartHistoryAssessment {
        if (candleCount < minCandles) {
            return ChartHistoryAssessment(
                blocked = true,
                rangeOpportunityScore = 0.0,
                progressiveScore = 0.0,
                deadChartScore = 1.0,
                blockedReason = "chart_history_blocked candles=$candleCount min=$minCandles",
                rationale = "Histori candle belum cukup.",
            )
        }
        if (activeCandleCount < minActiveCandles) {
            return ChartHistoryAssessment(
                blocked = true,
                rangeOpportunityScore = 0.0,
                progressiveScore = 0.0,
                deadChartScore = 1.0,
                blockedReason = "chart_activity_blocked activeCandles=$activeCandleCount min=$minActiveCandles",
                rationale = "Chart terlalu sepi.",
            )
        }
        if (distinctCloseBuckets < minDistinctCloseBuckets) {
            return ChartHistoryAssessment(
                blocked = true,
                rangeOpportunityScore = 0.0,
                progressiveScore = 0.0,
                deadChartScore = 1.0,
                blockedReason = "chart_variation_blocked distinctCloses=$distinctCloseBuckets min=$minDistinctCloseBuckets",
                rationale = "Close price kurang bervariasi.",
            )
        }
        val progressiveScore = weightedAverage(
            normalizePositive(netProgressPct, max(minRangePct * 1.8, 1.2)) to 0.24,
            higherHighRatio.coerceIn(0.0, 1.0) to 0.22,
            higherLowRatio.coerceIn(0.0, 1.0) to 0.22,
            closingProgressRatio.coerceIn(0.0, 1.0) to 0.18,
            inverseThresholdScore(directionFlipRate.coerceIn(0.0, 1.0), 1.0) to 0.14,
        )
        val deadChartScore = weightedAverage(
            normalizePositive(dominantCloseShare.coerceIn(0.0, 1.0), 0.62) to 0.34,
            normalizePositive(directionFlipRate.coerceIn(0.0, 1.0), 0.58) to 0.22,
            inverseThresholdScore(higherHighRatio.coerceIn(0.0, 1.0), 1.0) to 0.12,
            inverseThresholdScore(higherLowRatio.coerceIn(0.0, 1.0), 1.0) to 0.12,
            inverseThresholdScore(closingProgressRatio.coerceIn(0.0, 1.0), 1.0) to 0.10,
            inverseThresholdScore(normalizePositive(netProgressPct, max(minRangePct * 1.8, 1.2)), 1.0) to 0.10,
        )
        if (
            lastClose in 0.0000001..cheapNominalMaxPrice &&
            (
                distinctCloseBuckets < cheapNominalMinDistinctCloses ||
                    (deadChartScore >= 0.72 && progressiveScore < 0.45)
                )
        ) {
            return ChartHistoryAssessment(
                blocked = true,
                rangeOpportunityScore = 0.0,
                progressiveScore = progressiveScore,
                deadChartScore = deadChartScore,
                blockedReason = "cheap_nominal_chart_blocked lastClose=${formatDecimal(lastClose, 6)} distinctCloses=$distinctCloseBuckets min=$cheapNominalMinDistinctCloses",
                rationale = "Koin murah nominal tapi mutarnya sempit.",
            )
        }
        if (rangePct < minRangePct) {
            return ChartHistoryAssessment(
                blocked = true,
                rangeOpportunityScore = 0.0,
                progressiveScore = progressiveScore,
                deadChartScore = deadChartScore,
                blockedReason = "chart_flat_blocked rangePct=${formatDecimal(rangePct, 2)} min=${formatDecimal(minRangePct, 2)}",
                rationale = "Range chart terlalu datar.",
            )
        }
        if (
            deadChartScore >= 0.74 &&
            progressiveScore < 0.44 &&
            directionFlipRate >= 0.52 &&
            dominantCloseShare >= 0.45 &&
            rangePct <= max(minRangePct * 6.0, 5.0)
        ) {
            return ChartHistoryAssessment(
                blocked = true,
                rangeOpportunityScore = 0.0,
                progressiveScore = progressiveScore,
                deadChartScore = deadChartScore,
                blockedReason = "chart_ping_pong_blocked dead=${formatDecimal(deadChartScore, 2)} progressive=${formatDecimal(progressiveScore, 2)}",
                rationale = "Chart aktif semu: bolak-balik di level sempit tanpa progres naik yang sehat.",
            )
        }
        val rangeOpportunityScore = weightedAverage(
            normalizePositive(rangePct, minRangePct * 3.0) to 0.24,
            normalizePositive(distinctCloseBuckets.toDouble(), minDistinctCloseBuckets.toDouble() * 2.0) to 0.18,
            normalizePositive(activeCandleCount.toDouble(), minActiveCandles.toDouble() * 2.0) to 0.12,
            normalizePositive(candleCount.toDouble(), minCandles.toDouble() * 1.5) to 0.08,
            progressiveScore to 0.28,
            inverseThresholdScore(deadChartScore, 1.0) to 0.10,
        )
        val rationale = when {
            progressiveScore >= 0.62 && deadChartScore <= 0.42 ->
                "History chart progresif: higher-high/higher-low mulai konsisten."
            deadChartScore >= 0.58 ->
                "History chart masih aktif tapi progresnya lemah; cocoknya dipantau ketat."
            else -> "History chart cukup hidup untuk entry."
        }
        return ChartHistoryAssessment(
            blocked = false,
            rangeOpportunityScore = rangeOpportunityScore,
            progressiveScore = progressiveScore,
            deadChartScore = deadChartScore,
            blockedReason = null,
            rationale = rationale,
        )
    }

    fun computeChartPatternScore(patterns: List<ChartPatternScore>): Double {
        return patterns.map { it.strength }
            .average()
            .coerceIn(0.0, 1.0)
    }

    private fun detectBullishEngulfing(candles: List<Candle>): ChartPatternScore {
        if (candles.size < 2) return ChartPatternScore("bullish_engulfing", 0.0, 1.0, "")

        val prev = candles[candles.lastIndex - 1]
        val curr = candles.last()
        val prevBearish = prev.close < prev.open
        val currBullish = curr.close > curr.open
        val engulfs = curr.open <= prev.close && curr.close >= prev.open

        val strength = if (prevBearish && currBullish && engulfs) {
            0.80 + ((curr.volume / prev.volume.coerceAtLeast(0.001)) * 0.08)
        } else {
            0.0
        }
        return ChartPatternScore(
            patternType = "bullish_engulfing",
            strength = strength.coerceAtMost(0.95),
            confirmationVolumeMultiplier = (curr.volume / prev.volume.coerceAtLeast(0.001)).coerceAtMost(3.0),
            rationale = "Bear candle ditelan bull candle dengan volume lebih tinggi.",
        )
    }

    private fun detectHammer(candles: List<Candle>): ChartPatternScore {
        if (candles.isEmpty()) return ChartPatternScore("hammer", 0.0, 1.0, "")

        val hammer = candles.last()
        val lowerShadowRatio = hammer.lowerShadow / hammer.totalRange.coerceAtLeast(0.000001)
        val upperShadowRatio = hammer.upperShadow / hammer.totalRange.coerceAtLeast(0.000001)
        val isHammer = hammer.bodyRatio <= 0.28 &&
            lowerShadowRatio >= 0.48 &&
            upperShadowRatio <= 0.18 &&
            hammer.close >= hammer.open
        val strength = if (isHammer) {
            0.76 + (lowerShadowRatio * 0.18)
        } else {
            0.0
        }
        return ChartPatternScore(
            patternType = "hammer",
            strength = strength.coerceAtMost(0.92),
            confirmationVolumeMultiplier = 1.45,
            rationale = "Lower wick panjang, sinyal penolakan area bawah.",
        )
    }

    private fun detectBreakout(candles: List<Candle>, currentQuote: MarketQuote): ChartPatternScore {
        if (candles.size < 5) return ChartPatternScore("breakout", 0.0, 1.0, "")

        val referenceHigh = candles.takeLast(5).dropLast(1).maxOf { it.high }
        val currentPrice = currentQuote.bestAsk.toDoubleOrZero()
        val breakout = currentPrice > referenceHigh * 1.006
        val recentVolume = candles.takeLast(4).dropLast(1).map { it.volume }.average().coerceAtLeast(0.001)
        val volumeConfirmation = candles.last().volume >= recentVolume * 1.12
        val strength = if (breakout && volumeConfirmation) {
            0.84 + (currentQuote.recentTradeActivityScore * 0.10)
        } else {
            0.0
        }
        return ChartPatternScore(
            patternType = "breakout",
            strength = strength.coerceAtMost(0.96),
            confirmationVolumeMultiplier = if (volumeConfirmation) 2.0 else 1.0,
            rationale = "Harga tembus high pendek dengan activity naik.",
        )
    }

    private fun detectMorningStar(candles: List<Candle>): ChartPatternScore {
        if (candles.size < 3) return ChartPatternScore("morning_star", 0.0, 1.0, "")

        val c1 = candles[candles.lastIndex - 2]
        val c2 = candles[candles.lastIndex - 1]
        val c3 = candles.last()
        val bear1 = c1.close < c1.open
        val indecision2 = c2.bodyRatio <= 0.30
        val bull3 = c3.close > c3.open && c3.close >= ((c1.open + c1.close) / 2.0)
        val strength = if (bear1 && indecision2 && bull3) {
            0.80 + ((c3.volume / c1.volume.coerceAtLeast(0.001)) * 0.10)
        } else {
            0.0
        }
        return ChartPatternScore(
            patternType = "morning_star",
            strength = strength.coerceAtMost(0.94),
            confirmationVolumeMultiplier = 1.75,
            rationale = "Reversal 3 candle: selloff, pause, lalu reclaim.",
        )
    }

    private fun detectExhaustionSpike(candles: List<Candle>): ChartPatternScore {
        if (candles.size < 4) return ChartPatternScore("exhaustion_spike", 0.0, 1.0, "")

        val latest = candles.last()
        val recentAvgVolume = candles.takeLast(4).dropLast(1).map { it.volume }.average().coerceAtLeast(0.001)
        val hugeUpperWick = latest.upperShadow >= latest.bodySize * 1.4
        val stretchedRange = latest.totalRange >= candles.takeLast(4).dropLast(1).map { it.totalRange }.average() * 1.6
        val volumeClimax = latest.volume >= recentAvgVolume * 1.8
        val closesOffHigh = latest.close < (latest.high - (latest.totalRange * 0.32))
        val strength = if (hugeUpperWick && stretchedRange && volumeClimax && closesOffHigh) {
            0.78 + (latest.upperShadow / latest.totalRange.coerceAtLeast(0.000001)) * 0.14
        } else {
            0.0
        }
        return ChartPatternScore(
            patternType = "exhaustion_spike",
            strength = strength.coerceAtMost(0.93),
            confirmationVolumeMultiplier = (latest.volume / recentAvgVolume).coerceAtMost(4.0),
            rationale = "Spike ke atas gagal ditahan, rawan retrace tajam.",
        )
    }

    private fun estimateAtrPct(quote: MarketQuote): Double {
        val explicit = quote.realizedVolatilityPct.takeIf { it > 0.0 } ?: 0.0
        if (explicit > 0.0) {
            return (explicit * config.volatilityToAtrMultiplier)
                .coerceIn(config.minAtrPct, config.maxAtrPct)
        }
        val proxy = quote.spreadPct + (quote.estimatedSlippagePct * 0.90) + abs(quote.shortTermReturnPct * 0.18)
        return proxy.coerceIn(config.minAtrPct, config.maxAtrPct)
    }

    private fun deriveExhaustionRisk(
        quote: MarketQuote,
        atrPct: Double,
        liquidityScore: Double,
    ): Double {
        return weightedAverage(
            normalizePositive(quote.shortTermReturnPct, config.exhaustionShortTermPct) to 0.34,
            normalizePositive(quote.realizedVolatilityPct, config.exhaustionVolatilityPct) to 0.22,
            inverseThresholdScore(quote.trendQualityScore.coerceIn(0.0, 1.0), 1.0) to 0.08,
            inverseThresholdScore(liquidityScore, 1.0) to 0.14,
            normalizePositive(atrPct, 2.5) to 0.12,
            normalizePositive(quote.estimatedSlippagePct, config.maxHealthySlippagePct) to 0.10,
        )
    }

    private fun deriveStagnationScore(
        quote: MarketQuote,
        ageMinutes: Double?,
        unrealizedPnlPct: Double?,
    ): Double {
        val flatMoveScore = weightedAverage(
            inverseThresholdScore(abs(quote.shortTermReturnPct), config.stagnationShortTermPct) to 0.30,
            inverseThresholdScore(abs(quote.mediumTermReturnPct), config.stagnationMediumTermPct) to 0.18,
            inverseThresholdScore(quote.recentTradeActivityScore.coerceIn(0.0, 1.0), 1.0) to 0.18,
            inverseThresholdScore(quote.trendQualityScore.coerceIn(0.0, 1.0), 1.0) to 0.18,
            inverseThresholdScore(quote.realizedVolatilityPct, config.stagnationVolatilityPct) to 0.16,
        )
        val oneHourStuck = ageMinutes != null &&
            ageMinutes >= config.forceRotateMinutes &&
            unrealizedPnlPct != null &&
            unrealizedPnlPct in -0.5..0.5
        val deadTape = quote.recentTradeActivityScore <= 0.34 &&
            quote.trendQualityScore <= 0.42 &&
            abs(quote.shortTermReturnPct) <= config.stagnationShortTermPct * 0.75
        return (flatMoveScore + if (oneHourStuck) 0.40 else 0.0 + if (deadTape) 0.12 else 0.0).coerceIn(0.0, 1.0)
    }

    private fun resolvePreferredOrderType(
        quote: MarketQuote,
        liquidityScore: Double,
        exhaustionRiskScore: Double,
    ): PreferredOrderType {
        return when {
            quote.estimatedSlippagePct > config.absoluteMaxSlippagePct ||
                quote.spreadPct > config.absoluteMaxSpreadPct -> PreferredOrderType.AVOID
            liquidityScore < 0.34 || quote.quoteVolume24h.toDoubleOrZero() < config.minHealthyDailyVolumeIdr -> PreferredOrderType.LIMIT_PASSIVE
            exhaustionRiskScore >= 0.66 -> PreferredOrderType.LIMIT_MID
            quote.recentTradeActivityScore >= 0.72 &&
                quote.orderBookStabilityScore >= 0.70 &&
                quote.estimatedSlippagePct <= config.marketOrderMaxSlippagePct -> PreferredOrderType.MARKET
            else -> PreferredOrderType.LIMIT_MID
        }
    }

    private fun estimateRoundTripCostPct(quote: MarketQuote): Double {
        return config.estimatedRoundTripFeePct +
            quote.spreadPct.coerceAtLeast(0.0) +
            quote.estimatedSlippagePct.coerceAtLeast(0.0) +
            ((1.0 - quote.orderBookStabilityScore.coerceIn(0.0, 1.0)) * 0.16)
    }

    private fun normalizeRatio(value: Double, baseline: Double, saturationMultiplier: Double): Double {
        if (baseline <= 0.0) return 0.0
        return (value / (baseline * saturationMultiplier)).coerceIn(0.0, 1.0)
    }

    private fun normalizePositive(value: Double, target: Double): Double {
        if (target <= 0.0) return 0.0
        return (value / target).coerceIn(0.0, 1.0)
    }

    private fun normalizeNegative(value: Double, targetAbs: Double): Double {
        if (targetAbs <= 0.0) return 0.0
        return ((-value) / targetAbs).coerceIn(0.0, 1.0)
    }

    private fun inverseThresholdScore(value: Double, maxAllowed: Double): Double {
        if (maxAllowed <= 0.0) return 0.0
        return (1.0 - (value / maxAllowed)).coerceIn(0.0, 1.0)
    }

    private fun weightedAverage(vararg entries: Pair<Double, Double>): Double {
        val totalWeight = entries.sumOf { it.second }.coerceAtLeast(0.000001)
        return entries.sumOf { it.first.coerceIn(0.0, 1.0) * it.second }
            .div(totalWeight)
            .coerceIn(0.0, 1.0)
    }

    private fun formatPct(value: Double): String = "%.2f%%".format(value)

    private fun formatDecimal(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

    companion object {
        fun isBullishPattern(patternType: String): Boolean = patternType in setOf(
            "bullish_engulfing",
            "hammer",
            "breakout",
            "morning_star",
        )
    }
}

data class ChartAnalysisConfig(
    // MICRO-CAP FRIENDLY: Lowered volume requirements for small coins
    val absoluteMinDailyVolumeIdr: Double = 25_000_000.0,
    val absoluteMinTop5DepthIdr: Double = 8_000.0,
    val minHealthyDailyVolumeIdr: Double = 50_000_000.0,
    val minHealthyTop5DepthIdr: Double = 25_000.0,
    // MICRO-CAP FRIENDLY: Widened spread/slippage tolerance for illiquid micro-caps
    val absoluteMaxSpreadPct: Double = 2.50,
    val absoluteMaxSlippagePct: Double = 2.80,
    val maxHealthySpreadPct: Double = 1.80,
    val maxHealthySlippagePct: Double = 1.60,
    val marketOrderMaxSlippagePct: Double = 0.85,
    val estimatedRoundTripFeePct: Double = 0.60,
    val feeSafetyBufferPct: Double = 0.12,
    val minAtrPct: Double = 0.35,
    val maxAtrPct: Double = 6.50,
    val volatilityToAtrMultiplier: Double = 0.42,
    val atrStopLossMultiplier: Double = 0.95,
    val maxAtrStopLossPct: Double = 2.0,
    val minStopLossPct: Double = 0.55,
    val trailingStopAtrMultiplier: Double = 0.82,
    val minTrailingStopPct: Double = 0.70,
    val maxTrailingStopPct: Double = 3.20,
    val minSoftTakeProfitPct: Double = 1.10,
    val softTakeProfitCostMultiplier: Double = 1.55,
    val hardTakeProfitExtraPct: Double = 0.90,
    val hardTakeProfitMultiplier: Double = 1.55,
    val entryMomentumShortTermPct: Double = 3.4,
    val entryMomentumMediumTermPct: Double = 1.5,
    val exhaustionShortTermPct: Double = 12.0,
    val exhaustionVolatilityPct: Double = 7.0,
    val stagnationShortTermPct: Double = 0.90,
    val stagnationMediumTermPct: Double = 1.60,
    val stagnationVolatilityPct: Double = 1.20,
    val forceRotateMinutes: Double = 30.0,
)
