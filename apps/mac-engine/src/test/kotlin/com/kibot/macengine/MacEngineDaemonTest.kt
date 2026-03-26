package com.kibot.macengine

import com.kibot.controlplane.ControlPlaneConfig
import com.kibot.indodax.IndodaxClientConfig
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
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LeaseTerm
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
import com.kibot.testkit.FakeControlPlaneGateway
import com.kibot.testkit.FakeExchangeGateway
import kotlinx.coroutines.runBlocking
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
                    orderId = OrderId("ex-1"),
                    clientOrderId = com.kibot.shared.models.ClientOrderId("client-1"),
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
        assertEquals(PairId("btc_idr"), controlPlane.fetchRecentOrders(botId).first().pairId)
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

    private fun runtimeConfig(enableLiveExecution: Boolean = false): MacRuntimeConfig = MacRuntimeConfig(
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
            role = DeviceRole.STANDBY,
        ),
        pollIntervalMillis = 1_000,
        exchangePingRefreshIntervalMillis = 1_000,
        balanceRefreshIntervalMillis = 1_000,
        openOrdersRefreshIntervalMillis = 1_000,
        dailyRiskRefreshIntervalMillis = 5_000,
        devicesRefreshIntervalMillis = 5_000,
        commandsRefreshIntervalMillis = 1_000,
        weeklySummaryRefreshIntervalMillis = 5_000,
        recentOrdersRefreshIntervalMillis = 1_000,
        recentFillsRefreshIntervalMillis = 1_000,
        leaseTtlSeconds = 30,
        enableLiveExecution = enableLiveExecution,
        enableLanAdvertising = false,
        dashboardStatePollIntervalMillis = 15_000,
        dashboardLogPollIntervalMillis = 20_000,
        releaseLabel = "#test",
        aiSupportConfig = null,
        adaptiveAiPolicyPath = java.nio.file.Paths.get("build/tmp/test-adaptive-policy.json"),
        targetEnforcementMemoryPath = java.nio.file.Paths.get("build/tmp/test-target-enforcement-memory.json"),
        analysisPublishIntervalMillis = 1_000,
        strategyMetricsPublishIntervalMillis = 1_000,
        indodaxCredentials = null,
        indodaxClientConfig = IndodaxClientConfig(),
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
}
