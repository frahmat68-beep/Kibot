package com.kibot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HybridStrategyTests - Unit tests for Trinity Bot Phase 1 (70/30 strategy)
 *
 * Test scenarios:
 * 1. 90% winrate, +9% daily
 * 2. +6.5% daily
 * 3. +17% daily
 */
class HybridStrategyTests {

    private val capitalManager = CapitalAllocationManager(
        totalCapitalIdr = 47_500.0,
        stableRotationPercent = 0.70,
        aggressivePercent = 0.30,
        rebalanceDriftThreshold = 0.05,
    )

    private val whitelistManager = PairWhitelistManager()
    private val orderStrategy = OrderExecutionStrategy()

    @Test
    fun `Scenario 1 - Normal Day - 70 percent stable hit 1_8 percent target`() {
        capitalManager.reset()
        whitelistManager.resetAllStats()

        val stableAllocs = mutableListOf<Double>()
        repeat(10) {
            val alloc = capitalManager.allocate(isAnomalyCoin = false, requestedAmountIdr = 3_320.0)
            stableAllocs.add(alloc.allocatedIdr)
        }

        assertEquals(33_200.0, stableAllocs.sum(), 0.01, "Should allocate 70% of capital")

        repeat(7) { whitelistManager.recordTrade("STO", won = true) }
        repeat(3) { whitelistManager.recordTrade("STO", won = false) }

        val stats = requireNotNull(whitelistManager.getPairStats("STO")) { "STO should be tracked" }
        assertEquals(10, stats.totalTrades)
        assertEquals(7, stats.wins)
        assertEquals(70.0, stats.winRatePercent, 0.1)

        val exitPrice = FeeCalculator.calculateRequiredExitPrice(
            entryPrice = 100.0,
            targetProfitPercent = 0.018,
            entryIsMaker = true,
            exitIsMaker = true,
        )
        val netProfitPercent = FeeCalculator.calculateNetProfitPercent(
            entryPrice = 100.0,
            exitPrice = exitPrice,
            entryIsMaker = true,
            exitIsMaker = true,
        )

        val totalProfit = 3_320.0 * netProfitPercent * 7
        val dailyReturnPercent = (totalProfit / 33_200.0) * 100.0

        assertTrue(dailyReturnPercent >= 8.0, "Daily return should be >= 8% (got $dailyReturnPercent%)")
        assertTrue(dailyReturnPercent <= 10.0, "Daily return should be <= 10% (got $dailyReturnPercent%)")
    }

    @Test
    fun `Scenario 2 - Market Dip - stable 1_2 percent aggressive minus 0_5 percent`() {
        capitalManager.reset()
        whitelistManager.resetAllStats()

        val stableAllocPerTrade = 3_320.0
        val aggressiveAllocPerTrade = 2_750.0

        repeat(10) {
            capitalManager.allocate(isAnomalyCoin = false, requestedAmountIdr = stableAllocPerTrade)
        }
        repeat(3) {
            capitalManager.allocate(isAnomalyCoin = true, requestedAmountIdr = aggressiveAllocPerTrade)
        }

        val stableExitPrice = FeeCalculator.calculateRequiredExitPrice(
            entryPrice = 100.0,
            targetProfitPercent = 0.012,
            entryIsMaker = true,
            exitIsMaker = true,
        )
        val stableNetProfitPercent = FeeCalculator.calculateNetProfitPercent(
            entryPrice = 100.0,
            exitPrice = stableExitPrice,
            entryIsMaker = true,
            exitIsMaker = true,
        )

        val stableTotalProfit = stableAllocPerTrade * stableNetProfitPercent * 8
        val aggressiveProfitPerWin = aggressiveAllocPerTrade * (0.05 - 0.0066)
        val aggressiveLossPerLoser = aggressiveAllocPerTrade * (-0.005 - 0.0066)
        val aggressiveTotalProfit = aggressiveProfitPerWin + (aggressiveLossPerLoser * 2)

        val totalProfit = stableTotalProfit + aggressiveTotalProfit
        val dailyReturnPercent = (totalProfit / 47_500.0) * 100.0

        assertTrue(dailyReturnPercent >= 5.5, "Daily return should be >= 5.5% (got $dailyReturnPercent%)")
        assertTrue(dailyReturnPercent <= 7.5, "Daily return should be <= 7.5% (got $dailyReturnPercent%)")
    }

