package com.kibot.macengine.config

import com.kibot.aisupport.GeminiSupportConfig
import com.kibot.binance.BinanceClientConfig
import com.kibot.binance.BinanceCredentials
import com.kibot.controlplane.ControlPlaneConfig
import com.kibot.core.DeviceRegistration
import com.kibot.indodax.IndodaxClientConfig
import com.kibot.indodax.IndodaxCredentials
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DevicePlatform
import com.kibot.shared.models.DeviceRole
import com.kibot.shared.models.LogLevel
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class ExchangeKind {
    INDODAX,
    BINANCE_SPOT,
}

data class MacRuntimeConfig(
    val runtimeProfileKey: String,
    val exchangeKind: ExchangeKind,
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
    val shadowMode: Boolean = false,
    val enableExecutionAiAssist: Boolean = false,
    val enableLanAdvertising: Boolean = true,
    val dashboardStatePollIntervalMillis: Long = 5_000L,
    val dashboardLogPollIntervalMillis: Long = 5_000L,
    val releaseLabel: String,
    val aiSupportConfig: GeminiSupportConfig?,
    val adaptiveAiPolicyPath: Path,
    val targetEnforcementMemoryPath: Path,
    val pnlResetAnchorPath: Path,
    val monthlyPnlAnchorPath: Path,
    val localPositionStateEnabled: Boolean,
    val localPositionStatePath: Path,
    val analysisPublishIntervalMillis: Long,
    val strategyMetricsPublishIntervalMillis: Long,
    val supabaseLogUploadEnabled: Boolean,
    val supabaseLogMinLevel: LogLevel,
    val supabaseNonCriticalWriteEnabled: Boolean,
    val indodaxCredentials: IndodaxCredentials?,
    val indodaxClientConfig: IndodaxClientConfig,
    val binanceCredentials: BinanceCredentials?,
    val binanceClientConfig: BinanceClientConfig,
    val telegramAlertsEnabled: Boolean,
    val telegramBotToken: String?,
    val telegramChatId: String?,
    val leadLagSignalEnabled: Boolean,
    val leadLagTargetBotId: BotId?,
    val leadLagSignalTtlMillis: Long,
    val leadLagSignalCooldownMillis: Long,
    val leadLagMinConfidence: Double,
    val leadLagMinExpectedNetPct: Double,
    val leadLagMinShortTermReturnPct: Double,
    val leadLagNagaMinExpectedNetPct: Double,
    val leadLagNagaMinShortTermReturnPct: Double,
    val leadLagMidMinExpectedNetPct: Double,
    val leadLagMidMinShortTermReturnPct: Double,
    val leadLagMicinMinExpectedNetPct: Double,
    val leadLagMicinMinShortTermReturnPct: Double,
    val leadLagNagaSignalTtlMillis: Long,
    val leadLagMidSignalTtlMillis: Long,
    val leadLagMicinSignalTtlMillis: Long,
    val leadLagEnableNaga: Boolean,
    val leadLagEnableMid: Boolean,
    val leadLagEnableMicin: Boolean,
    val leadLagMinTradeActivityScore: Double,
    val leadLagForceRotationOnReceive: Boolean,
    val leadLagUdpEnabled: Boolean,
    val leadLagUdpListenPort: Int,
    val leadLagUdpTargetHost: String?,
    val leadLagUdpTargetPort: Int,
    val leadLagUdpBinaryProtocolEnabled: Boolean = false,
    val leadLagUdpBinaryDualStackEnabled: Boolean = true,
    val leadLagUdpSequenceWindowSize: Int = 64,
    val leadLagUdpDedupTtlMillis: Long = 4_000L,
    val leadLagUdpPrewarmTtlMillis: Long = 1_500L,
    val leadLagUdpHeartbeatEnabled: Boolean,
    val leadLagUdpHeartbeatIntervalMillis: Long,
    val leadLagUdpHeartbeatTimeoutMillis: Long,
    val leadLagUdpHeartbeatRequiredBotIds: Set<String>,
    val indodaxHyperGuardrailEnabled: Boolean,
    val indodaxHyperGuardrailTakerFeePct: Double,
    val hyperAggressiveConfig: HyperAggressiveConfig,
    val blueChipMinDailyVolumeIdr: Double = 500_000.0,
    val aListMinVolumeIdr: Double = 80_000_000.0,
    val chartGuardMinCandles: Int = 18,
    val chartGuardMinActiveCandles: Int = 6,
    val chartGuardMinDistinctCloseBuckets: Int = 4,
    val antiKoinMahalUseBudgetCheck: Boolean = true,
    val emergencyOverrideEnabled: Boolean = false,
    val blockedBaseAssets: Set<String> = setOf("usdt", "usdc", "indr", "fdusd", "tusd", "busd", "toko"),
)

