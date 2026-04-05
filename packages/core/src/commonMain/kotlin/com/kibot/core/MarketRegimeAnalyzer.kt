package com.kibot.core

import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.MarketOpportunitySnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PairTier
import kotlin.math.abs

class MarketRegimeAnalyzer(
    private val policy: MarketRegimePolicy = MarketRegimePolicy(),
    private val pairPolicy: PairSelectionPolicy = PairSelectionPolicy(),
) {
    fun analyze(
        quotes: List<MarketQuote>,
        rankedPairs: List<PairScore>,
        health: EngineHealthSnapshot,
        performanceMomentumScore: Double = 0.5,
    ): MarketOpportunitySnapshot {
        val topQuotes = quotes
            .sortedByDescending { it.quoteVolume24h.toDoubleOrZero() }
            .take(12)
        val candidates = rankedPairs.take(8)

        val trendScore = candidates.map { it.trendQualityScore }.averageOr(deriveUniverseTrendScore(topQuotes))
        val microstructureHealthScore = averageOf(
            candidates.map { it.recentHealthScore }.averageOr(0.5),
            candidates.map { it.stabilityScore }.averageOr(0.5),
            candidates.map { it.fillQualityScore }.averageOr(0.5),
            candidates.map { it.microstructureScore }.averageOr(0.5),
        )
        val opportunityAvailabilityScore = if (candidates.isEmpty()) {
            0.0
        } else {
            candidates.count { it.allowed && it.pairTier != PairTier.TIER_C }.toDouble() / candidates.size.toDouble()
        }
        val volatilityQualityScore = candidates.map { it.volatilityQualityScore }.averageOr(0.5)
        val volatilityClusterScore = deriveVolatilityClusterScore(topQuotes, candidates)
        val contextHealthScore = candidates.map { it.contextScore }.averageOr(0.5)
        val toxicityPenaltyScore = candidates.map { 1.0 - it.toxicityScore.coerceIn(0.0, 1.0) }.averageOr(0.5)
        val progressiveOpportunityScore = candidates.map { it.progressiveScore }.averageOr(0.5)
        val spreadHealthScore = topQuotes.map { inverseThresholdScore(it.spreadPct, pairPolicy.maxSpreadPct) }.averageOr(0.5)
        val slippageHealthScore = topQuotes.map { inverseThresholdScore(it.estimatedSlippagePct, pairPolicy.maxEstimatedSlippagePct) }.averageOr(0.5)

        val regime = when {
            trendScore <= policy.panicTrendScoreMax &&
                microstructureHealthScore <= policy.panicMicrostructureMax &&
                opportunityAvailabilityScore < 0.25 ->
                MarketRegime.BREAKDOWN_PANIC
            volatilityClusterScore >= policy.elevatedVolatilityClusterMin &&
                (toxicityPenaltyScore < 0.58 || spreadHealthScore < 0.60) ->
                MarketRegime.HIGH_VOLATILITY_UNCLEAR
            microstructureHealthScore <= policy.unclearMicrostructureMax ||
                volatilityQualityScore < 0.40 ||
                spreadHealthScore < 0.45 ||
                toxicityPenaltyScore < 0.44 ->
                MarketRegime.HIGH_VOLATILITY_UNCLEAR
            trendScore >= policy.healthyUptrendTrendScoreMin &&
                opportunityAvailabilityScore >= policy.healthyUptrendOpportunityMin &&
                progressiveOpportunityScore >= 0.56 ->
                MarketRegime.HEALTHY_UPTREND
            else -> MarketRegime.HEALTHY_SIDEWAYS
        }

        val marketOpportunityScore = weightedAverage(
            opportunityAvailabilityScore to 0.24,
            microstructureHealthScore to 0.20,
            volatilityQualityScore to 0.16,
            spreadHealthScore to 0.14,
            slippageHealthScore to 0.10,
            trendScore to 0.10,
            contextHealthScore to 0.10,
            toxicityPenaltyScore to 0.08,
            (1.0 - volatilityClusterScore) to 0.08,
            progressiveOpportunityScore to 0.08,
        )
        val botHealthScore = deriveBotHealthScore(health)
        val edgeConfidence = deriveEdgeConfidence(
            marketOpportunityScore = marketOpportunityScore,
            botHealthScore = botHealthScore,
            performanceMomentumScore = performanceMomentumScore,
            microstructureHealthScore = microstructureHealthScore,
            warningCount = health.warnings.size + health.anomalyCount,
        )
        val tacticalBiasScore = when (regime) {
            MarketRegime.HEALTHY_SIDEWAYS -> 0.78
            MarketRegime.HIGH_VOLATILITY_UNCLEAR -> 0.55
            MarketRegime.HEALTHY_UPTREND -> 0.42
            MarketRegime.BREAKDOWN_PANIC -> 0.10
        }
        val swingBiasScore = when (regime) {
            MarketRegime.HEALTHY_UPTREND -> 0.82
            MarketRegime.HEALTHY_SIDEWAYS -> 0.35
            MarketRegime.HIGH_VOLATILITY_UNCLEAR -> 0.18
            MarketRegime.BREAKDOWN_PANIC -> 0.05
        }

        val rationale = buildList {
            add("Opportunity score ${(marketOpportunityScore * 100).toInt()} / 100.")
            add("Bot health ${(botHealthScore * 100).toInt()} / 100.")
            if (opportunityAvailabilityScore < 0.30) add("Kandidat pair berkualitas masih sedikit.")
            if (microstructureHealthScore < 0.50) add("Kualitas microstructure belum stabil.")
            if (toxicityPenaltyScore < 0.46) add("Terlalu banyak pair sedang toxic atau mudah menyapu stop.")
            if (progressiveOpportunityScore < 0.46) add("Banyak chart belum benar-benar progresif, jadi rotasi harus lebih selektif.")
            if (volatilityClusterScore >= policy.elevatedVolatilityClusterMin) add("Pasar sedang masuk klaster volatilitas tinggi; trailing dan stop harus lebih adaptif.")
            if (edgeConfidence == EdgeConfidence.LOW) add("Edge confidence turun, mode agresif harus ditahan.")
        }

        return MarketOpportunitySnapshot(
            regime = regime,
            marketOpportunityScore = marketOpportunityScore,
            botHealthScore = botHealthScore,
            performanceMomentumScore = performanceMomentumScore.coerceIn(0.0, 1.0),
            edgeConfidence = edgeConfidence,
            tacticalBiasScore = tacticalBiasScore,
            swingBiasScore = swingBiasScore,
            opportunityAvailabilityScore = opportunityAvailabilityScore,
            microstructureHealthScore = microstructureHealthScore,
            volatilityClusterScore = volatilityClusterScore,
            rationale = rationale,
        )
    }

    private fun deriveVolatilityClusterScore(
        quotes: List<MarketQuote>,
        candidates: List<PairScore>,
    ): Double {
        if (quotes.isEmpty() && candidates.isEmpty()) return 0.0
        val quoteVolatility = quotes
            .map { quote ->
                weightedAverage(
                    (quote.realizedVolatilityPct / 6.0).coerceIn(0.0, 1.0) to 0.34,
                    (kotlin.math.abs(quote.shortTermReturnPct) / 4.0).coerceIn(0.0, 1.0) to 0.22,
                    quote.keltnerExtensionScore.coerceIn(0.0, 1.0) to 0.24,
                    quote.toxicFlowScore.coerceIn(0.0, 1.0) to 0.20,
                )
            }
            .averageOr(0.0)
        val candidateVolatility = candidates
            .map {
                weightedAverage(
                    (1.0 - it.volatilityQualityScore.coerceIn(0.0, 1.0)) to 0.42,
                    it.toxicityScore.coerceIn(0.0, 1.0) to 0.34,
                    it.statisticalStretchScore.coerceIn(0.0, 1.0) to 0.24,
                )
            }
            .averageOr(0.0)
        return averageOf(quoteVolatility, candidateVolatility).coerceIn(0.0, 1.0)
    }

    private fun deriveBotHealthScore(health: EngineHealthSnapshot): Double {
        val statusScore = when (health.status) {
            com.kibot.shared.models.HealthStatus.HEALTHY -> 1.0
            com.kibot.shared.models.HealthStatus.WARNING -> 0.60
            com.kibot.shared.models.HealthStatus.CRITICAL -> 0.20
        }
        val syncScore = when (health.syncHealth) {
            com.kibot.shared.models.SyncHealth.HEALTHY -> 1.0
            com.kibot.shared.models.SyncHealth.DEGRADED -> 0.60
            com.kibot.shared.models.SyncHealth.BROKEN -> 0.15
        }
        val connectivityScore = averageOf(
            if (health.exchangeReachable) 1.0 else 0.0,
            if (health.supabaseReachable) 1.0 else 0.0,
            if (health.websocketHealthy) 1.0 else 0.40,
        )
        val qualityScore = averageOf(
            health.fillQualityScore.coerceIn(0.0, 1.0),
            (1.0 - health.rejectRatePct.coerceAtLeast(0.0)).coerceIn(0.0, 1.0),
            (1.0 - (health.anomalyCount / 6.0)).coerceIn(0.0, 1.0),
        )
        val batteryScore = health.batteryPercent?.let { battery ->
            if (health.charging == true) 1.0 else (battery / 100.0).coerceIn(0.25, 1.0)
        } ?: 0.85

        return weightedAverage(
            statusScore to 0.24,
            syncScore to 0.20,
            connectivityScore to 0.24,
            qualityScore to 0.20,
            batteryScore to 0.12,
        )
    }

    private fun deriveUniverseTrendScore(quotes: List<MarketQuote>): Double {
        if (quotes.isEmpty()) return 0.5
        val directionalReturn = quotes.map { (it.shortTermReturnPct * 0.4) + (it.mediumTermReturnPct * 0.6) }.average()
        return (0.5 + (directionalReturn / 8.0)).coerceIn(0.0, 1.0)
    }

    private fun deriveEdgeConfidence(
        marketOpportunityScore: Double,
        botHealthScore: Double,
        performanceMomentumScore: Double,
        microstructureHealthScore: Double,
        warningCount: Int,
    ): EdgeConfidence {
        return when {
            marketOpportunityScore >= 0.72 &&
                botHealthScore >= 0.75 &&
                performanceMomentumScore >= 0.62 &&
                microstructureHealthScore >= 0.65 &&
                warningCount <= 2 ->
                EdgeConfidence.HIGH
            marketOpportunityScore < 0.45 ||
                botHealthScore < 0.45 ||
                performanceMomentumScore < 0.40 ||
                microstructureHealthScore < 0.42 ||
                warningCount >= 5 ->
                EdgeConfidence.LOW
            else -> EdgeConfidence.MEDIUM
        }
    }

    private fun inverseThresholdScore(value: Double, maxAllowed: Double): Double {
        if (maxAllowed <= 0.0) return 0.0
        return (1.0 - (abs(value) / maxAllowed)).coerceIn(0.0, 1.0)
    }

    private fun weightedAverage(vararg entries: Pair<Double, Double>): Double {
        val totalWeight = entries.sumOf { it.second }.coerceAtLeast(0.000001)
        return (entries.sumOf { it.first.coerceIn(0.0, 1.0) * it.second } / totalWeight).coerceIn(0.0, 1.0)
    }

    private fun averageOf(vararg values: Double): Double = values.toList().averageOr(0.0)

    private fun List<Double>.averageOr(fallback: Double): Double {
        if (isEmpty()) return fallback
        return average().coerceIn(0.0, 1.0)
    }
}
