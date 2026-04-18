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
    private val leadLagRatio: Double = 0.50,
    private val localPumpRatio: Double = 0.50,
    private val globalCashReservePercent: Double = 0.20,
    private val rebalanceDriftThreshold: Double = 0.05,
) {
    private var isMicroAccount: Boolean = false
    private var currentTotalEquityIdr = totalCapitalIdr
    
    // Bucket tracking
    private var availableLeadLagIdr = totalCapitalIdr * (1 - globalCashReservePercent) * leadLagRatio
    private var availableLocalPumpIdr = totalCapitalIdr * (1 - globalCashReservePercent) * localPumpRatio
    
    private var deployedLeadLagIdr = 0.0
    private var deployedLocalPumpIdr = 0.0
    
    companion object {
        const val MICRO_ACCOUNT_THRESHOLD_IDR = 500_000.0
        const val MIN_ORDER_INDODAX_IDR = 10_000.0
        const val MAX_SINGLE_POSITION_PCT = 0.25
        const val MULTI_SLOT_TRIGGER_IDR = 20_000.0

        /**
         * Compatibility helper for strategy/deployment modules that scale concurrent
         * slots by available free cash.
         */
        fun calculateDynamicAdditionalSlots(freeCashIdr: Double): Int {
            if (freeCashIdr < MULTI_SLOT_TRIGGER_IDR) return 0
            val slotBudget = (MIN_ORDER_INDODAX_IDR * 2.0).coerceAtLeast(20_000.0)
            return floor(freeCashIdr / slotBudget)
                .toInt()
                .coerceIn(0, 6)
        }
    }

    data class AllocationResult(
        val allocatedIdr: Double,
        val bucketType: String, // "LEAD_LAG", "LOCAL_PUMP", or "MICRO"
        val availableInBucket: Double,
        val orderType: String, // "LIMIT" or "MARKET"
        val currentAvailable: Double = availableInBucket,
        val requiresRebalance: Boolean = false,
        val rebalanceMessage: String = ""
    )

    fun allocate(isLeadLag: Boolean, requestedAmountIdr: Double): AllocationResult {
        if (checkMicroMode()) return allocateMicro(isLeadLag)
        
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        val limit = if (isLeadLag) availableLeadLagIdr else availableLocalPumpIdr
        
        val amount = minOf(requestedAmountIdr, limit, maxPerCoin)
        
        if (amount >= MIN_ORDER_INDODAX_IDR) {
            if (isLeadLag) {
                availableLeadLagIdr -= amount
                deployedLeadLagIdr += amount
            } else {
                availableLocalPumpIdr -= amount
                deployedLocalPumpIdr += amount
            }
        }
        
        return AllocationResult(
            allocatedIdr = amount,
            bucketType = if (isLeadLag) "LEAD_LAG" else "LOCAL_PUMP",
            availableInBucket = if (isLeadLag) availableLeadLagIdr else availableLocalPumpIdr,
            orderType = if (isLeadLag) "LIMIT" else "MARKET",
            requiresRebalance = requiresRebalance(),
            rebalanceMessage = if (requiresRebalance()) "Bucket drift detected" else ""
        )
    }

    /**
     * Backward-compatible aliases used by existing veto flows.
     * Bucket A = lead-lag, Bucket B = local pump/anomaly.
     */
    fun allocateA(requestedAmountIdr: Double): AllocationResult = allocate(
        isLeadLag = true,
        requestedAmountIdr = requestedAmountIdr,
    )

    fun allocateB(requestedAmountIdr: Double): AllocationResult = allocate(
        isLeadLag = false,
        requestedAmountIdr = requestedAmountIdr,
    )

    private fun checkMicroMode(): Boolean {
        isMicroAccount = currentTotalEquityIdr < MICRO_ACCOUNT_THRESHOLD_IDR
        return isMicroAccount
    }

    private fun allocateMicro(isLeadLag: Boolean): AllocationResult {
        val deployable = currentTotalEquityIdr * (1 - globalCashReservePercent)
        val amount = if (deployable >= MIN_ORDER_INDODAX_IDR) minOf(deployable * 0.5, 50_000.0) else 0.0
        
        return AllocationResult(
            allocatedIdr = amount,
            bucketType = "MICRO",
            availableInBucket = deployable,
            orderType = if (isLeadLag) "LIMIT" else "MARKET"
        )
    }

    fun depositProfit(profitIdr: Double, wasLeadLag: Boolean, entryBudget: Double) {
        currentTotalEquityIdr += profitIdr
        if (wasLeadLag) {
            availableLeadLagIdr = (availableLeadLagIdr + entryBudget + (profitIdr * 0.7)).coerceAtLeast(0.0)
            deployedLeadLagIdr = (deployedLeadLagIdr - entryBudget).coerceAtLeast(0.0)
        } else {
            availableLocalPumpIdr = (availableLocalPumpIdr + entryBudget + (profitIdr * 0.7)).coerceAtLeast(0.0)
            deployedLocalPumpIdr = (deployedLocalPumpIdr - entryBudget).coerceAtLeast(0.0)
        }
    }

    /**
     * Backward-compatible signature still used by validation tests and
     * parts of mac-engine.
     */
    fun depositProfit(profitIdr: Double, wasAggressiveTrade: Boolean) {
        depositProfit(
            profitIdr = profitIdr,
            wasLeadLag = wasAggressiveTrade,
            entryBudget = 0.0,
        )
    }

    fun updateEquity(totalEquityIdr: Double, freeIdr: Double, deployedLL: Double, deployedLP: Double) {
        this.currentTotalEquityIdr = totalEquityIdr
        this.deployedLeadLagIdr = deployedLL
        this.deployedLocalPumpIdr = deployedLP
        
        val tradeableTotal = totalEquityIdr * (1 - globalCashReservePercent)
        availableLeadLagIdr = maxOf(0.0, (tradeableTotal * leadLagRatio) - deployedLL)
        availableLocalPumpIdr = maxOf(0.0, (tradeableTotal * localPumpRatio) - deployedLP)
    }

    fun rebalance() {
        val tradeableTotal = currentTotalEquityIdr * (1 - globalCashReservePercent)
        val targetLeadLagIdr = tradeableTotal * leadLagRatio
        val targetLocalPumpIdr = tradeableTotal * localPumpRatio
        availableLeadLagIdr = (targetLeadLagIdr - deployedLeadLagIdr).coerceAtLeast(0.0)
        availableLocalPumpIdr = (targetLocalPumpIdr - deployedLocalPumpIdr).coerceAtLeast(0.0)
    }

    private fun currentDriftPercent(): Double {
        val totalDeployed = deployedLeadLagIdr + deployedLocalPumpIdr
        if (totalDeployed <= 0.0) return 0.0
        return kotlin.math.abs((deployedLeadLagIdr / totalDeployed) - leadLagRatio)
    }

    private fun requiresRebalance(): Boolean = currentDriftPercent() >= rebalanceDriftThreshold

    fun getStatus() = mapOf(
        "totalEquity" to currentTotalEquityIdr,
        "leadLagAvailable" to availableLeadLagIdr,
        "localPumpAvailable" to availableLocalPumpIdr,
        "leadLagDeployed" to deployedLeadLagIdr,
        "localPumpDeployed" to deployedLocalPumpIdr,
        "drift" to currentDriftPercent()
    )
}
