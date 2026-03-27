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
    val runtimeProfileKey: String,
    val port: Int,
    val bindHost: String,
    val controlPlane: ControlPlaneConfig,
    val device: DeviceRegistration,
    val pollIntervalMillis: Long,
    val exchangePingRefreshIntervalMillis: Long = 8_000L,
    val balanceRefreshIntervalMillis: Long = 8_000L,
    val openOrdersRefreshIntervalMillis: Long = 8_000L,
    val dailyRiskRefreshIntervalMillis: Long = 5_000L,
    val devicesRefreshIntervalMillis: Long = 30_000L,
    val commandsRefreshIntervalMillis: Long = 10_000L,
    val weeklySummaryRefreshIntervalMillis: Long = 60_000L,
    val recentOrdersRefreshIntervalMillis: Long = 15_000L,
    val recentFillsRefreshIntervalMillis: Long = 15_000L,
    val leaseTtlSeconds: Int,
    val enableLiveExecution: Boolean,
    val enableLanAdvertising: Boolean = true,
    val dashboardStatePollIntervalMillis: Long = 5_000L,
    val dashboardLogPollIntervalMillis: Long = 5_000L,
    val releaseLabel: String,
    val aiSupportConfig: GeminiSupportConfig?,
    val adaptiveAiPolicyPath: Path,
    val targetEnforcementMemoryPath: Path,
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
        val runtimeProfileKey = optional("BOT_PROFILE_KEY") ?: defaultRuntimeProfileKey(botId.value)
        val apiKey = optional("INDODAX_API_KEY")
        val apiSecret = optional("INDODAX_API_SECRET")
        val deviceRole = optional("DEVICE_ROLE")
            ?.uppercase()
            ?.let { raw -> runCatching { DeviceRole.valueOf(raw) }.getOrNull() }
            ?: DeviceRole.PRIMARY

        return MacRuntimeConfig(
            runtimeProfileKey = runtimeProfileKey,
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
                deviceId = DeviceId(optional("DEVICE_ID") ?: "oracle-main"),
                displayName = optional("DEVICE_DISPLAY_NAME") ?: defaultDisplayName(),
                platform = DevicePlatform.MACOS,
                role = deviceRole,
            ),
            pollIntervalMillis = optional("BOT_POLL_INTERVAL_MS")?.toLongOrNull() ?: 1_000L,
            exchangePingRefreshIntervalMillis = optional("BOT_EXCHANGE_PING_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 1_200L,
            balanceRefreshIntervalMillis = optional("BOT_BALANCE_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 1_400L,
            openOrdersRefreshIntervalMillis = optional("BOT_OPEN_ORDERS_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 1_400L,
            dailyRiskRefreshIntervalMillis = optional("BOT_DAILY_RISK_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 5_000L,
            devicesRefreshIntervalMillis = optional("BOT_DEVICES_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 30_000L,
            commandsRefreshIntervalMillis = optional("BOT_COMMANDS_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 1_000L,
            weeklySummaryRefreshIntervalMillis = optional("BOT_WEEKLY_SUMMARY_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 60_000L,
            recentOrdersRefreshIntervalMillis = optional("BOT_RECENT_ORDERS_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 1_400L,
            recentFillsRefreshIntervalMillis = optional("BOT_RECENT_FILLS_REFRESH_INTERVAL_MS")?.toLongOrNull() ?: 1_400L,
            leaseTtlSeconds = optional("BOT_DEFAULT_LEASE_TTL_SECONDS")?.toIntOrNull() ?: 30,
            enableLiveExecution = optional("BOT_ENABLE_LIVE_EXECUTION")?.equals("true", ignoreCase = true) == true,
            enableLanAdvertising = optional("MAC_ENGINE_ENABLE_LAN_ADVERTISE")?.equals("true", ignoreCase = true) ?: true,
            dashboardStatePollIntervalMillis = optional("MAC_DASHBOARD_STATE_POLL_INTERVAL_MS")?.toLongOrNull() ?: 5_000L,
            dashboardLogPollIntervalMillis = optional("MAC_DASHBOARD_LOG_POLL_INTERVAL_MS")?.toLongOrNull() ?: 5_000L,
            releaseLabel = optional("KIBOT_RELEASE_LABEL")
                ?: optional("KIBOT_RELEASE_TAG")
                ?: optional("GITHUB_RUN_NUMBER")?.let { "#$it" }
                ?: "#0",
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
            adaptiveAiPolicyPath = resolveScopedRuntimePath(
                explicit = optional("KIBOT_AI_ADAPTIVE_POLICY_PATH"),
                scopedDefault = cwd.resolve(".tmp/ai-audits/$runtimeProfileKey/latest/adaptive_policy.json"),
                legacyDefault = cwd.resolve(".tmp/ai-audits/latest/adaptive_policy.json"),
            ),
            targetEnforcementMemoryPath = resolveScopedRuntimePath(
                explicit = optional("KIBOT_TARGET_ENFORCEMENT_MEMORY_PATH"),
                scopedDefault = cwd.resolve(".tmp/runtime/$runtimeProfileKey/target_enforcement_memory.json"),
                legacyDefault = cwd.resolve(".tmp/runtime/target_enforcement_memory.json"),
            ),
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

    private fun defaultRuntimeProfileKey(botId: String): String {
        val normalized = botId.trim().lowercase()
        if (normalized.isBlank() || normalized == "main") return "indodax"
        return normalized
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "bot" }
    }

    private fun resolveScopedRuntimePath(
        explicit: String?,
        scopedDefault: Path,
        legacyDefault: Path,
    ): Path {
        explicit?.let { return Paths.get(it) }
        migrateLegacyRuntimeFile(legacyDefault, scopedDefault)
        return if (Files.exists(scopedDefault)) scopedDefault else if (Files.exists(legacyDefault)) legacyDefault else scopedDefault
    }

    private fun migrateLegacyRuntimeFile(
        legacyPath: Path,
        scopedPath: Path,
    ) {
        if (!Files.exists(legacyPath) || Files.exists(scopedPath)) return
        runCatching {
            scopedPath.parent?.let { Files.createDirectories(it) }
            Files.copy(legacyPath, scopedPath)
        }
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
