package com.kicryp.core

import com.kicryp.shared.models.BucketType
import com.kicryp.shared.models.OrderType
import kotlin.math.abs

class BarbarianAnomalyEngine(
    private val config: DualEngineConfig = DualEngineConfig(),
) : TradingEngine {
    override val engineId: String = "barbarian_anomaly"

    override fun evaluateSignal(udpSignal: KinanceSignal): EngineSignalDecision? {
        if (!isAnomalySignal(udpSignal)) return null

        // HARD BLOCK: anti-stagnant
        if (abs(udpSignal.shortTermReturnPct) < config.barbarianMinPriceVelocityPct1m) return null
        if (udpSignal.tickVelocity1m < config.barbarianMinTickVelocity) return null

        val context = PairSelectionContext(
            urgentEntryMode = true,
            leadLagEnabled = false,
            maxSpreadPct = config.barbarianMaxSpreadPct,
            bypassSpreadCheck = true,
            bypassVetoService = true,
            bypassRankingFloor = true,
            engineId = engineId,
        )

        return EngineSignalDecision(
            engineId = engineId,
            pairId = udpSignal.pairId,
            shouldEnter = true,
            reason = "barbarian_anomaly_${udpSignal.msgType.lowercase()}",
            selectionContext = context,
            forcedOrderType = OrderType.MARKET,
            bucketType = BucketType.AGGRESSIVE,
            trailingDistancePct = config.barbarianTrailingDistancePct,
            maxHoldSeconds = config.barbarianMaxHoldSeconds,
        )
    }

    private fun isAnomalySignal(signal: KinanceSignal): Boolean {
        val msg = signal.msgType.uppercase()
        val volumeHit = signal.volumeAnomalyMultiplier >= config.barbarianMinVolumeAnomalyMultiplier ||
            signal.tradeActivityScore >= config.barbarianMinVolumeAnomalyMultiplier
        val velocityHit = signal.tickVelocity1m >= config.barbarianMinTickVelocity ||
            signal.tickVelocity5m >= config.barbarianMinTickVelocity
        val breakoutHit = signal.shortTermReturnPct >= config.barbarianMinPriceVelocityPct1m ||
            signal.mediumTermReturnPct >= config.barbarianMinPriceBreakoutPct5m

        if (msg == "INSTANT_BUY_ANOMALY") return volumeHit || velocityHit || breakoutHit
        if (msg == "DETECTOR_HIT") return volumeHit && (velocityHit || breakoutHit)
        return false
    }
}

