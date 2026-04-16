package com.kicryp.core

/**
 * ForceRotateManager - Manages position rotation based on holding time and profitability
 */
class ForceRotateManager(
    private val performanceTracker: PairPerformanceTracker = PairPerformanceTracker()
) {
    
    data class RotationState(
        val pairId: String,
        val entryTime: Long,
        var lastWarningTime: Long? = null,
        var forceExitScheduled: Boolean = false,
        var rotateReason: String? = null,
    ) {
        val holdingMinutes: Long
            get() = (System.currentTimeMillis() - entryTime) / 60000
    }
    
    private val activePositions = mutableMapOf<String, RotationState>()
    private val rotationHistory = mutableListOf<RotationEvent>()
    
    data class RotationEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val pairId: String,
        val reason: String,
        val holdingMinutes: Int,
        val profitPercent: Double,
    )
    
    fun recordPositionEntry(pairId: String) {
        activePositions[pairId] = RotationState(
            pairId = pairId,
            entryTime = System.currentTimeMillis()
        )
    }
    
    fun getRotationPressure(
        pairId: String,
        currentProfitPercent: Double,
        holdingMinutes: Int
    ): Double {
        val perf = performanceTracker.getPairStats(pairId)
        
        return when {
            holdingMinutes > 120 -> 1.0
            holdingMinutes > 90 && currentProfitPercent < 0.5 -> 1.0
            holdingMinutes > 75 && currentProfitPercent < 0.3 -> 0.8
            holdingMinutes > 45 && currentProfitPercent < 0.2 -> 0.5
            perf?.isAggressive == true && holdingMinutes > 90 -> 0.9
            else -> 0.0
        }
    }
    
    fun shouldForceExit(
        pairId: String,
        currentProfitPercent: Double,
        holdingMinutes: Int
    ): Boolean {
        return getRotationPressure(pairId, currentProfitPercent, holdingMinutes) >= 1.0
    }
    
    fun getForceExitReason(
        pairId: String,
        currentProfitPercent: Double,
        holdingMinutes: Int
    ): String? {
        val pressure = getRotationPressure(pairId, currentProfitPercent, holdingMinutes)
        
        if (pressure < 1.0) return null
        
        return when {
            holdingMinutes > 120 -> "Max holding time exceeded (120+ min)"
            holdingMinutes > 90 && currentProfitPercent < 0.5 -> "Stagnant position (90+ min, profit < 0.5%)"
            else -> "Position rotation triggered"
        }
    }
    
    fun recordPositionExit(
        pairId: String,
        profitPercent: Double,
        reason: String = "normal_exit"
    ) {
        val state = activePositions.remove(pairId) ?: return
        
        rotationHistory.add(RotationEvent(
            pairId = pairId,
            reason = reason,
            holdingMinutes = state.holdingMinutes.toInt(),
            profitPercent = profitPercent,
        ))
    }
    
    fun getRecommendedHoldingMinutes(pairId: String): Int {
        return performanceTracker.getRecommendedHoldingMinutes(pairId)
    }
    
    fun getMaxHoldingMinutes(pairId: String): Int {
        val perf = performanceTracker.getPairStats(pairId)
        
        return when {
            perf?.isStable == true -> 120
            perf?.isAggressive == true -> 90
            else -> 90
        }
    }
    
    fun getActivePositions(): List<RotationState> = activePositions.values.toList()
    
    fun getRotationHistory(): List<RotationEvent> = rotationHistory.toList()
    
    fun clearStalePositions(maxAgeMinutes: Int = 180) {
        val now = System.currentTimeMillis()
        val staleThreshold = maxAgeMinutes * 60000L
        
        activePositions.entries.removeAll { (_, state) ->
            (now - state.entryTime) > staleThreshold
        }
    }
    
    fun resetAllStats() {
        activePositions.clear()
        rotationHistory.clear()
    }
}
