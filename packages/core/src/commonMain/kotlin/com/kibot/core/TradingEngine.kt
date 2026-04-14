package com.kibot.core

import com.kibot.shared.models.BucketType
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId

data class KinanceSignal(
    val pairId: PairId,
    val msgType: String,
    val trend: String,
    val confidence: Double,
    val expectedNetPct: Double,
    val shortTermReturnPct: Double,
    val mediumTermReturnPct: Double,
    val tradeActivityScore: Double,
    val volumeAnomalyMultiplier: Double = 0.0,
    val tickVelocity1m: Double = 0.0,
    val tickVelocity5m: Double = 0.0,
    val leadPairId: String? = null,
    val leadMomentumScore: Double = 0.0,
    val priceChange1mPct: Double = 0.0,
    val priceChange3mPct: Double = 0.0,
    val volumeTrend: String = "stable",
    val momentumScore: Double = 0.0,
    val rsi: Double = 50.0,
    val isBreakout: Boolean = false,
    val sentAtEpochMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
)

data class EngineSignalDecision(
    val engineId: String,
    val pairId: PairId,
    val shouldEnter: Boolean,
    val reason: String,
    val selectionContext: PairSelectionContext,
    val forcedOrderType: OrderType,
    val bucketType: BucketType,
    val trailingDistancePct: Double,
    val maxHoldSeconds: Int? = null,
)

interface TradingEngine {
    val engineId: String

    fun evaluateSignal(udpSignal: KinanceSignal): EngineSignalDecision?

    fun executeEntry(pair: String, signal: KinanceSignal): EngineSignalDecision? {
        if (!signal.pairId.value.equals(pair, ignoreCase = true)) return null
        return evaluateSignal(signal)
    }
}
