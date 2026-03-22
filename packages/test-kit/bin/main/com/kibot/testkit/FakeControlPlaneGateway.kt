package com.kibot.testkit

import com.kibot.core.ControlPlaneGateway
import com.kibot.core.DeviceRegistration
import com.kibot.shared.models.AuditLogRecord
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotStateSnapshot
import com.kibot.shared.models.BotUpdateRecommendation
import com.kibot.shared.models.CommandEnvelope
import com.kibot.shared.models.CommandId
import com.kibot.shared.models.CommandStatus
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.DailyEquityHistoryPoint
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.DeviceDescriptor
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.EncryptedCredentialBundle
import com.kibot.shared.models.EngineHeartbeatSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.ExecutionActionId
import com.kibot.shared.models.ExecutionActionTicket
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LeaseTerm
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.UserId
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.seconds

class FakeControlPlaneGateway(
    private val botId: BotId = BotId("main"),
    private val nowProvider: () -> Instant = { Clock.System.now() },
) : ControlPlaneGateway {
    private var commandCounter = 0
    private var actionCounter = 0
    private val userId = UserId("test-user")
    private val devices = linkedMapOf<DeviceId, DeviceDescriptor>()
    private val commands = mutableListOf<CommandEnvelope>()
    private val logs = mutableListOf<AuditLogRecord>()
    private val recentOrders = mutableListOf<OrderSnapshot>()
    val strategyMetrics = mutableListOf<PairScore>()
    val updateRecommendations = mutableListOf<BotUpdateRecommendation>()
    var runtimeIntelligence: RuntimeIntelligenceUpdate? = null
    var latestWeeklyLearningSummary: WeeklyLearningSummary? = null

    var botState: BotStateSnapshot = BotStateSnapshot(
        botId = botId,
        desiredState = BotDesiredState.OFF,
        effectiveState = BotEffectiveState.STOPPED,
        activeDeviceId = null,
        standbyDeviceId = null,
        currentTerm = LeaseTerm(0),
        syncHealth = com.kibot.shared.models.SyncHealth.DEGRADED,
        strategyMode = com.kibot.shared.models.StrategyMode.AUTO_CONSERVATIVE,
        lastHeartbeatAt = null,
    )

    var lease: EngineLeaseSnapshot? = null
    var dailyRisk: DailyRiskSnapshot? = null
    private val dailyRiskHistory = linkedMapOf<LocalDate, DailyRiskSnapshot>()
    var encryptedCredentialBundle: EncryptedCredentialBundle? = null

    override suspend fun registerDevice(registration: DeviceRegistration): DeviceDescriptor {
        val descriptor = DeviceDescriptor(
            userId = userId,
            deviceId = registration.deviceId,
            displayName = registration.displayName,
            platform = registration.platform,
            role = registration.role,
            isRevoked = false,
        )
        devices[registration.deviceId] = descriptor
        if (botState.standbyDeviceId == null && botState.activeDeviceId != registration.deviceId) {
            botState = botState.copy(standbyDeviceId = registration.deviceId)
        }
        return descriptor
    }

    override suspend fun fetchBotState(botId: BotId): BotStateSnapshot? = botState.takeIf { it.botId == botId }

    override suspend fun fetchLease(botId: BotId): EngineLeaseSnapshot? = lease?.takeIf { it.botId == botId }

    override suspend fun fetchDevices(botId: BotId): List<DeviceDescriptor> = devices.values.toList()

    override suspend fun fetchDailyRisk(botId: BotId, date: LocalDate): DailyRiskSnapshot? = dailyRiskHistory[date] ?: dailyRisk

    override suspend fun fetchDailyRiskHistory(botId: BotId, days: Int): List<DailyEquityHistoryPoint> {
        return dailyRiskHistory.entries
            .sortedBy { it.key }
            .takeLast(days)
            .map { (date, snapshot) ->
                DailyEquityHistoryPoint(
                    date = date,
                    openingEquityIdr = snapshot.openingEquityIdr,
                    currentEquityIdr = snapshot.currentEquityIdr,
                    realizedPnlIdr = snapshot.realizedPnlIdr,
                    unrealizedPnlIdr = snapshot.unrealizedPnlIdr,
                )
            }
    }

    override suspend fun upsertDailyRisk(botId: BotId, date: LocalDate, snapshot: DailyRiskSnapshot) {
        dailyRisk = snapshot
        dailyRiskHistory[date] = snapshot
    }

    override suspend fun fetchPendingCommands(botId: BotId, deviceId: DeviceId, limit: Int): List<CommandEnvelope> {
        return commands
            .filter { it.botId == botId && it.status == CommandStatus.QUEUED }
            .filter { it.targetDeviceId == null || it.targetDeviceId == deviceId }
            .take(limit)
    }

    override suspend fun setDesiredState(botId: BotId, desiredState: BotDesiredState) {
        botState = botState.copy(
            desiredState = desiredState,
            effectiveState = if (desiredState == BotDesiredState.OFF) BotEffectiveState.STOPPED else botState.effectiveState,
        )
    }

    override suspend fun acquireLease(botId: BotId, deviceId: DeviceId, ttlSeconds: Int): EngineLeaseSnapshot {
        val term = (lease?.term?.value ?: botState.currentTerm.value) + 1
        val acquired = EngineLeaseSnapshot(
            botId = botId,
            currentHolder = deviceId,
            term = LeaseTerm(term),
            state = LeaseState.HELD,
            expiresAt = nowProvider().plus(ttlSeconds.seconds),
            lastHeartbeatAt = nowProvider(),
            conflictDetected = false,
        )
        lease = acquired
        botState = botState.copy(
            activeDeviceId = deviceId,
            standbyDeviceId = devices.keys.firstOrNull { it != deviceId },
            currentTerm = LeaseTerm(term),
            effectiveState = BotEffectiveState.STARTING,
            lastHeartbeatAt = nowProvider(),
            syncHealth = com.kibot.shared.models.SyncHealth.HEALTHY,
        )
        return acquired
    }

    override suspend fun releaseLease(
        botId: BotId,
        deviceId: DeviceId,
        term: Long,
        reason: String?,
    ): EngineLeaseSnapshot {
        val released = EngineLeaseSnapshot(
            botId = botId,
            currentHolder = null,
            term = LeaseTerm(term),
            state = LeaseState.RELEASED,
            expiresAt = nowProvider(),
            lastHeartbeatAt = nowProvider(),
            conflictDetected = false,
        )
        lease = released
        botState = botState.copy(
            activeDeviceId = null,
            effectiveState = if (botState.desiredState == BotDesiredState.OFF) {
                BotEffectiveState.STOPPED
            } else {
                BotEffectiveState.DEGRADED
            },
            safeModeReason = reason,
        )
        return released
    }

    override suspend fun appendHeartbeat(snapshot: EngineHeartbeatSnapshot) {
        if (snapshot.isMaster) {
            lease = EngineLeaseSnapshot(
                botId = snapshot.botId,
                currentHolder = snapshot.deviceId,
                term = snapshot.term ?: LeaseTerm(0),
                state = LeaseState.HELD,
                expiresAt = snapshot.observedAt.plus(30.seconds),
                lastHeartbeatAt = snapshot.observedAt,
                conflictDetected = lease?.conflictDetected ?: false,
            )
            botState = botState.copy(
                activeDeviceId = snapshot.deviceId,
                currentTerm = snapshot.term ?: botState.currentTerm,
                effectiveState = snapshot.effectiveState,
                syncHealth = snapshot.health.syncHealth,
                lastHeartbeatAt = snapshot.observedAt,
            )
        }
    }

    override suspend fun publishRuntimeIntelligence(update: RuntimeIntelligenceUpdate) {
        runtimeIntelligence = update
        botState = botState.copy(
            currentPair = update.currentPair,
            operatingMode = update.operatingMode,
            edgeConfidence = update.edgeConfidence,
            aggressionScore = update.aggressionScore,
            riskLadderLevel = update.riskLadderLevel,
            profitProtectionStatus = update.profitProtectionStatus,
            marketRegime = update.marketRegime,
            distrustLabels = update.distrustLabels,
            activeCandidatePairs = update.activeCandidatePairs,
            safeModeReason = update.safeModeReason ?: botState.safeModeReason,
        )
    }

    override suspend fun appendStrategyMetrics(botId: BotId, metrics: List<PairScore>) {
        strategyMetrics += metrics
    }

    override suspend fun upsertWeeklyLearningSummary(summary: WeeklyLearningSummary) {
        latestWeeklyLearningSummary = summary
    }

    override suspend fun fetchLatestWeeklyLearningSummary(botId: BotId): WeeklyLearningSummary? {
        return latestWeeklyLearningSummary?.takeIf { it.botId == botId }
    }

    override suspend fun upsertUpdateRecommendation(recommendation: BotUpdateRecommendation) {
        val existingIndex = updateRecommendations.indexOfFirst {
            it.botId == recommendation.botId &&
                it.scope == recommendation.scope &&
                it.versionTag == recommendation.versionTag
        }
        if (existingIndex >= 0) {
            updateRecommendations[existingIndex] = recommendation
        } else {
            updateRecommendations += recommendation
        }
    }

    override suspend fun fetchLatestUpdateRecommendations(botId: BotId, limit: Int): List<BotUpdateRecommendation> {
        return updateRecommendations
            .filter { it.botId == botId }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    override suspend fun enqueueCommand(
        botId: BotId,
        createdBy: DeviceId,
        commandType: CommandType,
        targetDeviceId: DeviceId?,
        payloadJson: String?,
    ): CommandEnvelope {
        val command = CommandEnvelope(
            commandId = CommandId("cmd-${++commandCounter}"),
            botId = botId,
            createdBy = createdBy,
            targetDeviceId = targetDeviceId,
            commandType = commandType,
            status = CommandStatus.QUEUED,
            createdAt = nowProvider(),
            payloadJson = payloadJson,
        )
        commands += command
        return command
    }

    override suspend fun updateCommandStatus(commandId: CommandId, status: CommandStatus) {
        val index = commands.indexOfFirst { it.commandId == commandId }
        if (index >= 0) {
            commands[index] = commands[index].copy(status = status)
        }
    }

    override suspend fun reserveExecutionAction(
        botId: BotId,
        deviceId: DeviceId,
        term: Long,
        orderIntentId: String,
        actionType: String,
    ): ExecutionActionTicket {
        return ExecutionActionTicket(
            actionId = ExecutionActionId("action-${++actionCounter}"),
            botId = botId,
            term = LeaseTerm(term),
            deviceId = deviceId,
            orderIntentId = orderIntentId,
            expiresAt = nowProvider().plus(20.seconds),
        )
    }

    override suspend fun completeExecutionAction(actionId: ExecutionActionId, deviceId: DeviceId, status: String) = Unit

    override suspend fun markConflictSafeMode(botId: BotId, reason: String) {
        lease = lease?.copy(
            state = LeaseState.CONFLICT,
            conflictDetected = true,
            expiresAt = nowProvider(),
        )
        botState = botState.copy(
            effectiveState = BotEffectiveState.SAFE_MODE,
            syncHealth = com.kibot.shared.models.SyncHealth.BROKEN,
            safeModeReason = reason,
        )
    }

    override suspend fun appendLog(botId: BotId, record: AuditLogRecord) {
        logs += record
    }

    override suspend fun fetchRecentLogs(botId: BotId, limit: Int): List<AuditLogRecord> = logs.takeLast(limit).reversed()

    override suspend fun fetchRecentOrders(botId: BotId, limit: Int): List<OrderSnapshot> = recentOrders.takeLast(limit).reversed()

    override suspend fun fetchOpenPersistedOrders(botId: BotId): List<OrderSnapshot> = recentOrders.filter {
        it.status in setOf(
            com.kibot.shared.models.OrderStatus.CREATED,
            com.kibot.shared.models.OrderStatus.SUBMITTING,
            com.kibot.shared.models.OrderStatus.OPEN,
            com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED,
            com.kibot.shared.models.OrderStatus.CANCEL_REQUESTED,
            com.kibot.shared.models.OrderStatus.UNKNOWN,
        )
    }

    override suspend fun upsertOrderSnapshot(botId: BotId, term: Long, deviceId: DeviceId, order: OrderSnapshot) {
        val index = recentOrders.indexOfFirst { it.clientOrderId == order.clientOrderId }
        if (index >= 0) {
            recentOrders[index] = order
        } else {
            recentOrders += order
        }
    }

    override suspend fun upsertEncryptedCredentialBundle(bundle: EncryptedCredentialBundle) {
        encryptedCredentialBundle = bundle
    }

    override suspend fun fetchEncryptedCredentialBundle(botId: BotId): EncryptedCredentialBundle? = encryptedCredentialBundle

    fun seedLease(snapshot: EngineLeaseSnapshot?) {
        lease = snapshot
        if (snapshot != null) {
            botState = botState.copy(
                activeDeviceId = snapshot.currentHolder,
                currentTerm = snapshot.term,
                lastHeartbeatAt = snapshot.lastHeartbeatAt,
            )
        }
    }

    fun seedPersistedOrders(vararg orders: OrderSnapshot) {
        recentOrders.clear()
        recentOrders += orders
    }

    fun seedCommand(command: CommandEnvelope) {
        commands += command
    }
}
