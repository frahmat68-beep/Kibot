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
 * Keeps AI fan-out best-effort. If providers fail, we still return a bounded
 * math baseline so runtime and deploy pipelines stay healthy.
 */
class MultiAIClient(
    private val providers: List<AiProvider> = emptyList(),
) {
    suspend fun evaluateConsensus(
        candidates: List<AiSupportCandidate>,
    ): List<AiPairSupportHint> {
        if (candidates.isEmpty()) return emptyList()

        val baseline = generateMathBaseline(candidates)
        val providerHints = providers
            .filter { it.isAvailable }
            .mapNotNull { provider ->
                runCatching { provider.analyze(candidates) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }

        if (providerHints.isEmpty()) return baseline
        return aggregateLegionHints(baseline, providerHints)
    }

    suspend fun researchHolding(request: HoldingResearchRequest): HoldingResearchDecision? {
        val availableProviders = providers.filter { it.isAvailable }
        if (availableProviders.isEmpty()) return null

        val decisions = availableProviders.mapNotNull { provider ->
            runCatching { provider.researchHolding(request) }.getOrNull()
        }
        if (decisions.isEmpty()) return null

        val emergencyDecision = decisions.firstOrNull { it.action == HoldingResearchAction.EMERGENCY_DUMP }
        return emergencyDecision ?: decisions.first()
    }

    private fun generateMathBaseline(candidates: List<AiSupportCandidate>): List<AiPairSupportHint> {
        val generatedAt = Clock.System.now()
        return candidates.map { candidate ->
            val liquidityComfort = (
                (candidate.liquidityScore.coerceIn(0.0, 1.0) * 0.35) +
                    (candidate.trendQualityScore.coerceIn(0.0, 1.0) * 0.25) +
                    (candidate.holdabilityScore.coerceIn(0.0, 1.0) * 0.15) +
                    ((1.0 - (candidate.spreadPct / 2.0)).coerceIn(0.0, 1.0) * 0.12) +
                    ((1.0 - (candidate.estimatedSlippagePct / 2.0)).coerceIn(0.0, 1.0) * 0.13)
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

            AiPairSupportHint(
                pairId = candidate.pairId,
                supportBias = supportBias,
                cautionBias = cautionBias,
                cheapNominalWatch = cheapNominalWatch,
                rationale = buildString {
                    append("Math baseline: support ")
                    append(formatPct(supportBias))
                    append(", caution ")
                    append(formatPct(cautionBias))
                },
                generatedAt = generatedAt,
            )
        }
    }

    private fun aggregateLegionHints(
        baseline: List<AiPairSupportHint>,
        providerResults: List<List<AiPairSupportHint>>,
    ): List<AiPairSupportHint> {
        return baseline.map { baseHint ->
            val aiOpinions = providerResults.mapNotNull { hints ->
                hints.find { it.pairId == baseHint.pairId }
            }

            if (aiOpinions.isEmpty()) return@map baseHint

            val avgSupport = aiOpinions.map { it.supportBias }.average()
            val avgCaution = aiOpinions.map { it.cautionBias }.average()
            val blendedSupport = ((baseHint.supportBias * 0.6) + (avgSupport * 0.4)).coerceIn(0.0, 0.10)
            val blendedCaution = ((baseHint.cautionBias * 0.6) + (avgCaution * 0.4)).coerceIn(0.0, 0.08)
            val agreement = (60.0 + (1.0 - abs(avgSupport - avgCaution) / 0.08) * 40.0).coerceIn(50.0, 100.0)

            baseHint.copy(
                supportBias = blendedSupport,
                cautionBias = blendedCaution,
                cheapNominalWatch = baseHint.cheapNominalWatch || aiOpinions.any { it.cheapNominalWatch },
                rationale = buildString {
                    append("Legion consensus ")
                    append("%.0f%%".format(agreement))
                    append(": ")
                    append(aiOpinions.first().rationale.take(120))
                },
            )
        }
    }

    private fun formatPct(value: Double): String = "%.1f%%".format(value * 100.0)
}
