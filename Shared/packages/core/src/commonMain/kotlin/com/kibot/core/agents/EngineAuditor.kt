package com.kibot.core.agents

import com.kibot.shared.models.BotId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.toFormattedString
import org.slf4j.LoggerFactory

/**
 * EngineAuditor: The autonomous "internal police" of the KiBot Hydra architecture.
 * Responsible for verifying that the sum of allocated and free capital matches the total observed equity.
 */
interface EngineAuditor {
    /**
     * Verifies that the internal bookkeeping matches the reported engine equity.
     * Logs drift alerts if a discrepancy is detected.
     */
    fun checkIntegrity(
        totalEquity: DecimalValue,
        allocated: DecimalValue,
        free: DecimalValue,
        botId: BotId,
    ): AuditResult

    data class AuditResult(
        val isHealthy: Boolean,
        val drift: DecimalValue,
        val driftPct: Double,
    )
}

/**
 * Default implementation of the EngineAuditor.
 */
class DefaultEngineAuditor(
    private val alertThresholdIdr: DecimalValue = DecimalValue("0.01"),
    private val criticalDriftPct: Double = 0.1, // 0.1% drift is grounds for alert
) : EngineAuditor {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var alertCount = 0
    fun getAlertCount() = alertCount

    override fun checkIntegrity(
        totalEquity: DecimalValue,
        allocated: DecimalValue,
        free: DecimalValue,
        botId: BotId
    ): EngineAuditor.AuditResult {
        val totalInternal = allocated + free
        val drift = (totalEquity - totalInternal).absoluteValue()
        
        val driftPct = if (totalEquity > DecimalValue.Zero) {
            (drift / totalEquity) * 100.0
        } else 0.0

        val isHealthy = drift <= alertThresholdIdr

        if (!isHealthy) {
            alertCount++
            val severity = if (driftPct >= criticalDriftPct) "CRITICAL" else "WARNING"
            logger.error(
                "[AUDIT] [$severity] Capital Drift Detected for $botId! " +
                "Reality=${totalEquity.toFormattedString(2)} IDR, " +
                "Internal=${totalInternal.toFormattedString(2)} IDR, " +
                "Drift=${drift.toFormattedString(2)} IDR (${DecimalValue.fromDouble(driftPct).toFormattedString(4)}%)"
            )
        } else {
            logger.info("[AUDIT] Equity integrity verified for $botId. Drift=${drift.toFormattedString(8)} IDR")
        }

        return EngineAuditor.AuditResult(isHealthy, drift, driftPct)
    }
}
