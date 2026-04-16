package com.kicryp.core

import com.kicryp.shared.models.MarketQuote
import kotlin.math.abs

/**
 * MultiWavePumpRider - Ride mega pumps through multiple waves
 * 
 * Philosophy from user:
 * "koinnya udah naik lewat dari 100% dan dia selalu naik turun konstan 
 *  dan ujungnya peak di 250%"
 * 
 * Strategy:
 * - Detect multi-wave pump pattern (up-down-up-down with higher highs)
 * - Entry on each pullback (dip)
 * - Exit on each local peak
 * - Re-enter on next dip
 * - Eventually detect final dump and exit completely
 * 
 * Example: CTSI pumps 250%
 * Wave 1: 0% → 100% → 80% (pullback 20%)
 * Wave 2: 80% → 150% → 120% (pullback 20%)
 * Wave 3: 120% → 200% → 160% (pullback 20%)
 * Wave 4: 160% → 250% → DUMP
 * 
 * Bot should: Entry at 80%, 120%, 160% - Exit at 150%, 200%, 250%
 */
class MultiWavePumpRider(
    private val config: MultiWaveConfig = MultiWaveConfig(),
) {
    // Track pump state per pair
    private val pumpStates = mutableMapOf<String, MegaPumpState>()
    
    /**
     * Analyze if this is a multi-wave mega pump and get trading recommendation
     */
    fun analyzeMultiWavePump(quote: MarketQuote): MultiWaveSignal {
        val pairId = quote.pairId.value
        val currentPrice = quote.midPrice.toDoubleOrZero()
        val shortReturn = quote.shortTermReturnPct  // % gain from recent low
        val volumeScore = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        
        // Get or create pump state
        val state = pumpStates.getOrPut(pairId) {
            MegaPumpState(pairId = pairId)
        }
        
        // Update price history
        state.updatePrice(currentPrice, volumeScore)
        
        // Is this a mega pump? (>60% gain = beyond normal pump threshold)
        val isMegaPump = shortReturn >= config.megaPumpThresholdPct
        
        if (!isMegaPump) {
            // Not mega pump yet, let LatePumpEntryStrategy handle it
            return MultiWaveSignal(
                pairId = pairId,
                signalType = MultiWaveSignalType.DELEGATE_TO_LATE_PUMP,
                analysis = "Not mega pump yet (${shortReturn.format(1)}% < ${config.megaPumpThresholdPct}%)",
            )
        }
        
        // Detect current wave phase
        val wavePhase = detectWavePhase(state, currentPrice, shortReturn)
        
        // Check if pump is still healthy
        val pumpHealth = assessPumpHealth(state, volumeScore, shortReturn)
        
        // Generate trading signal based on wave phase and health
        return generateSignal(
            state = state,
            wavePhase = wavePhase,
            pumpHealth = pumpHealth,
            currentPrice = currentPrice,
            shortReturn = shortReturn,
            volumeScore = volumeScore,
        )
    }
    
    /**
     * Detect which phase of the wave we're in
     */
    private fun detectWavePhase(
        state: MegaPumpState,
        currentPrice: Double,
        shortReturn: Double,
    ): WavePhase {
        // FIX: Guard against division by zero - if waveHigh is 0 or negative, use currentPrice
        // This prevents NaN/Infinity which can corrupt trade decisions and cause financial loss
        if (state.waveHigh <= 0.0) {
            state.waveHigh = currentPrice
        }
        
        // Calculate pullback from recent high
        val pullbackPct = if (state.waveHigh > 0) {
            ((state.waveHigh - currentPrice) / state.waveHigh) * 100.0
        } else {
            0.0
        }
        
        // Calculate recovery from recent low
        val recoveryPct = if (state.waveLow > 0 && state.waveLow < currentPrice) {
            ((currentPrice - state.waveLow) / state.waveLow) * 100.0
        } else {
            0.0
        }
        
        // Update wave high/low
        if (currentPrice > state.waveHigh) {
            state.waveHigh = currentPrice
            state.waveLow = currentPrice * 0.95  // Reset low with some buffer
        }
        if (currentPrice < state.waveLow) {
            state.waveLow = currentPrice
        }
        
        return when {
            // Making new highs = CLIMBING
            currentPrice >= state.waveHigh * 0.98 -> WavePhase.CLIMBING
            
            // Healthy pullback zone (10-30% from high) = DIP_ENTRY_ZONE
            pullbackPct >= config.minDipPct && pullbackPct <= config.maxDipPct -> WavePhase.DIP_ENTRY_ZONE
            
            // Small pullback, wait for more = MINOR_PULLBACK
            pullbackPct > 0 && pullbackPct < config.minDipPct -> WavePhase.MINOR_PULLBACK
            
            // Deep pullback (>30%) = potential dump = DANGER_ZONE
            pullbackPct > config.maxDipPct -> WavePhase.DANGER_ZONE
            
            // Recovering from dip = RECOVERY
            recoveryPct > 5.0 -> WavePhase.RECOVERY
            
            else -> WavePhase.UNKNOWN
        }
    }
    
    /**
     * Assess overall health of the pump
     */
    private fun assessPumpHealth(
        state: MegaPumpState,
        volumeScore: Double,
        shortReturn: Double,
    ): PumpHealth {
        // Volume health (declining volume = pump dying)
        val volumeHealth = when {
            volumeScore >= 0.70 -> VolumeHealth.STRONG
            volumeScore >= 0.50 -> VolumeHealth.MODERATE
            volumeScore >= 0.35 -> VolumeHealth.WEAK
            else -> VolumeHealth.DYING
        }
        
        // Wave count health (too many waves = exhaustion)
        val waveHealth = when {
            state.waveCount <= 2 -> WaveCountHealth.FRESH
            state.waveCount <= 4 -> WaveCountHealth.MATURE
            state.waveCount <= 6 -> WaveCountHealth.EXHAUSTING
            else -> WaveCountHealth.TERMINAL
        }
        
        // Higher highs health (are we still making progress?)
        val higherHighsHealth = if (state.recentHighs.size >= 2) {
            val lastTwo = state.recentHighs.takeLast(2)
            if (lastTwo.last() > lastTwo.first()) {
                HigherHighsHealth.YES_BULLISH
            } else {
                HigherHighsHealth.NO_BEARISH
            }
        } else {
            HigherHighsHealth.UNKNOWN
        }
        
        // Combine into overall health score
        val healthScore = calculateHealthScore(volumeHealth, waveHealth, higherHighsHealth)
        
        return PumpHealth(
            volumeHealth = volumeHealth,
            waveHealth = waveHealth,
            higherHighsHealth = higherHighsHealth,
            overallScore = healthScore,
            isHealthy = healthScore >= 0.5,
            isDying = healthScore < 0.3,
        )
    }
    
    private fun calculateHealthScore(
        volume: VolumeHealth,
        wave: WaveCountHealth,
        higherHighs: HigherHighsHealth,
    ): Double {
        val volumeScore = when (volume) {
            VolumeHealth.STRONG -> 1.0
            VolumeHealth.MODERATE -> 0.7
            VolumeHealth.WEAK -> 0.4
            VolumeHealth.DYING -> 0.1
        }
        
        val waveScore = when (wave) {
            WaveCountHealth.FRESH -> 1.0
            WaveCountHealth.MATURE -> 0.7
            WaveCountHealth.EXHAUSTING -> 0.4
            WaveCountHealth.TERMINAL -> 0.1
        }
        
        val hhScore = when (higherHighs) {
            HigherHighsHealth.YES_BULLISH -> 1.0
            HigherHighsHealth.UNKNOWN -> 0.5
            HigherHighsHealth.NO_BEARISH -> 0.2
        }
        
        // Weighted average (volume is most important)
        return (volumeScore * 0.45) + (waveScore * 0.25) + (hhScore * 0.30)
    }
    
    /**
     * Generate trading signal based on analysis
     */
    private fun generateSignal(
        state: MegaPumpState,
        wavePhase: WavePhase,
        pumpHealth: PumpHealth,
        currentPrice: Double,
        shortReturn: Double,
        volumeScore: Double,
    ): MultiWaveSignal {
        // EMERGENCY EXIT: Pump is dying
        if (pumpHealth.isDying) {
            return MultiWaveSignal(
                pairId = state.pairId,
                signalType = MultiWaveSignalType.EXIT_ALL,
                reason = "PUMP_DYING",
                analysis = "Pump health critical (${(pumpHealth.overallScore * 100).toInt()}%), volume dying, exit all positions",
                urgency = 1.0,  // Maximum urgency
            )
        }
        
        // DANGER ZONE: Deep pullback, might be final dump
        if (wavePhase == WavePhase.DANGER_ZONE) {
            return MultiWaveSignal(
                pairId = state.pairId,
                signalType = MultiWaveSignalType.EXIT_PARTIAL,
                reason = "DANGER_ZONE",
                analysis = "Deep pullback detected, potential final dump - exit 50% position",
                exitPct = 0.50,
                urgency = 0.8,
            )
        }
        
        // DIP ENTRY ZONE: This is where we want to buy!
        if (wavePhase == WavePhase.DIP_ENTRY_ZONE && pumpHealth.isHealthy) {
            state.waveCount++
            state.recentHighs.add(state.waveHigh)
            
            // Calculate position size based on wave number (diminishing)
            val positionSize = calculateWavePositionSize(state.waveCount, shortReturn)
            val stopLoss = calculateWaveStopLoss(state.waveCount)
            val takeProfit = calculateWaveTakeProfit(state.waveCount)
            
            return MultiWaveSignal(
                pairId = state.pairId,
                signalType = MultiWaveSignalType.WAVE_ENTRY,
                reason = "DIP_ENTRY",
                analysis = "Wave ${state.waveCount} entry zone at ${shortReturn.format(1)}% pump, volume ${(volumeScore * 100).toInt()}%",
                entryPrice = currentPrice,
                positionSizePct = positionSize,
                stopLossPct = stopLoss,
                takeProfitPct = takeProfit,
                waveNumber = state.waveCount,
            )
        }
        
        // CLIMBING: At or near highs - potential exit zone
        if (wavePhase == WavePhase.CLIMBING) {
            // Check if we're at a significant level (every 25-30% gain)
            val gainFromLastEntry = if (state.lastEntryPrice > 0) {
                ((currentPrice - state.lastEntryPrice) / state.lastEntryPrice) * 100.0
            } else {
                0.0
            }
            
            if (gainFromLastEntry >= config.waveExitGainPct) {
                return MultiWaveSignal(
                    pairId = state.pairId,
                    signalType = MultiWaveSignalType.WAVE_EXIT,
                    reason = "WAVE_PEAK",
                    analysis = "Wave peak detected, ${gainFromLastEntry.format(1)}% gain - take profit on wave",
                    exitPct = 0.60,  // Exit 60% of wave position, keep 40% for continuation
                )
            }
            
            // Still climbing, hold
            return MultiWaveSignal(
                pairId = state.pairId,
                signalType = MultiWaveSignalType.HOLD,
                reason = "CLIMBING",
                analysis = "Still climbing to new highs, hold position",
            )
        }
        
        // MINOR PULLBACK: Wait for deeper dip
        if (wavePhase == WavePhase.MINOR_PULLBACK) {
            return MultiWaveSignal(
                pairId = state.pairId,
                signalType = MultiWaveSignalType.WAIT,
                reason = "WAIT_FOR_DIP",
                analysis = "Minor pullback, wait for ${config.minDipPct}-${config.maxDipPct}% dip for entry",
            )
        }
        
        // RECOVERY: Just recovered from dip, might be late entry opportunity
        if (wavePhase == WavePhase.RECOVERY && pumpHealth.isHealthy) {
            return MultiWaveSignal(
                pairId = state.pairId,
                signalType = MultiWaveSignalType.WAVE_ENTRY,
                reason = "RECOVERY_ENTRY",
                analysis = "Recovery from dip, late wave entry opportunity",
                entryPrice = currentPrice,
                positionSizePct = calculateWavePositionSize(state.waveCount, shortReturn) * 0.5,  // Half size for recovery entry
                stopLossPct = calculateWaveStopLoss(state.waveCount),
                takeProfitPct = calculateWaveTakeProfit(state.waveCount) * 0.7,  // Lower target
                waveNumber = state.waveCount,
            )
        }
        
        // Default: No clear signal
        return MultiWaveSignal(
            pairId = state.pairId,
            signalType = MultiWaveSignalType.NO_ACTION,
            analysis = "No clear multi-wave signal, phase: $wavePhase",
        )
    }
    
    /**
     * Calculate position size for each wave (diminishing returns)
     */
    private fun calculateWavePositionSize(waveNumber: Int, pumpPct: Double): Double {
        // Base size decreases with wave number
        val waveMultiplier = when (waveNumber) {
            1 -> 1.0      // First wave: full allocation
            2 -> 0.70     // Second wave: 70%
            3 -> 0.50     // Third wave: 50%
            4 -> 0.35     // Fourth wave: 35%
            5 -> 0.25     // Fifth wave: 25%
            else -> 0.15  // Beyond: minimal 15%
        }
        
        // Further reduce based on pump height
        val pumpMultiplier = when {
            pumpPct < 100 -> 1.0
            pumpPct < 150 -> 0.80
            pumpPct < 200 -> 0.60
            pumpPct < 300 -> 0.40
            else -> 0.25
        }
        
        return (waveMultiplier * pumpMultiplier).coerceIn(0.10, 1.0)
    }
    
    /**
     * Calculate stop loss for each wave
     */
    private fun calculateWaveStopLoss(waveNumber: Int): Double {
        // Tighter stops for later waves
        return when (waveNumber) {
            1 -> 5.0      // First wave: 5% stop
            2 -> 4.0      // Second wave: 4% stop
            3 -> 3.5      // Third wave: 3.5% stop
            4 -> 3.0      // Fourth wave: 3% stop
            else -> 2.5   // Beyond: 2.5% stop (very tight)
        }
    }
    
    /**
     * Calculate take profit for each wave
     */
    private fun calculateWaveTakeProfit(waveNumber: Int): Double {
        // Lower targets for later waves (diminishing gains)
        return when (waveNumber) {
            1 -> 25.0     // First wave: aim for 25%
            2 -> 20.0     // Second wave: aim for 20%
            3 -> 15.0     // Third wave: aim for 15%
            4 -> 10.0     // Fourth wave: aim for 10%
            else -> 8.0   // Beyond: 8% (quick scalp)
        }
    }
    
    /**
     * Record entry price for profit tracking
     */
    fun recordEntry(pairId: String, entryPrice: Double) {
        pumpStates[pairId]?.lastEntryPrice = entryPrice
    }
    
    /**
     * Clear state for a pair (after position fully closed)
     */
    fun clearState(pairId: String) {
        pumpStates.remove(pairId)
    }
    
    /**
     * Get current state for debugging/logging
     */
    fun getState(pairId: String): MegaPumpState? = pumpStates[pairId]
    
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}

