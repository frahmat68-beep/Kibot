package com.kibot.android.data.local

import com.kibot.android.security.SecureCredentialStore
import com.kibot.android.ui.EngineAction
import com.kibot.android.ui.KiBotUiState
import com.kibot.android.runtime.RuntimePreferenceStore
import com.kibot.core.ControlPlaneGateway
import com.kibot.core.DeviceRegistration
import com.kibot.core.ExchangeGateway
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.MarketQuote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

class AppRepository(
    private val database: AppDatabase,
    private val credentialStore: SecureCredentialStore,
    private val runtimePreferenceStore: RuntimePreferenceStore,
    private val exchangeGateway: ExchangeGateway,
    private val controlPlaneGateway: ControlPlaneGateway? = null,
    private val deviceRegistration: DeviceRegistration? = null,
    private val botId: BotId = BotId("main"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _uiState = MutableStateFlow(KiBotUiState.preview())
    val uiState: StateFlow<KiBotUiState> = _uiState.asStateFlow()
    private var deviceRegistered = false

    fun toggleBot(): Boolean {
        val current = _uiState.value
        val nextRunning = !current.isBotRunning
        _uiState.value = current.copy(
            isBotRunning = nextRunning,
            effectiveState = if (nextRunning) BotEffectiveState.RUNNING else BotEffectiveState.STOPPED,
            statusMessage = if (nextRunning) "Bot start requested." else "Bot stop requested.",
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

        val botState = gateway.fetchBotState(botId) ?: return
        val lease = gateway.fetchLease(botId)
        val devices = gateway.fetchDevices(botId)
        val risk = gateway.fetchDailyRisk(
            botId = botId,
            date = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Jakarta")).date,
        )
        val logs = gateway.fetchRecentLogs(botId, limit = 20)
        val orders = gateway.fetchRecentOrders(botId, limit = 20)
        val weeklyReview = gateway.fetchLatestWeeklyLearningSummary(botId)
        val liveBalances = runCatching { exchangeGateway.fetchBalances() }.getOrDefault(emptyList())
        val liveQuotes = runCatching { exchangeGateway.fetchMarketQuotes() }.getOrDefault(emptyList())
        val liveEquityIdr = estimateEquityIdr(liveBalances, liveQuotes)
        val openingEquityIdr = risk?.openingEquityIdr?.toDoubleOrZero()
        val livePnlTodayIdr = when {
            liveEquityIdr != null && openingEquityIdr != null -> liveEquityIdr - openingEquityIdr
            risk != null -> risk.realizedPnlIdr.toDoubleOrZero() + risk.unrealizedPnlIdr.toDoubleOrZero()
            else -> null
        }
        val modalSaatIniLabel = liveEquityIdr?.let(::formatIdr)
            ?: risk?.currentEquityIdr?.let { formatIdr(it.toDoubleOrZero()) }
            ?: _uiState.value.modalSaatIniIdr
        val pnlTodayLabel = livePnlTodayIdr?.let(::formatSignedIdr)
            ?: risk?.realizedPnlIdr?.let { formatSignedIdr(it.toDoubleOrZero()) }
            ?: _uiState.value.pnlTodayIdr

        val activeDeviceId = lease?.currentHolder ?: botState.activeDeviceId
        val activeEngine = devices.firstOrNull { it.deviceId == activeDeviceId }?.displayName ?: "Unknown"
        val standbyEngine = devices.firstOrNull { it.deviceId != activeDeviceId && !it.isRevoked }?.displayName ?: "Waiting"
        val lastHeartbeatMillis = botState.lastHeartbeatAt?.toEpochMilliseconds() ?: System.currentTimeMillis()

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
            pnlTodayIdr = pnlTodayLabel,
            modalSaatIniIdr = modalSaatIniLabel,
            drawdownPct = risk?.drawdownPct ?: _uiState.value.drawdownPct,
            dailyLossLimitPct = risk?.hardDailyLossLimitPct ?: _uiState.value.dailyLossLimitPct,
            riskBlocked = risk?.hardStopTriggered == true,
            pairAktif = botState.currentPair?.value ?: "-",
            leaseTerm = lease?.term?.value ?: botState.currentTerm.value,
            syncLagLabel = "${(System.currentTimeMillis() - lastHeartbeatMillis).coerceAtLeast(0)} ms",
            statusMessage = botState.safeModeReason ?: when (botState.effectiveState) {
                BotEffectiveState.SAFE_MODE -> "Safe mode active. Review status before resuming."
                else -> _uiState.value.statusMessage
            },
            weeklyLearningSummary = weeklyReview?.let {
                "Week ${it.periodStart} - ${it.periodEnd} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
            } ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            logs = logs.map {
                com.kibot.android.ui.LogUi(
                    level = it.level.name,
                    category = it.category,
                    message = it.message,
                )
            },
            trades = orders.map {
                com.kibot.android.ui.TradeUi(
                    pair = it.pairId.value,
                    side = it.side.name,
                    pnl = it.status.name,
                )
            },
            devices = devices.map {
                val isActive = it.deviceId == activeDeviceId
                com.kibot.android.ui.DeviceStatusUi(
                    name = it.displayName,
                    online = true,
                    active = isActive,
                    heartbeat = if (isActive) "${((System.currentTimeMillis() - lastHeartbeatMillis) / 1000).coerceAtLeast(0)}s ago" else "Unknown",
                    health = if (isActive) botState.syncHealth.name else "Standby",
                )
            },
        )
        persistState()

        logs.firstOrNull { it.level == LogLevel.ERROR }?.let { errorLog ->
            _uiState.value = _uiState.value.copy(statusMessage = errorLog.message)
        }
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

    private fun quoteAssetPriceIdr(asset: String, quotes: List<MarketQuote>): Double? {
        if (asset.equals("idr", ignoreCase = true)) return 1.0
        val directPair = "${asset.lowercase()}_idr"
        val directQuote = quotes.firstOrNull { it.pairId.value.equals(directPair, ignoreCase = true) }
        if (directQuote != null) return directQuote.midPrice.toDoubleOrZero()

        val usdtAssetQuote = quotes.firstOrNull { it.pairId.value.equals("${asset.lowercase()}_usdt", ignoreCase = true) }
        val usdtIdrQuote = quotes.firstOrNull { it.pairId.value.equals("usdt_idr", ignoreCase = true) }
        if (usdtAssetQuote != null && usdtIdrQuote != null) {
            return usdtAssetQuote.midPrice.toDoubleOrZero() * usdtIdrQuote.midPrice.toDoubleOrZero()
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
        val prefix = if (value >= 0.0) "+" else "-"
        return prefix + formatIdr(value.absoluteValue)
    }
}
