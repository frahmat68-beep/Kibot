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
 * The Legion Coordinator that manages multiple AI providers (Gemini, Groq, Nvidia, etc.)
 * and aggregates their findings into a unified consensus signal.
 */
class MultiAIClient(
    private val providers: List<AiProvider>
) {
    suspend fun evaluateConsensus(
        candidates: List<AiSupportCandidate>,
    ): List<AiPairSupportHint> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Get hints from all available providers in parallel
        val providerHints = providers
            .filter { it.isAvailable }
            .map { provider ->
                runCatching { provider.analyze(candidates) }
                    .getOrDefault(emptyList())
            }

        // 2. Generate the base math-driven hints (the "Deterministic Baseline")
        val mathHints = generateMathBaseline(candidates)

        if (providerHints.isEmpty()) return mathHints

        // 3. Aggregate: Merger the Math Baseline with AI Consensus
        return aggregateLegionHints(mathHints, providerHints)
    }

    /**
     * Research a holding using all available AI brains.
     */
    suspend fun researchHolding(request: HoldingResearchRequest): HoldingResearchDecision? {
        val availableProviders = providers.filter { it.isAvailable }
        if (availableProviders.isEmpty()) return null

        // For research, we take the most cautious result (Emergency Exit if ANY smart brain says so)
        val decisions = availableProviders.mapNotNull { provider ->
            provider.researchHolding(request)
        }

        if (decisions.isEmpty()) return null

        val emergencyVotes = decisions.count { it.action == HoldingResearchAction.EMERGENCY_DUMP }
        
        return if (emergencyVotes > 0) {
            decisions.first { it.action == HoldingResearchAction.EMERGENCY_DUMP }
        } else {
            decisions.first()
        }
    }

    private fun generateMathBaseline(candidates: List<AiSupportCandidate>): List<AiPairSupportHint> {
        val generatedAt = Clock.System.now()
    private fun aggregateLegionHints(
        baseline: List<AiPairSupportHint>,
        providerResults: List<List<AiPairSupportHint>>
    ): List<AiPairSupportHint> {
        return baseline.map { baseHint ->
            val aiOpinions = providerResults.mapNotNull { hints -> 
                hints.find { it.pairId == baseHint.pairId } 
            }
            
            if (aiOpinions.isEmpty()) return@map baseHint

            val avgSupport = (baseHint.supportBias + aiOpinions.map { it.supportBias }.average()) / 2.0
            val avgCaution = (baseHint.cautionBias + aiOpinions.map { it.cautionBias }.average()) / 2.0
            val agreement = (60.0 + (aiOpinions.size.toDouble() / providers.size.toDouble() * 40.0)).coerceIn(50.0, 100.0)

            baseHint.copy(
                supportBias = avgSupport.coerceIn(0.0, 0.10),
                cautionBias = avgCaution.coerceIn(0.0, 0.08),
                rationale = "Legion Consensus (${aiOpinions.size} AI + Math): " + 
                        aiOpinions.firstOrNull()?.rationale?.take(60) + "..."
            )
        }
    }

    private fun formatPct(value: Double): String = "%.1f%%".format(value * 100.0)
}
