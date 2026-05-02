package com.kibot.core

import com.kibot.shared.models.BotId
import com.kibot.shared.models.LearningObservation
import com.kibot.shared.models.PairId
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.TradingHorizon
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyLearningLoopTest {
    @Test
    fun `promotes strong pairs and penalizes weak ones`() {
        val review = WeeklyLearningLoop().review(
            botId = BotId("main"),
            periodStart = LocalDate(2026, 3, 9),
            periodEnd = LocalDate(2026, 3, 15),
            observations = buildList {
                repeat(4) { index ->
                    add(
                        LearningObservation(
                            observedAt = Instant.parse("2026-03-1${index}T01:00:00Z"),
                            pairId = PairId("btc_idr"),
                            setupType = SetupType.HEALTHY_SHORT_TERM_PULLBACK,
                            horizon = TradingHorizon.TACTICAL,
                            tradeTaken = true,
                            realizedPnlPct = 0.45,
                            expectedNetEdgePct = 0.30,
                            fillQualityScore = 0.82,
                            capitalUtilizationPct = 0.55,
                            productiveUtilizationPct = 0.45,
                        ),
                    )
                }
                repeat(4) { index ->
                    add(
                        LearningObservation(
                            observedAt = Instant.parse("2026-03-1${index}T05:00:00Z"),
                            pairId = PairId("thin_pair"),
                            setupType = SetupType.MICRO_MEAN_REVERSION,
                            horizon = TradingHorizon.TACTICAL,
                            tradeTaken = true,
                            realizedPnlPct = -0.35,
                            expectedNetEdgePct = 0.22,
                            slippagePct = 0.30,
                            falseEntry = true,
                            capitalUtilizationPct = 0.50,
                            productiveUtilizationPct = 0.18,
                        ),
                    )
                }
                repeat(4) { index ->
                    add(
                        LearningObservation(
                            observedAt = Instant.parse("2026-03-1${index}T09:00:00Z"),
                            pairId = PairId("sol_idr"),
                            setupType = SetupType.SWING_TREND_CONTINUATION,
                            horizon = TradingHorizon.SWING,
                            tradeTaken = true,
                            realizedPnlPct = 0.38,
                            expectedNetEdgePct = 0.28,
                            fillQualityScore = 0.78,
                            capitalUtilizationPct = 0.62,
                            productiveUtilizationPct = 0.48,
                        ),
                    )
                }
                repeat(3) { index ->
                    add(
                        LearningObservation(
                            observedAt = Instant.parse("2026-03-1${index}T13:00:00Z"),
                            tradeTaken = false,
                            avoidedBadTrade = true,
                            capitalUtilizationPct = 0.42,
                            productiveUtilizationPct = 0.40,
                        ),
                    )
                }
            },
        )

        assertEquals(PairId("btc_idr"), review.bestPairs.first())
        assertEquals(PairId("thin_pair"), review.worstPairs.first())
        assertTrue(review.profitFactor > 2.0)
        assertTrue(review.maximumDrawdownPct < 0.0)
        assertTrue(review.falseEntryRate > 0.20)
        assertTrue(review.noTradeQualityScore >= 0.5)
        assertTrue(review.adaptationPlan.temporaryBlacklistPairs.contains(PairId("thin_pair")))
    }
}
