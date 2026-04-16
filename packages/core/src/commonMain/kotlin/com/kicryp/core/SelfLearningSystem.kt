package com.kicryp.core

/**
 * SelfLearningSystem - AI-powered learning from trade outcomes
 */
class SelfLearningSystem {
    
    data class TradeOutcome(
        val pairId: String,
        val entryPrice: Double,
        val exitPrice: Double,
        val profitPercent: Double,
        val fee: Double,
        val pattern: ChartPatternRecognizer.PatternType,
        val btcChange1h: Double,
        val ethChange1h: Double,
        val holdingMinutes: Int,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    data class Lesson(
        val category: String,
        val finding: String,
        val confidence: Double,
        val impact: Double,
    )
    
    private val tradeHistory = mutableListOf<TradeOutcome>()
    private val lessons = mutableListOf<Lesson>()
    private val thresholds = mutableMapOf<String, Double>()
    
    init {
        thresholds["MIN_PROFIT_TARGET"] = 1.2
        thresholds["MAX_HOLDING_MINUTES"] = 90.0
        thresholds["BTC_CRASH_THRESHOLD"] = -2.0
        thresholds["CORRELATION_BOOST_THRESHOLD"] = 3.0
    }
    
    fun recordTrade(outcome: TradeOutcome) {
        tradeHistory.add(outcome)
        analyzeOutcome(outcome)
    }
    
    fun analyzeOutcome(outcome: TradeOutcome): List<Lesson> {
        val newLessons = mutableListOf<Lesson>()
        
        if (outcome.pattern != ChartPatternRecognizer.PatternType.UNKNOWN) {
            val patternLessons = analyzePatternEffectiveness(outcome)
            newLessons.addAll(patternLessons)
        }
        
        val correlationLessons = analyzeCorrelationImpact(outcome)
        newLessons.addAll(correlationLessons)
        
        val timingLessons = analyzeTimingEfficiency(outcome)
        newLessons.addAll(timingLessons)
        
        val feeLessons = analyzeFeeImpact(outcome)
        newLessons.addAll(feeLessons)
        
        lessons.addAll(newLessons)
        return newLessons
    }
    
    private fun analyzePatternEffectiveness(outcome: TradeOutcome): List<Lesson> {
        val results = mutableListOf<Lesson>()
        val recentPatternTrades = tradeHistory.filter {
            it.pattern == outcome.pattern && 
            (System.currentTimeMillis() - it.timestamp) < 7 * 24 * 3600 * 1000
        }
        
        if (recentPatternTrades.size >= 3) {
            val winRate = recentPatternTrades.count { it.profitPercent > 0 }.toDouble() / recentPatternTrades.size
            
            if (winRate > 0.7) {
                results.add(Lesson(
                    category = "PATTERN",
                    finding = "${outcome.pattern} is highly effective (${(winRate * 100).toInt()}% win rate)",
                    confidence = 0.8,
                    impact = 0.15
                ))
            } else if (winRate < 0.4) {
                results.add(Lesson(
                    category = "PATTERN",
                    finding = "${outcome.pattern} should be avoided (low win rate: ${(winRate * 100).toInt()}%)",
                    confidence = 0.7,
                    impact = -0.1
                ))
            }
        }
        
        return results
    }
    
    private fun analyzeCorrelationImpact(outcome: TradeOutcome): List<Lesson> {
        val results = mutableListOf<Lesson>()
        
        val btcDownTrades = tradeHistory.filter { it.btcChange1h < -1.0 }
        val btcUpTrades = tradeHistory.filter { it.btcChange1h > 2.0 }
        
        if (btcDownTrades.size > 5) {
            val downWinRate = btcDownTrades.count { it.profitPercent > 0 }.toDouble() / btcDownTrades.size
            if (downWinRate < 0.5) {
                results.add(Lesson(
                    category = "CORRELATION",
                    finding = "Trading when BTC down 1%+ results in low win rate (${(downWinRate * 100).toInt()}%)",
                    confidence = 0.75,
                    impact = -0.2
                ))
            }
        }
        
        return results
    }
    
    private fun analyzeTimingEfficiency(outcome: TradeOutcome): List<Lesson> {
        val results = mutableListOf<Lesson>()
        
        val quickTrades = tradeHistory.filter { it.holdingMinutes <= 30 }
        val slowTrades = tradeHistory.filter { it.holdingMinutes > 60 }
        
        if (quickTrades.size > 5 && slowTrades.size > 5) {
            val quickAvgProfit = quickTrades.map { it.profitPercent }.average()
            val slowAvgProfit = slowTrades.map { it.profitPercent }.average()
            
            if (quickAvgProfit > slowAvgProfit * 1.2) {
                results.add(Lesson(
                    category = "TIMING",
                    finding = "Quick exits (<=30min) outperform slow exits (${String.format("%.2f", quickAvgProfit)}% vs ${String.format("%.2f", slowAvgProfit)}%)",
                    confidence = 0.7,
                    impact = 0.1
                ))
            }
        }
        
        return results
    }
    
    private fun analyzeFeeImpact(outcome: TradeOutcome): List<Lesson> {
        val results = mutableListOf<Lesson>()
        
        if (outcome.fee > outcome.profitPercent * 0.5) {
            results.add(Lesson(
                category = "FEE",
                finding = "Fee was significant portion of profit (${String.format("%.2f", outcome.fee)}% of profit)",
                confidence = 0.8,
                impact = -0.05
            ))
        }
        
        return results
    }
    
    fun applyLessonsToThresholds() {
        val lastTradeTime = tradeHistory.lastOrNull()?.timestamp ?: return
        val recentLessons = lessons.filter {
            (System.currentTimeMillis() - lastTradeTime) < 30 * 24 * 3600 * 1000
        }
        
        val patternLessons = recentLessons.filter { it.category == "PATTERN" }
        var profitAdjustment = 0.0
        patternLessons.forEach { lesson ->
            if (lesson.impact > 0) {
                profitAdjustment += lesson.confidence * 0.1
            } else {
                profitAdjustment -= lesson.confidence * 0.05
            }
        }
        
        val currentProfit = thresholds["MIN_PROFIT_TARGET"] ?: 1.2
        thresholds["MIN_PROFIT_TARGET"] = (currentProfit + profitAdjustment).coerceIn(0.5, 3.0)
        
        val timingLessons = recentLessons.filter { it.category == "TIMING" }
        var holdingAdjustment = 0.0
        timingLessons.forEach { lesson ->
            if ("Quick exits" in lesson.finding) {
                holdingAdjustment -= lesson.confidence * 20.0
            }
        }
        
        val currentHolding = thresholds["MAX_HOLDING_MINUTES"] ?: 90.0
        thresholds["MAX_HOLDING_MINUTES"] = (currentHolding + holdingAdjustment).coerceIn(30.0, 120.0)
    }
    
    fun getThresholds(): Map<String, Double> = thresholds.toMap()
    
    fun getAllLessons(): List<Lesson> = lessons.toList()
    
    fun getTradeHistory(): List<TradeOutcome> = tradeHistory.toList()
    
    fun getHighConfidenceLessons(): List<Lesson> {
        return lessons.filter { it.confidence > 0.7 }
    }
    
    fun reset() {
        tradeHistory.clear()
        lessons.clear()
        thresholds.clear()
        
        thresholds["MIN_PROFIT_TARGET"] = 1.2
        thresholds["MAX_HOLDING_MINUTES"] = 90.0
        thresholds["BTC_CRASH_THRESHOLD"] = -2.0
        thresholds["CORRELATION_BOOST_THRESHOLD"] = 3.0
    }
}
