package com.kibot.core

import com.kibot.shared.models.AuditLogRecord
import com.kibot.shared.models.BotUpdateRecommendation
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.CommandEnvelope
import com.kibot.shared.models.CommandId
import com.kibot.shared.models.CommandStatus
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.DeviceDescriptor
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DevicePlatform
import com.kibot.shared.models.DeviceRole
import com.kibot.shared.models.EncryptedCredentialBundle
import com.kibot.shared.models.EngineHeartbeatSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.ExecutionActionId
import com.kibot.shared.models.ExecutionActionTicket
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.LocalDate

data class DeviceRegistration(
    val deviceId: DeviceId,
    val displayName: String,
    val platform: DevicePlatform,
    val role: DeviceRole,
)

interface ControlPlaneGateway {
    suspend fun registerDevice(registration: DeviceRegistration): DeviceDescriptor

    suspend fun fetchBotState(botId: BotId): com.kibot.shared.models.BotStateSnapshot?

    suspend fun fetchLease(botId: BotId): EngineLeaseSnapshot?

    suspend fun fetchDevices(botId: BotId): List<DeviceDescriptor>

    suspend fun fetchDailyRisk(botId: BotId, date: LocalDate): DailyRiskSnapshot?

    suspend fun fetchPendingCommands(botId: BotId, deviceId: DeviceId, limit: Int = 25): List<CommandEnvelope>

    suspend fun setDesiredState(botId: BotId, desiredState: BotDesiredState)

    suspend fun acquireLease(botId: BotId, deviceId: DeviceId, ttlSeconds: Int): EngineLeaseSnapshot

    suspend fun releaseLease(
        botId: BotId,
        deviceId: DeviceId,
        term: Long,
        reason: String? = null,
    ): EngineLeaseSnapshot

    suspend fun appendHeartbeat(snapshot: EngineHeartbeatSnapshot)

    suspend fun publishRuntimeIntelligence(update: RuntimeIntelligenceUpdate)

    suspend fun appendStrategyMetrics(botId: BotId, metrics: List<PairScore>)

    suspend fun upsertWeeklyLearningSummary(summary: WeeklyLearningSummary)

    suspend fun fetchLatestWeeklyLearningSummary(botId: BotId): WeeklyLearningSummary?

    suspend fun upsertUpdateRecommendation(recommendation: BotUpdateRecommendation)

    suspend fun fetchLatestUpdateRecommendations(botId: BotId, limit: Int = 10): List<BotUpdateRecommendation>

    suspend fun enqueueCommand(
        botId: BotId,
        createdBy: DeviceId,
        commandType: CommandType,
        targetDeviceId: DeviceId? = null,
        payloadJson: String? = null,
    ): CommandEnvelope

    suspend fun updateCommandStatus(commandId: CommandId, status: CommandStatus)

    suspend fun reserveExecutionAction(
        botId: BotId,
        deviceId: DeviceId,
        term: Long,
        orderIntentId: String,
        actionType: String,
    ): ExecutionActionTicket

    suspend fun completeExecutionAction(
        actionId: ExecutionActionId,
        deviceId: DeviceId,
        status: String,
    )

    suspend fun markConflictSafeMode(botId: BotId, reason: String)

    suspend fun appendLog(botId: BotId, record: AuditLogRecord)

    suspend fun fetchRecentLogs(botId: BotId, limit: Int = 50): List<AuditLogRecord>

    suspend fun fetchRecentOrders(botId: BotId, limit: Int = 50): List<OrderSnapshot>

    suspend fun fetchOpenPersistedOrders(botId: BotId): List<OrderSnapshot>

    suspend fun upsertOrderSnapshot(
        botId: BotId,
        term: Long,
        deviceId: DeviceId,
        order: OrderSnapshot,
    )

    suspend fun upsertEncryptedCredentialBundle(bundle: EncryptedCredentialBundle)

    suspend fun fetchEncryptedCredentialBundle(botId: BotId): EncryptedCredentialBundle?
}
