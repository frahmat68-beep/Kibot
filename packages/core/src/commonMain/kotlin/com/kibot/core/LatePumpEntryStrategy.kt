package com.kibot.core

import com.kibot.shared.models.MarketQuote
import kotlin.math.abs

/**
 * LatePumpEntryStrategy - Enter pumps that are ALREADY running
 * 
 * Philosophy from user:
 * "jika terlambat entry pikirkan cara masuk yang lebih aman, 
 *  tapi harus masuk jangan takut buat masuk"
 * 
 * AGGRESSIVE 30% BUCKET UPGRADE:
 * - Can chase pumps up to 60% (old limit: 15%)
 * - Multi-wave detection for pullback entries
 * - Scaled position sizing (higher pump = smaller size)
 * - Quick scalp targets (5-12%) with tight stops
 * 
 * Example: CTSI pumped +85% already
 * - Wait for pullback 5-20% from peak
 * - Enter with 25% position size (quarter)
 * - Stop loss: 2-3% (ultra tight)
 * - Take profit: 8-12% (quick exit)
 */
class LatePumpEntryStrategy(
    private val config: LatePumpConfig = LatePumpConfig(),
) {
    private val recentHighs = mutableMapOf<String, PumpHistory>()
    
    /**
     * Evaluate if we can safely enter a running pump
     * 
     * @param quote Market quote data
     * @param bucketType "STABLE" (70% capital, conservative) or "AGGRESSIVE" (30% capital, chase pumps)
     * 
     * AGGRESSIVE bucket can chase up to 60% pumps with scaled sizing
     * STABLE bucket conservative (max 15% pumps only, avoid noise)
     */
    fun evaluateLatePumpEntry(quote: MarketQuote, bucketType: String = "STABLE"): LatePumpEntry {
        val pairId = quote.pairId.value
        val currentPrice = quote.midPrice.toDoubleOrZero()
        val shortReturn = quote.shortTermReturnPct
        val volumeScore = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        
        // Different thresholds for STABLE vs AGGRESSIVE bucket
        val maxPumpChase = if (bucketType == "AGGRESSIVE") {
            config.aggressiveMaxPumpPct  // 60% for aggressive bucket
        } else {
            config.stableMaxPumpPct  // 15% for stable bucket
        }
        
        // Is this a running pump?
        val isPumping = shortReturn >= config.minPumpThresholdPct
        
        if (!isPumping) {
            return LatePumpEntry(
                canEnter = false,
                reason = "NOT_PUMPING",
            )
        }
        
        // STABLE bucket: avoid 1-2% noise, need real moves
        if (bucketType == "STABLE" && shortReturn < config.stableMinGainPct) {
            return LatePumpEntry(
                canEnter = false,
                reason = "NOISE_FILTER",
                analysis = "STABLE bucket ignores <${config.stableMinGainPct}% moves (noise filter)",
            )
        }
        
        // Track the pump high
        val history = recentHighs.getOrPut(pairId) { 
            PumpHistory(pairId, currentPrice, currentPrice)
        }
        
        if (currentPrice > history.peakPrice) {
            history.peakPrice = currentPrice
        }
        
        // FIX: Guard against division by zero - peakPrice could be 0 if initialized with bad data
        // This prevents NaN/Infinity which can corrupt trade decisions and cause financial loss
        if (history.peakPrice <= 0.0) {
            history.peakPrice = currentPrice
        }
        
        // Calculate pullback from peak
        val pullbackPct = ((history.peakPrice - currentPrice) / history.peakPrice) * 100.0
        
        // Dynamic pullback range based on bucket strategy
        val maxPullback = if (bucketType == "AGGRESSIVE") {
            config.aggressiveMaxPullbackPct  // 20% for aggressive
        } else {
            config.maxPullbackPct  // 15% for stable
        }
        
        // STRATEGY 1: Wait for healthy pullback
        if (pullbackPct >= config.minPullbackPct && pullbackPct <= maxPullback) {
            // Good! Price pulled back from peak
            // This is our entry zone
            
            if (volumeScore < 0.4) {
                return LatePumpEntry(
                    canEnter = false,
                    reason = "VOLUME_DYING",
                    analysis = "Pullback good but volume dying - pump might be over",
                )
            }
            
            // Scale position size based on pump height
            val positionSize = calculateSafePositionSize(shortReturn, bucketType)
            val stopLoss = calculateDynamicStopLoss(shortReturn, bucketType)
            val takeProfit = calculateDynamicTakeProfit(shortReturn, bucketType)
            
            return LatePumpEntry(
                canEnter = true,
                reason = "HEALTHY_PULLBACK",
                entryPrice = currentPrice,
                stopLossPct = stopLoss,
                takeProfitPct = takeProfit,
                positionSizePct = positionSize,
                analysis = "Pulled back ${pullbackPct.format(1)}% from peak (pump ${shortReturn.format(1)}%), pos size ${(positionSize*100).toInt()}%",
            )
        }
        
        // STRATEGY 2: Parabolic acceleration (risky but can work)
        // AGGRESSIVE bucket only - STABLE bucket avoid this
        if (bucketType == "AGGRESSIVE" && shortReturn >= 30.0 && shortReturn < maxPumpChase && volumeScore >= 0.70) {
            // Extreme pump still accelerating with strong volume
            // Enter small position, quick exit
            val positionSize = calculateSafePositionSize(shortReturn, bucketType)
            val stopLoss = calculateDynamicStopLoss(shortReturn, bucketType)
            val takeProfit = calculateDynamicTakeProfit(shortReturn, bucketType)
            
            return LatePumpEntry(
                canEnter = true,
                reason = "PARABOLIC_ACCELERATION",
                entryPrice = currentPrice,
                stopLossPct = stopLoss,
                takeProfitPct = takeProfit,
                positionSizePct = positionSize,
                analysis = "Extreme pump ${shortReturn.format(1)}%, aggressive chase with ${(positionSize*100).toInt()}% size",
            )
        }
        
        // STRATEGY 3: Mega pump handoff to MultiWavePumpRider
        // AGGRESSIVE bucket only - pumps >60% need wave riding strategy
        if (bucketType == "AGGRESSIVE" && shortReturn >= 60.0 && volumeScore >= 0.35) {
            return LatePumpEntry(
                canEnter = false,  // Don't enter here
                reason = "MEGA_PUMP_HANDOFF",
                analysis = "Pump ${shortReturn.format(1)}% is mega pump territory - use MultiWavePumpRider for wave riding",
                isMegaPump = true,  // Signal to use MultiWavePumpRider
            )
        }
        
        // STRATEGY 4: Too late, pump exhausted (different limits per bucket)
        if (shortReturn >= maxPumpChase || volumeScore < 0.3) {
            return LatePumpEntry(
                canEnter = false,
                reason = "PUMP_EXHAUSTED",
                analysis = "Pump ${shortReturn.format(1)}% exceeds ${bucketType} limit ${maxPumpChase.toInt()}% or volume dying",
            )
        }
        
        // STRATEGY 4: Still climbing but no pullback
        if (pullbackPct < config.minPullbackPct) {
            return LatePumpEntry(
                canEnter = false,
                reason = "WAIT_FOR_PULLBACK",
                analysis = "Pump ${shortReturn.format(1)}% running, wait for ${config.minPullbackPct.toInt()}-${maxPullback.toInt()}% pullback",
            )
        }
        
        // Default: Don't enter
        return LatePumpEntry(
            canEnter = false,
            reason = "NO_SAFE_ENTRY",
            analysis = "No clear safe entry pattern found",
        )
    }
    
    /**
     * Calculate safe position size based on pump height
     * Higher pump = smaller position (risk management)
     */
    private fun calculateSafePositionSize(pumpPct: Double, strategy: String): Double {
        if (strategy == "STABLE") {
            // STABLE bucket always conservative
            return 0.5  // Half size max
        }
        
        // AGGRESSIVE bucket: scale down as pump gets higher
        return when {
            pumpPct < 15.0 -> 1.0    // Full size (early entry)
            pumpPct < 30.0 -> 0.50   // Half size (moderate pump)
            pumpPct < 50.0 -> 0.35   // 1/3 size (high pump)
            else -> 0.25             // Quarter size (extreme pump)
        }
    }
    
    /**
     * Dynamic stop loss - tighter for higher pumps
     */
    private fun calculateDynamicStopLoss(pumpPct: Double, strategy: String): Double {
        if (strategy == "STABLE") {
            return config.latePumpStopLoss  // 4% for stable
        }
        
        // AGGRESSIVE bucket: ultra-tight stops for late entries
        return when {
            pumpPct < 15.0 -> 3.5   // Normal-ish stop
            pumpPct < 30.0 -> 3.0   // Tighter
            pumpPct < 50.0 -> 2.5   // Very tight
            else -> 2.0             // Ultra tight (60%+ pumps)
        }
    }
    
    /**
     * Dynamic take profit - quick scalps for late entries
     */
    private fun calculateDynamicTakeProfit(pumpPct: Double, strategy: String): Double {
        if (strategy == "STABLE") {
            return config.latePumpTakeProfit  // 10% target
        }
        
        // AGGRESSIVE bucket: quick scalp targets
        return when {
            pumpPct < 15.0 -> 12.0  // Can aim higher on early entry
            pumpPct < 30.0 -> 10.0  // Standard scalp
            pumpPct < 50.0 -> 8.0   // Quick exit
            else -> 5.0             // Very quick exit (60%+ pumps)
        }
    }
    
    /**
     * Check if pump is still valid (not dumping)
     */
    fun isPumpStillAlive(pairId: String, currentPrice: Double): Boolean {
        val history = recentHighs[pairId] ?: return false
        
        // If price dropped > 25% from peak, pump is dead
        val dropFromPeak = ((history.peakPrice - currentPrice) / history.peakPrice) * 100.0
        
        return dropFromPeak < 25.0
    }
    
    /**
     * Clear old pump history
     */
    fun clearStaleHistory() {
        // Keep only recent pumps (implementation can add timestamp tracking)
    }
    
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}

