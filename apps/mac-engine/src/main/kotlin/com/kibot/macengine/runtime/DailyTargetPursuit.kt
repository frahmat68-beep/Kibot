package com.kibot.macengine.runtime

import com.kibot.core.StrategyCycleResult
import com.kibot.shared.models.DecimalValue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max

data class DailyTargetPursuitConfig(
    val targetProfitPct: Double = 25.0,
    val warmupHours: Double = 1.5,
    val maxBudgetBoostMultiplier: Double = 1.95,
    val maxReserveReliefPct: Double = 0.12,
    val maxExtraSlots: Int = 2,
    val maxExecutionBoostMultiplier: Double = 1.72,
    val maxConcentrationBoostPct: Double = 0.24,
    val minEquityForExtraSlotIdr: Double = 105_000.0,
)

data class DailyTargetPursuit(
    val phase: String,
    val currentProfitPct: Double,
    val progressPct: Double,
    val targetGapPct: Double,
    val urgency: Double,
    val targetSatisfied: Boolean,
    val overdriveAllowed: Boolean,
    val budgetBoostMultiplier: Double,
    val reserveReliefPct: Double,
    val executionBoostMultiplier: Double,
    val extraSlots: Int,
    val concentrationBoostPct: Double,
    val rotationAgeHoursDelta: Double,
    val rotationScoreGapDelta: Double,
    val partialTakeProfitPnlDelta: Double,
    val winnerRunPnlDelta: Double,
    val meaningfulExitProfitDelta: Double,
    val rationale: List<String>,
) {
    val active: Boolean = urgency > 0.05
}

