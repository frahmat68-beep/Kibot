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
import com.kibot.core.LiveLearningReviewBuilder
import com.kibot.core.LiveRolloutGuard
import com.kibot.core.LiveExecutionCoordinator
import com.kibot.core.ManagedPosition
import com.kibot.core.ReconciliationService
import com.kibot.core.RiskConfig
import com.kibot.core.SituationalLearningEngine
import com.kibot.core.StrategyCycleResult
import com.kibot.core.StrategyOrchestrator
import com.kibot.core.TradeAutomationCoordinator
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
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.ReconciliationState
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.StrategySignal
import com.kibot.shared.models.StrategySignalType
import com.kibot.shared.models.SyncHealth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max
import kotlin.math.roundToLong

data class AndroidEngineTickResult(
    val effectiveState: BotEffectiveState,
    val statusMessage: String,
    val currentPair: String?,
    val operatingMode: String,
    val liveStatusSnapshot: LiveStatusSnapshot? = null,
    val liveLogEntry: LiveLogEntry? = null,
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
    private val liveLearningReviewBuilder: LiveLearningReviewBuilder = LiveLearningReviewBuilder(),
    private val liveRolloutGuard: LiveRolloutGuard = LiveRolloutGuard(),
    private val liveExecutionCoordinator: LiveExecutionCoordinator = LiveExecutionCoordinator(),
    private val situationalLearningEngine: SituationalLearningEngine = SituationalLearningEngine(),
    private val tradeAutomationCoordinator: TradeAutomationCoordinator = TradeAutomationCoordinator(),
    private val aiSupportCoordinator: GeminiSupportCoordinator? = null,
) {
    private val controlPlaneConfig = requireNotNull(config.controlPlane) {
        "Android engine butuh control-plane config yang valid."
    }
    private var registered = false
    private var lastAnalysisPublishedAt: Instant? = null
    private var lastStrategyMetricsPublishedAt: Instant? = null
    private var lastCandidateSignature: String? = null
    private var lastLearningSignature: String? = null
    private var lastLearningPublishedAt: Instant? = null
    private var lastWeeklyReviewPublishedAt: Instant? = null
    private var smoothedExchangePingMs: Double? = null
    private var lastSuccessfulExchangePingAt: Instant? = null
    private var lastSuccessfulControlPlaneAt: Instant? = null
    private var lastExecutionPolicyLogSignature: String? = null
    private var lastExecutionPolicyLoggedAt: Instant? = null
    private var lastEntryGateReason: String? = null
    private var forcedTrialExitConsumed = false
    private var stopProtectionStartedAt: Instant? = null

    suspend fun syncOnce(): AndroidEngineTickResult = coroutineScope {
        ensureRegistered()

        val now = Clock.System.now()
        val jakartaDate = jakartaNowDate(now)
        val pingStartedAtNs = System.nanoTime()
        val exchangeReachable = runCatching { exchange.ping() }.getOrElse { false }
        val exchangePingMs = ((System.nanoTime() - pingStartedAtNs) / 1_000_000L)
            .takeIf { exchangeReachable }
            ?.coerceAtLeast(1L)
        val displayPingMs = recordDisplayPing(
            now = now,
            exchangeReachable = exchangeReachable,
            rawPingMs = exchangePingMs,
        )
        val botStateDeferred = async { controlPlane.fetchBotState(controlPlaneConfig.botId) }
        val leaseDeferred = async { controlPlane.fetchLease(controlPlaneConfig.botId) }
        val devicesDeferred = async { controlPlane.fetchDevices(controlPlaneConfig.botId) }
        val dailyRiskDeferred = async { controlPlane.fetchDailyRisk(controlPlaneConfig.botId, jakartaDate) }
        val commandsDeferred = async { controlPlane.fetchPendingCommands(controlPlaneConfig.botId, config.device.deviceId) }
        val weeklyReviewDeferred = async {
            runCatching { controlPlane.fetchLatestWeeklyLearningSummary(controlPlaneConfig.botId) }.getOrNull()
        }

        val botState = botStateDeferred.await() ?: error("Bot state tidak ditemukan di control plane.")
        val lease = leaseDeferred.await()
        val devices = devicesDeferred.await()
        val dailyRisk = dailyRiskDeferred.await()
        val commands = commandsDeferred.await()
        val weeklyReview = weeklyReviewDeferred.await()
        lastSuccessfulControlPlaneAt = now

        val warnings = mutableListOf<String>()
        if (!exchangeReachable) warnings += "Exchange tidak bisa dijangkau."
        if (dailyRisk?.hardStopTriggered == true) warnings += "Daily hard stop aktif."
        if ((displayPingMs ?: 0L) >= entryBlockLatencyMs) warnings += "Latensi exchange sedang berat."

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

        val localHealth = buildLocalHealth(
            exchangeReachable = exchangeReachable,
            warnings = warnings,
            feedLatencyMs = displayPingMs ?: exchangePingMs,
            marketFeedHealthy = exchangeReachable,
        )
        val masterBeforeTakeover = leaseAfterCommands.isHeldBy(config.device.deviceId, now)
        if (botStateAfterCommands.desiredState == BotDesiredState.ON && stopProtectionStartedAt != null) {
            stopProtectionStartedAt = null
        }
        if (botStateAfterCommands.desiredState == BotDesiredState.ON && !masterBeforeTakeover) {
            maybeTakeOver(now, botStateAfterCommands, leaseAfterCommands, localHealth)
        } else if (botStateAfterCommands.desiredState == BotDesiredState.OFF && masterBeforeTakeover) {
            if (stopProtectionStartedAt == null) {
                stopProtectionStartedAt = now
                appendAuditLog(
                    LogLevel.WARN,
                    "STOP",
                    "Bot diminta OFF. Android masuk stop aman: blok entry baru lalu rapikan posisi sebelum lease dilepas.",
                )
            }
        }

        val initialBotState = controlPlane.fetchBotState(controlPlaneConfig.botId) ?: botStateAfterCommands
        val initialLease = controlPlane.fetchLease(controlPlaneConfig.botId)
        val isMaster = initialLease.isHeldBy(config.device.deviceId, now)
        val balancesDeferred: kotlinx.coroutines.Deferred<List<BalanceSnapshot>>? = if (exchangeReachable) {
            async { runCatching { exchange.fetchBalances() }.getOrDefault(emptyList()) }
        } else {
            null
        }
        val openOrdersDeferred: kotlinx.coroutines.Deferred<List<com.kibot.shared.models.OrderSnapshot>>? = if (exchangeReachable) {
            async { runCatching { exchange.fetchOpenOrders() }.getOrDefault(emptyList()) }
        } else {
            null
        }
        val marketQuotesDeferred: kotlinx.coroutines.Deferred<List<com.kibot.shared.models.MarketQuote>>? = if (exchangeReachable) {
            async { runCatching { exchange.fetchMarketQuotes() }.getOrDefault(emptyList()) }
        } else {
            null
        }
        val resolvedBalances = balancesDeferred?.await().orEmpty()
        val resolvedOpenOrders = openOrdersDeferred?.await().orEmpty()
        val resolvedMarketQuotes = marketQuotesDeferred?.await().orEmpty()
        if (exchangeReachable && resolvedMarketQuotes.isEmpty()) warnings += "Feed market kosong."
        val finalHealth = buildLocalHealth(
            exchangeReachable = exchangeReachable,
            warnings = warnings,
            feedLatencyMs = displayPingMs ?: exchangePingMs,
            marketFeedHealthy = exchangeReachable && resolvedMarketQuotes.isNotEmpty(),
        )
        val healthDecision = healthAdvisor.evaluate(finalHealth)
        val aiSupportEvaluation = if (isMaster && resolvedMarketQuotes.isNotEmpty()) {
            val shortlist = strategyOrchestrator.shortlistForSupport(resolvedMarketQuotes)
            aiSupportCoordinator?.evaluate(
                candidates = shortlist,
                now = now,
            )
        } else {
            null
        }
        val aiSupportHints = aiSupportEvaluation?.let { evaluation ->
            if (evaluation?.usedNetwork == true) {
                appendAuditLog(
                    LogLevel.INFO,
                    "AI_SUPPORT",
                    "REQUEST",
                )
            }
            evaluation?.hints.orEmpty()
        } ?: emptyList()
        val derivedDailyRisk = deriveDailyRiskSnapshot(
            previous = dailyRisk,
            balances = resolvedBalances,
            marketQuotes = resolvedMarketQuotes,
        ) ?: dailyRisk
        val strategyCycle = if (resolvedMarketQuotes.isNotEmpty()) {
            strategyOrchestrator.analyze(
                botId = controlPlaneConfig.botId,
                balances = resolvedBalances,
                openOrders = resolvedOpenOrders,
                dailyRisk = derivedDailyRisk,
                health = finalHealth,
                marketQuotes = resolvedMarketQuotes,
                pairSupportHints = aiSupportHints,
                weeklySummary = weeklyReview,
            )
        } else {
            null
        }
        val recentPersistedOrders = if (isMaster && exchangeReachable) {
            runCatching { controlPlane.fetchRecentOrders(controlPlaneConfig.botId, limit = 500) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val recentFills = if (isMaster && exchangeReachable) {
            relevantFillPairs(
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                openOrders = resolvedOpenOrders,
                persistedOrders = recentPersistedOrders,
                cycle = strategyCycle,
            ).flatMap { pairId ->
                runCatching { exchange.fetchRecentFills(pairId, limit = 30) }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        val reconciledOrderUpdates = if (isMaster && recentPersistedOrders.isNotEmpty()) {
            tradeAutomationCoordinator.reconcileOrders(
                persistedOrders = recentPersistedOrders,
                exchangeOpenOrders = resolvedOpenOrders,
                recentFills = recentFills,
            )
        } else {
            emptyList()
        }
        reconciledOrderUpdates.forEach { order ->
            controlPlane.upsertOrderSnapshot(
                botId = controlPlaneConfig.botId,
                term = initialLease?.term?.value ?: initialBotState.currentTerm.value,
                deviceId = config.device.deviceId,
                order = order,
            )
        }
        val effectiveRecentOrders = mergeRecentOrders(
            base = recentPersistedOrders,
            updates = reconciledOrderUpdates,
        )
        val snapshotManagedPositions = if (resolvedBalances.isNotEmpty() && resolvedMarketQuotes.isNotEmpty()) {
            tradeAutomationCoordinator.deriveManagedPositions(
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                reconciledOrders = effectiveRecentOrders,
                rankedPairs = strategyCycle?.rankedPairs.orEmpty(),
                now = now,
            )
        } else {
            emptyList()
        }

        var runtimeBotState = initialBotState
        var runtimeLease = initialLease
        var effectiveWeeklyReview = weeklyReview
        val effectiveDailyRisk = strategyCycle?.let { cycle ->
            derivedDailyRisk?.copy(
                hardStopTriggered = cycle.riskDecision.hardStopTriggered,
                riskLadderLevel = cycle.riskDecision.riskLadderLevel,
                profitProtectionStatus = cycle.riskDecision.profitProtectionStatus,
            )
        } ?: derivedDailyRisk
        if (isMaster && effectiveDailyRisk != null) {
            controlPlane.upsertDailyRisk(
                botId = controlPlaneConfig.botId,
                date = jakartaDate,
                snapshot = effectiveDailyRisk,
            )
        }
        var liveLogEntry: LiveLogEntry? = null
        if (isMaster && runtimeLease != null && strategyCycle != null) {
            effectiveWeeklyReview = maybePublishWeeklyLearningSummary(
                now = now,
                cycle = strategyCycle,
                marketQuotes = resolvedMarketQuotes,
                currentWeeklyReview = weeklyReview,
                recentOrders = effectiveRecentOrders,
            )
            publishAnalysisIfNeeded(now, runtimeLease, strategyCycle)
            liveLogEntry = maybeManageLiveTrading(
                now = now,
                lease = runtimeLease,
                cycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
                health = finalHealth,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                recentOrders = effectiveRecentOrders,
            )
            publishLearningSignalsIfNeeded(
                now = now,
                cycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
                aiBlockedReason = aiSupportEvaluation?.blockedReason,
                aiUsedNetwork = aiSupportEvaluation?.usedNetwork == true,
            )
            runtimeBotState = controlPlane.fetchBotState(controlPlaneConfig.botId) ?: runtimeBotState
            runtimeLease = controlPlane.fetchLease(controlPlaneConfig.botId)
        }
        if (botStateAfterCommands.desiredState == BotDesiredState.OFF && runtimeLease.isHeldBy(config.device.deviceId, now)) {
            handleStopProtection(
                now = now,
                lease = runtimeLease,
                managedPositions = snapshotManagedPositions,
                recentOrders = effectiveRecentOrders,
            )?.let { warnings += it }
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

        val visiblePair = strategyCycle?.selectedSignal?.pairId?.value
            ?: strategyCycle?.deploymentPlan?.candidates?.firstOrNull()?.pairId?.value
            ?: runtimeBotState.currentPair?.value
        val tickStatusMessage = when {
            runtimeBotState.effectiveState == BotEffectiveState.SAFE_MODE || runtimeLease?.conflictDetected == true ->
                runtimeBotState.safeModeReason ?: "SAFE_MODE aktif."
            healthDecision.reasons.isNotEmpty() -> healthDecision.reasons.joinToString(" ")
            strategyCycle != null -> strategyCycle.summary.joinToString(" ")
            runtimeLease.isHeldBy(config.device.deviceId, now) -> "Android sedang memegang lease master."
            else -> "Android standby memonitor status bot."
        }
        val ambientLiveLogEntry = if (liveLogEntry == null && strategyCycle != null) {
            buildAmbientLiveLogEntry(
                now = now,
                cycle = strategyCycle,
                managedPositions = snapshotManagedPositions,
                dailyRisk = effectiveDailyRisk,
                scanUniverseCount = resolvedMarketQuotes.size,
            )
        } else {
            null
        }

        return@coroutineScope AndroidEngineTickResult(
            effectiveState = runtimeEffectiveState,
            statusMessage = tickStatusMessage,
            currentPair = visiblePair,
            operatingMode = strategyCycle?.modeSnapshot?.mode?.name ?: runtimeBotState.operatingMode.name,
            liveStatusSnapshot = buildLiveStatusSnapshot(
                now = now,
                currentPair = visiblePair,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                dailyRisk = effectiveDailyRisk,
                internetPingMs = displayPingMs,
                scanUniverseCount = resolvedMarketQuotes.size,
                radarPairs = strategyCycle?.deploymentPlan?.candidates
                    ?.map { it.pairId.value }
                    ?.distinct()
                    ?.take(10)
                    .orEmpty(),
                statusMessage = tickStatusMessage,
                managedPositions = snapshotManagedPositions,
            ),
            liveLogEntry = liveLogEntry ?: ambientLiveLogEntry,
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
                        supabaseReachable = isControlPlaneReachable(Clock.System.now()),
                        marketFeedHealthy = false,
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
        if (
            lease != null &&
            lease.currentHolder != config.device.deviceId &&
            now < lease.expiresAt &&
            !lease.conflictDetected
        ) {
            return false
        }
        val balances = runCatching { exchange.fetchBalances() }.getOrDefault(emptyList())
        val openOrders = runCatching { exchange.fetchOpenOrders() }.getOrDefault(emptyList())
        val recentPersistedOrders = controlPlane.fetchRecentOrders(controlPlaneConfig.botId, limit = 500)
        val reconciliationPairs = (openOrders.map { it.pairId } + recentPersistedOrders.map { it.pairId })
            .distinct()
            .take(12)
        val fills = reconciliationPairs.flatMap { pairId ->
            runCatching { exchange.fetchRecentFills(pairId, limit = 20) }.getOrDefault(emptyList())
        }
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
            persistedOrders = recentPersistedOrders,
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
                    currentPair = cycle.selectedSignal?.pairId ?: cycle.deploymentPlan.candidates.firstOrNull()?.pairId,
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

    private suspend fun maybeManageLiveTrading(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        health: EngineHealthSnapshot,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): LiveLogEntry? {
        if (!config.enableLiveExecution) return null
        val entryStabilizedOrders = manageStaleEntryOrders(
            now = now,
            lease = lease,
            cycle = cycle,
            marketQuotes = marketQuotes,
            recentOrders = recentOrders,
        )
        val preExitManagedPositions = tradeAutomationCoordinator.deriveManagedPositions(
            balances = balances,
            marketQuotes = marketQuotes,
            reconciledOrders = entryStabilizedOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )
        val stabilizedOrders = manageStaleExitOrders(
            now = now,
            lease = lease,
            managedPositions = preExitManagedPositions,
            marketQuotes = marketQuotes,
            recentOrders = entryStabilizedOrders,
        )
        val activePersistedOrders = stabilizedOrders.filter { it.status in activeOrderStatuses }
        val managedPositions = tradeAutomationCoordinator.deriveManagedPositions(
            balances = balances,
            marketQuotes = marketQuotes,
            reconciledOrders = stabilizedOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )
        val exitDecision = tradeAutomationCoordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
        )
        val selectedExitDecision = maybeBuildTrialExitDecision(
            now = now,
            cycle = cycle,
            managedPositions = managedPositions,
            health = health,
            balances = balances,
            marketQuotes = marketQuotes,
        ) ?: maybeBuildManualStopExitDecision(
            now = now,
            cycle = cycle,
            managedPositions = managedPositions,
            health = health,
        ) ?: exitDecision
        if (selectedExitDecision != null) {
            val isTrialExit = selectedExitDecision.message.startsWith("Trial micro-exit")
            val preparedActiveOrders = prepareExitPath(
                now = now,
                lease = lease,
                recentOrders = stabilizedOrders,
                activePersistedOrders = activePersistedOrders,
                exitDecision = selectedExitDecision,
            )
            val result = liveExecutionCoordinator.submitExit(
                botId = controlPlaneConfig.botId,
                deviceId = config.device.deviceId,
                term = lease.term,
                executionPlan = selectedExitDecision.executionPlan,
                existingPersistedOrders = preparedActiveOrders,
                exchange = exchange,
                controlPlane = controlPlane,
            )
            appendAuditLog(
                level = when {
                    result.failSafeTriggered -> LogLevel.ERROR
                    result.submitted -> LogLevel.INFO
                    else -> LogLevel.WARN
                },
                category = "AUTO_EXIT",
                message = "${selectedExitDecision.message} ${result.message}",
            )
            if (result.submitted && isTrialExit) {
                forcedTrialExitConsumed = true
            }
            return when {
                result.submitted -> LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "SELL",
                    message = "SELL ${selectedExitDecision.position.pairId.value} • ${result.message}",
                )

                result.failSafeTriggered -> LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "RISK",
                    message = "AUTO_EXIT ${selectedExitDecision.position.pairId.value} gagal aman • ${result.message}",
                )

                else -> null
            }
        }
        if (stopProtectionStartedAt != null) {
            lastEntryGateReason = "Stop aman aktif. Entry baru ditahan sampai posisi dan order bersih."
            return LiveLogEntry(
                timestampEpochMs = now.toEpochMilliseconds(),
                category = "STOP",
                message = if (managedPositions.isEmpty()) {
                    "Stop aman aktif. Bot menunggu order bersih sebelum benar-benar mati."
                } else {
                    "Stop aman aktif. Bot tahan entry baru sambil rapikan ${managedPositions.first().pairId.value}."
                },
            )
        }

        val selectedSignal = cycle.selectedSignal
        val executionPlan = cycle.executionPlan ?: run {
            if (selectedSignal != null) {
                lastEntryGateReason = "Setup ${selectedSignal.pairId.value} ada, tapi budget minimum atau ukuran order belum lolos."
            } else {
                lastEntryGateReason = null
            }
            return null
        }
        if (!cycle.modeSnapshot.tradingAllowed || !cycle.riskDecision.allowNewEntries) {
            lastEntryGateReason = "Mode/risk gate masih menutup entry live."
            return null
        }
        val rolloutDecision = liveRolloutGuard.evaluate(cycle, weeklyReview)
        if (!rolloutDecision.allowed) {
            lastEntryGateReason = rolloutDecision.reason
            appendAuditLog(
                level = LogLevel.INFO,
                category = "ROLLOUT_GUARD",
                message = rolloutDecision.reason,
            )
            return null
        }
        entryBlockedByPortfolioState(
            cycle = cycle,
            executionPlan = executionPlan,
            managedPositions = managedPositions,
        )?.let { blockedReason ->
            lastEntryGateReason = blockedReason
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.WARN,
                category = "ENTRY_POLICY",
                message = blockedReason,
            )
            return null
        }
        if (activePersistedOrders.isNotEmpty()) {
            lastEntryGateReason = "Masih ada order aktif yang belum selesai, jadi entry baru ditunda."
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = "Entry ${executionPlan.signal.pairId.value} ditunda karena masih ada order aktif yang belum selesai.",
            )
            return null
        }

        val routedEntry = routeEntryPlanByLatency(
            executionPlan = executionPlan,
            health = health,
            marketQuotes = marketQuotes,
        )
        routedEntry.blockedReason?.let { blockedReason ->
            lastEntryGateReason = blockedReason
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.WARN,
                category = "ENTRY_POLICY",
                message = blockedReason,
            )
            return null
        }
        routedEntry.message?.let { note ->
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = note,
            )
        }
        val effectiveExecutionPlan = routedEntry.executionPlan ?: return null

        val result = liveExecutionCoordinator.submitEntry(
            botId = controlPlaneConfig.botId,
            deviceId = config.device.deviceId,
            term = lease.term,
            executionPlan = effectiveExecutionPlan,
            existingPersistedOrders = activePersistedOrders,
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
            lastEntryGateReason = null
            lastAnalysisPublishedAt = now
        } else {
            lastEntryGateReason = result.message
        }
        return when {
            result.submitted -> LiveLogEntry(
                timestampEpochMs = now.toEpochMilliseconds(),
                category = "BUY",
                message = "BUY ${effectiveExecutionPlan.signal.pairId.value} • ${result.message}",
            )

            result.failSafeTriggered -> LiveLogEntry(
                timestampEpochMs = now.toEpochMilliseconds(),
                category = "RISK",
                message = "ENTRY ${effectiveExecutionPlan.signal.pairId.value} fail-safe • ${result.message}",
            )

            else -> null
        }
    }

    private fun entryBlockedByPortfolioState(
        cycle: StrategyCycleResult,
        executionPlan: ExecutionPlan,
        managedPositions: List<ManagedPosition>,
    ): String? {
        if (managedPositions.isEmpty()) return null

        val samePairExposure = managedPositions.firstOrNull { it.pairId == executionPlan.signal.pairId }
        if (samePairExposure != null && samePairExposure.unrealizedPnlPct < 0.15) {
            return "Masih pegang ${executionPlan.signal.pairId.value} dan posisinya belum cukup hijau, jadi bot tidak averaging dulu."
        }

        val profitablePositions = managedPositions.filter { it.unrealizedPnlPct >= 0.20 }
        val redPositions = managedPositions.filter { it.unrealizedPnlPct <= -0.45 }
        val slotsAreFull = managedPositions.size >= cycle.deploymentPlan.maxActivePositions.coerceAtLeast(1)

        if (cycle.deploymentPlan.allowRotation && profitablePositions.isEmpty()) {
            return "Rotasi ditunda karena belum ada posisi yang sudah hijau setelah biaya."
        }
        if (slotsAreFull && redPositions.isNotEmpty() && profitablePositions.isEmpty()) {
            return "Entry baru ditahan karena portofolio masih merah dan belum ada posisi hijau untuk rotasi."
        }
        if (redPositions.size >= 3 && profitablePositions.isEmpty()) {
            return "Terlalu banyak posisi masih merah, jadi bot tahan entry baru sampai ada recovery nyata."
        }
        return null
    }

    private fun routeEntryPlanByLatency(
        executionPlan: ExecutionPlan,
        health: EngineHealthSnapshot,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): EntryRoutingDecision {
        if (executionPlan.side != OrderSide.BUY) {
            return EntryRoutingDecision(executionPlan = executionPlan)
        }
        val latencyMs = health.feedLatencyMs
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId }
        return when {
            latencyMs == null || latencyMs <= makerFirstMaxLatencyMs -> {
                if (executionPlan.orderType == OrderType.LIMIT && executionPlan.postOnlyPreferred) {
                    EntryRoutingDecision(executionPlan = executionPlan)
                } else {
                    val makerPrice = executionPlan.signal.entryPrice
                        ?: executionPlan.limitPrice
                        ?: quote?.bestBid
                        ?: quote?.midPrice
                        ?: return EntryRoutingDecision(
                            executionPlan = null,
                            blockedReason = "Entry ${executionPlan.signal.pairId.value} diblokir karena harga maker tidak tersedia.",
                        )
                    EntryRoutingDecision(
                        executionPlan = executionPlan.copy(
                            orderType = OrderType.LIMIT,
                            limitPrice = makerPrice,
                            postOnlyPreferred = true,
                        ),
                        message = "Ping hijau ${latencyLabel(latencyMs)}. Entry ${executionPlan.signal.pairId.value} dipaksa maker-first LIMIT.",
                    )
                }
            }

            latencyMs <= aggressiveLimitFallbackLatencyMs -> {
                val fastLimitPrice = quote?.bestAsk
                    ?: executionPlan.limitPrice
                    ?: executionPlan.signal.entryPrice
                    ?: return EntryRoutingDecision(
                        executionPlan = null,
                        blockedReason = "Entry ${executionPlan.signal.pairId.value} ditunda karena harga fallback tidak tersedia saat ping ${latencyMs}ms.",
                    )
                EntryRoutingDecision(
                    executionPlan = executionPlan.copy(
                        orderType = OrderType.LIMIT,
                        limitPrice = fastLimitPrice,
                        postOnlyPreferred = false,
                    ),
                    message = "Ping kuning ${latencyMs}ms. Entry ${executionPlan.signal.pairId.value} diturunkan ke LIMIT biasa agar tidak bergantung maker-only.",
                )
            }

            else -> {
                val fallbackLimitPrice = quote?.bestAsk
                    ?: executionPlan.limitPrice
                    ?: executionPlan.signal.entryPrice
                if (
                    executionPlan.signal.speculativePocket &&
                    executionPlan.expectedNetEdgePct >= 0.85 &&
                    latencyMs <= aggressiveLimitFallbackHardStopMs &&
                    fallbackLimitPrice != null
                ) {
                    EntryRoutingDecision(
                        executionPlan = executionPlan.copy(
                            orderType = OrderType.LIMIT,
                            limitPrice = fallbackLimitPrice,
                            postOnlyPreferred = false,
                        ),
                        message = "Ping tinggi ${latencyMs}ms. Pair breakout kuat ${executionPlan.signal.pairId.value} tetap boleh seed LIMIT cepat sambil jaga risiko.",
                    )
                } else {
                    EntryRoutingDecision(
                        executionPlan = null,
                        blockedReason = "Ping merah ${latencyMs}ms. Entry baru ${executionPlan.signal.pairId.value} diblokir sampai feed pulih; bot hanya fokus monitor/exit aman.",
                    )
                }
            }
        }
    }

    private suspend fun manageStaleEntryOrders(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val canceledSnapshots = mutableListOf<com.kibot.shared.models.OrderSnapshot>()
        recentOrders
            .filter { it.status in activeOrderStatuses && it.side == com.kibot.shared.models.OrderSide.BUY }
            .forEach { order ->
                val ageMinutes = ((now.toEpochMilliseconds() - order.createdAt.toEpochMilliseconds()).coerceAtLeast(0L) / 60_000.0)
                val bestAsk = quoteByPair[order.pairId]?.bestAsk?.toDoubleOrZero() ?: 0.0
                val orderPrice = order.price.toDoubleOrZero()
                val driftPct = if (bestAsk > 0.0 && orderPrice > 0.0) {
                    ((bestAsk - orderPrice) / orderPrice) * 100.0
                } else {
                    0.0
                }
                val pairFlipped = cycle.selectedSignal?.pairId != order.pairId
                val shouldCancel = stopProtectionStartedAt != null ||
                    ageMinutes >= staleEntryOrderMaxAgeMinutes ||
                    (pairFlipped && ageMinutes >= staleEntryOrderPairFlipGraceMinutes) ||
                    driftPct >= staleEntryOrderMaxDriftPct
                if (!shouldCancel) return@forEach

                val canceled = exchange.cancelOrder(order.clientOrderId)
                if (canceled) {
                    val canceledSnapshot = order.copy(
                        status = com.kibot.shared.models.OrderStatus.CANCELED,
                        updatedAt = now,
                    )
                    controlPlane.upsertOrderSnapshot(
                        botId = controlPlaneConfig.botId,
                        term = lease.term.value,
                        deviceId = config.device.deviceId,
                        order = canceledSnapshot,
                    )
                    canceledSnapshots += canceledSnapshot
                    appendAuditLog(
                        level = LogLevel.WARN,
                        category = "EXECUTION",
                        message = "Entry ${order.pairId.value} dibatalkan otomatis karena stale/drift (${formatDecimal(ageMinutes, 1)}m, ${formatDecimal(driftPct, 2)}%).",
                    )
                }
            }

        return mergeRecentOrders(
            base = recentOrders,
            updates = canceledSnapshots,
        )
    }

    private suspend fun manageStaleExitOrders(
        now: Instant,
        lease: EngineLeaseSnapshot,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        val positionsByPair = managedPositions.associateBy { it.pairId }
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val canceledSnapshots = mutableListOf<com.kibot.shared.models.OrderSnapshot>()
        recentOrders
            .filter {
                it.status in activeOrderStatuses &&
                    it.side == com.kibot.shared.models.OrderSide.SELL &&
                    it.orderType == com.kibot.shared.models.OrderType.LIMIT
            }
            .forEach { order ->
                val position = positionsByPair[order.pairId] ?: return@forEach
                val ageMinutes = ((now.toEpochMilliseconds() - order.createdAt.toEpochMilliseconds()).coerceAtLeast(0L) / 60_000.0)
                val bestBid = quoteByPair[order.pairId]?.bestBid?.toDoubleOrZero() ?: position.currentBidPrice.toDoubleOrZero()
                val orderPrice = order.price.toDoubleOrZero()
                val driftPct = if (bestBid > 0.0 && orderPrice > 0.0) {
                    ((orderPrice - bestBid) / orderPrice) * 100.0
                } else {
                    0.0
                }
                val shouldCancel = ageMinutes >= staleExitOrderMaxAgeMinutes ||
                    driftPct >= staleExitOrderMaxDriftPct ||
                    position.unrealizedPnlPct <= staleExitRepriceLossFloorPct
                if (!shouldCancel) return@forEach

                val canceled = exchange.cancelOrder(order.clientOrderId)
                if (canceled) {
                    val canceledSnapshot = order.copy(
                        status = com.kibot.shared.models.OrderStatus.CANCELED,
                        updatedAt = now,
                    )
                    controlPlane.upsertOrderSnapshot(
                        botId = controlPlaneConfig.botId,
                        term = lease.term.value,
                        deviceId = config.device.deviceId,
                        order = canceledSnapshot,
                    )
                    canceledSnapshots += canceledSnapshot
                    appendAuditLog(
                        level = LogLevel.WARN,
                        category = "AUTO_EXIT",
                        message = "Exit ${order.pairId.value} dibatalkan untuk reprice/fallback (${formatDecimal(ageMinutes, 1)}m, drift ${formatDecimal(driftPct, 2)}%).",
                    )
                }
            }

        return mergeRecentOrders(
            base = recentOrders,
            updates = canceledSnapshots,
        )
    }

    private suspend fun prepareExitPath(
        now: Instant,
        lease: EngineLeaseSnapshot,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        exitDecision: com.kibot.core.ExitDecision,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        if (exitDecision.executionPlan.orderType != com.kibot.shared.models.OrderType.MARKET) {
            return activePersistedOrders
        }
        val pairActiveSellOrders = activePersistedOrders.filter {
            it.pairId == exitDecision.position.pairId && it.side == com.kibot.shared.models.OrderSide.SELL
        }
        if (pairActiveSellOrders.isEmpty()) {
            return activePersistedOrders
        }

        val canceledSnapshots = mutableListOf<com.kibot.shared.models.OrderSnapshot>()
        pairActiveSellOrders.forEach { order ->
            val canceled = exchange.cancelOrder(order.clientOrderId)
            if (canceled) {
                val canceledSnapshot = order.copy(
                    status = com.kibot.shared.models.OrderStatus.CANCELED,
                    updatedAt = now,
                )
                controlPlane.upsertOrderSnapshot(
                    botId = controlPlaneConfig.botId,
                    term = lease.term.value,
                    deviceId = config.device.deviceId,
                    order = canceledSnapshot,
                )
                canceledSnapshots += canceledSnapshot
                appendAuditLog(
                    level = LogLevel.WARN,
                    category = "AUTO_EXIT",
                    message = "Exit lama ${order.clientOrderId.value} dibatalkan agar emergency exit ${exitDecision.position.pairId.value} bisa dijalankan.",
                )
            }
        }

        return mergeRecentOrders(
            base = recentOrders,
            updates = canceledSnapshots,
        ).filter { it.status in activeOrderStatuses }
    }

    private suspend fun maybePublishWeeklyLearningSummary(
        now: Instant,
        cycle: StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        currentWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): com.kibot.shared.models.WeeklyLearningSummary? {
        val shouldPublish = lastWeeklyReviewPublishedAt == null ||
            (now - lastWeeklyReviewPublishedAt!!).inWholeHours >= 6
        if (!shouldPublish) return currentWeeklyReview

        val summary = liveLearningReviewBuilder.build(
            botId = controlPlaneConfig.botId,
            now = now,
            cycle = cycle,
            marketQuotes = marketQuotes,
            recentOrders = recentOrders,
        ) ?: return currentWeeklyReview
        controlPlane.upsertWeeklyLearningSummary(summary)
        lastWeeklyReviewPublishedAt = now
        return summary
    }

    private suspend fun publishLearningSignalsIfNeeded(
        now: Instant,
        cycle: StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        aiBlockedReason: String?,
        aiUsedNetwork: Boolean,
    ) {
        val decision = situationalLearningEngine.evaluate(
            botId = controlPlaneConfig.botId,
            deviceId = config.device.deviceId,
            now = now,
            cycle = cycle,
            weeklySummary = weeklyReview,
            aiBlockedReason = aiBlockedReason,
            aiUsedNetwork = aiUsedNetwork,
        )
        if (decision.learningHints.isEmpty() && decision.updateRecommendations.isEmpty()) return

        val shouldPublish = lastLearningPublishedAt == null ||
            decision.signature != lastLearningSignature ||
            (now - lastLearningPublishedAt!!).inWholeHours >= 6
        if (!shouldPublish) return

        decision.learningHints.take(2).forEach { hint ->
            appendAuditLog(
                level = when (hint.severity) {
                    com.kibot.shared.models.AdvisorySeverity.HIGH -> LogLevel.WARN
                    com.kibot.shared.models.AdvisorySeverity.MEDIUM -> LogLevel.INFO
                    com.kibot.shared.models.AdvisorySeverity.LOW -> LogLevel.INFO
                },
                category = "LEARNING_HINT",
                message = hint.summary,
            )
        }
        decision.updateRecommendations.forEach { recommendation ->
            controlPlane.upsertUpdateRecommendation(recommendation)
            appendAuditLog(
                level = LogLevel.INFO,
                category = "UPDATE_HINT",
                message = "${recommendation.title}: ${recommendation.summary}",
            )
        }
        lastLearningSignature = decision.signature
        lastLearningPublishedAt = now
    }

    private fun buildLocalHealth(
        exchangeReachable: Boolean,
        warnings: List<String>,
        feedLatencyMs: Long? = null,
        supabaseReachable: Boolean = isControlPlaneReachable(Clock.System.now()),
        marketFeedHealthy: Boolean = exchangeReachable,
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
        val websocketHealthy = marketFeedHealthy

        val status = when {
            !exchangeReachable || !supabaseReachable || capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                HealthStatus.CRITICAL
            !marketFeedHealthy || warnings.isNotEmpty() -> HealthStatus.WARNING
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
            feedLatencyMs = feedLatencyMs,
            fillQualityScore = if (warnings.any { it.contains("fill", ignoreCase = true) }) 0.35 else 0.75,
            anomalyCount = warnings.size,
            lastError = warnings.firstOrNull(),
            warnings = warnings.distinct(),
        )
    }

    private fun isControlPlaneReachable(now: Instant): Boolean {
        val lastSuccess = lastSuccessfulControlPlaneAt ?: return false
        val stalenessMs = (now - lastSuccess).inWholeMilliseconds
        val graceWindowMs = (config.pollIntervalMillis * 3L).coerceAtLeast(12_000L)
        return stalenessMs <= graceWindowMs
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

    private suspend fun appendThrottledAuditLog(
        now: Instant,
        level: LogLevel,
        category: String,
        message: String,
    ) {
        val signature = "$category|$message"
        val lastLoggedAt = lastExecutionPolicyLoggedAt
        if (
            lastExecutionPolicyLogSignature == signature &&
            lastLoggedAt != null &&
            (now - lastLoggedAt).inWholeMinutes < executionPolicyLogCooldownMinutes
        ) {
            return
        }
        lastExecutionPolicyLogSignature = signature
        lastExecutionPolicyLoggedAt = now
        appendAuditLog(level = level, category = category, message = message)
    }

    private fun recordDisplayPing(
        now: Instant,
        exchangeReachable: Boolean,
        rawPingMs: Long?,
    ): Long? {
        if (exchangeReachable && rawPingMs != null) {
            val next = smoothedExchangePingMs
                ?.let { (it * 0.72) + (rawPingMs.toDouble() * 0.28) }
                ?: rawPingMs.toDouble()
            smoothedExchangePingMs = next
            lastSuccessfulExchangePingAt = now
            return next.roundToLong().coerceAtLeast(1L)
        }

        val lastSuccess = lastSuccessfulExchangePingAt
        if (lastSuccess != null && (now - lastSuccess).inWholeSeconds <= 45) {
            return smoothedExchangePingMs?.roundToLong()?.coerceAtLeast(1L)
        }

        smoothedExchangePingMs = null
        return null
    }

    private fun latencyLabel(latencyMs: Long?): String = when {
        latencyMs == null -> "--"
        latencyMs <= makerFirstMaxLatencyMs -> "${latencyMs}ms"
        latencyMs <= aggressiveLimitFallbackLatencyMs -> "${latencyMs}ms"
        else -> "${latencyMs}ms"
    }

    private fun buildLiveStatusSnapshot(
        now: Instant,
        currentPair: String?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        dailyRisk: com.kibot.shared.models.DailyRiskSnapshot?,
        internetPingMs: Long?,
        scanUniverseCount: Int,
        radarPairs: List<String>,
        statusMessage: String,
        managedPositions: List<ManagedPosition> = emptyList(),
    ): LiveStatusSnapshot? {
        if (balances.isEmpty()) return null
        val equity = balances.sumOf { balance ->
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            when {
                quantity <= 0.0 -> 0.0
                balance.asset.equals("idr", ignoreCase = true) -> quantity
                else -> quantity * (quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0)
            }
        }
        if (equity <= 0.0) return null
        val openingEquity = dailyRisk?.openingEquityIdr?.toDoubleOrZero() ?: equity
        val pnl = equity - openingEquity
        val managedHoldingItems = managedPositions
            .sortedByDescending { it.currentValueIdr.toDoubleOrZero() }
            .map { position ->
                val baseAsset = position.pairId.value.substringBefore('_').uppercase()
                LiveHoldingUi(
                    asset = baseAsset,
                    amount = formatAssetAmount(position.quantity.toDoubleOrZero(), baseAsset),
                    valueIdr = formatIdr(position.currentValueIdr.toDoubleOrZero()),
                    pnlIdr = formatSignedIdr(position.unrealizedPnlIdr.toDoubleOrZero()),
                    pnlPctLabel = formatSignedPercentFromPct(position.unrealizedPnlPct),
                )
            }
        val trackedAssets = managedHoldingItems.map { it.asset.uppercase() }.toSet()
        val balanceHoldingItems = balances
            .asSequence()
            .filterNot { it.asset.equals("idr", ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val asset = balance.asset.uppercase()
                if (asset in trackedAssets) return@mapNotNull null
                val value = quantity * (quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0)
                if (value < 1_000.0) return@mapNotNull null
                LiveHoldingUi(
                    asset = asset,
                    amount = formatAssetAmount(quantity, balance.asset),
                    valueIdr = formatIdr(value),
                )
            }
            .toList()
        val holdings = (managedHoldingItems + balanceHoldingItems)
            .sortedByDescending { it.valueIdr.filter(Char::isDigit).toLongOrNull() ?: 0L }
            .take(6)
        return LiveStatusSnapshot(
            updatedAtEpochMs = now.toEpochMilliseconds(),
            activePair = currentPair ?: "-",
            totalEquityIdr = formatIdr(equity),
            pnlTodayIdr = formatSignedIdr(pnl),
            internetPingMs = internetPingMs,
            scanUniverseCount = scanUniverseCount,
            radarPairs = radarPairs,
            holdings = holdings,
            statusMessage = statusMessage,
        )
    }

    private fun maybeBuildTrialExitDecision(
        now: Instant,
        cycle: StrategyCycleResult,
        managedPositions: List<ManagedPosition>,
        health: EngineHealthSnapshot,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.core.ExitDecision? {
        val targetPair = config.trialExitPair ?: return null
        if (forcedTrialExitConsumed) return null
        val position = managedPositions.firstOrNull { it.pairId.value.equals(targetPair, ignoreCase = true) }
            ?: buildSyntheticTrialPosition(
                now = now,
                targetPair = targetPair,
                balances = balances,
                marketQuotes = marketQuotes,
                cycle = cycle,
            )
            ?: return null
        val currentValue = position.currentValueIdr.toDoubleOrZero()
        if (currentValue <= 0.0 || currentValue > trialExitMaxNotionalIdr) return null
        val orderType = if ((health.feedLatencyMs ?: 0L) > aggressiveLimitFallbackLatencyMs) {
            OrderType.MARKET
        } else {
            OrderType.LIMIT
        }
        val exitPrice = position.currentBidPrice
        val signal = StrategySignal(
            pairId = position.pairId,
            signalType = StrategySignalType.EXIT,
            confidence = 1.0,
            rationale = listOf("trial_micro_exit"),
            entryPrice = exitPrice,
            takeProfitPrice = position.takeProfitPrice,
            stopPrice = position.stopPrice,
            setupType = position.setupType,
            horizon = position.horizon,
            pairTier = position.pairTier,
            speculativePocket = position.speculativePocket,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = position.expectedHoldingHours,
            expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
        )
        return com.kibot.core.ExitDecision(
            position = position,
            executionPlan = ExecutionPlan(
                signal = signal,
                side = OrderSide.SELL,
                orderType = orderType,
                quantity = position.quantity,
                limitPrice = if (orderType == OrderType.LIMIT) exitPrice else null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.riskDecision.riskLadderLevel,
                pairRankingScore = cycle.rankedPairs.firstOrNull { it.pairId == position.pairId }?.rankingScore ?: 0.0,
                speculativePocket = position.speculativePocket,
            ),
            reason = com.kibot.core.ExitReason.TIME_EXIT,
            message = "Trial micro-exit ${position.pairId.value} dijalankan untuk verifikasi sell otomatis.",
        )
    }

    private fun maybeBuildManualStopExitDecision(
        now: Instant,
        cycle: StrategyCycleResult,
        managedPositions: List<ManagedPosition>,
        health: EngineHealthSnapshot,
    ): com.kibot.core.ExitDecision? {
        if (stopProtectionStartedAt == null || managedPositions.isEmpty()) return null
        val position = managedPositions.maxByOrNull { it.currentValueIdr.toDoubleOrZero() } ?: return null
        val orderType = if ((health.feedLatencyMs ?: 0L) > aggressiveLimitFallbackLatencyMs) {
            OrderType.MARKET
        } else {
            OrderType.LIMIT
        }
        val signal = StrategySignal(
            pairId = position.pairId,
            signalType = StrategySignalType.EXIT,
            confidence = 0.95,
            rationale = listOf("manual_stop_protection"),
            entryPrice = position.currentBidPrice,
            takeProfitPrice = position.takeProfitPrice,
            stopPrice = position.stopPrice,
            setupType = position.setupType,
            horizon = position.horizon,
            pairTier = position.pairTier,
            speculativePocket = position.speculativePocket,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = position.expectedHoldingHours,
            expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
        )
        return com.kibot.core.ExitDecision(
            position = position,
            reason = com.kibot.core.ExitReason.TIME_EXIT,
            message = "Stop aman aktif. ${position.pairId.value} dijual dulu supaya bot bisa mati dengan posisi lebih bersih.",
            executionPlan = ExecutionPlan(
                signal = signal,
                side = OrderSide.SELL,
                orderType = orderType,
                quantity = position.quantity,
                limitPrice = if (orderType == OrderType.LIMIT) position.currentBidPrice else null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.riskDecision.riskLadderLevel,
                pairRankingScore = cycle.rankedPairs.firstOrNull { it.pairId == position.pairId }?.rankingScore ?: 0.60,
                speculativePocket = position.speculativePocket,
            ),
        )
    }

    private suspend fun handleStopProtection(
        now: Instant,
        lease: EngineLeaseSnapshot?,
        managedPositions: List<ManagedPosition>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): String? {
        val startedAt = stopProtectionStartedAt ?: return null
        val activeOrders = recentOrders.count { it.status in activeOrderStatuses }
        val hasExposure = managedPositions.isNotEmpty() || activeOrders > 0
        if (!hasExposure && lease != null) {
            controlPlane.releaseLease(
                botId = controlPlaneConfig.botId,
                deviceId = config.device.deviceId,
                term = lease.term.value,
                reason = "Stop aman selesai: posisi dan order sudah bersih.",
            )
            stopProtectionStartedAt = null
            appendAuditLog(LogLevel.INFO, "LEASE", "Android melepas lease setelah stop aman selesai.")
            return "Stop aman selesai. Lease dilepas."
        }
        val remainingSeconds = ((manualStopProtectionStatusWindowMs - (now - startedAt).inWholeMilliseconds).coerceAtLeast(0L) / 1_000L)
        return if (remainingSeconds > 0L) {
            "Stop aman aktif. Entry baru diblokir, posisi/order sedang dirapikan (${remainingSeconds}s)."
        } else {
            "Stop aman aktif. Bot tetap pegang lease sampai posisi dan order benar-benar bersih."
        }
    }

    private fun buildSyntheticTrialPosition(
        now: Instant,
        targetPair: String,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        cycle: StrategyCycleResult,
    ): ManagedPosition? {
        val pairId = PairId(targetPair.lowercase())
        val quote = marketQuotes.firstOrNull { it.pairId == pairId } ?: return null
        val baseAsset = pairId.value.substringBefore('_')
        val balance = balances.firstOrNull { it.asset.equals(baseAsset, ignoreCase = true) } ?: return null
        val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
        if (quantity <= 0.0) return null
        val quoteAsset = pairId.value.substringAfter('_', "idr")
        val quoteAssetPrice = quoteAssetPriceIdr(quoteAsset, marketQuotes) ?: return null
        val bidPrice = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: return null
        val currentValue = quantity * bidPrice * quoteAssetPrice
        if (currentValue <= 0.0) return null
        val rankedPair = cycle.rankedPairs.firstOrNull { it.pairId == pairId }
        return ManagedPosition(
            pairId = pairId,
            quantity = DecimalValue.fromDouble(quantity),
            averageEntryPrice = DecimalValue.fromDouble(bidPrice),
            currentBidPrice = quote.bestBid,
            currentValueIdr = DecimalValue.fromDouble(currentValue),
            unrealizedPnlIdr = DecimalValue.Zero,
            unrealizedPnlPct = 0.0,
            breakEvenPrice = DecimalValue.fromDouble(bidPrice * 1.0066),
            takeProfitPrice = quote.bestAsk,
            stopPrice = quote.bestBid,
            openedAt = now,
            updatedAt = now,
            horizon = rankedPair?.preferredHorizon ?: com.kibot.shared.models.TradingHorizon.TACTICAL,
            setupType = rankedPair?.preferredHorizon
                ?.let { com.kibot.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK }
                ?: com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
            pairTier = rankedPair?.pairTier ?: com.kibot.shared.models.PairTier.TIER_B,
            speculativePocket = rankedPair?.speculativePocket == true,
            expectedHoldingHours = 1.0,
        )
    }

    private fun buildAmbientLiveLogEntry(
        now: Instant,
        cycle: StrategyCycleResult,
        managedPositions: List<ManagedPosition>,
        dailyRisk: com.kibot.shared.models.DailyRiskSnapshot?,
        scanUniverseCount: Int,
    ): LiveLogEntry? {
        val candidates = cycle.deploymentPlan.candidates.map { it.pairId.value.lowercase() }.distinct()
        val selectedSignal = cycle.selectedSignal
        val executionPlan = cycle.executionPlan
        val profitToday = dailyRisk?.let {
            it.realizedPnlIdr.toDoubleOrZero() + it.unrealizedPnlIdr.toDoubleOrZero()
        } ?: 0.0
        val message = when {
            executionPlan != null &&
                cycle.modeSnapshot.tradingAllowed &&
                cycle.riskDecision.allowNewEntries -> {
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "SETUP",
                    message = "Siap entry ${executionPlan.signal.pairId.value.lowercase()}. Harga dan fill lagi dicek cepat.",
                )
            }

            selectedSignal != null && !lastEntryGateReason.isNullOrBlank() -> {
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "GATE",
                    message = "Bidik ${selectedSignal.pairId.value.lowercase()}. ${lastEntryGateReason.orEmpty()}",
                )
            }

            selectedSignal != null -> {
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "TARGET",
                    message = "Bidik ${selectedSignal.pairId.value.lowercase()}. Momentum sudah hidup, tinggal lolos gate akhir.",
                )
            }

            managedPositions.isNotEmpty() && candidates.isNotEmpty() -> {
                val held = managedPositions.take(2).joinToString(" • ") { it.pairId.value.lowercase() }
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "ROTASI",
                    message = "Pegang $held sambil ngawasin rotasi ke ${candidates.first()}.",
                )
            }

            candidates.isNotEmpty() -> {
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "SCAN",
                    message = "Radar cepat ${candidates.take(5).joinToString(" • ")}.",
                )
            }

            profitToday <= -10.0 -> {
                val lead = candidates.firstOrNull() ?: selectedSignal?.pairId?.value?.lowercase() ?: managedPositions.firstOrNull()?.pairId?.value?.lowercase() ?: "radar"
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "LOSS",
                    message = "Hari ini loss ${formatSignedIdr(profitToday)}. Bot ngerem sambil pantau $lead.",
                )
            }

            profitToday >= 10.0 -> {
                val lead = candidates.firstOrNull() ?: selectedSignal?.pairId?.value?.lowercase() ?: managedPositions.firstOrNull()?.pairId?.value?.lowercase() ?: "radar"
                LiveLogEntry(
                    timestampEpochMs = now.toEpochMilliseconds(),
                    category = "PROFIT",
                    message = "Hari ini ${formatSignedIdr(profitToday)}. Bot jaga hasil sambil pantau $lead.",
                )
            }

            else -> null
        }
        return message
    }

    private fun deriveDailyRiskSnapshot(
        previous: com.kibot.shared.models.DailyRiskSnapshot?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.DailyRiskSnapshot? {
        if (balances.isEmpty() || marketQuotes.isEmpty()) return previous
        val currentEquity = balances.sumOf { balance ->
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            when {
                quantity <= 0.0 -> 0.0
                balance.asset.equals("idr", ignoreCase = true) -> quantity
                else -> quantity * (quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0)
            }
        }
        if (currentEquity <= 0.0) return previous

        val openingEquity = previous?.openingEquityIdr?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: currentEquity
        val totalPnl = currentEquity - openingEquity
        val hasTrackedNonIdrHolding = balances.any { balance ->
            if (balance.asset.equals("idr", ignoreCase = true)) return@any false
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (quantity <= 0.0) return@any false
            val value = quantity * (quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0)
            value >= 1_000.0
        }
        val realizedPnl = if (hasTrackedNonIdrHolding) 0.0 else totalPnl
        val unrealizedPnl = totalPnl - realizedPnl
        val highWatermark = max(
            previous?.highWatermarkEquityIdr?.toDoubleOrZero() ?: openingEquity,
            currentEquity,
        )
        val profitableRange = (highWatermark - openingEquity).coerceAtLeast(0.0)
        val givebackPct = when {
            profitableRange <= 0.0 || currentEquity >= highWatermark -> 0.0
            else -> ((highWatermark - currentEquity) / profitableRange).coerceIn(0.0, 1.0)
        }
        val hardLimitPct = previous?.hardDailyLossLimitPct ?: 0.25
        val drawdownPct = if (openingEquity > 0.0 && currentEquity < openingEquity) {
            ((openingEquity - currentEquity) / openingEquity).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue.fromDouble(openingEquity),
            currentEquityIdr = DecimalValue.fromDouble(currentEquity),
            realizedPnlIdr = DecimalValue.fromDouble(realizedPnl),
            unrealizedPnlIdr = DecimalValue.fromDouble(unrealizedPnl),
            drawdownPct = drawdownPct,
            hardDailyLossLimitPct = hardLimitPct,
            hardStopTriggered = previous?.hardStopTriggered == true || drawdownPct >= hardLimitPct,
            rebasePending = previous?.rebasePending == true,
            riskLadderLevel = previous?.riskLadderLevel ?: com.kibot.shared.models.RiskLadderLevel.NORMAL,
            weeklyDrawdownPct = previous?.weeklyDrawdownPct ?: 0.0,
            lossStreakCount = previous?.lossStreakCount ?: 0,
            performanceDecayDetected = previous?.performanceDecayDetected == true,
            highWatermarkEquityIdr = DecimalValue.fromDouble(highWatermark),
            givebackPct = givebackPct,
            profitProtectionStatus = previous?.profitProtectionStatus ?: com.kibot.shared.models.ProfitProtectionStatus.INACTIVE,
        )
    }

    private fun relevantFillPairs(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        persistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: StrategyCycleResult?,
    ): List<com.kibot.shared.models.PairId> {
        val quotePairs = marketQuotes.map { it.pairId }.toSet()
        return buildSet {
            openOrders.mapTo(this) { it.pairId }
            persistedOrders.mapTo(this) { it.pairId }
            cycle?.selectedSignal?.pairId?.let(::add)
            cycle?.deploymentPlan?.candidates?.take(4)?.mapTo(this) { it.pairId }
            balances
                .filterNot { it.asset.equals("idr", ignoreCase = true) }
                .forEach { balance ->
                    listOf("idr", "usdt", "btc", "eth")
                        .asSequence()
                        .map { quoteAsset -> com.kibot.shared.models.PairId("${balance.asset.lowercase()}_$quoteAsset") }
                        .firstOrNull { it in quotePairs }
                        ?.let(::add)
                }
        }.take(10)
    }

    private fun mergeRecentOrders(
        base: List<com.kibot.shared.models.OrderSnapshot>,
        updates: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        if (updates.isEmpty()) return base
        val merged = linkedMapOf<String, com.kibot.shared.models.OrderSnapshot>()
        (base + updates)
            .sortedByDescending { it.updatedAt }
            .forEach { merged[it.clientOrderId.value] = it }
        return merged.values.toList()
    }

    private fun quoteAssetPriceIdr(asset: String, quotes: List<com.kibot.shared.models.MarketQuote>): Double? {
        if (asset.equals("idr", ignoreCase = true)) return 1.0
        val direct = quotes.firstOrNull { it.pairId.value.equals("${asset.lowercase()}_idr", ignoreCase = true) }
        if (direct != null) return direct.midPrice.toDoubleOrZero()
        val usdtAsset = quotes.firstOrNull { it.pairId.value.equals("${asset.lowercase()}_usdt", ignoreCase = true) }
        val usdtIdr = quotes.firstOrNull { it.pairId.value.equals("usdt_idr", ignoreCase = true) }
        if (usdtAsset != null && usdtIdr != null) {
            return usdtAsset.midPrice.toDoubleOrZero() * usdtIdr.midPrice.toDoubleOrZero()
        }
        return null
    }

    private fun formatIdr(value: Double): String {
        val formatter = java.text.NumberFormat.getCurrencyInstance(java.util.Locale("id", "ID")).apply {
            maximumFractionDigits = 0
        }
        return formatter.format(value)
    }

    private fun formatSignedIdr(value: Double): String {
        if (kotlin.math.abs(value) < 0.5) return "+${formatIdr(0.0)}"
        return (if (value >= 0.0) "+" else "-") + formatIdr(kotlin.math.abs(value))
    }

    private fun formatSignedPercentFromPct(valuePct: Double): String {
        val prefix = if (valuePct >= 0.0) "+" else "-"
        return "$prefix${formatDecimal(kotlin.math.abs(valuePct), 1)}%"
    }

    private fun formatDecimal(value: Double, digits: Int): String = "%.${digits}f".format(java.util.Locale.US, value)

    private fun formatAssetAmount(value: Double, asset: String): String {
        val formatted = when {
            value >= 100 -> "%,.0f".format(java.util.Locale.US, value)
            value >= 1 -> "%,.4f".format(java.util.Locale.US, value)
            else -> "%,.8f".format(java.util.Locale.US, value)
        }.trimEnd('0').trimEnd('.')
        return "$formatted ${asset.uppercase()}"
    }

    private fun estimatePortfolioValue(balances: List<BalanceSnapshot>): DecimalValue {
        val total = balances.sumOf { balance ->
            balance.totalValueInIdr?.toDoubleOrZero() ?: (balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero())
        }
        return DecimalValue.fromDouble(total.coerceAtLeast(0.0))
    }

    private fun jakartaNowDate(now: Instant) = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date

    private companion object {
        private const val staleEntryOrderMaxAgeMinutes = 6.0
        private const val staleEntryOrderPairFlipGraceMinutes = 2.5
        private const val staleEntryOrderMaxDriftPct = 0.70
        private const val staleExitOrderMaxAgeMinutes = 4.5
        private const val staleExitOrderMaxDriftPct = 0.55
        private const val staleExitRepriceLossFloorPct = -0.35
        private const val makerFirstMaxLatencyMs = 360L
        private const val aggressiveLimitFallbackLatencyMs = 900L
        private const val aggressiveLimitFallbackHardStopMs = 1_350L
        private const val entryBlockLatencyMs = 1100L
        private const val manualStopProtectionStatusWindowMs = 90_000L
        private const val executionPolicyLogCooldownMinutes = 2L
        private const val trialExitMaxNotionalIdr = 60_000.0
        private val activeOrderStatuses = setOf(
            com.kibot.shared.models.OrderStatus.CREATED,
            com.kibot.shared.models.OrderStatus.SUBMITTING,
            com.kibot.shared.models.OrderStatus.OPEN,
            com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED,
            com.kibot.shared.models.OrderStatus.CANCEL_REQUESTED,
            com.kibot.shared.models.OrderStatus.UNKNOWN,
        )
    }
}

private data class EntryRoutingDecision(
    val executionPlan: com.kibot.shared.models.ExecutionPlan?,
    val message: String? = null,
    val blockedReason: String? = null,
)

private fun EngineLeaseSnapshot?.isHeldBy(deviceId: DeviceId, now: Instant): Boolean {
    return this?.currentHolder == deviceId &&
        this.state == com.kibot.shared.models.LeaseState.HELD &&
        this.expiresAt > now &&
        !this.conflictDetected
}
