package com.kicryp.core

import com.kicryp.shared.models.MarketQuote
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CoinProfiler(
    private val policy: PairSelectionPolicy = PairSelectionPolicy(),
) {
    enum class Archetype {
        HIGH_BETA,
        SECTOR,
        ANOMALY,
        DINOSAUR,
        ZOMBIE,
        BALANCED,
    }

    data class Assessment(
        val archetype: Archetype,
        val structureScore: Double,
        val microstructureScore: Double,
        val contextScore: Double,
        val toxicityScore: Double,
        val executionQualityScore: Double,
        val progressiveScore: Double,
        val deadChartScore: Double,
        val vwapAnchorScore: Double,
        val rsiHealthScore: Double,
        val emaTrendScore: Double,
        val orderBookPressureScore: Double,
        val tickLifeScore: Double,
        val statisticalStretchScore: Double,
        val smartMoneyScore: Double,
        val seasonalityScore: Double,
        val keltnerScore: Double,
        val kellyFraction: Double,
        val shouldAvoid: Boolean,
        val rejectionReasons: List<String>,
        val rationale: List<String>,
    )

    fun assess(
        quote: MarketQuote,
        referenceQuotes: List<MarketQuote> = emptyList(),
        now: Instant = quote.capturedAt,
    ): Assessment {
        val pairKey = quote.pairId.value.lowercase()
        val baseAsset = pairKey.substringBefore("_")
        val derivedBtcContextScore = deriveBtcContextScore(quote, referenceQuotes, baseAsset)
        val globalCorrelationScore = quote.globalCorrelationScore.coerceIn(0.0, 1.0)
            .takeUnless { it == 0.5 }
            ?: inferGlobalCorrelationScore(baseAsset, quote)
        val sectorMomentumScore = quote.sectorMomentumScore.coerceIn(0.0, 1.0)
        val tickLifeScore = deriveTickLifeScore(quote)
        val vwapAnchorScore = deriveVwapAnchorScore(quote)
        val rsiHealthScore = deriveRsiHealthScore(quote.rsi14)
        val emaTrendScore = deriveEmaTrendScore(quote.emaFastOverSlowPct, quote)
        val orderBookPressureScore = deriveOrderBookPressureScore(quote.orderBookImbalance)
        val sessionBiasScore = deriveSessionBiasScore(now)
        val statisticalStretchScore = deriveStatisticalStretchScore(quote)
        val smartMoneyScore = deriveSmartMoneyScore(quote)
        val seasonalityScore = deriveSeasonalityScore(quote, sessionBiasScore)
        val keltnerScore = deriveKeltnerScore(quote)

        val structureScore = weightedAverage(
            quote.trendQualityScore.coerceIn(0.0, 1.0) to 0.24,
            emaTrendScore to 0.18,
            vwapAnchorScore to 0.14,
            rsiHealthScore to 0.10,
            positiveTrendProgressScore(quote) to 0.18,
            quote.historicalExpectancyScore.coerceIn(0.0, 1.0) to 0.16,
        )
        val microstructureScore = weightedAverage(
            inverseThresholdScore(quote.spreadPct, policy.maxSpreadPct * 1.35) to 0.17,
            inverseThresholdScore(quote.estimatedSlippagePct, policy.maxEstimatedSlippagePct * 1.35) to 0.19,
            quote.orderBookStabilityScore.coerceIn(0.0, 1.0) to 0.14,
            normalizeRatio(
                min(quote.bidDepthTop5Idr.toDoubleOrZero(), quote.askDepthTop5Idr.toDoubleOrZero()),
                policy.smallCapitalMinTop5DepthIdr,
                6.0,
            ) to 0.15,
            tickLifeScore to 0.16,
            orderBookPressureScore to 0.10,
            quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.09,
        )
        val contextScore = weightedAverage(
            derivedBtcContextScore to 0.30,
            globalCorrelationScore to 0.18,
            sectorMomentumScore to 0.16,
            seasonalityScore to 0.12,
            quote.localAnomalyScore.coerceIn(0.0, 1.0) to 0.10,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.14,
        )
        val deadChartScore = weightedAverage(
            inverseThresholdScore(abs(quote.shortTermReturnPct), max(policy.stagnantShortTermReturnPctMax, 0.01)) to 0.20,
            inverseThresholdScore(abs(quote.mediumTermReturnPct), max(policy.stagnantMediumTermReturnPctMax, 0.01)) to 0.18,
            inverseThresholdScore(tickLifeScore, 1.0) to 0.18,
            inverseThresholdScore(quote.recentTradeActivityScore.coerceIn(0.0, 1.0), 1.0) to 0.14,
            inverseThresholdScore(quote.trendQualityScore.coerceIn(0.0, 1.0), 1.0) to 0.18,
            normalizeRatio(policy.zombieDailyVolumeIdr, quote.quoteVolume24h.toDoubleOrZero().coerceAtLeast(1.0), 1.0) to 0.12,
        )
        val progressiveScore = weightedAverage(
            structureScore to 0.34,
            microstructureScore to 0.18,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.16,
            tickLifeScore to 0.12,
            positiveTrendProgressScore(quote) to 0.10,
            inverseThresholdScore(deadChartScore, 1.0) to 0.10,
        )
        val toxicityScore = deriveToxicityScore(
            quote = quote,
            deadChartScore = deadChartScore,
            globalCorrelationScore = globalCorrelationScore,
        )
        val executionQualityScore = weightedAverage(
            microstructureScore to 0.42,
            vwapAnchorScore to 0.14,
            orderBookPressureScore to 0.14,
            inverseThresholdScore(toxicityScore, 1.0) to 0.14,
            tickLifeScore to 0.16,
        )
        val archetype = classifyArchetype(
            baseAsset = baseAsset,
            quote = quote,
            globalCorrelationScore = globalCorrelationScore,
            sectorMomentumScore = sectorMomentumScore,
            deadChartScore = deadChartScore,
            tickLifeScore = tickLifeScore,
        )
        val shouldAvoid = when {
            toxicityScore >= policy.toxicFlowHardBlockScore -> true
            archetype == Archetype.ZOMBIE -> true
            archetype == Archetype.DINOSAUR && deadChartScore >= 0.66 -> true
            archetype == Archetype.HIGH_BETA && derivedBtcContextScore <= 0.26 -> true
            else -> false
        }
        val kellyFraction = deriveKellyFraction(
            quote = quote,
            structureScore = structureScore,
            microstructureScore = microstructureScore,
            contextScore = contextScore,
            toxicityScore = toxicityScore,
        )
        val rejectionReasons = buildList {
            if (archetype == Archetype.ZOMBIE) add("Koin zombie: tick frequency lemah dan chart aktif semu.")
            if (archetype == Archetype.DINOSAUR && deadChartScore >= 0.66) add("Koin dinosaurus terlalu lesu untuk rotasi cepat.")
            if (derivedBtcContextScore <= 0.26 && archetype == Archetype.HIGH_BETA) add("BTC sedang tidak aman untuk koin beta tinggi.")
            if (toxicityScore >= policy.toxicFlowHardBlockScore) add("Toxic flow terlalu tinggi, pair wajib dikarantina.")
            if (vwapAnchorScore <= 0.22 && quote.vwapDistancePct > policy.dangerousVwapExtensionPct) {
                add("Harga terlalu jauh dari VWAP, rawan revert.")
            }
            if (tickLifeScore <= 0.18 && quote.quoteVolume24h.toDoubleOrZero() <= policy.zombieDailyVolumeIdr) {
                add("Frekuensi transaksi terlalu kecil untuk dianggap breakout valid.")
            }
            if (statisticalStretchScore >= 0.82) add("Harga sudah terlalu ekstrem secara statistik; rawan mean reversion.")
            if (smartMoneyScore <= 0.28) add("Pergerakan belum didukung smart money yang cukup.")
        }
        val rationale = buildList {
            add("DNA ${archetype.name.lowercase()} | structure ${formatPct(structureScore)} | micro ${formatPct(microstructureScore)}.")
            add("Context ${formatPct(contextScore)} | toxic ${formatPct(toxicityScore)} | progressive ${formatPct(progressiveScore)}.")
            if (quote.vwapDistancePct != 0.0) add("VWAP drift ${formatPct(quote.vwapDistancePct / 100.0)} | RSI ${quote.rsi14.toInt()} | OBI ${formatPct((quote.orderBookImbalance + 1.0) / 2.0)}.")
            add("Z ${formatDecimal(quote.zScoreCurrent, 2)} | smart ${formatPct(smartMoneyScore)} | season ${formatPct(seasonalityScore)} | keltner ${formatPct(keltnerScore)}.")
        }

        return Assessment(
            archetype = archetype,
            structureScore = structureScore,
            microstructureScore = microstructureScore,
            contextScore = contextScore,
            toxicityScore = toxicityScore,
            executionQualityScore = executionQualityScore,
            progressiveScore = progressiveScore,
            deadChartScore = deadChartScore,
            vwapAnchorScore = vwapAnchorScore,
            rsiHealthScore = rsiHealthScore,
            emaTrendScore = emaTrendScore,
            orderBookPressureScore = orderBookPressureScore,
            tickLifeScore = tickLifeScore,
            statisticalStretchScore = statisticalStretchScore,
            smartMoneyScore = smartMoneyScore,
            seasonalityScore = seasonalityScore,
            keltnerScore = keltnerScore,
            kellyFraction = kellyFraction,
            shouldAvoid = shouldAvoid,
            rejectionReasons = rejectionReasons,
            rationale = rationale,
        )
    }

    private fun classifyArchetype(
        baseAsset: String,
        quote: MarketQuote,
        globalCorrelationScore: Double,
        sectorMomentumScore: Double,
        deadChartScore: Double,
        tickLifeScore: Double,
    ): Archetype {
        return when {
            tickLifeScore <= 0.14 &&
                quote.quoteVolume24h.toDoubleOrZero() <= policy.zombieDailyVolumeIdr &&
                deadChartScore >= 0.72 -> Archetype.ZOMBIE
            quote.quoteVolume24h.toDoubleOrZero() <= policy.dinosaurDailyVolumeIdr &&
                quote.recentTradeActivityScore <= 0.22 &&
                quote.trendQualityScore <= 0.38 -> Archetype.DINOSAUR
            quote.localAnomalyScore >= 0.66 ||
                (quote.shortTermReturnPct >= 4.0 && globalCorrelationScore <= 0.34) -> Archetype.ANOMALY
            sectorMomentumScore >= 0.66 -> Archetype.SECTOR
            inferHighBeta(baseAsset) || globalCorrelationScore >= 0.66 -> Archetype.HIGH_BETA
            else -> Archetype.BALANCED
        }
    }

    private fun deriveBtcContextScore(
        quote: MarketQuote,
        referenceQuotes: List<MarketQuote>,
        baseAsset: String,
    ): Double {
        val explicit = quote.btcContextScore.coerceIn(0.0, 1.0)
        if (explicit != 0.5) return explicit
        val btc = referenceQuotes.firstOrNull {
            val key = it.pairId.value.lowercase()
            key == "btc_idr" || key == "btc_usdt"
        }
        val eth = referenceQuotes.firstOrNull {
            val key = it.pairId.value.lowercase()
            key == "eth_idr" || key == "eth_usdt"
        }
        val btcMood = btc?.let {
            weightedAverage(
                inverseThresholdScore(-it.shortTermReturnPct, 2.0) to 0.56,
                inverseThresholdScore(-it.mediumTermReturnPct, 1.4) to 0.44,
            )
        } ?: 0.56
        val ethMood = eth?.let {
            weightedAverage(
                inverseThresholdScore(-it.shortTermReturnPct, 2.0) to 0.56,
                inverseThresholdScore(-it.mediumTermReturnPct, 1.4) to 0.44,
            )
        } ?: 0.56
        val marketMood = weightedAverage(btcMood to 0.70, ethMood to 0.30)
        return when {
            inferHighBeta(baseAsset) -> marketMood
            quote.localAnomalyScore >= 0.66 -> (0.55 + (marketMood * 0.25)).coerceIn(0.0, 1.0)
            else -> weightedAverage(marketMood to 0.56, quote.globalCorrelationScore.coerceIn(0.0, 1.0) to 0.44)
        }
    }

    private fun inferGlobalCorrelationScore(baseAsset: String, quote: MarketQuote): Double {
        return when {
            inferHighBeta(baseAsset) -> 0.78
            quote.localAnomalyScore >= 0.66 -> 0.28
            quote.quoteVolume24h.toDoubleOrZero() < policy.dinosaurDailyVolumeIdr -> 0.34
            else -> 0.52
        }
    }

    private fun inferHighBeta(baseAsset: String): Boolean = baseAsset in setOf(
        "btc", "eth", "sol", "ada", "avax", "matic", "arb", "op", "link", "xrp", "doge",
    )

    private fun deriveTickLifeScore(quote: MarketQuote): Double {
        val explicitTicks = quote.tickFrequencyPerMinute
        val tradeFlowFromCount = if (quote.tradeCount24h > 0) {
            (quote.tradeCount24h.toDouble() / 1440.0)
        } else {
            0.0
        }
        return weightedAverage(
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.46,
            normalizeRatio(max(explicitTicks, tradeFlowFromCount), policy.minTickFrequencyPerMinute, 8.0) to 0.36,
            normalizeRatio(quote.quoteVolume24h.toDoubleOrZero(), policy.smallCapitalMinDailyQuoteVolumeIdr, 8.0) to 0.18,
        )
    }

    private fun deriveVwapAnchorScore(quote: MarketQuote): Double {
        if (quote.vwapDistancePct == 0.0) {
            return weightedAverage(
                inverseThresholdScore(abs(quote.shortTermReturnPct), policy.healthyVwapExtensionPct * 2.0) to 0.42,
                quote.trendQualityScore.coerceIn(0.0, 1.0) to 0.30,
                quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.28,
            )
        }
        return when {
            quote.vwapDistancePct < -0.40 -> 0.62
            quote.vwapDistancePct <= policy.healthyVwapExtensionPct -> 0.88
            quote.vwapDistancePct <= policy.dangerousVwapExtensionPct -> 0.58
            else -> inverseThresholdScore(quote.vwapDistancePct, policy.dangerousVwapExtensionPct * 1.6)
        }.coerceIn(0.0, 1.0)
    }

    private fun deriveRsiHealthScore(rsi: Double): Double {
        return when {
            rsi <= 0.0 -> 0.5
            rsi < policy.rsiOversoldThreshold -> 0.52
            rsi in 48.0..68.0 -> 0.92
            rsi < policy.rsiOverboughtThreshold -> 0.74
            rsi < 82.0 -> 0.40
            else -> 0.20
        }
    }

    private fun deriveEmaTrendScore(emaFastOverSlowPct: Double, quote: MarketQuote): Double {
        if (emaFastOverSlowPct == 0.0) {
            return weightedAverage(
                quote.trendQualityScore.coerceIn(0.0, 1.0) to 0.56,
                positiveTrendProgressScore(quote) to 0.44,
            )
        }
        return (0.5 + (emaFastOverSlowPct / 3.2)).coerceIn(0.0, 1.0)
    }

    private fun deriveOrderBookPressureScore(orderBookImbalance: Double): Double {
        val normalized = ((orderBookImbalance.coerceIn(-1.0, 1.0) + 1.0) / 2.0).coerceIn(0.0, 1.0)
        return when {
            orderBookImbalance >= policy.strongOrderBookImbalance -> 0.94
            orderBookImbalance <= -policy.strongOrderBookImbalance -> 0.12
            else -> normalized
        }
    }

    private fun deriveSessionBiasScore(now: Instant): Double {
        val hour = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).hour
        return when (hour) {
            in 20..23, in 0..3 -> 0.86
            in 7..15 -> 0.58
            else -> 0.48
        }
    }

    private fun deriveStatisticalStretchScore(quote: MarketQuote): Double {
        val zPenalty = normalizeRatio(abs(quote.zScoreCurrent), 2.0, 2.4)
        val vwapPenalty = normalizeRatio(abs(quote.vwapDistancePct), policy.healthyVwapExtensionPct, 3.0)
        return weightedAverage(
            zPenalty to 0.58,
            vwapPenalty to 0.22,
            quote.keltnerExtensionScore.coerceIn(0.0, 1.0) to 0.20,
        )
    }

    private fun deriveSmartMoneyScore(quote: MarketQuote): Double {
        return weightedAverage(
            quote.smartMoneyIndex.coerceIn(0.0, 1.0) to 0.46,
            inverseThresholdScore(quote.cvdDivergenceScore.coerceIn(0.0, 1.0), 1.0) to 0.24,
            deriveOrderBookPressureScore(quote.orderBookImbalance) to 0.18,
            quote.fillQualityScore.coerceIn(0.0, 1.0) to 0.12,
        )
    }

    private fun deriveSeasonalityScore(
        quote: MarketQuote,
        sessionBiasScore: Double,
    ): Double {
        val normalizedMultiplier = ((quote.seasonalityMultiplier.coerceIn(0.45, 1.35) - 0.45) / 0.90).coerceIn(0.0, 1.0)
        return weightedAverage(
            normalizedMultiplier to 0.68,
            sessionBiasScore to 0.32,
        )
    }

    private fun deriveKeltnerScore(quote: MarketQuote): Double {
        return (1.0 - quote.keltnerExtensionScore.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }

    private fun deriveToxicityScore(
        quote: MarketQuote,
        deadChartScore: Double,
        globalCorrelationScore: Double,
    ): Double {
        return weightedAverage(
            quote.toxicFlowScore.coerceIn(0.0, 1.0) to 0.44,
            deadChartScore to 0.18,
            inverseThresholdScore(quote.fillQualityScore.coerceIn(0.0, 1.0), 1.0) to 0.10,
            inverseThresholdScore(quote.orderBookStabilityScore.coerceIn(0.0, 1.0), 1.0) to 0.12,
            normalizePositive(quote.localAnomalyScore.coerceIn(0.0, 1.0), 1.0) to 0.08,
            inverseThresholdScore(globalCorrelationScore, 1.0) to 0.08,
        )
    }

    private fun deriveKellyFraction(
        quote: MarketQuote,
        structureScore: Double,
        microstructureScore: Double,
        contextScore: Double,
        toxicityScore: Double,
    ): Double {
        val setupQuality = weightedAverage(
            quote.historicalExpectancyScore.coerceIn(0.0, 1.0) to 0.28,
            structureScore to 0.24,
            microstructureScore to 0.20,
            contextScore to 0.16,
            inverseThresholdScore(toxicityScore, 1.0) to 0.12,
        )
        val winProbability = (0.36 + (setupQuality * 0.40)).coerceIn(0.36, 0.76)
        val atrProxyPct = max(0.8, min(2.0, (quote.realizedVolatilityPct * 0.42).takeIf { it > 0.0 } ?: 1.0))
        val expectedRewardPct = max(
            atrProxyPct * 2.1,
            1.2 + max(quote.shortTermReturnPct, 0.0) * 0.18 + max(quote.mediumTermReturnPct, 0.0) * 0.12,
        )
        val b = (expectedRewardPct / atrProxyPct).coerceAtLeast(1.05)
        val rawKelly = ((winProbability * (b + 1.0)) - 1.0) / b
        return (rawKelly * 0.50).coerceIn(0.0, policy.kellyFractionCap)
    }

    private fun positiveTrendProgressScore(quote: MarketQuote): Double {
        return weightedAverage(
            normalizePositive(quote.shortTermReturnPct, 4.2) to 0.48,
            normalizePositive(quote.mediumTermReturnPct, 2.2) to 0.32,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.20,
        )
    }

    private fun normalizeRatio(value: Double, baseline: Double, saturationMultiplier: Double): Double {
        if (baseline <= 0.0) return 0.0
        return (value / (baseline * saturationMultiplier)).coerceIn(0.0, 1.0)
    }

    private fun normalizePositive(value: Double, target: Double): Double {
        if (target <= 0.0) return 0.0
        return (value / target).coerceIn(0.0, 1.0)
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

    private fun formatPct(score: Double): String = "%.0f".format(score.coerceIn(0.0, 1.0) * 100.0)
    private fun formatDecimal(value: Double, decimals: Int): String = "%.${decimals}f".format(value)
}
