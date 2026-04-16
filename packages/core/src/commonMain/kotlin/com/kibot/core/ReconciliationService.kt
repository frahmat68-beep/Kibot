package com.kibot.core

import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.ReconciliationReport
import com.kibot.shared.models.ReconciliationState
import kotlin.time.Duration.Companion.minutes

class ReconciliationService {
    private val unmatchedFillLookback = 20.minutes

    fun reconcile(
        portfolio: PortfolioSnapshot,
        recentFills: List<FillSnapshot>,
        persistedOrders: List<OrderSnapshot>,
    ): ReconciliationReport {
        val staleOpenOrders = portfolio.openOrders
            .filter { openOrder ->
                persistedOrders.none { persisted ->
                    persisted.clientOrderId == openOrder.clientOrderId &&
                        persisted.status == openOrder.status
                }
            }
            .map(OrderSnapshot::clientOrderId)

        val unmatchedFillCutoff = portfolio.lastSyncedAt - unmatchedFillLookback
        val unmatchedFills = recentFills.filter { fill ->
            fill.executedAt >= unmatchedFillCutoff &&
                persistedOrders.none { it.orderId == fill.orderId }
        }.map(FillSnapshot::fillId)

        val warnings = buildList {
            if (portfolio.totalEquityIdr.toDoubleOrZero() <= 0.0) {
                add("Current equity is zero or negative.")
            }
            if (portfolio.balances.isEmpty()) {
                add("No balances available during reconciliation.")
            }
        }

        val state = when {
            unmatchedFills.isNotEmpty() -> ReconciliationState.BLOCKED
            staleOpenOrders.isNotEmpty() || warnings.isNotEmpty() -> ReconciliationState.NEEDS_REVIEW
            else -> ReconciliationState.CLEAN
        }

        return ReconciliationReport(
            state = state,
            staleOpenOrders = staleOpenOrders,
            unmatchedFills = unmatchedFills,
            balanceWarnings = warnings,
            notes = buildList {
                if (portfolio.openOrders.any { it.status == OrderStatus.PARTIALLY_FILLED }) {
                    add("Partial fills detected, new entries should wait for order-state convergence.")
                }
                if (staleOpenOrders.isNotEmpty()) {
                    add("Stale open-order snapshots need review: ${staleOpenOrders.joinToString(",") { it.value }}")
                }
                if (unmatchedFills.isNotEmpty()) {
                    add("Unmatched fills require hard review: ${unmatchedFills.joinToString(",") { it.value }}")
                }
            },
        )
    }
}
