package com.kibot.macengine.runtime

import com.kibot.aisupport.GeminiSupportCoordinator
import com.kibot.core.ControlPlaneGateway
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
import com.kibot.core.StrategyOrchestrator
import com.kibot.macengine.config.MacRuntimeConfig
import com.kibot.macengine.state.MacStateRepository
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
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DeviceRole
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.EngineHeartbeatSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.ReconciliationReport
import com.kibot.shared.models.ReconciliationState
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.SyncHealth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class MacEngineDaemon(
    private val repository: MacStateRepository,
    private val controlPlane: ControlPlaneGateway,
    private val exchange: ExchangeGateway,
    private val config: MacRuntimeConfig,
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
    private val logger = LoggerFactory.getLogger(javaClass)
    private var registered = false
    private var lastAnalysisPublishedAt: Instant? = null
    private var lastStrategyMetricsPublishedAt: Instant? = null
    private var lastCandidateSignature: String? = null
    private var lastLearningSignature: String? = null
    private var lastLearningPublishedAt: Instant? = null
    private var lastWeeklyReviewPublishedAt: Instant? = null
    private var releaseCooldownUntil: Instant? = null

    suspend fun run() {
        logger.info("Mac engine daemon loop started.")
        while (true) {
            try {
                syncOnce()
            } catch (error: Throwable) {
                logger.error("Mac daemon sync failed.", error)
                repository.noteStatus("Daemon sync failed: ${error.message ?: "unknown error"}")
            }
            delay(config.pollIntervalMillis)
        }
    }

    suspend fun syncOnce() = coroutineScope {
        ensureRegistered()

        val now = Clock.System.now()
        val botState = controlPlane.fetchBotState(config.controlPlane.botId) ?: error("Bot state not found in Supabase.")
        val lease = controlPlane.fetchLease(config.controlPlane.botId)
        val devices = controlPlane.fetchDevices(config.controlPlane.botId)
        val dailyRisk = controlPlane.fetchDailyRisk(config.controlPlane.botId, jakartaNowDate(now))
        val commands = controlPlane.fetchPendingCommands(config.controlPlane.botId, config.device.deviceId)
        val weeklyReview = runCatching {
            controlPlane.fetchLatestWeeklyLearningSummary(config.controlPlane.botId)
        }.getOrNull()

        val exchangeReachable = runCatching { exchange.ping() }.getOrElse { false }
        val healthWarnings = mutableListOf<String>()
        if (!exchangeReachable) {
            healthWarnings += "Exchange unreachable or credentials not configured."
        }
        if (dailyRisk?.hardStopTriggered == true) {
            healthWarnings += "Daily hard stop is active."
        }

        var leaseAfterCommands = lease
        var botStateAfterCommands = botState

        commands.forEach { command ->
            val result = handleCommand(command, leaseAfterCommands, botStateAfterCommands)
            if (result != null) {
                leaseAfterCommands = controlPlane.fetchLease(config.controlPlane.botId)
                botStateAfterCommands = controlPlane.fetchBotState(config.controlPlane.botId) ?: botStateAfterCommands
                healthWarnings += result
            }
        }

        val localHealth = buildLocalHealth(exchangeReachable, healthWarnings)
        val masterBeforeTakeover = leaseAfterCommands.isHeldBy(config.device.deviceId, now)

        if (
            botStateAfterCommands.desiredState == BotDesiredState.ON &&
            !masterBeforeTakeover &&
            !shouldYieldToPrimary(botStateAfterCommands, now) &&
            !isInReleaseCooldown(now)
        ) {
            maybeTakeOver(
                now = now,
                botState = botStateAfterCommands,
                lease = leaseAfterCommands,
                localHealth = localHealth,
            )
        } else if (botStateAfterCommands.desiredState == BotDesiredState.OFF && masterBeforeTakeover) {
            controlPlane.releaseLease(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                term = leaseAfterCommands?.term?.value ?: 0L,
                reason = "Bot desired state is OFF.",
            )
            appendAuditLog(LogLevel.INFO, "LEASE", "Mac released master lease because desired state is OFF.")
        }

        val initialBotState = controlPlane.fetchBotState(config.controlPlane.botId) ?: botStateAfterCommands
        val initialLease = controlPlane.fetchLease(config.controlPlane.botId)
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
        if (exchangeReachable && resolvedMarketQuotes.isEmpty()) {
            healthWarnings += "Market quote feed kosong."
        }
        val finalHealth = buildLocalHealth(exchangeReachable, healthWarnings)
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
            if (evaluation.usedNetwork) {
                appendAuditLog(LogLevel.INFO, "AI_SUPPORT", "REQUEST")
            }
            evaluation.hints
        }.orEmpty()
        val strategyCycle = if (resolvedMarketQuotes.isNotEmpty()) {
            strategyOrchestrator.analyze(
                botId = config.controlPlane.botId,
                balances = resolvedBalances,
                openOrders = resolvedOpenOrders,
                dailyRisk = dailyRisk,
                health = finalHealth,
                marketQuotes = resolvedMarketQuotes,
                pairSupportHints = aiSupportHints,
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
            publishAnalysisIfNeeded(
                now = now,
                lease = runtimeLease,
                cycle = strategyCycle,
            )
            maybeExecuteLiveOrder(
                now = now,
                lease = runtimeLease,
                cycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
            )
            publishLearningSignalsIfNeeded(
                now = now,
                cycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
                aiBlockedReason = aiSupportEvaluation?.blockedReason,
                aiUsedNetwork = aiSupportEvaluation?.usedNetwork == true,
            )
            runtimeBotState = controlPlane.fetchBotState(config.controlPlane.botId) ?: runtimeBotState
            runtimeLease = controlPlane.fetchLease(config.controlPlane.botId)
        }

        controlPlane.appendHeartbeat(
            EngineHeartbeatSnapshot(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                observedAt = now,
                term = runtimeLease?.term,
                isMaster = runtimeLease.isHeldBy(config.device.deviceId, now),
                desiredState = runtimeBotState.desiredState,
                effectiveState = deriveEffectiveState(runtimeBotState, runtimeLease, healthDecision),
                health = finalHealth,
            ),
        )

        repository.applyRuntimeState(
            buildDashboardState(
                now = now,
                botState = runtimeBotState,
                lease = runtimeLease,
                devices = devices,
                localHealth = finalHealth,
                dailyRisk = dailyRisk,
                balances = resolvedBalances,
                strategyCycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
                healthDecisionSummary = if (healthDecision.reasons.isEmpty()) {
                    if (runtimeLease.isHeldBy(config.device.deviceId, now)) {
                        strategyCycle?.summary?.joinToString(" ") ?: "Master healthy. Lease fenced and heartbeat current."
                    } else {
                        strategyCycle?.summary?.firstOrNull()
                            ?: "Standby healthy, takeover ready when lease expires."
                    }
                } else {
                    healthDecision.reasons.joinToString(" ")
                },
            ),
        )
    }

    private suspend fun ensureRegistered() {
        if (registered) return
        controlPlane.registerDevice(config.device)
        registered = true
        repository.noteStatus("Mac engine registered with control-plane.")
        appendAuditLog(LogLevel.INFO, "AUTH", "Mac device registered with control-plane.")
    }

    private suspend fun handleCommand(
        command: CommandEnvelope,
        lease: EngineLeaseSnapshot?,
        botState: BotStateSnapshot,
    ): String? {
        return when (command.commandType) {
            CommandType.REQUEST_TAKEOVER -> {
                if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                    enterReleaseCooldown()
                    controlPlane.releaseLease(
                        botId = config.controlPlane.botId,
                        deviceId = config.device.deviceId,
                        term = lease?.term?.value ?: 0L,
                        reason = "Graceful takeover requested by ${command.createdBy.value}.",
                    )
                    controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                    appendAuditLog(LogLevel.WARN, "LEASE", "Mac released control after takeover request from ${command.createdBy.value}.")
                    "Graceful takeover request processed."
                } else {
                    controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                    "Takeover request acknowledged; Mac is not the active lease holder."
                }
            }

            CommandType.FORCE_SAFE_TAKEOVER -> {
                val outcome = maybeTakeOver(
                    now = Clock.System.now(),
                    botState = botState,
                    lease = lease,
                    localHealth = buildLocalHealth(
                        exchangeReachable = runCatching { exchange.ping() }.getOrDefault(false),
                        warnings = listOf("Force safe takeover requested."),
                    ),
                )
                controlPlane.updateCommandStatus(
                    command.commandId,
                    if (outcome) CommandStatus.SUCCEEDED else CommandStatus.FAILED,
                )
                if (outcome) {
                    "Force safe takeover succeeded."
                } else {
                    "Force safe takeover blocked by lease or reconciliation."
                }
            }

            CommandType.RELEASE_CONTROL -> {
                if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                    enterReleaseCooldown()
                    controlPlane.releaseLease(
                        botId = config.controlPlane.botId,
                        deviceId = config.device.deviceId,
                        term = lease?.term?.value ?: 0L,
                        reason = "Release control requested locally.",
                    )
                    appendAuditLog(LogLevel.INFO, "LEASE", "Mac released control on command.")
                }
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Release control command handled."
            }

            CommandType.SYNC_NOW -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Manual sync command handled."
            }

            CommandType.START_BOT -> {
                controlPlane.setDesiredState(config.controlPlane.botId, BotDesiredState.ON)
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Bot desired state switched to ON."
            }

            CommandType.STOP_BOT -> {
                controlPlane.setDesiredState(config.controlPlane.botId, BotDesiredState.OFF)
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Bot desired state switched to OFF."
            }

            CommandType.FORCE_STANDBY,
            CommandType.RESUME_FROM_SAFE_MODE,
            -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Command ${command.commandType.name} acknowledged."
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
        val fills = openOrders
            .map { it.pairId }
            .distinct()
            .flatMap { pairId ->
                runCatching { exchange.fetchRecentFills(pairId, limit = 20) }.getOrDefault(emptyList())
            }

        val persistedOrders = controlPlane.fetchOpenPersistedOrders(config.controlPlane.botId)
        val reconciliation = reconciliationService.reconcile(
            portfolio = PortfolioSnapshot(
                botId = config.controlPlane.botId,
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
                    botId = config.controlPlane.botId,
                    reason = evaluation.reasons.joinToString(" "),
                )
                appendAuditLog(
                    level = LogLevel.ERROR,
                    category = "FAILOVER",
                    message = "Fail-safe takeover block triggered: ${evaluation.reasons.joinToString(" ")}",
                )
            }
            return false
        }

        val acquiredLease = controlPlane.acquireLease(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            ttlSeconds = config.leaseTtlSeconds,
        )

        appendAuditLog(
            level = LogLevel.WARN,
            category = "FAILOVER",
            message = "Mac acquired master lease term ${acquiredLease.term.value} after safe reconciliation.",
        )
        return true
    }

    private fun shouldYieldToPrimary(
        botState: BotStateSnapshot,
        now: Instant,
    ): Boolean {
        if (config.device.role != DeviceRole.STANDBY) return false
        val activeDeviceId = botState.activeDeviceId ?: return false
        if (activeDeviceId == config.device.deviceId) return false
        val lastHeartbeatAt = botState.lastHeartbeatAt ?: return false
        val heartbeatAgeMs = now.toEpochMilliseconds() - lastHeartbeatAt.toEpochMilliseconds()
        val graceWindowMs = (config.leaseTtlSeconds * 1_000L) + 8_000L
        return heartbeatAgeMs in 0..graceWindowMs && botState.syncHealth != SyncHealth.BROKEN
    }

    private fun isInReleaseCooldown(now: Instant): Boolean {
        val until = releaseCooldownUntil ?: return false
        return now < until
    }

    private fun enterReleaseCooldown() {
        releaseCooldownUntil = Clock.System.now().plus((config.leaseTtlSeconds + 12).seconds)
    }

    private fun buildLocalHealth(exchangeReachable: Boolean, warnings: List<String>): EngineHealthSnapshot {
        val status = when {
            !exchangeReachable -> HealthStatus.CRITICAL
            warnings.isNotEmpty() -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }
        val syncHealth = when {
            status == HealthStatus.CRITICAL -> SyncHealth.BROKEN
            status == HealthStatus.WARNING -> SyncHealth.DEGRADED
            else -> SyncHealth.HEALTHY
        }
        return EngineHealthSnapshot(
            status = status,
            syncHealth = syncHealth,
            websocketHealthy = exchangeReachable,
            exchangeReachable = exchangeReachable,
            supabaseReachable = true,
            fillQualityScore = if (warnings.any { it.contains("fill", ignoreCase = true) }) 0.35 else 0.75,
            anomalyCount = warnings.size,
            lastError = warnings.firstOrNull(),
            warnings = warnings.distinct(),
        )
    }

    private suspend fun publishAnalysisIfNeeded(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
    ) {
        val candidateSignature = cycle.deploymentPlan.candidates.joinToString("|") { "${it.pairId.value}:${"%.2f".format(it.rankingScore)}" }
        val shouldPublishAnalysis = lastAnalysisPublishedAt == null ||
            (now - lastAnalysisPublishedAt!!).inWholeMilliseconds >= config.analysisPublishIntervalMillis ||
            candidateSignature != lastCandidateSignature
        val shouldPublishMetrics = lastStrategyMetricsPublishedAt == null ||
            (now - lastStrategyMetricsPublishedAt!!).inWholeMilliseconds >= config.strategyMetricsPublishIntervalMillis ||
            candidateSignature != lastCandidateSignature

        if (shouldPublishAnalysis) {
            controlPlane.publishRuntimeIntelligence(
                RuntimeIntelligenceUpdate(
                    botId = config.controlPlane.botId,
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
            controlPlane.appendStrategyMetrics(
                botId = config.controlPlane.botId,
                metrics = cycle.rankedPairs.take(5),
            )
            lastStrategyMetricsPublishedAt = now
        }
    }

    private suspend fun maybeExecuteLiveOrder(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
    ) {
        if (!config.enableLiveExecution) return
        val executionPlan = cycle.executionPlan ?: return
        if (!cycle.modeSnapshot.tradingAllowed) return
        if (cycle.riskDecision.allowNewEntries.not()) return
        val rolloutDecision = liveRolloutGuard.evaluate(cycle, weeklyReview)
        if (!rolloutDecision.allowed) {
            appendAuditLog(LogLevel.INFO, "ROLLOUT_GUARD", rolloutDecision.reason)
            return
        }

        val persistedOpenOrders = controlPlane.fetchOpenPersistedOrders(config.controlPlane.botId)
        if (persistedOpenOrders.isNotEmpty()) return

        val result = liveExecutionCoordinator.submitEntry(
            botId = config.controlPlane.botId,
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
            logger.info(
                "Live order submitted on {} pair={} clientOrderId={} term={}",
                config.device.displayName,
                executionPlan.signal.pairId.value,
                result.clientOrderId?.value,
                lease.term.value,
            )
            lastAnalysisPublishedAt = now
        } else if (result.failSafeTriggered) {
            logger.error("Live order submit became ambiguous, safe mode triggered.")
        }
    }

    private suspend fun maybePublishWeeklyLearningSummary(
        now: Instant,
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        currentWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
    ): com.kibot.shared.models.WeeklyLearningSummary? {
        val shouldPublish = lastWeeklyReviewPublishedAt == null ||
            (now - lastWeeklyReviewPublishedAt!!).inWholeHours >= 6
        if (!shouldPublish) return currentWeeklyReview

        val recentOrders = controlPlane.fetchRecentOrders(config.controlPlane.botId, limit = 120)
        val summary = liveLearningReviewBuilder.build(
            botId = config.controlPlane.botId,
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
        cycle: com.kibot.core.StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        aiBlockedReason: String?,
        aiUsedNetwork: Boolean,
    ) {
        val decision = situationalLearningEngine.evaluate(
            botId = config.controlPlane.botId,
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

    private suspend fun appendAuditLog(level: LogLevel, category: String, message: String) {
        runCatching {
            controlPlane.appendLog(
                botId = config.controlPlane.botId,
                record = AuditLogRecord(
                    recordedAt = Clock.System.now(),
                    level = level,
                    category = category,
                    deviceId = config.device.deviceId,
                    term = controlPlane.fetchLease(config.controlPlane.botId)?.term,
                    message = message,
                ),
            )
        }.onFailure { logger.warn("Failed to append audit log: {}", it.message) }
    }

    private fun deriveEffectiveState(
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        healthDecision: com.kibot.core.EntryHealthDecision,
    ): BotEffectiveState {
        if (botState.desiredState == BotDesiredState.OFF) return BotEffectiveState.STOPPED
        if (lease?.conflictDetected == true) return BotEffectiveState.SAFE_MODE
        return if (healthDecision.tradingAllowed) {
            if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                BotEffectiveState.RUNNING
            } else {
                BotEffectiveState.STARTING
            }
        } else {
            BotEffectiveState.DEGRADED
        }
    }

    private fun buildDashboardState(
        now: Instant,
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        devices: List<DeviceDescriptor>,
        localHealth: EngineHealthSnapshot,
        dailyRisk: DailyRiskSnapshot?,
        balances: List<BalanceSnapshot>,
        strategyCycle: com.kibot.core.StrategyCycleResult?,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        healthDecisionSummary: String,
    ): com.kibot.macengine.state.MacDashboardState {
        val activeDevice = devices.firstOrNull { it.deviceId == (lease?.currentHolder ?: botState.activeDeviceId) }
        val standbyDevice = devices.firstOrNull { it.deviceId != activeDevice?.deviceId && !it.isRevoked }
        val heartbeatInstant = lease?.lastHeartbeatAt ?: botState.lastHeartbeatAt
        val portfolioValue = estimatePortfolioValue(balances).toDoubleOrZero()
            .takeIf { it > 0.0 }
            ?: dailyRisk?.currentEquityIdr?.toDoubleOrZero()
            ?: 0.0
        val pnlToday = dailyRisk?.let {
            it.realizedPnlIdr.toDoubleOrZero() + it.unrealizedPnlIdr.toDoubleOrZero()
        } ?: 0.0

        return com.kibot.macengine.state.MacDashboardState(
            isBotRunning = botState.desiredState == BotDesiredState.ON,
            effectiveState = botState.effectiveState,
            operatingMode = strategyCycle?.modeSnapshot?.mode?.name ?: botState.operatingMode.name,
            edgeConfidence = strategyCycle?.modeSnapshot?.edgeConfidence?.name ?: botState.edgeConfidence.name,
            marketRegime = strategyCycle?.marketSnapshot?.regime?.name ?: botState.marketRegime.name,
            topCandidate = strategyCycle?.topCandidate?.value ?: botState.activeCandidatePairs.firstOrNull()?.value ?: "-",
            liveExecutionEnabled = config.enableLiveExecution,
            portfolioValueIdr = formatIdr(portfolioValue),
            pnlTodayIdr = formatSignedIdr(pnlToday),
            syncPathLabel = if (config.bindHost == "127.0.0.1" || config.bindHost == "localhost") "Supabase" else "Supabase + LAN",
            activeEngine = activeDevice?.displayName ?: "None",
            standbyEngine = standbyDevice?.displayName ?: "Waiting",
            syncHealth = localHealth.syncHealth.name,
            leaseTerm = lease?.term?.value ?: botState.currentTerm.value,
            healthSummary = if (botState.effectiveState == BotEffectiveState.SAFE_MODE || lease?.conflictDetected == true) {
                botState.safeModeReason ?: "Safe mode active. Manual review is required."
            } else {
                healthDecisionSummary
            },
            weeklyLearningSummary = weeklyReview?.let {
                "Week ${it.periodStart} - ${it.periodEnd} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
            } ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            lastHeartbeatLabel = heartbeatInstant?.let { formatAge(now, it) } ?: "Never",
            lastUpdatedLabel = formatUpdatedLabel(now),
            statusMessage = when {
                botState.effectiveState == BotEffectiveState.SAFE_MODE || lease?.conflictDetected == true ->
                    "Safe mode active. Resolve exchange/control-plane ambiguity before resuming."
                localHealth.status == HealthStatus.CRITICAL -> "Mac cannot trade safely yet. ${localHealth.warnings.firstOrNull().orEmpty()}".trim()
                lease.isHeldBy(config.device.deviceId, now) -> "Mac currently holds the master lease."
                else -> "Mac standby is monitoring lease and waiting for a safe takeover window."
            },
            lastUpdatedEpochMs = now.toEpochMilliseconds(),
        )
    }

    private fun estimatePortfolioValue(balances: List<BalanceSnapshot>): DecimalValue {
        val total = balances.sumOf { balance ->
            balance.totalValueInIdr?.toDoubleOrZero()
                ?: (balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero())
        }
        return DecimalValue.fromDouble(total.coerceAtLeast(0.0))
    }

    private fun jakartaNowDate(now: Instant): kotlinx.datetime.LocalDate {
        return now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
    }

    private fun formatAge(now: Instant, observedAt: Instant): String {
        val ageSeconds = ((now - observedAt).inWholeSeconds).coerceAtLeast(0)
        return when {
            ageSeconds < 60 -> "${ageSeconds}s ago"
            ageSeconds < 3_600 -> "${ageSeconds / 60}m ago"
            else -> "${ageSeconds / 3_600}h ago"
        }
    }

    private fun formatUpdatedLabel(now: Instant): String {
        val local = now.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$hh:$mm WIB"
    }

    private fun formatIdr(value: Double): String {
        val locale = Locale.Builder()
            .setLanguage("id")
            .setRegion("ID")
            .build()
        return NumberFormat.getCurrencyInstance(locale).apply {
            maximumFractionDigits = 0
        }.format(value)
    }

    private fun formatSignedIdr(value: Double): String {
        if (kotlin.math.abs(value) < 0.5) return "+${formatIdr(0.0)}"
        val prefix = if (value >= 0.0) "+" else "-"
        return prefix + formatIdr(kotlin.math.abs(value))
    }
}

private fun EngineLeaseSnapshot?.isHeldBy(deviceId: DeviceId, now: Instant): Boolean {
    return this != null &&
        currentHolder == deviceId &&
        state == LeaseState.HELD &&
        now < expiresAt &&
        !conflictDetected
}
