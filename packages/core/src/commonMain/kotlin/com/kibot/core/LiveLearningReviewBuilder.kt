package com.kibot.core

import com.kibot.shared.models.BotId
import com.kibot.shared.models.LearningObservation
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.TradingHorizon
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

data class LiveLearningReviewConfig(
    val lookbackDays: Int = 7,
    val maxRecentOrders: Int = 80,
    val publishIntervalHours: Int = 6,
    val missedOpportunityThreshold: Double = 0.70,
) {
    val lookbackWindowMs: Long = lookbackDays * 86_400_000L
}

class LiveLearningReviewBuilder(
    private val weeklyLearningLoop: WeeklyLearningLoop = WeeklyLearningLoop(),
    private val config: LiveLearningReviewConfig = LiveLearningReviewConfig(),
) {
    fun build(
        botId: BotId,
        now: Instant,
        cycle: StrategyCycleResult,
        marketQuotes: List<MarketQuote>,
        recentOrders: List<OrderSnapshot>,
    ): WeeklyLearningSummary? {
        val jakartaDate = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
        val periodStart = jakartaDate.minus(DatePeriod(days = config.lookbackDays - 1))
        val rankedByPair = cycle.rankedPairs.associateBy { it.pairId }
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val portfolioEquityIdr = cycle.portfolio.totalEquityIdr.toDoubleOrZero().coerceAtLeast(1.0)
        val cutoffEpochMs = now.toEpochMilliseconds() - config.lookbackWindowMs

        val observations = buildList {
            recentOrders
                .asSequence()
                .filter { it.updatedAt.toEpochMilliseconds() >= cutoffEpochMs }
                .filter { it.status in setOf(OrderStatus.FILLED, OrderStatus.PARTIALLY_FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED) }
                .take(config.maxRecentOrders)
                .forEach { order ->
                    val quote = quoteByPair[order.pairId]
                    val pairScore = rankedByPair[order.pairId]
                    val executedQty = order.executedQuantity.toDoubleOrZero().takeIf { it > 0.0 }
                        ?: order.originalQuantity.toDoubleOrZero()
                    val referencePrice = order.price.toDoubleOrZero().takeIf { it > 0.0 }
                        ?: quote?.midPrice?.toDoubleOrZero()
                        ?: 0.0
                    if (executedQty <= 0.0 || referencePrice <= 0.0) return@forEach

                    val notionalIdr = executedQty * referencePrice
                    val utilization = (notionalIdr / portfolioEquityIdr).coerceIn(0.0, 1.0)
                    val horizon = pairScore?.preferredHorizon ?: TradingHorizon.TACTICAL
                    val quoteMid = quote?.midPrice?.toDoubleOrZero() ?: referencePrice
                    val markToMarketPct = when (order.side) {
                        OrderSide.BUY -> ((quoteMid - referencePrice) / referencePrice) * 100.0
                        OrderSide.SELL -> ((referencePrice - quoteMid) / referencePrice) * 100.0
                    }
                    val feePenaltyPct = ((order.feePaid.toDoubleOrZero() / notionalIdr) * 100.0).coerceIn(0.0, 1.5)
                    val slippagePct = quote?.estimatedSlippagePct ?: 0.10
                    val realizedPnlPct = (markToMarketPct - feePenaltyPct - (slippagePct * 0.25)).coerceIn(-5.0, 5.0)

                    add(
                        LearningObservation(
                            observedAt = order.updatedAt,
                            pairId = order.pairId,
                            setupType = setupFor(horizon, quote),
                            horizon = horizon,
                            tradeTaken = true,
                            realizedPnlPct = realizedPnlPct,
                            expectedNetEdgePct = pairScore?.marketOpportunityScore ?: 0.0,
                            slippagePct = slippagePct,
                            fillQualityScore = pairScore?.fillQualityScore ?: quote?.fillQualityScore ?: 0.5,
                            falseEntry = order.side == OrderSide.BUY && realizedPnlPct <= -0.35,
                            capitalUtilizationPct = utilization,
                            productiveUtilizationPct = if (realizedPnlPct > 0.0) utilization else utilization * 0.45,
                        ),
                    )
                }

            cycle.rankedPairs
                .filterNot { it.allowed }
                .take(2)
                .forEach { pair ->
                    add(
                        LearningObservation(
                            observedAt = now,
                            pairId = pair.pairId,
                            setupType = SetupType.NO_TRADE,
                            horizon = pair.preferredHorizon,
                            tradeTaken = false,
                            avoidedBadTrade = pair.rejectionReasons.isNotEmpty(),
                            capitalUtilizationPct = 0.0,
                            productiveUtilizationPct = 0.0,
                        ),
                    )
                }

            if (
                cycle.selectedSignal == null &&
                cycle.modeSnapshot.tradingAllowed &&
                cycle.deploymentPlan.allowNewEntries
            ) {
                cycle.deploymentPlan.candidates.firstOrNull()?.takeIf {
                    it.marketOpportunityScore >= config.missedOpportunityThreshold
                }?.let { candidate ->
                    add(
                        LearningObservation(
                            observedAt = now,
                            pairId = candidate.pairId,
                            setupType = SetupType.NO_TRADE,
                            horizon = candidate.preferredHorizon,
                            tradeTaken = false,
                            missedQualifiedOpportunity = true,
                            capitalUtilizationPct = 0.0,
                            productiveUtilizationPct = 0.0,
                        ),
                    )
                }
            }
        }

        if (observations.isEmpty()) return null
        return weeklyLearningLoop.review(
            botId = botId,
            periodStart = periodStart,
            periodEnd = jakartaDate,
            observations = observations,
        )
    }

    private fun setupFor(horizon: TradingHorizon, quote: MarketQuote?): SetupType {
        if (horizon == TradingHorizon.SWING) return SetupType.SWING_TREND_CONTINUATION
        val shortReturn = quote?.shortTermReturnPct ?: 0.0
        return when {
            shortReturn < 0.70 -> SetupType.HEALTHY_SHORT_TERM_PULLBACK
            shortReturn < 1.40 -> SetupType.LIGHT_BREAKOUT_CONTINUATION
            else -> SetupType.MICRO_MEAN_REVERSION
        }
    }
}
