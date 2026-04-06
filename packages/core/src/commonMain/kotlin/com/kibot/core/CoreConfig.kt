package com.kibot.core

data class LeaseProtocolConfig(
    val heartbeatIntervalSeconds: Int = 10,
    val leaseTtlSeconds: Int = 30,
)

data class PairSelectionPolicy(
    // MICRO-CAP AGGRESSIVE: Lowered volume minimums for small coins
    val minDailyQuoteVolumeIdr: Double = 2_000_000.0,  // FIX: Turun dari 5M → 2M untuk micro-cap coins
    val smallCapitalMinDailyQuoteVolumeIdr: Double = 150_000.0,
    val smallCapitalMinTop5DepthIdr: Double = 5_000.0,
    val smallCapitalMinTradeCount24h: Int = 8,
    // MICRO-CAP AGGRESSIVE: Widened spread/slippage tolerance
    val smallCapitalMaxSpreadPct: Double = 2.50,
    val smallCapitalMaxSlippagePct: Double = 2.80,
    val maxSpreadPct: Double = 1.80,
    val hardSpreadVetoPct: Double = 1.50,
    val maxEstimatedSlippagePct: Double = 1.80,
    val minOrderBookStabilityScore: Double = 0.34,
    val minRecentTradeActivityScore: Double = 0.24,
    val minFillQualityScore: Double = 0.28,
    val minHistoricalExpectancyScore: Double = 0.32,
    val minTierAScore: Double = 0.72,
    val minTierBScore: Double = 0.52,
    val minHoldabilityForSwing: Double = 0.62,
    val minTrendScoreForSwing: Double = 0.60,
    val idealVolatilityPct: Double = 2.25,
    val maxAcceptedVolatilityPct: Double = 8.0,
    val minFeeAdjustedEdgeScore: Double = 0.24,
    val estimatedMakerRoundTripCostPct: Double = 0.52,
    val estimatedTakerRoundTripCostPct: Double = 0.78,
    val feeSafetyBufferPct: Double = 0.10,
    val strongNetEdgePct: Double = 1.60,
    val shortlistSize: Int = 128,
    val prefilterCandidatePoolSize: Int = 420,
    val blockedBaseAssets: Set<String> = setOf("usdt", "usdc", "indr", "fdusd", "tusd", "busd", "toko"),
    val stagnantShortTermReturnPctMax: Double = 0.60,
    val stagnantMediumTermReturnPctMax: Double = 1.20,
    val speculativeMinShortTermReturnPct: Double = 3.4,
    val speculativeMaxShortTermReturnPct: Double = 220.0,
    val speculativeMinMediumTermReturnPct: Double = 0.8,
    val speculativeMinTradeActivityScore: Double = 0.42,
    val speculativeMinDepthScore: Double = 0.18,
    val speculativeMinHistoricalExpectancyScore: Double = 0.10,
    val minTickFrequencyPerMinute: Double = 1.0,
    val zombieTickFrequencyPerMinute: Double = 0.25,
    val zombieDailyVolumeIdr: Double = 2_500_000.0,
    val dinosaurDailyVolumeIdr: Double = 8_000_000.0,
    val dangerousVwapExtensionPct: Double = 3.8,
    val healthyVwapExtensionPct: Double = 1.5,
    val rsiOverboughtThreshold: Double = 72.0,
    val rsiOversoldThreshold: Double = 31.0,
    val strongOrderBookImbalance: Double = 0.55,
    val toxicFlowHardBlockScore: Double = 0.82,
    val toxicFlowCautionScore: Double = 0.62,
    val kellyFractionCap: Double = 0.35,
)

