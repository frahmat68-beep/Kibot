package com.kibot.commandcenter.data.repository

import android.content.Context
import com.kibot.commandcenter.data.model.CommandCenterUiState
import com.kibot.commandcenter.data.model.ConnectionState
import com.kibot.commandcenter.data.model.ConsoleLine
import com.kibot.commandcenter.data.model.ConsoleRole
import com.kibot.commandcenter.data.model.DashboardTab
import com.kibot.commandcenter.data.model.ServerPaneState
import com.kibot.shared.models.CommandCenterLiveSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class CommandCenterStore(context: Context) {
    private val _uiState = MutableStateFlow(CommandCenterUiState())
    val uiState: StateFlow<CommandCenterUiState> = _uiState.asStateFlow()

    fun updateServer(serverKey: String, label: String, snapshot: CommandCenterLiveSnapshot?, connectionState: ConnectionState, lastError: String? = null) {
        val current = _uiState.value
        val currentPane = if (serverKey == "kidax") current.kidax else current.kinance
        val nextSnapshot = when {
            snapshot != null -> snapshot
            connectionState == ConnectionState.CONNECTED -> currentPane.snapshot
            else -> null
        }
        val nextPane = ServerPaneState(
            serverKey = serverKey,
            label = label,
            snapshot = nextSnapshot,
            connectionState = connectionState,
            lastError = lastError,
            lastUpdatedEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        val kidax = if (serverKey == "kidax") nextPane else current.kidax
        val kinance = if (serverKey == "kinance") nextPane else current.kinance
        val liveKidax = kidax.snapshot.takeIf { kidax.connectionState != ConnectionState.DISCONNECTED }
        val totalSourceKidax = liveKidax ?: kidax.snapshot
        val mergedPnl = mergePnl(totalSourceKidax)
        val mergedTotal = mergeTotal(totalSourceKidax)
        _uiState.value = current.copy(
            totalEquityLabel = mergedTotal,
            pnlTodayLabel = mergedPnl.first,
            pnlTodayPctLabel = mergedPnl.second,
            return7dLabel = totalSourceKidax?.return7dIdr ?: "+Rp0",
            return7dPctLabel = totalSourceKidax?.return7dPctLabel ?: "+0.0%",
            return30dLabel = totalSourceKidax?.return30dIdr ?: "+Rp0",
            return30dPctLabel = totalSourceKidax?.return30dPctLabel ?: "+0.0%",
            systemHealthLabel = deriveHealth(kidax),
            latencyLabel = deriveLatency(kidax),
            equityHistory = updateEquityHistory(current.equityHistory, mergedTotal),
            kidax = kidax,
            kinance = current.kinance.copy(
                connectionState = ConnectionState.DISCONNECTED,
                snapshot = null,
                lastError = current.kinance.lastError ?: "Kinance sync disabled",
            ),
        )
    }

    fun setSelectedTab(tab: DashboardTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun appendConsole(role: ConsoleRole, text: String) {
        _uiState.value = _uiState.value.copy(
            consoleLines = (_uiState.value.consoleLines + ConsoleLine(role, text, Clock.System.now().toEpochMilliseconds())).takeLast(60),
        )
    }

    private fun deriveHealth(kidax: ServerPaneState): String {
        return when {
            kidax.connectionState == ConnectionState.CONNECTED -> "HEALTHY"
            kidax.connectionState == ConnectionState.RECONNECTING -> "DEGRADED"
            else -> "BOOT"
        }
    }

    private fun deriveLatency(kidax: ServerPaneState): String {
        val kidaxPing = kidax.snapshot?.exchangePingValueMs ?: -1L
        val kidaxLabel = if (kidaxPing >= 0) "${kidaxPing} ms" else "--"
        return "KiDax $kidaxLabel"
    }

    private fun mergeTotal(kidax: CommandCenterLiveSnapshot?): String {
        val total = idrValue(kidax)
        return formatIdr(total)
    }

    private fun mergePnl(kidax: CommandCenterLiveSnapshot?): Pair<String, String> {
        val pnl = pnlValue(kidax)
        val totalValue = idrValue(kidax)
        val pctBase = (totalValue - pnl).coerceAtLeast(1.0)
        return formatSignedIdr(pnl) to formatSignedPercent((pnl / pctBase) * 100.0)
    }

    private fun updateEquityHistory(history: List<Double>, totalLabel: String): List<Double> {
        val total = parseIdr(totalLabel)
        val next = (history + total).takeLast(24)
        return next
    }

    private fun parseIdr(value: String?): Double {
        val raw = value.orEmpty().trim()
        val numeric = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
        val normalized = when {
            raw.contains("USDT", ignoreCase = true) || raw.contains("USDC", ignoreCase = true) ->
                numeric.replace(",", "")
            else ->
                numeric.replace(".", "").replace(",", ".")
        }
        return normalized.toDoubleOrNull() ?: 0.0
    }

    private fun parseSignedIdr(value: String?): Double {
        val raw = value.orEmpty().trim()
        val numeric = parseIdr(raw)
        val negative = raw.any { it == '-' || it == '−' || it == '–' || it == '—' }
        return if (negative) -numeric else numeric
    }

    private fun pnlValue(snapshot: CommandCenterLiveSnapshot?): Double {
        if (snapshot == null) return 0.0
        val raw = snapshot.pnlTodayIdr
        val parsed = parseSignedIdr(raw)
        return if (raw.contains("USDT", ignoreCase = true) || raw.contains("USDC", ignoreCase = true)) {
            parsed * snapshot.referenceQuoteAssetPriceIdr.orFallbackQuoteAssetPriceIdr()
        } else parsed
    }

    private fun idrValue(snapshot: CommandCenterLiveSnapshot?): Double {
        if (snapshot == null) return 0.0
        val raw = snapshot.totalValueIdr.ifBlank { snapshot.portfolioValueIdr }
        val parsed = parseIdr(raw)
        return if (raw.contains("USDT", ignoreCase = true) || raw.contains("USDC", ignoreCase = true)) {
            parsed * snapshot.referenceQuoteAssetPriceIdr.orFallbackQuoteAssetPriceIdr()
        } else parsed
    }

    private fun formatIdr(value: Double): String {
        val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale("id", "ID")))
        return "Rp${formatter.format(value.coerceAtLeast(0.0).toLong())}"
    }

    private fun formatSignedIdr(value: Double): String {
        val sign = if (value < 0) "-" else "+"
        return "$sign${formatIdr(kotlin.math.abs(value))}"
    }

    private fun formatSignedPercent(value: Double): String {
        val sign = if (value < 0) "-" else "+"
        val formatter = DecimalFormat("#,##0.0", DecimalFormatSymbols(Locale("id", "ID")))
        return "$sign${formatter.format(kotlin.math.abs(value))}%"
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    private fun Double?.orFallbackQuoteAssetPriceIdr(): Double = this ?: 16_000.0
}
