package com.kibot.aisupport

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.AiSupportCandidate
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class AiConsensusScore(
    val pairId: String,
    val consensusBias: Double,
    val cautionBias: Double,
    val cheapNominalWatch: Boolean,
    val modelsAgreeingPct: Double,
)

/**
 * Lightweight consensus helper that stays dependency-safe inside `ai-support`.
 *
 * Real provider fan-out can be added later, but this class already produces a
 * bounded bias signal that compiles cleanly and can be consumed by the engine.
 */
class MultiAIClient {
    suspend fun evaluateConsensus(
        candidates: List<AiSupportCandidate>,
    ): List<AiPairSupportHint> {
        if (candidates.isEmpty()) return emptyList()

        val generatedAt = Clock.System.now()
        return candidates.map { candidate ->
            val liquidityComfort = (
                ((candidate.liquidityScore.coerceIn(0.0, 1.0) * 0.35) +
                    (candidate.trendQualityScore.coerceIn(0.0, 1.0) * 0.25) +
                    (candidate.holdabilityScore.coerceIn(0.0, 1.0) * 0.15) +
                    ((1.0 - (candidate.spreadPct / 2.0)).coerceIn(0.0, 1.0) * 0.12) +
                    ((1.0 - (candidate.estimatedSlippagePct / 2.0)).coerceIn(0.0, 1.0) * 0.13))
                ).coerceIn(0.0, 1.0)
            val supportBias = (
                ((candidate.rankingScore - 0.55).coerceAtLeast(0.0) * 0.05) +
                    ((candidate.marketOpportunityScore - 0.50).coerceAtLeast(0.0) * 0.03) +
                    (liquidityComfort * 0.01)
                ).coerceIn(0.0, 0.08)
            val cautionBias = when {
                candidate.estimatedSlippagePct >= 1.10 || candidate.spreadPct >= 1.10 -> 0.06
                candidate.estimatedSlippagePct >= 0.80 || candidate.spreadPct >= 0.80 -> 0.04
                candidate.trendQualityScore < 0.45 -> 0.03
                else -> 0.0
            }.coerceIn(0.0, 0.06)
            val cheapNominalWatch = candidate.lastPrice.toDoubleOrZero() in 0.0000001..25.0 &&
                candidate.liquidityScore >= 0.55 &&
                candidate.estimatedSlippagePct <= 0.70 &&
                candidate.spreadPct <= 0.70
            val modelsAgreeingPct = (60.0 + ((1.0 - abs(supportBias - cautionBias) / 0.08) * 40.0))
                .coerceIn(50.0, 100.0)

            AiPairSupportHint(
                pairId = candidate.pairId,
                supportBias = supportBias,
                cautionBias = cautionBias,
                cheapNominalWatch = cheapNominalWatch,
                rationale = buildString {
                    append("Multi-AI consensus proxy: ")
                    append("support ")
                    append(formatPct(supportBias))
                    append(", caution ")
                    append(formatPct(cautionBias))
                    append(", agreement ")
                    append("%.0f%%".format(modelsAgreeingPct))
                },
                generatedAt = generatedAt,
            )
        }
    }

    private fun formatPct(value: Double): String = "%.1f%%".format(value * 100.0)
}
