package com.kibot.macengine.state

import com.kibot.shared.models.BotEffectiveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

enum class MacCommand {
    REQUEST_TAKEOVER,
    FORCE_SAFE_TAKEOVER,
    RELEASE_CONTROL,
    SYNC_NOW,
    START_BOT,
    STOP_BOT,
    TOGGLE_LIVE_EXECUTION,
}

@Serializable
data class MacHoldingDetail(
    val assetCode: String,
    val assetLabel: String,
    val quantityLabel: String,
    val valueIdrLabel: String,
    val entryPriceLabel: String,
    val currentPriceLabel: String,
    val pnlIdrLabel: String,
    val pnlPctLabel: String,
)

@Serializable
data class MacTimelineEntry(
    val timestampEpochMs: Long,
    val category: String,
    val message: String,
)

@Serializable
data class MacRecentOrder(
    val timestampEpochMs: Long,
    val pair: String,
    val side: String,
    val status: String,
    val orderType: String = "",
    val detail: String,
    val entryPriceLabel: String = "",
    val exitPriceLabel: String = "",
    val outcomeLabel: String = "",
    val pnlIdrLabel: String = "",
    val pnlPctLabel: String = "",
)

@Serializable
data class MacTrailingFloorDetail(
    val pair: String,
    val entryPriceLabel: String,
    val peakPriceLabel: String,
    val trailingFloorLabel: String,
    val currentBidLabel: String,
    val dropFromPeakPctLabel: String,
    val armed: Boolean,
)

@Serializable
data class MacNetWorthPoint(
    val timestampEpochMs: Long,
    val valueIdrLabel: String,
)

@Serializable
data class MacAssetAllocationDetail(
    val coin: String,
    val percentageLabel: String,
    val valueIdrLabel: String,
)

@Serializable
data class MacDashboardState(
    val isBotRunning: Boolean,
    val effectiveState: BotEffectiveState,
    val operatingMode: String,
    val edgeConfidence: String,
    val marketRegime: String,
    val topCandidate: String,
    val upstreamMarker: String = "",
    val radarPairs: List<String>,
    val scanUniverseCount: Int,
    val releaseLabel: String,
    val liveExecutionEnabled: Boolean,
    val portfolioValueIdr: String,
    val freeIdrLabel: String,
    val totalValueIdr: String,
    val referenceQuoteAssetPriceIdr: Double? = null,
    val pnlTodayIdr: String,
    val pnlTodayPctLabel: String,
    val return7dIdr: String,
    val return7dPctLabel: String,
    val return30dIdr: String,
    val return30dPctLabel: String,
    val cumulativeReturnIdr: String = "+Rp0",
    val cumulativeReturnPctLabel: String = "+0.0%",
    val targetPursuitLabel: String,
    val aiProviderSummary: String,
    val syncPathLabel: String,
    val activeEngine: String,
    val standbyEngine: String,
    val syncHealth: String,
    val leaseTerm: Long,
    val lastLeadLagSignalAgeMs: Long? = null,
    val healthSummary: String,
    val lastRejectedReason: String = "",
    val weeklyLearningSummary: String,
    val weeklyAdaptationSummary: String,
    val lastHeartbeatLabel: String,
    val lastUpdatedLabel: String,
    val statusMessage: String,
    val lastUpdatedEpochMs: Long,
    val serverLocation: String,
    val serverUptime: String,
    val heldAssets: List<String>,
    val holdingsDetailed: List<MacHoldingDetail>,
    val exchangePingMs: String,
    val exchangePingValueMs: Long? = null,
    val kidaxNodeStatus: String,
    val kibotNodeStatus: String,
    val kinanceNodeStatus: String,
    val liveTimeline: List<MacTimelineEntry>,
    val recentOrders: List<MacRecentOrder>,
    val trailingFloors: List<MacTrailingFloorDetail>,
    val netWorthHistory: List<MacNetWorthPoint> = emptyList(),
    val assetAllocationDetailed: List<MacAssetAllocationDetail> = emptyList(),
    val globalCircuitBreakerActive: Boolean = false,
    val bucketAAllocationPct: Double = 50.0,
    val bucketBAllocationPct: Double = 50.0,
    val bucketAUsageIdr: Double = 0.0,
    val bucketBUsageIdr: Double = 0.0,
    val lastLossTimestampEpochMs: Long? = null,
    val whatIfSimulation: com.kibot.shared.models.CommandCenterSimulationSummary? = null,
    val tradeHistory: kotlinx.serialization.json.JsonElement? = null,
) {
    companion object {
        fun preview(): MacDashboardState = MacDashboardState(
            isBotRunning = false,
            effectiveState = BotEffectiveState.STOPPED,
            operatingMode = "GROWTH",
            edgeConfidence = "MEDIUM",
            marketRegime = "HIGH_VOLATILITY_UNCLEAR",
            topCandidate = "-",
            upstreamMarker = "",
            radarPairs = emptyList(),
            scanUniverseCount = 0,
            releaseLabel = "#0",
            liveExecutionEnabled = false,
            portfolioValueIdr = "Rp0",
            freeIdrLabel = "Rp0",
            totalValueIdr = "Rp0",
            referenceQuoteAssetPriceIdr = null,
            pnlTodayIdr = "+Rp0",
            pnlTodayPctLabel = "+0.0%",
            return7dIdr = "+Rp0",
            return7dPctLabel = "+0.0%",
            return30dIdr = "+Rp0",
            return30dPctLabel = "+0.0%",
            cumulativeReturnIdr = "+Rp0",
            cumulativeReturnPctLabel = "+0.0%",
            targetPursuitLabel = "TRACKING",
            aiProviderSummary = "AI summary belum siap.",
            syncPathLabel = "Live Server",
            activeEngine = "Oracle Cloud Server",
            standbyEngine = "View Only",
            syncHealth = "DEGRADED",
            leaseTerm = 0,
            lastLeadLagSignalAgeMs = null,
            healthSummary = "Waiting for live server connection.",
            lastRejectedReason = "",
            weeklyLearningSummary = "Belum ada review mingguan.",
            weeklyAdaptationSummary = "Adaptasi mingguan akan tampil di sini.",
            lastHeartbeatLabel = "Never",
            lastUpdatedLabel = "Baru saja",
            statusMessage = "Server monitor is booting.",
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
            serverLocation = "Oracle Cloud (24/7)",
            serverUptime = "0m",
            heldAssets = emptyList(),
            holdingsDetailed = emptyList(),
            exchangePingMs = "--",
            exchangePingValueMs = null,
            kidaxNodeStatus = "offline",
            kibotNodeStatus = "offline",
            kinanceNodeStatus = "offline",
            liveTimeline = emptyList(),
            recentOrders = emptyList(),
            trailingFloors = emptyList(),
            netWorthHistory = emptyList(),
            assetAllocationDetailed = emptyList(),
            globalCircuitBreakerActive = false,
            bucketAAllocationPct = 50.0,
            bucketBAllocationPct = 50.0,
            bucketAUsageIdr = 0.0,
            bucketBUsageIdr = 0.0,
            lastLossTimestampEpochMs = null,
            whatIfSimulation = null,
            tradeHistory = null,
        )
    }
}

