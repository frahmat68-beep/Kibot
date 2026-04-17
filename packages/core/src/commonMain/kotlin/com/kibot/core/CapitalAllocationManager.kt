package com.kibot.core

import com.kibot.shared.models.DecimalValue
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
    private var totalCapitalIdr: DecimalValue = DecimalValue("60000"),
    private val stableRotationPercent: DecimalValue = DecimalValue("0.50"),
    private val aggressivePercent: DecimalValue = DecimalValue("0.50"),
    private val bucketBSpendablePercent: DecimalValue = DecimalValue("0.60"),
    private val globalCashReservePercent: DecimalValue = DecimalValue("0.20"),
    private val rebalanceDriftThreshold: DecimalValue = DecimalValue("0.05"),
) {
    private val bucketAPercent = aggressivePercent
    private val bucketBPercent = stableRotationPercent
    
    private var isMicroAccount: Boolean = false
    private var microModeMaxPositions: Int = 0
    private var currentTotalEquityIdr = totalCapitalIdr
    
    // Bucket tracking
    private var availableAIdr = totalCapitalIdr * (DecimalValue("1.0") - globalCashReservePercent) * bucketAPercent
    private var availableBIdr = totalCapitalIdr * (DecimalValue("1.0") - globalCashReservePercent) * bucketBPercent * bucketBSpendablePercent
    
    private var deployedAIdr = DecimalValue.Zero
    private var deployedBIdr = DecimalValue.Zero
    
    companion object {
        val MICRO_ACCOUNT_THRESHOLD_IDR = DecimalValue("500000")
        val MIN_ORDER_INDODAX_IDR = DecimalValue("10000")
        val MAX_SINGLE_POSITION_PCT = DecimalValue("0.25")
        val MULTI_SLOT_TRIGGER_IDR = DecimalValue("25000") // Threshold to allow parallel slots
        
        fun calculateDynamicAdditionalSlots(totalFreeIdr: DecimalValue): Int {
            if (totalFreeIdr < MIN_ORDER_INDODAX_IDR) return 0
            val count = totalFreeIdr.toScaledLong() / MIN_ORDER_INDODAX_IDR.toScaledLong()
            return count.toInt().coerceAtLeast(1)
        }
    }

    data class AllocationResult(
        val allocatedIdr: DecimalValue,
        val bucketType: String, // "A", "B", or "MICRO"
        val availableInBucket: DecimalValue,
        val role: String? = null,
        val currentAvailable: DecimalValue = availableInBucket,
        val requiresRebalance: Boolean = false,
        val rebalanceMessage: String = "",
        val positionRole: String? = role,
    )

    data class CapitalStatus(
        val totalEquityIdr: DecimalValue,
        val stableCapitalIdr: DecimalValue,
        val aggressiveCapitalIdr: DecimalValue,
        val totalDeployedStable: DecimalValue,
        val totalDeployedAggressive: DecimalValue,
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
    fun allocateA(requestedIdr: DecimalValue = DecimalValue.Zero): AllocationResult {
        if (checkMicroMode()) return allocateMicro(true)
        
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        val limit = availableAIdr
        
        val amount = if (requestedIdr > DecimalValue.Zero) {
            DecimalValue.minOf(requestedIdr, limit, maxPerCoin)
        } else {
            DecimalValue.minOf(limit, maxPerCoin)
        }
        
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
    fun allocateB(requestedIdr: DecimalValue = DecimalValue.Zero): AllocationResult {
        if (checkMicroMode()) return allocateMicro(false)
        
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        val limit = availableBIdr
        
        val amount = if (requestedIdr > DecimalValue.Zero) {
            DecimalValue.minOf(requestedIdr, limit, maxPerCoin)
        } else {
            DecimalValue.minOf(limit, maxPerCoin)
        }
        
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
        val deployable = currentTotalEquityIdr * (DecimalValue("1.0") - globalCashReservePercent)
        val maxPos = calculateMaxPositions(deployable)
        val perPos = if (maxPos > 0) deployable.divide(DecimalValue.fromInt(maxPos)) else DecimalValue.Zero
        
        val amount = if (perPos >= MIN_ORDER_INDODAX_IDR) perPos else DecimalValue.Zero
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

    fun calculateMaxPositions(totalFreeIdr: DecimalValue): Int {
        if (totalFreeIdr < MIN_ORDER_INDODAX_IDR) return 0
        return (totalFreeIdr.toScaledLong() / MIN_ORDER_INDODAX_IDR.toScaledLong()).toInt()
    }

    fun updateEquity(totalEquityIdr: DecimalValue, freeIdr: DecimalValue, deployedA: DecimalValue, deployedB: DecimalValue) {
        this.currentTotalEquityIdr = totalEquityIdr
        this.deployedAIdr = deployedA
        this.deployedBIdr = deployedB
        
        val tradeableTotal = totalEquityIdr * (DecimalValue("1.0") - globalCashReservePercent)
        
        val targetA = tradeableTotal * bucketAPercent
        val targetB = tradeableTotal * bucketBPercent * bucketBSpendablePercent
        
        availableAIdr = if (targetA > deployedA) targetA - deployedA else DecimalValue.Zero
        availableBIdr = if (targetB > deployedB) targetB - deployedB else DecimalValue.Zero
        
        // Clamp to actual free balance
        val totalUnmet = availableAIdr + availableBIdr
        if (totalUnmet > freeIdr && totalUnmet > DecimalValue.Zero) {
            val ratio = freeIdr.toDouble() / totalUnmet.toDouble()
            availableAIdr *= ratio
            availableBIdr *= ratio
        }
    }

    fun reset() {
        isMicroAccount = false
        microModeMaxPositions = 0
        currentTotalEquityIdr = totalCapitalIdr
        rebalanceCount = 0
        availableAIdr = totalCapitalIdr * (DecimalValue("1.0") - globalCashReservePercent) * bucketAPercent
        availableBIdr = totalCapitalIdr * (DecimalValue("1.0") - globalCashReservePercent) * bucketBPercent * bucketBSpendablePercent
        deployedAIdr = DecimalValue.Zero
        deployedBIdr = DecimalValue.Zero
    }

    fun allocate(
        isAnomalyCoin: Boolean,
        requestedAmountIdr: DecimalValue,
        totalFreeIdr: DecimalValue = availableAIdr + availableBIdr,
        currentPositionCount: Int = 0,
    ): AllocationResult {
        if (currentPositionCount >= calculateMaxPositions(totalFreeIdr)) {
            val bucket = if (isAnomalyCoin) availableAIdr else availableBIdr
            return AllocationResult(
                allocatedIdr = DecimalValue.Zero,
                bucketType = if (isAnomalyCoin) "AGGRESSIVE" else "STABLE",
                availableInBucket = bucket,
                currentAvailable = bucket,
                requiresRebalance = requiresRebalance(),
                rebalanceMessage = "Position limit reached for current free capital",
            )
        }
        return if (isAnomalyCoin) allocateA(requestedAmountIdr) else allocateB(requestedAmountIdr)
    }

    fun updateFreeCapital(
        freeIdr: DecimalValue,
        totalEquityIdr: DecimalValue,
        stableHoldingsIdr: DecimalValue,
        aggressiveHoldingsIdr: DecimalValue,
    ) {
        updateEquity(
            totalEquityIdr = totalEquityIdr,
            freeIdr = freeIdr,
            deployedA = aggressiveHoldingsIdr,
            deployedB = stableHoldingsIdr,
        )
    }

    fun depositProfit(profitIdr: DecimalValue, wasAggressiveTrade: Boolean) {
        currentTotalEquityIdr += profitIdr
        if (wasAggressiveTrade) {
            availableAIdr = (availableAIdr + profitIdr).absoluteValue()
            deployedAIdr = (deployedAIdr - profitIdr).absoluteValue()
        } else {
            availableBIdr = (availableBIdr + profitIdr).absoluteValue()
            deployedBIdr = (deployedBIdr - profitIdr).absoluteValue()
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
        totalFreeIdr: DecimalValue,
        currentPositionCount: Int,
        isHighPumpSignal: Boolean,
    ): AllocationResult {
        val maxPositions = calculateMaxPositions(totalFreeIdr)
        if (maxPositions <= 0 || currentPositionCount >= maxPositions) {
            val role = if (isHighPumpSignal) "CHASER" else "HOLDER"
            return AllocationResult(
                allocatedIdr = DecimalValue.Zero,
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
        stablePercent = bucketBPercent.toDouble() * 100.0,
        aggressivePercent = bucketAPercent.toDouble() * 100.0,
        mode = if (isMicroAccount) "MICRO" else "TRINITY_V7",
        driftPercent = currentDriftPercent(),
        rebalanceCount = rebalanceCount,
        requiresRebalance = requiresRebalance(),
    )

    private fun currentDriftPercent(): Double {
        val tradeableTotal = currentTotalEquityIdr * (DecimalValue("1.0") - globalCashReservePercent)
        if (tradeableTotal <= DecimalValue.Zero) return 0.0
        val targetBPct = (bucketBPercent * bucketBSpendablePercent).toDouble()
        val actualAPct = (deployedAIdr.toScaledLong().toDouble() / tradeableTotal.toScaledLong().toDouble()).coerceIn(0.0, 1.0)
        val actualBPct = (deployedBIdr.toScaledLong().toDouble() / tradeableTotal.toScaledLong().toDouble()).coerceIn(0.0, 1.0)
        return maxOf(abs(actualAPct - bucketAPercent.toDouble()), abs(actualBPct - targetBPct))
    }

    private fun requiresRebalance(): Boolean = DecimalValue.fromDouble(currentDriftPercent()) >= rebalanceDriftThreshold

    private fun buildRebalanceMessage(): String =
        if (requiresRebalance()) "Capital bucket drift exceeded threshold" else ""
}
