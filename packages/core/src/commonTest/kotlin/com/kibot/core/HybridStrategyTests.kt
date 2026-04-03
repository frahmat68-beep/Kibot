package com.kibot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * HybridStrategyTests - Unit tests for Trinity Bot Phase 1 (70/30 strategy)
 * 
 * Test Scenarios:
 90% winrate, +9% daily
 +6.5% daily
 +17% daily
 */
class HybridStrategyTests {
    
    private val capitalManager = CapitalAllocationManager(
        totalCapitalIdr = 47_500.0,
        stableRotationPercent = 0.70,
        aggressivePercent = 0.30,
        rebalanceDriftThreshold = 0.05
    )
    
    private val whitelistManager = PairWhitelistManager()
    private val orderStrategy = OrderExecutionStrategy()
    
    // ==================== SCENARIO 1: Normal Day ====================
 expect 90% winrate, +9% daily
    // 10 stable trades at Rp33,200 = 3,320 IDR per trade
    // 70% win rate = 7 winners
    // Expected: 7 * 3,320 * 1.8% = ~417 IDR profit
    
    @Test
    fun `Scenario 1 - Normal Day - 70 percent stable hit 1_8 percent target`() {
        // Reset state
        capitalManager.reset()
        whitelistManager.resetAllStats()
        
        // Allocate capital for 10 stable trades
        val stableAllocs = mutableListOf<Double>()
        repeat(10) {
            val alloc = capitalManager.allocate(isAnomalyCoin = false, requestedAmountIdr = 3_320.0)
            stableAllocs.add(alloc.allocatedIdr)
        }
        
        // Verify 70% bucket capacity
        assertEquals(33_200.0, stableAllocs.sum(), 0.01, "Should allocate 70% of capital")
        
        // Simulate 7 winners + 3 losses
        val stableWins = 7
        val stableLosses = 3
        
        // Record trade results
        repeat(stableWins) { whitelistManager.recordTrade("STO", won = true) }
        repeat(stableLosses) { whitelistManager.recordTrade("STO", won = false) }
        
        // Verify win rate
        val stats = whitelistManager.getPairStats("STO")
        assertNotNull(stats, "STO should be tracked")
        assertEquals(10, stats.totalTrades)
        assertEquals(7, stats.wins)
        assertEquals(70.0, stats.winRatePercent, 0.1)
        
        // Calculate profit with 1.8% target
        val entryPrice = 100.0
        val targetProfitPercent = 0.018
        val exitPrice = FeeCalculator.calculateRequiredExitPrice(
            entryPrice = entryPrice,
            targetProfitPercent = targetProfitPercent,
            entryIsMaker = true,  // 70% stable uses Limit orders
            exitIsMaker = true
        )
        
        // Calculate net profit for 1 trade
        val netProfitPercent = FeeCalculator.calculateNetProfitPercent(
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            entryIsMaker = true,
            exitIsMaker = true
        )
        
        // 1 trade worth: 3,320 * net profit %
        val profitPerTrade = 3_320.0 * netProfitPercent
        val totalProfit = profitPerTrade * stableWins
        
        // Expected: ~9% daily return on capital
        val dailyReturnPercent = (totalProfit / 33_200.0) * 100.0
        assertTrue(dailyReturnPercent >= 8.0, "Daily return should be >= 8% (got $dailyReturnPercent%)")
        assertTrue(dailyReturnPercent <= 10.0, "Daily return should be <= 10% (got $dailyReturnPercent%)")
        
        println Scenario 1 PASSED: Normal Day")(
        println("   Win rate: ${stats.winRatePercent}%")
        println("   Profit per trade: ${profitPerTrade.toInt()} IDR")
        println("   Total profit: ${totalProfit.toInt()} IDR")
        println("   Daily return: ${dailyReturnPercent.toInt()}%")
    }
    
    // ==================== SCENARIO 2: Market Dip ====================
 +6.5% daily
    // 10 stable trades: 8 winners at 1.2% = +9.6% on stable bucket
    // 3 aggressive trades: 1 winner at 5%, 2 losers at -0.5% = -2% on aggressive bucket
    // Net: +9.6% * 70% + (-2%) * 30% = 6.7% + (-0.6%) = ~6.1%
    
