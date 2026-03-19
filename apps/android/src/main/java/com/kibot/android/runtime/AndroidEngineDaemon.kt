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
import com.kibot.core.ReconciliationService
import com.kibot.core.RiskConfig
import com.kibot.core.SituationalLearningEngine
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong

data class AndroidEngineTickResult(
    val effectiveState: BotEffectiveState,
    val statusMessage: String,
    val currentPair: String?,
    val operatingMode: String,
    val liveStatusSnapshot: LiveStatusSnapshot? = null,
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
            feedLatencyMs = exchangePingMs,
            marketFeedHealthy = exchangeReachable,
        )
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
            feedLatencyMs = exchangePingMs,
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
        val strategyCycle = if (resolvedMarketQuotes.isNotEmpty()) {
            strategyOrchestrator.analyze(
                botId = controlPlaneConfig.botId,
                balances = resolvedBalances,
                openOrders = resolvedOpenOrders,
                dailyRisk = dailyRisk,
                health = finalHealth,
                marketQuotes = resolvedMarketQuotes,
                pairSupportHints = aiSupportHints,
                weeklySummary = weeklyReview,
            )
        } else {
            null
        }

        var runtimeBotState = initialBotState
        var runtimeLease = initialLease
        var effectiveWeeklyReview = weeklyReview
        if (isMaster && runtimeLease != null && strategyCycle != null) {
            effectiveWeeklyReview = maybePublishWeeklyLearningSummary(
                now = now,
                cycle = strategyCycle,
                marketQuotes = resolvedMarketQuotes,
                currentWeeklyReview = weeklyReview,
            )
            publishAnalysisIfNeeded(now, runtimeLease, strategyCycle)
            maybeExecuteLiveOrder(now, runtimeLease, strategyCycle, effectiveWeeklyReview)
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

        return@coroutineScope AndroidEngineTickResult(
            effectiveState = runtimeEffectiveState,
            statusMessage = when {
                runtimeBotState.effectiveState == BotEffectiveState.SAFE_MODE || runtimeLease?.conflictDetected == true ->
                    runtimeBotState.safeModeReason ?: "SAFE_MODE aktif."
                healthDecision.reasons.isNotEmpty() -> healthDecision.reasons.joinToString(" ")
                strategyCycle != null -> strategyCycle.summary.joinToString(" ")
                runtimeLease.isHeldBy(config.device.deviceId, now) -> "Android sedang memegang lease master."
                else -> "Android standby memonitor status bot."
            },
            currentPair = visiblePair,
            operatingMode = strategyCycle?.modeSnapshot?.mode?.name ?: runtimeBotState.operatingMode.name,
            liveStatusSnapshot = buildLiveStatusSnapshot(
                now = now,
                currentPair = visiblePair,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                dailyRisk = dailyRisk,
                internetPingMs = displayPingMs,
                scanUniverseCount = resolvedMarketQuotes.size,
                radarPairs = strategyCycle?.deploymentPlan?.candidates
                    ?.map { it.pairId.value }
                    ?.distinct()
                    ?.take(4)
                    .orEmpty(),
            ),
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
        val recentPersistedOrders = controlPlane.fetchRecentOrders(controlPlaneConfig.botId, limit = 120)
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

    private suspend fun maybeExecuteLiveOrder(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
    ) {
        if (!config.enableLiveExecution) return
        val executionPlan = cycle.executionPlan ?: return
        if (!cycle.modeSnapshot.tradingAllowed || !cycle.riskDecision.allowNewEntries) return
        val rolloutDecision = liveRolloutGuard.evaluate(cycle, weeklyReview)
        if (!rolloutDecision.allowed) {
            appendAuditLog(
                level = LogLevel.INFO,
                category = "ROLLOUT_GUARD",
                message = rolloutDecision.reason,
            )
            return
        }

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

    private suspend fun maybePublishWeeklyLearningSummary(
        now: Instant,
        cycle: StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        currentWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
    ): com.kibot.shared.models.WeeklyLearningSummary? {
        val shouldPublish = lastWeeklyReviewPublishedAt == null ||
            (now - lastWeeklyReviewPublishedAt!!).inWholeHours >= 6
        if (!shouldPublish) return currentWeeklyReview

        val recentOrders = controlPlane.fetchRecentOrders(controlPlaneConfig.botId, limit = 120)
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

    private fun buildLiveStatusSnapshot(
        now: Instant,
        currentPair: String?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        dailyRisk: com.kibot.shared.models.DailyRiskSnapshot?,
        internetPingMs: Long?,
        scanUniverseCount: Int,
        radarPairs: List<String>,
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
        val holdings = balances
            .asSequence()
            .filterNot { it.asset.equals("idr", ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val value = quantity * (quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0)
                if (value < 1_000.0) return@mapNotNull null
                LiveHoldingUi(
                    asset = balance.asset.uppercase(),
                    amount = formatAssetAmount(quantity, balance.asset),
                    valueIdr = formatIdr(value),
                )
            }
            .sortedByDescending { it.valueIdr.filter(Char::isDigit).toLongOrNull() ?: 0L }
            .take(4)
            .toList()
        return LiveStatusSnapshot(
            updatedAtEpochMs = now.toEpochMilliseconds(),
            activePair = currentPair ?: "-",
            totalEquityIdr = formatIdr(equity),
            pnlTodayIdr = formatSignedIdr(pnl),
            internetPingMs = internetPingMs,
            scanUniverseCount = scanUniverseCount,
            radarPairs = radarPairs,
            holdings = holdings,
        )
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
}

private fun EngineLeaseSnapshot?.isHeldBy(deviceId: DeviceId, now: Instant): Boolean {
    return this?.currentHolder == deviceId &&
        this.state == com.kibot.shared.models.LeaseState.HELD &&
        this.expiresAt > now &&
        !this.conflictDetected
}
