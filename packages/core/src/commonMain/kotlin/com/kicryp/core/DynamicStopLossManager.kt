package com.kicryp.core

/**
 * DynamicStopLossManager - Manages stop-loss levels based on market conditions
 * 
 * Phase 2: Dynamic stops adjusted for pair stability and volatility
 * - 70% stable: 0.5% stop-loss (tight stops for reliable pairs)
 * - 30% aggressive: 1% stop-loss (wider stops for risky pairs)
 * - Adjust based on volatility: low vol = tighter, high vol = wider
 * - Emergency exit on major drawdown (> 3% loss)
 */
class DynamicStopLossManager(
    private val performanceTracker: PairPerformanceTracker = PairPerformanceTracker()
) {
    
    data class StopLossConfig(
        val pairId: String,
        val baseStopLossPercent: Double,  // Base stop-loss %
        val volatilityAdjustment: Double,  // Adjustment factor based on volatility
        val finalStopLossPercent: Double,  // Final calculated stop-loss %
        val emergencyStopPercent: Double = 3.0,  // Emergency exit threshold
        val isEmergencyStopActive: Boolean = false,
    )
    
    private val stopsLosses = mutableMapOf<String, StopLossConfig>()
    
    /**
     * Calculate dynamic stop-loss for a pair
     */
    fun calculateStopLoss(pairId: String, volatility: Double = 0.5): StopLossConfig {
        val existingConfig = stopsLosses[pairId]
        if (existingConfig != null && existingConfig.isEmergencyStopActive) {
            return existingConfig
        }
        
        val perf = performanceTracker.getPairStats(pairId)
        
        // Base stop-loss from pair performance
        val baseStop = performanceTracker.getDynamicStopLoss(pairId)
        
        // Volatility adjustment (0.5 to 2.0x multiplier)
        // High volatility = wider stop, Low volatility = tighter stop
        val volAdjustment = when {
            volatility < 0.3 -> 0.8  // Low vol: tighten to 80%
            volatility > 0.7 -> 1.5  // High vol: widen to 150%
            else -> 1.0  // Normal vol: no adjustment
        }
        
        val finalStop = baseStop * volAdjustment
        
        val config = StopLossConfig(
            pairId = pairId,
            baseStopLossPercent = baseStop,
            volatilityAdjustment = volAdjustment,
            finalStopLossPercent = finalStop,
        )
        
        stopsLosses[pairId] = config
        return config
    }
    
    /**
     * Check if position should trigger emergency stop
     * Emergency stop activates at > 3% loss or market crash conditions
     */
    fun shouldTriggerEmergencyStop(
        pairId: String,
        currentLoss: Double,  // Current loss %
        marketCondition: MarketCondition = MarketCondition.NORMAL
    ): Boolean {
        return when {
            currentLoss < -3.0 -> true  // Hard rule: > 3% loss
            marketCondition == MarketCondition.CRASH -> true
            marketCondition == MarketCondition.CIRCUIT_BREAKER -> true
            else -> false
        }
    }
    
    /**
     * Get stop-loss price for an entry price
     */
    fun getStopLossPrice(pairId: String, entryPrice: Double, volatility: Double = 0.5): Double {
        val config = calculateStopLoss(pairId, volatility)
        val stopLossPercent = config.finalStopLossPercent / 100.0
        return entryPrice * (1.0 - stopLossPercent)
    }
    
    /**
     * Get emergency stop price (hard exit point)
     */
    fun getEmergencyStopPrice(entryPrice: Double): Double {
        return entryPrice * (1.0 - 0.03)  // 3% hard stop
    }
    
    /**
     * Activate emergency stop for a pair
     */
    fun activateEmergencyStop(pairId: String) {
        val config = stopsLosses[pairId]
        if (config != null) {
            stopsLosses[pairId] = config.copy(isEmergencyStopActive = true)
        }
    }
    
    /**
     * Deactivate emergency stop when position closed
     */
    fun deactivateEmergencyStop(pairId: String) {
        stopsLosses.remove(pairId)
    }
    
    /**
     * Get current stop-loss config for pair
     */
    fun getStopLossConfig(pairId: String): StopLossConfig? = stopsLosses[pairId]
    
    /**
     * Get all active stop-losses
     */
    fun getAllStopLosses(): Map<String, StopLossConfig> = stopsLosses.toMap()
    
    enum class MarketCondition {
        NORMAL,
        VOLATILE,
        CRASH,
        CIRCUIT_BREAKER,
        OPPORTUNITY,
    }
}
