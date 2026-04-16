package com.kicryp.core

import com.kicryp.shared.models.EngineHealthSnapshot
import com.kicryp.shared.models.HealthStatus
import com.kicryp.shared.models.SyncHealth
import kotlin.test.Test
import kotlin.test.assertTrue

class HealthAdvisorTest {
    @Test
    fun `suggests takeover when battery is low and not charging`() {
        val decision = HealthAdvisor().evaluate(
            EngineHealthSnapshot(
                status = HealthStatus.HEALTHY,
                syncHealth = SyncHealth.HEALTHY,
                websocketHealthy = true,
                exchangeReachable = true,
                supabaseReachable = true,
                batteryPercent = 20,
                charging = false,
            ),
        )

        assertTrue(decision.shouldSuggestTakeover)
    }
}
