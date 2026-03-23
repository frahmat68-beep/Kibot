package com.kibot.core

import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.SetupType
import com.kibot.shared.models.StrategySignal
import com.kibot.shared.models.StrategySignalType
import com.kibot.shared.models.TradingHorizon
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.math.max

data class TradeAutomationConfig(
    val minTrackedPositionValueIdr: Double = 12_000.0,
    val thesisInvalidRankingFloor: Double = 0.46,
    val thesisInvalidAgeHours: Double = 4.0,
    val timeExitGraceMultiplier: Double = 2.25,
    val maxStaleLossPctForTimeExit: Double = 0.10,
    val orderFillTolerancePct: Double = 0.0025,
    val emergencyMarketExitLossPct: Double = -2.2,
    val ambiguousOrderGraceMinutes: Double = 4.0,
    val estimatedRoundTripCostPct: Double = 0.66,
    val breakEvenExitBufferPct: Double = 0.18,
    val speculativeWinnerRunMinPnlPct: Double = 1.2,
    val speculativeWinnerRunMinTrendScore: Double = 0.62,
    val speculativeWinnerRunMinHealthScore: Double = 0.60,
    val speculativeWinnerRunMinOpportunityScore: Double = 0.60,
    val breakoutWinnerRunMinPnlPct: Double = 0.75,
    val breakoutWinnerRunMinTrendScore: Double = 0.60,
    val breakoutWinnerRunMinHealthScore: Double = 0.58,
    val breakoutWinnerRunMinOpportunityScore: Double = 0.58,
    val minMeaningfulNonEmergencyExitProfitPct: Double = 1.20,
    val minMeaningfulNonEmergencyExitProfitIdr: Double = 180.0,
)

data class ManagedPosition(
    val pairId: PairId,
    val quantity: DecimalValue,
    val averageEntryPrice: DecimalValue,
    val currentBidPrice: DecimalValue,
    val currentValueIdr: DecimalValue,
    val unrealizedPnlIdr: DecimalValue,
    val unrealizedPnlPct: Double,
    val breakEvenPrice: DecimalValue,
    val takeProfitPrice: DecimalValue,
    val stopPrice: DecimalValue,
    val openedAt: Instant,
    val updatedAt: Instant,
    val horizon: TradingHorizon,
    val setupType: SetupType,
    val pairTier: com.kibot.shared.models.PairTier,
    val speculativePocket: Boolean,
    val expectedHoldingHours: Double,
)

enum class ExitReason {
    PROFIT_EXIT,
    STOP_LOSS_EXIT,
    TIME_EXIT,
    THESIS_INVALID_EXIT,
    PROFIT_PROTECTION_EXIT,
}

data class ExitDecision(
    val position: ManagedPosition,
    val executionPlan: ExecutionPlan,
    val reason: ExitReason,
    val message: String,
)

