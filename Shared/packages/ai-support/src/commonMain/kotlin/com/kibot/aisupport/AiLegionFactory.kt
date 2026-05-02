package com.kibot.aisupport

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/**
 * Factory to build the full AI Legion based on provided environment keys.
 */
object AiLegionFactory {
    fun build(
        env: Map<String, String>,
        httpClient: HttpClient,
        json: Json
    ): MultiAIClient {
        val providers = mutableListOf<AiProvider>()

        // 1. Google Gemini
        val geminiApiKey = env["GEMINI_SUPPORT_API_KEY"] ?: env["BINANCE_GEMINI_SUPPORT_API_KEY"]
        if (!geminiApiKey.isNullOrBlank()) {
            val config = GeminiSupportConfig(
                enabled = true,
                apiKey = geminiApiKey,
                model = env["GEMINI_SUPPORT_MODEL"] ?: "gemini-2.0-flash-lite"
            )
            providers.add(GeminiSupportClient(config))
        }

        // 2. NVIDIA NIM (User provided new key)
        val nvidiaKey = env["NVIDIA_API_KEY"] ?: "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"
        if (nvidiaKey.isNotBlank()) {
            providers.add(OpenAiCompatibleProvider(
                providerName = "NVIDIA NIM",
                apiKey = nvidiaKey,
                baseUrl = "https://integrate.api.nvidia.com/v1",
                model = "meta/llama-3.1-70b-instruct",
                isEnabled = true,
                httpClient = httpClient,
                json = json
            ))
        }

        // 3. Groq Cloud
        val groqKey = env["GROQ_API_KEY"] ?: env["BINANCE_GROQ_API_KEY"]
        if (!groqKey.isNullOrBlank()) {
            providers.add(OpenAiCompatibleProvider(
                providerName = "Groq Cloud",
                apiKey = groqKey,
                baseUrl = "https://api.groq.com/openai/v1",
                model = "llama3-70b-8192",
                isEnabled = true,
                httpClient = httpClient,
                json = json
            ))
        }

        // 4. OpenRouter
        val orKey = env["OPENROUTER_API_KEY"] ?: env["BINANCE_OPENROUTER_API_KEY"]
        if (!orKey.isNullOrBlank()) {
            providers.add(OpenAiCompatibleProvider(
                providerName = "OpenRouter",
                apiKey = orKey,
                baseUrl = "https://openrouter.ai/api/v1",
                model = "meta-llama/llama-3.1-70b-instruct",
                isEnabled = true,
                httpClient = httpClient,
                json = json
            ))
        }

        // 5. Zero-Config Fallback (Always added as last resort)
        providers.add(PublicFallbackProvider(httpClient, json))

        return MultiAIClient(providers)
    }
}