class MacStateRepository {
    private val _state = MutableStateFlow(MacDashboardState.preview())
    val state: StateFlow<MacDashboardState> = _state.asStateFlow()
    private val startedAtEpochMs = Clock.System.now().toEpochMilliseconds()
    private val recentOrderRetentionMs = 30 * 60 * 1000L
    private val liveTimelineRetentionMs = 6 * 60 * 60 * 1000L

    private fun isHealthyPingLabel(label: String?): Boolean {
        if (label.isNullOrBlank() || label == "--") return false
        val digits = label.filter { it.isDigit() }
        val pingMs = digits.toLongOrNull() ?: return false
        return pingMs in 1..5_000L
    }

    fun applyRuntimeState(next: MacDashboardState) {
        val nowEpochMs = Clock.System.now().toEpochMilliseconds()
        val uptimeMs = nowEpochMs - startedAtEpochMs
        val uptimeText = formatUptime(uptimeMs)
        val prev = _state.value
        val prevLooksLikePreview =
            prev.statusMessage == "Server monitor is booting." &&
                prev.healthSummary.contains("Waiting for live server connection", ignoreCase = true) &&
                prev.scanUniverseCount == 0 &&
                prev.portfolioValueIdr == "Rp0" &&
                prev.totalValueIdr == "Rp0"
        val nextPortfolioValue = parseMonetaryLabel(next.portfolioValueIdr)
        val prevPortfolioValue = parseMonetaryLabel(prev.portfolioValueIdr)
        val quoteFeedLikelyMissing =
            next.referenceQuoteAssetPriceIdr == null ||
                next.exchangePingValueMs == null ||
                next.exchangePingMs == "--"
        val looksLikeBootSnapshot =
            next.scanUniverseCount == 0 &&
                next.topCandidate == "-" &&
                next.heldAssets.isEmpty() &&
                next.holdingsDetailed.isEmpty() &&
                next.recentOrders.isEmpty() &&
                (
                    next.healthSummary.contains("Waiting for live server connection", ignoreCase = true) ||
                        next.statusMessage.contains("boot", ignoreCase = true) ||
                        next.statusMessage.contains("sinkron", ignoreCase = true)
                    )
        val keepPortfolioFallback =
            (
                next.portfolioValueIdr == "Rp0" &&
                    prev.portfolioValueIdr != "Rp0" &&
                    (next.statusMessage.contains("sync", ignoreCase = true) ||
                        next.statusMessage.contains("lease", ignoreCase = true) ||
                        next.statusMessage.contains("failed", ignoreCase = true))
                ) ||
                (
                    prev.holdingsDetailed.isNotEmpty() &&
                        next.holdingsDetailed.isEmpty() &&
                        next.syncHealth != "HEALTHY" &&
                        quoteFeedLikelyMissing &&
                        prevPortfolioValue > 0.0 &&
                        nextPortfolioValue in 0.0..(prevPortfolioValue * 0.92)
                    )
        val freshPreviousTimeline = prev.liveTimeline.filter { entry ->
            entry.timestampEpochMs <= 0L || nowEpochMs - entry.timestampEpochMs <= liveTimelineRetentionMs
        }
        val freshPreviousOrders = prev.recentOrders.filter { order ->
            order.timestampEpochMs <= 0L || nowEpochMs - order.timestampEpochMs <= recentOrderRetentionMs
        }
        val hasFreshRuntimeSignal =
            next.isBotRunning ||
                next.scanUniverseCount > 0 ||
                next.liveTimeline.isNotEmpty() ||
                next.recentOrders.isNotEmpty() ||
                next.heldAssets.isNotEmpty() ||
                next.holdingsDetailed.isNotEmpty() ||
                next.lastLeadLagSignalAgeMs != null ||
                next.exchangePingValueMs != null ||
                next.statusMessage.contains("running", ignoreCase = true) ||
                next.statusMessage.contains("online", ignoreCase = true) ||
                next.statusMessage.contains("healthy", ignoreCase = true) ||
                next.statusMessage.contains("sync", ignoreCase = true)
        val resolvedTimeline = when {
            next.liveTimeline.isNotEmpty() -> next.liveTimeline
            looksLikeBootSnapshot || keepPortfolioFallback -> freshPreviousTimeline
            else -> emptyList()
        }
        val resolvedRecentOrders = when {
            next.recentOrders.isNotEmpty() -> next.recentOrders
            looksLikeBootSnapshot || keepPortfolioFallback -> freshPreviousOrders
            else -> emptyList()
        }
        val preservePreviousRuntime = !prevLooksLikePreview && (looksLikeBootSnapshot || !hasFreshRuntimeSignal)
        _state.value = next.copy(
            isBotRunning = if (preservePreviousRuntime && prev.isBotRunning) prev.isBotRunning else next.isBotRunning,
            effectiveState = if (preservePreviousRuntime && prev.isBotRunning) prev.effectiveState else next.effectiveState,
            operatingMode = if (preservePreviousRuntime && prev.scanUniverseCount > 0) prev.operatingMode else next.operatingMode,
            edgeConfidence = if (preservePreviousRuntime && prev.scanUniverseCount > 0) prev.edgeConfidence else next.edgeConfidence,
            marketRegime = if (preservePreviousRuntime && prev.scanUniverseCount > 0) prev.marketRegime else next.marketRegime,
            topCandidate = if (preservePreviousRuntime && prev.topCandidate != "-") prev.topCandidate else next.topCandidate,
            radarPairs = if (preservePreviousRuntime && prev.radarPairs.isNotEmpty()) prev.radarPairs else next.radarPairs,
            scanUniverseCount = if (preservePreviousRuntime && prev.scanUniverseCount > 0) prev.scanUniverseCount else next.scanUniverseCount,
            liveExecutionEnabled = if (preservePreviousRuntime && prev.isBotRunning) prev.liveExecutionEnabled else next.liveExecutionEnabled,
            portfolioValueIdr = if (keepPortfolioFallback && prev.portfolioValueIdr != "Rp0") prev.portfolioValueIdr else next.portfolioValueIdr,
            totalValueIdr = if (keepPortfolioFallback && prev.totalValueIdr != "Rp0") prev.totalValueIdr else next.totalValueIdr,
            freeIdrLabel = if (keepPortfolioFallback && prev.freeIdrLabel != "Rp0") prev.freeIdrLabel else next.freeIdrLabel,
            syncHealth = if (preservePreviousRuntime && prev.syncHealth != "BROKEN") prev.syncHealth else next.syncHealth,
            healthSummary = if (preservePreviousRuntime && prev.healthSummary.isNotBlank()) prev.healthSummary else next.healthSummary,
            statusMessage = if (preservePreviousRuntime && prev.statusMessage.isNotBlank()) prev.statusMessage else next.statusMessage,
            exchangePingMs = if (preservePreviousRuntime && isHealthyPingLabel(prev.exchangePingMs)) prev.exchangePingMs else next.exchangePingMs,
            exchangePingValueMs = if (preservePreviousRuntime && (prev.exchangePingValueMs ?: Long.MAX_VALUE) in 1..5_000L) prev.exchangePingValueMs else next.exchangePingValueMs,
            kidaxNodeStatus = if (preservePreviousRuntime && prev.kidaxNodeStatus != "offline") prev.kidaxNodeStatus else next.kidaxNodeStatus,
            kibotNodeStatus = if (preservePreviousRuntime && prev.kibotNodeStatus != "offline") prev.kibotNodeStatus else next.kibotNodeStatus,
            kinanceNodeStatus = if (preservePreviousRuntime && prev.kinanceNodeStatus != "offline") prev.kinanceNodeStatus else next.kinanceNodeStatus,
            heldAssets = if ((looksLikeBootSnapshot || keepPortfolioFallback) && prev.heldAssets.isNotEmpty()) prev.heldAssets else next.heldAssets,
            holdingsDetailed = if ((looksLikeBootSnapshot || keepPortfolioFallback) && prev.holdingsDetailed.isNotEmpty()) prev.holdingsDetailed else next.holdingsDetailed,
            lastUpdatedEpochMs = nowEpochMs,
            serverUptime = uptimeText,
            liveTimeline = resolvedTimeline,
            recentOrders = resolvedRecentOrders,
            netWorthHistory = if ((looksLikeBootSnapshot || keepPortfolioFallback) && prev.netWorthHistory.isNotEmpty()) {
                prev.netWorthHistory
            } else {
                next.netWorthHistory
            },
            assetAllocationDetailed = if ((looksLikeBootSnapshot || keepPortfolioFallback) && prev.assetAllocationDetailed.isNotEmpty()) {
                prev.assetAllocationDetailed
            } else {
                next.assetAllocationDetailed
            },
        )
    }

