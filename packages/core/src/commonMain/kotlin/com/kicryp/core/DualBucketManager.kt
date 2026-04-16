package com.kicryp.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * DualBucketManager — KiCryp v7.0 Capital Architecture
 * 
 * 50% Bucket A: Global Lead-Lag (Aggressive)
 * - 100% spendable of its 50% share.
 * - Targeted at momentum and lead-lag arbitrage.
 * 
 * 50% Bucket B: Local Indodax (Stable/Conviction)
 * - 60% spendable of its 50% share (40% LOCAL RESERVE).
 * - Requires Conviction Score >= 7.0 for entry.
 * 
 * Global Safeguard:
 * - 20% Cash Reserve always maintained.
 */
class DualBucketManager(
    private val totalCapitalIdr: Double,
    private val bucketAPercent: Double = 0.50,
    private val bucketBPercent: Double = 0.50,
    private val bucketBSpendablePercent: Double = 0.60,
    private val globalCashReservePercent: Double = 0.20
) {
    private var currentTotalEquityIdr = totalCapitalIdr
    
    // Available capital in each bucket
    private var availableAIdr = totalCapitalIdr * (1 - globalCashReservePercent) * bucketAPercent
    private var availableBIdr = totalCapitalIdr * (1 - globalCashReservePercent) * bucketBPercent * bucketBSpendablePercent
    
    private var deployedAIdr = 0.0
    private var deployedBIdr = 0.0

    companion object {
        const val MIN_ORDER_IDR = 10_000.0
        const val MAX_SINGLE_POSITION_PCT = 0.20
        const val BUCKET_B_CONVICTION_THRESHOLD = 7.0
    }

    data class AllocationResult(
        val allocatedIdr: Double,
        val bucketType: String,
        val reason: String = ""
    )

    /**
     * Allocate capital based on the signal and conviction.
     */
    fun allocate(
        isAggressive: Boolean,
        requestedAmountIdr: Double,
        convictionScore: Double = 0.0,
        currentPositionCount: Int = 0
    ): AllocationResult {
        
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        
        if (isAggressive) {
            // Bucket A: Aggressive
            val limit = availableAIdr
            val amount = minOf(requestedAmountIdr, limit, maxPerCoin)
            
            if (amount < MIN_ORDER_IDR) {
                return AllocationResult(0.0, "AGGRESSIVE", "Bucket A insufficient: available=${limit.format(0)}")
            }
            
            availableAIdr -= amount
            deployedAIdr += amount
            return AllocationResult(amount, "AGGRESSIVE")
            
        } else {
            // Bucket B: Stable/Local
            if (convictionScore < BUCKET_B_CONVICTION_THRESHOLD) {
                return AllocationResult(0.0, "STABLE", "Conviction too low: $convictionScore < $BUCKET_B_CONVICTION_THRESHOLD")
            }
            
            val limit = availableBIdr
            val amount = minOf(requestedAmountIdr, limit, maxPerCoin)
            
            if (amount < MIN_ORDER_IDR) {
                return AllocationResult(0.0, "STABLE", "Bucket B insufficient: available=${limit.format(0)} (40% reserve locked)")
            }
            
            availableBIdr -= amount
            deployedBIdr += amount
            return AllocationResult(amount, "STABLE")
        }
    }

    fun updateEquity(totalEquityIdr: Double, freeIdr: Double, deployedA: Double, deployedB: Double) {
        this.currentTotalEquityIdr = totalEquityIdr
        this.deployedAIdr = deployedA
        this.deployedBIdr = deployedB
        
        val tradeableTotal = totalEquityIdr * (1 - globalCashReservePercent)
        
        val targetA = tradeableTotal * bucketAPercent
        val targetB = tradeableTotal * bucketBPercent * bucketBSpendablePercent
        
        availableAIdr = maxOf(0.0, targetA - deployedA)
        availableBIdr = maxOf(0.0, targetB - deployedB)
        
        // Ensure we don't allocate more than physically possible
        val totalUnmet = availableAIdr + availableBIdr
        if (totalUnmet > freeIdr && totalUnmet > 0) {
            val ratio = freeIdr / totalUnmet
            availableAIdr *= ratio
            availableBIdr *= ratio
        }
    }

    fun depositProfit(profitIdr: Double, wasAggressiveTrade: Boolean) {
        currentTotalEquityIdr += profitIdr
        if (wasAggressiveTrade) {
            availableAIdr = (availableAIdr + profitIdr).coerceAtLeast(0.0)
            deployedAIdr = (deployedAIdr - profitIdr).coerceAtLeast(0.0)
        } else {
            availableBIdr = (availableBIdr + profitIdr).coerceAtLeast(0.0)
        }
    }

    data class CapitalStatus(
        val totalEquityIdr: Double,
        val stableCapitalIdr: Double,
        val aggressiveCapitalIdr: Double,
        val totalDeployedStable: Double,
        val totalDeployedAggressive: Double,
        val stablePercent: Double,
        val aggressivePercent: Double,
        val mode: String = "TRINITY_V7",
        val driftPercent: Double = 0.0,
        val rebalanceCount: Int = 0,
        val requiresRebalance: Boolean = false
    )

    fun getStatus(): CapitalStatus = CapitalStatus(
        totalEquityIdr = currentTotalEquityIdr,
        stableCapitalIdr = availableBIdr,
        aggressiveCapitalIdr = availableAIdr,
        totalDeployedStable = deployedBIdr,
        totalDeployedAggressive = deployedAIdr,
        stablePercent = bucketBPercent * 100.0,
        aggressivePercent = bucketAPercent * 100.0
    )

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
