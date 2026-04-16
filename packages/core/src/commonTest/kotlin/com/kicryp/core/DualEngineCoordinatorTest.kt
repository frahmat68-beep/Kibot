package com.kicryp.core

import com.kicryp.shared.models.BalanceSnapshot
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.PairId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DualEngineCoordinatorTest {
    @Test
    fun `anomaly signal prioritizes barbarian engine`() {
        val coordinator = DualEngineCoordinator()
        val decision = coordinator.pickPriorityDecision(
            udpSignal = KinanceSignal(
                pairId = PairId("pepe_idr"),
                msgType = "INSTANT_BUY_ANOMALY",
                trend = "UP",
                confidence = 0.91,
                expectedNetPct = 2.4,
                shortTermReturnPct = 1.6,
                mediumTermReturnPct = 2.6,
                tradeActivityScore = 3.0,
                volumeAnomalyMultiplier = 3.1,
                tickVelocity1m = 4.2,
                tickVelocity5m = 4.8,
                leadPairId = null,
                leadMomentumScore = 0.20,
            ),
            balances = listOf(BalanceSnapshot(asset = "idr", free = DecimalValue.fromDouble(100_000.0))),
        )
        assertNotNull(decision)
        assertEquals("barbarian_anomaly", decision.engineId)
    }
}