    private fun formatUptime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return "%02dh %02dm".format(hours, minutes)
    }

    fun noteStatus(message: String) {
        _state.value = _state.value.copy(
            statusMessage = message,
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun noteBootstrapProgress(
        message: String,
        liveExecutionEnabled: Boolean,
    ) {
        val current = _state.value
        _state.value = current.copy(
            isBotRunning = true,
            effectiveState = if (current.effectiveState == BotEffectiveState.RUNNING) {
                current.effectiveState
            } else {
                BotEffectiveState.DEGRADED
            },
            liveExecutionEnabled = liveExecutionEnabled,
            syncHealth = if (current.syncHealth == "HEALTHY") current.syncHealth else "DEGRADED",
            healthSummary = if (current.healthSummary.contains("Waiting for live server connection", ignoreCase = true)) {
                message
            } else {
                current.healthSummary
            },
            statusMessage = message,
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun recordTimeline(
        category: String,
        message: String,
        timestampEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    ) {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isBlank()) return
        val normalizedCategory = category.trim().ifBlank { "STATUS" }
        val nextEntry = MacTimelineEntry(
            timestampEpochMs = timestampEpochMs,
            category = normalizedCategory,
            message = normalizedMessage,
        )
        val existing = _state.value.liveTimeline
        val top = existing.firstOrNull()
        val merged = if (
            top != null &&
            top.category == nextEntry.category &&
            top.message == nextEntry.message &&
            kotlin.math.abs(top.timestampEpochMs - nextEntry.timestampEpochMs) <= 20_000L
        ) {
            existing
        } else {
            listOf(nextEntry) + existing
        }
        _state.value = _state.value.copy(
            liveTimeline = merged
                .distinctBy { "${it.category}|${it.message}" }
                .take(18),
            statusMessage = _state.value.statusMessage,
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun applyAndReturn(command: MacCommand): MacDashboardState {
        val next = _state.value.copy(
            statusMessage = command.defaultStatusMessage(),
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        _state.value = next
        recordTimeline("SYNC", command.defaultStatusMessage())
        return next
    }
}

private fun parseMonetaryLabel(label: String): Double {
    val cleaned = label
        .replace("Rp", "", ignoreCase = true)
        .replace(".", "")
        .replace(",", ".")
        .replace("+", "")
        .trim()
    return cleaned.toDoubleOrNull() ?: 0.0
}

private fun MacCommand.defaultStatusMessage(): String = when (this) {
    MacCommand.REQUEST_TAKEOVER -> "Takeover request queued."
    MacCommand.FORCE_SAFE_TAKEOVER -> "Safe takeover request queued."
    MacCommand.RELEASE_CONTROL -> "Release request queued."
    MacCommand.SYNC_NOW -> "Manual refresh requested."
    MacCommand.START_BOT -> "Start request queued."
    MacCommand.STOP_BOT -> "Stop request queued."
    MacCommand.TOGGLE_LIVE_EXECUTION -> "Execution mode update queued."
}