object MacRuntimeConfigLoader {
    fun load(cwd: Path = Paths.get("").toAbsolutePath()): MacRuntimeConfig {
        val fileValues = linkedMapOf<String, String>()
        val explicitEnvFile = System.getenv("KIBOT_ENV_FILE")?.takeIf { it.isNotBlank() }?.let(Paths::get)
        val hintedBotId = System.getenv("BOT_ID")?.takeIf { it.isNotBlank() }
        candidateEnvFiles(
            start = cwd,
            explicitEnvFile = explicitEnvFile,
            hintedBotId = hintedBotId,
        ).forEach { path ->
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
        val exchangeKind = optional("KIBOT_EXCHANGE_KIND")
            ?.uppercase()
            ?.let { raw ->
                when (raw) {
                    "INDODAX" -> ExchangeKind.INDODAX
                    "BINANCE", "BINANCE_SPOT" -> ExchangeKind.BINANCE_SPOT
                    else -> null
                }
            }
            ?: if (optional("BINANCE_API_KEY") != null || optional("BINANCE_API_SECRET") != null || runtimeProfileKey.contains("binance")) {
                ExchangeKind.BINANCE_SPOT
            } else {
                ExchangeKind.INDODAX
            }
        val apiKey = optional("INDODAX_API_KEY")
        val apiSecret = optional("INDODAX_API_SECRET")
        val binanceApiKey = optional("BINANCE_API_KEY")
        val binanceApiSecret = optional("BINANCE_API_SECRET")
        val deviceRole = optional("DEVICE_ROLE")
            ?.uppercase()
            ?.let { raw -> runCatching { DeviceRole.valueOf(raw) }.getOrNull() }
            ?: DeviceRole.PRIMARY

        return MacRuntimeConfig(
            runtimeProfileKey = runtimeProfileKey,
            exchangeKind = exchangeKind,
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
            shadowMode = optional("SHADOW_MODE")?.equals("true", ignoreCase = true) ?: false,
            enableExecutionAiAssist = optional("BOT_ENABLE_EXECUTION_AI_ASSIST")?.equals("true", ignoreCase = true) ?: false,
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
            pnlResetAnchorPath = resolveScopedRuntimePath(
                explicit = optional("KIBOT_PNL_RESET_ANCHOR_PATH"),
                scopedDefault = cwd.resolve(".tmp/runtime/$runtimeProfileKey/pnl_reset_anchor.json"),
                legacyDefault = cwd.resolve(".tmp/runtime/pnl_reset_anchor.json"),
            ),
            monthlyPnlAnchorPath = resolveScopedRuntimePath(
                explicit = optional("KIBOT_MONTHLY_PNL_ANCHOR_PATH"),
                scopedDefault = cwd.resolve(".tmp/runtime/$runtimeProfileKey/monthly_pnl_anchor.json"),
                legacyDefault = cwd.resolve(".tmp/runtime/monthly_pnl_anchor.json"),
            ),
            localPositionStatePath = resolveScopedRuntimePath(
                explicit = optional("KIBOT_LOCAL_POSITION_STATE_PATH"),
                scopedDefault = cwd.resolve(".tmp/runtime/$runtimeProfileKey/local_position_state.json"),
                legacyDefault = cwd.resolve(".tmp/runtime/local_position_state.json"),
            ),
            localPositionStateEnabled = optional("KIBOT_LOCAL_POSITION_STATE_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
            analysisPublishIntervalMillis = optional("BOT_ANALYSIS_PUBLISH_INTERVAL_MS")?.toLongOrNull() ?: 30_000L,
            strategyMetricsPublishIntervalMillis = optional("BOT_STRATEGY_METRICS_PUBLISH_INTERVAL_MS")?.toLongOrNull() ?: 300_000L,
            supabaseLogUploadEnabled = optional("BOT_SUPABASE_LOG_UPLOAD_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
            supabaseLogMinLevel = optional("BOT_SUPABASE_LOG_MIN_LEVEL")
                ?.uppercase()
                ?.let { raw -> runCatching { LogLevel.valueOf(raw) }.getOrNull() }
                ?: LogLevel.INFO,
            supabaseNonCriticalWriteEnabled = optional("BOT_SUPABASE_NONCRITICAL_WRITE_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
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
                shadowMode = optional("SHADOW_MODE")?.equals("true", ignoreCase = true) ?: false,
            ),
            binanceCredentials = when {
                binanceApiKey == null && binanceApiSecret == null -> null
                binanceApiKey != null && binanceApiSecret != null -> BinanceCredentials(apiKey = binanceApiKey, apiSecret = binanceApiSecret)
                else -> error("BINANCE_API_KEY and BINANCE_API_SECRET must be set together.")
            },
            binanceClientConfig = BinanceClientConfig(
                publicBaseUrl = optional("BINANCE_PUBLIC_BASE_URL") ?: "https://api.binance.com",
                privateBaseUrl = optional("BINANCE_PRIVATE_BASE_URL") ?: "https://api.binance.com",
                receiveWindowMillis = optional("BINANCE_RECEIVE_WINDOW_MS")?.toLongOrNull() ?: 10_000L,
                defaultFeePct = optional("BINANCE_DEFAULT_FEE_PCT")?.toDoubleOrNull() ?: 0.001,
                primaryQuoteAsset = optional("BINANCE_PRIMARY_QUOTE_ASSET")?.lowercase() ?: "usdt",
                shadowMode = optional("SHADOW_MODE")?.equals("true", ignoreCase = true) ?: false,
            ),
            telegramAlertsEnabled = optional("KIBOT_TELEGRAM_ALERTS_ENABLED")?.equals("true", ignoreCase = true) == true,
            telegramBotToken = optional("KIBOT_TELEGRAM_BOT_TOKEN"),
            telegramChatId = optional("KIBOT_TELEGRAM_CHAT_ID"),
            leadLagSignalEnabled = optional("KIBOT_LEAD_LAG_SIGNAL_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
            leadLagTargetBotId = optional("KIBOT_LEAD_LAG_TARGET_BOT_ID")
                ?.takeIf { it.isNotBlank() }
                ?.let(::BotId),
            leadLagSignalTtlMillis = optional("KIBOT_LEAD_LAG_SIGNAL_TTL_MS")?.toLongOrNull() ?: 120_000L,
            leadLagSignalCooldownMillis = optional("KIBOT_LEAD_LAG_SIGNAL_COOLDOWN_MS")?.toLongOrNull() ?: 20_000L,
            leadLagMinConfidence = optional("KIBOT_LEAD_LAG_MIN_CONFIDENCE")?.toDoubleOrNull() ?: 0.72,
            leadLagMinExpectedNetPct = optional("KIBOT_LEAD_LAG_MIN_EXPECTED_NET_PCT")?.toDoubleOrNull() ?: 1.05,
            leadLagMinShortTermReturnPct = optional("KIBOT_LEAD_LAG_MIN_SHORT_TERM_RETURN_PCT")?.toDoubleOrNull() ?: 2.2,
            leadLagNagaMinExpectedNetPct = optional("KIBOT_LEAD_LAG_NAGA_MIN_EXPECTED_NET_PCT")?.toDoubleOrNull() ?: 1.10,
            leadLagNagaMinShortTermReturnPct = optional("KIBOT_LEAD_LAG_NAGA_MIN_SHORT_TERM_RETURN_PCT")?.toDoubleOrNull() ?: 2.5,
            leadLagMidMinExpectedNetPct = optional("KIBOT_LEAD_LAG_MID_MIN_EXPECTED_NET_PCT")?.toDoubleOrNull() ?: 0.90,
            leadLagMidMinShortTermReturnPct = optional("KIBOT_LEAD_LAG_MID_MIN_SHORT_TERM_RETURN_PCT")?.toDoubleOrNull() ?: 1.4,
            leadLagMicinMinExpectedNetPct = optional("KIBOT_LEAD_LAG_MICIN_MIN_EXPECTED_NET_PCT")?.toDoubleOrNull() ?: 0.60,
            leadLagMicinMinShortTermReturnPct = optional("KIBOT_LEAD_LAG_MICIN_MIN_SHORT_TERM_RETURN_PCT")?.toDoubleOrNull() ?: 0.9,
            leadLagNagaSignalTtlMillis = optional("KIBOT_LEAD_LAG_NAGA_SIGNAL_TTL_MS")?.toLongOrNull() ?: 2_000L,
            leadLagMidSignalTtlMillis = optional("KIBOT_LEAD_LAG_MID_SIGNAL_TTL_MS")?.toLongOrNull() ?: 5_000L,
            leadLagMicinSignalTtlMillis = optional("KIBOT_LEAD_LAG_MICIN_SIGNAL_TTL_MS")?.toLongOrNull() ?: 12_000L,
            leadLagEnableNaga = optional("KIBOT_LEAD_LAG_ENABLE_NAGA")?.equals("true", ignoreCase = true) ?: false,
            leadLagEnableMid = optional("KIBOT_LEAD_LAG_ENABLE_MID")?.equals("true", ignoreCase = true) ?: true,
            leadLagEnableMicin = optional("KIBOT_LEAD_LAG_ENABLE_MICIN")?.equals("true", ignoreCase = true) ?: true,
            leadLagMinTradeActivityScore = optional("KIBOT_LEAD_LAG_MIN_TRADE_ACTIVITY_SCORE")?.toDoubleOrNull() ?: 0.58,
            leadLagForceRotationOnReceive = optional("KIBOT_LEAD_LAG_FORCE_ROTATION_ON_RECEIVE")
                ?.equals("true", ignoreCase = true)
                ?: true,
            leadLagUdpEnabled = optional("KIBOT_LEAD_LAG_UDP_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
            leadLagUdpListenPort = optional("KIBOT_LEAD_LAG_UDP_LISTEN_PORT")?.toIntOrNull() ?: 9999,
            leadLagUdpTargetHost = optional("KIBOT_LEAD_LAG_UDP_TARGET_HOST"),
            leadLagUdpTargetPort = optional("KIBOT_LEAD_LAG_UDP_TARGET_PORT")?.toIntOrNull() ?: 9999,
            leadLagUdpBinaryProtocolEnabled = optional("KIBOT_LEAD_LAG_UDP_BINARY_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: false,
            leadLagUdpBinaryDualStackEnabled = optional("KIBOT_LEAD_LAG_UDP_BINARY_DUAL_STACK")
                ?.equals("true", ignoreCase = true)
                ?: true,
            leadLagUdpSequenceWindowSize = optional("KIBOT_LEAD_LAG_UDP_SEQUENCE_WINDOW")
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 64,
            leadLagUdpDedupTtlMillis = optional("KIBOT_LEAD_LAG_UDP_DEDUP_TTL_MS")
                ?.toLongOrNull()
                ?.coerceAtLeast(500L)
                ?: 4_000L,
            leadLagUdpPrewarmTtlMillis = optional("KIBOT_LEAD_LAG_UDP_PREWARM_TTL_MS")
                ?.toLongOrNull()
                ?.coerceAtLeast(250L)
                ?: 1_500L,
            leadLagUdpHeartbeatEnabled = optional("KIBOT_LEAD_LAG_UDP_HEARTBEAT_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
            leadLagUdpHeartbeatIntervalMillis = optional("KIBOT_LEAD_LAG_UDP_HEARTBEAT_INTERVAL_MS")
                ?.toLongOrNull()
                ?: 100L,
            leadLagUdpHeartbeatTimeoutMillis = optional("KIBOT_LEAD_LAG_UDP_HEARTBEAT_TIMEOUT_MS")
                ?.toLongOrNull()
                ?: 300L,  // FIX: Minimal 3x interval (100ms * 3) untuk stability, mencegah false disconnect
            leadLagUdpHeartbeatRequiredBotIds = optional("KIBOT_HIVE_EXPECTED_BOT_IDS")
                ?.split(",")
                ?.mapNotNull { token ->
                    token.trim()
                        .takeIf { it.isNotBlank() }
                        ?.lowercase()
                }
                ?.toSet()
                ?: defaultHeartbeatPeers(botId.value),
            indodaxHyperGuardrailEnabled = optional("KIBOT_INDODAX_HYPER_GUARDRAIL_ENABLED")
                ?.equals("true", ignoreCase = true)
                ?: true,
            indodaxHyperGuardrailTakerFeePct = optional("KIBOT_INDODAX_HYPER_GUARDRAIL_TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.51,
            hyperAggressiveConfig = HyperAggressiveConfig(
                targetDailyPct = optional("KIBOT_HYPER_TARGET_DAILY_PCT")?.toDoubleOrNull() ?: 25.0,
                sexyWindowMs = optional("KIBOT_HYPER_SEXY_WINDOW_MS")?.toLongOrNull() ?: 60_000L,
                sexyMinPriceDeltaPct = optional("KIBOT_HYPER_SEXY_MIN_PRICE_DELTA_PCT")?.toDoubleOrNull() ?: 1.5,
                sexyMinVolumeAnomalyMultiplier = optional("KIBOT_HYPER_SEXY_MIN_VOLUME_MULTIPLIER")?.toDoubleOrNull() ?: 2.5,
                sexyMinTradeActivityScore = optional("KIBOT_HYPER_SEXY_MIN_TRADE_ACTIVITY_SCORE")?.toDoubleOrNull() ?: 0.72,
                superSexyWindowMs = optional("KIBOT_HYPER_SUPER_SEXY_WINDOW_MS")?.toLongOrNull() ?: 2_000L,
                superSexyMinPriceDeltaPct = optional("KIBOT_HYPER_SUPER_SEXY_MIN_PRICE_DELTA_PCT")?.toDoubleOrNull() ?: 4.0,
                superSexyMinVolumeAnomalyMultiplier = optional("KIBOT_HYPER_SUPER_SEXY_MIN_VOLUME_MULTIPLIER")?.toDoubleOrNull() ?: 10.0,
                vShapeDumpWindowMs = optional("KIBOT_HYPER_VSHAPE_DUMP_WINDOW_MS")?.toLongOrNull() ?: 5_000L,
                vShapeMinDumpPct = optional("KIBOT_HYPER_VSHAPE_MIN_DUMP_PCT")?.toDoubleOrNull() ?: 5.0,
                vShapeBounceConfirmMs = optional("KIBOT_HYPER_VSHAPE_BOUNCE_CONFIRM_MS")?.toLongOrNull() ?: 6_000L,
                vShapeBounceVolumeAnomalyMultiplier = optional("KIBOT_HYPER_VSHAPE_VOLUME_MULTIPLIER")?.toDoubleOrNull() ?: 4.0,
                wallSmasherWindowMs = optional("KIBOT_HYPER_WALL_WINDOW_MS")?.toLongOrNull() ?: 6_000L,
                wallSmasherVolumeAnomalyMultiplier = optional("KIBOT_HYPER_WALL_VOLUME_MULTIPLIER")?.toDoubleOrNull() ?: 4.5,
                wallSmasherMinSpreadCompressionPct = optional("KIBOT_HYPER_WALL_SPREAD_COMPRESSION_PCT")?.toDoubleOrNull() ?: 25.0,
                volumeBaselineWindowMs = optional("KIBOT_HYPER_VOLUME_BASELINE_WINDOW_MS")?.toLongOrNull() ?: 60_000L,
                stagnantWindowMs = optional("KIBOT_HYPER_STAGNANT_WINDOW_MS")?.toLongOrNull() ?: 180_000L,
                stagnantMaxMovePct = optional("KIBOT_HYPER_STAGNANT_MAX_MOVE_PCT")?.toDoubleOrNull() ?: 0.5,
                trailingStopPct = optional("KIBOT_HYPER_TRAILING_STOP_PCT")?.toDoubleOrNull() ?: 1.5,
                trailingArmMinGainPct = optional("KIBOT_HYPER_TRAILING_ARM_MIN_GAIN_PCT")?.toDoubleOrNull() ?: 0.8,
                microPulseKeepMs = optional("KIBOT_HYPER_MICRO_PULSE_KEEP_MS")?.toLongOrNull() ?: 190_000L,
                microPulseMaxSamplesPerPair = optional("KIBOT_HYPER_MICRO_PULSE_MAX_SAMPLES_PER_PAIR")?.toIntOrNull() ?: 260,
                microPulseMaxPairs = optional("KIBOT_HYPER_MICRO_PULSE_MAX_PAIRS")?.toIntOrNull() ?: 1400,
                allInLiquidationMaxPnlPct = optional("KIBOT_HYPER_ALL_IN_LIQUIDATION_MAX_PNL_PCT")?.toDoubleOrNull() ?: 1.0,
            ),
            blueChipMinDailyVolumeIdr = optional("KIBOT_BLUECHIP_MIN_VOLUME_IDR")?.toDoubleOrNull() ?: 500_000.0,
            aListMinVolumeIdr = optional("KIBOT_ALIST_MIN_VOLUME_IDR")?.toDoubleOrNull() ?: 80_000_000.0,
            chartGuardMinCandles = optional("KIBOT_CHART_GUARD_MIN_CANDLES")?.toIntOrNull() ?: 18,
            chartGuardMinActiveCandles = optional("KIBOT_CHART_GUARD_MIN_ACTIVE_CANDLES")?.toIntOrNull() ?: 6,
            chartGuardMinDistinctCloseBuckets = optional("KIBOT_CHART_GUARD_MIN_DISTINCT_CLOSE_BUCKETS")?.toIntOrNull() ?: 4,
            antiKoinMahalUseBudgetCheck = optional("KIBOT_ANTI_KOIN_MAHAL_USE_BUDGET_CHECK")?.equals("true", ignoreCase = true) ?: true,
            emergencyOverrideEnabled = optional("KIBOT_ENABLE_EMERGENCY_OVERRIDE")?.equals("true", ignoreCase = true) ?: false,
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

    private fun defaultHeartbeatPeers(botId: String): Set<String> {
        return when (botId.trim().lowercase()) {
            "kinance" -> setOf("kidax", "kibot")
            "kibot" -> setOf("kinance", "kidax")
            "kidax", "main" -> setOf("kinance", "kibot")
            else -> emptySet()
        }
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

    private fun candidateEnvFiles(
        start: Path,
        explicitEnvFile: Path? = null,
        hintedBotId: String? = null,
    ): List<Path> {
        val dirs = buildList {
            var current: Path? = start
            repeat(6) {
                if (current == null) return@repeat
                add(current)
                current = current?.parent
            }
        }.distinct()

        val hintedSuffix = hintedBotId
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.let { ".env.$it" }

        val discovered = dirs
            .flatMap { dir ->
                buildList {
                    add(dir.resolve(".env"))
                    hintedSuffix?.let { add(dir.resolve(it)) }
                    add(dir.resolve("apps/mac-engine/.env"))
                    hintedSuffix?.let { add(dir.resolve("apps/mac-engine/$it")) }
                }
            }
            .distinct()

        return buildList {
            addAll(discovered)
            explicitEnvFile?.let { add(it) }
        }
            .distinct()
            .filter { Files.exists(it) }
    }
}