// ====== Data Classes ======

data class MultiWaveConfig(
    val megaPumpThresholdPct: Double = 60.0,  // Consider mega pump if >60% (handoff from LatePumpEntry)
    val minDipPct: Double = 10.0,             // Min pullback for entry (10%)
    val maxDipPct: Double = 30.0,             // Max pullback before danger (30%)
    val waveExitGainPct: Double = 20.0,       // Exit partial when wave gains 20%+
    val maxWaves: Int = 6,                    // Max waves to ride
)

data class MegaPumpState(
    val pairId: String,
    var waveHigh: Double = 0.0,
    var waveLow: Double = Double.MAX_VALUE,
    var waveCount: Int = 0,
    var lastEntryPrice: Double = 0.0,
    val recentHighs: MutableList<Double> = mutableListOf(),
    val priceHistory: MutableList<PricePoint> = mutableListOf(),
) {
    fun updatePrice(price: Double, volume: Double) {
        priceHistory.add(PricePoint(price, volume, System.currentTimeMillis()))
        // Keep only last 100 points
        if (priceHistory.size > 100) {
            priceHistory.removeAt(0)
        }
    }
}

data class PricePoint(
    val price: Double,
    val volumeScore: Double,
    val timestamp: Long,
)

enum class WavePhase {
    CLIMBING,          // Making new highs
    MINOR_PULLBACK,    // Small dip, not enough for entry
    DIP_ENTRY_ZONE,    // Good pullback, entry opportunity
    RECOVERY,          // Recovering from dip
    DANGER_ZONE,       // Deep pullback, might be dump
    UNKNOWN,
}

