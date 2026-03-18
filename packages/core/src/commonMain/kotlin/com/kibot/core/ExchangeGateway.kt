package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.PairId

interface ExchangeGateway {
    suspend fun ping(): Boolean

    suspend fun fetchMarketQuotes(): List<MarketQuote>

    suspend fun fetchBalances(): List<BalanceSnapshot>

    suspend fun fetchOpenOrders(): List<OrderSnapshot>

    suspend fun fetchRecentFills(pairId: PairId? = null, limit: Int = 50): List<FillSnapshot>

    suspend fun placeOrder(
        plan: ExecutionPlan,
        clientOrderId: ClientOrderId,
    ): OrderSnapshot

    suspend fun cancelOrder(clientOrderId: ClientOrderId): Boolean
}
