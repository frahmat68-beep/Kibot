package com.kibot.core

import kotlinx.datetime.Instant
import kotlin.math.abs

/**
 * PriceGapScanner - Detect arbitrage antara Binance (Kinance) dan Indodax (KiDax)
 * 
 * Purpose:
 * - Kinance scan Binance, kirim harga ke KiBot
 * - KiDax scan Indodax, kirim harga ke KiBot
 * - KiBot detect gap = arbitrage opportunity!
 * 
 * Example:
 * Binance BTC: $40,000
 * Indodax BTC: Rp610M (= $40,667 at 15000 rate)
 * Gap: 1.67% → BUY Binance, SELL Indodax = profit!
 * 
 * Tapi kita gak bisa trading di Binance, jadi gunakan gap
 * untuk PREDICT kenaikan harga di Indodax
 */
class PriceGapScanner {
    private val pairSnapshots = mutableMapOf<String, PriceSnapshot>()
    private val arbitrageOpportunities = mutableListOf<ArbitrageOpportunity>()
    
    /**
     * Kinance sends Binance price
     */
    fun recordBinancePrice(
        pair: String,  // e.g., "BTC"
        priceUsd: Double,
        volume24hUsd: Double,
        volumeScore: Double,
        timestamp: Instant,
    ) {
        val snapshot = pairSnapshots.getOrPut(pair) {
            PriceSnapshot(pair = pair)
        }
        
        snapshot.binancePrice = priceUsd
        snapshot.binanceVolume24h = volume24hUsd
        snapshot.binanceVolumeScore = volumeScore
        snapshot.binanceLastUpdate = timestamp
    }
    
    /**
     * KiDax sends Indodax price
     */
    fun recordIndodaxPrice(
        pair: String,  // e.g., "BTC/IDR"
        priceIdr: Double,
        volume24hIdr: Double,
        volumeScore: Double,
        timestamp: Instant,
    ) {
        val snapshot = pairSnapshots.getOrPut(pair) {
            PriceSnapshot(pair = pair)
        }
        
        snapshot.indodaxPrice = priceIdr
        snapshot.indodaxVolume24h = volume24hIdr
        snapshot.indodaxVolumeScore = volumeScore
        snapshot.indodaxLastUpdate = timestamp
    }
    
    /**
     * Detect arbitrage opportunity
     */
    fun scanForArbitrage(
        exchangeRate: Double = 15000.0,  // Rp/USD
        minGapPercentile: Double = 1.0,  // Min 1% gap to care
    ): List<ArbitrageOpportunity> {
        val opportunities = mutableListOf<ArbitrageOpportunity>()
        
        pairSnapshots.values.forEach { snapshot ->
            val binancePrice = snapshot.binancePrice
            val indodaxPrice = snapshot.indodaxPrice
            
            if (binancePrice == null || indodaxPrice == null) return@forEach
            
            // Convert to same currency (IDR)
            val binancePriceIdr = binancePrice * exchangeRate
            
            // Calculate gap
            val gap = ((indodaxPrice - binancePriceIdr) / binancePriceIdr) * 100.0
            val absGap = abs(gap)
            
            if (absGap >= minGapPercentile) {
                val opportunity = ArbitrageOpportunity(
                    pair = snapshot.pair,
                    binancePriceUsd = binancePrice,
                    binancePriceIdr = binancePriceIdr,
                    indodaxPriceIdr = indodaxPrice,
                    gapPercentile = gap,
                    binanceVolume24hUsd = snapshot.binanceVolume24h ?: 0.0,
                    binanceVolumeScore = snapshot.binanceVolumeScore ?: 0.0,
                    indodaxVolume24hIdr = snapshot.indodaxVolume24h ?: 0.0,
                    indodaxVolumeScore = snapshot.indodaxVolumeScore ?: 0.0,
                    interpretation = when {
                        gap > minGapPercentile -> ArbitrageInterpretation.BULLISH_INDODAX
                        gap < -minGapPercentile -> ArbitrageInterpretation.BEARISH_INDODAX
                        else -> ArbitrageInterpretation.NEUTRAL
                    },
                    actionableReason = when {
                        gap > 3.0 -> "Strong bullish: Indodax overpriced → expect pullback or huge volume spike"
                        gap > 1.5 -> "Mild bullish: Indodax leading, momentum strong"
                        gap < -3.0 -> "Strong bearish: Indodax underpriced → huge buyers waiting, might dump"
                        gap < -1.5 -> "Mild bearish: Indodax weak, sellers dominating"
                        else -> "Neutral: Prices aligned, normal trading"
                    },
                )
                
                opportunities.add(opportunity)
            }
        }
        
        arbitrageOpportunities.clear()
        arbitrageOpportunities.addAll(opportunities)
        
        return opportunities.sortedByDescending { abs(it.gapPercentile) }
    }
    
