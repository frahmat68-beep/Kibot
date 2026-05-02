package com.kibot.aisupport

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.AiSupportCandidate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A generic provider for any AI service that follows the OpenAI chat completions format.
 * Covers NVIDIA NIM, Groq, OpenRouter, and more.
 */
class OpenAiCompatibleProvider(
    override val providerName: String,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val isEnabled: Boolean,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : AiProvider {

    override val isAvailable: Boolean get() = isEnabled && apiKey.isNotBlank()

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.1,
        @SerialName("response_format") val responseFormat: ResponseFormat? = null
    )

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(val choices: List<Choice>)

    @Serializable
    private data class Choice(val message: ChatMessage)

    override suspend fun analyze(candidates: List<AiSupportCandidate>): List<AiPairSupportHint> {
        if (!isAvailable || candidates.isEmpty()) return emptyList()

        val prompt = buildStandardPrompt(candidates)
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("user", prompt)),
            responseFormat = ResponseFormat("json_object")
        )

        val response = httpClient.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<ChatResponse>()

        val rawText = response.choices.firstOrNull()?.message?.content ?: return emptyList()
        return parseStandardResponse(rawText)
    }

    override suspend fun researchHolding(request: HoldingResearchRequest): HoldingResearchDecision? {
        if (!isAvailable) return null

        val prompt = buildHoldingPrompt(request)
        val apiRequest = ChatRequest(
            model = model,
            messages = listOf(ChatMessage("user", prompt)),
            temperature = 0.0
        )

        val response = httpClient.post("$baseUrl/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(apiRequest)
        }.body<ChatResponse>()

        val rawText = response.choices.firstOrNull()?.message?.content ?: return null
        return HoldingResearchDecision(
            pairId = request.pairId,
            action = parseHoldingResearchAction(rawText),
            rawResponse = rawText
        )
    }

    private fun buildStandardPrompt(candidates: List<AiSupportCandidate>): String {
        // We reuse the same logic from Gemini for consistency
        return """
            Kamu adalah support system sekunder untuk bot trading spot Indodax private.
            Tugasmu hanya memberi bias kecil pada shortlist pair yang SUDAH lolos filter kuantitatif.
            
            Balas STRICT JSON object dengan format:
            {
              "pairs": [
                {
                  "pair_id": "xrp_idr",
                  "support_bias": 0.02,
                  "caution_bias": 0.01,
                  "cheap_nominal_watch": false,
                  "rationale": "Likuid, spread sehat, momentum stabil"
                }
              ]
            }
            
            Shortlist:
            ${json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AiSupportCandidate.serializer()), candidates)}
        """.trimIndent()
    }

    private fun parseStandardResponse(rawText: String): List<AiPairSupportHint> {
        return runCatching {
            val parsed = json.decodeFromString(AiSupportResponse.serializer(), rawText)
            val generatedAt = Clock.System.now()
            parsed.pairs.map { item ->
                AiPairSupportHint(
                    pairId = com.kibot.shared.models.PairId(item.pairId),
                    supportBias = item.supportBias.coerceIn(0.0, 0.08),
                    cautionBias = item.cautionBias.coerceIn(0.0, 0.06),
                    cheapNominalWatch = item.cheapNominalWatch,
                    rationale = item.rationale.trim(),
                    generatedAt = generatedAt
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildHoldingPrompt(request: HoldingResearchRequest): String {
        return """
            Assess this crypto holding for emergency exit:
            - Pair: ${request.pairId}
            - Minutes Held: ${request.holdingMinutes}
            - PnL %: ${request.pnlPct}
            
            Reply with ONLY: ACTION: HOLD or ACTION: EMERGENCY_DUMP
        """.trimIndent()
    }
}
