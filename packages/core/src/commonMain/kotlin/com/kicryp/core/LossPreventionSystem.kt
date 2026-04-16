package com.kicryp.core

import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.PairId
import kotlin.math.abs

/**
 * LossPreventionSystem - Aggressive loss prevention and fast learning
 * 
 * Philosophy:
 * - Cut losses FAST (don't let 1% become 3%)
 * - Learn from EVERY mistake immediately
 * - Block bad coins quickly
 * - Preserve capital above all
 */
class LossPreventionSystem(
    private val config: LossPreventionConfig = LossPreventionConfig(),
) {
    private val lossHistory = mutableMapOf<String, MutableList<LossEvent>>()
    private val temporaryBlacklist = mutableMapOf<String, BlacklistEntry>()
    
    /**
     * Evaluate if we should exit a position to prevent losses
     */
    fun shouldForceExit(
        pairId: String,
        entryPrice: Double,
        currentPrice: Double,
        ageMinutes: Double,
        unrealizedPnlPct: Double,
        quote: MarketQuote?,
    ): ForceExitDecision {
        // Fast loss cut - don't wait
        if (unrealizedPnlPct <= -config.fastExitLossPct) {
            return ForceExitDecision(
                shouldExit = true,
                reason = "FAST_LOSS_CUT",
                urgency = ExitUrgency.IMMEDIATE,
                blockMinutes = 30,  // Block for 30min after fast loss
            )
        }
        
        // Stagnant position eating capital
        if (ageMinutes >= config.stagnantPositionMinutes && 
            unrealizedPnlPct < config.minAcceptablePnlPct) {
            return ForceExitDecision(
                shouldExit = true,
                reason = "STAGNANT_CAPITAL",
                urgency = ExitUrgency.NORMAL,
                blockMinutes = 15,
            )
        }
        
        // Death spiral detection (price keeps dropping)
        if (quote != null && isInDeathSpiral(pairId, quote, unrealizedPnlPct)) {
            return ForceExitDecision(
                shouldExit = true,
                reason = "DEATH_SPIRAL",
                urgency = ExitUrgency.IMMEDIATE,
                blockMinutes = 60,  // Block for 1 hour
            )
        }
        
        // Repeat loser - this coin keeps losing
        if (isRepeatLoser(pairId)) {
            return ForceExitDecision(
                shouldExit = true,
                reason = "REPEAT_LOSER",
                urgency = ExitUrgency.IMMEDIATE,
                blockMinutes = 120,  // Block for 2 hours
            )
        }
        
        return ForceExitDecision(shouldExit = false)
    }
    
    /**
     * Record a loss for learning
     */
    fun recordLoss(event: LossEvent) {
        val history = lossHistory.getOrPut(event.pairId) { mutableListOf() }
        history.add(event)
        
        // Keep only recent history (last 24 hours)
        val cutoff = System.currentTimeMillis() - 86_400_000L
        history.removeAll { it.timestamp < cutoff }
        
        // Auto-blacklist if too many losses
        if (history.size >= config.maxLossesBeforeBlacklist) {
            val totalLoss = history.sumOf { it.lossIdr }
            if (totalLoss >= config.blacklistLossThresholdIdr) {
                blockPair(
                    pairId = event.pairId,
                    reason = "TOO_MANY_LOSSES",
                    minutes = config.repeatLoserBlockMinutes,
                )
            }
        }
    }
    
    /**
     * Check if pair is currently blacklisted
     */
    fun isBlacklisted(pairId: String): Boolean {
        val entry = temporaryBlacklist[pairId] ?: return false
        
        // Check if blacklist expired
        if (System.currentTimeMillis() > entry.expiresAt) {
            temporaryBlacklist.remove(pairId)
            return false
        }
        
        return true
    }
    
    /**
     * Get blacklist reason if blocked
     */
    fun getBlacklistReason(pairId: String): String? {
        return temporaryBlacklist[pairId]?.reason
    }
    
    /**
     * Block a pair temporarily
     */
    fun blockPair(pairId: String, reason: String, minutes: Int) {
        val expiresAt = System.currentTimeMillis() + (minutes * 60_000L)
        temporaryBlacklist[pairId] = BlacklistEntry(
            reason = reason,
            expiresAt = expiresAt,
            blockedAt = System.currentTimeMillis(),
        )
    }
    
    /**
     * Check if coin is in death spiral (keeps dropping)
     */
    private fun isInDeathSpiral(
        pairId: String,
        quote: MarketQuote,
        unrealizedPnlPct: Double,
    ): Boolean {
        // Severe negative momentum
        val severeDowntrend = quote.shortTermReturnPct <= -5.0 && 
                              quote.mediumTermReturnPct <= -3.0
        
        // Already in loss and still dropping
        val dropWhileLosing = unrealizedPnlPct < -1.0 && 
                              quote.shortTermReturnPct < -2.0
        
        // Selling pressure (ask depth > bid depth)
        val bidDepth = quote.bidDepthTop5Idr.toDoubleOrZero()
        val askDepth = quote.askDepthTop5Idr.toDoubleOrZero()
        val sellingPressure = askDepth > bidDepth * 1.5
        
        return severeDowntrend || (dropWhileLosing && sellingPressure)
    }
    
    /**
     * Check if this coin is a repeat loser
     */
    private fun isRepeatLoser(pairId: String): Boolean {
        val history = lossHistory[pairId] ?: return false
        
        // 2+ losses in last 6 hours = repeat loser
        val recentCutoff = System.currentTimeMillis() - 21_600_000L  // 6 hours
        val recentLosses = history.count { it.timestamp >= recentCutoff }
        
        return recentLosses >= 2
    }
    
    /**
     * Get loss statistics for a pair
     */
    fun getLossStats(pairId: String): LossStats? {
        val history = lossHistory[pairId] ?: return null
        if (history.isEmpty()) return null
        
        return LossStats(
            totalLosses = history.size,
            totalLossIdr = history.sumOf { it.lossIdr },
            avgLossPct = history.map { it.lossPct }.average(),
            lastLossMinutesAgo = (System.currentTimeMillis() - history.last().timestamp) / 60_000L,
        )
    }
    
    /**
     * Clear expired blacklist entries
     */
    fun cleanupExpiredBlacklists() {
        val now = System.currentTimeMillis()
        temporaryBlacklist.entries.removeIf { it.value.expiresAt <= now }
    }
}

