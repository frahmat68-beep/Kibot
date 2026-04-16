package com.kicryp.core

import com.kicryp.shared.models.BalanceSnapshot
import com.kicryp.shared.models.BotId
import com.kicryp.shared.models.DailyRiskSnapshot
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.EngineHealthSnapshot
import com.kicryp.shared.models.HealthStatus
import com.kicryp.shared.models.PortfolioSnapshot
import com.kicryp.shared.models.SyncHealth
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskEngineTest {
    private val health = EngineHealthSnapshot(
        status = HealthStatus.HEALTHY,
        syncHealth = SyncHealth.HEALTHY,
        websocketHealthy = true,
        exchangeReachable = true,
        supabaseReachable = true,
    )

    @Test
    fun `blocks entries when hard stop is active`() {
        val decision = RiskEngine().evaluate(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("100000"),
                lastSyncedAt = Instant.parse("2026-03-15T01:00:00Z"),
            ),
            dailyRisk = DailyRiskSnapshot(
                openingEquityIdr = DecimalValue("100000"),
                currentEquityIdr = DecimalValue("70000"),
                realizedPnlIdr = DecimalValue("-30000"),
                unrealizedPnlIdr = DecimalValue("0"),
                drawdownPct = 0.30,
                hardDailyLossLimitPct = 0.25,
                hardStopTriggered = true,
                rebasePending = false,
            ),
            health = health,
        )

        assertFalse(decision.allowNewEntries)
    }

    @Test
    fun `blocks entries when realized loss exceeds rupiah cap`() {
        val decision = RiskEngine(
            config = RiskConfig(hardRealizedLossLimitIdr = 10_000.0),
        ).evaluate(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("100000"),
                lastSyncedAt = Instant.parse("2026-03-15T01:00:00Z"),
            ),
            dailyRisk = DailyRiskSnapshot(
                openingEquityIdr = DecimalValue("100000"),
                currentEquityIdr = DecimalValue("101000"),
                realizedPnlIdr = DecimalValue("-30000"),
                unrealizedPnlIdr = DecimalValue("31000"),
                drawdownPct = 0.0,
                hardDailyLossLimitPct = 0.25,
                hardStopTriggered = false,
                rebasePending = false,
            ),
            health = health,
        )

        assertFalse(decision.allowNewEntries)
        assertTrue(decision.hardStopTriggered)
    }

    @Test
    fun `allows entries when risk is healthy`() {
        val decision = RiskEngine().evaluate(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("100000"),
                lastSyncedAt = Instant.parse("2026-03-15T01:00:00Z"),
            ),
            dailyRisk = DailyRiskSnapshot(
                openingEquityIdr = DecimalValue("100000"),
                currentEquityIdr = DecimalValue("101000"),
                realizedPnlIdr = DecimalValue("1000"),
                unrealizedPnlIdr = DecimalValue("0"),
                drawdownPct = 0.02,
                hardDailyLossLimitPct = 0.25,
                hardStopTriggered = false,
                rebasePending = false,
            ),
            health = health,
        )

        assertTrue(decision.allowNewEntries)
    }

    @Test
    fun `blocks entries when daily profit lock is active`() {
        val decision = RiskEngine().evaluate(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("101500"),
                lastSyncedAt = Instant.parse("2026-03-15T01:00:00Z"),
            ),
            dailyRisk = DailyRiskSnapshot(
                openingEquityIdr = DecimalValue("100000"),
                currentEquityIdr = DecimalValue("101500"),
                realizedPnlIdr = DecimalValue("1500"),
                unrealizedPnlIdr = DecimalValue("0"),
                drawdownPct = 0.0,
                hardDailyLossLimitPct = 0.25,
                hardStopTriggered = false,
                rebasePending = false,
            ),
            health = health,
        )

        assertTrue(decision.allowNewEntries)
        assertFalse(decision.dailyProfitLockActive)
    }

    @Test
    fun `default circuit breaker stops entries at five percent drawdown`() {
        val decision = RiskEngine().evaluate(
            portfolio = PortfolioSnapshot(
                botId = BotId("main"),
                balances = listOf(BalanceSnapshot("idr", DecimalValue("100000"))),
                openOrders = emptyList(),
                positions = emptyList(),
                totalEquityIdr = DecimalValue("95000"),
                lastSyncedAt = Instant.parse("2026-03-15T01:00:00Z"),
            ),
            dailyRisk = DailyRiskSnapshot(
                openingEquityIdr = DecimalValue("100000"),
                currentEquityIdr = DecimalValue("95000"),
                realizedPnlIdr = DecimalValue("-5000"),
                unrealizedPnlIdr = DecimalValue("0"),
                drawdownPct = 0.05,
                hardDailyLossLimitPct = 0.05,
                hardStopTriggered = false,
                rebasePending = false,
            ),
            health = health,
        )

        assertFalse(decision.allowNewEntries)
        assertTrue(decision.hardStopTriggered)
    }
}
