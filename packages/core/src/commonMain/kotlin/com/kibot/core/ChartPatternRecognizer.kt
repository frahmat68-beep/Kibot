package com.kibot.core

/**
 * ChartPatternRecognizer - Recognize bullish/bearish chart patterns
 * 
 * Phase 3: Pattern Recognition & Correlation
 * - Whitelist patterns: DOUBLE_BOTTOM, BREAKOUT_RESISTANCE, CUP_HANDLE, INVERSE_HEAD_SHOULDERS
 * - Blacklist patterns: VERTICAL_PUMP, LOW_VOLUME_BREAKOUT
 * - Track each pattern's success rate
 * - Method: recognizePattern(priceHistory, volumeHistory) -> PatternType?
 */
class ChartPatternRecognizer {
    
    enum class PatternType {
        DOUBLE_BOTTOM,
        BREAKOUT_RESISTANCE,
        CUP_HANDLE,
        INVERSE_HEAD_SHOULDERS,
        // Blacklist patterns (avoid)
        VERTICAL_PUMP,
        LOW_VOLUME_BREAKOUT,
        // Neutral
        UNKNOWN,
    }
    
    data class PatternStats(
        val pattern: PatternType,
        var totalOccurrences: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var avgProfitPercent: Double = 0.0,
    ) {
        val winRate: Double
            get() = if (totalOccurrences == 0) 0.0 else (wins.toDouble() / totalOccurrences) * 100.0
    }
    
    private val patternStats = mutableMapOf<PatternType, PatternStats>()
    
    init {
        // Initialize pattern tracking
        PatternType.values().forEach { pattern ->
            patternStats[pattern] = PatternStats(pattern)
        }
    }
    
    /**
     * Recognize chart pattern from price and volume history
     * 
     * @param priceHistory Last 50 candles (oldest first)
     * @param volumeHistory Corresponding volume data
     * @return PatternType if recognized, or UNKNOWN
     */
    fun recognizePattern(
        priceHistory: List<Double>,
        volumeHistory: List<Double>
    ): PatternType {
        if (priceHistory.size < 10 || volumeHistory.size != priceHistory.size) {
            return PatternType.UNKNOWN
        }
        
        val prices = priceHistory
        val volumes = volumeHistory
        val current = prices.last()
        val prevPrice = prices[prices.size - 2]
        val avgPrice = prices.takeLast(20).average()
        val avgVolume = volumes.takeLast(20).average()
        
        // Check for VERTICAL_PUMP (blacklist)
        if (isVerticalPump(prices, volumes)) {
            return PatternType.VERTICAL_PUMP
        }
        
        // Check for LOW_VOLUME_BREAKOUT (blacklist)
        if (isLowVolumeBreakout(prices, volumes, avgVolume)) {
            return PatternType.LOW_VOLUME_BREAKOUT
        }
        
        // Check for DOUBLE_BOTTOM (whitelist)
        if (isDoubleBottom(prices)) {
            return PatternType.DOUBLE_BOTTOM
        }
        
        // Check for BREAKOUT_RESISTANCE (whitelist)
        if (isBreakoutResistance(prices, avgPrice)) {
            return PatternType.BREAKOUT_RESISTANCE
        }
        
        // Check for CUP_HANDLE (whitelist)
        if (isCupHandle(prices)) {
            return PatternType.CUP_HANDLE
        }
        
        // Check for INVERSE_HEAD_SHOULDERS (whitelist)
        if (isInverseHeadShoulders(prices)) {
            return PatternType.INVERSE_HEAD_SHOULDERS
        }
        
        return PatternType.UNKNOWN
    }
    
    private fun isVerticalPump(prices: List<Double>, volumes: List<Double>): Boolean {
        val last5Prices = prices.takeLast(5)
        val recentChange = ((last5Prices.last() - last5Prices.first()) / last5Prices.first()) * 100
        
        // 15%+ pump in 5 candles is suspicious
        return recentChange > 15.0
    }
    
