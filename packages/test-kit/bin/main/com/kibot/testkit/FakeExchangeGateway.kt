package com.kibot.testkit

import com.kibot.core.ExchangeGateway
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.FillId
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.PairId
import kotlinx.datetime.Clock

class FakeExchangeGateway(
    private val marketQuotes: MutableList<MarketQuote> = mutableListOf(),
    private val balances: MutableList<BalanceSnapshot> = mutableListOf(),
    private val orders: MutableList<OrderSnapshot> = mutableListOf(),
    private val fills: MutableList<FillSnapshot> = mutableListOf(),
    private val failOnPlaceOrder: Boolean = false,
) : ExchangeGateway {
    override suspend fun ping(): Boolean = true

    override suspend fun fetchMarketQuotes(): List<MarketQuote> = marketQuotes.toList()

    override suspend fun fetchBalances(): List<BalanceSnapshot> = balances.toList()

    override suspend fun fetchOpenOrders(): List<OrderSnapshot> = orders.filter {
        it.status == OrderStatus.OPEN || it.status == OrderStatus.PARTIALLY_FILLED
    }

    override suspend fun fetchRecentFills(pairId: PairId?, limit: Int): List<FillSnapshot> {
        return fills.filter { pairId == null || it.pairId == pairId }.takeLast(limit)
    }

    override suspend fun placeOrder(plan: ExecutionPlan, clientOrderId: ClientOrderId): OrderSnapshot {
        if (failOnPlaceOrder) {
            error("Simulated exchange submit failure.")
        }
        val now = Clock.System.now()
        return OrderSnapshot(
            orderId = OrderId("fake-${orders.size + 1}"),
            clientOrderId = clientOrderId,
            pairId = plan.signal.pairId,
            side = plan.side,
            orderType = plan.orderType,
            status = OrderStatus.OPEN,
            price = plan.limitPrice ?: plan.signal.entryPrice ?: error("Missing price for fake order."),
            originalQuantity = plan.quantity,
            executedQuantity = com.kibot.shared.models.DecimalValue.Zero,
            remainingQuantity = plan.quantity,
            createdAt = now,
            updatedAt = now,
        ).also(orders::add)
    }

    override suspend fun cancelOrder(clientOrderId: ClientOrderId): Boolean {
        val index = orders.indexOfFirst { it.clientOrderId == clientOrderId }
        if (index < 0) return false

        val existing = orders[index]
        orders[index] = existing.copy(
            status = OrderStatus.CANCELED,
            updatedAt = Clock.System.now(),
        )
        return true
    }

    fun recordFill(order: OrderSnapshot, quantity: String, price: String) {
        fills += FillSnapshot(
            fillId = FillId("fill-${fills.size + 1}"),
            orderId = order.orderId,
            pairId = order.pairId,
            side = order.side,
            quantity = com.kibot.shared.models.DecimalValue(quantity),
            price = com.kibot.shared.models.DecimalValue(price),
            fee = com.kibot.shared.models.DecimalValue.Zero,
            feeAsset = "idr",
            executedAt = Clock.System.now(),
        )
    }

    fun currentOrders(): List<OrderSnapshot> = orders.toList()
}