data class PairSelectionContext(
    val userBalanceIdr: Double = 0.0,
    val availableCashIdr: Double = 0.0,
    val minimumExecutableNotionalIdr: Double = 10_000.0,
    val basketCount: Int = 1,
    val maxSpreadPct: Double = 2.0,
    val leadSectorFamily: String? = null,
    val leadPairId: String? = null,
    val leadMomentumScore: Double = 0.0,
    val leadSectorHotnessScore: Double = 0.0,
    val leadVolumeVelocityScore: Double = 0.0,
    val urgentEntryMode: Boolean = false,
    val leadLagEnabled: Boolean = false,
    // DUAL ENGINE: Bypass flags for Barbarian Anomaly Engine
    val bypassSpreadCheck: Boolean = false,
    val bypassVetoService: Boolean = false,
    val bypassRankingFloor: Boolean = false,
    val engineId: String? = null,  // "macro_follower" or "barbarian_anomaly"
)

data class RiskConfig(
    // MICRO-CAP: Tighter loss limits for protection
    val hardDailyLossLimitPct: Double = 0.03,
    val hardRealizedLossLimitIdr: Double = 10_000.0,
    val maxDailyTradeActions: Int = 999,  // EMERGENCY FIX: Unlimited trades (was 24) - if profitable, keep trading!
    val maxDailyRoundTrips: Int = 999,    // EMERGENCY FIX: Unlimited round-trips (was 12)
    val dailyProfitLockPct: Double = 999.0,  // EMERGENCY FIX: NEVER stop on profit (was 0.010 = 1%)
    val dailyProfitLockRankingScore: Double = 0.99,
    val dailyProfitLockOpportunityScore: Double = 0.90,
    val dailyProfitLockConfidenceFloor: Double = 0.99,
    val warningDrawdownPct: Double = 999.0,  // EMERGENCY FIX: Never stop on drawdown - bot must stay active!
    val reduceSizeDrawdownPct: Double = 999.0,  // EMERGENCY FIX: Don't reduce size
    val defensiveDrawdownPct: Double = 999.0,   // EMERGENCY FIX: Don't go defensive
    val restrictedEntriesDrawdownPct: Double = 999.0,  // EMERGENCY FIX: Never restrict entries
    val stopNewEntriesDrawdownPct: Double = 999.0,     // EMERGENCY FIX: NEVER stop trading!
    val maxConcurrentPositions: Int = 8,  // EMERGENCY FIX: Increased from 6 to 8 (user has 2 stuck, needs rotation space)
    val minimumCashReservePct: Double = 0.01,
    val defensiveCashReservePct: Double = 0.02,  // EMERGENCY FIX: Reduce reserve, maximize deployment (was 0.40 = 40%!)
    val attackCashReservePct: Double = 0.01,
    val maxPerPositionBudgetPct: Double = 0.98,
    // MICRO-CAP: Lower minimum position sizes
    val targetMinPositionBudgetIdr: Double = 15_000.0,
    val minSecondSlotRankingScore: Double = 0.66,
    val minSecondSlotOpportunityScore: Double = 0.56,
    val singlePositionBudgetBoostMultiplier: Double = 1.55,
    val multiPositionBudgetSplitMultiplier: Double = 1.08,
    val reducedSizeMultiplier: Double = 0.75,
    val defensiveSizeMultiplier: Double = 0.50,
    val attackSizeMultiplier: Double = 1.10,
    val dominantTierAReserveReliefPct: Double = 0.04,
    val dominantTierAMinCashReservePct: Double = 0.06,
    val speculativePocketMaxEquityPct: Double = 0.25,
    val loserHeatCautionPct: Double = 0.010,
    val loserHeatHardBrakePct: Double = 0.018,
    val top1DeployableConcentrationMaxPct: Double = 0.45,
    val top2DeployableConcentrationMaxPct: Double = 0.70,
    val dominantAllInRankingScoreMin: Double = 0.84,
    val dominantAllInOpportunityScoreMin: Double = 0.74,
    val dominantAllInGapMin: Double = 0.09,
    val dominantAllInMaxAllocationPct: Double = 0.75,
    val rotationRankingGapMin: Double = 0.05,
    val rotationMinNetUpgradePct: Double = 1.20,
    val rotationMinClearProfitPct: Double = 0.60,
    val rotationMinClearProfitIdr: Double = 80.0,
    val blockEntriesBelowBatteryPct: Int = 18,
    val suggestTakeoverBelowBatteryPct: Int = 30,
)

