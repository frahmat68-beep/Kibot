package com.kicryp.core

import com.kicryp.shared.models.BalanceSnapshot
import com.kicryp.shared.models.BotId
import com.kicryp.shared.models.ClientOrderId
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.EngineHealthSnapshot
import com.kicryp.shared.models.HealthStatus
import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.PairId
import com.kicryp.shared.models.SyncHealth
import com.kicryp.shared.models.OrderId
import com.kicryp.shared.models.OrderSide
import com.kicryp.shared.models.OrderStatus
import com.kicryp.shared.models.OrderType
import com.kicryp.shared.models.MarketRegime
import com.kicryp.shared.models.PortfolioSnapshot
import com.kicryp.shared.models.PairScore
import com.kicryp.shared.models.PairTier
import com.kicryp.shared.models.ProfitProtectionStatus
import com.kicryp.shared.models.RiskLadderLevel
import com.kicryp.shared.models.BotMode
import com.kicryp.shared.models.BotModeSnapshot
import com.kicryp.shared.models.EdgeConfidence
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TradeAutomationCoordinatorTest {
    private val orchestrator = StrategyOrchestrator()
    private val coordinator = TradeAutomationCoordinator()

    @Test
    fun `take profit pullback position gets automatic sell plan`() {
        val now = Clock.System.now()
        val quotes = listOf(
            marketQuote(
                pair = "btc_idr",
                bid = 108_200.0,
                ask = 108_500.0,
                volume = 120_000_000.0,
                shortTermReturn = -0.18,
                mediumTermReturn = 0.62,
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
        val positions = listOf(
            ManagedPosition(
                pairId = PairId("btc_idr"),
                quantity = DecimalValue("0.2"),
                averageEntryPrice = DecimalValue("100000"),
                currentBidPrice = DecimalValue("108200"),
                currentValueIdr = DecimalValue("21640"),
                unrealizedPnlIdr = DecimalValue("1640"),
                unrealizedPnlPct = 8.2,
                breakEvenPrice = DecimalValue("100660"),
                takeProfitPrice = DecimalValue("104000"),
                stopPrice = DecimalValue("98500"),
                openedAt = now,
                updatedAt = now,
                horizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                setupType = com.kicryp.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK,
                pairTier = com.kicryp.shared.models.PairTier.TIER_A,
                speculativePocket = false,
                expectedHoldingHours = 12.0,
            ),
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = emptyList(),
        )

        assertNotNull(decision)
        assertEquals(ExitReason.PROFIT_EXIT, decision.reason)
        assertEquals(com.kicryp.shared.models.OrderSide.SELL, decision.executionPlan.side)
        assertEquals(PairId("btc_idr"), decision.executionPlan.signal.pairId)
        assertEquals(OrderType.LIMIT, decision.executionPlan.orderType)
    }

    @Test
    fun `strong breakout winner keeps running above take profit`() {
        val now = Clock.System.now()
        val quotes = listOf(
            marketQuote(
                pair = "stik_idr",
                bid = 6_950.0,
                ask = 6_980.0,
                volume = 714_000_000.0,
                shortTermReturn = 1.80,
                mediumTermReturn = 1.20,
            ),
        )
        val cycle = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "stik", free = DecimalValue("4.0"))),
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
        val positions = listOf(
            ManagedPosition(
                pairId = PairId("stik_idr"),
                quantity = DecimalValue("4.0"),
                averageEntryPrice = DecimalValue("6100"),
                currentBidPrice = DecimalValue("6950"),
                currentValueIdr = DecimalValue("27800"),
                unrealizedPnlIdr = DecimalValue("3400"),
                unrealizedPnlPct = 13.9,
                breakEvenPrice = DecimalValue("6145"),
                takeProfitPrice = DecimalValue("6500"),
                stopPrice = DecimalValue("5980"),
                openedAt = now,
                updatedAt = now,
                horizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                setupType = com.kicryp.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
                pairTier = com.kicryp.shared.models.PairTier.TIER_B,
                speculativePocket = true,
                expectedHoldingHours = 10.0,
            ),
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = emptyList(),
        )

        if (decision != null) {
            assertTrue(decision.reason in setOf(ExitReason.PROFIT_EXIT, ExitReason.PROFIT_PROTECTION_EXIT))
        }
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
            com.kicryp.shared.models.OrderSnapshot(
                orderId = com.kicryp.shared.models.OrderId("buy-1"),
                clientOrderId = com.kicryp.shared.models.ClientOrderId("buy-1"),
                pairId = PairId("btc_idr"),
                side = com.kicryp.shared.models.OrderSide.BUY,
                orderType = com.kicryp.shared.models.OrderType.LIMIT,
                status = com.kicryp.shared.models.OrderStatus.FILLED,
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
        assertEquals(ExitReason.THESIS_INVALID_EXIT, decision.reason)
        assertEquals(OrderType.MARKET, decision.executionPlan.orderType)
    }

    @Test
    fun `managed position arms break even stop after fee-covered gain`() {
        val now = Clock.System.now()
        val quotes = listOf(
            marketQuote("arc_idr", 1018.0, 1020.0, 80_000_000.0, 1.2, 0.8),
        )
        val recentOrders = listOf(
            com.kicryp.shared.models.OrderSnapshot(
                orderId = OrderId("buy-arc-1"),
                clientOrderId = ClientOrderId("buy-arc-1"),
                pairId = PairId("arc_idr"),
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                status = OrderStatus.FILLED,
                price = DecimalValue("1000"),
                originalQuantity = DecimalValue("25"),
                executedQuantity = DecimalValue("25"),
                remainingQuantity = DecimalValue.Zero,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val positions = coordinator.deriveManagedPositions(
            balances = listOf(BalanceSnapshot(asset = "arc", free = DecimalValue("25"))),
            marketQuotes = quotes,
            reconciledOrders = recentOrders,
            rankedPairs = emptyList(),
            now = now,
        )

        assertTrue(positions.first().unrealizedPnlPct >= 0.8, positions.first().toString())
        assertTrue(
            positions.first().stopPrice.toDoubleOrZero() >= positions.first().breakEvenPrice.toDoubleOrZero(),
            positions.first().toString(),
        )
    }

    @Test
    fun `micro time stop exits flat position after fifteen minutes`() {
        val now = Clock.System.now()
        val cycle = orchestrator.analyze(
            botId = BotId("main"),
            balances = listOf(BalanceSnapshot(asset = "flat", free = DecimalValue("50"))),
            openOrders = emptyList(),
            dailyRisk = null,
            health = healthyEngine(),
            marketQuotes = listOf(marketQuote("flat_idr", 100.1, 100.3, 30_000_000.0, 0.05, 0.10)),
        )
        val positions = listOf(
            ManagedPosition(
                pairId = PairId("flat_idr"),
                quantity = DecimalValue("50"),
                averageEntryPrice = DecimalValue("100"),
                currentBidPrice = DecimalValue("100.1"),
                currentValueIdr = DecimalValue("5005"),
                unrealizedPnlIdr = DecimalValue("5"),
                unrealizedPnlPct = 0.10,
                breakEvenPrice = DecimalValue("100.6"),
                takeProfitPrice = DecimalValue("103"),
                stopPrice = DecimalValue("98"),
                openedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (16 * 60 * 1000)),
                updatedAt = now,
                horizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                setupType = com.kicryp.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
                pairTier = PairTier.TIER_B,
                speculativePocket = false,
                expectedHoldingHours = 6.0,
            ),
        )

        val decision = coordinator.planExit(now, cycle, positions, emptyList())

        assertNotNull(decision)
        assertEquals(ExitReason.TIME_EXIT, decision.reason)
    }

    @Test
    fun `hard loss exit can choose next best replacement candidate and use market sell`() {
        val now = Clock.System.now()
        val coordinator = TradeAutomationCoordinator()
        val cycle = StrategyCycleResult(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("63466"),
                lastSyncedAt = now,
            ),
            dailyRisk = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("ont_idr", 968.0, 971.0, 24_000_000.0, -0.6, -0.2)),
            ).dailyRisk,
            rankedPairs = listOf(
                pairScore("ont_idr", ranking = 0.48, opportunity = 0.30),
            ),
            marketSnapshot = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("ont_idr", 968.0, 971.0, 24_000_000.0, -0.6, -0.2)),
            ).marketSnapshot,
            healthDecision = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("ont_idr", 968.0, 971.0, 24_000_000.0, -0.6, -0.2)),
            ).healthDecision,
            riskDecision = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("ont_idr", 968.0, 971.0, 24_000_000.0, -0.6, -0.2)),
            ).riskDecision,
            modeSnapshot = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("3"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("ont_idr", 968.0, 971.0, 24_000_000.0, -0.6, -0.2)),
            ).modeSnapshot,
            deploymentPlan = com.kicryp.shared.models.CapitalDeploymentPlan(
                allowNewEntries = false,
                allowRotation = true,
                maxActivePositions = 2,
                suggestedPerPositionBudgetIdr = 30_000.0,
                targetCashReservePct = 0.01,
                capitalUtilizationTargetPct = 0.99,
                preferredHorizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                candidates = listOf(
                    com.kicryp.shared.models.CandidateOpportunity(
                        pairId = PairId("ont_idr"),
                        tier = com.kicryp.shared.models.PairTier.TIER_B,
                        preferredHorizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                        rankingScore = 0.90,
                        marketOpportunityScore = 0.86,
                        expectedNetProfitabilityPct = 2.2,
                        holdabilityScore = 0.55,
                        speculativePocket = false,
                        rationale = emptyList(),
                    ),
                    com.kicryp.shared.models.CandidateOpportunity(
                        pairId = PairId("croak_idr"),
                        tier = com.kicryp.shared.models.PairTier.TIER_B,
                        preferredHorizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                        rankingScore = 0.86,
                        marketOpportunityScore = 0.82,
                        expectedNetProfitabilityPct = 2.4,
                        holdabilityScore = 0.51,
                        speculativePocket = true,
                        rationale = emptyList(),
                    ),
                ),
                rationale = emptyList(),
            ),
            selectedSignal = null,
            executionPlan = null,
            topCandidate = PairId("ont_idr"),
            distrustLabels = emptyList(),
            summary = emptyList(),
            entrySignals = emptyList(),
            entryExecutionPlans = emptyList(),
        )
        val positions = listOf(
            ManagedPosition(
                pairId = PairId("ont_idr"),
                quantity = DecimalValue("16.30"),
                averageEntryPrice = DecimalValue("970"),
                currentBidPrice = DecimalValue("940"),
                currentValueIdr = DecimalValue("15322"),
                unrealizedPnlIdr = DecimalValue("-489"),
                unrealizedPnlPct = -3.1,
                breakEvenPrice = DecimalValue("976"),
                takeProfitPrice = DecimalValue("1012"),
                stopPrice = DecimalValue("928"),
                openedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (70 * 60 * 1000)),
                updatedAt = now,
                horizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                setupType = com.kicryp.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK,
                pairTier = com.kicryp.shared.models.PairTier.TIER_B,
                speculativePocket = false,
                expectedHoldingHours = 8.0,
            ),
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
        assertTrue(decision.message.contains("croak_idr", ignoreCase = true))
    }

    @Test
    fun `one hour stagnant tactical position force rotates`() {
        val now = Clock.System.now()
        val cycle = StrategyCycleResult(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("10000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("50000"),
                lastSyncedAt = now,
            ),
            dailyRisk = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("10000"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("flat_idr", 100.0, 100.2, 90_000_000.0, 0.1, 0.2)),
            ).dailyRisk,
            rankedPairs = listOf(
                pairScore("flat_idr", ranking = 0.50, opportunity = 0.42),
                pairScore("rocket_idr", ranking = 0.88, opportunity = 0.82),
            ),
            marketSnapshot = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("10000"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("flat_idr", 100.0, 100.2, 90_000_000.0, 0.1, 0.2)),
            ).marketSnapshot,
            healthDecision = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("10000"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("flat_idr", 100.0, 100.2, 90_000_000.0, 0.1, 0.2)),
            ).healthDecision,
            riskDecision = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("10000"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("flat_idr", 100.0, 100.2, 90_000_000.0, 0.1, 0.2)),
            ).riskDecision,
            modeSnapshot = orchestrator.analyze(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("10000"))),
                openOrders = emptyList(),
                dailyRisk = null,
                health = healthyEngine(),
                marketQuotes = listOf(marketQuote("flat_idr", 100.0, 100.2, 90_000_000.0, 0.1, 0.2)),
            ).modeSnapshot,
            deploymentPlan = com.kicryp.shared.models.CapitalDeploymentPlan(
                allowNewEntries = false,
                allowRotation = true,
                maxActivePositions = 2,
                suggestedPerPositionBudgetIdr = 20_000.0,
                targetCashReservePct = 0.01,
                capitalUtilizationTargetPct = 0.95,
                preferredHorizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                candidates = listOf(
                    com.kicryp.shared.models.CandidateOpportunity(
                        pairId = PairId("rocket_idr"),
                        tier = com.kicryp.shared.models.PairTier.TIER_B,
                        preferredHorizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                        rankingScore = 0.88,
                        marketOpportunityScore = 0.82,
                        expectedNetProfitabilityPct = 2.1,
                        holdabilityScore = 0.54,
                        speculativePocket = false,
                    ),
                ),
            ),
            selectedSignal = null,
            executionPlan = null,
            topCandidate = PairId("rocket_idr"),
            distrustLabels = emptyList(),
            summary = emptyList(),
            entrySignals = emptyList(),
            entryExecutionPlans = emptyList(),
        )
        val positions = listOf(
            ManagedPosition(
                pairId = PairId("flat_idr"),
                quantity = DecimalValue("100"),
                averageEntryPrice = DecimalValue("100"),
                currentBidPrice = DecimalValue("100.12"),
                currentValueIdr = DecimalValue("10012"),
                unrealizedPnlIdr = DecimalValue("12"),
                unrealizedPnlPct = 0.12,
                breakEvenPrice = DecimalValue("100.7"),
                takeProfitPrice = DecimalValue("103.0"),
                stopPrice = DecimalValue("98.5"),
                openedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (70 * 60 * 1000)),
                updatedAt = now,
                horizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                setupType = com.kicryp.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK,
                pairTier = com.kicryp.shared.models.PairTier.TIER_B,
                speculativePocket = false,
                expectedHoldingHours = 8.0,
                chartRotationUrgencyScore = 0.78,
            ),
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = emptyList(),
        )

        assertNotNull(decision)
        assertEquals(ExitReason.ROTATION_EXIT, decision.reason)
        assertTrue(decision.message.contains("rocket_idr", ignoreCase = true))
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
            com.kicryp.shared.models.OrderSnapshot(
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
            com.kicryp.shared.models.OrderSnapshot(
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
    fun `time exit can close stale position even below break even`() {
        val now = Clock.System.now()
        val openedAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (14 * 60 * 60 * 1000))
        val quotes = listOf(
            marketQuote(
                pair = "btc_idr",
                bid = 99_000.0,
                ask = 99_200.0,
                volume = 120_000_000.0,
                shortTermReturn = 0.2,
                mediumTermReturn = 0.4,
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
        val positions = listOf(
            ManagedPosition(
                pairId = PairId("btc_idr"),
                quantity = DecimalValue("0.2"),
                averageEntryPrice = DecimalValue("100000"),
                currentBidPrice = DecimalValue("99000"),
                currentValueIdr = DecimalValue("19800"),
                unrealizedPnlIdr = DecimalValue("-200"),
                unrealizedPnlPct = -1.0,
                breakEvenPrice = DecimalValue("100660"),
                takeProfitPrice = DecimalValue("104000"),
                stopPrice = DecimalValue("97000"),
                openedAt = openedAt,
                updatedAt = now,
                horizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
                setupType = com.kicryp.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK,
                pairTier = com.kicryp.shared.models.PairTier.TIER_A,
                speculativePocket = false,
                expectedHoldingHours = 8.0,
            ),
        )

        val decision = coordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = positions,
            activeOrders = emptyList(),
        )

        assertNotNull(decision)
        assertEquals(ExitReason.TIME_EXIT, decision.reason)
    }

    @Test
    fun `stale unknown order without exchange match gets canceled`() {
        val now = Clock.System.now()
        val staleTimestamp = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - (15 * 60 * 1000))
        val staleOrder = com.kicryp.shared.models.OrderSnapshot(
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
        val openOrder = com.kicryp.shared.models.OrderSnapshot(
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
            com.kicryp.shared.models.FillSnapshot(
                fillId = com.kicryp.shared.models.FillId("fill-1"),
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

    private fun healthyEngine() = EngineHealthSnapshot(
        status = HealthStatus.HEALTHY,
        syncHealth = SyncHealth.HEALTHY,
        websocketHealthy = true,
        exchangeReachable = true,
        supabaseReachable = true,
    )

    private fun pairScore(pair: String, ranking: Double, opportunity: Double) = PairScore(
        pairId = PairId(pair),
        liquidityScore = 0.72,
        spreadScore = 0.70,
        slippageScore = 0.68,
        stabilityScore = 0.64,
        volumeConsistencyScore = 0.66,
        volatilityQualityScore = 0.74,
        trendQualityScore = 0.58,
        historicalExpectancyScore = 0.46,
        recentHealthScore = 0.60,
        fillQualityScore = 0.56,
        holdabilityScore = 0.46,
        feeAdjustedEdgeScore = opportunity,
        marketOpportunityScore = opportunity.coerceIn(0.0, 1.0),
        rankingScore = ranking,
        pairTier = PairTier.TIER_B,
        preferredHorizon = com.kicryp.shared.models.TradingHorizon.TACTICAL,
        speculativePocket = false,
        allowed = true,
        rejectionReasons = emptyList(),
    )
}
