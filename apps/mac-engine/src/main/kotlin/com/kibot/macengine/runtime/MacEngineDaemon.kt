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
import com.kibot.core.TradeAutomationConfig
import com.kibot.core.TradeAutomationCoordinator
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.nio.file.Files
import java.util.Locale
import kotlin.math.max
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
    private val tradeAutomationCoordinator: TradeAutomationCoordinator = TradeAutomationCoordinator(),
    private val aiSupportCoordinator: GeminiSupportCoordinator? = null,
) {
    private data class TargetEnforcementMemory(
        val memoryDate: LocalDate? = null,
        val lastHourlyWindowIndex: Int = 0,
        val consecutiveHourlyMisses: Int = 0,
        val lastHourlyShortfallPct: Double = 0.0,
        val lastCheckpointWindowIndex: Int = 0,
        val consecutiveCheckpointMisses: Int = 0,
        val lastCheckpointShortfallPct: Double = 0.0,
    )

    private val logger = LoggerFactory.getLogger(javaClass)
    private val adaptiveAiPolicyLoader = AdaptiveAiPolicyLoader(config.adaptiveAiPolicyPath)
    private val aiProviderStatusLoader = AiProviderStatusLoader()
    private val dailyTargetPursuitBrain = DailyTargetPursuitBrain()
    private var registered = false
    private var lastAnalysisPublishedAt: Instant? = null
    private var lastStrategyMetricsPublishedAt: Instant? = null
    private var lastCandidateSignature: String? = null
    private var lastLearningSignature: String? = null
    private var lastLearningPublishedAt: Instant? = null
    private var lastWeeklyReviewPublishedAt: Instant? = null
    private var releaseCooldownUntil: Instant? = null
    private var lastSuccessfulControlPlaneAt: Instant? = null
    private var smoothedExchangePingMs: Double? = null
    private var lastSuccessfulExchangePingAt: Instant? = null
    private var lastExchangeProbeAt: Instant? = null
    private var lastExchangeReachable: Boolean = false
    private var lastExchangePingMs: Long? = null
    private var consecutiveExchangeProbeFailures: Int = 0
    private var lastExecutionPolicyLogSignature: String? = null
    private var lastExecutionPolicyLoggedAt: Instant? = null
    private var lastObservedLeaseTerm: com.kibot.shared.models.LeaseTerm? = null
    private var conflictRecoveryHoldUntil: Instant? = null
    private var conflictRecoveryTerm: com.kibot.shared.models.LeaseTerm? = null
    private var cachedDevices: List<DeviceDescriptor> = emptyList()
    private var devicesFetchedAt: Instant? = null
    private var cachedDailyRisk: DailyRiskSnapshot? = null
    private var cachedDailyRiskDate: kotlinx.datetime.LocalDate? = null
    private var dailyRiskFetchedAt: Instant? = null
    private var commandsFetchedAt: Instant? = null
    private var cachedWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary? = null
    private var weeklyReviewFetchedAt: Instant? = null
    private var cachedBalances: List<BalanceSnapshot> = emptyList()
    private var balancesFetchedAt: Instant? = null
    private var cachedOpenOrders: List<com.kibot.shared.models.OrderSnapshot> = emptyList()
    private var openOrdersFetchedAt: Instant? = null
    private var cachedRecentOrders: List<com.kibot.shared.models.OrderSnapshot> = emptyList()
    private var recentOrdersFetchedAt: Instant? = null
    private var cachedRecentFills: List<com.kibot.shared.models.FillSnapshot> = emptyList()
    private var recentFillsFetchedAt: Instant? = null
    private var cachedRecentFillsKey: String? = null
    private var cachedAdaptiveAiPolicy: AdaptiveAiPolicy? = null
    private var adaptiveAiPolicyFetchedAt: Instant? = null
    private var targetEnforcementMemory = loadTargetEnforcementMemory()

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
        val jakartaDate = jakartaNowDate(now)
        val botState = controlPlane.fetchBotState(config.controlPlane.botId) ?: error("Bot state not found in Supabase.")
        val lease = controlPlane.fetchLease(config.controlPlane.botId)
        lastObservedLeaseTerm = lease?.term ?: botState.currentTerm
        val devices = refreshDevices(now)
        val dailyRisk = refreshDailyRisk(now, jakartaDate)
        val equityHistory = runCatching {
            controlPlane.fetchDailyRiskHistory(config.controlPlane.botId, days = 40)
        }.getOrDefault(emptyList())
        val commands = refreshPendingCommands(now)
        val weeklyReview = refreshWeeklyReview(now)
        lastSuccessfulControlPlaneAt = now

        val (exchangeReachable, exchangePingMs) = probeExchange(now)
        val displayPingMs = recordDisplayPing(
            now = now,
            exchangeReachable = exchangeReachable,
            rawPingMs = exchangePingMs,
        )
        val healthWarnings = mutableListOf<String>()
        if (!exchangeReachable) {
            healthWarnings += "Exchange unreachable or credentials not configured."
        }
        if (dailyRisk?.hardStopTriggered == true) {
            healthWarnings += "Daily hard stop is active."
        }
        if ((displayPingMs ?: 0L) >= entryBlockLatencyMs) {
            healthWarnings += "Exchange latency is heavy."
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
        commandsFetchedAt = now

        val localHealth = buildLocalHealth(
            exchangeReachable = exchangeReachable,
            warnings = healthWarnings,
            feedLatencyMs = displayPingMs ?: exchangePingMs,
            marketFeedHealthy = exchangeReachable,
        )
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
        lastObservedLeaseTerm = initialLease?.term ?: initialBotState.currentTerm
        val isMaster = initialLease.isHeldBy(config.device.deviceId, now)
        val balancesDeferred: kotlinx.coroutines.Deferred<List<BalanceSnapshot>>? = if (exchangeReachable) {
            async { refreshBalances(now) }
        } else {
            null
        }
        val openOrdersDeferred: kotlinx.coroutines.Deferred<List<com.kibot.shared.models.OrderSnapshot>>? = if (exchangeReachable) {
            async { refreshOpenOrders(now) }
        } else {
            null
        }
        val marketQuotesDeferred: kotlinx.coroutines.Deferred<List<com.kibot.shared.models.MarketQuote>>? = if (exchangeReachable) {
            async { runCatching { exchange.fetchMarketQuotes() }.getOrDefault(emptyList()) }
        } else {
            null
        }
        val resolvedBalances = balancesDeferred?.await() ?: cachedBalances
        val resolvedOpenOrders = openOrdersDeferred?.await() ?: cachedOpenOrders
        val resolvedMarketQuotes = marketQuotesDeferred?.await().orEmpty()
        if (exchangeReachable && resolvedMarketQuotes.isEmpty()) {
            healthWarnings += "Market quote feed kosong."
        }
        val finalHealth = buildLocalHealth(
            exchangeReachable = exchangeReachable,
            warnings = healthWarnings,
            feedLatencyMs = displayPingMs ?: exchangePingMs,
            marketFeedHealthy = exchangeReachable && resolvedMarketQuotes.isNotEmpty(),
        )
        val healthDecision = healthAdvisor.evaluate(finalHealth)
        val adaptiveAiPolicy = refreshAdaptiveAiPolicy(now)
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
        val effectiveAiSupportHints = mergeAiSupportHints(
            liveHints = aiSupportHints,
            adaptivePolicy = adaptiveAiPolicy,
        )
        val derivedDailyRisk = deriveDailyRiskSnapshot(
            previous = dailyRisk,
            balances = resolvedBalances,
            marketQuotes = resolvedMarketQuotes,
        ) ?: dailyRisk
        val strategyCycle = if (resolvedMarketQuotes.isNotEmpty()) {
            val baseCycle = strategyOrchestrator.analyze(
                botId = config.controlPlane.botId,
                balances = resolvedBalances,
                openOrders = resolvedOpenOrders,
                dailyRisk = derivedDailyRisk,
                health = finalHealth,
                marketQuotes = resolvedMarketQuotes,
                pairSupportHints = effectiveAiSupportHints,
                weeklySummary = weeklyReview,
            )
            applyPursuitPolicy(
                cycle = baseCycle,
                adaptiveAiPolicy = adaptiveAiPolicy,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                now = now,
            )
        } else {
            null
        }
        val recentPersistedOrders = if (isMaster && exchangeReachable) {
            refreshRecentOrders(now)
        } else {
            cachedRecentOrders
        }
        val recentFills = if (isMaster && exchangeReachable) {
            refreshRecentFills(
                now = now,
                pairIds = relevantFillPairs(
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                openOrders = resolvedOpenOrders,
                persistedOrders = recentPersistedOrders,
                cycle = strategyCycle,
                ),
            )
        } else {
            cachedRecentFills
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
                botId = config.controlPlane.botId,
                term = initialLease?.term?.value ?: initialBotState.currentTerm.value,
                deviceId = config.device.deviceId,
                order = order,
            )
        }
        val effectiveRecentOrders = mergeRecentOrders(
            base = recentPersistedOrders,
            updates = reconciledOrderUpdates,
        )
        cachedRecentOrders = effectiveRecentOrders
        recentOrdersFetchedAt = now

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
                botId = config.controlPlane.botId,
                date = jakartaDate,
                snapshot = effectiveDailyRisk,
            )
            cachedDailyRisk = effectiveDailyRisk
            cachedDailyRiskDate = jakartaDate
            dailyRiskFetchedAt = now
        }
        if (isMaster && runtimeLease != null && strategyCycle != null) {
            effectiveWeeklyReview = maybePublishWeeklyLearningSummary(
                now = now,
                cycle = strategyCycle,
                marketQuotes = resolvedMarketQuotes,
                currentWeeklyReview = weeklyReview,
                recentOrders = effectiveRecentOrders,
            )
            publishAnalysisIfNeeded(
                now = now,
                lease = runtimeLease,
                cycle = strategyCycle,
            )
            maybeManageLiveTrading(
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
            runtimeBotState = controlPlane.fetchBotState(config.controlPlane.botId) ?: runtimeBotState
            runtimeLease = controlPlane.fetchLease(config.controlPlane.botId)
            lastObservedLeaseTerm = runtimeLease?.term ?: runtimeBotState.currentTerm
        }

        controlPlane.appendHeartbeat(
            EngineHeartbeatSnapshot(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                observedAt = now,
                term = runtimeLease?.term,
                isMaster = runtimeLease.isHeldBy(config.device.deviceId, now),
                desiredState = runtimeBotState.desiredState,
                effectiveState = deriveEffectiveState(now, runtimeBotState, runtimeLease, healthDecision),
                health = finalHealth,
            ),
        )

        repository.applyRuntimeState(
            buildDashboardState(
                now = now,
                jakartaDate = jakartaDate,
                botState = runtimeBotState,
                lease = runtimeLease,
                devices = devices,
                localHealth = finalHealth,
                dailyRisk = dailyRisk,
                equityHistory = equityHistory,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                strategyCycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
                recentOrders = effectiveRecentOrders,
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
        repository.noteStatus("Server monitor connected to live feed.")
        appendAuditLog(LogLevel.INFO, "AUTH", "Server monitor connected to live feed.")
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
                        supabaseReachable = isControlPlaneReachable(Clock.System.now()),
                        marketFeedHealthy = false,
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
            CommandType.TOGGLE_LIVE_EXECUTION -> {
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
            lease?.currentHolder == config.device.deviceId &&
            lease.conflictDetected &&
            isConflictRecoveryHoldActive(now, lease)
        ) {
            return false
        }
        if (
            lease != null &&
            lease.currentHolder != config.device.deviceId &&
            now < lease.expiresAt &&
            !lease.conflictDetected
        ) {
            return false
        }
        val balances = runCatching { exchange.fetchBalances() }.getOrDefault(emptyList())
        val marketQuotes = runCatching { exchange.fetchMarketQuotes() }.getOrDefault(emptyList())
        val openOrders = runCatching { exchange.fetchOpenOrders() }.getOrDefault(emptyList())
        val recentPersistedOrders = controlPlane.fetchRecentOrders(config.controlPlane.botId, limit = 200)
        val reconciliationPairs = (openOrders.map { it.pairId } + recentPersistedOrders.map { it.pairId })
            .distinct()
            .take(4)
        val fills = reconciliationPairs
            .flatMap { pairId ->
                runCatching { exchange.fetchRecentFills(pairId, limit = 12) }.getOrDefault(emptyList())
            }
        val reconciliation = reconciliationService.reconcile(
            portfolio = PortfolioSnapshot(
                botId = config.controlPlane.botId,
                balances = balances,
                openOrders = openOrders,
                positions = emptyList<PositionSnapshot>(),
                totalEquityIdr = estimatePortfolioValue(balances, marketQuotes),
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

        if (
            lease?.currentHolder == config.device.deviceId &&
            lease.conflictDetected &&
            reconciliation.state == ReconciliationState.CLEAN &&
            localHealth.status != HealthStatus.CRITICAL
        ) {
            val recoveredLease = controlPlane.acquireLease(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                ttlSeconds = config.leaseTtlSeconds,
            )
            lastObservedLeaseTerm = recoveredLease.term
            activateConflictRecoveryHold(now, recoveredLease.term)
            appendAuditLog(
                level = LogLevel.WARN,
                category = "FAILOVER",
                message = "Lease conflict cleared by reacquiring term ${recoveredLease.term.value} after clean reconciliation.",
            )
            return true
        }

        if (!evaluation.allowed) {
            val shouldEscalateConflict = when {
                reconciliation.state == ReconciliationState.BLOCKED -> true
                lease?.conflictDetected == true && lease.currentHolder != config.device.deviceId -> true
                else -> false
            }
            if (shouldEscalateConflict) {
                if (!isConflictRecoveryHoldActive(now, lease)) {
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
            }
            return false
        }

        val acquiredLease = controlPlane.acquireLease(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            ttlSeconds = config.leaseTtlSeconds,
        )
        lastObservedLeaseTerm = acquiredLease.term

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

    private fun buildLocalHealth(
        exchangeReachable: Boolean,
        warnings: List<String>,
        feedLatencyMs: Long? = null,
        supabaseReachable: Boolean = isControlPlaneReachable(Clock.System.now()),
        marketFeedHealthy: Boolean = exchangeReachable,
    ): EngineHealthSnapshot {
        val exchangeHardDown = !exchangeReachable && consecutiveExchangeProbeFailures >= 2
        val status = when {
            exchangeHardDown || !supabaseReachable -> HealthStatus.CRITICAL
            !marketFeedHealthy || warnings.isNotEmpty() -> HealthStatus.WARNING
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
            websocketHealthy = marketFeedHealthy,
            exchangeReachable = exchangeReachable,
            supabaseReachable = supabaseReachable,
            feedLatencyMs = feedLatencyMs,
            fillQualityScore = if (warnings.any { it.contains("fill", ignoreCase = true) }) 0.35 else 0.75,
            anomalyCount = warnings.size,
            lastError = warnings.firstOrNull(),
            warnings = warnings.distinct(),
        )
    }

    private fun isControlPlaneReachable(now: Instant): Boolean {
        val lastSuccess = lastSuccessfulControlPlaneAt ?: return false
        val stalenessMs = (now.toEpochMilliseconds() - lastSuccess.toEpochMilliseconds()).coerceAtLeast(0L)
        val graceWindowMs = (config.pollIntervalMillis * 8L).coerceAtLeast(30_000L)
        return stalenessMs <= graceWindowMs
    }

    private fun shouldRefresh(
        now: Instant,
        lastFetchedAt: Instant?,
        intervalMillis: Long,
        force: Boolean = false,
    ): Boolean {
        if (force || lastFetchedAt == null) return true
        return (now - lastFetchedAt).inWholeMilliseconds >= intervalMillis
    }

    private suspend fun probeExchange(now: Instant): Pair<Boolean, Long?> {
        if (!shouldRefresh(now, lastExchangeProbeAt, config.exchangePingRefreshIntervalMillis, force = lastExchangeProbeAt == null)) {
            return lastExchangeReachable to lastExchangePingMs
        }
        val pingStartedAtNs = System.nanoTime()
        val exchangeReachable = runCatching { exchange.ping() }.getOrElse { false }
        val exchangePingMs = ((System.nanoTime() - pingStartedAtNs) / 1_000_000L)
            .takeIf { exchangeReachable }
            ?.coerceAtLeast(1L)
        lastExchangeProbeAt = now
        lastExchangeReachable = exchangeReachable
        lastExchangePingMs = exchangePingMs
        consecutiveExchangeProbeFailures = if (exchangeReachable) 0 else (consecutiveExchangeProbeFailures + 1).coerceAtMost(10)
        return exchangeReachable to exchangePingMs
    }

    private fun activateConflictRecoveryHold(
        now: Instant,
        term: com.kibot.shared.models.LeaseTerm,
    ) {
        conflictRecoveryTerm = term
        conflictRecoveryHoldUntil = now.plus(35.seconds)
    }

    private fun isConflictRecoveryHoldActive(
        now: Instant,
        lease: EngineLeaseSnapshot?,
    ): Boolean {
        val until = conflictRecoveryHoldUntil ?: return false
        val termMatches = conflictRecoveryTerm == null || lease?.term == conflictRecoveryTerm
        val sameHolder = lease?.currentHolder == config.device.deviceId
        return sameHolder && termMatches && now < until
    }

    private suspend fun refreshDevices(now: Instant): List<DeviceDescriptor> {
        if (!shouldRefresh(now, devicesFetchedAt, config.devicesRefreshIntervalMillis, force = cachedDevices.isEmpty())) {
            return cachedDevices
        }
        cachedDevices = runCatching { controlPlane.fetchDevices(config.controlPlane.botId) }.getOrElse { cachedDevices }
        devicesFetchedAt = now
        return cachedDevices
    }

    private suspend fun refreshDailyRisk(
        now: Instant,
        date: kotlinx.datetime.LocalDate,
    ): DailyRiskSnapshot? {
        val force = cachedDailyRiskDate != date
        if (!shouldRefresh(now, dailyRiskFetchedAt, config.dailyRiskRefreshIntervalMillis, force = force)) {
            return cachedDailyRisk
        }
        cachedDailyRisk = runCatching { controlPlane.fetchDailyRisk(config.controlPlane.botId, date) }.getOrElse { cachedDailyRisk }
        cachedDailyRiskDate = date
        dailyRiskFetchedAt = now
        return cachedDailyRisk
    }

    private suspend fun refreshPendingCommands(now: Instant): List<CommandEnvelope> {
        if (!shouldRefresh(now, commandsFetchedAt, config.commandsRefreshIntervalMillis, force = commandsFetchedAt == null)) {
            return emptyList()
        }
        commandsFetchedAt = now
        return runCatching {
            controlPlane.fetchPendingCommands(config.controlPlane.botId, config.device.deviceId)
        }.getOrDefault(emptyList())
    }

    private suspend fun refreshWeeklyReview(now: Instant): com.kibot.shared.models.WeeklyLearningSummary? {
        if (!shouldRefresh(now, weeklyReviewFetchedAt, config.weeklySummaryRefreshIntervalMillis, force = weeklyReviewFetchedAt == null && cachedWeeklyReview == null)) {
            return cachedWeeklyReview
        }
        cachedWeeklyReview = runCatching {
            controlPlane.fetchLatestWeeklyLearningSummary(config.controlPlane.botId)
        }.getOrElse { cachedWeeklyReview }
        weeklyReviewFetchedAt = now
        return cachedWeeklyReview
    }

    private suspend fun refreshBalances(now: Instant): List<BalanceSnapshot> {
        if (!shouldRefresh(now, balancesFetchedAt, config.balanceRefreshIntervalMillis, force = cachedBalances.isEmpty())) {
            return cachedBalances
        }
        cachedBalances = runCatching { exchange.fetchBalances() }.getOrElse { cachedBalances }
        balancesFetchedAt = now
        return cachedBalances
    }

    private suspend fun refreshOpenOrders(now: Instant): List<com.kibot.shared.models.OrderSnapshot> {
        if (!shouldRefresh(now, openOrdersFetchedAt, config.openOrdersRefreshIntervalMillis, force = openOrdersFetchedAt == null)) {
            return cachedOpenOrders
        }
        cachedOpenOrders = runCatching { exchange.fetchOpenOrders() }.getOrElse { cachedOpenOrders }
        openOrdersFetchedAt = now
        return cachedOpenOrders
    }

    private suspend fun refreshRecentOrders(now: Instant): List<com.kibot.shared.models.OrderSnapshot> {
        if (!shouldRefresh(now, recentOrdersFetchedAt, config.recentOrdersRefreshIntervalMillis, force = recentOrdersFetchedAt == null)) {
            return cachedRecentOrders
        }
        cachedRecentOrders = runCatching {
            controlPlane.fetchRecentOrders(config.controlPlane.botId, limit = 200)
        }.getOrElse { cachedRecentOrders }
        recentOrdersFetchedAt = now
        return cachedRecentOrders
    }

    private suspend fun refreshRecentFills(
        now: Instant,
        pairIds: List<com.kibot.shared.models.PairId>,
    ): List<com.kibot.shared.models.FillSnapshot> {
        val pairKey = pairIds.joinToString("|") { it.value }
        val shouldRefresh = shouldRefresh(
            now = now,
            lastFetchedAt = recentFillsFetchedAt,
            intervalMillis = config.recentFillsRefreshIntervalMillis,
            force = recentFillsFetchedAt == null || pairKey != cachedRecentFillsKey,
        )
        if (!shouldRefresh) return cachedRecentFills
        cachedRecentFills = pairIds.flatMap { pairId ->
            runCatching { exchange.fetchRecentFills(pairId, limit = 12) }.getOrDefault(emptyList())
        }
        recentFillsFetchedAt = now
        cachedRecentFillsKey = pairKey
        return cachedRecentFills
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

    private suspend fun maybeManageLiveTrading(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        health: EngineHealthSnapshot,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ) {
        if (!config.enableLiveExecution) return
        val adaptiveCoordinator = buildAdaptiveTradeAutomationCoordinator(cycle)
        val entryStabilizedOrders = manageStaleEntryOrders(
            now = now,
            lease = lease,
            cycle = cycle,
            marketQuotes = marketQuotes,
            recentOrders = recentOrders,
        )
        cachedRecentOrders = entryStabilizedOrders
        val preExitManagedPositions = adaptiveCoordinator.deriveManagedPositions(
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
        cachedRecentOrders = stabilizedOrders
        val activePersistedOrders = stabilizedOrders.filter { it.status in activeOrderStatuses }
        val managedPositions = adaptiveCoordinator.deriveManagedPositions(
            balances = balances,
            marketQuotes = marketQuotes,
            reconciledOrders = stabilizedOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )
        val exitDecision = adaptiveCoordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
        )
        if (exitDecision != null) {
            val preparedActiveOrders = prepareExitPath(
                now = now,
                lease = lease,
                recentOrders = stabilizedOrders,
                activePersistedOrders = activePersistedOrders,
                exitDecision = exitDecision,
            )
            val result = liveExecutionCoordinator.submitExit(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                term = lease.term,
                executionPlan = exitDecision.executionPlan,
                existingPersistedOrders = preparedActiveOrders,
                exchange = exchange,
                controlPlane = controlPlane,
            )
            result.order?.let {
                cachedRecentOrders = mergeRecentOrders(stabilizedOrders, listOf(it))
                recentOrdersFetchedAt = now
            }
            appendAuditLog(
                level = when {
                    result.failSafeTriggered -> LogLevel.ERROR
                    result.submitted -> LogLevel.INFO
                    else -> LogLevel.WARN
                },
                category = "AUTO_EXIT",
                message = "${exitDecision.message} ${result.message}",
            )
            return
        }

        val candidateExecutionPlans = cycle.entryExecutionPlans.ifEmpty {
            listOfNotNull(cycle.executionPlan)
        }
        if (candidateExecutionPlans.isEmpty()) return
        if (!cycle.modeSnapshot.tradingAllowed) return
        if (cycle.riskDecision.allowNewEntries.not()) return
        val activeBuyOrders = activePersistedOrders.filter { it.side == com.kibot.shared.models.OrderSide.BUY }
        val availableEntrySlots = (
            cycle.deploymentPlan.maxActivePositions -
                managedPositions.size -
                activeBuyOrders.size
            ).coerceAtLeast(0)
        val batchLimit = determineEntryBatchLimit(
            cycle = cycle,
            availableEntrySlots = availableEntrySlots,
            candidateExecutionPlans = candidateExecutionPlans,
        )
        if (batchLimit <= 0 && !cycle.deploymentPlan.allowRotation) {
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = "Entry baru ditahan karena slot aktif dan pending buy sudah penuh.",
            )
            return
        }

        var workingOrders = activePersistedOrders
        var submittedCount = 0
        var lastBlockedReason: String? = null
        candidateExecutionPlans.forEach { candidatePlan ->
            if (submittedCount >= batchLimit) return@forEach
            if (workingOrders.any { it.pairId == candidatePlan.signal.pairId }) {
                lastBlockedReason = "Entry ${candidatePlan.signal.pairId.value} ditunda karena pair yang sama masih punya order aktif."
                return@forEach
            }

            entryBlockedByPortfolioState(
                cycle = cycle,
                executionPlan = candidatePlan,
                managedPositions = managedPositions,
            )?.let { blockedReason ->
                lastBlockedReason = blockedReason
                return@forEach
            }

            val rolloutDecision = liveRolloutGuard.evaluate(
                cycle.copy(
                    selectedSignal = candidatePlan.signal,
                    executionPlan = candidatePlan,
                ),
                weeklyReview,
            )
            if (!rolloutDecision.allowed) {
                lastBlockedReason = rolloutDecision.reason
                return@forEach
            }

            val routedEntry = routeEntryPlanByLatency(
                executionPlan = candidatePlan,
                health = health,
                marketQuotes = marketQuotes,
            )
            routedEntry.blockedReason?.let { blockedReason ->
                lastBlockedReason = blockedReason
                return@forEach
            }
            routedEntry.message?.let { note ->
                appendThrottledAuditLog(
                    now = now,
                    level = LogLevel.INFO,
                    category = "ENTRY_POLICY",
                    message = note,
                )
            }
            val effectiveExecutionPlan = routedEntry.executionPlan ?: return@forEach

            val result = liveExecutionCoordinator.submitEntry(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                term = lease.term,
                executionPlan = effectiveExecutionPlan,
                existingPersistedOrders = workingOrders,
                exchange = exchange,
                controlPlane = controlPlane,
            )
            result.order?.let {
                workingOrders = mergeRecentOrders(workingOrders, listOf(it)).filter { snapshot ->
                    snapshot.status in activeOrderStatuses
                }
                cachedRecentOrders = mergeRecentOrders(cachedRecentOrders, listOf(it))
                recentOrdersFetchedAt = now
            }

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
                submittedCount += 1
                logger.info(
                    "Live order submitted on {} pair={} clientOrderId={} term={}",
                    config.device.displayName,
                    effectiveExecutionPlan.signal.pairId.value,
                    result.clientOrderId?.value,
                    lease.term.value,
                )
                lastAnalysisPublishedAt = now
            } else if (result.failSafeTriggered) {
                logger.error("Live order submit became ambiguous, safe mode triggered.")
                return
            } else {
                lastBlockedReason = result.message
            }
        }

        if (submittedCount == 0 && !lastBlockedReason.isNullOrBlank()) {
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = lastBlockedReason.orEmpty(),
            )
        }
    }

    private fun routeEntryPlanByLatency(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        health: EngineHealthSnapshot,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): EntryRoutingDecision {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) {
            return EntryRoutingDecision(executionPlan = executionPlan)
        }
        val latencyMs = health.feedLatencyMs
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId }
        return when {
            latencyMs == null || latencyMs <= makerFirstMaxLatencyMs -> {
                if (executionPlan.orderType == com.kibot.shared.models.OrderType.LIMIT && executionPlan.postOnlyPreferred) {
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
                            orderType = com.kibot.shared.models.OrderType.LIMIT,
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
                        orderType = com.kibot.shared.models.OrderType.LIMIT,
                        limitPrice = fastLimitPrice,
                        postOnlyPreferred = false,
                    ),
                    message = "Ping kuning ${latencyMs}ms. Entry ${executionPlan.signal.pairId.value} diturunkan ke LIMIT biasa agar tidak bergantung maker-only.",
                )
            }

            else -> {
                val breakoutExceptionEligible =
                    executionPlan.signal.signalType == com.kibot.shared.models.StrategySignalType.BREAKOUT_ENTRY &&
                        executionPlan.signal.confidence >= 0.74 &&
                        executionPlan.expectedNetEdgePct >= 0.95 &&
                        quote != null &&
                        quote.recentTradeActivityScore >= 0.58 &&
                        quote.estimatedSlippagePct <= 0.95 &&
                        quote.spreadPct <= 1.45 &&
                        health.syncHealth != SyncHealth.BROKEN
                if (breakoutExceptionEligible) {
                    val fastLimitPrice = quote?.bestAsk
                        ?: executionPlan.limitPrice
                        ?: executionPlan.signal.entryPrice
                        ?: return EntryRoutingDecision(
                            executionPlan = null,
                            blockedReason = "Entry breakout ${executionPlan.signal.pairId.value} gagal karena harga fast-limit tidak tersedia saat ping ${latencyMs}ms.",
                        )
                    EntryRoutingDecision(
                        executionPlan = executionPlan.copy(
                            orderType = com.kibot.shared.models.OrderType.LIMIT,
                            limitPrice = fastLimitPrice,
                            postOnlyPreferred = false,
                        ),
                        message = "Ping merah ${latencyMs}ms, tapi breakout ${executionPlan.signal.pairId.value} cukup kuat. Bot tetap izinkan fast-limit exception agar tidak telat ke momentum.",
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

    private fun entryBlockedByPortfolioState(
        cycle: com.kibot.core.StrategyCycleResult,
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        managedPositions: List<com.kibot.core.ManagedPosition>,
    ): String? {
        if (managedPositions.isEmpty()) return null

        val samePairExposure = managedPositions.firstOrNull { it.pairId == executionPlan.signal.pairId }
        if (samePairExposure != null && samePairExposure.unrealizedPnlPct < 0.20) {
            return "Masih pegang ${executionPlan.signal.pairId.value} dan posisinya belum cukup hijau, jadi bot tidak averaging dulu."
        }

        val slotsAreFull = managedPositions.size >= cycle.deploymentPlan.maxActivePositions.coerceAtLeast(1)

        if (!slotsAreFull) return null

        if (!cycle.deploymentPlan.allowRotation) {
            return "Entry baru ditahan karena semua slot penuh dan kandidat pengganti belum cukup menang setelah biaya."
        }
        return null
    }

    private suspend fun manageStaleEntryOrders(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val currentEntryPairs = (
            cycle.entryExecutionPlans.map { it.signal.pairId } +
                listOfNotNull(cycle.selectedSignal?.pairId)
            ).toSet()
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
                val pairFlipped = currentEntryPairs.isNotEmpty() && order.pairId !in currentEntryPairs
                val shouldCancel = ageMinutes >= staleEntryOrderMaxAgeMinutes ||
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
                        botId = config.controlPlane.botId,
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
                        botId = config.controlPlane.botId,
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
                    botId = config.controlPlane.botId,
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
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        currentWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): com.kibot.shared.models.WeeklyLearningSummary? {
        val shouldPublish = lastWeeklyReviewPublishedAt == null ||
            (now - lastWeeklyReviewPublishedAt!!).inWholeHours >= 6
        if (!shouldPublish) return currentWeeklyReview

        val summary = liveLearningReviewBuilder.build(
            botId = config.controlPlane.botId,
            now = now,
            cycle = cycle,
            marketQuotes = marketQuotes,
            recentOrders = recentOrders,
        ) ?: return currentWeeklyReview
        controlPlane.upsertWeeklyLearningSummary(summary)
        cachedWeeklyReview = summary
        weeklyReviewFetchedAt = now
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
        if (shouldExposeToLiveTimeline(category, message)) {
            repository.recordTimeline(
                category = category.uppercase(),
                message = message,
            )
        }
        runCatching {
            controlPlane.appendLog(
                botId = config.controlPlane.botId,
                record = AuditLogRecord(
                    recordedAt = Clock.System.now(),
                    level = level,
                    category = category,
                    deviceId = config.device.deviceId,
                    term = lastObservedLeaseTerm,
                    message = message,
                ),
            )
        }.onFailure { logger.warn("Failed to append audit log: {}", it.message) }
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
            return next.toLong().coerceAtLeast(1L)
        }

        smoothedExchangePingMs = null
        lastSuccessfulExchangePingAt = null
        return null
    }

    private fun deriveEffectiveState(
        now: Instant,
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        healthDecision: com.kibot.core.EntryHealthDecision,
    ): BotEffectiveState {
        if (botState.desiredState == BotDesiredState.OFF) return BotEffectiveState.STOPPED
        if (lease?.conflictDetected == true && !isConflictRecoveryHoldActive(now, lease)) return BotEffectiveState.SAFE_MODE
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
        jakartaDate: LocalDate,
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        devices: List<DeviceDescriptor>,
        localHealth: EngineHealthSnapshot,
        dailyRisk: DailyRiskSnapshot?,
        equityHistory: List<com.kibot.shared.models.DailyEquityHistoryPoint>,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        strategyCycle: com.kibot.core.StrategyCycleResult?,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        healthDecisionSummary: String,
    ): com.kibot.macengine.state.MacDashboardState {
        val heartbeatInstant = botState.lastHeartbeatAt ?: lease?.lastHeartbeatAt
        val filteredRadarPairs = buildDisplayRadarPairs(
            strategyCycle = strategyCycle,
            botState = botState,
        )
        val targetPursuit = strategyCycle?.let {
            dailyTargetPursuitBrain.evaluate(
                cycle = it,
                adaptiveAiPolicy = cachedAdaptiveAiPolicy,
                now = now,
            )
        }
        val topCandidate = preferredDisplayPair(
            primary = strategyCycle?.topCandidate?.value ?: strategyCycle?.selectedSignal?.pairId?.value,
            fallback = filteredRadarPairs.firstOrNull(),
        )
        val portfolioValue = estimatePortfolioValue(balances, marketQuotes).toDoubleOrZero()
            .takeIf { it > 0.0 }
            ?: dailyRisk?.currentEquityIdr?.toDoubleOrZero()
            ?: 0.0
        val pnlToday = dailyRisk?.let {
            it.realizedPnlIdr.toDoubleOrZero() + it.unrealizedPnlIdr.toDoubleOrZero()
        } ?: 0.0
        val openingEquity = (portfolioValue - pnlToday).takeIf { it > 0.0 }
        val pnlTodayPctLabel = openingEquity
            ?.let { formatSignedPercent(pnlToday / it) }
            ?: "+0.0%"
        val heldAssets = balances
            .filterNot { it.asset.equals("idr", ignoreCase = true) }
            .filter { it.free.toDoubleOrZero() + it.locked.toDoubleOrZero() > 0.0 }
            .map { "${it.asset.uppercase()}: ${formatDecimal(it.free.toDoubleOrZero(), 6)}" }
        val holdingsDetailed = balances
            .filterNot { it.asset.equals("idr", ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val assetCode = balance.asset.uppercase()
                val assetValueIdr = balance.totalValueInIdr?.toDoubleOrZero()
                    ?: quoteAssetPriceIdr(balance.asset, marketQuotes)?.let { it * quantity }
                    ?: 0.0
                com.kibot.macengine.state.MacHoldingDetail(
                    assetCode = assetCode,
                    assetLabel = displayAssetLabel(balance.asset),
                    quantityLabel = "${formatDecimal(quantity, 8)} $assetCode",
                    valueIdrLabel = formatIdr(assetValueIdr),
                )
            }
            .sortedByDescending { detail -> detail.valueIdrLabel.parseRupiahLabel() ?: 0.0 }
        val scanUniverseCount = marketQuotes.size
        val displayHeartbeatLabel = when {
            localHealth.syncHealth == SyncHealth.HEALTHY && botState.effectiveState != BotEffectiveState.STOPPED -> "baru saja"
            localHealth.syncHealth == SyncHealth.DEGRADED && botState.effectiveState != BotEffectiveState.STOPPED -> "beberapa saat lalu"
            else -> heartbeatInstant?.let { formatAge(now, it) } ?: "Never"
        }
        val statusMessage = when {
            botState.effectiveState == BotEffectiveState.SAFE_MODE || lease?.conflictDetected == true ->
                "Safe mode aktif. Tunggu status trade dan data exchange benar-benar bersih."
            localHealth.status == HealthStatus.CRITICAL ->
                "Server Oracle lagi bermasalah: ${localHealth.warnings.firstOrNull().orEmpty()}".trim()
            targetPursuit != null && targetPursuit.active && topCandidate != "-" ->
                "${targetPursuit.phase} • PnL 1D $pnlTodayPctLabel • urgency ${formatDecimal(targetPursuit.urgency * 100.0, 0)}% • fokus entry cepat $topCandidate."
            holdingsDetailed.isNotEmpty() && topCandidate != "-" ->
                "Server pegang ${holdingsDetailed.size} aset dan fokus cari entry baru di $topCandidate."
            topCandidate != "-" && scanUniverseCount > 0 ->
                "Server scan $scanUniverseCount pair dan fokus entry breakout $topCandidate."
            scanUniverseCount > 0 ->
                "Server scan $scanUniverseCount pair dan cari momentum yang layak."
            else -> "Server Oracle lagi sinkron dan pantau market."
        }
        val recentOrderCards = recentOrders
            .sortedByDescending { it.updatedAt }
            .map { order ->
                val quantity = max(order.executedQuantity.toDoubleOrZero(), order.originalQuantity.toDoubleOrZero())
                val price = order.price.toDoubleOrZero()
                com.kibot.macengine.state.MacRecentOrder(
                    timestampEpochMs = order.updatedAt.toEpochMilliseconds(),
                    pair = order.pairId.value.lowercase(),
                    side = order.side.name,
                    status = order.status.name,
                    detail = "${formatDecimal(quantity, 8)} @ ${formatIdr(price)}",
                )
            }
            .take(18)
        val liveTimeline = buildLiveTimeline(
            now = now,
            existingTimeline = repository.state.value.liveTimeline,
            botState = botState,
            topCandidate = topCandidate,
            holdingsDetailed = holdingsDetailed,
            scanUniverseCount = scanUniverseCount,
            healthSummary = healthDecisionSummary,
            recentOrders = recentOrderCards,
            targetPursuit = targetPursuit,
            aiProviderSummary = aiProviderStatusLoader.loadOrDefault(config.adaptiveAiPolicyPath).summaryLabel,
        )
        val weeklyBaseline = resolveReturnBaseline(
            history = equityHistory,
            currentDate = jakartaDate,
            rangeStart = startOfWeek(jakartaDate),
            fallbackEquity = portfolioValue,
        )
        val monthlyBaseline = resolveReturnBaseline(
            history = equityHistory,
            currentDate = jakartaDate,
            rangeStart = LocalDate(jakartaDate.year, jakartaDate.month, 1),
            fallbackEquity = portfolioValue,
        )
        val return7d = portfolioValue - weeklyBaseline
        val return7dPct = if (weeklyBaseline > 0.0) return7d / weeklyBaseline else 0.0
        val return30d = portfolioValue - monthlyBaseline
        val return30dPct = if (monthlyBaseline > 0.0) return30d / monthlyBaseline else 0.0
        val aiProviderStatus = aiProviderStatusLoader.loadOrDefault(config.adaptiveAiPolicyPath)

        return com.kibot.macengine.state.MacDashboardState(
            isBotRunning = botState.effectiveState != BotEffectiveState.STOPPED,
            effectiveState = botState.effectiveState,
            operatingMode = strategyCycle?.modeSnapshot?.mode?.name ?: botState.operatingMode.name,
            edgeConfidence = strategyCycle?.modeSnapshot?.edgeConfidence?.name ?: botState.edgeConfidence.name,
            marketRegime = strategyCycle?.marketSnapshot?.regime?.name ?: botState.marketRegime.name,
            topCandidate = topCandidate,
            radarPairs = filteredRadarPairs,
            scanUniverseCount = scanUniverseCount,
            releaseLabel = if (config.releaseLabel.startsWith("#")) config.releaseLabel else "#${config.releaseLabel}",
            liveExecutionEnabled = config.enableLiveExecution,
            portfolioValueIdr = formatIdr(portfolioValue),
            pnlTodayIdr = formatSignedIdr(pnlToday),
            pnlTodayPctLabel = pnlTodayPctLabel,
            return7dIdr = formatSignedIdr(return7d),
            return7dPctLabel = formatSignedPercent(return7dPct),
            return30dIdr = formatSignedIdr(return30d),
            return30dPctLabel = formatSignedPercent(return30dPct),
            targetPursuitLabel = targetPursuit?.phase ?: "TRACKING",
            aiProviderSummary = aiProviderStatus.summaryLabel,
            syncPathLabel = "Live Server",
            activeEngine = "Oracle Cloud Server",
            standbyEngine = "View Only",
            syncHealth = localHealth.syncHealth.name,
            leaseTerm = lease?.term?.value ?: botState.currentTerm.value,
            healthSummary = if (botState.effectiveState == BotEffectiveState.SAFE_MODE || lease?.conflictDetected == true) {
                botState.safeModeReason ?: "Safe mode active. Manual review is required."
            } else {
                listOfNotNull(
                    healthDecisionSummary.takeIf { it.isNotBlank() },
                    targetPursuit?.takeIf { it.active }?.let {
                        "${it.phase} ${formatDecimal(it.currentProfitPct, 2)}% / 25.00% • urgency ${formatDecimal(it.urgency * 100.0, 0)}% • slot +${it.extraSlots}"
                    },
                    aiProviderStatus.summaryLabel.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            },
            weeklyLearningSummary = weeklyReview?.let {
                "Week ${it.periodStart} - ${it.periodEnd} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
            } ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            lastHeartbeatLabel = displayHeartbeatLabel,
            lastUpdatedLabel = formatUpdatedLabel(now),
            statusMessage = statusMessage,
            lastUpdatedEpochMs = now.toEpochMilliseconds(),
            heldAssets = heldAssets,
            holdingsDetailed = holdingsDetailed,
            exchangePingMs = localHealth.feedLatencyMs?.let { "${it}ms" } ?: "--",
            exchangePingValueMs = localHealth.feedLatencyMs,
            serverLocation = "Oracle Cloud (24/7)",
            serverUptime = repository.state.value.serverUptime,
            liveTimeline = liveTimeline,
            recentOrders = recentOrderCards,
        )
    }

    private fun relevantFillPairs(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        persistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult?,
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
        }.take(4)
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

    private fun estimatePortfolioValue(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): DecimalValue {
        val total = balances.sumOf { balance ->
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            val totalValueInIdr = balance.totalValueInIdr
            when {
                quantity <= 0.0 -> 0.0
                balance.asset.equals("idr", ignoreCase = true) -> quantity
                totalValueInIdr != null -> totalValueInIdr.toDoubleOrZero()
                else -> (quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0) * quantity
            }
        }
        return DecimalValue.fromDouble(total.coerceAtLeast(0.0))
    }

    private fun deriveDailyRiskSnapshot(
        previous: DailyRiskSnapshot?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): DailyRiskSnapshot? {
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
        return DailyRiskSnapshot(
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

    private fun quoteAssetPriceIdr(
        asset: String,
        quotes: List<com.kibot.shared.models.MarketQuote>,
    ): Double? {
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

    private fun formatSignedPercent(value: Double): String {
        val pct = value * 100.0
        val prefix = if (pct >= 0.0) "+" else "-"
        return "$prefix${formatDecimal(kotlin.math.abs(pct), 1)}%"
    }

    private fun formatDecimal(value: Double, digits: Int): String = "%.${digits}f".format(java.util.Locale.US, value)

    private fun buildDisplayRadarPairs(
        strategyCycle: com.kibot.core.StrategyCycleResult?,
        botState: BotStateSnapshot,
    ): List<String> {
        return buildList {
            strategyCycle?.selectedSignal?.pairId?.value?.let(::add)
            strategyCycle?.topCandidate?.value?.let(::add)
            strategyCycle?.deploymentPlan?.candidates?.mapTo(this) { it.pairId.value }
        }.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { hiddenStablePairs.contains(it.lowercase()) }
            .distinct()
            .take(10)
            .toList()
    }

    private fun preferredDisplayPair(primary: String?, fallback: String?): String {
        return primary?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: "-"
    }

    private fun buildLiveTimeline(
        now: Instant,
        existingTimeline: List<com.kibot.macengine.state.MacTimelineEntry>,
        botState: BotStateSnapshot,
        topCandidate: String,
        holdingsDetailed: List<com.kibot.macengine.state.MacHoldingDetail>,
        scanUniverseCount: Int,
        healthSummary: String,
        recentOrders: List<com.kibot.macengine.state.MacRecentOrder>,
        targetPursuit: DailyTargetPursuit?,
        aiProviderSummary: String,
    ): List<com.kibot.macengine.state.MacTimelineEntry> {
        val freshStatusEntries = buildSyntheticTimeline(
            now = now,
            botState = botState,
            topCandidate = topCandidate,
            holdingsDetailed = holdingsDetailed,
            scanUniverseCount = scanUniverseCount,
            healthSummary = healthSummary,
            targetPursuit = targetPursuit,
            aiProviderSummary = aiProviderSummary,
        )
        val orderEntries = recentOrders.mapNotNull(::toTimelineEntry)
        val preservedOperatorEntries = existingTimeline
            .filterNot { it.category in setOf("STATUS", "HEALTH") }
            .filter { shouldExposeToLiveTimeline(it.category, it.message) }
            .filter { now.toEpochMilliseconds() - it.timestampEpochMs <= 2 * 60 * 60 * 1000L }
        return (freshStatusEntries + orderEntries + preservedOperatorEntries)
            .sortedByDescending { it.timestampEpochMs }
            .distinctBy { "${it.category}|${it.message}" }
            .take(18)
    }

    private fun buildSyntheticTimeline(
        now: Instant,
        botState: BotStateSnapshot,
        topCandidate: String,
        holdingsDetailed: List<com.kibot.macengine.state.MacHoldingDetail>,
        scanUniverseCount: Int,
        healthSummary: String,
        targetPursuit: DailyTargetPursuit?,
        aiProviderSummary: String,
    ): List<com.kibot.macengine.state.MacTimelineEntry> {
        val primaryMessage = when {
            botState.effectiveState == BotEffectiveState.SAFE_MODE ->
                "Server masuk safe mode dan tahan entry baru."
            targetPursuit?.overdriveAllowed == true && topCandidate != "-" ->
                "Target 25% sudah lewat. Bot masuk overdrive dan tetap buru lonjakan $topCandidate."
            targetPursuit?.active == true && topCandidate != "-" ->
                "Target 25% dikejar. Progress ${formatDecimal(targetPursuit.currentProfitPct, 2)}%, urgency ${formatDecimal(targetPursuit.urgency * 100.0, 0)}%, fokus entry cepat $topCandidate."
            holdingsDetailed.isNotEmpty() && topCandidate != "-" ->
                "Server pegang ${holdingsDetailed.size} aset dan awasi entry cepat $topCandidate."
            topCandidate != "-" ->
                "Server lagi bidik $topCandidate dari $scanUniverseCount pair."
            else ->
                "Server lagi sinkron dan scan market live."
        }
        val entries = mutableListOf(
            com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds(),
                category = "STATUS",
                message = primaryMessage,
            ),
        )
        if (topCandidate != "-") {
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 500L,
                category = if (targetPursuit?.overdriveAllowed == true) "ROTASI" else "TARGET",
                message = when {
                    targetPursuit?.overdriveAllowed == true ->
                        "Profit harian sudah lewat target, tapi $topCandidate sangat kuat. Bot tetap tekan winner sampai momentum patah."
                    targetPursuit?.active == true ->
                        "Fokus server sekarang $topCandidate. Entry ditembak lebih cepat karena target harian belum selesai."
                    else ->
                        "Fokus server sekarang $topCandidate. Entry akan ditembak kalau breakout lanjut dan biaya masih masuk akal."
                },
            )
        }
        if (holdingsDetailed.isNotEmpty()) {
            val watchedHoldings = holdingsDetailed
                .take(3)
                .joinToString(" • ") { it.assetCode }
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 750L,
                category = "HOLD",
                message = "Server sedang jaga ${holdingsDetailed.size} aset: $watchedHoldings.",
            )
        }
        if (healthSummary.isNotBlank()) {
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 1_000L,
                category = "HEALTH",
                message = healthSummary,
            )
        }
        if (aiProviderSummary.isNotBlank()) {
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 1_250L,
                category = "AI",
                message = aiProviderSummary,
            )
        }
        return entries
    }

    private fun toTimelineEntry(
        order: com.kibot.macengine.state.MacRecentOrder,
    ): com.kibot.macengine.state.MacTimelineEntry? {
        val status = order.status.uppercase()
        val message = when (status) {
            "FILLED" -> "${order.side} ${order.pair} fill ${order.detail}."
            "PARTIALLY_FILLED" -> "${order.side} ${order.pair} mulai fill ${order.detail}."
            "OPEN", "SUBMITTING" -> "Pasang ${order.side.lowercase()} ${order.pair} ${order.detail}."
            "CANCELED" -> "Order ${order.pair} dibatalkan karena setup berubah."
            else -> return null
        }
        return com.kibot.macengine.state.MacTimelineEntry(
            timestampEpochMs = order.timestampEpochMs,
            category = when (order.side.uppercase()) {
                "BUY" -> if (status == "FILLED" || status == "PARTIALLY_FILLED") "BUY" else "TARGET"
                "SELL" -> if (status == "FILLED" || status == "PARTIALLY_FILLED") "SELL" else "TARGET"
                else -> "SYNC"
            },
            message = message,
        )
    }

    private fun shouldExposeToLiveTimeline(category: String, message: String): Boolean {
        val normalizedCategory = category.uppercase()
        val normalizedMessage = message.lowercase()
        if (normalizedMessage.isBlank()) return false
        if (
            normalizedCategory == "AUTH" ||
            normalizedMessage.contains("control-plane") ||
            normalizedMessage.contains("registered with control-plane") ||
            normalizedMessage.contains("registered to control plane") ||
            normalizedMessage.contains("device registered")
        ) {
            return false
        }
        if (
            normalizedCategory in setOf("ROTASI", "SCAN", "TARGET") &&
            hiddenStablePairs.any { normalizedMessage.contains(it) }
        ) {
            return false
        }
        return true
    }

    private fun displayAssetLabel(asset: String): String = when (asset.lowercase()) {
        "idr" -> "Rupiah"
        "usdt" -> "Tether"
        "usdc" -> "USD Coin"
        "btc" -> "Bitcoin"
        "eth" -> "Ethereum"
        "xrp" -> "XRP"
        "trx" -> "Tron"
        "sol" -> "Solana"
        "doge" -> "Doge"
        else -> asset.uppercase()
    }

    private fun String.parseRupiahLabel(): Double? {
        val normalized = replace("Rp", "")
            .replace(".", "")
            .replace(",", ".")
            .replace(" ", "")
            .trim()
        return normalized.toDoubleOrNull()
    }

    private fun latencyLabel(latencyMs: Long?): String = when {
        latencyMs == null -> "--"
        else -> "${latencyMs}ms"
    }

    private fun refreshAdaptiveAiPolicy(now: Instant): AdaptiveAiPolicy? {
        val fetchedAt = adaptiveAiPolicyFetchedAt
        if (fetchedAt != null && (now - fetchedAt).inWholeSeconds < 60) {
            return cachedAdaptiveAiPolicy
        }
        adaptiveAiPolicyFetchedAt = now
        val loaded = runCatching { adaptiveAiPolicyLoader.loadOrNull(now) }
            .onFailure { logger.warn("Adaptive AI policy load failed: {}", it.message) }
            .getOrNull()
        val previousSignature = cachedAdaptiveAiPolicy?.successfulProviders?.sorted().orEmpty().joinToString(",")
        val nextSignature = loaded?.successfulProviders?.sorted().orEmpty().joinToString(",")
        if (loaded != null && loaded.isActive && nextSignature != previousSignature) {
            logger.info(
                "Adaptive AI policy loaded providers={} consensus={} path={}",
                loaded.successfulProviders.joinToString(","),
                formatDecimal(loaded.consensusStrength, 2),
                config.adaptiveAiPolicyPath,
            )
        }
        cachedAdaptiveAiPolicy = loaded
        return loaded
    }

    private fun mergeAiSupportHints(
        liveHints: List<com.kibot.shared.models.AiPairSupportHint>,
        adaptivePolicy: AdaptiveAiPolicy?,
    ): List<com.kibot.shared.models.AiPairSupportHint> {
        val adaptiveHints = adaptivePolicy?.pairHints.orEmpty()
        if (liveHints.isEmpty() && adaptiveHints.isEmpty()) return emptyList()
        val rankingScale = adaptivePolicy?.adjustments?.rankingBiasScale ?: 1.0
        val executionHints = adaptivePolicy?.executionHints ?: AdaptiveAiExecutionHints()
        return (liveHints + adaptiveHints)
            .groupBy { it.pairId }
            .map { (pairId, hints) ->
                val replacementHint = executionHints.replacementHints.firstOrNull { it.replacePair == pairId }
                val supportBonus = when {
                    replacementHint != null -> 0.025
                    executionHints.concentrationPair == pairId -> 0.03
                    pairId in executionHints.rotateNowPairs -> 0.02
                    pairId in executionHints.holdLongerPairs -> 0.015
                    else -> 0.0
                }
                val cautionBonus = when {
                    executionHints.replacementHints.any { it.cutPair == pairId } -> 0.04
                    pairId.belongsToAvoidFamily(executionHints.avoidPairFamilies) -> 0.03
                    else -> 0.0
                }
                val supportBias = (hints.sumOf { it.supportBias } + supportBonus).coerceIn(0.0, 0.08) * rankingScale
                val cautionBias = (hints.sumOf { it.cautionBias } + cautionBonus).coerceIn(0.0, 0.06)
                val latest = hints.maxByOrNull { it.generatedAt.toEpochMilliseconds() } ?: hints.first()
                latest.copy(
                    pairId = pairId,
                    supportBias = supportBias.coerceIn(0.0, 0.08),
                    cautionBias = cautionBias.coerceIn(0.0, 0.06),
                    rationale = hints.joinToString(" | ") { it.rationale }.take(240),
                )
            }
    }

    private fun applyPursuitPolicy(
        cycle: com.kibot.core.StrategyCycleResult,
        adaptiveAiPolicy: AdaptiveAiPolicy?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        now: Instant,
    ): com.kibot.core.StrategyCycleResult {
        val jakartaDate = jakartaNowDate(now)
        val pursuit = dailyTargetPursuitBrain.evaluate(
            cycle = cycle,
            adaptiveAiPolicy = adaptiveAiPolicy,
            now = now,
        )
        val enforcementMemory = updateTargetEnforcementMemory(
            pursuit = pursuit,
            jakartaDate = jakartaDate,
        )
        if (!pursuit.active && adaptiveAiPolicy == null) return cycle

        val aiAdjustments = adaptiveAiPolicy?.adjustments ?: AdaptiveAiAdjustments()
        val executionHints = adaptiveAiPolicy?.executionHints ?: AdaptiveAiExecutionHints()
        val watchdog = adaptiveAiPolicy?.watchdog ?: AdaptiveAiWatchdog()
        val repeatedHourlyPenalty = enforcementMemory.consecutiveHourlyMisses.coerceIn(0, 4)
        val repeatedCheckpointPenalty = enforcementMemory.consecutiveCheckpointMisses.coerceIn(0, 3)
        val worseningHourlyMiss = pursuit.hourlyMissed &&
            pursuit.hourlyWindowIndex > enforcementMemory.lastHourlyWindowIndex &&
            pursuit.hourlyShortfallPct > (enforcementMemory.lastHourlyShortfallPct + 0.35)
        val worseningCheckpointMiss = pursuit.checkpointMissed &&
            pursuit.checkpointWindowIndex > enforcementMemory.lastCheckpointWindowIndex &&
            pursuit.checkpointShortfallPct > (enforcementMemory.lastCheckpointShortfallPct + 0.45)
        val budgetBoostMultiplier = maxOf(
            watchdog.budgetBoostFloor,
            (
            pursuit.budgetBoostMultiplier *
                (1.0 + aiAdjustments.budgetBoostMultiplierDelta.coerceIn(0.0, 0.35)) +
                (repeatedHourlyPenalty * 0.04) +
                (repeatedCheckpointPenalty * 0.06)
            ).coerceIn(1.0, 2.0)
        )
        val boostedBudget = (cycle.deploymentPlan.suggestedPerPositionBudgetIdr * budgetBoostMultiplier)
            .coerceAtLeast(cycle.deploymentPlan.suggestedPerPositionBudgetIdr)
        val openPositions = cycle.portfolio.positions.count { it.state != com.kibot.shared.models.PositionState.CLOSED }
        val candidates = cycle.deploymentPlan.candidates
        val actionProfile = when {
            watchdog.severity == "CRITICAL" -> "EMERGENCY_PURSUIT"
            worseningCheckpointMiss -> "EMERGENCY_PURSUIT"
            worseningHourlyMiss && repeatedHourlyPenalty >= 2 -> "EMERGENCY_PURSUIT"
            repeatedCheckpointPenalty >= 2 -> "EMERGENCY_PURSUIT"
            pursuit.checkpointEscalationLevel >= 3 -> "EMERGENCY_PURSUIT"
            repeatedHourlyPenalty >= 3 -> "HARD_HOURLY_PUSH"
            pursuit.checkpointMissed -> "CHECKPOINT_REPLAN"
            pursuit.hourlyEscalationLevel >= 2 -> "HARD_HOURLY_PUSH"
            pursuit.hourlyMissed -> "HOURLY_PUSH"
            pursuit.profitWindowOpen -> "PROFIT_HUNT"
            else -> "BASE"
        }
        val topCandidate = candidates.firstOrNull()
        val dominantConcentrationSignal = topCandidate != null &&
            topCandidate.rankingScore >= 0.82 &&
            topCandidate.marketOpportunityScore >= 0.70 &&
            topCandidate.expectedNetProfitabilityPct >= 2.0
        val highConvictionReserveFloor = when {
            executionHints.concentrationPair != null && dominantConcentrationSignal -> 0.005
            dominantConcentrationSignal && pursuit.profitWindowOpen -> 0.005
            actionProfile in setOf("CHECKPOINT_REPLAN", "EMERGENCY_PURSUIT") && dominantConcentrationSignal -> 0.008
            else -> 0.02
        }
        val boostedReservePct = (
            cycle.deploymentPlan.targetCashReservePct -
                pursuit.reserveReliefPct -
                aiAdjustments.reserveReliefPctDelta.coerceIn(0.0, 0.08) -
                (repeatedCheckpointPenalty * 0.01) -
                (if (executionHints.concentrationPair != null) 0.005 else 0.0)
            ).coerceIn(highConvictionReserveFloor, cycle.deploymentPlan.targetCashReservePct.coerceAtLeast(highConvictionReserveFloor))
        val concentrationPressureStep = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.22
            "CHECKPOINT_REPLAN" -> 0.18
            "HARD_HOURLY_PUSH" -> 0.14
            "HOURLY_PUSH" -> 0.10
            "PROFIT_HUNT" -> 0.12
            else -> 0.0
        }
        val reserveReliefStep = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.018
            "CHECKPOINT_REPLAN" -> 0.014
            "HARD_HOURLY_PUSH" -> 0.010
            "HOURLY_PUSH" -> 0.007
            "PROFIT_HUNT" -> 0.008
            else -> 0.0
        }
        val boostedReservePctWithPressure = (
            boostedReservePct - reserveReliefStep
        ).coerceIn(highConvictionReserveFloor, cycle.deploymentPlan.targetCashReservePct.coerceAtLeast(highConvictionReserveFloor))
        val finalReservePct = minOf(boostedReservePctWithPressure, cycle.deploymentPlan.targetCashReservePct - watchdog.reserveReliefFloor)
            .coerceIn(highConvictionReserveFloor, cycle.deploymentPlan.targetCashReservePct.coerceAtLeast(highConvictionReserveFloor))
        val hourlyEnforcementHeadroom = when {
            actionProfile == "EMERGENCY_PURSUIT" -> 1
            actionProfile == "CHECKPOINT_REPLAN" -> 1
            actionProfile == "HARD_HOURLY_PUSH" && candidates.count {
                it.rankingScore >= 0.72 &&
                    it.marketOpportunityScore >= 0.64 &&
                    it.expectedNetProfitabilityPct >= 1.10
            } >= 2 -> 1
            actionProfile == "PROFIT_HUNT" -> 1
            else -> 0
        }
        val riskHeadroomCeiling = cycle.riskDecision.maxAllowedAdditionalPositions.coerceAtLeast(0)
        val baselineHeadroom = (cycle.deploymentPlan.maxActivePositions - openPositions).coerceAtLeast(0)
        val requestedSlotHeadroom = (
            baselineHeadroom +
                minOf(pursuit.extraSlots, hourlyEnforcementHeadroom) +
                hourlyEnforcementHeadroom +
                aiAdjustments.extraSlotsDelta.coerceIn(0, 2) +
                (if (watchdog.forceRotation) 1 else 0)
            ).coerceAtLeast(baselineHeadroom)
        val opportunityQualifiedHeadroom = qualifiedAdditionalHeadroom(
            candidates = candidates,
            openPositions = openPositions,
            profitWindowOpen = pursuit.profitWindowOpen,
            checkpointMissed = pursuit.checkpointMissed,
            actionProfile = actionProfile,
            baselineHeadroom = baselineHeadroom,
        )
        val effectiveHeadroom = minOf(
            riskHeadroomCeiling,
            minOf(requestedSlotHeadroom, opportunityQualifiedHeadroom),
        ).coerceAtLeast(0)
        val boostedActivePositions = maxOf(
            cycle.deploymentPlan.maxActivePositions,
            openPositions + effectiveHeadroom,
        ).coerceAtMost(6)
        val existingCapitalTarget = cycle.deploymentPlan.capitalUtilizationTargetPct
            .coerceIn(0.02, 0.98)
        val boostedCapitalTarget = (1.0 - finalReservePct).coerceIn(0.02, 0.98)
        val normalizedBudget = finalizePerPositionBudgetIdr(
            currentEquityIdr = cycle.portfolio.totalEquityIdr.toDoubleOrZero(),
            boostedCapitalTargetPct = boostedCapitalTarget,
            baseBudgetIdr = boostedBudget,
            finalActivePositions = boostedActivePositions,
            openPositions = openPositions,
            candidates = candidates,
            concentrationBoostPct = pursuit.concentrationBoostPct +
                concentrationPressureStep +
                aiAdjustments.allocationFocusPctDelta.coerceIn(0.0, 0.16) +
                (if (watchdog.forceConcentration) 0.06 else 0.0),
            profitWindowOpen = pursuit.profitWindowOpen,
            concentrationPair = executionHints.concentrationPair,
            actionProfile = actionProfile,
        )
        val finalConcentrationBoostPct = pursuit.concentrationBoostPct +
            concentrationPressureStep +
            aiAdjustments.allocationFocusPctDelta.coerceIn(0.0, 0.16) +
            (if (watchdog.forceConcentration) 0.06 else 0.0)

        val updatedDeploymentPlan = cycle.deploymentPlan.copy(
            allowRotation = cycle.deploymentPlan.allowRotation ||
                pursuit.urgency >= 0.42 ||
                pursuit.hourlyMissed ||
                pursuit.checkpointMissed ||
                watchdog.forceRotation ||
                executionHints.rotateNowPairs.isNotEmpty() ||
                executionHints.replacementHints.isNotEmpty(),
            maxActivePositions = boostedActivePositions,
            suggestedPerPositionBudgetIdr = normalizedBudget,
            targetCashReservePct = finalReservePct,
            capitalUtilizationTargetPct = maxOf(existingCapitalTarget, boostedCapitalTarget),
            rationale = cycle.deploymentPlan.rationale + pursuit.rationale,
        )

        val updatedExecutionPlan = cycle.executionPlan?.let { plan ->
            scaleExecutionPlanForPursuit(
                executionPlan = plan,
                balances = balances,
                marketQuotes = marketQuotes,
                targetBudgetIdr = normalizedBudget,
                concentrationBoostPct = finalConcentrationBoostPct,
                executionBoostMultiplier = maxOf(pursuit.executionBoostMultiplier, watchdog.executionBoostFloor),
            )
        }

        return cycle.copy(
            deploymentPlan = updatedDeploymentPlan,
            executionPlan = updatedExecutionPlan,
            summary = cycle.summary + buildList {
                add("Daily target ${pursuit.phase}: ${formatDecimal(pursuit.currentProfitPct, 2)}% / 25.00% dengan urgency ${formatDecimal(pursuit.urgency * 100.0, 0)}%.")
                if (pursuit.hourlyMissed) add("Evaluasi 1 jam miss ${pursuit.hourlyMissCount} langkah (${formatDecimal(pursuit.hourlyShortfallPct, 2)}%), action $actionProfile aktif.")
                if (pursuit.checkpointMissed) add("Checkpoint 3 jam ke-${pursuit.checkpointWindowIndex} miss ${formatDecimal(pursuit.checkpointShortfallPct, 2)}%, jadi replan wajib aktif.")
                if (enforcementMemory.consecutiveHourlyMisses > 1) add("Miss hourly berturut-turut ${enforcementMemory.consecutiveHourlyMisses}x, jadi tekanan pursuit ditahan tetap tinggi.")
                if (enforcementMemory.consecutiveCheckpointMisses > 0) add("Miss checkpoint berturut-turut ${enforcementMemory.consecutiveCheckpointMisses}x, jadi rotasi dan sizing dipaksa lebih keras.")
                if (pursuit.profitWindowOpen && !pursuit.checkpointMissed) add("Profit window terbuka, jadi bot tetap agresif cari entry cepat walau checkpoint hanya jadi patokan.")
                if (pursuit.forcedReplan) add("Bot masuk forced replan untuk mengejar target harian yang tertinggal.")
                if (watchdog.status != "IDLE" && watchdog.reprimand.isNotBlank()) add("AI watchdog: ${watchdog.reprimand}")
                if (watchdog.rootCauses.isNotEmpty()) add("AI watchdog akar masalah: ${watchdog.rootCauses.take(3).joinToString(", ")}.")
                if (watchdog.requiredActions.isNotEmpty()) add("AI watchdog aksi wajib: ${watchdog.requiredActions.take(2).joinToString(", ")}.")
                if (effectiveHeadroom < requestedSlotHeadroom) add("Slot tambahan dibatasi kualitas shortlist agar agresif tetap profit-first.")
                if (executionHints.rotateNowPairs.isNotEmpty()) add("AI execution hint mendorong rotasi ke ${executionHints.rotateNowPairs.take(2).joinToString(",") { it.value }}.")
                if (executionHints.replacementHints.isNotEmpty()) add("AI melihat holding ${executionHints.replacementHints.take(2).joinToString(", ") { "${it.cutPair.value}→${it.replacePair.value}" }} lebih efisien untuk digeser.")
                executionHints.concentrationPair?.let { add("AI execution hint mendorong konsentrasi modal ke ${it.value}.") }
                if (pursuit.urgency >= 0.40) add("Sizing entry dinaikkan dan reserve dikendurkan untuk kejar target harian.")
                if (pursuit.overdriveAllowed) add("Target tercapai tapi breakout masih ganas, jadi bot tetap tekan entry winner dan biarkan profit lanjut.")
                if (updatedDeploymentPlan.allowRotation) add("Rotasi loser/stagnan dipercepat saat pair baru terlihat lebih eksplosif.")
            },
        )
    }

    private fun scaleExecutionPlanForPursuit(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        targetBudgetIdr: Double,
        concentrationBoostPct: Double,
        executionBoostMultiplier: Double,
    ): com.kibot.shared.models.ExecutionPlan {
        val pairAssets = executionPlan.signal.pairId.pairAssets()
        val quoteAssetPriceIdr = quoteAssetPriceIdr(pairAssets.quoteAsset, marketQuotes) ?: return executionPlan
        val quoteBalanceUnits = balances
            .firstOrNull { it.asset.equals(pairAssets.quoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: return executionPlan
        val availableBudgetIdr = if (pairAssets.quoteAsset.equals("idr", ignoreCase = true)) {
            quoteBalanceUnits
        } else {
            quoteBalanceUnits * quoteAssetPriceIdr
        }
        val baseBudgetIdr = executionPlan.quoteBudget?.toDoubleOrZero()
            ?: executionPlan.limitPrice?.toDoubleOrZero()?.let { price ->
                executionPlan.quantity.toDoubleOrZero() * price * if (pairAssets.quoteAsset == "idr") 1.0 else quoteAssetPriceIdr
            }
            ?: return executionPlan
        val boostedBudgetIdr = minOf(
            targetBudgetIdr * (1.0 + concentrationBoostPct.coerceIn(0.0, 0.24)),
            availableBudgetIdr * 0.985,
            baseBudgetIdr * executionBoostMultiplier.coerceIn(1.0, 1.95),
        ).coerceAtLeast(baseBudgetIdr)
        if (boostedBudgetIdr <= baseBudgetIdr) return executionPlan
        val ratio = (boostedBudgetIdr / baseBudgetIdr).coerceAtLeast(1.0)
        return executionPlan.copy(
            quantity = DecimalValue.fromDouble(executionPlan.quantity.toDoubleOrZero() * ratio),
            quoteBudget = DecimalValue.fromDouble(boostedBudgetIdr),
        )
    }

    private fun determineEntryBatchLimit(
        cycle: com.kibot.core.StrategyCycleResult,
        availableEntrySlots: Int,
        candidateExecutionPlans: List<com.kibot.shared.models.ExecutionPlan>,
    ): Int {
        if (availableEntrySlots <= 0 || candidateExecutionPlans.isEmpty()) return 0
        val secondPlan = candidateExecutionPlans.getOrNull(1)
        val thirdPlan = candidateExecutionPlans.getOrNull(2)
        val secondStrong = secondPlan?.let {
            it.pairRankingScore >= 0.70 && it.expectedNetEdgePct >= 1.12
        } == true
        val thirdStrong = thirdPlan?.let {
            it.pairRankingScore >= 0.76 && it.expectedNetEdgePct >= 1.35
        } == true
        val aggressiveBatchTarget = when {
            thirdStrong && cycle.deploymentPlan.allowRotation -> 3
            secondStrong -> 2
            else -> 1
        }
        return minOf(availableEntrySlots, aggressiveBatchTarget, candidateExecutionPlans.size)
    }

    private fun qualifiedAdditionalHeadroom(
        candidates: List<com.kibot.shared.models.CandidateOpportunity>,
        openPositions: Int,
        profitWindowOpen: Boolean,
        checkpointMissed: Boolean,
        actionProfile: String,
        baselineHeadroom: Int,
    ): Int {
        if (candidates.isEmpty()) return 0
        val strongRankingFloor = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.66
            "CHECKPOINT_REPLAN" -> 0.68
            "HARD_HOURLY_PUSH" -> 0.69
            "HOURLY_PUSH" -> 0.70
            else -> 0.72
        }
        val strongOpportunityFloor = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.62
            "CHECKPOINT_REPLAN" -> 0.64
            "HARD_HOURLY_PUSH" -> 0.65
            "HOURLY_PUSH" -> 0.65
            else -> 0.66
        }
        val strongNetFloor = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 1.02
            "CHECKPOINT_REPLAN" -> 1.08
            "HARD_HOURLY_PUSH" -> 1.12
            "HOURLY_PUSH" -> 1.16
            else -> 1.20
        }
        val strongCandidates = candidates.count {
            it.rankingScore >= strongRankingFloor &&
                it.marketOpportunityScore >= strongOpportunityFloor &&
                it.expectedNetProfitabilityPct >= strongNetFloor
        }
        val explosiveCandidates = candidates.count {
            it.rankingScore >= 0.82 &&
                it.marketOpportunityScore >= 0.76 &&
                it.expectedNetProfitabilityPct >= 2.10
        }
        val desiredAdditional = when {
            explosiveCandidates >= 3 -> 3
            explosiveCandidates >= 2 && strongCandidates >= 4 -> 3
            checkpointMissed && strongCandidates >= 4 -> 3
            profitWindowOpen && strongCandidates >= 4 -> 3
            explosiveCandidates >= 2 -> 2
            checkpointMissed && strongCandidates >= 2 -> 2
            profitWindowOpen && strongCandidates >= 2 -> 2
            strongCandidates >= 1 -> 1
            else -> 0
        }
        return maxOf(baselineHeadroom, desiredAdditional).coerceAtLeast((0 - openPositions).coerceAtLeast(0))
    }

    private fun finalizePerPositionBudgetIdr(
        currentEquityIdr: Double,
        boostedCapitalTargetPct: Double,
        baseBudgetIdr: Double,
        finalActivePositions: Int,
        openPositions: Int,
        candidates: List<com.kibot.shared.models.CandidateOpportunity>,
        concentrationBoostPct: Double,
        profitWindowOpen: Boolean,
        concentrationPair: com.kibot.shared.models.PairId?,
        actionProfile: String,
    ): Double {
        val deployableCapitalIdr = (currentEquityIdr * boostedCapitalTargetPct).coerceAtLeast(baseBudgetIdr)
        val topCandidate = candidates.firstOrNull()
        val aiConcentrationBoost = if (concentrationPair != null && concentrationPair == topCandidate?.pairId) 0.06 else 0.0
        val concentrationLedProfile = actionProfile in setOf("PROFIT_HUNT", "CHECKPOINT_REPLAN", "EMERGENCY_PURSUIT")
        val preserveExtraSlot = !concentrationLedProfile || (topCandidate == null && !profitWindowOpen)
        val normalizedSlotCount = when {
            preserveExtraSlot -> maxOf(finalActivePositions, openPositions + 1, 1)
            finalActivePositions > openPositions -> maxOf(finalActivePositions - 1, openPositions.coerceAtLeast(1))
            else -> maxOf(finalActivePositions, 1)
        }
        val slotNormalizedBudgetIdr = deployableCapitalIdr / normalizedSlotCount
        val concentrationMultiplier = when {
            topCandidate != null &&
                topCandidate.rankingScore >= 0.84 &&
                topCandidate.expectedNetProfitabilityPct >= 2.30 ->
                1.18 + concentrationBoostPct.coerceIn(0.0, 0.18) + aiConcentrationBoost
            profitWindowOpen -> 1.10 + concentrationBoostPct.coerceIn(0.0, 0.14) + aiConcentrationBoost
            else -> 1.0 + (concentrationBoostPct.coerceIn(0.0, 0.10) * 0.6) + aiConcentrationBoost
        }
        val floorBudgetIdr = maxOf(
            baseBudgetIdr * 0.92,
            slotNormalizedBudgetIdr * concentrationMultiplier,
        )
        val ceilingBudgetIdr = deployableCapitalIdr * if (profitWindowOpen) 0.78 else 0.58
        return floorBudgetIdr
            .coerceAtMost(ceilingBudgetIdr.coerceAtLeast(baseBudgetIdr * 0.94))
            .coerceAtLeast(baseBudgetIdr * 0.88)
    }

    private fun updateTargetEnforcementMemory(
        pursuit: DailyTargetPursuit,
        jakartaDate: LocalDate,
    ): TargetEnforcementMemory {
        val current = if (targetEnforcementMemory.memoryDate != jakartaDate) {
            TargetEnforcementMemory(memoryDate = jakartaDate)
        } else {
            targetEnforcementMemory
        }
        val nextHourlyMisses = if (pursuit.hourlyWindowIndex > current.lastHourlyWindowIndex) {
            if (pursuit.hourlyMissed) current.consecutiveHourlyMisses + 1 else 0
        } else {
            current.consecutiveHourlyMisses
        }
        val nextCheckpointMisses = if (pursuit.checkpointWindowIndex > current.lastCheckpointWindowIndex) {
            if (pursuit.checkpointMissed) current.consecutiveCheckpointMisses + 1 else 0
        } else {
            current.consecutiveCheckpointMisses
        }
        val updated = current.copy(
            memoryDate = jakartaDate,
            lastHourlyWindowIndex = maxOf(current.lastHourlyWindowIndex, pursuit.hourlyWindowIndex),
            consecutiveHourlyMisses = nextHourlyMisses.coerceIn(0, 6),
            lastHourlyShortfallPct = if (pursuit.hourlyWindowIndex > current.lastHourlyWindowIndex) {
                pursuit.hourlyShortfallPct
            } else {
                current.lastHourlyShortfallPct
            },
            lastCheckpointWindowIndex = maxOf(current.lastCheckpointWindowIndex, pursuit.checkpointWindowIndex),
            consecutiveCheckpointMisses = nextCheckpointMisses.coerceIn(0, 4),
            lastCheckpointShortfallPct = if (pursuit.checkpointWindowIndex > current.lastCheckpointWindowIndex) {
                pursuit.checkpointShortfallPct
            } else {
                current.lastCheckpointShortfallPct
            },
        )
        targetEnforcementMemory = updated
        persistTargetEnforcementMemory(updated)
        return updated
    }

    private fun loadTargetEnforcementMemory(): TargetEnforcementMemory {
        val path = config.targetEnforcementMemoryPath
        val raw = runCatching {
            if (!Files.exists(path)) return TargetEnforcementMemory()
            Files.readString(path)
        }.getOrNull() ?: return TargetEnforcementMemory()
        val values = raw
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) null else parts[0].trim() to parts[1].trim()
            }
            .toMap()
        return TargetEnforcementMemory(
            memoryDate = values["memoryDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            lastHourlyWindowIndex = values["lastHourlyWindowIndex"]?.toIntOrNull() ?: 0,
            consecutiveHourlyMisses = values["consecutiveHourlyMisses"]?.toIntOrNull() ?: 0,
            lastHourlyShortfallPct = values["lastHourlyShortfallPct"]?.toDoubleOrNull() ?: 0.0,
            lastCheckpointWindowIndex = values["lastCheckpointWindowIndex"]?.toIntOrNull() ?: 0,
            consecutiveCheckpointMisses = values["consecutiveCheckpointMisses"]?.toIntOrNull() ?: 0,
            lastCheckpointShortfallPct = values["lastCheckpointShortfallPct"]?.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun persistTargetEnforcementMemory(memory: TargetEnforcementMemory) {
        runCatching {
            val path = config.targetEnforcementMemoryPath
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                path,
                buildString {
                    appendLine("memoryDate=${memory.memoryDate}")
                    appendLine("lastHourlyWindowIndex=${memory.lastHourlyWindowIndex}")
                    appendLine("consecutiveHourlyMisses=${memory.consecutiveHourlyMisses}")
                    appendLine("lastHourlyShortfallPct=${memory.lastHourlyShortfallPct}")
                    appendLine("lastCheckpointWindowIndex=${memory.lastCheckpointWindowIndex}")
                    appendLine("consecutiveCheckpointMisses=${memory.consecutiveCheckpointMisses}")
                    appendLine("lastCheckpointShortfallPct=${memory.lastCheckpointShortfallPct}")
                },
            )
        }.onFailure {
            logger.warn("Failed to persist target enforcement memory: {}", it.message)
        }
    }

    private fun com.kibot.shared.models.PairId.belongsToAvoidFamily(
        families: List<String>,
    ): Boolean {
        if (families.isEmpty()) return false
        val base = value.substringBefore('_').lowercase()
        return families.any { family ->
            family == base || value.contains(family, ignoreCase = true)
        }
    }

    private fun buildAdaptiveTradeAutomationCoordinator(
        cycle: com.kibot.core.StrategyCycleResult,
    ): TradeAutomationCoordinator {
        val pursuit = dailyTargetPursuitBrain.evaluate(
            cycle = cycle,
            adaptiveAiPolicy = cachedAdaptiveAiPolicy,
            now = Clock.System.now(),
        )
        val enforcementMemory = targetEnforcementMemory
        val adjustments = cachedAdaptiveAiPolicy?.adjustments
        val executionHints = cachedAdaptiveAiPolicy?.executionHints ?: AdaptiveAiExecutionHints()
        if (adjustments == null && !pursuit.active && executionHints.replacementHints.isEmpty()) return tradeAutomationCoordinator
        val aiAdjustments = adjustments ?: AdaptiveAiAdjustments()
        val repeatedHourlyPenalty = enforcementMemory.consecutiveHourlyMisses.coerceIn(0, 4)
        val repeatedCheckpointPenalty = enforcementMemory.consecutiveCheckpointMisses.coerceIn(0, 3)
        val aiReplacementPressure = if (executionHints.replacementHints.isNotEmpty()) 0.08 else 0.0
        val defaults = TradeAutomationConfig()
        val adjusted = TradeAutomationConfig(
            staleRotationMinAgeHours = (defaults.staleRotationMinAgeHours + aiAdjustments.rotationAgeHoursDelta + pursuit.rotationAgeHoursDelta - (repeatedHourlyPenalty * 0.03) - (repeatedCheckpointPenalty * 0.04) - aiReplacementPressure)
                .coerceAtLeast(0.12),
            staleRotationMinScoreGap = (defaults.staleRotationMinScoreGap + aiAdjustments.rotationScoreGapDelta + pursuit.rotationScoreGapDelta)
                .coerceIn(0.02, 0.14),
            loserRotationMinAgeHours = (defaults.loserRotationMinAgeHours + ((aiAdjustments.rotationAgeHoursDelta + pursuit.rotationAgeHoursDelta) * 0.75) - (repeatedHourlyPenalty * 0.02) - (repeatedCheckpointPenalty * 0.03) - (aiReplacementPressure * 0.75))
                .coerceAtLeast(0.12),
            loserRotationMinScoreGap = (defaults.loserRotationMinScoreGap + aiAdjustments.rotationScoreGapDelta + pursuit.rotationScoreGapDelta)
                .coerceIn(0.02, 0.10),
            maxStaleLossPctForTimeExit = (defaults.maxStaleLossPctForTimeExit + 0.08 + (repeatedHourlyPenalty * 0.04) + (repeatedCheckpointPenalty * 0.06))
                .coerceIn(0.10, 0.42),
            partialTakeProfitMinPnlPct = (defaults.partialTakeProfitMinPnlPct + aiAdjustments.partialTakeProfitPnlDelta + pursuit.partialTakeProfitPnlDelta)
                .coerceIn(0.95, 2.8),
            minMeaningfulNonEmergencyExitProfitPct = (defaults.minMeaningfulNonEmergencyExitProfitPct + aiAdjustments.meaningfulExitProfitDelta + pursuit.meaningfulExitProfitDelta - (repeatedHourlyPenalty * 0.03) - (repeatedCheckpointPenalty * 0.05))
                .coerceIn(0.16, 0.95),
            breakoutWinnerRunMinPnlPct = (defaults.breakoutWinnerRunMinPnlPct + aiAdjustments.winnerRunPnlDelta + pursuit.winnerRunPnlDelta)
                .coerceIn(0.35, 1.2),
            speculativeWinnerRunMinPnlPct = (defaults.speculativeWinnerRunMinPnlPct + aiAdjustments.winnerRunPnlDelta + pursuit.winnerRunPnlDelta)
                .coerceIn(0.80, 1.8),
            staleUnderwaterKillMinAgeHours = (defaults.staleUnderwaterKillMinAgeHours - (repeatedHourlyPenalty * 0.03) - (repeatedCheckpointPenalty * 0.04) - aiReplacementPressure)
                .coerceAtLeast(0.25),
            staleUnderwaterKillMinScoreGap = (defaults.staleUnderwaterKillMinScoreGap - (repeatedHourlyPenalty * 0.01) - (repeatedCheckpointPenalty * 0.015) - (aiReplacementPressure * 0.10))
                .coerceIn(0.03, 0.10),
            staleUnderwaterKillMinNetUpgradePct = (defaults.staleUnderwaterKillMinNetUpgradePct - (repeatedHourlyPenalty * 0.06) - (repeatedCheckpointPenalty * 0.08) - (aiReplacementPressure * 0.9))
                .coerceIn(0.88, 1.30),
            tacticalStaleMaxAgeHours = (defaults.tacticalStaleMaxAgeHours - (repeatedHourlyPenalty * 0.25) - (repeatedCheckpointPenalty * 0.40) - (aiReplacementPressure * 4.0))
                .coerceIn(1.6, defaults.tacticalStaleMaxAgeHours),
        )
        return TradeAutomationCoordinator(config = adjusted)
    }

    private fun resolveReturnBaseline(
        history: List<com.kibot.shared.models.DailyEquityHistoryPoint>,
        currentDate: LocalDate,
        rangeStart: LocalDate,
        fallbackEquity: Double,
    ): Double {
        if (history.isEmpty()) return fallbackEquity
        val sorted = history.sortedBy { it.date }
        val inRange = sorted.filter { it.date >= rangeStart && it.date <= currentDate }
        val anchor = inRange.firstOrNull() ?: sorted.lastOrNull { it.date < rangeStart } ?: sorted.firstOrNull()
        return anchor?.openingEquityIdr?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: anchor?.currentEquityIdr?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: fallbackEquity
    }

    private fun startOfWeek(date: LocalDate): LocalDate {
        val offset = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> 0
            DayOfWeek.TUESDAY -> 1
            DayOfWeek.WEDNESDAY -> 2
            DayOfWeek.THURSDAY -> 3
            DayOfWeek.FRIDAY -> 4
            DayOfWeek.SATURDAY -> 5
            DayOfWeek.SUNDAY -> 6
        }
        return date.minus(DatePeriod(days = offset))
    }

    private data class PairAssetParts(
        val baseAsset: String,
        val quoteAsset: String,
    )

    private fun com.kibot.shared.models.PairId.pairAssets(): PairAssetParts {
        val parts = value.lowercase().split("_")
        val base = parts.getOrNull(0).orEmpty().ifBlank { value.lowercase() }
        val quote = parts.getOrNull(1).orEmpty().ifBlank { "idr" }
        return PairAssetParts(baseAsset = base, quoteAsset = quote)
    }

    private companion object {
        private const val staleEntryOrderMaxAgeMinutes = 6.0
        private const val staleEntryOrderPairFlipGraceMinutes = 2.5
        private const val staleEntryOrderMaxDriftPct = 0.70
        private const val staleExitOrderMaxAgeMinutes = 4.5
        private const val staleExitOrderMaxDriftPct = 0.55
        private const val staleExitRepriceLossFloorPct = -0.35
        private const val makerFirstMaxLatencyMs = 150L
        private const val aggressiveLimitFallbackLatencyMs = 850L
        private const val entryBlockLatencyMs = 1200L
        private const val executionPolicyLogCooldownMinutes = 2L
        private val hiddenStablePairs = setOf("usdt_idr", "usdc_idr", "indr_idr")
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
    return this != null &&
        currentHolder == deviceId &&
        state == LeaseState.HELD &&
        now < expiresAt &&
        !conflictDetected
}
