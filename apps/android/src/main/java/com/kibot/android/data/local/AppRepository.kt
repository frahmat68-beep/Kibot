package com.kibot.android.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kibot.android.runtime.LiveHoldingUi
import com.kibot.android.runtime.LiveStatusSnapshot
import com.kibot.android.runtime.LiveStatusStore
import com.kibot.android.runtime.RuntimePreferenceStore
import com.kibot.android.security.SecureCredentialStore
import com.kibot.android.ui.DeviceStatusUi
import com.kibot.android.ui.EngineAction
import com.kibot.android.ui.KiBotUiState
import com.kibot.android.ui.LogUi
import com.kibot.android.ui.PortfolioAllocationUi
import com.kibot.android.ui.PortfolioSectionUi
import com.kibot.android.ui.PortfolioTrendPointUi
import com.kibot.android.ui.PositionCardUi
import com.kibot.android.ui.TradeUi
import com.kibot.android.widget.KiBotWidgetProvider
import com.kibot.core.ControlPlaneGateway
import com.kibot.core.DeviceRegistration
import com.kibot.core.ExchangeGateway
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DailyEquityHistoryPoint
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.max

class AppRepository(
    private val appContext: Context,
    private val database: AppDatabase,
    private val credentialStore: SecureCredentialStore,
    private val runtimePreferenceStore: RuntimePreferenceStore,
    private val liveStatusStore: LiveStatusStore,
    private val exchangeGateway: ExchangeGateway,
    private val controlPlaneGateway: ControlPlaneGateway? = null,
    private val deviceRegistration: DeviceRegistration? = null,
    private val botId: BotId = BotId("main"),
    private val macLanSyncBaseUrl: String? = null,
    private val serverMonitorBaseUrl: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _uiState = MutableStateFlow(KiBotUiState.preview())
    val uiState: StateFlow<KiBotUiState> = _uiState.asStateFlow()
    private val macLanDiscovery = MacLanDiscovery(appContext)
    private var deviceRegistered = false
    private var cachedAuxiliaryData: AuxiliarySyncCache? = null
    private var cachedLanEndpoint: CachedLanEndpoint? = macLanSyncBaseUrl
        ?.trim()
        ?.removeSuffix("/")
        ?.removeSuffix("/api/lan/ping")
        ?.takeIf { it.isNotBlank() }
        ?.let { CachedLanEndpoint(it, 0L) }
    private val normalizedServerMonitorBaseUrl = serverMonitorBaseUrl
        ?.trim()
        ?.removeSuffix("/")
        ?.takeIf { it.isNotBlank() }
    private var lastLanDiscoveryAttemptEpochMs: Long = 0L

    private data class ValuedHolding(
        val asset: String,
        val quantity: Double,
        val valueIdr: Double,
        val pnlIdrLabel: String = "",
        val pnlPctLabel: String = "",
    )

    fun toggleBot(): Boolean {
        _uiState.value = _uiState.value.copy(
            statusMessage = "App HP sekarang view-only. Kontrol bot berjalan penuh di server Oracle.",
        )
        return _uiState.value.isBotRunning
    }

    fun dispatchCommand(action: EngineAction) {
        _uiState.value = _uiState.value.copy(
            statusMessage = "Perintah ${action.name} diblok. App ini hanya monitor hasil trade server Oracle.",
        )
    }

    suspend fun syncNow() {
        val now = Clock.System.now()
        val serverMonitor = fetchServerMonitorState()
        if (serverMonitor != null) {
            syncFromServerMonitor(serverMonitor, now)
            persistState()
            return
        }
        if (normalizedServerMonitorBaseUrl != null) {
            syncFromUnavailableServerFeed(now)
            persistState()
            return
        }

        val gateway = controlPlaneGateway ?: return
        ensureDeviceRegistered()
        val jakartaDate = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
        val storedLiveSnapshot = liveStatusStore.current().takeIf { it.updatedAtEpochMs > 0L }
        val fallbackSnapshot = storedLiveSnapshot?.takeIf { isUiUsableLiveSnapshot(it, now) }

        val botStateDeferred = scope.async { gateway.fetchBotState(botId) }
        val leaseDeferred = scope.async { gateway.fetchLease(botId) }
        val devicesDeferred = scope.async { gateway.fetchDevices(botId) }
        val riskDeferred = scope.async {
            gateway.fetchDailyRisk(
                botId = botId,
                date = jakartaDate,
            )
        }
        val riskHistoryDeferred = scope.async {
            gateway.fetchDailyRiskHistory(
                botId = botId,
                days = 7,
            )
        }
        val liveBalancesDeferred = scope.async {
            runCatching { exchangeGateway.fetchBalances() }.getOrDefault(emptyList())
        }
        val liveQuotesDeferred = scope.async {
            runCatching { exchangeGateway.fetchMarketQuotes() }.getOrDefault(emptyList())
        }

        val botState = botStateDeferred.await() ?: return
        val lease = leaseDeferred.await()
        val devices = devicesDeferred.await()
        val risk = riskDeferred.await()
        val riskHistory = riskHistoryDeferred.await()
        val liveBalances = liveBalancesDeferred.await()
        val liveQuotes = liveQuotesDeferred.await()
        val liveIdrBalance = liveBalances
            .firstOrNull { it.asset.equals("idr", ignoreCase = true) }
            ?.let { it.free.toDoubleOrZero() + it.locked.toDoubleOrZero() }
        val auxiliary = fetchAuxiliaryData(gateway, now)
        val logs = auxiliary.logs
        val orders = auxiliary.orders
        val weeklyReview = auxiliary.weeklyReview
        val liveEquityIdr = estimateEquityIdr(liveBalances, liveQuotes)
            ?: fallbackSnapshot?.totalEquityIdr?.parseRupiahLabel()
        val openingEquityIdr = when {
            risk?.openingEquityIdr != null -> risk.openingEquityIdr.toDoubleOrZero()
            liveEquityIdr != null -> runtimePreferenceStore.getOrRememberDailyOpeningEquity(
                dateKey = jakartaDate.toString(),
                currentEquityIdr = liveEquityIdr,
            )
            else -> null
        }
        val livePnlTodayIdr = when {
            liveEquityIdr != null && openingEquityIdr != null -> liveEquityIdr - openingEquityIdr
            fallbackSnapshot != null -> fallbackSnapshot.pnlTodayIdr.parseRupiahLabel()
            risk != null -> risk.realizedPnlIdr.toDoubleOrZero() + risk.unrealizedPnlIdr.toDoubleOrZero()
            else -> 0.0
        }
        val modalSaatIniLabel = liveEquityIdr?.let(::formatIdr)
            ?: fallbackSnapshot?.totalEquityIdr
            ?: risk?.currentEquityIdr?.let { formatIdr(it.toDoubleOrZero()) }
            ?: _uiState.value.modalSaatIniIdr
        val pnlTodayLabel = livePnlTodayIdr?.let(::formatSignedIdr)
            ?: fallbackSnapshot?.pnlTodayIdr
            ?: formatSignedIdr(0.0)
        val pnlTodayPctLabel = when {
            livePnlTodayIdr != null && openingEquityIdr != null && openingEquityIdr > 0.0 ->
                formatSignedPercent(livePnlTodayIdr / openingEquityIdr)
            fallbackSnapshot != null -> fallbackSnapshot.derivedPnlPctLabel()
            else -> "+0.0%"
        }
        val valuedHoldings = when {
            liveBalances.isNotEmpty() && liveQuotes.isNotEmpty() -> buildValuedHoldings(
                balances = liveBalances,
                quotes = liveQuotes,
                liveSnapshot = fallbackSnapshot,
            )
            !fallbackSnapshot?.holdings.isNullOrEmpty() -> buildSnapshotValuedHoldings(fallbackSnapshot!!)
            else -> emptyList()
        }
        val livePositions = buildLivePositions(valuedHoldings)
        val portfolioSection = buildPortfolioSection(
            now = now,
            history = riskHistory,
            totalEquityLabel = modalSaatIniLabel,
            totalEquityIdr = liveEquityIdr ?: modalSaatIniLabel.parseRupiahLabel(),
            pnlTodayLabel = pnlTodayLabel,
            pnlTodayPctLabel = pnlTodayPctLabel,
            risk = risk,
            holdings = valuedHoldings,
            cashReadyIdrOverride = liveIdrBalance,
        )

        val blockedDisplayPairs = setOf("usdt_idr", "usdc_idr", "indr_idr")
        val activeDeviceId = lease?.currentHolder ?: botState.activeDeviceId
        val activeEngine = devices.firstOrNull { it.deviceId == activeDeviceId }?.displayName ?: "Unknown"
        val standbyEngine = devices.firstOrNull { it.deviceId != activeDeviceId && !it.isRevoked }?.displayName ?: "Waiting"
        val lastHeartbeatMillis = botState.lastHeartbeatAt?.toEpochMilliseconds() ?: System.currentTimeMillis()
        val syncLagMillis = (System.currentTimeMillis() - lastHeartbeatMillis).coerceAtLeast(0)
        val displayRadarPairs = filterDisplayPairs(botState.activeCandidatePairs.map { it.value })
        val candidateSummary = (displayRadarPairs.ifEmpty { fallbackSnapshot?.radarPairs.orEmpty() })
            .take(3)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" • ") { it.lowercase() }
        val serverTimeline = buildServerLiveEntries(
            serverLogs = emptyList(),
            logs = logs,
            orders = orders,
            fallbackTimestamp = now.toEpochMilliseconds(),
        )
        val serverStatusMessage = serverTimeline.firstOrNull()?.message
            ?: botState.safeModeReason
            ?: candidateSummary?.let { "Server lagi pantau $it." }
            ?: defaultStatusMessage(botState.effectiveState)
        runtimePreferenceStore.setDesiredOn(botState.effectiveState != BotEffectiveState.STOPPED)

        _uiState.value = _uiState.value.copy(
            isBotRunning = botState.effectiveState != BotEffectiveState.STOPPED,
            effectiveState = botState.effectiveState,
            operatingMode = botState.operatingMode.name,
            edgeConfidence = botState.edgeConfidence.name,
            marketRegime = botState.marketRegime.name,
            riskLadderLevel = risk?.riskLadderLevel?.name ?: botState.riskLadderLevel.name,
            profitProtectionStatus = risk?.profitProtectionStatus?.name ?: botState.profitProtectionStatus.name,
            activeEngine = activeEngine,
            standbyEngine = standbyEngine,
            syncHealth = botState.syncHealth.name,
            internetPingLabel = fallbackSnapshot?.internetPingLabel() ?: _uiState.value.internetPingLabel,
            pnlTodayIdr = pnlTodayLabel,
            pnlTodayPctLabel = pnlTodayPctLabel,
            modalSaatIniIdr = modalSaatIniLabel,
            scanUniverseCount = fallbackSnapshot?.scanUniverseCount
                ?: _uiState.value.scanUniverseCount,
            radarPairs = displayRadarPairs.takeIf { it.isNotEmpty() }
                ?: fallbackSnapshot?.radarPairs?.takeIf { it.isNotEmpty() }
                ?: _uiState.value.radarPairs,
            drawdownPct = risk?.drawdownPct ?: _uiState.value.drawdownPct,
            dailyLossLimitPct = risk?.hardDailyLossLimitPct ?: _uiState.value.dailyLossLimitPct,
            riskBlocked = risk?.hardStopTriggered == true,
            pairAktif = botState.currentPair?.value
                ?.lowercase()
                ?.takeUnless { it in blockedDisplayPairs }
                ?: displayRadarPairs.firstOrNull()
                ?: fallbackSnapshot?.activePair?.takeIf { it.isNotBlank() && it != "-" }
                ?: _uiState.value.radarPairs.firstOrNull()
                ?: _uiState.value.pairAktif,
            leaseTerm = lease?.term?.value ?: botState.currentTerm.value,
            syncLagLabel = formatSyncLag(syncLagMillis),
            syncPathLabel = "Live Feed",
            lastUpdatedLabel = formatLastUpdated(now),
            statusMessage = serverStatusMessage,
            weeklyLearningSummary = weeklyReview?.let {
                "Week ${it.periodStart} - ${it.periodEnd} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
            } ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            positions = livePositions.ifEmpty { emptyList() },
            portfolio = portfolioSection,
            liveLogEntries = serverTimeline,
            logs = filterOperatorLogs(logs).map {
                LogUi(
                    level = it.level.name,
                    category = it.category,
                    message = it.message,
                    timeLabel = formatMomentLabel(it.recordedAt),
                )
            },
            trades = orders
                .filter {
                    it.executedQuantity.toDoubleOrZero() > 0.0 ||
                        it.status == com.kibot.shared.models.OrderStatus.FILLED ||
                        it.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED
                }
                .map {
                TradeUi(
                    pair = it.pairId.value,
                    side = "${it.side.name} • ${it.orderType.name}",
                    status = it.status.name,
                    detail = "${formatQuantity(max(it.executedQuantity.toDoubleOrZero(), it.originalQuantity.toDoubleOrZero()))} @ ${formatIdr(max(it.price.toDoubleOrZero(), 0.0))}",
                    timeLabel = formatMomentLabel(it.updatedAt),
                )
            },
            devices = devices.map {
                val isActive = it.deviceId == activeDeviceId
                DeviceStatusUi(
                    name = it.displayName,
                    online = true,
                    active = isActive,
                    heartbeat = if (isActive) "${((System.currentTimeMillis() - lastHeartbeatMillis) / 1000).coerceAtLeast(0)}s ago" else "Unknown",
                    health = if (isActive) botState.syncHealth.name else "Standby",
                )
            },
        )
        val refreshedSnapshot = LiveStatusSnapshot(
            updatedAtEpochMs = System.currentTimeMillis(),
            activePair = botState.currentPair?.value
                ?: _uiState.value.pairAktif
                .takeIf { it.isNotBlank() && it != "-" }
                ?: _uiState.value.radarPairs.firstOrNull()
                ?: "-",
            totalEquityIdr = modalSaatIniLabel,
            pnlTodayIdr = pnlTodayLabel,
            internetPingMs = fallbackSnapshot?.internetPingMs,
            scanUniverseCount = _uiState.value.scanUniverseCount,
            radarPairs = _uiState.value.radarPairs,
            holdings = livePositions.map {
                LiveHoldingUi(
                    asset = it.pair,
                    amount = it.quantity,
                    valueIdr = it.value,
                    pnlIdr = it.pnl.substringBefore(" ").takeIf { label -> label.isNotBlank() } ?: "",
                    pnlPctLabel = it.pnl.substringAfter(" ", "").takeIf { label -> label.isNotBlank() } ?: "",
                )
            },
            statusMessage = serverStatusMessage,
            liveLogEntries = serverTimeline,
        )
        liveStatusStore.update(refreshedSnapshot)
        runCatching {
            KiBotWidgetProvider.updateAll(appContext, refreshedSnapshot)
        }
        persistState()
    }

    private suspend fun syncFromServerMonitor(
        serverBundle: ServerMonitorBundle,
        now: Instant,
    ) {
        val state = serverBundle.state
        val totalEquityIdr = state.portfolioValueIdr.parseRupiahLabel()
        val valuedHoldings = buildServerValuedHoldings(state)
        val filteredRadarPairs = filterDisplayPairs(state.radarPairs)
        val livePair = state.topCandidate.lowercase().takeUnless { it in HIDDEN_STABLE_PAIRS }
            ?: filteredRadarPairs.firstOrNull()
            ?: "-"
        val liveEntries = buildServerLiveEntries(
            serverLogs = serverBundle.serverLogs,
            logs = serverBundle.logs,
            orders = serverBundle.orders,
            fallbackTimestamp = now.toEpochMilliseconds(),
        )
        val statusMessage = state.statusMessage
            .takeIf { it.isNotBlank() }
            ?: liveEntries.firstOrNull()?.message
            ?: "Server Oracle sedang memantau market live."
        val portfolio = buildPortfolioSection(
            now = now,
            history = serverBundle.riskHistory,
            totalEquityLabel = state.portfolioValueIdr,
            totalEquityIdr = totalEquityIdr,
            pnlTodayLabel = state.pnlTodayIdr,
            pnlTodayPctLabel = state.pnlTodayPctLabel,
            risk = null,
            holdings = valuedHoldings,
            cashReadyIdrOverride = buildServerCashReady(totalEquityIdr, valuedHoldings),
        )

        _uiState.value = _uiState.value.copy(
            isBotRunning = state.isBotRunning || state.effectiveState != BotEffectiveState.STOPPED,
            effectiveState = state.effectiveState,
            operatingMode = state.operatingMode,
            edgeConfidence = state.edgeConfidence,
            marketRegime = state.marketRegime,
            activeEngine = state.activeEngine.ifBlank { "Oracle Cloud Server" },
            standbyEngine = state.serverLocation.ifBlank { "-" },
            syncHealth = state.syncHealth,
            internetPingLabel = state.exchangePingMs.ifBlank { "--" },
            pnlTodayIdr = state.pnlTodayIdr,
            pnlTodayPctLabel = state.pnlTodayPctLabel,
            modalSaatIniIdr = state.portfolioValueIdr,
            scanUniverseCount = state.scanUniverseCount,
            radarPairs = filteredRadarPairs,
            pairAktif = livePair,
            syncLagLabel = state.lastHeartbeatLabel.ifBlank { "--" },
            syncPathLabel = "Live Server",
            lastUpdatedLabel = state.lastUpdatedLabel.ifBlank { formatLastUpdated(now) },
            statusMessage = statusMessage,
            weeklyLearningSummary = state.weeklyLearningSummary
                .takeIf { it.isNotBlank() }
                ?: serverBundle.weeklyReview?.let {
                    "Week ${it.periodStart} - ${it.periodEnd} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
                }
                ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = state.weeklyAdaptationSummary
                .takeIf { it.isNotBlank() }
                ?: serverBundle.weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                    ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            positions = buildServerPositions(state),
            portfolio = portfolio,
            liveLogEntries = liveEntries,
            logs = buildServerLogCards(serverBundle.serverLogs, serverBundle.logs),
            trades = serverBundle.orders
                .filter {
                    it.executedQuantity.toDoubleOrZero() > 0.0 ||
                        it.status == OrderStatus.FILLED ||
                        it.status == OrderStatus.PARTIALLY_FILLED
                }
                .map {
                    TradeUi(
                        pair = it.pairId.value.lowercase(),
                        side = "${it.side.name} • ${it.orderType.name}",
                        status = it.status.name,
                        detail = "${formatQuantity(max(it.executedQuantity.toDoubleOrZero(), it.originalQuantity.toDoubleOrZero()))} @ ${formatIdr(max(it.price.toDoubleOrZero(), 0.0))}",
                        timeLabel = formatMomentLabel(it.updatedAt),
                    )
                },
            devices = listOf(
                DeviceStatusUi(
                    name = state.serverLocation.ifBlank { "Oracle Cloud Server" },
                    online = true,
                    active = true,
                    heartbeat = state.lastHeartbeatLabel.ifBlank { "--" },
                    health = state.syncHealth,
                ),
            ),
        )

        val refreshedSnapshot = LiveStatusSnapshot(
            updatedAtEpochMs = state.lastUpdatedEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            activePair = livePair,
            totalEquityIdr = state.portfolioValueIdr,
            pnlTodayIdr = state.pnlTodayIdr,
            internetPingMs = state.exchangePingMs.parsePingMs(),
            scanUniverseCount = state.scanUniverseCount,
            radarPairs = filteredRadarPairs,
            holdings = buildServerLiveHoldings(state),
            statusMessage = statusMessage,
            liveLogEntries = liveEntries,
        )
        liveStatusStore.update(refreshedSnapshot)
        runCatching {
            KiBotWidgetProvider.updateAll(appContext, refreshedSnapshot)
        }
    }

    private fun syncFromUnavailableServerFeed(
        now: Instant,
    ) {
        val previousState = _uiState.value
        val fallbackSnapshot = liveStatusStore.current().takeIf { isUiUsableLiveSnapshot(it, now) }
        val activePair = fallbackSnapshot?.activePair
            ?.takeIf { it.isNotBlank() && it != "-" }
            ?: previousState.pairAktif
        val radarPairs = fallbackSnapshot?.radarPairs
            ?.map { it.lowercase() }
            ?.filter { it !in HIDDEN_STABLE_PAIRS }
            ?.take(10)
            ?.takeIf { it.isNotEmpty() }
            ?: previousState.radarPairs
        val positions = fallbackSnapshot?.toPositionCards()
            ?.takeIf { it.isNotEmpty() }
            ?: previousState.positions
        val fallbackStatus = "Feed server Oracle belum terhubung. App tetap menahan snapshot terakhir."
        val fallbackLogs = fallbackSnapshot?.liveLogEntries
            ?.takeIf { it.isNotEmpty() }
            ?: previousState.liveLogEntries
        _uiState.value = previousState.copy(
            isBotRunning = previousState.effectiveState != BotEffectiveState.STOPPED,
            effectiveState = if (previousState.effectiveState == BotEffectiveState.STOPPED) {
                BotEffectiveState.DEGRADED
            } else {
                previousState.effectiveState
            },
            activeEngine = "Oracle Cloud Server",
            standbyEngine = "-",
            syncHealth = "DEGRADED",
            syncPathLabel = "Live Server",
            syncLagLabel = "--",
            pairAktif = activePair ?: "-",
            radarPairs = radarPairs,
            positions = positions,
            statusMessage = fallbackStatus,
            lastUpdatedLabel = formatLastUpdated(now),
            liveLogEntries = fallbackLogs,
            logs = listOf(
                LogUi(
                    level = LogLevel.WARN.name,
                    category = "SERVER",
                    message = fallbackStatus,
                    timeLabel = formatLastUpdated(now),
                ),
            ),
            devices = listOf(
                DeviceStatusUi(
                    name = "Oracle Cloud Server",
                    online = false,
                    active = true,
                    heartbeat = "--",
                    health = "DEGRADED",
                ),
            ),
        )
    }

    private suspend fun fetchAuxiliaryData(
        gateway: ControlPlaneGateway,
        now: Instant,
    ): AuxiliarySyncCache {
        val cached = cachedAuxiliaryData
        if (cached != null && now.toEpochMilliseconds() - cached.fetchedAtEpochMs < AUXILIARY_CACHE_TTL_MS) {
            return cached
        }

        val refreshed = coroutineScope {
            val logsDeferred = async { gateway.fetchRecentLogs(botId, limit = 20) }
            val ordersDeferred = async { gateway.fetchRecentOrders(botId, limit = 20) }
            val weeklyReviewDeferred = async { gateway.fetchLatestWeeklyLearningSummary(botId) }
            AuxiliarySyncCache(
                fetchedAtEpochMs = now.toEpochMilliseconds(),
                logs = logsDeferred.await(),
                orders = ordersDeferred.await(),
                weeklyReview = weeklyReviewDeferred.await(),
            )
        }
        cachedAuxiliaryData = refreshed
        return refreshed
    }

    private suspend fun fetchServerMonitorState(): ServerMonitorBundle? {
        val baseUrl = normalizedServerMonitorBaseUrl ?: return null
        val stateDeferred = scope.async { fetchJsonObject("$baseUrl/api/state") }
        val logsDeferred = scope.async { fetchJsonArrayStrings("$baseUrl/api/logs") }
        val auxiliaryDeferred = scope.async {
            val gateway = controlPlaneGateway ?: return@async null
            runCatching { fetchAuxiliaryData(gateway, Clock.System.now()) }.getOrNull()
        }
        val riskHistoryDeferred = scope.async {
            val gateway = controlPlaneGateway ?: return@async emptyList()
            runCatching { gateway.fetchDailyRiskHistory(botId, days = 7) }.getOrDefault(emptyList())
        }

        val stateJson = stateDeferred.await() ?: return null
        val parsedState = parseServerMonitorState(stateJson) ?: return null
        val auxiliary = auxiliaryDeferred.await()
        return ServerMonitorBundle(
            state = parsedState,
            serverLogs = logsDeferred.await(),
            logs = auxiliary?.logs.orEmpty(),
            orders = auxiliary?.orders.orEmpty(),
            weeklyReview = auxiliary?.weeklyReview,
            riskHistory = riskHistoryDeferred.await(),
        )
    }

    fun isDesiredOn(): Boolean = runtimePreferenceStore.isDesiredOn()

    private fun persistState() {
        scope.launch {
            database.appDao().upsertBotState(
                CachedBotStateEntity(
                    botId = botId.value,
                    desiredOn = _uiState.value.isBotRunning,
                    effectiveState = _uiState.value.effectiveState.name,
                    activeEngine = _uiState.value.activeEngine,
                    standbyEngine = _uiState.value.standbyEngine,
                    syncHealth = _uiState.value.syncHealth,
                    pnlTodayIdr = _uiState.value.pnlTodayIdr,
                    drawdownPct = _uiState.value.drawdownPct,
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun ensureDeviceRegistered() {
        if (deviceRegistered) return
        val gateway = controlPlaneGateway ?: return
        val registration = deviceRegistration ?: return
        gateway.registerDevice(registration)
        deviceRegistered = true
    }

    private fun estimateEquityIdr(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
    ): Double? {
        if (balances.isEmpty()) return null
        val total = balances.sumOf { balance ->
            val units = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            when {
                units <= 0.0 -> 0.0
                balance.asset.equals("idr", ignoreCase = true) -> units
                else -> {
                    val priceIdr = quoteAssetPriceIdr(balance.asset, marketQuotes) ?: 0.0
                    units * priceIdr
                }
            }
        }
        return total.takeIf { it > 0.0 }
    }

    private fun buildLivePositions(
        holdings: List<ValuedHolding>,
    ): List<PositionCardUi> {
        return holdings
            .map { holding ->
                PositionCardUi(
                    pair = holding.asset,
                    quantity = "${formatQuantity(holding.quantity)} ${holding.asset}",
                    value = formatIdr(holding.valueIdr),
                    pnl = listOf(holding.pnlIdrLabel, holding.pnlPctLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                )
            }
            .sortedByDescending { extractCurrencyValue(it.value) }
    }

    private fun buildServerPositions(
        state: ServerMonitorState,
    ): List<PositionCardUi> {
        return state.holdingsDetailed
            .map { holding ->
                PositionCardUi(
                    pair = holding.assetCode.uppercase(),
                    quantity = holding.quantityLabel,
                    value = holding.valueIdrLabel,
                )
            }
            .sortedByDescending { extractCurrencyValue(it.value) }
    }

    private fun buildServerLiveHoldings(
        state: ServerMonitorState,
    ): List<LiveHoldingUi> {
        return state.holdingsDetailed.map { holding ->
            LiveHoldingUi(
                asset = holding.assetCode.uppercase(),
                amount = holding.quantityLabel,
                valueIdr = holding.valueIdrLabel,
            )
        }
    }

    private fun buildServerValuedHoldings(
        state: ServerMonitorState,
    ): List<ValuedHolding> {
        return state.holdingsDetailed
            .mapNotNull { holding ->
                val valueIdr = holding.valueIdrLabel.parseRupiahLabel() ?: return@mapNotNull null
                ValuedHolding(
                    asset = holding.assetCode.uppercase(),
                    quantity = extractQuantityValue(holding.quantityLabel),
                    valueIdr = valueIdr,
                )
            }
            .sortedByDescending { it.valueIdr }
    }

    private fun buildServerCashReady(
        totalEquityIdr: Double?,
        holdings: List<ValuedHolding>,
    ): Double? {
        val equity = totalEquityIdr ?: return null
        return (equity - holdings.sumOf { it.valueIdr }).coerceAtLeast(0.0)
    }

    private fun buildPortfolioSection(
        now: Instant,
        history: List<DailyEquityHistoryPoint>,
        totalEquityLabel: String,
        totalEquityIdr: Double?,
        pnlTodayLabel: String,
        pnlTodayPctLabel: String,
        risk: com.kibot.shared.models.DailyRiskSnapshot?,
        holdings: List<ValuedHolding>,
        cashReadyIdrOverride: Double?,
    ): PortfolioSectionUi {
        val currentEquity = totalEquityIdr ?: totalEquityLabel.parseRupiahLabel() ?: 0.0
        val allocationSource = holdings
            .map {
                AllocationSource(
                    label = it.asset,
                    valueIdr = it.valueIdr,
                )
            }
            .filter { it.valueIdr > 0.0 }

        val investedValue = allocationSource.sumOf { it.valueIdr }
        val cashReadyIdr = cashReadyIdrOverride
            ?.takeIf { it >= 0.0 }
            ?: (currentEquity - investedValue).coerceAtLeast(0.0)
        val cashReadyPct = if (currentEquity > 0.0) cashReadyIdr / currentEquity else 0.0
        val holdingsUnrealized = holdings.sumOf { it.pnlIdrLabel.parseRupiahLabel() ?: 0.0 }
        val totalUnrealized = holdingsUnrealized.takeIf { it != 0.0 }
            ?: risk?.unrealizedPnlIdr?.toDoubleOrZero()
            ?: 0.0
        val topConcentrationPct = allocationSource.maxOfOrNull { it.valueIdr }
            ?.let { largest -> if (currentEquity > 0.0) largest / currentEquity else 0.0 }
            ?: 0.0
        val chartPoints = buildPortfolioChartPoints(
            history = history,
            currentEquityIdr = currentEquity,
            now = now,
        )
        val oldestEquity = chartPoints.firstOrNull()?.valueIdr?.takeIf { it > 0.0 } ?: currentEquity
        val sevenDayDelta = currentEquity - oldestEquity
        val sevenDayPct = if (oldestEquity > 0.0) sevenDayDelta / oldestEquity else 0.0

        return PortfolioSectionUi(
            oneDayReturnLabel = pnlTodayLabel,
            oneDayReturnPctLabel = pnlTodayPctLabel,
            sevenDayReturnLabel = formatSignedIdr(sevenDayDelta),
            sevenDayReturnPctLabel = formatSignedPercent(sevenDayPct),
            cashReadyLabel = formatIdr(cashReadyIdr),
            cashReadyPctLabel = "%.0f%%".format(Locale.US, cashReadyPct * 100.0),
            totalUnrealizedLabel = formatSignedIdr(totalUnrealized),
            concentrationLabel = "Top 1 %.0f%%".format(Locale.US, topConcentrationPct * 100.0),
            chartPoints = chartPoints,
            allocations = buildAllocationItems(
                currentEquityIdr = currentEquity,
                cashReadyIdr = cashReadyIdr,
                holdings = allocationSource,
            ),
            lastUpdatedLabel = formatLastUpdated(now),
        )
    }

    private fun buildPortfolioChartPoints(
        history: List<DailyEquityHistoryPoint>,
        currentEquityIdr: Double,
        now: Instant,
    ): List<PortfolioTrendPointUi> {
        val points = history
            .sortedBy { it.date }
            .map {
                PortfolioTrendPointUi(
                    label = "${it.date.dayOfMonth}/${it.date.monthNumber}",
                    valueIdr = it.currentEquityIdr.toDoubleOrZero(),
                )
            }
            .toMutableList()
        val localDate = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
        val todayLabel = "${localDate.dayOfMonth}/${localDate.monthNumber}"
        if (points.none { it.label == todayLabel }) {
            points += PortfolioTrendPointUi(
                label = todayLabel,
                valueIdr = currentEquityIdr,
            )
        }
        return points.takeLast(7).ifEmpty {
            listOf(PortfolioTrendPointUi("Hari ini", currentEquityIdr))
        }
    }

    private fun buildAllocationItems(
        currentEquityIdr: Double,
        cashReadyIdr: Double,
        holdings: List<AllocationSource>,
    ): List<PortfolioAllocationUi> {
        val ranked = holdings.sortedByDescending { it.valueIdr }
        val topHoldings = ranked.take(3)
        val othersValue = ranked.drop(3).sumOf { it.valueIdr }
        return buildList {
            if (cashReadyIdr > 0.0) {
                val pct = if (currentEquityIdr > 0.0) cashReadyIdr / currentEquityIdr else 0.0
                add(
                    PortfolioAllocationUi(
                        label = "Cash",
                        valueLabel = formatIdr(cashReadyIdr),
                        pct = pct,
                        pctLabel = "%.0f%%".format(Locale.US, pct * 100.0),
                    ),
                )
            }
            topHoldings.forEach { holding ->
                val pct = if (currentEquityIdr > 0.0) holding.valueIdr / currentEquityIdr else 0.0
                add(
                    PortfolioAllocationUi(
                        label = holding.label,
                        valueLabel = formatIdr(holding.valueIdr),
                        pct = pct,
                        pctLabel = "%.0f%%".format(Locale.US, pct * 100.0),
                    ),
                )
            }
            if (othersValue > 0.0) {
                val pct = if (currentEquityIdr > 0.0) othersValue / currentEquityIdr else 0.0
                add(
                    PortfolioAllocationUi(
                        label = "Others",
                        valueLabel = formatIdr(othersValue),
                        pct = pct,
                        pctLabel = "%.0f%%".format(Locale.US, pct * 100.0),
                    ),
                )
            }
        }
    }

    private fun buildValuedHoldings(
        balances: List<BalanceSnapshot>,
        quotes: List<MarketQuote>,
        liveSnapshot: LiveStatusSnapshot?,
    ): List<ValuedHolding> {
        val snapshotHoldingsByAsset = liveSnapshot
            ?.holdings
            ?.associateBy { it.asset.uppercase() }
            .orEmpty()
        return balances
            .asSequence()
            .filterNot { it.asset.equals("idr", ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val valueIdr = quoteAssetPriceIdr(balance.asset, quotes)?.let { quantity * it } ?: 0.0
                if (valueIdr < MIN_VISIBLE_HOLDING_IDR) return@mapNotNull null
                val assetCode = balance.asset.uppercase()
                val snapshotHolding = snapshotHoldingsByAsset[assetCode]
                ValuedHolding(
                    asset = assetCode,
                    quantity = quantity,
                    valueIdr = valueIdr,
                    pnlIdrLabel = snapshotHolding?.pnlIdr.orEmpty(),
                    pnlPctLabel = snapshotHolding?.pnlPctLabel.orEmpty(),
                )
            }
            .sortedByDescending { it.valueIdr }
            .toList()
    }

    private fun buildSnapshotValuedHoldings(
        liveSnapshot: LiveStatusSnapshot,
    ): List<ValuedHolding> {
        return liveSnapshot.holdings
            .mapNotNull { holding ->
                val valueIdr = holding.valueIdr.parseRupiahLabel() ?: return@mapNotNull null
                if (valueIdr < MIN_VISIBLE_HOLDING_IDR) return@mapNotNull null
                ValuedHolding(
                    asset = holding.asset.uppercase(),
                    quantity = extractQuantityValue(holding.amount),
                    valueIdr = valueIdr,
                    pnlIdrLabel = holding.pnlIdr,
                    pnlPctLabel = holding.pnlPctLabel,
                )
            }
            .sortedByDescending { it.valueIdr }
    }

    private fun quoteAssetPriceIdr(asset: String, quotes: List<MarketQuote>): Double? {
        if (asset.equals("idr", ignoreCase = true)) return 1.0
        val directPair = "${asset.lowercase()}_idr"
        val directQuote = quotes.firstOrNull { it.pairId.value.equals(directPair, ignoreCase = true) }
        if (directQuote != null) {
            return directQuote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
                ?: directQuote.midPrice.toDoubleOrZero()
        }

        val usdtAssetQuote = quotes.firstOrNull { it.pairId.value.equals("${asset.lowercase()}_usdt", ignoreCase = true) }
        val usdtIdrQuote = quotes.firstOrNull { it.pairId.value.equals("usdt_idr", ignoreCase = true) }
        if (usdtAssetQuote != null && usdtIdrQuote != null) {
            val usdtAssetPrice = usdtAssetQuote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
                ?: usdtAssetQuote.midPrice.toDoubleOrZero()
            val usdtIdrPrice = usdtIdrQuote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
                ?: usdtIdrQuote.midPrice.toDoubleOrZero()
            return usdtAssetPrice * usdtIdrPrice
        }
        return null
    }

    private fun formatIdr(value: Double): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
            maximumFractionDigits = 0
        }
        return formatter.format(value)
    }

    private fun formatSignedIdr(value: Double): String {
        if (value.absoluteValue < 0.5) return "+${formatIdr(0.0)}"
        val prefix = if (value >= 0.0) "+" else "-"
        return prefix + formatIdr(value.absoluteValue)
    }

    private fun formatQuantity(value: Double): String {
        return when {
            value >= 100 -> "%,.0f".format(Locale.US, value)
            value >= 1 -> "%,.4f".format(Locale.US, value)
            else -> "%,.8f".format(Locale.US, value)
        }.trimEnd('0').trimEnd('.')
    }

    private fun formatSyncLag(valueMs: Long): String {
        return when {
            valueMs < 1_000 -> "${valueMs} ms"
            valueMs < 60_000 -> "${valueMs / 1_000}s"
            else -> "${valueMs / 60_000}m"
        }
    }

    private fun formatLastUpdated(now: Instant): String {
        val local = now.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$hh:$mm WIB"
    }

    private suspend fun fetchLanMacSnapshot(): LanMacSnapshot? {
        if (!isWifiConnected()) return null

        val nowMs = System.currentTimeMillis()
        val candidates = linkedSetOf<String>().apply {
            cachedLanEndpoint?.baseUrl?.let(::add)
            macLanSyncBaseUrl
                ?.trim()
                ?.removeSuffix("/")
                ?.removeSuffix("/api/lan/ping")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }

        candidates?.forEach { baseUrl ->
            probeLanBaseUrl(baseUrl)?.let {
                cachedLanEndpoint = CachedLanEndpoint(baseUrl, nowMs)
                return it
            }
        }

        val shouldDiscover = nowMs - lastLanDiscoveryAttemptEpochMs >= LAN_DISCOVERY_RETRY_MS
        if (!shouldDiscover) return null

        lastLanDiscoveryAttemptEpochMs = nowMs
        val discoveredBaseUrl = macLanDiscovery.discoverBaseUrl()
        if (discoveredBaseUrl != null) {
            probeLanBaseUrl(discoveredBaseUrl)?.let {
                cachedLanEndpoint = CachedLanEndpoint(discoveredBaseUrl, nowMs)
                return it
            }
        }
        return null
    }

    private fun probeLanBaseUrl(baseUrl: String): LanMacSnapshot? {
        return runCatching {
            val connection = (URL("$baseUrl/api/lan/ping").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1_000
                readTimeout = 1_000
                requestMethod = "GET"
            }
            connection.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                LanMacSnapshot(
                    reachable = json.optBoolean("ok", false),
                    baseUrl = baseUrl,
                )
            }
        }.getOrNull()?.takeIf { it.reachable }
    }

    private fun resolveSyncPathLabel(
        lanSnapshot: LanMacSnapshot?,
    ): String {
        if (lanSnapshot?.reachable == true) return "Live + LAN"
        val verifiedAt = cachedLanEndpoint?.verifiedAtEpochMs ?: 0L
        if (verifiedAt > 0L && System.currentTimeMillis() - verifiedAt <= LAN_VERIFIED_TTL_MS) {
            return "Live + LAN"
        }
        return "Live Feed"
    }

    private fun isWifiConnected(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun extractCurrencyValue(label: String): Double {
        return label
            .replace("~", "")
            .replace("+", "")
            .replace("-", "")
            .replace("Rp", "")
            .replace(".", "")
            .replace(",", ".")
            .toDoubleOrNull()
            ?: 0.0
    }

    private fun LiveStatusSnapshot.toPositionCards(): List<PositionCardUi> {
        return holdings
            .filterNot { it.valueIdr.trim() in setOf("Rp0", "+Rp0", "-Rp0", "~Rp0") }
            .map {
                PositionCardUi(
                    pair = it.asset,
                    quantity = it.amount,
                    value = it.valueIdr,
                    pnl = listOf(it.pnlIdr, it.pnlPctLabel)
                        .filter { label -> label.isNotBlank() }
                        .joinToString(" "),
                )
            }
    }

    private fun isFreshLiveSnapshot(
        snapshot: LiveStatusSnapshot,
        now: Instant,
    ): Boolean {
        if (snapshot.updatedAtEpochMs <= 0L) return false
        return now.toEpochMilliseconds() - snapshot.updatedAtEpochMs <= LIVE_SNAPSHOT_FRESHNESS_MS
    }

    private fun isUiUsableLiveSnapshot(
        snapshot: LiveStatusSnapshot,
        now: Instant,
    ): Boolean {
        if (snapshot.updatedAtEpochMs <= 0L) return false
        return now.toEpochMilliseconds() - snapshot.updatedAtEpochMs <= LIVE_SNAPSHOT_UI_TTL_MS
    }

    private fun LiveStatusSnapshot.derivedPnlPctLabel(): String {
        val equity = totalEquityIdr.parseRupiahLabel() ?: return "+0.0%"
        val pnl = pnlTodayIdr.parseRupiahLabel() ?: return "+0.0%"
        val opening = (equity - pnl).takeIf { it > 0.0 } ?: return "+0.0%"
        val pct = kotlin.math.abs(pnl / opening)
        val prefix = if (pnlTodayIdr.trim().startsWith("-") || pnl < 0.0) "-" else "+"
        return prefix + "%.1f%%".format(Locale.US, pct * 100.0)
    }

    private fun LiveStatusSnapshot.internetPingLabel(): String {
        return internetPingMs?.let { "${it} ms" } ?: "--"
    }

    private fun String.parseRupiahLabel(): Double? {
        val cleaned = trim()
            .replace("~", "")
            .replace("Rp", "")
            .replace(".", "")
            .replace(",", ".")
            .replace("+", "")
        val numeric = cleaned.toDoubleOrNull() ?: return null
        return if (trim().startsWith("-")) -numeric else numeric
    }

    private fun formatSignedPercent(value: Double): String {
        val pct = value * 100.0
        val prefix = if (pct >= 0.0) "+" else "-"
        return prefix + "%.1f%%".format(Locale.US, pct.absoluteValue)
    }

    private fun formatMomentLabel(instant: Instant): String {
        val local = instant.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$hh:$mm"
    }

    private fun extractQuantityValue(label: String): Double {
        val numeric = label
            .trim()
            .substringBeforeLast(' ')
            .replace(",", "")
        return numeric.toDoubleOrNull() ?: 0.0
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            connection.inputStream.bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
        }.getOrNull()
    }

    private fun fetchJsonArrayStrings(url: String): List<String> {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
            }
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                val array = org.json.JSONArray(body)
                List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseServerMonitorState(
        json: JSONObject,
    ): ServerMonitorState? {
        val effectiveState = runCatching {
            BotEffectiveState.valueOf(json.optString("effectiveState", BotEffectiveState.STOPPED.name))
        }.getOrDefault(BotEffectiveState.STOPPED)
        val portfolioValueIdr = json.optString("portfolioValueIdr").takeIf { it.isNotBlank() } ?: return null
        return ServerMonitorState(
            isBotRunning = json.optBoolean("isBotRunning", effectiveState != BotEffectiveState.STOPPED),
            effectiveState = effectiveState,
            operatingMode = json.optString("operatingMode", "-"),
            edgeConfidence = json.optString("edgeConfidence", "-"),
            marketRegime = json.optString("marketRegime", "-"),
            topCandidate = json.optString("topCandidate", "-"),
            radarPairs = json.optJSONArray("radarPairs").toStringList(),
            scanUniverseCount = json.optInt("scanUniverseCount", 0),
            portfolioValueIdr = portfolioValueIdr,
            pnlTodayIdr = json.optString("pnlTodayIdr", "+Rp0"),
            pnlTodayPctLabel = json.optString("pnlTodayPctLabel", "+0.0%"),
            syncPathLabel = json.optString("syncPathLabel", "Live Server"),
            activeEngine = json.optString("activeEngine", "Oracle Cloud Server"),
            standbyEngine = json.optString("standbyEngine", "-"),
            syncHealth = json.optString("syncHealth", "UNKNOWN"),
            leaseTerm = json.optLong("leaseTerm", 0L),
            healthSummary = json.optString("healthSummary", "-"),
            weeklyLearningSummary = json.optString("weeklyLearningSummary", ""),
            weeklyAdaptationSummary = json.optString("weeklyAdaptationSummary", ""),
            lastHeartbeatLabel = json.optString("lastHeartbeatLabel", ""),
            lastUpdatedLabel = json.optString("lastUpdatedLabel", ""),
            statusMessage = json.optString("statusMessage", ""),
            lastUpdatedEpochMs = json.optLong("lastUpdatedEpochMs", 0L),
            serverLocation = json.optString("serverLocation", "Oracle Cloud Server"),
            serverUptime = json.optString("serverUptime", "-"),
            exchangePingMs = json.optString("exchangePingMs", "--"),
            holdingsDetailed = json.optJSONArray("holdingsDetailed").toHoldingDetailList(),
        )
    }

    private fun buildServerLiveEntries(
        serverLogs: List<String>,
        logs: List<com.kibot.shared.models.AuditLogRecord>,
        orders: List<OrderSnapshot>,
        fallbackTimestamp: Long,
    ): List<com.kibot.android.runtime.LiveLogEntry> {
        val serverEntries = serverLogs.mapIndexedNotNull { index, line ->
            toServerLiveEntry(
                line = line,
                fallbackTimestamp = fallbackTimestamp - (index * 1_000L),
            )
        }
        val tradeEntries = orders.mapNotNull(::toServerLiveEntry)
        val operatorEntries = filterOperatorLogs(logs).mapNotNull(::toServerLiveEntry)
        val merged = (serverEntries + tradeEntries + operatorEntries)
            .sortedByDescending { it.timestampEpochMs }
            .distinctBy { "${it.category}|${it.message}" }
        return if (merged.isNotEmpty()) {
            merged.take(10)
        } else {
            listOf(
                com.kibot.android.runtime.LiveLogEntry(
                    timestampEpochMs = fallbackTimestamp,
                    category = "SYNC",
                    message = "Server Oracle lagi sinkron dan pantau market.",
                ),
            )
        }
    }

    private fun filterOperatorLogs(
        logs: List<com.kibot.shared.models.AuditLogRecord>,
    ): List<com.kibot.shared.models.AuditLogRecord> {
        return logs
            .filterNot { log ->
                val category = log.category.uppercase()
                val message = log.message.lowercase()
                category == "AUTH" ||
                    message.contains("control-plane") ||
                    message.contains("registered with control-plane") ||
                    message.contains("registered to control plane") ||
                    message.contains("device registered") ||
                    (
                        category in setOf("ROTASI", "SCAN", "TARGET") &&
                            (
                                HIDDEN_STABLE_PAIRS.any { pair -> message.contains(pair) }
                                )
                        )
            }
            .sortedByDescending { it.recordedAt }
    }

    private fun toServerLiveEntry(
        order: OrderSnapshot,
    ): com.kibot.android.runtime.LiveLogEntry? {
        val quantity = max(order.executedQuantity.toDoubleOrZero(), order.originalQuantity.toDoubleOrZero())
        if (quantity <= 0.0) return null
        val price = order.price.toDoubleOrZero()
        val pair = order.pairId.value.lowercase()
        val category = when (order.side) {
            com.kibot.shared.models.OrderSide.BUY -> if (order.status == OrderStatus.OPEN) "TARGET" else "BUY"
            com.kibot.shared.models.OrderSide.SELL -> if (order.status == OrderStatus.OPEN) "TARGET" else "SELL"
        }
        val message = when (order.status) {
            OrderStatus.OPEN, OrderStatus.SUBMITTING ->
                "Server pasang ${order.side.name.lowercase()} $pair @ ${formatIdr(price)} dan lagi tunggu fill."
            OrderStatus.PARTIALLY_FILLED ->
                "${order.side.name} $pair mulai keisi ${formatQuantity(quantity)} @ ${formatIdr(price)}."
            OrderStatus.FILLED ->
                "${order.side.name} $pair sukses ${formatQuantity(quantity)} @ ${formatIdr(price)}."
            OrderStatus.CANCELED ->
                "Order $pair dibatalkan karena momentum berubah."
            else -> return null
        }
        return com.kibot.android.runtime.LiveLogEntry(
            timestampEpochMs = order.updatedAt.toEpochMilliseconds(),
            category = category,
            message = message,
        )
    }

    private fun toServerLiveEntry(
        log: com.kibot.shared.models.AuditLogRecord,
    ): com.kibot.android.runtime.LiveLogEntry? {
        val message = log.message.trim()
        if (message.isBlank()) return null
        return com.kibot.android.runtime.LiveLogEntry(
            timestampEpochMs = log.recordedAt.toEpochMilliseconds(),
            category = log.category.uppercase(),
            message = message,
        )
    }

    private fun toServerLiveEntry(
        line: String,
        fallbackTimestamp: Long,
    ): com.kibot.android.runtime.LiveLogEntry? {
        val message = line.trim()
        if (message.isBlank()) return null
        val category = inferServerLogCategory(message)
        if (
            category in setOf("ROTASI", "SCAN", "TARGET") &&
            HIDDEN_STABLE_PAIRS.any { pair -> message.lowercase().contains(pair) }
        ) {
            return null
        }
        return com.kibot.android.runtime.LiveLogEntry(
            timestampEpochMs = fallbackTimestamp,
            category = category,
            message = message,
        )
    }

    private fun buildServerLogCards(
        serverLogs: List<String>,
        logs: List<com.kibot.shared.models.AuditLogRecord>,
    ): List<LogUi> {
        val serverCards = serverLogs.map { line ->
            LogUi(
                level = "INFO",
                category = inferServerLogCategory(line),
                message = line.trim(),
                timeLabel = formatLastUpdated(Clock.System.now()),
            )
        }
        val operatorCards = filterOperatorLogs(logs).map {
            LogUi(
                level = it.level.name,
                category = it.category,
                message = it.message,
                timeLabel = formatMomentLabel(it.recordedAt),
            )
        }
        return (serverCards + operatorCards)
            .filter { it.message.isNotBlank() }
            .distinctBy { "${it.category}|${it.message}" }
            .take(20)
    }

    private fun filterDisplayPairs(
        pairs: List<String>,
    ): List<String> {
        return pairs
            .map { it.lowercase() }
            .filter { it.isNotBlank() && it !in HIDDEN_STABLE_PAIRS }
            .distinct()
            .take(10)
    }

    companion object {
        private val HIDDEN_STABLE_PAIRS = setOf("usdt_idr", "usdc_idr", "indr_idr")
        private const val MIN_VISIBLE_HOLDING_IDR = 1.0
        private const val AUXILIARY_CACHE_TTL_MS = 20_000L
        private const val LIVE_SNAPSHOT_FRESHNESS_MS = 15_000L
        private const val LIVE_SNAPSHOT_UI_TTL_MS = 60_000L
        private const val LAN_DISCOVERY_RETRY_MS = 60_000L
        private const val LAN_VERIFIED_TTL_MS = 120_000L
    }

    private fun defaultStatusMessage(state: BotEffectiveState): String {
        return when (state) {
            BotEffectiveState.STOPPED -> "Bot sedang berhenti."
            BotEffectiveState.STARTING -> "Sinkronisasi engine berjalan."
            BotEffectiveState.RUNNING -> "Sync live Indodax aktif."
            BotEffectiveState.DEGRADED -> "Engine degraded. Menunggu sync pulih."
            BotEffectiveState.SAFE_MODE -> "Safe mode active. Review status before resuming."
        }
    }
}

private data class LanMacSnapshot(
    val reachable: Boolean,
    val baseUrl: String,
)

private data class AuxiliarySyncCache(
    val fetchedAtEpochMs: Long,
    val logs: List<com.kibot.shared.models.AuditLogRecord>,
    val orders: List<com.kibot.shared.models.OrderSnapshot>,
    val weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
)

private data class ServerMonitorBundle(
    val state: ServerMonitorState,
    val serverLogs: List<String>,
    val logs: List<com.kibot.shared.models.AuditLogRecord>,
    val orders: List<com.kibot.shared.models.OrderSnapshot>,
    val weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
    val riskHistory: List<DailyEquityHistoryPoint>,
)

private data class ServerMonitorState(
    val isBotRunning: Boolean,
    val effectiveState: BotEffectiveState,
    val operatingMode: String,
    val edgeConfidence: String,
    val marketRegime: String,
    val topCandidate: String,
    val radarPairs: List<String>,
    val scanUniverseCount: Int,
    val portfolioValueIdr: String,
    val pnlTodayIdr: String,
    val pnlTodayPctLabel: String,
    val syncPathLabel: String,
    val activeEngine: String,
    val standbyEngine: String,
    val syncHealth: String,
    val leaseTerm: Long,
    val healthSummary: String,
    val weeklyLearningSummary: String,
    val weeklyAdaptationSummary: String,
    val lastHeartbeatLabel: String,
    val lastUpdatedLabel: String,
    val statusMessage: String,
    val lastUpdatedEpochMs: Long,
    val serverLocation: String,
    val serverUptime: String,
    val exchangePingMs: String,
    val holdingsDetailed: List<ServerHoldingDetail>,
)

private data class ServerHoldingDetail(
    val assetCode: String,
    val assetLabel: String,
    val quantityLabel: String,
    val valueIdrLabel: String,
)

private data class CachedLanEndpoint(
    val baseUrl: String,
    val verifiedAtEpochMs: Long,
)

private data class AllocationSource(
    val label: String,
    val valueIdr: Double,
)

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> optString(index) }
        .filter { it.isNotBlank() }
}

