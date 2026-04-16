package com.kibot.core

import com.kibot.shared.models.*
import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * SharedPositionTracker - ALL 3 bots know what KiDax is holding
 * 
 * When KiDax buys:
 * - Broadcast UDP: "I bought BTC at 150, fee 0.3%, target 165"
 * - Kinance knows: "OK, BTC position open, watch Binance for exit signal"
 * - KiCryp knows: "OK, track BTC performance, ready to veto exit"
 */
class SharedPositionTracker {
    private val positions = mutableMapOf<String, SharedPosition>()
    
    /**
     * Broadcast when KiDax opens position
     */
    fun broadcastPositionOpened(
        pair: String,
        entryPrice: DecimalValue,
        quantity: DecimalValue,
        entryFeeIdr: DecimalValue,
        capitalUsedIdr: DecimalValue,
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
        exitPrice: DecimalValue,
        exitFeeIdr: DecimalValue,
        netProfitIdr: DecimalValue,
        netProfitPct: DecimalValue,
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
            entryPrice = position?.entryPrice ?: DecimalValue.Zero,
            exitPrice = exitPrice,
            netProfitIdr = netProfitIdr,
            netProfitPct = netProfitPct,
            totalFeeIdr = (position?.entryFeeIdr ?: DecimalValue.Zero) + exitFeeIdr,
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
    fun getTotalCapitalDeployed(): DecimalValue {
        return getOpenPositions().fold(DecimalValue.Zero) { acc, p -> acc + p.capitalUsedIdr }
    }
    
    /**
     * Calculate capital by strategy
     */
    fun getCapitalByStrategy(strategy: PositionStrategy): DecimalValue {
        return getOpenPositions()
            .filter { it.strategy == strategy }
            .fold(DecimalValue.Zero) { acc, p -> acc + p.capitalUsedIdr }
    }
    
    /**
     * Check if we're within capital limits
     * 20% for ANOMALY, 80% for STABLE
     */
    fun validateCapitalAllocation(
        totalCash: DecimalValue,
        proposedStrategy: PositionStrategy,
        proposedAmount: DecimalValue,
    ): CapitalAllocationCheck {
        val currentAnomalyCapital = getCapitalByStrategy(PositionStrategy.ANOMALY)
        val currentStableCapital = getCapitalByStrategy(PositionStrategy.STABLE)
        
        val maxAnomalyCapital = totalCash * 0.20  // 20% max for anomaly
        val maxStableCapital = totalCash * 0.80   // 80% max for stable
        
        return when (proposedStrategy) {
            PositionStrategy.ANOMALY -> {
                val newAnomalyTotal = currentAnomalyCapital + proposedAmount
                if (newAnomalyTotal > maxAnomalyCapital) {
                    CapitalAllocationCheck(
                        allowed = false,
                        reason = "ANOMALY_CAPITAL_EXCEEDED",
                        currentAnomalyPct = (currentAnomalyCapital / totalCash) * 100.0,
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
                        currentStablePct = (currentStableCapital / totalCash) * 100.0,
                        maxStablePct = 80.0,
                    )
                } else {
                    CapitalAllocationCheck(allowed = true)
                }
            }
        }
    }
}

data class SharedPosition(
    val pair: String,
    val entryPrice: DecimalValue,
    val quantity: DecimalValue,
    val entryFeeIdr: DecimalValue,
    val capitalUsedIdr: DecimalValue,
    val strategy: PositionStrategy,
    val openedAt: Instant,
    var status: PositionStatus,
    var exitPrice: DecimalValue? = null,
    var netProfitIdr: DecimalValue? = null,
    var totalFeeIdr: DecimalValue? = null,
)

data class PositionBroadcast(
    val action: String,  // POSITION_OPENED or POSITION_CLOSED
    val pair: String,
    val entryPrice: DecimalValue = DecimalValue.Zero,
    val exitPrice: DecimalValue? = null,
    val quantity: DecimalValue = DecimalValue.Zero,
    val entryFeeIdr: DecimalValue = DecimalValue.Zero,
    val capitalUsedIdr: DecimalValue = DecimalValue.Zero,
    val strategy: String = "",
    val targetProfitPct: Double = 0.0,
    val stopLossPct: Double = 0.0,
    val netProfitIdr: DecimalValue? = null,
    val netProfitPct: DecimalValue? = null,
    val totalFeeIdr: DecimalValue? = null,
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
