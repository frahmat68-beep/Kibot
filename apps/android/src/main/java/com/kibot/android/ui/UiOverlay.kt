package com.kibot.android.ui

import com.kibot.android.runtime.LiveStatusSnapshot

fun KiBotUiState.withLiveSnapshot(snapshot: LiveStatusSnapshot?): KiBotUiState {
    if (snapshot == null) return this
    val livePositions = snapshot.holdings
        .filterNot { holding ->
            when (holding.valueIdr.trim()) {
                "Rp0", "+Rp0", "-Rp0", "~Rp0" -> true
                else -> false
            }
        }
        .map { holding ->
            PositionCardUi(
                pair = holding.asset,
                quantity = holding.amount,
                value = holding.valueIdr,
            )
        }
    return copy(
        modalSaatIniIdr = snapshot.totalEquityIdr,
        pnlTodayIdr = snapshot.pnlTodayIdr,
        pnlTodayPctLabel = snapshot.derivedPnlPctLabel(),
        internetPingLabel = snapshot.internetPingLabel(),
        pairAktif = snapshot.activePair.ifBlank { pairAktif },
        positions = if (livePositions.isNotEmpty()) livePositions else positions,
    )
}

private fun LiveStatusSnapshot.derivedPnlPctLabel(): String {
    val equity = totalEquityIdr.parseRupiahLabel() ?: return "+0.0%"
    val pnl = pnlTodayIdr.parseRupiahLabel() ?: return "+0.0%"
    val opening = (equity - pnl).takeIf { it > 0.0 } ?: return "+0.0%"
    val pct = pnl / opening
    return formatSignedPercent(pct)
}

private fun String.parseRupiahLabel(): Double? {
    val cleaned = trim()
        .replace("~", "")
        .replace("Rp", "")
        .replace(".", "")
        .replace(",", ".")
        .replace("+", "")
    val value = cleaned.toDoubleOrNull() ?: return null
    return if (trim().startsWith("-")) -value else value
}

private fun formatSignedPercent(value: Double): String {
    val pct = value * 100.0
    val prefix = if (pct >= 0.0) "+" else "-"
    return prefix + "%.1f%%".format(kotlin.math.abs(pct))
}

private fun LiveStatusSnapshot.internetPingLabel(): String {
    return internetPingMs?.let { "${it} ms" } ?: "--"
}
