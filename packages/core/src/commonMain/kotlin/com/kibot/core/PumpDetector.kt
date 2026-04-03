package com.kibot.core

import com.kibot.shared.models.MarketQuote
import kotlin.math.abs

/**
 * PumpDetector - Detects early pump signals for 100%+ moves
 * 
 * Philosophy:
 * - Enter BEFORE the pump (not after)
 * - Use volume explosion + price velocity as early indicators
 * - Conservative entry, aggressive exit
 */
class PumpDetector(
    private val config: PumpDetectorConfig = PumpDetectorConfig(),
) {
    /**
     * Analyze quote for pump potential
     * Returns PumpSignal with confidence score 0.0-1.0
     */
    fun detectPumpPotential(quote: MarketQuote): PumpSignal {
        val volumeExplosionScore = detectVolumeExplosion(quote)
        val priceVelocityScore = detectPriceVelocity(quote)
        val orderBookImbalanceScore = detectOrderBookImbalance(quote)
        val microstructureScore = detectMicrostructure(quote)
        
        // Weighted scoring
        val rawScore = weightedAverage(
            volumeExplosionScore to 0.35,  // Volume is king
            priceVelocityScore to 0.30,     // Price momentum
            orderBookImbalanceScore to 0.20, // Bid pressure
            microstructureScore to 0.15,     // Market quality
        )
        
        // Boost for micro-caps (they pump harder)
        val pricePenalty = when {
            quote.midPrice.toDoubleOrZero() > 10000.0 -> -0.15  // Expensive coins rarely 100x
            quote.midPrice.toDoubleOrZero() > 1000.0 -> -0.05
            quote.midPrice.toDoubleOrZero() < 100.0 -> 0.10     // Cheap = high potential
            quote.midPrice.toDoubleOrZero() < 50.0 -> 0.15      // Ultra cheap = mega pump potential
            else -> 0.0
        }
        
        val confidence = (rawScore + pricePenalty).coerceIn(0.0, 1.0)
        
        return PumpSignal(
            pairId = quote.pairId.value,
            confidence = confidence,
            volumeExplosion = volumeExplosionScore,
            priceVelocity = priceVelocityScore,
            orderBookPressure = orderBookImbalanceScore,
            entryRecommendation = deriveEntryRecommendation(confidence, quote),
            exitStrategy = deriveExitStrategy(confidence, quote),
        )
    }
    
    private fun detectVolumeExplosion(quote: MarketQuote): Double {
        val volume24h = quote.quoteVolume24h.toDoubleOrZero()
        if (volume24h <= 0.0) return 0.0
        
        // Recent volume spike (if available from recentTradeActivityScore)
        val recentActivity = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        
        // Trade count spike
        val tradeCountScore = when {
            quote.tradeCount24h >= 1000 -> 0.95
            quote.tradeCount24h >= 500 -> 0.75
            quote.tradeCount24h >= 250 -> 0.55
            quote.tradeCount24h >= 100 -> 0.35
            else -> 0.15
        }
        
        return (recentActivity * 0.6 + tradeCountScore * 0.4).coerceIn(0.0, 1.0)
    }
    
    private fun detectPriceVelocity(quote: MarketQuote): Double {
        val shortTermReturn = quote.shortTermReturnPct
        val mediumTermReturn = quote.mediumTermReturnPct
        
        // Strong upward momentum
        val momentumScore = when {
            shortTermReturn >= 15.0 -> 0.95  // Already pumping
            shortTermReturn >= 8.0 -> 0.75   // Early pump
            shortTermReturn >= 4.0 -> 0.55   // Building momentum
            shortTermReturn >= 2.0 -> 0.35   // Starting to move
            shortTermReturn >= 1.0 -> 0.20   // Slight uptick
            else -> 0.05
        }
        
        // Acceleration bonus (short > medium means accelerating)
        val accelerationBonus = if (shortTermReturn > mediumTermReturn && shortTermReturn > 2.0) {
            0.15
        } else {
            0.0
        }
        
        return (momentumScore + accelerationBonus).coerceIn(0.0, 1.0)
    }
    
    private fun detectOrderBookImbalance(quote: MarketQuote): Double {
        val bidDepth = quote.bidDepthTop5Idr.toDoubleOrZero()
        val askDepth = quote.askDepthTop5Idr.toDoubleOrZero()
        
        if (bidDepth <= 0.0 || askDepth <= 0.0) return 0.5
        
        // Bid > Ask = buying pressure
        val ratio = bidDepth / (bidDepth + askDepth)
        
        return when {
            ratio >= 0.75 -> 0.95  // Extreme buying pressure
            ratio >= 0.65 -> 0.75  // Strong buying
            ratio >= 0.55 -> 0.55  // Moderate buying
            ratio >= 0.45 -> 0.35  // Neutral
            else -> 0.15           // Selling pressure
        }
    }
    
    private fun detectMicrostructure(quote: MarketQuote): Double {
        val stability = quote.orderBookStabilityScore.coerceIn(0.0, 1.0)
        val fillQuality = quote.fillQualityScore.coerceIn(0.0, 1.0)
        val trendQuality = quote.trendQualityScore.coerceIn(0.0, 1.0)
        
        // Good microstructure = sustainable pump
        return (stability * 0.3 + fillQuality * 0.3 + trendQuality * 0.4).coerceIn(0.0, 1.0)
    }
    
    private fun deriveEntryRecommendation(confidence: Double, quote: MarketQuote): EntryRecommendation {
        val spread = quote.spreadPct
        val shortReturn = quote.shortTermReturnPct
        
        // Note: This is early detection logic (pre-pump entry)
        // LatePumpEntryStrategy handles late entries (already pumping)
        
        return when {
            confidence >= 0.85 && shortReturn < 5.0 && spread <= 2.5 -> 
                EntryRecommendation.AGGRESSIVE_BUY  // Early pump signal, go heavy
            confidence >= 0.70 && shortReturn < 8.0 && spread <= 3.0 -> 
                EntryRecommendation.MODERATE_BUY    // Good signal, normal size
            confidence >= 0.55 && shortReturn < 12.0 -> 
                EntryRecommendation.SMALL_BUY       // Decent signal, small position
            shortReturn >= 15.0 -> 
                EntryRecommendation.WAIT            // Hand off to LatePumpEntryStrategy
            else -> 
                EntryRecommendation.SKIP            // Weak signal
        }
    }
    
    private fun deriveExitStrategy(confidence: Double, quote: MarketQuote): ExitStrategy {
        val volatility = quote.realizedVolatilityPct
        
        return when {
            confidence >= 0.80 -> ExitStrategy(
                takeProfitPct = 25.0,  // Aim for 25% on high-confidence pumps
                stopLossPct = 4.0,     // Wider stop for volatile pumps
                trailingStopPct = 8.0, // Let winners run
            )
            confidence >= 0.60 -> ExitStrategy(
                takeProfitPct = 12.0,  // Moderate target
                stopLossPct = 3.0,
                trailingStopPct = 5.0,
            )
            else -> ExitStrategy(
                takeProfitPct = 6.0,   // Conservative target
                stopLossPct = 2.5,
                trailingStopPct = 3.5,
            )
        }
    }
    
    private fun weightedAverage(vararg pairs: Pair<Double, Double>): Double {
        var sum = 0.0
        var weightSum = 0.0
        for ((value, weight) in pairs) {
            sum += value * weight
            weightSum += weight
        }
        return if (weightSum > 0.0) sum / weightSum else 0.0
    }
}

data class PumpDetectorConfig(
    val minVolumeExplosionScore: Double = 0.65,
    val minPriceVelocityScore: Double = 0.55,
    val minConfidenceForEntry: Double = 0.55,
    val maxPriceForMegaPump: Double = 100.0,  // Coins < Rp100 can 100x
)

data class PumpSignal(
    val pairId: String,
    val confidence: Double,  // 0.0-1.0
    val volumeExplosion: Double,
    val priceVelocity: Double,
    val orderBookPressure: Double,
    val entryRecommendation: EntryRecommendation,
    val exitStrategy: ExitStrategy,
)

enum class EntryRecommendation {
    AGGRESSIVE_BUY,  // High confidence, enter with large position
    MODERATE_BUY,    // Good signal, normal position
    SMALL_BUY,       // Decent signal, small position
    WAIT,            // Already pumped, wait for pullback
    SKIP,            // Weak signal, skip
}

data class ExitStrategy(
    val takeProfitPct: Double,   // Target profit %
    val stopLossPct: Double,     // Maximum loss %
    val trailingStopPct: Double, // Trailing stop for winners
)
