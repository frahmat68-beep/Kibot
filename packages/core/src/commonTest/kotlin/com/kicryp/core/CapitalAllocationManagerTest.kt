package com.kicryp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapitalAllocationManagerTest {
    @Test
    fun `updateFreeCapital clamps sleeve availability to actual free cash`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 100_000.0)

        manager.updateFreeCapital(
            freeIdr = 10_000.0,
            totalEquityIdr = 100_000.0,
            stableHoldingsIdr = 0.0,
            aggressiveHoldingsIdr = 0.0,
        )

        val status = manager.getStatus()
        val totalAvailable = status.stableCapitalIdr + status.aggressiveCapitalIdr

        assertEquals(10_000.0, totalAvailable, 0.0001)
        assertEquals(3_750.0, status.stableCapitalIdr, 0.0001)
        assertEquals(6_250.0, status.aggressiveCapitalIdr, 0.0001)
    }

    @Test
    fun `micro mode does not advertise slots when deployable cash is below venue minimum`() {
        val manager = CapitalAllocationManager(totalCapitalIdr = 47_500.0)

        val maxPositions = manager.calculateMaxPositions(totalFreeIdr = 9_000.0)
        val allocation = manager.allocateMicroMode(
            totalFreeIdr = 9_000.0,
            currentPositionCount = 0,
            isHighPumpSignal = false,
        )

        assertEquals(0, maxPositions)
        assertEquals("max_positions_reached", allocation.rebalanceMessage)
        assertTrue(allocation.allocatedIdr <= 0.0)
    }
}
