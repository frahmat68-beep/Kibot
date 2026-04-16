package com.kibot.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
                    val params = fetchParams()
                    val hash = params.hashCode()
                    
                    if (hash != lastConfigHash && lastConfigHash != 0) {
                        println("[CONFIG_RELOAD] New params detected!")
                        println("[CONFIG_RELOAD] $params")
                        onConfigChange(params)
                    }
                    
                    _currentParams.value = params
                    lastConfigHash = hash
                    
                } catch (e: Exception) {
                    println("[CONFIG_RELOAD] Poll failed: ${e.message}")
                }
                
                // CRITICAL: Long delay to avoid Supabase bandwidth limits
                // Default 60 minutes, minimum 15 minutes
                val delayMinutes = pollIntervalMinutes.coerceAtLeast(15)
                delay(delayMinutes.minutes)
            }
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
