package com.kibot.core

import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.SyncHealth

data class EntryHealthDecision(
    val tradingAllowed: Boolean,
    val shouldSuggestTakeover: Boolean,
    val reasons: List<String>,
)

class HealthAdvisor(
    private val config: RiskConfig = RiskConfig(),
) {
    fun evaluate(snapshot: EngineHealthSnapshot): EntryHealthDecision {
        val reasons = mutableListOf<String>()

        if (snapshot.status == HealthStatus.CRITICAL) {
            reasons += "Engine health is critical."
        }
        if (snapshot.syncHealth == SyncHealth.BROKEN) {
            reasons += "Control-plane sync is broken."
        }
        if (!snapshot.exchangeReachable) {
            reasons += "Exchange is unreachable."
        }
        if (!snapshot.websocketHealthy && !snapshot.exchangeReachable) {
            reasons += "Realtime market/trade stream is degraded."
        }
        snapshot.batteryPercent?.let { batteryPercent ->
            if (snapshot.charging == false && batteryPercent <= config.blockEntriesBelowBatteryPct) {
                reasons += "Battery is too low for safe live trading."
            }
        }

        val batteryPercent = snapshot.batteryPercent
        val shouldSuggestTakeover = batteryPercent != null &&
            snapshot.charging == false &&
            batteryPercent <= config.suggestTakeoverBelowBatteryPct

        return EntryHealthDecision(
            tradingAllowed = reasons.isEmpty(),
            shouldSuggestTakeover = shouldSuggestTakeover,
            reasons = reasons,
        )
    }
}
