package com.kibot.core

import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PairId

class VetoService {
    fun shouldVetoEntry(
        candidate: PairScore,
        quote: MarketQuote,
        leadLagSignal: LeadLagSelectionSignal?,
        priceBandAllowed: Boolean,
        softAuditOnly: Boolean = false,
    ): Boolean {
        if (!priceBandAllowed) return true
        if (softAuditOnly) return false
        val signal = leadLagSignal ?: return false
        if (signal.fatigue) {
            val sameSector = signal.leadSectorFamily != null &&
                signal.leadSectorFamily == familyOf(candidate.pairId)
            if (!sameSector) return true
        }
        if (signal.leadPairId?.value?.lowercase() == candidate.pairId.value.lowercase()) {
            return false
        }
        // FIX: Lead tinggi + Sector rendah = LAGGING OPPORTUNITY, bukan VETO!
        // Jika lead sudah pump tapi sector belum ikut, ini peluang entry sebelum sector catch-up
        // OLD (BROKEN): return signal.leadMomentumScore >= 0.72 && quote.sectorMomentumScore < 0.52
        // Sekarang: APPROVE entry pada kondisi ini (return false = no veto)
        if (signal.leadMomentumScore >= 0.72 && quote.sectorMomentumScore < 0.52) {
            return false  // LAGGING OPPORTUNITY - APPROVE, don't veto!
        }
        return false  // Default: allow entry
    }

    fun shouldTightenTrailing(
        pairId: PairId,
        leadLagSignal: LeadLagSelectionSignal?,
    ): Boolean {
        val signal = leadLagSignal ?: return false
        if (!signal.fatigue) return false
        val sameSector = signal.leadSectorFamily != null && signal.leadSectorFamily == familyOf(pairId)
        return sameSector || signal.leadPairId?.value?.lowercase() == pairId.value.lowercase()
    }

    private fun familyOf(pairId: PairId): String {
        val entry = com.kibot.core.data.CoinUniverse.byIndodax[pairId.value.lowercase()]
        if (entry != null) return entry.correlationGroup.name.lowercase()
        return pairId.value.substringBefore('_').lowercase()
    }
}
