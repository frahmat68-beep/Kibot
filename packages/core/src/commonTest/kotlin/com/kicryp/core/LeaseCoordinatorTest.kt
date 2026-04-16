package com.kicryp.core

import com.kicryp.shared.models.BotDesiredState
import com.kicryp.shared.models.BotId
import com.kicryp.shared.models.DeviceId
import com.kicryp.shared.models.EngineHealthSnapshot
import com.kicryp.shared.models.EngineLeaseSnapshot
import com.kicryp.shared.models.HealthStatus
import com.kicryp.shared.models.LeaseState
import com.kicryp.shared.models.LeaseTerm
import com.kicryp.shared.models.ReconciliationReport
import com.kicryp.shared.models.ReconciliationState
import com.kicryp.shared.models.SyncHealth
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

