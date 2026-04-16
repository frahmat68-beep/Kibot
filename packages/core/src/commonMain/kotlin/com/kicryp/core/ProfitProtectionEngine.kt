package com.kicryp.core

import com.kicryp.shared.models.DailyRiskSnapshot
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.ProfitProtectionSnapshot
import com.kicryp.shared.models.ProfitProtectionStatus

class ProfitProtectionEngine(
    private val config: ProfitProtectionConfig = ProfitProtectionConfig(),
) {
    fun evaluate(dailyRisk: DailyRiskSnapshot): ProfitProtectionSnapshot {
        val opening = dailyRisk.openingEquityIdr.toDoubleOrZero().coerceAtLeast(1.0)
        val current = dailyRisk.currentEquityIdr.toDoubleOrZero()
        val highWatermark = maxOf(
            dailyRisk.highWatermarkEquityIdr.toDoubleOrZero(),
            current,
            opening,
        )
        val weeklyProfitPct = ((highWatermark - opening) / opening).coerceAtLeast(0.0)
        val givebackPct = when {
            highWatermark <= opening -> 0.0
            current >= highWatermark -> 0.0
            else -> ((highWatermark - current) / (highWatermark - opening)).coerceIn(0.0, 1.0)
        }

        val status = when {
            weeklyProfitPct >= config.weeklyProfitGuardPct && givebackPct >= config.coolingGivebackPct ->
                ProfitProtectionStatus.COOLING_AGGRESSION
            weeklyProfitPct >= config.weeklyProfitGuardPct && givebackPct >= config.givebackWarningPct ->
                ProfitProtectionStatus.TRAILING_HIGH_WATERMARK
            weeklyProfitPct >= config.weeklyProfitGuardPct ->
                ProfitProtectionStatus.GUARDING_WEEKLY_PROFIT
            else -> dailyRisk.profitProtectionStatus.takeIf { it != ProfitProtectionStatus.INACTIVE }
                ?: ProfitProtectionStatus.INACTIVE
        }

        val rationale = buildList {
            if (weeklyProfitPct >= config.weeklyProfitGuardPct) {
                add("Profit mingguan sudah cukup untuk dijaga.")
            }
            if (givebackPct >= config.givebackWarningPct) {
                add("Sebagian profit mulai kembali ke market.")
            }
            if (status == ProfitProtectionStatus.COOLING_AGGRESSION) {
                add("Agresivitas perlu diturunkan sementara.")
            }
        }

        val aggressionMultiplier = when (status) {
            ProfitProtectionStatus.INACTIVE -> 1.0
            ProfitProtectionStatus.GUARDING_WEEKLY_PROFIT -> config.trailingGuardMultiplier
            ProfitProtectionStatus.TRAILING_HIGH_WATERMARK -> 0.80
            ProfitProtectionStatus.COOLING_AGGRESSION -> config.coolingAggressionMultiplier
        }
        val sizeMultiplier = when (status) {
            ProfitProtectionStatus.INACTIVE -> 1.0
            ProfitProtectionStatus.GUARDING_WEEKLY_PROFIT -> 0.90
            ProfitProtectionStatus.TRAILING_HIGH_WATERMARK -> 0.78
            ProfitProtectionStatus.COOLING_AGGRESSION -> 0.65
        }

        return ProfitProtectionSnapshot(
            status = status,
            highWatermarkEquityIdr = DecimalValue.fromDouble(highWatermark),
            givebackPct = givebackPct,
            weeklyProfitPct = weeklyProfitPct,
            aggressionMultiplier = aggressionMultiplier,
            sizeMultiplier = sizeMultiplier,
            rationale = rationale,
        )
    }
}
