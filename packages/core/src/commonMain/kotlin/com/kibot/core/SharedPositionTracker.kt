package com.kibot.core

import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * SharedPositionTracker - ALL 3 bots know what KiDax is holding
 * 
 * When KiDax buys:
 * - Broadcast UDP: "I bought BTC at 150, fee 0.3%, target 165"
 * - Kinance knows: "OK, BTC position open, watch Binance for exit signal"
 * - KiBot knows: "OK, track BTC performance, ready to veto exit"
 */
class SharedPositionTracker {
    private val positions = mutableMapOf<String, SharedPosition>()
    
    /**
     * Broadcast when KiDax opens position
     */
    fun broadcastPositionOpened(
        pair: String,
        entryPrice: Double,
        quantity: Double,
        entryFeeIdr: Double,
        capitalUsedIdr: Double,
        strategy: PositionStrategy,  // ANOMALY or STABLE
    ): PositionBroadcast {
        val position = SharedPosition(
            pair = pair,
            entryPrice = entryPrice,
            quantity = quantity,
            entryFeeIdr = entryFeeIdr,
            capitalUsedIdr = capitalUsedIdr,
            strategy = strategy,
            openedAt = Instant.DISTANT_PAST,  // Will be set by caller
            status = PositionStatus.OPEN,
        )
        
        positions[pair] = position
        
        return PositionBroadcast(
            action = "POSITION_OPENED",
            pair = pair,
            entryPrice = entryPrice,
            quantity = quantity,
            entryFeeIdr = entryFeeIdr,
            capitalUsedIdr = capitalUsedIdr,
            strategy = strategy.name,
            targetProfitPct = when (strategy) {
                PositionStrategy.ANOMALY -> 15.0  // 15% target for anomaly
                PositionStrategy.STABLE -> 3.0     // 3% target for stable
            },
            stopLossPct = when (strategy) {
                PositionStrategy.ANOMALY -> 3.0   // Wider stop for anomaly
                PositionStrategy.STABLE -> 2.0    // Tight stop for stable
            },
        )
    }
    
    /**
     * Broadcast when KiDax closes position
     */
    fun broadcastPositionClosed(
        pair: String,
        exitPrice: Double,
        exitFeeIdr: Double,
        netProfitIdr: Double,
        netProfitPct: Double,
        holdMinutes: Double,
        reason: String,
    ): PositionBroadcast {
        val position = positions[pair]
        
        if (position != null) {
            position.status = PositionStatus.CLOSED
            position.exitPrice = exitPrice
            position.netProfitIdr = netProfitIdr
            position.totalFeeIdr = position.entryFeeIdr + exitFeeIdr
        }
        
        return PositionBroadcast(
            action = "POSITION_CLOSED",
            pair = pair,
            entryPrice = position?.entryPrice ?: 0.0,
            exitPrice = exitPrice,
            netProfitIdr = netProfitIdr,
            netProfitPct = netProfitPct,
            totalFeeIdr = (position?.entryFeeIdr ?: 0.0) + exitFeeIdr,
            holdMinutes = holdMinutes,
            reason = reason,
        )
    }
    
    /**
     * Get all open positions (for other bots to see)
     */
    fun getOpenPositions(): List<SharedPosition> {
        return positions.values.filter { it.status == PositionStatus.OPEN }
    }
    
    /**
     * Get position info (other bots can query)
     */
    fun getPosition(pair: String): SharedPosition? {
        return positions[pair]
    }
    
    /**
     * Calculate total capital deployed
     */
    fun getTotalCapitalDeployed(): Double {
        return getOpenPositions().sumOf { it.capitalUsedIdr }
    }
    
    /**
     * Calculate capital by strategy
     */
    fun getCapitalByStrategy(strategy: PositionStrategy): Double {
        return getOpenPositions()
            .filter { it.strategy == strategy }
            .sumOf { it.capitalUsedIdr }
    }
    
    /**
     * Check if we're within capital limits
     * 20% for ANOMALY, 80% for STABLE
     */
    fun validateCapitalAllocation(
        totalCash: Double,
        proposedStrategy: PositionStrategy,
        proposedAmount: Double,
    ): CapitalAllocationCheck {
        val currentAnomalyCapital = getCapitalByStrategy(PositionStrategy.ANOMALY)
        val currentStableCapital = getCapitalByStrategy(PositionStrategy.STABLE)
        val totalDeployed = getTotalCapitalDeployed()
        
        val maxAnomalyCapital = totalCash * 0.20  // 20% max for anomaly
        val maxStableCapital = totalCash * 0.80   // 80% max for stable
        
        return when (proposedStrategy) {
            PositionStrategy.ANOMALY -> {
                val newAnomalyTotal = currentAnomalyCapital + proposedAmount
                if (newAnomalyTotal > maxAnomalyCapital) {
                    CapitalAllocationCheck(
                        allowed = false,
                        reason = "ANOMALY_CAPITAL_EXCEEDED",
                        currentAnomalyPct = (currentAnomalyCapital / totalCash) * 100,
                        maxAnomalyPct = 20.0,
                    )
                } else {
                    CapitalAllocationCheck(allowed = true)
                }
            }
            PositionStrategy.STABLE -> {
                val newStableTotal = currentStableCapital + proposedAmount
                if (newStableTotal > maxStableCapital) {
                    CapitalAllocationCheck(
                        allowed = false,
                        reason = "STABLE_CAPITAL_EXCEEDED",
                        currentStablePct = (currentStableCapital / totalCash) * 100,
                        maxStablePct = 80.0,
                    )
                } else {
                    CapitalAllocationCheck(allowed = true)
                }
            }
        }
    }
}

enum class PositionStrategy {
    ANOMALY,  // 20% capital - chase pumps, high risk high reward
    STABLE,   // 80% capital - steady trading, lower risk
}

enum class PositionStatus {
    OPEN,
    CLOSED,
}

data class SharedPosition(
    val pair: String,
    val entryPrice: Double,
    val quantity: Double,
    val entryFeeIdr: Double,
    val capitalUsedIdr: Double,
    val strategy: PositionStrategy,
    val openedAt: Instant,
    var status: PositionStatus,
    var exitPrice: Double? = null,
    var netProfitIdr: Double? = null,
    var totalFeeIdr: Double? = null,
)

data class PositionBroadcast(
    val action: String,  // POSITION_OPENED or POSITION_CLOSED
    val pair: String,
    val entryPrice: Double = 0.0,
    val exitPrice: Double? = null,
    val quantity: Double = 0.0,
    val entryFeeIdr: Double = 0.0,
    val capitalUsedIdr: Double = 0.0,
    val strategy: String = "",
    val targetProfitPct: Double = 0.0,
    val stopLossPct: Double = 0.0,
    val netProfitIdr: Double? = null,
    val netProfitPct: Double? = null,
    val totalFeeIdr: Double? = null,
    val holdMinutes: Double? = null,
    val reason: String = "",
)

data class CapitalAllocationCheck(
    val allowed: Boolean,
    val reason: String = "",
    val currentAnomalyPct: Double = 0.0,
    val maxAnomalyPct: Double = 20.0,
    val currentStablePct: Double = 0.0,
    val maxStablePct: Double = 80.0,
)
