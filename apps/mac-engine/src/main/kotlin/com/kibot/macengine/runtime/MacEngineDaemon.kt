package com.kibot.macengine.runtime

import com.kibot.aisupport.GeminiSupportCoordinator
import com.kibot.aisupport.MultiAIClient
import com.kibot.core.CapitalDeploymentEngine
import com.kibot.core.ChartAnalyzer
import com.kibot.core.PumpDetector
import com.kibot.core.LossPreventionSystem
import com.kibot.core.SharedPositionTracker
import com.kibot.core.TradeLedger
import com.kibot.core.TrinityHeartbeatMonitor
import com.kibot.core.LatePumpEntryStrategy
import com.kibot.core.PositionStrategy
import com.kibot.core.ControlPlaneGateway
import com.kibot.core.ExchangeGateway
import com.kibot.core.MarketBuyImpactEstimate
import com.kibot.core.HealthAdvisor
import com.kibot.core.LeaseCoordinator
import com.kibot.core.LeaseProtocolConfig
import com.kibot.core.LiveLearningReviewBuilder
import com.kibot.core.LiveRolloutGuard
import com.kibot.core.LiveExecutionCoordinator
import com.kibot.core.MarketRegimeAnalyzer
import com.kibot.core.PairSelectionPolicy
import com.kibot.core.PairSelector
import com.kibot.core.ReconciliationService
import com.kibot.core.RiskConfig
import com.kibot.core.RiskEngine
import com.kibot.core.SituationalLearningEngine
import com.kibot.core.StrategyExecutionConfig
import com.kibot.core.StrategyOrchestrator
import com.kibot.core.TradeAutomationConfig
import com.kibot.core.TradeAutomationCoordinator
import com.kibot.macengine.config.ExchangeKind
import com.kibot.macengine.config.HyperAggressiveConfig
import com.kibot.macengine.config.MacRuntimeConfig
import com.kibot.macengine.state.MacStateRepository
import com.kibot.shared.models.AuditLogRecord
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotStateSnapshot
import com.kibot.shared.models.CommandEnvelope
import com.kibot.shared.models.CommandStatus
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.DeviceDescriptor
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DeviceRole
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.EngineHeartbeatSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.HealthStatus
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.ReconciliationReport
import com.kibot.shared.models.ReconciliationState
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.SyncHealth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Duration
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


private fun buildStrategyOrchestrator(exchangeKind: ExchangeKind): StrategyOrchestrator {
    val pairPolicy = exchangePairSelectionPolicy(exchangeKind)
    val riskConfig = exchangeRiskConfig(exchangeKind)
    val executionConfig = exchangeExecutionConfig(exchangeKind)
    return StrategyOrchestrator(
        pairSelector = PairSelector(pairPolicy),
        regimeAnalyzer = MarketRegimeAnalyzer(pairPolicy = pairPolicy),
        healthAdvisor = HealthAdvisor(riskConfig),
        riskEngine = RiskEngine(config = riskConfig),
        deploymentEngine = CapitalDeploymentEngine(config = riskConfig),
        executionConfig = executionConfig,
        riskConfig = riskConfig,
    )
}

private fun buildTradeAutomationCoordinator(exchangeKind: ExchangeKind): TradeAutomationCoordinator {
    return TradeAutomationCoordinator(
        executionConfig = exchangeExecutionConfig(exchangeKind),
        config = exchangeTradeAutomationConfig(exchangeKind),
    )
}

private fun exchangePairSelectionPolicy(exchangeKind: ExchangeKind): PairSelectionPolicy = when (exchangeKind) {
    ExchangeKind.INDODAX -> PairSelectionPolicy(
        shortlistSize = 260,
        prefilterCandidatePoolSize = 1200,
        minDailyQuoteVolumeIdr = 75_000.0,
        smallCapitalMinDailyQuoteVolumeIdr = 4_500.0,
        smallCapitalMinTop5DepthIdr = 1_500.0,
        smallCapitalMinTradeCount24h = 110,
        smallCapitalMaxSpreadPct = 1.45,
        smallCapitalMaxSlippagePct = 1.25,
        speculativeMinShortTermReturnPct = 1.8,
        speculativeMinMediumTermReturnPct = 0.35,
    )
    ExchangeKind.BINANCE_SPOT -> PairSelectionPolicy(
        minDailyQuoteVolumeIdr = 25_000.0,
        smallCapitalMinDailyQuoteVolumeIdr = 1_200.0,
        smallCapitalMinTop5DepthIdr = 350.0,
        smallCapitalMinTradeCount24h = 80,
        smallCapitalMaxSpreadPct = 0.95,
        smallCapitalMaxSlippagePct = 0.85,
        maxSpreadPct = 0.60,
        maxEstimatedSlippagePct = 0.55,
        minFeeAdjustedEdgeScore = 0.18,
        estimatedMakerRoundTripCostPct = 0.16,
        estimatedTakerRoundTripCostPct = 0.28,
        feeSafetyBufferPct = 0.04,
        strongNetEdgePct = 0.90,
        shortlistSize = 220,
        prefilterCandidatePoolSize = 900,
        blockedBaseAssets = setOf("usdt", "usdc", "indr", "fdusd", "tusd", "busd", "toko"),
        speculativeMinShortTermReturnPct = 2.2,
        speculativeMinMediumTermReturnPct = 0.45,
    )
}

private fun exchangeRiskConfig(exchangeKind: ExchangeKind): RiskConfig = when (exchangeKind) {
    ExchangeKind.INDODAX -> RiskConfig(
        hardDailyLossLimitPct = 0.03,
        hardRealizedLossLimitIdr = 15_000.0,
        warningDrawdownPct = 0.02,
        reduceSizeDrawdownPct = 0.04,
        defensiveDrawdownPct = 0.06,
        restrictedEntriesDrawdownPct = 0.08,
        stopNewEntriesDrawdownPct = 0.10,
    )
    ExchangeKind.BINANCE_SPOT -> RiskConfig(
        targetMinPositionBudgetIdr = 8.0,
        minimumCashReservePct = 0.02,
        attackCashReservePct = 0.015,
        rotationMinClearProfitIdr = 120.0,
        dominantTierAMinCashReservePct = 0.03,
    )
}

private fun exchangeExecutionConfig(exchangeKind: ExchangeKind): StrategyExecutionConfig = when (exchangeKind) {
    ExchangeKind.INDODAX -> StrategyExecutionConfig(
        minOrderNotionalIdr = 10_000.0,
    )
    ExchangeKind.BINANCE_SPOT -> StrategyExecutionConfig(
        referenceQuoteAsset = "usdt",
        minOrderNotionalIdr = 7.5,
        candidateCount = 260,
        maxExecutableEntriesPerCycle = 6,
        minExpectedNetProfitIdr = 0.14,
        minExpectedNetProfitIdrSpeculative = 0.18,
        minProfitAfterFeesBufferIdr = 0.04,
        marketEntryMinExpectedNetProfitPct = 0.16,
        marketEntryMaxSpreadPct = 0.55,
        marketEntryMaxSlippagePct = 0.45,
        marketEntryTightSpreadPct = 0.38,
        marketEntryTightSlippagePct = 0.30,
        breakoutAggressiveEntryMinExpectedNetProfitPct = 0.15,
        executionAllowedQuoteAssets = setOf("usdt"),
    )
}

private fun exchangeTradeAutomationConfig(exchangeKind: ExchangeKind): TradeAutomationConfig = when (exchangeKind) {
    ExchangeKind.INDODAX -> TradeAutomationConfig(
        minTrackedPositionValueIdr = 9_000.0,
        partialTakeProfitMinRemainingNotionalIdr = 10_000.0,
        partialTakeProfitMinPositionNotionalIdr = 10_000.0,
    )
    ExchangeKind.BINANCE_SPOT -> TradeAutomationConfig(
        minTrackedPositionValueIdr = 7.5,
        estimatedRoundTripCostPct = 0.20,
        adaptiveFeeFloorPct = 0.08,
        maxAdaptiveRoundTripCostPct = 0.60,
        minMeaningfulNonEmergencyExitProfitIdr = 0.20,
        rotationMinNetUpgradePct = 1.10,
        partialTakeProfitMinRemainingNotionalIdr = 7.5,
        partialTakeProfitMinPositionNotionalIdr = 10.0,
        partialTakeProfitMinNetSurplusIdr = 0.20,
    )
}

class MacEngineDaemon(
    private val repository: MacStateRepository,
    private val controlPlane: ControlPlaneGateway,
    private val exchange: ExchangeGateway,
    private val config: MacRuntimeConfig,
    private val leaseCoordinator: LeaseCoordinator = LeaseCoordinator(
        LeaseProtocolConfig(
            heartbeatIntervalSeconds = 10,
            leaseTtlSeconds = config.leaseTtlSeconds,
        ),
    ),
    private val reconciliationService: ReconciliationService = ReconciliationService(),
    private val healthAdvisor: HealthAdvisor = HealthAdvisor(exchangeRiskConfig(config.exchangeKind)),
    private val strategyOrchestrator: StrategyOrchestrator = buildStrategyOrchestrator(config.exchangeKind),
    private val liveLearningReviewBuilder: LiveLearningReviewBuilder = LiveLearningReviewBuilder(),
    private val liveRolloutGuard: LiveRolloutGuard = LiveRolloutGuard(),
    private val liveExecutionCoordinator: LiveExecutionCoordinator = LiveExecutionCoordinator(),
    private val situationalLearningEngine: SituationalLearningEngine = SituationalLearningEngine(),
    private val tradeAutomationCoordinator: TradeAutomationCoordinator = buildTradeAutomationCoordinator(config.exchangeKind),
    private val aiSupportCoordinator: GeminiSupportCoordinator? = null,
    private val multiAiCoordinator: MultiAIClient? = MultiAIClient(),
) {
    private val chartAnalyzer = ChartAnalyzer()
    private val pumpDetector = PumpDetector()  // NEW: Pump detection for 100%+ moves
    private val lossPreventionSystem = LossPreventionSystem()  // NEW: Aggressive loss prevention
    
    // Trinity Communication & Learning Systems
    private val sharedPositionTracker = SharedPositionTracker()  // All bots know what KiDax holds
    private val tradeLedger = TradeLedger()  // Track every trade for learning
    private val heartbeatMonitor = TrinityHeartbeatMonitor()  // Monitor bot health
    private val latePumpEntry = LatePumpEntryStrategy()  // Enter pumps that already started
    
    private val onlyRuntimeLogPrefixes = setOf("EXECUTION_BUY", "EXECUTION_SELL", "WHY_NOT_BUY")
    private data class CapitalAwareness(
        val totalEquityIdr: Double,
        val lowCapital: Boolean,
        val signalOnlyMode: Boolean,
        val note: String,
    )
    private data class TargetEnforcementMemory(
        val memoryDate: LocalDate? = null,
        val lastHourlyWindowIndex: Int = 0,
        val consecutiveHourlyMisses: Int = 0,
        val lastHourlyShortfallPct: Double = 0.0,
        val lastCheckpointWindowIndex: Int = 0,
        val consecutiveCheckpointMisses: Int = 0,
        val lastCheckpointShortfallPct: Double = 0.0,
    )

    @Serializable
    private data class MonthlyPnlAnchorSnapshot(
        val botId: String,
        val deviceId: String,
        val monthKey: String,
        val anchorEquityIdr: Double,
        val observedAtEpochMs: Long,
        val reason: String = "auto_month_reset",
    )

    @Serializable
    private data class PnlResetAnchorSnapshot(
        val botId: String,
        val deviceId: String,
        val anchorEquityIdr: Double,
        val observedAtEpochMs: Long,
        val reason: String = "manual_topup_reset",
    )

    @Serializable
    private data class LeadLagCalloutPayload(
        val kind: String = "lead_lag_breakout",
        val msgType: String = "DETECTOR_HIT",
        val traceId: String,
        val senderBotId: String,
        val pairId: String,
        val trend: String = "UP",
        val detectedAtEpochMs: Long,
        val confidence: Double,
        val expectedNetPct: Double,
        val shortTermReturnPct: Double,
        val mediumTermReturnPct: Double,
        val tradeActivityScore: Double,
        val forceRotation: Boolean = true,
        val sentAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val payload: JsonObject? = null,
    )

    private data class ActiveLeadLagCallout(
        val traceId: String,
        val senderBotId: String,
        val pairId: com.kibot.shared.models.PairId,
        val trend: String,
        val msgType: String,
        val confidence: Double,
        val expectedNetPct: Double,
        val shortTermReturnPct: Double,
        val coinClass: CoinClass,
        val sentAtEpochMs: Long,
        val receivedAt: Instant,
        val forceRotation: Boolean,
        val expiresAt: Instant,
    )

    private data class LocalTrailingSnapshot(
        val pair: com.kibot.shared.models.PairId,
        val entryPrice: Double,
        val peakPrice: Double,
        val floorPrice: Double,
        val currentBid: Double,
        val dynamicTrailingStopPct: Double,
        val armed: Boolean,
        val retroactivePeakApplied: Boolean = false,
    )

    @Serializable
    private data class ActivePositionWire(
        val pairId: String,
        val entryPrice: Double,
        val currentPrice: Double,
        val pnlPct: Double,
        val pnlIdr: Double,
        val quantity: Double,
        val notionalIdr: Double,
    )

    @Serializable
    private data class ActivePositionsPayload(
        val kind: String = "trinity_state",
        val msgType: String,
        val senderBotId: String,
        val sentAtEpochMs: Long,
        val idrFree: Double,
        val totalEquityIdr: Double,
        val positions: List<ActivePositionWire>,
    )

    @Serializable
    private data class TrinityHeartbeatPayload(
        val kind: String = "trinity_state",
        val msgType: String = "HEARTBEAT",
        val senderBotId: String,
        val sentAtEpochMs: Long,
        val activePair: String? = null,
        val safeModeArmed: Boolean = false,
    )

    private data class UdpExecutionPrewarm(
        val traceId: String,
        val pairId: com.kibot.shared.models.PairId,
        val armedAt: Instant,
        val expiresAt: Instant,
        val msgType: String,
    )

    private enum class UdpBinaryMessageType(val code: Byte, val wireMsgType: String) {
        HEARTBEAT(1, "HEARTBEAT"),
        DETECTOR_HIT(10, "DETECTOR_HIT"),
        INSTANT_BUY_ANOMALY(11, "INSTANT_BUY_ANOMALY"),
        VETO_APPROVED(12, "VETO_APPROVED"),
        VETO_REJECTED(13, "VETO_REJECTED"),
        VETO_SELL_CONFIRMED(14, "VETO_SELL_CONFIRMED"),
        EMERGENCY_VETO_SELL(15, "EMERGENCY_VETO_SELL"),
        SELL_WALL_SURGE(16, "SELL_WALL_SURGE"),
        MOMENTUM_LOSS(17, "MOMENTUM_LOSS"),
        ORDERBOOK_COLLAPSE(18, "ORDERBOOK_COLLAPSE"),
        UNKNOWN(127, "UNKNOWN");

        companion object {
            fun fromCode(code: Byte): UdpBinaryMessageType = entries.firstOrNull { it.code == code } ?: UNKNOWN
            fun fromMsgType(msgType: String): UdpBinaryMessageType = entries.firstOrNull {
                it.wireMsgType.equals(msgType, ignoreCase = true)
            } ?: UNKNOWN
        }
    }

    private data class DecodedUdpPacket(
        val heartbeat: TrinityHeartbeatPayload? = null,
        val leadLag: LeadLagCalloutPayload? = null,
        val senderBotId: String? = null,
        val sequenceId: Int? = null,
        val dedupKey: String? = null,
        val binary: Boolean = false,
    )

    @Serializable
    private data class LocalManagedPositionState(
        val pairId: String,
        val baseAsset: String,
        val quantity: String,
        val averageEntryPrice: String,
        val breakEvenPrice: String,
        val stopPrice: String,
        val takeProfitPrice: String,
        val unrealizedPnlPct: Double,
        val openedAtEpochMs: Long,
        val updatedAtEpochMs: Long,
    )

    @Serializable
    private data class LocalPositionStateSnapshot(
        val botId: String,
        val deviceId: String,
        val observedAtEpochMs: Long,
        val orders: List<com.kibot.shared.models.OrderSnapshot>,
        val managedPositions: List<LocalManagedPositionState>,
    )

    private data class HistoricalPeakCacheEntry(
        val peakPrice: Double,
        val fetchedAtEpochMs: Long,
    )
    private data class CandleHistoryGuardCacheEntry(
        val candleCount: Int,
        val activeCandleCount: Int,
        val distinctCloseBuckets: Int,
        val rangePct: Double,
        val lastClose: Double,
        val dominantCloseShare: Double,
        val directionFlipRate: Double,
        val higherHighRatio: Double,
        val higherLowRatio: Double,
        val closingProgressRatio: Double,
        val netProgressPct: Double,
        val fetchedAtEpochMs: Long,
    )

    private data class MultiTimeframeQuoteCacheEntry(
        val quote: com.kibot.shared.models.MarketQuote,
        val fetchedAtEpochMs: Long,
    )

    private data class OrderBookPulseSample(
        val atEpochMs: Long,
        val imbalance: Double,
        val bidDepthIdr: Double,
        val askDepthIdr: Double,
        val stabilityScore: Double,
    )

    @Serializable
    private data class ToxicFlowStateEntry(
        val pairId: String,
        val stopLossHits: Int = 0,
        val lastStopLossAtEpochMs: Long = 0L,
        val consecutiveSweepHits: Int = 0,
        val quarantinedUntilEpochMs: Long = 0L,
        val lastReason: String = "",
    )

    @Serializable
    private data class ToxicFlowStateSnapshot(
        val botId: String,
        val deviceId: String,
        val observedAtEpochMs: Long,
        val entries: List<ToxicFlowStateEntry>,
    )

    private data class TrinityPendingSignal(
        val traceId: String,
        val pairId: String,
        val trend: String,
        val msgType: String,
        val senderBotId: String,
        val detectedAtEpochMs: Long,
        val sentAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val confidence: Double,
        val expectedNetPct: Double,
        val forceRotation: Boolean,
    )

    private data class LeadLagClassStats(
        val accepted: Int = 0,
        val rejectedClassDisabled: Int = 0,
        val rejectedTooOld: Int = 0,
        val entries: Int = 0,
        val exits: Int = 0,
    )

    private data class PairMicroPulseSample(
        val atEpochMs: Long,
        val midPrice: Double,
        val quoteVolume24h: Double,
    )

    private data class HyperAggressiveTracker(
        val hoursElapsed: Double,
        val accumulatedPnlPct: Double,
        val hourlyPnlPct: Double,
        val targetHourlyPct: Double,
        val hungry: Boolean,
    )

    private enum class HyperTargetKind {
        SEXY,
        SUPER_SEXY,
        V_SHAPE_BOUNCE,
        WALL_SMASH,
    }

    private data class HyperTargetCandidate(
        val pairId: com.kibot.shared.models.PairId,
        val kind: HyperTargetKind,
        val score: Double,
    )

    @Serializable
    private data class LeadLagTelemetryEvent(
        val event: String,
        val traceId: String,
        val pairId: String,
        val coinClass: String,
        val sourceBotId: String,
        val targetBotId: String?,
        val t0DetectedAtEpochMs: Long? = null,
        val t1UdpSentAtEpochMs: Long? = null,
        val t2UdpReceivedAtEpochMs: Long? = null,
        val t3BuySubmittedAtEpochMs: Long? = null,
        val t4SellSubmittedAtEpochMs: Long? = null,
        val buyPrice: Double? = null,
        val sellPrice: Double? = null,
        val quantity: Double? = null,
        val slippagePct: Double? = null,
        val pnlIdr: Double? = null,
        val transportLatencyMs: Long? = null,
        val receiveToBuyLatencyMs: Long? = null,
        val endToEndToBuyLatencyMs: Long? = null,
        val endToEndToSellLatencyMs: Long? = null,
        val note: String? = null,
        val isShadowMode: Boolean = false,
    )
    @Serializable
    private data class CorrelationMatrixMessage(
        val msgType: String = "CORRELATION_MATRIX",
        val sectors: Map<String, List<String>> = emptyMap(),
    )

    @Serializable
    private data class BufferedDailyRiskWrite(
        val botId: String,
        val date: String,
        val snapshot: DailyRiskSnapshot,
    )

    @Serializable
    private data class BufferedFastTelemetryWrite(
        val totalBalanceIdr: Double,
        val currentPingMs: Long? = null,
        val activeLivePairs: List<String> = emptyList(),
    )

    @Serializable
    private data class NonCriticalControlPlaneBufferSnapshot(
        val botId: String,
        val deviceId: String,
        val observedAtEpochMs: Long = 0L,
        val lastFlushEpochMs: Long = 0L,
        val pendingHeartbeat: EngineHeartbeatSnapshot? = null,
        val pendingDailyRisk: BufferedDailyRiskWrite? = null,
        val pendingFastTelemetry: BufferedFastTelemetryWrite? = null,
    )

    private data class ForcedSellSignal(
        val traceId: String,
        val expiresAtEpochMs: Long,
    )

    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }
    private val nonCriticalControlPlaneFlushIntervalMs = 60.minutes.inWholeMilliseconds
    private var nonCriticalControlPlaneBuffer = loadNonCriticalControlPlaneBuffer()
    private val adaptiveAiPolicyLoader = AdaptiveAiPolicyLoader(config.adaptiveAiPolicyPath)
    private val aiProviderStatusLoader = AiProviderStatusLoader()
    private val dailyTargetPursuitBrain = DailyTargetPursuitBrain()
    private var registered = false
    private var lastAnalysisPublishedAt: Instant? = null
    private var lastStrategyMetricsPublishedAt: Instant? = null
    private var lastCandidateSignature: String? = null
    private var lastLearningSignature: String? = null
    private var lastLearningPublishedAt: Instant? = null
    private var lastWeeklyReviewPublishedAt: Instant? = null
    private var releaseCooldownUntil: Instant? = null
    private var lastSuccessfulControlPlaneAt: Instant? = null
    private var smoothedExchangePingMs: Double? = null
    private var lastSuccessfulExchangePingAt: Instant? = null
    private var lastExchangeProbeAt: Instant? = null
    private var lastExchangeReachable: Boolean = false
    private var lastExchangePingMs: Long? = null
    private var consecutiveExchangeProbeFailures: Int = 0
    private var lastExecutionPolicyLogSignature: String? = null
    private var lastExecutionPolicyLoggedAt: Instant? = null
    private var lastObservedLeaseTerm: com.kibot.shared.models.LeaseTerm? = null
    private var conflictRecoveryHoldUntil: Instant? = null
    private var conflictRecoveryTerm: com.kibot.shared.models.LeaseTerm? = null
    private var cachedDevices: List<DeviceDescriptor> = emptyList()
    private var devicesFetchedAt: Instant? = null
    private var cachedDailyRisk: DailyRiskSnapshot? = null
    private var cachedDailyRiskDate: kotlinx.datetime.LocalDate? = null
    private var dailyRiskFetchedAt: Instant? = null
    private var commandsFetchedAt: Instant? = null
    private var cachedWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary? = null
    private var weeklyReviewFetchedAt: Instant? = null
    private var cachedEquityHistory: List<com.kibot.shared.models.DailyEquityHistoryPoint> = emptyList()
    private var equityHistoryFetchedAt: Instant? = null
    private var cachedBalances: List<BalanceSnapshot> = emptyList()
    private var balancesFetchedAt: Instant? = null
    private var cachedOpenOrders: List<com.kibot.shared.models.OrderSnapshot> = emptyList()
    private var openOrdersFetchedAt: Instant? = null
    private var cachedRecentOrders: List<com.kibot.shared.models.OrderSnapshot> = emptyList()
    private var recentOrdersFetchedAt: Instant? = null
    private var cachedRecentFills: List<com.kibot.shared.models.FillSnapshot> = emptyList()
    private var recentFillsFetchedAt: Instant? = null
    private var cachedRecentFillsKey: String? = null
    private var cachedAdaptiveAiPolicy: AdaptiveAiPolicy? = null
    private var adaptiveAiPolicyFetchedAt: Instant? = null
    private var targetEnforcementMemory = loadTargetEnforcementMemory()
    private var monthlyPnlAnchor = loadMonthlyPnlAnchor()
    private var lastMonthlyPnlAnchorSignature: String? = null
    private var pnlResetAnchor = loadPnlResetAnchor()
    private var lastPnlResetAnchorSignature: String? = null
    private var activeLeadLagCallout: ActiveLeadLagCallout? = null
    // Use ConcurrentHashMap for thread-safe access from UDP listener and main loop
    private val pendingKinanceSignalsByTrace = java.util.concurrent.ConcurrentHashMap<String, TrinityPendingSignal>()
    private val pendingKibotVetosByTrace = java.util.concurrent.ConcurrentHashMap<String, TrinityPendingSignal>()
    private val forcedSellTraceByPair = java.util.concurrent.ConcurrentHashMap<String, ForcedSellSignal>()
    private val sellWallFirstSeenAtByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val leadLagSentAtByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private enum class CoinClass { NAGA, MID, MICIN }
    private val leadLagOriginSentAtByPair = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val leadLagReceivedAtByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val leadLagEntrySubmittedAtByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val leadLagTraceByPair = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val leadLagDetectedAtByPair = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val leadLagStatsByClass = java.util.concurrent.ConcurrentHashMap<CoinClass, LeadLagClassStats>()
    private var lastLeadLagAlarmAt: Instant? = null
    private val leadLagMicroPulseByPair = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<PairMicroPulseSample>>()
    private val leadLagGradualPulseByPair = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<PairMicroPulseSample>>()
    private val leadLagTrailingPeakBidByPair = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val hyperAggressivePulseByPair = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<PairMicroPulseSample>>()
    private val hyperAggressiveTrackedEntryAtByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val hyperAggressivePeakBidByPair = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val localAutonomyPeakBidByPair = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val localAutonomyTrailingFloorByPair = java.util.concurrent.ConcurrentHashMap<String, LocalTrailingSnapshot>()
    private val localAutonomyTrailingFloorLogByPair = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val historicalPeakCacheByPair = java.util.concurrent.ConcurrentHashMap<String, HistoricalPeakCacheEntry>()
    private val candleHistoryGuardCacheByPair = java.util.concurrent.ConcurrentHashMap<String, CandleHistoryGuardCacheEntry>()
    private val multiTimeframeQuoteCacheByPair = java.util.concurrent.ConcurrentHashMap<String, MultiTimeframeQuoteCacheEntry>()
    private val spoofPulseByPair = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<OrderBookPulseSample>>()
    private val spoofSuspiciousUntilByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val indodaxHistoryHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val telegramAlertNotifier = TelegramAlertNotifier(
        enabled = config.telegramAlertsEnabled,
        botToken = config.telegramBotToken,
        chatId = config.telegramChatId,
    )
    private val hyperAggressiveEntryReasonByPair = java.util.concurrent.ConcurrentHashMap<String, HyperTargetKind>()
    private val partialTakeProfitExecutedByPair = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var lastSuperSexyTarget: com.kibot.shared.models.PairId? = null
    private val hyperConfig = config.hyperAggressiveConfig
    @Volatile private var dynamicSectorCorrelationBook: Map<String, Set<String>> = emptyMap()
    @Volatile private var aListTunnelPairs: Set<String> = emptySet()
    @Volatile private var indodaxFocusBases: Set<String> = emptySet()
    @Volatile private var indodaxFocusFetchedAt: Instant? = null
    private val dynamicVipUntilByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    @Volatile private var lastWhyNotBuyAt: Instant? = null
    @Volatile private var lastWhyNotBuySignature: String? = null
    private val sinBinUntilByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val crashGuardTriggerTimeline = ArrayDeque<Instant>()
    @Volatile private var globalCooldownUntil: Instant? = null
    private val dustQuarantinePairs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val kidaxActivePositionsByPair = java.util.concurrent.ConcurrentHashMap<String, ActivePositionWire>()
    private val emergencyWarningCooldownByPair = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    @Volatile private var aiRuntimeProviderStatusLabel: String? = null
    @Volatile private var aiRuntimeProviderStatusAt: Instant? = null
    @Volatile private var holdingsFocusToggle = false
    @Volatile private var lastActivePositionsBroadcastAt: Instant? = null
    @Volatile private var lastLeaseLockdownAttemptAt: Instant? = null
    private val daemonStartedAt: Instant = Clock.System.now()
    private val lastTrinityHeartbeatByBotId = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    @Volatile private var lastTrinityHeartbeatSentAt: Instant? = null
    private val udpSequenceCounter = AtomicInteger(1)
    private val udpLastSequenceBySender = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val udpRecentDedupKeys = java.util.concurrent.ConcurrentHashMap<String, Instant>()
    private val udpExecutionPrewarmByPair = java.util.concurrent.ConcurrentHashMap<String, UdpExecutionPrewarm>()
    @Volatile private var trinityHeartbeatSafeModeReason: String? = null
    @Volatile private var startupRecoveryAudited = false
    @Volatile private var lastLocalPositionStateSignature: String? = null
    @Volatile private var lastToxicFlowStateSignature: String? = null
    @Volatile private var localRecoveryFallbackAnnounced = false
    @Volatile private var lastOperatorAlertStateKey: String? = null
    private val toxicFlowStateByPair = loadToxicFlowState().entries.associateBy { it.pairId.lowercase() }.toMutableMap()
    private val hiveExtraUdpPeers: List<Pair<String, Int>> = run {
        val raw = System.getenv("KIBOT_HIVE_UDP_PEERS")
            ?.split(",")
            ?.mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val parts = trimmed.split(":")
                val host = parts.firstOrNull()?.trim().orEmpty()
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: config.leadLagUdpTargetPort
                if (host.isBlank()) null else host to port
            }
            .orEmpty()
        raw
    }

    private fun classifyPair(pairId: PairId): CoinClass {
        val id = pairId.value.lowercase()
        return when {
            id.startsWith("btc_") || id.startsWith("eth_") || id.startsWith("sol_") -> CoinClass.NAGA
            id.startsWith("doge_") || id.startsWith("shib_") || id.startsWith("pepe_") || id.startsWith("xrp_") -> CoinClass.MID
            else -> CoinClass.MICIN
        }
    }

    // Static A-List gate disabled: selection must remain adaptive by chart/history/market state.
    private fun isAListPair(pairId: PairId): Boolean = false

    private fun computeAListTunnelPairs(
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): Set<String> {
        if (marketQuotes.isEmpty()) return emptySet()
        return marketQuotes.asSequence()
            .filter { quote ->
                val quoteAsset = quote.pairId.pairAssets().quoteAsset.lowercase()
                quoteAsset == "idr" || quoteAsset == "usdt" || quoteAsset == "usdc"
            }
            .filter { quote -> quote.pairId.value.lowercase() !in hiddenStablePairs }
            .map { it.pairId.value.lowercase() }
            .toSet()
    }

    private fun refreshAListTunnelPairs(
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        if (marketQuotes.isEmpty()) return
        aListTunnelPairs = computeAListTunnelPairs(marketQuotes)
    }

    private fun isAListTunnelPair(pairId: PairId): Boolean {
        val quote = pairId.pairAssets().quoteAsset.lowercase()
        val key = pairId.value.lowercase()
        if (key in hiddenStablePairs) return false
        if (dynamicVipUntilByPair[key]?.let { Clock.System.now() < it } == true) return true
        return key in aListTunnelPairs || quote in setOf("idr", "usdt", "usdc", "btc", "eth", "bnb")
    }

    private fun refreshIndodaxFocusUniverse(now: Instant) {
        val last = indodaxFocusFetchedAt
        if (last != null && (now - last).inWholeMilliseconds < indodaxFocusRefreshIntervalMs) return
        val next = runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(indodaxSummariesEndpoint))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            val response = indodaxHistoryHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching emptySet<String>()
            val root = json.parseToJsonElement(response.body()).jsonObject
            val tickers = root["tickers"]?.jsonObject ?: return@runCatching emptySet<String>()
            tickers.keys
                .mapNotNull { key ->
                    val lower = key.lowercase()
                    if (!lower.endsWith("_idr")) return@mapNotNull null
                    lower.substringBefore("_idr").takeIf { it.isNotBlank() }
                }
                .toSet()
        }.getOrElse { emptySet() }
        if (next.isNotEmpty()) {
            indodaxFocusBases = next
            indodaxFocusFetchedAt = now
        }
    }

    private fun hasStrongGlobalSentiment(pairId: PairId): Boolean {
        val base = pairId.pairAssets().baseAsset.lowercase()
        return dynamicSectorCorrelationBook.values.any { base in it }
    }

    private suspend fun markDynamicVip(
        pairId: PairId,
        now: Instant,
        reason: String,
    ) {
        val key = pairId.value.lowercase()
        dynamicVipUntilByPair[key] = now.plus(dynamicVipTtlMinutes.minutes)
        appendThrottledAuditLog(
            now = now,
            level = LogLevel.INFO,
            category = "VIP_DYNAMIC",
            message = "Dynamic VIP armed ${pairId.value} reason=$reason ttl=${dynamicVipTtlMinutes}m.",
        )
    }

    private fun pruneDynamicVip(now: Instant) {
        dynamicVipUntilByPair.entries.removeIf { (_, until) -> now >= until }
    }

    private fun isDynamicVipActive(
        pairId: PairId,
        now: Instant,
    ): Boolean {
        val key = pairId.value.lowercase()
        val until = dynamicVipUntilByPair[key] ?: return false
        if (now >= until) {
            dynamicVipUntilByPair.remove(key)
            return false
        }
        return true
    }

    private fun projectedEntryNetPct(
        quote: com.kibot.shared.models.MarketQuote,
        assumeTaker: Boolean,
    ): Double {
        val grossTrendPct = maxOf(
            quote.shortTermReturnPct,
            quote.mediumTermReturnPct * 0.8,
            0.45,
        )
        val feePct = if (assumeTaker) config.indodaxHyperGuardrailTakerFeePct else 0.0
        val impactPct = (quote.estimatedSlippagePct * 0.35).coerceAtLeast(0.05)
        return grossTrendPct - feePct - impactPct
    }

    private suspend fun seedDynamicVipFromPanopticon(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        if (marketQuotes.isEmpty()) return
        marketQuotes
            .asSequence()
            .filter { it.pairId.pairAssets().quoteAsset.equals("idr", ignoreCase = true) }
            .filter { estimateQuoteVolumeIdr(it, marketQuotes) >= config.aListMinVolumeIdr }
            .filter { it.midPrice.toDoubleOrZero() > 0.0 }
            .forEach { quote ->
                val pairId = quote.pairId
                val instantSurge = passesKinanceInstantAnomalyFilter(
                    pairId = pairId,
                    quote = quote,
                    now = now,
                    marketQuotes = marketQuotes,
                )
                val directionalSurge =
                    quote.shortTermReturnPct >= dynamicVipMinShortTermSurgePct &&
                        quote.recentTradeActivityScore >= dynamicVipMinTradeActivityScore
                val globalSentiment =
                    hasStrongGlobalSentiment(pairId) &&
                        quote.mediumTermReturnPct >= dynamicVipMinMediumTrendPct &&
                        quote.recentTradeActivityScore >= (dynamicVipMinTradeActivityScore - 0.05)
                if (instantSurge || directionalSurge || globalSentiment) {
                    val reason = when {
                        instantSurge -> "momentum_surge"
                        globalSentiment -> "global_sentiment"
                        else -> "directional_surge"
                    }
                    markDynamicVip(pairId = pairId, now = now, reason = reason)
                }
            }
    }

    private fun shouldEnforceMainLeaseLockdown(): Boolean {
        return config.exchangeKind == ExchangeKind.INDODAX &&
            config.controlPlane.botId.value.equals("main", ignoreCase = true)
    }

    private fun isLeaseReserveOwnershipConflict(error: Throwable): Boolean {
        val msg = error.message?.lowercase().orEmpty()
        return msg.contains("only the active lease holder may reserve execution actions")
    }

    private suspend fun attemptLeaseLockdownRecovery(now: Instant) {
        if (!shouldEnforceMainLeaseLockdown()) return
        val lastAttempt = lastLeaseLockdownAttemptAt
        if (lastAttempt != null && (now - lastAttempt).inWholeMilliseconds < leaseLockdownRetryCooldownMs) return
        lastLeaseLockdownAttemptAt = now
        runCatching {
            controlPlane.acquireLease(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                ttlSeconds = config.leaseTtlSeconds,
            )
        }.onSuccess { lease ->
            lastObservedLeaseTerm = lease.term
            repository.noteStatus("Lease lockdown active: reclaimed execution lease term ${lease.term.value}.")
            appendAuditLog(
                level = LogLevel.WARN,
                category = "FAILOVER",
                message = "Lease lockdown reclaimed term ${lease.term.value} for ${config.device.deviceId.value}.",
            )
        }.onFailure { retryError ->
            repository.noteStatus("Lease lockdown retry gagal: ${retryError.message ?: "unknown"}")
        }
    }

    private suspend fun ensureLeaseLockdownOwnership(
        now: Instant,
        existingLease: EngineLeaseSnapshot?,
    ): EngineLeaseSnapshot? {
        if (!shouldEnforceMainLeaseLockdown()) return existingLease
        if (existingLease?.conflictDetected == true) return existingLease
        if (
            existingLease != null &&
            existingLease.currentHolder != null &&
            existingLease.currentHolder != config.device.deviceId
        ) {
            return existingLease
        }
        val currentBotState = runCatching { controlPlane.fetchBotState(config.controlPlane.botId) }.getOrNull()
        if (currentBotState?.effectiveState == BotEffectiveState.SAFE_MODE) return existingLease
        if (existingLease.isHeldBy(config.device.deviceId, now) && existingLease?.conflictDetected != true) {
            return existingLease
        }
        attemptLeaseLockdownRecovery(now)
        return runCatching { controlPlane.fetchLease(config.controlPlane.botId) }.getOrNull() ?: existingLease
    }

    private suspend fun <T> readControlPlane(
        fallback: T,
        timeoutMs: Long? = null,
        block: suspend () -> T,
    ): T {
        val effectiveTimeoutMs = timeoutMs ?: if (registered) 4_000L else 12_000L
        return withTimeoutOrNull(effectiveTimeoutMs) {
            runCatching { block() }.getOrNull()
        } ?: fallback
    }

    private suspend fun writeControlPlane(
        context: String,
        timeoutMs: Long? = null,
        block: suspend () -> Unit,
    ): Boolean {
        val effectiveTimeoutMs = timeoutMs ?: if (registered) 4_000L else 12_000L
        val outcome = withTimeoutOrNull(effectiveTimeoutMs) {
            runCatching {
                block()
                true
            }.getOrElse { error ->
                logger.warn("Control-plane write failed during {}: {}", context, error.message ?: "unknown")
                false
            }
        } ?: false
        if (!outcome) {
            appendAuditLog(
                level = LogLevel.WARN,
                category = "CONTROL_PLANE",
                message = "Control-plane write skipped/failed during $context.",
            )
        }
        return outcome
    }

    private fun nonCriticalControlPlaneBufferPath(): java.nio.file.Path {
        val basePath = if (config.localPositionStateEnabled) {
            config.localPositionStatePath
        } else {
            config.targetEnforcementMemoryPath
        }
        val parent = basePath.parent ?: java.nio.file.Paths.get(".")
        return parent.resolve("control-plane-noncritical-buffer.json")
    }

    private fun loadNonCriticalControlPlaneBuffer(): NonCriticalControlPlaneBufferSnapshot {
        val path = nonCriticalControlPlaneBufferPath()
        return runCatching {
            if (!Files.exists(path)) {
                return NonCriticalControlPlaneBufferSnapshot(
                    botId = config.controlPlane.botId.value,
                    deviceId = config.device.deviceId.value,
                )
            }
            json.decodeFromString<NonCriticalControlPlaneBufferSnapshot>(Files.readString(path))
        }.getOrElse {
            logger.warn("Failed to load control-plane non-critical buffer: {}", it.message)
            NonCriticalControlPlaneBufferSnapshot(
                botId = config.controlPlane.botId.value,
                deviceId = config.device.deviceId.value,
            )
        }
    }

    private fun persistNonCriticalControlPlaneBuffer() {
        runCatching {
            val path = nonCriticalControlPlaneBufferPath()
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, json.encodeToString(nonCriticalControlPlaneBuffer))
        }.onFailure {
            logger.warn("Failed to persist control-plane non-critical buffer: {}", it.message)
        }
    }

    private fun queueNonCriticalHeartbeat(now: Instant, snapshot: EngineHeartbeatSnapshot) {
        val existing = nonCriticalControlPlaneBuffer
        nonCriticalControlPlaneBuffer = existing.copy(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            observedAtEpochMs = now.toEpochMilliseconds(),
            lastFlushEpochMs = existing.lastFlushEpochMs.takeIf { it > 0L } ?: now.toEpochMilliseconds(),
            pendingHeartbeat = snapshot,
        )
        persistNonCriticalControlPlaneBuffer()
    }

    private fun queueNonCriticalDailyRisk(
        now: Instant,
        botId: BotId,
        date: LocalDate,
        snapshot: DailyRiskSnapshot,
    ) {
        val existing = nonCriticalControlPlaneBuffer
        nonCriticalControlPlaneBuffer = existing.copy(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            observedAtEpochMs = now.toEpochMilliseconds(),
            lastFlushEpochMs = existing.lastFlushEpochMs.takeIf { it > 0L } ?: now.toEpochMilliseconds(),
            pendingDailyRisk = BufferedDailyRiskWrite(
                botId = botId.value,
                date = date.toString(),
                snapshot = snapshot,
            ),
        )
        persistNonCriticalControlPlaneBuffer()
    }

    private fun queueNonCriticalFastTelemetry(
        now: Instant,
        totalBalanceIdr: Double,
        currentPingMs: Long?,
        activeLivePairs: List<String>,
    ) {
        val existing = nonCriticalControlPlaneBuffer
        nonCriticalControlPlaneBuffer = existing.copy(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            observedAtEpochMs = now.toEpochMilliseconds(),
            lastFlushEpochMs = existing.lastFlushEpochMs.takeIf { it > 0L } ?: now.toEpochMilliseconds(),
            pendingFastTelemetry = BufferedFastTelemetryWrite(
                totalBalanceIdr = totalBalanceIdr,
                currentPingMs = currentPingMs,
                activeLivePairs = activeLivePairs.distinct(),
            ),
        )
        persistNonCriticalControlPlaneBuffer()
    }

    suspend fun flushNonCriticalControlPlaneBufferNow() {
        flushNonCriticalControlPlaneBuffer(now = Clock.System.now(), force = true)
    }

    private suspend fun flushNonCriticalControlPlaneBuffer(now: Instant, force: Boolean = false) {
        val buffer = nonCriticalControlPlaneBuffer
        val hasPending = buffer.pendingHeartbeat != null || buffer.pendingDailyRisk != null || buffer.pendingFastTelemetry != null
        if (!hasPending) return
        val lastFlushEpochMs = buffer.lastFlushEpochMs
        if (!force && lastFlushEpochMs > 0L && now.toEpochMilliseconds() - lastFlushEpochMs < nonCriticalControlPlaneFlushIntervalMs) return

        var flushedHeartbeat = false
        var flushedDailyRisk = false
        var flushedFastTelemetry = false

        buffer.pendingHeartbeat?.let { snapshot ->
            flushedHeartbeat = writeControlPlane("flush-buffered-heartbeat", timeoutMs = 12_000L) {
                controlPlane.appendHeartbeat(snapshot)
            }
        }
        buffer.pendingDailyRisk?.let { pending ->
            flushedDailyRisk = writeControlPlane("flush-buffered-daily-risk", timeoutMs = 12_000L) {
                controlPlane.upsertDailyRisk(
                    botId = BotId(pending.botId),
                    date = LocalDate.parse(pending.date),
                    snapshot = pending.snapshot,
                )
            }
        }
        buffer.pendingFastTelemetry?.let { pending ->
            flushedFastTelemetry = writeControlPlane("flush-buffered-fast-telemetry", timeoutMs = 12_000L) {
                controlPlane.upsertKingDashboardFastTelemetry(
                    totalBalanceIdr = pending.totalBalanceIdr,
                    currentPingMs = pending.currentPingMs,
                    activeLivePairs = pending.activeLivePairs,
                )
            }
        }

        nonCriticalControlPlaneBuffer = buffer.copy(
            observedAtEpochMs = now.toEpochMilliseconds(),
            lastFlushEpochMs = now.toEpochMilliseconds(),
            pendingHeartbeat = if (flushedHeartbeat) null else buffer.pendingHeartbeat,
            pendingDailyRisk = if (flushedDailyRisk) null else buffer.pendingDailyRisk,
            pendingFastTelemetry = if (flushedFastTelemetry) null else buffer.pendingFastTelemetry,
        )
        persistNonCriticalControlPlaneBuffer()
    }

    private fun estimateQuoteVolumeIdr(
        quote: com.kibot.shared.models.MarketQuote,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): Double {
        val volume = quote.quoteVolume24h.toDoubleOrZero().coerceAtLeast(0.0)
        if (volume <= 0.0) return 0.0
        val quoteAsset = quote.pairId.pairAssets().quoteAsset.lowercase()
        return when (quoteAsset) {
            "idr" -> volume
            "usdt", "usdc" -> volume * resolveFxToIdr("usdt", marketQuotes)
            "btc" -> volume * resolveFxToIdr("btc", marketQuotes)
            "eth" -> volume * resolveFxToIdr("eth", marketQuotes)
            "bnb" -> volume * resolveFxToIdr("bnb", marketQuotes)
            else -> volume
        }
    }

    private fun resolveFxToIdr(
        base: String,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): Double {
        val lowerBase = base.lowercase()
        return marketQuotes.firstOrNull { it.pairId.value.equals("${lowerBase}_idr", ignoreCase = true) }
            ?.midPrice
            ?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: when (lowerBase) {
                "usdt", "usdc" -> 16_200.0
                "btc" -> 1_000_000_000.0
                "eth" -> 50_000_000.0
                "bnb" -> 8_000_000.0
                else -> 1.0
            }
    }

    private fun isLeadLagClassEnabled(coinClass: CoinClass): Boolean = when (coinClass) {
        CoinClass.NAGA -> config.leadLagEnableNaga
        CoinClass.MID -> config.leadLagEnableMid
        CoinClass.MICIN -> config.leadLagEnableMicin
    }

    private fun updateLeadLagStats(coinClass: CoinClass, mutate: (LeadLagClassStats) -> LeadLagClassStats) {
        val previous = leadLagStatsByClass[coinClass] ?: LeadLagClassStats()
        leadLagStatsByClass[coinClass] = mutate(previous)
    }

    private fun pruneLeadLagTelemetry(now: Instant) {
        val staleThresholdMs = leadLagTelemetryKeepWindowHours.hours.inWholeMilliseconds
        val minEpoch = now.toEpochMilliseconds() - staleThresholdMs
        leadLagSentAtByPair.entries.removeIf { (_, value) -> value.toEpochMilliseconds() < minEpoch }
        leadLagOriginSentAtByPair.entries.removeIf { (_, value) -> value < minEpoch }
        leadLagReceivedAtByPair.entries.removeIf { (_, value) -> value.toEpochMilliseconds() < minEpoch }
        leadLagEntrySubmittedAtByPair.entries.removeIf { (_, value) -> value.toEpochMilliseconds() < minEpoch }

        // Cap map growth in case market scan expands aggressively.
        trimToMaxSize(leadLagSentAtByPair, leadLagTelemetryMaxPairs)
        trimToMaxSize(leadLagOriginSentAtByPair, leadLagTelemetryMaxPairs)
        trimToMaxSize(leadLagReceivedAtByPair, leadLagTelemetryMaxPairs)
        trimToMaxSize(leadLagEntrySubmittedAtByPair, leadLagTelemetryMaxPairs)
        trimToMaxSize(leadLagTraceByPair, leadLagTelemetryMaxPairs)
        trimToMaxSize(leadLagDetectedAtByPair, leadLagTelemetryMaxPairs)
        leadLagTrailingPeakBidByPair.entries.removeIf { (pairKey, _) -> pairKey !in leadLagEntrySubmittedAtByPair.keys }
        while (leadLagMicroPulseByPair.size > leadLagMicroPulseMaxPairs) {
            val oldestKey = leadLagMicroPulseByPair.keys.firstOrNull() ?: break
            leadLagMicroPulseByPair.remove(oldestKey)
        }
        while (leadLagGradualPulseByPair.size > leadLagGradualPulseMaxPairs) {
            val oldestKey = leadLagGradualPulseByPair.keys.firstOrNull() ?: break
            leadLagGradualPulseByPair.remove(oldestKey)
        }
        while (hyperAggressivePulseByPair.size > hyperConfig.microPulseMaxPairs) {
            val oldestKey = hyperAggressivePulseByPair.keys.firstOrNull() ?: break
            hyperAggressivePulseByPair.remove(oldestKey)
        }
        hyperAggressivePeakBidByPair.entries.removeIf { (pairKey, _) -> pairKey !in hyperAggressiveTrackedEntryAtByPair.keys }
        hyperAggressiveEntryReasonByPair.entries.removeIf { (pairKey, _) -> pairKey !in hyperAggressiveTrackedEntryAtByPair.keys }
        localAutonomyPeakBidByPair.entries.removeIf { (pairKey, _) ->
            localAutonomyTrailingFloorByPair[pairKey] == null && pairKey !in leadLagEntrySubmittedAtByPair.keys
        }
        localAutonomyTrailingFloorLogByPair.entries.removeIf { (pairKey, _) -> pairKey !in localAutonomyTrailingFloorByPair.keys }
    }

    private fun updateLeadLagMicroPulseSnapshots(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        val nowMs = now.toEpochMilliseconds()
        marketQuotes.forEach { quote ->
            val pairKey = quote.pairId.value.lowercase()
            val deque = leadLagMicroPulseByPair.getOrPut(pairKey) { ArrayDeque() }
            deque.addLast(
                PairMicroPulseSample(
                    atEpochMs = nowMs,
                    midPrice = quote.midPrice.toDoubleOrZero(),
                    quoteVolume24h = quote.quoteVolume24h.toDoubleOrZero(),
                ),
            )
            while (deque.isNotEmpty() && (nowMs - deque.first().atEpochMs) > leadLagMicroPulseKeepMs) {
                deque.removeFirst()
            }
            while (deque.size > leadLagMicroPulseMaxSamplesPerPair) {
                deque.removeFirst()
            }
            val gradualDeque = leadLagGradualPulseByPair.getOrPut(pairKey) { ArrayDeque() }
            gradualDeque.addLast(
                PairMicroPulseSample(
                    atEpochMs = nowMs,
                    midPrice = quote.midPrice.toDoubleOrZero(),
                    quoteVolume24h = quote.quoteVolume24h.toDoubleOrZero(),
                ),
            )
            while (gradualDeque.isNotEmpty() && (nowMs - gradualDeque.first().atEpochMs) > leadLagGradualKeepMs) {
                gradualDeque.removeFirst()
            }
            while (gradualDeque.size > leadLagGradualMaxSamplesPerPair) {
                gradualDeque.removeFirst()
            }
        }
    }

    private fun passesKinanceMicroBreakoutFilter(
        pairId: com.kibot.shared.models.PairId,
        now: Instant,
    ): Boolean {
        val deque = leadLagMicroPulseByPair[pairId.value.lowercase()] ?: return false
        if (deque.size < 3) return false
        val nowMs = now.toEpochMilliseconds()
        val latest = deque.lastOrNull() ?: return false
        val priceAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= leadLagDetectorPriceWindowMs } ?: return false
        if (priceAnchor.midPrice <= 0.0 || latest.midPrice <= 0.0) return false
        val priceDeltaPct = ((latest.midPrice - priceAnchor.midPrice) / priceAnchor.midPrice) * 100.0
        if (priceDeltaPct < leadLagDetectorMinPriceDeltaPct) return false

        val oneSecAgo = deque.lastOrNull { (nowMs - it.atEpochMs) >= 1_000L } ?: return false
        val sixtySecAgo = deque.lastOrNull { (nowMs - it.atEpochMs) >= leadLagDetectorVolumeBaselineWindowMs } ?: return false
        val currentPerSecVolume = (latest.quoteVolume24h - oneSecAgo.quoteVolume24h).coerceAtLeast(0.0)
        val baselineDurationSec = ((latest.atEpochMs - sixtySecAgo.atEpochMs).coerceAtLeast(1L) / 1000.0).coerceAtLeast(1.0)
        val baselineDelta = (latest.quoteVolume24h - sixtySecAgo.quoteVolume24h).coerceAtLeast(0.0)
        val baselinePerSec = (baselineDelta / baselineDurationSec).coerceAtLeast(0.0000001)
        return currentPerSecVolume >= (baselinePerSec * leadLagDetectorMinVolumeAnomalyMultiplier)
    }

    private fun passesKinanceGradualUptrendFilter(
        pairId: com.kibot.shared.models.PairId,
        now: Instant,
    ): Boolean {
        val deque = leadLagGradualPulseByPair[pairId.value.lowercase()] ?: return false
        if (deque.size < 8) return false
        val nowMs = now.toEpochMilliseconds()
        val latest = deque.lastOrNull() ?: return false
        val anchor5m = deque.lastOrNull { (nowMs - it.atEpochMs) >= 300_000L } ?: return false
        val anchor10m = deque.lastOrNull { (nowMs - it.atEpochMs) >= 600_000L } ?: anchor5m
        if (latest.midPrice <= 0.0 || anchor5m.midPrice <= 0.0 || anchor10m.midPrice <= 0.0) return false
        val delta5m = ((latest.midPrice - anchor5m.midPrice) / anchor5m.midPrice) * 100.0
        val delta10m = ((latest.midPrice - anchor10m.midPrice) / anchor10m.midPrice) * 100.0
        val oneSecAgo = deque.lastOrNull { (nowMs - it.atEpochMs) >= 1_000L } ?: return false
        val sixtySecAgo = deque.lastOrNull { (nowMs - it.atEpochMs) >= 60_000L } ?: return false
        val currentPerSecVolume = (latest.quoteVolume24h - oneSecAgo.quoteVolume24h).coerceAtLeast(0.0)
        val baselineDurationSec = ((latest.atEpochMs - sixtySecAgo.atEpochMs).coerceAtLeast(1L) / 1000.0).coerceAtLeast(1.0)
        val baselineDelta = (latest.quoteVolume24h - sixtySecAgo.quoteVolume24h).coerceAtLeast(0.0)
        val baselinePerSec = (baselineDelta / baselineDurationSec).coerceAtLeast(0.0000001)
        val noSpikeVolume = currentPerSecVolume <= (baselinePerSec * 1.8)
        return delta5m >= 1.2 && delta10m >= 2.0 && noSpikeVolume
    }

    private fun passesKinanceInstantAnomalyFilter(
        pairId: com.kibot.shared.models.PairId,
        quote: com.kibot.shared.models.MarketQuote,
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): Boolean {
        val volumeIdr = estimateQuoteVolumeIdr(quote, marketQuotes)
        if (volumeIdr < config.aListMinVolumeIdr) return false
        val deque = leadLagMicroPulseByPair[pairId.value.lowercase()] ?: return false
        if (deque.size < 4) return false
        val nowMs = now.toEpochMilliseconds()
        val latest = deque.lastOrNull() ?: return false
        val anchor15 = deque.lastOrNull { (nowMs - it.atEpochMs) >= 15_000L } ?: deque.firstOrNull() ?: return false
        val anchor30 = deque.lastOrNull { (nowMs - it.atEpochMs) >= 30_000L } ?: anchor15
        if (latest.midPrice <= 0.0 || anchor15.midPrice <= 0.0 || anchor30.midPrice <= 0.0) return false
        val delta15 = ((latest.midPrice - anchor15.midPrice) / anchor15.midPrice) * 100.0
        val delta30 = ((latest.midPrice - anchor30.midPrice) / anchor30.midPrice) * 100.0
        val oneSecAgo = deque.lastOrNull { (nowMs - it.atEpochMs) >= 1_000L } ?: return false
        val baselineAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= 30_000L } ?: deque.firstOrNull() ?: return false
        val currentPerSecVolume = (latest.quoteVolume24h - oneSecAgo.quoteVolume24h).coerceAtLeast(0.0)
        val baselineDurationSec = ((latest.atEpochMs - baselineAnchor.atEpochMs).coerceAtLeast(1L) / 1000.0).coerceAtLeast(1.0)
        val baselineDelta = (latest.quoteVolume24h - baselineAnchor.quoteVolume24h).coerceAtLeast(0.0)
        val baselinePerSec = (baselineDelta / baselineDurationSec).coerceAtLeast(0.0000001)
        val volumeAnomaly = currentPerSecVolume >= (baselinePerSec * instantAnomalyVolumeMultiplier)
        val priceAnomaly = delta15 >= instantAnomalyMinPriceDelta15sPct || delta30 >= instantAnomalyMinPriceDelta30sPct
        return priceAnomaly && volumeAnomaly && quote.recentTradeActivityScore >= instantAnomalyMinTradeActivityScore
    }

    private fun updateHyperAggressivePulseSnapshots(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        val nowMs = now.toEpochMilliseconds()
        marketQuotes.forEach { quote ->
            val pairKey = quote.pairId.value.lowercase()
            val deque = hyperAggressivePulseByPair.getOrPut(pairKey) { ArrayDeque() }
            deque.addLast(
                PairMicroPulseSample(
                    atEpochMs = nowMs,
                    midPrice = quote.midPrice.toDoubleOrZero(),
                    quoteVolume24h = quote.quoteVolume24h.toDoubleOrZero(),
                ),
            )
            while (deque.isNotEmpty() && (nowMs - deque.first().atEpochMs) > hyperConfig.microPulseKeepMs) {
                deque.removeFirst()
            }
            while (deque.size > hyperConfig.microPulseMaxSamplesPerPair) {
                deque.removeFirst()
            }
        }
    }

    private fun evaluateHyperAggressiveTracker(
        now: Instant,
        dailyRisk: DailyRiskSnapshot,
    ): HyperAggressiveTracker {
        val local = now.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hoursElapsed = ((local.hour + (local.minute / 60.0) + (local.second / 3600.0)).coerceAtLeast(1.0 / 60.0))
        val opening = dailyRisk.openingEquityIdr.toDoubleOrZero().coerceAtLeast(1.0)
        val current = dailyRisk.currentEquityIdr.toDoubleOrZero().coerceAtLeast(0.0)
        val accumulatedPnlPct = ((current - opening) / opening) * 100.0
        val hourlyPnlPct = accumulatedPnlPct / hoursElapsed
        val targetHourlyPct = hyperConfig.targetDailyPct / 24.0
        return HyperAggressiveTracker(
            hoursElapsed = hoursElapsed,
            accumulatedPnlPct = accumulatedPnlPct,
            hourlyPnlPct = hourlyPnlPct,
            targetHourlyPct = targetHourlyPct,
            hungry = hourlyPnlPct < targetHourlyPct,
        )
    }

    private fun detectHyperAggressiveTargets(
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        now: Instant,
    ): List<HyperTargetCandidate> {
        val nowMs = now.toEpochMilliseconds()
        val directTargets = marketQuotes.mapNotNull { quote ->
            val deque = hyperAggressivePulseByPair[quote.pairId.value.lowercase()] ?: return@mapNotNull null
            if (deque.size < 3) return@mapNotNull null
            val latest = deque.lastOrNull() ?: return@mapNotNull null
            if (latest.midPrice <= 0.0) return@mapNotNull null
            val oneSecAgo = deque.lastOrNull { (nowMs - it.atEpochMs) >= 1_000L } ?: deque.firstOrNull() ?: return@mapNotNull null
            val baselineAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= hyperConfig.volumeBaselineWindowMs } ?: deque.firstOrNull()
            if (baselineAnchor == null) return@mapNotNull null
            val currentPerSecVolume = (latest.quoteVolume24h - oneSecAgo.quoteVolume24h).coerceAtLeast(0.0)
            val baselineDurationSec = ((latest.atEpochMs - baselineAnchor.atEpochMs).coerceAtLeast(1L) / 1000.0).coerceAtLeast(1.0)
            val baselineDelta = (latest.quoteVolume24h - baselineAnchor.quoteVolume24h).coerceAtLeast(0.0)
            val baselinePerSec = (baselineDelta / baselineDurationSec).coerceAtLeast(0.0000001)
            val sexyAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= hyperConfig.sexyWindowMs } ?: deque.firstOrNull()
            val superAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= hyperConfig.superSexyWindowMs } ?: deque.firstOrNull()
            val vDumpAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= hyperConfig.vShapeDumpWindowMs } ?: deque.firstOrNull()
            val vBounceAnchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= hyperConfig.vShapeBounceConfirmMs } ?: deque.firstOrNull()
            val sexyPriceDelta = if (sexyAnchor != null && sexyAnchor.midPrice > 0.0) ((latest.midPrice - sexyAnchor.midPrice) / sexyAnchor.midPrice) * 100.0 else 0.0
            val superPriceDelta = if (superAnchor != null && superAnchor.midPrice > 0.0) ((latest.midPrice - superAnchor.midPrice) / superAnchor.midPrice) * 100.0 else 0.0
            val dumpDelta = if (vDumpAnchor != null && vDumpAnchor.midPrice > 0.0) ((latest.midPrice - vDumpAnchor.midPrice) / vDumpAnchor.midPrice) * 100.0 else 0.0
            val bounceFromLowPct = deque.minOfOrNull { sample ->
                if (sample.midPrice > 0.0) ((latest.midPrice - sample.midPrice) / sample.midPrice) * 100.0 else 0.0
            } ?: 0.0
            val spreadNow = quote.spreadPct.coerceAtLeast(0.0)
            val spreadAnchor = sexyAnchor?.let { anchor ->
                val anchorBid = anchor.midPrice * 0.999
                val anchorAsk = anchor.midPrice * 1.001
                if (anchorBid > 0.0) ((anchorAsk - anchorBid) / anchorBid) * 100.0 else spreadNow
            } ?: spreadNow
            val spreadCompressionPct = if (spreadAnchor > 0.0) ((spreadAnchor - spreadNow) / spreadAnchor) * 100.0 else 0.0
            val sexy = sexyPriceDelta >= hyperConfig.sexyMinPriceDeltaPct &&
                currentPerSecVolume >= (baselinePerSec * hyperConfig.sexyMinVolumeAnomalyMultiplier) &&
                quote.recentTradeActivityScore >= hyperConfig.sexyMinTradeActivityScore
            val superSexy = superPriceDelta >= hyperConfig.superSexyMinPriceDeltaPct &&
                currentPerSecVolume >= (baselinePerSec * hyperConfig.superSexyMinVolumeAnomalyMultiplier)
            val vShape = dumpDelta <= -hyperConfig.vShapeMinDumpPct &&
                currentPerSecVolume >= (baselinePerSec * hyperConfig.vShapeBounceVolumeAnomalyMultiplier) &&
                bounceFromLowPct > 0.6 &&
                vBounceAnchor != null
            val wallSmash = currentPerSecVolume >= (baselinePerSec * hyperConfig.wallSmasherVolumeAnomalyMultiplier) &&
                spreadCompressionPct >= hyperConfig.wallSmasherMinSpreadCompressionPct &&
                quote.shortTermReturnPct >= 0.8
            val gradualUptrend = quote.shortTermReturnPct >= 0.25 &&
                quote.mediumTermReturnPct >= 0.12 &&
                kotlin.math.abs(quote.shortTermReturnPct - quote.mediumTermReturnPct) <= 1.35 &&
                quote.recentTradeActivityScore >= 0.42

            when {
                superSexy -> HyperTargetCandidate(quote.pairId, HyperTargetKind.SUPER_SEXY, 100.0 + superPriceDelta)
                vShape -> HyperTargetCandidate(quote.pairId, HyperTargetKind.V_SHAPE_BOUNCE, 90.0 + bounceFromLowPct)
                wallSmash -> HyperTargetCandidate(quote.pairId, HyperTargetKind.WALL_SMASH, 80.0 + quote.shortTermReturnPct)
                gradualUptrend -> HyperTargetCandidate(quote.pairId, HyperTargetKind.SEXY, 76.0 + quote.shortTermReturnPct)
                sexy -> HyperTargetCandidate(quote.pairId, HyperTargetKind.SEXY, 70.0 + sexyPriceDelta)
                else -> null
            }
        }
        val btcUp = marketQuotes.any {
            val key = it.pairId.value.lowercase()
            (key == "btc_usdt" || key == "btc_idr") && (it.shortTermReturnPct >= 0.18 || it.mediumTermReturnPct >= 0.10)
        }
        val ethUp = marketQuotes.any {
            val key = it.pairId.value.lowercase()
            (key == "eth_usdt" || key == "eth_idr") && (it.shortTermReturnPct >= 0.18 || it.mediumTermReturnPct >= 0.10)
        }
        val oracleTargets = if (btcUp || ethUp) {
            marketQuotes.asSequence()
                .filter { q ->
                    q.pairId.pairAssets().quoteAsset.equals("idr", ignoreCase = true) &&
                        q.pairId.value.lowercase() !in hiddenStablePairs &&
                        q.shortTermReturnPct >= -0.35 &&
                        q.mediumTermReturnPct >= 0.10 &&
                        q.recentTradeActivityScore >= 0.38 &&
                        q.estimatedSlippagePct <= 2.2
                }
                .map { HyperTargetCandidate(it.pairId, HyperTargetKind.SEXY, 88.0 + it.shortTermReturnPct) }
                .toList()
        } else {
            emptyList()
        }
        val sectors = dynamicSectorCorrelationBook
        val pumpedBases = directTargets
            .filter { it.score >= 80.0 }
            .map { it.pairId.pairAssets().baseAsset.lowercase() }
            .toSet()
        val sectorTargets = sectors.values.flatMap { sectorBases ->
            if (pumpedBases.none { it in sectorBases }) return@flatMap emptyList()
            marketQuotes
                .asSequence()
                .filter { q -> q.pairId.pairAssets().baseAsset.lowercase() in sectorBases }
                .filter { q -> q.recentTradeActivityScore >= 0.35 && q.estimatedSlippagePct <= 2.4 }
                .map { q -> HyperTargetCandidate(q.pairId, HyperTargetKind.SEXY, 84.0 + q.shortTermReturnPct) }
                .toList()
        }
        return (directTargets + oracleTargets + sectorTargets)
            .distinctBy { it.pairId }
            .sortedByDescending { it.score }
    }

    private suspend fun filterHyperTargetsByEnvironmentGuardrail(
        targets: List<HyperTargetCandidate>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        cycle: com.kibot.core.StrategyCycleResult,
    ): List<HyperTargetCandidate> {
        if (targets.isEmpty()) return emptyList()
        if (config.exchangeKind != ExchangeKind.INDODAX || !config.indodaxHyperGuardrailEnabled) return targets
        return targets.filter { target ->
            val quote = marketQuotes.firstOrNull { it.pairId == target.pairId } ?: return@filter false
            val expectedMomentumPct = maxOf(
                quote.shortTermReturnPct,
                quote.mediumTermReturnPct * 0.4,
                0.0,
            )
            val quoteBudget = minOf(
                cycle.deploymentPlan.suggestedPerPositionBudgetIdr.coerceAtLeast(50_000.0),
                cycle.portfolio.totalEquityIdr.toDoubleOrZero().coerceAtLeast(50_000.0) * 0.85,
            )
            val impact = runCatching {
                exchange.estimateMarketBuyImpact(
                    pairId = target.pairId,
                    quoteBudget = quoteBudget,
                )
            }.onFailure { error ->
                when (error) {
                    is SocketTimeoutException,
                    is CancellationException -> logger.error(
                        "Guardrail market impact timeout/cancelled pair={} budget={}",
                        target.pairId.value,
                        formatDecimal(quoteBudget, 2),
                        error,
                    )

                    else -> logger.error(
                        "Guardrail market impact failed pair={} budget={} reason={}",
                        target.pairId.value,
                        formatDecimal(quoteBudget, 2),
                        error.message ?: "unknown",
                        error,
                    )
                }
            }.getOrNull()
            val slippagePct = impact?.slippagePct ?: return@filter false
            val totalEntryCostPct = slippagePct + config.indodaxHyperGuardrailTakerFeePct
            val allowed = totalEntryCostPct <= expectedMomentumPct
            if (!allowed) {
                appendThrottledAuditLog(
                    now = Clock.System.now(),
                    level = LogLevel.INFO,
                    category = "HYPER_GUARDRAIL",
                    message = "Guardrail INDODAX blokir ${target.pairId.value}: cost ${formatDecimal(totalEntryCostPct, 2)}% > momentum ${formatDecimal(expectedMomentumPct, 2)}%.",
                )
                logger.warn(
                    "INDODAX guardrail blocked pair={} costPct={} momentumPct={}",
                    target.pairId.value,
                    formatDecimal(totalEntryCostPct, 2),
                    formatDecimal(expectedMomentumPct, 2),
                )
            }
            allowed
        }
    }

    private fun emitEngineHeartbeat(
        now: Instant,
        scannedPairs: Int,
        aggressive: Boolean,
    ) {
        // suppressed to reduce runtime noise
    }

    private suspend fun emitKingDashboardFastTelemetry(
        now: Instant,
        strategyCycle: com.kibot.core.StrategyCycleResult?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        displayPingMs: Long?,
    ) {
        val last = lastKingDashboardFastTelemetryAt
        if (last != null && (now - last).inWholeSeconds < 5) return
        lastKingDashboardFastTelemetryAt = now

        val totalBalanceIdr = strategyCycle?.portfolio?.totalEquityIdr?.toDoubleOrZero()
            ?: balances
                .firstOrNull { it.asset.equals("idr", ignoreCase = true) }
                ?.totalValueInIdr
                ?.toDoubleOrZero()
            ?: 0.0
        val activeLivePairs = strategyCycle
            ?.deploymentPlan
            ?.candidates
            ?.take(8)
            ?.map { it.pairId.value }
            ?.distinct()
            ?: marketQuotes.take(6).map { it.pairId.value }

        queueNonCriticalFastTelemetry(
            now = now,
            totalBalanceIdr = totalBalanceIdr,
            currentPingMs = displayPingMs,
            activeLivePairs = activeLivePairs,
        )
    }

    private fun isStagnantPair(
        pairId: com.kibot.shared.models.PairId,
        now: Instant,
    ): Boolean {
        val deque = hyperAggressivePulseByPair[pairId.value.lowercase()] ?: return false
        if (deque.size < 3) return false
        val nowMs = now.toEpochMilliseconds()
        val latest = deque.lastOrNull() ?: return false
        val anchor = deque.lastOrNull { (nowMs - it.atEpochMs) >= hyperConfig.stagnantWindowMs } ?: deque.firstOrNull()
        if (anchor == null || anchor.midPrice <= 0.0 || latest.midPrice <= 0.0) return false
        val deltaPct = kotlin.math.abs(((latest.midPrice - anchor.midPrice) / anchor.midPrice) * 100.0)
        return deltaPct < hyperConfig.stagnantMaxMovePct
    }

    private fun planHyperAggressiveRotationExit(
        now: Instant,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
        hungry: Boolean,
        sexyTargets: List<com.kibot.shared.models.PairId>,
        superSexyTarget: com.kibot.shared.models.PairId?,
    ): com.kibot.core.ExitDecision? {
        if (managedPositions.isEmpty()) return null
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val sexySet = sexyTargets.toSet()
        val rankedFallbackReplacement = cycle.rankedPairs
            .asSequence()
            .map { it.pairId }
            .firstOrNull { candidate -> managedPositions.none { it.pairId == candidate } }
        val replacement = superSexyTarget ?: sexyTargets.firstOrNull() ?: rankedFallbackReplacement
        if (replacement == null) return null
        return managedPositions.firstOrNull { position ->
            val noSellOrder = activeByPair[position.pairId].orEmpty().none { it.side == com.kibot.shared.models.OrderSide.SELL }
            val stagnant = isStagnantPair(position.pairId, now)
            val ageHours = ((now.toEpochMilliseconds() - position.openedAt.toEpochMilliseconds()).coerceAtLeast(0L) / 3_600_000.0)
            val timeBasedZombieStagnant = ageHours >= 1.0 && position.unrealizedPnlPct in -0.5..0.5
            val replacementDiff = replacement != position.pairId
            val replacementSexy = replacement in sexySet
            val lowProfit = position.unrealizedPnlPct < hyperConfig.allInLiquidationMaxPnlPct
            val allInLiquidation = superSexyTarget != null && replacementDiff && (stagnant || lowProfit)
            val adaptiveStagnantRotation = (stagnant || timeBasedZombieStagnant) && replacementDiff && (replacementSexy || position.unrealizedPnlPct <= 1.5)
            noSellOrder && (allInLiquidation || adaptiveStagnantRotation)
        }?.let { position ->
            val pairScore = cycle.rankedPairs.firstOrNull { it.pairId == position.pairId }
            val signal = com.kibot.shared.models.StrategySignal(
                pairId = position.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                confidence = (pairScore?.rankingScore ?: 0.70).coerceIn(0.55, 0.99),
                rationale = listOf("Posisi stagnant/TTL lewat wajib dibongkar untuk rotasi momentum."),
                entryPrice = position.currentBidPrice,
                takeProfitPrice = position.takeProfitPrice,
                stopPrice = position.stopPrice,
                setupType = position.setupType,
                horizon = position.horizon,
                pairTier = position.pairTier,
                speculativePocket = true,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = position.expectedHoldingHours,
                expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
            )
            com.kibot.core.ExitDecision(
                position = position,
                reason = com.kibot.core.ExitReason.ROTATION_EXIT,
                message = if (superSexyTarget != null) {
                    "HyperAggressive ALL_IN liquidation: ${position.pairId.value} -> ${replacement?.value}."
                } else if (((now.toEpochMilliseconds() - position.openedAt.toEpochMilliseconds()).coerceAtLeast(0L) / 3_600_000.0) >= 1.0 &&
                    position.unrealizedPnlPct in -0.5..0.5
                ) {
                    "Zombie TTL rotate: ${position.pairId.value} >1h stagnan, putar ke ${replacement?.value}."
                } else if (replacement in sexySet) {
                    "HyperAggressive rotation: ${position.pairId.value} stagnant, putar ke ${replacement?.value}."
                } else {
                    "Adaptive rotation: ${position.pairId.value} stagnan, diputar ke kandidat kuat ${replacement.value}."
                },
                executionPlan = com.kibot.shared.models.ExecutionPlan(
                    signal = signal,
                    side = com.kibot.shared.models.OrderSide.SELL,
                    orderType = com.kibot.shared.models.OrderType.MARKET,
                    quantity = position.quantity,
                    limitPrice = null,
                    quoteBudget = null,
                    postOnlyPreferred = false,
                    expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                    botMode = cycle.modeSnapshot.mode,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    pairRankingScore = pairScore?.rankingScore ?: 0.70,
                    speculativePocket = true,
                ),
            )
        }
    }

    private fun planHyperAggressiveTrailingExit(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
        hungry: Boolean,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.core.ExitDecision? {
        if (!hungry || managedPositions.isEmpty()) return null
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val scoredByPair = cycle.rankedPairs.associateBy { it.pairId }
        for (position in managedPositions) {
            val pairKey = position.pairId.value.lowercase()
            val currentBid = position.currentBidPrice.toDoubleOrZero()
            val peak = maxOf(hyperAggressivePeakBidByPair[pairKey] ?: currentBid, currentBid)
            hyperAggressivePeakBidByPair[pairKey] = peak
            val entryPx = position.averageEntryPrice.toDoubleOrZero().coerceAtLeast(0.0000001)
            val gainPct = ((peak - entryPx) / entryPx) * 100.0
            val pairScore = scoredByPair[position.pairId]
            val partialTaken = partialTakeProfitExecutedByPair[pairKey] == true
            val noSellOrder = activeByPair[position.pairId].orEmpty().none { it.side == com.kibot.shared.models.OrderSide.SELL }
            val qty = position.quantity.toDoubleOrZero()
            val bid = position.currentBidPrice.toDoubleOrZero()
            val notional = qty * bid
            if (!partialTaken && gainPct >= 10.0 && noSellOrder && qty > 0.0 && notional >= 10_000.0) {
                val signal = com.kibot.shared.models.StrategySignal(
                    pairId = position.pairId,
                    signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                    confidence = (pairScore?.rankingScore ?: 0.80).coerceIn(0.55, 0.99),
                    rationale = listOf("Scale out 50% untuk amankan modal/profit, sisanya let it ride."),
                    entryPrice = position.currentBidPrice,
                    takeProfitPrice = position.takeProfitPrice,
                    stopPrice = position.stopPrice,
                    setupType = position.setupType,
                    horizon = position.horizon,
                    pairTier = position.pairTier,
                    speculativePocket = true,
                    marketRegime = cycle.marketSnapshot.regime,
                    edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                    expectedHoldingHours = position.expectedHoldingHours,
                    expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
                )
                return com.kibot.core.ExitDecision(
                    position = position,
                    reason = com.kibot.core.ExitReason.PROFIT_EXIT,
                    message = "Partial take profit ${position.pairId.value}: lock 50% di >10%.",
                    executionPlan = com.kibot.shared.models.ExecutionPlan(
                        signal = signal,
                        side = com.kibot.shared.models.OrderSide.SELL,
                        orderType = com.kibot.shared.models.OrderType.MARKET,
                        quantity = DecimalValue.fromDouble(qty * 0.5),
                        limitPrice = null,
                        quoteBudget = null,
                        postOnlyPreferred = false,
                        expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                        botMode = cycle.modeSnapshot.mode,
                        riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                        pairRankingScore = pairScore?.rankingScore ?: 0.80,
                        speculativePocket = true,
                    ),
                )
            }
            val dynamicTrailingStopPct = dynamicTrailingStopPct(gainPct, currentBid)
            val armed = peak >= (entryPx * (1.0 + (hyperConfig.trailingArmMinGainPct / 100.0)))
            if (!armed) continue
            val shouldExitByTrail = currentBid <= (peak * (1.0 - (dynamicTrailingStopPct / 100.0))) && noSellOrder
            if (!shouldExitByTrail) continue
            val signal = com.kibot.shared.models.StrategySignal(
                pairId = position.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                confidence = (pairScore?.rankingScore ?: 0.72).coerceIn(0.50, 0.99),
                rationale = listOf("HyperAggressive elastic trailing stop terpukul, kunci profit."),
                entryPrice = position.currentBidPrice,
                takeProfitPrice = position.takeProfitPrice,
                stopPrice = position.stopPrice,
                setupType = position.setupType,
                horizon = position.horizon,
                pairTier = position.pairTier,
                speculativePocket = true,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = position.expectedHoldingHours,
                expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
            )
            com.kibot.core.ExitDecision(
                position = position,
                reason = com.kibot.core.ExitReason.PROFIT_PROTECTION_EXIT,
                message = "HyperAggressive trailing exit ${position.pairId.value}.",
                executionPlan = com.kibot.shared.models.ExecutionPlan(
                    signal = signal,
                    side = com.kibot.shared.models.OrderSide.SELL,
                    orderType = com.kibot.shared.models.OrderType.MARKET,
                    quantity = position.quantity,
                    limitPrice = null,
                    quoteBudget = null,
                    postOnlyPreferred = false,
                    expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                    botMode = cycle.modeSnapshot.mode,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    pairRankingScore = pairScore?.rankingScore ?: 0.72,
                    speculativePocket = true,
                ),
            )
        }
        return null
    }

    private fun computePeakWithRetroactiveHistory(
        pairId: com.kibot.shared.models.PairId,
        currentBid: Double,
        entryPrice: Double,
        sinceEpochMs: Long?,
    ): Pair<Double, Boolean> {
        val pairKey = pairId.value.lowercase()
        val forwardPeak = maxOf(localAutonomyPeakBidByPair[pairKey] ?: currentBid, currentBid)
        val retroPeak = if (config.exchangeKind == ExchangeKind.INDODAX) {
            fetchIndodaxHistoricalPeakSince(pairId, sinceEpochMs)
        } else {
            null
        }
        val chosenPeak = maxOf(forwardPeak, retroPeak ?: 0.0, entryPrice)
        val retroApplied = (retroPeak ?: 0.0) > (forwardPeak + 0.0000001)
        localAutonomyPeakBidByPair[pairKey] = chosenPeak
        return chosenPeak to retroApplied
    }

    private fun fetchIndodaxHistoricalPeakSince(
        pairId: com.kibot.shared.models.PairId,
        sinceEpochMs: Long?,
    ): Double? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        val pairKey = pairId.value.lowercase()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val cached = historicalPeakCacheByPair[pairKey]
        if (cached != null && (nowMs - cached.fetchedAtEpochMs) <= 60_000L) {
            return cached.peakPrice
        }
        val fromSec = ((sinceEpochMs ?: (nowMs - 6.hours.inWholeMilliseconds)).coerceAtLeast(0L) / 1000L).coerceAtLeast(1L)
        val toSec = (nowMs / 1000L).coerceAtLeast(fromSec + 60L)
        val symbol = pairId.value.replace("_", "")
        val url = "https://indodax.com/tradingview/history_v2?symbol=$symbol&tf=15&from=$fromSec&to=$toSec"
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build()
            val response = indodaxHistoryHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 300) return null
            val root = json.parseToJsonElement(response.body())
            val candles = root.jsonArray
            var peak = 0.0
            for (candle in candles) {
                val high = candle.jsonObject["High"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                if (high > peak) peak = high
            }
            if (peak > 0.0) {
                historicalPeakCacheByPair[pairKey] = HistoricalPeakCacheEntry(
                    peakPrice = peak,
                    fetchedAtEpochMs = nowMs,
                )
                peak
            } else {
                null
            }
        }.getOrNull()
    }

    private fun fetchIndodaxCandleHistoryGuardStats(
        pairId: com.kibot.shared.models.PairId,
    ): CandleHistoryGuardCacheEntry? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        val pairKey = pairId.value.lowercase()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val cached = candleHistoryGuardCacheByPair[pairKey]
        if (cached != null && (nowMs - cached.fetchedAtEpochMs) <= 60_000L) {
            return cached
        }
        val toSec = (nowMs / 1000L).coerceAtLeast(1L)
        val fromSec = (toSec - chartGuardLookbackSeconds).coerceAtLeast(1L)
        val symbol = pairId.value.replace("_", "")
        val url = "https://indodax.com/tradingview/history_v2?symbol=$symbol&tf=15&from=$fromSec&to=$toSec"
        return runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build()
            val response = indodaxHistoryHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 300) return null
            val candles = json.parseToJsonElement(response.body()).jsonArray
            var minLow = Double.MAX_VALUE
            var maxHigh = 0.0
            var count = 0
            var activeCount = 0
            var lastClose = 0.0
            val closeBuckets = linkedSetOf<String>()
            val closeBucketSequence = mutableListOf<String>()
            val closes = mutableListOf<Double>()
            var previousClose: Double? = null
            var lastDirection = 0
            var directionalMoves = 0
            var directionFlips = 0
            var previousHigh: Double? = null
            var previousLow: Double? = null
            var higherHighs = 0
            var higherLows = 0
            var structureComparisons = 0
            for (candle in candles) {
                val high = candle.jsonObject["High"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val low = candle.jsonObject["Low"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val open = candle.jsonObject["Open"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val close = candle.jsonObject["Close"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val volume = candle.jsonObject["Volume"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    ?: candle.jsonObject["Vol"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                if (high == null || low == null || high <= 0.0 || low <= 0.0) continue
                count += 1
                if (high > maxHigh) maxHigh = high
                if (low < minLow) minLow = low
                if (close != null && close > 0.0) {
                    lastClose = close
                    val bucket = priceBucketKey(close)
                    closeBuckets += bucket
                    closeBucketSequence += bucket
                    closes += close
                    val priorClose = previousClose
                    if (priorClose != null && priorClose > 0.0) {
                        val delta = close - priorClose
                        val direction = when {
                            delta > 0.0 -> 1
                            delta < 0.0 -> -1
                            else -> 0
                        }
                        if (direction != 0) {
                            directionalMoves += 1
                            if (lastDirection != 0 && direction != lastDirection) directionFlips += 1
                            lastDirection = direction
                        }
                    }
                    previousClose = close
                }
                val priorHigh = previousHigh
                val priorLow = previousLow
                if (priorHigh != null && priorLow != null) {
                    structureComparisons += 1
                    if (high > priorHigh) higherHighs += 1
                    if (low > priorLow) higherLows += 1
                }
                previousHigh = high
                previousLow = low
                val moved = if (open != null && close != null && open > 0.0) {
                    kotlin.math.abs(close - open) / open
                } else {
                    0.0
                }
                if ((volume ?: 0.0) > 0.0 || moved >= 0.001) activeCount += 1
            }
            val dominantCloseShare = closeBucketSequence
                .groupingBy { it }
                .eachCount()
                .values
                .maxOrNull()
                ?.toDouble()
                ?.div(closeBucketSequence.size.coerceAtLeast(1).toDouble())
                ?: 0.0
            val directionFlipRate = if (directionalMoves > 1) {
                directionFlips.toDouble() / directionalMoves.toDouble()
            } else {
                0.0
            }
            val higherHighRatio = if (structureComparisons > 0) {
                higherHighs.toDouble() / structureComparisons.toDouble()
            } else {
                0.0
            }
            val higherLowRatio = if (structureComparisons > 0) {
                higherLows.toDouble() / structureComparisons.toDouble()
            } else {
                0.0
            }
            val minClose = closes.minOrNull() ?: 0.0
            val maxClose = closes.maxOrNull() ?: 0.0
            val closingProgressRatio = if (maxClose > minClose && lastClose > 0.0) {
                ((lastClose - minClose) / (maxClose - minClose)).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val firstClose = closes.firstOrNull() ?: 0.0
            val netProgressPct = if (firstClose > 0.0 && lastClose > 0.0) {
                ((lastClose - firstClose) / firstClose) * 100.0
            } else {
                0.0
            }
            val rangePct = if (count > 0 && minLow > 0.0 && maxHigh >= minLow) {
                ((maxHigh - minLow) / minLow) * 100.0
            } else {
                0.0
            }
            CandleHistoryGuardCacheEntry(
                candleCount = count,
                activeCandleCount = activeCount,
                distinctCloseBuckets = closeBuckets.size,
                rangePct = rangePct.coerceAtLeast(0.0),
                lastClose = lastClose.coerceAtLeast(0.0),
                dominantCloseShare = dominantCloseShare.coerceIn(0.0, 1.0),
                directionFlipRate = directionFlipRate.coerceIn(0.0, 1.0),
                higherHighRatio = higherHighRatio.coerceIn(0.0, 1.0),
                higherLowRatio = higherLowRatio.coerceIn(0.0, 1.0),
                closingProgressRatio = closingProgressRatio.coerceIn(0.0, 1.0),
                netProgressPct = netProgressPct,
                fetchedAtEpochMs = nowMs,
            ).also { candleHistoryGuardCacheByPair[pairKey] = it }
        }.getOrNull()
    }

    private fun priceBucketKey(price: Double): String {
        if (price <= 0.0) return "0"
        return when {
            price >= 1_000.0 -> formatDecimal(price, 0)
            price >= 100.0 -> formatDecimal(price, 1)
            price >= 1.0 -> formatDecimal(price, 2)
            else -> formatDecimal(price, 6)
        }
    }

    private fun enrichRuntimeMarketQuotes(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        balances: List<BalanceSnapshot>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.MarketQuote> {
        if (config.exchangeKind != ExchangeKind.INDODAX || marketQuotes.isEmpty()) return marketQuotes
        val activePairs = openOrders.map { it.pairId }.toSet() +
            balances
                .asSequence()
                .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
                .map { PairId("${it.asset.lowercase()}_${referenceQuoteAsset()}") }
                .toSet()
        val focusPairs = marketQuotes
            .sortedByDescending { quote ->
                quote.quoteVolume24h.toDoubleOrZero() * 0.45 +
                    quote.recentTradeActivityScore * 1_000_000.0 +
                    kotlin.math.abs(quote.shortTermReturnPct) * 150_000.0 +
                    quote.trendQualityScore * 800_000.0
            }
            .take(runtimeEnrichmentFocusPairs)
            .map { it.pairId }
            .toMutableSet()
            .also { it.addAll(activePairs) }
        if (focusPairs.isEmpty()) return marketQuotes
        return marketQuotes.map { quote ->
            if (quote.pairId !in focusPairs) {
                quote
            } else {
                buildRuntimeEnrichedQuote(now, quote)
            }
        }
    }

    private fun buildRuntimeEnrichedQuote(
        now: Instant,
        quote: com.kibot.shared.models.MarketQuote,
    ): com.kibot.shared.models.MarketQuote {
        if (config.exchangeKind != ExchangeKind.INDODAX) return quote
        val pairKey = quote.pairId.value.lowercase()
        val cache = multiTimeframeQuoteCacheByPair[pairKey]
        if (cache != null && (now.toEpochMilliseconds() - cache.fetchedAtEpochMs) <= runtimeEnrichmentCacheTtlMs) {
            return cache.quote.copy(capturedAt = quote.capturedAt)
        }
        val history = fetchIndodaxCandleHistoryGuardStats(quote.pairId) ?: return quote
        val rsi = deriveRsi14FromGuard(history)
        val emaFastOverSlowPct = deriveEmaTrendPct(quote, history)
        val vwapDistancePct = deriveRuntimeVwapDistancePct(quote, history)
        val tickFrequencyPerMinute = deriveTickFrequencyPerMinute(history)
        val orderBookImbalance = deriveRuntimeOrderBookImbalance(quote)
        val zScoreCurrent = deriveRuntimeZScore(quote, history)
        val keltnerExtensionScore = deriveRuntimeKeltnerExtensionScore(
            quote = quote,
            history = history,
            vwapDistancePct = vwapDistancePct,
            zScoreCurrent = zScoreCurrent,
        )
        val cvdDivergenceScore = deriveRuntimeCvdDivergenceScore(
            quote = quote,
            history = history,
            orderBookImbalance = orderBookImbalance,
            zScoreCurrent = zScoreCurrent,
        )
        val smartMoneyIndex = deriveRuntimeSmartMoneyIndex(
            quote = quote,
            orderBookImbalance = orderBookImbalance,
            cvdDivergenceScore = cvdDivergenceScore,
        )
        val seasonalityMultiplier = deriveRuntimeSeasonalityMultiplier(now)
        val localAnomalyScore = deriveLocalAnomalyScore(quote, history)
        val toxicFlowScore = deriveRuntimeToxicFlowScore(quote, history)
        val enriched = quote.copy(
            vwapDistancePct = vwapDistancePct,
            rsi14 = rsi,
            emaFastOverSlowPct = emaFastOverSlowPct,
            tickFrequencyPerMinute = tickFrequencyPerMinute,
            orderBookImbalance = orderBookImbalance,
            zScoreCurrent = zScoreCurrent,
            cvdDivergenceScore = cvdDivergenceScore,
            smartMoneyIndex = smartMoneyIndex,
            seasonalityMultiplier = seasonalityMultiplier,
            keltnerExtensionScore = keltnerExtensionScore,
            localAnomalyScore = localAnomalyScore,
            toxicFlowScore = maxOf(quote.toxicFlowScore, toxicFlowScore),
            capturedAt = quote.capturedAt,
        )
        multiTimeframeQuoteCacheByPair[pairKey] = MultiTimeframeQuoteCacheEntry(
            quote = enriched,
            fetchedAtEpochMs = now.toEpochMilliseconds(),
        )
        return enriched
    }

    private fun deriveRsi14FromGuard(history: CandleHistoryGuardCacheEntry): Double {
        val trendBias = ((history.higherHighRatio + history.higherLowRatio) / 2.0).coerceIn(0.0, 1.0)
        val progressBias = ((history.closingProgressRatio * 0.7) + (history.netProgressPct / 8.0)).coerceIn(0.0, 1.0)
        val reversalPenalty = history.directionFlipRate.coerceIn(0.0, 1.0) * 0.45
        return (38.0 + (trendBias * 22.0) + (progressBias * 24.0) - (reversalPenalty * 20.0))
            .coerceIn(15.0, 88.0)
    }

    private fun deriveEmaTrendPct(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
    ): Double {
        return (
            (history.higherHighRatio - 0.5) * 4.2 +
                (history.higherLowRatio - 0.5) * 4.2 +
                (history.closingProgressRatio - 0.5) * 2.4 +
                (quote.shortTermReturnPct * 0.16) +
                (quote.mediumTermReturnPct * 0.08)
            ).coerceIn(-6.0, 6.0)
    }

    private fun deriveRuntimeVwapDistancePct(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
    ): Double {
        val price = quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: return quote.vwapDistancePct
        if (history.lastClose <= 0.0) return quote.vwapDistancePct
        val syntheticVwap = history.lastClose / (1.0 + ((history.closingProgressRatio - 0.5) * 0.04))
        if (syntheticVwap <= 0.0) return quote.vwapDistancePct
        return (((price - syntheticVwap) / syntheticVwap) * 100.0).coerceIn(-8.0, 8.0)
    }

    private fun deriveTickFrequencyPerMinute(history: CandleHistoryGuardCacheEntry): Double {
        return (history.activeCandleCount.toDouble() / runtimeEnrichmentLookbackMinutes.toDouble())
            .coerceAtLeast(0.0)
    }

    private fun deriveRuntimeZScore(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
    ): Double {
        val price = quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: return 0.0
        val mean = history.lastClose.takeIf { it > 0.0 } ?: price
        val rangePct = history.rangePct.coerceAtLeast(0.12)
        val sigma = (mean * (rangePct / 100.0) * 0.42).coerceAtLeast(mean * 0.0012)
        return ((price - mean) / sigma).coerceIn(-6.0, 6.0)
    }

    private fun deriveRuntimeKeltnerExtensionScore(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
        vwapDistancePct: Double,
        zScoreCurrent: Double,
    ): Double {
        val atrProxyPct = maxOf(history.rangePct * 0.34, quote.realizedVolatilityPct * 0.70, 0.35)
        val extensionFromCenter = kotlin.math.abs(vwapDistancePct) / (atrProxyPct * 1.45)
        val extensionFromStatistic = kotlin.math.abs(zScoreCurrent) / 3.2
        return ((extensionFromCenter * 0.58) + (extensionFromStatistic * 0.42)).coerceIn(0.0, 1.0)
    }

    private fun deriveRuntimeCvdDivergenceScore(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
        orderBookImbalance: Double,
        zScoreCurrent: Double,
    ): Double {
        val pricePush = (quote.shortTermReturnPct.coerceAtLeast(0.0) / 4.0).coerceIn(0.0, 1.0)
        val exhaustionFlow = ((-orderBookImbalance).coerceAtLeast(0.0) * 0.46)
        val weakClose = history.dominantCloseShare.coerceIn(0.0, 1.0) * 0.22
        val statisticalStretch = (kotlin.math.abs(zScoreCurrent) / 4.0).coerceIn(0.0, 1.0) * 0.18
        val divergence = if (pricePush > 0.18) {
            exhaustionFlow + weakClose + statisticalStretch
        } else {
            (history.directionFlipRate.coerceIn(0.0, 1.0) * 0.26) + weakClose
        }
        return divergence.coerceIn(0.0, 1.0)
    }

    private fun deriveRuntimeSmartMoneyIndex(
        quote: com.kibot.shared.models.MarketQuote,
        orderBookImbalance: Double,
        cvdDivergenceScore: Double,
    ): Double {
        val flowSupport = ((orderBookImbalance + 1.0) / 2.0).coerceIn(0.0, 1.0)
        val activitySupport = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        val depthSupport = (
            quote.bidDepthTop5Idr.toDoubleOrZero() /
                (quote.askDepthTop5Idr.toDoubleOrZero().coerceAtLeast(1.0))
            ).coerceIn(0.35, 1.80) / 1.80
        val stabilitySupport = quote.orderBookStabilityScore.coerceIn(0.0, 1.0)
        val divergencePenalty = 1.0 - cvdDivergenceScore.coerceIn(0.0, 1.0)
        return (
            flowSupport * 0.28 +
                activitySupport * 0.26 +
                depthSupport * 0.18 +
                stabilitySupport * 0.16 +
                divergencePenalty * 0.12
            ).coerceIn(0.0, 1.0)
    }

    private fun deriveRuntimeSeasonalityMultiplier(now: Instant): Double {
        val local = now.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hour = local.hour
        val weekendPenalty = if (local.date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) 0.18 else 0.0
        val sessionBoost = when (hour) {
            in 20..23 -> 0.14
            in 0..3 -> 0.18
            in 7..10 -> 0.06
            else -> 0.0
        }
        val deadSessionPenalty = when (hour) {
            in 11..16 -> 0.08
            in 4..6 -> 0.05
            else -> 0.0
        }
        return (1.0 + sessionBoost - deadSessionPenalty - weekendPenalty).coerceIn(0.55, 1.24)
    }

    private fun deriveRuntimeOrderBookImbalance(
        quote: com.kibot.shared.models.MarketQuote,
    ): Double {
        val bid = quote.bidDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
        val ask = quote.askDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
        val denom = (bid + ask).takeIf { it > 0.0 } ?: return quote.orderBookImbalance
        return ((bid - ask) / denom).coerceIn(-1.0, 1.0)
    }

    private fun deriveLocalAnomalyScore(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
    ): Double {
        val isolatedMove = kotlin.math.abs(quote.shortTermReturnPct - quote.mediumTermReturnPct).coerceAtLeast(0.0)
        val structurePenalty = history.directionFlipRate * 0.35 + history.dominantCloseShare * 0.30
        return ((isolatedMove / 6.0) + (history.rangePct / 12.0) - structurePenalty).coerceIn(0.0, 1.0)
    }

    private fun deriveRuntimeToxicFlowScore(
        quote: com.kibot.shared.models.MarketQuote,
        history: CandleHistoryGuardCacheEntry,
    ): Double {
        val flipPenalty = history.directionFlipRate.coerceIn(0.0, 1.0) * 0.42
        val deadPenalty = history.dominantCloseShare.coerceIn(0.0, 1.0) * 0.28
        val whipsawPenalty = if (history.rangePct > 0.0) {
            (quote.realizedVolatilityPct / history.rangePct).coerceIn(0.0, 1.0) * 0.20
        } else {
            0.0
        }
        val stressPenalty = kotlin.math.abs(deriveRuntimeOrderBookImbalance(quote)) * (1.0 - quote.orderBookStabilityScore.coerceIn(0.0, 1.0)) * 0.18
        return (flipPenalty + deadPenalty + whipsawPenalty + stressPenalty).coerceIn(0.0, 1.0)
    }

    private fun updateOrderBookSpoofRadar(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        marketQuotes.forEach { quote ->
            val pairKey = quote.pairId.value.lowercase()
            val deque = spoofPulseByPair.getOrPut(pairKey) { ArrayDeque() }
            deque.addLast(
                OrderBookPulseSample(
                    atEpochMs = now.toEpochMilliseconds(),
                    imbalance = quote.orderBookImbalance.coerceIn(-1.0, 1.0),
                    bidDepthIdr = quote.bidDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0),
                    askDepthIdr = quote.askDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0),
                    stabilityScore = quote.orderBookStabilityScore.coerceIn(0.0, 1.0),
                ),
            )
            while (deque.size > spoofPulseWindowSamples) deque.removeFirst()
            val samples = deque.toList()
            if (samples.size < 3) return@forEach
            val signFlips = samples.zipWithNext().count { (left, right) ->
                left.imbalance != 0.0 && right.imbalance != 0.0 &&
                    kotlin.math.sign(left.imbalance) != kotlin.math.sign(right.imbalance)
            }
            val averageStability = samples.map { it.stabilityScore }.average()
            val depthBaseline = samples.first().run { bidDepthIdr + askDepthIdr }.coerceAtLeast(1.0)
            val depthSwing = samples.maxOf { kotlin.math.abs((it.bidDepthIdr + it.askDepthIdr) - depthBaseline) } / depthBaseline
            val maxImbalance = samples.maxOf { kotlin.math.abs(it.imbalance) }
            if (signFlips >= 2 && averageStability < 0.55 && depthSwing >= 0.32 && maxImbalance >= 0.52) {
                spoofSuspiciousUntilByPair[pairKey] = now.plus(spoofSuspicionCooldown)
            }
        }
    }

    private fun routeByAntiSpoofRadar(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        now: Instant,
    ): com.kibot.shared.models.ExecutionPlan? {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return executionPlan
        val pairKey = executionPlan.signal.pairId.value.lowercase()
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId } ?: return executionPlan
        val suspicionUntil = spoofSuspiciousUntilByPair[pairKey]
        val unstablePressure = kotlin.math.abs(quote.orderBookImbalance) >= 0.55 && quote.orderBookStabilityScore <= 0.42
        if ((suspicionUntil == null || now >= suspicionUntil) && !unstablePressure) return executionPlan
        if (quote.estimatedSlippagePct >= 1.35 && quote.spreadPct >= 0.85) return null
        val passivePrice = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
            ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
            ?: return executionPlan
        return executionPlan.copy(
            orderType = com.kibot.shared.models.OrderType.LIMIT,
            limitPrice = DecimalValue.fromDouble(passivePrice),
            postOnlyPreferred = false,
        )
    }

    private fun applyAdaptiveOrderSlicing(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return executionPlan
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId } ?: return executionPlan
        val budgetIdr = executionPlan.quoteBudget?.toDoubleOrZero()?.takeIf { it > 0.0 }
            ?: return executionPlan
        val topDepthIdr = quote.askDepthTop5Idr.toDoubleOrZero().takeIf { it > 0.0 } ?: return executionPlan
        val spoofPenalty = if (spoofSuspiciousUntilByPair[executionPlan.signal.pairId.value.lowercase()] != null) 0.72 else 1.0
        val sliceBudgetIdr = minOf(
            budgetIdr,
            topDepthIdr * depthGuardMaxTopBookImpactPct * spoofPenalty,
        ).coerceAtLeast(minimumLiveNotionalForExchange())
        if (sliceBudgetIdr >= budgetIdr * 0.96) return executionPlan
        val referencePrice = executionPlan.limitPrice?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: quote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 }
            ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
            ?: return executionPlan
        val slicedQty = (sliceBudgetIdr / referencePrice).coerceAtLeast(0.00000001)
        return executionPlan.copy(
            quantity = DecimalValue.fromDouble(slicedQty),
            quoteBudget = DecimalValue.fromDouble(sliceBudgetIdr),
        )
    }

    private fun captureLocalTrailingSnapshots(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<LocalTrailingSnapshot> {
        if (managedPositions.isEmpty() && balances.isEmpty()) {
            localAutonomyTrailingFloorByPair.clear()
            return emptyList()
        }
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val managedSnapshots = managedPositions.mapNotNull { position ->
            val pairKey = position.pairId.value.lowercase()
            val currentBid = position.currentBidPrice.toDoubleOrZero()
            if (currentBid <= 0.0) return@mapNotNull null
            val entryPx = position.averageEntryPrice.toDoubleOrZero().coerceAtLeast(0.0000001)
            val recentBuySinceMs = recentOrders
                .asSequence()
                .filter { it.pairId == position.pairId }
                .filter { it.side == com.kibot.shared.models.OrderSide.BUY }
                .filter { it.status == com.kibot.shared.models.OrderStatus.FILLED || it.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED }
                .maxByOrNull { it.updatedAt.toEpochMilliseconds() }
                ?.updatedAt
                ?.toEpochMilliseconds()
            val (peak, retroApplied) = computePeakWithRetroactiveHistory(
                pairId = position.pairId,
                currentBid = currentBid,
                entryPrice = entryPx,
                sinceEpochMs = recentBuySinceMs,
            )
            val gainPct = ((peak - entryPx) / entryPx) * 100.0
            val dynamicTrailingStopPct = dynamicTrailingStopPct(gainPct, currentBid)
            val armed = peak >= (entryPx * (1.0 + (hyperConfig.trailingArmMinGainPct / 100.0)))
            val floor = peak * (1.0 - (dynamicTrailingStopPct / 100.0))
            val notional = position.currentValueIdr.toDoubleOrZero().takeIf { it > 0.0 }
                ?: (currentBid * position.quantity.toDoubleOrZero().coerceAtLeast(0.0))
            if (notional < dustUiHideMinValueIdr) return@mapNotNull null
            val hasActiveSellOrder = activeByPair[position.pairId].orEmpty().any { it.side == com.kibot.shared.models.OrderSide.SELL }
            val snapshot = LocalTrailingSnapshot(
                pair = position.pairId,
                entryPrice = entryPx,
                peakPrice = peak,
                floorPrice = floor,
                currentBid = currentBid,
                dynamicTrailingStopPct = dynamicTrailingStopPct,
                armed = armed && !hasActiveSellOrder,
                retroactivePeakApplied = retroApplied,
            )
            val previousFloor = localAutonomyTrailingFloorLogByPair[pairKey]
            if (previousFloor == null || kotlin.math.abs(previousFloor - floor) > 0.0000001) {
                logger.info(
                    "TRAILING_FLOOR_UPDATED pair={} entry={} peak={} floor={} current={} armed={} trailDropPct={}",
                    pairKey,
                    formatDecimal(entryPx, 8),
                    formatDecimal(peak, 8),
                    formatDecimal(floor, 8),
                    formatDecimal(currentBid, 8),
                    snapshot.armed,
                    formatDecimal(dynamicTrailingStopPct, 2),
                )
                localAutonomyTrailingFloorLogByPair[pairKey] = floor
            }
            snapshot
        }
        val managedKeys = managedSnapshots.map { it.pair.value.lowercase() }.toSet()
        val recentFilledBuyPriceByPair = recentOrders
            .asSequence()
            .filter { it.side == com.kibot.shared.models.OrderSide.BUY }
            .filter { it.status == com.kibot.shared.models.OrderStatus.FILLED || it.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED }
            .sortedByDescending { it.updatedAt.toEpochMilliseconds() }
            .associate { it.pairId.value.lowercase() to it.price.toDoubleOrZero() }
        val fallbackSnapshots = balances
            .asSequence()
            .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val pairValue = "${balance.asset.lowercase()}_${referenceQuoteAsset()}"
                if (pairValue in managedKeys) return@mapNotNull null
                val pairId = com.kibot.shared.models.PairId(pairValue)
                val quote = marketQuotes.firstOrNull { it.pairId == pairId } ?: return@mapNotNull null
                val currentBid = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: return@mapNotNull null
                val entryPx = recentFilledBuyPriceByPair[pairValue]
                    ?.takeIf { it > 0.0 }
                    ?: currentBid
                val recentBuySinceMs = recentOrders
                    .asSequence()
                    .filter { it.pairId.value.equals(pairValue, ignoreCase = true) }
                    .filter { it.side == com.kibot.shared.models.OrderSide.BUY }
                    .filter { it.status == com.kibot.shared.models.OrderStatus.FILLED || it.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED }
                    .maxByOrNull { it.updatedAt.toEpochMilliseconds() }
                    ?.updatedAt
                    ?.toEpochMilliseconds()
                val (peak, retroApplied) = computePeakWithRetroactiveHistory(
                    pairId = pairId,
                    currentBid = currentBid,
                    entryPrice = entryPx,
                    sinceEpochMs = recentBuySinceMs,
                )
                val gainPct = ((peak - entryPx) / entryPx.coerceAtLeast(0.0000001)) * 100.0
                val dynamicTrailingStopPct = dynamicTrailingStopPct(gainPct, currentBid)
                val armed = peak >= (entryPx * (1.0 + (hyperConfig.trailingArmMinGainPct / 100.0)))
                val floor = peak * (1.0 - (dynamicTrailingStopPct / 100.0))
                val notional = currentBid * quantity
                if (notional < dustUiHideMinValueIdr) return@mapNotNull null
                val hasActiveSellOrder = activeByPair[pairId].orEmpty().any { it.side == com.kibot.shared.models.OrderSide.SELL }
                val snapshot = LocalTrailingSnapshot(
                    pair = pairId,
                    entryPrice = entryPx,
                    peakPrice = peak,
                    floorPrice = floor,
                    currentBid = currentBid,
                    dynamicTrailingStopPct = dynamicTrailingStopPct,
                    armed = armed && !hasActiveSellOrder,
                    retroactivePeakApplied = retroApplied,
                )
                val previousFloor = localAutonomyTrailingFloorLogByPair[pairValue]
                if (previousFloor == null || kotlin.math.abs(previousFloor - floor) > 0.0000001) {
                    logger.info(
                        "TRAILING_FLOOR_UPDATED pair={} entry={} peak={} floor={} current={} armed={} trailDropPct={}",
                        pairValue,
                        formatDecimal(entryPx, 8),
                        formatDecimal(peak, 8),
                        formatDecimal(floor, 8),
                        formatDecimal(currentBid, 8),
                        snapshot.armed,
                        formatDecimal(dynamicTrailingStopPct, 2),
                    )
                    localAutonomyTrailingFloorLogByPair[pairValue] = floor
                }
                snapshot
            }
            .toList()
        val snapshots = managedSnapshots + fallbackSnapshots
        val activeKeys = snapshots.map { it.pair.value.lowercase() }.toSet()
        localAutonomyTrailingFloorByPair.keys.toList().forEach { key ->
            if (key !in activeKeys) localAutonomyTrailingFloorByPair.remove(key)
        }
        snapshots.forEach { snap ->
            localAutonomyTrailingFloorByPair[snap.pair.value.lowercase()] = snap
        }
        return snapshots
    }

    private fun planLocalAutonomyTrailingExit(
        snapshots: List<LocalTrailingSnapshot>,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        balances: List<BalanceSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
    ): com.kibot.core.ExitDecision? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (snapshots.isEmpty()) return null
        val scoredByPair = cycle.rankedPairs.associateBy { it.pairId }
        fun quantityForSnapshot(snapshot: LocalTrailingSnapshot): Double {
            val fromManaged = managedPositions
                .firstOrNull { it.pairId == snapshot.pair }
                ?.quantity
                ?.toDoubleOrZero()
                ?: 0.0
            if (fromManaged > 0.0) return fromManaged
            val baseAsset = snapshot.pair.value.substringBefore("_")
            return balances
                .firstOrNull { it.asset.equals(baseAsset, ignoreCase = true) }
                ?.let { it.free.toDoubleOrZero() + it.locked.toDoubleOrZero() }
                ?.coerceAtLeast(0.0)
                ?: 0.0
        }
        fun notionalForSnapshot(snapshot: LocalTrailingSnapshot): Double {
            return snapshot.currentBid * quantityForSnapshot(snapshot).coerceAtLeast(0.0)
        }
        val minimumNotional = minimumLiveNotionalForExchange()
        val snapshot = snapshots
            .asSequence()
            .filter { it.armed && it.currentBid <= it.floorPrice }
            .filter { notionalForSnapshot(it) >= minimumNotional }
            .maxByOrNull {
                val breachPct = (it.floorPrice - it.currentBid) / it.floorPrice.coerceAtLeast(0.0000001)
                val qty = quantityForSnapshot(it).coerceAtLeast(0.0)
                val notionalBreach = (it.floorPrice - it.currentBid) * qty
                (breachPct * 10_000.0) + notionalBreach
            }
            ?: return null
        val position = managedPositions.firstOrNull { it.pairId == snapshot.pair }
        val pairScore = scoredByPair[snapshot.pair]
        val quantity = position?.quantity
            ?: run {
                val baseAsset = snapshot.pair.value.substringBefore("_")
                val balanceQty = balances
                    .firstOrNull { it.asset.equals(baseAsset, ignoreCase = true) }
                    ?.let { it.free.toDoubleOrZero() + it.locked.toDoubleOrZero() }
                    ?.coerceAtLeast(0.0)
                    ?: 0.0
                if (balanceQty <= 0.0) return null
                DecimalValue.fromDouble(balanceQty)
            }
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = snapshot.pair,
            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
            confidence = (pairScore?.rankingScore ?: 0.78).coerceIn(0.60, 0.99),
            rationale = listOf("Local autonomy trailing floor terpukul, amankan profit/kerugian lokal tanpa tunggu veto eksternal."),
            entryPrice = DecimalValue.fromDouble(snapshot.currentBid),
            takeProfitPrice = null,
            stopPrice = DecimalValue.fromDouble(snapshot.floorPrice),
            setupType = position?.setupType ?: com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION,
            horizon = position?.horizon ?: com.kibot.shared.models.TradingHorizon.TACTICAL,
            pairTier = position?.pairTier ?: com.kibot.shared.models.PairTier.TIER_B,
            speculativePocket = true,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = position?.expectedHoldingHours ?: 0.0,
            expectedNetProfitabilityPct = kotlin.math.abs(position?.unrealizedPnlPct ?: 0.0),
        )
        val sellReason = if (snapshot.retroactivePeakApplied) {
            "retroactive_trailing_stop_hit"
        } else {
            "local_trailing_stop_hit"
        }
        logger.info(
            "SELL_DECISION_REASON pair={} reason={} current={} floor={} peak={} trailDropPct={}",
            snapshot.pair.value,
            sellReason,
            formatDecimal(snapshot.currentBid, 8),
            formatDecimal(snapshot.floorPrice, 8),
            formatDecimal(snapshot.peakPrice, 8),
            formatDecimal(snapshot.dynamicTrailingStopPct, 2),
        )
        return com.kibot.core.ExitDecision(
            position = position ?: com.kibot.core.ManagedPosition(
                pairId = snapshot.pair,
                quantity = quantity,
                averageEntryPrice = DecimalValue.fromDouble(snapshot.entryPrice),
                currentBidPrice = DecimalValue.fromDouble(snapshot.currentBid),
                currentValueIdr = DecimalValue.fromDouble(snapshot.currentBid * quantity.toDoubleOrZero()),
                unrealizedPnlIdr = DecimalValue.Zero,
                unrealizedPnlPct = if (snapshot.entryPrice > 0.0) {
                    ((snapshot.currentBid - snapshot.entryPrice) / snapshot.entryPrice) * 100.0
                } else {
                    0.0
                },
                breakEvenPrice = DecimalValue.fromDouble(snapshot.entryPrice),
                openedAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
                setupType = com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION,
                horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
                pairTier = com.kibot.shared.models.PairTier.TIER_B,
                takeProfitPrice = DecimalValue.fromDouble(snapshot.peakPrice),
                stopPrice = DecimalValue.fromDouble(snapshot.floorPrice),
                speculativePocket = true,
                expectedHoldingHours = 0.0,
            ),
            reason = com.kibot.core.ExitReason.PROFIT_PROTECTION_EXIT,
            message = "LOCAL_AUTONOMY_TRAILING ${snapshot.pair.value}: bid ${formatDecimal(snapshot.currentBid, 8)} <= floor ${formatDecimal(snapshot.floorPrice, 8)}.",
            executionPlan = com.kibot.shared.models.ExecutionPlan(
                signal = signal,
                side = com.kibot.shared.models.OrderSide.SELL,
                orderType = com.kibot.shared.models.OrderType.MARKET,
                quantity = quantity,
                limitPrice = null,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(position?.unrealizedPnlPct ?: 0.0),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                pairRankingScore = pairScore?.rankingScore ?: 0.78,
                speculativePocket = true,
            ),
        )
    }

    private fun enforceMaxSpreadCapForMarketBuy(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return executionPlan
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return executionPlan
        if (executionPlan.orderType != com.kibot.shared.models.OrderType.MARKET) return executionPlan
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId } ?: return executionPlan
        val spreadPct = quote.spreadPct.coerceAtLeast(0.0)
        if (spreadPct <= 1.5) return executionPlan
        val mid = quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: return null
        return executionPlan.copy(
            orderType = com.kibot.shared.models.OrderType.LIMIT,
            limitPrice = DecimalValue.fromDouble(mid),
            postOnlyPreferred = false,
        )
    }

    private fun minimumLiveNotionalForExchange(): Double = when (config.exchangeKind) {
        ExchangeKind.INDODAX -> 10_000.0
        ExchangeKind.BINANCE_SPOT -> 7.5
    }

    private fun entryBlockedByBlueChipVolume(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): String? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return null
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId } ?: return null
        val volumeIdr = quote.quoteVolume24h.toDoubleOrZero().coerceAtLeast(0.0)
        if (volumeIdr >= config.blueChipMinDailyVolumeIdr) return null
        return "bluechip_volume_blocked volume24h=${formatDecimal(volumeIdr, 0)} min=${formatDecimal(config.blueChipMinDailyVolumeIdr, 0)}"
    }

    private fun entryBlockedByShortFlatChart(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
    ): String? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return null
        val pairId = executionPlan.signal.pairId
        if (!pairId.pairAssets().quoteAsset.equals("idr", ignoreCase = true)) return null
        return assessDynamicHistoryGuard(pairId)?.blockedReason
    }

    private fun assessDynamicHistoryGuard(
        pairId: com.kibot.shared.models.PairId,
    ): com.kibot.core.ChartAnalyzer.ChartHistoryAssessment? {
        val stats = fetchIndodaxCandleHistoryGuardStats(pairId) ?: return null
        return chartAnalyzer.assessHistoryGuard(
            candleCount = stats.candleCount,
            activeCandleCount = stats.activeCandleCount,
            distinctCloseBuckets = stats.distinctCloseBuckets,
            rangePct = stats.rangePct,
            lastClose = stats.lastClose,
            dominantCloseShare = stats.dominantCloseShare,
            directionFlipRate = stats.directionFlipRate,
            higherHighRatio = stats.higherHighRatio,
            higherLowRatio = stats.higherLowRatio,
            closingProgressRatio = stats.closingProgressRatio,
            netProgressPct = stats.netProgressPct,
            minCandles = config.chartGuardMinCandles,
            minActiveCandles = config.chartGuardMinActiveCandles,
            minDistinctCloseBuckets = config.chartGuardMinDistinctCloseBuckets,
            cheapNominalMaxPrice = chartGuardCheapNominalMaxPriceIdr,
            cheapNominalMinDistinctCloses = chartGuardCheapNominalMinDistinctCloses,
            minRangePct = chartGuardMinRangePct,
        )
    }

    private fun prioritizeExecutionPlansByChartAndCapital(
        executionPlans: List<com.kibot.shared.models.ExecutionPlan>,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): List<com.kibot.shared.models.ExecutionPlan> {
        if (executionPlans.size <= 1) return executionPlans
        val freeIdr = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        val totalEquityIdr = balances.sumOf { it.totalValueInIdr?.toDoubleOrZero() ?: 0.0 }.takeIf { it > 0.0 } ?: freeIdr
        val lowCapital = totalEquityIdr in 0.0000001..150_000.0
        return executionPlans.sortedByDescending { plan ->
            val quote = marketQuotes.firstOrNull { it.pairId == plan.signal.pairId }
            val chartAssessment = quote?.let { chartAnalyzer.analyzeQuoteSnapshot(it) }
            val entryPrice = plan.signal.entryPrice?.toDoubleOrZero()
                ?: plan.limitPrice?.toDoubleOrZero()
                ?: quote?.bestAsk?.toDoubleOrZero()
                ?: 0.0
            val budgetIdr = plan.quoteBudget?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: freeIdr
            val affordableUnits = if (entryPrice > 0.0) budgetIdr / entryPrice else 0.0
            val historyAssessment = assessDynamicHistoryGuard(plan.signal.pairId)
            val underBalanceBias = when {
                !lowCapital -> 0.0
                entryPrice <= 0.0 -> -0.10
                entryPrice > freeIdr -> -0.30
                affordableUnits >= 120.0 -> 0.18
                affordableUnits >= 40.0 -> 0.10
                affordableUnits >= 16.0 -> 0.04
                affordableUnits >= 8.0 -> 0.01
                else -> -0.18
            }
            val capitalMismatchBias = when {
                !lowCapital -> 0.0
                entryPrice <= 0.0 -> -0.20
                freeIdr <= 0.0 -> -1.20
                entryPrice > freeIdr * 0.90 -> -1.40
                affordableUnits < 4.0 -> -1.20
                affordableUnits < 8.0 -> -0.65
                affordableUnits < 16.0 -> -0.25
                quote != null && quote.spreadPct > 1.8 && affordableUnits < 20.0 -> -0.45
                else -> 0.08 * kotlin.math.min(affordableUnits / 10.0, 2.0)
            }
            val chartBias = when {
                chartAssessment == null -> 0.0
                chartAssessment.shouldAvoidEntry -> -0.50
                else -> (
                    chartAssessment.entryScore * 0.45 +
                        (1.0 - chartAssessment.exhaustionRiskScore) * 0.20 +
                        (1.0 - chartAssessment.rotationUrgencyScore) * 0.10 +
                        chartAssessment.netEntryScore * 0.25
                    )
            }
            val historyBias = when {
                historyAssessment == null -> 0.0
                historyAssessment.blocked -> -0.45
                else -> (
                    historyAssessment.rangeOpportunityScore * 0.22 +
                        historyAssessment.progressiveScore * 0.14 -
                        historyAssessment.deadChartScore * 0.20
                    )
            }
            plan.pairRankingScore + underBalanceBias + capitalMismatchBias + chartBias + historyBias
        }
    }

    private fun routeByChartAnalyzer(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan? {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return executionPlan
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId } ?: return executionPlan
        val chartAssessment = chartAnalyzer.analyzeQuoteSnapshot(quote)
        if (chartAssessment.shouldAvoidEntry) return null
        return when (chartAssessment.preferredOrderType) {
            ChartAnalyzer.PreferredOrderType.AVOID -> null
            ChartAnalyzer.PreferredOrderType.MARKET -> executionPlan
            ChartAnalyzer.PreferredOrderType.LIMIT_MID -> {
                val mid = quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: return executionPlan
                executionPlan.copy(
                    orderType = com.kibot.shared.models.OrderType.LIMIT,
                    limitPrice = DecimalValue.fromDouble(mid),
                    postOnlyPreferred = false,
                )
            }
            ChartAnalyzer.PreferredOrderType.LIMIT_PASSIVE -> {
                val bid = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: return executionPlan
                executionPlan.copy(
                    orderType = com.kibot.shared.models.OrderType.LIMIT,
                    limitPrice = DecimalValue.fromDouble(bid),
                    postOnlyPreferred = false,
                )
            }
        }
    }

    private fun routeByDepthGuard(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan {
        if (config.exchangeKind != ExchangeKind.INDODAX) return executionPlan
        if (executionPlan.orderType != com.kibot.shared.models.OrderType.MARKET) return executionPlan
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId } ?: return executionPlan
        val bestBid = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: return executionPlan
        val bestAsk = quote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 } ?: return executionPlan
        val budgetIdr = executionPlan.quoteBudget?.toDoubleOrZero()?.takeIf { it > 0.0 }
            ?: (executionPlan.quantity.toDoubleOrZero() * if (executionPlan.side == com.kibot.shared.models.OrderSide.BUY) bestAsk else bestBid)
        if (budgetIdr <= 0.0) return executionPlan
        val topDepthIdr = if (executionPlan.side == com.kibot.shared.models.OrderSide.BUY) {
            quote.askDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
        } else {
            quote.bidDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
        }
        if (topDepthIdr <= 0.0) return executionPlan
        if (budgetIdr <= (topDepthIdr * depthGuardMaxTopBookImpactPct)) return executionPlan
        val mid = quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: return executionPlan
        return executionPlan.copy(
            orderType = com.kibot.shared.models.OrderType.LIMIT,
            limitPrice = DecimalValue.fromDouble(mid),
            postOnlyPreferred = false,
        )
    }

    private fun planEmergencyGarbageLiquidation(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
    ): com.kibot.core.ExitDecision? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (balances.isEmpty()) return null
        val quoteByPair = marketQuotes.associateBy { it.pairId.value.lowercase() }
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val target = balances
            .asSequence()
            .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val pairValue = "${balance.asset.lowercase()}_${referenceQuoteAsset()}"
                val quote = quoteByPair[pairValue] ?: return@mapNotNull null
                val currentBid = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: return@mapNotNull null
                val currentValue = quantity * currentBid
                Triple(pairValue, quantity, currentValue)
            }
            .filter { (pairValue, _, currentValue) ->
                val key = pairValue.lowercase()
                key in garbageNukePairs &&
                    currentValue >= garbageNukeMinNotionalIdr &&
                    activeByPair[com.kibot.shared.models.PairId(pairValue)].orEmpty().none { it.side == com.kibot.shared.models.OrderSide.SELL }
            }
            .sortedByDescending { (_, _, currentValue) -> currentValue }
            .firstOrNull()
            ?: return null
        val pairId = com.kibot.shared.models.PairId(target.first)
        val quantity = DecimalValue.fromDouble(target.second)
        val currentBid = quoteByPair[target.first]?.bestBid?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: quoteByPair[target.first]?.midPrice?.toDoubleOrZero()
            ?: return null
        val currentValueIdr = target.third
        val score = cycle.rankedPairs.firstOrNull { it.pairId == pairId }?.rankingScore ?: 0.70
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = pairId,
            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
            confidence = score.coerceIn(0.60, 0.99),
            rationale = listOf("Emergency garbage nuke untuk membebaskan IDR dari koin low-conviction."),
            entryPrice = DecimalValue.fromDouble(currentBid),
            takeProfitPrice = null,
            stopPrice = null,
            setupType = com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION,
            horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
            pairTier = com.kibot.shared.models.PairTier.TIER_B,
            speculativePocket = true,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = 0.0,
            expectedNetProfitabilityPct = 0.0,
        )
        val syntheticPosition = com.kibot.core.ManagedPosition(
            pairId = pairId,
            quantity = quantity,
            averageEntryPrice = DecimalValue.fromDouble(currentBid),
            currentBidPrice = DecimalValue.fromDouble(currentBid),
            currentValueIdr = DecimalValue.fromDouble(currentValueIdr),
            unrealizedPnlIdr = DecimalValue.Zero,
            unrealizedPnlPct = 0.0,
            breakEvenPrice = DecimalValue.fromDouble(currentBid),
            openedAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            setupType = com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION,
            horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
            pairTier = com.kibot.shared.models.PairTier.TIER_B,
            takeProfitPrice = DecimalValue.fromDouble(currentBid),
            stopPrice = DecimalValue.fromDouble(currentBid),
            speculativePocket = true,
            expectedHoldingHours = 0.0,
        )
        return com.kibot.core.ExitDecision(
            position = syntheticPosition,
            reason = com.kibot.core.ExitReason.ROTATION_EXIT,
            message = "EMERGENCY_GARBAGE_NUKE ${pairId.value}",
            executionPlan = com.kibot.shared.models.ExecutionPlan(
                signal = signal,
                side = com.kibot.shared.models.OrderSide.SELL,
                orderType = com.kibot.shared.models.OrderType.MARKET,
                quantity = quantity,
                limitPrice = null,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = 0.0,
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                pairRankingScore = score,
                speculativePocket = true,
            ),
        )
    }

    private fun planCrashHardStopExit(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.core.ExitDecision? {
        if (managedPositions.isEmpty()) return null
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val btcEthCrash = marketQuotes.any {
            val key = it.pairId.value.lowercase()
            (key == "btc_usdt" || key == "btc_idr" || key == "eth_usdt" || key == "eth_idr") &&
                (it.shortTermReturnPct <= -2.0 || it.mediumTermReturnPct <= -2.0)
        }
        return managedPositions.firstOrNull { position ->
            val noSellOrder = activeByPair[position.pairId].orEmpty().none { it.side == com.kibot.shared.models.OrderSide.SELL }
            noSellOrder && (position.unrealizedPnlPct <= hardStopLossPct || btcEthCrash)
        }?.let { position ->
            val score = cycle.rankedPairs.firstOrNull { it.pairId == position.pairId }?.rankingScore ?: 0.72
            val signal = com.kibot.shared.models.StrategySignal(
                pairId = position.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                confidence = score.coerceIn(0.60, 0.99),
                rationale = listOf("Crash guard: hard stop-loss absolut / BTC-ETH crash trigger."),
                entryPrice = position.currentBidPrice,
                takeProfitPrice = position.takeProfitPrice,
                stopPrice = position.stopPrice,
                setupType = position.setupType,
                horizon = position.horizon,
                pairTier = position.pairTier,
                speculativePocket = true,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = position.expectedHoldingHours,
                expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
            )
            com.kibot.core.ExitDecision(
                position = position,
                reason = com.kibot.core.ExitReason.STOP_LOSS_EXIT,
                message = "CRASH_GUARD sell ${position.pairId.value}.",
                executionPlan = com.kibot.shared.models.ExecutionPlan(
                    signal = signal,
                    side = com.kibot.shared.models.OrderSide.SELL,
                    orderType = com.kibot.shared.models.OrderType.MARKET,
                    quantity = position.quantity,
                    limitPrice = null,
                    quoteBudget = null,
                    postOnlyPreferred = false,
                    expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                    botMode = cycle.modeSnapshot.mode,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    pairRankingScore = score,
                    speculativePocket = true,
                ),
            )
        }
    }

    private fun planHardTimeoutExit(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
        now: Instant,
    ): com.kibot.core.ExitDecision? {
        if (managedPositions.isEmpty()) return null
        
        val hardTimeoutHours = 12.0
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        
        return managedPositions.firstOrNull { position ->
            val noSellOrder = activeByPair[position.pairId].orEmpty().none { it.side == com.kibot.shared.models.OrderSide.SELL }
            val heldHours = ((now.toEpochMilliseconds() - position.openedAt.toEpochMilliseconds()).coerceAtLeast(0L) / 3_600_000.0)
            noSellOrder && heldHours >= hardTimeoutHours
        }?.let { position ->
            val score = cycle.rankedPairs.firstOrNull { it.pairId == position.pairId }?.rankingScore ?: 0.72
            val signal = com.kibot.shared.models.StrategySignal(
                pairId = position.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                confidence = score.coerceIn(0.60, 0.99),
                rationale = listOf("Hard timeout: posisi ditahan >12 jam HARUS ditutup untuk mencegah capital lock."),
                entryPrice = position.currentBidPrice,
                takeProfitPrice = position.takeProfitPrice,
                stopPrice = position.stopPrice,
                setupType = position.setupType,
                horizon = position.horizon,
                pairTier = position.pairTier,
                speculativePocket = true,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = position.expectedHoldingHours,
                expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
            )
            com.kibot.core.ExitDecision(
                position = position,
                reason = com.kibot.core.ExitReason.TIME_EXIT,
                message = "HARD_TIMEOUT forced sell ${position.pairId.value} setelah ${String.format("%.1f", ((now.toEpochMilliseconds() - position.openedAt.toEpochMilliseconds()) / 3_600_000.0))}h untuk mencegah capital lock.",
                executionPlan = com.kibot.shared.models.ExecutionPlan(
                    signal = signal,
                    side = com.kibot.shared.models.OrderSide.SELL,
                    orderType = com.kibot.shared.models.OrderType.MARKET,
                    quantity = position.quantity,
                    limitPrice = null,
                    quoteBudget = null,
                    postOnlyPreferred = false,
                    expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                    botMode = cycle.modeSnapshot.mode,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    pairRankingScore = score,
                    speculativePocket = true,
                ),
            )
        }
    }

    private fun planEmergencyLiquidityRebalanceExit(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        balances: List<BalanceSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
    ): com.kibot.core.ExitDecision? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (managedPositions.isEmpty()) return null
        val idrFree = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        if (idrFree >= emergencyLiquidityMinIdr) return null
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val candidate = managedPositions
            .filter { activeByPair[it.pairId].orEmpty().none { order -> order.side == com.kibot.shared.models.OrderSide.SELL } }
            .minByOrNull { it.unrealizedPnlPct }
            ?: return null
        val score = cycle.rankedPairs.firstOrNull { it.pairId == candidate.pairId }?.rankingScore ?: 0.65
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = candidate.pairId,
            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
            confidence = score.coerceIn(0.50, 0.95),
            rationale = listOf("Emergency liquidity rebalance: bebaskan IDR untuk cycle entry berikutnya."),
            entryPrice = candidate.currentBidPrice,
            takeProfitPrice = candidate.takeProfitPrice,
            stopPrice = candidate.stopPrice,
            setupType = candidate.setupType,
            horizon = candidate.horizon,
            pairTier = candidate.pairTier,
            speculativePocket = true,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = candidate.expectedHoldingHours,
            expectedNetProfitabilityPct = kotlin.math.abs(candidate.unrealizedPnlPct),
        )
        return com.kibot.core.ExitDecision(
            position = candidate,
            reason = com.kibot.core.ExitReason.ROTATION_EXIT,
            message = "Liquidity rebalance sell ${candidate.pairId.value}; IDR free ${formatDecimal(idrFree, 0)} < ${formatDecimal(emergencyLiquidityMinIdr, 0)}.",
            executionPlan = com.kibot.shared.models.ExecutionPlan(
                signal = signal,
                side = com.kibot.shared.models.OrderSide.SELL,
                orderType = com.kibot.shared.models.OrderType.MARKET,
                quantity = candidate.quantity,
                limitPrice = null,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(candidate.unrealizedPnlPct),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                pairRankingScore = score,
                speculativePocket = true,
            ),
        )
    }

    private fun planOpportunityCostLiquidation(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        balances: List<BalanceSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
        leadLagPriorityPair: com.kibot.shared.models.PairId?,
        superSexyTarget: com.kibot.shared.models.PairId?,
    ): com.kibot.core.ExitDecision? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        val targetPair = leadLagPriorityPair ?: superSexyTarget ?: return null
        if (managedPositions.isEmpty()) return null
        val idrFree = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        if (idrFree >= opportunityLiquidationMinIdr) return null
        val activeByPair = activeOrders.filter { it.status in activeOrderStatuses }.groupBy { it.pairId }
        val worst = managedPositions
            .filter { it.pairId != targetPair }
            .filter { activeByPair[it.pairId].orEmpty().none { order -> order.side == com.kibot.shared.models.OrderSide.SELL } }
            .minByOrNull { it.unrealizedPnlPct }
            ?: return null
        val score = cycle.rankedPairs.firstOrNull { it.pairId == worst.pairId }?.rankingScore ?: 0.68
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = worst.pairId,
            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
            confidence = score.coerceIn(0.55, 0.98),
            rationale = listOf("Opportunity-cost liquidation: buang loser untuk kejar anomaly ${targetPair.value}."),
            entryPrice = worst.currentBidPrice,
            takeProfitPrice = worst.takeProfitPrice,
            stopPrice = worst.stopPrice,
            setupType = worst.setupType,
            horizon = worst.horizon,
            pairTier = worst.pairTier,
            speculativePocket = true,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = worst.expectedHoldingHours,
            expectedNetProfitabilityPct = kotlin.math.abs(worst.unrealizedPnlPct),
        )
        return com.kibot.core.ExitDecision(
            position = worst,
            reason = com.kibot.core.ExitReason.ROTATION_EXIT,
            message = "Opportunity-cost liquidation ${worst.pairId.value} => buka IDR untuk ${targetPair.value}.",
            executionPlan = com.kibot.shared.models.ExecutionPlan(
                signal = signal,
                side = com.kibot.shared.models.OrderSide.SELL,
                orderType = com.kibot.shared.models.OrderType.MARKET,
                quantity = worst.quantity,
                limitPrice = null,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(worst.unrealizedPnlPct),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                pairRankingScore = score,
                speculativePocket = true,
            ),
        )
    }

    private suspend fun emitLeadLagExecutionReport(
        pairId: com.kibot.shared.models.PairId,
        status: String,
        t0DetectedAtMs: Long?,
        t1ReceivedAtMs: Long?,
        t2BuyAtMs: Long?,
        t3SellAtMs: Long?,
        slippagePct: Double?,
        finalPnlIdr: Double?,
    ) {
        val payload = buildString {
            append("{")
            append("\"coin_pair\":\"${pairId.value}\",")
            append("\"kinance_detect_time_ms\":${t0DetectedAtMs ?: -1},")
            append("\"kidax_receive_time_ms\":${t1ReceivedAtMs ?: -1},")
            append("\"kidax_buy_time_ms\":${t2BuyAtMs ?: -1},")
            append("\"kidax_sell_time_ms\":${t3SellAtMs ?: -1},")
            append("\"slippage_percentage\":${slippagePct ?: -1.0},")
            append("\"final_pnl_idr\":${finalPnlIdr ?: 0.0},")
            append("\"status\":\"$status\",")
            append("\"is_shadow_mode\":${config.shadowMode}")
            append("}")
        }
        runCatching {
            controlPlane.appendLog(
                botId = config.controlPlane.botId,
                record = AuditLogRecord(
                    recordedAt = Clock.System.now(),
                    level = if (status == "ABORTED_SLIPPAGE") LogLevel.WARN else LogLevel.INFO,
                    category = "LEAD_LAG_EXECUTION_REPORT",
                    deviceId = config.device.deviceId,
                    term = lastObservedLeaseTerm,
                    message = payload,
                ),
            )
        }.onFailure { logger.warn("Failed to append lead-lag execution report: {}", it.message) }
    }

    private fun <T> trimToMaxSize(map: LinkedHashMap<String, T>, maxSize: Int) {
        while (map.size > maxSize) {
            val oldest = map.entries.firstOrNull()?.key ?: return
            map.remove(oldest)
        }
    }

    private fun <T> trimToMaxSize(map: java.util.concurrent.ConcurrentHashMap<String, T>, maxSize: Int) {
        while (map.size > maxSize) {
            val oldest = map.keys().asIterator().let { if (it.hasNext()) it.next() else null } ?: return
            map.remove(oldest)
        }
    }

    private fun dynamicTrailingStopPct(gainPct: Double, currentPrice: Double? = null): Double {
        val base = when {
            gainPct >= 20.0 -> 4.2
            gainPct >= 12.0 -> 3.4
            gainPct >= 7.0 -> 2.8
            gainPct >= 3.5 -> 2.2
            gainPct >= 1.5 -> 1.8
            else -> 1.5
        }
        // MICRO-CAP BOOST: Widen trailing stop for cheap coins to avoid noise-triggered exits
        val priceBoost = when {
            currentPrice == null || currentPrice <= 0.0 -> 0.0
            currentPrice < 50.0 -> 7.0   // Ultra micro-cap: very wide stop
            currentPrice < 100.0 -> 5.5  // Micro-cap: wide stop
            currentPrice < 300.0 -> 4.0  // Small-cap: moderate stop
            currentPrice < 500.0 -> 3.0  // Low-mid cap
            currentPrice < 1000.0 -> 1.5 // Mid cap
            else -> 0.0
        }
        return maxOf(base, priceBoost)
    }

    private fun logWhyNotBuy(now: Instant, pair: String, reason: String) {
        val lastAt = lastWhyNotBuyAt
        if (lastAt != null && (now - lastAt).inWholeMilliseconds < 1_500L) return
        val signature = "$pair|$reason"
        lastWhyNotBuyAt = now
        lastWhyNotBuySignature = signature
        logger.info("[WHY_NOT_BUY] pair={} reason={}", pair, reason)
    }

    private fun refreshProtectiveState(now: Instant) {
        sinBinUntilByPair.entries.removeIf { (_, until) -> now >= until }
        spoofSuspiciousUntilByPair.entries.removeIf { (_, until) -> now >= until }
        refreshToxicFlowState(now)
        pruneDynamicVip(now)
        while (crashGuardTriggerTimeline.isNotEmpty() &&
            (now - crashGuardTriggerTimeline.first()).inWholeMilliseconds > crashGuardWindowMinutes * 60_000L
        ) {
            crashGuardTriggerTimeline.removeFirst()
        }
        if (globalCooldownUntil != null && now >= globalCooldownUntil!!) {
            globalCooldownUntil = null
        }
    }

    private fun markCrashGuardTriggered(now: Instant, pairId: PairId) {
        sinBinUntilByPair[pairId.value.lowercase()] = now.plus(sinBinHours.hours)
        crashGuardTriggerTimeline.addLast(now)
        while (crashGuardTriggerTimeline.isNotEmpty() &&
            (now - crashGuardTriggerTimeline.first()).inWholeMilliseconds > crashGuardWindowMinutes * 60_000L
        ) {
            crashGuardTriggerTimeline.removeFirst()
        }
        if (crashGuardTriggerTimeline.size >= crashGuardGlobalThreshold) {
            globalCooldownUntil = now.plus(globalCooldownMinutes.minutes)
        }
    }

    private fun entryBlockedByProtectiveBrake(now: Instant, pairId: PairId): String? {
        val pairKey = pairId.value.lowercase()
        globalCooldownUntil?.let { until ->
            if (now < until) {
                return "GLOBAL_COOLDOWN_ACTIVE until ${formatJktTime(until)} setelah crash-guard beruntun."
            }
        }
        sinBinUntilByPair[pairKey]?.let { until ->
            if (now < until) {
                return "SIN_BIN_ACTIVE $pairKey sampai ${formatJktTime(until)} (cooldown patah hati)."
            }
        }
        toxicFlowQuarantineUntil(pairId)?.let { until ->
            if (now < until) {
                return "TOXIC_COOLDOWN_ACTIVE $pairKey sampai ${formatJktTime(until)} karena kena sweep stop-loss berulang."
            }
        }
        return null
    }

    private fun updateDustQuarantine(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        val previous = dustQuarantinePairs.toSet()
        val next = mutableSetOf<String>()
        balances.forEach { balance ->
            if (balance.asset.equals(referenceQuoteAsset(), ignoreCase = true)) return@forEach
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (quantity <= 0.0) return@forEach
            val pair = "${balance.asset.lowercase()}_${referenceQuoteAsset()}"
            val quote = marketQuotes.firstOrNull { it.pairId.value.equals(pair, ignoreCase = true) }
            val px = quote?.midPrice?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: 0.0
            val value = quantity * px
            val key = pair.lowercase()
            val wasDust = key in previous
            val shouldQuarantine = if (wasDust) {
                value in 0.0..dustQuarantineReleaseMinValueIdr
            } else {
                value in 0.0..dustQuarantineMinValueIdr
            }
            if (shouldQuarantine) next += key
        }
        dustQuarantinePairs.clear()
        dustQuarantinePairs += next
    }

    private fun fallbackBotState(now: Instant): BotStateSnapshot {
        return BotStateSnapshot(
            botId = config.controlPlane.botId,
            desiredState = BotDesiredState.ON,
            effectiveState = BotEffectiveState.DEGRADED,
            activeDeviceId = config.device.deviceId,
            standbyDeviceId = null,
            currentTerm = lastObservedLeaseTerm ?: com.kibot.shared.models.LeaseTerm(0),
            syncHealth = SyncHealth.DEGRADED,
            strategyMode = com.kibot.shared.models.StrategyMode.ATTACK,
            safeModeReason = "Control plane unavailable, fail-open local trading mode.",
            currentPair = null,
            lastHeartbeatAt = now,
            operatingMode = BotMode.GROWTH,
            edgeConfidence = com.kibot.shared.models.EdgeConfidence.MEDIUM,
        )
    }

    private suspend fun emitLeadLagTelemetry(event: LeadLagTelemetryEvent) {
        val payload = json.encodeToString(event.copy(isShadowMode = config.shadowMode))
        appendAuditLog(
            level = LogLevel.INFO,
            category = "LEAD_LAG_TELEMETRY",
            message = payload,
        )
    }
    private var leadLagListenerSocket: DatagramSocket? = null
    private val leadLagListenerReady = AtomicBoolean(false)
    private var lastEngineHeartbeatLogAt: Instant? = null
    private var lastKingDashboardFastTelemetryAt: Instant? = null
    private var lastControlPlaneHeartbeatAt: Instant? = null
    private var lastAutonomousResolverAt: Instant? = null
    private var lastNonEmptyMarketQuotesAt: Instant? = null

    suspend fun run() {
        logger.info("Mac engine daemon loop started.")
        // Force reset runtime cache so engine re-syncs from exchange snapshots immediately.
        cachedRecentOrders = emptyList()
        cachedOpenOrders = emptyList()
        cachedBalances = emptyList()
        ensureLeadLagListenerInitialized()
        while (true) {
            try {
                syncOnce()
            } catch (error: CancellationException) {
                logger.info("Daemon sync cancelled; retrying next cycle.")
                repository.noteStatus("Daemon sync cancelled: ${error.message ?: "unknown cancellation"}")
            } catch (error: Throwable) {
                if (isLeaseReserveOwnershipConflict(error)) {
                    repository.noteStatus("Lease owner drift terdeteksi; menjalankan lease lockdown recovery.")
                    attemptLeaseLockdownRecovery(Clock.System.now())
                } else if (error.message?.contains("Lease is conflicted or expired", ignoreCase = true) == true) {
                    repository.noteStatus("Lease conflict transient; auto-recovering.")
                } else {
                    logger.error("Mac daemon sync failed.", error)
                    repository.noteStatus("Daemon sync failed: ${error.message ?: "unknown error"}")
                }
            }
            delay(config.pollIntervalMillis)
        }
    }

    suspend fun syncOnce() = coroutineScope {
        val cycleStartedAt = Clock.System.now()
        pollLeadLagUdpCommands(cycleStartedAt)
        maybeEmitTrinityHeartbeat(cycleStartedAt)
        pruneLeadLagTelemetry(cycleStartedAt)
        ensureRegistered()

        val now = Clock.System.now()
        val jakartaDate = jakartaNowDate(now)
        val peerBotStates = listOf("kidax", "kibot", "kinance")
            .associateWith { peerId ->
                if (peerId.equals(config.controlPlane.botId.value, ignoreCase = true)) {
                    null
                } else {
                    readControlPlane<BotStateSnapshot?>(null) {
                        controlPlane.fetchBotState(BotId(peerId))
                    }
                }
            }
        val botState = (readControlPlane<BotStateSnapshot?>(null) {
            controlPlane.fetchBotState(config.controlPlane.botId)
        } ?: fallbackBotState(now))
        var lease = readControlPlane<EngineLeaseSnapshot?>(null) {
            runCatching { controlPlane.fetchLease(config.controlPlane.botId) }.getOrNull()
        }
        lease = ensureLeaseLockdownOwnership(now, lease)
        lastObservedLeaseTerm = lease?.term ?: botState.currentTerm
        val devices = refreshDevices(now)
        val dailyRisk = refreshDailyRisk(now, jakartaDate)
        val equityHistory = refreshEquityHistory(now)
        val commands = refreshPendingCommands(now)
        val weeklyReview = refreshWeeklyReview(now)
        lastSuccessfulControlPlaneAt = now

        val (exchangeReachable, exchangePingMs) = probeExchange(now)
        val displayPingMs = recordDisplayPing(
            now = now,
            exchangeReachable = exchangeReachable,
            rawPingMs = exchangePingMs,
        )
        val healthWarnings = mutableListOf<String>()
        val reportOnlyMode = config.shadowMode || !config.enableLiveExecution
        if (!exchangeReachable && !reportOnlyMode) {
            healthWarnings += "Exchange unreachable or credentials not configured."
        }
        if (dailyRisk?.hardStopTriggered == true) {
            healthWarnings += "Daily hard stop is active."
        }
        if ((displayPingMs ?: 0L) >= entryBlockLatencyMs) {
            healthWarnings += "Exchange latency is heavy."
        }

        var leaseAfterCommands = lease
        var botStateAfterCommands = botState

        commands.forEach { command ->
            val result = handleCommand(command, leaseAfterCommands, botStateAfterCommands)
            if (result != null) {
                leaseAfterCommands = readControlPlane(leaseAfterCommands) {
                    controlPlane.fetchLease(config.controlPlane.botId)
                }
                botStateAfterCommands = readControlPlane<BotStateSnapshot?>(null) {
                    controlPlane.fetchBotState(config.controlPlane.botId)
                } ?: botStateAfterCommands
                if (result.isOperationalHealthWarning()) {
                    healthWarnings += result
                }
            }
        }
        commandsFetchedAt = now
        val trinityHeartbeatBrakeReason = enforceTrinityHeartbeatBrake(now)
        if (trinityHeartbeatBrakeReason != null) {
            healthWarnings += trinityHeartbeatBrakeReason
        }

        val localHealth = buildLocalHealth(
            exchangeReachable = exchangeReachable,
            warnings = healthWarnings,
            feedLatencyMs = displayPingMs ?: exchangePingMs,
            marketFeedHealthy = exchangeReachable,
            reportOnlyMode = reportOnlyMode,
        )
        val masterBeforeTakeover = leaseAfterCommands.isHeldBy(config.device.deviceId, now)

        if (
            botStateAfterCommands.desiredState == BotDesiredState.ON &&
            !masterBeforeTakeover &&
            !shouldYieldToPrimary(botStateAfterCommands, now) &&
            !isInReleaseCooldown(now)
        ) {
            maybeTakeOver(
                now = now,
                botState = botStateAfterCommands,
                lease = leaseAfterCommands,
                localHealth = localHealth,
            )
        } else if (botStateAfterCommands.desiredState == BotDesiredState.OFF && masterBeforeTakeover) {
            writeControlPlane("release-lease-desired-off") {
                controlPlane.releaseLease(
                    botId = config.controlPlane.botId,
                    deviceId = config.device.deviceId,
                    term = leaseAfterCommands?.term?.value ?: 0L,
                    reason = "Bot desired state is OFF.",
                )
            }
            appendAuditLog(LogLevel.INFO, "LEASE", "Mac released master lease because desired state is OFF.")
        }

        val initialBotState = (readControlPlane<BotStateSnapshot?>(null) {
            controlPlane.fetchBotState(config.controlPlane.botId)
        } ?: botStateAfterCommands)
        var initialLease = readControlPlane<EngineLeaseSnapshot?>(null) {
            controlPlane.fetchLease(config.controlPlane.botId)
        }
        initialLease = ensureLeaseLockdownOwnership(now, initialLease)
        lastObservedLeaseTerm = initialLease?.term ?: initialBotState.currentTerm
        repository.applyRuntimeState(
            buildDashboardState(
                now = now,
                jakartaDate = jakartaDate,
                botState = initialBotState,
                peerBotStates = peerBotStates + (config.controlPlane.botId.value.lowercase() to initialBotState),
                lease = initialLease,
                devices = devices,
                localHealth = localHealth,
                dailyRisk = dailyRisk,
                equityHistory = equityHistory,
                balances = cachedBalances,
                marketQuotes = emptyList(),
                strategyCycle = null,
                weeklyReview = weeklyReview,
                recentOrders = cachedRecentOrders,
                supportEval = null,
                healthDecisionSummary = "Warm-up sync in progress. Exchange and control-plane data are still settling.",
            ),
        )
        val isMaster = initialLease.isHeldBy(config.device.deviceId, now)
        val balancesDeferred: kotlinx.coroutines.Deferred<List<BalanceSnapshot>>? = if (exchangeReachable) {
            async { refreshBalances(now) }
        } else {
            null
        }
        val openOrdersDeferred: kotlinx.coroutines.Deferred<List<com.kibot.shared.models.OrderSnapshot>>? = if (exchangeReachable) {
            async { refreshOpenOrders(now) }
        } else {
            null
        }
        val marketQuotesDeferred: kotlinx.coroutines.Deferred<Result<List<com.kibot.shared.models.MarketQuote>>>? = if (exchangeReachable) {
            async { runCatching { exchange.fetchMarketQuotes() } }
        } else {
            null
        }
        // Use withTimeoutOrNull to prevent indefinite blocking if exchange hangs
        val awaitTimeoutMs = 15000L  // 15 second timeout for exchange requests
        val resolvedBalances = balancesDeferred?.let { 
            withTimeoutOrNull(awaitTimeoutMs) { it.await() } 
        } ?: cachedBalances
        val resolvedOpenOrders = openOrdersDeferred?.let { 
            withTimeoutOrNull(awaitTimeoutMs) { it.await() } 
        } ?: cachedOpenOrders
        val rawMarketQuotes = marketQuotesDeferred?.let {
            withTimeoutOrNull(awaitTimeoutMs) { it.await() }
        }?.fold(
            onSuccess = { it },
            onFailure = { error ->
                healthWarnings += "Market quote feed fetch failed: ${error.message ?: "unknown"}"
                emptyList()
            },
        ).orEmpty()
        val resolvedMarketQuotes = enrichRuntimeMarketQuotes(
            now = now,
            marketQuotes = rawMarketQuotes,
            balances = resolvedBalances,
            openOrders = resolvedOpenOrders,
        )
        if (resolvedMarketQuotes.isNotEmpty()) {
            lastNonEmptyMarketQuotesAt = now
            updateLeadLagMicroPulseSnapshots(now, resolvedMarketQuotes)
            updateOrderBookSpoofRadar(now, resolvedMarketQuotes)
        }
        val recentQuoteFreshEnough = lastNonEmptyMarketQuotesAt?.let { lastHealthyAt ->
            (now - lastHealthyAt).inWholeSeconds <= 90
        } ?: false
        val marketFeedHealthy = resolvedMarketQuotes.isNotEmpty() ||
            recentQuoteFreshEnough ||
            (exchangeReachable && config.exchangeKind == ExchangeKind.BINANCE_SPOT)
        if (exchangeReachable && resolvedMarketQuotes.isEmpty() && !marketFeedHealthy) {
            healthWarnings += "Market quote feed kosong."
        }
        val finalHealth = buildLocalHealth(
            exchangeReachable = exchangeReachable,
            warnings = healthWarnings,
            feedLatencyMs = displayPingMs ?: exchangePingMs,
            marketFeedHealthy = marketFeedHealthy,
            reportOnlyMode = reportOnlyMode,
        )
        val healthDecision = healthAdvisor.evaluate(finalHealth)
        val adaptiveAiPolicy = if (config.enableExecutionAiAssist) refreshAdaptiveAiPolicy(now) else null
        val aiSupportEvaluation = if (config.enableExecutionAiAssist && isMaster && resolvedMarketQuotes.isNotEmpty()) {
            val shortlist = strategyOrchestrator.shortlistForSupport(resolvedMarketQuotes)
            aiSupportCoordinator?.evaluate(
                candidates = shortlist,
                now = now,
            )
        } else {
            null
        }
        val aiSupportHints = aiSupportEvaluation?.let { evaluation ->
            if (evaluation.usedNetwork) {
                appendAuditLog(LogLevel.INFO, "AI_SUPPORT", "REQUEST")
            }
            evaluation.hints
        }.orEmpty()
        val leadLagHints = leadLagSupportHints(
            now = now,
            marketQuotes = resolvedMarketQuotes,
        )
        val effectiveAiSupportHints = mergeAiSupportHints(
            liveHints = aiSupportHints,
            adaptivePolicy = adaptiveAiPolicy,
        ) + leadLagHints
        val recentPersistedOrders = if (isMaster && exchangeReachable) {
            refreshRecentOrders(now)
        } else {
            cachedRecentOrders
        }
        val derivedDailyRisk = deriveDailyRiskSnapshot(
            now = now,
            previous = dailyRisk,
            balances = resolvedBalances,
            marketQuotes = resolvedMarketQuotes,
            recentOrders = recentPersistedOrders,
        ) ?: dailyRisk
        val strategyCycle = if (resolvedMarketQuotes.isNotEmpty()) {
            val baseCycle = strategyOrchestrator.analyze(
                botId = config.controlPlane.botId,
                balances = resolvedBalances,
                openOrders = resolvedOpenOrders,
                dailyRisk = derivedDailyRisk,
                health = finalHealth,
                marketQuotes = resolvedMarketQuotes,
                pairSupportHints = effectiveAiSupportHints,
                weeklySummary = weeklyReview,
                aiSoftAuditOnly = aiSupportEvaluation?.blockedReason != null,
            )
            applyPursuitPolicy(
                cycle = baseCycle,
                adaptiveAiPolicy = adaptiveAiPolicy,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                now = now,
            )
        } else {
            null
        }
        emitEngineHeartbeat(
            now = now,
            scannedPairs = resolvedMarketQuotes.size,
            aggressive = strategyCycle?.dailyRisk?.let { evaluateHyperAggressiveTracker(now, it).hungry } ?: false,
        )
        emitKingDashboardFastTelemetry(
            now = now,
            strategyCycle = strategyCycle,
            balances = resolvedBalances,
            marketQuotes = resolvedMarketQuotes,
            displayPingMs = displayPingMs ?: exchangePingMs,
        )
        val recentFills = if (isMaster && exchangeReachable) {
            refreshRecentFills(
                now = now,
                pairIds = relevantFillPairs(
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                openOrders = resolvedOpenOrders,
                persistedOrders = recentPersistedOrders,
                cycle = strategyCycle,
                ),
            )
        } else {
            cachedRecentFills
        }
        val reconciledOrderUpdates = if (isMaster && recentPersistedOrders.isNotEmpty()) {
            tradeAutomationCoordinator.reconcileOrders(
                persistedOrders = recentPersistedOrders,
                exchangeOpenOrders = resolvedOpenOrders,
                recentFills = recentFills,
            )
        } else {
            emptyList()
        }
        reconciledOrderUpdates.forEach { order ->
            writeControlPlane("upsert-order-snapshot-${order.orderId}") {
                controlPlane.upsertOrderSnapshot(
                    botId = config.controlPlane.botId,
                    term = initialLease?.term?.value ?: initialBotState.currentTerm.value,
                    deviceId = config.device.deviceId,
                    order = order,
                )
            }
        }
        val effectiveRecentOrders = mergeRecentOrders(
            base = recentPersistedOrders,
            updates = reconciledOrderUpdates,
        )
        cachedRecentOrders = effectiveRecentOrders
        auditLocalRecoveryStateIfNeeded(
            now = now,
            balances = resolvedBalances,
            persistedOrders = effectiveRecentOrders,
        )
        recentOrdersFetchedAt = now

        var runtimeBotState = initialBotState
        var runtimeLease = initialLease

        var effectiveWeeklyReview = weeklyReview
        val effectiveDailyRisk = strategyCycle?.let { cycle ->
            derivedDailyRisk?.copy(
                hardStopTriggered = cycle.riskDecision.hardStopTriggered,
                riskLadderLevel = cycle.riskDecision.riskLadderLevel,
                profitProtectionStatus = cycle.riskDecision.profitProtectionStatus,
            )
        } ?: derivedDailyRisk
        if (isMaster && effectiveDailyRisk != null) {
            queueNonCriticalDailyRisk(
                now = now,
                botId = config.controlPlane.botId,
                date = jakartaDate,
                snapshot = effectiveDailyRisk,
            )
            cachedDailyRisk = effectiveDailyRisk
            cachedDailyRiskDate = jakartaDate
            dailyRiskFetchedAt = now
        }
        if (isMaster && runtimeLease != null && strategyCycle != null) {
            runCatching {
                maybeDispatchLeadLagCallout(
                    now = now,
                    lease = runtimeLease,
                    cycle = strategyCycle,
                    marketQuotes = resolvedMarketQuotes,
                )
                effectiveWeeklyReview = maybePublishWeeklyLearningSummary(
                    now = now,
                    cycle = strategyCycle,
                    marketQuotes = resolvedMarketQuotes,
                    currentWeeklyReview = weeklyReview,
                    recentOrders = effectiveRecentOrders,
                )
                publishAnalysisIfNeeded(
                    now = now,
                    lease = runtimeLease,
                    cycle = strategyCycle,
                )
                maybeManageLiveTrading(
                    now = now,
                    lease = runtimeLease,
                    cycle = strategyCycle,
                    weeklyReview = effectiveWeeklyReview,
                    health = finalHealth,
                    balances = resolvedBalances,
                    marketQuotes = resolvedMarketQuotes,
                    recentOrders = effectiveRecentOrders,
                    aiSoftAuditOnly = aiSupportEvaluation?.blockedReason != null,
                )
                publishLearningSignalsIfNeeded(
                    now = now,
                    cycle = strategyCycle,
                    weeklyReview = effectiveWeeklyReview,
                    aiBlockedReason = aiSupportEvaluation?.blockedReason,
                    aiUsedNetwork = aiSupportEvaluation?.usedNetwork == true,
                )
            }.onFailure { error ->
                healthWarnings += "Master execution degraded: ${error.message ?: "unknown"}"
                appendAuditLog(
                    level = LogLevel.WARN,
                    category = "CONTROL_PLANE",
                    message = "Master side-effects degraded: ${error.message ?: "unknown"}",
                )
            }
            runtimeBotState = readControlPlane<BotStateSnapshot?>(null) {
                controlPlane.fetchBotState(config.controlPlane.botId)
            } ?: runtimeBotState
            runtimeLease = readControlPlane(runtimeLease) {
                controlPlane.fetchLease(config.controlPlane.botId)
            }
            runtimeLease = ensureLeaseLockdownOwnership(now, runtimeLease)
            lastObservedLeaseTerm = runtimeLease?.term ?: runtimeBotState.currentTerm
        }

        val lastHeartbeatAt = lastControlPlaneHeartbeatAt
        val derivedEffectiveState = deriveEffectiveState(now, runtimeBotState, runtimeLease, healthDecision)
        val derivedSyncHealth = if (healthDecision.reasons.isEmpty()) {
            SyncHealth.HEALTHY
        } else {
            localHealth.syncHealth
        }
        val displayBotState = runtimeBotState.copy(
            effectiveState = derivedEffectiveState,
            syncHealth = derivedSyncHealth,
            safeModeReason = if (derivedEffectiveState == BotEffectiveState.SAFE_MODE) runtimeBotState.safeModeReason else null,
        )
        repository.applyRuntimeState(
            buildDashboardState(
                now = now,
                jakartaDate = jakartaDate,
                botState = displayBotState,
                peerBotStates = peerBotStates + (config.controlPlane.botId.value.lowercase() to displayBotState),
                lease = runtimeLease,
                devices = devices,
                localHealth = finalHealth,
                dailyRisk = dailyRisk,
                equityHistory = equityHistory,
                balances = resolvedBalances,
                marketQuotes = resolvedMarketQuotes,
                strategyCycle = strategyCycle,
                weeklyReview = effectiveWeeklyReview,
                recentOrders = effectiveRecentOrders,
                supportEval = aiSupportEvaluation,
                healthDecisionSummary = if (healthDecision.reasons.isEmpty()) {
                    if (runtimeLease.isHeldBy(config.device.deviceId, now)) {
                        strategyCycle?.summary?.joinToString(" ") ?: "Master healthy. Lease fenced and heartbeat current."
                    } else {
                        strategyCycle?.summary?.firstOrNull()
                            ?: "Standby healthy, takeover ready when lease expires."
                    }
                } else {
                    healthDecision.reasons.joinToString(" ")
                },
            ),
        )
        maybeNotifyOperatorAlert(
            now = now,
            botState = displayBotState,
            localHealth = finalHealth,
            topCandidate = strategyCycle?.topCandidate?.value ?: strategyCycle?.selectedSignal?.pairId?.value,
        )

        if (lastHeartbeatAt == null || (now - lastHeartbeatAt).inWholeSeconds >= 10) {
            queueNonCriticalHeartbeat(
                now = now,
                snapshot = EngineHeartbeatSnapshot(
                    botId = config.controlPlane.botId,
                    deviceId = config.device.deviceId,
                    observedAt = now,
                    term = runtimeLease?.term,
                    isMaster = runtimeLease.isHeldBy(config.device.deviceId, now),
                    desiredState = runtimeBotState.desiredState,
                    effectiveState = derivedEffectiveState,
                    health = finalHealth,
                ),
            )
            lastControlPlaneHeartbeatAt = now
        }
        flushNonCriticalControlPlaneBuffer(now = now)
    }

    private suspend fun ensureRegistered() {
        if (registered) return
        if (writeControlPlane(context = "register-device", timeoutMs = 12_000L) {
                controlPlane.registerDevice(config.device)
            }
        ) {
            registered = true
            repository.noteBootstrapProgress(
                message = "Server monitor connected to live feed.",
                liveExecutionEnabled = config.enableLiveExecution,
            )
            appendAuditLog(LogLevel.INFO, "AUTH", "Server monitor connected to live feed.")
        } else {
            repository.noteBootstrapProgress(
                message = "Server monitor waiting for control-plane registration.",
                liveExecutionEnabled = config.enableLiveExecution,
            )
        }
    }

    private suspend fun handleCommand(
        command: CommandEnvelope,
        lease: EngineLeaseSnapshot?,
        botState: BotStateSnapshot,
    ): String? {
        return when (command.commandType) {
            CommandType.REQUEST_TAKEOVER -> {
                if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                    enterReleaseCooldown()
                    controlPlane.releaseLease(
                        botId = config.controlPlane.botId,
                        deviceId = config.device.deviceId,
                        term = lease?.term?.value ?: 0L,
                        reason = "Graceful takeover requested by ${command.createdBy.value}.",
                    )
                    controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                    appendAuditLog(LogLevel.WARN, "LEASE", "Mac released control after takeover request from ${command.createdBy.value}.")
                    "Graceful takeover request processed."
                } else {
                    controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                    "Takeover request acknowledged; Mac is not the active lease holder."
                }
            }

            CommandType.FORCE_SAFE_TAKEOVER -> {
                val outcome = maybeTakeOver(
                    now = Clock.System.now(),
                    botState = botState,
                    lease = lease,
                    localHealth = buildLocalHealth(
                        exchangeReachable = runCatching { exchange.ping() }.getOrDefault(false),
                        warnings = listOf("Force safe takeover requested."),
                        supabaseReachable = isControlPlaneReachable(Clock.System.now()),
                        marketFeedHealthy = false,
                    ),
                )
                controlPlane.updateCommandStatus(
                    command.commandId,
                    if (outcome) CommandStatus.SUCCEEDED else CommandStatus.FAILED,
                )
                if (outcome) {
                    "Force safe takeover succeeded."
                } else {
                    "Force safe takeover blocked by lease or reconciliation."
                }
            }

            CommandType.RELEASE_CONTROL -> {
                if (lease.isHeldBy(config.device.deviceId, Clock.System.now())) {
                    enterReleaseCooldown()
                    controlPlane.releaseLease(
                        botId = config.controlPlane.botId,
                        deviceId = config.device.deviceId,
                        term = lease?.term?.value ?: 0L,
                        reason = "Release control requested locally.",
                    )
                    appendAuditLog(LogLevel.INFO, "LEASE", "Mac released control on command.")
                }
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Release control command handled."
            }

            CommandType.SYNC_NOW -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                handleLeadLagPayload(command.payloadJson, Clock.System.now())
                    ?: "Manual sync command handled."
            }

            CommandType.START_BOT -> {
                controlPlane.setDesiredState(config.controlPlane.botId, BotDesiredState.ON)
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Bot desired state switched to ON."
            }

            CommandType.STOP_BOT -> {
                controlPlane.setDesiredState(config.controlPlane.botId, BotDesiredState.OFF)
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Bot desired state switched to OFF."
            }

            CommandType.FORCE_STANDBY,
            -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Command ${command.commandType.name} acknowledged."
            }
            CommandType.RESUME_FROM_SAFE_MODE -> {
                trinityHeartbeatSafeModeReason = null
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Command ${command.commandType.name} acknowledged."
            }
            CommandType.TOGGLE_LIVE_EXECUTION -> {
                controlPlane.updateCommandStatus(command.commandId, CommandStatus.SUCCEEDED)
                "Command ${command.commandType.name} acknowledged."
            }
        }
    }

    private suspend fun maybeTakeOver(
        now: Instant,
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        localHealth: EngineHealthSnapshot,
    ): Boolean {
        if (lease?.conflictDetected == true && botState.desiredState == BotDesiredState.ON) {
            val forcedLease = controlPlane.acquireLease(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                ttlSeconds = config.leaseTtlSeconds,
            )
            lastObservedLeaseTerm = forcedLease.term
            activateConflictRecoveryHold(now, forcedLease.term)
            appendAuditLog(
                level = LogLevel.WARN,
                category = "FAILOVER",
                message = "Conflict override active: force-acquire lease term ${forcedLease.term.value}.",
            )
            return true
        }
        if (
            lease?.currentHolder == config.device.deviceId &&
            lease.conflictDetected &&
            isConflictRecoveryHoldActive(now, lease)
        ) {
            return false
        }
        if (
            lease != null &&
            lease.currentHolder != config.device.deviceId &&
            now < lease.expiresAt &&
            !lease.conflictDetected
        ) {
            return false
        }
        val balances = runCatching { exchange.fetchBalances() }.getOrDefault(emptyList())
        val marketQuotes = runCatching { exchange.fetchMarketQuotes() }.getOrDefault(emptyList())
        val openOrders = runCatching { exchange.fetchOpenOrders() }.getOrDefault(emptyList())
        val recentPersistedOrders = controlPlane.fetchRecentOrders(config.controlPlane.botId, limit = 200)
        val reconciliationPairs = (openOrders.map { it.pairId } + recentPersistedOrders.map { it.pairId })
            .distinct()
            .take(12)
        val fills = reconciliationPairs
            .flatMap { pairId ->
                runCatching { exchange.fetchRecentFills(pairId, limit = 20) }.getOrDefault(emptyList())
            }
        val reconciliation = reconciliationService.reconcile(
            portfolio = PortfolioSnapshot(
                botId = config.controlPlane.botId,
                balances = balances,
                openOrders = openOrders,
                positions = emptyList<PositionSnapshot>(),
                totalEquityIdr = estimatePortfolioValue(balances, marketQuotes),
                lastSyncedAt = now,
            ),
            recentFills = fills,
            persistedOrders = recentPersistedOrders,
        )

        val evaluation = leaseCoordinator.canAcquireMastership(
            now = now,
            currentLease = lease,
            requester = config.device.deviceId,
            reconciliationReport = reconciliation,
            requesterHealth = localHealth,
            desiredState = botState.desiredState,
        )

        val ambiguousRemoteState =
            lease != null &&
                lease.currentHolder != config.device.deviceId &&
                reconciliation.state != ReconciliationState.CLEAN

        if (
            lease?.currentHolder == config.device.deviceId &&
            lease.conflictDetected &&
            reconciliation.state == ReconciliationState.CLEAN &&
            localHealth.status != HealthStatus.CRITICAL
        ) {
            val recoveredLease = controlPlane.acquireLease(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                ttlSeconds = config.leaseTtlSeconds,
            )
            lastObservedLeaseTerm = recoveredLease.term
            activateConflictRecoveryHold(now, recoveredLease.term)
            appendAuditLog(
                level = LogLevel.WARN,
                category = "FAILOVER",
                message = "Lease conflict cleared by reacquiring term ${recoveredLease.term.value} after clean reconciliation.",
            )
            return true
        }

        if (!evaluation.allowed || ambiguousRemoteState) {
            val shouldEscalateConflict = when {
                reconciliation.state == ReconciliationState.BLOCKED -> true
                ambiguousRemoteState -> true
                lease?.conflictDetected == true && lease.currentHolder != config.device.deviceId -> true
                else -> false
            }
            if (shouldEscalateConflict) {
                if (!isConflictRecoveryHoldActive(now, lease)) {
                    val failSafeReason = buildList {
                        addAll(evaluation.reasons)
                        if (ambiguousRemoteState) {
                            addAll(reconciliation.notes)
                            if (reconciliation.notes.isEmpty()) {
                                add("Reconciliation butuh review sebelum takeover dilakukan.")
                            }
                        }
                    }.distinct().joinToString(" ")
                    controlPlane.markConflictSafeMode(
                        botId = config.controlPlane.botId,
                        reason = failSafeReason,
                    )
                    appendAuditLog(
                        level = LogLevel.ERROR,
                        category = "FAILOVER",
                        message = "Fail-safe takeover block triggered: $failSafeReason",
                    )
                }
            }
            return false
        }

        val acquiredLease = controlPlane.acquireLease(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            ttlSeconds = config.leaseTtlSeconds,
        )
        lastObservedLeaseTerm = acquiredLease.term

        appendAuditLog(
            level = LogLevel.WARN,
            category = "FAILOVER",
            message = "Mac acquired master lease term ${acquiredLease.term.value} after safe reconciliation.",
        )
        return true
    }

    private suspend fun handleLeadLagPayload(payloadJson: String?, now: Instant): String? {
        if (!config.leadLagSignalEnabled) return null  // Accept signals on any exchange
        val payload = payloadJson
            ?.takeIf { it.contains("lead_lag_breakout") || it.contains("\"msgType\"") }
            ?.let { raw -> runCatching { json.decodeFromString<LeadLagCalloutPayload>(raw) }.getOrNull() }
            ?: return null
        if (payload.kind != "lead_lag_breakout") return null
        val normalizedMsgType = payload.msgType.uppercase()
        val payloadPairId = PairId(payload.pairId)
        val payloadReason = payload.payload?.get("reason")?.jsonPrimitive?.contentOrNull?.uppercase()
        val pairCooldownReject = payload.senderBotId.contains("kibot", ignoreCase = true) &&
            normalizedMsgType == "VETO_REJECTED" &&
            payloadReason == "PAIR_COOLDOWN"
        if (pairCooldownReject) {
            val pairKey = payloadPairId.value.lowercase()
            val cooldownUntil = Instant.fromEpochMilliseconds(payload.expiresAtEpochMs).plus(sinBinHours.hours)
            sinBinUntilByPair[pairKey] = cooldownUntil
            logger.info(
                "SELL_DECISION_REASON pair={} reason=pair_cooldown_veto trace={} cooldownUntil={}",
                pairKey,
                payload.traceId,
                formatJktTime(cooldownUntil),
            )
            appendAuditLog(
                level = LogLevel.WARN,
                category = "LEAD_LAG",
                message = "KiBot blacklist/cooldown aktif untuk $pairKey sampai ${formatJktTime(cooldownUntil)}.",
            )
            repository.noteStatus("KiBot blacklist aktif untuk $pairKey sampai ${formatJktTime(cooldownUntil)}.")
            return "Pair ${payload.pairId} masuk cooldown dari KiBot; entry lokal diblok."
        }
        if (normalizedMsgType in setOf("DETECTOR_HIT", "INSTANT_BUY_ANOMALY")) {
            markDynamicVip(
                pairId = payloadPairId,
                now = now,
                reason = if (normalizedMsgType == "INSTANT_BUY_ANOMALY") "udp_instant_anomaly" else "udp_detector_hit",
            )
            armUdpExecutionPrewarm(payload, now)
        }
        val isEmergencySell = payload.senderBotId.contains("kibot", ignoreCase = true) &&
            normalizedMsgType == "EMERGENCY_VETO_SELL"
        if (isEmergencySell) {
            forcedSellTraceByPair[payload.pairId.lowercase()] = ForcedSellSignal(
                traceId = payload.traceId,
                expiresAtEpochMs = payload.expiresAtEpochMs,
            )
            val expiresAt = Instant.fromEpochMilliseconds(payload.expiresAtEpochMs)
            activeLeadLagCallout = ActiveLeadLagCallout(
                traceId = payload.traceId,
                senderBotId = payload.senderBotId,
                pairId = com.kibot.shared.models.PairId(payload.pairId),
                trend = "REVERSAL",
                msgType = normalizedMsgType,
                confidence = payload.confidence,
                expectedNetPct = payload.expectedNetPct,
                shortTermReturnPct = payload.shortTermReturnPct,
                coinClass = classifyPair(com.kibot.shared.models.PairId(payload.pairId)),
                sentAtEpochMs = payload.sentAtEpochMs,
                receivedAt = now,
                forceRotation = true,
                expiresAt = expiresAt,
            )
            logger.info(
                "SELL_SIGNAL_RECEIVED pair={} msgType={} trace={} transport={}ms trend=REVERSAL",
                payload.pairId.lowercase(),
                normalizedMsgType,
                payload.traceId,
                (now.toEpochMilliseconds() - payload.sentAtEpochMs).coerceAtLeast(0L),
            )
            return "Emergency veto sell diterima untuk ${payload.pairId}; force sell diprioritaskan."
        }
        val instantAnomalyDirect = payload.senderBotId.contains("kinance", ignoreCase = true) &&
            normalizedMsgType == "INSTANT_BUY_ANOMALY"
        val isKinanceSignal = payload.senderBotId.contains("kinance", ignoreCase = true) &&
            normalizedMsgType in setOf("DETECTOR_HIT", "INSTANT_BUY_ANOMALY", "SELL_WALL_SURGE", "MOMENTUM_LOSS")
        val isKibotVeto = payload.senderBotId.contains("kibot", ignoreCase = true) &&
            normalizedMsgType in setOf("VETO_APPROVED", "VETO_SELL_CONFIRMED")
        val trinitySignal = TrinityPendingSignal(
            traceId = payload.traceId,
            pairId = payload.pairId,
            trend = payload.trend.uppercase(),
            msgType = normalizedMsgType,
            senderBotId = payload.senderBotId,
            detectedAtEpochMs = payload.detectedAtEpochMs,
            sentAtEpochMs = payload.sentAtEpochMs,
            expiresAtEpochMs = payload.expiresAtEpochMs,
            confidence = payload.confidence,
            expectedNetPct = payload.expectedNetPct,
            forceRotation = payload.forceRotation,
        )
        if (isKinanceSignal) {
            pendingKinanceSignalsByTrace[payload.traceId] = trinitySignal
        }
        if (isKibotVeto) {
            pendingKibotVetosByTrace[payload.traceId] = trinitySignal
            armUdpExecutionPrewarm(payload, now)
        }
        val kinanceSignal = pendingKinanceSignalsByTrace[payload.traceId]
        val kibotVeto = pendingKibotVetosByTrace[payload.traceId]
        val bothReady = instantAnomalyDirect || (
            kinanceSignal != null && kibotVeto != null &&
            kinanceSignal.pairId.equals(kibotVeto.pairId, ignoreCase = true)
            )
        if (!bothReady) {
            return "Menunggu double confirmation trace=${payload.traceId} msgType=$normalizedMsgType."
        }
        val signalEnvelope = if (instantAnomalyDirect) trinitySignal else (kinanceSignal ?: trinitySignal)
        if (instantAnomalyDirect) {
            pendingKinanceSignalsByTrace.remove(payload.traceId)
            pendingKibotVetosByTrace.remove(payload.traceId)
        }
        if (signalEnvelope.expiresAtEpochMs <= now.toEpochMilliseconds()) {
            pendingKinanceSignalsByTrace.remove(payload.traceId)
            pendingKibotVetosByTrace.remove(payload.traceId)
            logger.info(
                "SELL_DECISION_REASON pair={} reason=signal_expired trace={} ageMs={}",
                signalEnvelope.pairId.lowercase(),
                payload.traceId,
                (now.toEpochMilliseconds() - signalEnvelope.sentAtEpochMs).coerceAtLeast(0L),
            )
            return "Signal ${payload.traceId} expired sebelum double confirmation lengkap."
        }
        val coinClass = classifyPair(PairId(signalEnvelope.pairId))
        if (!isLeadLagClassEnabled(coinClass)) {
            updateLeadLagStats(coinClass) { it.copy(rejectedClassDisabled = it.rejectedClassDisabled + 1) }
            return "Lead-lag callout ${signalEnvelope.pairId} diabaikan karena kelas ${coinClass.name.lowercase()} nonaktif."
        }
        val ttlMs = (signalEnvelope.expiresAtEpochMs - signalEnvelope.sentAtEpochMs).coerceAtLeast(0L)
        val ageMs = (now.toEpochMilliseconds() - signalEnvelope.sentAtEpochMs).coerceAtLeast(0L)
        if (isLeadLagPayloadTooOld(Instant.fromEpochMilliseconds(signalEnvelope.sentAtEpochMs), now)) {
            pendingKinanceSignalsByTrace.remove(payload.traceId)
            pendingKibotVetosByTrace.remove(payload.traceId)
            updateLeadLagStats(coinClass) { it.copy(rejectedTooOld = it.rejectedTooOld + 1) }
            logger.info(
                "SELL_DECISION_REASON pair={} reason=stale_abort trace={} ageMs={} limitMs={}",
                signalEnvelope.pairId.lowercase(),
                payload.traceId,
                ageMs,
                leadLagSignalMaxAgeMillis,
            )
            return "Signal ${payload.traceId} dibatalkan: stale ${ageMs}ms (batas ${leadLagSignalMaxAgeMillis}ms)."
        }
        val highVelocity = payload.shortTermReturnPct >= leadLagFreshnessHighVelocityShortReturnPct ||
            payload.tradeActivityScore >= leadLagFreshnessHighVelocityTradeScore
        if (highVelocity && ttlMs > 0L && ageMs > (ttlMs / 2L)) {
            updateLeadLagStats(coinClass) { it.copy(rejectedTooOld = it.rejectedTooOld + 1) }
            return "Lead-lag callout ${signalEnvelope.pairId} diabaikan karena terlalu tua (${ageMs}ms/${ttlMs}ms) saat high-velocity."
        }
        val expiresAt = Instant.fromEpochMilliseconds(signalEnvelope.expiresAtEpochMs)
        if (expiresAt <= now) return "Lead-lag callout ${signalEnvelope.pairId} diabaikan karena sudah kedaluwarsa."
        val transportLatencyMs = (now.toEpochMilliseconds() - signalEnvelope.sentAtEpochMs).coerceAtLeast(0L)
        val nextCallout = ActiveLeadLagCallout(
            traceId = payload.traceId,
            senderBotId = signalEnvelope.senderBotId,
            pairId = com.kibot.shared.models.PairId(signalEnvelope.pairId),
            trend = signalEnvelope.trend.uppercase(),
            msgType = signalEnvelope.msgType.uppercase(),
            confidence = signalEnvelope.confidence,
            expectedNetPct = signalEnvelope.expectedNetPct,
            shortTermReturnPct = payload.shortTermReturnPct,
            coinClass = coinClass,
            sentAtEpochMs = signalEnvelope.sentAtEpochMs,
            receivedAt = now,
            forceRotation = signalEnvelope.forceRotation && config.leadLagForceRotationOnReceive,
            expiresAt = expiresAt,
        )
        activeLeadLagCallout = nextCallout
        if (normalizedMsgType == "SELL_WALL_SURGE" || normalizedMsgType == "MOMENTUM_LOSS" || normalizedMsgType == "VETO_SELL_CONFIRMED") {
            logger.info(
                "SELL_SIGNAL_RECEIVED pair={} msgType={} trace={} transport={}ms trend={}",
                payload.pairId.lowercase(),
                normalizedMsgType,
                payload.traceId,
                transportLatencyMs,
                signalEnvelope.trend.uppercase(),
            )
        }
        val pairKey = signalEnvelope.pairId.lowercase()
        leadLagTraceByPair[pairKey] = payload.traceId
        leadLagDetectedAtByPair[pairKey] = signalEnvelope.detectedAtEpochMs
        leadLagOriginSentAtByPair[pairKey] = signalEnvelope.sentAtEpochMs
        leadLagReceivedAtByPair[pairKey] = now
        updateLeadLagStats(coinClass) { it.copy(accepted = it.accepted + 1) }
        appendAuditLog(
            level = LogLevel.INFO,
            category = "LEAD_LAG",
            message = "KiDax terima callout ${signalEnvelope.pairId} kelas=${coinClass.name.lowercase()} transport=${transportLatencyMs}ms ttl=${(signalEnvelope.expiresAtEpochMs - signalEnvelope.sentAtEpochMs).coerceAtLeast(0L)}ms.",
        )
        emitLeadLagTelemetry(
            LeadLagTelemetryEvent(
                event = "T2_UDP_RECEIVED",
                traceId = payload.traceId,
                pairId = signalEnvelope.pairId,
                coinClass = coinClass.name.lowercase(),
                sourceBotId = signalEnvelope.senderBotId,
                targetBotId = config.controlPlane.botId.value,
                t0DetectedAtEpochMs = signalEnvelope.detectedAtEpochMs,
                t1UdpSentAtEpochMs = signalEnvelope.sentAtEpochMs,
                t2UdpReceivedAtEpochMs = now.toEpochMilliseconds(),
                transportLatencyMs = transportLatencyMs,
                note = "Callout diterima KiDax.",
            ),
        )
        if (transportLatencyMs >= leadLagAlarmTransportLatencyMs) {
            val shouldAlert = lastLeadLagAlarmAt == null ||
                (now - (lastLeadLagAlarmAt ?: now)).inWholeMilliseconds >= leadLagAlarmCooldownMillis
            if (shouldAlert) {
                lastLeadLagAlarmAt = now
                appendAuditLog(
                    level = LogLevel.WARN,
                    category = "LEAD_LAG",
                    message = "Alarm latency lead-lag tinggi: ${transportLatencyMs}ms untuk ${payload.pairId}.",
                )
            }
        }
        if (kibotVeto?.msgType == "VETO_SELL_CONFIRMED" || signalEnvelope.trend.equals("REVERSAL", ignoreCase = true)) {
            val nowSeen = now
            val firstSeen = sellWallFirstSeenAtByPair[pairKey]
            if (firstSeen == null) {
                sellWallFirstSeenAtByPair[pairKey] = nowSeen
                pendingKinanceSignalsByTrace.remove(payload.traceId)
                pendingKibotVetosByTrace.remove(payload.traceId)
                return "SELL wall baru terdeteksi untuk ${payload.pairId}; tunggu konfirmasi >3 detik (anti-spoof)."
            }
            val seenMs = (nowSeen - firstSeen).inWholeMilliseconds
            val confirmWindowMs = when {
                normalizedMsgType == "VETO_SELL_CONFIRMED" || normalizedMsgType == "MOMENTUM_LOSS" -> leadLagSellWallFastConfirmMs
                kotlin.math.abs(payload.shortTermReturnPct) >= 2.2 || payload.tradeActivityScore >= 0.78 -> leadLagSellWallFastConfirmMs
                else -> leadLagSellWallConfirmMs
            }
            if (seenMs < confirmWindowMs) {
                pendingKinanceSignalsByTrace.remove(payload.traceId)
                pendingKibotVetosByTrace.remove(payload.traceId)
                return "SELL wall ${payload.pairId} ditahan (${seenMs}ms/${confirmWindowMs}ms) untuk anti-spoof."
            }
            forcedSellTraceByPair[pairKey] = ForcedSellSignal(
                traceId = payload.traceId,
                expiresAtEpochMs = payload.expiresAtEpochMs,
            )
            sellWallFirstSeenAtByPair.remove(pairKey)
        }
        pendingKinanceSignalsByTrace.remove(payload.traceId)
        pendingKibotVetosByTrace.remove(payload.traceId)
        return "Lead-lag callout aktif: ${payload.pairId} dari ${payload.senderBotId}, force rotate siap diprioritaskan."
    }

    private fun isLeadLagPayloadTooOld(sentAt: Instant, now: Instant): Boolean {
        return (now - sentAt).inWholeMilliseconds > leadLagSignalMaxAgeMillis
    }

    private fun nextUdpSequenceId(): Int = udpSequenceCounter.getAndIncrement().coerceAtLeast(1)

    private fun senderCodeFor(botId: String): Byte = when {
        botId.contains("kinance", ignoreCase = true) -> 1
        botId.contains("kibot", ignoreCase = true) -> 2
        botId.contains("kidax", ignoreCase = true) -> 3
        else -> 15
    }

    private fun botIdForSenderCode(code: Byte): String = when (code.toInt()) {
        1 -> "kinance"
        2 -> "kibot"
        3 -> "kidax"
        else -> "unknown"
    }

    private fun trendCodeFor(trend: String): Byte = when {
        trend.equals("REVERSAL", ignoreCase = true) -> 2
        trend.equals("ANOMALY_UP", ignoreCase = true) -> 3
        trend.equals("GRADUAL_UP", ignoreCase = true) -> 4
        else -> 1
    }

    private fun trendForCode(code: Byte): String = when (code.toInt()) {
        2 -> "REVERSAL"
        3 -> "ANOMALY_UP"
        4 -> "GRADUAL_UP"
        else -> "UP"
    }

    private fun ByteBuffer.putFixedAscii(value: String?, length: Int) {
        val bytes = (value ?: "").toByteArray(Charsets.US_ASCII).copyOf(length)
        put(bytes)
    }

    private fun ByteBuffer.readFixedAscii(length: Int): String {
        val bytes = ByteArray(length)
        get(bytes)
        return bytes.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ').trim()
    }

    private fun trimUdpCommandCaches(now: Instant) {
        val cutoff = now - (config.leadLagUdpDedupTtlMillis * 2L).milliseconds
        udpRecentDedupKeys.entries.removeIf { (_, seenAt) -> seenAt < cutoff }
        udpExecutionPrewarmByPair.entries.removeIf { (_, prewarm) -> prewarm.expiresAt <= now }
    }

    private fun shouldRejectUdpSequence(senderBotId: String, sequenceId: Int): Boolean {
        val sender = senderBotId.trim().lowercase()
        if (sender.isBlank()) return false
        val lastSeen = udpLastSequenceBySender[sender] ?: run {
            udpLastSequenceBySender[sender] = sequenceId
            return false
        }
        if (sequenceId <= lastSeen) return true
        val staleFloor = sequenceId + config.leadLagUdpSequenceWindowSize
        if (staleFloor < lastSeen) return true
        udpLastSequenceBySender[sender] = sequenceId
        return false
    }

    private fun shouldRejectUdpDedup(dedupKey: String?, now: Instant): Boolean {
        val key = dedupKey?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val seenAt = udpRecentDedupKeys[key] ?: run {
            udpRecentDedupKeys[key] = now
            return false
        }
        return if ((now - seenAt).inWholeMilliseconds <= config.leadLagUdpDedupTtlMillis) {
            true
        } else {
            udpRecentDedupKeys[key] = now
            false
        }
    }

    private fun armUdpExecutionPrewarm(payload: LeadLagCalloutPayload, now: Instant) {
        val pairId = PairId(payload.pairId)
        val expiresAt = Instant.fromEpochMilliseconds(payload.sentAtEpochMs) + config.leadLagUdpPrewarmTtlMillis.milliseconds
        udpExecutionPrewarmByPair[pairId.value.lowercase()] = UdpExecutionPrewarm(
            traceId = payload.traceId,
            pairId = pairId,
            armedAt = now,
            expiresAt = expiresAt,
            msgType = payload.msgType.uppercase(),
        )
    }

    private fun isUdpExecutionPrewarmActive(pairId: PairId, now: Instant): Boolean {
        val prewarm = udpExecutionPrewarmByPair[pairId.value.lowercase()] ?: return false
        if (prewarm.expiresAt <= now) {
            udpExecutionPrewarmByPair.remove(pairId.value.lowercase())
            return false
        }
        return true
    }

    private fun encodeBinaryLeadLagPacket(payload: LeadLagCalloutPayload): ByteArray? {
        val msgType = UdpBinaryMessageType.fromMsgType(payload.msgType)
        if (msgType == UdpBinaryMessageType.UNKNOWN) return null
        val buffer = ByteBuffer.allocate(84).order(ByteOrder.BIG_ENDIAN)
        buffer.put('K'.code.toByte())
        buffer.put('B'.code.toByte())
        buffer.put(1)
        buffer.put(msgType.code)
        var flags = 0
        if (payload.forceRotation) flags = flags or 0x01
        buffer.put(flags.toByte())
        buffer.put(senderCodeFor(payload.senderBotId))
        buffer.put(trendCodeFor(payload.trend))
        buffer.put(0)
        buffer.putInt(nextUdpSequenceId())
        buffer.putLong(payload.sentAtEpochMs)
        buffer.putLong(payload.detectedAtEpochMs)
        buffer.putLong(payload.expiresAtEpochMs)
        buffer.putInt(payload.traceId.hashCode())
        buffer.putFixedAscii(payload.pairId, 24)
        buffer.putFloat(payload.confidence.toFloat())
        buffer.putFloat(payload.expectedNetPct.toFloat())
        buffer.putFloat(payload.shortTermReturnPct.toFloat())
        buffer.putFloat(payload.tradeActivityScore.toFloat())
        return buffer.array()
    }

    private fun encodeBinaryHeartbeatPacket(payload: TrinityHeartbeatPayload): ByteArray {
        val buffer = ByteBuffer.allocate(84).order(ByteOrder.BIG_ENDIAN)
        buffer.put('K'.code.toByte())
        buffer.put('B'.code.toByte())
        buffer.put(1)
        buffer.put(UdpBinaryMessageType.HEARTBEAT.code)
        var flags = 0
        if (payload.safeModeArmed) flags = flags or 0x02
        buffer.put(flags.toByte())
        buffer.put(senderCodeFor(payload.senderBotId))
        buffer.put(0)
        buffer.put(0)
        buffer.putInt(nextUdpSequenceId())
        buffer.putLong(payload.sentAtEpochMs)
        buffer.putLong(payload.sentAtEpochMs)
        buffer.putLong(payload.sentAtEpochMs + config.leadLagUdpHeartbeatTimeoutMillis)
        buffer.putInt((payload.activePair ?: "").hashCode())
        buffer.putFixedAscii(payload.activePair, 24)
        repeat(4) { buffer.putFloat(0f) }
        return buffer.array()
    }

    private fun decodeBinaryUdpPacket(packetBytes: ByteArray, length: Int): DecodedUdpPacket? {
        if (length < 84) return null
        val buffer = ByteBuffer.wrap(packetBytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magicA = buffer.get()
        val magicB = buffer.get()
        if (magicA != 'K'.code.toByte() || magicB != 'B'.code.toByte()) return null
        val version = buffer.get()
        if (version.toInt() != 1) return null
        val msgType = UdpBinaryMessageType.fromCode(buffer.get())
        val flags = buffer.get().toInt()
        val senderBotId = botIdForSenderCode(buffer.get())
        val trend = trendForCode(buffer.get())
        buffer.get()
        val sequenceId = buffer.int
        val sentAtEpochMs = buffer.long
        val detectedAtEpochMs = buffer.long
        val expiresAtEpochMs = buffer.long
        val traceHash = buffer.int
        val pairId = buffer.readFixedAscii(24)
        val confidence = buffer.float.toDouble()
        val expectedNetPct = buffer.float.toDouble()
        val shortTermReturnPct = buffer.float.toDouble()
        val tradeActivityScore = buffer.float.toDouble()
        return when (msgType) {
            UdpBinaryMessageType.HEARTBEAT -> DecodedUdpPacket(
                heartbeat = TrinityHeartbeatPayload(
                    senderBotId = senderBotId,
                    sentAtEpochMs = sentAtEpochMs,
                    activePair = pairId.takeIf { it.isNotBlank() },
                    safeModeArmed = flags and 0x02 != 0,
                ),
                senderBotId = senderBotId,
                sequenceId = sequenceId,
                binary = true,
            )
            UdpBinaryMessageType.UNKNOWN -> null
            else -> {
                val dedupKey = "${senderBotId.lowercase()}:${msgType.wireMsgType}:${pairId.lowercase()}:${traceHash}"
                DecodedUdpPacket(
                    leadLag = LeadLagCalloutPayload(
                        msgType = msgType.wireMsgType,
                        traceId = "udp-$traceHash",
                        senderBotId = senderBotId,
                        pairId = pairId,
                        trend = trend,
                        detectedAtEpochMs = detectedAtEpochMs,
                        confidence = confidence,
                        expectedNetPct = expectedNetPct,
                        shortTermReturnPct = shortTermReturnPct,
                        mediumTermReturnPct = 0.0,
                        tradeActivityScore = tradeActivityScore,
                        forceRotation = flags and 0x01 != 0,
                        sentAtEpochMs = sentAtEpochMs,
                        expiresAtEpochMs = expiresAtEpochMs,
                    ),
                    senderBotId = senderBotId,
                    sequenceId = sequenceId,
                    dedupKey = dedupKey,
                    binary = true,
                )
            }
        }
    }

    private fun ensureLeadLagListenerInitialized() {
        if (!config.leadLagUdpEnabled) return
        if (leadLagListenerReady.get()) return
        runCatching {
            val socket = DatagramSocket(config.leadLagUdpListenPort)
            // FIX: Increased timeout from 5ms to 50ms to prevent packet drops
            // 5ms was too tight - under load or network jitter, valid packets were being dropped
            // causing missed trading signals and potential financial loss
            socket.soTimeout = 50
            // FIX: Set receive buffer to 8MB to handle burst traffic from KiNance
            // Default buffer (~64KB-256KB) can overflow during high-frequency signal bursts
            socket.receiveBufferSize = 8 * 1024 * 1024
            leadLagListenerSocket = socket
            leadLagListenerReady.set(true)
            // suppressed
        }.onFailure {
            logger.warn("Lead-lag UDP listener init failed: {}", it.message)
        }
    }

    private suspend fun pollLeadLagUdpCommands(now: Instant) {
        val socket = leadLagListenerSocket ?: return
        trimUdpCommandCaches(now)
        // Process up to 200 packets per poll to drain buffer (heartbeat at 100ms = ~80 packets per 8s poll)
        repeat(200) {
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val decodedBinary = if (config.leadLagUdpBinaryProtocolEnabled || config.leadLagUdpBinaryDualStackEnabled) {
                    decodeBinaryUdpPacket(packet.data, packet.length)
                } else {
                    null
                }
                if (decodedBinary != null) {
                    val senderBotId = decodedBinary.senderBotId.orEmpty()
                    val sequenceId = decodedBinary.sequenceId
                    if (sequenceId != null && shouldRejectUdpSequence(senderBotId, sequenceId)) return@repeat
                    if (shouldRejectUdpDedup(decodedBinary.dedupKey, now)) return@repeat
                    val heartbeat = decodedBinary.heartbeat
                    if (heartbeat != null && handleTrinityHeartbeatPayload(json.encodeToString(heartbeat), now)) return@repeat
                    val leadLag = decodedBinary.leadLag
                    if (leadLag != null) {
                        handleLeadLagPayload(json.encodeToString(leadLag), now)
                        return@repeat
                    }
                }
                val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (handleTrinityHeartbeatPayload(payload, now)) return@repeat
                if (handleActivePositionsPayload(payload)) return@repeat
                if (handleDynamicCorrelationPayload(payload)) return@repeat
                if (handleAiProviderStatusPayload(payload, now)) return@repeat
                handleLeadLagPayload(payload, now)
            } catch (_: SocketTimeoutException) {
                return
            } catch (error: Throwable) {
                logger.warn("Lead-lag UDP receive failed: {}", error.message)
                return
            }
        }
    }

    private fun maybeEmitTrinityHeartbeat(now: Instant) {
        if (!config.leadLagUdpEnabled || !config.leadLagUdpHeartbeatEnabled) return
        if (config.leadLagUdpHeartbeatRequiredBotIds.isEmpty()) return
        val lastSent = lastTrinityHeartbeatSentAt
        if (lastSent != null && (now - lastSent).inWholeMilliseconds < config.leadLagUdpHeartbeatIntervalMillis) return
        val payload = TrinityHeartbeatPayload(
            senderBotId = config.controlPlane.botId.value,
            sentAtEpochMs = now.toEpochMilliseconds(),
            activePair = activeLeadLagCallout?.pairId?.value ?: lastSuperSexyTarget?.value,
            safeModeArmed = trinityHeartbeatSafeModeReason != null,
        )
        if (sendLeadLagUdp(json.encodeToString(payload))) {
            lastTrinityHeartbeatSentAt = now
        }
    }

    private suspend fun enforceTrinityHeartbeatBrake(now: Instant): String? {
        if (!config.leadLagUdpEnabled || !config.leadLagUdpHeartbeatEnabled) return null
        val expectedBots = config.leadLagUdpHeartbeatRequiredBotIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != config.controlPlane.botId.value.lowercase() }
            .toSet()
        if (expectedBots.isEmpty()) return null
        val graceMs = maxOf(
            config.leadLagUdpHeartbeatTimeoutMillis * 2L,
            config.leadLagUdpHeartbeatIntervalMillis * 4L,
        )
        val uptimeMs = (now - daemonStartedAt).inWholeMilliseconds
        if (uptimeMs < graceMs) return null
        val stalePeers = expectedBots.mapNotNull { botId ->
            val seenAt = lastTrinityHeartbeatByBotId[botId]
            if (seenAt == null) {
                "$botId:no_heartbeat"
            } else {
                val ageMs = (now - seenAt).inWholeMilliseconds
                if (ageMs > config.leadLagUdpHeartbeatTimeoutMillis) "$botId:${ageMs}ms" else null
            }
        }
        if (stalePeers.isEmpty()) {
            val previousReason = trinityHeartbeatSafeModeReason
            if (previousReason != null) {
                trinityHeartbeatSafeModeReason = null
                repository.noteStatus("Trinity heartbeat pulih; safe mode dilepas.")
                appendAuditLog(
                    level = LogLevel.INFO,
                    category = "TRINITY_HEARTBEAT",
                    message = "Trinity heartbeat recovered; safe mode cleared.",
                )
            }
            return null
        }
        val reason = "Trinity heartbeat timeout: ${stalePeers.joinToString(", ")}. Suspend new entries, biarkan trailing lokal mengurus exit."
        if (trinityHeartbeatSafeModeReason == null) {
            trinityHeartbeatSafeModeReason = reason
            controlPlane.markConflictSafeMode(
                botId = config.controlPlane.botId,
                reason = reason,
            )
            repository.noteStatus(reason)
            appendAuditLog(
                level = LogLevel.ERROR,
                category = "TRINITY_HEARTBEAT",
                message = reason,
            )
        }
        return reason
    }

    private fun String.isOperationalHealthWarning(): Boolean {
        val normalized = lowercase()
        return listOf(
            "unreachable",
            "broken",
            "critical",
            "halt",
            "denied",
            "reconciliation",
        ).any { token -> normalized.contains(token) }
    }

    private fun handleActivePositionsPayload(payload: String): Boolean {
        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val msgType = root["msgType"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return false
            if (msgType != "ACTIVE_POSITIONS") return false
            if (config.exchangeKind != ExchangeKind.BINANCE_SPOT) return false
            val positionsNode = root["positions"]?.jsonArray ?: return false
            kidaxActivePositionsByPair.clear()
            positionsNode.forEach { node ->
                val obj = node.jsonObject
                val pairId = obj["pairId"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim().orEmpty()
                if (pairId.isBlank()) return@forEach
                val wire = ActivePositionWire(
                    pairId = pairId,
                    entryPrice = obj["entryPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                    currentPrice = obj["currentPrice"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                    pnlPct = obj["pnlPct"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                    pnlIdr = obj["pnlIdr"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                    quantity = obj["quantity"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                    notionalIdr = obj["notionalIdr"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                )
                kidaxActivePositionsByPair[pairId] = wire
            }
            true
        }.getOrDefault(false)
    }

    private fun handleDynamicCorrelationPayload(payload: String): Boolean {
        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val msgType = root["msgType"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return false
            if (msgType != "CORRELATION_MATRIX") return false
            val sectorsNode = root["sectors"] ?: return false
            val parsed = sectorsNode.jsonObject.mapValues { (_, arr) ->
                arr.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }.toSet()
            }.filterValues { it.isNotEmpty() }
            if (parsed.isNotEmpty()) {
                dynamicSectorCorrelationBook = parsed
            }
            true
        }.getOrDefault(false)
    }

    private fun handleAiProviderStatusPayload(payload: String, now: Instant): Boolean {
        return runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val msgType = root["msgType"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return false
            if (msgType != "AI_PROVIDER_STATUS") return false
            val ok = root["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val provider = root["provider"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val task = root["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            aiRuntimeProviderStatusLabel = when {
                ok && provider.isNotBlank() -> "AI sehat: ${provider.uppercase()} ($task)"
                ok -> "AI ONLINE (standby)"
                else -> "AI LIMITED (provider failover)"
            }
            aiRuntimeProviderStatusAt = now
            true
        }.getOrDefault(false)
    }

    private fun handleTrinityHeartbeatPayload(payload: String, now: Instant): Boolean {
        return runCatching {
            val heartbeat = json.decodeFromString<TrinityHeartbeatPayload>(payload)
            if (heartbeat.kind != "trinity_state") return false
            if (!heartbeat.msgType.equals("HEARTBEAT", ignoreCase = true)) return false
            val sender = heartbeat.senderBotId.trim().lowercase()
            if (sender.isBlank() || sender == config.controlPlane.botId.value.lowercase()) return true
            lastTrinityHeartbeatByBotId[sender] = now
            true
        }.getOrDefault(false)
    }

    private fun shouldYieldToPrimary(
        botState: BotStateSnapshot,
        now: Instant,
    ): Boolean {
        if (config.device.role != DeviceRole.STANDBY) return false
        val activeDeviceId = botState.activeDeviceId ?: return false
        if (activeDeviceId == config.device.deviceId) return false
        val lastHeartbeatAt = botState.lastHeartbeatAt ?: return false
        val heartbeatAgeMs = now.toEpochMilliseconds() - lastHeartbeatAt.toEpochMilliseconds()
        val graceWindowMs = (config.leaseTtlSeconds * 1_000L) + 8_000L
        return heartbeatAgeMs in 0..graceWindowMs && botState.syncHealth != SyncHealth.BROKEN
    }

    private fun isInReleaseCooldown(now: Instant): Boolean {
        val until = releaseCooldownUntil ?: return false
        return now < until
    }

    private fun enterReleaseCooldown() {
        releaseCooldownUntil = Clock.System.now().plus((config.leaseTtlSeconds + 12).seconds)
    }

    private fun buildLocalHealth(
        exchangeReachable: Boolean,
        warnings: List<String>,
        feedLatencyMs: Long? = null,
        supabaseReachable: Boolean = isControlPlaneReachable(Clock.System.now()),
        marketFeedHealthy: Boolean = exchangeReachable,
        reportOnlyMode: Boolean = false,
    ): EngineHealthSnapshot {
        val exchangeHardDown = !exchangeReachable && consecutiveExchangeProbeFailures >= 2 && !reportOnlyMode
        val severeWarnings = warnings.filter { it.isSevereHealthWarning() }
        val controlPlaneFailOpen =
            !supabaseReachable &&
                exchangeReachable &&
                !reportOnlyMode &&
                config.device.role == DeviceRole.PRIMARY
        val status = when {
            exchangeHardDown || (!supabaseReachable && !controlPlaneFailOpen) -> HealthStatus.CRITICAL
            controlPlaneFailOpen -> HealthStatus.WARNING
            reportOnlyMode && supabaseReachable -> HealthStatus.HEALTHY
            !marketFeedHealthy || severeWarnings.isNotEmpty() -> HealthStatus.WARNING
            else -> HealthStatus.HEALTHY
        }
        val syncHealth = when {
            status == HealthStatus.CRITICAL -> SyncHealth.BROKEN
            status == HealthStatus.WARNING -> SyncHealth.DEGRADED
            else -> SyncHealth.HEALTHY
        }
        val filteredWarnings = if (reportOnlyMode) {
            severeWarnings.filterNot { it.contains("Exchange unreachable or credentials not configured.", ignoreCase = true) }
        } else {
            severeWarnings
        } + listOfNotNull(
            "Control plane unreachable; fail-open local trading mode active.".takeIf { controlPlaneFailOpen },
        )
        return EngineHealthSnapshot(
            status = status,
            syncHealth = syncHealth,
            websocketHealthy = if (reportOnlyMode) true else marketFeedHealthy,
            exchangeReachable = exchangeReachable,
            supabaseReachable = supabaseReachable,
            feedLatencyMs = feedLatencyMs,
            fillQualityScore = if (filteredWarnings.any { it.contains("fill", ignoreCase = true) }) 0.35 else 0.75,
            anomalyCount = filteredWarnings.size,
            lastError = filteredWarnings.firstOrNull(),
            warnings = filteredWarnings.distinct(),
        )
    }

    private fun String.isSevereHealthWarning(): Boolean {
        val normalized = lowercase()
        return listOf(
            "exchange unreachable",
            "daily hard stop",
            "heartbeat timeout",
            "reconciliation",
            "blocked",
            "error",
            "critical",
            "broken",
        ).any { token -> normalized.contains(token) }
    }

    private fun isControlPlaneReachable(now: Instant): Boolean {
        val lastSuccess = lastSuccessfulControlPlaneAt ?: return false
        val stalenessMs = (now.toEpochMilliseconds() - lastSuccess.toEpochMilliseconds()).coerceAtLeast(0L)
        val graceWindowMs = (config.pollIntervalMillis * 8L).coerceAtLeast(30_000L)
        return stalenessMs <= graceWindowMs
    }

    private fun shouldRefresh(
        now: Instant,
        lastFetchedAt: Instant?,
        intervalMillis: Long,
        force: Boolean = false,
    ): Boolean {
        if (force || lastFetchedAt == null) return true
        return (now - lastFetchedAt).inWholeMilliseconds >= intervalMillis
    }

    private suspend fun probeExchange(now: Instant): Pair<Boolean, Long?> {
        if (!shouldRefresh(now, lastExchangeProbeAt, config.exchangePingRefreshIntervalMillis, force = lastExchangeProbeAt == null)) {
            return lastExchangeReachable to lastExchangePingMs
        }
        val publicPingUrl = when (config.exchangeKind) {
            ExchangeKind.BINANCE_SPOT -> "${config.binanceClientConfig.publicBaseUrl}/api/v3/ping"
            ExchangeKind.INDODAX -> "${config.indodaxClientConfig.publicBaseUrl}/ticker/btcidr"
        }
        val publicPingStartedAtNs = System.nanoTime()
        var exchangeReachable = probePublicHttpUrl(publicPingUrl)
        var exchangePingMs = ((System.nanoTime() - publicPingStartedAtNs) / 1_000_000L)
            .takeIf { exchangeReachable }
            ?.coerceAtLeast(1L)

        if (!exchangeReachable) {
            val pingStartedAtNs = System.nanoTime()
            exchangeReachable = runCatching { exchange.ping() }.getOrElse { false }
            exchangePingMs = ((System.nanoTime() - pingStartedAtNs) / 1_000_000L)
                .takeIf { exchangeReachable }
                ?.coerceAtLeast(1L)
        }

        if (!exchangeReachable) {
            val fallbackStartedAtNs = System.nanoTime()
            val fallbackReachable = runCatching {
                exchange.fetchMarketQuotes().isNotEmpty()
            }.getOrDefault(false)
            if (fallbackReachable) {
                exchangeReachable = true
                exchangePingMs = ((System.nanoTime() - fallbackStartedAtNs) / 1_000_000L).coerceAtLeast(1L)
                logger.info("Exchange probe fallback succeeded via market quotes.")
            }
        }
        lastExchangeProbeAt = now
        lastExchangeReachable = exchangeReachable
        lastExchangePingMs = exchangePingMs
        consecutiveExchangeProbeFailures = if (exchangeReachable) 0 else (consecutiveExchangeProbeFailures + 1).coerceAtMost(10)
        return exchangeReachable to exchangePingMs
    }

    private fun probePublicHttpUrl(url: String): Boolean {
        return runCatching {
            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "KiBot/1.0 (+https://kibot.local)")
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            connection.instanceFollowRedirects = true
            connection.connect()
            connection.responseCode in 200..299
        }.getOrDefault(false)
    }

    private fun activateConflictRecoveryHold(
        now: Instant,
        term: com.kibot.shared.models.LeaseTerm,
    ) {
        conflictRecoveryTerm = term
        conflictRecoveryHoldUntil = now.plus(35.seconds)
    }

    private fun isConflictRecoveryHoldActive(
        now: Instant,
        lease: EngineLeaseSnapshot?,
    ): Boolean {
        val until = conflictRecoveryHoldUntil ?: return false
        val termMatches = conflictRecoveryTerm == null || lease?.term == conflictRecoveryTerm
        val sameHolder = lease?.currentHolder == config.device.deviceId
        return sameHolder && termMatches && now < until
    }

    private suspend fun refreshDevices(now: Instant): List<DeviceDescriptor> {
        if (!shouldRefresh(now, devicesFetchedAt, config.devicesRefreshIntervalMillis, force = cachedDevices.isEmpty())) {
            return cachedDevices
        }
        cachedDevices = withTimeoutOrNull(4_000L) {
            runCatching { controlPlane.fetchDevices(config.controlPlane.botId) }.getOrElse { cachedDevices }
        } ?: cachedDevices
        devicesFetchedAt = now
        return cachedDevices
    }

    private suspend fun refreshDailyRisk(
        now: Instant,
        date: kotlinx.datetime.LocalDate,
    ): DailyRiskSnapshot? {
        val force = cachedDailyRiskDate != date
        if (!shouldRefresh(now, dailyRiskFetchedAt, config.dailyRiskRefreshIntervalMillis, force = force)) {
            return cachedDailyRisk
        }
        cachedDailyRisk = withTimeoutOrNull(4_000L) {
            runCatching { controlPlane.fetchDailyRisk(config.controlPlane.botId, date) }.getOrElse { cachedDailyRisk }
        } ?: cachedDailyRisk
        cachedDailyRiskDate = date
        dailyRiskFetchedAt = now
        return cachedDailyRisk
    }

    private suspend fun refreshPendingCommands(now: Instant): List<CommandEnvelope> {
        if (!shouldRefresh(now, commandsFetchedAt, config.commandsRefreshIntervalMillis, force = commandsFetchedAt == null)) {
            return emptyList()
        }
        commandsFetchedAt = now
        return withTimeoutOrNull(4_000L) {
            runCatching {
                controlPlane.fetchPendingCommands(config.controlPlane.botId, config.device.deviceId)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    private suspend fun refreshWeeklyReview(now: Instant): com.kibot.shared.models.WeeklyLearningSummary? {
        if (!shouldRefresh(now, weeklyReviewFetchedAt, config.weeklySummaryRefreshIntervalMillis, force = weeklyReviewFetchedAt == null && cachedWeeklyReview == null)) {
            return cachedWeeklyReview
        }
        cachedWeeklyReview = withTimeoutOrNull(4_000L) {
            runCatching {
                controlPlane.fetchLatestWeeklyLearningSummary(config.controlPlane.botId)
            }.getOrElse { cachedWeeklyReview }
        } ?: cachedWeeklyReview
        weeklyReviewFetchedAt = now
        return cachedWeeklyReview
    }

    private suspend fun refreshEquityHistory(now: Instant): List<com.kibot.shared.models.DailyEquityHistoryPoint> {
        if (!shouldRefresh(now, equityHistoryFetchedAt, 60_000L, force = cachedEquityHistory.isEmpty())) {
            return cachedEquityHistory
        }
        cachedEquityHistory = withTimeoutOrNull(4_000L) {
            runCatching {
                controlPlane.fetchDailyRiskHistory(config.controlPlane.botId, days = 40)
            }.getOrElse { cachedEquityHistory }
        } ?: cachedEquityHistory
        equityHistoryFetchedAt = now
        return cachedEquityHistory
    }

    private suspend fun refreshBalances(now: Instant): List<BalanceSnapshot> {
        if (!shouldRefresh(now, balancesFetchedAt, config.balanceRefreshIntervalMillis, force = cachedBalances.isEmpty())) {
            return cachedBalances
        }
        val fetched = withTimeoutOrNull(8_000L) {
            runCatching { exchange.fetchBalances() }
        } ?: Result.failure(IllegalStateException("exchange balances timeout"))
        cachedBalances = fetched.getOrElse { cachedBalances }
        balancesFetchedAt = now
        return cachedBalances
    }

    private suspend fun refreshOpenOrders(now: Instant): List<com.kibot.shared.models.OrderSnapshot> {
        if (!shouldRefresh(now, openOrdersFetchedAt, config.openOrdersRefreshIntervalMillis, force = openOrdersFetchedAt == null)) {
            return cachedOpenOrders
        }
        val fetched = withTimeoutOrNull(8_000L) {
            runCatching { exchange.fetchOpenOrders() }
        } ?: Result.failure(IllegalStateException("exchange open orders timeout"))
        cachedOpenOrders = fetched.getOrElse { cachedOpenOrders }
        openOrdersFetchedAt = now
        return cachedOpenOrders
    }

    private suspend fun refreshRecentOrders(now: Instant): List<com.kibot.shared.models.OrderSnapshot> {
        if (!shouldRefresh(now, recentOrdersFetchedAt, config.recentOrdersRefreshIntervalMillis, force = recentOrdersFetchedAt == null)) {
            return cachedRecentOrders
        }
        val fetchedOrders = withTimeoutOrNull(4_000L) {
            runCatching {
                controlPlane.fetchRecentOrders(config.controlPlane.botId, limit = 200)
            }.getOrElse { cachedRecentOrders }
        } ?: cachedRecentOrders
        if (!config.localPositionStateEnabled) {
            cachedRecentOrders = fetchedOrders
            recentOrdersFetchedAt = now
            return cachedRecentOrders
        }
        // ALWAYS merge local orders to preserve entry price history for holdings display
        val localOrders = loadLocalPositionState().orders
        cachedRecentOrders = if (fetchedOrders.isEmpty() && localOrders.isNotEmpty()) {
            if (!localRecoveryFallbackAnnounced) {
                localRecoveryFallbackAnnounced = true
                val message = "Recovery audit: remote order snapshot kosong, fallback ke local position state."
                repository.noteStatus(message)
                appendAuditLog(
                    level = LogLevel.INFO,
                    category = "LOCAL_RECOVERY",
                    message = message,
                )
            }
            localOrders
        } else {
            // Always merge local orders even if API returned some orders
            // This ensures we have full entry price history for all held positions
            mergeRecentOrders(
                base = fetchedOrders,
                updates = localOrders,
            )
        }
        recentOrdersFetchedAt = now
        return cachedRecentOrders
    }

    private suspend fun refreshRecentFills(
        now: Instant,
        pairIds: List<com.kibot.shared.models.PairId>,
    ): List<com.kibot.shared.models.FillSnapshot> {
        val pairKey = pairIds.joinToString("|") { it.value }
        val shouldRefresh = shouldRefresh(
            now = now,
            lastFetchedAt = recentFillsFetchedAt,
            intervalMillis = config.recentFillsRefreshIntervalMillis,
            force = recentFillsFetchedAt == null || pairKey != cachedRecentFillsKey,
        )
        if (!shouldRefresh) return cachedRecentFills
        cachedRecentFills = pairIds.flatMap { pairId ->
            withTimeoutOrNull(8_000L) {
                runCatching { exchange.fetchRecentFills(pairId, limit = 12) }.getOrDefault(emptyList())
            } ?: emptyList()
        }
        recentFillsFetchedAt = now
        cachedRecentFillsKey = pairKey
        return cachedRecentFills
    }

    private suspend fun publishAnalysisIfNeeded(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
    ) {
        if (!config.supabaseNonCriticalWriteEnabled) return
        val candidateSignature = cycle.deploymentPlan.candidates.joinToString("|") { "${it.pairId.value}:${"%.2f".format(it.rankingScore)}" }
        val shouldPublishAnalysis = lastAnalysisPublishedAt == null ||
            (now - lastAnalysisPublishedAt!!).inWholeMilliseconds >= config.analysisPublishIntervalMillis ||
            candidateSignature != lastCandidateSignature
        val shouldPublishMetrics = lastStrategyMetricsPublishedAt == null ||
            (now - lastStrategyMetricsPublishedAt!!).inWholeMilliseconds >= config.strategyMetricsPublishIntervalMillis ||
            candidateSignature != lastCandidateSignature

        if (shouldPublishAnalysis) {
            controlPlane.publishRuntimeIntelligence(
                RuntimeIntelligenceUpdate(
                    botId = config.controlPlane.botId,
                    deviceId = config.device.deviceId,
                    term = lease.term,
                    currentPair = cycle.selectedSignal?.pairId,
                    operatingMode = cycle.modeSnapshot.mode,
                    edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                    aggressionScore = cycle.modeSnapshot.aggressionScore,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    profitProtectionStatus = cycle.modeSnapshot.profitProtectionStatus,
                    marketRegime = cycle.marketSnapshot.regime,
                    distrustLabels = cycle.distrustLabels,
                    activeCandidatePairs = cycle.deploymentPlan.candidates.map { it.pairId },
                    marketOpportunityScore = cycle.marketSnapshot.marketOpportunityScore,
                    botHealthScore = cycle.marketSnapshot.botHealthScore,
                    performanceMomentumScore = cycle.marketSnapshot.performanceMomentumScore,
                    safeModeReason = if (cycle.modeSnapshot.mode.name == "SAFE") cycle.summary.firstOrNull() else null,
                ),
            )
            lastAnalysisPublishedAt = now
            lastCandidateSignature = candidateSignature
        }

        if (shouldPublishMetrics) {
            controlPlane.appendStrategyMetrics(
                botId = config.controlPlane.botId,
                metrics = cycle.rankedPairs.take(5),
            )
            lastStrategyMetricsPublishedAt = now
        }
    }

    private suspend fun maybeManageLiveTrading(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        health: EngineHealthSnapshot,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        aiSoftAuditOnly: Boolean = false,
    ) {
        if (!config.enableLiveExecution) return
        val isExecutionOwner = config.exchangeKind == ExchangeKind.INDODAX &&
            config.controlPlane.botId.value.equals("main", ignoreCase = true)
        if (!isExecutionOwner) return
        refreshProtectiveState(now)
        refreshIndodaxFocusUniverse(now)
        refreshAListTunnelPairs(marketQuotes)
        seedDynamicVipFromPanopticon(now = now, marketQuotes = marketQuotes)
        updateDustQuarantine(balances = balances, marketQuotes = marketQuotes)
        updateHyperAggressivePulseSnapshots(now = now, marketQuotes = marketQuotes)
        val hyperAggressiveTracker = evaluateHyperAggressiveTracker(now = now, dailyRisk = cycle.dailyRisk)
        val rawHyperTargets = if (hyperAggressiveTracker.hungry) {
            detectHyperAggressiveTargets(marketQuotes = marketQuotes, now = now)
        } else {
            emptyList()
        }
        val hyperTargets = filterHyperTargetsByEnvironmentGuardrail(
            targets = rawHyperTargets,
            marketQuotes = marketQuotes,
            cycle = cycle,
        )
        val sexyMomentumTargets = hyperTargets.map { it.pairId }.distinct()
        val superSexyTarget = hyperTargets.firstOrNull { it.kind == HyperTargetKind.SUPER_SEXY }?.pairId
        lastSuperSexyTarget = superSexyTarget
        if (hyperAggressiveTracker.hungry) {
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "HOURLY_TARGET_TRACKER",
                message = "HUNGRY mode aktif: ${formatDecimal(hyperAggressiveTracker.hourlyPnlPct, 2)}%/jam < target ${formatDecimal(hyperAggressiveTracker.targetHourlyPct, 2)}%/jam.",
            )
        }
        val adaptiveCoordinator = buildAdaptiveTradeAutomationCoordinator(cycle)
        val capitalAwareness = deriveCapitalAwareness(cycle = cycle, balances = balances)
        if (capitalAwareness.lowCapital) {
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "CAPITAL_AWARE",
                message = capitalAwareness.note,
            )
        }
        val entryStabilizedOrders = manageStaleEntryOrders(
            now = now,
            lease = lease,
            cycle = cycle,
            marketQuotes = marketQuotes,
            recentOrders = recentOrders,
        )
        cachedRecentOrders = entryStabilizedOrders
        val preExitManagedPositions = adaptiveCoordinator.deriveManagedPositions(
            balances = balances,
            marketQuotes = marketQuotes,
            reconciledOrders = entryStabilizedOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )
        val stabilizedOrders = manageStaleExitOrders(
            now = now,
            lease = lease,
            managedPositions = preExitManagedPositions,
            marketQuotes = marketQuotes,
            recentOrders = entryStabilizedOrders,
        )
        cachedRecentOrders = stabilizedOrders
        val activePersistedOrders = stabilizedOrders.filter { it.status in activeOrderStatuses }
        maybeRunAutonomousResolver(
            now = now,
            lease = lease,
            activePersistedOrders = activePersistedOrders,
        )
        val managedPositions = adaptiveCoordinator.deriveManagedPositions(
            balances = balances,
            marketQuotes = marketQuotes,
            reconciledOrders = stabilizedOrders,
            rankedPairs = cycle.rankedPairs,
            now = now,
        )
        persistLocalPositionState(
            now = now,
            recentOrders = stabilizedOrders,
            managedPositions = managedPositions,
        )
        maybeBroadcastActivePositions(
            now = now,
            balances = balances,
            managedPositions = managedPositions,
            marketQuotes = marketQuotes,
        )
        val localTrailingSnapshots = captureLocalTrailingSnapshots(
            managedPositions = managedPositions,
            balances = balances,
            marketQuotes = marketQuotes,
            recentOrders = stabilizedOrders,
            activeOrders = activePersistedOrders,
        )
        val hyperAggressiveTrailingExit = planHyperAggressiveTrailingExit(
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
            hungry = hyperAggressiveTracker.hungry,
            marketQuotes = marketQuotes,
        )
        val localAutonomyTrailingExit = planLocalAutonomyTrailingExit(
            snapshots = localTrailingSnapshots,
            managedPositions = managedPositions,
            balances = balances,
            cycle = cycle,
        )
        val hyperAggressiveRotationExit = planHyperAggressiveRotationExit(
            now = now,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
            hungry = hyperAggressiveTracker.hungry,
            sexyTargets = sexyMomentumTargets,
            superSexyTarget = superSexyTarget,
        )
        val leadLagTrailingExit = planLeadLagTrailingExit(
            now = now,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
        )
        val forcedSellExit = planForcedSellByTrinityConfirm(
            now = now,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
        )
        val leadLagPriorityPair = activeLeadLagPriorityPair(now)
        val opportunityCostExit = planOpportunityCostLiquidation(
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            balances = balances,
            cycle = cycle,
            leadLagPriorityPair = leadLagPriorityPair,
            superSexyTarget = superSexyTarget,
        )
        val emergencyGarbageExit = planEmergencyGarbageLiquidation(
            balances = balances,
            marketQuotes = marketQuotes,
            activeOrders = activePersistedOrders,
            cycle = cycle,
        )
        val emergencyLiquidityExit = planEmergencyLiquidityRebalanceExit(
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            balances = balances,
            cycle = cycle,
        )
        val crashHardStopExit = planCrashHardStopExit(
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
            marketQuotes = marketQuotes,
        )
        val hardTimeoutExit = planHardTimeoutExit(
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
            now = now,
        )
        val cleanupRotationExit = planPreRotationCleanupExit(
            now = now,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
            cycle = cycle,
            hungry = hyperAggressiveTracker.hungry,
            marketQuotes = marketQuotes,
        )
        val exitDecision = emergencyGarbageExit ?: hardTimeoutExit ?: opportunityCostExit ?: emergencyLiquidityExit ?: crashHardStopExit ?: localAutonomyTrailingExit ?: forcedSellExit ?: hyperAggressiveTrailingExit ?: hyperAggressiveRotationExit ?: leadLagTrailingExit ?: adaptiveCoordinator.planExit(
            now = now,
            cycle = cycle,
            managedPositions = managedPositions,
            activeOrders = activePersistedOrders,
        ) ?: cleanupRotationExit
        val filteredExitDecision = exitDecision?.takeUnless { decision ->
            val pairKey = decision.executionPlan.signal.pairId.value.lowercase()
            if (!dustQuarantinePairs.contains(pairKey) && pairKey !in garbageNukePairs) return@takeUnless false
            val bid = marketQuotes
                .firstOrNull { it.pairId == decision.executionPlan.signal.pairId }
                ?.bestBid
                ?.toDoubleOrZero()
                ?.takeIf { it > 0.0 }
                ?: marketQuotes
                    .firstOrNull { it.pairId == decision.executionPlan.signal.pairId }
                    ?.midPrice
                    ?.toDoubleOrZero()
                ?.takeIf { it > 0.0 }
            ?: 0.0
            val notional = decision.executionPlan.quantity.toDoubleOrZero() * bid
            notional < minimumLiveNotionalForExchange() && pairKey !in garbageNukePairs
        }
        if (filteredExitDecision != null) {
            logger.info(
                "SELL_DECISION_REASON pair={} reason={} orderType={}",
                filteredExitDecision.executionPlan.signal.pairId.value.lowercase(),
                filteredExitDecision.message,
                filteredExitDecision.executionPlan.orderType.name,
            )
            var smartRoutedExitPlan = routeExitPlanBySmartSell(
                executionPlan = filteredExitDecision.executionPlan,
                exitReasonMessage = filteredExitDecision.message,
                marketQuotes = marketQuotes,
            )
            smartRoutedExitPlan = routeByDepthGuard(
                executionPlan = smartRoutedExitPlan,
                marketQuotes = marketQuotes,
            )
            val preparedActiveOrders = prepareExitPath(
                now = now,
                lease = lease,
                recentOrders = stabilizedOrders,
                activePersistedOrders = activePersistedOrders,
                exitDecision = filteredExitDecision.copy(executionPlan = smartRoutedExitPlan),
            )
            val result = liveExecutionCoordinator.submitExit(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                term = lease.term,
                executionPlan = smartRoutedExitPlan,
                existingPersistedOrders = preparedActiveOrders,
                exchange = exchange,
                controlPlane = controlPlane,
            )
            result.order?.let {
                cachedRecentOrders = mergeRecentOrders(stabilizedOrders, listOf(it))
                recentOrdersFetchedAt = now
                val forcedKey = filteredExitDecision.position.pairId.value.lowercase()
                forcedSellTraceByPair.remove(forcedKey)
            }
            if (result.submitted) {
                logger.info(
                    "[EXECUTION_SELL] pair={} reason={} detail={}",
                    filteredExitDecision.executionPlan.signal.pairId.value,
                    filteredExitDecision.message,
                    result.message,
                )
            }
            var continueToEntryAfterExit = false
            if (result.submitted) {
                val pairKey = filteredExitDecision.executionPlan.signal.pairId.value.lowercase()
                val exitAt = now
                val requestedSellQty = filteredExitDecision.executionPlan.quantity.toDoubleOrZero()
                val positionQty = filteredExitDecision.position.quantity.toDoubleOrZero().coerceAtLeast(0.0000001)
                val isPartialExit = requestedSellQty in 0.0..(positionQty * 0.98)
                if (filteredExitDecision.message.startsWith("CRASH_GUARD", ignoreCase = true)) {
                    markCrashGuardTriggered(now, filteredExitDecision.executionPlan.signal.pairId)
                }
                if (isPartialExit) {
                    partialTakeProfitExecutedByPair[pairKey] = true
                }
                val coinClass = classifyPair(filteredExitDecision.executionPlan.signal.pairId)
                updateLeadLagStats(coinClass) { it.copy(exits = it.exits + 1) }
                val entryAt = leadLagEntrySubmittedAtByPair[pairKey]
                val traceId = leadLagTraceByPair[pairKey]
                val detectedAtMs = leadLagDetectedAtByPair[pairKey]
                val sentAtMs = leadLagOriginSentAtByPair[pairKey]
                val receivedAt = leadLagReceivedAtByPair[pairKey]
                val sellPrice = result.order?.price?.toDoubleOrZero()
                val sellQty = result.order?.executedQuantity?.toDoubleOrZero()
                    ?.takeIf { it > 0.0 }
                    ?: result.order?.originalQuantity?.toDoubleOrZero()?.takeIf { it > 0.0 }
                val buyPrice = cachedRecentOrders
                    .asSequence()
                    .filter { it.pairId == filteredExitDecision.executionPlan.signal.pairId }
                    .filter { it.side == com.kibot.shared.models.OrderSide.BUY }
                    .filter { it.status == com.kibot.shared.models.OrderStatus.FILLED || it.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED }
                    .maxByOrNull { it.updatedAt.toEpochMilliseconds() }
                    ?.price
                    ?.toDoubleOrZero()
                val pnlIdr = if (buyPrice != null && sellPrice != null && sellQty != null && sellQty > 0.0) {
                    (sellPrice - buyPrice) * sellQty
                } else {
                    null
                }
                if (entryAt != null || sentAtMs != null || receivedAt != null) {
                    val holdMs = entryAt?.let { (exitAt - it).inWholeMilliseconds }?.coerceAtLeast(0L)
                    val receiveToExitMs = receivedAt?.let { (exitAt - it).inWholeMilliseconds }?.coerceAtLeast(0L)
                    val endToEndMs = sentAtMs?.let { (exitAt.toEpochMilliseconds() - it).coerceAtLeast(0L) }
                    appendAuditLog(
                        level = LogLevel.INFO,
                        category = "LEAD_LAG",
                        message = "Telemetry exit ${filteredExitDecision.executionPlan.signal.pairId.value}: hold=${holdMs ?: -1}ms receive->exit=${receiveToExitMs ?: -1}ms end2end=${endToEndMs ?: -1}ms.",
                    )
                    if (traceId != null) {
                        emitLeadLagTelemetry(
                            LeadLagTelemetryEvent(
                                event = "T4_SELL_SUBMITTED",
                                traceId = traceId,
                                pairId = filteredExitDecision.executionPlan.signal.pairId.value,
                                coinClass = coinClass.name.lowercase(),
                                sourceBotId = config.controlPlane.botId.value,
                                targetBotId = null,
                                t0DetectedAtEpochMs = detectedAtMs,
                                t1UdpSentAtEpochMs = sentAtMs,
                                t2UdpReceivedAtEpochMs = receivedAt?.toEpochMilliseconds(),
                                t3BuySubmittedAtEpochMs = entryAt?.toEpochMilliseconds(),
                                t4SellSubmittedAtEpochMs = exitAt.toEpochMilliseconds(),
                                buyPrice = buyPrice,
                                sellPrice = sellPrice,
                                quantity = sellQty,
                                pnlIdr = pnlIdr,
                                endToEndToSellLatencyMs = endToEndMs,
                                note = "KiDax submit sell.",
                            ),
                        )
                    }
                    when (hyperAggressiveEntryReasonByPair[pairKey]) {
                        HyperTargetKind.V_SHAPE_BOUNCE -> appendAuditLog(
                            level = LogLevel.INFO,
                            category = "V_SHAPE_BOUNCE_SUCCESS",
                            message = "V_SHAPE_BOUNCE_SUCCESS ${filteredExitDecision.executionPlan.signal.pairId.value} berhasil tangkap rebound.",
                        )
                        HyperTargetKind.WALL_SMASH -> appendAuditLog(
                            level = LogLevel.INFO,
                            category = "WALL_SMASH_SUCCESS",
                            message = "WALL_SMASH_SUCCESS ${filteredExitDecision.executionPlan.signal.pairId.value} berhasil tembus resistance.",
                        )
                        else -> Unit
                    }
                    emitLeadLagExecutionReport(
                    pairId = filteredExitDecision.executionPlan.signal.pairId,
                    status = "SUCCESS",
                        t0DetectedAtMs = detectedAtMs,
                        t1ReceivedAtMs = receivedAt?.toEpochMilliseconds(),
                        t2BuyAtMs = entryAt?.toEpochMilliseconds(),
                        t3SellAtMs = exitAt.toEpochMilliseconds(),
                        slippagePct = null,
                        finalPnlIdr = pnlIdr,
                    )
                }
                val exitedHyperPair = hyperAggressiveEntryReasonByPair[pairKey] != null
                val allowFastReEntry =
                    filteredExitDecision.position.unrealizedPnlPct > 0.0 ||
                        filteredExitDecision.reason == com.kibot.core.ExitReason.PROFIT_EXIT ||
                        filteredExitDecision.reason == com.kibot.core.ExitReason.PROFIT_PROTECTION_EXIT
                continueToEntryAfterExit = when {
                    filteredExitDecision.message.startsWith("HyperAggressive rotation:", ignoreCase = true) ->
                        allowFastReEntry
                    filteredExitDecision.message.startsWith("HyperAggressive ALL_IN liquidation:", ignoreCase = true) ->
                        allowFastReEntry
                    exitedHyperPair ->
                        allowFastReEntry
                    else -> false
                }
                if (!isPartialExit) {
                    leadLagEntrySubmittedAtByPair.remove(pairKey)
                    leadLagReceivedAtByPair.remove(pairKey)
                    leadLagOriginSentAtByPair.remove(pairKey)
                    leadLagTraceByPair.remove(pairKey)
                    leadLagDetectedAtByPair.remove(pairKey)
                    hyperAggressiveTrackedEntryAtByPair.remove(pairKey)
                    hyperAggressivePeakBidByPair.remove(pairKey)
                    hyperAggressiveEntryReasonByPair.remove(pairKey)
                    partialTakeProfitExecutedByPair.remove(pairKey)
                }
                if (filteredExitDecision.reason == com.kibot.core.ExitReason.STOP_LOSS_EXIT) {
                    recordStopLossToxicEvent(
                        now = now,
                        pairId = filteredExitDecision.executionPlan.signal.pairId,
                        marketQuotes = marketQuotes,
                    )
                }
            }
            if (!continueToEntryAfterExit) return
        }
        val directHyperEntrySubmitted = maybeSubmitDirectHyperAggressiveEntry(
            now = now,
            lease = lease,
            cycle = cycle,
            balances = balances,
            marketQuotes = marketQuotes,
            activePersistedOrders = activePersistedOrders,
            managedPositions = managedPositions,
            hungry = hyperAggressiveTracker.hungry,
            hyperTargets = hyperTargets,
        )
        if (directHyperEntrySubmitted) return

        val syntheticHyperEntry = if (hyperAggressiveTracker.hungry && hyperTargets.isNotEmpty()) {
            buildHyperAggressiveSyntheticEntryPlan(
                cycle = cycle,
                balances = balances,
                marketQuotes = marketQuotes,
                target = hyperTargets.first(),
            )
        } else {
            null
        }
        val candidateExecutionPlans = (listOfNotNull(syntheticHyperEntry) + cycle.entryExecutionPlans)
            .ifEmpty { listOfNotNull(cycle.executionPlan) }
        if (candidateExecutionPlans.isEmpty()) {
            if (config.exchangeKind == ExchangeKind.INDODAX) {
                val dynamicVipSubmitted = maybeSubmitDynamicVipEntry(
                    now = now,
                    lease = lease,
                    cycle = cycle,
                    balances = balances,
                    marketQuotes = marketQuotes,
                    activePersistedOrders = activePersistedOrders,
                    managedPositions = managedPositions,
                )
                if (dynamicVipSubmitted) return
                val scalpingSubmitted = maybeSubmitLightScalpingEntry(
                    now = now,
                    lease = lease,
                    cycle = cycle,
                    balances = balances,
                    marketQuotes = marketQuotes,
                    activePersistedOrders = activePersistedOrders,
                    managedPositions = managedPositions,
                )
                if (scalpingSubmitted) logger.info("[EXECUTION_BUY] pair=baseline reason=fallback_without_anomaly")
            }
            logWhyNotBuy(now, "entry", "no_chart_history_candidate")
            return
        }
        if (trinityHeartbeatSafeModeReason != null) {
            val safeModeOverrideEligible = leadLagPriorityPair != null &&
                candidateExecutionPlans.any { it.signal.pairId == leadLagPriorityPair }
            if (!safeModeOverrideEligible) {
                logWhyNotBuy(now, "entry", "trinity_heartbeat_safe_mode")
                return
            }
            appendThrottledAuditLog(
                now = now,
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = "Trinity safe mode softened for lead-lag candidate ${leadLagPriorityPair?.value ?: "unknown"}. AI cooldown diperlakukan sebagai soft audit.",
            )
        }
        val hyperAggressivePriorityPair = superSexyTarget ?: sexyMomentumTargets.firstOrNull()
        val prioritizedExecutionPlans = if (leadLagPriorityPair != null || hyperAggressivePriorityPair != null) {
            candidateExecutionPlans.sortedByDescending {
                val leadLagScore = if (leadLagPriorityPair != null && it.signal.pairId == leadLagPriorityPair) 2 else 0
                val hyperScore = if (hyperAggressivePriorityPair != null && it.signal.pairId == hyperAggressivePriorityPair) 1 else 0
                leadLagScore + hyperScore
            }
        } else {
            candidateExecutionPlans
        }.let { prioritized ->
            prioritizeExecutionPlansByChartAndCapital(
                executionPlans = prioritized,
                balances = balances,
                marketQuotes = marketQuotes,
            )
        }
        val entryManagedPositions = managedPositions
        val activeBuyOrders = activePersistedOrders.filter { it.side == com.kibot.shared.models.OrderSide.BUY }
        val availableEntrySlots = (
            cycle.deploymentPlan.maxActivePositions -
                entryManagedPositions.size -
                activeBuyOrders.size
            ).coerceAtLeast(0)
        val batchLimit = determineEntryBatchLimit(
            cycle = cycle,
            availableEntrySlots = availableEntrySlots,
            candidateExecutionPlans = prioritizedExecutionPlans,
        )
        if (batchLimit <= 0) {
            logWhyNotBuy(now, "baseline", "slot_or_pending_buy_full")
            return
        }
        val effectiveBatchLimit = batchLimit

        var workingOrders = activePersistedOrders
        var submittedCount = 0
        var lastBlockedReason: String? = null
        prioritizedExecutionPlans.forEach { candidatePlan ->
            if (submittedCount >= effectiveBatchLimit) return@forEach
            entryBlockedByProtectiveBrake(now, candidatePlan.signal.pairId)?.let { blockedReason ->
                lastBlockedReason = blockedReason
                return@forEach
            }
            if (workingOrders.any { it.pairId == candidatePlan.signal.pairId }) {
                lastBlockedReason = "Entry ${candidatePlan.signal.pairId.value} ditunda karena pair yang sama masih punya order aktif."
                return@forEach
            }

            entryBlockedByPortfolioState(
                cycle = cycle,
                executionPlan = candidatePlan,
                managedPositions = managedPositions,
                activeOrders = workingOrders,
                leadLagPriorityPair = leadLagPriorityPair,
            )?.let { blockedReason ->
                lastBlockedReason = blockedReason
                return@forEach
            }

            val routedEntry = routeEntryPlanByLatency(
                executionPlan = candidatePlan,
                health = health,
                marketQuotes = marketQuotes,
            )
            routedEntry.blockedReason?.let { blockedReason ->
                lastBlockedReason = blockedReason
                return@forEach
            }
            routedEntry.message?.let { note ->
                appendThrottledAuditLog(
                    now = now,
                    level = LogLevel.INFO,
                    category = "ENTRY_POLICY",
                    message = note,
                )
            }
            var effectiveExecutionPlan = routedEntry.executionPlan ?: return@forEach
            effectiveExecutionPlan = adaptExecutionPlanByCapital(
                executionPlan = effectiveExecutionPlan,
                totalEquityIdr = capitalAwareness.totalEquityIdr,
            )
            effectiveExecutionPlan = normalizeExecutionPlanForVenue(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            ) ?: run {
                lastBlockedReason = "Entry ${candidatePlan.signal.pairId.value} ditunda karena gagal memenuhi minimum order venue."
                return@forEach
            }
            val antiKoinMahalBlocked = entryBlockedByAntiKoinMahal(
                executionPlan = effectiveExecutionPlan,
                balances = balances,
                marketQuotes = marketQuotes,
            )
            if (antiKoinMahalBlocked != null) {
                lastBlockedReason = antiKoinMahalBlocked
                return@forEach
            }
            val capitalMismatchBlocked = entryBlockedByCapitalMismatch(
                executionPlan = effectiveExecutionPlan,
                balances = balances,
                marketQuotes = marketQuotes,
            )
            if (capitalMismatchBlocked != null) {
                lastBlockedReason = capitalMismatchBlocked
                return@forEach
            }
            val blueChipBlocked = entryBlockedByBlueChipVolume(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            )
            if (blueChipBlocked != null) {
                lastBlockedReason = blueChipBlocked
                return@forEach
            }
            val shortFlatChartBlocked = entryBlockedByShortFlatChart(
                executionPlan = effectiveExecutionPlan,
            )
            if (shortFlatChartBlocked != null) {
                lastBlockedReason = shortFlatChartBlocked
                return@forEach
            }
            if (superSexyTarget != null && effectiveExecutionPlan.signal.pairId == superSexyTarget) {
                effectiveExecutionPlan = amplifyToAllInBudget(
                    executionPlan = effectiveExecutionPlan,
                    balances = balances,
                    marketQuotes = marketQuotes,
                )
                effectiveExecutionPlan = effectiveExecutionPlan.copy(
                    orderType = com.kibot.shared.models.OrderType.MARKET,
                    limitPrice = null,
                    postOnlyPreferred = false,
                )
            } else if (leadLagPriorityPair != null &&
                effectiveExecutionPlan.signal.pairId == leadLagPriorityPair &&
                config.leadLagForceRotationOnReceive
            ) {
                effectiveExecutionPlan = amplifyToAllInBudget(
                    executionPlan = effectiveExecutionPlan,
                    balances = balances,
                    marketQuotes = marketQuotes,
                )
                val activeCallout = activeLeadLagCallout
                val prewarmed = isUdpExecutionPrewarmActive(effectiveExecutionPlan.signal.pairId, now)
                if (activeCallout != null &&
                    activeCallout.pairId == effectiveExecutionPlan.signal.pairId &&
                    activeCallout.trend.equals("GRADUAL_UP", ignoreCase = true)
                ) {
                    val quote = marketQuotes.firstOrNull { it.pairId == effectiveExecutionPlan.signal.pairId }
                    val mid = quote?.midPrice?.toDoubleOrZero()?.takeIf { it > 0.0 }
                    if (mid != null) {
                        effectiveExecutionPlan = effectiveExecutionPlan.copy(
                            orderType = com.kibot.shared.models.OrderType.LIMIT,
                            limitPrice = DecimalValue.fromDouble(mid),
                            postOnlyPreferred = false,
                        )
                    }
                } else if (activeCallout != null && activeCallout.pairId == effectiveExecutionPlan.signal.pairId) {
                    effectiveExecutionPlan = effectiveExecutionPlan.copy(
                        orderType = com.kibot.shared.models.OrderType.MARKET,
                        limitPrice = null,
                        postOnlyPreferred = false,
                    )
                }
                if (prewarmed) {
                    effectiveExecutionPlan = effectiveExecutionPlan.copy(
                        orderType = com.kibot.shared.models.OrderType.MARKET,
                        limitPrice = null,
                        postOnlyPreferred = false,
                    )
                }
                if (activeCallout != null &&
                    activeCallout.pairId == effectiveExecutionPlan.signal.pairId &&
                    activeCallout.shortTermReturnPct >= leadLagFomoThresholdPct
                ) {
                    val quote = marketQuotes.firstOrNull { it.pairId == effectiveExecutionPlan.signal.pairId }
                    val bestAsk = quote?.bestAsk?.toDoubleOrZero()?.takeIf { it > 0.0 }
                    if (bestAsk != null) {
                        val correctionLimit = bestAsk * (1.0 - leadLagFomoCorrectionEntryPct / 100.0)
                        effectiveExecutionPlan = effectiveExecutionPlan.copy(
                            orderType = com.kibot.shared.models.OrderType.LIMIT,
                            limitPrice = DecimalValue.fromDouble(correctionLimit),
                            postOnlyPreferred = false,
                        )
                    }
                }
            }
            val slippageGuardFailure = evaluateLeadLagSlippageGuard(
                now = now,
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            )
            if (slippageGuardFailure != null) {
                lastBlockedReason = slippageGuardFailure
                return@forEach
            }
            effectiveExecutionPlan = enforceMaxSpreadCapForMarketBuy(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            ) ?: run {
                lastBlockedReason = "spread_cap_enforcement_failed"
                return@forEach
            }
            effectiveExecutionPlan = routeByChartAnalyzer(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            ) ?: run {
                lastBlockedReason = "chart_analyzer_vetoed_entry"
                return@forEach
            }
            effectiveExecutionPlan = routeByAntiSpoofRadar(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
                now = now,
            ) ?: run {
                lastBlockedReason = "anti_spoof_radar_blocked_entry"
                return@forEach
            }
            effectiveExecutionPlan = routeByDepthGuard(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            )
            effectiveExecutionPlan = applyAdaptiveOrderSlicing(
                executionPlan = effectiveExecutionPlan,
                marketQuotes = marketQuotes,
            )

            val submissionLease = ensureLeaseLockdownOwnership(now, lease) ?: lease
            if (!submissionLease.isHeldBy(config.device.deviceId, now)) {
                lastBlockedReason = "Lease belum dipegang ${config.device.deviceId.value}; menunggu sinkron holder aktif."
                return@forEach
            }
            val result = liveExecutionCoordinator.submitEntry(
                botId = config.controlPlane.botId,
                deviceId = config.device.deviceId,
                term = submissionLease.term,
                executionPlan = effectiveExecutionPlan,
                existingPersistedOrders = workingOrders,
                exchange = exchange,
                controlPlane = controlPlane,
            )
            result.order?.let {
                workingOrders = mergeRecentOrders(workingOrders, listOf(it)).filter { snapshot ->
                    snapshot.status in activeOrderStatuses
                }
                cachedRecentOrders = mergeRecentOrders(cachedRecentOrders, listOf(it))
                recentOrdersFetchedAt = now
            }

            if (result.submitted) {
                logger.info(
                    "[EXECUTION_BUY] pair={} reason=entry detail={}",
                    effectiveExecutionPlan.signal.pairId.value,
                    result.message,
                )
            }

            if (result.submitted) {
                submittedCount += 1
                val pairKey = effectiveExecutionPlan.signal.pairId.value.lowercase()
                udpExecutionPrewarmByPair.remove(pairKey)
                partialTakeProfitExecutedByPair[pairKey] = false
                val entryAt = now
                if (hyperAggressiveTracker.hungry) {
                    hyperAggressiveTrackedEntryAtByPair[pairKey] = entryAt
                    hyperAggressivePeakBidByPair[pairKey] = marketQuotes.firstOrNull { it.pairId == effectiveExecutionPlan.signal.pairId }?.bestBid?.toDoubleOrZero()
                        ?: result.order?.price?.toDoubleOrZero()
                        ?: 0.0
                    val reason = hyperTargets.firstOrNull { it.pairId == effectiveExecutionPlan.signal.pairId }?.kind ?: HyperTargetKind.SEXY
                    hyperAggressiveEntryReasonByPair[pairKey] = reason
                    appendAuditLog(
                        level = LogLevel.INFO,
                        category = "BUY_MOMENTUM",
                        message = if (hyperAggressivePriorityPair != null && effectiveExecutionPlan.signal.pairId == hyperAggressivePriorityPair) {
                            "BUY_MOMENTUM ${effectiveExecutionPlan.signal.pairId.value} karena HUNGRY mode dan sexy momentum aktif."
                        } else {
                            "BUY_MOMENTUM ${effectiveExecutionPlan.signal.pairId.value} karena HUNGRY mode mencari peluang mandiri."
                        },
                    )
                }
                val coinClass = classifyPair(effectiveExecutionPlan.signal.pairId)
                updateLeadLagStats(coinClass) { it.copy(entries = it.entries + 1) }
                val traceId = leadLagTraceByPair[pairKey]
                val detectedAtMs = leadLagDetectedAtByPair[pairKey]
                val receivedAt = leadLagReceivedAtByPair[pairKey]
                val sentAtMs = leadLagOriginSentAtByPair[pairKey]
                if (receivedAt != null || sentAtMs != null) {
                    leadLagEntrySubmittedAtByPair[pairKey] = entryAt
                    val receiveToEntryMs = receivedAt?.let { (entryAt - it).inWholeMilliseconds }?.coerceAtLeast(0L)
                    val endToEndMs = sentAtMs?.let { (entryAt.toEpochMilliseconds() - it).coerceAtLeast(0L) }
                    appendAuditLog(
                        level = LogLevel.INFO,
                        category = "LEAD_LAG",
                        message = "Telemetry entry ${effectiveExecutionPlan.signal.pairId.value}: receive->entry=${receiveToEntryMs ?: -1}ms end2end=${endToEndMs ?: -1}ms.",
                    )
                    if (traceId != null) {
                        emitLeadLagTelemetry(
                            LeadLagTelemetryEvent(
                                event = "T3_BUY_SUBMITTED",
                                traceId = traceId,
                                pairId = effectiveExecutionPlan.signal.pairId.value,
                                coinClass = coinClass.name.lowercase(),
                                sourceBotId = config.controlPlane.botId.value,
                                targetBotId = null,
                                t0DetectedAtEpochMs = detectedAtMs,
                                t1UdpSentAtEpochMs = sentAtMs,
                                t2UdpReceivedAtEpochMs = receivedAt?.toEpochMilliseconds(),
                                t3BuySubmittedAtEpochMs = entryAt.toEpochMilliseconds(),
                                buyPrice = result.order?.price?.toDoubleOrZero(),
                                quantity = result.order?.executedQuantity?.toDoubleOrZero()
                                    ?.takeIf { it > 0.0 }
                                    ?: result.order?.originalQuantity?.toDoubleOrZero()?.takeIf { it > 0.0 },
                                receiveToBuyLatencyMs = receiveToEntryMs,
                                endToEndToBuyLatencyMs = endToEndMs,
                                note = "KiDax submit buy.",
                            ),
                        )
                    }
                    if ((endToEndMs ?: 0L) >= leadLagAlarmEndToEndLatencyMs) {
                        val shouldAlert = lastLeadLagAlarmAt == null ||
                            (entryAt - (lastLeadLagAlarmAt ?: entryAt)).inWholeMilliseconds >= leadLagAlarmCooldownMillis
                        if (shouldAlert) {
                            lastLeadLagAlarmAt = entryAt
                            appendAuditLog(
                                level = LogLevel.WARN,
                                category = "LEAD_LAG",
                                message = "Alarm end-to-end lead-lag tinggi: ${endToEndMs}ms untuk ${effectiveExecutionPlan.signal.pairId.value}.",
                            )
                        }
                    }
                }
                logger.info(
                    "[EXECUTION_BUY] pair={} mode=AGRESIF_CUAN clientOrderId={}",
                    effectiveExecutionPlan.signal.pairId.value,
                    result.clientOrderId?.value ?: "-",
                )
                lastAnalysisPublishedAt = now
            } else if (result.failSafeTriggered) {
                controlPlane.markConflictSafeMode(
                    botId = config.controlPlane.botId,
                    reason = result.message.ifBlank { "Submit order ambigu; engine masuk safe mode." },
                )
                logger.error("Live order submit became ambiguous, safe mode triggered.")
                return
            } else {
                lastBlockedReason = result.message
            }
        }

        if (submittedCount == 0 && !lastBlockedReason.isNullOrBlank()) {
            logWhyNotBuy(now, "entry", lastBlockedReason.orEmpty().replace('\n', ' '))
        }
        if (submittedCount == 0 && config.exchangeKind == ExchangeKind.INDODAX) {
            val scalpingSubmitted = maybeSubmitLightScalpingEntry(
                now = now,
                lease = lease,
                cycle = cycle,
                balances = balances,
                marketQuotes = marketQuotes,
                activePersistedOrders = workingOrders,
                managedPositions = entryManagedPositions,
            )
            if (scalpingSubmitted) {
                logger.info("[EXECUTION_BUY] pair=baseline reason=anomaly_quiet_light_scalping")
            }
        }
    }

    private suspend fun maybeRunAutonomousResolver(
        now: Instant,
        lease: EngineLeaseSnapshot,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ) {
        if (config.exchangeKind != ExchangeKind.INDODAX) return
        val lastRun = lastAutonomousResolverAt
        if (lastRun != null && (now - lastRun).inWholeMilliseconds < autonomousResolverIntervalMs) return
        lastAutonomousResolverAt = now

        val exchangeOpenOrders = cachedOpenOrders
            .filter { it.status in activeOrderStatuses }
            .associateBy { it.clientOrderId.value }

        val staleOpenOrders = cachedOpenOrders.filter { order ->
            order.status in activeOrderStatuses &&
                (now - order.updatedAt).inWholeMilliseconds >= autonomousResolverStaleOrderMs
        }

        var canceledAny = false
        staleOpenOrders.forEach { order ->
            val canceled = runCatching { exchange.cancelOrder(order.clientOrderId) }.getOrDefault(false)
            if (canceled) {
                canceledAny = true
                logger.info(
                    "[EXECUTION_SELL] pair={} reason=cancel_stale_active_order detail=clientOrderId={} side={} ageMs={}",
                    order.pairId.value,
                    order.clientOrderId.value,
                    order.side.name,
                    (now - order.updatedAt).inWholeMilliseconds,
                )
            }
        }

        val stalePersistedWithoutExchange = activePersistedOrders.filter { order ->
            val missingOnExchange = exchangeOpenOrders[order.clientOrderId.value] == null
            missingOnExchange && (now - order.updatedAt).inWholeMilliseconds >= autonomousResolverStaleOrderMs
        }
        if (stalePersistedWithoutExchange.isNotEmpty()) {
            val staleIds = stalePersistedWithoutExchange.map { it.clientOrderId }.toSet()
            cachedRecentOrders = cachedRecentOrders.filterNot { it.clientOrderId in staleIds }
            canceledAny = true
        }

        if (canceledAny) {
            repository.noteStatus("Autonomous resolver cleaned stale orders; lease retained for continuous execution.")
        }
    }

    private fun hasActiveBuyLock(
        now: Instant,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        pairId: PairId? = null,
    ): Boolean {
        val activeExchangeBuys = cachedOpenOrders.any { order ->
            order.side == com.kibot.shared.models.OrderSide.BUY &&
                order.status in activeOrderStatuses &&
                (pairId == null || order.pairId == pairId)
        }
        if (activeExchangeBuys) return true
        val freshPersistedBuys = activePersistedOrders.any { order ->
            order.side == com.kibot.shared.models.OrderSide.BUY &&
                order.status in activeOrderStatuses &&
                (pairId == null || order.pairId == pairId) &&
                (now - order.updatedAt).inWholeMilliseconds < autonomousResolverStaleOrderMs
        }
        return freshPersistedBuys
    }

    private fun hasRotationExitInFlight(
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): Boolean {
        if (managedPositions.isEmpty()) return false
        val activeSellPairs = activePersistedOrders
            .filter { it.status in activeOrderStatuses && it.side == com.kibot.shared.models.OrderSide.SELL }
            .map { it.pairId }
            .toSet()
        if (activeSellPairs.isEmpty()) return false
        return managedPositions.any { it.pairId in activeSellPairs }
    }

    private fun maybeBroadcastActivePositions(
        now: Instant,
        balances: List<BalanceSnapshot>,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        if (config.exchangeKind != ExchangeKind.INDODAX) return
        if (!config.leadLagUdpEnabled) return
        val peers = buildUdpPeerList()
        if (peers.isEmpty()) return
        val lastAt = lastActivePositionsBroadcastAt
        if (lastAt != null && (now - lastAt).inWholeMilliseconds < 3_000L) return
        val managedByPair = managedPositions.associateBy { it.pairId.value.lowercase() }
        val quoteByPair = marketQuotes.associateBy { it.pairId.value.lowercase() }
        val idrFree = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        val wires = balances
            .asSequence()
            .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = (balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()).coerceAtLeast(0.0)
                if (quantity <= 0.0) return@mapNotNull null
                val pairKey = "${balance.asset.lowercase()}_${referenceQuoteAsset()}".lowercase()
                val quote = quoteByPair[pairKey] ?: return@mapNotNull null
                val currentPrice = quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: return@mapNotNull null
                val managed = managedByPair[pairKey]
                val entryPrice = managed?.averageEntryPrice?.toDoubleOrZero()
                    ?.takeIf { it > 0.0 }
                    ?: currentPrice
                val pnlPct = managed?.unrealizedPnlPct
                    ?: if (entryPrice > 0.0) ((currentPrice - entryPrice) / entryPrice) * 100.0 else 0.0
                val pnlIdr = managed?.unrealizedPnlIdr?.toDoubleOrZero()
                    ?: (currentPrice - entryPrice) * quantity
                val notional = currentPrice * quantity
                if (notional < dustUiHideMinValueIdr) return@mapNotNull null
                ActivePositionWire(
                    pairId = pairKey,
                    entryPrice = entryPrice,
                    currentPrice = currentPrice,
                    pnlPct = pnlPct,
                    pnlIdr = pnlIdr,
                    quantity = quantity,
                    notionalIdr = notional,
                )
            }
            .toList()
        val totalEquity = idrFree + wires.sumOf { it.notionalIdr.coerceAtLeast(0.0) }
        val payload = ActivePositionsPayload(
            msgType = "ACTIVE_POSITIONS",
            senderBotId = config.controlPlane.botId.value,
            sentAtEpochMs = now.toEpochMilliseconds(),
            idrFree = idrFree,
            totalEquityIdr = totalEquity,
            positions = wires,
        )
        val sent = sendLeadLagUdp(json.encodeToString(payload))
        if (sent) {
            lastActivePositionsBroadcastAt = now
        }
    }

    private suspend fun maybeSubmitDirectHyperAggressiveEntry(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        hungry: Boolean,
        hyperTargets: List<HyperTargetCandidate>,
    ): Boolean {
        if (!hungry || hyperTargets.isEmpty()) return false
        val target = hyperTargets.first()
        entryBlockedByProtectiveBrake(now, target.pairId)?.let {
            logWhyNotBuy(now, target.pairId.value, it)
            return false
        }
        if (hasActiveBuyLock(now = now, activePersistedOrders = activePersistedOrders, pairId = target.pairId)) return false
        if (managedPositions.any { it.pairId == target.pairId }) return false
        val synthetic = buildHyperAggressiveSyntheticEntryPlan(
            cycle = cycle,
            balances = balances,
            marketQuotes = marketQuotes,
            target = target,
        ) ?: return false
        val adaptedSynthetic = adaptExecutionPlanByCapital(
            executionPlan = synthetic,
            totalEquityIdr = deriveCapitalAwareness(cycle = cycle, balances = balances).totalEquityIdr,
        )
        var normalizedSynthetic = normalizeExecutionPlanForVenue(
            executionPlan = adaptedSynthetic,
            marketQuotes = marketQuotes,
        ) ?: return false
        normalizedSynthetic = routeByChartAnalyzer(
            executionPlan = normalizedSynthetic,
            marketQuotes = marketQuotes,
        ) ?: run {
            logWhyNotBuy(now, target.pairId.value, "chart_guard_blocked_hyper_entry")
            return false
        }
        // Maker optimization for non-spike entries (gradual/sexy). Keep MARKET for anomaly spikes.
        if (target.kind == HyperTargetKind.SEXY) {
            val quote = marketQuotes.firstOrNull { it.pairId == target.pairId }
            val bestBid = quote?.bestBid?.toDoubleOrZero()?.takeIf { it > 0.0 }
            if (bestBid != null) {
                normalizedSynthetic = normalizedSynthetic.copy(
                    orderType = com.kibot.shared.models.OrderType.LIMIT,
                    limitPrice = DecimalValue.fromDouble(bestBid),
                    postOnlyPreferred = true,
                )
            }
        }
        // Liquidity guard for Indodax all-in/taker style entries.
        if (config.exchangeKind == ExchangeKind.INDODAX && normalizedSynthetic.orderType == com.kibot.shared.models.OrderType.MARKET) {
            val quote = marketQuotes.firstOrNull { it.pairId == target.pairId }
            val budgetIdr = normalizedSynthetic.quoteBudget?.toDoubleOrZero()?.takeIf { it > 0.0 }
                ?: (normalizedSynthetic.quantity.toDoubleOrZero() * (quote?.bestAsk?.toDoubleOrZero() ?: 0.0))
            val bidTop5 = quote?.bidDepthTop5Idr?.toDoubleOrZero() ?: 0.0
            if (budgetIdr > 0.0) {
                val requiredBidDepth = budgetIdr * 1.10
                if (bidTop5 < requiredBidDepth) {
                    logWhyNotBuy(
                        now,
                        target.pairId.value,
                        "liquidity_guard_failed bidTop5=${formatDecimal(bidTop5, 0)} required=${formatDecimal(requiredBidDepth, 0)}",
                    )
                    return false
                }
            }
        }
        val antiKoinMahalBlocked = entryBlockedByAntiKoinMahal(
            executionPlan = normalizedSynthetic,
            balances = balances,
            marketQuotes = marketQuotes,
        )
        if (antiKoinMahalBlocked != null) {
            appendAuditLog(
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = antiKoinMahalBlocked,
            )
            return false
        }
        val capitalMismatchBlocked = entryBlockedByCapitalMismatch(
            executionPlan = normalizedSynthetic,
            balances = balances,
            marketQuotes = marketQuotes,
        )
        if (capitalMismatchBlocked != null) {
            appendAuditLog(
                level = LogLevel.INFO,
                category = "ENTRY_POLICY",
                message = capitalMismatchBlocked,
            )
            return false
        }
        val blueChipBlocked = entryBlockedByBlueChipVolume(
            executionPlan = normalizedSynthetic,
            marketQuotes = marketQuotes,
        )
        if (blueChipBlocked != null) {
            logWhyNotBuy(now, target.pairId.value, blueChipBlocked)
            return false
        }
        normalizedSynthetic = enforceMaxSpreadCapForMarketBuy(
            executionPlan = normalizedSynthetic,
            marketQuotes = marketQuotes,
        ) ?: run {
            logWhyNotBuy(now, target.pairId.value, "spread_cap_enforcement_failed")
            return false
        }
        normalizedSynthetic = routeByDepthGuard(
            executionPlan = normalizedSynthetic,
            marketQuotes = marketQuotes,
        )
        normalizedSynthetic = applyAdaptiveOrderSlicing(
            executionPlan = normalizedSynthetic,
            marketQuotes = marketQuotes,
        )
        val result = liveExecutionCoordinator.submitEntry(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            term = lease.term,
            executionPlan = normalizedSynthetic,
            existingPersistedOrders = activePersistedOrders,
            exchange = exchange,
            controlPlane = controlPlane,
        )
        appendAuditLog(
            level = if (result.submitted) LogLevel.INFO else LogLevel.WARN,
            category = "BUY_MOMENTUM",
            message = if (result.submitted) {
                "BUY_MOMENTUM ${target.pairId.value} via direct hyper entry (${target.kind.name})."
            } else {
                "Direct hyper entry ${target.pairId.value} gagal: ${result.message}"
            },
        )
        if (result.submitted) {
            val pairKey = target.pairId.value.lowercase()
            hyperAggressiveTrackedEntryAtByPair[pairKey] = now
            hyperAggressivePeakBidByPair[pairKey] = marketQuotes.firstOrNull { it.pairId == target.pairId }?.bestBid?.toDoubleOrZero() ?: 0.0
            hyperAggressiveEntryReasonByPair[pairKey] = target.kind
            result.order?.let {
                cachedRecentOrders = mergeRecentOrders(cachedRecentOrders, listOf(it))
                recentOrdersFetchedAt = now
            }
            if (target.kind == HyperTargetKind.SUPER_SEXY) {
                lastSuperSexyTarget = target.pairId
            }
            return true
        }
        return false
    }

    private suspend fun maybeSubmitDynamicVipEntry(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        managedPositions: List<com.kibot.core.ManagedPosition>,
    ): Boolean {
        if (config.exchangeKind != ExchangeKind.INDODAX) return false
        if (globalCooldownUntil?.let { now < it } == true) return false
        val rotationExitInFlight = hasRotationExitInFlight(
            managedPositions = managedPositions,
            activePersistedOrders = activePersistedOrders,
        )
        if (managedPositions.isNotEmpty() && !rotationExitInFlight) return false
        val activeBuy = hasActiveBuyLock(
            now = now,
            activePersistedOrders = activePersistedOrders,
            pairId = null,
        )
        if (activeBuy) return false
        val idrFree = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        if (idrFree < 10_500.0) return false
        val vipCandidates = marketQuotes
            .asSequence()
            .filter { it.pairId.pairAssets().quoteAsset.equals("idr", ignoreCase = true) }
            .filter { estimateQuoteVolumeIdr(it, marketQuotes) >= config.aListMinVolumeIdr }
            .filter { it.midPrice.toDoubleOrZero() > 0.0 }
            .filter { it.spreadPct <= 3.0 }
            .filter { it.estimatedSlippagePct <= 2.4 }
            .filter { quote ->
                val entryPrice = quote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: 0.0
                val affordabilityRatio = if (entryPrice > 0.0) idrFree / entryPrice else 0.0
                idrFree > 150_000.0 || affordabilityRatio >= 4.0
            }
            .filter { quote ->
                isDynamicVipActive(quote.pairId, now) ||
                    hasStrongGlobalSentiment(quote.pairId) ||
                    quote.shortTermReturnPct >= dynamicVipMinShortTermSurgePct
            }
            .sortedByDescending { quote ->
                val dynamicBoost = if (isDynamicVipActive(quote.pairId, now)) 2.3 else 0.0
                val globalBoost = if (hasStrongGlobalSentiment(quote.pairId)) 1.4 else 0.0
                val volumeBoost = (estimateQuoteVolumeIdr(quote, marketQuotes) / 100_000_000.0).coerceAtMost(4.5) * 0.22
                dynamicBoost +
                    globalBoost +
                    (quote.shortTermReturnPct * 0.70) +
                    (quote.mediumTermReturnPct * 0.35) +
                    (quote.recentTradeActivityScore * 0.95) +
                    volumeBoost
            }
            .toList()
        val targetQuote = vipCandidates.firstOrNull() ?: return false
        val projectedNetPct = projectedEntryNetPct(
            quote = targetQuote,
            assumeTaker = true,
        )
        if (projectedNetPct <= dynamicVipMinProjectedNetPct) {
            logWhyNotBuy(
                now = now,
                pair = targetQuote.pairId.value,
                reason = "dynamic_vip_net_projection_too_low(${formatDecimal(projectedNetPct, 2)}%)",
            )
            return false
        }
        entryBlockedByProtectiveBrake(now, targetQuote.pairId)?.let { blocked ->
            logWhyNotBuy(now, targetQuote.pairId.value, blocked)
            return false
        }
        val pairScore = cycle.rankedPairs.firstOrNull { it.pairId == targetQuote.pairId }?.rankingScore ?: 0.60
        val reservedFeeBuffer = maxOf(300.0, idrFree * 0.012)
        val spendableBudgetIdr = (idrFree - reservedFeeBuffer).coerceAtLeast(0.0)
        if (spendableBudgetIdr < 10_500.0) {
            logWhyNotBuy(
                now = now,
                pair = targetQuote.pairId.value,
                reason = "insufficient_idr_after_buffer(${formatDecimal(spendableBudgetIdr, 0)})",
            )
            return false
        }
        val budgetIdr = spendableBudgetIdr
        val bestAsk = targetQuote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 } ?: return false
        val bestBid = targetQuote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: bestAsk
        val useMarketOrder = isDynamicVipActive(targetQuote.pairId, now) &&
            targetQuote.shortTermReturnPct >= dynamicVipMarketEntryShortTermMinPct &&
            targetQuote.spreadPct <= dynamicVipMarketEntryMaxSpreadPct &&
            targetQuote.estimatedSlippagePct <= dynamicVipMarketEntryMaxSlippagePct
        val refPrice = if (useMarketOrder) bestAsk else bestBid
        val quantity = (budgetIdr / refPrice).coerceAtLeast(0.00000001)
        val executionPlan = com.kibot.shared.models.ExecutionPlan(
            signal = com.kibot.shared.models.StrategySignal(
                pairId = targetQuote.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.BREAKOUT_ENTRY,
                confidence = pairScore.coerceIn(0.42, 0.92),
                rationale = listOf(
                    "Dynamic VIP entry aktif: momentum/global sentiment + chart/history + volume sehat.",
                ),
                entryPrice = DecimalValue.fromDouble(refPrice),
                takeProfitPrice = null,
                stopPrice = null,
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
                horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
                pairTier = com.kibot.shared.models.PairTier.TIER_B,
                speculativePocket = false,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = 0.25,
                expectedNetProfitabilityPct = maxOf(projectedNetPct, 0.20),
            ),
            side = com.kibot.shared.models.OrderSide.BUY,
            orderType = if (useMarketOrder) com.kibot.shared.models.OrderType.MARKET else com.kibot.shared.models.OrderType.LIMIT,
            quantity = DecimalValue.fromDouble(quantity),
            limitPrice = if (useMarketOrder) null else DecimalValue.fromDouble(bestBid),
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
            postOnlyPreferred = !useMarketOrder,
            expectedNetEdgePct = maxOf(projectedNetPct, 0.20),
            botMode = cycle.modeSnapshot.mode,
            riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
            pairRankingScore = pairScore,
            speculativePocket = false,
        )
        var normalized = normalizeExecutionPlanForVenue(executionPlan, marketQuotes) ?: return false
        normalized = routeByChartAnalyzer(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        ) ?: run {
            logWhyNotBuy(now, targetQuote.pairId.value, "chart_guard_blocked_dynamic_vip")
            return false
        }
        val slippageGuardFailure = evaluateLeadLagSlippageGuard(
            now = now,
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )
        if (slippageGuardFailure != null) {
            logWhyNotBuy(now, targetQuote.pairId.value, slippageGuardFailure)
            return false
        }
        normalized = routeByDepthGuard(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )
        normalized = applyAdaptiveOrderSlicing(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )
        entryBlockedByAntiKoinMahal(
            executionPlan = normalized,
            balances = balances,
            marketQuotes = marketQuotes,
        )?.let { blocked ->
            logWhyNotBuy(now, targetQuote.pairId.value, blocked)
            return false
        }
        entryBlockedByCapitalMismatch(
            executionPlan = normalized,
            balances = balances,
            marketQuotes = marketQuotes,
        )?.let { blocked ->
            logWhyNotBuy(now, targetQuote.pairId.value, blocked)
            return false
        }
        entryBlockedByBlueChipVolume(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )?.let { blocked ->
            logWhyNotBuy(now, targetQuote.pairId.value, blocked)
            return false
        }
        entryBlockedByShortFlatChart(
            executionPlan = normalized,
        )?.let { blocked ->
            logWhyNotBuy(now, targetQuote.pairId.value, blocked)
            return false
        }
        val result = liveExecutionCoordinator.submitEntry(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            term = lease.term,
            executionPlan = normalized,
            existingPersistedOrders = activePersistedOrders,
            exchange = exchange,
            controlPlane = controlPlane,
        )
        if (!result.submitted) {
            logWhyNotBuy(now, targetQuote.pairId.value, result.message)
            return false
        }
        markDynamicVip(targetQuote.pairId, now, "dynamic_vip_entry")
        result.order?.let {
            cachedRecentOrders = mergeRecentOrders(cachedRecentOrders, listOf(it))
            recentOrdersFetchedAt = now
        }
        logger.info(
            "[EXECUTION_BUY] pair={} reason=dynamic_vip mode={} budgetIdr={}",
            targetQuote.pairId.value,
            if (useMarketOrder) "MARKET" else "LIMIT",
            formatDecimal(budgetIdr, 0),
        )
        return true
    }

    private suspend fun maybeSubmitLightScalpingEntry(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        managedPositions: List<com.kibot.core.ManagedPosition>,
    ): Boolean {
        if (config.exchangeKind != ExchangeKind.INDODAX) return false
        if (globalCooldownUntil?.let { now < it } == true) {
            logWhyNotBuy(now, "baseline", "GLOBAL_COOLDOWN_ACTIVE sampai ${formatJktTime(globalCooldownUntil!!)}")
            return false
        }
        val rotationExitInFlight = hasRotationExitInFlight(
            managedPositions = managedPositions,
            activePersistedOrders = activePersistedOrders,
        )
        if (managedPositions.isNotEmpty() && !rotationExitInFlight) {
            logWhyNotBuy(now, "baseline", "existing_managed_positions")
            return false
        }
        val activeBuy = hasActiveBuyLock(
            now = now,
            activePersistedOrders = activePersistedOrders,
            pairId = null,
        )
        if (activeBuy) {
            logWhyNotBuy(now, "baseline", "active_buy_order_exists")
            return false
        }
        val idrFree = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        if (idrFree < 10_500.0) {
            logWhyNotBuy(now, "baseline", "insufficient_idr_free(${formatDecimal(idrFree, 0)})")
            return false
        }
        val baselineCandidates = marketQuotes
            .asSequence()
            .filter { it.pairId.pairAssets().quoteAsset.equals("idr", ignoreCase = true) }
            .filter { estimateQuoteVolumeIdr(it, marketQuotes) >= config.aListMinVolumeIdr }
            .filter { it.spreadPct <= 2.8 }
            .filter { it.estimatedSlippagePct <= 2.2 }
            .filter { it.midPrice.toDoubleOrZero() > 0.0 }
            .filter { quote ->
                val entryPrice = quote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: quote.midPrice.toDoubleOrZero().takeIf { it > 0.0 }
                    ?: 0.0
                val affordabilityRatio = if (entryPrice > 0.0) idrFree / entryPrice else 0.0
                idrFree > 150_000.0 || affordabilityRatio >= 4.0
            }
            .toList()
        val targetQuote = baselineCandidates
            .asSequence()
            .filter { it.shortTermReturnPct >= -0.60 }
            .sortedByDescending {
                val dynamicBoost = if (isDynamicVipActive(it.pairId, now)) 1.9 else 0.0
                val globalBoost = if (hasStrongGlobalSentiment(it.pairId)) 1.1 else 0.0
                val volumeBoost = (estimateQuoteVolumeIdr(it, marketQuotes) / 100_000_000.0).coerceAtMost(2.0) * 0.20
                dynamicBoost + globalBoost + (it.shortTermReturnPct * 0.55) + (it.recentTradeActivityScore * 0.30) + volumeBoost
            }
            .firstOrNull()
            ?: baselineCandidates
                .asSequence()
                .filter { it.shortTermReturnPct >= -0.45 }
                .sortedByDescending {
                    val dynamicBoost = if (isDynamicVipActive(it.pairId, now)) 1.30 else 0.0
                    val globalBoost = if (hasStrongGlobalSentiment(it.pairId)) 0.70 else 0.0
                    val volumeBoost = (estimateQuoteVolumeIdr(it, marketQuotes) / 100_000_000.0).coerceAtMost(2.5) * 0.25
                    dynamicBoost + globalBoost + volumeBoost + it.shortTermReturnPct + it.recentTradeActivityScore
                }
                .firstOrNull()
            ?: run {
                logWhyNotBuy(now, "baseline", "no_liquid_baseline_candidate")
                return false
            }
        val projectedNetPct = projectedEntryNetPct(
            quote = targetQuote,
            assumeTaker = false,
        )
        if (projectedNetPct <= baselineMinProjectedNetPct) {
            logWhyNotBuy(
                now = now,
                pair = targetQuote.pairId.value,
                reason = "baseline_net_projection_too_low(${formatDecimal(projectedNetPct, 2)}%)",
            )
            return false
        }
        entryBlockedByProtectiveBrake(now, targetQuote.pairId)?.let {
            logWhyNotBuy(now, targetQuote.pairId.value, it)
            return false
        }
        val pairScore = cycle.rankedPairs.firstOrNull { it.pairId == targetQuote.pairId }?.rankingScore ?: 0.62
        val makerEntryPrice = targetQuote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: return false
        val reservedFeeBuffer = maxOf(300.0, idrFree * 0.015)
        val spendableBudgetIdr = (idrFree - reservedFeeBuffer).coerceAtLeast(0.0)
        if (spendableBudgetIdr < 10_500.0) {
            logWhyNotBuy(now, "baseline", "insufficient_idr_after_buffer(${formatDecimal(spendableBudgetIdr, 0)})")
            return false
        }
        val budgetIdr = spendableBudgetIdr
        val quantity = (budgetIdr / makerEntryPrice).coerceAtLeast(0.00000001)
        val executionPlan = com.kibot.shared.models.ExecutionPlan(
            signal = com.kibot.shared.models.StrategySignal(
                pairId = targetQuote.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.BREAKOUT_ENTRY,
                confidence = pairScore.coerceIn(0.45, 0.9),
                rationale = listOf("Light scalping fallback aktif untuk menjaga mesin terus bergerak."),
                entryPrice = DecimalValue.fromDouble(makerEntryPrice),
                takeProfitPrice = null,
                stopPrice = null,
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
                horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
                pairTier = com.kibot.shared.models.PairTier.TIER_B,
                speculativePocket = false,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = 0.3,
                expectedNetProfitabilityPct = maxOf(targetQuote.shortTermReturnPct, 0.1),
            ),
            side = com.kibot.shared.models.OrderSide.BUY,
            orderType = com.kibot.shared.models.OrderType.LIMIT,
            quantity = DecimalValue.fromDouble(quantity),
            limitPrice = DecimalValue.fromDouble(makerEntryPrice),
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
            postOnlyPreferred = true,
            expectedNetEdgePct = maxOf(targetQuote.shortTermReturnPct, 0.1),
            botMode = cycle.modeSnapshot.mode,
            riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
            pairRankingScore = pairScore,
            speculativePocket = false,
        )
        var normalized = normalizeExecutionPlanForVenue(executionPlan, marketQuotes) ?: run {
            logWhyNotBuy(now, targetQuote.pairId.value, "venue_normalization_failed")
            return false
        }
        normalized = routeByChartAnalyzer(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        ) ?: run {
            logWhyNotBuy(now, targetQuote.pairId.value, "chart_guard_blocked_baseline")
            return false
        }
        val slippageGuardFailure = evaluateLeadLagSlippageGuard(
            now = now,
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )
        if (slippageGuardFailure != null) {
            logWhyNotBuy(now, targetQuote.pairId.value, slippageGuardFailure)
            return false
        }
        val antiKoinMahalBlocked = entryBlockedByAntiKoinMahal(
            executionPlan = normalized,
            balances = balances,
            marketQuotes = marketQuotes,
        )
        if (antiKoinMahalBlocked != null) {
            logWhyNotBuy(now, targetQuote.pairId.value, antiKoinMahalBlocked)
            return false
        }
        val capitalMismatchBlocked = entryBlockedByCapitalMismatch(
            executionPlan = normalized,
            balances = balances,
            marketQuotes = marketQuotes,
        )
        if (capitalMismatchBlocked != null) {
            logWhyNotBuy(now, targetQuote.pairId.value, capitalMismatchBlocked)
            return false
        }
        val blueChipBlocked = entryBlockedByBlueChipVolume(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )
        if (blueChipBlocked != null) {
            logWhyNotBuy(now, targetQuote.pairId.value, blueChipBlocked)
            return false
        }
        val shortFlatChartBlocked = entryBlockedByShortFlatChart(
            executionPlan = normalized,
        )
        if (shortFlatChartBlocked != null) {
            logWhyNotBuy(now, targetQuote.pairId.value, shortFlatChartBlocked)
            return false
        }
        normalized = applyAdaptiveOrderSlicing(
            executionPlan = normalized,
            marketQuotes = marketQuotes,
        )
        val result = liveExecutionCoordinator.submitEntry(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            term = lease.term,
            executionPlan = normalized,
            existingPersistedOrders = activePersistedOrders,
            exchange = exchange,
            controlPlane = controlPlane,
        )
        if (result.submitted) {
            result.order?.let {
                cachedRecentOrders = mergeRecentOrders(cachedRecentOrders, listOf(it))
                recentOrdersFetchedAt = now
            }
            logger.info(
                "[EXECUTION_BUY] pair={} reason=baseline_scalping budgetIdr={}",
                targetQuote.pairId.value,
                formatDecimal(budgetIdr, 0),
            )
        }
        if (!result.submitted) {
            logWhyNotBuy(now, targetQuote.pairId.value, result.message)
        }
        return result.submitted
    }

    private fun routeEntryPlanByLatency(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        health: EngineHealthSnapshot,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): EntryRoutingDecision {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) {
            return EntryRoutingDecision(executionPlan = executionPlan)
        }
        val latencyMs = health.feedLatencyMs
        val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId }
        return when {
            latencyMs == null || latencyMs <= makerFirstMaxLatencyMs -> {
                if (executionPlan.orderType == com.kibot.shared.models.OrderType.LIMIT && executionPlan.postOnlyPreferred) {
                    EntryRoutingDecision(executionPlan = executionPlan)
                } else {
                    val makerPrice = executionPlan.signal.entryPrice
                        ?: executionPlan.limitPrice
                        ?: quote?.bestBid
                        ?: quote?.midPrice
                        ?: return EntryRoutingDecision(
                            executionPlan = null,
                            blockedReason = "Entry ${executionPlan.signal.pairId.value} diblokir karena harga maker tidak tersedia.",
                        )
                    EntryRoutingDecision(
                        executionPlan = executionPlan.copy(
                            orderType = com.kibot.shared.models.OrderType.LIMIT,
                            limitPrice = makerPrice,
                            postOnlyPreferred = true,
                        ),
                        message = "Ping hijau ${latencyLabel(latencyMs)}. Entry ${executionPlan.signal.pairId.value} dipaksa maker-first LIMIT.",
                    )
                }
            }

            latencyMs <= aggressiveLimitFallbackLatencyMs -> {
                val fastLimitPrice = quote?.bestAsk
                    ?: executionPlan.limitPrice
                    ?: executionPlan.signal.entryPrice
                    ?: return EntryRoutingDecision(
                        executionPlan = null,
                        blockedReason = "Entry ${executionPlan.signal.pairId.value} ditunda karena harga fallback tidak tersedia saat ping ${latencyMs}ms.",
                    )
                EntryRoutingDecision(
                    executionPlan = executionPlan.copy(
                        orderType = com.kibot.shared.models.OrderType.LIMIT,
                        limitPrice = fastLimitPrice,
                        postOnlyPreferred = false,
                    ),
                    message = "Ping kuning ${latencyMs}ms. Entry ${executionPlan.signal.pairId.value} diturunkan ke LIMIT biasa agar tidak bergantung maker-only.",
                )
            }

            else -> {
                val breakoutExceptionEligible =
                    executionPlan.signal.signalType == com.kibot.shared.models.StrategySignalType.BREAKOUT_ENTRY &&
                        executionPlan.signal.confidence >= 0.74 &&
                        executionPlan.expectedNetEdgePct >= 0.95 &&
                        quote != null &&
                        quote.recentTradeActivityScore >= 0.58 &&
                        quote.estimatedSlippagePct <= 0.95 &&
                        quote.spreadPct <= 1.45 &&
                        health.syncHealth != SyncHealth.BROKEN
                if (breakoutExceptionEligible) {
                    val fastLimitPrice = quote?.bestAsk
                        ?: executionPlan.limitPrice
                        ?: executionPlan.signal.entryPrice
                        ?: return EntryRoutingDecision(
                            executionPlan = null,
                            blockedReason = "Entry breakout ${executionPlan.signal.pairId.value} gagal karena harga fast-limit tidak tersedia saat ping ${latencyMs}ms.",
                        )
                    EntryRoutingDecision(
                        executionPlan = executionPlan.copy(
                            orderType = com.kibot.shared.models.OrderType.LIMIT,
                            limitPrice = fastLimitPrice,
                            postOnlyPreferred = false,
                        ),
                        message = "Ping merah ${latencyMs}ms, tapi breakout ${executionPlan.signal.pairId.value} cukup kuat. Bot tetap izinkan fast-limit exception agar tidak telat ke momentum.",
                    )
                } else {
                    EntryRoutingDecision(
                        executionPlan = null,
                        blockedReason = "Ping merah ${latencyMs}ms. Entry baru ${executionPlan.signal.pairId.value} diblokir sampai feed pulih; bot hanya fokus monitor/exit aman.",
                    )
                }
            }
        }
    }

    private fun routeExitPlanBySmartSell(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        exitReasonMessage: String,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.SELL) return executionPlan
        val pairId = executionPlan.signal.pairId
        val pairKey = pairId.value.lowercase()
        val quote = marketQuotes.firstOrNull { it.pairId == pairId }
        val activeCallout = activeLeadLagCallout?.takeIf { it.pairId == pairId }
        val crashStyleExit = exitReasonMessage.startsWith("CRASH_GUARD", ignoreCase = true) ||
            exitReasonMessage.contains("HARD_STOP", ignoreCase = true) ||
            exitReasonMessage.contains("BTC_DUMP", ignoreCase = true)
        if (crashStyleExit) {
            return executionPlan.copy(
                orderType = com.kibot.shared.models.OrderType.MARKET,
                limitPrice = null,
                postOnlyPreferred = false,
            )
        }
        val gradualReversal = activeCallout != null &&
            activeCallout.msgType in setOf("MOMENTUM_LOSS", "SELL_WALL_SURGE") &&
            activeCallout.trend.equals("REVERSAL", ignoreCase = true) &&
            activeCallout.shortTermReturnPct > -1.2
        if (!gradualReversal) return executionPlan
        val limitSellPrice = quote?.bestAsk?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: return executionPlan
        logger.info(
            "Smart sell gradual {}: gunakan LIMIT ask {} untuk hemat fee maker.",
            pairKey,
            formatDecimal(limitSellPrice, 8),
        )
        return executionPlan.copy(
            orderType = com.kibot.shared.models.OrderType.LIMIT,
            limitPrice = DecimalValue.fromDouble(limitSellPrice),
            postOnlyPreferred = true,
        )
    }

    private suspend fun evaluateLeadLagSlippageGuard(
        now: Instant,
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): String? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return null
        val pairKey = executionPlan.signal.pairId.value.lowercase()
        val traceId = leadLagTraceByPair[pairKey] ?: return null

        val impact = runCatching {
            exchange.estimateMarketBuyImpact(
                pairId = executionPlan.signal.pairId,
                quoteBudget = leadLagSlippageGuardQuoteBudgetIdr,
            )
        }.getOrNull()
        val slippagePct = when {
            impact != null -> impact.slippagePct
            else -> {
                val quote = marketQuotes.firstOrNull { it.pairId == executionPlan.signal.pairId }
                quote?.estimatedSlippagePct ?: Double.POSITIVE_INFINITY
            }
        }
        val bookExhausted = impact?.exhaustedBook == true
        if (slippagePct <= leadLagSlippageGuardMaxPct && !bookExhausted) return null

        val detectedAtMs = leadLagDetectedAtByPair[pairKey]
        val receivedAtMs = leadLagReceivedAtByPair[pairKey]?.toEpochMilliseconds()
        emitLeadLagExecutionReport(
            pairId = executionPlan.signal.pairId,
            status = "ABORTED_SLIPPAGE",
            t0DetectedAtMs = detectedAtMs,
            t1ReceivedAtMs = receivedAtMs,
            t2BuyAtMs = null,
            t3SellAtMs = null,
            slippagePct = slippagePct.takeIf { it.isFinite() },
            finalPnlIdr = 0.0,
        )
        emitLeadLagTelemetry(
            LeadLagTelemetryEvent(
                event = "ABORTED_SLIPPAGE",
                traceId = traceId,
                pairId = executionPlan.signal.pairId.value,
                coinClass = classifyPair(executionPlan.signal.pairId).name.lowercase(),
                sourceBotId = config.controlPlane.botId.value,
                targetBotId = null,
                t0DetectedAtEpochMs = detectedAtMs,
                t1UdpSentAtEpochMs = leadLagOriginSentAtByPair[pairKey],
                t2UdpReceivedAtEpochMs = receivedAtMs,
                buyPrice = impact?.averagePrice,
                slippagePct = slippagePct.takeIf { it.isFinite() },
                note = "Slippage guard block (> ${leadLagSlippageGuardMaxPct}%).",
            ),
        )
        leadLagEntrySubmittedAtByPair.remove(pairKey)
        leadLagReceivedAtByPair.remove(pairKey)
        leadLagOriginSentAtByPair.remove(pairKey)
        leadLagTraceByPair.remove(pairKey)
        leadLagDetectedAtByPair.remove(pairKey)
        leadLagTrailingPeakBidByPair.remove(pairKey)
        activeLeadLagCallout = activeLeadLagCallout?.takeUnless { it.pairId == executionPlan.signal.pairId }
        return "Lead-lag ${executionPlan.signal.pairId.value} diblokir slippage guard ${formatDecimal(slippagePct, 2)}% (budget Rp${formatDecimal(leadLagSlippageGuardQuoteBudgetIdr, 0)})."
    }

    private fun planForcedSellByTrinityConfirm(
        now: Instant,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
    ): com.kibot.core.ExitDecision? {
        if (managedPositions.isEmpty() || forcedSellTraceByPair.isEmpty()) return null
        val nowMs = now.toEpochMilliseconds()
        forcedSellTraceByPair.entries.removeIf { (_, signal) -> signal.expiresAtEpochMs <= nowMs }
        if (forcedSellTraceByPair.isEmpty()) return null
        val activeByPair = activeOrders
            .filter { it.status in activeOrderStatuses }
            .groupBy { it.pairId }
        val scoredByPair = cycle.rankedPairs.associateBy { it.pairId }
        val forcedPair = managedPositions.firstOrNull { position ->
            val key = position.pairId.value.lowercase()
            val hasSellOrder = activeByPair[position.pairId].orEmpty().any { it.side == com.kibot.shared.models.OrderSide.SELL }
            val signal = forcedSellTraceByPair[key]
            signal != null && signal.expiresAtEpochMs > nowMs && !hasSellOrder
        } ?: return null
        val pairScore = scoredByPair[forcedPair.pairId]
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = forcedPair.pairId,
            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
            confidence = 0.99,
            rationale = listOf("REVERSAL confirmed (Kinance + KiBot veto): sell paksa tanpa menunggu stagnan."),
            entryPrice = forcedPair.currentBidPrice,
            takeProfitPrice = forcedPair.takeProfitPrice,
            stopPrice = forcedPair.stopPrice,
            setupType = forcedPair.setupType,
            horizon = forcedPair.horizon,
            pairTier = forcedPair.pairTier,
            speculativePocket = forcedPair.speculativePocket,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = forcedPair.expectedHoldingHours,
            expectedNetProfitabilityPct = kotlin.math.abs(forcedPair.unrealizedPnlPct),
        )
        return com.kibot.core.ExitDecision(
            position = forcedPair,
            reason = com.kibot.core.ExitReason.ROTATION_EXIT,
            message = "TRINITY_V3 forced sell: ${forcedPair.pairId.value} due to VETO_SELL_CONFIRMED.",
            executionPlan = com.kibot.shared.models.ExecutionPlan(
                signal = signal,
                side = com.kibot.shared.models.OrderSide.SELL,
                orderType = com.kibot.shared.models.OrderType.MARKET,
                quantity = forcedPair.quantity,
                limitPrice = null,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(forcedPair.unrealizedPnlPct),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                pairRankingScore = pairScore?.rankingScore ?: 0.85,
                speculativePocket = forcedPair.speculativePocket,
            ),
        )
    }

    private fun planLeadLagTrailingExit(
        now: Instant,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
    ): com.kibot.core.ExitDecision? {
        if (managedPositions.isEmpty()) return null
        val activeByPair = activeOrders
            .filter { it.status in activeOrderStatuses }
            .groupBy { it.pairId }
        val scoredByPair = cycle.rankedPairs.associateBy { it.pairId }

        return managedPositions.firstOrNull { position ->
            val pairKey = position.pairId.value.lowercase()
            if (pairKey !in leadLagEntrySubmittedAtByPair.keys) return@firstOrNull false
            val existingPeak = leadLagTrailingPeakBidByPair[pairKey]
            val currentBid = position.currentBidPrice.toDoubleOrZero()
            val peak = maxOf(existingPeak ?: currentBid, currentBid)
            leadLagTrailingPeakBidByPair[pairKey] = peak
            val entryPx = position.averageEntryPrice.toDoubleOrZero().coerceAtLeast(0.0000001)
            val gainPct = ((peak - entryPx) / entryPx) * 100.0
            val dynamicTrailingStopPct = dynamicTrailingStopPct(gainPct, currentBid)
            val armed = peak >= (entryPx * (1.0 + (leadLagTrailingArmMinGainPct / 100.0)))
            if (!armed) return@firstOrNull false
            val trailingFloor = peak * (1.0 - (dynamicTrailingStopPct / 100.0))
            val noSellOrder = activeByPair[position.pairId].orEmpty().none { it.side == com.kibot.shared.models.OrderSide.SELL }
            currentBid <= trailingFloor && noSellOrder
        }?.let { position ->
            val pairScore = scoredByPair[position.pairId]
            val signal = com.kibot.shared.models.StrategySignal(
                pairId = position.pairId,
                signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                confidence = (pairScore?.rankingScore ?: 0.66).coerceIn(0.45, 0.99),
                rationale = listOf("Elastic trailing stop lead-lag terpukul, exit cepat untuk kunci profit."),
                entryPrice = position.currentBidPrice,
                takeProfitPrice = position.takeProfitPrice,
                stopPrice = position.stopPrice,
                setupType = position.setupType,
                horizon = position.horizon,
                pairTier = position.pairTier,
                speculativePocket = position.speculativePocket,
                marketRegime = cycle.marketSnapshot.regime,
                edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                expectedHoldingHours = position.expectedHoldingHours,
                expectedNetProfitabilityPct = kotlin.math.abs(position.unrealizedPnlPct),
            )
            com.kibot.core.ExitDecision(
                position = position,
                reason = com.kibot.core.ExitReason.PROFIT_PROTECTION_EXIT,
                message = "Lead-lag trailing stop 1.5% aktif untuk ${position.pairId.value}.",
                executionPlan = com.kibot.shared.models.ExecutionPlan(
                    signal = signal,
                    side = com.kibot.shared.models.OrderSide.SELL,
                    orderType = com.kibot.shared.models.OrderType.MARKET,
                    quantity = position.quantity,
                    limitPrice = null,
                    quoteBudget = null,
                    postOnlyPreferred = false,
                    expectedNetEdgePct = kotlin.math.abs(position.unrealizedPnlPct),
                    botMode = cycle.modeSnapshot.mode,
                    riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                    pairRankingScore = pairScore?.rankingScore ?: 0.66,
                    speculativePocket = position.speculativePocket,
                ),
            )
        }
    }

    private fun entryBlockedByPortfolioState(
        cycle: com.kibot.core.StrategyCycleResult,
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        leadLagPriorityPair: com.kibot.shared.models.PairId?,
    ): String? {
        if (managedPositions.isEmpty()) return null

        val samePairExposure = managedPositions.firstOrNull { it.pairId == executionPlan.signal.pairId }
        if (samePairExposure != null && samePairExposure.unrealizedPnlPct < 0.20) {
            return "Masih pegang ${executionPlan.signal.pairId.value} dan posisinya belum cukup hijau, jadi bot tidak averaging dulu."
        }

        val slotsAreFull = managedPositions.size >= cycle.deploymentPlan.maxActivePositions.coerceAtLeast(1)

        if (!slotsAreFull) return null

        val activeSellPairs = activeOrders
            .filter { it.status in activeOrderStatuses && it.side == com.kibot.shared.models.OrderSide.SELL }
            .map { it.pairId }
            .toSet()
        if (activeSellPairs.isNotEmpty() && cycle.deploymentPlan.allowRotation) {
            val managedWaitingToExit = managedPositions.count { it.pairId in activeSellPairs }
            if (managedWaitingToExit >= 1) return null
        }

        if (leadLagPriorityPair != null && executionPlan.signal.pairId == leadLagPriorityPair && config.leadLagForceRotationOnReceive) {
            return null
        }

        if (!cycle.deploymentPlan.allowRotation) {
            return "Entry baru ditahan karena semua slot penuh dan kandidat pengganti belum cukup menang setelah biaya."
        }
        return null
    }

    private suspend fun manageStaleEntryOrders(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val currentEntryPairs = (
            cycle.entryExecutionPlans.map { it.signal.pairId } +
                listOfNotNull(cycle.selectedSignal?.pairId)
            ).toSet()
        val canceledSnapshots = mutableListOf<com.kibot.shared.models.OrderSnapshot>()
        recentOrders
            .filter { it.status in activeOrderStatuses && it.side == com.kibot.shared.models.OrderSide.BUY }
            .forEach { order ->
                val ageMinutes = ((now.toEpochMilliseconds() - order.createdAt.toEpochMilliseconds()).coerceAtLeast(0L) / 60_000.0)
                val bestAsk = quoteByPair[order.pairId]?.bestAsk?.toDoubleOrZero() ?: 0.0
                val orderPrice = order.price.toDoubleOrZero()
                val driftPct = if (bestAsk > 0.0 && orderPrice > 0.0) {
                    ((bestAsk - orderPrice) / orderPrice) * 100.0
                } else {
                    0.0
                }
                val pairFlipped = currentEntryPairs.isNotEmpty() && order.pairId !in currentEntryPairs
                val partialFillTimeout = order.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED &&
                    ageMinutes >= stalePartialFillMaxAgeMinutes
                val shouldCancel = ageMinutes >= staleEntryOrderMaxAgeMinutes ||
                    partialFillTimeout ||
                    (pairFlipped && ageMinutes >= staleEntryOrderPairFlipGraceMinutes) ||
                    driftPct >= staleEntryOrderMaxDriftPct
                if (!shouldCancel) return@forEach

                val canceled = exchange.cancelOrder(order.clientOrderId)
                if (canceled) {
                    val canceledSnapshot = order.copy(
                        status = com.kibot.shared.models.OrderStatus.CANCELED,
                        updatedAt = now,
                    )
                    controlPlane.upsertOrderSnapshot(
                        botId = config.controlPlane.botId,
                        term = lease.term.value,
                        deviceId = config.device.deviceId,
                        order = canceledSnapshot,
                    )
                    canceledSnapshots += canceledSnapshot
                    val shouldChaseMarket = ageMinutes >= 0.10 && driftPct >= 0.05
                    if (shouldChaseMarket) {
                        val ranked = cycle.rankedPairs.firstOrNull { it.pairId == order.pairId }
                        val signal = com.kibot.shared.models.StrategySignal(
                            pairId = order.pairId,
                            signalType = com.kibot.shared.models.StrategySignalType.BREAKOUT_ENTRY,
                            confidence = (ranked?.rankingScore ?: 0.75).coerceIn(0.55, 0.98),
                            rationale = listOf("Limit stale >10s dan harga kabur, chase market untuk eksekusi instan."),
                            entryPrice = quoteByPair[order.pairId]?.bestAsk,
                            setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
                            horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
                            pairTier = ranked?.pairTier ?: com.kibot.shared.models.PairTier.TIER_B,
                            speculativePocket = true,
                            marketRegime = cycle.marketSnapshot.regime,
                            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
                            expectedHoldingHours = 0.4,
                            expectedNetProfitabilityPct = 0.25,
                        )
                        val chasePlan = com.kibot.shared.models.ExecutionPlan(
                            signal = signal,
                            side = com.kibot.shared.models.OrderSide.BUY,
                            orderType = com.kibot.shared.models.OrderType.MARKET,
                            quantity = order.remainingQuantity.takeIf { it.toDoubleOrZero() > 0.0 } ?: order.originalQuantity,
                            limitPrice = null,
                            quoteBudget = null,
                            postOnlyPreferred = false,
                            expectedNetEdgePct = maxOf(0.20, ranked?.feeAdjustedEdgeScore ?: 0.20),
                            botMode = cycle.modeSnapshot.mode,
                            riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                            pairRankingScore = ranked?.rankingScore ?: 0.70,
                            speculativePocket = true,
                        )
                        val slicedChasePlan = applyAdaptiveOrderSlicing(
                            executionPlan = chasePlan,
                            marketQuotes = quoteByPair.values.toList(),
                        )
                        val chaseResult = liveExecutionCoordinator.submitEntry(
                            botId = config.controlPlane.botId,
                            deviceId = config.device.deviceId,
                            term = lease.term,
                            executionPlan = slicedChasePlan,
                            existingPersistedOrders = recentOrders,
                            exchange = exchange,
                            controlPlane = controlPlane,
                        )
                        if (chaseResult.submitted) {
                            logger.info("[ORDER_CHASE] pair={} action=CANCELED_LIMIT_AND_FIRED_MARKET", order.pairId.value)
                        } else {
                            logWhyNotBuy(now, order.pairId.value, "order_chase_failed:${chaseResult.message}")
                        }
                    }
                }
            }

        return mergeRecentOrders(
            base = recentOrders,
            updates = canceledSnapshots,
        )
    }

    private suspend fun manageStaleExitOrders(
        now: Instant,
        lease: EngineLeaseSnapshot,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        val positionsByPair = managedPositions.associateBy { it.pairId }
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val canceledSnapshots = mutableListOf<com.kibot.shared.models.OrderSnapshot>()
        recentOrders
            .filter {
                it.status in activeOrderStatuses &&
                    it.side == com.kibot.shared.models.OrderSide.SELL &&
                    it.orderType == com.kibot.shared.models.OrderType.LIMIT
            }
            .forEach { order ->
                val position = positionsByPair[order.pairId] ?: return@forEach
                val ageMinutes = ((now.toEpochMilliseconds() - order.createdAt.toEpochMilliseconds()).coerceAtLeast(0L) / 60_000.0)
                val bestBid = quoteByPair[order.pairId]?.bestBid?.toDoubleOrZero() ?: position.currentBidPrice.toDoubleOrZero()
                val orderPrice = order.price.toDoubleOrZero()
                val driftPct = if (bestBid > 0.0 && orderPrice > 0.0) {
                    ((orderPrice - bestBid) / orderPrice) * 100.0
                } else {
                    0.0
                }
                val partialFillTimeout = order.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED &&
                    ageMinutes >= stalePartialFillMaxAgeMinutes
                val shouldCancel = ageMinutes >= staleExitOrderMaxAgeMinutes ||
                    (order.status == com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED && ageMinutes >= stalePartialFillMaxAgeMinutes) ||
                    driftPct >= staleExitOrderMaxDriftPct ||
                    position.unrealizedPnlPct <= staleExitRepriceLossFloorPct
                if (!shouldCancel) return@forEach

                val canceled = exchange.cancelOrder(order.clientOrderId)
                if (canceled) {
                    val canceledSnapshot = order.copy(
                        status = com.kibot.shared.models.OrderStatus.CANCELED,
                        updatedAt = now,
                    )
                    controlPlane.upsertOrderSnapshot(
                        botId = config.controlPlane.botId,
                        term = lease.term.value,
                        deviceId = config.device.deviceId,
                        order = canceledSnapshot,
                    )
                    canceledSnapshots += canceledSnapshot
                    val shouldMarketDumpRemainder = partialFillTimeout &&
                        order.remainingQuantity.toDoubleOrZero() > 0.0
                    if (shouldMarketDumpRemainder) {
                        val position = managedPositions.firstOrNull { it.pairId == order.pairId } ?: return@forEach
                        val quote = quoteByPair[order.pairId]
                        val dumpSignal = com.kibot.shared.models.StrategySignal(
                            pairId = order.pairId,
                            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
                            confidence = 0.96,
                            rationale = listOf("Partial fill stale >5s, cancel and market dump sisa untuk hindari jebakan falling knife."),
                            entryPrice = quote?.bestBid ?: order.price,
                            takeProfitPrice = position.takeProfitPrice,
                            stopPrice = position.stopPrice,
                            setupType = position.setupType,
                            horizon = position.horizon,
                            pairTier = position.pairTier,
                            speculativePocket = position.speculativePocket,
                            marketRegime = com.kibot.shared.models.MarketRegime.HIGH_VOLATILITY_UNCLEAR,
                            edgeConfidence = com.kibot.shared.models.EdgeConfidence.MEDIUM,
                            expectedHoldingHours = position.expectedHoldingHours,
                            expectedNetProfitabilityPct = position.unrealizedPnlPct.coerceAtLeast(0.0),
                        )
                        val dumpPlan = com.kibot.shared.models.ExecutionPlan(
                            signal = dumpSignal,
                            side = com.kibot.shared.models.OrderSide.SELL,
                            orderType = com.kibot.shared.models.OrderType.MARKET,
                            quantity = order.remainingQuantity.takeIf { it.toDoubleOrZero() > 0.0 } ?: order.originalQuantity,
                            limitPrice = null,
                            quoteBudget = null,
                            postOnlyPreferred = false,
                            expectedNetEdgePct = position.unrealizedPnlPct.coerceAtLeast(0.0),
                            botMode = BotMode.GROWTH,
                            riskLadderLevel = com.kibot.shared.models.RiskLadderLevel.NORMAL,
                            pairRankingScore = 0.92,
                            speculativePocket = position.speculativePocket,
                        )
                        val dumpResult = liveExecutionCoordinator.submitExit(
                            botId = config.controlPlane.botId,
                            deviceId = config.device.deviceId,
                            term = lease.term,
                            executionPlan = dumpPlan,
                            existingPersistedOrders = recentOrders,
                            exchange = exchange,
                            controlPlane = controlPlane,
                        )
                        if (dumpResult.submitted) {
                            appendAuditLog(
                                level = LogLevel.WARN,
                                category = "AUTO_EXIT",
                                message = "Partial fill stale ${order.pairId.value} dibatalkan lalu market dump sisa ${formatDecimal(order.remainingQuantity.toDoubleOrZero(), 8)}.",
                            )
                        }
                    } else {
                        appendAuditLog(
                            level = LogLevel.WARN,
                            category = "AUTO_EXIT",
                            message = "Exit ${order.pairId.value} dibatalkan untuk reprice/fallback (${formatDecimal(ageMinutes, 1)}m, drift ${formatDecimal(driftPct, 2)}%).",
                        )
                    }
                }
            }

        return mergeRecentOrders(
            base = recentOrders,
            updates = canceledSnapshots,
        )
    }

    private suspend fun prepareExitPath(
        now: Instant,
        lease: EngineLeaseSnapshot,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        activePersistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        exitDecision: com.kibot.core.ExitDecision,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        if (exitDecision.executionPlan.orderType != com.kibot.shared.models.OrderType.MARKET) {
            return activePersistedOrders
        }
        val pairActiveSellOrders = activePersistedOrders.filter {
            it.pairId == exitDecision.position.pairId && it.side == com.kibot.shared.models.OrderSide.SELL
        }
        if (pairActiveSellOrders.isEmpty()) {
            return activePersistedOrders
        }

        val canceledSnapshots = mutableListOf<com.kibot.shared.models.OrderSnapshot>()
        pairActiveSellOrders.forEach { order ->
            val canceled = exchange.cancelOrder(order.clientOrderId)
            if (canceled) {
                val canceledSnapshot = order.copy(
                    status = com.kibot.shared.models.OrderStatus.CANCELED,
                    updatedAt = now,
                )
                controlPlane.upsertOrderSnapshot(
                    botId = config.controlPlane.botId,
                    term = lease.term.value,
                    deviceId = config.device.deviceId,
                    order = canceledSnapshot,
                )
                canceledSnapshots += canceledSnapshot
                appendAuditLog(
                    level = LogLevel.WARN,
                    category = "AUTO_EXIT",
                    message = "Exit lama ${order.clientOrderId.value} dibatalkan agar emergency exit ${exitDecision.position.pairId.value} bisa dijalankan.",
                )
            }
        }

        return mergeRecentOrders(
            base = recentOrders,
            updates = canceledSnapshots,
        ).filter { it.status in activeOrderStatuses }
    }

    private suspend fun maybePublishWeeklyLearningSummary(
        now: Instant,
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        currentWeeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): com.kibot.shared.models.WeeklyLearningSummary? {
        val shouldPublish = lastWeeklyReviewPublishedAt == null ||
            (now - lastWeeklyReviewPublishedAt!!).inWholeHours >= 1
        if (!shouldPublish) return currentWeeklyReview

        val summary = liveLearningReviewBuilder.build(
            botId = config.controlPlane.botId,
            now = now,
            cycle = cycle,
            marketQuotes = marketQuotes,
            recentOrders = recentOrders,
        ) ?: return currentWeeklyReview
        controlPlane.upsertWeeklyLearningSummary(summary)
        cachedWeeklyReview = summary
        weeklyReviewFetchedAt = now
        lastWeeklyReviewPublishedAt = now
        return summary
    }

    private suspend fun publishLearningSignalsIfNeeded(
        now: Instant,
        cycle: com.kibot.core.StrategyCycleResult,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        aiBlockedReason: String?,
        aiUsedNetwork: Boolean,
    ) {
        if (!config.supabaseNonCriticalWriteEnabled) return
        val decision = situationalLearningEngine.evaluate(
            botId = config.controlPlane.botId,
            deviceId = config.device.deviceId,
            now = now,
            cycle = cycle,
            weeklySummary = weeklyReview,
            aiBlockedReason = aiBlockedReason,
            aiUsedNetwork = aiUsedNetwork,
        )
        if (decision.learningHints.isEmpty() && decision.updateRecommendations.isEmpty()) return

        val shouldPublish = lastLearningPublishedAt == null ||
            decision.signature != lastLearningSignature ||
            (now - lastLearningPublishedAt!!).inWholeHours >= 1
        if (!shouldPublish) return

        decision.learningHints.take(2).forEach { hint ->
            appendAuditLog(
                level = when (hint.severity) {
                    com.kibot.shared.models.AdvisorySeverity.HIGH -> LogLevel.WARN
                    com.kibot.shared.models.AdvisorySeverity.MEDIUM -> LogLevel.INFO
                    com.kibot.shared.models.AdvisorySeverity.LOW -> LogLevel.INFO
                },
                category = "LEARNING_HINT",
                message = hint.summary,
            )
        }
        decision.updateRecommendations.forEach { recommendation ->
            controlPlane.upsertUpdateRecommendation(recommendation)
            appendAuditLog(
                level = LogLevel.INFO,
                category = "UPDATE_HINT",
                message = "${recommendation.title}: ${recommendation.summary}",
            )
        }

        lastLearningSignature = decision.signature
        lastLearningPublishedAt = now
    }

    private suspend fun appendAuditLog(level: LogLevel, category: String, message: String) {
        val normalizedCategory = category.uppercase()
        if (onlyRuntimeLogPrefixes.none { normalizedCategory.startsWith(it) }) return
        if (shouldExposeToLiveTimeline(normalizedCategory, message)) {
            repository.recordTimeline(
                category = normalizedCategory,
                message = message,
            )
        }
        if (!config.supabaseLogUploadEnabled) return
        val isCriticalUpload = normalizedCategory in importantSupabaseUploadCategories
        val minimumNonCriticalLevel = maxOf(config.supabaseLogMinLevel.ordinal, LogLevel.WARN.ordinal)
        if (!isCriticalUpload && level.ordinal < minimumNonCriticalLevel) return
        runCatching {
            controlPlane.appendLog(
                botId = config.controlPlane.botId,
                record = AuditLogRecord(
                    recordedAt = Clock.System.now(),
                    level = level,
                    category = normalizedCategory,
                    deviceId = config.device.deviceId,
                    term = lastObservedLeaseTerm,
                    message = message,
                ),
            )
        }.onFailure { logger.warn("Failed to append audit log: {}", it.message) }
    }

    private suspend fun appendThrottledAuditLog(
        now: Instant,
        level: LogLevel,
        category: String,
        message: String,
    ) {
        val signature = "$category|$message"
        val lastLoggedAt = lastExecutionPolicyLoggedAt
        if (
            lastExecutionPolicyLogSignature == signature &&
            lastLoggedAt != null &&
            (now - lastLoggedAt).inWholeMinutes < executionPolicyLogCooldownMinutes
        ) {
            return
        }
        lastExecutionPolicyLogSignature = signature
        lastExecutionPolicyLoggedAt = now
        appendAuditLog(level = level, category = category, message = message)
    }

    private fun recordDisplayPing(
        now: Instant,
        exchangeReachable: Boolean,
        rawPingMs: Long?,
    ): Long? {
        if (exchangeReachable && rawPingMs != null) {
            val next = smoothedExchangePingMs
                ?.let { (it * 0.72) + (rawPingMs.toDouble() * 0.28) }
                ?: rawPingMs.toDouble()
            smoothedExchangePingMs = next
            lastSuccessfulExchangePingAt = now
            return next.toLong().coerceAtLeast(1L)
        }

        smoothedExchangePingMs = null
        lastSuccessfulExchangePingAt = null
        return null
    }

    private fun deriveEffectiveState(
        now: Instant,
        botState: BotStateSnapshot,
        lease: EngineLeaseSnapshot?,
        healthDecision: com.kibot.core.EntryHealthDecision,
    ): BotEffectiveState {
        if (botState.desiredState == BotDesiredState.OFF) return BotEffectiveState.STOPPED
        val leaseHeld = lease.isHeldBy(config.device.deviceId, Clock.System.now())
        return when {
            healthDecision.tradingAllowed -> BotEffectiveState.RUNNING
            leaseHeld -> BotEffectiveState.DEGRADED
            else -> BotEffectiveState.DEGRADED
        }
    }

    private suspend fun maybeNotifyOperatorAlert(
        now: Instant,
        botState: BotStateSnapshot,
        localHealth: EngineHealthSnapshot,
        topCandidate: String?,
    ) {
        if (!config.telegramAlertsEnabled) return
        val alertState = when {
            botState.effectiveState == BotEffectiveState.SAFE_MODE || botState.syncHealth == SyncHealth.BROKEN || localHealth.status == HealthStatus.CRITICAL -> "critical"
            botState.effectiveState == BotEffectiveState.DEGRADED || botState.syncHealth == SyncHealth.DEGRADED || localHealth.status == HealthStatus.WARNING -> "degraded"
            else -> "healthy"
        }
        val previous = lastOperatorAlertStateKey
        if (previous == alertState) return
        val candidateText = topCandidate?.takeIf { it.isNotBlank() && it != "-" } ?: "none"
        val botName = config.controlPlane.botId.value.uppercase()
        val issueText = repository.state.value.healthSummary
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("Regime ", ignoreCase = true) }
            .firstOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?: "ada gangguan di engine"
        val candidateLine = if (candidateText == "none") {
            "Belum ada kandidat yang layak."
        } else {
            "Lagi mantau: $candidateText"
        }
        val message = when (alertState) {
            "critical" -> """
                🚨 $botName lagi bermasalah
                $candidateLine
                Intinya: $issueText
            """.trimIndent()

            "degraded" -> """
                ⚠️ $botName lagi kurang fit
                $candidateLine
                Catatan: $issueText
            """.trimIndent()

            else -> """
                ✅ $botName udah aman lagi
                $candidateLine
                Update: ${formatUpdatedLabel(now)}
            """.trimIndent()
        }
        telegramAlertNotifier.send(message)
        lastOperatorAlertStateKey = alertState
    }

    private fun buildDashboardState(
        now: Instant,
        jakartaDate: LocalDate,
        botState: BotStateSnapshot,
        peerBotStates: Map<String, BotStateSnapshot?>,
        lease: EngineLeaseSnapshot?,
        devices: List<DeviceDescriptor>,
        localHealth: EngineHealthSnapshot,
        dailyRisk: DailyRiskSnapshot?,
        equityHistory: List<com.kibot.shared.models.DailyEquityHistoryPoint>,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        strategyCycle: com.kibot.core.StrategyCycleResult?,
        weeklyReview: com.kibot.shared.models.WeeklyLearningSummary?,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        supportEval: com.kibot.aisupport.GeminiSupportEvaluation?,
        healthDecisionSummary: String,
    ): com.kibot.macengine.state.MacDashboardState {
        val heartbeatInstant = botState.lastHeartbeatAt ?: lease?.lastHeartbeatAt
        val filteredRadarPairs = buildDisplayRadarPairs(
            strategyCycle = strategyCycle,
            botState = botState,
        )
        val targetPursuit = strategyCycle?.let {
            dailyTargetPursuitBrain.evaluate(
                cycle = it,
                adaptiveAiPolicy = cachedAdaptiveAiPolicy,
                now = now,
            )
        }
        val topCandidate = preferredDisplayPair(
            primary = strategyCycle?.topCandidate?.value ?: strategyCycle?.selectedSignal?.pairId?.value,
            fallback = filteredRadarPairs.firstOrNull(),
        )
        val portfolioValue = estimatePortfolioValue(balances, marketQuotes).toDoubleOrZero()
            .takeIf { it > 0.0 }
            ?: dailyRisk?.currentEquityIdr?.toDoubleOrZero()
            ?: parseMonetaryLabel(repository.state.value.portfolioValueIdr)
            ?: 0.0
        val manualResetBaseline = resolvePnlResetBaseline(jakartaDate)
        val freeIdr = balances
            .firstOrNull { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            ?.let { it.free.toDoubleOrZero() + it.locked.toDoubleOrZero() }
            ?.coerceAtLeast(0.0)
            ?: 0.0
        val pnlToday = manualResetBaseline?.let { portfolioValue - it }
            ?: dailyRisk?.let {
                it.realizedPnlIdr.toDoubleOrZero() + it.unrealizedPnlIdr.toDoubleOrZero()
            }
            ?: 0.0
        val openingEquity = manualResetBaseline?.takeIf { it > 0.0 }
            ?: (portfolioValue - pnlToday).takeIf { it > 0.0 }
        val pnlTodayPctLabel = openingEquity
            ?.let { formatSignedPercent(pnlToday / it) }
            ?: "+0.0%"
        val avgEntryByPair = mutableMapOf<String, Pair<Double, Double>>() // pair -> qty, avg entry
        recentOrders.sortedBy { it.updatedAt }.forEach { order ->
            val pair = order.pairId.value.lowercase()
            val quantity = max(order.executedQuantity.toDoubleOrZero(), order.originalQuantity.toDoubleOrZero())
            val price = order.price.toDoubleOrZero().coerceAtLeast(0.0)
            if (quantity <= 0.0 || price <= 0.0) return@forEach
            when (order.side.name.uppercase()) {
                "BUY" -> {
                    val (prevQty, prevAvg) = avgEntryByPair[pair] ?: (0.0 to price)
                    val nextQty = prevQty + quantity
                    val nextAvg = if (nextQty > 0.0) ((prevQty * prevAvg) + (quantity * price)) / nextQty else price
                    avgEntryByPair[pair] = nextQty to nextAvg
                }
                "SELL" -> {
                    val (heldQty, avgEntry) = avgEntryByPair[pair] ?: (0.0 to 0.0)
                    val nextQty = (heldQty - quantity).coerceAtLeast(0.0)
                    if (avgEntry > 0.0) {
                        avgEntryByPair[pair] = nextQty to avgEntry
                    }
                }
            }
        }
        val heldAssets = balances
            .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val assetValueIdr = balance.totalValueInIdr?.toDoubleOrZero()
                    ?: quoteAssetReferencePrice(balance.asset, marketQuotes)?.let { it * quantity }
                    ?: 0.0
                if (assetValueIdr < dustUiHideMinValueIdr) return@mapNotNull null
                "${balance.asset.uppercase()}: ${formatDecimal(quantity, 6)}"
            }
        val holdingsDetailed = balances
            .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            .mapNotNull { balance ->
                val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                if (quantity <= 0.0) return@mapNotNull null
                val assetCode = balance.asset.uppercase()
                val assetValueIdr = balance.totalValueInIdr?.toDoubleOrZero()
                    ?: quoteAssetReferencePrice(balance.asset, marketQuotes)?.let { it * quantity }
                    ?: 0.0
                if (assetValueIdr < dustUiHideMinValueIdr) return@mapNotNull null
                val pairKey = "${balance.asset.lowercase()}_${referenceQuoteAsset()}".lowercase()
                val avgEntry = avgEntryByPair[pairKey]?.second ?: 0.0
                val currentPrice = quoteAssetReferencePrice(balance.asset, marketQuotes)?.takeIf { it > 0.0 } ?: 0.0
                val pnlIdr = if (avgEntry > 0.0 && currentPrice > 0.0) {
                    (currentPrice - avgEntry) * quantity
                } else 0.0
                val pnlPct = if (avgEntry > 0.0 && currentPrice > 0.0) {
                    ((currentPrice - avgEntry) / avgEntry) * 100.0
                } else {
                    0.0
                }
                com.kibot.macengine.state.MacHoldingDetail(
                    assetCode = assetCode,
                    assetLabel = displayAssetLabel(balance.asset),
                    quantityLabel = "${formatDecimal(quantity, 8)} $assetCode",
                    valueIdrLabel = formatMonetary(assetValueIdr),
                    entryPriceLabel = formatMonetary(avgEntry),
                    currentPriceLabel = formatMonetary(currentPrice),
                    pnlIdrLabel = formatSignedMonetary(pnlIdr),
                    pnlPctLabel = formatSignedPercent(pnlPct / 100.0),
                )
            }
            .sortedByDescending { detail -> parseMonetaryLabel(detail.valueIdrLabel) ?: 0.0 }
        val scanUniverseCount = marketQuotes.size
        val localExposureByPair = buildMap {
            balances
                .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
                .forEach { balance ->
                    val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
                    if (quantity <= 0.0) return@forEach
                    val pairKey = "${balance.asset.lowercase()}_${referenceQuoteAsset()}".lowercase()
                    val quote = marketQuotes.firstOrNull { it.pairId.value.equals(pairKey, ignoreCase = true) }
                    val currentBid = quote?.bestBid?.toDoubleOrZero()?.takeIf { it > 0.0 }
                        ?: quote?.midPrice?.toDoubleOrZero()?.takeIf { it > 0.0 }
                        ?: 0.0
                    val notional = (currentBid * quantity).coerceAtLeast(0.0)
                    put(pairKey, notional)
                }
        }
        val trailingFloors = localAutonomyTrailingFloorByPair.values
            .filter { snapshot ->
                (localExposureByPair[snapshot.pair.value.lowercase()] ?: 0.0) >= dustUiHideMinValueIdr
            }
            .sortedBy { snapshot ->
                val denominator = snapshot.floorPrice.takeIf { it > 0.0 } ?: 0.0000001
                kotlin.math.abs((snapshot.currentBid - snapshot.floorPrice) / denominator)
            }
            .map { snapshot ->
                val dropPctFromPeak = if (snapshot.peakPrice > 0.0) {
                    ((snapshot.peakPrice - snapshot.currentBid) / snapshot.peakPrice) * 100.0
                } else {
                    0.0
                }
                com.kibot.macengine.state.MacTrailingFloorDetail(
                    pair = snapshot.pair.value.lowercase(),
                    entryPriceLabel = formatMonetary(snapshot.entryPrice),
                    peakPriceLabel = formatMonetary(snapshot.peakPrice),
                    trailingFloorLabel = formatMonetary(snapshot.floorPrice),
                    currentBidLabel = formatMonetary(snapshot.currentBid),
                    dropFromPeakPctLabel = formatSignedPercent(-(dropPctFromPeak / 100.0)),
                    armed = snapshot.armed,
                )
            }
            .take(24)
        val displayHeartbeatLabel = when {
            botState.syncHealth == SyncHealth.HEALTHY && botState.effectiveState != BotEffectiveState.STOPPED -> "baru saja"
            botState.syncHealth == SyncHealth.DEGRADED && botState.effectiveState != BotEffectiveState.STOPPED -> "beberapa saat lalu"
            else -> heartbeatInstant?.let { formatAge(now, it) } ?: "Never"
        }
        fun localNodeStatus(): String = when {
            botState.desiredState == BotDesiredState.OFF || botState.effectiveState == BotEffectiveState.STOPPED -> "offline"
            botState.syncHealth == SyncHealth.BROKEN || botState.effectiveState == BotEffectiveState.DEGRADED -> "degraded"
            else -> "online"
        }
        fun peerNodeStatus(peerBotId: String): String {
            if (config.controlPlane.botId.value.equals(peerBotId, ignoreCase = true)) return localNodeStatus()
            val peerState = peerBotStates[peerBotId.lowercase()] ?: return "offline"
            val udpSeenAt = lastTrinityHeartbeatByBotId[peerBotId.lowercase()]
            val udpAgeMs = udpSeenAt?.let { (now - it).inWholeMilliseconds }
            val heartbeatAt = peerState.lastHeartbeatAt ?: return when {
                peerState.desiredState == BotDesiredState.OFF || peerState.effectiveState == BotEffectiveState.STOPPED -> "offline"
                udpAgeMs != null && udpAgeMs <= config.leadLagUdpHeartbeatTimeoutMillis -> "online"
                peerState.syncHealth == SyncHealth.BROKEN || peerState.effectiveState == BotEffectiveState.SAFE_MODE -> "degraded"
                else -> "online"
            }
            val ageMs = (now - heartbeatAt).inWholeMilliseconds
            return when {
                peerState.desiredState == BotDesiredState.OFF || peerState.effectiveState == BotEffectiveState.STOPPED -> "offline"
                udpAgeMs != null && udpAgeMs <= config.leadLagUdpHeartbeatTimeoutMillis -> "online"
                peerState.syncHealth == SyncHealth.BROKEN || peerState.effectiveState == BotEffectiveState.SAFE_MODE -> "degraded"
                ageMs <= 180_000L -> "online"
                else -> "degraded"
            }
        }
        val remoteLeaseConflict = lease?.conflictDetected == true && lease.currentHolder != config.device.deviceId
        val statusMessage = when {
            botState.effectiveState == BotEffectiveState.SAFE_MODE ->
                "Safe mode aktif. Tunggu status trade dan data exchange benar-benar bersih."
            localHealth.status == HealthStatus.CRITICAL ->
                "Server Oracle lagi bermasalah: ${localHealth.warnings.firstOrNull().orEmpty()}".trim()
            targetPursuit != null && targetPursuit.active && topCandidate != "-" ->
                "${targetPursuit.phase} • PnL 1D $pnlTodayPctLabel • urgency ${formatDecimal(targetPursuit.urgency * 100.0, 0)}% • fokus entry cepat $topCandidate."
            holdingsDetailed.isNotEmpty() && topCandidate != "-" ->
                "Server pegang ${holdingsDetailed.size} aset dan fokus cari entry baru di $topCandidate."
            topCandidate != "-" && scanUniverseCount > 0 ->
                "Server scan $scanUniverseCount pair dan fokus entry breakout $topCandidate."
            scanUniverseCount > 0 ->
                "Server scan $scanUniverseCount pair dan cari momentum yang layak."
            else -> "Server Oracle lagi sinkron dan pantau market."
        }
        val avgBuyByPair = mutableMapOf<String, Pair<Double, Double>>() // pair -> held qty, avg buy
        val recentOrderCards = recentOrders
            .sortedBy { it.updatedAt }
            .map { order ->
                val pair = order.pairId.value.lowercase()
                val quantity = max(order.executedQuantity.toDoubleOrZero(), order.originalQuantity.toDoubleOrZero())
                val price = order.price.toDoubleOrZero().coerceAtLeast(0.0)
                val side = order.side.name.uppercase()
                var pnlIdrLabel = ""
                var pnlPctLabel = ""
                val detail = when {
                    quantity <= 0.0 || price <= 0.0 -> "${formatDecimal(quantity, 8)} @ ${formatMonetary(price)}"
                    side == "BUY" -> {
                        val (prevQty, prevAvg) = avgBuyByPair[pair] ?: (0.0 to price)
                        val nextQty = prevQty + quantity
                        val nextAvg = if (nextQty > 0.0) ((prevQty * prevAvg) + (quantity * price)) / nextQty else price
                        avgBuyByPair[pair] = nextQty to nextAvg
                        "${formatDecimal(quantity, 8)} @ ${formatMonetary(price)}"
                    }
                    side == "SELL" -> {
                        val (heldQty, avgBuy) = avgBuyByPair[pair] ?: (0.0 to 0.0)
                        val estimatedPnl = if (avgBuy > 0.0) (price - avgBuy) * quantity else 0.0
                        val outcome = when {
                            avgBuy <= 0.0 -> "PnL n/a"
                            estimatedPnl >= 0.0 -> {
                                pnlIdrLabel = formatSignedMonetary(estimatedPnl)
                                pnlPctLabel = if (avgBuy > 0.0 && quantity > 0.0) formatSignedPercent((estimatedPnl / (avgBuy * quantity)) * 100.0) else "+0.0%"
                                "Untung ${formatSignedMonetary(estimatedPnl)}"
                            }
                            else -> {
                                pnlIdrLabel = formatSignedMonetary(estimatedPnl)
                                pnlPctLabel = if (avgBuy > 0.0 && quantity > 0.0) formatSignedPercent((estimatedPnl / (avgBuy * quantity)) * 100.0) else "+0.0%"
                                "Rugi ${formatSignedMonetary(estimatedPnl)}"
                            }
                        }
                        val nextHeldQty = (heldQty - quantity).coerceAtLeast(0.0)
                        if (avgBuy > 0.0) {
                            avgBuyByPair[pair] = nextHeldQty to avgBuy
                        }
                        "${formatDecimal(quantity, 8)} @ ${formatMonetary(price)} • $outcome"
                    }
                    else -> "${formatDecimal(quantity, 8)} @ ${formatMonetary(price)}"
                }
                com.kibot.macengine.state.MacRecentOrder(
                    timestampEpochMs = order.updatedAt.toEpochMilliseconds(),
                    pair = pair,
                    side = order.side.name,
                    status = order.status.name,
                    detail = detail,
                    pnlIdrLabel = pnlIdrLabel,
                    pnlPctLabel = pnlPctLabel,
                )
            }
            .sortedByDescending { it.timestampEpochMs }
            .take(18)
        val aiProviderStatus = aiProviderStatusLoader.loadOrDefault(config.adaptiveAiPolicyPath)
        val aiSummaryLabel = resolveAiSummaryLabel(
            loadedStatus = aiProviderStatus,
            aiSupportEvaluation = supportEval,
        )
        val liveTimeline = buildLiveTimeline(
            now = now,
            existingTimeline = repository.state.value.liveTimeline,
            botState = botState,
            topCandidate = topCandidate,
            holdingsDetailed = holdingsDetailed,
            scanUniverseCount = scanUniverseCount,
            healthSummary = healthDecisionSummary,
            recentOrders = recentOrderCards,
            targetPursuit = targetPursuit,
            aiProviderSummary = aiSummaryLabel,
        )
        val weeklyBaseline = resolveReturnBaseline(
            history = equityHistory,
            currentDate = jakartaDate,
            rangeStart = startOfWeek(jakartaDate),
            fallbackEquity = manualResetBaseline ?: portfolioValue,
        )
        val monthlyBaseline = resolveReturnBaseline(
            history = equityHistory,
            currentDate = jakartaDate,
            rangeStart = LocalDate(jakartaDate.year, jakartaDate.month, 1),
            fallbackEquity = manualResetBaseline ?: portfolioValue,
        )
        val hasReliablePerformanceHistory =
            holdingsDetailed.isNotEmpty() ||
                recentOrders.any { it.status == com.kibot.shared.models.OrderStatus.FILLED } ||
                equityHistory.size >= 3
        val safeFallbackBaseline = manualResetBaseline ?: portfolioValue
        val return7dBaseline = if (hasReliablePerformanceHistory) {
            manualResetBaseline ?: weeklyBaseline
        } else {
            safeFallbackBaseline
        }
        val return7d = portfolioValue - return7dBaseline
        val return7dPct = if (return7dBaseline > 0.0) return7d / return7dBaseline else 0.0
        val monthlyResetBaseline = if (hasReliablePerformanceHistory) {
            manualResetBaseline ?: resolveMonthlyReturnBaseline(
                currentDate = jakartaDate,
                currentEquity = portfolioValue,
                fallbackEquity = monthlyBaseline,
            )
        } else {
            safeFallbackBaseline
        }
        val return30d = portfolioValue - monthlyResetBaseline
        val return30dPct = if (monthlyResetBaseline > 0.0) return30d / monthlyResetBaseline else 0.0
        
        // Calculate cumulative return since bot started (using oldest equity history or manual reset baseline)
        val cumulativeBaseline = if (hasReliablePerformanceHistory) {
            manualResetBaseline ?: equityHistory.lastOrNull()?.currentEquityIdr?.toDoubleOrZero() ?: portfolioValue
        } else {
            safeFallbackBaseline
        }
        val cumulativeReturn = portfolioValue - cumulativeBaseline
        val cumulativeReturnPct = if (cumulativeBaseline > 0.0) cumulativeReturn / cumulativeBaseline else 0.0
        
        return com.kibot.macengine.state.MacDashboardState(
            isBotRunning = botState.effectiveState != BotEffectiveState.STOPPED,
            effectiveState = botState.effectiveState,
            operatingMode = strategyCycle?.modeSnapshot?.mode?.name ?: botState.operatingMode.name,
            edgeConfidence = strategyCycle?.modeSnapshot?.edgeConfidence?.name ?: botState.edgeConfidence.name,
            marketRegime = strategyCycle?.marketSnapshot?.regime?.name ?: botState.marketRegime.name,
            topCandidate = topCandidate,
            radarPairs = filteredRadarPairs,
            scanUniverseCount = scanUniverseCount,
            releaseLabel = if (config.releaseLabel.startsWith("#")) config.releaseLabel else "#${config.releaseLabel}",
            liveExecutionEnabled = config.enableLiveExecution,
            portfolioValueIdr = formatMonetary(portfolioValue),
            freeIdrLabel = formatMonetary(freeIdr),
            totalValueIdr = formatMonetary(portfolioValue),
            referenceQuoteAssetPriceIdr = quoteAssetToIdrPrice(referenceQuoteAsset(), marketQuotes),
            pnlTodayIdr = formatSignedMonetary(pnlToday),
            pnlTodayPctLabel = pnlTodayPctLabel,
            return7dIdr = formatSignedMonetary(return7d),
            return7dPctLabel = formatSignedPercent(return7dPct),
            return30dIdr = formatSignedMonetary(return30d),
            return30dPctLabel = formatSignedPercent(return30dPct),
            targetPursuitLabel = targetPursuit?.phase ?: "TRACKING",
            aiProviderSummary = aiSummaryLabel,
            syncPathLabel = "Live Server",
            activeEngine = "Oracle Cloud Server",
            standbyEngine = "View Only",
            syncHealth = botState.syncHealth.name,
            leaseTerm = lease?.term?.value ?: botState.currentTerm.value,
            healthSummary = if (botState.effectiveState == BotEffectiveState.SAFE_MODE) {
                botState.safeModeReason ?: "Safe mode active. Manual review is required."
            } else {
                listOfNotNull(
                    healthDecisionSummary.takeIf { it.isNotBlank() },
                    targetPursuit?.takeIf { it.active }?.let {
                        "${it.phase} ${formatDecimal(it.currentProfitPct, 2)}% / 25.00% • urgency ${formatDecimal(it.urgency * 100.0, 0)}% • slot +${it.extraSlots}"
                    },
                    aiSummaryLabel.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
            },
            weeklyLearningSummary = weeklyReview?.let {
                "Week ${it.periodStart} - ${it.periodEnd} • PF ${formatDecimal(it.profitFactor, 2)} • MDD ${formatSignedPercent(it.maximumDrawdownPct)} • no-trade ${(it.noTradeQualityScore * 100).toInt()}% • util ${(it.productiveUtilizationPct * 100).toInt()}%"
            } ?: "Belum ada review mingguan.",
            weeklyAdaptationSummary = weeklyReview?.adaptationPlan?.notes?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
                ?: "Adaptasi mingguan belum tersedia.",
            lastHeartbeatLabel = displayHeartbeatLabel,
            lastUpdatedLabel = formatUpdatedLabel(now),
            statusMessage = statusMessage,
            lastUpdatedEpochMs = now.toEpochMilliseconds(),
            heldAssets = heldAssets,
            holdingsDetailed = holdingsDetailed,
            exchangePingMs = localHealth.feedLatencyMs?.let { "${it}ms" } ?: "--",
            exchangePingValueMs = localHealth.feedLatencyMs,
            kidaxNodeStatus = peerNodeStatus("kidax"),
            kibotNodeStatus = peerNodeStatus("kibot"),
            kinanceNodeStatus = peerNodeStatus("kinance"),
            serverLocation = "Oracle Cloud (24/7)",
            serverUptime = repository.state.value.serverUptime,
            liveTimeline = liveTimeline,
            recentOrders = recentOrderCards,
            trailingFloors = trailingFloors,
        )
    }

    private fun relevantFillPairs(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        persistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult?,
    ): List<com.kibot.shared.models.PairId> {
        val quotePairs = marketQuotes.map { it.pairId }.toSet()
        return buildSet {
            openOrders.mapTo(this) { it.pairId }
            persistedOrders.mapTo(this) { it.pairId }
            cycle?.selectedSignal?.pairId?.let(::add)
            cycle?.deploymentPlan?.candidates?.take(4)?.mapTo(this) { it.pairId }
            balances
                .filterNot { it.asset.equals("idr", ignoreCase = true) }
                .forEach { balance ->
                    listOf("idr", "usdt", "btc", "eth")
                        .asSequence()
                        .map { quoteAsset -> com.kibot.shared.models.PairId("${balance.asset.lowercase()}_$quoteAsset") }
                        .firstOrNull { it in quotePairs }
                        ?.let(::add)
                }
        }.take(4)
    }

    private fun mergeRecentOrders(
        base: List<com.kibot.shared.models.OrderSnapshot>,
        updates: List<com.kibot.shared.models.OrderSnapshot>,
    ): List<com.kibot.shared.models.OrderSnapshot> {
        if (updates.isEmpty()) return base
        val merged = linkedMapOf<String, com.kibot.shared.models.OrderSnapshot>()
        (base + updates)
            .sortedByDescending { it.updatedAt }
            .forEach { merged[it.clientOrderId.value] = it }
        return merged.values.toList()
    }

    private fun estimatePortfolioValue(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): DecimalValue {
        val referenceQuoteIdr = quoteAssetToIdrPrice(referenceQuoteAsset(), marketQuotes) ?: 1.0
        val total = balances.sumOf { balance ->
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            val totalValueInIdr = balance.totalValueInIdr
            when {
                quantity <= 0.0 -> 0.0
                balance.asset.equals(referenceQuoteAsset(), ignoreCase = true) -> quantity * referenceQuoteIdr
                totalValueInIdr != null -> totalValueInIdr.toDoubleOrZero()
                else -> (quoteAssetReferencePrice(balance.asset, marketQuotes) ?: 0.0) * quantity
            }
        }
        return DecimalValue.fromDouble(total.coerceAtLeast(0.0))
    }

    private fun deriveDailyRiskSnapshot(
        now: Instant,
        previous: DailyRiskSnapshot?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): DailyRiskSnapshot? {
        if (balances.isEmpty() || marketQuotes.isEmpty()) return previous
        val referenceQuoteIdr = quoteAssetToIdrPrice(referenceQuoteAsset(), marketQuotes) ?: 1.0
        val jakartaDate = now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
        val filledOrderStatuses = setOf(
            com.kibot.shared.models.OrderStatus.FILLED,
            com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED,
        )
        val recentFilledOrders = recentOrders.filter { order ->
            val orderDate = order.updatedAt.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
            orderDate == jakartaDate && order.status in filledOrderStatuses
        }
        val dailyTradeCount = recentFilledOrders.size
        val dailyRoundTripCount = recentFilledOrders.count { it.side == com.kibot.shared.models.OrderSide.SELL }
        val currentEquity = balances.sumOf { balance ->
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            when {
                quantity <= 0.0 -> 0.0
                balance.asset.equals(referenceQuoteAsset(), ignoreCase = true) -> quantity * referenceQuoteIdr
                else -> quantity * (quoteAssetReferencePrice(balance.asset, marketQuotes) ?: 0.0)
            }
        }
        if (currentEquity <= 0.0) return previous

        val openingEquity = previous?.openingEquityIdr?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: currentEquity
        val totalPnl = currentEquity - openingEquity
        val hasTrackedNonIdrHolding = balances.any { balance ->
            if (balance.asset.equals(referenceQuoteAsset(), ignoreCase = true)) return@any false
            val quantity = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (quantity <= 0.0) return@any false
            val value = quantity * (quoteAssetReferencePrice(balance.asset, marketQuotes) ?: 0.0)
            value >= minMeaningfulTrackedValue()
        }
        val realizedPnl = if (hasTrackedNonIdrHolding) 0.0 else totalPnl
        val unrealizedPnl = totalPnl - realizedPnl
        val highWatermark = max(
            previous?.highWatermarkEquityIdr?.toDoubleOrZero() ?: openingEquity,
            currentEquity,
        )
        val profitableRange = (highWatermark - openingEquity).coerceAtLeast(0.0)
        val givebackPct = when {
            profitableRange <= 0.0 || currentEquity >= highWatermark -> 0.0
            else -> ((highWatermark - currentEquity) / profitableRange).coerceIn(0.0, 1.0)
        }
        val hardLimitPct = previous?.hardDailyLossLimitPct ?: 0.05
        val drawdownPct = if (openingEquity > 0.0 && currentEquity < openingEquity) {
            ((openingEquity - currentEquity) / openingEquity).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return DailyRiskSnapshot(
            openingEquityIdr = DecimalValue.fromDouble(openingEquity),
            currentEquityIdr = DecimalValue.fromDouble(currentEquity),
            realizedPnlIdr = DecimalValue.fromDouble(realizedPnl),
            unrealizedPnlIdr = DecimalValue.fromDouble(unrealizedPnl),
            tradeCount24h = dailyTradeCount,
            drawdownPct = drawdownPct,
            hardDailyLossLimitPct = hardLimitPct,
            hardStopTriggered = previous?.hardStopTriggered == true || drawdownPct >= hardLimitPct,
            rebasePending = previous?.rebasePending == true,
            riskLadderLevel = previous?.riskLadderLevel ?: com.kibot.shared.models.RiskLadderLevel.NORMAL,
            weeklyDrawdownPct = previous?.weeklyDrawdownPct ?: 0.0,
            lossStreakCount = previous?.lossStreakCount ?: 0,
            performanceDecayDetected = previous?.performanceDecayDetected == true,
            highWatermarkEquityIdr = DecimalValue.fromDouble(highWatermark),
            givebackPct = givebackPct,
            profitProtectionStatus = previous?.profitProtectionStatus ?: com.kibot.shared.models.ProfitProtectionStatus.INACTIVE,
            dailyTradeCount = dailyTradeCount,
            dailyRoundTripCount = dailyRoundTripCount,
        )
    }

    private fun quoteAssetReferencePrice(
        asset: String,
        quotes: List<com.kibot.shared.models.MarketQuote>,
    ): Double? {
        val normalizedAsset = asset.lowercase()
        val referenceQuoteAsset = referenceQuoteAsset()
        if (normalizedAsset.equals(referenceQuoteAsset, ignoreCase = true)) return 1.0
        val direct = quotes.firstOrNull {
            it.pairId.value.equals("${normalizedAsset}_$referenceQuoteAsset", ignoreCase = true)
        }
        if (direct != null) return direct.midPrice.toDoubleOrZero()
        if (!referenceQuoteAsset.equals("idr", ignoreCase = true)) {
            val directIdr = quotes.firstOrNull { it.pairId.value.equals("${normalizedAsset}_idr", ignoreCase = true) }
            val referenceIdr = quotes.firstOrNull { it.pairId.value.equals("${referenceQuoteAsset}_idr", ignoreCase = true) }
            if (directIdr != null && referenceIdr != null) {
                val directIdrPrice = directIdr.midPrice.toDoubleOrZero()
                val referenceIdrPrice = referenceIdr.midPrice.toDoubleOrZero()
                if (directIdrPrice > 0.0 && referenceIdrPrice > 0.0) return directIdrPrice / referenceIdrPrice
            }
        }
        return null
    }

    private fun quoteAssetToIdrPrice(
        asset: String,
        quotes: List<com.kibot.shared.models.MarketQuote>,
    ): Double? {
        val normalizedAsset = asset.lowercase()
        if (normalizedAsset == "idr") return 1.0
        val directIdr = quotes.firstOrNull { it.pairId.value.equals("${normalizedAsset}_idr", ignoreCase = true) }
        if (directIdr != null) return directIdr.midPrice.toDoubleOrZero()
        val refQuote = referenceQuoteAsset()
        if (normalizedAsset == refQuote.lowercase()) {
            val refIdr = quotes.firstOrNull { it.pairId.value.equals("${refQuote}_idr", ignoreCase = true) }
            if (refIdr != null) return refIdr.midPrice.toDoubleOrZero()
            val crossRate = listOf("btc", "eth", "xrp", "sol", "bnb", "trx", "doge")
                .asSequence()
                .mapNotNull { anchor ->
                    val refPair = quotes.firstOrNull { it.pairId.value.equals("${anchor}_$refQuote", ignoreCase = true) }
                    val idrPair = quotes.firstOrNull { it.pairId.value.equals("${anchor}_idr", ignoreCase = true) }
                    val refMid = refPair?.midPrice?.toDoubleOrZero()?.takeIf { it > 0.0 }
                    val idrMid = idrPair?.midPrice?.toDoubleOrZero()?.takeIf { it > 0.0 }
                    if (refMid != null && idrMid != null) idrMid / refMid else null
                }
                .firstOrNull { it > 0.0 }
            return crossRate ?: 16_000.0
        }
        val directRef = quotes.firstOrNull { it.pairId.value.equals("${normalizedAsset}_$refQuote", ignoreCase = true) }
        val refIdr = quotes.firstOrNull { it.pairId.value.equals("${refQuote}_idr", ignoreCase = true) }
        return if (directRef != null && refIdr != null) {
            directRef.midPrice.toDoubleOrZero() * refIdr.midPrice.toDoubleOrZero()
        } else null
    }

    private fun jakartaNowDate(now: Instant): kotlinx.datetime.LocalDate {
        return now.toLocalDateTime(TimeZone.of("Asia/Jakarta")).date
    }

    private fun formatAge(now: Instant, observedAt: Instant): String {
        val ageSeconds = ((now - observedAt).inWholeSeconds).coerceAtLeast(0)
        return when {
            ageSeconds < 60 -> "${ageSeconds}s ago"
            ageSeconds < 3_600 -> "${ageSeconds / 60}m ago"
            else -> "${ageSeconds / 3_600}h ago"
        }
    }

    private fun formatUpdatedLabel(now: Instant): String {
        val local = now.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$hh:$mm WIB"
    }

    private fun formatJktTime(instant: Instant): String {
        val local = instant.toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$hh:$mm WIB"
    }

    private fun formatIdr(value: Double): String {
        val locale = Locale.Builder()
            .setLanguage("id")
            .setRegion("ID")
            .build()
        return NumberFormat.getCurrencyInstance(locale).apply {
            maximumFractionDigits = 0
        }.format(value)
    }

    private fun formatMonetary(value: Double): String {
        return if (referenceQuoteAsset().equals("idr", ignoreCase = true)) {
            formatIdr(value)
        } else {
            "${formatDecimal(value, 2)} ${referenceQuoteAsset().uppercase()}"
        }
    }

    private fun formatSignedMonetary(value: Double): String {
        if (kotlin.math.abs(value) < 0.0005) return "+${formatMonetary(0.0)}"
        val prefix = if (value >= 0.0) "+" else "-"
        return prefix + formatMonetary(kotlin.math.abs(value))
    }

    private fun parseMonetaryLabel(label: String): Double? {
        return if (referenceQuoteAsset().equals("idr", ignoreCase = true)) {
            label.parseRupiahLabel()
        } else {
            label.substringBefore(" ").replace(",", "").toDoubleOrNull()
        }
    }

    private fun referenceQuoteAsset(): String = when (config.exchangeKind) {
        ExchangeKind.INDODAX -> "idr"
        ExchangeKind.BINANCE_SPOT -> config.binanceClientConfig.primaryQuoteAsset.lowercase()
    }

    private fun minMeaningfulTrackedValue(): Double = when (config.exchangeKind) {
        ExchangeKind.INDODAX -> 1_000.0
        ExchangeKind.BINANCE_SPOT -> 1.0
    }

    private fun formatSignedPercent(value: Double): String {
        val pct = value * 100.0
        val prefix = if (pct >= 0.0) "+" else "-"
        return "$prefix${formatDecimal(kotlin.math.abs(pct), 1)}%"
    }

    private fun formatDecimal(value: Double, digits: Int): String = "%.${digits}f".format(java.util.Locale.US, value)

    private fun buildDisplayRadarPairs(
        strategyCycle: com.kibot.core.StrategyCycleResult?,
        botState: BotStateSnapshot,
    ): List<String> {
        return buildList {
            activeLeadLagCallout?.pairId?.value?.let(::add)
            strategyCycle?.selectedSignal?.pairId?.value?.let(::add)
            strategyCycle?.topCandidate?.value?.let(::add)
            strategyCycle?.deploymentPlan?.candidates?.mapTo(this) { it.pairId.value }
        }.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { hiddenStablePairs.contains(it.lowercase()) }
            .distinct()
            .take(10)
            .toList()
    }

    private fun preferredDisplayPair(primary: String?, fallback: String?): String {
        return primary?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: "-"
    }

    private fun buildLiveTimeline(
        now: Instant,
        existingTimeline: List<com.kibot.macengine.state.MacTimelineEntry>,
        botState: BotStateSnapshot,
        topCandidate: String,
        holdingsDetailed: List<com.kibot.macengine.state.MacHoldingDetail>,
        scanUniverseCount: Int,
        healthSummary: String,
        recentOrders: List<com.kibot.macengine.state.MacRecentOrder>,
        targetPursuit: DailyTargetPursuit?,
        aiProviderSummary: String,
    ): List<com.kibot.macengine.state.MacTimelineEntry> {
        val orderEntries = recentOrders.mapNotNull(::toTimelineEntry)
        val preservedOperatorEntries = existingTimeline
            .filter { shouldExposeToLiveTimeline(it.category, it.message) }
            .filter { now.toEpochMilliseconds() - it.timestampEpochMs <= 2 * 60 * 60 * 1000L }
        return (orderEntries + preservedOperatorEntries)
            .sortedByDescending { it.timestampEpochMs }
            .distinctBy { "${it.category}|${it.message}" }
            .take(12)
    }

    private fun buildSyntheticTimeline(
        now: Instant,
        botState: BotStateSnapshot,
        topCandidate: String,
        holdingsDetailed: List<com.kibot.macengine.state.MacHoldingDetail>,
        scanUniverseCount: Int,
        healthSummary: String,
        targetPursuit: DailyTargetPursuit?,
        aiProviderSummary: String,
    ): List<com.kibot.macengine.state.MacTimelineEntry> {
        val primaryMessage = when {
            botState.effectiveState == BotEffectiveState.SAFE_MODE ->
                "Server masuk safe mode dan tahan entry baru."
            targetPursuit?.overdriveAllowed == true && topCandidate != "-" ->
                "Target 25% sudah lewat. Bot masuk overdrive dan tetap buru lonjakan $topCandidate."
            targetPursuit?.active == true && topCandidate != "-" ->
                "Target 25% dikejar. Progress ${formatDecimal(targetPursuit.currentProfitPct, 2)}%, urgency ${formatDecimal(targetPursuit.urgency * 100.0, 0)}%, fokus entry cepat $topCandidate."
            holdingsDetailed.isNotEmpty() && topCandidate != "-" ->
                "Server pegang ${holdingsDetailed.size} aset dan awasi entry cepat $topCandidate."
            topCandidate != "-" ->
                "Server lagi bidik $topCandidate dari $scanUniverseCount pair."
            else ->
                "Server lagi sinkron dan scan market live."
        }
        val entries = mutableListOf(
            com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds(),
                category = "STATUS",
                message = primaryMessage,
            ),
        )
        if (topCandidate != "-") {
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 500L,
                category = if (targetPursuit?.overdriveAllowed == true) "ROTASI" else "TARGET",
                message = when {
                    targetPursuit?.overdriveAllowed == true ->
                        "Profit harian sudah lewat target, tapi $topCandidate sangat kuat. Bot tetap tekan winner sampai momentum patah."
                    targetPursuit?.active == true ->
                        "Fokus server sekarang $topCandidate. Entry ditembak lebih cepat karena target harian belum selesai."
                    else ->
                        "Fokus server sekarang $topCandidate. Entry akan ditembak kalau breakout lanjut dan biaya masih masuk akal."
                },
            )
        }
        if (holdingsDetailed.isNotEmpty()) {
            val watchedHoldings = holdingsDetailed
                .take(3)
                .joinToString(" • ") { it.assetCode }
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 750L,
                category = "HOLD",
                message = "Server sedang jaga ${holdingsDetailed.size} aset: $watchedHoldings.",
            )
        }
        if (healthSummary.isNotBlank()) {
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 1_000L,
                category = "HEALTH",
                message = healthSummary,
            )
        }
        if (aiProviderSummary.isNotBlank()) {
            entries += com.kibot.macengine.state.MacTimelineEntry(
                timestampEpochMs = now.toEpochMilliseconds() - 1_250L,
                category = "AI",
                message = aiProviderSummary,
            )
        }
        return entries
    }

    private fun toTimelineEntry(
        order: com.kibot.macengine.state.MacRecentOrder,
    ): com.kibot.macengine.state.MacTimelineEntry? {
        val status = order.status.uppercase()
        val message = when (status) {
            "FILLED" -> "${order.side} ${order.pair} fill ${order.detail}."
            "PARTIALLY_FILLED" -> "${order.side} ${order.pair} mulai fill ${order.detail}."
            "OPEN", "SUBMITTING" -> "Pasang ${order.side.lowercase()} ${order.pair} ${order.detail}."
            "CANCELED" -> "Order ${order.pair} dibatalkan karena setup berubah."
            else -> return null
        }
        return com.kibot.macengine.state.MacTimelineEntry(
            timestampEpochMs = order.timestampEpochMs,
            category = when (order.side.uppercase()) {
                "BUY" -> if (status == "FILLED" || status == "PARTIALLY_FILLED") "BUY" else "TARGET"
                "SELL" -> if (status == "FILLED" || status == "PARTIALLY_FILLED") "SELL" else "TARGET"
                else -> "SYNC"
            },
            message = message,
        )
    }

    private fun shouldExposeToLiveTimeline(category: String, message: String): Boolean {
        val normalizedCategory = category.uppercase()
        val normalizedMessage = message.lowercase()
        if (normalizedMessage.isBlank()) return false
        if (normalizedCategory !in importantTimelineCategories) return false
        if (
            normalizedCategory == "AUTH" ||
            normalizedMessage.contains("control-plane") ||
            normalizedMessage.contains("registered with control-plane") ||
            normalizedMessage.contains("registered to control plane") ||
            normalizedMessage.contains("device registered")
        ) {
            return false
        }
        if (
            normalizedCategory in setOf("ROTASI", "SCAN", "TARGET") &&
            hiddenStablePairs.any { normalizedMessage.contains(it) }
        ) {
            return false
        }
        return true
    }

    private fun resolveAiSummaryLabel(
        loadedStatus: AiProviderStatusSnapshot,
        aiSupportEvaluation: com.kibot.aisupport.GeminiSupportEvaluation?,
    ): String {
        val runtimeLabel = aiRuntimeProviderStatusLabel
        val runtimeAt = aiRuntimeProviderStatusAt
        if (runtimeLabel != null && runtimeAt != null) {
            val ageMs = (Clock.System.now().toEpochMilliseconds() - runtimeAt.toEpochMilliseconds()).coerceAtLeast(0L)
            if (ageMs <= 3_600_000L) {
                return runtimeLabel
            }
        }
        val loaded = loadedStatus.summaryLabel.trim()
        if (loaded.isNotBlank() && !loaded.equals("AI summary belum siap.", ignoreCase = true)) {
            return loaded
        }
        if (!config.enableExecutionAiAssist || aiSupportCoordinator == null) {
            return "AI OFFLINE"
        }
        val blockedReason = aiSupportEvaluation?.blockedReason
            ?.replace('_', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return when {
            aiSupportEvaluation?.usedNetwork == true -> "AI ONLINE (runtime assist)"
            blockedReason != null -> "AI LIMITED ($blockedReason)"
            else -> "AI ONLINE (standby)"
        }
    }

    private fun displayAssetLabel(asset: String): String = when (asset.lowercase()) {
        "idr" -> "Rupiah"
        "usdt" -> "Tether"
        "usdc" -> "USD Coin"
        "btc" -> "Bitcoin"
        "eth" -> "Ethereum"
        "xrp" -> "XRP"
        "trx" -> "Tron"
        "sol" -> "Solana"
        "doge" -> "Doge"
        else -> asset.uppercase()
    }

    private fun String.parseRupiahLabel(): Double? {
        val normalized = replace("Rp", "")
            .replace(".", "")
            .replace(",", ".")
            .replace(" ", "")
            .trim()
        return normalized.toDoubleOrNull()
    }

    private fun latencyLabel(latencyMs: Long?): String = when {
        latencyMs == null -> "--"
        else -> "${latencyMs}ms"
    }

    private fun activeLeadLagPriorityPair(now: Instant): com.kibot.shared.models.PairId? {
        val callout = activeLeadLagCallout ?: return null
        if (callout.expiresAt <= now) {
            activeLeadLagCallout = null
            return null
        }
        return callout.pairId
    }

    private fun refreshAdaptiveAiPolicy(now: Instant): AdaptiveAiPolicy? {
        val fetchedAt = adaptiveAiPolicyFetchedAt
        if (fetchedAt != null && (now - fetchedAt).inWholeSeconds < 60) {
            return cachedAdaptiveAiPolicy
        }
        adaptiveAiPolicyFetchedAt = now
        val loaded = runCatching { adaptiveAiPolicyLoader.loadOrNull(now) }
            .onFailure { logger.warn("Adaptive AI policy load failed: {}", it.message) }
            .getOrNull()
        val previousSignature = cachedAdaptiveAiPolicy?.successfulProviders?.sorted().orEmpty().joinToString(",")
        val nextSignature = loaded?.successfulProviders?.sorted().orEmpty().joinToString(",")
        if (loaded != null && loaded.isActive && nextSignature != previousSignature) {
            logger.info(
                "Adaptive AI policy loaded providers={} consensus={} path={}",
                loaded.successfulProviders.joinToString(","),
                formatDecimal(loaded.consensusStrength, 2),
                config.adaptiveAiPolicyPath,
            )
        }
        cachedAdaptiveAiPolicy = loaded
        return loaded
    }

    private fun mergeAiSupportHints(
        liveHints: List<com.kibot.shared.models.AiPairSupportHint>,
        adaptivePolicy: AdaptiveAiPolicy?,
    ): List<com.kibot.shared.models.AiPairSupportHint> {
        val adaptiveHints = adaptivePolicy?.pairHints.orEmpty()
        if (liveHints.isEmpty() && adaptiveHints.isEmpty()) return emptyList()
        val rankingScale = adaptivePolicy?.adjustments?.rankingBiasScale ?: 1.0
        val executionHints = adaptivePolicy?.executionHints ?: AdaptiveAiExecutionHints()
        return (liveHints + adaptiveHints)
            .groupBy { it.pairId }
            .map { (pairId, hints) ->
                val replacementHint = executionHints.replacementHints.firstOrNull { it.replacePair == pairId }
                val supportBonus = when {
                    replacementHint != null -> 0.025
                    executionHints.concentrationPair == pairId -> 0.03
                    pairId in executionHints.rotateNowPairs -> 0.02
                    pairId in executionHints.holdLongerPairs -> 0.015
                    else -> 0.0
                }
                val cautionBonus = when {
                    executionHints.replacementHints.any { it.cutPair == pairId } -> 0.04
                    pairId.belongsToAvoidFamily(executionHints.avoidPairFamilies) -> 0.03
                    else -> 0.0
                }
                val supportBias = (hints.sumOf { it.supportBias } + supportBonus).coerceIn(0.0, 0.08) * rankingScale
                val cautionBias = (hints.sumOf { it.cautionBias } + cautionBonus).coerceIn(0.0, 0.06)
                val latest = hints.maxByOrNull { it.generatedAt.toEpochMilliseconds() } ?: hints.first()
                latest.copy(
                    pairId = pairId,
                    supportBias = supportBias.coerceIn(0.0, 0.08),
                    cautionBias = cautionBias.coerceIn(0.0, 0.06),
                    rationale = hints.joinToString(" | ") { it.rationale }.take(240),
                )
            }
    }

    private fun leadLagSupportHints(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): List<com.kibot.shared.models.AiPairSupportHint> {
        val callout = activeLeadLagCallout ?: return emptyList()
        if (callout.expiresAt <= now) {
            activeLeadLagCallout = null
            return emptyList()
        }
        if (marketQuotes.none { it.pairId == callout.pairId }) return emptyList()
        return listOf(
            com.kibot.shared.models.AiPairSupportHint(
                pairId = callout.pairId,
                supportBias = 0.08,
                cautionBias = 0.0,
                cheapNominalWatch = false,
                rationale = "Lead-lag Kinance->KiDax: ${callout.pairId.value} diprioritaskan sebelum momentum lokal terlambat.",
                generatedAt = now,
            ),
        )
    }

    private suspend fun maybeDispatchLeadLagCallout(
        now: Instant,
        lease: EngineLeaseSnapshot,
        cycle: com.kibot.core.StrategyCycleResult,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        if (!config.leadLagSignalEnabled || config.exchangeKind != ExchangeKind.BINANCE_SPOT) return
        if (!lease.isHeldBy(config.device.deviceId, now)) return
        val targetBotId = config.leadLagTargetBotId ?: return
        refreshIndodaxFocusUniverse(now)
        refreshAListTunnelPairs(marketQuotes)
        seedDynamicVipFromPanopticon(now = now, marketQuotes = marketQuotes)
        val kidaxWatchPairs = kidaxActivePositionsByPair.keys.toSet()
        val emergencyHoldingTriggered = maybeDispatchKinanceEmergencyWarnings(
            now = now,
            marketQuotes = marketQuotes,
            targetBotId = targetBotId,
        )
        if (emergencyHoldingTriggered) return
        val holdingsFocusMode = if (kidaxWatchPairs.isNotEmpty()) {
            holdingsFocusToggle = true
            true
        } else {
            holdingsFocusToggle = false
            false
        }
        val candidates = cycle.entryExecutionPlans
            .ifEmpty { listOfNotNull(cycle.executionPlan) }
            .let { plans ->
                if (!holdingsFocusMode) plans
                else plans.filter { it.signal.pairId.value.lowercase() in kidaxWatchPairs }
            }
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val prioritizedCandidates = candidates.sortedByDescending { plan ->
            val pairKey = plan.signal.pairId.value.lowercase()
            val quote = quoteByPair[plan.signal.pairId]
            val watchBoost = if (pairKey in kidaxWatchPairs) 2.0 else 0.0
            val returnBoost = quote?.shortTermReturnPct?.coerceAtLeast(0.0)?.times(0.20) ?: 0.0
            val tradeBoost = quote?.recentTradeActivityScore?.times(0.35) ?: 0.0
            watchBoost + returnBoost + tradeBoost
        }
        var selectedSignalLabel = "MICRO_BREAKOUT"
        var selectedMsgType = "DETECTOR_HIT"
        val selected = prioritizedCandidates.firstOrNull { plan ->
            val quote = marketQuotes.firstOrNull { it.pairId == plan.signal.pairId } ?: return@firstOrNull false
            val coinClass = classifyPair(plan.signal.pairId)
            if (!isLeadLagClassEnabled(coinClass)) return@firstOrNull false
            val antiFakeoutPass = passesKinanceMicroBreakoutFilter(
                pairId = plan.signal.pairId,
                now = now,
            )
            val gradualUptrendPass = passesKinanceGradualUptrendFilter(
                pairId = plan.signal.pairId,
                now = now,
            )
            val instantAnomalyPass = passesKinanceInstantAnomalyFilter(
                pairId = plan.signal.pairId,
                quote = quote,
                now = now,
                marketQuotes = marketQuotes,
            )
            if (!antiFakeoutPass && !gradualUptrendPass && !instantAnomalyPass) return@firstOrNull false
            if (instantAnomalyPass) {
                selectedSignalLabel = "INSTANT_ANOMALY"
                selectedMsgType = "INSTANT_BUY_ANOMALY"
            } else {
                selectedSignalLabel = if (gradualUptrendPass && !antiFakeoutPass) "GRADUAL_UPTREND" else "MICRO_BREAKOUT"
                selectedMsgType = "DETECTOR_HIT"
            }
            val (minNet, minShort) = when (coinClass) {
                CoinClass.NAGA -> config.leadLagNagaMinExpectedNetPct to config.leadLagNagaMinShortTermReturnPct
                CoinClass.MID -> config.leadLagMidMinExpectedNetPct to config.leadLagMidMinShortTermReturnPct
                CoinClass.MICIN -> config.leadLagMicinMinExpectedNetPct to config.leadLagMicinMinShortTermReturnPct
            }
            val effectiveMinNet = if (instantAnomalyPass) (minNet * instantAnomalyExpectedNetRelaxFactor) else minNet
            val effectiveMinShort = if (instantAnomalyPass) (minShort * instantAnomalyShortReturnRelaxFactor) else minShort
            val effectiveMinConfidence = if (instantAnomalyPass) {
                (config.leadLagMinConfidence - instantAnomalyConfidenceRelax).coerceAtLeast(0.42)
            } else {
                config.leadLagMinConfidence
            }
            plan.signal.signalType != com.kibot.shared.models.StrategySignalType.EXIT &&
                plan.signal.confidence >= effectiveMinConfidence &&
                plan.expectedNetEdgePct >= effectiveMinNet &&
                quote.shortTermReturnPct >= effectiveMinShort &&
                quote.recentTradeActivityScore >= config.leadLagMinTradeActivityScore
        }
        val fallbackQuote = if (selected == null) {
            val orderedQuotes = if (kidaxWatchPairs.isEmpty()) {
                marketQuotes
            } else {
                marketQuotes.sortedByDescending { quote -> if (quote.pairId.value.lowercase() in kidaxWatchPairs) 1 else 0 }
            }
            val scopedQuotes = if (!holdingsFocusMode) {
                orderedQuotes
            } else {
                orderedQuotes.filter { it.pairId.value.lowercase() in kidaxWatchPairs }
            }
            scopedQuotes.firstOrNull { quote ->
                val coinClass = classifyPair(quote.pairId)
                val volumeIdr = estimateQuoteVolumeIdr(quote, marketQuotes)
                isLeadLagClassEnabled(coinClass) &&
                    quote.shortTermReturnPct >= 0.35 &&
                    quote.recentTradeActivityScore >= 0.30 &&
                    volumeIdr >= config.aListMinVolumeIdr
            }
        } else {
            null
        }
        if (selected == null && fallbackQuote == null) return
        val selectedPair = selected?.signal?.pairId ?: fallbackQuote!!.pairId
        val selectedExpectedNet = selected?.expectedNetEdgePct ?: maxOf(fallbackQuote?.shortTermReturnPct ?: 0.2, 0.2)
        val selectedConfidence = selected?.signal?.confidence ?: 0.45
        val pairKey = selectedPair.value.lowercase()
        val lastSentAt = leadLagSentAtByPair[pairKey]
        val signalCooldownMs = if (selectedMsgType == "INSTANT_BUY_ANOMALY") {
            aListInstantSignalCooldownMs
        } else {
            config.leadLagSignalCooldownMillis
        }
        if (lastSentAt != null && (now - lastSentAt).inWholeMilliseconds < signalCooldownMs) return
        val quote = marketQuotes.firstOrNull { it.pairId == selectedPair } ?: return
        val detectedAtMs = now.toEpochMilliseconds()
        val sentAtMs = detectedAtMs
        val traceId = "ll-${selectedPair.value.lowercase()}-$sentAtMs"
        val coinClass = classifyPair(selectedPair)
        val ttlMs = when (coinClass) {
            CoinClass.NAGA -> config.leadLagNagaSignalTtlMillis
            CoinClass.MID -> config.leadLagMidSignalTtlMillis
            CoinClass.MICIN -> config.leadLagMicinSignalTtlMillis
        }.coerceAtLeast(500L)
        val payload = LeadLagCalloutPayload(
            traceId = traceId,
            msgType = selectedMsgType,
            senderBotId = config.controlPlane.botId.value,
            pairId = selectedPair.value,
            trend = when (selectedSignalLabel) {
                "GRADUAL_UPTREND" -> "GRADUAL_UP"
                "INSTANT_ANOMALY" -> "ANOMALY_UP"
                else -> "UP"
            },
            detectedAtEpochMs = detectedAtMs,
            confidence = selectedConfidence,
            expectedNetPct = selectedExpectedNet,
            shortTermReturnPct = quote.shortTermReturnPct,
            mediumTermReturnPct = quote.mediumTermReturnPct,
            tradeActivityScore = quote.recentTradeActivityScore,
            forceRotation = true,
            sentAtEpochMs = sentAtMs,
            expiresAtEpochMs = sentAtMs + ttlMs,
        )
        markDynamicVip(
            pairId = selectedPair,
            now = now,
            reason = if (selectedMsgType == "INSTANT_BUY_ANOMALY") "kinance_instant_dispatch" else "kinance_dispatch",
        )
        emitLeadLagTelemetry(
            LeadLagTelemetryEvent(
                event = "T0_DETECTED",
                traceId = traceId,
                pairId = selectedPair.value,
                coinClass = coinClass.name.lowercase(),
                sourceBotId = config.controlPlane.botId.value,
                targetBotId = targetBotId.value,
                t0DetectedAtEpochMs = detectedAtMs,
                note = "Kinance deteksi breakout kandidat lead-lag.",
            ),
        )
        val payloadJson = json.encodeToString(payload)
        val udpSent = sendLeadLagUdp(payloadJson)
        val dispatched = if (udpSent) {
            true
        } else {
            runCatching {
                controlPlane.enqueueCommand(
                    botId = targetBotId,
                    createdBy = config.device.deviceId,
                    commandType = CommandType.SYNC_NOW,
                    payloadJson = payloadJson,
                )
            }.isSuccess
        }
        if (dispatched) {
            leadLagSentAtByPair[pairKey] = now
            appendAuditLog(
                level = LogLevel.INFO,
                category = "LEAD_LAG",
                message = if (udpSent) {
                    "Kinance kirim callout ${selectedPair.value} kelas=${coinClass.name.lowercase()} ttl=${ttlMs}ms via UDP ke ${targetBotId.value}."
                } else {
                    "Kinance fallback queue untuk ${selectedPair.value} kelas=${coinClass.name.lowercase()} ttl=${ttlMs}ms ke ${targetBotId.value}."
                },
            )
            emitLeadLagTelemetry(
                LeadLagTelemetryEvent(
                    event = if (udpSent) "T1_UDP_SENT" else "T1_FALLBACK_QUEUE",
                    traceId = traceId,
                    pairId = selectedPair.value,
                    coinClass = coinClass.name.lowercase(),
                    sourceBotId = config.controlPlane.botId.value,
                    targetBotId = targetBotId.value,
                    t0DetectedAtEpochMs = detectedAtMs,
                    t1UdpSentAtEpochMs = sentAtMs,
                    note = if (udpSent) "Kinance kirim UDP callout." else "Kinance fallback ke command queue.",
                ),
            )
        } else {
            logger.warn("Lead-lag callout dispatch failed for pair {}", selectedPair.value)
        }

        val reversalQuote = marketQuotes.firstOrNull { quote ->
            quote.shortTermReturnPct <= -0.9 &&
                quote.recentTradeActivityScore >= config.leadLagMinTradeActivityScore
        }
        if (reversalQuote != null) {
            val sellTrace = "ll-sell-${reversalQuote.pairId.value.lowercase()}-${now.toEpochMilliseconds()}"
            val reversalMagnitude = kotlin.math.abs(reversalQuote.shortTermReturnPct)
            val reversalMsgType = if (reversalMagnitude < 1.8) "MOMENTUM_LOSS" else "SELL_WALL_SURGE"
            val sellPayload = LeadLagCalloutPayload(
                traceId = sellTrace,
                msgType = reversalMsgType,
                senderBotId = config.controlPlane.botId.value,
                pairId = reversalQuote.pairId.value,
                trend = "REVERSAL",
                detectedAtEpochMs = detectedAtMs,
                confidence = 0.88,
                expectedNetPct = maxOf(reversalQuote.shortTermReturnPct * -1.0, 0.1),
                shortTermReturnPct = reversalQuote.shortTermReturnPct,
                mediumTermReturnPct = reversalQuote.mediumTermReturnPct,
                tradeActivityScore = reversalQuote.recentTradeActivityScore,
                forceRotation = true,
                sentAtEpochMs = sentAtMs,
                expiresAtEpochMs = sentAtMs + 3_000L,
            )
            val sellJson = json.encodeToString(sellPayload)
            val (sellDispatched, sellViaUdp) = dispatchLeadLagPayloadWithFallback(
                payloadJson = sellJson,
                targetBotId = targetBotId,
            )
            if (sellDispatched) {
                appendAuditLog(
                    level = LogLevel.WARN,
                    category = "LEAD_LAG",
                    message = if (sellViaUdp) {
                        "Kinance kirim ${reversalMsgType} ${reversalQuote.pairId.value} ttl=3000ms via UDP ke ${targetBotId.value}."
                    } else {
                        "Kinance fallback queue ${reversalMsgType} ${reversalQuote.pairId.value} ttl=3000ms ke ${targetBotId.value}."
                    },
                )
            }
        }
    }

    private suspend fun maybeDispatchKinanceEmergencyWarnings(
        now: Instant,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        targetBotId: BotId,
    ): Boolean {
        if (config.exchangeKind != ExchangeKind.BINANCE_SPOT) return false
        if (kidaxActivePositionsByPair.isEmpty()) return false
        var sentWarning = false
        kidaxActivePositionsByPair.values.forEach { tracked ->
            val pairId = com.kibot.shared.models.PairId(tracked.pairId)
            val quote = marketQuotes.firstOrNull { it.pairId == pairId } ?: return@forEach
            val bidDepth = quote.bidDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
            val askDepth = quote.askDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
            val trackedNotional = tracked.notionalIdr.coerceAtLeast(0.0)
            val depthCollapse = trackedNotional > 0.0 &&
                (bidDepth < (trackedNotional * 0.35) || askDepth < (trackedNotional * 0.35))
            val momentumCollapse = quote.shortTermReturnPct <= -1.2 && quote.mediumTermReturnPct <= -0.6
            if (!depthCollapse && !momentumCollapse) return@forEach
            val pairKey = tracked.pairId.lowercase()
            val lastSent = emergencyWarningCooldownByPair[pairKey]
            if (lastSent != null && (now - lastSent).inWholeMilliseconds < 3_000L) return@forEach
            emergencyWarningCooldownByPair[pairKey] = now
            val sentAt = now.toEpochMilliseconds()
            val traceId = "emg-${pairKey}-$sentAt"
            val emergencyMsgType = if (depthCollapse) "ORDERBOOK_COLLAPSE" else "MOMENTUM_LOSS"
            val payload = LeadLagCalloutPayload(
                traceId = traceId,
                msgType = emergencyMsgType,
                senderBotId = config.controlPlane.botId.value,
                pairId = tracked.pairId,
                trend = "REVERSAL",
                detectedAtEpochMs = sentAt,
                confidence = 0.92,
                expectedNetPct = kotlin.math.abs(quote.shortTermReturnPct).coerceAtLeast(0.2),
                shortTermReturnPct = quote.shortTermReturnPct,
                mediumTermReturnPct = quote.mediumTermReturnPct,
                tradeActivityScore = quote.recentTradeActivityScore,
                forceRotation = true,
                sentAtEpochMs = sentAt,
                expiresAtEpochMs = sentAt + 3_000L,
            )
            val payloadJson = json.encodeToString(payload)
            val (dispatched, viaUdp) = dispatchLeadLagPayloadWithFallback(
                payloadJson = payloadJson,
                targetBotId = targetBotId,
            )
            if (dispatched) {
                sentWarning = true
                appendAuditLog(
                    level = LogLevel.WARN,
                    category = "LEAD_LAG",
                    message = if (viaUdp) {
                        "Kinance emergency warning ${tracked.pairId}: depth/momentum collapse, kirim ${emergencyMsgType} via UDP ke ${targetBotId.value}."
                    } else {
                        "Kinance emergency warning ${tracked.pairId}: depth/momentum collapse, fallback queue ${emergencyMsgType} ke ${targetBotId.value}."
                    },
                )
            }
        }
        return sentWarning
    }

    private suspend fun dispatchLeadLagPayloadWithFallback(
        payloadJson: String,
        targetBotId: BotId,
    ): Pair<Boolean, Boolean> {
        val udpSent = sendLeadLagUdp(payloadJson)
        if (udpSent) return true to true
        val queued = runCatching {
            controlPlane.enqueueCommand(
                botId = targetBotId,
                createdBy = config.device.deviceId,
                commandType = CommandType.SYNC_NOW,
                payloadJson = payloadJson,
            )
        }.isSuccess
        return queued to false
    }

    private fun encodeBinaryUdpPayloadIfSupported(payloadJson: String): ByteArray? {
        if (!config.leadLagUdpBinaryProtocolEnabled) return null
        runCatching {
            val heartbeat = json.decodeFromString<TrinityHeartbeatPayload>(payloadJson)
            if (heartbeat.kind == "trinity_state" && heartbeat.msgType.equals("HEARTBEAT", ignoreCase = true)) {
                return encodeBinaryHeartbeatPacket(heartbeat)
            }
        }
        runCatching {
            val payload = json.decodeFromString<LeadLagCalloutPayload>(payloadJson)
            if (payload.kind == "lead_lag_breakout") {
                return encodeBinaryLeadLagPacket(payload)
            }
        }
        return null
    }

    private fun sendLeadLagUdp(payloadJson: String): Boolean {
        if (!config.leadLagUdpEnabled) return false
        val peers = buildUdpPeerList()
        if (peers.isEmpty()) return false
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500  // Increased from 100ms
                val bytes = encodeBinaryUdpPayloadIfSupported(payloadJson) ?: payloadJson.toByteArray(Charsets.UTF_8)
                peers.forEach { (targetHost, targetPort) ->
                    val targetAddress = InetAddress.getByName(targetHost)
                    val packet = DatagramPacket(bytes, bytes.size, targetAddress, targetPort)
                    socket.send(packet)
                }
            }
            true
        }.getOrElse {
            logger.warn("Lead-lag UDP send failed: {}", it.message)
            false
        }
    }

    private fun buildUdpPeerList(): List<Pair<String, Int>> {
        val peers = linkedSetOf<Pair<String, Int>>()
        config.leadLagUdpTargetHost
            ?.takeIf { it.isNotBlank() }
            ?.let { peers += (it to config.leadLagUdpTargetPort) }
        hiveExtraUdpPeers.forEach { peers += it }
        return peers.toList()
    }

    private fun applyPursuitPolicy(
        cycle: com.kibot.core.StrategyCycleResult,
        adaptiveAiPolicy: AdaptiveAiPolicy?,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        now: Instant,
    ): com.kibot.core.StrategyCycleResult {
        val jakartaDate = jakartaNowDate(now)
        val pursuit = dailyTargetPursuitBrain.evaluate(
            cycle = cycle,
            adaptiveAiPolicy = adaptiveAiPolicy,
            now = now,
        )
        val enforcementMemory = updateTargetEnforcementMemory(
            pursuit = pursuit,
            jakartaDate = jakartaDate,
        )
        if (!pursuit.active && adaptiveAiPolicy == null) return cycle

        val aiAdjustments = adaptiveAiPolicy?.adjustments ?: AdaptiveAiAdjustments()
        val executionHints = adaptiveAiPolicy?.executionHints ?: AdaptiveAiExecutionHints()
        val watchdog = adaptiveAiPolicy?.watchdog ?: AdaptiveAiWatchdog()
        val repeatedHourlyPenalty = enforcementMemory.consecutiveHourlyMisses.coerceIn(0, 4)
        val repeatedCheckpointPenalty = enforcementMemory.consecutiveCheckpointMisses.coerceIn(0, 3)
        val worseningHourlyMiss = pursuit.hourlyMissed &&
            pursuit.hourlyWindowIndex > enforcementMemory.lastHourlyWindowIndex &&
            pursuit.hourlyShortfallPct > (enforcementMemory.lastHourlyShortfallPct + 0.35)
        val worseningCheckpointMiss = pursuit.checkpointMissed &&
            pursuit.checkpointWindowIndex > enforcementMemory.lastCheckpointWindowIndex &&
            pursuit.checkpointShortfallPct > (enforcementMemory.lastCheckpointShortfallPct + 0.45)
        val budgetBoostMultiplier = maxOf(
            watchdog.budgetBoostFloor,
            (
            pursuit.budgetBoostMultiplier *
                (1.0 + aiAdjustments.budgetBoostMultiplierDelta.coerceIn(0.0, 0.35)) +
                (repeatedHourlyPenalty * 0.04) +
                (repeatedCheckpointPenalty * 0.06)
            ).coerceIn(1.0, 2.0)
        )
        val boostedBudget = (cycle.deploymentPlan.suggestedPerPositionBudgetIdr * budgetBoostMultiplier)
            .coerceAtLeast(cycle.deploymentPlan.suggestedPerPositionBudgetIdr)
        val openPositions = cycle.portfolio.positions.count { it.state != com.kibot.shared.models.PositionState.CLOSED }
        val candidates = cycle.deploymentPlan.candidates
        val actionProfile = when {
            watchdog.severity == "CRITICAL" -> "EMERGENCY_PURSUIT"
            worseningCheckpointMiss -> "EMERGENCY_PURSUIT"
            worseningHourlyMiss && repeatedHourlyPenalty >= 2 -> "EMERGENCY_PURSUIT"
            repeatedCheckpointPenalty >= 2 -> "EMERGENCY_PURSUIT"
            pursuit.checkpointEscalationLevel >= 3 -> "EMERGENCY_PURSUIT"
            repeatedHourlyPenalty >= 3 -> "HARD_HOURLY_PUSH"
            pursuit.checkpointMissed -> "CHECKPOINT_REPLAN"
            pursuit.hourlyEscalationLevel >= 2 -> "HARD_HOURLY_PUSH"
            pursuit.hourlyMissed -> "HOURLY_PUSH"
            pursuit.profitWindowOpen -> "PROFIT_HUNT"
            else -> "BASE"
        }
        val topCandidate = candidates.firstOrNull()
        val dominantConcentrationSignal = topCandidate != null &&
            topCandidate.rankingScore >= 0.82 &&
            topCandidate.marketOpportunityScore >= 0.70 &&
            topCandidate.expectedNetProfitabilityPct >= 2.0
        val highConvictionReserveFloor = when {
            executionHints.concentrationPair != null && dominantConcentrationSignal -> 0.005
            dominantConcentrationSignal && pursuit.profitWindowOpen -> 0.005
            actionProfile in setOf("CHECKPOINT_REPLAN", "EMERGENCY_PURSUIT") && dominantConcentrationSignal -> 0.008
            else -> 0.02
        }
        val boostedReservePct = (
            cycle.deploymentPlan.targetCashReservePct -
                pursuit.reserveReliefPct -
                aiAdjustments.reserveReliefPctDelta.coerceIn(0.0, 0.08) -
                (repeatedCheckpointPenalty * 0.01) -
                (if (executionHints.concentrationPair != null) 0.005 else 0.0)
            ).coerceIn(highConvictionReserveFloor, cycle.deploymentPlan.targetCashReservePct.coerceAtLeast(highConvictionReserveFloor))
        val concentrationPressureStep = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.22
            "CHECKPOINT_REPLAN" -> 0.18
            "HARD_HOURLY_PUSH" -> 0.14
            "HOURLY_PUSH" -> 0.10
            "PROFIT_HUNT" -> 0.12
            else -> 0.0
        }
        val reserveReliefStep = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.018
            "CHECKPOINT_REPLAN" -> 0.014
            "HARD_HOURLY_PUSH" -> 0.010
            "HOURLY_PUSH" -> 0.007
            "PROFIT_HUNT" -> 0.008
            else -> 0.0
        }
        val boostedReservePctWithPressure = (
            boostedReservePct - reserveReliefStep
        ).coerceIn(highConvictionReserveFloor, cycle.deploymentPlan.targetCashReservePct.coerceAtLeast(highConvictionReserveFloor))
        val finalReservePct = minOf(boostedReservePctWithPressure, cycle.deploymentPlan.targetCashReservePct - watchdog.reserveReliefFloor)
            .coerceIn(highConvictionReserveFloor, cycle.deploymentPlan.targetCashReservePct.coerceAtLeast(highConvictionReserveFloor))
        val hourlyEnforcementHeadroom = when {
            actionProfile == "EMERGENCY_PURSUIT" -> 1
            actionProfile == "CHECKPOINT_REPLAN" -> 1
            actionProfile == "HARD_HOURLY_PUSH" && candidates.count {
                it.rankingScore >= 0.72 &&
                    it.marketOpportunityScore >= 0.64 &&
                    it.expectedNetProfitabilityPct >= 1.10
            } >= 2 -> 1
            actionProfile == "PROFIT_HUNT" -> 1
            else -> 0
        }
        val riskHeadroomCeiling = cycle.riskDecision.maxAllowedAdditionalPositions.coerceAtLeast(0)
        val baselineHeadroom = (cycle.deploymentPlan.maxActivePositions - openPositions).coerceAtLeast(0)
        val requestedSlotHeadroom = (
            baselineHeadroom +
                minOf(pursuit.extraSlots, hourlyEnforcementHeadroom) +
                hourlyEnforcementHeadroom +
                aiAdjustments.extraSlotsDelta.coerceIn(0, 2) +
                (if (watchdog.forceRotation) 1 else 0)
            ).coerceAtLeast(baselineHeadroom)
        val opportunityQualifiedHeadroom = qualifiedAdditionalHeadroom(
            candidates = candidates,
            openPositions = openPositions,
            profitWindowOpen = pursuit.profitWindowOpen,
            checkpointMissed = pursuit.checkpointMissed,
            actionProfile = actionProfile,
            baselineHeadroom = baselineHeadroom,
        )
        val effectiveHeadroom = minOf(
            riskHeadroomCeiling,
            minOf(requestedSlotHeadroom, opportunityQualifiedHeadroom),
        ).coerceAtLeast(0)
        val boostedActivePositions = maxOf(
            cycle.deploymentPlan.maxActivePositions,
            openPositions + effectiveHeadroom,
        ).coerceAtMost(6)
        val existingCapitalTarget = cycle.deploymentPlan.capitalUtilizationTargetPct
            .coerceIn(0.02, 0.98)
        val boostedCapitalTarget = (1.0 - finalReservePct).coerceIn(0.02, 0.98)
        val normalizedBudget = finalizePerPositionBudgetIdr(
            currentEquityIdr = cycle.portfolio.totalEquityIdr.toDoubleOrZero(),
            boostedCapitalTargetPct = boostedCapitalTarget,
            baseBudgetIdr = boostedBudget,
            finalActivePositions = boostedActivePositions,
            openPositions = openPositions,
            candidates = candidates,
            concentrationBoostPct = pursuit.concentrationBoostPct +
                concentrationPressureStep +
                aiAdjustments.allocationFocusPctDelta.coerceIn(0.0, 0.16) +
                (if (watchdog.forceConcentration) 0.06 else 0.0),
            profitWindowOpen = pursuit.profitWindowOpen,
            concentrationPair = executionHints.concentrationPair,
            actionProfile = actionProfile,
        )
        val finalConcentrationBoostPct = pursuit.concentrationBoostPct +
            concentrationPressureStep +
            aiAdjustments.allocationFocusPctDelta.coerceIn(0.0, 0.16) +
            (if (watchdog.forceConcentration) 0.06 else 0.0)

        val updatedDeploymentPlan = cycle.deploymentPlan.copy(
            allowRotation = cycle.deploymentPlan.allowRotation ||
                pursuit.urgency >= 0.42 ||
                pursuit.hourlyMissed ||
                pursuit.checkpointMissed ||
                watchdog.forceRotation ||
                executionHints.rotateNowPairs.isNotEmpty() ||
                executionHints.replacementHints.isNotEmpty(),
            maxActivePositions = boostedActivePositions,
            suggestedPerPositionBudgetIdr = normalizedBudget,
            targetCashReservePct = finalReservePct,
            capitalUtilizationTargetPct = maxOf(existingCapitalTarget, boostedCapitalTarget),
            rationale = cycle.deploymentPlan.rationale + pursuit.rationale,
        )

        val updatedExecutionPlan = cycle.executionPlan?.let { plan ->
            scaleExecutionPlanForPursuit(
                executionPlan = plan,
                balances = balances,
                marketQuotes = marketQuotes,
                targetBudgetIdr = normalizedBudget,
                concentrationBoostPct = finalConcentrationBoostPct,
                executionBoostMultiplier = maxOf(pursuit.executionBoostMultiplier, watchdog.executionBoostFloor),
            )
        }

        return cycle.copy(
            deploymentPlan = updatedDeploymentPlan,
            executionPlan = updatedExecutionPlan,
            summary = cycle.summary + buildList {
                add("Daily target ${pursuit.phase}: ${formatDecimal(pursuit.currentProfitPct, 2)}% / 25.00% dengan urgency ${formatDecimal(pursuit.urgency * 100.0, 0)}%.")
                if (pursuit.hourlyMissed) add("Evaluasi 1 jam miss ${pursuit.hourlyMissCount} langkah (${formatDecimal(pursuit.hourlyShortfallPct, 2)}%), action $actionProfile aktif.")
                if (pursuit.checkpointMissed) add("Checkpoint 3 jam ke-${pursuit.checkpointWindowIndex} miss ${formatDecimal(pursuit.checkpointShortfallPct, 2)}%, jadi replan wajib aktif.")
                if (enforcementMemory.consecutiveHourlyMisses > 1) add("Miss hourly berturut-turut ${enforcementMemory.consecutiveHourlyMisses}x, jadi tekanan pursuit ditahan tetap tinggi.")
                if (enforcementMemory.consecutiveCheckpointMisses > 0) add("Miss checkpoint berturut-turut ${enforcementMemory.consecutiveCheckpointMisses}x, jadi rotasi dan sizing dipaksa lebih keras.")
                if (pursuit.profitWindowOpen && !pursuit.checkpointMissed) add("Profit window terbuka, jadi bot tetap agresif cari entry cepat walau checkpoint hanya jadi patokan.")
                if (pursuit.forcedReplan) add("Bot masuk forced replan untuk mengejar target harian yang tertinggal.")
                if (watchdog.status != "IDLE" && watchdog.reprimand.isNotBlank()) add("AI watchdog: ${watchdog.reprimand}")
                if (watchdog.rootCauses.isNotEmpty()) add("AI watchdog akar masalah: ${watchdog.rootCauses.take(3).joinToString(", ")}.")
                if (watchdog.requiredActions.isNotEmpty()) add("AI watchdog aksi wajib: ${watchdog.requiredActions.take(2).joinToString(", ")}.")
                if (effectiveHeadroom < requestedSlotHeadroom) add("Slot tambahan dibatasi kualitas shortlist agar agresif tetap profit-first.")
                if (executionHints.rotateNowPairs.isNotEmpty()) add("AI execution hint mendorong rotasi ke ${executionHints.rotateNowPairs.take(2).joinToString(",") { it.value }}.")
                if (executionHints.replacementHints.isNotEmpty()) add("AI melihat holding ${executionHints.replacementHints.take(2).joinToString(", ") { "${it.cutPair.value}→${it.replacePair.value}" }} lebih efisien untuk digeser.")
                executionHints.concentrationPair?.let { add("AI execution hint mendorong konsentrasi modal ke ${it.value}.") }
                if (pursuit.urgency >= 0.40) add("Sizing entry dinaikkan dan reserve dikendurkan untuk kejar target harian.")
                if (pursuit.overdriveAllowed) add("Target tercapai tapi breakout masih ganas, jadi bot tetap tekan entry winner dan biarkan profit lanjut.")
                if (updatedDeploymentPlan.allowRotation) add("Rotasi loser/stagnan dipercepat saat pair baru terlihat lebih eksplosif.")
            },
        )
    }

    private fun scaleExecutionPlanForPursuit(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        targetBudgetIdr: Double,
        concentrationBoostPct: Double,
        executionBoostMultiplier: Double,
    ): com.kibot.shared.models.ExecutionPlan {
        val pairAssets = executionPlan.signal.pairId.pairAssets()
        val quoteAssetPriceIdr = quoteAssetReferencePrice(pairAssets.quoteAsset, marketQuotes) ?: return executionPlan
        val quoteBalanceUnits = balances
            .firstOrNull { it.asset.equals(pairAssets.quoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: return executionPlan
        val availableBudgetIdr = if (pairAssets.quoteAsset.equals(referenceQuoteAsset(), ignoreCase = true)) {
            quoteBalanceUnits
        } else {
            quoteBalanceUnits * quoteAssetPriceIdr
        }
        val baseBudgetIdr = executionPlan.quoteBudget?.toDoubleOrZero()
            ?: executionPlan.limitPrice?.toDoubleOrZero()?.let { price ->
                executionPlan.quantity.toDoubleOrZero() * price * if (pairAssets.quoteAsset.equals(referenceQuoteAsset(), ignoreCase = true)) 1.0 else quoteAssetPriceIdr
            }
            ?: return executionPlan
        val boostedBudgetIdr = minOf(
            targetBudgetIdr * (1.0 + concentrationBoostPct.coerceIn(0.0, 0.24)),
            availableBudgetIdr * 0.985,
            baseBudgetIdr * executionBoostMultiplier.coerceIn(1.0, 1.95),
        ).coerceAtLeast(baseBudgetIdr)
        if (boostedBudgetIdr <= baseBudgetIdr) return executionPlan
        val ratio = (boostedBudgetIdr / baseBudgetIdr).coerceAtLeast(1.0)
        return executionPlan.copy(
            quantity = DecimalValue.fromDouble(executionPlan.quantity.toDoubleOrZero() * ratio),
            quoteBudget = DecimalValue.fromDouble(boostedBudgetIdr),
        )
    }

    private fun amplifyToAllInBudget(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan {
        val assets = executionPlan.signal.pairId.pairAssets()
        val quoteAsset = assets.quoteAsset
        val quoteAssetPriceIdr = quoteAssetReferencePrice(quoteAsset, marketQuotes) ?: return executionPlan
        val freeQuoteUnits = balances
            .firstOrNull { it.asset.equals(quoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: return executionPlan
        val allInBudgetIdr = if (quoteAsset.equals(referenceQuoteAsset(), ignoreCase = true)) {
            freeQuoteUnits * 1.0
        } else {
            (freeQuoteUnits * quoteAssetPriceIdr) * 1.0
        }
        val currentBudgetIdr = executionPlan.quoteBudget?.toDoubleOrZero()
            ?: executionPlan.limitPrice?.toDoubleOrZero()?.let { executionPlan.quantity.toDoubleOrZero() * it * quoteAssetPriceIdr }
            ?: 0.0
        if (allInBudgetIdr <= 0.0 || allInBudgetIdr <= currentBudgetIdr) return executionPlan
        val ratio = (allInBudgetIdr / currentBudgetIdr.coerceAtLeast(0.0001)).coerceIn(1.0, 50.0)
        return executionPlan.copy(
            quantity = DecimalValue.fromDouble(executionPlan.quantity.toDoubleOrZero() * ratio),
            quoteBudget = DecimalValue.fromDouble(allInBudgetIdr),
        )
    }

    private fun determineEntryBatchLimit(
        cycle: com.kibot.core.StrategyCycleResult,
        availableEntrySlots: Int,
        candidateExecutionPlans: List<com.kibot.shared.models.ExecutionPlan>,
    ): Int {
        if (availableEntrySlots <= 0 || candidateExecutionPlans.isEmpty()) return 0
        val secondPlan = candidateExecutionPlans.getOrNull(1)
        val thirdPlan = candidateExecutionPlans.getOrNull(2)
        val secondStrong = secondPlan?.let {
            it.pairRankingScore >= 0.70 && it.expectedNetEdgePct >= 1.12
        } == true
        val thirdStrong = thirdPlan?.let {
            it.pairRankingScore >= 0.76 && it.expectedNetEdgePct >= 1.35
        } == true
        val aggressiveBatchTarget = when {
            thirdStrong && cycle.deploymentPlan.allowRotation -> 3
            secondStrong -> 2
            else -> 1
        }
        return minOf(availableEntrySlots, aggressiveBatchTarget, candidateExecutionPlans.size)
    }

    private fun qualifiedAdditionalHeadroom(
        candidates: List<com.kibot.shared.models.CandidateOpportunity>,
        openPositions: Int,
        profitWindowOpen: Boolean,
        checkpointMissed: Boolean,
        actionProfile: String,
        baselineHeadroom: Int,
    ): Int {
        if (candidates.isEmpty()) return 0
        val strongRankingFloor = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.66
            "CHECKPOINT_REPLAN" -> 0.68
            "HARD_HOURLY_PUSH" -> 0.69
            "HOURLY_PUSH" -> 0.70
            else -> 0.72
        }
        val strongOpportunityFloor = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 0.62
            "CHECKPOINT_REPLAN" -> 0.64
            "HARD_HOURLY_PUSH" -> 0.65
            "HOURLY_PUSH" -> 0.65
            else -> 0.66
        }
        val strongNetFloor = when (actionProfile) {
            "EMERGENCY_PURSUIT" -> 1.02
            "CHECKPOINT_REPLAN" -> 1.08
            "HARD_HOURLY_PUSH" -> 1.12
            "HOURLY_PUSH" -> 1.16
            else -> 1.20
        }
        val strongCandidates = candidates.count {
            it.rankingScore >= strongRankingFloor &&
                it.marketOpportunityScore >= strongOpportunityFloor &&
                it.expectedNetProfitabilityPct >= strongNetFloor
        }
        val explosiveCandidates = candidates.count {
            it.rankingScore >= 0.82 &&
                it.marketOpportunityScore >= 0.76 &&
                it.expectedNetProfitabilityPct >= 2.10
        }
        val desiredAdditional = when {
            explosiveCandidates >= 3 -> 3
            explosiveCandidates >= 2 && strongCandidates >= 4 -> 3
            checkpointMissed && strongCandidates >= 4 -> 3
            profitWindowOpen && strongCandidates >= 4 -> 3
            explosiveCandidates >= 2 -> 2
            checkpointMissed && strongCandidates >= 2 -> 2
            profitWindowOpen && strongCandidates >= 2 -> 2
            strongCandidates >= 1 -> 1
            else -> 0
        }
        return maxOf(baselineHeadroom, desiredAdditional).coerceAtLeast((0 - openPositions).coerceAtLeast(0))
    }

    private fun finalizePerPositionBudgetIdr(
        currentEquityIdr: Double,
        boostedCapitalTargetPct: Double,
        baseBudgetIdr: Double,
        finalActivePositions: Int,
        openPositions: Int,
        candidates: List<com.kibot.shared.models.CandidateOpportunity>,
        concentrationBoostPct: Double,
        profitWindowOpen: Boolean,
        concentrationPair: com.kibot.shared.models.PairId?,
        actionProfile: String,
    ): Double {
        val deployableCapitalIdr = (currentEquityIdr * boostedCapitalTargetPct).coerceAtLeast(baseBudgetIdr)
        val topCandidate = candidates.firstOrNull()
        val aiConcentrationBoost = if (concentrationPair != null && concentrationPair == topCandidate?.pairId) 0.06 else 0.0
        val concentrationLedProfile = actionProfile in setOf("PROFIT_HUNT", "CHECKPOINT_REPLAN", "EMERGENCY_PURSUIT")
        val preserveExtraSlot = !concentrationLedProfile || (topCandidate == null && !profitWindowOpen)
        val normalizedSlotCount = when {
            preserveExtraSlot -> maxOf(finalActivePositions, openPositions + 1, 1)
            finalActivePositions > openPositions -> maxOf(finalActivePositions - 1, openPositions.coerceAtLeast(1))
            else -> maxOf(finalActivePositions, 1)
        }
        val slotNormalizedBudgetIdr = deployableCapitalIdr / normalizedSlotCount
        val concentrationMultiplier = when {
            topCandidate != null &&
                topCandidate.rankingScore >= 0.84 &&
                topCandidate.expectedNetProfitabilityPct >= 2.30 ->
                1.18 + concentrationBoostPct.coerceIn(0.0, 0.18) + aiConcentrationBoost
            profitWindowOpen -> 1.10 + concentrationBoostPct.coerceIn(0.0, 0.14) + aiConcentrationBoost
            else -> 1.0 + (concentrationBoostPct.coerceIn(0.0, 0.10) * 0.6) + aiConcentrationBoost
        }
        val floorBudgetIdr = maxOf(
            baseBudgetIdr * 0.92,
            slotNormalizedBudgetIdr * concentrationMultiplier,
        )
        val maxPerPositionPct = 0.25  // CRITICAL FIX: 25% max per position (Zero-Cash Mindset)
        val ceilingBudgetIdr = deployableCapitalIdr * maxPerPositionPct  // Apply 25% ceiling
        return floorBudgetIdr
            .coerceAtMost(ceilingBudgetIdr.coerceAtLeast(baseBudgetIdr * 0.94))
            .coerceAtLeast(baseBudgetIdr * 0.88)
    }

    private fun buildHyperAggressiveSyntheticEntryPlan(
        cycle: com.kibot.core.StrategyCycleResult,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
        target: HyperTargetCandidate,
    ): com.kibot.shared.models.ExecutionPlan? {
        val quote = marketQuotes.firstOrNull { it.pairId == target.pairId } ?: return null
        val bestAsk = quote.bestAsk.toDoubleOrZero()
        if (bestAsk <= 0.0) return null
        val assets = target.pairId.pairAssets()
        val quoteAsset = assets.quoteAsset
        val quoteAssetPriceIdr = quoteAssetReferencePrice(quoteAsset, marketQuotes) ?: return null
        if (quoteAssetPriceIdr <= 0.0) return null
        val freeQuote = balances.firstOrNull { it.asset.equals(quoteAsset, ignoreCase = true) }?.free?.toDoubleOrZero() ?: return null
        val freeBudgetIdr = if (quoteAsset.equals(referenceQuoteAsset(), ignoreCase = true)) freeQuote else freeQuote * quoteAssetPriceIdr
        if (freeBudgetIdr <= 0.0) return null
        val minLiveNotionalIdr = when (config.exchangeKind) {
            ExchangeKind.INDODAX -> 10_000.0
            ExchangeKind.BINANCE_SPOT -> 7.5
        }
        val budgetIdr = minOf(
            freeBudgetIdr * 0.75,
            cycle.deploymentPlan.suggestedPerPositionBudgetIdr * if (target.kind == HyperTargetKind.SUPER_SEXY) 2.5 else 1.5,
        ).coerceAtLeast(minLiveNotionalIdr)
        if (budgetIdr > freeBudgetIdr) return null
        val entry = bestAsk
        val qty = (budgetIdr / (entry * quoteAssetPriceIdr)).coerceAtLeast(0.0000001)
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = target.pairId,
            signalType = com.kibot.shared.models.StrategySignalType.BREAKOUT_ENTRY,
            confidence = when (target.kind) {
                HyperTargetKind.SUPER_SEXY -> 0.99
                HyperTargetKind.V_SHAPE_BOUNCE -> 0.93
                HyperTargetKind.WALL_SMASH -> 0.91
                HyperTargetKind.SEXY -> 0.88
            },
            rationale = listOf("HyperAggressive synthetic entry ${target.kind.name.lowercase()}."),
            entryPrice = quote.bestAsk,
            takeProfitPrice = null,
            stopPrice = null,
            setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION,
            horizon = com.kibot.shared.models.TradingHorizon.TACTICAL,
            pairTier = com.kibot.shared.models.PairTier.TIER_B,
            speculativePocket = true,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = 2.0,
            expectedNetProfitabilityPct = maxOf(1.2, quote.shortTermReturnPct),
        )
        return com.kibot.shared.models.ExecutionPlan(
            signal = signal,
            side = com.kibot.shared.models.OrderSide.BUY,
            orderType = com.kibot.shared.models.OrderType.MARKET,
            quantity = DecimalValue.fromDouble(qty),
            limitPrice = null,
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
            postOnlyPreferred = false,
            expectedNetEdgePct = maxOf(1.2, quote.shortTermReturnPct),
            botMode = cycle.modeSnapshot.mode,
            riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
            pairRankingScore = (cycle.rankedPairs.firstOrNull { it.pairId == target.pairId }?.rankingScore ?: 0.82),
            speculativePocket = true,
        )
    }

    private fun normalizeExecutionPlanForVenue(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.shared.models.ExecutionPlan? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return executionPlan
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return executionPlan

        val pairId = executionPlan.signal.pairId
        val pairAssets = pairId.pairAssets()
        if (!pairAssets.quoteAsset.equals("idr", ignoreCase = true)) return executionPlan

        val quote = marketQuotes.firstOrNull { it.pairId == pairId }
        val refPrice = executionPlan.signal.entryPrice?.toDoubleOrZero()
            ?: executionPlan.limitPrice?.toDoubleOrZero()
            ?: quote?.bestAsk?.toDoubleOrZero()
            ?: quote?.midPrice?.toDoubleOrZero()
            ?: 0.0
        if (refPrice <= 0.0) return null

        val minNotionalIdr = 10_250.0
        val currentBudgetIdr = executionPlan.quoteBudget?.toDoubleOrZero()
            ?: (executionPlan.quantity.toDoubleOrZero() * refPrice)
        if (currentBudgetIdr < minNotionalIdr) return null
        val budgetIdr = currentBudgetIdr
        val qty = (budgetIdr / refPrice).coerceAtLeast(0.00000001)

        return executionPlan.copy(
            quantity = DecimalValue.fromDouble(qty),
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
        )
    }

    private fun entryBlockedByAntiKoinMahal(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): String? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return null
        val pair = executionPlan.signal.pairId
        val assets = pair.pairAssets()
        if (!assets.quoteAsset.equals("idr", ignoreCase = true)) return null
        val pricePerCoin = executionPlan.signal.entryPrice?.toDoubleOrZero()
            ?: executionPlan.limitPrice?.toDoubleOrZero()
            ?: marketQuotes.firstOrNull { it.pairId == pair }?.bestAsk?.toDoubleOrZero()
            ?: return "Entry ${pair.value} ditunda karena harga referensi belum tersedia."
        if (pricePerCoin <= 0.0) return "Entry ${pair.value} ditunda karena harga referensi tidak valid."
        val freeIdr = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: 0.0
        if (config.antiKoinMahalUseBudgetCheck) {
            val budgetIdr = executionPlan.quoteBudget?.toDoubleOrZero() ?: freeIdr
            val minOrderIdr = 10_000.0
            if (budgetIdr < minOrderIdr && freeIdr < minOrderIdr) {
                return "Anti-Koin Mahal block ${pair.value}: budget Rp${formatDecimal(budgetIdr, 0)} dan saldo Rp${formatDecimal(freeIdr, 0)} < minimum order Rp${formatDecimal(minOrderIdr, 0)}."
            }
        } else {
            if (pricePerCoin > freeIdr) {
                return "Anti-Koin Mahal block ${pair.value}: harga per koin Rp${formatDecimal(pricePerCoin, 0)} > saldo tersedia Rp${formatDecimal(freeIdr, 0)}."
            }
        }
        return null
    }

    private fun entryBlockedByCapitalMismatch(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): String? {
        if (config.exchangeKind != ExchangeKind.INDODAX) return null
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return null
        val pair = executionPlan.signal.pairId
        val assets = pair.pairAssets()
        if (!assets.quoteAsset.equals("idr", ignoreCase = true)) return null

        val quote = marketQuotes.firstOrNull { it.pairId == pair } ?: return null
        val entryPrice = executionPlan.signal.entryPrice?.toDoubleOrZero()
            ?: executionPlan.limitPrice?.toDoubleOrZero()
            ?: quote.bestAsk.toDoubleOrZero()
            ?: quote.midPrice.toDoubleOrZero()
            ?: 0.0
        if (entryPrice <= 0.0) {
            return "Capital mismatch block ${pair.value}: harga referensi tidak valid."
        }

        val freeIdr = balances.firstOrNull { it.asset.equals("idr", ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: 0.0
        val totalEquityIdr = balances.sumOf { it.totalValueInIdr?.toDoubleOrZero() ?: 0.0 }
        val lowCapital = totalEquityIdr in 0.0000001..150_000.0 || freeIdr in 0.0000001..150_000.0
        if (!lowCapital) return null

        val affordabilityRatio = if (entryPrice > 0.0) freeIdr / entryPrice else 0.0
        return when {
            freeIdr <= 0.0 -> "Capital mismatch block ${pair.value}: saldo IDR kosong."
            entryPrice > freeIdr * 0.90 -> "Capital mismatch block ${pair.value}: harga entry Rp${formatDecimal(entryPrice, 0)} terlalu mahal dibanding saldo free Rp${formatDecimal(freeIdr, 0)}."
            affordabilityRatio < 4.0 -> "Capital mismatch block ${pair.value}: affordability ratio ${formatDecimal(affordabilityRatio, 2)}x terlalu kecil untuk modal rendah."
            quote.spreadPct > 1.8 && affordabilityRatio < 8.0 -> "Capital mismatch block ${pair.value}: spread ${formatDecimal(quote.spreadPct, 2)}% terlalu lebar untuk modal rendah."
            else -> null
        }
    }

    private fun adaptExecutionPlanByCapital(
        executionPlan: com.kibot.shared.models.ExecutionPlan,
        totalEquityIdr: Double,
    ): com.kibot.shared.models.ExecutionPlan {
        if (executionPlan.side != com.kibot.shared.models.OrderSide.BUY) return executionPlan
        if (totalEquityIdr <= 0.0) return executionPlan
        return when (config.exchangeKind) {
            ExchangeKind.INDODAX -> {
                val budgetCapIdr = when {
                    totalEquityIdr < 90_000.0 -> 11_500.0
                    totalEquityIdr < 180_000.0 -> 18_000.0
                    else -> totalEquityIdr * 0.22
                }.coerceAtLeast(10_250.0)
                val current = executionPlan.quoteBudget?.toDoubleOrZero() ?: budgetCapIdr
                executionPlan.copy(
                    quoteBudget = DecimalValue.fromDouble(minOf(current, budgetCapIdr)),
                )
            }
            ExchangeKind.BINANCE_SPOT -> {
                val budgetCapIdr = when {
                    totalEquityIdr < 250_000.0 -> totalEquityIdr * 0.35
                    else -> totalEquityIdr * 0.22
                }.coerceAtLeast(12_000.0)
                val current = executionPlan.quoteBudget?.toDoubleOrZero() ?: budgetCapIdr
                executionPlan.copy(
                    quoteBudget = DecimalValue.fromDouble(minOf(current, budgetCapIdr)),
                )
            }
        }
    }

    private fun deriveCapitalAwareness(
        cycle: com.kibot.core.StrategyCycleResult,
        balances: List<BalanceSnapshot>,
    ): CapitalAwareness {
        val totalEquityIdr = cycle.portfolio.totalEquityIdr?.toDoubleOrZero()?.takeIf { it > 0.0 }
            ?: balances.sumOf { it.totalValueInIdr?.toDoubleOrZero() ?: 0.0 }
        return when (config.exchangeKind) {
            ExchangeKind.INDODAX -> {
                val low = totalEquityIdr < 120_000.0
                CapitalAwareness(
                    totalEquityIdr = totalEquityIdr,
                    lowCapital = low,
                    signalOnlyMode = false,
                    note = if (low) {
                        "Modal KiDax terbatas (Rp${formatDecimal(totalEquityIdr, 0)}), sizing entry dipadatkan dan rotasi diprioritaskan."
                    } else {
                        "Modal KiDax aman untuk mode agresif bertahap."
                    },
                )
            }
            ExchangeKind.BINANCE_SPOT -> {
                val low = totalEquityIdr < 300_000.0
                CapitalAwareness(
                    totalEquityIdr = totalEquityIdr,
                    lowCapital = low,
                    signalOnlyMode = false,
                    note = if (low) {
                        "Modal Kinance terbatas (Rp${formatDecimal(totalEquityIdr, 0)}), entry diperkecil supaya tidak cepat habis."
                    } else {
                        "Modal Kinance cukup untuk serangan agresif."
                    },
                )
            }
        }
    }

    private fun planPreRotationCleanupExit(
        now: Instant,
        managedPositions: List<com.kibot.core.ManagedPosition>,
        activeOrders: List<com.kibot.shared.models.OrderSnapshot>,
        cycle: com.kibot.core.StrategyCycleResult,
        hungry: Boolean,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ): com.kibot.core.ExitDecision? {
        if (!hungry || managedPositions.isEmpty()) return null
        val maxSlots = cycle.deploymentPlan.maxActivePositions.coerceAtLeast(1)
        val activeSellPairs = activeOrders
            .filter { it.status in activeOrderStatuses && it.side == com.kibot.shared.models.OrderSide.SELL }
            .map { it.pairId }
            .toSet()
        val slotsPressure = managedPositions.size >= maxSlots
        if (!slotsPressure) return null

        val candidate = managedPositions
            .asSequence()
            .filter { it.pairId !in activeSellPairs }
            .filter { isStagnantPair(it.pairId, now) }
            .filter { it.unrealizedPnlPct <= 1.0 }
            .minByOrNull { position ->
                marketQuotes.firstOrNull { q -> q.pairId == position.pairId }?.quoteVolume24h?.toDoubleOrZero()
                    ?: Double.MAX_VALUE
            } ?: return null

        val pairScore = cycle.rankedPairs.firstOrNull { it.pairId == candidate.pairId }
        val signal = com.kibot.shared.models.StrategySignal(
            pairId = candidate.pairId,
            signalType = com.kibot.shared.models.StrategySignalType.EXIT,
            confidence = (pairScore?.rankingScore ?: 0.70).coerceIn(0.55, 0.99),
            rationale = listOf("Pre-rotation cleanup: pair stagnan dibongkar untuk buka slot entry baru."),
            entryPrice = candidate.currentBidPrice,
            takeProfitPrice = candidate.takeProfitPrice,
            stopPrice = candidate.stopPrice,
            setupType = candidate.setupType,
            horizon = candidate.horizon,
            pairTier = candidate.pairTier,
            speculativePocket = true,
            marketRegime = cycle.marketSnapshot.regime,
            edgeConfidence = cycle.modeSnapshot.edgeConfidence,
            expectedHoldingHours = candidate.expectedHoldingHours,
            expectedNetProfitabilityPct = kotlin.math.abs(candidate.unrealizedPnlPct),
        )
        return com.kibot.core.ExitDecision(
            position = candidate,
            reason = com.kibot.core.ExitReason.ROTATION_EXIT,
            message = "Pre-rotation cleanup: ${candidate.pairId.value} stagnan, posisi dicairkan untuk buka slot entry agresif.",
            executionPlan = com.kibot.shared.models.ExecutionPlan(
                signal = signal,
                side = com.kibot.shared.models.OrderSide.SELL,
                orderType = com.kibot.shared.models.OrderType.MARKET,
                quantity = candidate.quantity,
                limitPrice = null,
                quoteBudget = null,
                postOnlyPreferred = false,
                expectedNetEdgePct = kotlin.math.abs(candidate.unrealizedPnlPct),
                botMode = cycle.modeSnapshot.mode,
                riskLadderLevel = cycle.modeSnapshot.riskLadderLevel,
                pairRankingScore = pairScore?.rankingScore ?: 0.70,
                speculativePocket = true,
            ),
        )
    }

    private fun updateTargetEnforcementMemory(
        pursuit: DailyTargetPursuit,
        jakartaDate: LocalDate,
    ): TargetEnforcementMemory {
        val current = if (targetEnforcementMemory.memoryDate != jakartaDate) {
            TargetEnforcementMemory(memoryDate = jakartaDate)
        } else {
            targetEnforcementMemory
        }
        val nextHourlyMisses = if (pursuit.hourlyWindowIndex > current.lastHourlyWindowIndex) {
            if (pursuit.hourlyMissed) current.consecutiveHourlyMisses + 1 else 0
        } else {
            current.consecutiveHourlyMisses
        }
        val nextCheckpointMisses = if (pursuit.checkpointWindowIndex > current.lastCheckpointWindowIndex) {
            if (pursuit.checkpointMissed) current.consecutiveCheckpointMisses + 1 else 0
        } else {
            current.consecutiveCheckpointMisses
        }
        val updated = current.copy(
            memoryDate = jakartaDate,
            lastHourlyWindowIndex = maxOf(current.lastHourlyWindowIndex, pursuit.hourlyWindowIndex),
            consecutiveHourlyMisses = nextHourlyMisses.coerceIn(0, 6),
            lastHourlyShortfallPct = if (pursuit.hourlyWindowIndex > current.lastHourlyWindowIndex) {
                pursuit.hourlyShortfallPct
            } else {
                current.lastHourlyShortfallPct
            },
            lastCheckpointWindowIndex = maxOf(current.lastCheckpointWindowIndex, pursuit.checkpointWindowIndex),
            consecutiveCheckpointMisses = nextCheckpointMisses.coerceIn(0, 4),
            lastCheckpointShortfallPct = if (pursuit.checkpointWindowIndex > current.lastCheckpointWindowIndex) {
                pursuit.checkpointShortfallPct
            } else {
                current.lastCheckpointShortfallPct
            },
        )
        targetEnforcementMemory = updated
        persistTargetEnforcementMemory(updated)
        return updated
    }

    private fun loadTargetEnforcementMemory(): TargetEnforcementMemory {
        val path = config.targetEnforcementMemoryPath
        val raw = runCatching {
            if (!Files.exists(path)) return TargetEnforcementMemory()
            Files.readString(path)
        }.getOrNull() ?: return TargetEnforcementMemory()
        val values = raw
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) null else parts[0].trim() to parts[1].trim()
            }
            .toMap()
        return TargetEnforcementMemory(
            memoryDate = values["memoryDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            lastHourlyWindowIndex = values["lastHourlyWindowIndex"]?.toIntOrNull() ?: 0,
            consecutiveHourlyMisses = values["consecutiveHourlyMisses"]?.toIntOrNull() ?: 0,
            lastHourlyShortfallPct = values["lastHourlyShortfallPct"]?.toDoubleOrNull() ?: 0.0,
            lastCheckpointWindowIndex = values["lastCheckpointWindowIndex"]?.toIntOrNull() ?: 0,
            consecutiveCheckpointMisses = values["consecutiveCheckpointMisses"]?.toIntOrNull() ?: 0,
            lastCheckpointShortfallPct = values["lastCheckpointShortfallPct"]?.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun persistTargetEnforcementMemory(memory: TargetEnforcementMemory) {
        runCatching {
            val path = config.targetEnforcementMemoryPath
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                path,
                buildString {
                    appendLine("memoryDate=${memory.memoryDate}")
                    appendLine("lastHourlyWindowIndex=${memory.lastHourlyWindowIndex}")
                    appendLine("consecutiveHourlyMisses=${memory.consecutiveHourlyMisses}")
                    appendLine("lastHourlyShortfallPct=${memory.lastHourlyShortfallPct}")
                    appendLine("lastCheckpointWindowIndex=${memory.lastCheckpointWindowIndex}")
                    appendLine("consecutiveCheckpointMisses=${memory.consecutiveCheckpointMisses}")
                    appendLine("lastCheckpointShortfallPct=${memory.lastCheckpointShortfallPct}")
                },
            )
        }.onFailure {
            logger.warn("Failed to persist target enforcement memory: {}", it.message)
        }
    }

    private fun toxicFlowStatePath(): java.nio.file.Path {
        val basePath = if (config.localPositionStateEnabled) {
            config.localPositionStatePath
        } else {
            config.targetEnforcementMemoryPath
        }
        val parent = basePath.parent ?: java.nio.file.Paths.get(".")
        return parent.resolve("pair-toxic-flow-state.json")
    }

    private fun loadToxicFlowState(): ToxicFlowStateSnapshot {
        val path = toxicFlowStatePath()
        return runCatching {
            if (!Files.exists(path)) return ToxicFlowStateSnapshot(
                botId = config.controlPlane.botId.value,
                deviceId = config.device.deviceId.value,
                observedAtEpochMs = 0L,
                entries = emptyList(),
            )
            json.decodeFromString<ToxicFlowStateSnapshot>(Files.readString(path))
        }.getOrElse {
            logger.warn("Failed to load toxic flow state: {}", it.message)
            ToxicFlowStateSnapshot(
                botId = config.controlPlane.botId.value,
                deviceId = config.device.deviceId.value,
                observedAtEpochMs = 0L,
                entries = emptyList(),
            )
        }
    }

    private fun persistToxicFlowState(now: Instant) {
        val snapshot = ToxicFlowStateSnapshot(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            observedAtEpochMs = now.toEpochMilliseconds(),
            entries = toxicFlowStateByPair.values
                .sortedBy { it.pairId }
                .toList(),
        )
        val encoded = json.encodeToString(snapshot)
        if (encoded == lastToxicFlowStateSignature) return
        runCatching {
            val path = toxicFlowStatePath()
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, encoded)
            lastToxicFlowStateSignature = encoded
        }.onFailure {
            logger.warn("Failed to persist toxic flow state: {}", it.message)
        }
    }

    private fun refreshToxicFlowState(now: Instant) {
        val updated = toxicFlowStateByPair.mapValues { (_, entry) ->
            if (entry.quarantinedUntilEpochMs > 0L && now.toEpochMilliseconds() >= entry.quarantinedUntilEpochMs) {
                entry.copy(quarantinedUntilEpochMs = 0L, consecutiveSweepHits = 0, lastReason = "quarantine_expired")
            } else if (entry.quarantinedUntilEpochMs > 0L) {
                val maxCooldownUntil = now.plus(15.minutes).toEpochMilliseconds()
                if (entry.quarantinedUntilEpochMs > maxCooldownUntil) {
                    entry.copy(quarantinedUntilEpochMs = maxCooldownUntil)
                } else {
                    entry
                }
            } else {
                entry
            }
        }
        toxicFlowStateByPair.clear()
        toxicFlowStateByPair.putAll(updated)
    }

    private fun toxicFlowQuarantineUntil(pairId: PairId): Instant? {
        val state = toxicFlowStateByPair[pairId.value.lowercase()] ?: return null
        val epochMs = state.quarantinedUntilEpochMs.takeIf { it > 0L } ?: return null
        return Instant.fromEpochMilliseconds(epochMs)
    }

    private fun recordStopLossToxicEvent(
        now: Instant,
        pairId: PairId,
        marketQuotes: List<com.kibot.shared.models.MarketQuote>,
    ) {
        val key = pairId.value.lowercase()
        val previous = toxicFlowStateByPair[key]
        val quote = marketQuotes.firstOrNull { it.pairId == pairId }
        val constructiveMacro = quote?.let {
            it.emaFastOverSlowPct > -0.20 ||
                it.btcContextScore >= 0.48 ||
                it.globalCorrelationScore >= 0.52 ||
                it.vwapDistancePct >= -2.2
        } ?: false
        val repeatedWithinWindow = previous?.lastStopLossAtEpochMs?.let {
            (now.toEpochMilliseconds() - it).coerceAtLeast(0L) <= 60.minutes.inWholeMilliseconds
        } ?: false
        val nextSweepHits = if (repeatedWithinWindow) {
            (previous?.consecutiveSweepHits ?: 0) + 1
        } else {
            1
        }
        val cooldownMinutes = 15L
        val quarantineUntilEpochMs = now.plus(cooldownMinutes.minutes).toEpochMilliseconds()
        val updated = ToxicFlowStateEntry(
            pairId = pairId.value,
            stopLossHits = (previous?.stopLossHits ?: 0) + 1,
            lastStopLossAtEpochMs = now.toEpochMilliseconds(),
            consecutiveSweepHits = nextSweepHits,
            quarantinedUntilEpochMs = quarantineUntilEpochMs,
            lastReason = if (constructiveMacro && nextSweepHits >= 2) "repeated_stop_loss_sweep" else "single_stop_loss",
        )
        toxicFlowStateByPair[key] = updated
        persistToxicFlowState(now)
        val until = Instant.fromEpochMilliseconds(updated.quarantinedUntilEpochMs)
        logger.warn(
            "TOXIC_FLOW pair={} cooldownUntil={} reason={}",
            pairId.value.lowercase(),
            formatJktTime(until),
            updated.lastReason,
        )
        repository.noteStatus("Toxic flow cooldown aktif untuk ${pairId.value} sampai ${formatJktTime(until)}.")
    }

    private fun loadLocalPositionState(): LocalPositionStateSnapshot {
        if (!config.localPositionStateEnabled) {
            return LocalPositionStateSnapshot(
                botId = config.controlPlane.botId.value,
                deviceId = config.device.deviceId.value,
                observedAtEpochMs = 0L,
                orders = emptyList(),
                managedPositions = emptyList(),
            )
        }
        val path = config.localPositionStatePath
        return runCatching {
            if (!Files.exists(path)) return LocalPositionStateSnapshot(
                botId = config.controlPlane.botId.value,
                deviceId = config.device.deviceId.value,
                observedAtEpochMs = 0L,
                orders = emptyList(),
                managedPositions = emptyList(),
            )
            json.decodeFromString<LocalPositionStateSnapshot>(Files.readString(path))
        }.getOrElse {
            logger.warn("Failed to load local position state: {}", it.message)
            LocalPositionStateSnapshot(
                botId = config.controlPlane.botId.value,
                deviceId = config.device.deviceId.value,
                observedAtEpochMs = 0L,
                orders = emptyList(),
                managedPositions = emptyList(),
            )
        }
    }

    private fun persistLocalPositionState(
        now: Instant,
        recentOrders: List<com.kibot.shared.models.OrderSnapshot>,
        managedPositions: List<com.kibot.core.ManagedPosition>,
    ) {
        if (!config.localPositionStateEnabled) return
        val snapshot = LocalPositionStateSnapshot(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            observedAtEpochMs = now.toEpochMilliseconds(),
            orders = recentOrders.takeLast(80),
            managedPositions = managedPositions.map { position ->
                LocalManagedPositionState(
                    pairId = position.pairId.value,
                    baseAsset = position.pairId.pairAssets().baseAsset,
                    quantity = position.quantity.value,
                    averageEntryPrice = position.averageEntryPrice.value,
                    breakEvenPrice = position.breakEvenPrice.value,
                    stopPrice = position.stopPrice.value,
                    takeProfitPrice = position.takeProfitPrice.value,
                    unrealizedPnlPct = position.unrealizedPnlPct,
                    openedAtEpochMs = position.openedAt.toEpochMilliseconds(),
                    updatedAtEpochMs = position.updatedAt.toEpochMilliseconds(),
                )
            },
        )
        val encoded = json.encodeToString(snapshot)
        if (encoded == lastLocalPositionStateSignature) return
        runCatching {
            val path = config.localPositionStatePath
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, encoded)
            lastLocalPositionStateSignature = encoded
        }.onFailure {
            logger.warn("Failed to persist local position state: {}", it.message)
        }
    }

    private suspend fun auditLocalRecoveryStateIfNeeded(
        now: Instant,
        balances: List<BalanceSnapshot>,
        persistedOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ) {
        if (!config.localPositionStateEnabled) return
        if (startupRecoveryAudited) return
        startupRecoveryAudited = true
        val localState = loadLocalPositionState()
        val heldAssets = balances
            .filterNot { it.asset.equals(referenceQuoteAsset(), ignoreCase = true) }
            .filter { (it.free.toDoubleOrZero() + it.locked.toDoubleOrZero()) > 0.0 }
            .map { it.asset.lowercase() }
            .toSet()
        val recoveredPositions = localState.managedPositions.filter { it.baseAsset.lowercase() in heldAssets }
        val recoveredOrders = localState.orders.filter { order ->
            order.side == com.kibot.shared.models.OrderSide.BUY &&
                order.pairId.pairAssets().baseAsset.lowercase() in heldAssets
        }
        if (recoveredPositions.isEmpty() && recoveredOrders.isEmpty()) return
        val missingOrderCoverage = recoveredPositions.any { recoveredState ->
            persistedOrders.none { it.pairId.value.equals(recoveredState.pairId, ignoreCase = true) }
        }
        val summary = when {
            recoveredPositions.isNotEmpty() -> recoveredPositions.joinToString(", ") {
                "${it.pairId}@${formatDecimal(it.averageEntryPrice.toDoubleOrNull() ?: 0.0, 8)}"
            }
            else -> recoveredOrders.joinToString(", ") {
                "${it.pairId.value}@${formatDecimal(it.price.toDoubleOrZero(), 8)}"
            }
        }
        val statusMessage = if (missingOrderCoverage) {
            "Recovery audit: local state temukan holding aktif $summary. Bot pakai snapshot lokal sebagai cadangan startup."
        } else {
            "Recovery audit: holding aktif $summary tervalidasi dari state lokal + order sink."
        }
        repository.noteStatus(statusMessage)
        appendAuditLog(
            level = LogLevel.INFO,
            category = "LOCAL_RECOVERY",
            message = "$statusMessage age=${(now.toEpochMilliseconds() - localState.observedAtEpochMs).coerceAtLeast(0L)}ms.",
        )
    }

    private fun com.kibot.shared.models.PairId.belongsToAvoidFamily(
        families: List<String>,
    ): Boolean {
        if (families.isEmpty()) return false
        val base = value.substringBefore('_').lowercase()
        return families.any { family ->
            family == base || value.contains(family, ignoreCase = true)
        }
    }

    private fun buildAdaptiveTradeAutomationCoordinator(
        cycle: com.kibot.core.StrategyCycleResult,
    ): TradeAutomationCoordinator {
        if (!config.enableExecutionAiAssist) return tradeAutomationCoordinator
        val pursuit = dailyTargetPursuitBrain.evaluate(
            cycle = cycle,
            adaptiveAiPolicy = cachedAdaptiveAiPolicy,
            now = Clock.System.now(),
        )
        val enforcementMemory = targetEnforcementMemory
        val adjustments = cachedAdaptiveAiPolicy?.adjustments
        val executionHints = cachedAdaptiveAiPolicy?.executionHints ?: AdaptiveAiExecutionHints()
        if (adjustments == null && !pursuit.active && executionHints.replacementHints.isEmpty()) return tradeAutomationCoordinator
        val aiAdjustments = adjustments ?: AdaptiveAiAdjustments()
        val repeatedHourlyPenalty = enforcementMemory.consecutiveHourlyMisses.coerceIn(0, 4)
        val repeatedCheckpointPenalty = enforcementMemory.consecutiveCheckpointMisses.coerceIn(0, 3)
        val aiReplacementPressure = if (executionHints.replacementHints.isNotEmpty()) 0.08 else 0.0
        val defaults = TradeAutomationConfig()
        val adjusted = TradeAutomationConfig(
            staleRotationMinAgeHours = (defaults.staleRotationMinAgeHours + aiAdjustments.rotationAgeHoursDelta + pursuit.rotationAgeHoursDelta - (repeatedHourlyPenalty * 0.03) - (repeatedCheckpointPenalty * 0.04) - aiReplacementPressure)
                .coerceAtLeast(0.50),
            staleRotationMinScoreGap = (defaults.staleRotationMinScoreGap + aiAdjustments.rotationScoreGapDelta + pursuit.rotationScoreGapDelta)
                .coerceIn(0.05, 0.14),
            loserRotationMinAgeHours = (defaults.loserRotationMinAgeHours + ((aiAdjustments.rotationAgeHoursDelta + pursuit.rotationAgeHoursDelta) * 0.75) - (repeatedHourlyPenalty * 0.02) - (repeatedCheckpointPenalty * 0.03) - (aiReplacementPressure * 0.75))
                .coerceAtLeast(0.35),
            loserRotationMinScoreGap = (defaults.loserRotationMinScoreGap + aiAdjustments.rotationScoreGapDelta + pursuit.rotationScoreGapDelta)
                .coerceIn(0.04, 0.10),
            maxStaleLossPctForTimeExit = (defaults.maxStaleLossPctForTimeExit + 0.08 + (repeatedHourlyPenalty * 0.04) + (repeatedCheckpointPenalty * 0.06))
                .coerceIn(0.10, 0.42),
            partialTakeProfitMinPnlPct = (defaults.partialTakeProfitMinPnlPct + aiAdjustments.partialTakeProfitPnlDelta + pursuit.partialTakeProfitPnlDelta)
                .coerceIn(1.80, 2.8),
            minMeaningfulNonEmergencyExitProfitPct = (defaults.minMeaningfulNonEmergencyExitProfitPct + aiAdjustments.meaningfulExitProfitDelta + pursuit.meaningfulExitProfitDelta - (repeatedHourlyPenalty * 0.03) - (repeatedCheckpointPenalty * 0.05))
                .coerceIn(0.75, 0.95),
            breakoutWinnerRunMinPnlPct = (defaults.breakoutWinnerRunMinPnlPct + aiAdjustments.winnerRunPnlDelta + pursuit.winnerRunPnlDelta)
                .coerceIn(0.60, 1.2),
            speculativeWinnerRunMinPnlPct = (defaults.speculativeWinnerRunMinPnlPct + aiAdjustments.winnerRunPnlDelta + pursuit.winnerRunPnlDelta)
                .coerceIn(1.10, 1.8),
            staleUnderwaterKillMinAgeHours = (defaults.staleUnderwaterKillMinAgeHours - (repeatedHourlyPenalty * 0.03) - (repeatedCheckpointPenalty * 0.04) - aiReplacementPressure)
                .coerceAtLeast(0.50),
            staleUnderwaterKillMinScoreGap = (defaults.staleUnderwaterKillMinScoreGap - (repeatedHourlyPenalty * 0.01) - (repeatedCheckpointPenalty * 0.015) - (aiReplacementPressure * 0.10))
                .coerceIn(0.05, 0.10),
            staleUnderwaterKillMinNetUpgradePct = (defaults.staleUnderwaterKillMinNetUpgradePct - (repeatedHourlyPenalty * 0.06) - (repeatedCheckpointPenalty * 0.08) - (aiReplacementPressure * 0.9))
                .coerceIn(1.10, 1.30),
            tacticalStaleMaxAgeHours = (defaults.tacticalStaleMaxAgeHours - (repeatedHourlyPenalty * 0.25) - (repeatedCheckpointPenalty * 0.40) - (aiReplacementPressure * 4.0))
                .coerceIn(1.0, defaults.tacticalStaleMaxAgeHours),
        )
        return TradeAutomationCoordinator(config = adjusted)
    }

    private fun resolveReturnBaseline(
        history: List<com.kibot.shared.models.DailyEquityHistoryPoint>,
        currentDate: LocalDate,
        rangeStart: LocalDate,
        fallbackEquity: Double,
    ): Double {
        if (history.isEmpty()) return fallbackEquity
        val sorted = history.sortedBy { it.date }
        val inRange = sorted.filter { it.date >= rangeStart && it.date <= currentDate }
        val anchor = inRange.firstOrNull() ?: sorted.lastOrNull { it.date < rangeStart } ?: sorted.firstOrNull()
        return anchor?.openingEquityIdr?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: anchor?.currentEquityIdr?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?: fallbackEquity
    }

    private fun resolveMonthlyReturnBaseline(
        currentDate: LocalDate,
        currentEquity: Double,
        fallbackEquity: Double,
    ): Double {
        resolvePnlResetBaseline(currentDate)?.let { return it }
        if (currentEquity <= 0.0) return fallbackEquity
        val currentMonthKey = monthKey(currentDate)
        val existing = monthlyPnlAnchor
        if (existing != null && existing.monthKey == currentMonthKey && existing.anchorEquityIdr > 0.0) {
            return existing.anchorEquityIdr
        }
        val snapshot = MonthlyPnlAnchorSnapshot(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            monthKey = currentMonthKey,
            anchorEquityIdr = currentEquity,
            observedAtEpochMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        monthlyPnlAnchor = snapshot
        persistMonthlyPnlAnchor(snapshot)
        repository.noteStatus("Monthly PnL reset anchor diset ke ${formatMonetary(currentEquity)} untuk ${currentMonthKey}.")
        return currentEquity
    }

    private fun resolvePnlResetBaseline(currentDate: LocalDate): Double? {
        val anchor = pnlResetAnchor ?: return null
        val anchorDate = kotlinx.datetime.Instant.fromEpochMilliseconds(anchor.observedAtEpochMs)
            .toLocalDateTime(TimeZone.of("Asia/Jakarta"))
            .date
        if (anchorDate != currentDate) return null
        return anchor.anchorEquityIdr.takeIf { it > 0.0 }
    }

    private fun monthKey(date: LocalDate): String = "%04d-%02d".format(date.year, date.monthNumber)

    private fun monthlyPnlAnchorPath(): java.nio.file.Path = config.monthlyPnlAnchorPath

    private fun pnlResetAnchorPath(): java.nio.file.Path = config.pnlResetAnchorPath

    private fun loadPnlResetAnchor(): PnlResetAnchorSnapshot? {
        val path = pnlResetAnchorPath()
        return runCatching {
            if (!Files.exists(path)) return null
            json.decodeFromString<PnlResetAnchorSnapshot>(Files.readString(path))
        }.getOrElse {
            logger.warn("Failed to load manual PnL reset anchor: {}", it.message)
            null
        }
    }

    private fun persistPnlResetAnchor(snapshot: PnlResetAnchorSnapshot) {
        val encoded = json.encodeToString(snapshot)
        if (encoded == lastPnlResetAnchorSignature) return
        runCatching {
            val path = pnlResetAnchorPath()
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, encoded)
            lastPnlResetAnchorSignature = encoded
        }.onFailure {
            logger.warn("Failed to persist manual PnL reset anchor: {}", it.message)
        }
    }

    private fun setManualPnlResetAnchor(currentEquity: Double, reason: String = "manual_topup_reset") {
        val snapshot = PnlResetAnchorSnapshot(
            botId = config.controlPlane.botId.value,
            deviceId = config.device.deviceId.value,
            anchorEquityIdr = currentEquity,
            observedAtEpochMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            reason = reason,
        )
        pnlResetAnchor = snapshot
        persistPnlResetAnchor(snapshot)
        repository.noteStatus("Manual PnL reset anchor diset ke ${formatMonetary(currentEquity)}.")
    }

    private fun loadMonthlyPnlAnchor(): MonthlyPnlAnchorSnapshot? {
        val path = monthlyPnlAnchorPath()
        return runCatching {
            if (!Files.exists(path)) return null
            json.decodeFromString<MonthlyPnlAnchorSnapshot>(Files.readString(path))
        }.getOrElse {
            logger.warn("Failed to load monthly PnL anchor: {}", it.message)
            null
        }
    }

    private fun persistMonthlyPnlAnchor(snapshot: MonthlyPnlAnchorSnapshot) {
        val encoded = json.encodeToString(snapshot)
        if (encoded == lastMonthlyPnlAnchorSignature) return
        runCatching {
            val path = monthlyPnlAnchorPath()
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, encoded)
            lastMonthlyPnlAnchorSignature = encoded
        }.onFailure {
            logger.warn("Failed to persist monthly PnL anchor: {}", it.message)
        }
    }

    private fun startOfWeek(date: LocalDate): LocalDate {
        val offset = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> 0
            DayOfWeek.TUESDAY -> 1
            DayOfWeek.WEDNESDAY -> 2
            DayOfWeek.THURSDAY -> 3
            DayOfWeek.FRIDAY -> 4
            DayOfWeek.SATURDAY -> 5
            DayOfWeek.SUNDAY -> 6
        }
        return date.minus(DatePeriod(days = offset))
    }

    private data class PairAssetParts(
        val baseAsset: String,
        val quoteAsset: String,
    )

    private fun com.kibot.shared.models.PairId.pairAssets(): PairAssetParts {
        val parts = value.lowercase().split("_")
        val base = parts.getOrNull(0).orEmpty().ifBlank { value.lowercase() }
        val quote = parts.getOrNull(1).orEmpty().ifBlank { "idr" }
        return PairAssetParts(baseAsset = base, quoteAsset = quote)
    }

    private companion object {
        private const val staleEntryOrderMaxAgeMinutes = 10.0 / 60.0
        private const val stalePartialFillMaxAgeMinutes = 5.0 / 60.0
        private const val staleEntryOrderPairFlipGraceMinutes = 10.0 / 60.0
        private const val staleEntryOrderMaxDriftPct = 0.15
        private const val staleExitOrderMaxAgeMinutes = 10.0 / 60.0
        private const val staleExitOrderMaxDriftPct = 0.55
        private const val staleExitRepriceLossFloorPct = -0.35
        private const val blueChipMinDailyVolumeIdrDefault = 50_000_000.0
        private const val aListMinVolumeIdrDefault = 80_000_000.0
        private const val dynamicVipTtlMinutes = 20
        private const val dynamicVipMinShortTermSurgePct = 0.45
        private const val dynamicVipMinTradeActivityScore = 0.34
        private const val dynamicVipMinMediumTrendPct = 0.25
        private const val dynamicVipMinProjectedNetPct = 0.08
        private const val dynamicVipMarketEntryShortTermMinPct = 0.95
        private const val dynamicVipMarketEntryMaxSpreadPct = 2.2
        private const val dynamicVipMarketEntryMaxSlippagePct = 1.9
        private const val baselineMinProjectedNetPct = 0.04
        private const val chartGuardLookbackSeconds = 6 * 60 * 60L
        private const val chartGuardMinCandlesDefault = 18
        private const val chartGuardMinActiveCandlesDefault = 6
        private const val chartGuardMinDistinctCloseBucketsDefault = 4
        private const val chartGuardCheapNominalMaxPriceIdr = 25.0
        private const val chartGuardCheapNominalMinDistinctCloses = 10
        private const val chartGuardMinRangePct = 0.80
        private const val runtimeEnrichmentFocusPairs = 12
        private const val runtimeEnrichmentLookbackMinutes = 360
        private const val runtimeEnrichmentCacheTtlMs = 60_000L
        private const val spoofPulseWindowSamples = 6
        private const val aListPriorityScoreBoost = 8.0
        private const val aListInstantSignalCooldownMs = 2_500L
        private const val indodaxSummariesEndpoint = "https://indodax.com/api/summaries"
        private const val indodaxFocusRefreshIntervalMs = 180_000L
        private const val instantAnomalyMinPriceDelta15sPct = 0.55
        private const val instantAnomalyMinPriceDelta30sPct = 0.90
        private const val instantAnomalyVolumeMultiplier = 1.20
        private const val instantAnomalyMinTradeActivityScore = 0.42
        private const val instantAnomalyExpectedNetRelaxFactor = 0.72
        private const val instantAnomalyShortReturnRelaxFactor = 0.65
        private const val instantAnomalyConfidenceRelax = 0.16
        private const val dustUiHideMinValueIdr = 1_000.0
        private const val autonomousResolverIntervalMs = 5_000L
        private const val autonomousResolverStaleOrderMs = 10_000L
        private const val depthGuardMaxTopBookImpactPct = 0.30
        private val spoofSuspicionCooldown = 75.seconds
        private const val makerFirstMaxLatencyMs = 150L
        private const val aggressiveLimitFallbackLatencyMs = 1500L
        private const val entryBlockLatencyMs = 2300L
        private const val executionPolicyLogCooldownMinutes = 2L
        private const val leadLagFreshnessHighVelocityShortReturnPct = 2.2
        private const val leadLagSignalMaxAgeMillis = 2000L  // Increased from 500ms
        private const val leadLagSellWallConfirmMs = 2_200L
        private const val leadLagSellWallFastConfirmMs = 900L
        private const val leadLagFomoThresholdPct = 15.0
        private const val leadLagFomoCorrectionEntryPct = 4.0
        private const val leadLagFreshnessHighVelocityTradeScore = 0.72
        private const val leadLagAlarmTransportLatencyMs = 1200L
        private const val leadLagAlarmEndToEndLatencyMs = 2000L
        private const val leadLagAlarmCooldownMillis = 90_000L
        private const val leadLagTelemetryMaxPairs = 300
        private const val leadLagTelemetryKeepWindowHours = 6L
        private const val leadLagDetectorPriceWindowMs = 3_000L
        private const val leadLagDetectorVolumeBaselineWindowMs = 60_000L
        private const val leadLagDetectorMinPriceDeltaPct = 0.8
        private const val leadLagDetectorMinVolumeAnomalyMultiplier = 1.6
        private const val leadLagMicroPulseKeepMs = 70_000L
        private const val leadLagMicroPulseMaxSamplesPerPair = 180
        private const val leadLagMicroPulseMaxPairs = 1200
        private const val leadLagGradualKeepMs = 960_000L
        private const val leadLagGradualMaxSamplesPerPair = 960
        private const val leadLagGradualPulseMaxPairs = 1200
        private const val leadLagSlippageGuardQuoteBudgetIdr = 5_000_000.0
        private const val leadLagSlippageGuardMaxPct = 3.0
        private const val leadLagTrailingStopPct = 1.5
        private const val leadLagTrailingArmMinGainPct = 0.8
        private const val leaseLockdownRetryCooldownMs = 5_000L
        private const val hardStopLossPct = -3.5
        private const val sinBinHours = 3
        private const val crashGuardWindowMinutes = 15
        private const val crashGuardGlobalThreshold = 3
        private const val globalCooldownMinutes = 30
        private const val dustQuarantineMinValueIdr = 10_500.0
        private const val dustQuarantineReleaseMinValueIdr = 11_000.0
        private const val emergencyLiquidityMinIdr = 10_000.0
        private const val opportunityLiquidationMinIdr = 10_000.0
        private const val garbageNukeMinNotionalIdr = 5_000.0
        private const val hyperAggressiveTargetDailyPct = 25.0
        private const val hyperAggressiveSexyWindowMs = 60_000L
        private const val hyperAggressiveSexyMinPriceDeltaPct = 1.5
        private const val hyperAggressiveSexyMinVolumeAnomalyMultiplier = 2.5
        private const val hyperAggressiveSexyMinTradeActivityScore = 0.72
        private const val hyperAggressiveVolumeBaselineWindowMs = 60_000L
        private const val hyperAggressiveStagnantWindowMs = 180_000L
        private const val hyperAggressiveStagnantMaxMovePct = 0.5
        private const val hyperAggressiveTrailingStopPct = 1.5
        private const val hyperAggressiveTrailingArmMinGainPct = 0.8
        private const val hyperAggressiveMicroPulseKeepMs = 190_000L
        private const val hyperAggressiveMicroPulseMaxSamplesPerPair = 260
        private const val hyperAggressiveMicroPulseMaxPairs = 1400
        private val importantTimelineCategories = setOf(
            "BUY",
            "SELL",
            "LOSS",
            "PROFIT",
            "RISK",
            "GUARDRAIL",
            "ABORTED",
            "HEALTH",
            "AI",
        )
        private val importantSupabaseUploadCategories = setOf(
            "BUY",
            "SELL",
            "LOSS",
            "PROFIT",
            "RISK",
            "GUARDRAIL",
            "ABORTED",
            "HEARTBEAT",
            "LEAD_LAG_TELEMETRY",
            "LEAD_LAG_EXECUTION_REPORT",
        )
        private val hiddenStablePairs = setOf("usdt_idr", "usdc_idr", "indr_idr")
        private val aListPriorityBases = setOf(
            "doge",
            "pepe",
            "shib",
            "trx",
            "xlm",
            "ondo",
            "xrp",
            "ada",
            "matic",
            "sol",
            "link",
            "avax",
            "bnb",
            "arb",
            "sui",
            "inj",
        )
        private val garbageNukePairs = setOf(
            "h2o_idr",
            "rvm_idr",
            "mpro_idr",
            "dusk_idr",
            "fet_idr",
            "wlfi_idr",
            "kaito_idr",
            "plpa_idr",
            "xpr_idr",
            "xrp_idr",
        )
        private val activeOrderStatuses = setOf(
            com.kibot.shared.models.OrderStatus.CREATED,
            com.kibot.shared.models.OrderStatus.SUBMITTING,
            com.kibot.shared.models.OrderStatus.OPEN,
            com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED,
            com.kibot.shared.models.OrderStatus.CANCEL_REQUESTED,
            com.kibot.shared.models.OrderStatus.UNKNOWN,
        )
    }
}

private data class EntryRoutingDecision(
    val executionPlan: com.kibot.shared.models.ExecutionPlan?,
    val message: String? = null,
    val blockedReason: String? = null,
)

private fun EngineLeaseSnapshot?.isHeldBy(deviceId: DeviceId, now: Instant): Boolean {
    return this != null &&
        currentHolder == deviceId &&
        state == LeaseState.HELD &&
        now < expiresAt &&
        !conflictDetected
}