    @Test
    fun `Scenario 2 - Market Dip - stable 1_2 percent aggressive minus 0_5 percent`() {
        capitalManager.reset()
        whitelistManager.resetAllStats()
        
        // Stable bucket: 70% of capital
        val stableCapital = 33_200.0
        val stableAllocPerTrade = 3_320.0
        
        // Aggressive bucket: 30% of capital
        val aggressiveCapital = 8_300.0
        val aggressiveAllocPerTrade = 2_750.0  // ~3 trades from 8,300
        
        // Allocate stable trades: 10 trades, 8 winners
        repeat(10) {
            capitalManager.allocate(isAnomalyCoin = false, requestedAmountIdr = stableAllocPerTrade)
        }
        
        // Allocate aggressive trades: 3 trades
        repeat(3) {
            capitalManager.allocate(isAnomalyCoin = true, requestedAmountIdr = aggressiveAllocPerTrade)
        }
        
        // Stable: 8 wins at 1.2%, 2 losses
        // Calculate 1.2% profit target
        val stableEntryPrice = 100.0
        val stableTargetPercent = 0.012
        val stableExitPrice = FeeCalculator.calculateRequiredExitPrice(
            stableEntryPrice, stableTargetPercent, true, true
        )
        val stableNetProfitPercent = FeeCalculator.calculateNetProfitPercent(
            stableEntryPrice, stableExitPrice, true, true
        )
        
        val stableProfitPerWin = stableAllocPerTrade * stableNetProfitPercent
        val stableTotalProfit = stableProfitPerWin * 8  // 8 winners
        
        // Aggressive: 1 winner at 5%, 2 losers at -0.5%
        val aggressiveWinPercent = 0.05
        val aggressiveLossPercent = -0.005
        val aggressiveProfitPerWin = aggressiveAllocPerTrade * (aggressiveWinPercent - 0.0066)  // Net after fees
        val aggressiveLossPerLoser = aggressiveAllocPerTrade * (aggressiveLossPercent - 0.0066)  // Net after fees
        val aggressiveTotalProfit = aggressiveProfitPerWin * 1 + aggressiveLossPerLoser * 2
        
        // Total portfolio profit
        val totalProfit = stableTotalProfit + aggressiveTotalProfit
        val totalCapital = 47_500.0
        val dailyReturnPercent = (totalProfit / totalCapital) * 100.0
        
        // Expected: +6.5% %%%%%%%%%%%)daily (
        assertTrue(dailyReturnPercent >= 5.5, "Daily return should be >= 5.5% (got $dailyReturnPercent%)")
        assertTrue(dailyReturnPercent <= 7.5, "Daily return should be <= 7.5% (got $dailyReturnPercent%)")
        
        println Scenario 2 PASSED: Market Dip")(
        println("   Stable profit: ${stableTotalProfit.toInt()} IDR")
        println("   Aggressive profit: ${aggressiveTotalProfit.toInt()} IDR")
        println("   Total profit: ${totalProfit.toInt()} IDR")
        println("   Daily return: ${dailyReturnPercent.toInt()}%")
    }
    
    // ==================== SCENARIO 3: All Winners ====================
 +17% daily
    // 10 stable trades at 1.8% = +18% on stable bucket
    // 3 aggressive trades at 5% = +15% on aggressive bucket
    // Net: +18% * 70% + 15% * 30% = 12.6% + 4.5% = 17.1%
    
