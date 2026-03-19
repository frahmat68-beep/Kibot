package com.kibot.aisupport

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.AiSupportCandidate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.round

class GeminiSupportClient private constructor(
    private val config: GeminiSupportConfig,
    private val json: Json,
    private val httpClient: HttpClient,
) {
    constructor(config: GeminiSupportConfig) : this(
        config = config,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        },
        httpClient = createPlatformHttpClient(
            json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                isLenient = true
            },
            timeoutMillis = config.timeoutMillis,
        ),
    )

    suspend fun analyze(candidates: List<AiSupportCandidate>): List<AiPairSupportHint> {
        if (!config.isUsable || candidates.isEmpty()) return emptyList()

        val payload = GeminiGenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(
                            text = buildPrompt(candidates),
                        ),
                    ),
                ),
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.1,
                maxOutputTokens = 700,
                responseMimeType = "application/json",
            ),
        )

        val response = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent") {
            parameter("key", config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body<GeminiGenerateContentResponse>()

        val rawText = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?.trim()
            ?: return emptyList()

        val parsed = json.decodeFromString(AiSupportResponse.serializer(), rawText)
        val generatedAt = Clock.System.now()
        return parsed.pairs.map { item ->
            AiPairSupportHint(
                pairId = com.kibot.shared.models.PairId(item.pairId),
                supportBias = item.supportBias.coerceIn(0.0, 0.05),
                cautionBias = item.cautionBias.coerceIn(0.0, 0.05),
                cheapNominalWatch = item.cheapNominalWatch,
                rationale = item.rationale.trim(),
                generatedAt = generatedAt,
            )
        }
    }

    private fun buildPrompt(candidates: List<AiSupportCandidate>): String {
        val candidateJson = json.encodeToString(ListSerializer(AiSupportCandidate.serializer()), candidates)
        return """
            Kamu adalah support system sekunder untuk bot trading spot Indodax private.
            Tugasmu hanya memberi bias kecil pada shortlist pair yang SUDAH lolos filter kuantitatif.
            
            Aturan keras:
            - Jangan memutuskan entry/exit.
            - Jangan override safety rules.
            - Harga nominal murah BUKAN edge utama.
            - Boleh tandai cheap nominal watch hanya kalau likuiditas, spread, slippage, dan trend tetap layak.
            - Support bias dan caution bias harus kecil, 0.00 sampai 0.05.
            - Fokus ke pair yang berpotensi produktif untuk modal kecil tanpa mengabaikan mikrostruktur.
            - Jika pair terlihat hype/rawan/liquidity trap, naikkan caution, bukan support.
            
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
            $candidateJson
        """.trimIndent()
    }
}

class GeminiSupportCoordinator(
    private val config: GeminiSupportConfig,
    private val client: GeminiSupportClient,
) {
    private var lastSignature: String? = null
    private var lastRequestedAt: Instant? = null
    private var lastHints: List<AiPairSupportHint> = emptyList()

    suspend fun evaluate(candidates: List<AiSupportCandidate>, now: Instant = Clock.System.now()): List<AiPairSupportHint> {
        if (!config.isUsable || candidates.isEmpty()) return emptyList()
        val trimmed = candidates.take(config.maxCandidates)
        val signature = trimmed.joinToString("|") {
            buildString {
                append(it.pairId.value)
                append(':')
                append(it.rankingScore.round3String())
                append(':')
                append(it.marketOpportunityScore.round3String())
                append(':')
                append(it.spreadPct.round3String())
            }
        }

        val requestedAt = lastRequestedAt
        val ageMinutes = requestedAt?.let { (now - it).inWholeMinutes } ?: Long.MAX_VALUE
        if (signature == lastSignature && ageMinutes in 0 until config.minIntervalMinutes.toLong()) {
            return lastHints
        }
        if (requestedAt != null && ageMinutes in 0 until config.minIntervalMinutes.toLong()) {
            return lastHints
        }

        return runCatching { client.analyze(trimmed) }
            .onSuccess {
                lastSignature = signature
                lastRequestedAt = now
                lastHints = it
            }
            .getOrElse { lastHints }
    }
}

@Serializable
private data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    @SerialName("generationConfig")
    val generationConfig: GeminiGenerationConfig,
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart>,
)

@Serializable
private data class GeminiPart(
    val text: String,
)

@Serializable
private data class GeminiGenerationConfig(
    val temperature: Double,
    @SerialName("maxOutputTokens")
    val maxOutputTokens: Int,
    @SerialName("responseMimeType")
    val responseMimeType: String,
)

@Serializable
private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
)

@Serializable
private data class AiSupportResponse(
    val pairs: List<AiSupportResponseItem> = emptyList(),
)

@Serializable
private data class AiSupportResponseItem(
    @SerialName("pair_id")
    val pairId: String,
    @SerialName("support_bias")
    val supportBias: Double = 0.0,
    @SerialName("caution_bias")
    val cautionBias: Double = 0.0,
    @SerialName("cheap_nominal_watch")
    val cheapNominalWatch: Boolean = false,
    val rationale: String = "",
)

private fun Double.round3String(): String = (round(this * 1000.0) / 1000.0).toString()