    /**
     * Get trading signal from price gap
     */
    fun getSignalFromGap(opportunity: ArbitrageOpportunity): PriceGapSignal {
        return when (opportunity.interpretation) {
            ArbitrageInterpretation.BULLISH_INDODAX -> {
                // Indodax overpriced = expect pullback OR huge volume coming
                PriceGapSignal(
                    signal = "WATCH_CLOSELY",
                    bias = "BULLISH",
                    rationale = "Indodax leading pump, momentum strong, ${opportunity.gapPercentile.format(2)}% ahead of Binance",
                    actionForKiDax = "HIGH PRIORITY for entry, but be careful of dump",
                )
            }
            ArbitrageInterpretation.BEARISH_INDODAX -> {
                // Indodax underpriced = potential catch-up move OR weakness
                PriceGapSignal(
                    signal = "CAUTION",
                    bias = "BEARISH",
                    rationale = "Indodax lagging, ${abs(opportunity.gapPercentile).format(2)}% below Binance",
                    actionForKiDax = "Be very careful, selling pressure strong here",
                )
            }
            ArbitrageInterpretation.NEUTRAL -> {
                PriceGapSignal(
                    signal = "NORMAL",
                    bias = "NEUTRAL",
                    rationale = "Prices aligned, normal market",
                    actionForKiDax = "Use standard strategy",
                )
            }
        }
    }
    
    /**
     * All bots can know price gaps
     */
    fun getAllOpenGaps(): List<ArbitrageOpportunity> {
        return arbitrageOpportunities.toList()
    }
    
    /**
     * Top 5 opportunities
     */
    fun getTopOpportunities(count: Int = 5): List<ArbitrageOpportunity> {
        return arbitrageOpportunities
            .sortedByDescending { abs(it.gapPercentile) }
            .take(count)
    }
    
    /**
     * Is this pair showing early pump signal?
     */
    fun detectEarlyPumpSignal(pair: String): EarlyPumpSignal? {
        val snapshot = pairSnapshots[pair] ?: return null
        
        val binancePrice = snapshot.binancePrice ?: return null
        val indodaxPrice = snapshot.indodaxPrice ?: return null
        val binanceVolumeScore = snapshot.binanceVolumeScore ?: return null
        val indodaxVolumeScore = snapshot.indodaxVolumeScore ?: return null
        
        val binancePriceIdr = binancePrice * 15000.0
        val gap = ((indodaxPrice - binancePriceIdr) / binancePriceIdr) * 100.0
        
        // Early pump signal: Binance volume high, gap widening
        if (binanceVolumeScore > 0.7 && gap > 0.5) {
            return EarlyPumpSignal(
                pair = pair,
                binanceVolumeScore = binanceVolumeScore,
                gap = gap,
                interpretation = "Binance leading pump, Indodax catching up soon",
                confidence = minOf(1.0, (binanceVolumeScore * 1.2) + (gap / 100.0)),
            )
        }
        
        return null
    }
    
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}

data class PriceSnapshot(
    val pair: String,
    var binancePrice: Double? = null,
    var binanceVolume24h: Double? = null,
    var binanceVolumeScore: Double? = null,
    var binanceLastUpdate: Instant? = null,
    var indodaxPrice: Double? = null,
    var indodaxVolume24h: Double? = null,
    var indodaxVolumeScore: Double? = null,
    var indodaxLastUpdate: Instant? = null,
)

data class ArbitrageOpportunity(
    val pair: String,
    val binancePriceUsd: Double,
    val binancePriceIdr: Double,
    val indodaxPriceIdr: Double,
    val gapPercentile: Double,
    val binanceVolume24hUsd: Double,
    val binanceVolumeScore: Double,
    val indodaxVolume24hIdr: Double,
    val indodaxVolumeScore: Double,
    val interpretation: ArbitrageInterpretation,
    val actionableReason: String,
)

enum class ArbitrageInterpretation {
    BULLISH_INDODAX,    // Indodax ahead, momentum strong
    BEARISH_INDODAX,    // Indodax behind, weakness
    NEUTRAL,             // Prices aligned
}

data class PriceGapSignal(
    val signal: String,  // WATCH_CLOSELY, CAUTION, NORMAL
    val bias: String,    // BULLISH, BEARISH, NEUTRAL
    val rationale: String,
    val actionForKiDax: String,
)

data class EarlyPumpSignal(
    val pair: String,
    val binanceVolumeScore: Double,
    val gap: Double,
    val interpretation: String,
    val confidence: Double,
)