private fun org.json.JSONArray?.toHoldingDetailList(): List<ServerHoldingDetail> {
    if (this == null) return emptyList()
    return List(length()) { index -> optJSONObject(index) }
        .mapNotNull { item ->
            item ?: return@mapNotNull null
            val assetCode = item.optString("assetCode").ifBlank { return@mapNotNull null }
            ServerHoldingDetail(
                assetCode = assetCode,
                assetLabel = item.optString("assetLabel", assetCode),
                quantityLabel = item.optString("quantityLabel", "-"),
                valueIdrLabel = item.optString("valueIdrLabel", "Rp0"),
            )
        }
}

private fun String.parsePingMs(): Long? {
    return lowercase()
        .replace("ms", "")
        .replace(" ", "")
        .toLongOrNull()
}

private fun inferServerLogCategory(message: String): String {
    val normalized = message.lowercase()
    return when {
        normalized.contains("buy") || normalized.contains("beli") -> "BUY"
        normalized.contains("sell") || normalized.contains("jual") -> "SELL"
        normalized.contains("rotasi") -> "ROTASI"
        normalized.contains("scan") -> "SCAN"
        normalized.contains("target") || normalized.contains("bidik") -> "TARGET"
        normalized.contains("risk") || normalized.contains("stop") -> "RISK"
        normalized.contains("profit") -> "PROFIT"
        normalized.contains("loss") || normalized.contains("minus") -> "LOSS"
        else -> "SYNC"
    }
}
