package com.kibot.core

import com.kibot.shared.models.*
import java.time.Instant

class AlwaysInvestedPolicy {
    data class EntryDecision(val granted: Boolean, val reason: String)

    fun shouldEnter(
        pairId: PairId,
        quote: MarketQuote,
        config: EngineConfig,
        now: Instant
    ): EntryDecision {
        // 1. Fee Gate: Expected return must cover round-trip fees
        val estFees = 0.0021 // 0.21% default taker round-trip
        val grossTrend = quote.shortTermReturnPct / 100.0
        
        if (grossTrend < estFees * 1.5) {
            return EntryDecision(false, "Insufficient expected alpha (Trend ${quote.shortTermReturnPct}% < Fee Gate)")
        }

        // 2. Liquidity Gate: Min volume to avoid slippage
        if (quote.quoteVolume24h.toDoubleOrZero() < 50_000_000.0) {
            return EntryDecision(false, "Low liquidity (Volume < 50M IDR)")
        }

        return EntryDecision(true, "Policy OK")
    }
}
