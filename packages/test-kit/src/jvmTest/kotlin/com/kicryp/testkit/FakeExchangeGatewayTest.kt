package com.kicryp.testkit

import com.google.common.truth.Truth.assertThat
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.ExecutionPlan
import com.kicryp.shared.models.OrderSide
import com.kicryp.shared.models.OrderType
import com.kicryp.shared.models.PairId
import com.kicryp.shared.models.StrategySignal
import com.kicryp.shared.models.StrategySignalType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class FakeExchangeGatewayTest {
    @Test
    fun `place order stores open order`() = runBlocking {
        val gateway = FakeExchangeGateway()
        val order = gateway.placeOrder(
            plan = ExecutionPlan(
                signal = StrategySignal(
                    pairId = PairId("btc_idr"),
                    signalType = StrategySignalType.BREAKOUT_ENTRY,
                    confidence = 0.8,
                    rationale = listOf("test"),
                    entryPrice = DecimalValue("1000"),
                ),
                side = OrderSide.BUY,
                orderType = OrderType.LIMIT,
                quantity = DecimalValue("0.001"),
                limitPrice = DecimalValue("1000"),
                expectedNetEdgePct = 0.4,
            ),
            clientOrderId = com.kicryp.shared.models.ClientOrderId("cid-1"),
        )

        assertThat(order.status.name).isEqualTo("OPEN")
        assertThat(gateway.fetchOpenOrders()).hasSize(1)
    }
}
