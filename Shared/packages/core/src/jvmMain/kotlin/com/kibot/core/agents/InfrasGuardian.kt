package com.kibot.core.agents

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.io.File
import java.nio.file.Paths
import org.slf4j.LoggerFactory

/**
 * JvmInfrasGuardian - JVM-specific implementation of infrastructure guarding.
 * 
 * Optimized for resource-constrained environments like Oracle Micro (1GB RAM).
 */
class JvmInfrasGuardian : InfrasGuardian {
    private val logger = LoggerFactory.getLogger(JvmInfrasGuardian::class.java)
    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)

    override fun checkHealth(): InfrasGuardian.HealthSnapshot {
        val heap = memoryBean.heapMemoryUsage
        val nonHeap = memoryBean.nonHeapMemoryUsage
        
        val jvmHeapUsagePct = if (heap.max > 0) heap.used.toDouble() / heap.max else 0.0
        val jvmNonHeapMb = nonHeap.used.toDouble() / 1024 / 1024
        
        // System RAM usage (Host level)
        val totalPhysicalMemory = osBean.totalMemorySize
        val freePhysicalMemory = osBean.freeMemorySize
        val systemRamUsagePct = if (totalPhysicalMemory > 0) {
            (totalPhysicalMemory - freePhysicalMemory).toDouble() / totalPhysicalMemory
        } else 0.0
        
        val cpuLoadPct = osBean.cpuLoad.coerceAtLeast(0.0)
        
        // Storage check (/ or current work dir)
        val file = File(".")
        val storageFreeGb = file.freeSpace.toDouble() / 1024 / 1024 / 1024

        val status = when {
            systemRamUsagePct > 0.92 || jvmHeapUsagePct > 0.90 -> InfrasGuardian.HealthStatusLevel.EMERGENCY
            systemRamUsagePct > 0.85 || jvmHeapUsagePct > 0.82 -> InfrasGuardian.HealthStatusLevel.CRITICAL
            systemRamUsagePct > 0.75 || jvmHeapUsagePct > 0.70 -> InfrasGuardian.HealthStatusLevel.CAUTION
            else -> InfrasGuardian.HealthStatusLevel.OPTIMAL
        }

        val recommendations = mutableListOf<InfrasGuardian.Recommendation>()
        if (status != InfrasGuardian.HealthStatusLevel.OPTIMAL) {
            recommendations += InfrasGuardian.Recommendation.FORCE_GC
            if (status.ordinal >= InfrasGuardian.HealthStatusLevel.CAUTION.ordinal) {
                recommendations += InfrasGuardian.Recommendation.FLUSH_CACHES
                recommendations += InfrasGuardian.Recommendation.THROTTLE_CYCLES
            }
            if (status == InfrasGuardian.HealthStatusLevel.EMERGENCY) {
                recommendations += InfrasGuardian.Recommendation.PAUSE_STRATEGY
            }
        }

        return InfrasGuardian.HealthSnapshot(
            systemRamUsagePct = systemRamUsagePct,
            jvmHeapUsagePct = jvmHeapUsagePct,
            jvmNonHeapUsageMb = jvmNonHeapMb,
            cpuLoadPct = cpuLoadPct,
            storageFreeGb = storageFreeGb,
            healthStatus = status,
            recommendations = recommendations,
            timestamp = Clock.System.now(),
        )
    }

    override fun performSelfHealing(snapshot: InfrasGuardian.HealthSnapshot): List<String> {
        val actionsTaken = mutableListOf<String>()
        
        if (snapshot.recommendations.contains(InfrasGuardian.Recommendation.FORCE_GC)) {
            logger.warn("InfrasGuardian: Triggering Emergency Garbage Collection (RAM: ${"%.1f".format(snapshot.systemRamUsagePct * 100)}%)")
            System.gc()
            actionsTaken += "FORCED_GC"
        }
        
        // Logical self-healing (flushing, throttling) must be handled by the Engine orchestrator
        // since this agent doesn't own the caches directly.
        
        return actionsTaken
    }
}