enum class VolumeHealth { STRONG, MODERATE, WEAK, DYING }
enum class WaveCountHealth { FRESH, MATURE, EXHAUSTING, TERMINAL }
enum class HigherHighsHealth { YES_BULLISH, NO_BEARISH, UNKNOWN }

data class PumpHealth(
    val volumeHealth: VolumeHealth,
    val waveHealth: WaveCountHealth,
    val higherHighsHealth: HigherHighsHealth,
    val overallScore: Double,
    val isHealthy: Boolean,
    val isDying: Boolean,
)

enum class MultiWaveSignalType {
    WAVE_ENTRY,              // Enter at dip
    WAVE_EXIT,               // Exit at peak
    EXIT_PARTIAL,            // Exit some position (risk management)
    EXIT_ALL,                // Exit everything (pump dying)
    HOLD,                    // Keep current position
    WAIT,                    // Wait for better entry
    NO_ACTION,               // No clear signal
    DELEGATE_TO_LATE_PUMP,   // Not mega pump yet, use LatePumpEntry
}

data class MultiWaveSignal(
    val pairId: String,
    val signalType: MultiWaveSignalType,
    val reason: String = "",
    val analysis: String = "",
    val entryPrice: Double = 0.0,
    val positionSizePct: Double = 0.0,
    val stopLossPct: Double = 0.0,
    val takeProfitPct: Double = 0.0,
    val exitPct: Double = 0.0,           // How much to exit (for partial exits)
    val waveNumber: Int = 0,
    val urgency: Double = 0.5,           // 0-1, higher = more urgent
)
