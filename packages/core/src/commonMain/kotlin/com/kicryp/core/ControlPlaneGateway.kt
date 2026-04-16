package com.kicryp.core

import com.kicryp.shared.models.AuditLogRecord
import com.kicryp.shared.models.BotUpdateRecommendation
import com.kicryp.shared.models.BotDesiredState
import com.kicryp.shared.models.BotId
import com.kicryp.shared.models.CommandEnvelope
import com.kicryp.shared.models.CommandId
import com.kicryp.shared.models.CommandStatus
import com.kicryp.shared.models.CommandType
import com.kicryp.shared.models.DailyEquityHistoryPoint
import com.kicryp.shared.models.DailyRiskSnapshot
import com.kicryp.shared.models.DeviceDescriptor
import com.kicryp.shared.models.DeviceId
import com.kicryp.shared.models.DevicePlatform
import com.kicryp.shared.models.DeviceRole
import com.kicryp.shared.models.EncryptedCredentialBundle
import com.kicryp.shared.models.EngineHeartbeatSnapshot
import com.kicryp.shared.models.EngineLeaseSnapshot
import com.kicryp.shared.models.ExecutionActionId
import com.kicryp.shared.models.ExecutionActionTicket
import com.kicryp.shared.models.OrderSnapshot
import com.kicryp.shared.models.PairScore
import com.kicryp.shared.models.PositionSnapshot
import com.kicryp.shared.models.RuntimeIntelligenceUpdate
import com.kicryp.shared.models.WeeklyLearningSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Instant

data class KingDashboardSnapshot(
    val totalBalanceIdr: Double,
    val currentPingMs: Long?,
    val activeLivePairs: List<String>,
    val latestManagerLog: String?,
    val udpPingMs: Long? = null,
    val kidaxPingMs: Long? = null,
    val kinancePingMs: Long? = null,
    val targetProgressPct: Double? = null,
    val kidaxBalanceIdr: Double? = null,
    val kinanceBalanceIdr: Double? = null,
    val kidaxPnlTodayPct: Double? = null,
    val kinancePnlTodayPct: Double? = null,
    val kidaxPairActive: String? = null,
    val kinancePairActive: String? = null,
)

data class TradeHistoryRecord(
    val pair: String,
    val side: String,
    val status: String,
    val detail: String,
    val createdAt: Instant? = null,
)

data class DeviceRegistration(
    val deviceId: DeviceId,
    val displayName: String,
    val platform: DevicePlatform,
    val role: DeviceRole,
)

interface ControlPlaneGateway {
    suspend fun registerDevice(registration: DeviceRegistration): DeviceDescriptor

    suspend fun fetchBotState(botId: BotId): com.kicryp.shared.models.BotStateSnapshot?

    suspend fun fetchLease(botId: BotId): EngineLeaseSnapshot?

    suspend fun fetchDevices(botId: BotId): List<DeviceDescriptor>

    suspend fun fetchDailyRisk(botId: BotId, date: LocalDate): DailyRiskSnapshot?

    suspend fun fetchDailyRiskHistory(botId: BotId, days: Int = 7): List<DailyEquityHistoryPoint>

    suspend fun upsertDailyRisk(
        botId: BotId,
        date: LocalDate,
        snapshot: DailyRiskSnapshot,
    )

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

    suspend fun upsertKingDashboardFastTelemetry(
        totalBalanceIdr: Double,
        currentPingMs: Long?,
        activeLivePairs: List<String>,
    )

    suspend fun fetchKingDashboardSnapshot(): KingDashboardSnapshot?

    suspend fun fetchTradeHistory(limit: Int = 50, offset: Int = 0): List<TradeHistoryRecord>

    suspend fun fetchRecentLogs(botId: BotId, limit: Int = 50): List<AuditLogRecord>

    suspend fun fetchRecentOrders(botId: BotId, limit: Int = 50): List<OrderSnapshot>

    suspend fun fetchOpenPersistedOrders(botId: BotId): List<OrderSnapshot>

    suspend fun fetchActivePositions(botId: BotId): List<PositionSnapshot>

    suspend fun upsertOrderSnapshot(
        botId: BotId,
        term: Long,
        deviceId: DeviceId,
        order: OrderSnapshot,
    )

    suspend fun upsertEncryptedCredentialBundle(bundle: EncryptedCredentialBundle)

    suspend fun fetchEncryptedCredentialBundle(botId: BotId): EncryptedCredentialBundle?
}
