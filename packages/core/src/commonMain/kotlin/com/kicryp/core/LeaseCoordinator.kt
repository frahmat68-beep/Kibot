package com.kicryp.core

import com.kicryp.shared.models.BotDesiredState
import com.kicryp.shared.models.DeviceId
import com.kicryp.shared.models.EngineHealthSnapshot
import com.kicryp.shared.models.EngineLeaseSnapshot
import com.kicryp.shared.models.HealthStatus
import com.kicryp.shared.models.LeaseState
import com.kicryp.shared.models.ReconciliationReport
import com.kicryp.shared.models.ReconciliationState
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class TakeoverEvaluation(
    val allowed: Boolean,
    val failSafe: Boolean,
    val reasons: List<String>,
)

class LeaseCoordinator(
    private val config: LeaseProtocolConfig = LeaseProtocolConfig(),
) {
    fun canAcquireMastership(
        now: Instant = Clock.System.now(),
        currentLease: EngineLeaseSnapshot?,
        requester: DeviceId,
        reconciliationReport: ReconciliationReport,
        requesterHealth: EngineHealthSnapshot,
        desiredState: BotDesiredState,
    ): TakeoverEvaluation {
        val reasons = mutableListOf<String>()

        if (desiredState == BotDesiredState.OFF) {
            reasons += "Bot desired state is OFF."
        }

        if (requesterHealth.status == HealthStatus.CRITICAL || !requesterHealth.supabaseReachable) {
            reasons += "Requester health is not sufficient for takeover."
        }

        if (reconciliationReport.state == ReconciliationState.BLOCKED) {
            reasons += "Reconciliation is blocked."
        }

        val leaseExpired = currentLease == null || now >= currentLease.expiresAt
        val isAlreadyHolder = currentLease?.currentHolder == requester && currentLease.state == LeaseState.HELD

        if (!leaseExpired && !isAlreadyHolder) {
            reasons += "Current lease is still valid."
        }

        if (currentLease?.conflictDetected == true && !leaseExpired && !isAlreadyHolder) {
            reasons += "Conflict detected on current lease."
        }

        return TakeoverEvaluation(
            allowed = reasons.isEmpty(),
            failSafe = reasons.any { it.contains("Conflict") || it.contains("Reconciliation") },
            reasons = reasons,
        )
    }

    fun canIssueTradeWrite(
        currentLease: EngineLeaseSnapshot,
        requester: DeviceId,
        now: Instant = Clock.System.now(),
    ): Boolean {
        return currentLease.currentHolder == requester &&
            currentLease.state == LeaseState.HELD &&
            now < currentLease.expiresAt &&
            !currentLease.conflictDetected
    }
}