data class MarketRegimePolicy(
    val healthyUptrendTrendScoreMin: Double = 0.58,
    val healthyUptrendOpportunityMin: Double = 0.62,
    val panicTrendScoreMax: Double = 0.24,
    val panicMicrostructureMax: Double = 0.40,
    val unclearMicrostructureMax: Double = 0.48,
    val elevatedVolatilityClusterMin: Double = 0.64,
    val balancedMarketMakingSpreadMinPct: Double = 0.12,
    val balancedMarketMakingSpreadMaxPct: Double = 1.10,
    val balancedMarketMakingMaxStatisticalStretch: Double = 0.72,
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
    val minimumTradeSamples: Int = 8,
    val minimumPairSamples: Int = 2,
    val maximumWhitelistPairs: Int = 3,
    val maximumBlacklistPairs: Int = 4,
    val maxAggressionDeltaPerReview: Double = 0.03,
    val maxSizeDeltaPerReview: Double = 0.03,
    val maxBiasDeltaPerReview: Double = 0.06,
)

data class StrategyExecutionConfig(
    val referenceQuoteAsset: String = "idr",
    val minOrderNotionalIdr: Double = 15_000.0,
    val entrySpendBufferPct: Double = 0.002,
    // ANTI-PENAKUT FIX: Turunkan threshold entry agar bot lebih berani masuk
    val growthMinRankingScore: Double = 0.50,      // Was 0.63 - terlalu tinggi, banyak peluang terlewat
    val defensiveMinRankingScore: Double = 0.55,   // Was 0.82 - terlalu ketat, bot jadi penakut
    val attackMinRankingScore: Double = 0.40,      // Was 0.52 - attack mode harus lebih agresif
    val minExpectedOpportunityScore: Double = 0.42,
    val candidateCount: Int = 160,
    val maxExecutableEntriesPerCycle: Int = 4,
    val productiveIdleRankingDelta: Double = 0.09,
    val productiveIdleOpportunityDelta: Double = 0.08,
    val minExpectedNetProfitIdr: Double = 100.0,  // FIX: Turun dari 200 → 100 untuk account kecil
    val minExpectedNetProfitIdrSpeculative: Double = 120.0,  // FIX: Turun dari 220 → 120
    val minProfitToCostMultiplier: Double = 1.10,
    val minProfitAfterFeesBufferIdr: Double = 55.0,
    val minNetEdgeAfterCostsBufferPct: Double = 0.0,
    val liquidityImpactReducerEnabled: Boolean = true,
    val liquidityImpactDepthCoverageRatio: Double = 0.45,
    val liquidityImpactMaxBudgetToBidDepthRatio: Double = 0.50,
    val marketEntryEnabled: Boolean = true,
    val marketEntryMinRankingScore: Double = 0.46,
    val marketEntryMinExpectedNetProfitPct: Double = 0.30,
    val marketEntryMaxSpreadPct: Double = 0.90,
    val marketEntryMaxSlippagePct: Double = 0.82,
    val marketEntryTightSpreadPct: Double = 0.68,
    val marketEntryTightSlippagePct: Double = 0.60,
    val marketEntryMinTradeActivityScore: Double = 0.42,
    val marketEntryMinTrendScore: Double = 0.48,
    val sidewaysMakerModeEnabled: Boolean = true,
    val sidewaysMakerModeMinSpreadPct: Double = 0.12,
    val sidewaysMakerModeMaxSpreadPct: Double = 1.10,
    val sidewaysMakerModeMaxZScoreAbs: Double = 2.6,
    val sidewaysMakerModeMaxVwapExtensionPct: Double = 1.4,
    val sidewaysMakerModeMinTradeActivityScore: Double = 0.34,
    val pairReentryCooldownSeconds: Int = 12,
    val breakoutAggressiveEntryMinRankingScore: Double = 0.40,
    val breakoutAggressiveEntryMinExpectedNetProfitPct: Double = 0.22,
    val breakoutAggressiveEntryMinShortTermReturnPct: Double = 1.5,
    val breakoutAggressiveEntryMinMediumTermReturnPct: Double = 0.35,
    val executionAllowedQuoteAssets: Set<String> = setOf("idr"),
)

