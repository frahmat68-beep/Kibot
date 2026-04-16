package com.kicryp.shared.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class DeviceDescriptor(
    val userId: UserId,
    val deviceId: DeviceId,
    val displayName: String,
    val platform: DevicePlatform,
    val role: DeviceRole,
    val isRevoked: Boolean = false,
)

@Serializable
data class EngineHealthSnapshot(
    val status: HealthStatus,
    val syncHealth: SyncHealth,
    val websocketHealthy: Boolean,
    val exchangeReachable: Boolean,
    val supabaseReachable: Boolean,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val networkMetered: Boolean? = null,
    val heartbeatLagMs: Long? = null,
    val feedLatencyMs: Long? = null,
    val fillQualityScore: Double = 0.5,
    val rejectRatePct: Double = 0.0,
    val anomalyCount: Int = 0,
    val lastError: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class EngineHeartbeatSnapshot(
    val botId: BotId,
    val deviceId: DeviceId,
    val observedAt: Instant,
    val term: LeaseTerm?,
    val isMaster: Boolean,
    val desiredState: BotDesiredState,
    val effectiveState: BotEffectiveState,
    val health: EngineHealthSnapshot,
)

@Serializable
data class EngineLeaseSnapshot(
    val botId: BotId,
    val currentHolder: DeviceId?,
    val term: LeaseTerm,
    val state: LeaseState,
    val expiresAt: Instant,
    val lastHeartbeatAt: Instant?,
    val conflictDetected: Boolean,
)

@Serializable
data class BotStateSnapshot(
    val botId: BotId,
    val desiredState: BotDesiredState,
    val effectiveState: BotEffectiveState,
    val activeDeviceId: DeviceId?,
    val standbyDeviceId: DeviceId?,
    val currentTerm: LeaseTerm,
    val syncHealth: SyncHealth,
    val strategyMode: StrategyMode,
    val safeModeReason: String? = null,
    val currentPair: PairId? = null,
    val lastHeartbeatAt: Instant? = null,
    val operatingMode: BotMode = BotMode.GROWTH,
    val edgeConfidence: EdgeConfidence = EdgeConfidence.MEDIUM,
    val aggressionScore: Double = 0.5,
    val riskLadderLevel: RiskLadderLevel = RiskLadderLevel.NORMAL,
    val profitProtectionStatus: ProfitProtectionStatus = ProfitProtectionStatus.INACTIVE,
    val marketRegime: MarketRegime = MarketRegime.HIGH_VOLATILITY_UNCLEAR,
    val distrustLabels: List<DistrustLabel> = emptyList(),
    val activeCandidatePairs: List<PairId> = emptyList(),
)

@Serializable
data class CommandEnvelope(
    val commandId: CommandId,
    val botId: BotId,
    val createdBy: DeviceId,
    val targetDeviceId: DeviceId? = null,
    val commandType: CommandType,
    val status: CommandStatus,
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val payloadJson: String? = null,
)

@Serializable
data class ExecutionActionTicket(
    val actionId: ExecutionActionId,
    val botId: BotId,
    val term: LeaseTerm,
    val deviceId: DeviceId,
    val orderIntentId: String,
    val expiresAt: Instant,
)

@Serializable
data class AuditLogRecord(
    val recordedAt: Instant,
    val level: LogLevel,
    val category: String,
    val deviceId: DeviceId?,
    val term: LeaseTerm?,
    val message: String,
    val metadataJson: String? = null,
)
