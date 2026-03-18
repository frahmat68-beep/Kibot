package com.kibot.shared.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class BalanceSnapshot(
    val asset: String,
    val free: DecimalValue,
    val locked: DecimalValue = DecimalValue.Zero,
    val totalValueInIdr: DecimalValue? = null,
)

@Serializable
data class MarketQuote(
    val pairId: PairId,
    val bestBid: DecimalValue,
    val bestAsk: DecimalValue,
    val midPrice: DecimalValue,
    val spreadPct: Double,
    val quoteVolume24h: DecimalValue,
    val baseVolume24h: DecimalValue,
    val estimatedSlippagePct: Double,
    val orderBookStabilityScore: Double,
    val tradeCount24h: Int = 0,
    val bidDepthTop5Idr: DecimalValue = DecimalValue.Zero,
    val askDepthTop5Idr: DecimalValue = DecimalValue.Zero,
    val shortTermReturnPct: Double = 0.0,
    val mediumTermReturnPct: Double = 0.0,
    val realizedVolatilityPct: Double = 0.0,
    val recentTradeActivityScore: Double = 0.5,
    val volatilityQualityScore: Double = 0.5,
    val trendQualityScore: Double = 0.5,
    val historicalExpectancyScore: Double = 0.5,
    val fillQualityScore: Double = 0.5,
    val holdabilityScore: Double = 0.5,
    val capturedAt: Instant,
)

@Serializable
data class PairScore(
    val pairId: PairId,
    val liquidityScore: Double,
    val spreadScore: Double,
    val slippageScore: Double,
    val stabilityScore: Double,
    val volumeConsistencyScore: Double = 0.0,
    val volatilityQualityScore: Double = 0.0,
    val trendQualityScore: Double = 0.0,
    val historicalExpectancyScore: Double = 0.0,
    val recentHealthScore: Double = 0.0,
    val fillQualityScore: Double = 0.0,
    val holdabilityScore: Double = 0.0,
    val feeAdjustedEdgeScore: Double,
    val marketOpportunityScore: Double = 0.0,
    val rankingScore: Double = 0.0,
    val pairTier: PairTier = PairTier.TIER_C,
    val preferredHorizon: TradingHorizon = TradingHorizon.TACTICAL,
    val allowed: Boolean = false,
    val rejectionReasons: List<String> = emptyList(),
)

@Serializable
data class StrategySignal(
    val pairId: PairId,
    val signalType: StrategySignalType,
    val confidence: Double,
    val rationale: List<String>,
    val entryPrice: DecimalValue? = null,
    val takeProfitPrice: DecimalValue? = null,
    val stopPrice: DecimalValue? = null,
    val setupType: SetupType = SetupType.NO_TRADE,
    val horizon: TradingHorizon = TradingHorizon.TACTICAL,
    val pairTier: PairTier = PairTier.TIER_C,
    val marketRegime: MarketRegime = MarketRegime.HIGH_VOLATILITY_UNCLEAR,
    val edgeConfidence: EdgeConfidence = EdgeConfidence.MEDIUM,
    val expectedHoldingHours: Double = 0.0,
    val expectedNetProfitabilityPct: Double = 0.0,
    val noTradeReason: String? = null,
)

@Serializable
data class ExecutionPlan(
    val signal: StrategySignal,
    val side: OrderSide,
    val orderType: OrderType,
    val quantity: DecimalValue,
    val limitPrice: DecimalValue? = null,
    val quoteBudget: DecimalValue? = null,
    val postOnlyPreferred: Boolean = true,
    val expectedNetEdgePct: Double,
    val botMode: BotMode = BotMode.GROWTH,
    val riskLadderLevel: RiskLadderLevel = RiskLadderLevel.NORMAL,
    val pairRankingScore: Double = 0.0,
)

@Serializable
data class OrderSnapshot(
    val orderId: OrderId,
    val clientOrderId: ClientOrderId,
    val pairId: PairId,
    val side: OrderSide,
    val orderType: OrderType,
    val status: OrderStatus,
    val price: DecimalValue,
    val originalQuantity: DecimalValue,
    val executedQuantity: DecimalValue,
    val remainingQuantity: DecimalValue,
    val feePaid: DecimalValue = DecimalValue.Zero,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class FillSnapshot(
    val fillId: FillId,
    val orderId: OrderId,
    val pairId: PairId,
    val side: OrderSide,
    val quantity: DecimalValue,
    val price: DecimalValue,
    val fee: DecimalValue,
    val feeAsset: String,
    val executedAt: Instant,
)

@Serializable
data class PositionSnapshot(
    val positionId: PositionId,
    val pairId: PairId,
    val baseAsset: String,
    val quoteAsset: String,
    val state: PositionState,
    val quantity: DecimalValue,
    val averageEntryPrice: DecimalValue,
    val realizedPnlIdr: DecimalValue,
    val unrealizedPnlIdr: DecimalValue,
    val horizon: TradingHorizon = TradingHorizon.TACTICAL,
    val setupType: SetupType = SetupType.NO_TRADE,
    val thesisSummary: String? = null,
    val highWatermarkPnlIdr: DecimalValue = DecimalValue.Zero,
    val openedAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class PortfolioSnapshot(
    val botId: BotId,
    val balances: List<BalanceSnapshot>,
    val openOrders: List<OrderSnapshot>,
    val positions: List<PositionSnapshot>,
    val totalEquityIdr: DecimalValue,
    val lastSyncedAt: Instant,
)

@Serializable
data class DailyRiskSnapshot(
    val openingEquityIdr: DecimalValue,
    val currentEquityIdr: DecimalValue,
    val realizedPnlIdr: DecimalValue,
    val unrealizedPnlIdr: DecimalValue,
    val drawdownPct: Double,
    val hardDailyLossLimitPct: Double,
    val hardStopTriggered: Boolean,
    val rebasePending: Boolean,
    val riskLadderLevel: RiskLadderLevel = RiskLadderLevel.NORMAL,
    val weeklyDrawdownPct: Double = 0.0,
    val lossStreakCount: Int = 0,
    val performanceDecayDetected: Boolean = false,
    val highWatermarkEquityIdr: DecimalValue = currentEquityIdr,
    val givebackPct: Double = 0.0,
    val profitProtectionStatus: ProfitProtectionStatus = ProfitProtectionStatus.INACTIVE,
)

@Serializable
data class ReconciliationReport(
    val state: ReconciliationState,
    val staleOpenOrders: List<ClientOrderId> = emptyList(),
    val unmatchedFills: List<FillId> = emptyList(),
    val balanceWarnings: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)
