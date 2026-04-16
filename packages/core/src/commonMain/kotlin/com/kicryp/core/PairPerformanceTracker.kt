package com.kicryp.core

import kotlin.math.abs

/**
 * PairPerformanceTracker - Track wins, losses, avg profit %, holding time per pair
 */
class PairPerformanceTracker {
    
    data class PairPerformance(
        val pairId: String,
        var totalTrades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalProfitPercent: Double = 0.0,
        var totalHoldingMinutes: Int = 0,
        var maxProfitPercent: Double = 0.0,
        var minProfitPercent: Double = 0.0,
        var avgHoldingMinutes: Int = 0,
        var volatilityScore: Double = 0.5,
        var lastTradeTime: Long = System.currentTimeMillis(),
    ) {
        val winRate: Double
            get() = if (totalTrades == 0) 0.0 else (wins.toDouble() / totalTrades) * 100.0
        
        val avgProfitPercent: Double
            get() = if (totalTrades == 0) 0.0 else totalProfitPercent / totalTrades
        
        val isStable: Boolean
            get() = volatilityScore < 0.4
        
        val isAggressive: Boolean
            get() = volatilityScore >= 0.4
    }
    
    private data class TradeRecord(
        val pairId: String,
        val entryTime: Long = System.currentTimeMillis(),
        var exitTime: Long? = null,
        var profitPercent: Double = 0.0,
        var holdingTimeMinutes: Int = 0,
        var won: Boolean = false,
    )
    
    private val pairPerformances = mutableMapOf<String, PairPerformance>()
    private val activePositions = mutableMapOf<String, TradeRecord>()
    
    private val DEFAULT_VOLATILITY_SCORE = 0.5
    
    fun recordEntry(pairId: String) {
        val record = TradeRecord(pairId)
        activePositions[pairId] = record
        
        if (!pairPerformances.containsKey(pairId)) {
            pairPerformances[pairId] = PairPerformance(
                pairId = pairId,
                volatilityScore = DEFAULT_VOLATILITY_SCORE
            )
        }
    }
    
    fun recordExit(pairId: String, profitPercent: Double, timeHeldMinutes: Int) {
        val record = activePositions.remove(pairId) ?: return
        
        val perf = pairPerformances.getOrPut(pairId) {
            PairPerformance(pairId = pairId, volatilityScore = DEFAULT_VOLATILITY_SCORE)
        }
        
        val won = profitPercent >= 0.0
        
        record.profitPercent = profitPercent
        record.holdingTimeMinutes = timeHeldMinutes
        record.won = won
        record.exitTime = System.currentTimeMillis()
        
        perf.totalTrades++
        if (won) perf.wins++ else perf.losses++
        
        perf.totalProfitPercent += profitPercent
        perf.totalHoldingMinutes += timeHeldMinutes
        perf.avgHoldingMinutes = if (perf.totalTrades > 0) {
            perf.totalHoldingMinutes / perf.totalTrades
        } else 0
        
        perf.maxProfitPercent = maxOf(perf.maxProfitPercent, profitPercent)
        perf.minProfitPercent = minOf(perf.minProfitPercent, profitPercent)
        perf.lastTradeTime = System.currentTimeMillis()
        
        updateVolatilityScore(perf)
    }
    
    private fun updateVolatilityScore(perf: PairPerformance) {
        if (perf.totalTrades < 3) return
        
        val range = abs(perf.maxProfitPercent - perf.minProfitPercent)
        val variance = range * range / perf.totalTrades
        val newScore = minOf(1.0, variance / 100.0 + 0.1)
        
        perf.volatilityScore = (perf.volatilityScore * 0.7) + (newScore * 0.3)
    }
    
    fun getDynamicStopLoss(pairId: String): Double {
        val perf = pairPerformances[pairId] ?: return 1.0
        
        return when {
            perf.winRate < 30.0 && perf.totalTrades >= 10 -> 1.5
            perf.isStable && perf.winRate >= 60.0 -> 0.5
            else -> 1.0
        }
    }
    
    fun getShouldForceRotate(pairId: String, timeHeldMinutes: Int, currentProfitPercent: Double): Boolean {
        val perf = pairPerformances[pairId] ?: return false
        
        return when {
            timeHeldMinutes > 120 -> true
            timeHeldMinutes > 90 && currentProfitPercent < 0.5 -> true
            perf.isAggressive && timeHeldMinutes > 90 -> true
            else -> false
        }
    }
    
    fun getExpectedProfitTarget(pairId: String): Double {
        val perf = pairPerformances[pairId] ?: return 1.5
        
        if (perf.totalTrades < 5) {
            return if (perf.isStable) 1.2 else 2.0
        }
        
        val avgProfit = perf.avgProfitPercent
        return maxOf(0.5, avgProfit * 0.7)
    }
    
    fun getRecommendedHoldingMinutes(pairId: String): Int {
        val perf = pairPerformances[pairId] ?: return 45
        
        if (perf.totalTrades < 3) {
            return if (perf.isStable) 60 else 45
        }
        
        return minOf(120, maxOf(15, perf.avgHoldingMinutes))
    }
    
    fun getPairStats(pairId: String): PairPerformance? = pairPerformances[pairId]
    
    fun getAllPairStats(): List<PairPerformance> = pairPerformances.values.toList()
    
    fun resetAllStats() {
        pairPerformances.clear()
        activePositions.clear()
    }
    
    fun getActivePositionCount(): Int = activePositions.size
    
    fun clearStalePosition(pairId: String, maxAgeMinutes: Int = 180) {
        val record = activePositions[pairId] ?: return
        val ageMinutes = (System.currentTimeMillis() - record.entryTime) / 60000
        
        if (ageMinutes > maxAgeMinutes) {
            activePositions.remove(pairId)
        }
    }
}