    @Test
    fun `Scenario 3 - All Winners - both buckets hit targets`() {
        capitalManager.reset()
        whitelistManager.resetAllStats()

        val stableExitPrice = FeeCalculator.calculateRequiredExitPrice(
            entryPrice = 100.0,
            targetProfitPercent = 0.018,
            entryIsMaker = true,
            exitIsMaker = true,
        )
        val stableNetPercent = FeeCalculator.calculateNetProfitPercent(
            entryPrice = 100.0,
            exitPrice = stableExitPrice,
            entryIsMaker = true,
            exitIsMaker = true,
        )
        val stableTotalProfit = 3_320.0 * stableNetPercent * 10

        val aggressiveExitPrice = FeeCalculator.calculateRequiredExitPrice(
            entryPrice = 100.0,
            targetProfitPercent = 0.05,
            entryIsMaker = false,
            exitIsMaker = false,
        )
        val aggressiveNetPercent = FeeCalculator.calculateNetProfitPercent(
            entryPrice = 100.0,
            exitPrice = aggressiveExitPrice,
            entryIsMaker = false,
            exitIsMaker = false,
        )
        val aggressiveTotalProfit = 2_750.0 * aggressiveNetPercent * 3

        val totalProfit = stableTotalProfit + aggressiveTotalProfit
        val dailyReturnPercent = (totalProfit / 47_500.0) * 100.0

        assertTrue(dailyReturnPercent >= 16.0, "Daily return should be >= 16% (got $dailyReturnPercent%)")
        assertTrue(dailyReturnPercent <= 18.0, "Daily return should be <= 18% (got $dailyReturnPercent%)")
    }

    @Test
    fun `Hard whitelist - STO DRX D always approved`() {
        assertTrue(whitelistManager.isPairWhitelisted("STO"))
        assertTrue(whitelistManager.isPairWhitelisted("DRX"))
        assertTrue(whitelistManager.isPairWhitelisted("D"))
    }

    @Test
    fun `New pair - probationary period allows trading`() {
        assertTrue(whitelistManager.isPairWhitelisted("NEWPAIR"))
        repeat(10) {
            whitelistManager.recordTrade("NEWPAIR", won = (it % 2 == 0))
        }
        assertTrue(whitelistManager.isPairWhitelisted("NEWPAIR"), "Should still be whitelisted in probation")
    }

    @Test
    fun `Proven winner - 20+ trades with 65%+ winrate becomes whitelisted`() {
        assertFalse(whitelistManager.isPairWhitelisted("WINNER"))
        repeat(14) { whitelistManager.recordTrade("WINNER", won = true) }
        repeat(6) { whitelistManager.recordTrade("WINNER", won = false) }
        assertTrue(whitelistManager.isPairWhitelisted("WINNER"), "Should be dynamically whitelisted")
        assertEquals(1, whitelistManager.getSummary().dynamicWhitelistCount)
    }

    @Test
    fun `Proven loser - 20+ trades with less than 65% winrate blacklisted`() {
        assertTrue(whitelistManager.isPairWhitelisted("LOSER"), "Initially probationary")
        repeat(12) { whitelistManager.recordTrade("LOSER", won = true) }
        repeat(8) { whitelistManager.recordTrade("LOSER", won = false) }
        assertFalse(whitelistManager.isPairWhitelisted("LOSER"), "Should be blacklisted")
        assertEquals(1, whitelistManager.getSummary().blacklistedCount)
    }

    @Test
    fun `Capital allocation - 70 stable 30 aggressive split`() {
        val status = capitalManager.getStatus()
        assertEquals(33_200.0, status.stableCapitalIdr, 0.1)
        assertEquals(8_300.0, status.aggressiveCapitalIdr, 0.1)
        assertEquals(70.0, status.stablePercent, 0.1)
        assertEquals(30.0, status.aggressivePercent, 0.1)
    }

    @Test
    fun `Capital allocation - allocate stable trade`() {
        val alloc = capitalManager.allocate(isAnomalyCoin = false, requestedAmountIdr = 5_000.0)
        assertEquals(5_000.0, alloc.allocatedIdr)
        assertEquals("STABLE", alloc.bucketType)
        assertEquals(28_200.0, alloc.currentAvailable, 0.1)
    }

    @Test
    fun `Capital allocation - allocate aggressive trade`() {
        val alloc = capitalManager.allocate(isAnomalyCoin = true, requestedAmountIdr = 3_000.0)
        assertEquals(3_000.0, alloc.allocatedIdr)
        assertEquals("AGGRESSIVE", alloc.bucketType)
        assertEquals(5_300.0, alloc.currentAvailable, 0.1)
    }

    @Test
    fun `Capital allocation - deposit profit rebalances`() {
        val alloc = capitalManager.allocate(isAnomalyCoin = true, requestedAmountIdr = 6_000.0)
        assertTrue(alloc.requiresRebalance, "Should need rebalance after 72% depletion of aggressive bucket")
        capitalManager.depositProfit(1_000.0, wasAggressiveTrade = true)
        val status = capitalManager.getStatus()
        assertTrue(status.stablePercent in 69.0..71.0, "Stable should be rebalanced to ~70%")
    }

    @Test
    fun `Order execution strategy - stable uses limit orders`() {
        val rec = orderStrategy.recommendEntryOrderType(
            isAnomalyCoin = false,
            pumpConfidence = 0.3,
            spreadPercent = 0.01,
            volumeScore = 0.6,
        )
        assertEquals(OrderExecutionStrategy.OrderType.LIMIT, rec.orderType)
    }

    @Test
    fun `Order execution strategy - anomaly uses market orders`() {
        val rec = orderStrategy.recommendEntryOrderType(
            isAnomalyCoin = true,
            pumpConfidence = 0.85,
            spreadPercent = 0.01,
            volumeScore = 0.6,
        )
        assertEquals(OrderExecutionStrategy.OrderType.MARKET, rec.orderType)
    }
}
