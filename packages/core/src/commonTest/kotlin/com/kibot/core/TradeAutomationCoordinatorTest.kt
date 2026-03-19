package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.MarketRegime
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TradeAutomationCoordinatorTest {
    private val orchestrator = StrategyOrchestrator()
    private val coordinator = TradeAutomationCoordinator()

    @Test
    fun `take profit position gets automatic sell plan`() {
        val now = Clock.System.now()
        val quotes = listOf(
            marketQuote(
                pair = "btc_idr",
                bid = 103_500.0,
                ask = 103_700.0,
                volume = 120_000_000.0,
                shortTermReturn = 2.4,
                mediumTermReturn = 5.0,
            ),
        )
        val cycle = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = EngineHealthSnapshot(
                status = HealthStatus.HEALTHY,
                syncHealth = SyncHealth.HEALTHY,
                websocketHealthy = true,
                exchangeReachable = true,
                supabaseReachable = true,
            ),
            marketQuotes = quotes,
        )
        val recentOrders = listOf(
            com.kibot.shared.models.OrderSnapshot(
                orderId = com.kibot.shared.models.OrderId("buy-1"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("buy-1"),
                pairId = PairId("btc_idr"),
                side = com.kibot.shared.models.OrderSide.BUY,
                orderType = com.kibot.shared.models.OrderType.LIMIT,
                status = com.kibot.shared.models.OrderStatus.FILLED,
                price = DecimalValue("100000"),
                originalQuantity = DecimalValue("0.2"),
                executedQuantity = DecimalValue("0.2"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val positions = coordinator.deriveManagedPositions(
            balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
            marketQuotes = quotes,
            reconciledOrders = recentOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = emptyList(),
        )

        assertNotNull(decision)
        assertEquals(ExitReason.PROFIT_EXIT, decision.reason)
        assertEquals(com.kibot.shared.models.OrderSide.SELL, decision.executionPlan.side)
        assertEquals(PairId("btc_idr"), decision.executionPlan.signal.pairId)
        assertEquals(OrderType.LIMIT, decision.executionPlan.orderType)
    }

    @Test
    fun `breakdown panic uses emergency market sell`() {
        val now = Clock.System.now()
        val quotes = listOf(
            marketQuote(
                pair = "btc_idr",
                bid = 96_500.0,
                ask = 96_900.0,
                volume = 120_000_000.0,
                shortTermReturn = -3.8,
                mediumTermReturn = -6.1,
            ),
        )
        val cycle = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = EngineHealthSnapshot(
                status = HealthStatus.HEALTHY,
                syncHealth = SyncHealth.HEALTHY,
                websocketHealthy = true,
                exchangeReachable = true,
                supabaseReachable = true,
            ),
            marketQuotes = quotes,
        ).copy(
            marketSnapshot = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = EngineHealthSnapshot(
                    status = HealthStatus.HEALTHY,
                    syncHealth = SyncHealth.HEALTHY,
                    websocketHealthy = true,
                    exchangeReachable = true,
                    supabaseReachable = true,
                ),
                marketQuotes = quotes,
            ).marketSnapshot.copy(regime = MarketRegime.BREAKDOWN_PANIC),
        )
        val recentOrders = listOf(
            com.kibot.shared.models.OrderSnapshot(
                orderId = com.kibot.shared.models.OrderId("buy-1"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("buy-1"),
                pairId = PairId("btc_idr"),
                side = com.kibot.shared.models.OrderSide.BUY,
                orderType = com.kibot.shared.models.OrderType.LIMIT,
                status = com.kibot.shared.models.OrderStatus.FILLED,
                price = DecimalValue("100000"),
                originalQuantity = DecimalValue("0.2"),
                executedQuantity = DecimalValue("0.2"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val positions = coordinator.deriveManagedPositions(
            balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
            marketQuotes = quotes,
            reconciledOrders = recentOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = emptyList(),
        )

        assertNotNull(decision)
        assertEquals(ExitReason.STOP_LOSS_EXIT, decision.reason)
        assertEquals(OrderType.MARKET, decision.executionPlan.orderType)
    }

    @Test
    fun `emergency market exit can override existing sell order on same pair`() {
        val now = Clock.System.now()
        val quotes = listOf(
            marketQuote(
                pair = "btc_idr",
                bid = 96_500.0,
                ask = 96_900.0,
                volume = 120_000_000.0,
                shortTermReturn = -3.8,
                mediumTermReturn = -6.1,
            ),
        )
        val baseCycle = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = EngineHealthSnapshot(
                status = HealthStatus.HEALTHY,
                syncHealth = SyncHealth.HEALTHY,
                websocketHealthy = true,
                exchangeReachable = true,
                supabaseReachable = true,
            ),
            marketQuotes = quotes,
        )
        val cycle = baseCycle.copy(
            marketSnapshot = baseCycle.marketSnapshot.copy(regime = MarketRegime.BREAKDOWN_PANIC),
        )
        val recentOrders = listOf(
            com.kibot.shared.models.OrderSnapshot(
                orderId = OrderId("buy-1"),
                clientOrderId = ClientOrderId("buy-1"),
                pairId = PairId("btc_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.LIMIT,
                status = OrderStatus.FILLED,
                price = DecimalValue("100000"),
                originalQuantity = DecimalValue("0.2"),
                executedQuantity = DecimalValue("0.2"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val activeSellOrders = listOf(
            com.kibot.shared.models.OrderSnapshot(
                orderId = OrderId("sell-1"),
                clientOrderId = ClientOrderId("sell-1"),
                pairId = PairId("btc_idr"),
                side = OrderSide.SELL,
                orderType = OrderType.LIMIT,
                status = OrderStatus.OPEN,
                price = DecimalValue("101500"),
                originalQuantity = DecimalValue("0.2"),
                executedQuantity = DecimalValue.Zero,
                remainingQuantity = DecimalValue("0.2"),
                createdAt = now,
                updatedAt = now,
            ),
        )

        val positions = coordinator.deriveManagedPositions(
            balances = listOf(BalanceSnapshot(asset = "btc", free = DecimalValue("0.2"))),
            marketQuotes = quotes,
            reconciledOrders = recentOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = activeSellOrders,
        )

        assertNotNull(decision)
        assertEquals(OrderType.MARKET, decision.executionPlan.orderType)
    }

    @Test
    fun `stale unknown order without exchange match gets canceled`() {
        val now = Clock.System.now()
        val staleTimestamp = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (15 * 60 * 1000))
        val staleOrder = com.kibot.shared.models.OrderSnapshot(
            orderId = OrderId("ghost-1"),
            clientOrderId = ClientOrderId("ghost-1"),
            pairId = PairId("xrp_idr"),
            side = OrderSide.BUY,
            orderType = OrderType.LIMIT,
            status = OrderStatus.UNKNOWN,
            price = DecimalValue("24909"),
            originalQuantity = DecimalValue("2.2776835367"),
            executedQuantity = DecimalValue.Zero,
            remainingQuantity = DecimalValue("2.2776835367"),
            createdAt = staleTimestamp,
            updatedAt = staleTimestamp,
        )

        val reconciled = coordinator.reconcileOrders(
            persistedOrders = listOf(staleOrder),
            exchangeOpenOrders = emptyList(),
            recentFills = emptyList(),
        )

        assertEquals(1, reconciled.size)
        assertEquals(OrderStatus.CANCELED, reconciled.first().status)
    }

    @Test
    fun `filled order without open exchange row gets normalized from fills`() {
        val now = Clock.System.now()
        val openOrder = com.kibot.shared.models.OrderSnapshot(
            orderId = OrderId("188954894"),
            clientOrderId = ClientOrderId("bot-xrp-1"),
            pairId = PairId("xrp_idr"),
            side = OrderSide.BUY,
            orderType = OrderType.LIMIT,
            status = OrderStatus.OPEN,
            price = DecimalValue("24626"),
            originalQuantity = DecimalValue("1.77466904"),
            executedQuantity = DecimalValue.Zero,
            remainingQuantity = DecimalValue("1.77466904"),
            createdAt = now,
            updatedAt = now,
        )
        val fills = listOf(
            com.kibot.shared.models.FillSnapshot(
                fillId = com.kibot.shared.models.FillId("fill-1"),
                orderId = OrderId("188954894"),
                pairId = PairId("xrp_idr"),
                side = OrderSide.BUY,
                quantity = DecimalValue("1.77091245"),
                price = DecimalValue("24626"),
                fee = DecimalValue("43"),
                feeAsset = "idr",
                executedAt = now,
            ),
        )

        val reconciled = coordinator.reconcileOrders(
            persistedOrders = listOf(openOrder),
            exchangeOpenOrders = emptyList(),
            recentFills = fills,
        )

        assertEquals(1, reconciled.size)
        assertEquals(OrderStatus.FILLED, reconciled.first().status)
        assertEquals("1.77091245", reconciled.first().originalQuantity.value)
        assertEquals("1.77091245", reconciled.first().executedQuantity.value)
        assertEquals(0.0, reconciled.first().remainingQuantity.toDoubleOrZero())
    }

    private fun marketQuote(
        pair: String,
        bid: Double,
        ask: Double,
        volume: Double,
        shortTermReturn: Double,
        mediumTermReturn: Double,
    ): MarketQuote = MarketQuote(
        pairId = PairId(pair),
        bestBid = DecimalValue.fromDouble(bid),
        bestAsk = DecimalValue.fromDouble(ask),
        midPrice = DecimalValue.fromDouble((bid + ask) / 2.0),
        spreadPct = ((ask - bid) / ((ask + bid) / 2.0)) * 100.0,
        quoteVolume24h = DecimalValue.fromDouble(volume),
        baseVolume24h = DecimalValue.fromDouble(volume / bid),
        estimatedSlippagePct = 0.12,
        orderBookStabilityScore = 0.91,
        tradeCount24h = 550,
        bidDepthTop5Idr = DecimalValue.fromDouble(2_000_000.0),
        askDepthTop5Idr = DecimalValue.fromDouble(2_000_000.0),
        shortTermReturnPct = shortTermReturn,
        mediumTermReturnPct = mediumTermReturn,
        realizedVolatilityPct = 3.1,
        recentTradeActivityScore = 0.86,
        volatilityQualityScore = 0.78,
        trendQualityScore = 0.82,
        historicalExpectancyScore = 0.74,
        fillQualityScore = 0.88,
        holdabilityScore = 0.72,
        capturedAt = Clock.System.now(),
    )
}
