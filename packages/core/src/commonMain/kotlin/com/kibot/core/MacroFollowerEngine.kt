package com.kibot.core

import com.kibot.shared.models.BucketType
import com.kibot.shared.models.OrderType

class MacroFollowerEngine(
    private val config: DualEngineConfig = DualEngineConfig(),
) : TradingEngine {
    override val engineId: String = "macro_follower"

    override fun evaluateSignal(udpSignal: KinanceSignal): EngineSignalDecision? {
        if (!isMacroLeadLagSignal(udpSignal)) return null
        if (!isCorrelatedPair(udpSignal.pairId.value)) return null
        if (udpSignal.leadMomentumScore < 0.60) return null

        val context = PairSelectionContext(
            urgentEntryMode = udpSignal.leadMomentumScore >= 0.80,
            leadLagEnabled = true,
            leadPairId = udpSignal.leadPairId,
            leadMomentumScore = udpSignal.leadMomentumScore,
            maxSpreadPct = 2.5,
            bypassSpreadCheck = false,
            bypassVetoService = false,
            bypassRankingFloor = false,
            engineId = engineId,
        )

        return EngineSignalDecision(
            engineId = engineId,
            pairId = udpSignal.pairId,
            shouldEnter = true,
            reason = "macro_lead_lag_${udpSignal.msgType.lowercase()}",
            selectionContext = context,
            forcedOrderType = OrderType.LIMIT,
            bucketType = BucketType.STABLE,
            trailingDistancePct = config.macroTrailingDistancePct,
            maxHoldSeconds = null,
        )
    }

    private fun isMacroLeadLagSignal(signal: KinanceSignal): Boolean {
        val msg = signal.msgType.uppercase()
        if (msg !in setOf("DETECTOR_HIT", "VETO_APPROVED", "INSTANT_BUY_ANOMALY")) return false
        if (!signal.trend.equals("UP", ignoreCase = true) && !signal.trend.equals("GRADUAL_UP", ignoreCase = true)) return false
        return true
    }

    private fun isCorrelatedPair(pair: String): Boolean {
        return pair.lowercase() in config.macroCorrelatedPairs
    }
}