class DailyTargetPursuitBrain(
    private val config: DailyTargetPursuitConfig = DailyTargetPursuitConfig(),
) {
    fun evaluate(
        cycle: StrategyCycleResult,
        adaptiveAiPolicy: AdaptiveAiPolicy?,
        now: Instant,
        timeZone: TimeZone = TimeZone.of("Asia/Jakarta"),
    ): DailyTargetPursuit {
        val openingEquity = cycle.dailyRisk.openingEquityIdr.toDoubleOrZero().takeIf { it > 0.0 }
            ?: cycle.portfolio.totalEquityIdr.toDoubleOrZero().coerceAtLeast(1.0)
        val currentEquity = cycle.portfolio.totalEquityIdr.toDoubleOrZero().coerceAtLeast(0.0)
        val profitPct = if (openingEquity > 0.0) {
            ((currentEquity - openingEquity) / openingEquity) * 100.0
        } else {
            0.0
        }
        val progressPct = (profitPct / config.targetProfitPct).coerceIn(-1.0, 2.5)
        val targetGapPct = (config.targetProfitPct - profitPct).coerceAtLeast(0.0)
        val localDateTime = now.toLocalDateTime(timeZone)
        val elapsedHours = localDateTime.hour + (localDateTime.minute / 60.0)
        val normalizedDayProgress = ((elapsedHours - config.warmupHours) / max(1.0, 24.0 - config.warmupHours))
            .coerceIn(0.0, 1.0)
        val normalizedTargetProgress = (profitPct / config.targetProfitPct).coerceIn(0.0, 1.0)
        val behindSchedule = (normalizedDayProgress - normalizedTargetProgress).coerceAtLeast(0.0)
        val gapPressure = (targetGapPct / config.targetProfitPct).coerceIn(0.0, 1.35)
        val drawdownPenalty = cycle.dailyRisk.drawdownPct.coerceIn(0.0, 0.18)
        val aiConsensus = adaptiveAiPolicy?.consensusStrength?.coerceIn(0.0, 1.0) ?: 0.0
        val topCandidateQuality = cycle.deploymentPlan.candidates.firstOrNull()?.let {
            (it.rankingScore * 0.58) + (it.marketOpportunityScore * 0.42)
        } ?: 0.0
        val targetSatisfied = profitPct >= config.targetProfitPct
        val overdriveAllowed = targetSatisfied &&
            (
                topCandidateQuality >= 0.84 ||
                    aiConsensus >= 0.72 ||
                    cycle.selectedSignal?.expectedNetProfitabilityPct?.let { it >= 3.8 } == true
                )
        val urgency = when {
            targetSatisfied && !overdriveAllowed -> 0.0
            targetSatisfied && overdriveAllowed -> (
                0.24 +
                    ((topCandidateQuality - 0.78).coerceAtLeast(0.0) * 0.55) +
                    (aiConsensus * 0.18)
                ).coerceIn(0.20, 0.62)
            else -> (
                (gapPressure * 0.56) +
                    (behindSchedule * 0.28) +
                    (aiConsensus * 0.16) +
                    ((topCandidateQuality - 0.55).coerceAtLeast(0.0) * 0.22) -
                    (drawdownPenalty * 0.30)
                ).coerceIn(0.0, 1.0)
        }

        val budgetBoostMultiplier = (1.0 + (urgency * 0.52) + (aiConsensus * 0.18))
            .coerceIn(1.0, config.maxBudgetBoostMultiplier)
        val executionBoostMultiplier = (1.0 + (urgency * 0.38) + (aiConsensus * 0.14))
            .coerceIn(1.0, config.maxExecutionBoostMultiplier)
        val reserveReliefPct = (0.03 + (urgency * 0.05) + (aiConsensus * 0.02))
            .coerceIn(0.0, config.maxReserveReliefPct)
        val extraSlots = when {
            currentEquity < config.minEquityForExtraSlotIdr -> 0
            urgency < 0.54 -> 0
            urgency < 0.76 || currentEquity < (config.minEquityForExtraSlotIdr * 1.35) -> 1
            else -> config.maxExtraSlots
        }
        val concentrationBoostPct = ((urgency * 0.12) + (aiConsensus * 0.06))
            .coerceIn(0.0, config.maxConcentrationBoostPct)

        val rationale = buildList {
            add("Target harian ${formatPct(config.targetProfitPct)} dengan progress ${formatPct(progressPct * 100.0)}.")
            if (targetSatisfied && overdriveAllowed) add("Target harian sudah lewat, tapi setup sangat kuat jadi bot tetap overdrive untuk kejar profit lanjutan.")
            if (targetSatisfied && !overdriveAllowed) add("Target harian sudah tercapai dan tidak ada setup ekstrem, jadi bot mulai jaga hasil.")
            if (targetGapPct > 0.0) add("Gap target tersisa ${formatPct(targetGapPct)} dan urgency ${formatPct(urgency * 100.0)}.")
            if (behindSchedule > 0.12) add("Bot tertinggal dari ritme target harian, jadi pursuit pressure dinaikkan.")
            if (aiConsensus >= 0.55) add("Consensus AI kuat, sizing dan fokus modal boleh dinaikkan.")
            if (extraSlots > 0) add("Modal cukup untuk membuka ${extraSlots} slot tambahan sambil tetap aman untuk kapasitas server.")
        }

        return DailyTargetPursuit(
            phase = when {
                targetSatisfied && overdriveAllowed -> "OVERDRIVE"
                targetSatisfied -> "LOCK_PROFIT"
                urgency >= 0.72 -> "FULL_CHASE"
                urgency >= 0.42 -> "CHASE"
                else -> "TRACKING"
            },
            currentProfitPct = profitPct,
            progressPct = progressPct,
            targetGapPct = targetGapPct,
            urgency = urgency,
            targetSatisfied = targetSatisfied,
            overdriveAllowed = overdriveAllowed,
            budgetBoostMultiplier = budgetBoostMultiplier,
            reserveReliefPct = reserveReliefPct,
            executionBoostMultiplier = executionBoostMultiplier,
            extraSlots = extraSlots,
            concentrationBoostPct = concentrationBoostPct,
            rotationAgeHoursDelta = (-0.10 - (urgency * 0.28)).coerceAtLeast(-0.40),
            rotationScoreGapDelta = (-0.02 - (urgency * 0.05)).coerceAtLeast(-0.08),
            partialTakeProfitPnlDelta = (urgency * 0.55).coerceAtMost(0.55),
            winnerRunPnlDelta = (-0.16 - (urgency * 0.22)).coerceAtLeast(-0.40),
            meaningfulExitProfitDelta = (urgency * 0.18).coerceAtMost(0.18),
            rationale = rationale,
        )
    }

    private fun DecimalValue.toDoubleOrZero(): Double = value.toDoubleOrNull() ?: 0.0

    private fun formatPct(value: Double): String = String.format("%.1f%%", value)
}