data class LatePumpConfig(
    // Pump detection thresholds
    val minPumpThresholdPct: Double = 15.0,  // Consider it a pump if > 15%
    
    // STABLE bucket limits (70% capital)
    val stableMinGainPct: Double = 2.5,      // Filter out 1-2% noise moves
    val stableMaxPumpPct: Double = 15.0,     // Max 15% pump chase for STABLE
    
    // AGGRESSIVE bucket limits (30% capital)
    val aggressiveMaxPumpPct: Double = 60.0, // Max 60% pump chase for AGGRESSIVE
    
    // Pullback detection
    val minPullbackPct: Double = 5.0,           // Wait for at least 5% pullback
    val maxPullbackPct: Double = 15.0,          // Max 15% pullback for STABLE
    val aggressiveMaxPullbackPct: Double = 20.0, // Max 20% pullback for AGGRESSIVE
    
    // Default risk parameters (overridden by dynamic functions)
    val latePumpStopLoss: Double = 4.0,       // Tight stop for late entries
    val latePumpTakeProfit: Double = 10.0,    // Don't be greedy, take 10% and run
)

data class LatePumpEntry(
    val canEnter: Boolean,
    val reason: String,
    val entryPrice: Double = 0.0,
    val stopLossPct: Double = 0.0,
    val takeProfitPct: Double = 0.0,
    val positionSizePct: Double = 1.0,  // 1.0 = full size, 0.5 = half, 0.25 = quarter
    val analysis: String = "",
    val isMegaPump: Boolean = false,    // If true, handoff to MultiWavePumpRider
)

data class PumpHistory(
    val pairId: String,
    var peakPrice: Double,
    var entryPrice: Double,
)
