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
                pnl = holding.valueIdr,
            )
        }
    return copy(
        modalSaatIniIdr = snapshot.totalEquityIdr,
        pnlTodayIdr = snapshot.pnlTodayIdr,
        pairAktif = snapshot.activePair.ifBlank { pairAktif },
        positions = if (livePositions.isNotEmpty()) livePositions else positions,
    )
}