    private fun isLowVolumeBreakout(
        prices: List<Double>,
        volumes: List<Double>,
        avgVolume: Double
    ): Boolean {
        val lastVolume = volumes.last()
        val lastPrice = prices.last()
        val prevPrice = prices[prices.size - 2]
        val priceChange = ((lastPrice - prevPrice) / prevPrice) * 100
        
        // Price move > 2% but volume < 50% of average = suspicious
        return priceChange > 2.0 && lastVolume < (avgVolume * 0.5)
    }
    
    private fun isDoubleBottom(prices: List<Double>): Boolean {
        if (prices.size < 20) return false
        
        val last20 = prices.takeLast(20)
        val min1 = last20.slice(0..5).minOrNull() ?: return false
        val min2 = last20.takeLast(5).minOrNull() ?: return false
        val valley = last20.slice(6..14).minOrNull() ?: return false
        
        // Two similar lows with valley between them
        val similar = kotlin.math.abs(min1 - min2) / min1 < 0.02
        val hasValley = (min1 < valley) && (min2 < valley)
        
        return similar && hasValley
    }
    
    private fun isBreakoutResistance(prices: List<Double>, avgPrice: Double): Boolean {
        if (prices.size < 15) return false
        
        val last5 = prices.takeLast(5)
        val resistance = prices.takeLast(20).maxOrNull() ?: return false
        val current = last5.average()
        
        // Price breaking above resistance with confirmation
        return current > resistance && current > (avgPrice * 1.02)
    }
    
    private fun isCupHandle(prices: List<Double>): Boolean {
        if (prices.size < 25) return false
        
        val last25 = prices.takeLast(25)
        val cup = last25.slice(0..14)
        val handle = last25.takeLast(10)
        
        val cupMin = cup.minOrNull() ?: return false
        val cupMax = cup.maxOrNull() ?: return false
        val handleMin = handle.minOrNull() ?: return false
        
        // Handle should be higher than cup bottom
        return handleMin > cupMin && (cupMax - cupMin) > 0
    }
    
    private fun isInverseHeadShoulders(prices: List<Double>): Boolean {
        if (prices.size < 30) return false
        
        val last30 = prices.takeLast(30)
        val leftShoulder = last30.slice(0..9).minOrNull() ?: return false
        val head = last30.slice(10..19).minOrNull() ?: return false
        val rightShoulder = last30.slice(20..29).minOrNull() ?: return false
        
        // Head lower than both shoulders
        val validPattern = (head < leftShoulder) && (head < rightShoulder)
        val shouldersClose = kotlin.math.abs(leftShoulder - rightShoulder) / leftShoulder < 0.05
        
        return validPattern && shouldersClose
    }
    
    /**
     * Record pattern trade outcome
     */
    fun recordPatternOutcome(
        pattern: PatternType,
        profitPercent: Double,
        won: Boolean
    ) {
        val stats = patternStats[pattern] ?: return
        
        stats.totalOccurrences++
        if (won) {
            stats.wins++
            stats.avgProfitPercent = ((stats.avgProfitPercent * (stats.wins - 1)) + profitPercent) / stats.wins
        } else {
            stats.losses++
        }
    }
    
    /**
     * Get pattern success rate
     */
    fun getPatternStats(pattern: PatternType): PatternStats? = patternStats[pattern]
    
    /**
     * Get whitelisted patterns only
     */
    fun getWhitelistedPatterns(): List<PatternType> {
        return listOf(
            PatternType.DOUBLE_BOTTOM,
            PatternType.BREAKOUT_RESISTANCE,
            PatternType.CUP_HANDLE,
            PatternType.INVERSE_HEAD_SHOULDERS,
        )
    }
    
    /**
     * Get blacklisted patterns (avoid)
     */
    fun getBlacklistedPatterns(): List<PatternType> {
        return listOf(
            PatternType.VERTICAL_PUMP,
            PatternType.LOW_VOLUME_BREAKOUT,
        )
    }
    
    /**
     * Check if pattern is whitelisted
     */
    fun isPatternWhitelisted(pattern: PatternType): Boolean {
        return pattern in getWhitelistedPatterns()
    }
    
    /**
     * Check if pattern is blacklisted
     */
    fun isPatternBlacklisted(pattern: PatternType): Boolean {
        return pattern in getBlacklistedPatterns()
    }
}
