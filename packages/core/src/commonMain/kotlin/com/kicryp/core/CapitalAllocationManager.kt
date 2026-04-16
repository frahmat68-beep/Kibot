package com.kicryp.core

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
    private val stableRotationPercent: Double = 0.50,
    private val aggressivePercent: Double = 0.50,
    private val bucketBSpendablePercent: Double = 0.60,
    private val globalCashReservePercent: Double = 0.20,
    private val rebalanceDriftThreshold: Double = 0.05,
) {
    private val bucketAPercent = aggressivePercent
    private val bucketBPercent = stableRotationPercent
    
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
        const val MULTI_SLOT_TRIGGER_IDR = 25_000.0 // Threshold to allow parallel slots
        
        fun calculateDynamicAdditionalSlots(totalFreeIdr: Double): Int {
            if (totalFreeIdr < MIN_ORDER_INDODAX_IDR) return 0
            return floor(totalFreeIdr / MIN_ORDER_INDODAX_IDR).toInt().coerceAtLeast(1)
        }
    }

    data class AllocationResult(
        val allocatedIdr: Double,
        val bucketType: String, // "A", "B", or "MICRO"
        val availableInBucket: Double,
        val role: String? = null,
        val currentAvailable: Double = availableInBucket,
        val requiresRebalance: Boolean = false,
        val rebalanceMessage: String = "",
        val positionRole: String? = role,
    )

    data class CapitalStatus(
        val totalEquityIdr: Double,
        val stableCapitalIdr: Double,
        val aggressiveCapitalIdr: Double,
        val totalDeployedStable: Double,
        val totalDeployedAggressive: Double,
        val stablePercent: Double,
        val aggressivePercent: Double,
        val mode: String,
        val driftPercent: Double,
        val rebalanceCount: Int,
        val requiresRebalance: Boolean,
    )

    private var rebalanceCount = 0

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
        
        return AllocationResult(
            allocatedIdr = amount,
            bucketType = "AGGRESSIVE",
            availableInBucket = availableAIdr,
            currentAvailable = availableAIdr,
            requiresRebalance = requiresRebalance(),
            rebalanceMessage = buildRebalanceMessage(),
        )
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
        
        return AllocationResult(
            allocatedIdr = amount,
            bucketType = "STABLE",
            availableInBucket = availableBIdr,
            currentAvailable = availableBIdr,
            requiresRebalance = requiresRebalance(),
            rebalanceMessage = buildRebalanceMessage(),
        )
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
        
        return AllocationResult(
            allocatedIdr = amount,
            bucketType = "MICRO_POOL",
            availableInBucket = deployable,
            role = role,
            currentAvailable = deployable,
            requiresRebalance = false,
            rebalanceMessage = "",
            positionRole = role,
        )
    }

    fun calculateMaxPositions(totalFreeIdr: Double): Int {
        return (totalFreeIdr / MIN_ORDER_INDODAX_IDR).toInt().coerceAtLeast(0)
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

    fun reset() {
        isMicroAccount = false
        microModeMaxPositions = 0
        currentTotalEquityIdr = totalCapitalIdr
        rebalanceCount = 0
        availableAIdr = totalCapitalIdr * (1 - globalCashReservePercent) * bucketAPercent
        availableBIdr = totalCapitalIdr * (1 - globalCashReservePercent) * bucketBPercent * bucketBSpendablePercent
        deployedAIdr = 0.0
        deployedBIdr = 0.0
    }

    fun allocate(
        isAnomalyCoin: Boolean,
        requestedAmountIdr: Double,
        totalFreeIdr: Double = availableAIdr + availableBIdr,
        currentPositionCount: Int = 0,
    ): AllocationResult {
        if (currentPositionCount >= calculateMaxPositions(totalFreeIdr)) {
            return AllocationResult(
                allocatedIdr = 0.0,
                bucketType = if (isAnomalyCoin) "AGGRESSIVE" else "STABLE",
                availableInBucket = if (isAnomalyCoin) availableAIdr else availableBIdr,
                currentAvailable = if (isAnomalyCoin) availableAIdr else availableBIdr,
                requiresRebalance = requiresRebalance(),
                rebalanceMessage = "Position limit reached for current free capital",
            )
        }
        return if (isAnomalyCoin) allocateA(requestedAmountIdr) else allocateB(requestedAmountIdr)
    }

    fun updateFreeCapital(
        freeIdr: Double,
        totalEquityIdr: Double,
        stableHoldingsIdr: Double,
        aggressiveHoldingsIdr: Double,
    ) {
        updateEquity(
            totalEquityIdr = totalEquityIdr,
            freeIdr = freeIdr,
            deployedA = aggressiveHoldingsIdr,
            deployedB = stableHoldingsIdr,
        )
    }

    fun depositProfit(profitIdr: Double, wasAggressiveTrade: Boolean) {
        currentTotalEquityIdr += profitIdr
        if (wasAggressiveTrade) {
            availableAIdr = (availableAIdr + profitIdr).coerceAtLeast(0.0)
            deployedAIdr = (deployedAIdr - profitIdr).coerceAtLeast(0.0)
        } else {
            availableBIdr = (availableBIdr + profitIdr).coerceAtLeast(0.0)
            deployedBIdr = (deployedBIdr - profitIdr).coerceAtLeast(0.0)
        }
    }

    fun rebalance() {
        rebalanceCount += 1
        updateEquity(
            totalEquityIdr = currentTotalEquityIdr,
            freeIdr = availableAIdr + availableBIdr,
            deployedA = deployedAIdr,
            deployedB = deployedBIdr,
        )
    }

    fun allocateMicroMode(
        totalFreeIdr: Double,
        currentPositionCount: Int,
        isHighPumpSignal: Boolean,
    ): AllocationResult {
        val maxPositions = calculateMaxPositions(totalFreeIdr)
        if (maxPositions <= 0 || currentPositionCount >= maxPositions) {
            val role = if (isHighPumpSignal) "CHASER" else "HOLDER"
            return AllocationResult(
                allocatedIdr = 0.0,
                bucketType = "MICRO_POOL",
                availableInBucket = totalFreeIdr,
                role = role,
                currentAvailable = totalFreeIdr,
                requiresRebalance = false,
                rebalanceMessage = "max_positions_reached",
                positionRole = role,
            )
        }
        return allocateMicro(isAggressive = isHighPumpSignal)
    }

    fun getStatus(): CapitalStatus = CapitalStatus(
        totalEquityIdr = currentTotalEquityIdr,
        stableCapitalIdr = availableBIdr,
        aggressiveCapitalIdr = availableAIdr,
        totalDeployedStable = deployedBIdr,
        totalDeployedAggressive = deployedAIdr,
        stablePercent = bucketBPercent * 100.0,
        aggressivePercent = bucketAPercent * 100.0,
        mode = if (isMicroAccount) "MICRO" else "TRINITY_V7",
        driftPercent = currentDriftPercent(),
        rebalanceCount = rebalanceCount,
        requiresRebalance = requiresRebalance(),
    )

    private fun currentDriftPercent(): Double {
        val tradeableTotal = currentTotalEquityIdr * (1 - globalCashReservePercent)
        if (tradeableTotal <= 0.0) return 0.0
        val actualAPct = deployedAIdr / tradeableTotal
        val actualBPct = deployedBIdr / tradeableTotal
        return maxOf(abs(actualAPct - bucketAPercent), abs(actualBPct - (bucketBPercent * bucketBSpendablePercent)))
    }

    private fun requiresRebalance(): Boolean = currentDriftPercent() >= rebalanceDriftThreshold

    private fun buildRebalanceMessage(): String =
        if (requiresRebalance()) "Capital bucket drift exceeded threshold" else ""
}
