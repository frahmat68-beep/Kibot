package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.FillId
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.ReconciliationState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconciliationServiceTest {
    @Test
    fun `blocks when fill cannot be matched to persisted order`() {
        val now = Instant.parse("2026-03-15T01:00:00Z")
        val report = ReconciliationService().reconcile(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("100000"),
                lastSyncedAt = now,
            ),
            recentFills = listOf(
                FillSnapshot(
                    fillId = FillId("fill-1"),
                    orderId = OrderId("order-1"),
                    pairId = PairId("btc_idr"),
                    side = OrderSide.BUY,
                    quantity = DecimalValue("0.001"),
                    price = DecimalValue("1000000"),
                    fee = DecimalValue("1000"),
                    feeAsset = "idr",
                    executedAt = now,
                ),
            ),
            persistedOrders = emptyList(),
        )

        assertEquals(ReconciliationState.BLOCKED, report.state)
    }

    @Test
    fun `accepts recent fill when matching persisted order is available`() {
        val now = Instant.parse("2026-03-15T01:00:00Z")
        val report = ReconciliationService().reconcile(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("100000"),
                lastSyncedAt = now,
            ),
            recentFills = listOf(
                FillSnapshot(
                    fillId = FillId("fill-1"),
                    orderId = OrderId("order-1"),
                    pairId = PairId("btc_idr"),
                    side = OrderSide.BUY,
                    quantity = DecimalValue("0.001"),
                    price = DecimalValue("1000000"),
                    fee = DecimalValue("1000"),
                    feeAsset = "idr",
                    executedAt = now,
                ),
            ),
            persistedOrders = listOf(
                OrderSnapshot(
                    orderId = OrderId("order-1"),
                    clientOrderId = com.kibot.shared.models.ClientOrderId("client-1"),
                    pairId = PairId("btc_idr"),
                    side = OrderSide.BUY,
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.FILLED,
                    price = DecimalValue("1000000"),
                    originalQuantity = DecimalValue("0.001"),
                    executedQuantity = DecimalValue("0.001"),
                    remainingQuantity = DecimalValue.Zero,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )

        assertEquals(ReconciliationState.CLEAN, report.state)
    }
}
