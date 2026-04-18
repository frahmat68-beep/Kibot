package com.kibot.core

import com.kibot.shared.models.*
import kotlin.math.*

class ChartAnalyzer {
    fun calculateRSI(prices: List<Double>, period: Int = 14): Double {
        if (prices.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val diff = prices[i] - prices[i - 1]
            if (diff > 0) gains += diff else losses -= diff
        }
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun calculateVWAP(candles: List<Candle>): Double {
        var tpv = 0.0
        var totalVolume = 0.0
        candles.forEach {
            val typicalPrice = (it.high + it.low + it.close) / 3.0
            tpv += typicalPrice * it.volume
            totalVolume += it.volume
        }
        return if (totalVolume > 0) tpv / totalVolume else 0.0
    }

    fun detectVolumeSpike(currentVolume: Double, avgVolume: Double): Boolean {
        return currentVolume > avgVolume * 2.5
    }
}
