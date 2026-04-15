package com.kibot.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * CapitalAllocationManager - Trinity v7.0 Dual Bucket Architecture
 * 
 * 50% Bucket A: Global Lead-Lag (Kinance + KiCom)
 * - 100% spendable of its 50% share
 * - Low-latency consensus signals
 * 
 * 50% Bucket B: Local Indodax-Only (ConvictionScore)
 * - 60% spendable of its 50% share (40% local reserve)
 * - Higher profit targets, mathematical conviction
 * 
 * Global:
 * - 20% Cash Reserve always maintained
 * - Micro-Account Mode (<500K) for small balances
 */
class CapitalAllocationManager(
    private val totalCapitalIdr: Double = 60_000.0,
    private val bucketAPercent: Double = 0.50,
    private val bucketBPercent: Double = 0.50,
    private val bucketBSpendablePercent: Double = 0.60,
    private val globalCashReservePercent: Double = 0.20,
    private val rebalanceDriftThreshold: Double = 0.05
) {
    
    private var isMicroAccount: Boolean = false
    private var microModeMaxPositions: Int = 0
    private var currentTotalEquityIdr = totalCapitalIdr
    
    // Bucket tracking
    private var availableAIdr = totalCapitalIdr * (1 - globalCashReservePercent) * bucketAPercent
    private var availableBIdr = totalCapitalIdr * (1 - globalCashReservePercent) * bucketBPercent * bucketBSpendablePercent
    
    private var deployedAIdr = 0.0
    private var deployedBIdr = 0.0
    
    companion object {
        const val MICRO_ACCOUNT_THRESHOLD_IDR = 500_000.0
        const val MIN_ORDER_INDODAX_IDR = 10_000.0
        const val MAX_SINGLE_POSITION_PCT = 0.25
        
        fun calculateDynamicAdditionalSlots(totalFreeIdr: Double): Int {
            if (totalFreeIdr < MIN_ORDER_INDODAX_IDR) return 0
            return floor(totalFreeIdr / MIN_ORDER_INDODAX_IDR).toInt().coerceAtLeast(1)
        }
    }

    data class AllocationResult(
        val allocatedIdr: Double,
        val bucketType: String, // "A", "B", or "MICRO"
        val availableInBucket: Double,
        val role: String? = null
    )

    /**
     * Allocate for Bucket A (Global Lead-Lag)
     */
    fun allocateA(requestedIdr: Double = 0.0): AllocationResult {
        if (checkMicroMode()) return allocateMicro(true)
        
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        val limit = availableAIdr
        
        val amount = if (requestedIdr > 0) minOf(requestedIdr, limit, maxPerCoin) else minOf(limit, maxPerCoin)
        
        if (amount >= MIN_ORDER_INDODAX_IDR) {
            availableAIdr -= amount
            deployedAIdr += amount
        }
        
        return AllocationResult(amount, "A", availableAIdr)
    }

    /**
     * Allocate for Bucket B (Local Indodax-Only)
     */
    fun allocateB(requestedIdr: Double = 0.0): AllocationResult {
        if (checkMicroMode()) return allocateMicro(false)
        
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        // Bucket B has 40% reserve within its own allocation
        val limit = availableBIdr
        
        val amount = if (requestedIdr > 0) minOf(requestedIdr, limit, maxPerCoin) else minOf(limit, maxPerCoin)
        
        if (amount >= MIN_ORDER_INDODAX_IDR) {
            availableBIdr -= amount
            deployedBIdr += amount
        }
        
        return AllocationResult(amount, "B", availableBIdr)
    }

    private fun checkMicroMode(): Boolean {
        isMicroAccount = currentTotalEquityIdr < MICRO_ACCOUNT_THRESHOLD_IDR
        return isMicroAccount
    }

    private fun allocateMicro(isAggressive: Boolean): AllocationResult {
        val deployable = currentTotalEquityIdr * (1 - globalCashReservePercent)
        val maxPos = calculateMaxPositions(deployable)
        val perPos = if (maxPos > 0) deployable / maxPos else 0.0
        
        val amount = if (perPos >= MIN_ORDER_INDODAX_IDR) perPos else 0.0
        val role = if (isAggressive) "CHASER" else "HOLDER"
        
        return AllocationResult(amount, "MICRO", deployable, role)
    }

    fun calculateMaxPositions(deployableIdr: Double): Int {
        return (deployableIdr / MIN_ORDER_INDODAX_IDR).toInt().coerceAtLeast(0)
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
        
        // Clamp to actual free balance
        val totalUnmet = availableAIdr + availableBIdr
        if (totalUnmet > freeIdr && totalUnmet > 0) {
            val ratio = freeIdr / totalUnmet
            availableAIdr *= ratio
            availableBIdr *= ratio
        }
    }

    fun getStatus() = mapOf(
        "total_equity" to currentTotalEquityIdr,
        "available_a" to availableAIdr,
        "available_b" to availableBIdr,
        "deployed_a" to deployedAIdr,
        "deployed_b" to deployedBIdr,
        "mode" to if (isMicroAccount) "MICRO" else "TRINITY_V7"
    )
}
