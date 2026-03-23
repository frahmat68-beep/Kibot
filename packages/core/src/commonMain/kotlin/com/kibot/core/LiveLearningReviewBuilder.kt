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
    val minimumMeaningfulNetProfitIdr: Double = 180.0,
    val minimumMeaningfulNetProfitPct: Double = 1.20,
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
        val realizedRoundTripBySellOrder = buildRealizedRoundTripBySellOrder(recentOrders)

        val observations = buildList {
            recentOrders
                .asSequence()
                .filter { it.updatedAt.toEpochMilliseconds() >= cutoffEpochMs }
                .filter { order ->
                    when (order.status) {
                        OrderStatus.FILLED,
                        OrderStatus.PARTIALLY_FILLED,
                        -> true

                        OrderStatus.CANCELED -> order.executedQuantity.toDoubleOrZero() > 0.0
                        else -> false
                    }
                }
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
                    val realizedRoundTrip = if (order.side == OrderSide.SELL) {
                        realizedRoundTripBySellOrder[order.orderId]
                    } else {
                        null
                    }
                    val realizedPnlPct = when (order.side) {
                        OrderSide.SELL -> realizedRoundTrip?.pnlPct
                            ?: (markToMarketPct - feePenaltyPct - (slippagePct * 0.25))
                        OrderSide.BUY -> markToMarketPct - feePenaltyPct - (slippagePct * 0.25)
                    }.coerceIn(-5.0, 5.0)
                    val realizedPnlIdr = when (order.side) {
                        OrderSide.SELL -> realizedRoundTrip?.pnlIdr
                            ?: ((realizedPnlPct / 100.0) * notionalIdr)
                        OrderSide.BUY -> ((realizedPnlPct / 100.0) * notionalIdr)
                    }
                    val lowQualityRoundTrip = order.side == OrderSide.SELL &&
                        realizedPnlPct < config.minimumMeaningfulNetProfitPct &&
                        realizedPnlIdr < config.minimumMeaningfulNetProfitIdr
                    val productiveTrade = realizedPnlPct > config.minimumMeaningfulNetProfitPct &&
                        realizedPnlIdr >= config.minimumMeaningfulNetProfitIdr

                    add(
                        LearningObservation(
                            observedAt = order.updatedAt,
                            pairId = order.pairId,
                            setupType = setupFor(horizon, quote),
                            horizon = horizon,
                            tradeTaken = true,
                            realizedPnlPct = realizedPnlPct,
                            expectedNetEdgePct = pairScore?.feeAdjustedEdgeScore ?: 0.0,
                            slippagePct = slippagePct,
                            fillQualityScore = pairScore?.fillQualityScore ?: quote?.fillQualityScore ?: 0.5,
                            falseEntry = when (order.side) {
                                OrderSide.BUY -> realizedPnlPct <= -0.55
                                OrderSide.SELL -> realizedPnlPct <= -0.20 || lowQualityRoundTrip
                            },
                            capitalUtilizationPct = utilization,
                            productiveUtilizationPct = when {
                                productiveTrade -> utilization
                                realizedPnlPct > 0.0 -> utilization * 0.25
                                else -> utilization * 0.45
                            },
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

            if (
                cycle.selectedSignal != null &&
                cycle.executionPlan == null &&
                cycle.modeSnapshot.tradingAllowed &&
                cycle.deploymentPlan.allowNewEntries
            ) {
                add(
                    LearningObservation(
                        observedAt = now,
                        pairId = cycle.selectedSignal.pairId,
                        setupType = cycle.selectedSignal.setupType,
                        horizon = cycle.selectedSignal.horizon,
                        tradeTaken = false,
                        missedQualifiedOpportunity = cycle.selectedSignal.expectedNetProfitabilityPct >= config.minimumMeaningfulNetProfitPct,
                        capitalUtilizationPct = 0.0,
                        productiveUtilizationPct = 0.0,
                    ),
                )
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

    private fun buildRealizedRoundTripBySellOrder(
        recentOrders: List<OrderSnapshot>,
    ): Map<com.kibot.shared.models.OrderId, RealizedRoundTrip> {
        data class OpenLot(
            val quantity: Double,
            val price: Double,
        )

        val lotsByPair = mutableMapOf<com.kibot.shared.models.PairId, ArrayDeque<OpenLot>>()
        val realizedByOrder = mutableMapOf<com.kibot.shared.models.OrderId, RealizedRoundTrip>()

        recentOrders
            .sortedBy { it.updatedAt.toEpochMilliseconds() }
            .forEach { order ->
                val executedQty = order.executedQuantity.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: order.originalQuantity.toDoubleOrZero()
                val price = order.price.toDoubleOrZero()
                if (executedQty <= 0.0 || price <= 0.0) return@forEach

                when (order.side) {
                    OrderSide.BUY -> {
                        val queue = lotsByPair.getOrPut(order.pairId) { ArrayDeque() }
                        queue.addLast(OpenLot(quantity = executedQty, price = price))
                    }

                    OrderSide.SELL -> {
                        val queue = lotsByPair.getOrPut(order.pairId) { ArrayDeque() }
                        var remainingQty = executedQty
                        var costQty = 0.0
                        var costNotional = 0.0

                        while (remainingQty > 0.0 && queue.isNotEmpty()) {
                            val lot = queue.removeFirst()
                            val matchedQty = minOf(remainingQty, lot.quantity)
                            costQty += matchedQty
                            costNotional += matchedQty * lot.price
                            remainingQty -= matchedQty
                            val leftoverQty = lot.quantity - matchedQty
                            if (leftoverQty > 0.0) {
                                queue.addFirst(lot.copy(quantity = leftoverQty))
                            }
                        }

                        val averageCost = when {
                            costQty > 0.0 -> costNotional / costQty
                            else -> price
                        }
                        val sellNotional = executedQty * price
                        val feePenaltyPct = ((order.feePaid.toDoubleOrZero() / sellNotional) * 100.0).coerceIn(0.0, 1.5)
                        val grossPct = if (averageCost > 0.0) ((price - averageCost) / averageCost) * 100.0 else 0.0
                        val netPct = grossPct - feePenaltyPct
                        val netPnlIdr = ((price - averageCost) * executedQty) - order.feePaid.toDoubleOrZero()
                        realizedByOrder[order.orderId] = RealizedRoundTrip(
                            pnlPct = netPct,
                            pnlIdr = netPnlIdr,
                        )
                    }
                }
            }

        return realizedByOrder
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

    private data class RealizedRoundTrip(
        val pnlPct: Double,
        val pnlIdr: Double,
    )
}
