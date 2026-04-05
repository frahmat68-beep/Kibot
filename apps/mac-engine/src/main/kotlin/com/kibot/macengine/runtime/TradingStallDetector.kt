/**
 * Trading Stall Detector & Auto-Recovery System
 * 
 * Detects when bot hasn't made a trade for N minutes
 * Automatically escalates bot mode to prevent stagnation
 * 
 * User requirement: "If no trade in 1 hour, DO SOMETHING or it's stagnan"
 */

package com.kibot.macengine.runtime

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class TradingStallState(
    val lastTradeTimestamp: Instant = Clock.System.now(),
    val stallDetectedAt: Instant? = null,
    val stallMinutes: Long = 0,
    val escapeAttemptCount: Int = 0,
    val lastEscapeAttemptMode: String = "SAFE"
)

class TradingStallDetector(
    val stallTimeoutMinutes: Long = 60,  // 1 hour threshold (configurable)
    val escalationModes: List<String> = listOf("SAFE", "DEFENSIVE", "GROWTH", "ATTACK")
) {
    private var state = TradingStallState()
    
    /**
     * Call this when a trade is successfully executed
     */
    fun recordTradeExecution() {
        state = state.copy(
            lastTradeTimestamp = Clock.System.now(),
            stallDetectedAt = null,
            stallMinutes = 0,
            escapeAttemptCount = 0
        )
    }
    
    /**
     * Check if trading is currently stalled
     */
    fun isStalled(now: Instant = Clock.System.now()): Boolean {
        val minutesSinceLastTrade = (now.toEpochMilliseconds() - state.lastTradeTimestamp.toEpochMilliseconds()) / 60000
        
        if (minutesSinceLastTrade >= stallTimeoutMinutes) {
            if (state.stallDetectedAt == null) {
                // First time detecting stall - log it
                state = state.copy(
                    stallDetectedAt = now,
                    stallMinutes = minutesSinceLastTrade
                )
            }
            return true
        }
        return false
    }
    
    /**
     * Get recommended escalation mode to recover from stall
     */
    fun getEscalationMode(currentMode: String): String {
        val currentIndex = escalationModes.indexOf(currentMode)
        if (currentIndex >= 0 && currentIndex < escalationModes.size - 1) {
            return escalationModes[currentIndex + 1]  // Escalate to next mode
        }
        return escalationModes.last()  // Already at max aggression
    }
    
    /**
     * Record escape attempt (mode escalation)
     */
    fun recordEscapeAttempt(newMode: String) {
        state = state.copy(
            escapeAttemptCount = state.escapeAttemptCount + 1,
            lastEscapeAttemptMode = newMode
        )
    }
    
    fun getState(): TradingStallState = state
}

/**
 * Stall recovery action set
 */
sealed class StallRecoveryAction {
    data class EscalateBotMode(val from: String, val to: String) : StallRecoveryAction()
    data class ClearDailyProfitLock(val dailyGain: Double) : StallRecoveryAction()
    data class ReduceVetoThreshold(val thresholdReduction: Double) : StallRecoveryAction()
    data class NotifyOperator(val message: String) : StallRecoveryAction()
    data class ClearRiskLimits(val reason: String) : StallRecoveryAction()
}

class StallRecoveryExecutor {
    fun executeRecovery(actions: List<StallRecoveryAction>): String {
        val log = StringBuilder()
        
        for (action in actions) {
            when (action) {
                is StallRecoveryAction.EscalateBotMode -> {
                    log.append("[STALL_RECOVERY] Escalating bot mode: ${action.from} → ${action.to}\n")
                }
                is StallRecoveryAction.ClearDailyProfitLock -> {
                    log.append("[STALL_RECOVERY] Clearing daily profit lock (was blocking at ${action.dailyGain}%)\n")
                }
                is StallRecoveryAction.ReduceVetoThreshold -> {
                    log.append("[STALL_RECOVERY] Reducing veto threshold by ${action.thresholdReduction}%\n")
                }
                is StallRecoveryAction.NotifyOperator -> {
                    log.append("[STALL_RECOVERY] ⚠️ ${action.message}\n")
                }
                is StallRecoveryAction.ClearRiskLimits -> {
                    log.append("[STALL_RECOVERY] Clearing risk limits: ${action.reason}\n")
                }
            }
        }
        
        return log.toString()
    }
}
