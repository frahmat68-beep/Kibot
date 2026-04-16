package com.kicryp.shared.models

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class AdvisorySeverity {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
data class MarketOpportunitySnapshot(
    val regime: MarketRegime,
    val marketOpportunityScore: Double,
    val botHealthScore: Double,
    val performanceMomentumScore: Double,
    val edgeConfidence: EdgeConfidence,
    val tacticalBiasScore: Double,
    val swingBiasScore: Double,
    val opportunityAvailabilityScore: Double,
    val microstructureHealthScore: Double,
    val volatilityClusterScore: Double = 0.0,
    val rationale: List<String> = emptyList(),
)

@Serializable
data class BotModeSnapshot(
    val mode: BotMode,
    val edgeConfidence: EdgeConfidence,
    val aggressionScore: Double,
    val riskLadderLevel: RiskLadderLevel,
    val profitProtectionStatus: ProfitProtectionStatus,
    val tacticalBiasScore: Double,
    val swingBiasScore: Double,
    val tradingAllowed: Boolean,
    val rationale: List<String> = emptyList(),
)

@Serializable
data class ProfitProtectionSnapshot(
    val status: ProfitProtectionStatus,
    val highWatermarkEquityIdr: DecimalValue,
    val givebackPct: Double,
    val weeklyProfitPct: Double,
    val aggressionMultiplier: Double,
    val sizeMultiplier: Double,
    val rationale: List<String> = emptyList(),
)

@Serializable
data class CandidateOpportunity(
    val pairId: PairId,
    val tier: PairTier,
    val preferredHorizon: TradingHorizon,
    val rankingScore: Double,
    val marketOpportunityScore: Double,
    val expectedNetProfitabilityPct: Double,
    val holdabilityScore: Double,
    val speculativePocket: Boolean = false,
    val rationale: List<String> = emptyList(),
)

@Serializable
data class AiSupportCandidate(
    val pairId: PairId,
    val pairTier: PairTier,
    val preferredHorizon: TradingHorizon,
    val rankingScore: Double,
    val marketOpportunityScore: Double,
    val liquidityScore: Double,
    val spreadPct: Double,
    val estimatedSlippagePct: Double,
    val trendQualityScore: Double,
    val holdabilityScore: Double,
    val lastPrice: DecimalValue,
)

@Serializable
data class AiPairSupportHint(
    val pairId: PairId,
    val supportBias: Double = 0.0,
    val cautionBias: Double = 0.0,
    val cheapNominalWatch: Boolean = false,
    val rationale: String = "",
    val generatedAt: Instant,
)

@Serializable
data class CapitalDeploymentPlan(
    val allowNewEntries: Boolean,
    val allowRotation: Boolean,
    val maxActivePositions: Int,
    val suggestedPerPositionBudgetIdr: Double,
    val targetCashReservePct: Double,
    val capitalUtilizationTargetPct: Double,
    val preferredHorizon: TradingHorizon?,
    val candidates: List<CandidateOpportunity>,
    val rationale: List<String> = emptyList(),
)

@Serializable
data class LearningObservation(
    val observedAt: Instant,
    val pairId: PairId? = null,
    val setupType: SetupType = SetupType.NO_TRADE,
    val horizon: TradingHorizon = TradingHorizon.TACTICAL,
    val tradeTaken: Boolean,
    val realizedPnlPct: Double = 0.0,
    val expectedNetEdgePct: Double = 0.0,
    val slippagePct: Double = 0.0,
    val fillQualityScore: Double = 0.5,
    val avoidedBadTrade: Boolean = false,
    val missedQualifiedOpportunity: Boolean = false,
    val falseEntry: Boolean = false,
    val capitalUtilizationPct: Double = 0.0,
    val productiveUtilizationPct: Double = 0.0,
)

@Serializable
data class ExecutionAnomalySignature(
    val observedAt: Instant,
    val pairId: PairId,
    val setupType: SetupType,
    val anomalyGrade: String,
    val preFillLookbackMinutes: Int = 5,
    val vwapDistancePct: Double = 0.0,
    val orderBookImbalance: Double = 0.0,
    val cvdDivergenceScore: Double = 0.0,
    val tickFrequencyPerMinute: Double = 0.0,
    val realizedPnlPct: Double = 0.0,
    val expectedNetEdgePct: Double = 0.0,
    val confidenceScore: Double = 0.0,
    val rationale: List<String> = emptyList(),
)

@Serializable
data class WeeklyAdaptationPlan(
    val whitelistPairs: List<PairId> = emptyList(),
    val temporaryBlacklistPairs: List<PairId> = emptyList(),
    val setupBias: Map<String, Double> = emptyMap(),
    val activeHours: List<Int> = emptyList(),
    val aggressionMultiplierDelta: Double = 0.0,
    val sizeMultiplierDelta: Double = 0.0,
    val tacticalBiasDelta: Double = 0.0,
    val swingBiasDelta: Double = 0.0,
    val notes: List<String> = emptyList(),
)

@Serializable
data class WeeklyLearningSummary(
    val botId: BotId,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val tradeCount: Int = 0,
    val profitFactor: Double = 0.0,
    val maximumDrawdownPct: Double = 0.0,
    val bestPairs: List<PairId> = emptyList(),
    val worstPairs: List<PairId> = emptyList(),
    val bestSetups: List<SetupType> = emptyList(),
    val worstSetups: List<SetupType> = emptyList(),
    val bestHours: List<Int> = emptyList(),
    val worstHours: List<Int> = emptyList(),
    val falseEntryRate: Double,
    val noTradeQualityScore: Double,
    val avoidedBadTradesIndicator: Double,
    val capitalUtilizationPct: Double,
    val productiveUtilizationPct: Double,
    val missedOpportunityRate: Double,
    val tacticalExpectancy: Double,
    val swingExpectancy: Double,
    val adaptationPlan: WeeklyAdaptationPlan,
    val notes: List<String> = emptyList(),
    val executionSignatures: List<ExecutionAnomalySignature> = emptyList(),
)

@Serializable
data class LearningHint(
    val hintCode: String,
    val severity: AdvisorySeverity,
    val source: String,
    val summary: String,
    val rationale: List<String> = emptyList(),
    val generatedAt: Instant,
)

@Serializable
data class BotUpdateRecommendation(
    val botId: BotId,
    val scope: String = "update_recommendation",
    val versionTag: String,
    val reasonCode: String,
    val severity: AdvisorySeverity,
    val title: String,
    val summary: String,
    val source: String,
    val confidenceScore: Double,
    val evidence: Map<String, Double> = emptyMap(),
    val recommendedActions: List<String> = emptyList(),
    val createdByDeviceId: DeviceId? = null,
    val createdAt: Instant,
)

@Serializable
data class RuntimeIntelligenceUpdate(
    val botId: BotId,
    val deviceId: DeviceId,
    val term: LeaseTerm,
    val currentPair: PairId? = null,
    val operatingMode: BotMode,
    val edgeConfidence: EdgeConfidence,
    val aggressionScore: Double,
    val riskLadderLevel: RiskLadderLevel,
    val profitProtectionStatus: ProfitProtectionStatus,
    val marketRegime: MarketRegime,
    val distrustLabels: List<DistrustLabel> = emptyList(),
    val activeCandidatePairs: List<PairId> = emptyList(),
    val marketOpportunityScore: Double,
    val botHealthScore: Double,
    val performanceMomentumScore: Double,
    val safeModeReason: String? = null,
)
