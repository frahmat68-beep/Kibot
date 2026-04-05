package com.kibot.core

import kotlin.math.abs

/**
 * CapitalAllocationManager - Manages 70/30 capital split for Trinity Bot
 * 
 * 70% Stable Rotation (Rp33,200 on Rp47,500 total)
 * - Conservative trades, 1.8% profit targets
 * - Limit orders for fee optimization
 * - Should generate ~90% of daily wins
 * 
 * 30% Aggressive (Rp8,300 on Rp47,500 total)
 * - Pump/anomaly trades, 3-5% profit targets
 * - Market orders for speed
 * - Higher volatility, higher reward
 * 
 * Capital rebalancing when drift > 5% detected
 */
class CapitalAllocationManager(
    private val totalCapitalIdr: Double = 47_500.0,  // IDR 47.5K total
    private val stableRotationPercent: Double = 0.70,  // 70% stable
    private val aggressivePercent: Double = 0.30,     // 30% aggressive
    private val rebalanceDriftThreshold: Double = 0.05 // 5% drift threshold
) {
    
    // Current allocations
    private var currentStableCapitalIdr = totalCapitalIdr * stableRotationPercent
    private var currentAggressiveCapitalIdr = totalCapitalIdr * aggressivePercent
    
    // Original target allocations (for rebalance detection)
    private val targetStableCapitalIdr = totalCapitalIdr * stableRotationPercent
    private val targetAggressiveCapitalIdr = totalCapitalIdr * aggressivePercent
    
    // Tracking
    private var totalDeployedStableIdr = 0.0
    private var totalDeployedAggressiveIdr = 0.0
    private var rebalanceCount = 0
    
    data class AllocationResult(
        val allocatedIdr: Double,
        val bucketType: String,  // "STABLE" or "AGGRESSIVE"
        val originalTarget: Double,
        val currentAvailable: Double,
        val requiresRebalance: Boolean,
        val rebalanceMessage: String? = null,
    )
    
    data class AllocationStatus(
        val stableCapitalIdr: Double,
        val aggressiveCapitalIdr: Double,
        val stablePercent: Double,
        val aggressivePercent: Double,
        val requiresRebalance: Boolean,
        val driftPercent: Double,
        val totalDeployedStable: Double,
        val totalDeployedAggressive: Double,
        val rebalanceCount: Int,
    )
    
    /**
     * Allocate capital for a trade
     * 
     * @param isAnomalyCoin True for 30% aggressive bucket, False for 70% stable bucket
     * @param requestedAmountIdr Amount to allocate
     * @return AllocationResult with allocated amount or error
     */
    fun allocate(isAnomalyCoin: Boolean, requestedAmountIdr: Double = 0.0): AllocationResult {
        val (currentBucket, targetBucket, bucketName) = if (isAnomalyCoin) {
            Triple(
                currentAggressiveCapitalIdr,
                targetAggressiveCapitalIdr,
                "AGGRESSIVE"
            )
        } else {
            Triple(
                currentStableCapitalIdr,
                targetStableCapitalIdr,
                "STABLE"
            )
        }
        
        // Use requested amount or full available bucket
        val allocateAmount = if (requestedAmountIdr > 0) {
            minOf(requestedAmountIdr, currentBucket)
        } else {
            currentBucket
        }
        
        // Deduct from current allocation
        if (isAnomalyCoin) {
            currentAggressiveCapitalIdr -= allocateAmount
            totalDeployedAggressiveIdr += allocateAmount
        } else {
            currentStableCapitalIdr -= allocateAmount
            totalDeployedStableIdr += allocateAmount
        }
        
        // Check if rebalance needed
        val requiresRebalance = detectRebalanceNeeded()
        
        return AllocationResult(
            allocatedIdr = allocateAmount,
            bucketType = bucketName,
            originalTarget = targetBucket,
            currentAvailable = if (isAnomalyCoin) currentAggressiveCapitalIdr else currentStableCapitalIdr,
            requiresRebalance = requiresRebalance,
            rebalanceMessage = if (requiresRebalance) {
                "Drift detected: ${getDriftPercent()}% | Rebalance recommended"
            } else null
        )
    }
    
    /**
     * Rebalance capital back to 70/30 split
     * Called when drift exceeds 5%
     */
    fun rebalance(): AllocationStatus {
        val currentTotal = currentStableCapitalIdr + currentAggressiveCapitalIdr
        
        currentStableCapitalIdr = currentTotal * stableRotationPercent
        currentAggressiveCapitalIdr = currentTotal * aggressivePercent
        
        rebalanceCount++
        
        return getStatus()
    }
    
    /**
     * Deposit profits back into capital pool
     * 
     * @param profitIdr Amount earned (net after fees)
     * @param wasAggressiveTrade True if profit came from aggressive trade
     */
    fun depositProfit(profitIdr: Double, wasAggressiveTrade: Boolean) {
        if (wasAggressiveTrade) {
            currentAggressiveCapitalIdr += profitIdr
        } else {
            currentStableCapitalIdr += profitIdr
        }
        
        // Auto-rebalance if drift exceeded
        if (detectRebalanceNeeded()) {
            rebalance()
        }
    }
    
    /**
     * Check if capital allocation drifted too far
     */
    private fun detectRebalanceNeeded(): Boolean {
        val totalAvailable = currentStableCapitalIdr + currentAggressiveCapitalIdr
        val currentStablePercent = currentStableCapitalIdr / totalAvailable
        val driftFromTarget = abs(currentStablePercent - stableRotationPercent)
        
        return driftFromTarget > rebalanceDriftThreshold
    }
    
    /**
     * Get current drift percentage
     */
    private fun getDriftPercent(): Double {
        val totalAvailable = currentStableCapitalIdr + currentAggressiveCapitalIdr
        if (totalAvailable == 0.0) return 0.0
        
        val currentStablePercent = currentStableCapitalIdr / totalAvailable
        return abs(currentStablePercent - stableRotationPercent) * 100.0
    }
    
    /**
     * Get current allocation status
     */
    fun getStatus(): AllocationStatus {
        val total = currentStableCapitalIdr + currentAggressiveCapitalIdr
        val stablePercent = if (total > 0) (currentStableCapitalIdr / total) * 100.0 else 0.0
        val aggressivePercent = if (total > 0) (currentAggressiveCapitalIdr / total) * 100.0 else 0.0
        
        return AllocationStatus(
            stableCapitalIdr = currentStableCapitalIdr,
            aggressiveCapitalIdr = currentAggressiveCapitalIdr,
            stablePercent = stablePercent,
            aggressivePercent = aggressivePercent,
            requiresRebalance = detectRebalanceNeeded(),
            driftPercent = getDriftPercent(),
            totalDeployedStable = totalDeployedStableIdr,
            totalDeployedAggressive = totalDeployedAggressiveIdr,
            rebalanceCount = rebalanceCount,
        )
    }
    
    /**
     * Reset to initial allocation
     */
    fun reset() {
        currentStableCapitalIdr = totalCapitalIdr * stableRotationPercent
        currentAggressiveCapitalIdr = totalCapitalIdr * aggressivePercent
        totalDeployedStableIdr = 0.0
        totalDeployedAggressiveIdr = 0.0
        rebalanceCount = 0
    }
}
