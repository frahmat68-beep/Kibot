package com.kibot.commandcenter.data.model

import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotMode
import com.kibot.shared.models.CommandCenterHolding
import com.kibot.shared.models.CommandCenterLiveSnapshot
import com.kibot.shared.models.CommandCenterOrder
import com.kibot.shared.models.CommandCenterTimelineEntry
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.SyncHealth
import kotlinx.serialization.Serializable

@Serializable
data class CommandCenterServerStateWire(
    val effectiveState: BotEffectiveState = BotEffectiveState.STOPPED,
    val syncHealth: SyncHealth = SyncHealth.DEGRADED,
    val liveExecutionEnabled: Boolean = false,
    val operatingMode: BotMode = BotMode.GROWTH,
    val edgeConfidence: EdgeConfidence = EdgeConfidence.MEDIUM,
    val marketRegime: MarketRegime = MarketRegime.HIGH_VOLATILITY_UNCLEAR,
    val aiProviderSummary: String = "",
    val activeEngine: String = "",
    val standbyEngine: String = "",
    val topCandidate: String = "-",
    val scanUniverseCount: Int = 0,
    val leaseTerm: Long = 0L,
    val healthSummary: String = "",
    val statusMessage: String = "",
    val lastHeartbeatLabel: String = "",
    val lastUpdatedLabel: String = "",
    val totalValueIdr: String = "Rp0",
    val portfolioValueIdr: String = "Rp0",
    val freeIdrLabel: String = "Rp0",
    val pnlTodayIdr: String = "+Rp0",
    val pnlTodayPctLabel: String = "+0.0%",
    val return7dIdr: String = "+Rp0",
    val return7dPctLabel: String = "+0.0%",
    val return30dIdr: String = "+Rp0",
    val return30dPctLabel: String = "+0.0%",
    val exchangePingMs: String = "-- ms",
    val exchangePingValueMs: Long? = null,
    val referenceQuoteAssetPriceIdr: Double? = null,
    val serverUptime: String = "00h 00m",
    val releaseLabel: String = "#0",
    val targetPursuitLabel: String = "",
    val radarPairs: List<String> = emptyList(),
    val holdingsDetailed: List<CommandCenterHolding> = emptyList(),
    val recentOrders: List<CommandCenterOrder> = emptyList(),
    val liveTimeline: List<CommandCenterTimelineEntry> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
) {
    fun toLiveSnapshot(serverId: String, serverLabel: String): CommandCenterLiveSnapshot = CommandCenterLiveSnapshot(
        serverId = serverId,
        serverLabel = serverLabel,
        botId = BotId(serverId),
        effectiveState = effectiveState,
        syncHealth = syncHealth,
        liveExecutionEnabled = liveExecutionEnabled,
        operatingMode = operatingMode,
        edgeConfidence = edgeConfidence,
        marketRegime = marketRegime,
        aiProviderSummary = aiProviderSummary,
        activeEngine = activeEngine,
        standbyEngine = standbyEngine,
        topCandidate = topCandidate,
        scanUniverseCount = scanUniverseCount,
        leaseTerm = leaseTerm,
        healthSummary = healthSummary,
        statusMessage = statusMessage,
        lastHeartbeatLabel = lastHeartbeatLabel,
        lastUpdatedLabel = lastUpdatedLabel,
        totalValueIdr = totalValueIdr,
        portfolioValueIdr = portfolioValueIdr,
        freeIdrLabel = freeIdrLabel,
        pnlTodayIdr = pnlTodayIdr,
        pnlTodayPctLabel = pnlTodayPctLabel,
        return7dIdr = return7dIdr,
        return7dPctLabel = return7dPctLabel,
        return30dIdr = return30dIdr,
        return30dPctLabel = return30dPctLabel,
        exchangePingMs = exchangePingMs,
        exchangePingValueMs = exchangePingValueMs,
        referenceQuoteAssetPriceIdr = referenceQuoteAssetPriceIdr,
        serverUptime = serverUptime,
        releaseLabel = releaseLabel,
        targetPursuitLabel = targetPursuitLabel,
        radarPairs = radarPairs,
        holdingsDetailed = holdingsDetailed,
        recentOrders = recentOrders,
        liveTimeline = liveTimeline,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
