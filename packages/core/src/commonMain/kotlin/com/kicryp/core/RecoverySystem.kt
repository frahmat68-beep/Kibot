package com.kicryp.core

import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * RecoverySystem - Balikin profit dari loss
 * 
 * Filosofi:
 * - Ketika lagi rugi, JANGAN PANIC
 * - Tapi juga JANGAN HOLD TERUS
 * - Exit STAGNAN coins cepat-cepat
 * - Fokus ke koin VOLATILE untuk quick recovery
 * - Minimize loss, maximize recovery opportunity
 * 
 * Target: Dari -6.6% hari ini → +0% atau positive
 */
class RecoverySystem {
    private val stagnantThresholdMinutes = 90
    private val recoveryThresholdPct = -5.0  // Trigger recovery jika loss > 5%
    private val staganAssets = mutableSetOf<String>()  // Coins to exit
    
    /**
     * Analyze recovery mode
     */
    fun analyzeRecoveryNeeded(
        currentPnlPct: Double,
        equityIdr: Double,
        previousEquityIdr: Double,
    ): RecoveryAnalysis {
        val losses = abs(minOf(currentPnlPct, 0.0))
        
        return RecoveryAnalysis(
            isInRecoveryMode = losses > abs(recoveryThresholdPct),
            currentLossPct = losses,
            recoveryTarget = 0.0,  // Target: break-even or positive
            recoveryUrgency = when {
                losses > 10.0 -> RecoveryUrgency.CRITICAL   // > 10% loss
                losses > 5.0 -> RecoveryUrgency.HIGH        // > 5% loss
                losses > 2.0 -> RecoveryUrgency.MEDIUM      // > 2% loss
                else -> RecoveryUrgency.LOW                  // < 2% loss
            },
            suggestedStrategy = determineRecoveryStrategy(losses),
        )
    }
    
    /**
     * Determine recovery strategy based on loss severity
     */
    private fun determineRecoveryStrategy(currentLossPct: Double): RecoveryStrategy {
        return when {
            currentLossPct > 10.0 -> {
                // CRITICAL: Cut losses, reset everything
                RecoveryStrategy(
                    name = "RESET_AND_RECOVER",
                    exitStagnanCoinsPriority = ExitPriority.IMMEDIATE,
                    entryAggressiveness = 0.3,  // 30% of normal size
                    targetProfitPercentile = 5.0,  // Quick 5% targets
                    allowedStrategies = setOf("MICRO_CAP", "PUMP_DETECTION"),
                    forbidden = setOf("HOLD_FOR_RECOVERY", "AVERAGE_DOWN"),
                )
            }
            currentLossPct > 5.0 -> {
                // HIGH: Aggressive exit stagnan, pivot to volatility
                RecoveryStrategy(
                    name = "AGGRESSIVE_RECOVERY",
                    exitStagnanCoinsPriority = ExitPriority.HIGH,
                    entryAggressiveness = 0.6,
                    targetProfitPercentile = 3.0,  // 3% quick targets
                    allowedStrategies = setOf("MICRO_CAP", "PUMP_DETECTION", "ARBITRAGE"),
                    forbidden = setOf("HOLD_FOR_RECOVERY"),
                )
            }
            currentLossPct > 2.0 -> {
                // MEDIUM: Normal exit, pivot to better pairs
                RecoveryStrategy(
                    name = "NORMAL_RECOVERY",
                    exitStagnanCoinsPriority = ExitPriority.MEDIUM,
                    entryAggressiveness = 0.8,
                    targetProfitPercentile = 2.0,
                    allowedStrategies = setOf("MICRO_CAP", "PUMP_DETECTION", "CORRELATION"),
                    forbidden = emptySet(),
                )
            }
            else -> {
                // LOW: Normal operations
                RecoveryStrategy(
                    name = "NORMAL_TRADING",
                    exitStagnanCoinsPriority = ExitPriority.LOW,
                    entryAggressiveness = 1.0,
                    targetProfitPercentile = 1.5,
                    allowedStrategies = setOf("ALL"),
                    forbidden = emptySet(),
                )
            }
        }
    }
    
    /**
     * Evaluate if coin is stagnant
     */
    fun isStagnant(
        pairId: String,
        currentReturn: Double,
        holdMinutes: Double,
        volumeScore: Double,
    ): StagnatCoinAnalysis {
        val isStuckByTime = holdMinutes > stagnantThresholdMinutes
        val isStuckByReturn = abs(currentReturn) < 0.5  // < 0.5% return
        val isStuckByVolume = volumeScore < 0.2  // Low volume
        
        val isStagnant = isStuckByTime && (isStuckByReturn || isStuckByVolume)
        
        if (isStagnant && !staganAssets.contains(pairId)) {
            staganAssets.add(pairId)
        }
        
        return StagnatCoinAnalysis(
            pair = pairId,
            isStagnant = isStagnant,
            holdMinutes = holdMinutes,
            currentReturnPct = currentReturn,
            volumeScore = volumeScore,
            reasons = buildList {
                if (isStuckByTime) add("Held > ${stagnantThresholdMinutes}min")
                if (isStuckByReturn) add("Return < 0.5%")
                if (isStuckByVolume) add("Volume low")
            },
            exitRecommendation = when {
                isStagnant -> ExitRecommendation.FORCE_EXIT_NOW
                holdMinutes > stagnantThresholdMinutes -> ExitRecommendation.EXIT_SOON
                currentReturn < -1.0 -> ExitRecommendation.EXIT_ASAP
                else -> ExitRecommendation.HOLD_OR_TRAIL
            },
        )
    }
    
