package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BucketType

data class EngineBudgetSnapshot(
    val macroBudgetIdr: Double,
    val barbarianBudgetIdr: Double,
)

class DualEngineCoordinator(
    private val config: DualEngineConfig = DualEngineConfig(),
    private val macroEngine: TradingEngine = MacroFollowerEngine(config),
    private val barbarianEngine: TradingEngine = BarbarianAnomalyEngine(config),
) {
    fun splitFreeIdr(freeIdr: Double): EngineBudgetSnapshot {
        val safeFree = freeIdr.coerceAtLeast(0.0)
        val macro = safeFree * config.macroFollowerAllocationPct
        val barbarian = safeFree * config.barbarianAnomalyAllocationPct
        return EngineBudgetSnapshot(
            macroBudgetIdr = macro,
            barbarianBudgetIdr = barbarian,
        )
    }

    fun evaluate(udpSignal: KiBotSignal): List<EngineSignalDecision> {
        return listOfNotNull(
            macroEngine.evaluateSignal(udpSignal),
            barbarianEngine.evaluateSignal(udpSignal),
        )
    }

    fun pickPriorityDecision(
        udpSignal: KiBotSignal,
        balances: List<BalanceSnapshot>,
    ): EngineSignalDecision? {
        val freeIdr = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }
            ?.free?.toDoubleOrZero() ?: 0.0
        val budget = splitFreeIdr(freeIdr)
        val candidates = evaluate(udpSignal)
        if (candidates.isEmpty()) return null

        // If both match, prioritize barbarian for explicit anomaly signals
        val barbarian = candidates.firstOrNull { it.bucketType == BucketType.AGGRESSIVE }
        val macro = candidates.firstOrNull { it.bucketType == BucketType.STABLE }
        return when {
            barbarian != null && budget.barbarianBudgetIdr >= 20_000.0 -> barbarian
            macro != null && budget.macroBudgetIdr >= 20_000.0 -> macro
            barbarian != null -> barbarian
            else -> macro
        }
    }
}
