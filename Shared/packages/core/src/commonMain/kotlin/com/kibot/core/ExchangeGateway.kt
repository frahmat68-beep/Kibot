package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.PairId

data class MarketBuyImpactEstimate(
    val pairId: PairId,
    val quoteBudget: Double,
    val averagePrice: Double,
    val lastPrice: Double,
    val slippagePct: Double,
    val consumedLevels: Int,
    val exhaustedBook: Boolean,
)

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

    suspend fun estimateMarketBuyImpact(
        pairId: PairId,
        quoteBudget: Double,
    ): MarketBuyImpactEstimate? = null
}

open class ExchangeExecutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ExchangeRejectedException(
    message: String,
    cause: Throwable? = null,
) : ExchangeExecutionException(message, cause)

class ExchangeOrderVisibilityException(
    message: String,
    cause: Throwable? = null,
) : ExchangeExecutionException(message, cause)
