package com.kicryp.core

import com.kicryp.shared.models.AdvisorySeverity
import com.kicryp.shared.models.BotId
import com.kicryp.shared.models.BotUpdateRecommendation
import com.kicryp.shared.models.DeviceId
import com.kicryp.shared.models.LearningHint
import com.kicryp.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.pow

data class SituationalLearningConfig(
    val falseEntryAlertPct: Double = 0.24,
    val lowProductiveUtilizationPct: Double = 0.30,
    val highMissedOpportunityPct: Double = 0.18,
    val healthyMarketOpportunityScore: Double = 0.70,
    val lowNoTradeQualityScore: Double = 0.36,
    val strongExpectancyPct: Double = 0.18,
    val maxRecommendationsPerCycle: Int = 1,
)

data class SituationalLearningDecision(
    val learningHints: List<LearningHint> = emptyList(),
    val updateRecommendations: List<BotUpdateRecommendation> = emptyList(),
) {
    val signature: String = buildString {
        append(learningHints.joinToString("|") { "${it.hintCode}:${it.severity.name}" })
        append("||")
        append(updateRecommendations.joinToString("|") { it.versionTag })
    }
}

class SituationalLearningEngine(
    private val config: SituationalLearningConfig = SituationalLearningConfig(),
) {
    fun evaluate(
        botId: BotId,
        deviceId: DeviceId,
        now: Instant,
        cycle: StrategyCycleResult,
        weeklySummary: WeeklyLearningSummary?,
        aiBlockedReason: String? = null,
        aiUsedNetwork: Boolean = false,
    ): SituationalLearningDecision {
        val hints = mutableListOf<LearningHint>()
        val recommendations = mutableListOf<BotUpdateRecommendation>()

        weeklySummary?.let { review ->
            val aGradeSignatures = review.executionSignatures.filter { it.anomalyGrade == "A-GRADE_ANOMALY" }
            if (aGradeSignatures.isNotEmpty()) {
                val strongest = aGradeSignatures.maxBy { it.confidenceScore }
                val rationale = listOf(
                    "Signature ${strongest.pairId.value} sudah terbaca sebagai ${strongest.anomalyGrade}.",
                    "VWAP=${strongest.vwapDistancePct.asPctString()} OBI=${strongest.orderBookImbalance.asRatioString()} CVD=${strongest.cvdDivergenceScore.asRatioString()} tick=${strongest.tickFrequencyPerMinute.asRateString()} /m.",
                    "Blueprint ini bisa dipakai sebagai acuan ONT-style anomaly high-probability.",
                )
                hints += hint(
                    code = "ont_a_grade_anomaly",
                    severity = AdvisorySeverity.HIGH,
                    source = "execution_signature",
                    summary = "A-grade anomaly blueprint terdeteksi, jadikan ini acuan pola kualitas tinggi.",
                    rationale = rationale,
                    now = now,
                )
                recommendations.add(
                    0,
                    recommendation(
                        botId = botId,
                        deviceId = deviceId,
                        reasonCode = "ont_a_grade_blueprint",
                        severity = AdvisorySeverity.HIGH,
                        title = "Codify A-grade anomaly",
                        summary = "Blueprint anomali mirip ONT terdeteksi, gunakan sebagai acuan prioritas sebelum entry berikutnya.",
                        source = "execution_signature",
                        confidenceScore = strongest.confidenceScore.coerceIn(0.0, 1.0),
                        evidence = mapOf(
                            "confidence_score" to strongest.confidenceScore,
                            "vwap_distance_pct" to strongest.vwapDistancePct,
                            "order_book_imbalance" to strongest.orderBookImbalance,
                            "cvd_divergence_score" to strongest.cvdDivergenceScore,
                            "tick_frequency_per_minute" to strongest.tickFrequencyPerMinute,
                        ),
                        actions = listOf(
                            "Prioritaskan pair dengan OBI bullish, CVD positif, dan VWAP compression yang serupa.",
                            "Gunakan signature ini sebagai blueprint anomaly untuk rotasi berikutnya.",
                            "Tetap hormati daily profit lock dan quarantine bila posisi sudah aman.",
                        ),
                        now = now,
                    ),
                )
            }

            if (review.falseEntryRate >= config.falseEntryAlertPct) {
                val rationale = listOf(
                    "False entry rate ${review.falseEntryRate.asPctString()} sudah melewati batas sehat.",
                    "Perlu entry gate lebih ketat supaya modal kecil tidak bocor oleh trade lemah.",
                )
                hints += hint(
                    code = "false_entry_pressure",
                    severity = AdvisorySeverity.HIGH,
                    source = "weekly_review",
                    summary = "False entry sedang naik, bot perlu lebih ketat memilih setup.",
                    rationale = rationale,
                    now = now,
                )
                recommendations += recommendation(
                    botId = botId,
                    deviceId = deviceId,
                    reasonCode = "tighten_entry_gate",
                    severity = AdvisorySeverity.HIGH,
                    title = "Review threshold entry",
                    summary = "False entry tinggi, ada sinyal bahwa threshold entry dan filter execution perlu ditinjau.",
                    source = "weekly_review",
                    confidenceScore = 0.78,
                    evidence = mapOf(
                        "false_entry_rate" to review.falseEntryRate,
                        "no_trade_quality_score" to review.noTradeQualityScore,
                    ),
                    actions = listOf(
                        "Naikkan threshold ranking minimum secara kecil.",
                        "Perketat filter spread dan slippage saat mode GROWTH.",
                        "Prioritaskan setup dengan fill quality lebih baik.",
                    ),
                    now = now,
                )
            }

            if (
                review.productiveUtilizationPct <= config.lowProductiveUtilizationPct &&
                review.missedOpportunityRate >= config.highMissedOpportunityPct &&
                cycle.marketSnapshot.marketOpportunityScore >= config.healthyMarketOpportunityScore
            ) {
                val rationale = listOf(
                    "Market cukup sehat, tetapi modal produktif masih rendah.",
                    "Ada peluang yang lolos, tetapi bot terlalu sering diam atau kalah prioritas.",
                )
                hints += hint(
                    code = "productive_utilization_gap",
                    severity = AdvisorySeverity.MEDIUM,
                    source = "weekly_review",
                    summary = "Produktivitas modal masih rendah saat market sebenarnya cukup layak.",
                    rationale = rationale,
                    now = now,
                )
                recommendations += recommendation(
                    botId = botId,
                    deviceId = deviceId,
                    reasonCode = "improve_capital_deployment",
                    severity = AdvisorySeverity.MEDIUM,
                    title = "Tuning capital deployment",
                    summary = "Bot tampak terlalu konservatif saat peluang sehat muncul, jadi deployment logic layak ditinjau.",
                    source = "weekly_review",
                    confidenceScore = 0.70,
                    evidence = mapOf(
                        "productive_utilization_pct" to review.productiveUtilizationPct,
                        "missed_opportunity_rate" to review.missedOpportunityRate,
                        "market_opportunity_score" to cycle.marketSnapshot.marketOpportunityScore,
                    ),
                    actions = listOf(
                        "Tinjau syarat pembukaan slot kedua tetap dalam batas aman.",
                        "Kurangi idle cash ringan saat peluang tier A jelas dominan.",
                        "Pantau rotasi kandidat agar modal tidak terlalu lama diam.",
                    ),
                    now = now,
                )
            }

            if (
                review.noTradeQualityScore <= config.lowNoTradeQualityScore &&
                cycle.marketSnapshot.marketOpportunityScore <= 0.45
            ) {
                hints += hint(
                    code = "no_trade_quality_soft",
                    severity = AdvisorySeverity.MEDIUM,
                    source = "weekly_review",
                    summary = "Kualitas keputusan no-trade belum konsisten.",
                    rationale = listOf(
                        "Bot masih perlu lebih jelas membedakan no-trade yang bagus dan no-trade yang terlalu takut.",
                    ),
                    now = now,
                )
            }

            if (
                review.tacticalExpectancy >= config.strongExpectancyPct &&
                review.falseEntryRate < 0.18 &&
                cycle.modeSnapshot.tradingAllowed &&
                cycle.modeSnapshot.aggressionScore < 0.58
            ) {
                hints += hint(
                    code = "bounded_growth_room",
                    severity = AdvisorySeverity.LOW,
                    source = "weekly_review",
                    summary = "Ada ruang kecil untuk lebih produktif tanpa mengganggu safety core.",
                    rationale = listOf(
                        "Expectancy tactical cukup sehat, jadi bot bisa sedikit lebih aktif secara bounded.",
                    ),
                    now = now,
                )
            }
        } ?: run {
            hints += hint(
                code = "learning_sample_thin",
                severity = AdvisorySeverity.LOW,
                source = "runtime",
                summary = "Data belajar mingguan belum cukup, jadi adaptasi otomatis tetap ringan.",
                rationale = listOf("Bot tetap membaca market live, tetapi perubahan perilaku harus tetap kecil."),
                now = now,
            )
        }

        when (aiBlockedReason) {
            "hourly_budget",
            "daily_budget",
            "global_cooldown",
            "same_signature_cooldown" -> {
                hints += hint(
                    code = "ai_budget_guard",
                    severity = AdvisorySeverity.LOW,
                    source = "ai_support",
                    summary = "AI support sedang ditahan agar tidak boros limit gratis.",
                    rationale = listOf(
                        "Bot tetap jalan dengan logika kuantitatif utama.",
                        "AI hanya support kecil dan sekarang memakai cache atau cooldown.",
                    ),
                    now = now,
                )
            }
        }

        if (aiUsedNetwork && cycle.marketSnapshot.marketOpportunityScore < 0.40) {
            hints += hint(
                code = "ai_call_low_value_market",
                severity = AdvisorySeverity.LOW,
                source = "ai_support",
                summary = "AI support sempat dipakai saat market kurang menarik, jadi frekuensinya perlu tetap dijaga rendah.",
                rationale = listOf("Gunakan AI hanya saat shortlist kandidat benar-benar layak."),
                now = now,
            )
        }

        return SituationalLearningDecision(
            learningHints = hints.distinctBy { it.hintCode },
            updateRecommendations = recommendations
                .distinctBy { it.versionTag }
                .take(config.maxRecommendationsPerCycle),
        )
    }

    private fun hint(
        code: String,
        severity: AdvisorySeverity,
        source: String,
        summary: String,
        rationale: List<String>,
        now: Instant,
    ) = LearningHint(
        hintCode = code,
        severity = severity,
        source = source,
        summary = summary,
        rationale = rationale,
        generatedAt = now,
    )

    private fun recommendation(
        botId: BotId,
        deviceId: DeviceId,
        reasonCode: String,
        severity: AdvisorySeverity,
        title: String,
        summary: String,
        source: String,
        confidenceScore: Double,
        evidence: Map<String, Double>,
        actions: List<String>,
        now: Instant,
    ) = BotUpdateRecommendation(
        botId = botId,
        versionTag = buildVersionTag(reasonCode, now),
        reasonCode = reasonCode,
        severity = severity,
        title = title,
        summary = summary,
        source = source,
        confidenceScore = confidenceScore.coerceIn(0.0, 1.0),
        evidence = evidence,
        recommendedActions = actions,
        createdByDeviceId = deviceId,
        createdAt = now,
    )

    private fun buildVersionTag(reasonCode: String, now: Instant): String {
        val utcDate = now.toLocalDateTime(TimeZone.UTC).date
        return "${utcDate.year}${utcDate.monthNumber.toString().padStart(2, '0')}${utcDate.dayOfMonth.toString().padStart(2, '0')}-$reasonCode"
    }
}

private fun Double.asPctString(): String = "${(this * 100.0).toInt()}%"
private fun Double.asRatioString(): String = roundedTo(2).toString()
private fun Double.asRateString(): String = roundedTo(1).toString()

private fun Double.roundedTo(decimals: Int): Double {
    val factor = when (decimals) {
        0 -> 1.0
        1 -> 10.0
        2 -> 100.0
        3 -> 1_000.0
        else -> 10.0.pow(decimals)
    }
    return kotlin.math.round(this * factor) / factor
}
