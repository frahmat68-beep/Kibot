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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.round
import kotlin.time.Duration.Companion.minutes

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
                maxOutputTokens = config.maxOutputTokens,
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
                supportBias = item.supportBias.coerceIn(0.0, 0.08),
                cautionBias = item.cautionBias.coerceIn(0.0, 0.06),
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
            - Support bias dan caution bias harus kecil, 0.00 sampai 0.08 untuk support dan 0.00 sampai 0.06 untuk caution.
            - Fokus ke pair yang berpotensi produktif untuk modal kecil tanpa mengabaikan mikrostruktur.
            - Jika shortlist berubah material atau ada breakout jelas, utamakan pair yang paling mungkin bergerak cepat, bukan pair yang sekadar aman.
            - Jika pair terlihat mandek/rotasi lebih layak ke pair lain, turunkan support dan naikkan caution.
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
    private data class ShortlistSnapshot(
        val signature: String,
        val topPairsSignature: String,
        val topCandidateStrength: Double,
        val breadthStrength: Double,
    )

    private var lastSignature: String? = null
    private var lastSnapshot: ShortlistSnapshot? = null
    private var lastRequestedAt: Instant? = null
    private var lastHints: List<AiPairSupportHint> = emptyList()
    private var cooldownUntil: Instant? = null
    private val requestHistory = mutableListOf<Instant>()

    suspend fun evaluate(
        candidates: List<AiSupportCandidate>,
        now: Instant = Clock.System.now(),
    ): GeminiSupportEvaluation {
        if (!config.isUsable || candidates.isEmpty()) return GeminiSupportEvaluation()
        requestHistory.removeAll { requestAt -> (now - requestAt).inWholeHours >= 24 }

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
        val snapshot = ShortlistSnapshot(
            signature = signature,
            topPairsSignature = trimmed.take(3).joinToString("|") { it.pairId.value },
            topCandidateStrength = trimmed.firstOrNull()?.let {
                (it.rankingScore * 0.55) + (it.marketOpportunityScore * 0.45)
            } ?: 0.0,
            breadthStrength = trimmed.take(3).map {
                (it.rankingScore * 0.50) + (it.marketOpportunityScore * 0.35) + (it.trendQualityScore * 0.15)
            }.average().takeIf { !it.isNaN() } ?: 0.0,
        )

        val requestedAt = lastRequestedAt
        val ageMinutes = requestedAt?.let { (now - it).inWholeMinutes } ?: Long.MAX_VALUE
        val cacheFresh = ageMinutes in 0..75
        val materialChange = isMaterialShortlistChange(previous = lastSnapshot, current = snapshot)
        val breakoutTrigger = snapshot.topCandidateStrength >= 0.84 || snapshot.breadthStrength >= 0.80
        val expeditedRequeryAllowed = materialChange && breakoutTrigger && ageMinutes >= 35
        val reusableCachedHints = cacheFresh && !materialChange && lastHints.isNotEmpty()

        cooldownUntil?.let { blockedUntil ->
            if (now < blockedUntil) {
                return GeminiSupportEvaluation(
                    hints = if (reusableCachedHints) lastHints else emptyList(),
                    reusedCachedHints = reusableCachedHints,
                    blockedReason = "failure_cooldown",
                )
            }
        }

        if (signature == lastSignature && ageMinutes in 0 until config.minIntervalMinutes.toLong()) {
            return GeminiSupportEvaluation(
                hints = if (cacheFresh) lastHints else emptyList(),
                reusedCachedHints = cacheFresh && lastHints.isNotEmpty(),
                blockedReason = if (cacheFresh) "same_signature_cooldown" else "stale_same_signature",
            )
        }
        if (requestedAt != null && ageMinutes in 0 until config.minIntervalMinutes.toLong() && !expeditedRequeryAllowed) {
            return GeminiSupportEvaluation(
                hints = if (cacheFresh && !materialChange) lastHints else emptyList(),
                reusedCachedHints = cacheFresh && !materialChange && lastHints.isNotEmpty(),
                blockedReason = if (materialChange) "material_change_wait" else "global_cooldown",
            )
        }

        val hourlyRequests = requestHistory.count { requestAt ->
            (now - requestAt).inWholeMinutes in 0 until 60
        }
        if (hourlyRequests >= config.hourlyRequestBudget) {
            return GeminiSupportEvaluation(
                hints = if (reusableCachedHints) lastHints else emptyList(),
                reusedCachedHints = reusableCachedHints,
                blockedReason = "hourly_budget",
            )
        }

        val currentUtcDate = now.toLocalDateTime(TimeZone.UTC).date
        val dailyRequests = requestHistory.count { requestAt ->
            requestAt.toLocalDateTime(TimeZone.UTC).date == currentUtcDate
        }
        if (dailyRequests >= config.dailyRequestBudget) {
            return GeminiSupportEvaluation(
                hints = if (reusableCachedHints) lastHints else emptyList(),
                reusedCachedHints = reusableCachedHints,
                blockedReason = "daily_budget",
            )
        }

        return runCatching { client.analyze(trimmed) }
            .fold(
                onSuccess = {
                    lastSignature = signature
                    lastSnapshot = snapshot
                    lastRequestedAt = now
                    lastHints = it
                    cooldownUntil = null
                    requestHistory += now
                    GeminiSupportEvaluation(
                        hints = it,
                        usedNetwork = true,
                    )
                },
                onFailure = {
                    cooldownUntil = now + config.failureCooldownMinutes.minutes
                    GeminiSupportEvaluation(
                        hints = if (cacheFresh && !materialChange) lastHints else emptyList(),
                        reusedCachedHints = cacheFresh && !materialChange && lastHints.isNotEmpty(),
                        blockedReason = "request_failed",
                    )
                },
            )
    }

    private fun isMaterialShortlistChange(
        previous: ShortlistSnapshot?,
        current: ShortlistSnapshot,
    ): Boolean {
        if (previous == null) return true
        if (previous.topPairsSignature != current.topPairsSignature) return true
        if (kotlin.math.abs(previous.topCandidateStrength - current.topCandidateStrength) >= 0.055) return true
        if (kotlin.math.abs(previous.breadthStrength - current.breadthStrength) >= 0.045) return true
        return false
    }
}

data class GeminiSupportEvaluation(
    val hints: List<AiPairSupportHint> = emptyList(),
    val usedNetwork: Boolean = false,
    val reusedCachedHints: Boolean = false,
    val blockedReason: String? = null,
)

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
