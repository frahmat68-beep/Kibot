package com.kibot.core

data class LeaseProtocolConfig(
    val heartbeatIntervalSeconds: Int = 10,
    val leaseTtlSeconds: Int = 30,
)

data class PairSelectionPolicy(
    val minDailyQuoteVolumeIdr: Double = 15_000_000.0,
    val smallCapitalMinDailyQuoteVolumeIdr: Double = 2_200_000.0,
    val smallCapitalMinTop5DepthIdr: Double = 55_000.0,
    val smallCapitalMinTradeCount24h: Int = 110,
    val smallCapitalMaxSpreadPct: Double = 0.55,
    val smallCapitalMaxSlippagePct: Double = 0.50,
    val maxSpreadPct: Double = 0.75,
    val maxEstimatedSlippagePct: Double = 0.80,
    val minOrderBookStabilityScore: Double = 0.45,
    val minRecentTradeActivityScore: Double = 0.42,
    val minFillQualityScore: Double = 0.42,
    val minHistoricalExpectancyScore: Double = 0.40,
    val minTierAScore: Double = 0.74,
    val minTierBScore: Double = 0.58,
    val minHoldabilityForSwing: Double = 0.62,
    val minTrendScoreForSwing: Double = 0.60,
    val idealVolatilityPct: Double = 2.25,
    val maxAcceptedVolatilityPct: Double = 8.0,
    val minFeeAdjustedEdgeScore: Double = 0.30,
    val shortlistSize: Int = 24,
    val prefilterCandidatePoolSize: Int = 72,
    val speculativeMinShortTermReturnPct: Double = 0.8,
    val speculativeMaxShortTermReturnPct: Double = 28.0,
    val speculativeMinMediumTermReturnPct: Double = 1.8,
    val speculativeMinTradeActivityScore: Double = 0.62,
    val speculativeMinDepthScore: Double = 0.52,
    val speculativeMinHistoricalExpectancyScore: Double = 0.40,
)

data class RiskConfig(
    val hardDailyLossLimitPct: Double = 0.25,
    val warningDrawdownPct: Double = 0.05,
    val reduceSizeDrawdownPct: Double = 0.08,
    val defensiveDrawdownPct: Double = 0.12,
    val restrictedEntriesDrawdownPct: Double = 0.15,
    val stopNewEntriesDrawdownPct: Double = 0.18,
    val maxConcurrentPositions: Int = 10,
    val minimumCashReservePct: Double = 0.06,
    val defensiveCashReservePct: Double = 0.40,
    val attackCashReservePct: Double = 0.08,
    val maxPerPositionBudgetPct: Double = 0.80,
    val targetMinPositionBudgetIdr: Double = 12_000.0,
    val minSecondSlotRankingScore: Double = 0.70,
    val minSecondSlotOpportunityScore: Double = 0.60,
    val singlePositionBudgetBoostMultiplier: Double = 1.20,
    val multiPositionBudgetSplitMultiplier: Double = 0.98,
    val reducedSizeMultiplier: Double = 0.75,
    val defensiveSizeMultiplier: Double = 0.50,
    val attackSizeMultiplier: Double = 1.10,
    val dominantTierAReserveReliefPct: Double = 0.03,
    val dominantTierAMinCashReservePct: Double = 0.10,
    val speculativePocketMaxEquityPct: Double = 0.25,
    val loserHeatCautionPct: Double = 0.010,
    val loserHeatHardBrakePct: Double = 0.018,
    val top1DeployableConcentrationMaxPct: Double = 0.45,
    val top2DeployableConcentrationMaxPct: Double = 0.70,
    val rotationRankingGapMin: Double = 0.08,
    val blockEntriesBelowBatteryPct: Int = 18,
    val suggestTakeoverBelowBatteryPct: Int = 30,
)

data class MarketRegimePolicy(
    val healthyUptrendTrendScoreMin: Double = 0.58,
    val healthyUptrendOpportunityMin: Double = 0.62,
    val panicTrendScoreMax: Double = 0.24,
    val panicMicrostructureMax: Double = 0.40,
    val unclearMicrostructureMax: Double = 0.48,
)

data class BotModePolicy(
    val attackOpportunityScoreMin: Double = 0.78,
    val attackBotHealthScoreMin: Double = 0.82,
    val attackPerformanceScoreMin: Double = 0.65,
    val growthOpportunityScoreMin: Double = 0.55,
    val growthBotHealthScoreMin: Double = 0.62,
)

data class ProfitProtectionConfig(
    val weeklyProfitGuardPct: Double = 0.06,
    val givebackWarningPct: Double = 0.18,
    val coolingGivebackPct: Double = 0.30,
    val trailingGuardMultiplier: Double = 0.90,
    val coolingAggressionMultiplier: Double = 0.65,
)

data class WeeklyLearningConfig(
    val minimumTradeSamples: Int = 12,
    val minimumPairSamples: Int = 3,
    val maximumWhitelistPairs: Int = 3,
    val maximumBlacklistPairs: Int = 3,
    val maxAggressionDeltaPerReview: Double = 0.05,
    val maxSizeDeltaPerReview: Double = 0.05,
    val maxBiasDeltaPerReview: Double = 0.08,
)

data class StrategyExecutionConfig(
    val minOrderNotionalIdr: Double = 12_000.0,
    val entrySpendBufferPct: Double = 0.01,
    val growthMinRankingScore: Double = 0.67,
    val defensiveMinRankingScore: Double = 0.82,
    val attackMinRankingScore: Double = 0.64,
    val minExpectedOpportunityScore: Double = 0.54,
    val candidateCount: Int = 18,
    val productiveIdleRankingDelta: Double = 0.06,
    val productiveIdleOpportunityDelta: Double = 0.06,
    val marketEntryEnabled: Boolean = true,
    val marketEntryMinRankingScore: Double = 0.74,
    val marketEntryMinExpectedNetProfitPct: Double = 0.36,
    val marketEntryMaxSpreadPct: Double = 0.28,
    val marketEntryMaxSlippagePct: Double = 0.24,
    val marketEntryMinTradeActivityScore: Double = 0.72,
    val marketEntryMinTrendScore: Double = 0.68,
)
