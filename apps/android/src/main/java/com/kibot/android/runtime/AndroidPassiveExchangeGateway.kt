package com.kibot.android.runtime

import com.kibot.core.ExchangeGateway
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.PairId

class AndroidPassiveExchangeGateway : ExchangeGateway {
    override suspend fun ping(): Boolean = false

    override suspend fun fetchMarketQuotes(): List<MarketQuote> = emptyList()

    override suspend fun fetchBalances(): List<BalanceSnapshot> = emptyList()

    override suspend fun fetchOpenOrders(): List<OrderSnapshot> = emptyList()

    override suspend fun fetchRecentFills(pairId: PairId?, limit: Int): List<FillSnapshot> = emptyList()

    override suspend fun placeOrder(plan: ExecutionPlan, clientOrderId: ClientOrderId): OrderSnapshot {
        error("Android passive gateway cannot place live orders.")
    }

    override suspend fun cancelOrder(clientOrderId: ClientOrderId): Boolean = false
}
