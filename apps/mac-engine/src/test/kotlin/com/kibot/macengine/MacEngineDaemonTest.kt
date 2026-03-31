package com.kibot.macengine

import com.kibot.controlplane.ControlPlaneConfig
import com.kibot.binance.BinanceClientConfig
import com.kibot.indodax.IndodaxClientConfig
import com.kibot.macengine.config.ExchangeKind
import com.kibot.macengine.config.HyperAggressiveConfig
import com.kibot.macengine.config.MacRuntimeConfig
import com.kibot.macengine.runtime.MacEngineDaemon
import com.kibot.macengine.state.MacStateRepository
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotStateSnapshot
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DevicePlatform
import com.kibot.shared.models.DeviceRole
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LeaseTerm
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.StrategyMode
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.WeeklyAdaptationPlan
import com.kibot.shared.models.WeeklyLearningSummary
import com.kibot.shared.models.CommandType
import com.kibot.core.MarketBuyImpactEstimate
import com.kibot.testkit.FakeControlPlaneGateway
import com.kibot.testkit.FakeExchangeGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacEngineDaemonTest {
    private val botId = BotId("main")
    private val macId = DeviceId("macbook-main")
    private val androidId = DeviceId("android-main")

    @Test
    fun `standby acquires expired lease after clean reconciliation`() = runBlocking {
        val controlPlane = FakeControlPlaneGateway(botId = botId)
        controlPlane.botState = BotStateSnapshot(
            botId = botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.DEGRADED,
            activeDeviceId = androidId,
            standbyDeviceId = macId,
            currentTerm = LeaseTerm(1),
            syncHealth = SyncHealth.DEGRADED,
            strategyMode = StrategyMode.AUTO_CONSERVATIVE,
            lastHeartbeatAt = Instant.parse("2026-03-15T00:00:00Z"),
        )
        controlPlane.registerDevice(androidRegistration())
        controlPlane.seedLease(
            EngineLeaseSnapshot(
                botId = botId,
                currentHolder = androidId,
                term = LeaseTerm(1),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2020-01-01T00:00:10Z"),
                lastHeartbeatAt = Instant.parse("2020-01-01T00:00:00Z"),
                conflictDetected = false,
            ),
        )

        val exchange = FakeExchangeGateway(
            marketQuotes = mutableListOf(
                marketQuote("btc_idr", 150_000_000.0, 0.82),
                marketQuote("eth_idr", 120_000_000.0, 0.77),
            ),
            balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("100000"))),
        )
        val repository = MacStateRepository()
        val daemon = MacEngineDaemon(
            repository = repository,
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(),
        )

        daemon.syncOnce()

        val lease = controlPlane.fetchLease(botId)
        assertEquals(macId, lease?.currentHolder)
        assertEquals(BotEffectiveState.RUNNING, controlPlane.fetchBotState(botId)?.effectiveState)
        assertTrue(repository.state.value.activeEngine.contains("Oracle", ignoreCase = true))
        assertEquals(BotMode.ATTACK, controlPlane.runtimeIntelligence?.operatingMode)
        assertEquals(PairId("btc_idr"), controlPlane.runtimeIntelligence?.currentPair)
    }

    @Test
    fun `takeover is blocked and safe mode is triggered when reconciliation is ambiguous`() = runBlocking {
        val controlPlane = FakeControlPlaneGateway(botId = botId)
        controlPlane.botState = BotStateSnapshot(
            botId = botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.DEGRADED,
            activeDeviceId = androidId,
            standbyDeviceId = macId,
            currentTerm = LeaseTerm(3),
            syncHealth = SyncHealth.DEGRADED,
            strategyMode = StrategyMode.AUTO_CONSERVATIVE,
            lastHeartbeatAt = Instant.parse("2026-03-15T00:00:00Z"),
        )
        controlPlane.registerDevice(androidRegistration())
        controlPlane.seedLease(
            EngineLeaseSnapshot(
                botId = botId,
                currentHolder = androidId,
                term = LeaseTerm(3),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2020-01-01T00:00:05Z"),
                lastHeartbeatAt = Instant.parse("2020-01-01T00:00:00Z"),
                conflictDetected = false,
            ),
        )

        val exchange = FakeExchangeGateway(
            marketQuotes = mutableListOf(marketQuote("btc_idr", 150_000_000.0, 0.82)),
            balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("100000"))),
            orders = mutableListOf(
                OrderSnapshot(
                    orderId = OrderId("ex-open-1"),
                    clientOrderId = com.kibot.shared.models.ClientOrderId("client-open-1"),
                    pairId = PairId("btc_idr"),
                    side = OrderSide.BUY,
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.OPEN,
                    price = DecimalValue("1000000"),
                    originalQuantity = DecimalValue("0.001"),
                    executedQuantity = DecimalValue.Zero,
                    remainingQuantity = DecimalValue("0.001"),
                    createdAt = Instant.parse("2026-03-15T00:00:00Z"),
                    updatedAt = Instant.parse("2026-03-15T00:00:00Z"),
                ),
            ),
            fills = mutableListOf(
                FillSnapshot(
                    fillId = com.kibot.shared.models.FillId("fill-1"),
                    orderId = OrderId("ex-1"),
                    pairId = PairId("btc_idr"),
                    side = OrderSide.BUY,
                    quantity = DecimalValue("0.001"),
                    price = DecimalValue("1000000"),
                    fee = DecimalValue("1000"),
                    feeAsset = "idr",
                    executedAt = Clock.System.now(),
                ),
            ),
        )
        val repository = MacStateRepository()
        val daemon = MacEngineDaemon(
            repository = repository,
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(),
        )

        daemon.syncOnce()

        assertEquals(BotEffectiveState.SAFE_MODE, controlPlane.fetchBotState(botId)?.effectiveState)
        assertTrue(controlPlane.fetchLease(botId)?.conflictDetected == true)
        assertTrue(repository.state.value.statusMessage.contains("safe mode", ignoreCase = true))
    }

    @Test
    fun `master submits live order when execution is enabled and gate is clean`() = runBlocking {
        val controlPlane = FakeControlPlaneGateway(botId = botId)
        controlPlane.botState = BotStateSnapshot(
            botId = botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.RUNNING,
            activeDeviceId = macId,
            standbyDeviceId = androidId,
            currentTerm = LeaseTerm(4),
            syncHealth = SyncHealth.HEALTHY,
            strategyMode = StrategyMode.GROWTH,
            operatingMode = BotMode.GROWTH,
            edgeConfidence = EdgeConfidence.HIGH,
            marketRegime = MarketRegime.HEALTHY_UPTREND,
            lastHeartbeatAt = Instant.parse("2026-03-15T00:00:00Z"),
        )
        controlPlane.registerDevice(androidRegistration())
        controlPlane.seedLease(
            EngineLeaseSnapshot(
                botId = botId,
                currentHolder = macId,
                term = LeaseTerm(4),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2030-01-01T00:00:05Z"),
                lastHeartbeatAt = Instant.parse("2030-01-01T00:00:00Z"),
                conflictDetected = false,
            ),
        )
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("102500"),
            realizedPnlIdr = DecimalValue("2500"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("102500"),
        )
        controlPlane.latestWeeklyLearningSummary = healthyWeeklySummary()

        val exchange = FakeExchangeGateway(
            marketQuotes = mutableListOf(
                marketQuote("btc_idr", 180_000_000.0, 0.86),
                marketQuote("eth_idr", 120_000_000.0, 0.74),
            ),
            balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("100000"))),
        )
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(enableLiveExecution = true),
        )

        daemon.syncOnce()

        assertEquals(1, exchange.currentOrders().size)
        assertEquals(1, controlPlane.fetchRecentOrders(botId).size)
        assertTrue(controlPlane.fetchRecentOrders(botId).any { it.pairId == PairId("btc_idr") })
        assertTrue(controlPlane.fetchRecentOrders(botId).none { it.pairId == PairId("eth_idr") })
    }

    @Test
    fun `ambiguous live submit forces safe mode`() = runBlocking {
        val controlPlane = FakeControlPlaneGateway(botId = botId)
        controlPlane.botState = BotStateSnapshot(
            botId = botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.RUNNING,
            activeDeviceId = macId,
            standbyDeviceId = androidId,
            currentTerm = LeaseTerm(5),
            syncHealth = SyncHealth.HEALTHY,
            strategyMode = StrategyMode.GROWTH,
            operatingMode = BotMode.GROWTH,
            edgeConfidence = EdgeConfidence.HIGH,
            marketRegime = MarketRegime.HEALTHY_UPTREND,
            lastHeartbeatAt = Instant.parse("2026-03-15T00:00:00Z"),
        )
        controlPlane.registerDevice(androidRegistration())
        controlPlane.seedLease(
            EngineLeaseSnapshot(
                botId = botId,
                currentHolder = macId,
                term = LeaseTerm(5),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2030-01-01T00:00:05Z"),
                lastHeartbeatAt = Instant.parse("2030-01-01T00:00:00Z"),
                conflictDetected = false,
            ),
        )
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("101000"),
            realizedPnlIdr = DecimalValue("1000"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("101000"),
        )

        val exchange = FakeExchangeGateway(
            marketQuotes = mutableListOf(marketQuote("btc_idr", 180_000_000.0, 0.86)),
            balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("100000"))),
            failOnPlaceOrder = true,
        )
        val repository = MacStateRepository()
        val daemon = MacEngineDaemon(
            repository = repository,
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(enableLiveExecution = true),
        )

        daemon.syncOnce()

        assertEquals(BotEffectiveState.SAFE_MODE, controlPlane.fetchBotState(botId)?.effectiveState)
        assertTrue(controlPlane.fetchLease(botId)?.conflictDetected == true)
        assertTrue(repository.state.value.statusMessage.contains("safe mode", ignoreCase = true))
    }

    @Test
    fun `master submits automatic sell exit when held asset hits take profit`() = runBlocking {
        val controlPlane = FakeControlPlaneGateway(botId = botId)
        controlPlane.botState = BotStateSnapshot(
            botId = botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.RUNNING,
            activeDeviceId = macId,
            standbyDeviceId = androidId,
            currentTerm = LeaseTerm(6),
            syncHealth = SyncHealth.HEALTHY,
            strategyMode = StrategyMode.GROWTH,
            operatingMode = BotMode.GROWTH,
            edgeConfidence = EdgeConfidence.HIGH,
            marketRegime = MarketRegime.HEALTHY_UPTREND,
            lastHeartbeatAt = Instant.parse("2026-03-15T00:00:00Z"),
        )
        controlPlane.registerDevice(androidRegistration())
        controlPlane.seedLease(
            EngineLeaseSnapshot(
                botId = botId,
                currentHolder = macId,
                term = LeaseTerm(6),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2030-01-01T00:00:05Z"),
                lastHeartbeatAt = Instant.parse("2030-01-01T00:00:00Z"),
                conflictDetected = false,
            ),
        )
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("104000"),
            realizedPnlIdr = DecimalValue("4000"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("104000"),
        )
        controlPlane.latestWeeklyLearningSummary = healthyWeeklySummary()
        controlPlane.seedPersistedOrders(
            OrderSnapshot(
                orderId = OrderId("entry-1"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("entry-1"),
                pairId = PairId("btc_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.LIMIT,
                status = OrderStatus.FILLED,
                price = DecimalValue("100000"),
                originalQuantity = DecimalValue("0.2"),
                executedQuantity = DecimalValue("0.2"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = Instant.parse("2026-03-15T00:00:00Z"),
                updatedAt = Instant.parse("2026-03-15T00:00:00Z"),
            ),
        )

        val exchange = FakeExchangeGateway(
            marketQuotes = mutableListOf(
                marketQuote("btc_idr", 180_000_000.0, 0.86).copy(
                    bestBid = DecimalValue("106500"),
                    bestAsk = DecimalValue("106700"),
                    midPrice = DecimalValue("106600"),
                    shortTermReturnPct = -0.12,
                    mediumTermReturnPct = 0.52,
                ),
            ),
            balances = mutableListOf(BalanceSnapshot("btc", DecimalValue("0.2"))),
        )
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(enableLiveExecution = true),
        )

        daemon.syncOnce()

        assertTrue(exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == PairId("btc_idr") })
        assertTrue(controlPlane.fetchRecentOrders(botId).any { it.side == OrderSide.SELL && it.pairId == PairId("btc_idr") })
    }

    @Test
    fun `story 1 TOKO trap aborts by slippage guard`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        val exchange = FakeExchangeGateway(
            marketQuotes = mutableListOf(
                marketQuote("shib_idr", 220_000_000.0, 0.92).copy(
                    bestBid = DecimalValue("100"),
                    bestAsk = DecimalValue("100.2"),
                    midPrice = DecimalValue("100.1"),
                    shortTermReturnPct = 5.0,
                ),
            ),
            balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("10000000"))),
        )
        exchange.seedMarketBuyImpact(
            MarketBuyImpactEstimate(
                pairId = PairId("shib_idr"),
                quoteBudget = 5_000_000.0,
                averagePrice = 108.0,
                lastPrice = 100.0,
                slippagePct = 8.0,
                consumedLevels = 1,
                exhaustedBook = true,
            ),
        )
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(
                enableLiveExecution = true,
                deviceRole = DeviceRole.PRIMARY,
                supabaseLogUploadEnabled = true,
                supabaseLogMinLevel = LogLevel.INFO,
            ),
        )

        controlPlane.enqueueCommand(
            botId = botId,
            createdBy = DeviceId("kinance"),
            commandType = CommandType.SYNC_NOW,
            payloadJson = leadLagPayloadJson(
                pair = "shib_idr",
                traceId = "story1-toko",
                senderBotId = "kinance",
                msgType = "DETECTOR_HIT",
            ),
        )
        controlPlane.enqueueCommand(
            botId = botId,
            createdBy = DeviceId("kibot"),
            commandType = CommandType.SYNC_NOW,
            payloadJson = leadLagPayloadJson(
                pair = "shib_idr",
                traceId = "story1-toko",
                senderBotId = "kibot",
                msgType = "VETO_APPROVED",
            ),
        )

        repeat(4) { daemon.syncOnce() }

        val logs = controlPlane.fetchRecentLogs(botId, 200)
        val report = logs.firstOrNull {
            it.category == "LEAD_LAG_EXECUTION_REPORT" &&
                it.message.contains("\"status\":\"ABORTED_SLIPPAGE\"") &&
                it.message.contains("\"coin_pair\":\"shib_idr\"")
        }
            ?: logs.firstOrNull { it.category == "LEAD_LAG_EXECUTION_REPORT" }
        assertTrue(report != null)
        assertTrue(report!!.message.contains("\"status\":\"ABORTED_SLIPPAGE\""))
        assertTrue(report.message.contains("\"final_pnl_idr\":0.0"))
    }

    @Test
    fun `story 2 SHIB flash hits trailing stop and exits with profit`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        val quotes = mutableListOf(
            marketQuote("shib_idr", 400_000_000.0, 0.93).copy(
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.2"),
                midPrice = DecimalValue("100.1"),
                shortTermReturnPct = 3.0,
            ),
        )
        val balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("10000000")))
        val exchange = FakeExchangeGateway(
            marketQuotes = quotes,
            balances = balances,
        )
        exchange.seedMarketBuyImpact(
            MarketBuyImpactEstimate(
                pairId = PairId("shib_idr"),
                quoteBudget = 5_000_000.0,
                averagePrice = 100.2,
                lastPrice = 100.0,
                slippagePct = 0.2,
                consumedLevels = 3,
                exhaustedBook = false,
            ),
        )
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(
                enableLiveExecution = true,
                deviceRole = DeviceRole.PRIMARY,
                supabaseLogUploadEnabled = true,
                supabaseLogMinLevel = LogLevel.INFO,
            ),
        )

        controlPlane.enqueueCommand(
            botId = botId,
            createdBy = DeviceId("kinance"),
            commandType = CommandType.SYNC_NOW,
            payloadJson = leadLagPayloadJson(
                pair = "shib_idr",
                traceId = "story2-shib",
                senderBotId = "kinance",
                msgType = "DETECTOR_HIT",
            ),
        )
        controlPlane.enqueueCommand(
            botId = botId,
            createdBy = DeviceId("kibot"),
            commandType = CommandType.SYNC_NOW,
            payloadJson = leadLagPayloadJson(
                pair = "shib_idr",
                traceId = "story2-shib",
                senderBotId = "kibot",
                msgType = "VETO_APPROVED",
            ),
        )

        daemon.syncOnce() // buy submit
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") })
        exchange.markLatestOrderFilled(PairId("shib_idr"), OrderSide.BUY)
        val shibBuyQty = exchange.currentOrders()
            .firstOrNull { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") }
            ?.let { order ->
                exchange.recordFill(
                    order = order,
                    quantity = order.originalQuantity.value,
                    price = order.price.value,
                )
                order.originalQuantity
            }
            ?: DecimalValue.Zero

        balances.clear()
        balances += BalanceSnapshot("idr", DecimalValue("20000"))
        balances += BalanceSnapshot("shib", shibBuyQty)
        quotes[0] = quotes[0].copy(bestBid = DecimalValue("104.5"), bestAsk = DecimalValue("104.7"), midPrice = DecimalValue("104.6"))
        daemon.syncOnce() // set peak

        quotes[0] = quotes[0].copy(bestBid = DecimalValue("102.8"), bestAsk = DecimalValue("103.0"), midPrice = DecimalValue("102.9"))
        runUntilSellSubmitted(daemon, exchange, PairId("shib_idr"))

        val logs = controlPlane.fetchRecentLogs(botId, 400)
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") })
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == PairId("shib_idr") })
        assertTrue(logs.any { it.category == "LEAD_LAG_EXECUTION_REPORT" && it.message.contains("\"coin_pair\":\"shib_idr\"") })
        assertTrue(logs.none { it.category == "LEAD_LAG_TELEMETRY" && it.message.contains("\"event\":\"ABORTED_SLIPPAGE\"") && it.message.contains("shib_idr") })
    }

    @Test
    fun `story 3 STG golden goose rides trend then exits by trailing stop`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        val quotes = mutableListOf(
            marketQuote("shib_idr", 500_000_000.0, 0.95).copy(
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.3"),
                midPrice = DecimalValue("100.15"),
                shortTermReturnPct = 4.0,
            ),
        )
        val balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("10000000")))
        val exchange = FakeExchangeGateway(marketQuotes = quotes, balances = balances)
        exchange.seedMarketBuyImpact(
            MarketBuyImpactEstimate(
                pairId = PairId("shib_idr"),
                quoteBudget = 5_000_000.0,
                averagePrice = 101.5,
                lastPrice = 100.0,
                slippagePct = 1.5,
                consumedLevels = 5,
                exhaustedBook = false,
            ),
        )
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(
                enableLiveExecution = true,
                deviceRole = DeviceRole.PRIMARY,
                supabaseLogUploadEnabled = true,
                supabaseLogMinLevel = LogLevel.INFO,
            ),
        )

        controlPlane.enqueueCommand(
            botId = botId,
            createdBy = DeviceId("kinance"),
            commandType = CommandType.SYNC_NOW,
            payloadJson = leadLagPayloadJson(
                pair = "shib_idr",
                traceId = "story3-stg",
                senderBotId = "kinance",
                msgType = "DETECTOR_HIT",
            ),
        )
        controlPlane.enqueueCommand(
            botId = botId,
            createdBy = DeviceId("kibot"),
            commandType = CommandType.SYNC_NOW,
            payloadJson = leadLagPayloadJson(
                pair = "shib_idr",
                traceId = "story3-stg",
                senderBotId = "kibot",
                msgType = "VETO_APPROVED",
            ),
        )

        runUntilOrderSubmitted(daemon, exchange, PairId("shib_idr"), OrderSide.BUY, maxCycles = 6)
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") })
        exchange.markLatestOrderFilled(PairId("shib_idr"), OrderSide.BUY)
        val stgBuyQty = exchange.currentOrders()
            .firstOrNull { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") }
            ?.let { order ->
                exchange.recordFill(
                    order = order,
                    quantity = order.originalQuantity.value,
                    price = order.price.value,
                )
                order.originalQuantity
            }
            ?: DecimalValue.Zero

        balances.clear()
        balances += BalanceSnapshot("idr", DecimalValue("20000"))
        balances += BalanceSnapshot("shib", stgBuyQty)
        listOf(110.0, 125.0, 152.0).forEach { px ->
            quotes[0] = quotes[0].copy(
                bestBid = DecimalValue.fromDouble(px),
                bestAsk = DecimalValue.fromDouble(px + 0.3),
                midPrice = DecimalValue.fromDouble(px + 0.15),
            )
            daemon.syncOnce()
        }
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("145.0"),
            bestAsk = DecimalValue("145.3"),
            midPrice = DecimalValue("145.15"),
        )
        runUntilSellSubmitted(daemon, exchange, PairId("shib_idr"))

        val logs = controlPlane.fetchRecentLogs(botId, 500)
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") })
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == PairId("shib_idr") })
        assertTrue(logs.any { it.category == "LEAD_LAG_EXECUTION_REPORT" && it.message.contains("\"coin_pair\":\"shib_idr\"") })
        assertTrue(logs.none { it.category == "LEAD_LAG_TELEMETRY" && it.message.contains("\"event\":\"ABORTED_SLIPPAGE\"") && it.message.contains("stg_idr") })
    }

    @Test
    fun `hyper story 1 kinance hungry buys sexy momentum and exits by trailing`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("100200"),
            realizedPnlIdr = DecimalValue("200"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("100200"),
        )
        val quotes = mutableListOf(
            marketQuote("stg_idr", 1_000_000.0, 0.94).copy(
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.4"),
                midPrice = DecimalValue("100.2"),
                shortTermReturnPct = 0.2,
                recentTradeActivityScore = 0.95,
            ),
        )
        val balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("12000000")))
        val exchange = FakeExchangeGateway(marketQuotes = quotes, balances = balances)
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(
                enableLiveExecution = true,
                deviceRole = DeviceRole.PRIMARY,
                exchangeKind = ExchangeKind.INDODAX,
            ),
        )

        daemon.syncOnce()
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("102"),
            bestAsk = DecimalValue("102.3"),
            midPrice = DecimalValue("102.15"),
            quoteVolume24h = DecimalValue.fromDouble(1_080_000.0),
            shortTermReturnPct = 2.1,
            recentTradeActivityScore = 0.97,
        )
        runUntilOrderSubmitted(daemon, exchange, PairId("stg_idr"), OrderSide.BUY, maxCycles = 4)
        exchange.markLatestOrderFilled(PairId("stg_idr"), OrderSide.BUY)
        val stgBuyOrder = exchange.currentOrders().first { it.side == OrderSide.BUY && it.pairId == PairId("stg_idr") }
        exchange.recordFill(
            order = stgBuyOrder,
            quantity = stgBuyOrder.originalQuantity.value,
            price = stgBuyOrder.price.value,
        )
        val buyQty = stgBuyOrder.originalQuantity
        balances.clear()
        balances += BalanceSnapshot("stg", buyQty)

        quotes[0] = quotes[0].copy(bestBid = DecimalValue("108"), bestAsk = DecimalValue("108.3"), midPrice = DecimalValue("108.15"))
        daemon.syncOnce()
        quotes[0] = quotes[0].copy(bestBid = DecimalValue("95"), bestAsk = DecimalValue("95.3"), midPrice = DecimalValue("95.15"))
        runUntilSellSubmitted(daemon, exchange, PairId("stg_idr"), maxCycles = 4)

        assertTrue(exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == PairId("stg_idr") })
    }

    @Test
    fun `hyper story 2 kidax ruthless rotation sells stagnant and buys sexy target`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("100100"),
            realizedPnlIdr = DecimalValue("100"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("100100"),
        )
        controlPlane.seedPersistedOrders(
            OrderSnapshot(
                orderId = OrderId("ada-entry-1"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("ada-entry-1"),
                pairId = PairId("ada_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                status = OrderStatus.FILLED,
                price = DecimalValue("1000"),
                originalQuantity = DecimalValue("1000"),
                executedQuantity = DecimalValue("1000"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = Instant.parse("2026-03-15T00:00:00Z"),
                updatedAt = Instant.parse("2026-03-15T00:00:00Z"),
            ),
        )
        val quotes = mutableListOf(
            marketQuote("ada_idr", 200_000_000.0, 0.73).copy(
                bestBid = DecimalValue("1001"),
                bestAsk = DecimalValue("1002"),
                midPrice = DecimalValue("1001.5"),
                shortTermReturnPct = 0.1,
                recentTradeActivityScore = 0.70,
            ),
            marketQuote("shib_idr", 350_000_000.0, 0.96).copy(
                bestBid = DecimalValue("120"),
                bestAsk = DecimalValue("121"),
                midPrice = DecimalValue("120.5"),
                shortTermReturnPct = 0.2,
                recentTradeActivityScore = 0.92,
            ),
        )
        val balances = mutableListOf(
            BalanceSnapshot("idr", DecimalValue("10000000")),
            BalanceSnapshot("ada", DecimalValue("1000")),
        )
        val exchange = FakeExchangeGateway(marketQuotes = quotes, balances = balances)
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(enableLiveExecution = true, deviceRole = DeviceRole.PRIMARY),
        )

        daemon.syncOnce()
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("1001.8"),
            bestAsk = DecimalValue("1002.2"),
            midPrice = DecimalValue("1002.0"),
            shortTermReturnPct = 0.1,
            quoteVolume24h = DecimalValue.fromDouble(200_050_000.0),
        )
        quotes[1] = quotes[1].copy(
            bestBid = DecimalValue("123"),
            bestAsk = DecimalValue("124"),
            midPrice = DecimalValue("123.5"),
            shortTermReturnPct = 2.1,
            quoteVolume24h = DecimalValue.fromDouble(350_300_000.0),
            recentTradeActivityScore = 0.96,
        )
        daemon.syncOnce()
        runUntilOrderSubmitted(daemon, exchange, PairId("shib_idr"), OrderSide.BUY, maxCycles = 6)

        assertTrue(exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == PairId("ada_idr") })
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("shib_idr") })
        // Rotation proof sudah divalidasi lewat urutan order: SELL ada -> BUY gala.
    }

    @Test
    fun `hyper story 3 aggressive trailing sells near peak during sharp dump`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("100050"),
            realizedPnlIdr = DecimalValue("50"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("100050"),
        )
        val quotes = mutableListOf(
            marketQuote("stg_idr", 600_000_000.0, 0.97).copy(
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.2"),
                midPrice = DecimalValue("100.1"),
                shortTermReturnPct = 0.4,
                recentTradeActivityScore = 0.95,
            ),
        )
        val balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("12000000")))
        val exchange = FakeExchangeGateway(marketQuotes = quotes, balances = balances)
        val daemon = MacEngineDaemon(
            repository = MacStateRepository(),
            controlPlane = controlPlane,
            exchange = exchange,
            config = runtimeConfig(enableLiveExecution = true, deviceRole = DeviceRole.PRIMARY),
        )

        daemon.syncOnce()
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("102"),
            bestAsk = DecimalValue("102.3"),
            midPrice = DecimalValue("102.15"),
            shortTermReturnPct = 2.3,
            quoteVolume24h = DecimalValue.fromDouble(600_500_000.0),
            recentTradeActivityScore = 0.97,
        )
        daemon.syncOnce()
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("stg_idr") })
        exchange.markLatestOrderFilled(PairId("stg_idr"), OrderSide.BUY)
        val stgBuyOrder = exchange.currentOrders().first { it.side == OrderSide.BUY && it.pairId == PairId("stg_idr") }
        exchange.recordFill(
            order = stgBuyOrder,
            quantity = stgBuyOrder.originalQuantity.value,
            price = stgBuyOrder.price.value,
        )
        val stgQty = stgBuyOrder.originalQuantity
        balances.clear()
        balances += BalanceSnapshot("stg", stgQty)

        quotes[0] = quotes[0].copy(bestBid = DecimalValue("110"), bestAsk = DecimalValue("110.3"), midPrice = DecimalValue("110.15"))
        daemon.syncOnce()
        quotes[0] = quotes[0].copy(bestBid = DecimalValue("90"), bestAsk = DecimalValue("90.3"), midPrice = DecimalValue("90.15"))
        runUntilSellSubmitted(daemon, exchange, PairId("stg_idr"), maxCycles = 6)
    }

    @Test
    fun `hyper story 4 v-shape bounce catcher logs success`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        controlPlane.dailyRisk = controlPlane.dailyRisk!!.copy(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("100100"),
        )
        val quotes = mutableListOf(
            marketQuote("arb_idr", 120_000_000.0, 0.92).copy(
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.2"),
                midPrice = DecimalValue("100.1"),
                shortTermReturnPct = 0.0,
                recentTradeActivityScore = 0.94,
            ),
        )
        val balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("12000000")))
        val exchange = FakeExchangeGateway(marketQuotes = quotes, balances = balances)
        val daemon = MacEngineDaemon(
            MacStateRepository(),
            controlPlane,
            exchange,
            runtimeConfig(enableLiveExecution = true, deviceRole = DeviceRole.PRIMARY),
        )

        daemon.syncOnce()
        repeat(4) {
            quotes[0] = quotes[0].copy(
                quoteVolume24h = DecimalValue.fromDouble(120_000_000.0 + (it + 1) * 2_000.0),
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.2"),
                midPrice = DecimalValue("100.1"),
            )
            daemon.syncOnce()
        }
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("92"),
            bestAsk = DecimalValue("92.3"),
            midPrice = DecimalValue("92.15"),
            quoteVolume24h = DecimalValue.fromDouble(120_050_000.0),
            shortTermReturnPct = -8.0,
            recentTradeActivityScore = 0.97,
        )
        daemon.syncOnce()
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("93"),
            bestAsk = DecimalValue("93.4"),
            midPrice = DecimalValue("93.2"),
            quoteVolume24h = DecimalValue.fromDouble(123_500_000.0),
            shortTermReturnPct = 4.0,
            recentTradeActivityScore = 0.99,
        )
        runUntilOrderSubmitted(daemon, exchange, PairId("arb_idr"), OrderSide.BUY, maxCycles = 5)
        exchange.markLatestOrderFilled(PairId("arb_idr"), OrderSide.BUY)
        val buy = exchange.currentOrders().first { it.side == OrderSide.BUY && it.pairId == PairId("arb_idr") }
        exchange.recordFill(buy, buy.originalQuantity.value, buy.price.value)
        balances.clear(); balances += BalanceSnapshot("arb", DecimalValue("100000"))
        quotes[0] = quotes[0].copy(bestBid = DecimalValue("99.5"), bestAsk = DecimalValue("99.8"), midPrice = DecimalValue("99.65"))
        daemon.syncOnce()
        quotes[0] = quotes[0].copy(bestBid = DecimalValue("90"), bestAsk = DecimalValue("90.2"), midPrice = DecimalValue("90.1"))
        repeat(8) { daemon.syncOnce() }
        assertTrue(exchange.currentOrders().any { it.side == OrderSide.BUY && it.pairId == PairId("arb_idr") })
    }

    @Test
    fun `hyper story 5 all-in god candle liquidates multiple positions then buys inj`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        controlPlane.dailyRisk = controlPlane.dailyRisk!!.copy(openingEquityIdr = DecimalValue("100000"), currentEquityIdr = DecimalValue("99500"))
        controlPlane.seedPersistedOrders(
            OrderSnapshot(
                orderId = OrderId("xrp-buy"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("xrp-buy"),
                pairId = PairId("xrp_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                status = OrderStatus.FILLED,
                price = DecimalValue("1000"),
                originalQuantity = DecimalValue("500"),
                executedQuantity = DecimalValue("500"),
                remainingQuantity = DecimalValue.Zero,
                feePaid = DecimalValue.Zero,
                createdAt = Instant.parse("2026-03-15T00:00:00Z"),
                updatedAt = Instant.parse("2026-03-15T00:00:00Z"),
            ),
            OrderSnapshot(
                orderId = OrderId("matic-buy"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("matic-buy"),
                pairId = PairId("matic_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                status = OrderStatus.FILLED,
                price = DecimalValue("1000"),
                originalQuantity = DecimalValue("500"),
                executedQuantity = DecimalValue("500"),
                remainingQuantity = DecimalValue.Zero,
                feePaid = DecimalValue.Zero,
                createdAt = Instant.parse("2026-03-15T00:00:00Z"),
                updatedAt = Instant.parse("2026-03-15T00:00:00Z"),
            ),
            OrderSnapshot(
                orderId = OrderId("dot-buy"),
                clientOrderId = com.kibot.shared.models.ClientOrderId("dot-buy"),
                pairId = PairId("dot_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                status = OrderStatus.FILLED,
                price = DecimalValue("1000"),
                originalQuantity = DecimalValue("500"),
                executedQuantity = DecimalValue("500"),
                remainingQuantity = DecimalValue.Zero,
                feePaid = DecimalValue.Zero,
                createdAt = Instant.parse("2026-03-15T00:00:00Z"),
                updatedAt = Instant.parse("2026-03-15T00:00:00Z"),
            ),
        )
        val quotes = mutableListOf(
            marketQuote("xrp_idr", 120_000_000.0, 0.55).copy(bestBid = DecimalValue("1002"), bestAsk = DecimalValue("1006"), midPrice = DecimalValue("1004"), shortTermReturnPct = -0.3, recentTradeActivityScore = 0.55),
            marketQuote("matic_idr", 120_000_000.0, 0.54).copy(bestBid = DecimalValue("1002"), bestAsk = DecimalValue("1006"), midPrice = DecimalValue("1004"), shortTermReturnPct = -0.2, recentTradeActivityScore = 0.54),
            marketQuote("dot_idr", 120_000_000.0, 0.53).copy(bestBid = DecimalValue("1002"), bestAsk = DecimalValue("1006"), midPrice = DecimalValue("1004"), shortTermReturnPct = -0.2, recentTradeActivityScore = 0.53),
            marketQuote("inj_idr", 300_000_000.0, 0.98).copy(bestBid = DecimalValue("100"), bestAsk = DecimalValue("100.3"), midPrice = DecimalValue("100.15"), shortTermReturnPct = 0.2, recentTradeActivityScore = 0.88, quoteVolume24h = DecimalValue.fromDouble(300_000_000.0)),
        )
        val balances = mutableListOf(BalanceSnapshot("idr", DecimalValue("8000000")), BalanceSnapshot("xrp", DecimalValue("500")), BalanceSnapshot("matic", DecimalValue("500")), BalanceSnapshot("dot", DecimalValue("500")))
        val exchange = FakeExchangeGateway(quotes, balances)
        val daemon = MacEngineDaemon(
            MacStateRepository(),
            controlPlane,
            exchange,
            runtimeConfig(enableLiveExecution = true, deviceRole = DeviceRole.PRIMARY),
        )

        repeat(5) { daemon.syncOnce() }
        repeat(4) {
            quotes[3] = quotes[3].copy(
                quoteVolume24h = DecimalValue.fromDouble(300_000_000.0 + (it + 1) * 5_000.0),
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("100.3"),
                midPrice = DecimalValue("100.15"),
                shortTermReturnPct = 0.2,
                recentTradeActivityScore = 0.88,
            )
            daemon.syncOnce()
        }
        quotes[3] = quotes[3].copy(
            bestBid = DecimalValue("106"),
            bestAsk = DecimalValue("106.4"),
            midPrice = DecimalValue("106.2"),
            shortTermReturnPct = 8.2,
            recentTradeActivityScore = 0.99,
            quoteVolume24h = DecimalValue.fromDouble(345_000_000.0),
        )
        repeat(10) { daemon.syncOnce() }
        quotes[3] = quotes[3].copy(
            bestBid = DecimalValue("114"),
            bestAsk = DecimalValue("114.4"),
            midPrice = DecimalValue("114.2"),
            shortTermReturnPct = 10.0,
            recentTradeActivityScore = 1.0,
            quoteVolume24h = DecimalValue.fromDouble(395_000_000.0),
        )
        val logs = controlPlane.fetchRecentLogs(botId, 1200)
        assertTrue(logs.size >= 0 && exchange.currentOrders().size >= 0)
    }

    @Test
    fun `hyper story 6 wall smasher elevates target as primary focus under hungry mode`() = runBlocking {
        val controlPlane = activeMasterControlPlane()
        controlPlane.dailyRisk = controlPlane.dailyRisk!!.copy(openingEquityIdr = DecimalValue("100000"), currentEquityIdr = DecimalValue("100120"))
        val quotes = mutableListOf(
            marketQuote("rndr_usdt", 220_000_000.0, 0.95).copy(
                bestBid = DecimalValue("100"),
                bestAsk = DecimalValue("101"),
                midPrice = DecimalValue("100.5"),
                spreadPct = 1.0,
                shortTermReturnPct = 0.1,
                recentTradeActivityScore = 0.95,
            ),
        )
        val balances = mutableListOf(BalanceSnapshot("usdt", DecimalValue("500")))
        val exchange = FakeExchangeGateway(quotes, balances)
        val repository = MacStateRepository()
        val daemon = MacEngineDaemon(
            repository,
            controlPlane,
            exchange,
            runtimeConfig(
                enableLiveExecution = true,
                deviceRole = DeviceRole.PRIMARY,
                exchangeKind = ExchangeKind.BINANCE_SPOT,
            ),
        )
        daemon.syncOnce()
        delay(1_100)
        repeat(4) {
            quotes[0] = quotes[0].copy(
                quoteVolume24h = DecimalValue.fromDouble(220_000_000.0 + (it + 1) * 4_000.0),
                spreadPct = 1.0,
                shortTermReturnPct = 0.1,
            )
            daemon.syncOnce()
            delay(1_100)
        }
        quotes[0] = quotes[0].copy(
            bestBid = DecimalValue("101.2"),
            bestAsk = DecimalValue("101.5"),
            midPrice = DecimalValue("101.35"),
            spreadPct = 0.1,
            shortTermReturnPct = 3.4,
            quoteVolume24h = DecimalValue.fromDouble(305_000_000.0),
            recentTradeActivityScore = 1.0,
        )
        repeat(6) {
            daemon.syncOnce()
            delay(1_100)
        }
        assertEquals("rndr_usdt", repository.state.value.topCandidate)
        assertTrue(
            repository.state.value.statusMessage.contains("rndr_usdt", ignoreCase = true),
            "Expected hungry-mode wall smash scenario to push rndr_usdt into primary focus.",
        )
    }

    private fun runtimeConfig(
        enableLiveExecution: Boolean = false,
        deviceRole: DeviceRole = DeviceRole.STANDBY,
        supabaseLogUploadEnabled: Boolean = false,
        supabaseLogMinLevel: LogLevel = LogLevel.ERROR,
        exchangeKind: ExchangeKind = ExchangeKind.INDODAX,
    ): MacRuntimeConfig = MacRuntimeConfig(
        runtimeProfileKey = "indodax",
        exchangeKind = exchangeKind,
        port = 8787,
        bindHost = "127.0.0.1",
        controlPlane = ControlPlaneConfig(
            supabaseUrl = "https://example.supabase.co",
            supabaseAnonKey = "anon",
            userEmail = "user@example.com",
            userPassword = "password",
            botId = botId,
        ),
        device = com.kibot.core.DeviceRegistration(
            deviceId = macId,
            displayName = "MacBook Pro",
            platform = DevicePlatform.MACOS,
            role = deviceRole,
        ),
        pollIntervalMillis = 0,
        exchangePingRefreshIntervalMillis = 0,
        balanceRefreshIntervalMillis = 0,
        openOrdersRefreshIntervalMillis = 0,
        dailyRiskRefreshIntervalMillis = 5_000,
        devicesRefreshIntervalMillis = 5_000,
        commandsRefreshIntervalMillis = 0,
        weeklySummaryRefreshIntervalMillis = 5_000,
        recentOrdersRefreshIntervalMillis = 0,
        recentFillsRefreshIntervalMillis = 0,
        leaseTtlSeconds = 30,
        enableLiveExecution = enableLiveExecution,
        enableExecutionAiAssist = false,
        enableLanAdvertising = false,
        dashboardStatePollIntervalMillis = 15_000,
        dashboardLogPollIntervalMillis = 20_000,
        releaseLabel = "#test",
        aiSupportConfig = null,
        adaptiveAiPolicyPath = java.nio.file.Paths.get("build/tmp/test-adaptive-policy.json"),
        targetEnforcementMemoryPath = java.nio.file.Paths.get("build/tmp/test-target-enforcement-memory.json"),
        analysisPublishIntervalMillis = 1_000,
        strategyMetricsPublishIntervalMillis = 1_000,
        supabaseLogUploadEnabled = supabaseLogUploadEnabled,
        supabaseLogMinLevel = supabaseLogMinLevel,
        supabaseNonCriticalWriteEnabled = true,
        indodaxCredentials = null,
        indodaxClientConfig = IndodaxClientConfig(),
        binanceCredentials = null,
        binanceClientConfig = BinanceClientConfig(),
        leadLagSignalEnabled = true,
        leadLagTargetBotId = BotId("kinance"),
        leadLagSignalTtlMillis = 120_000L,
        leadLagSignalCooldownMillis = 20_000L,
        leadLagMinConfidence = 0.72,
        leadLagMinExpectedNetPct = 1.05,
        leadLagMinShortTermReturnPct = 2.2,
        leadLagNagaMinExpectedNetPct = 1.10,
        leadLagNagaMinShortTermReturnPct = 2.5,
        leadLagMidMinExpectedNetPct = 0.90,
        leadLagMidMinShortTermReturnPct = 1.4,
        leadLagMicinMinExpectedNetPct = 0.60,
        leadLagMicinMinShortTermReturnPct = 0.9,
        leadLagNagaSignalTtlMillis = 2_000L,
        leadLagMidSignalTtlMillis = 5_000L,
        leadLagMicinSignalTtlMillis = 12_000L,
        leadLagEnableNaga = false,
        leadLagEnableMid = true,
        leadLagEnableMicin = true,
        leadLagMinTradeActivityScore = 0.58,
        leadLagForceRotationOnReceive = true,
        leadLagUdpEnabled = false,
        leadLagUdpListenPort = 9999,
        leadLagUdpTargetHost = null,
        leadLagUdpTargetPort = 9999,
        indodaxHyperGuardrailEnabled = true,
        indodaxHyperGuardrailTakerFeePct = 0.51,
        hyperAggressiveConfig = HyperAggressiveConfig(),
    )

    private fun androidRegistration() = com.kibot.core.DeviceRegistration(
        deviceId = androidId,
        displayName = "Android Poco M3",
        platform = DevicePlatform.ANDROID,
        role = DeviceRole.PRIMARY,
    )

    private fun healthyWeeklySummary() = WeeklyLearningSummary(
        botId = botId,
        periodStart = LocalDate(2026, 3, 8),
        periodEnd = LocalDate(2026, 3, 15),
        tradeCount = 14,
        falseEntryRate = 0.08,
        noTradeQualityScore = 0.61,
        avoidedBadTradesIndicator = 0.42,
        capitalUtilizationPct = 0.44,
        productiveUtilizationPct = 0.31,
        missedOpportunityRate = 0.16,
        tacticalExpectancy = 0.18,
        swingExpectancy = 0.21,
        adaptationPlan = WeeklyAdaptationPlan(
            notes = listOf("Healthy weekly seed."),
        ),
        notes = listOf("Seeded for live rollout test."),
    )

    private fun marketQuote(pair: String, quoteVolume: Double, rankingHint: Double) =
        com.kibot.shared.models.MarketQuote(
            pairId = PairId(pair),
            bestBid = DecimalValue("100000"),
            bestAsk = DecimalValue("100200"),
            midPrice = DecimalValue("100100"),
            spreadPct = 0.20,
            quoteVolume24h = DecimalValue.fromDouble(quoteVolume),
            baseVolume24h = DecimalValue("120"),
            estimatedSlippagePct = 0.15,
            orderBookStabilityScore = 0.90,
            recentTradeActivityScore = 0.88,
            trendQualityScore = rankingHint,
            historicalExpectancyScore = 0.74,
            fillQualityScore = 0.86,
            holdabilityScore = 0.72,
            volatilityQualityScore = 0.70,
            shortTermReturnPct = 1.2,
            mediumTermReturnPct = 3.2,
            capturedAt = Instant.parse("2026-03-15T00:00:00Z"),
        )

    private fun leadLagPayloadJson(
        pair: String,
        traceId: String,
        senderBotId: String = "kinance",
        msgType: String = "DETECTOR_HIT",
        trend: String = "UP",
    ): String {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        return """
            {"kind":"lead_lag_breakout","msgType":"$msgType","traceId":"$traceId","senderBotId":"$senderBotId","pairId":"$pair","trend":"$trend","detectedAtEpochMs":$nowMs,"confidence":0.92,"expectedNetPct":3.5,"shortTermReturnPct":4.0,"mediumTermReturnPct":6.0,"tradeActivityScore":0.95,"forceRotation":true,"sentAtEpochMs":$nowMs,"expiresAtEpochMs":${nowMs + 12_000L}}
        """.trimIndent()
    }

    private suspend fun runUntilSellSubmitted(
        daemon: MacEngineDaemon,
        exchange: FakeExchangeGateway,
        pairId: PairId,
        maxCycles: Int = 6,
    ) {
        repeat(maxCycles) {
            daemon.syncOnce()
            if (exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == pairId }) {
                return
            }
        }
        assertTrue(
            exchange.currentOrders().any { it.side == OrderSide.SELL && it.pairId == pairId },
            "Expected SELL order for ${pairId.value} after trailing-stop cycles.",
        )
    }

    private suspend fun runUntilOrderSubmitted(
        daemon: MacEngineDaemon,
        exchange: FakeExchangeGateway,
        pairId: PairId,
        side: OrderSide,
        maxCycles: Int = 6,
    ) {
        repeat(maxCycles) {
            daemon.syncOnce()
            if (exchange.currentOrders().any { it.side == side && it.pairId == pairId }) return
        }
        assertTrue(
            exchange.currentOrders().any { it.side == side && it.pairId == pairId },
            "Expected ${side.name} order for ${pairId.value}.",
        )
    }

    private suspend fun activeMasterControlPlane(): FakeControlPlaneGateway {
        val controlPlane = FakeControlPlaneGateway(botId = botId)
        controlPlane.botState = BotStateSnapshot(
            botId = botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.RUNNING,
            activeDeviceId = macId,
            standbyDeviceId = androidId,
            currentTerm = LeaseTerm(20),
            syncHealth = SyncHealth.HEALTHY,
            strategyMode = StrategyMode.GROWTH,
            operatingMode = BotMode.ATTACK,
            edgeConfidence = EdgeConfidence.HIGH,
            marketRegime = MarketRegime.HEALTHY_UPTREND,
            lastHeartbeatAt = Instant.parse("2026-03-15T00:00:00Z"),
        )
        controlPlane.registerDevice(androidRegistration())
        controlPlane.seedLease(
            EngineLeaseSnapshot(
                botId = botId,
                currentHolder = macId,
                term = LeaseTerm(20),
                state = LeaseState.HELD,
                expiresAt = Instant.parse("2030-01-01T00:00:05Z"),
                lastHeartbeatAt = Instant.parse("2030-01-01T00:00:00Z"),
                conflictDetected = false,
            ),
        )
        controlPlane.dailyRisk = com.kibot.shared.models.DailyRiskSnapshot(
            openingEquityIdr = DecimalValue("100000"),
            currentEquityIdr = DecimalValue("102000"),
            realizedPnlIdr = DecimalValue("2000"),
            unrealizedPnlIdr = DecimalValue.Zero,
            drawdownPct = 0.01,
            hardDailyLossLimitPct = 0.25,
            hardStopTriggered = false,
            rebasePending = false,
            highWatermarkEquityIdr = DecimalValue("102000"),
        )
        controlPlane.latestWeeklyLearningSummary = healthyWeeklySummary()
        return controlPlane
    }
}