    @Test
    fun `Scenario 3 - All Winners - both buckets hit targets`() {
        capitalManager.reset()
        whitelistManager.resetAllStats()
        
        // Stable: 10 wins at 1.8%
        val stableAllocPerTrade = 3_320.0
        val stableTargetPercent = 0.018
        val stableEntryPrice = 100.0
        val stableExitPrice = FeeCalculator.calculateRequiredExitPrice(
            stableEntryPrice, stableTargetPercent, true, true
        )
        val stableNetPercent = FeeCalculator.calculateNetProfitPercent(
            stableEntryPrice, stableExitPrice, true, true
        )
        
        val stableProfitPerTrade = stableAllocPerTrade * stableNetPercent
        val stableTotalProfit = stableProfitPerTrade * 10
        val stableReturn = (stableTotalProfit / 33_200.0) * 100.0
        
        // Aggressive: 3 wins at 5%
        val aggressiveAllocPerTrade = 2_750.0
        val aggressiveTargetPercent = 0.05
        val aggressiveEntryPrice = 100.0
        val aggressiveExitPrice = FeeCalculator.calculateRequiredExitPrice(
            aggressiveEntryPrice, aggressiveTargetPercent, false, false  // Market orders for aggressive
        )
        val aggressiveNetPercent = FeeCalculator.calculateNetProfitPercent(
            aggressiveEntryPrice, aggressiveExitPrice, false, false
        )
        
        val aggressiveProfitPerTrade = aggressiveAllocPerTrade * aggressiveNetPercent
        val aggressiveTotalProfit = aggressiveProfitPerTrade * 3
        val aggressiveReturn = (aggressiveTotalProfit / 8_300.0) * 100.0
        
        // Portfolio level
        val totalProfit = stableTotalProfit + aggressiveTotalProfit
        val dailyReturnPercent = (totalProfit / 47_500.0) * 100.0
        
        // Expected: ~17% daily
        assertTrue(dailyReturnPercent >= 16.0, "Daily return should be >= 16% (got $dailyReturnPercent%)")
        assertTrue(dailyReturnPercent <= 18.0, "Daily return should be <= 18% (got $dailyReturnPercent%)")
        
        println Scenario 3 PASSED: All Winners")(
        println("   Stable return: ${stableReturn.toInt()}% (${stableTotalProfit.toInt()} IDR)")
        println("   Aggressive return: ${aggressiveReturn.toInt()}% (${aggressiveTotalProfit.toInt()} IDR)")
        println("   Portfolio return: ${dailyReturnPercent.toInt()}%")
        println("   Total profit: ${totalProfit.toInt()} IDR")
    }
    
    // ==================== WHITELIST TESTS ====================
    
    @Test
    fun `Hard whitelist - STO DRX D always approved`() {
        assertTrue(whitelistManager.isPairWhitelisted("STO"))
        assertTrue(whitelistManager.isPairWhitelisted("DRX"))
        assertTrue(whitelistManager.isPairWhitelisted("D"))
    }
    
    @Test
    fun `New pair - probationary period allows trading`() {
        assertTrue(whitelistManager.isPairWhitelisted("NEWPAIR"))
        
        // Add 10 trades, should still be probationary
        repeat(10) {
            whitelistManager.recordTrade("NEWPAIR", won = (it % 2 == 0))
        }
        assertTrue(whitelistManager.isPairWhitelisted("NEWPAIR"), "Should still be whitelisted in probation")
    }
    
    @Test
    fun `Proven winner - 20+ trades with 65%+ winrate becomes whitelisted`() {
        assertFalse(whitelistManager.isPairWhitelisted("WINNER"))
        
        // Add 20 trades: 14 wins (70% rate)
        repeat(14) { whitelistManager.recordTrade("WINNER", won = true) }
        repeat(6) { whitelistManager.recordTrade("WINNER", won = false) }
        
        assertTrue(whitelistManager.isPairWhitelisted("WINNER"), "Should be dynamically whitelisted")
        
        val summary = whitelistManager.getSummary()
        assertEquals(1, summary.dynamicWhitelistCount)
    }
    
    @Test
    fun `Proven loser - 20+ trades with <65% winrate blacklisted`() {
        assertTrue(whitelistManager.isPairWhitelisted("LOSER"), "Initially probationary")
        
        // Add 20 trades: 12 wins (60% rate)
        repeat(12) { whitelistManager.recordTrade("LOSER", won = true) }
        repeat(8) { whitelistManager.recordTrade("LOSER", won = false) }
        
        assertFalse(whitelistManager.isPairWhitelisted("LOSER"), "Should be blacklisted")
        
        val summary = whitelistManager.getSummary()
        assertEquals(1, summary.blacklistedCount)
    }
    
    // ==================== CAPITAL ALLOCATION TESTS ====================
    
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
        val alloc1 = capitalManager.allocate(isAnomalyCoin = true, requestedAmountIdr = 6_000.0)
        assertEquals(true, alloc1.requiresRebalance, "Should need rebalance after 72% depletion of aggressive bucket")
        
        // Deposit profit
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
            volumeScore = 0.6
        )
        
        assertEquals(OrderExecutionStrategy.OrderType.LIMIT, rec.orderType)
    }
    
    @Test
    fun `Order execution strategy - anomaly uses market orders`() {
        val rec = orderStrategy.recommendEntryOrderType(
            isAnomalyCoin = true,
            pumpConfidence = 0.85,
            spreadPercent = 0.01,
            volumeScore = 0.6
        )
        
        assertEquals(OrderExecutionStrategy.OrderType.MARKET, rec.orderType)
    }
    
    private fun assertNotNull(value: Any?, message: String? = null) {
        assertTrue(value != null, message)
    }
}
