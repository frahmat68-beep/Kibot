package com.kibot.core

import kotlin.math.abs
import kotlin.math.floor

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
 * Micro-Account Mode (<500K):
 * - Merges buckets into single pool
 * - Scales positions based on balance
 * - Assigns HOLDER/CHASER roles
 * 
 * Capital rebalancing when drift > 5% detected
 */
class CapitalAllocationManager(
    private val totalCapitalIdr: Double = 47_500.0,  // IDR 47.5K total
    private val stableRotationPercent: Double = 0.70,  // 70% stable
    private val aggressivePercent: Double = 0.30,     // 30% aggressive
    private val rebalanceDriftThreshold: Double = 0.05 // 5% drift threshold
) {
    
    // Micro-account mode detection
    private var isMicroAccount: Boolean = false
    private var microModeMaxPositions: Int = 0
    
    companion object {
        const val MICRO_ACCOUNT_THRESHOLD_IDR = 500_000.0
        const val MIN_ORDER_INDODAX_IDR = 20_000.0
        const val MULTI_SLOT_TRIGGER_IDR = 20_000.0
        const val DEPLOYABLE_PCT = 0.90
        const val MAX_SINGLE_POSITION_PCT = 0.25 // Max 25% of total equity per coin

        fun calculateDynamicAdditionalSlots(totalFreeIdr: Double): Int {
            if (totalFreeIdr < MULTI_SLOT_TRIGGER_IDR) return 0
            return floor(totalFreeIdr / MULTI_SLOT_TRIGGER_IDR).toInt().coerceAtLeast(1)
        }
    }
    
    // Position role for micro-mode
    enum class PositionRole {
        HOLDER,   // Patient, target 3%+
        CHASER    // Aggressive, target 1.5%, rotates on pump
    }
    
    // Track total equity for proper 70/30 calculation
    private var currentTotalEquityIdr = totalCapitalIdr
    
    // Current allocations (recalculated based on free capital within 70/30 split)
    private var currentStableCapitalIdr = totalCapitalIdr * stableRotationPercent
    private var currentAggressiveCapitalIdr = totalCapitalIdr * aggressivePercent
    
    // Original target allocations (for rebalance detection)
    private val targetStableCapitalIdr = totalCapitalIdr * stableRotationPercent
    private val targetAggressiveCapitalIdr = totalCapitalIdr * aggressivePercent
    
    // Tracking deployed capital PER BUCKET (not just total)
    private var totalDeployedStableIdr = 0.0
    private var totalDeployedAggressiveIdr = 0.0
    private var rebalanceCount = 0
    
    data class AllocationResult(
        val allocatedIdr: Double,
        val bucketType: String,  // "STABLE", "AGGRESSIVE", or "MICRO_POOL"
        val originalTarget: Double,
        val currentAvailable: Double,
        val requiresRebalance: Boolean,
        val rebalanceMessage: String? = null,
        val positionRole: PositionRole? = null  // NEW: Only set in micro mode
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
     * @param totalFreeIdr Current free balance (for micro-mode check)
     * @param currentPositionCount Current open positions
     * @return AllocationResult with allocated amount or error
     */
    fun allocate(
        isAnomalyCoin: Boolean, 
        requestedAmountIdr: Double = 0.0,
        totalFreeIdr: Double = 0.0,
        currentPositionCount: Int = 0
    ): AllocationResult {
        
        // NEW: Check micro-mode first
        if (totalFreeIdr > 0 && checkMicroMode(totalFreeIdr)) {
            return allocateMicroMode(
                totalFreeIdr = totalFreeIdr,
                currentPositionCount = currentPositionCount,
                isHighPumpSignal = isAnomalyCoin
            )
        }
        
        // EXISTING: Normal 70/30 allocation logic
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
        
        // TRINITY v6.2: Enforce 25% per-coin cap
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        
        // Use requested amount or full available bucket, but NEVER exceed 25% total cap
        val allocateAmount = if (requestedAmountIdr > 0) {
            minOf(requestedAmountIdr, currentBucket, maxPerCoin)
        } else {
            minOf(currentBucket, maxPerCoin)
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
        if (totalAvailable <= 0.0) return false
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
    
    /**
     * Recalculate deployed capital based on positions currently held
     * This properly accounts for positions that have been exited
     * 
     * @param stableHoldingsIdr Total IDR value of positions in stable bucket
     * @param aggressiveHoldingsIdr Total IDR value of positions in aggressive bucket
     */
    fun recalculateDeployed(stableHoldingsIdr: Double = 0.0, aggressiveHoldingsIdr: Double = 0.0) {
        totalDeployedStableIdr = maxOf(0.0, stableHoldingsIdr)
        totalDeployedAggressiveIdr = maxOf(0.0, aggressiveHoldingsIdr)
    }
    
    /**
     * Update available capital based on current equity and free balance
     * 
     * Proper 70/30 calculation:
     * - Available_Stable = (Total_Equity x 0.70) - Currently_Deployed_Stable
     * - Available_Aggressive = (Total_Equity x 0.30) - Currently_Deployed_Aggressive
     * 
     * @param freeIdrBalance Current free IDR balance from exchange
     * @param totalEquityIdr Current total portfolio equity
     * @param stableHoldingsIdr Total notional value of currently held stable positions
     * @param aggressiveHoldingsIdr Total notional value of currently held aggressive positions
     */
    fun updateFreeCapital(
        freeIdrBalance: Double, 
        totalEquityIdr: Double = currentTotalEquityIdr,
        stableHoldingsIdr: Double = 0.0,
        aggressiveHoldingsIdr: Double = 0.0
    ) {
        // Update total equity tracking
        currentTotalEquityIdr = totalEquityIdr
        
        // Recalculate deployed based on actual holdings NOW (this resets stale deployed tracking)
        recalculateDeployed(stableHoldingsIdr, aggressiveHoldingsIdr)
        
        // Calculate target allocations based on TOTAL EQUITY
        val targetStableCapital = totalEquityIdr * stableRotationPercent
        val targetAggressiveCapital = totalEquityIdr * aggressivePercent
        
        // Calculate unmet sleeve demand first, then clamp the combined result to actual free cash.
        val unmetStable = maxOf(0.0, targetStableCapital - totalDeployedStableIdr)
        val unmetAggressive = maxOf(0.0, targetAggressiveCapital - totalDeployedAggressiveIdr)
        val totalUnmet = unmetStable + unmetAggressive
        val actualFreeIdr = maxOf(0.0, freeIdrBalance)
        val scaling = when {
            actualFreeIdr <= 0.0 || totalUnmet <= 0.0 -> 0.0
            totalUnmet <= actualFreeIdr -> 1.0
            else -> actualFreeIdr / totalUnmet
        }
        val availableStable = unmetStable * scaling
        val availableAggressive = unmetAggressive * scaling
        
        // Set current available allocations
        currentStableCapitalIdr = availableStable
        currentAggressiveCapitalIdr = availableAggressive
    }
    
    // ========== MICRO-ACCOUNT MODE METHODS ==========
    
    /**
     * Calculate max positions based on available capital and minimum order size.
     * Used in micro-account mode. Scales with balance growth.
     */
    fun calculateMaxPositions(totalFreeIdr: Double): Int {
        val deployable = totalFreeIdr * DEPLOYABLE_PCT
        if (deployable < MIN_ORDER_INDODAX_IDR) return 0
        val multiSlotCap = calculateDynamicAdditionalSlots(deployable)
        val maxPos = maxOf(
            (deployable / MIN_ORDER_INDODAX_IDR).toInt(),
            multiSlotCap,
        )
        // Scale up naturally: more saldo = more diversification
        // No artificial cap - let it grow until 70/30 mode kicks in at 500K
        return maxPos.coerceAtLeast(0)
    }
    
    /**
     * Check if micro-account mode should be active
     */
    fun checkMicroMode(totalFreeIdr: Double): Boolean {
        val effectiveAccountSizeIdr = maxOf(totalFreeIdr, currentTotalEquityIdr)
        isMicroAccount = effectiveAccountSizeIdr < MICRO_ACCOUNT_THRESHOLD_IDR
        if (isMicroAccount) {
            microModeMaxPositions = calculateMaxPositions(totalFreeIdr)
            println("[MICRO_MODE] Activated: totalFree=$totalFreeIdr effectiveAccount=$effectiveAccountSizeIdr maxPositions=$microModeMaxPositions")
        }
        return isMicroAccount
    }
    
    /**
     * Get allocation per position in micro mode
     */
    fun getMicroModeAllocationPerPosition(totalFreeIdr: Double): Double {
        val deployable = totalFreeIdr * DEPLOYABLE_PCT
        val maxPos = calculateMaxPositions(totalFreeIdr)
        return if (maxPos > 0) deployable / maxPos else 0.0
    }
    
    /**
     * Allocate capital in micro-account mode.
     * Merges Stable + Aggressive buckets into single pool.
     * Assigns position roles: HOLDER (first) or CHASER (second+).
     */
    fun allocateMicroMode(
        totalFreeIdr: Double,
        currentPositionCount: Int,
        isHighPumpSignal: Boolean
    ): AllocationResult {
        val deployable = totalFreeIdr * DEPLOYABLE_PCT
        val maxPositions = calculateMaxPositions(totalFreeIdr)
        
        // Check if we can open another position
        if (currentPositionCount >= maxPositions) {
            println("[MICRO_MODE] Max positions reached: $currentPositionCount/$maxPositions")
            return AllocationResult(
                allocatedIdr = 0.0,
                bucketType = "MICRO_POOL",
                originalTarget = 0.0,
                currentAvailable = 0.0,
                requiresRebalance = false,
                rebalanceMessage = "max_positions_reached",
                positionRole = null
            )
        }
        
        // Check minimum order size
        // TRINITY v6.2: Enforce 25% per-coin cap even in micro-mode
        val maxPerCoin = currentTotalEquityIdr * MAX_SINGLE_POSITION_PCT
        val rawAllocationPerPos = deployable / maxPositions
        val allocationPerPos = minOf(rawAllocationPerPos, maxPerCoin)

        if (allocationPerPos < MIN_ORDER_INDODAX_IDR) {
            println("[MICRO_MODE] Insufficient capital: allocation=$allocationPerPos < min=$MIN_ORDER_INDODAX_IDR")
            return AllocationResult(
                allocatedIdr = 0.0,
                bucketType = "MICRO_POOL",
                originalTarget = 0.0,
                currentAvailable = 0.0,
                requiresRebalance = false,
                rebalanceMessage = "below_minimum_order",
                positionRole = null
            )
        }
        
        // Assign role based on position count and signal
        val role = when {
            currentPositionCount == 0 && !isHighPumpSignal -> PositionRole.HOLDER
            currentPositionCount == 0 && isHighPumpSignal -> PositionRole.CHASER
            currentPositionCount >= 1 -> PositionRole.CHASER // Second+ position always chaser
            else -> PositionRole.CHASER
        }
        
        println("[MICRO_MODE] Allocated Rp$allocationPerPos as $role (position ${currentPositionCount + 1}/$maxPositions)")
        
        return AllocationResult(
            allocatedIdr = allocationPerPos,
            bucketType = "MICRO_POOL",
            originalTarget = deployable,
            currentAvailable = deployable - (allocationPerPos * currentPositionCount),
            requiresRebalance = false,
            rebalanceMessage = null,
            positionRole = role
        )
    }
    
    /**
     * Check if CHASER position should rotate to a new pump.
     * Only rotates if math makes sense (new gain - fees > current profit).
     */
    fun shouldRotateChaser(
        currentUnrealizedProfitPct: Double,
        newCoinExpectedGainPct: Double,
        estimatedRotationCostPct: Double = 0.01 // ~1% for buy+sell fees+spread
    ): Boolean {
        val netGainAfterRotation = newCoinExpectedGainPct - estimatedRotationCostPct
        val shouldRotate = netGainAfterRotation > currentUnrealizedProfitPct
        
        println("[ROTATION_CHECK] current=${currentUnrealizedProfitPct}%, " +
            "newExpected=${newCoinExpectedGainPct}%, " +
            "cost=${estimatedRotationCostPct}%, " +
            "netGain=$netGainAfterRotation%, " +
            "shouldRotate=$shouldRotate")
        
        return shouldRotate
    }
    
    /**
     * Check minimum profit threshold before allowing exit.
     * Ensures we don't sell at a loss after fees.
     */
    fun canExitWithProfit(
        unrealizedProfitPct: Double,
        minProfitThresholdPct: Double = 0.008 // 0.8% covers fees
    ): Boolean {
        return unrealizedProfitPct >= minProfitThresholdPct
    }
}
