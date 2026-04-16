package com.kicryp.macengine.runtime

import com.kicryp.core.ExchangeGateway
import com.kicryp.shared.models.BalanceSnapshot
import com.kicryp.shared.models.ClientOrderId
import com.kicryp.shared.models.ExecutionPlan
import com.kicryp.shared.models.FillSnapshot
import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.OrderSnapshot
import com.kicryp.shared.models.PairId

class PassiveExchangeGateway : ExchangeGateway {
    override suspend fun ping(): Boolean = false

    override suspend fun fetchMarketQuotes(): List<MarketQuote> = emptyList()

    override suspend fun fetchBalances(): List<BalanceSnapshot> = emptyList()

    override suspend fun fetchOpenOrders(): List<OrderSnapshot> = emptyList()

    override suspend fun fetchRecentFills(pairId: PairId?, limit: Int): List<FillSnapshot> = emptyList()

    override suspend fun placeOrder(plan: ExecutionPlan, clientOrderId: ClientOrderId): OrderSnapshot {
        error("Passive exchange gateway cannot place orders.")
    }

    override suspend fun cancelOrder(clientOrderId: ClientOrderId): Boolean = false
}