// DUAL ENGINE CONFIG: Capital Allocation & Engine-Specific Parameters
data class DualEngineConfig(
    // Capital Allocation (70% Macro / 30% Barbarian)
    val macroFollowerAllocationPct: Double = 0.70,
    val barbarianAnomalyAllocationPct: Double = 0.30,
    
    // MACRO FOLLOWER ENGINE (70%) - Disciplined BTC/ETH lead-lag follower
    val macroCorrelatedPairs: Set<String> = setOf(
        // Meme coins yang follow BTC
        "doge_idr", "pepe_idr", "shib_idr", "floki_idr", "bonk_idr", "wif_idr",
        // L1/L2 yang follow ETH
        "sol_idr", "avax_idr", "near_idr", "ada_idr", "matic_idr", 
        "arb_idr", "op_idr", "trx_idr", "xlm_idr", "ont_idr", "plpa_idr"
    ),
    val macroMaxVwapExtensionForEntry: Double = 0.5,  // Don't buy above VWAP
    val macroPreferLimitOrders: Boolean = true,  // Use Maker for low fees
    val macroTrailingStopInitialPct: Double = 2.5,  // Wider initial stop
    val macroTrailingDistancePct: Double = 1.5,  // More room to breathe
    val macroMinRankingScore: Double = 0.55,  // Still selective but not paranoid
    
    // BARBARIAN ANOMALY ENGINE (30%) - Ultra-aggressive pump chaser
    val barbarianMaxSpreadPct: Double = 4.0,  // Allow up to 4% spread (bypass normal 1.8% limit)
    val barbarianMaxSlippagePct: Double = 3.5,  // High slippage tolerance for instant entry
    val barbarianMinTickVelocity: Double = 3.0,  // HARD BLOCK: Min 3 ticks/minute (anti-stagnant)
    val barbarianMinPriceVelocityPct1m: Double = 0.8,  // Must have >0.8% price move in 1 minute
    val barbarianMinVolumeAnomalyMultiplier: Double = 2.5,  // Volume must be 2.5x average
    val barbarianMinPriceBreakoutPct5m: Double = 2.0,  // Or 2%+ price move in 5 minutes
    val barbarianForceMarketOrders: Boolean = true,  // Always use Taker for speed
    val barbarianTrailingStopInitialPct: Double = 1.5,  // Tight stop for quick exit
    val barbarianTrailingDistancePct: Double = 0.8,  // FIX: Longgarkan dari 0.5 → 0.8 (anti-premature exit)
    val barbarianTrailingActivationProfitPct: Double = 0.5,  // FIX: Naikkan dari 0.3 → 0.5 (wait for real profit)
    val barbarianMaxHoldSeconds: Int = 180,  // Force exit after 3 minutes max
    val barbarianDecayVelocityMinTicks: Double = 2.0,  // FIX: Min 2 ticks/min to consider "active"
    val barbarianMinRankingScore: Double = 0.30,  // Very low floor - let the pump speak
    val barbarianBypassAllGuardrails: Boolean = true,  // Master switch to skip spread/veto/ranking checks
    
    // Engine Selection Criteria
    val preferBarbarianOnHighVolatility: Boolean = true,
    val volatilityThresholdForBarbarian: Double = 3.5,  // Switch to Barbarian if volatility >3.5%
)
