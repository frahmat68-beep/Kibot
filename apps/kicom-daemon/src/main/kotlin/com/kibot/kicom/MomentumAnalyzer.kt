package com.kibot.kicom

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

data class PriceTick(val price: Double, val timestamp: Long)

class MomentumAnalyzer(
    private val thresholdPct: Double = 0.5,
    private val windowSeconds: Long = 60
) {
    private val history = ConcurrentHashMap<String, MutableList<PriceTick>>()
    private val _momentumFlow = MutableSharedFlow<PairMomentum>(extraBufferCapacity = 50)
    val momentumFlow = _momentumFlow.asSharedFlow()

    data class PairMomentum(
        val symbol: String,
        val priceChangePct: Double,
        val convictionScore: Double
    )

    suspend fun processTick(data: WsTickerData) {
        val symbol = data.i
        val price = data.a ?: data.k ?: return
        val now = System.currentTimeMillis()

        val ticks = history.getOrPut(symbol) { mutableListOf() }
        ticks.add(PriceTick(price, now))

        // Cleanup old ticks
        ticks.removeIf { now - it.timestamp > windowSeconds * 1000 }

        if (ticks.size < 2) return

        val firstTick = ticks.first()
        val changePct = ((price - firstTick.price) / firstTick.price) * 100.0

        if (changePct >= thresholdPct) {
            // Conviction score: 0.5% -> 0.1, 5% -> 1.0 (capped)
            val conviction = (changePct / 5.0).coerceIn(0.1, 1.0)
            _momentumFlow.emit(PairMomentum(symbol, changePct, conviction))
            
            // Clear history for this pair after emission to avoid double trigger for same move
            // unless we want continuous signals? Usually better to clear or put on cooldown.
            ticks.clear()
            ticks.add(PriceTick(price, now))
        }
    }
}
