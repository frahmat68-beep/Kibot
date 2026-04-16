package com.kibot.core

/**
 * BTCETHCorrelationFilter - Monitor BTC/USDT and ETH/USDT 1h changes
 * 
 * Phase 3: Correlation-based trading signals
 * - BTC down 2%: BLOCK all entry (risk too high)
 * - BTC up 3%: BOOST entry size 1.5x
 * - ETH correlation: similar logic
 * - Method: getSignalModifier() -> returns adjustment factor
 *   (0.0 = block, 1.0 = normal, 1.5 = boost)
 */
class BTCETHCorrelationFilter {
    
    data class CorrelationState(
        val btcChange1h: Double = 0.0,  // % change in last 1h
        val ethChange1h: Double = 0.0,  // % change in last 1h
        val btcChange24h: Double = 0.0,  // % change in last 24h
        val ethChange24h: Double = 0.0,  // % change in last 24h
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    private var currentState: CorrelationState = CorrelationState()
    
    /**
     * Update BTC/ETH market data
     */
    fun updateMarketData(
        btcChange1h: Double,
        ethChange1h: Double,
        btcChange24h: Double,
        ethChange24h: Double
    ) {
        currentState = CorrelationState(
            btcChange1h = btcChange1h,
            ethChange1h = ethChange1h,
            btcChange24h = btcChange24h,
            ethChange24h = ethChange24h,
        )
    }
    
    /**
     * Get signal modifier (entry size adjustment)
     * 
     * Returns:
     * - 0.0: BLOCK all entries (risk too high)
     * - 0.5: REDUCE entry size 50% (caution)
     * - 1.0: NORMAL entry size
     * - 1.5: BOOST entry size 150% (opportunity)
     * - 2.0: AGGRESSIVE boost (rare)
     */
    fun getSignalModifier(): Double {
        // Rule 1: BTC down 2%+ = BLOCK
        if (currentState.btcChange1h < -2.0) {
            return 0.0
        }
        
        // Rule 2: BTC down 1%+ but not -2% = REDUCE
        if (currentState.btcChange1h < -1.0) {
            return 0.5
        }
        
        // Rule 3: BTC up 3%+ = BOOST
        if (currentState.btcChange1h > 3.0) {
            return 1.5
        }
        
        // Rule 4: BTC up 2%+ = SLIGHT BOOST
        if (currentState.btcChange1h > 2.0) {
            return 1.25
        }
        
        // Rule 5: ETH down 2% = REDUCE (even if BTC ok)
        if (currentState.ethChange1h < -2.0) {
            return 0.5
        }
        
        // Rule 6: ETH up 3% = BOOST (if BTC not down)
        if (currentState.ethChange1h > 3.0 && currentState.btcChange1h >= -1.0) {
            return 1.5
        }
        
        // Rule 7: Check 24h correlation for trend
        if (currentState.btcChange24h < -5.0 && currentState.ethChange24h < -5.0) {
            return 0.5  // Bear market: be cautious
        }
        
        if (currentState.btcChange24h > 5.0 && currentState.ethChange24h > 5.0) {
            return 1.25  // Bull market: slight boost
        }
        
        return 1.0  // Normal conditions
    }
    
    /**
     * Check if market conditions allow entry
     */
    fun isEntryAllowed(): Boolean {
        return getSignalModifier() > 0.0
    }
    
    /**
     * Get market condition description
     */
    fun getMarketCondition(): String {
        val modifier = getSignalModifier()
        
        return when {
            modifier == 0.0 -> "BLOCKED - BTC major decline (${String.format("%.1f", currentState.btcChange1h)}%)"
            modifier == 0.5 -> "CAUTIOUS - Market weakness detected"
            modifier >= 1.5 -> "BOOSTED - Strong market momentum (BTC +${String.format("%.1f", currentState.btcChange1h)}%)"
            modifier > 1.0 -> "POSITIVE - Slight market strength"
            else -> "NORMAL - Neutral market conditions"
        }
    }
    
    /**
     * Get current correlation state
     */
    fun getCurrentState(): CorrelationState = currentState
    
    /**
     * Reset to neutral state
     */
    fun resetToNeutral() {
        currentState = CorrelationState()
    }
    
    /**
     * Check if major market event occurred
     */
    fun isMajorMarketEvent(): Boolean {
        return kotlin.math.abs(currentState.btcChange1h) > 5.0 ||
               kotlin.math.abs(currentState.ethChange1h) > 5.0 ||
               kotlin.math.abs(currentState.btcChange24h) > 10.0
    }
}
