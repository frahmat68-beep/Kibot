package com.kicryp.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * TradeLedger - Track EVERY trade with fees, slippage, and P/L
 * 
 * Purpose: Learn from mistakes
 * - Which coins have high slippage?
 * - Which entry strategies lose money?
 * - Which pairs always stop out?
 * 
 * Example:
 * Trade #1: Bought BTC at 100, sold at 105, fee 0.6%, profit 4.4%
 * Trade #2: Bought DRX at 50, sold at 48, fee 0.6%, LOSS -4.6%
 * 
 * Learning: DRX has tight spread, don't trade it anymore!
 */
class TradeLedger {
    private val trades = mutableListOf<TradeRecord>()
    private val pairStats = mutableMapOf<String, PairStatistics>()
    
    /**
     * Record completed trade
     */
    fun recordTrade(
        pair: String,
        strategy: PositionStrategy,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        entryFeeIdr: Double,
        exitFeeIdr: Double,
        slippageIdr: Double,
        netProfitIdr: Double,
        netProfitPct: Double,
        holdMinutes: Double,
        exitReason: String,
        timestamp: Instant = Clock.System.now(),
    ): TradeRecord {
        val totalFeeIdr = entryFeeIdr + exitFeeIdr
        val totalCostIdr = totalFeeIdr + slippageIdr
        
        val record = TradeRecord(
            id = trades.size + 1,
            timestamp = timestamp,
            pair = pair,
            strategy = strategy,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            quantity = quantity,
            entryFeeIdr = entryFeeIdr,
            exitFeeIdr = exitFeeIdr,
            totalFeeIdr = totalFeeIdr,
            slippageIdr = slippageIdr,
            totalCostIdr = totalCostIdr,
            netProfitIdr = netProfitIdr,
            netProfitPct = netProfitPct,
            holdMinutes = holdMinutes,
            exitReason = exitReason,
        )
        
        trades.add(record)
        
        // Update pair statistics
        updatePairStats(record)
        
        return record
    }
    
    /**
     * Update pair statistics for learning
     */
    private fun updatePairStats(trade: TradeRecord) {
        val stats = pairStats.getOrPut(trade.pair) {
            PairStatistics(
                pair = trade.pair,
                totalTrades = 0,
                winCount = 0,
                lossCount = 0,
                totalProfitIdr = 0.0,
                totalFeesPaid = 0.0,
                avgSlippageIdr = 0.0,
                winRate = 0.0,
                avgProfitPct = 0.0,
            )
        }
        
        stats.totalTrades++
        stats.totalProfitIdr += trade.netProfitIdr
        stats.totalFeesPaid += trade.totalFeeIdr
        
        if (trade.netProfitIdr > 0) {
            stats.winCount++
        } else if (trade.netProfitIdr < 0) {
            stats.lossCount++
        }
        
        // Update averages
        stats.winRate = (stats.winCount.toDouble() / stats.totalTrades) * 100.0
        stats.avgProfitPct = trades
            .filter { it.pair == trade.pair }
            .map { it.netProfitPct }
            .average()
        
        stats.avgSlippageIdr = trades
            .filter { it.pair == trade.pair }
            .map { it.slippageIdr }
            .average()
    }
    
