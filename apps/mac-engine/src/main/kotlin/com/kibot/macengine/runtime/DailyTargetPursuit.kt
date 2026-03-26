package com.kibot.macengine.runtime

import com.kibot.core.StrategyCycleResult
import com.kibot.shared.models.DecimalValue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil
import kotlin.math.max

data class DailyTargetPursuitConfig(
    val targetProfitPct: Double = 25.0,
    val hourlyEvaluationCadenceHours: Double = 1.0,
    val threeHourCheckpointPct: Double = 10.0,
    val checkpointCadenceHours: Double = 3.0,
    val warmupHours: Double = 1.5,
    val checkpointUnrealizedCreditWeight: Double = 0.12,
    val minUnrealizedCreditWeight: Double = 0.18,
    val maxUnrealizedCreditWeight: Double = 0.45,
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
    val hourlyWindowIndex: Int,
    val hourlyMissed: Boolean,
    val hourlyMissCount: Int,
    val hourlyShortfallPct: Double,
    val hourlyEscalationLevel: Int,
    val checkpointWindowIndex: Int,
    val checkpointMissed: Boolean,
    val checkpointShortfallPct: Double,
    val checkpointEscalationLevel: Int,
    val forcedReplan: Boolean,
    val profitWindowOpen: Boolean,
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
        val localDateTime = now.toLocalDateTime(timeZone)
        val elapsedHours = localDateTime.hour + (localDateTime.minute / 60.0)
        val realizedProfitPct = if (openingEquity > 0.0) {
            (cycle.dailyRisk.realizedPnlIdr.toDoubleOrZero() / openingEquity) * 100.0
        } else {
            0.0
        }
        val unrealizedProfitPct = if (openingEquity > 0.0) {
            (cycle.dailyRisk.unrealizedPnlIdr.toDoubleOrZero() / openingEquity) * 100.0
        } else {
            0.0
        }
        val unrealizedCreditWeight = (
            config.minUnrealizedCreditWeight +
                (((elapsedHours / 24.0).coerceIn(0.0, 1.0)) * (config.maxUnrealizedCreditWeight - config.minUnrealizedCreditWeight))
            ).coerceIn(config.minUnrealizedCreditWeight, config.maxUnrealizedCreditWeight)
        val effectiveProfitPct = realizedProfitPct + (unrealizedProfitPct * unrealizedCreditWeight)
        val checkpointProfitPct = realizedProfitPct + (unrealizedProfitPct * config.checkpointUnrealizedCreditWeight)
        val progressPct = (effectiveProfitPct / config.targetProfitPct).coerceIn(-1.0, 2.5)
        val targetGapPct = (config.targetProfitPct - effectiveProfitPct).coerceAtLeast(0.0)
        val normalizedDayProgress = ((elapsedHours - config.warmupHours) / max(1.0, 24.0 - config.warmupHours))
            .coerceIn(0.0, 1.0)
        val normalizedTargetProgress = (effectiveProfitPct / config.targetProfitPct).coerceIn(0.0, 1.0)
        val behindSchedule = (normalizedDayProgress - normalizedTargetProgress).coerceAtLeast(0.0)
        val gapPressure = (targetGapPct / config.targetProfitPct).coerceIn(0.0, 1.35)
        val drawdownPenalty = cycle.dailyRisk.drawdownPct.coerceIn(0.0, 0.18)
        val aiConsensus = adaptiveAiPolicy?.consensusStrength?.coerceIn(0.0, 1.0) ?: 0.0
        val hourlyEvaluationWindow = kotlin.math.floor(elapsedHours / config.hourlyEvaluationCadenceHours)
            .toInt()
            .coerceAtLeast(0)
        val expectedProfitByNowPct = expectedProfitAt(elapsedHours)
        val hourlyExpectedProfitPct = if (hourlyEvaluationWindow > 0) {
            expectedProfitAt(hourlyEvaluationWindow * config.hourlyEvaluationCadenceHours)
        } else {
            0.0
        }
        val completedCheckpointWindow = kotlin.math.floor(elapsedHours / config.checkpointCadenceHours)
            .toInt()
            .coerceAtLeast(0)
        val checkpointExpectedProfitPct = if (completedCheckpointWindow > 0) {
            checkpointTargetAtWindow(completedCheckpointWindow)
        } else {
            0.0
        }
        val checkpointShortfallPct = (checkpointExpectedProfitPct - checkpointProfitPct).coerceAtLeast(0.0)
        val checkpointMissed = completedCheckpointWindow > 0 && checkpointShortfallPct > 0.0
        val hourlyShortfallPct = (hourlyExpectedProfitPct - effectiveProfitPct).coerceAtLeast(0.0)
        val hourlyMissed = hourlyEvaluationWindow > 0 && hourlyShortfallPct > 0.0
        val targetPctPerHour = (config.targetProfitPct / 24.0).coerceAtLeast(0.10)
        val hourlyMissCount = if (hourlyMissed) {
            ceil(hourlyShortfallPct / targetPctPerHour).toInt().coerceIn(1, 6)
        } else {
            0
        }
        val hourlyEscalationLevel = when {
            !hourlyMissed -> 0
            hourlyShortfallPct >= 3.2 || hourlyMissCount >= 3 -> 3
            hourlyShortfallPct >= 1.6 || hourlyMissCount >= 2 -> 2
            else -> 1
        }
        val checkpointEscalationLevel = when {
            !checkpointMissed -> 0
            completedCheckpointWindow >= 3 || checkpointShortfallPct >= 6.0 -> 3
            completedCheckpointWindow >= 2 || checkpointShortfallPct >= 3.0 -> 2
            else -> 1
        }
        val forcedReplan = checkpointMissed ||
            hourlyMissCount >= 2 ||
            (hourlyMissed && elapsedHours >= 2.0 && hourlyShortfallPct >= 1.20)
        val hourlyPressure = ((expectedProfitByNowPct - effectiveProfitPct) / config.targetProfitPct).coerceIn(0.0, 1.0)
        val topCandidateQuality = cycle.deploymentPlan.candidates.firstOrNull()?.let {
            (it.rankingScore * 0.58) + (it.marketOpportunityScore * 0.42)
        } ?: 0.0
        val profitWindowOpen =
            topCandidateQuality >= 0.82 ||
                aiConsensus >= 0.68 ||
                cycle.selectedSignal?.expectedNetProfitabilityPct?.let { it >= 2.8 } == true
        val targetSatisfied = (
            checkpointProfitPct >= config.targetProfitPct ||
                (effectiveProfitPct >= config.targetProfitPct && realizedProfitPct >= (config.targetProfitPct * 0.55))
            ) &&
            (realizedProfitPct >= (config.targetProfitPct * 0.35) || cycle.dailyRisk.givebackPct <= 0.12)
        val overdriveAllowed = targetSatisfied &&
            (
                topCandidateQuality >= 0.84 ||
                    aiConsensus >= 0.72 ||
                    cycle.selectedSignal?.expectedNetProfitabilityPct?.let { it >= 3.8 } == true
                )
        val baseUrgency = when {
            targetSatisfied && !overdriveAllowed -> 0.0
            targetSatisfied && overdriveAllowed -> (
                0.24 +
                    ((topCandidateQuality - 0.78).coerceAtLeast(0.0) * 0.55) +
                    (aiConsensus * 0.18)
                ).coerceIn(0.20, 0.62)
            else -> (
                (gapPressure * 0.56) +
                    (behindSchedule * 0.18) +
                    (hourlyPressure * 0.22) +
                    (aiConsensus * 0.16) +
                    (if (checkpointMissed) 0.24 else 0.0) +
                    ((topCandidateQuality - 0.55).coerceAtLeast(0.0) * 0.22) -
                    (drawdownPenalty * 0.30)
                ).coerceIn(0.0, 1.0)
        }
        val urgencyFloor = when {
            targetSatisfied -> 0.0
            checkpointMissed -> 0.78
            forcedReplan -> 0.64
            profitWindowOpen -> 0.30
            hourlyMissed -> (0.46 + (hourlyMissCount * 0.05)).coerceAtMost(0.60)
            else -> 0.0
        }
        val urgency = max(baseUrgency, urgencyFloor).coerceIn(0.0, 1.0)

        val budgetBoostMultiplier = max(
            (1.0 + (urgency * 0.52) + (aiConsensus * 0.18)).coerceIn(1.0, config.maxBudgetBoostMultiplier),
            when {
                checkpointMissed -> 1.62
                forcedReplan -> 1.42
                profitWindowOpen -> 1.18
                hourlyMissed -> 1.24
                else -> 1.0
            },
        ).coerceIn(1.0, config.maxBudgetBoostMultiplier)
        val executionBoostMultiplier = max(
            (1.0 + (urgency * 0.38) + (aiConsensus * 0.14)).coerceIn(1.0, config.maxExecutionBoostMultiplier),
            when {
                checkpointMissed -> 1.44
                forcedReplan -> 1.28
                profitWindowOpen -> 1.12
                hourlyMissed -> 1.16
                else -> 1.0
            },
        ).coerceIn(1.0, config.maxExecutionBoostMultiplier)
        val reserveReliefPct = max(
            (0.03 + (urgency * 0.05) + (aiConsensus * 0.02)).coerceIn(0.0, config.maxReserveReliefPct),
            when {
                checkpointMissed -> 0.09
                forcedReplan -> 0.07
                profitWindowOpen -> 0.04
                hourlyMissed -> 0.05
                else -> 0.03
            },
        ).coerceIn(0.0, config.maxReserveReliefPct)
        val extraSlots = when {
            currentEquity < config.minEquityForExtraSlotIdr -> 0
            checkpointMissed && currentEquity >= (config.minEquityForExtraSlotIdr * 1.12) -> config.maxExtraSlots
            forcedReplan && currentEquity >= config.minEquityForExtraSlotIdr -> 1.coerceAtMost(config.maxExtraSlots)
            profitWindowOpen && currentEquity >= config.minEquityForExtraSlotIdr && urgency >= 0.36 -> 1.coerceAtMost(config.maxExtraSlots)
            hourlyMissed && currentEquity >= config.minEquityForExtraSlotIdr && urgency >= 0.54 -> 1.coerceAtMost(config.maxExtraSlots)
            urgency < 0.50 && !checkpointMissed -> 0
            urgency < 0.76 || currentEquity < (config.minEquityForExtraSlotIdr * 1.35) -> 1
            else -> config.maxExtraSlots
        }
        val concentrationBoostPct = max(
            ((urgency * 0.12) + (aiConsensus * 0.06)).coerceIn(0.0, config.maxConcentrationBoostPct),
            when {
                checkpointMissed -> 0.16
                forcedReplan -> 0.12
                profitWindowOpen -> 0.08
                else -> 0.0
            },
        ).coerceIn(0.0, config.maxConcentrationBoostPct)

        val rationale = buildList {
            add("Target harian ${formatPct(config.targetProfitPct)} dengan progress efektif ${formatPct(progressPct * 100.0)}.")
            if (hourlyEvaluationWindow > 0) {
                add("Evaluasi 1 jam ke-$hourlyEvaluationWindow aktif: pace target meminta minimal ${formatPct(hourlyExpectedProfitPct)}.")
            }
            if (hourlyMissed) add("Evaluasi hourly miss ${hourlyMissCount} langkah pace (${formatPct(hourlyShortfallPct)} shortfall), jadi bot wajib menaikkan tekanan entry, sizing, dan rotasi.")
            if (profitWindowOpen && !targetSatisfied) add("Profit window terbuka: kandidat saat ini cukup kuat untuk tetap agresif meski checkpoint hanya jadi patokan.")
            if (targetSatisfied && overdriveAllowed) add("Target harian sudah lewat, tapi setup sangat kuat jadi bot tetap overdrive untuk kejar profit lanjutan.")
            if (targetSatisfied && !overdriveAllowed) add("Target harian sudah tercapai dan tidak ada setup ekstrem, jadi bot mulai jaga hasil.")
            if (targetGapPct > 0.0) add("Gap target tersisa ${formatPct(targetGapPct)} dan urgency ${formatPct(urgency * 100.0)}.")
            if (behindSchedule > 0.12) add("Bot tertinggal dari ritme target harian, jadi pursuit pressure dinaikkan.")
            if (hourlyPressure > 0.12) add("Evaluasi jam ini menunjukkan bot tertinggal dari pace harian, jadi sizing, rotasi, dan filter entry dipaksa lebih agresif.")
            if (checkpointMissed) add("Checkpoint 3 jam ke-$completedCheckpointWindow miss: target ${formatPct(checkpointExpectedProfitPct)} vs realisasi keras ${formatPct(checkpointProfitPct)}, jadi bot wajib replan agresif.")
            if (forcedReplan && !checkpointMissed) add("Gap hourly terlalu besar, jadi bot masuk forced replan sebelum checkpoint berikutnya.")
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
            currentProfitPct = effectiveProfitPct,
            progressPct = progressPct,
            targetGapPct = targetGapPct,
            urgency = urgency,
            hourlyWindowIndex = hourlyEvaluationWindow,
            hourlyMissed = hourlyMissed,
            hourlyMissCount = hourlyMissCount,
            hourlyShortfallPct = hourlyShortfallPct,
            hourlyEscalationLevel = hourlyEscalationLevel,
            checkpointWindowIndex = completedCheckpointWindow,
            checkpointMissed = checkpointMissed,
            checkpointShortfallPct = checkpointShortfallPct,
            checkpointEscalationLevel = checkpointEscalationLevel,
            forcedReplan = forcedReplan,
            profitWindowOpen = profitWindowOpen,
            targetSatisfied = targetSatisfied,
            overdriveAllowed = overdriveAllowed,
            budgetBoostMultiplier = budgetBoostMultiplier,
            reserveReliefPct = reserveReliefPct,
            executionBoostMultiplier = executionBoostMultiplier,
            extraSlots = extraSlots,
            concentrationBoostPct = concentrationBoostPct,
            rotationAgeHoursDelta = (-0.10 - (urgency * 0.28) - (if (forcedReplan) 0.08 else 0.0)).coerceAtLeast(-0.46),
            rotationScoreGapDelta = (-0.02 - (urgency * 0.05) - (if (forcedReplan) 0.02 else 0.0)).coerceAtLeast(-0.10),
            partialTakeProfitPnlDelta = (urgency * 0.55).coerceAtMost(0.55),
            winnerRunPnlDelta = (-0.16 - (urgency * 0.22)).coerceAtLeast(-0.40),
            meaningfulExitProfitDelta = (-0.08 - (urgency * 0.18) - (if (checkpointMissed) 0.12 else 0.0) - (if (forcedReplan && !checkpointMissed) 0.06 else 0.0)).coerceAtLeast(-0.36),
            rationale = rationale,
        )
    }

    private fun DecimalValue.toDoubleOrZero(): Double = value.toDoubleOrNull() ?: 0.0

    private fun expectedProfitAt(elapsedHours: Double): Double = when {
        elapsedHours <= 0.0 -> 0.0
        else -> {
            val checkpointWindow = kotlin.math.floor(elapsedHours / config.checkpointCadenceHours).toInt().coerceAtLeast(0)
            val checkpointStartHour = checkpointWindow * config.checkpointCadenceHours
            val previousTarget = if (checkpointWindow <= 0) 0.0 else checkpointTargetAtWindow(checkpointWindow)
            val nextWindow = checkpointWindow + 1
            val nextTarget = checkpointTargetAtWindow(nextWindow)
            val segmentProgress = ((elapsedHours - checkpointStartHour) / config.checkpointCadenceHours).coerceIn(0.0, 1.0)
            previousTarget + ((nextTarget - previousTarget) * segmentProgress)
        }
    }.coerceIn(0.0, config.targetProfitPct)

    private fun checkpointTargetAtWindow(window: Int): Double = when {
        window <= 0 -> 0.0
        window == 1 -> config.threeHourCheckpointPct
        window == 2 -> 14.0
        window == 3 -> 18.0
        window == 4 -> 21.0
        window == 5 -> 23.0
        else -> config.targetProfitPct
    }.coerceIn(0.0, config.targetProfitPct)

    private fun formatPct(value: Double): String = String.format("%.1f%%", value)
}
