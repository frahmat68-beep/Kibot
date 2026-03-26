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
    val detail: String,
)

@Serializable
data class MacDashboardState(
    val isBotRunning: Boolean,
    val effectiveState: BotEffectiveState,
    val operatingMode: String,
    val edgeConfidence: String,
    val marketRegime: String,
    val topCandidate: String,
    val radarPairs: List<String>,
    val scanUniverseCount: Int,
    val releaseLabel: String,
    val liveExecutionEnabled: Boolean,
    val portfolioValueIdr: String,
    val pnlTodayIdr: String,
    val pnlTodayPctLabel: String,
    val return7dIdr: String,
    val return7dPctLabel: String,
    val return30dIdr: String,
    val return30dPctLabel: String,
    val targetPursuitLabel: String,
    val aiProviderSummary: String,
    val syncPathLabel: String,
    val activeEngine: String,
    val standbyEngine: String,
    val syncHealth: String,
    val leaseTerm: Long,
    val healthSummary: String,
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
    val liveTimeline: List<MacTimelineEntry>,
    val recentOrders: List<MacRecentOrder>,
) {
    companion object {
        fun preview(): MacDashboardState = MacDashboardState(
            isBotRunning = false,
            effectiveState = BotEffectiveState.STOPPED,
            operatingMode = "GROWTH",
            edgeConfidence = "MEDIUM",
            marketRegime = "HIGH_VOLATILITY_UNCLEAR",
            topCandidate = "-",
            radarPairs = emptyList(),
            scanUniverseCount = 0,
            releaseLabel = "#0",
            liveExecutionEnabled = false,
            portfolioValueIdr = "Rp0",
            pnlTodayIdr = "+Rp0",
            pnlTodayPctLabel = "+0.0%",
            return7dIdr = "+Rp0",
            return7dPctLabel = "+0.0%",
            return30dIdr = "+Rp0",
            return30dPctLabel = "+0.0%",
            targetPursuitLabel = "TRACKING",
            aiProviderSummary = "AI summary belum siap.",
            syncPathLabel = "Live Server",
            activeEngine = "Oracle Cloud Server",
            standbyEngine = "View Only",
            syncHealth = "DEGRADED",
            leaseTerm = 0,
            healthSummary = "Waiting for live server connection.",
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
            liveTimeline = emptyList(),
            recentOrders = emptyList(),
        )
    }
}

class MacStateRepository {
    private val _state = MutableStateFlow(MacDashboardState.preview())
    val state: StateFlow<MacDashboardState> = _state.asStateFlow()
    private val startedAtEpochMs = Clock.System.now().toEpochMilliseconds()

    fun applyRuntimeState(next: MacDashboardState) {
        val uptimeMs = Clock.System.now().toEpochMilliseconds() - startedAtEpochMs
        val uptimeText = formatUptime(uptimeMs)
        _state.value = next.copy(
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
            serverUptime = uptimeText,
            liveTimeline = if (next.liveTimeline.isNotEmpty()) {
                next.liveTimeline
            } else {
                _state.value.liveTimeline
            },
            recentOrders = if (next.recentOrders.isNotEmpty()) {
                next.recentOrders
            } else {
                _state.value.recentOrders
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

private fun MacCommand.defaultStatusMessage(): String = when (this) {
    MacCommand.REQUEST_TAKEOVER -> "Takeover request queued."
    MacCommand.FORCE_SAFE_TAKEOVER -> "Safe takeover request queued."
    MacCommand.RELEASE_CONTROL -> "Release request queued."
    MacCommand.SYNC_NOW -> "Manual refresh requested."
    MacCommand.START_BOT -> "Start request queued."
    MacCommand.STOP_BOT -> "Stop request queued."
    MacCommand.TOGGLE_LIVE_EXECUTION -> "Execution mode update queued."
}
