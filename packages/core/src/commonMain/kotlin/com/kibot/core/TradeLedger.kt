package com.kibot.core

import com.kibot.shared.models.*
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
        entryPrice: DecimalValue,
        exitPrice: DecimalValue,
        quantity: DecimalValue,
        entryFeeIdr: DecimalValue,
        exitFeeIdr: DecimalValue,
        slippageIdr: DecimalValue,
        netProfitIdr: DecimalValue,
        netProfitPct: DecimalValue,
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
                totalProfitIdr = DecimalValue.Zero,
                totalFeesPaid = DecimalValue.Zero,
                avgSlippageIdr = DecimalValue.Zero,
                winRate = 0.0,
                avgProfitPct = DecimalValue.Zero,
            )
        }
        
        stats.totalTrades++
        stats.totalProfitIdr += trade.netProfitIdr
        stats.totalFeesPaid += trade.totalFeeIdr
        
        if (trade.netProfitIdr > DecimalValue.Zero) {
            stats.winCount++
        } else if (trade.netProfitIdr < DecimalValue.Zero) {
            stats.lossCount++
        }
        
        // Update averages
        stats.winRate = (stats.winCount.toDouble() / stats.totalTrades) * 100.0
        
        val pairTrades = trades.filter { it.pair == trade.pair }
        if (pairTrades.isNotEmpty()) {
            stats.avgProfitPct = pairTrades.fold(DecimalValue.Zero) { acc, t -> acc + t.netProfitPct } / pairTrades.size.toDouble()
            stats.avgSlippageIdr = pairTrades.fold(DecimalValue.Zero) { acc, t -> acc + t.slippageIdr } / pairTrades.size.toDouble()
        }
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
                    message = "${stats.pair} has ${stats.winRate.toFormattedString(1)}% win rate - avoid trading this!",
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
            .filter { it.avgSlippageIdr > DecimalValue("1000") }  // More than Rp1000 avg slippage
            .forEach { stats ->
                insights.add(LearningInsight(
                    type = InsightType.HIGH_SLIPPAGE,
                    message = "${stats.pair} has Rp${stats.avgSlippageIdr.toFormattedString(0)} avg slippage - very costly!",
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
                val totalProfit = trades.fold(DecimalValue.Zero) { acc, t -> acc + t.netProfitIdr }
                val avgProfit = trades.fold(DecimalValue.Zero) { acc, t -> acc + t.netProfitPct } / trades.size.toDouble()
                totalProfit to avgProfit
            }
        
        strategyPerformance.forEach { (strategy, performance) ->
            val (totalProfit, avgProfit) = performance
            
            if (totalProfit < DecimalValue("-50000")) {  // Lost > Rp50k
                insights.add(LearningInsight(
                    type = InsightType.BAD_STRATEGY,
                    message = "$strategy strategy lost Rp${totalProfit.toFormattedString(0)} total - reconsider this!",
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
            .sortedByDescending { it.avgProfitPct.toDoubleOrZero() }
            .take(3)
            .forEach { stats ->
                insights.add(LearningInsight(
                    type = InsightType.GOOD_PAIR,
                    message = "${stats.pair} is performing well: ${stats.winRate.toFormattedString(1)}% win rate, ${stats.avgProfitPct.toFormattedString(2)}% avg profit",
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
        val totalProfit = trades.fold(DecimalValue.Zero) { acc, t -> acc + t.netProfitIdr }
        val totalFees = trades.fold(DecimalValue.Zero) { acc, t -> acc + t.totalFeeIdr }
        val winCount = trades.count { it.netProfitIdr > DecimalValue.Zero }
        val lossCount = trades.count { it.netProfitIdr < DecimalValue.Zero }
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
            avgProfitPerTrade = if (trades.isNotEmpty()) totalProfit / trades.size.toDouble() else DecimalValue.Zero,
        )
    }
}

data class TradeRecord(
    val id: Int,
    val timestamp: Instant,
    val pair: String,
    val strategy: PositionStrategy,
    val entryPrice: DecimalValue,
    val exitPrice: DecimalValue,
    val quantity: DecimalValue,
    val entryFeeIdr: DecimalValue,
    val exitFeeIdr: DecimalValue,
    val totalFeeIdr: DecimalValue,
    val slippageIdr: DecimalValue,
    val totalCostIdr: DecimalValue,
    val netProfitIdr: DecimalValue,
    val netProfitPct: DecimalValue,
    val holdMinutes: Double,
    val exitReason: String,
)

data class PairStatistics(
    val pair: String,
    var totalTrades: Int,
    var winCount: Int,
    var lossCount: Int,
    var totalProfitIdr: DecimalValue,
    var totalFeesPaid: DecimalValue,
    var avgSlippageIdr: DecimalValue,
    var winRate: Double,
    var avgProfitPct: DecimalValue,
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
    val totalProfitIdr: DecimalValue,
    val totalFeesIdr: DecimalValue,
    val winCount: Int,
    val lossCount: Int,
    val winRate: Double,
    val avgProfitPerTrade: DecimalValue,
)
