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
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.DailyEquityHistoryPoint
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.MarketQuote
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
    private var lastLanDiscoveryAttemptEpochMs: Long = 0L

    private data class ValuedHolding(
        val asset: String,
        val quantity: Double,
        val valueIdr: Double,
        val pnlIdrLabel: String = "",
        val pnlPctLabel: String = "",
    )

    fun toggleBot(): Boolean {
        val current = _uiState.value
        val nextRunning = !current.isBotRunning
        _uiState.value = current.copy(
            isBotRunning = nextRunning,
            effectiveState = if (nextRunning) BotEffectiveState.RUNNING else BotEffectiveState.STOPPED,
            statusMessage = if (nextRunning) "Bot start requested." else "Stop aman dimulai. Bot blok entry baru lalu rapikan posisi dulu.",
        )
        runtimePreferenceStore.setDesiredOn(nextRunning)

        persistState()
        scope.launch {
            controlPlaneGateway?.setDesiredState(
                botId = botId,
                desiredState = if (nextRunning) BotDesiredState.ON else BotDesiredState.OFF,
            )
            syncNow()
        }
        return nextRunning
    }

    fun dispatchCommand(action: EngineAction) {
        val current = _uiState.value
        _uiState.value = when (action) {
            EngineAction.RequestTakeover -> current.copy(statusMessage = "Request takeover queued.")
            EngineAction.ForceSafeTakeover -> current.copy(statusMessage = "Force safe takeover queued.")
            EngineAction.ReleaseControl -> current.copy(statusMessage = "Release control queued.")
            EngineAction.SyncNow -> current.copy(statusMessage = "Manual sync started.")
        }

        persistState()
        scope.launch {
            ensureDeviceRegistered()
            val gateway = controlPlaneGateway ?: return@launch
            val deviceId = deviceRegistration?.deviceId ?: return@launch
            when (action) {
                EngineAction.RequestTakeover -> {
                    val activeDevice = gateway.fetchBotState(botId)?.activeDeviceId?.takeIf { it != deviceId }
                    gateway.enqueueCommand(
                        botId = botId,
                        createdBy = deviceId,
                        commandType = CommandType.REQUEST_TAKEOVER,
                        targetDeviceId = activeDevice,
                    )
                }

                EngineAction.ForceSafeTakeover -> gateway.enqueueCommand(
                    botId = botId,
                    createdBy = deviceId,
                    commandType = CommandType.FORCE_SAFE_TAKEOVER,
                    targetDeviceId = deviceId,
                )

                EngineAction.ReleaseControl -> gateway.enqueueCommand(
                    botId = botId,
                    createdBy = deviceId,
                    commandType = CommandType.RELEASE_CONTROL,
                    targetDeviceId = deviceId,
                )

                EngineAction.SyncNow -> gateway.enqueueCommand(
                    botId = botId,
                    createdBy = deviceId,
                    commandType = CommandType.SYNC_NOW,
                    targetDeviceId = deviceId,
                )
            }
            syncNow()
        }
    }

    suspend fun syncNow() {
        val gateway = controlPlaneGateway ?: return
        ensureDeviceRegistered()
        val now = Clock.System.now()
        val jakartaDate = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
        val storedLiveSnapshot = liveStatusStore.current().takeIf { it.updatedAtEpochMs > 0L }
        val currentLiveSnapshot = storedLiveSnapshot?.takeIf { isFreshLiveSnapshot(it, now) }
        val uiPreferredSnapshot = currentLiveSnapshot ?: storedLiveSnapshot?.takeIf { isUiUsableLiveSnapshot(it, now) }

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
        val lanSnapshotDeferred = scope.async { fetchLanMacSnapshot() }
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
        val lanSnapshot = lanSnapshotDeferred.await()
        val auxiliary = fetchAuxiliaryData(gateway, now)
        val logs = auxiliary.logs
        val orders = auxiliary.orders
        val weeklyReview = auxiliary.weeklyReview
        val liveEquityIdr = estimateEquityIdr(liveBalances, liveQuotes)
            ?: uiPreferredSnapshot?.totalEquityIdr?.parseRupiahLabel()
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
            uiPreferredSnapshot != null -> uiPreferredSnapshot.pnlTodayIdr.parseRupiahLabel()
            risk != null -> risk.realizedPnlIdr.toDoubleOrZero() + risk.unrealizedPnlIdr.toDoubleOrZero()
            else -> 0.0
        }
        val modalSaatIniLabel = liveEquityIdr?.let(::formatIdr)
            ?: uiPreferredSnapshot?.totalEquityIdr
            ?: risk?.currentEquityIdr?.let { formatIdr(it.toDoubleOrZero()) }
            ?: _uiState.value.modalSaatIniIdr
        val pnlTodayLabel = livePnlTodayIdr?.let(::formatSignedIdr)
            ?: uiPreferredSnapshot?.pnlTodayIdr
            ?: formatSignedIdr(0.0)
        val pnlTodayPctLabel = when {
            livePnlTodayIdr != null && openingEquityIdr != null && openingEquityIdr > 0.0 ->
                formatSignedPercent(livePnlTodayIdr / openingEquityIdr)
            uiPreferredSnapshot != null -> uiPreferredSnapshot.derivedPnlPctLabel()
            else -> "+0.0%"
        }
        val valuedHoldings = when {
            liveBalances.isNotEmpty() && liveQuotes.isNotEmpty() -> buildValuedHoldings(
                balances = liveBalances,
                quotes = liveQuotes,
                liveSnapshot = uiPreferredSnapshot,
            )
            !uiPreferredSnapshot?.holdings.isNullOrEmpty() -> buildSnapshotValuedHoldings(uiPreferredSnapshot!!)
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

        val activeDeviceId = lease?.currentHolder ?: botState.activeDeviceId
        val activeEngine = devices.firstOrNull { it.deviceId == activeDeviceId }?.displayName ?: "Unknown"
        val standbyEngine = devices.firstOrNull { it.deviceId != activeDeviceId && !it.isRevoked }?.displayName ?: "Waiting"
        val lastHeartbeatMillis = botState.lastHeartbeatAt?.toEpochMilliseconds() ?: System.currentTimeMillis()
        val syncLagMillis = (System.currentTimeMillis() - lastHeartbeatMillis).coerceAtLeast(0)
        val candidateSummary = (uiPreferredSnapshot?.radarPairs ?: botState.activeCandidatePairs.map { it.value })
            .take(3)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" • ") { it.lowercase() }
        runtimePreferenceStore.setDesiredOn(botState.desiredState == BotDesiredState.ON)

        _uiState.value = _uiState.value.copy(
            isBotRunning = botState.desiredState == BotDesiredState.ON,
            effectiveState = botState.effectiveState,
            operatingMode = botState.operatingMode.name,
            edgeConfidence = botState.edgeConfidence.name,
            marketRegime = botState.marketRegime.name,
            riskLadderLevel = risk?.riskLadderLevel?.name ?: botState.riskLadderLevel.name,
            profitProtectionStatus = risk?.profitProtectionStatus?.name ?: botState.profitProtectionStatus.name,
            activeEngine = activeEngine,
            standbyEngine = standbyEngine,
            syncHealth = botState.syncHealth.name,
            internetPingLabel = uiPreferredSnapshot?.internetPingLabel() ?: _uiState.value.internetPingLabel,
            pnlTodayIdr = pnlTodayLabel,
            pnlTodayPctLabel = pnlTodayPctLabel,
            modalSaatIniIdr = modalSaatIniLabel,
            scanUniverseCount = uiPreferredSnapshot?.scanUniverseCount
                ?: _uiState.value.scanUniverseCount,
            radarPairs = uiPreferredSnapshot?.radarPairs?.takeIf { it.isNotEmpty() }
                ?: botState.activeCandidatePairs.map { it.value }.takeIf { it.isNotEmpty() }
                ?: _uiState.value.radarPairs,
            drawdownPct = risk?.drawdownPct ?: _uiState.value.drawdownPct,
            dailyLossLimitPct = risk?.hardDailyLossLimitPct ?: _uiState.value.dailyLossLimitPct,
            riskBlocked = risk?.hardStopTriggered == true,
            pairAktif = uiPreferredSnapshot?.activePair
                ?.takeIf { it.isNotBlank() && it != "-" }
                ?: botState.currentPair?.value
                ?: botState.activeCandidatePairs.firstOrNull()?.value
                ?: _uiState.value.radarPairs.firstOrNull()
                ?: _uiState.value.pairAktif,
            leaseTerm = lease?.term?.value ?: botState.currentTerm.value,
            syncLagLabel = formatSyncLag(syncLagMillis),
            syncPathLabel = resolveSyncPathLabel(lanSnapshot),
            lastUpdatedLabel = formatLastUpdated(now),
            statusMessage = uiPreferredSnapshot?.statusMessage?.takeIf { it.isNotBlank() }
                ?: botState.safeModeReason
                ?: candidateSummary?.let { "Radar aktif: $it" }
                ?: defaultStatusMessage(botState.effectiveState),
            weeklyLearningSummary = weeklyReview?.let {
                "Week ${it.periodStart} - ${it.periodEnd} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
            } ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            positions = livePositions.ifEmpty { emptyList() },
            portfolio = portfolioSection,
            liveLogEntries = uiPreferredSnapshot?.liveLogEntries ?: _uiState.value.liveLogEntries,
            logs = logs.map {
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
            updatedAtEpochMs = uiPreferredSnapshot?.updatedAtEpochMs ?: System.currentTimeMillis(),
            activePair = botState.currentPair?.value
                ?: _uiState.value.pairAktif
                .takeIf { it.isNotBlank() && it != "-" }
                ?: _uiState.value.radarPairs.firstOrNull()
                ?: "-",
            totalEquityIdr = modalSaatIniLabel,
            pnlTodayIdr = pnlTodayLabel,
            internetPingMs = uiPreferredSnapshot?.internetPingMs,
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
            statusMessage = _uiState.value.statusMessage,
            liveLogEntries = uiPreferredSnapshot?.liveLogEntries ?: _uiState.value.liveLogEntries,
        )
        liveStatusStore.update(refreshedSnapshot)
        runCatching {
            KiBotWidgetProvider.updateAll(appContext, refreshedSnapshot)
        }
        persistState()
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

    companion object {
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

private data class CachedLanEndpoint(
    val baseUrl: String,
    val verifiedAtEpochMs: Long,
)

private data class AllocationSource(
    val label: String,
    val valueIdr: Double,
)
