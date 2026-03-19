package com.kibot.core

import com.kibot.shared.models.BotId
import com.kibot.shared.models.LearningObservation
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.TradingHorizon
import com.kibot.shared.models.WeeklyAdaptationPlan
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class WeeklyLearningLoop(
    private val config: WeeklyLearningConfig = WeeklyLearningConfig(),
) {
    fun review(
        botId: BotId,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        observations: List<LearningObservation>,
    ): WeeklyLearningSummary {
        val trades = observations.filter { it.tradeTaken }
        val noTrades = observations.filterNot { it.tradeTaken }

        val pairStats = trades
            .filter { it.pairId != null }
            .groupBy { it.pairId!! }
            .mapValues { (_, entries) -> expectancySummary(entries) }
        val setupStats = trades
            .groupBy { it.setupType }
            .mapValues { (_, entries) -> expectancySummary(entries) }
        val hourStats = observations
            .groupBy { it.observedAt.hourOfDayUtc() }
            .mapValues { (_, entries) -> expectancySummary(entries.filter { it.tradeTaken }) }

        val tacticalTrades = trades.filter { it.horizon == TradingHorizon.TACTICAL }
        val swingTrades = trades.filter { it.horizon == TradingHorizon.SWING }
        val falseEntryRate = trades.ratioOf { it.falseEntry || (it.realizedPnlPct < 0.0 && it.expectedNetEdgePct > 0.0) }.ifNaN(0.0)
        val noTradeQualityScore = noTrades.ratioOf { it.avoidedBadTrade }.ifNaN(0.5)
        val avoidedBadTradesIndicator = noTrades.ratioOf { it.avoidedBadTrade }.ifNaN(0.0)
        val missedOpportunityRate = observations.ratioOf { it.missedQualifiedOpportunity }.ifNaN(0.0)
        val capitalUtilizationPct = observations.map { it.capitalUtilizationPct }.averageOr(0.0)
        val productiveUtilizationPct = observations.map { it.productiveUtilizationPct }.averageOr(0.0)
        val tacticalExpectancy = expectancy(tacticalTrades)
        val swingExpectancy = expectancy(swingTrades)

        val bestPairs = pairStats.entries
            .filter { it.value.sampleCount >= config.minimumPairSamples }
            .sortedByDescending { it.value.expectancyScore }
            .take(3)
            .map { it.key }
        val worstPairs = pairStats.entries
            .filter { it.value.sampleCount >= config.minimumPairSamples }
            .sortedBy { it.value.expectancyScore }
            .take(3)
            .map { it.key }
        val bestSetups = setupStats.entries
            .filter { it.value.sampleCount > 0 }
            .sortedByDescending { it.value.expectancyScore }
            .take(3)
            .map { it.key }
        val worstSetups = setupStats.entries
            .filter { it.value.sampleCount > 0 }
            .sortedBy { it.value.expectancyScore }
            .take(3)
            .map { it.key }
        val bestHours = hourStats.entries
            .filter { it.value.sampleCount > 0 }
            .sortedByDescending { it.value.expectancyScore }
            .take(4)
            .map { it.key }
        val worstHours = hourStats.entries
            .filter { it.value.sampleCount > 0 }
            .sortedBy { it.value.expectancyScore }
            .take(4)
            .map { it.key }

        val adaptationPlan = buildAdaptationPlan(
            tradeCount = trades.size,
            bestPairs = bestPairs,
            worstPairs = worstPairs,
            bestHours = bestHours,
            setupStats = setupStats,
            tacticalExpectancy = tacticalExpectancy,
            swingExpectancy = swingExpectancy,
            falseEntryRate = falseEntryRate,
            noTradeQualityScore = noTradeQualityScore,
        )

        val notes = buildList {
            if (trades.size < config.minimumTradeSamples) add("Sample minggu ini masih kecil, jadi adaptasi dibuat ringan.")
            if (falseEntryRate > 0.35) add("False entry meningkat, threshold entry perlu diperketat.")
            if (noTradeQualityScore >= 0.60) add("Kualitas no-trade sudah cukup baik.")
            if (swingExpectancy > tacticalExpectancy + 0.25) add("Bias swing mulai lebih menarik.")
            if (tacticalExpectancy > swingExpectancy + 0.25) add("Tactical masih jadi mesin utama.")
        }

        return WeeklyLearningSummary(
            botId = botId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            tradeCount = trades.size,
            bestPairs = bestPairs,
            worstPairs = worstPairs,
            bestSetups = bestSetups,
            worstSetups = worstSetups,
            bestHours = bestHours,
            worstHours = worstHours,
            falseEntryRate = falseEntryRate,
            noTradeQualityScore = noTradeQualityScore,
            avoidedBadTradesIndicator = avoidedBadTradesIndicator,
            capitalUtilizationPct = capitalUtilizationPct,
            productiveUtilizationPct = productiveUtilizationPct,
            missedOpportunityRate = missedOpportunityRate,
            tacticalExpectancy = tacticalExpectancy,
            swingExpectancy = swingExpectancy,
            adaptationPlan = adaptationPlan,
            notes = notes,
        )
    }

    private fun buildAdaptationPlan(
        tradeCount: Int,
        bestPairs: List<PairId>,
        worstPairs: List<PairId>,
        bestHours: List<Int>,
        setupStats: Map<SetupType, ExpectancySummary>,
        tacticalExpectancy: Double,
        swingExpectancy: Double,
        falseEntryRate: Double,
        noTradeQualityScore: Double,
    ): WeeklyAdaptationPlan {
        val enoughSample = tradeCount >= config.minimumTradeSamples
        val aggressionDelta = when {
            !enoughSample -> 0.0
            tacticalExpectancy > 0.18 && swingExpectancy > 0.18 && falseEntryRate < 0.20 ->
                config.maxAggressionDeltaPerReview
            falseEntryRate > 0.32 ->
                -config.maxAggressionDeltaPerReview
            else -> 0.0
        }
        val sizeDelta = when {
            !enoughSample -> 0.0
            falseEntryRate > 0.32 -> -config.maxSizeDeltaPerReview
            noTradeQualityScore > 0.62 && tacticalExpectancy > 0.10 -> config.maxSizeDeltaPerReview / 2.0
            else -> 0.0
        }
        val tacticalBiasDelta = when {
            tacticalExpectancy > swingExpectancy + 0.15 -> config.maxBiasDeltaPerReview
            swingExpectancy > tacticalExpectancy + 0.15 -> -config.maxBiasDeltaPerReview
            else -> 0.0
        }
        val swingBiasDelta = -tacticalBiasDelta

        val setupBias = setupStats.entries.associate { entry ->
            entry.key.name to entry.value.expectancyScore.coerceIn(-0.15, 0.15)
        }

        val notes = buildList {
            if (!enoughSample) add("Adaptasi mingguan dibatasi karena sample masih kecil.")
            if (bestPairs.isNotEmpty()) add("Pair terbaik masuk prioritas ringan.")
            if (worstPairs.isNotEmpty()) add("Pair terburuk masuk blacklist sementara.")
        }

        return WeeklyAdaptationPlan(
            whitelistPairs = bestPairs.take(config.maximumWhitelistPairs),
            temporaryBlacklistPairs = worstPairs.take(config.maximumBlacklistPairs),
            setupBias = setupBias,
            activeHours = bestHours,
            aggressionMultiplierDelta = aggressionDelta,
            sizeMultiplierDelta = sizeDelta,
            tacticalBiasDelta = tacticalBiasDelta,
            swingBiasDelta = swingBiasDelta,
            notes = notes,
        )
    }

    private fun expectancy(entries: List<LearningObservation>): Double {
        if (entries.isEmpty()) return 0.0
        val winRate = entries.ratioOf { it.realizedPnlPct > 0.0 }.ifNaN(0.0)
        val avgPnl = entries.map { it.realizedPnlPct }.averageOr(0.0)
        val avgSlippagePenalty = entries.map { it.slippagePct }.averageOr(0.0) * 0.25
        return ((avgPnl * 0.7) + (winRate * 0.3) - avgSlippagePenalty).coerceIn(-1.0, 1.0)
    }

    private fun expectancySummary(entries: List<LearningObservation>) = ExpectancySummary(
        expectancyScore = expectancy(entries),
        sampleCount = entries.size,
    )

    private data class ExpectancySummary(
        val expectancyScore: Double,
        val sampleCount: Int,
    )

    private fun List<LearningObservation>.ratioOf(predicate: (LearningObservation) -> Boolean): Double {
        if (isEmpty()) return Double.NaN
        return count(predicate).toDouble() / size.toDouble()
    }

    private fun List<Double>.averageOr(fallback: Double): Double {
        if (isEmpty()) return fallback
        return average()
    }

    private fun Double.ifNaN(fallback: Double): Double = if (isNaN()) fallback else this

    private fun Instant.hourOfDayUtc(): Int = toString().substring(11, 13).toIntOrNull() ?: 0
}
