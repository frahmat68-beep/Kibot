package com.kibot.aisupport

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.AiSupportCandidate

/**
 * Universal interface for all AI providers in the KiBot Trinity Legion.
 * Allows for seamless switching between Gemini, Groq, Nvidia, etc.
 */
interface AiProvider {
    val providerName: String
    val isAvailable: Boolean

    /**
     * Analyze a list of quantitative candidates and provide AI-driven bias/rationale.
     */
    suspend fun analyze(candidates: List<AiSupportCandidate>): List<AiPairSupportHint>

    /**
     * Specifically assess a current holding to decide whether to HOLD or EMERGENCY_DUMP.
     */
    suspend fun researchHolding(request: HoldingResearchRequest): HoldingResearchDecision?
}
