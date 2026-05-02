package com.kibot.core.agents

import kotlinx.datetime.Instant

/**
 * InfrasGuardian - Infrastructure health & resource safety agent.
 * 
 * Monitors RAM, CPU, and stability markers to prevent OOM or stalls.
 */
interface InfrasGuardian {
    data class HealthSnapshot(
        val systemRamUsagePct: Double,
        val jvmHeapUsagePct: Double,
        val jvmNonHeapUsageMb: Double,
        val cpuLoadPct: Double,
        val storageFreeGb: Double,
        val healthStatus: HealthStatusLevel,
        val recommendations: List<Recommendation>,
        val timestamp: Instant,
    )

    enum class HealthStatusLevel {
        OPTIMAL,    // All good
        CAUTION,    // Resources getting tight (RAM > 75%)
        CRITICAL,   // Near limit (RAM > 85%, OOM risk)
        EMERGENCY   // Immediate action required (RAM > 92%)
    }

    enum class Recommendation {
        NONE,
        FLUSH_CACHES,
        THROTTLE_CYCLES,
        PAUSE_STRATEGY,
        FORCE_GC,
        RESTART_REQUIRED
    }

    /**
     * Get the latest health state of the host infrastructure.
     */
    fun checkHealth(): HealthSnapshot

    /**
     * Attempt to self-heal based on recommendations.
     */
    fun performSelfHealing(snapshot: HealthSnapshot): List<String>
}