class TradeAutomationCoordinator(
    private val executionConfig: StrategyExecutionConfig = StrategyExecutionConfig(),
    private val config: TradeAutomationConfig = TradeAutomationConfig(),
) {
    fun reconcileOrders(
        persistedOrders: List<OrderSnapshot>,
        exchangeOpenOrders: List<OrderSnapshot>,
        recentFills: List<FillSnapshot>,
    ): List<OrderSnapshot> {
        if (persistedOrders.isEmpty()) return emptyList()

        val exchangeOpenByClientId = exchangeOpenOrders.associateBy { it.clientOrderId }
        val fillsByOrderId = recentFills.groupBy { it.orderId }

        return persistedOrders.mapNotNull { persisted ->
            val active = persisted.status in activeOrderStatuses
            if (!active) return@mapNotNull null

            val openOrder = exchangeOpenByClientId[persisted.clientOrderId]
            if (openOrder != null) {
                return@mapNotNull openOrder.takeIf { it != persisted }
            }

            val fills = fillsByOrderId[persisted.orderId].orEmpty()
            if (fills.isEmpty()) {
                val ageMinutes = ((Clock.System.now().toEpochMilliseconds() - persisted.updatedAt.toEpochMilliseconds())
                    .coerceAtLeast(0L) / 60_000.0)
                return@mapNotNull when (persisted.status) {
                    OrderStatus.CANCEL_REQUESTED -> persisted.copy(
                        status = OrderStatus.CANCELED,
                        updatedAt = Clock.System.now(),
                    )

                    OrderStatus.UNKNOWN,
                    OrderStatus.SUBMITTING,
                    OrderStatus.CREATED,
                    -> if (ageMinutes >= config.ambiguousOrderGraceMinutes) {
                        persisted.copy(
                            status = OrderStatus.CANCELED,
                            updatedAt = Clock.System.now(),
                        )
                    } else {
                        null
                    }

                    else -> null
                }
            }

            val executedQuantity = fills.sumOf { it.quantity.toDoubleOrZero() }
            val originalQuantity = persisted.originalQuantity.toDoubleOrZero().coerceAtLeast(0.0)
            val tolerance = max(originalQuantity * config.orderFillTolerancePct, 0.00000001)
            val forceFilled = persisted.status != OrderStatus.CANCEL_REQUESTED &&
                executedQuantity > tolerance
            val effectiveOriginalQuantity = if (forceFilled) executedQuantity else originalQuantity
            val remainingQuantity = if (forceFilled) {
                0.0
            } else {
                (effectiveOriginalQuantity - executedQuantity).coerceAtLeast(0.0)
            }
            val status = when {
                forceFilled -> OrderStatus.FILLED
                executedQuantity + tolerance >= originalQuantity -> OrderStatus.FILLED
                executedQuantity > tolerance -> OrderStatus.PARTIALLY_FILLED
                else -> persisted.status
            }
            val feePaid = fills.sumOf { it.fee.toDoubleOrZero() }
            val updatedAt = fills.maxOfOrNull { it.executedAt } ?: persisted.updatedAt
            val weightedPrice = weightedFillPrice(fills) ?: persisted.price.toDoubleOrZero()

            persisted.copy(
                status = status,
                price = DecimalValue.fromDouble(weightedPrice),
                originalQuantity = DecimalValue.fromDouble(effectiveOriginalQuantity),
                executedQuantity = DecimalValue.fromDouble(executedQuantity),
                remainingQuantity = DecimalValue.fromDouble(remainingQuantity),
                feePaid = DecimalValue.fromDouble(feePaid),
                updatedAt = updatedAt,
            ).takeIf { it != persisted }
        }
    }

    fun deriveManagedPositions(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
        reconciledOrders: List<OrderSnapshot>,
        rankedPairs: List<PairScore>,
        now: Instant,
    ): List<ManagedPosition> {
        if (balances.isEmpty() || marketQuotes.isEmpty()) return emptyList()

        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val rankedByPair = rankedPairs.associateBy { it.pairId }
        val ordersByPair = reconciledOrders.groupBy { it.pairId }

        return balances.mapNotNull { balance ->
            val balanceQuantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (balance.asset.equals("idr", ignoreCase = true) || balanceQuantity <= 0.0) {
                return@mapNotNull null
            }

            val pairId = resolveBalancePairId(
                asset = balance.asset,
                ordersByPair = ordersByPair,
                quoteByPair = quoteByPair,
            ) ?: return@mapNotNull null
            val quote = quoteByPair[pairId] ?: return@mapNotNull null
            val quoteAssetPriceIdr = quoteAssetPriceIdr(pairId.assets().quoteAsset, marketQuotes) ?: return@mapNotNull null
            val valueIdr = balanceQuantity * quote.bestBid.toDoubleOrZero() * quoteAssetPriceIdr
            if (valueIdr < max(config.minTrackedPositionValueIdr, executionConfig.minOrderNotionalIdr)) return@mapNotNull null

            val pairOrders = ordersByPair[pairId].orEmpty()
            val rankedPair = rankedByPair[pairId]
            val buyOrders = pairOrders.filter { it.side == OrderSide.BUY && it.filledQuantity() > 0.0 }
            val weightedEntryPrice = weightedAveragePrice(buyOrders)
                ?: pairOrders
                    .firstOrNull { it.side == OrderSide.BUY }
                    ?.price
                    ?.toDoubleOrZero()
                ?: quote.midPrice.toDoubleOrZero()
            val breakEvenPrice = DecimalValue.fromDouble(
                weightedEntryPrice * (1.0 + ((config.estimatedRoundTripCostPct + config.breakEvenExitBufferPct) / 100.0)),
            )
            val openedAt = buyOrders.maxByOrNull { it.updatedAt }?.updatedAt
                ?: pairOrders.firstOrNull { it.side == OrderSide.BUY }?.createdAt
                ?: now
            val horizon = rankedPair?.preferredHorizon
                ?: if (quote.mediumTermReturnPct >= 1.0) TradingHorizon.SWING else TradingHorizon.TACTICAL
            val speculativePocket = rankedPair?.speculativePocket == true
            val takeProfitPrice = DecimalValue.fromDouble(
                weightedEntryPrice *
                    when {
                        speculativePocket -> 1.14
                        horizon == TradingHorizon.SWING -> 1.075
                        else -> 1.04
                    },
            )
            val stopPrice = DecimalValue.fromDouble(
                weightedEntryPrice *
                    when {
                        speculativePocket -> 0.976
                        horizon == TradingHorizon.SWING -> 0.96
                        else -> 0.985
                    },
            )
            val currentBid = quote.bestBid.toDoubleOrZero()
            val unrealizedPnlPct = if (weightedEntryPrice > 0.0) {
                ((currentBid - weightedEntryPrice) / weightedEntryPrice) * 100.0
            } else {
                0.0
            }
            val unrealizedPnlIdr = (currentBid - weightedEntryPrice) * balanceQuantity * quoteAssetPriceIdr
            ManagedPosition(
                pairId = pairId,
                quantity = DecimalValue.fromDouble(balanceQuantity),
                averageEntryPrice = DecimalValue.fromDouble(weightedEntryPrice),
                currentBidPrice = quote.bestBid,
                currentValueIdr = DecimalValue.fromDouble(valueIdr),
                unrealizedPnlIdr = DecimalValue.fromDouble(unrealizedPnlIdr),
                unrealizedPnlPct = unrealizedPnlPct,
                breakEvenPrice = breakEvenPrice,
                takeProfitPrice = takeProfitPrice,
                stopPrice = stopPrice,
                openedAt = openedAt,
                updatedAt = now,
                horizon = horizon,
                setupType = when {
                    speculativePocket -> SetupType.LIGHT_BREAKOUT_CONTINUATION
                    horizon == TradingHorizon.SWING -> SetupType.SWING_TREND_CONTINUATION
                    quote.shortTermReturnPct < 0.0 -> SetupType.HEALTHY_SHORT_TERM_PULLBACK
                    else -> SetupType.LIGHT_BREAKOUT_CONTINUATION
                },
                pairTier = rankedPair?.pairTier ?: com.kibot.shared.models.PairTier.TIER_B,
                speculativePocket = speculativePocket,
                expectedHoldingHours = when {
                    speculativePocket -> 8.0
                    horizon == TradingHorizon.SWING -> 72.0
                    else -> 12.0
                },
            )
        }.sortedByDescending { it.currentValueIdr.toDoubleOrZero() }
    }

    fun planExit(
        now: Instant,
        cycle: StrategyCycleResult,
        managedPositions: List<ManagedPosition>,
        activeOrders: List<OrderSnapshot>,
    ): ExitDecision? {
        if (managedPositions.isEmpty()) return null

        val activeOrdersByPair = activeOrders
            .filter { it.status in activeOrderStatuses }
            .groupBy { it.pairId }
        val rankedByPair = cycle.rankedPairs.associateBy { it.pairId }

        return managedPositions
            .asSequence()
            .mapNotNull { position ->
                val decision = buildExitDecision(
                    now = now,
                    position = position,
                    pairScore = rankedByPair[position.pairId],
                    marketRegime = cycle.marketSnapshot.regime,
                    modeSnapshot = cycle.modeSnapshot,
                    riskDecision = cycle.riskDecision,
                )
                val pairActiveOrders = activeOrdersByPair[position.pairId].orEmpty()
                when {
                    decision == null -> null
                    pairActiveOrders.isEmpty() -> decision
                    decision.executionPlan.orderType == OrderType.MARKET &&
                        pairActiveOrders.all { it.side == OrderSide.SELL } -> decision
                    else -> null
                }
            }
            .sortedByDescending { exitPriority(it.reason) }
            .firstOrNull()
    }

    private fun buildExitDecision(
        now: Instant,
        position: ManagedPosition,
        pairScore: PairScore?,
        marketRegime: MarketRegime,
        modeSnapshot: BotModeSnapshot,
        riskDecision: RiskDecision,
    ): ExitDecision? {
        val currentBid = position.currentBidPrice.toDoubleOrZero()
        val breakEvenPrice = position.breakEvenPrice.toDoubleOrZero()
        val stopPrice = position.stopPrice.toDoubleOrZero()
        val takeProfitPrice = position.takeProfitPrice.toDoubleOrZero()
        val ageHours = ((now.toEpochMilliseconds() - position.openedAt.toEpochMilliseconds()).coerceAtLeast(0L) / 3_600_000.0)
        val keepWinnerRunning = shouldKeepWinnerRunning(
            position = position,
            pairScore = pairScore,
            marketRegime = marketRegime,
            riskDecision = riskDecision,
        )

        val exitReason = when {
            currentBid <= stopPrice -> ExitReason.STOP_LOSS_EXIT
            currentBid >= takeProfitPrice && !keepWinnerRunning -> ExitReason.PROFIT_EXIT
            marketRegime == MarketRegime.BREAKDOWN_PANIC -> ExitReason.THESIS_INVALID_EXIT
            pairScore != null &&
                (!pairScore.allowed || pairScore.rankingScore < config.thesisInvalidRankingFloor) &&
                ageHours >= config.thesisInvalidAgeHours &&
                position.unrealizedPnlPct > -1.5 -> ExitReason.THESIS_INVALID_EXIT
            ageHours >= position.expectedHoldingHours * config.timeExitGraceMultiplier &&
                position.unrealizedPnlPct <= config.maxStaleLossPctForTimeExit -> ExitReason.TIME_EXIT
            riskDecision.profitProtectionStatus != com.kibot.shared.models.ProfitProtectionStatus.INACTIVE &&
                position.unrealizedPnlPct >= (if (position.speculativePocket) 2.6 else 1.4) &&
                currentBid < takeProfitPrice * when {
                    keepWinnerRunning && position.speculativePocket -> 0.960
                    keepWinnerRunning -> 0.982
                    position.speculativePocket -> 0.980
                    else -> 0.992
                } ->
                ExitReason.PROFIT_PROTECTION_EXIT
            else -> null
        } ?: return null

        val useEmergencyMarketExit = riskDecision.hardStopTriggered ||
            marketRegime == MarketRegime.BREAKDOWN_PANIC ||
            (exitReason == ExitReason.STOP_LOSS_EXIT && position.unrealizedPnlPct <= config.emergencyMarketExitLossPct)

        val nonEmergencyExitBelowBreakEven = !useEmergencyMarketExit &&
            exitReason in setOf(
                ExitReason.PROFIT_EXIT,
                ExitReason.TIME_EXIT,
                ExitReason.THESIS_INVALID_EXIT,
                ExitReason.PROFIT_PROTECTION_EXIT,
            ) &&
            currentBid < breakEvenPrice
        if (nonEmergencyExitBelowBreakEven) {
            return null
        }
        val nonEmergencyExitTooSmall = !useEmergencyMarketExit &&
            exitReason in setOf(
                ExitReason.PROFIT_EXIT,
                ExitReason.TIME_EXIT,
                ExitReason.THESIS_INVALID_EXIT,
                ExitReason.PROFIT_PROTECTION_EXIT,
            ) &&
            position.unrealizedPnlPct < config.minMeaningfulNonEmergencyExitProfitPct &&
            position.unrealizedPnlIdr.toDoubleOrZero() < config.minMeaningfulNonEmergencyExitProfitIdr
        if (nonEmergencyExitTooSmall) {
            return null
        }

        val signal = StrategySignal(
            pairId = position.pairId,
            signalType = StrategySignalType.EXIT,
            confidence = (pairScore?.rankingScore ?: 0.62).coerceIn(0.45, 0.98),
            rationale = listOf(exitReasonMessage(exitReason, position)),
            entryPrice = position.currentBidPrice,
            takeProfitPrice = position.takeProfitPrice,
            stopPrice = position.stopPrice,
            setupType = position.setupType,
            horizon = position.horizon,
            pairTier = position.pairTier,
            speculativePocket = position.speculativePocket,
            marketRegime = marketRegime,
            edgeConfidence = modeSnapshot.edgeConfidence,
            expectedHoldingHours = position.expectedHoldingHours,
            expectedNetProfitabilityPct = abs(position.unrealizedPnlPct),
        )

        return ExitDecision(
            position = position,
            reason = exitReason,
            message = exitReasonMessage(exitReason, position),
            executionPlan = ExecutionPlan(
                signal = signal,
                side = OrderSide.SELL,
                orderType = if (useEmergencyMarketExit) OrderType.MARKET else OrderType.LIMIT,
                quantity = position.quantity,
                limitPrice = if (useEmergencyMarketExit) null else position.currentBidPrice,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = abs(position.unrealizedPnlPct),
                botMode = modeSnapshot.mode,
                riskLadderLevel = modeSnapshot.riskLadderLevel,
                pairRankingScore = pairScore?.rankingScore ?: 0.62,
                speculativePocket = position.speculativePocket,
            ),
        )
    }

    private fun shouldKeepWinnerRunning(
        position: ManagedPosition,
        pairScore: PairScore?,
        marketRegime: MarketRegime,
        riskDecision: RiskDecision,
    ): Boolean {
        if (riskDecision.hardStopTriggered || marketRegime == MarketRegime.BREAKDOWN_PANIC) return false
        val score = pairScore ?: return false
        return when {
            position.speculativePocket ->
                position.unrealizedPnlPct >= config.speculativeWinnerRunMinPnlPct &&
                    score.allowed &&
                    score.trendQualityScore >= config.speculativeWinnerRunMinTrendScore &&
                    score.recentHealthScore >= config.speculativeWinnerRunMinHealthScore &&
                    score.marketOpportunityScore >= config.speculativeWinnerRunMinOpportunityScore
            position.setupType == SetupType.LIGHT_BREAKOUT_CONTINUATION ->
                position.unrealizedPnlPct >= config.breakoutWinnerRunMinPnlPct &&
                    score.allowed &&
                    score.trendQualityScore >= config.breakoutWinnerRunMinTrendScore &&
                    score.recentHealthScore >= config.breakoutWinnerRunMinHealthScore &&
                    score.marketOpportunityScore >= config.breakoutWinnerRunMinOpportunityScore
            position.horizon == TradingHorizon.SWING ->
                position.unrealizedPnlPct >= 1.20 &&
                    score.allowed &&
                    score.trendQualityScore >= 0.60 &&
                    score.marketOpportunityScore >= 0.58
            else -> false
        }
    }

    private fun resolveBalancePairId(
        asset: String,
        ordersByPair: Map<PairId, List<OrderSnapshot>>,
        quoteByPair: Map<PairId, MarketQuote>,
    ): PairId? {
        val normalizedAsset = asset.lowercase()
        val orderPair = ordersByPair.keys
            .filter { it.assets().baseAsset == normalizedAsset }
            .maxByOrNull { pairId ->
                ordersByPair[pairId].orEmpty().maxOfOrNull { it.updatedAt.toEpochMilliseconds() } ?: Long.MIN_VALUE
            }
        if (orderPair != null) return orderPair

        val preferredQuotes = listOf("idr", "usdt", "btc", "eth")
        return preferredQuotes
            .asSequence()
            .map { quoteAsset -> PairId("${normalizedAsset}_$quoteAsset") }
            .firstOrNull { it in quoteByPair }
    }

    private fun weightedAveragePrice(orders: List<OrderSnapshot>): Double? {
        val priceTimesQty = orders.sumOf { it.price.toDoubleOrZero() * it.filledQuantity() }
        val quantity = orders.sumOf { it.filledQuantity() }
        if (quantity <= 0.0) return null
        return priceTimesQty / quantity
    }

    private fun weightedFillPrice(fills: List<FillSnapshot>): Double? {
        val quantity = fills.sumOf { it.quantity.toDoubleOrZero() }
        if (quantity <= 0.0) return null
        return fills.sumOf { it.price.toDoubleOrZero() * it.quantity.toDoubleOrZero() } / quantity
    }

    private fun OrderSnapshot.filledQuantity(): Double {
        val executed = executedQuantity.toDoubleOrZero()
        if (executed > 0.0) return executed
        return if (status == OrderStatus.FILLED) originalQuantity.toDoubleOrZero() else 0.0
    }

    private fun exitPriority(reason: ExitReason): Int = when (reason) {
        ExitReason.STOP_LOSS_EXIT -> 5
        ExitReason.THESIS_INVALID_EXIT -> 4
        ExitReason.PROFIT_PROTECTION_EXIT -> 3
        ExitReason.PROFIT_EXIT -> 2
        ExitReason.TIME_EXIT -> 1
    }

    private fun exitReasonMessage(reason: ExitReason, position: ManagedPosition): String = when (reason) {
        ExitReason.PROFIT_EXIT -> "Take-profit ${position.pairId.value} tersentuh, bot menyiapkan exit otomatis."
        ExitReason.STOP_LOSS_EXIT -> "Stop-loss ${position.pairId.value} tersentuh, bot menyiapkan exit otomatis."
        ExitReason.TIME_EXIT -> "Holding ${position.pairId.value} sudah terlalu lama tanpa kualitas yang cukup, bot menutup posisi."
        ExitReason.THESIS_INVALID_EXIT -> "Thesis ${position.pairId.value} melemah atau market memburuk, bot menutup posisi."
        ExitReason.PROFIT_PROTECTION_EXIT -> "Profit ${position.pairId.value} mulai giveback, bot mengunci hasil yang sudah ada."
    }

    private fun quoteAssetPriceIdr(asset: String, quotes: List<MarketQuote>): Double? {
        if (asset.equals("idr", ignoreCase = true)) return 1.0
        val directPair = PairId("${asset.lowercase()}_idr")
        val directQuote = quotes.firstOrNull { it.pairId == directPair }
        return directQuote?.midPrice?.toDoubleOrZero()
    }

    private data class PairParts(
        val baseAsset: String,
        val quoteAsset: String,
    )

    private fun PairId.assets(): PairParts {
        val parts = value.lowercase().split("_")
        return if (parts.size == 2) {
            PairParts(parts[0], parts[1])
        } else {
            val quoteAsset = listOf("idr", "usdt", "btc", "eth").firstOrNull { value.lowercase().endsWith(it) }
                ?: "idr"
            PairParts(value.lowercase().removeSuffix(quoteAsset), quoteAsset)
        }
    }

    private companion object {
        private val activeOrderStatuses = setOf(
            OrderStatus.CREATED,
            OrderStatus.SUBMITTING,
            OrderStatus.OPEN,
            OrderStatus.PARTIALLY_FILLED,
            OrderStatus.CANCEL_REQUESTED,
            OrderStatus.UNKNOWN,
        )
    }
}
