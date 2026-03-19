package com.kibot.macengine.config

import com.kibot.aisupport.GeminiSupportConfig
import com.kibot.controlplane.ControlPlaneConfig
import com.kibot.core.DeviceRegistration
import com.kibot.indodax.IndodaxClientConfig
import com.kibot.indodax.IndodaxCredentials
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DevicePlatform
import com.kibot.shared.models.DeviceRole
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class MacRuntimeConfig(
    val port: Int,
    val bindHost: String,
    val controlPlane: ControlPlaneConfig,
    val device: DeviceRegistration,
    val pollIntervalMillis: Long,
    val leaseTtlSeconds: Int,
    val enableLiveExecution: Boolean,
    val aiSupportConfig: GeminiSupportConfig?,
    val analysisPublishIntervalMillis: Long,
    val strategyMetricsPublishIntervalMillis: Long,
    val indodaxCredentials: IndodaxCredentials?,
    val indodaxClientConfig: IndodaxClientConfig,
)

object MacRuntimeConfigLoader {
    fun load(cwd: Path = Paths.get("").toAbsolutePath()): MacRuntimeConfig {
        val fileValues = linkedMapOf<String, String>()
        candidateEnvFiles(cwd).forEach { path ->
            if (Files.exists(path)) {
                parseEnvFile(path).forEach { (key, value) -> fileValues[key] = value }
            }
        }

        val merged = fileValues + System.getenv().filterValues { it.isNotBlank() }

        fun required(name: String): String = merged[name]?.takeIf { it.isNotBlank() }
            ?: error("Missing required environment variable: $name")

        fun optional(name: String): String? = merged[name]?.takeIf { it.isNotBlank() }

        val botId = BotId(optional("BOT_ID") ?: "main")
        val apiKey = optional("INDODAX_API_KEY")
        val apiSecret = optional("INDODAX_API_SECRET")

        return MacRuntimeConfig(
            port = optional("MAC_ENGINE_PORT")?.toIntOrNull() ?: 8787,
            bindHost = optional("MAC_ENGINE_BIND_HOST") ?: "0.0.0.0",
            controlPlane = ControlPlaneConfig(
                supabaseUrl = required("SUPABASE_URL"),
                supabaseAnonKey = required("SUPABASE_ANON_KEY"),
                userEmail = required("SUPABASE_USER_EMAIL"),
                userPassword = required("SUPABASE_USER_PASSWORD"),
                botId = botId,
            ),
            device = DeviceRegistration(
                deviceId = DeviceId(optional("DEVICE_ID") ?: "macbook-main"),
                displayName = optional("DEVICE_DISPLAY_NAME") ?: defaultDisplayName(),
                platform = DevicePlatform.MACOS,
                role = DeviceRole.STANDBY,
            ),
            pollIntervalMillis = optional("BOT_POLL_INTERVAL_MS")?.toLongOrNull() ?: 5_000L,
            leaseTtlSeconds = optional("BOT_DEFAULT_LEASE_TTL_SECONDS")?.toIntOrNull() ?: 30,
            enableLiveExecution = optional("BOT_ENABLE_LIVE_EXECUTION")?.equals("true", ignoreCase = true) == true,
            aiSupportConfig = optional("GEMINI_SUPPORT_API_KEY")
                ?.takeIf { optional("GEMINI_SUPPORT_ENABLED")?.equals("true", ignoreCase = true) == true }
                ?.let { apiKey ->
                    GeminiSupportConfig(
                        enabled = true,
                        apiKey = apiKey,
                        model = optional("GEMINI_SUPPORT_MODEL") ?: "gemini-2.0-flash-lite",
                        maxCandidates = optional("GEMINI_SUPPORT_MAX_CANDIDATES")?.toIntOrNull() ?: 6,
                        minIntervalMinutes = optional("GEMINI_SUPPORT_MIN_INTERVAL_MINUTES")?.toIntOrNull() ?: 240,
                        timeoutMillis = optional("GEMINI_SUPPORT_TIMEOUT_MS")?.toLongOrNull() ?: 15_000L,
                        maxOutputTokens = optional("GEMINI_SUPPORT_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: 384,
                        hourlyRequestBudget = optional("GEMINI_SUPPORT_HOURLY_REQUEST_BUDGET")?.toIntOrNull() ?: 2,
                        dailyRequestBudget = optional("GEMINI_SUPPORT_DAILY_REQUEST_BUDGET")?.toIntOrNull() ?: 12,
                        failureCooldownMinutes = optional("GEMINI_SUPPORT_FAILURE_COOLDOWN_MINUTES")?.toIntOrNull() ?: 120,
                    )
                },
            analysisPublishIntervalMillis = optional("BOT_ANALYSIS_PUBLISH_INTERVAL_MS")?.toLongOrNull() ?: 30_000L,
            strategyMetricsPublishIntervalMillis = optional("BOT_STRATEGY_METRICS_PUBLISH_INTERVAL_MS")?.toLongOrNull() ?: 300_000L,
            indodaxCredentials = when {
                apiKey == null && apiSecret == null -> null
                apiKey != null && apiSecret != null -> IndodaxCredentials(apiKey = apiKey, apiSecret = apiSecret)
                else -> error("INDODAX_API_KEY and INDODAX_API_SECRET must be set together.")
            },
            indodaxClientConfig = IndodaxClientConfig(
                publicBaseUrl = optional("INDODAX_PUBLIC_BASE_URL") ?: "https://indodax.com/api",
                privateBaseUrl = optional("INDODAX_PRIVATE_BASE_URL") ?: "https://indodax.com/tapi",
                tradeApiV2BaseUrl = optional("INDODAX_TRADE_API_V2_BASE_URL") ?: "https://tapi.indodax.com",
                publicWebSocketUrl = optional("INDODAX_WS_PUBLIC_URL") ?: "wss://ws1.indodax.com/ws",
                privateWebSocketUrl = optional("INDODAX_WS_PRIVATE_URL") ?: "wss://pws.indodax.com/ws/?cf_ws_frame_ping_pong=true",
            ),
        )
    }

    private fun parseEnvFile(path: Path): Map<String, String> {
        return Files.readAllLines(path)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { line ->
                val (key, rawValue) = line.split("=", limit = 2)
                key.trim() to rawValue.trim().removeSurrounding("\"")
            }
    }

    private fun defaultDisplayName(): String {
        return runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "MacBook Engine"
    }

    private fun candidateEnvFiles(start: Path): List<Path> {
        val dirs = buildList {
            var current: Path? = start
            repeat(6) {
                if (current == null) return@repeat
                add(current)
                current = current?.parent
            }
        }.distinct()

        return dirs
            .flatMap { dir ->
                listOf(
                    dir.resolve(".env"),
                    dir.resolve("apps/mac-engine/.env"),
                )
            }
            .distinct()
    }
}
