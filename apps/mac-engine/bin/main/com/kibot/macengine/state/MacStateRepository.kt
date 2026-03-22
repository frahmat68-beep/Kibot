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
}

@Serializable
data class MacDashboardState(
    val isBotRunning: Boolean,
    val effectiveState: BotEffectiveState,
    val operatingMode: String,
    val edgeConfidence: String,
    val marketRegime: String,
    val topCandidate: String,
    val liveExecutionEnabled: Boolean,
    val portfolioValueIdr: String,
    val pnlTodayIdr: String,
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
) {
    companion object {
        fun preview(): MacDashboardState = MacDashboardState(
            isBotRunning = false,
            effectiveState = BotEffectiveState.STOPPED,
            operatingMode = "GROWTH",
            edgeConfidence = "MEDIUM",
            marketRegime = "HIGH_VOLATILITY_UNCLEAR",
            topCandidate = "-",
            liveExecutionEnabled = false,
            portfolioValueIdr = "Rp0",
            pnlTodayIdr = "+Rp0",
            syncPathLabel = "Supabase",
            activeEngine = "Android",
            standbyEngine = "Mac",
            syncHealth = "DEGRADED",
            leaseTerm = 0,
            healthSummary = "Waiting for control-plane connection.",
            weeklyLearningSummary = "Belum ada review mingguan.",
            weeklyAdaptationSummary = "Adaptasi mingguan akan tampil di sini.",
            lastHeartbeatLabel = "Never",
            lastUpdatedLabel = "Baru saja",
            statusMessage = "Mac standby is booting.",
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }
}

class MacStateRepository {
    private val _state = MutableStateFlow(MacDashboardState.preview())
    val state: StateFlow<MacDashboardState> = _state.asStateFlow()

    fun applyRuntimeState(next: MacDashboardState) {
        _state.value = next.copy(lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds())
    }

    fun noteStatus(message: String) {
        _state.value = _state.value.copy(
            statusMessage = message,
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
    }

    fun applyAndReturn(command: MacCommand): MacDashboardState {
        val next = _state.value.copy(
            statusMessage = command.defaultStatusMessage(),
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        _state.value = next
        return next
    }
}

private fun MacCommand.defaultStatusMessage(): String = when (this) {
    MacCommand.REQUEST_TAKEOVER -> "Graceful takeover requested."
    MacCommand.FORCE_SAFE_TAKEOVER -> "Force safe takeover requested."
    MacCommand.RELEASE_CONTROL -> "Release control requested."
    MacCommand.SYNC_NOW -> "Manual sync requested."
    MacCommand.START_BOT -> "Bot start requested."
    MacCommand.STOP_BOT -> "Bot stop requested."
}
