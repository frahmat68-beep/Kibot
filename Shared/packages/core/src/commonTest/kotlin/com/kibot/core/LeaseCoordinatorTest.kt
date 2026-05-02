package com.kibot.core

import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LeaseTerm
import com.kibot.shared.models.ReconciliationReport
import com.kibot.shared.models.ReconciliationState
import com.kibot.shared.models.SyncHealth
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaseCoordinatorTest {
    private val coordinator = LeaseCoordinator()
    private val health = EngineHealthSnapshot(
        status = HealthStatus.HEALTHY,
        syncHealth = SyncHealth.HEALTHY,
        websocketHealthy = true,
        exchangeReachable = true,
        supabaseReachable = true,
    )

    @Test
    fun `blocks takeover while unexpired lease exists`() {
        val evaluation = coordinator.canAcquireMastership(
            now = Instant.parse("2026-03-15T01:00:00Z"),
            currentLease = EngineLeaseSnapshot(
                botId = BotId("main"),
                currentHolder = DeviceId("android"),
                term = LeaseTerm(4),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2026-03-15T01:00:10Z"),
                lastHeartbeatAt = Instant.parse("2026-03-15T00:59:58Z"),
                conflictDetected = false,
            ),
            requester = DeviceId("mac"),
            reconciliationReport = ReconciliationReport(state = ReconciliationState.CLEAN),
            requesterHealth = health,
            desiredState = BotDesiredState.ON,
        )

        assertFalse(evaluation.allowed)
    }

    @Test
    fun `allows takeover after expiry with clean reconciliation`() {
        val evaluation = coordinator.canAcquireMastership(
            now = Instant.parse("2026-03-15T01:00:00Z"),
            currentLease = EngineLeaseSnapshot(
                botId = BotId("main"),
                currentHolder = DeviceId("android"),
                term = LeaseTerm(4),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2026-03-15T00:59:00Z"),
                lastHeartbeatAt = Instant.parse("2026-03-15T00:58:50Z"),
                conflictDetected = false,
            ),
            requester = DeviceId("mac"),
            reconciliationReport = ReconciliationReport(state = ReconciliationState.CLEAN),
            requesterHealth = health,
            desiredState = BotDesiredState.ON,
        )

        assertTrue(evaluation.allowed)
    }
}

