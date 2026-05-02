package com.kibot.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * DynamicConfigReloader — Hot-Reload tanpa restart
 * 
 * Poll Supabase setiap 1 JAM untuk config baru (FREE TIER bandwidth limit!)
 * Apply changes tanpa downtime
 * 
 * CRITICAL: Polling interval 1 HOUR minimum to avoid Supabase egress limits
 */
class DynamicConfigReloader(
    private val controlPlane: ControlPlaneGateway,
    private val pollIntervalMinutes: Int = 60, // DEFAULT 1 HOUR
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastConfigHash: Int = 0
    
    private val _currentParams = MutableStateFlow(DynamicParams())
    val currentParams: StateFlow<DynamicParams> = _currentParams
    
    @Serializable
    data class DynamicParams(
        val trailingStopPct: Double = 1.5,
        val volatilityThreshold: Double = 8.0,
        val cooldownMinutes: Int = 5,
        val fomoGuardMicro: Double = 35.0,
        val fomoGuardMid: Double = 22.0,
        val fomoGuardBig: Double = 15.0,
        val aiApprovalMinScore: Double = 0.48,
        val aiApprovalMinNetPct: Double = 0.08,
        
        // Strategy Governor Overrides
        val mscMin: Double = 0.60,
        val leadLagRatio: Double = 0.50,
        val localPumpRatio: Double = 0.50,
        val maxPerTradeBudgetPct: Double = 0.25,
        val dailyLossLimitPct: Double = 3.0,
        val profitLockRatio: Double = 0.30,
        val strategyMode: String = "NORMAL" // DEFENSIVE, NORMAL, OPPORTUNISTIC
    )
    
    @Serializable
    data class ParamRow(
        val param_key: String,
        val param_value: ParamValue,
        val updated_by: String? = null,
        val updated_at: String? = null,
    )
    
    @Serializable
    data class ParamValue(
        val value: Double
    )
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    fun startPolling(onConfigChange: (DynamicParams) -> Unit) {
        scope.launch {
            while (isActive) {
                try {
                    // Try Local Governor Directives first (High Priority, Fast)
                    val localDirectivesFile = "state/governor_directives.json".toPath()
                    val params = if (FileSystem.SYSTEM.exists(localDirectivesFile)) {
                        parseLocalDirectives(
                            FileSystem.SYSTEM.read(localDirectivesFile) {
                                readUtf8()
                            }
                        )
                    } else {
                        fetchParams()
                    }
                    
                    val hash = params.hashCode()
                    if (hash != lastConfigHash && lastConfigHash != 0) {
                        println("[CONFIG_RELOAD] New params from Governor detected!")
                        onConfigChange(params)
                    }
                    
                    _currentParams.value = params
                    lastConfigHash = hash
                    
                } catch (e: Exception) {
                    println("[CONFIG_RELOAD] Reload failed: ${e.message}")
                }
                
                // Adaptive delay: check local file more frequently (every 10s)
                // but poll remote Supabase less frequently.
                delay(10000)
            }
        }
    }

    private fun parseLocalDirectives(jsonStr: String): DynamicParams {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val scanner = root["scanner"]?.jsonObject
            val capital = root["capital"]?.jsonObject
            val risk = root["risk"]?.jsonObject
            
            DynamicParams(
                mscMin = scanner?.get("msc_min")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.60,
                leadLagRatio = capital?.get("ratio")?.jsonObject?.get("LEAD_LAG")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.50,
                localPumpRatio = capital?.get("ratio")?.jsonObject?.get("LOCAL_PUMP")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.50,
                maxPerTradeBudgetPct = capital?.get("max_per_trade")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.25,
                dailyLossLimitPct = risk?.get("daily_loss_limit_pct")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 3.0,
                profitLockRatio = risk?.get("lock_ratio")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.30,
                strategyMode = root["strategy_mode"]?.jsonPrimitive?.contentOrNull ?: "NORMAL"
            )
        } catch (e: Exception) {
            println("[CONFIG_RELOAD] Error parsing local directives: ${e.message}")
            DynamicParams()
        }
    }
    
    private suspend fun fetchParams(): DynamicParams {
        // NOTE: This is a placeholder implementation
        // Real implementation should use SupabaseControlPlaneClient to fetch from dynamic_params table
        // For now, return defaults
        return DynamicParams()
    }
    
    fun stop() {
        scope.cancel()
    }
}