    /**
     * Coins to exit ASAP
     */
    fun getExitTargets(): List<String> {
        return staganAssets.toList()
    }
    
    /**
     * Clear stagnant list after exits
     */
    fun clearStagnantAfterExit(pair: String) {
        staganAssets.remove(pair)
    }
    
    /**
     * Get recovery actions for current market
     */
    fun getRecoveryActions(
        currentLossPct: Double,
        freeIdRatio: Double,  // 0.0 to 1.0
    ): List<RecoveryAction> {
        val actions = mutableListOf<RecoveryAction>()
        
        when {
            currentLossPct > 10.0 -> {
                actions.add(RecoveryAction(
                    priority = 1,
                    action = "EXIT_WORST_3_COINS",
                    reason = "Severe loss > 10%, cut losers now",
                    timeline = "IMMEDIATE",
                ))
                actions.add(RecoveryAction(
                    priority = 2,
                    action = "REDUCE_POSITION_SIZES",
                    reason = "Use smaller entries until recovery",
                    timeline = "NEXT_3_TRADES",
                ))
                actions.add(RecoveryAction(
                    priority = 3,
                    action = "FOCUS_PUMP_DETECTION",
                    reason = "Only trade confirmed pump signals",
                    timeline = "UNTIL_RECOVERY",
                ))
            }
            currentLossPct > 5.0 -> {
                actions.add(RecoveryAction(
                    priority = 1,
                    action = "EXIT_STAGNANT_COINS",
                    reason = "Exit coins not moving",
                    timeline = "THIS_HOUR",
                ))
                actions.add(RecoveryAction(
                    priority = 2,
                    action = "INCREASE_ENTRY_FREQUENCY",
                    reason = "More trades = faster recovery",
                    timeline = "NEXT_2_HOURS",
                ))
                actions.add(RecoveryAction(
                    priority = 3,
                    action = "USE_LATE_PUMP_ENTRY",
                    reason = "Chase already-moving coins",
                    timeline = "CONTINUOUS",
                ))
            }
            currentLossPct > 2.0 -> {
                actions.add(RecoveryAction(
                    priority = 1,
                    action = "EXIT_STAGNANT_OR_NEGATIVE",
                    reason = "Don't hold dead weight",
                    timeline = "THIS_HOUR",
                ))
                actions.add(RecoveryAction(
                    priority = 2,
                    action = "INCREASE_CAPITAL_DEPLOYMENT",
                    reason = "Use idle cash aggressively",
                    timeline = "CONTINUOUS",
                ))
            }
        }
        
        // If have idle cash, use it
        if (freeIdRatio > 0.3) {
            actions.add(0, RecoveryAction(
                priority = 0,
                action = "DEPLOY_IDLE_CASH",
                reason = "Using ${(freeIdRatio * 100).toInt()}% idle capital",
                timeline = "NOW",
            ))
        }
        
        return actions
    }
    
    /**
     * Entry size adjustment for recovery
     */
    fun adjustEntrySizeForRecovery(
        normalSize: Double,
        currentLossPct: Double,
    ): Double {
        return normalSize * when {
            currentLossPct > 10.0 -> 0.3   // 30% of normal
            currentLossPct > 5.0 -> 0.5    // 50% of normal
            currentLossPct > 2.0 -> 0.7    // 70% of normal
            else -> 1.0                     // Normal size
        }
    }
    
    /**
     * Take profit targets for recovery
     */
    fun getRecoveryTakeProfitPct(currentLossPct: Double): Double {
        return when {
            currentLossPct > 10.0 -> 5.0   // Quick 5% profit
            currentLossPct > 5.0 -> 3.0    // Quick 3% profit
            currentLossPct > 2.0 -> 2.0    // 2% profit
            else -> 1.5                     // Normal 1.5%
        }
    }
}

data class RecoveryAnalysis(
    val isInRecoveryMode: Boolean,
    val currentLossPct: Double,
    val recoveryTarget: Double,
    val recoveryUrgency: RecoveryUrgency,
    val suggestedStrategy: RecoveryStrategy,
)

enum class RecoveryUrgency {
    CRITICAL,  // > 10% loss
    HIGH,      // > 5% loss
    MEDIUM,    // > 2% loss
    LOW,       // < 2% loss
}

data class RecoveryStrategy(
    val name: String,
    val exitStagnanCoinsPriority: ExitPriority,
    val entryAggressiveness: Double,  // 0.0-1.0
    val targetProfitPercentile: Double,
    val allowedStrategies: Set<String>,
    val forbidden: Set<String>,
)

enum class ExitPriority {
    IMMEDIATE,  // Exit in next 5 minutes
    HIGH,       // Exit in next 30 minutes
    MEDIUM,     // Exit in next hour
    LOW,        // Exit when opportunity
}

data class StagnatCoinAnalysis(
    val pair: String,
    val isStagnant: Boolean,
    val holdMinutes: Double,
    val currentReturnPct: Double,
    val volumeScore: Double,
    val reasons: List<String>,
    val exitRecommendation: ExitRecommendation,
)

enum class ExitRecommendation {
    FORCE_EXIT_NOW,   // Exit immediately, don't wait
    EXIT_ASAP,        // Exit in next few minutes
    EXIT_SOON,        // Exit in next hour
    HOLD_OR_TRAIL,    // Hold or use trailing stop
}

data class RecoveryAction(
    val priority: Int,
    val action: String,
    val reason: String,
    val timeline: String,
)