    /**
     * Get learning insights
     */
    fun getLearningInsights(): List<LearningInsight> {
        val insights = mutableListOf<LearningInsight>()
        
        // Find pairs with low win rate (< 40%)
        pairStats.values
            .filter { it.totalTrades >= 3 && it.winRate < 40.0 }
            .forEach { stats ->
                insights.add(LearningInsight(
                    type = InsightType.BAD_PAIR,
                    message = "${stats.pair} has ${stats.winRate.format(1)}% win rate - avoid trading this!",
                    pair = stats.pair,
                    severity = InsightSeverity.HIGH,
                    data = mapOf(
                        "winRate" to stats.winRate.toString(),
                        "totalTrades" to stats.totalTrades.toString(),
                    ),
                ))
            }
        
        // Find pairs with high fees/slippage
        pairStats.values
            .filter { it.avgSlippageIdr > 1000.0 }  // More than Rp1000 avg slippage
            .forEach { stats ->
                insights.add(LearningInsight(
                    type = InsightType.HIGH_SLIPPAGE,
                    message = "${stats.pair} has Rp${stats.avgSlippageIdr.format(0)} avg slippage - very costly!",
                    pair = stats.pair,
                    severity = InsightSeverity.MEDIUM,
                    data = mapOf(
                        "avgSlippage" to stats.avgSlippageIdr.toString(),
                    ),
                ))
            }
        
        // Find losing strategies
        val strategyPerformance = trades
            .groupBy { it.strategy }
            .mapValues { (_, trades) ->
                val totalProfit = trades.sumOf { it.netProfitIdr }
                val avgProfit = trades.map { it.netProfitPct }.average()
                totalProfit to avgProfit
            }
        
        strategyPerformance.forEach { (strategy, performance) ->
            val (totalProfit, avgProfit) = performance
            
            if (totalProfit < -50000.0) {  // Lost > Rp50k
                insights.add(LearningInsight(
                    type = InsightType.BAD_STRATEGY,
                    message = "$strategy strategy lost Rp${totalProfit.format(0)} total - reconsider this!",
                    severity = InsightSeverity.HIGH,
                    data = mapOf(
                        "strategy" to strategy.name,
                        "totalLoss" to totalProfit.toString(),
                        "avgProfitPct" to avgProfit.toString(),
                    ),
                ))
            }
        }
        
        // Find best performing pairs
        pairStats.values
            .filter { it.totalTrades >= 3 && it.winRate >= 60.0 }
            .sortedByDescending { it.avgProfitPct }
            .take(3)
            .forEach { stats ->
                insights.add(LearningInsight(
                    type = InsightType.GOOD_PAIR,
                    message = "${stats.pair} is performing well: ${stats.winRate.format(1)}% win rate, ${stats.avgProfitPct.format(2)}% avg profit",
                    pair = stats.pair,
                    severity = InsightSeverity.INFO,
                    data = mapOf(
                        "winRate" to stats.winRate.toString(),
                        "avgProfit" to stats.avgProfitPct.toString(),
                    ),
                ))
            }
        
        return insights
    }
    
    /**
     * Get recent trades
     */
    fun getRecentTrades(limit: Int = 20): List<TradeRecord> {
        return trades.takeLast(limit)
    }
    
    /**
     * Get pair statistics
     */
    fun getPairStats(pair: String): PairStatistics? {
        return pairStats[pair]
    }
    
    /**
     * Get overall performance
     */
    fun getOverallPerformance(): PerformanceSummary {
        val totalProfit = trades.sumOf { it.netProfitIdr }
        val totalFees = trades.sumOf { it.totalFeeIdr }
        val winCount = trades.count { it.netProfitIdr > 0 }
        val lossCount = trades.count { it.netProfitIdr < 0 }
        val winRate = if (trades.isNotEmpty()) {
            (winCount.toDouble() / trades.size) * 100.0
        } else 0.0
        
        return PerformanceSummary(
            totalTrades = trades.size,
            totalProfitIdr = totalProfit,
            totalFeesIdr = totalFees,
            winCount = winCount,
            lossCount = lossCount,
            winRate = winRate,
            avgProfitPerTrade = if (trades.isNotEmpty()) totalProfit / trades.size else 0.0,
        )
    }
    
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}

data class TradeRecord(
    val id: Int,
    val timestamp: Instant,
    val pair: String,
    val strategy: PositionStrategy,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val entryFeeIdr: Double,
    val exitFeeIdr: Double,
    val totalFeeIdr: Double,
    val slippageIdr: Double,
    val totalCostIdr: Double,
    val netProfitIdr: Double,
    val netProfitPct: Double,
    val holdMinutes: Double,
    val exitReason: String,
)

data class PairStatistics(
    val pair: String,
    var totalTrades: Int,
    var winCount: Int,
    var lossCount: Int,
    var totalProfitIdr: Double,
    var totalFeesPaid: Double,
    var avgSlippageIdr: Double,
    var winRate: Double,
    var avgProfitPct: Double,
)

data class LearningInsight(
    val type: InsightType,
    val message: String,
    val pair: String = "",
    val severity: InsightSeverity,
    val data: Map<String, String> = emptyMap(),
)

enum class InsightType {
    BAD_PAIR,           // Pair has low win rate
    HIGH_SLIPPAGE,      // Pair has high slippage
    BAD_STRATEGY,       // Strategy losing money
    GOOD_PAIR,          // Pair performing well
    TIMING_ISSUE,       // Entry/exit timing problems
}

enum class InsightSeverity {
    HIGH,     // Critical issue, must fix
    MEDIUM,   // Important, should address
    INFO,     // Informational, good to know
}

data class PerformanceSummary(
    val totalTrades: Int,
    val totalProfitIdr: Double,
    val totalFeesIdr: Double,
    val winCount: Int,
    val lossCount: Int,
    val winRate: Double,
    val avgProfitPerTrade: Double,
)
