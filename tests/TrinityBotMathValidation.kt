package com.kibot.tests

import com.kibot.core.CapitalAllocationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * TRINITY BOT MATHEMATICAL VALIDATION
 * 
 * Tests capital split 70% STABLE / 30% AGGRESSIVE
 * Validates profit/loss calculations, rebalancing, fee impact
 * 
 * TEST SCENARIOS:
 * 1. Initial capital split validation
 * 2. Dual position handling (STABLE + AGGRESSIVE)
 * 3. Profit on STABLE rebalancing
 * 4. Loss on AGGRESSIVE handling
 * 5. Fee impact on position sizing
 * 6. Drift detection and auto-rebalance
 * 7. Edge cases (zero capital, all losses, extreme drift)
 */
class TrinityBotMathValidation {
    
    private val TAKER_FEE_PCT = 0.51 / 100.0  // 0.51% taker fee
    private val MAKER_FEE_PCT = 0.00 / 100.0  // 0.00% maker fee (free)
    
    @Test
    fun `Test 1 - Initial 70-30 split validation`() {
        val manager = CapitalAllocationManager(
            totalCapitalIdr = 100_000.0,
            stableRotationPercent = 0.70,
            aggressivePercent = 0.30
        )
        
        val status = manager.getStatus()
        
        // Verify exact split
        assertEquals(70_000.0, status.stableCapitalIdr, 0.01, "STABLE bucket should be 70k")
        assertEquals(30_000.0, status.aggressiveCapitalIdr, 0.01, "AGGRESSIVE bucket should be 30k")
        assertEquals(70.0, status.stablePercent, 0.01, "STABLE should be 70%")
        assertEquals(30.0, status.aggressivePercent, 0.01, "AGGRESSIVE should be 30%")
        assertFalse(status.requiresRebalance, "No rebalance needed at start")
        assertEquals(0.0, status.driftPercent, 0.01, "Zero drift at start")
    }
    
