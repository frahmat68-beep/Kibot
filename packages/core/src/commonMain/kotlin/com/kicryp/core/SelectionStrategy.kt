package com.kicryp.core

import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.PairId
import com.kicryp.shared.models.PairScore

interface SelectionStrategy {
    fun rank(
        quotes: List<MarketQuote>,
        context: PairSelectionContext = PairSelectionContext(),
    ): List<PairScore>

    fun shortlist(
        quotes: List<MarketQuote>,
        context: PairSelectionContext = PairSelectionContext(),
    ): List<PairScore> = rank(quotes, context)
        .filter { it.allowed }
        .take(defaultShortlistSize())

    fun defaultShortlistSize(): Int = 12
}

data class LeadLagSelectionSignal(
    val leadPairId: PairId?,
    val leadSectorFamily: String?,
    val leadMomentumScore: Double = 0.0,
    val fatigue: Boolean = false,
)
