package com.kibot.android.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.kibot.core.ControlPlaneGateway
import com.kibot.core.EntryHealthDecision
import com.kibot.core.ExchangeGateway
import com.kibot.core.HealthAdvisor
import com.kibot.core.LeaseCoordinator
import com.kibot.core.LeaseProtocolConfig
import com.kibot.core.LiveExecutionCoordinator
import com.kibot.core.ReconciliationService
import com.kibot.core.RiskConfig
import com.kibot.core.StrategyCycleResult
import com.kibot.core.StrategyOrchestrator
import com.kibot.aisupport.GeminiSupportCoordinator
import com.kibot.shared.models.AuditLogRecord
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotStateSnapshot
import com.kibot.shared.models.CommandEnvelope
import com.kibot.shared.models.CommandStatus
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.DeviceDescriptor
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.EngineHeartbeatSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.ReconciliationState
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.SyncHealth
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class AndroidEngineTickResult(
    val effectiveState: BotEffectiveState,
    val statusMessage: String,
    val currentPair: String?,
    val operatingMode: String,
)

class AndroidEngineDaemon(
    private val context: Context,
    private val controlPlane: ControlPlaneGateway,
    private val exchange: ExchangeGateway,
    private val config: AndroidRuntimeConfig,
    private val leaseCoordinator: LeaseCoordinator = LeaseCoordinator(
        LeaseProtocolConfig(
            heartbeatIntervalSeconds = 10,
            leaseTtlSeconds = config.leaseTtlSeconds,
        ),
    ),
    private val reconciliationService: ReconciliationService = ReconciliationService(),
    private val healthAdvisor: HealthAdvisor = HealthAdvisor(RiskConfig()),
    private val strategyOrchestrator: StrategyOrchestrator = StrategyOrchestrator(),
    private val liveExecutionCoordinator: LiveExecutionCoordinator = LiveExecutionCoordinator(),
    private val aiSupportCoordinator: GeminiSupportCoordinator? = null,
) {
    private val controlPlaneConfig = requireNotNull(config.controlPlane) {
        "Android engine butuh control-plane config yang valid."
    }
    private var registered = false
    private var lastAnalysisPublishedAt: Instant? = null
    private var lastStrategyMetricsPublishedAt: Instant? = null
    private var lastCandidateSignature: String? = null

    suspend fun syncOnce(): AndroidEngineTickResult {
        ensureRegistered()

        val now = Clock.System.now()
        val botState = controlPlane.fetchBotState(controlPlaneConfig.botId)
            ?: error("Bot state tidak ditemukan di control plane.")
        val lease = controlPlane.fetchLease(controlPlaneConfig.botId)
        val devices = controlPlane.fetchDevices(controlPlaneConfig.botId)
        val dailyRisk = controlPlane.fetchDailyRisk(controlPlaneConfig.botId, jakartaNowDate(now))
        val commands = controlPlane.fetchPendingCommands(controlPlaneConfig.botId, config.device.deviceId)

        val exchangeReachable = runCatching { exchange.ping() }.getOrElse { false }
        val warnings = mutableListOf<String>()
        if (!exchangeReachable) warnings += "Exchange tidak bisa dijangkau."
        if (dailyRisk?.hardStopTriggered == true) warnings += "Daily hard stop aktif."

        var leaseAfterCommands = lease
        var botStateAfterCommands = botState
        commands.forEach { command ->
            val result = handleCommand(command, leaseAfterCommands, botStateAfterCommands)
            if (result != null) {
                warnings += result
                leaseAfterCommands = controlPlane.fetchLease(controlPlaneConfig.botId)
                botStateAfterCommands = controlPlane.fetchBotState(controlPlaneConfig.botId) ?: botStateAfterCommands
            }
        }

        val localHealth = buildLocalHealth(exchangeReachable, warnings)
        val masterBeforeTakeover = leaseAfterCommands.isHeldBy(config.device.deviceId, now)
        if (botStateAfterCommands.desiredState == BotDesiredState.ON && !masterBeforeTakeover) {
            maybeTakeOver(now, botStateAfterCommands, leaseAfterCommands, localHealth)
        } else if (botStateAfterCommands.desiredState == BotDesiredState.OFF && masterBeforeTakeover) {
            controlPlane.releaseLease(
                botId = controlPlaneConfig.botId,
                deviceId = config.device.deviceId,
                term = leaseAfterCommands?.term?.value ?: 0L,
                reason = "Bot diminta OFF dari Android.",
            )
            appendAuditLog(LogLevel.INFO, "LEASE", "Android melepas lease karena desired state OFF.")
        }

        val initialBotState = controlPlane.fetchBotState(controlPlaneConfig.botId) ?: botStateAfterCommands
        val initialLease = controlPlane.fetchLease(controlPlaneConfig.botId)
        val isMaster = initialLease.isHeldBy(config.device.deviceId, now)
        val balances = if (exchangeReachable) runCatching { exchange.fetchBalances() }.getOrDefault(emptyList()) else emptyList()
        val openOrders = if (exchangeReachable) runCatching { exchange.fetchOpenOrders() }.getOrDefault(emptyList()) else emptyList()
        val marketQuotes = if (exchangeReachable) runCatching { exchange.fetchMarketQuotes() }.getOrDefault(emptyList()) else emptyList()
        if (exchangeReachable && marketQuotes.isEmpty()) warnings += "Feed market kosong."
        val finalHealth = buildLocalHealth(exchangeReachable, warnings)
        val healthDecision = healthAdvisor.evaluate(finalHealth)
        val aiSupportHints = if (isMaster && marketQuotes.isNotEmpty()) {
            val shortlist = strategyOrchestrator.shortlistForSupport(marketQuotes)
            val evaluation = aiSupportCoordinator
                ?.evaluate(
                    candidates = shortlist,
                    now = now,
                )
            if (evaluation?.usedNetwork == true) {
                appendAuditLog(
                    LogLevel.INFO,
                    "AI_SUPPORT",
                    "REQUEST",
                )
            }
            evaluation?.hints.orEmpty()
        } else {
            emptyList()
        }
        val strategyCycle = if (marketQuotes.isNotEmpty()) {
            strategyOrchestrator.analyze(
                botId = controlPlaneConfig.botId,
                balances = balances,
                openOrders = openOrders,
                dailyRisk = dailyRisk,
                health = finalHealth,
                marketQuotes = marketQuotes,
                pairSupportHints = aiSupportHints,
            )
        } else {
            null
        }

        var runtimeBotState = initialBotState
        var runtimeLease = initialLease
        if (isMaster && runtimeLease != null && strategyCycle != null) {
            publishAnalysisIfNeeded(now, runtimeLease, strategyCycle)
            maybeExecuteLiveOrder(now, runtimeLease, strategyCycle)
            runtimeBotState = controlPlane.fetchBotState(controlPlaneConfig.botId) ?: runtimeBotState
            runtimeLease = controlPlane.fetchLease(controlPlaneConfig.botId)
        }

        val runtimeEffectiveState = deriveEffectiveState(runtimeBotState, runtimeLease, healthDecision)
        controlPlane.appendHeartbeat(
            EngineHeartbeatSnapshot(
                botId = controlPlaneConfig.botId,
                deviceId = config.device.deviceId,
                observedAt = now,
                term = runtimeLease?.term,
                isMaster = runtimeLease.isHeldBy(config.device.deviceId, now),
                desiredState = runtimeBotState.desiredState,
                effectiveState = runtimeEffectiveState,
                health = finalHealth,
            ),
        )

        return AndroidEngineTickResult(
            effectiveState = runtimeEffectiveState,
            statusMessage = when {
                runtimeBotState.effectiveState == BotEffectiveState.SAFE_MODE || runtimeLease?.conflictDetected == true ->
                    runtimeBotState.safeModeReason ?: "SAFE_MODE aktif."
                healthDecision.reasons.isNotEmpty() -> healthDecision.reasons.joinToString(" ")
                strategyCycle != null -> strategyCycle.summary.joinToString(" ")
                runtimeLease.isHeldBy(config.device.deviceId, now) -> "Android sedang memegang lease master."
                else -> "Android standby memonitor status bot."
            },
            currentPair = strategyCycle?.selectedSignal?.pairId?.value ?: runtimeBotState.currentPair?.value,
            operatingMode = strategyCycle?.modeSnapshot?.mode?.name ?: runtimeBotState.operatingMode.name,
        )
    }

    private suspend fun ensureRegistered() {
        if (registered) return
        controlPlane.registerDevice(config.device)
        registered = true
        appendAuditLog(LogLevel.INFO, "AUTH", "Android device terdaftar ke control plane.")
    }

    private suspend fun handleCommand(
        command: CommandEnvelope,
        lease: EngineLeaseSnapshot?,
        botState: BotStateSnapshot,
    ): String? {
        return when (command.commandType) {
            CommandType.REQUEST_TAKEOVER -> {
                if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                    controlPlane.releaseLease(
                        botId = controlPlaneConfig.botId,
                        deviceId = config.device.deviceId,
                        term = lease?.term?.value ?: 0L,
                        reason = "Android melepas kontrol untuk graceful takeover.",
                    )
                }
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Request takeover diproses."
            }

            CommandType.FORCE_SAFE_TAKEOVER -> {
                val outcome = maybeTakeOver(
                    now = Clock.System.now(),
                    botState = botState,
                    lease = lease,
                    localHealth = buildLocalHealth(
                        exchangeReachable = runCatching { exchange.ping() }.getOrDefault(false),
                        warnings = listOf("Force safe takeover diminta."),
                    ),
                )
                controlPlane.updateCommandStatus(command.commandId, if (outcome) CommandStatus.SUCCEEDED else CommandStatus.FAILED)
                if (outcome) "Force safe takeover berhasil." else "Force safe takeover diblokir."
            }

            CommandType.RELEASE_CONTROL -> {
                if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                    controlPlane.releaseLease(
                        botId = controlPlaneConfig.botId,
                        deviceId = config.device.deviceId,
                        term = lease?.term?.value ?: 0L,
                        reason = "Android release control.",
                    )
                }
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Release control diproses."
            }

            CommandType.SYNC_NOW -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Sync manual diproses."
            }

            CommandType.START_BOT -> {
                controlPlane.setDesiredState(controlPlaneConfig.botId, BotDesiredState.ON)
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Bot desired state ON."
            }

            CommandType.STOP_BOT -> {
                controlPlane.setDesiredState(controlPlaneConfig.botId, BotDesiredState.OFF)
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Bot desired state OFF."
            }

            CommandType.FORCE_STANDBY,
            CommandType.RESUME_FROM_SAFE_MODE,
            -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Command ${command.commandType.name} diakui."
            }
        }
    }

    private suspend fun maybeTakeOver(
        now: Instant,
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        localHealth: EngineHealthSnapshot,
    ): Boolean {
        val balances = runCatching { exchange.fetchBalances() }.getOrDefault(emptyList())
        val openOrders = runCatching { exchange.fetchOpenOrders() }.getOrDefault(emptyList())
        val fills = openOrders.map { it.pairId }.distinct().flatMap { pairId ->
            runCatching { exchange.fetchRecentFills(pairId, limit = 20) }.getOrDefault(emptyList())
        }
        val persistedOrders = controlPlane.fetchOpenPersistedOrders(controlPlaneConfig.botId)
        val reconciliation = reconciliationService.reconcile(
            portfolio = PortfolioSnapshot(
                botId = controlPlaneConfig.botId,
                balances = balances,
                openOrders = openOrders,
                positions = emptyList<PositionSnapshot>(),
                totalEquityIdr = estimatePortfolioValue(balances),
                lastSyncedAt = now,
            ),
            recentFills = fills,
            persistedOrders = persistedOrders,
        )
        val evaluation = leaseCoordinator.canAcquireMastership(
            now = now,
            currentLease = lease,
            requester = config.device.deviceId,
            reconciliationReport = reconciliation,
            requesterHealth = localHealth,
            desiredState = botState.desiredState,
        )
        if (!evaluation.allowed) {
            if (evaluation.failSafe || reconciliation.state != ReconciliationState.CLEAN) {
                controlPlane.markConflictSafeMode(
                    botId = controlPlaneConfig.botId,
                    reason = evaluation.reasons.joinToString(" "),
                )
                appendAuditLog(LogLevel.ERROR, "FAILOVER", "Android fail-safe block: ${evaluation.reasons.joinToString(" ")}")
            }
            return false
        }
        controlPlane.acquireLease(
            botId = controlPlaneConfig.botId,
            deviceId = config.device.deviceId,
            ttlSeconds = config.leaseTtlSeconds,
        )
        appendAuditLog(LogLevel.WARN, "FAILOVER", "Android mengambil lease master setelah rekonsiliasi aman.")
        return true
    }

    private suspend fun publishAnalysisIfNeeded(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: StrategyCycleResult,
    ) {
        val candidateSignature = cycle.deploymentPlan.candidates.joinToString("|") { "${it.pairId.value}:${"%.2f".format(it.rankingScore)}" }
        val shouldPublishAnalysis = lastAnalysisPublishedAt == null ||
            (now - lastAnalysisPublishedAt!!).inWholeMilliseconds >= 30_000 ||
            candidateSignature != lastCandidateSignature
        val shouldPublishMetrics = lastStrategyMetricsPublishedAt == null ||
            (now - lastStrategyMetricsPublishedAt!!).inWholeMilliseconds >= 300_000 ||
            candidateSignature != lastCandidateSignature

        if (shouldPublishAnalysis) {
            controlPlane.publishRuntimeIntelligence(
                RuntimeIntelligenceUpdate(
                    botId = controlPlaneConfig.botId,
                    deviceId = config.device.deviceId,
                    term = lease.term,
                    currentPair = cycle.selectedSignal?.pairId,
                    operatingMode = cycle.modeSnapshot.mode,
                    edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                    aggressionScore = cycle.modeSnapshot.aggressionScore,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    profitProtectionStatus = cycle.modeSnapshot.profitProtectionStatus,
                    marketRegime = cycle.marketSnapshot.regime,
                    distrustLabels = cycle.distrustLabels,
                    activeCandidatePairs = cycle.deploymentPlan.candidates.map { it.pairId },
                    marketOpportunityScore = cycle.marketSnapshot.marketOpportunityScore,
                    botHealthScore = cycle.marketSnapshot.botHealthScore,
                    performanceMomentumScore = cycle.marketSnapshot.performanceMomentumScore,
                    safeModeReason = if (cycle.modeSnapshot.mode.name == "SAFE") cycle.summary.firstOrNull() else null,
                ),
            )
            lastAnalysisPublishedAt = now
            lastCandidateSignature = candidateSignature
        }
        if (shouldPublishMetrics) {
            controlPlane.appendStrategyMetrics(controlPlaneConfig.botId, cycle.rankedPairs.take(5))
            lastStrategyMetricsPublishedAt = now
        }
    }

    private suspend fun maybeExecuteLiveOrder(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: StrategyCycleResult,
    ) {
        if (!config.enableLiveExecution) return
        val executionPlan = cycle.executionPlan ?: return
        if (!cycle.modeSnapshot.tradingAllowed || !cycle.riskDecision.allowNewEntries) return

        val persistedOpenOrders = controlPlane.fetchOpenPersistedOrders(controlPlaneConfig.botId)
        if (persistedOpenOrders.isNotEmpty()) return

        val result = liveExecutionCoordinator.submitEntry(
            botId = controlPlaneConfig.botId,
            deviceId = config.device.deviceId,
            term = lease.term,
            executionPlan = executionPlan,
            existingPersistedOrders = persistedOpenOrders,
            exchange = exchange,
            controlPlane = controlPlane,
        )
        appendAuditLog(
            level = when {
                result.failSafeTriggered -> LogLevel.ERROR
                result.submitted -> LogLevel.INFO
                else -> LogLevel.WARN
            },
            category = "EXECUTION",
            message = result.message,
        )
        if (result.submitted) {
            lastAnalysisPublishedAt = now
        }
    }

    private fun buildLocalHealth(
        exchangeReachable: Boolean,
        warnings: List<String>,
    ): EngineHealthSnapshot {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryPct = batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null
        }
        val charging = batteryStatus?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
                -> true
                else -> false
            }
        }
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        val networkMetered = connectivityManager.isActiveNetworkMetered
        val supabaseReachable = true
        val websocketHealthy = exchangeReachable

        val status = when {
            !exchangeReachable || capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                HealthStatus.CRITICAL
            warnings.isNotEmpty() -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }
        val syncHealth = when (status) {
            HealthStatus.HEALTHY -> SyncHealth.HEALTHY
            HealthStatus.WARNING -> SyncHealth.DEGRADED
            HealthStatus.CRITICAL -> SyncHealth.BROKEN
        }
        return EngineHealthSnapshot(
            status = status,
            syncHealth = syncHealth,
            websocketHealthy = websocketHealthy,
            exchangeReachable = exchangeReachable,
            supabaseReachable = supabaseReachable,
            batteryPercent = batteryPct,
            charging = charging,
            networkMetered = networkMetered,
            fillQualityScore = if (warnings.any { it.contains("fill", ignoreCase = true) }) 0.35 else 0.75,
            anomalyCount = warnings.size,
            lastError = warnings.firstOrNull(),
            warnings = warnings.distinct(),
        )
    }

    private fun deriveEffectiveState(
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        healthDecision: EntryHealthDecision,
    ): BotEffectiveState {
        if (botState.desiredState == BotDesiredState.OFF) return BotEffectiveState.STOPPED
        if (lease?.conflictDetected == true) return BotEffectiveState.SAFE_MODE
        return if (healthDecision.tradingAllowed) {
            if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) BotEffectiveState.RUNNING else BotEffectiveState.STARTING
        } else {
            BotEffectiveState.DEGRADED
        }
    }

    private suspend fun appendAuditLog(level: LogLevel, category: String, message: String) {
        runCatching {
            controlPlane.appendLog(
                botId = controlPlaneConfig.botId,
                record = AuditLogRecord(
                    recordedAt = Clock.System.now(),
                    level = level,
                    category = category,
                    deviceId = config.device.deviceId,
                    term = controlPlane.fetchLease(controlPlaneConfig.botId)?.term,
                    message = message,
                ),
            )
        }
    }

    private fun estimatePortfolioValue(balances: List<BalanceSnapshot>): DecimalValue {
        val total = balances.sumOf { balance ->
            balance.totalValueInIdr?.toDoubleOrZero() ?: (balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero())
        }
        return DecimalValue.fromDouble(total.coerceAtLeast(0.0))
    }

    private fun jakartaNowDate(now: Instant) = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
}

private fun EngineLeaseSnapshot?.isHeldBy(deviceId: DeviceId, now: Instant): Boolean {
    return this?.currentHolder == deviceId &&
        this.state == com.kibot.shared.models.LeaseState.HELD &&
        this.expiresAt > now &&
        !this.conflictDetected
}
