package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.SyncHealth
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
                currentEquityIdr = DecimalValue("105000"),
                realizedPnlIdr = DecimalValue("5000"),
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
}