    @Test
    fun `Test 2 - Open 2 STABLE positions (25k each)`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // Open first STABLE position: 25k
        val alloc1 = manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 25_000.0)
        assertEquals(25_000.0, alloc1.allocatedIdr, 0.01)
        assertEquals("STABLE", alloc1.bucketType)
        assertEquals(45_000.0, alloc1.currentAvailable, 0.01, "45k remaining in STABLE")
        
        // Open second STABLE position: 25k
        val alloc2 = manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 25_000.0)
        assertEquals(25_000.0, alloc2.allocatedIdr, 0.01)
        assertEquals(20_000.0, alloc2.currentAvailable, 0.01, "20k remaining in STABLE")
        
        val status = manager.getStatus()
        assertEquals(20_000.0, status.stableCapitalIdr, 0.01, "20k left in STABLE")
        assertEquals(30_000.0, status.aggressiveCapitalIdr, 0.01, "30k AGGRESSIVE untouched")
        assertEquals(50_000.0, status.totalDeployedStable, 0.01, "50k total deployed STABLE")
        assertEquals(0.0, status.totalDeployedAggressive, 0.01, "0 deployed AGGRESSIVE")
    }
    
    @Test
    fun `Test 3 - Profit on STABLE (+5k), check rebalance`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // Deploy 50k STABLE (2 positions x 25k)
        manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 25_000.0)
        manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 25_000.0)
        
        // Realize profit: +5k on STABLE trade
        manager.depositProfit(profitIdr = 5_000.0, wasAggressiveTrade = false)
        
        val status = manager.getStatus()
        
        // Total capital now: 50k (remaining) + 5k (profit) = 55k available
        // After auto-rebalance: 55k * 70% = 38.5k STABLE, 55k * 30% = 16.5k AGGRESSIVE
        assertEquals(38_500.0, status.stableCapitalIdr, 0.01, "STABLE rebalanced to 38.5k")
        assertEquals(16_500.0, status.aggressiveCapitalIdr, 0.01, "AGGRESSIVE rebalanced to 16.5k")
        assertEquals(1, status.rebalanceCount, "Should have rebalanced once")
    }
    
    @Test
    fun `Test 4 - Loss on AGGRESSIVE (-3k), check tolerance`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // Deploy 10k AGGRESSIVE
        manager.allocate(isAnomalyCoin = true, requestedAmountIdr = 10_000.0)
        
        // Realize loss: -3k on AGGRESSIVE trade
        manager.depositProfit(profitIdr = -3_000.0, wasAggressiveTrade = true)
        
        val status = manager.getStatus()
        
        // AGGRESSIVE: 30k - 10k + (-3k) = 17k
        // STABLE: 70k (untouched)
        // Total: 87k
        // After auto-rebalance: 87k * 70% = 60.9k STABLE, 87k * 30% = 26.1k AGGRESSIVE
        assertEquals(60_900.0, status.stableCapitalIdr, 0.01, "STABLE rebalanced to 60.9k")
        assertEquals(26_100.0, status.aggressiveCapitalIdr, 0.01, "AGGRESSIVE rebalanced to 26.1k after loss")
        assertTrue(status.rebalanceCount > 0, "Should have rebalanced after loss")
    }
    
    @Test
    fun `Test 5 - Fee impact on position sizing (Taker 0_51%)`() {
        val positionBudgetIdr = 25_000.0
        val takerFee = positionBudgetIdr * TAKER_FEE_PCT
        val netBuyAmount = positionBudgetIdr - takerFee
        
        assertEquals(127.5, takerFee, 0.01, "Taker fee should be Rp127.5")
        assertEquals(24_872.5, netBuyAmount, 0.01, "Net buy amount after fee")
        
        // Simulate sell with 5% profit
        val sellPrice = netBuyAmount * 1.05
        val sellFee = sellPrice * TAKER_FEE_PCT
        val netSellAmount = sellPrice - sellFee
        
        val grossProfit = netSellAmount - positionBudgetIdr
        
        assertTrue(grossProfit > 0, "Should still profit after fees")
        assertTrue(grossProfit < 1_250.0, "Profit reduced by fees (5% of 25k = 1250, fees eat some)")
    }
    
    @Test
    fun `Test 6 - Drift detection - AGGRESSIVE gains 10k should trigger rebalance`() {
        val manager = CapitalAllocationManager(
            totalCapitalIdr = 100_000.0,
            rebalanceDriftThreshold = 0.05  // 5% drift threshold
        )
        
        // Simulate AGGRESSIVE winning big: +10k profit
        manager.depositProfit(profitIdr = 10_000.0, wasAggressiveTrade = true)
        
        val status = manager.getStatus()
        
        // Before rebalance (if no auto-rebalance):
        // STABLE: 70k, AGGRESSIVE: 30k + 10k = 40k
        // Total: 110k
        // AGGRESSIVE% = 40k / 110k = 36.36% (drift = 6.36% from 30%)
        // After auto-rebalance:
        // STABLE: 110k * 70% = 77k
        // AGGRESSIVE: 110k * 30% = 33k
        
        assertEquals(77_000.0, status.stableCapitalIdr, 0.01, "STABLE rebalanced to 77k")
        assertEquals(33_000.0, status.aggressiveCapitalIdr, 0.01, "AGGRESSIVE rebalanced to 33k")
        assertEquals(1, status.rebalanceCount, "Should have auto-rebalanced once")
    }
    
    @Test
    fun `Test 7 - Scenario - Multiple positions across buckets`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // Day 1: Open 2 STABLE (20k each) + 1 AGGRESSIVE (15k)
        manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 20_000.0)
        manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 20_000.0)
        manager.allocate(isAnomalyCoin = true, requestedAmountIdr = 15_000.0)
        
        val status1 = manager.getStatus()
        assertEquals(30_000.0, status1.stableCapitalIdr, 0.01, "30k left in STABLE")
        assertEquals(15_000.0, status1.aggressiveCapitalIdr, 0.01, "15k left in AGGRESSIVE")
        
        // Day 1 Results: STABLE +2k, AGGRESSIVE +3k
        manager.depositProfit(profitIdr = 2_000.0, wasAggressiveTrade = false)
        manager.depositProfit(profitIdr = 3_000.0, wasAggressiveTrade = true)
        
        val status2 = manager.getStatus()
        // Total available: 30k + 15k + 2k + 3k = 50k
        // After auto-rebalance: 50k * 70% = 35k STABLE, 50k * 30% = 15k AGGRESSIVE
        assertEquals(35_000.0, status2.stableCapitalIdr, 0.01, "STABLE rebalanced to 35k")
        assertEquals(15_000.0, status2.aggressiveCapitalIdr, 0.01, "AGGRESSIVE rebalanced to 15k")
    }
    
    @Test
    fun `Test 8 - Edge case - Zero capital left in AGGRESSIVE`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // Deploy all AGGRESSIVE capital
        manager.allocate(isAnomalyCoin = true, requestedAmountIdr = 30_000.0)
        
        // Try to allocate more AGGRESSIVE (should get 0)
        val alloc = manager.allocate(isAnomalyCoin = true, requestedAmountIdr = 10_000.0)
        
        assertEquals(0.0, alloc.allocatedIdr, 0.01, "Should allocate 0 when bucket empty")
        assertEquals("AGGRESSIVE", alloc.bucketType)
        assertEquals(0.0, alloc.currentAvailable, 0.01, "No AGGRESSIVE capital left")
    }
    
    @Test
    fun `Test 9 - Edge case - All capital lost`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // Deploy all capital
        manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 70_000.0)
        manager.allocate(isAnomalyCoin = true, requestedAmountIdr = 30_000.0)
        
        // Massive loss: -90k (wipe out most capital)
        manager.depositProfit(profitIdr = -50_000.0, wasAggressiveTrade = false)
        manager.depositProfit(profitIdr = -40_000.0, wasAggressiveTrade = true)
        
        val status = manager.getStatus()
        
        // Total remaining: 100k - 90k = 10k
        // After rebalance: 10k * 70% = 7k STABLE, 10k * 30% = 3k AGGRESSIVE
        assertEquals(7_000.0, status.stableCapitalIdr, 0.01, "STABLE rebalanced to 7k")
        assertEquals(3_000.0, status.aggressiveCapitalIdr, 0.01, "AGGRESSIVE rebalanced to 3k")
    }
    
    @Test
    fun `Test 10 - No rebalance if drift under 5%`() {
        val manager = CapitalAllocationManager(
            totalCapitalIdr = 100_000.0,
            rebalanceDriftThreshold = 0.05  // 5% threshold
        )
        
        // Small profit on STABLE: +1k (should not trigger rebalance)
        manager.depositProfit(profitIdr = 1_000.0, wasAggressiveTrade = false)
        
        val status = manager.getStatus()
        
        // STABLE: 70k + 1k = 71k, AGGRESSIVE: 30k
        // Total: 101k
        // STABLE% = 71k / 101k = 70.3% (drift = 0.3%, under 5%)
        assertEquals(71_000.0, status.stableCapitalIdr, 0.01, "No rebalance, STABLE at 71k")
        assertEquals(30_000.0, status.aggressiveCapitalIdr, 0.01, "No rebalance, AGGRESSIVE at 30k")
        assertEquals(0, status.rebalanceCount, "No rebalance triggered (drift < 5%)")
    }
    
    @Test
    fun `Test 11 - Extreme drift forces rebalance`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)
        
        // AGGRESSIVE gains massive: +50k (unlikely but test edge case)
        manager.depositProfit(profitIdr = 50_000.0, wasAggressiveTrade = true)
        
        val status = manager.getStatus()
        
        // Total: 150k
        // After rebalance: 150k * 70% = 105k STABLE, 150k * 30% = 45k AGGRESSIVE
        assertEquals(105_000.0, status.stableCapitalIdr, 0.01, "Extreme rebalance STABLE to 105k")
        assertEquals(45_000.0, status.aggressiveCapitalIdr, 0.01, "Extreme rebalance AGGRESSIVE to 45k")
        assertTrue(status.rebalanceCount > 0, "Should have rebalanced on extreme drift")
    }
    
    @Test
    fun `Test 12 - Position sizing respects max 25% per coin rule`() {
        val totalCapital = 100_000.0
        val maxPerCoin = totalCapital * 0.25  // 25% max per coin
        
        assertEquals(25_000.0, maxPerCoin, 0.01, "Max 25k per coin bound check")
        
        // STABLE bucket: 70k available
        // If trying to allocate 30k (>25% of total), should be capped at 25k
        val manager = CapitalAllocationManager(totalCapitalIdr = totalCapital)
        
        // Request 30k from STABLE (but should respect 25% rule)
        val alloc = manager.allocate(isAnomalyCoin = false, requestedAmountIdr = 30_000.0)
        
        // TRINITY v6.2: Should now be capped at 25k
        assertEquals(25_000.0, alloc.allocatedIdr, 0.01, "Allocated 25k (capped by single-coin rule)")
        assertEquals(45_000.0, alloc.currentAvailable, 0.01, "45k left in STABLE (70k - 25k used)")
    }
}
