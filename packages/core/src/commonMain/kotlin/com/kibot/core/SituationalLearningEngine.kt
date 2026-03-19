package com.kibot.core

import com.kibot.shared.models.AdvisorySeverity
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotUpdateRecommendation
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.LearningHint
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class SituationalLearningConfig(
    val falseEntryAlertPct: Double = 0.28,
    val lowProductiveUtilizationPct: Double = 0.34,
    val highMissedOpportunityPct: Double = 0.22,
    val healthyMarketOpportunityScore: Double = 0.66,
    val lowNoTradeQualityScore: Double = 0.40,
    val strongExpectancyPct: Double = 0.14,
    val maxRecommendationsPerCycle: Int = 2,
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