data class LossPreventionConfig(
    val fastExitLossPct: Double = 2.0,  // Exit immediately if loss >= 2%
    val stagnantPositionMinutes: Double = 90.0,  // 1.5 hours without profit = stagnant
    val minAcceptablePnlPct: Double = 0.5,  // Minimum profit to avoid force exit
    val maxLossesBeforeBlacklist: Int = 3,  // 3 losses = blacklist
    val blacklistLossThresholdIdr: Double = 5000.0,  // Blacklist if total loss >= Rp5K
    val repeatLoserBlockMinutes: Int = 240,  // Block repeat losers for 4 hours
)

data class ForceExitDecision(
    val shouldExit: Boolean,
    val reason: String = "",
    val urgency: ExitUrgency = ExitUrgency.NORMAL,
    val blockMinutes: Int = 0,  // How long to block this pair after exit
)

enum class ExitUrgency {
    IMMEDIATE,  // Market sell NOW
    NORMAL,     // Limit sell at current price
}

data class LossEvent(
    val pairId: String,
    val timestamp: Long,
    val lossIdr: Double,
    val lossPct: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val holdMinutes: Double,
    val reason: String,
)

data class BlacklistEntry(
    val reason: String,
    val expiresAt: Long,
    val blockedAt: Long,
)

data class LossStats(
    val totalLosses: Int,
    val totalLossIdr: Double,
    val avgLossPct: Double,
    val lastLossMinutesAgo: Long,
)
