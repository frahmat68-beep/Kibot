package com.kibot.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * KiBotVetoSystem - KiBot adalah APPROVAL AUTHORITY untuk semua trades
 * 
 * Phase 1-3 Integration (Trinity Bot Hybrid Strategy):
 * Phase 1: PairWhitelistManager, CapitalAllocationManager, OrderExecutionStrategy
 * Phase 2: PairPerformanceTracker, DynamicStopLossManager, ForceRotateManager
 * Phase 3: ChartPatternRecognizer, BTCETHCorrelationFilter, SelfLearningSystem
 */
class KiBotVetoSystem(
    private val pairWhitelist: PairWhitelistManager = PairWhitelistManager(),
    private val capitalAllocator: CapitalAllocationManager = CapitalAllocationManager(),
    private val orderStrategy: OrderExecutionStrategy = OrderExecutionStrategy(),
    // Phase 2
    private val performanceTracker: PairPerformanceTracker = PairPerformanceTracker(),
    private val stopLossManager: DynamicStopLossManager = DynamicStopLossManager(),
    private val forceRotateManager: ForceRotateManager = ForceRotateManager(),
    // Phase 3
    private val patternRecognizer: ChartPatternRecognizer = ChartPatternRecognizer(),
    private val correlationFilter: BTCETHCorrelationFilter = BTCETHCorrelationFilter(),
    private val learningSystem: SelfLearningSystem = SelfLearningSystem(),
) {
    private val tradeApprovals = mutableMapOf<String, TradeApproval>()
    private val recentVetoes = mutableListOf<VetoRecord>()
    
    // Veto record
    data class VetoRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val pairId: String,
        val reason: String,
        val rule: Int,
    )
    
    /**
     * KiBot reports: "I want to BUY pair X"
     * KiCryp APPROVES or VETOES
     * 
     * Phase 1: Whitelist + Capital allocation
     * Phase 2: Dynamic stops + Force rotation checks
     * Phase 3: Pattern + Correlation filtering
     */
    fun evaluateBuyOrder(
        pairId: String,
        price: Double,
        quantity: Double,
        costIdr: Double,
        currentEquityIdr: Double,
        currentLossPct: Double,
        capitalAvailableIdr: Double,
        positionStrategy: PositionStrategy,
        spreadPercent: Double = 0.01,
        volumeScore: Double = 0.5,
        volatility: Double = 1.0,
        // Phase 3 params
        priceHistory: List<Double> = emptyList(),
        volumeHistory: List<Double> = emptyList(),
        btcChange1h: Double = 0.0,
        ethChange1h: Double = 0.0,
    ): BuyApproval {
        // Rule 0: PHASE 1 - Whitelist check
        if (!pairWhitelist.isPairWhitelisted(pairId)) {
            return BuyApproval(
                approved = false,
                reason = "PAIR_BLACKLISTED",
                message = "$pairId failed whitelist check",
                vetoStrength = VetoStrength.HARD,
            )
        }
        
        // Rule 1: Daily loss limit
        // FIX: Changed < to <= because -15% IS less than -10% but should ALSO trigger veto
        // Original bug: -15% < -10.0 is TRUE (allowed trading at 15% loss!), -10% < -10.0 is FALSE
        // Correct: -15% <= -10.0 is TRUE, -10% <= -10.0 is TRUE (both vetoed as intended)
        if (currentLossPct <= -10.0) {
            return BuyApproval(
                approved = false,
                reason = "DAILY_LOSS_LIMIT",
                message = "Daily loss >= 10%, VETO all new entries",
                vetoStrength = VetoStrength.HARD,
            )
        }
        
        // Rule 2: Capital allocation check (PHASE 1)
        val isAnomalyCoin = positionStrategy == PositionStrategy.ANOMALY
        // Trinity v7.0: Anomaly maps to Bucket B, Stable to Bucket A
        val allocResult = if (isAnomalyCoin) {
            capitalAllocator.allocateB(costIdr)
        } else {
            capitalAllocator.allocateA(costIdr)
        }
        
        if (allocResult.allocatedIdr < costIdr) {
            return BuyApproval(
                approved = false,
                reason = "INSUFFICIENT_CAPITAL_ALLOCATED",
                message = "Insufficient capital in bucket",
                vetoStrength = VetoStrength.HARD,
            )
        }
        
        // Rule 3: Repeat loser check
        val pairLossCount = getPairRecentLossCount(pairId)
        if (pairLossCount >= 3) {
            return BuyApproval(
                approved = false,
                reason = "REPEAT_LOSER",
                message = "$pairId lost $pairLossCount times recently",
                vetoStrength = VetoStrength.HARD,
            )
        }
        
        // Rule 4: Risk concentration
        val concentrationRatio = costIdr / currentEquityIdr
        if (concentrationRatio > 0.15 && positionStrategy == PositionStrategy.STABLE) {
            return BuyApproval(
                approved = false,
                reason = "CONCENTRATION_LIMIT",
                message = "Would be ${(concentrationRatio * 100).toInt()}% of portfolio",
                vetoStrength = VetoStrength.SOFT,
            )
        }
        
        // PHASE 3: Correlation filtering
        val signalModifier = correlationFilter.getSignalModifier()
        if (signalModifier <= 0.0) {
            return BuyApproval(
                approved = false,
                reason = "CORRELATION_BLOCKED",
                message = correlationFilter.getMarketCondition(),
                vetoStrength = VetoStrength.HARD,
            )
        }
        
        // PHASE 3: Pattern recognition
        var patternBonus = 0.0
        if (priceHistory.isNotEmpty() && volumeHistory.isNotEmpty()) {
            val pattern = patternRecognizer.recognizePattern(priceHistory, volumeHistory)
            
            if (patternRecognizer.isPatternBlacklisted(pattern)) {
                return BuyApproval(
                    approved = false,
                    reason = "PATTERN_BLACKLISTED",
                    message = "Detected blacklisted pattern: $pattern",
                    vetoStrength = VetoStrength.SOFT,
                )
            }
            
            if (patternRecognizer.isPatternWhitelisted(pattern)) {
                patternBonus = 0.1  // 10% confidence boost
            }
        }
        
        // Rule 5: Recovery mode
        if (currentLossPct < -5.0) {
            return BuyApproval(
                approved = true,
                reason = "APPROVED_RECOVERY_MODE",
                message = "Entry approved but reduced size (recovery mode)",
                vetoStrength = VetoStrength.NONE,
                sizeAdjustmentMultiplier = (0.6 * signalModifier).toFloat(),
            )
        }
        
        // Get recommended order type
        val pumpConfidence = (if (isAnomalyCoin) 0.8 else 0.3) + patternBonus
        val orderRec = orderStrategy.recommendEntryOrderType(
            isAnomalyCoin = isAnomalyCoin,
            pumpConfidence = pumpConfidence,
            spreadPercent = spreadPercent,
            volumeScore = volumeScore
        )
        
        // Get recommended profit target
        val profitTarget = orderStrategy.recommendProfitTarget(isAnomalyCoin, volumeScore, volatility)
        
        // PHASE 2: Get dynamic stop-loss
        val stopLoss = performanceTracker.getDynamicStopLoss(pairId)
        
        // APPROVED!
        return BuyApproval(
            approved = true,
            reason = "APPROVED",
            message = buildString {
                append("Buy $pairId approved | ")
                append(orderRec.orderType)
                append(" | Target: ${(profitTarget * 100).toInt()}%")
            },
            vetoStrength = VetoStrength.NONE,
            recommendedOrderType = orderRec.orderType.toString(),
            recommendedProfitTargetPercent = profitTarget,
            recommendedStopLossPercent = stopLoss,
            allocatedCapitalIdr = allocResult.allocatedIdr,
            sizeAdjustmentMultiplier = signalModifier.toFloat(),
        )
    }
    
    /**
     * Record a completed trade for learning
     */
    fun recordTradeCompletion(
        pairId: String,
        entryPrice: Double,
        exitPrice: Double,
        fee: Double,
        profitPercent: Double,
        pattern: ChartPatternRecognizer.PatternType,
        btcChange1h: Double,
        ethChange1h: Double,
        holdingMinutes: Int,
    ) {
        // Record for pair performance tracking
        performanceTracker.recordExit(pairId, profitPercent, holdingMinutes)
        forceRotateManager.recordPositionExit(pairId, profitPercent)
        
        // Record for learning system
        val outcome = SelfLearningSystem.TradeOutcome(
            pairId = pairId,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            profitPercent = profitPercent,
            fee = fee,
            pattern = pattern,
            btcChange1h = btcChange1h,
            ethChange1h = ethChange1h,
            holdingMinutes = holdingMinutes,
        )
        learningSystem.recordTrade(outcome)
        
        // Record to pair whitelist
        pairWhitelist.recordTrade(pairId, profitPercent > 0)
        
        // FIX: Populate tradeApprovals to enable repeat loser tracking
        // Without this, getPairRecentLossCount() always returns 0 (dead code bug)
        // This prevents blocking pairs that lose repeatedly, causing financial loss
        val tradeKey = "${pairId}-${System.currentTimeMillis()}"
        tradeApprovals[tradeKey] = TradeApproval(
            pair = pairId,
            price = exitPrice,
            won = profitPercent > 0,
        )
        
        // Prune old trade records (keep last 24 hours only)
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        tradeApprovals.entries.removeIf { it.value.timestamp < cutoffTime }
    }
    
    /**
     * Check if position should be force rotated
     */
    fun shouldForceRotate(
        pairId: String,
        currentProfitPercent: Double,
        holdingMinutes: Int
    ): Boolean {
        return performanceTracker.getShouldForceRotate(pairId, holdingMinutes, currentProfitPercent)
    }
    
    /**
     * Get force exit reason if applicable
     */
    fun getForceExitReason(
        pairId: String,
        currentProfitPercent: Double,
        holdingMinutes: Int
    ): String? {
        return if (shouldForceRotate(pairId, currentProfitPercent, holdingMinutes)) {
            forceRotateManager.getForceExitReason(pairId, currentProfitPercent, holdingMinutes)
        } else null
    }
    
    /**
     * Get dynamic stop-loss for position
     */
    fun getDynamicStopLoss(pairId: String): Double {
        return performanceTracker.getDynamicStopLoss(pairId)
    }
    
    /**
     * Update market correlation data
     */
    fun updateMarketCorrelation(btcChange1h: Double, ethChange1h: Double, btcChange24h: Double, ethChange24h: Double) {
        correlationFilter.updateMarketData(btcChange1h, ethChange1h, btcChange24h, ethChange24h)
    }
    
    /**
     * Get market signal modifier
     */
    fun getSignalModifier(): Double {
        return correlationFilter.getSignalModifier()
    }
    
    /**
     * Apply learned lessons to thresholds
     */
    fun applyLearnings() {
        learningSystem.applyLessonsToThresholds()
    }
    
    private fun getPairRecentLossCount(pairId: String): Int {
        return tradeApprovals.values
            .filter { it.pair == pairId && !it.won }
            .size
    }
    
    enum class PositionStrategy {
        STABLE,
        ANOMALY,
    }
    
    enum class VetoStrength {
        NONE,
        SOFT,
        HARD,
    }
    
    data class BuyApproval(
        val approved: Boolean,
        val reason: String,
        val message: String,
        val vetoStrength: VetoStrength = VetoStrength.NONE,
        val recommendedOrderType: String = "UNKNOWN",
        val recommendedProfitTargetPercent: Double = 1.5,
        val recommendedStopLossPercent: Double = 1.0,
        val allocatedCapitalIdr: Double = 0.0,
        val sizeAdjustmentMultiplier: Float = 1.0f,
    )
    
    data class TradeApproval(
        val pair: String,
        val price: Double,
        val won: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )
}
