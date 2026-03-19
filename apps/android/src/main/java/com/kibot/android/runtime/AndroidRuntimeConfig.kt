package com.kibot.android.runtime

import android.os.Build
import com.kibot.android.BuildConfig
import com.kibot.aisupport.GeminiSupportConfig
import com.kibot.controlplane.ControlPlaneConfig
import com.kibot.core.DeviceRegistration
import com.kibot.indodax.IndodaxClientConfig
import com.kibot.indodax.IndodaxCredentials
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DevicePlatform
import com.kibot.shared.models.DeviceRole

data class AndroidRuntimeConfig(
    val controlPlane: ControlPlaneConfig?,
    val device: DeviceRegistration,
    val pollIntervalMillis: Long,
    val leaseTtlSeconds: Int,
    val enableLiveExecution: Boolean,
    val macLanSyncBaseUrl: String?,
    val aiSupportConfig: GeminiSupportConfig?,
    val indodaxCredentials: IndodaxCredentials?,
    val indodaxClientConfig: IndodaxClientConfig,
) {
    val isControlPlaneConfigured: Boolean = controlPlane != null
    val isExchangeConfigured: Boolean = indodaxCredentials != null
}

object AndroidRuntimeConfigLoader {
    fun load(): AndroidRuntimeConfig {
        val botId = BotId(BuildConfig.KIBOT_BOT_ID.ifBlank { "main" })
        val controlPlane = if (
            BuildConfig.KIBOT_SUPABASE_URL.isNotBlank() &&
            BuildConfig.KIBOT_SUPABASE_ANON_KEY.isNotBlank() &&
            BuildConfig.KIBOT_SUPABASE_USER_EMAIL.isNotBlank() &&
            BuildConfig.KIBOT_SUPABASE_USER_PASSWORD.isNotBlank()
        ) {
            ControlPlaneConfig(
                supabaseUrl = BuildConfig.KIBOT_SUPABASE_URL,
                supabaseAnonKey = BuildConfig.KIBOT_SUPABASE_ANON_KEY,
                userEmail = BuildConfig.KIBOT_SUPABASE_USER_EMAIL,
                userPassword = BuildConfig.KIBOT_SUPABASE_USER_PASSWORD,
                botId = botId,
            )
        } else {
            null
        }

        val credentials = if (
            BuildConfig.KIBOT_INDODAX_API_KEY.isNotBlank() &&
            BuildConfig.KIBOT_INDODAX_API_SECRET.isNotBlank()
        ) {
            IndodaxCredentials(
                apiKey = BuildConfig.KIBOT_INDODAX_API_KEY,
                apiSecret = BuildConfig.KIBOT_INDODAX_API_SECRET,
            )
        } else {
            null
        }

        val aiSupportConfig = if (
            BuildConfig.KIBOT_GEMINI_SUPPORT_ENABLED &&
            BuildConfig.KIBOT_GEMINI_SUPPORT_API_KEY.isNotBlank()
        ) {
            GeminiSupportConfig(
                enabled = true,
                apiKey = BuildConfig.KIBOT_GEMINI_SUPPORT_API_KEY,
                model = BuildConfig.KIBOT_GEMINI_SUPPORT_MODEL,
                maxCandidates = BuildConfig.KIBOT_GEMINI_SUPPORT_MAX_CANDIDATES,
                minIntervalMinutes = BuildConfig.KIBOT_GEMINI_SUPPORT_MIN_INTERVAL_MINUTES,
                timeoutMillis = BuildConfig.KIBOT_GEMINI_SUPPORT_TIMEOUT_MS,
                maxOutputTokens = BuildConfig.KIBOT_GEMINI_SUPPORT_MAX_OUTPUT_TOKENS,
                hourlyRequestBudget = BuildConfig.KIBOT_GEMINI_SUPPORT_HOURLY_REQUEST_BUDGET,
                dailyRequestBudget = BuildConfig.KIBOT_GEMINI_SUPPORT_DAILY_REQUEST_BUDGET,
                failureCooldownMinutes = BuildConfig.KIBOT_GEMINI_SUPPORT_FAILURE_COOLDOWN_MINUTES,
            )
        } else {
            null
        }

        return AndroidRuntimeConfig(
            controlPlane = controlPlane,
            device = DeviceRegistration(
                deviceId = DeviceId("android-${Build.MODEL.lowercase().replace("\\s+".toRegex(), "-")}"),
                displayName = "${Build.MANUFACTURER} ${Build.MODEL}",
                platform = DevicePlatform.ANDROID,
                role = DeviceRole.PRIMARY,
            ),
            pollIntervalMillis = BuildConfig.KIBOT_POLL_INTERVAL_MS,
            leaseTtlSeconds = BuildConfig.KIBOT_LEASE_TTL_SECONDS,
            enableLiveExecution = BuildConfig.KIBOT_ENABLE_LIVE_EXECUTION,
            macLanSyncBaseUrl = BuildConfig.KIBOT_MAC_LAN_SYNC_URL.takeIf { it.isNotBlank() },
            aiSupportConfig = aiSupportConfig,
            indodaxCredentials = credentials,
            indodaxClientConfig = IndodaxClientConfig(
                publicBaseUrl = BuildConfig.KIBOT_INDODAX_PUBLIC_BASE_URL,
                privateBaseUrl = BuildConfig.KIBOT_INDODAX_PRIVATE_BASE_URL,
                tradeApiV2BaseUrl = BuildConfig.KIBOT_INDODAX_TRADE_API_V2_BASE_URL,
                publicWebSocketUrl = BuildConfig.KIBOT_INDODAX_WS_PUBLIC_URL,
                privateWebSocketUrl = BuildConfig.KIBOT_INDODAX_WS_PRIVATE_URL,
            ),
        )
    }
}
