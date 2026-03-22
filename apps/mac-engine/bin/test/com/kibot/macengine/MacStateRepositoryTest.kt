package com.kibot.macengine

import com.kibot.macengine.state.MacCommand
import com.kibot.macengine.state.MacDashboardState
import com.kibot.macengine.state.MacStateRepository
import com.kibot.shared.models.BotEffectiveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacStateRepositoryTest {
    @Test
    fun `status note updates repository message`() {
        val repository = MacStateRepository()
        repository.applyAndReturn(MacCommand.START_BOT)
        assertTrue(repository.state.value.statusMessage.contains("start", ignoreCase = true))
    }

    @Test
    fun `runtime state replaces dashboard snapshot`() {
        val repository = MacStateRepository()
        repository.applyRuntimeState(
            MacDashboardState(
                isBotRunning = true,
                effectiveState = BotEffectiveState.RUNNING,
                operatingMode = "GROWTH",
                edgeConfidence = "HIGH",
                marketRegime = "HEALTHY_UPTREND",
                topCandidate = "btc_idr",
                liveExecutionEnabled = false,
                portfolioValueIdr = "Rp100.000",
                pnlTodayIdr = "+Rp2.500",
                syncPathLabel = "Supabase + LAN",
                activeEngine = "MacBook Pro",
                standbyEngine = "Android Poco M3",
                syncHealth = "HEALTHY",
                leaseTerm = 11,
                healthSummary = "Master healthy.",
                weeklyLearningSummary = "Belum ada review mingguan.",
                weeklyAdaptationSummary = "Adaptasi mingguan belum tersedia.",
                lastHeartbeatLabel = "2s ago",
                lastUpdatedLabel = "08:15 WIB",
                statusMessage = "Mac currently holds the master lease.",
                lastUpdatedEpochMs = 1L,
            ),
        )

        assertTrue(repository.state.value.isBotRunning)
        assertEquals("MacBook Pro", repository.state.value.activeEngine)
    }
}
