package com.kicryp.macengine

import com.kicryp.macengine.state.MacCommand
import com.kicryp.macengine.state.MacDashboardState
import com.kicryp.macengine.state.MacHoldingDetail
import com.kicryp.macengine.state.MacStateRepository
import com.kicryp.shared.models.BotEffectiveState
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
                radarPairs = listOf("btc_idr", "eth_idr"),
                scanUniverseCount = 120,
                releaseLabel = "#99",
                liveExecutionEnabled = false,
                portfolioValueIdr = "Rp100.000",
                freeIdrLabel = "Rp20.000",
                totalValueIdr = "Rp100.000",
                pnlTodayIdr = "+Rp2.500",
                pnlTodayPctLabel = "+2.5%",
                return7dIdr = "+Rp5.000",
                return7dPctLabel = "+5.0%",
                return30dIdr = "+Rp12.000",
                return30dPctLabel = "+12.0%",
                targetPursuitLabel = "CHASE",
                aiProviderSummary = "AI sehat: openrouter",
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
                serverLocation = "Oracle Cloud Singapore",
                serverUptime = "02h 15m",
                heldAssets = listOf("DOGE", "TRX"),
                holdingsDetailed = listOf(
                    MacHoldingDetail(
                        assetCode = "DOGE",
                        assetLabel = "Doge",
                        quantityLabel = "10 DOGE",
                        valueIdrLabel = "Rp12.000",
                        entryPriceLabel = "Rp1.080",
                        currentPriceLabel = "Rp1.200",
                        pnlIdrLabel = "+Rp1.200",
                        pnlPctLabel = "+10.0%",
                    ),
                ),
                exchangePingMs = "84",
                kidaxNodeStatus = "online",
                kibotNodeStatus = "online",
                kinanceNodeStatus = "online",
                liveTimeline = emptyList(),
                recentOrders = emptyList(),
                trailingFloors = emptyList(),
            ),
        )

        assertTrue(repository.state.value.isBotRunning)
        assertEquals("MacBook Pro", repository.state.value.activeEngine)
    }
}
