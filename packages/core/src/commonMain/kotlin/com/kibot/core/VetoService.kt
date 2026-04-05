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
        return signal.leadMomentumScore >= 0.72 && quote.sectorMomentumScore < 0.52
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
        val base = pairId.value.substringBefore('_').lowercase()
        return when (base) {
            in setOf("doge", "shib", "pepe", "floki", "bonk", "wif") -> "meme"
            in setOf("fet", "agix", "ocean", "render", "tao") -> "ai"
            in setOf("sol", "ada", "avax", "matic", "arb", "op", "eth", "near", "ont", "trx", "xlm", "plpa", "kaito") -> "l1_l2"
            in setOf("btc") -> "btc"
            else -> base
        }
    }
}
