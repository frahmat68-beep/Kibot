package com.kibot.aisupport

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.AiSupportCandidate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A zero-configuration provider using public AI endpoints (Pollinations.ai).
 * No API key required. Best used as a persistent fallback.
 */
class PublicFallbackProvider(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : AiProvider {

    override val providerName: String = "Public AI (Pollinations)"
    override val isAvailable: Boolean = true // Always available as no key is needed

    @Serializable
    private data class PollinationsRequest(
        val messages: List<Message>,
        val model: String = "llama",
        val jsonMode: Boolean = true
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    override suspend fun analyze(candidates: List<AiSupportCandidate>): List<AiPairSupportHint> {
        val prompt = """
            Return a JSON object ranking these crypto pairs.
            Format: {"pairs": [{"pair_id": "...", "support_bias": 0.02, "caution_bias": 0.01, "rationale": "..."}]}
            
            Shortlist:
            ${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AiSupportCandidate.serializer()), candidates)}
        """.trimIndent()

        return runCatching {
            val request = PollinationsRequest(
                messages = listOf(Message("user", prompt))
            )
            
            // Pollinations text API
            val rawText = httpClient.post("https://text.pollinations.ai/") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<String>()

            parseStandardResponse(rawText)
        }.getOrDefault(emptyList())
    }

    override suspend fun researchHolding(request: HoldingResearchRequest): HoldingResearchDecision? {
        val prompt = "Assess crypto holding ${request.pairId} (PnL ${request.pnlPct}%). Reply ONLY: ACTION: HOLD or ACTION: EMERGENCY_DUMP"
        
        return runCatching {
            val response = httpClient.post("https://text.pollinations.ai/") {
                contentType(ContentType.Application.Json)
                setBody(PollinationsRequest(messages = listOf(Message("user", prompt)), jsonMode = false))
            }.body<String>()

            HoldingResearchDecision(
                pairId = request.pairId,
                action = parseHoldingResearchAction(response),
                rawResponse = response
            )
        }.getOrNull()
    }

    private fun parseStandardResponse(rawText: String): List<AiPairSupportHint> {
        return runCatching {
            val cleanJson = extractJson(rawText)
            val parsed = json.decodeFromString(AiSupportResponse.serializer(), cleanJson)
            val generatedAt = Clock.System.now()
            parsed.pairs.map { item ->
                AiPairSupportHint(
                    pairId = com.kibot.shared.models.PairId(item.pairId),
                    supportBias = item.supportBias.coerceIn(0.0, 0.08),
                    cautionBias = item.cautionBias.coerceIn(0.0, 0.06),
                    cheapNominalWatch = false,
                    rationale = item.rationale.trim(),
                    generatedAt = generatedAt
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun extractJson(text: String): String {
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        return if (firstBrace != -1 && lastBrace != -1) {
            text.substring(firstBrace, lastBrace + 1)
        } else text
    }
}

@Serializable
private data class AiSupportResponse(val pairs: List<AiSupportResponseItem> = emptyList())

@Serializable
private data class AiSupportResponseItem(
    val pair_id: String,
    val support_bias: Double = 0.0,
    val caution_bias: Double = 0.0,
    val rationale: String = ""
)
