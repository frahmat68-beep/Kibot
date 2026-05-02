package com.kibot.core

import com.kibot.shared.models.AiPairSupportHint
import com.kibot.shared.models.AiSupportCandidate
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.DistrustLabel
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.EngineHealthSnapshot
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.MarketOpportunitySnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionId
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.StrategySignal
import com.kibot.shared.models.StrategySignalType
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.TradingHorizon
import com.kibot.shared.models.WeeklyLearningSummary
import kotlinx.datetime.Clock

data class StrategyCycleResult(
    val portfolio: PortfolioSnapshot,
    val dailyRisk: DailyRiskSnapshot,
    val rankedPairs: List<PairScore>,
    val marketSnapshot: MarketOpportunitySnapshot,
    val healthDecision: EntryHealthDecision,
    val riskDecision: RiskDecision,
    val modeSnapshot: BotModeSnapshot,
    val deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
    val selectedSignal: StrategySignal?,
    val executionPlan: ExecutionPlan?,
    val topCandidate: PairId?,
    val distrustLabels: List<DistrustLabel>,
    val summary: List<String>,
    val entrySignals: List<StrategySignal> = emptyList(),
    val entryExecutionPlans: List<ExecutionPlan> = emptyList(),
)

class StrategyOrchestrator(
    private val pairSelector: PairSelector = PairSelector(),
    private val regimeAnalyzer: MarketRegimeAnalyzer = MarketRegimeAnalyzer(),
    private val healthAdvisor: HealthAdvisor = HealthAdvisor(),
    private val riskEngine: RiskEngine = RiskEngine(),
    private val botModeDecider: BotModeDecider = BotModeDecider(),
    private val deploymentEngine: CapitalDeploymentEngine = CapitalDeploymentEngine(),
    private val vetoService: VetoService = VetoService(),
    private val executionConfig: StrategyExecutionConfig = StrategyExecutionConfig(),
    private var riskConfig: RiskConfig = RiskConfig(),
    private val dualEngineConfig: DualEngineConfig = DualEngineConfig(),
) {
    fun updateRiskConfig(newConfig: RiskConfig) {
        this.riskConfig = newConfig
        riskEngine.updateConfig(newConfig)
        deploymentEngine.updateConfig(newConfig)
        healthAdvisor.updateConfig(newConfig)
    }

    private val recentPairExitTimestampsMs = mutableMapOf<PairId, Long>()
    private var lastObservedHeldPairs: Set<PairId> = emptySet()

    fun analyze(
        botId: BotId,
        balances: List<BalanceSnapshot>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        dailyRisk: DailyRiskSnapshot?,
        health: EngineHealthSnapshot,
        marketQuotes: List<MarketQuote>,
        pairSupportHints: List<AiPairSupportHint> = emptyList(),
        weeklySummary: WeeklyLearningSummary? = null,
        aiSoftAuditOnly: Boolean = false,
        aiConsensus: Double? = null,
    ): StrategyCycleResult {
        return analyzeWithContext(
            botId = botId,
            balances = balances,
            openOrders = openOrders,
            dailyRisk = dailyRisk,
            health = health,
            marketQuotes = marketQuotes,
            pairSupportHints = pairSupportHints,
            weeklySummary = weeklySummary,
            aiSoftAuditOnly = aiSoftAuditOnly,
            aiConsensus = aiConsensus,
            selectionContextOverride = null,
        )
    }

    fun analyzeWithContext(
        botId: BotId,
        balances: List<BalanceSnapshot>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        dailyRisk: DailyRiskSnapshot?,
        health: EngineHealthSnapshot,
        marketQuotes: List<MarketQuote>,
        pairSupportHints: List<AiPairSupportHint> = emptyList(),
        weeklySummary: WeeklyLearningSummary? = null,
        aiSoftAuditOnly: Boolean = false,
        aiConsensus: Double? = null,
        selectionContextOverride: PairSelectionContext? = null,
        pairHistoricalWinRate: Map<String, Double> = emptyMap(),
        pairHistoricalLossCount: Map<String, Int> = emptyMap(),
    ): StrategyCycleResult {
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val equity = estimatePortfolioValueReference(balances, marketQuotes)
        val syntheticPositions = deriveSyntheticPositions(balances, marketQuotes)
        val nowMs = Clock.System.now().toEpochMilliseconds()
        trackRecentPairExits(
            currentHeldPairs = syntheticPositions.map { it.pairId }.toSet(),
            observedAtEpochMs = nowMs,
        )
        val portfolio = PortfolioSnapshot(
            botId = botId,
            balances = balances,
            openOrders = openOrders,
            positions = syntheticPositions,
            totalEquityIdr = DecimalValue.fromDouble(equity),
            lastSyncedAt = kotlinx.datetime.Clock.System.now(),
        )
        val resolvedRisk = dailyRisk ?: fallbackDailyRisk(equity)
        val leadLagSignal = pairSupportHints
            .maxByOrNull { hint -> hint.supportBias - hint.cautionBias }
            ?.let {
                LeadLagSelectionSignal(
                    leadPairId = it.pairId,
                    leadSectorFamily = correlationFamily(it.pairId),
                    leadMomentumScore = it.supportBias,
                    fatigue = it.cautionBias > it.supportBias,
                )
            }
        val urgentEntryMode = leadLagSignal != null &&
            !leadLagSignal.fatigue &&
            leadLagSignal.leadMomentumScore >= 0.80
        val leadLagMaxSpreadPct = if (urgentEntryMode) 5.0 else 2.0
        val referenceQuoteAsset = executionConfig.referenceQuoteAsset
        val freeCashIdr = balances.firstOrNull { it.asset.equals(referenceQuoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: 0.0
        val dynamicAdditionalSlots = if (referenceQuoteAsset.equals("idr", ignoreCase = true)) {
            CapitalAllocationManager.calculateDynamicAdditionalSlots(freeCashIdr)
        } else {
            0
        }
        val selectionContext = selectionContextOverride?.copy(
            userBalanceIdr = equity,
            availableCashIdr = freeCashIdr,
            minimumExecutableNotionalIdr = executionConfig.minOrderNotionalIdr,
            basketCount = riskConfig.maxConcurrentPositions.coerceAtLeast(syntheticPositions.size + dynamicAdditionalSlots.coerceAtLeast(1)),
        ) ?: PairSelectionContext(
            userBalanceIdr = equity,
            availableCashIdr = freeCashIdr,
            minimumExecutableNotionalIdr = executionConfig.minOrderNotionalIdr,
            basketCount = riskConfig.maxConcurrentPositions.coerceAtLeast(syntheticPositions.size + dynamicAdditionalSlots.coerceAtLeast(1)),
            leadSectorFamily = leadLagSignal?.leadSectorFamily,
            leadPairId = leadLagSignal?.leadPairId?.value,
            leadMomentumScore = leadLagSignal?.leadMomentumScore ?: 0.0,
            leadSectorHotnessScore = leadLagSignal?.leadMomentumScore ?: 0.0,
            leadVolumeVelocityScore = if (urgentEntryMode) leadLagSignal?.leadMomentumScore ?: 0.0 else 0.0,
            urgentEntryMode = urgentEntryMode,
            maxSpreadPct = leadLagMaxSpreadPct,
            leadLagEnabled = leadLagSignal != null,
            pairHistoricalWinRate = pairHistoricalWinRate,
            pairHistoricalLossCount = pairHistoricalLossCount,
        )
        val rankedPairs = applyWeeklyLearningBias(
            rankedPairs = applySupportHints(
                rankedPairs = pairSelector.rank(marketQuotes, selectionContext),
                pairSupportHints = pairSupportHints,
            ),
            weeklySummary = weeklySummary,
            observedAt = Clock.System.now(),
        )
        val healthDecision = healthAdvisor.evaluate(health)
        val analyzedMarketSnapshot = regimeAnalyzer.analyze(
            quotes = marketQuotes,
            rankedPairs = rankedPairs,
            health = health,
            performanceMomentumScore = derivePerformanceMomentumScore(resolvedRisk, rankedPairs),
        )
        val marketSnapshot = applyMomentumOverride(
            marketSnapshot = analyzedMarketSnapshot,
            rankedPairs = rankedPairs,
            quoteByPair = quoteByPair,
            leadLagSignal = leadLagSignal,
            healthDecision = healthDecision,
        )
        val riskDecision = riskEngine.evaluate(
            portfolio = portfolio,
            dailyRisk = resolvedRisk,
            health = health,
        )
        val modeSnapshot = botModeDecider.decide(
            market = marketSnapshot,
            risk = riskDecision,
            healthDecision = healthDecision,
        )
        val baseDeploymentPlan = deploymentEngine.plan(
            portfolio = portfolio,
            rankedPairs = rankedPairs,
            risk = riskDecision,
            mode = modeSnapshot,
        )
        val deploymentPlan = if (freeCashIdr >= CapitalAllocationManager.MULTI_SLOT_TRIGGER_IDR) {
            val dynamicMaxActivePositions = maxOf(
                baseDeploymentPlan.maxActivePositions,
                syntheticPositions.size + dynamicAdditionalSlots,
            )
            baseDeploymentPlan.copy(
                allowNewEntries = modeSnapshot.tradingAllowed && riskDecision.allowNewEntries,
                maxActivePositions = dynamicMaxActivePositions,
                rationale = baseDeploymentPlan.rationale + listOf(
                    "Free cash ${freeCashIdr.toInt()} masih cukup untuk slot paralel, jadi kapasitas posisi diperluas dinamis.",
                ),
            )
        } else {
            baseDeploymentPlan
        }

        val entrySignals = buildSignals(
            rankedPairs = rankedPairs,
            quoteByPair = quoteByPair,
            marketQuotes = marketQuotes,
            balances = balances,
            positions = syntheticPositions,
            marketSnapshot = marketSnapshot,
            modeSnapshot = modeSnapshot,
            deploymentPlan = deploymentPlan,
            openOrders = openOrders,
            weeklySummary = weeklySummary,
            dailyRisk = resolvedRisk,
            observedAtEpochMs = nowMs,
            leadLagSignal = leadLagSignal,
            aiSoftAuditOnly = aiSoftAuditOnly,
            aiConsensus = aiConsensus,
            selectionContext = selectionContext,
        )
        val entryExecutionPlans = entrySignals
            .let { signals ->
                val rankedByPair = rankedPairs.associateBy { it.pairId }
                signals.mapNotNull { signal ->
                    signal.toExecutionPlan(
                        balances = balances,
                        positions = syntheticPositions,
                        quoteByPair = quoteByPair,
                        marketQuotes = marketQuotes,
                        deploymentPlan = deploymentPlan,
                        modeSnapshot = modeSnapshot,
                        rankedByPair = rankedByPair,
                    )
                }
            }
        val selectedSignal = entryExecutionPlans.firstOrNull()?.signal ?: entrySignals.firstOrNull()
        val executionPlan = entryExecutionPlans.firstOrNull()

        val distrustLabels = buildDistrustLabels(
            health = health,
            healthDecision = healthDecision,
            riskDecision = riskDecision,
            market = marketSnapshot,
            signal = selectedSignal,
        )
        val summary = buildSummary(
            market = marketSnapshot,
            mode = modeSnapshot,
            risk = riskDecision,
            signal = selectedSignal,
            liveGateReady = executionPlan != null,
        )

        return StrategyCycleResult(
            portfolio = portfolio,
            dailyRisk = resolvedRisk,
            rankedPairs = rankedPairs,
            marketSnapshot = marketSnapshot,
            healthDecision = healthDecision,
            riskDecision = riskDecision,
            modeSnapshot = modeSnapshot,
            deploymentPlan = deploymentPlan,
            selectedSignal = selectedSignal,
            executionPlan = executionPlan,
            topCandidate = deploymentPlan.candidates.firstOrNull()?.pairId,
            distrustLabels = distrustLabels,
            summary = summary,
            entrySignals = entrySignals,
            entryExecutionPlans = entryExecutionPlans,
        )
    }

    fun shortlistForSupport(
        marketQuotes: List<MarketQuote>,
        maxCandidates: Int = 6,
    ): List<AiSupportCandidate> {
        return pairSelector.shortlist(marketQuotes)
            .take(maxCandidates)
            .mapNotNull { pairScore ->
                val quote = marketQuotes.firstOrNull { it.pairId == pairScore.pairId } ?: return@mapNotNull null
                AiSupportCandidate(
                    pairId = pairScore.pairId,
                    pairTier = pairScore.pairTier,
                    preferredHorizon = pairScore.preferredHorizon,
                    rankingScore = pairScore.rankingScore,
                    marketOpportunityScore = pairScore.marketOpportunityScore,
                    liquidityScore = pairScore.liquidityScore,
                    spreadPct = quote.spreadPct,
                    estimatedSlippagePct = quote.estimatedSlippagePct,
                    trendQualityScore = pairScore.trendQualityScore,
                    holdabilityScore = pairScore.holdabilityScore,
                    lastPrice = quote.midPrice,
                )
            }
    }

    private fun buildSignals(
        rankedPairs: List<PairScore>,
        quoteByPair: Map<PairId, MarketQuote>,
        marketQuotes: List<MarketQuote>,
        balances: List<BalanceSnapshot>,
        positions: List<PositionSnapshot>,
        marketSnapshot: MarketOpportunitySnapshot,
        modeSnapshot: BotModeSnapshot,
        deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        weeklySummary: WeeklyLearningSummary?,
        dailyRisk: DailyRiskSnapshot?,
        observedAtEpochMs: Long,
        leadLagSignal: LeadLagSelectionSignal?,
        aiSoftAuditOnly: Boolean,
        aiConsensus: Double?,
        selectionContext: PairSelectionContext,
    ): List<StrategySignal> {
        if (!modeSnapshot.tradingAllowed || (!deploymentPlan.allowNewEntries && !deploymentPlan.allowRotation)) return emptyList()
        if (marketSnapshot.regime == MarketRegime.BREAKDOWN_PANIC) return emptyList()
        val urgentEntryMode = leadLagSignal != null &&
            !leadLagSignal.fatigue &&
            leadLagSignal.leadMomentumScore >= 0.80
        val pendingBuyPairs = openOrders
            .asSequence()
            .filter { it.side == OrderSide.BUY && it.status in activeBuyOrderStatuses }
            .map { it.pairId }
            .toSet()
        val heldPairs = positions
            .filter { it.state != PositionState.CLOSED }
            .map { it.pairId }
            .toSet()

        val rotationFundingAllowed = deploymentPlan.allowRotation && heldPairs.isNotEmpty()
        val parallelMomentumBiasActive = marketSnapshot.regime == MarketRegime.HIGH_VOLATILITY_MOMENTUM &&
            deploymentPlan.allowNewEntries &&
            deploymentPlan.maxActivePositions > heldPairs.size &&
            deploymentPlan.suggestedPerPositionBudgetIdr >= executionConfig.minOrderNotionalIdr
        val thresholds = resolveEntryThresholds(
            modeSnapshot = modeSnapshot,
            marketSnapshot = marketSnapshot,
            heldPairs = heldPairs,
            weeklySummary = weeklySummary,
            dailyRisk = dailyRisk,
            urgentEntryMode = urgentEntryMode,
            parallelMomentumBiasActive = parallelMomentumBiasActive,
        )
        if (thresholds.dailyProfitLockActive) return emptyList()
        val scoreFloor = 0.0
        val dominantPairId = deploymentPlan.candidates
            .take(2)
            .let { topCandidates ->
                val first = topCandidates.firstOrNull()
                val second = topCandidates.getOrNull(1)
                if (first != null && (second == null || (first.rankingScore - second.rankingScore) >= 0.07)) {
                    first.pairId
                } else {
                    null
                }
            }

        val chosenCandidates = deploymentPlan.candidates
            .take(executionConfig.candidateCount)
            .mapNotNull { candidate ->
                val pairScore = rankedPairs.firstOrNull { it.pairId == candidate.pairId } ?: return@mapNotNull null
                val quote = quoteByPair[candidate.pairId] ?: return@mapNotNull null
                val pairAssets = candidate.pairId.assets()
                if (pairAssets.quoteAsset !in executionConfig.executionAllowedQuoteAssets) return@mapNotNull null
                val hasFunding = hasFundedQuoteAsset(
                    pairId = candidate.pairId,
                    balances = balances,
                    marketQuotes = marketQuotes,
                    targetBudgetIdr = deploymentPlan.suggestedPerPositionBudgetIdr,
                )
                if (candidate.pairId in heldPairs || candidate.pairId in pendingBuyPairs) return@mapNotNull null
                if (!hasFunding && !rotationFundingAllowed) return@mapNotNull null
                if (isPairInReentryCooldown(candidate.pairId, observedAtEpochMs)) return@mapNotNull null
                if (!selectionContext.bypassVetoService && vetoService.shouldVetoEntry(
                        candidate = pairScore,
                        quote = quote,
                        leadLagSignal = leadLagSignal,
                        priceBandAllowed = true,
                        softAuditOnly = aiSoftAuditOnly,
                        aiConsensus = aiConsensus,
                    )
                ) {
                    return@mapNotNull null
                }

                val setupReadiness = deriveSetupReadiness(
                    pairScore = pairScore,
                    quote = quote,
                    marketSnapshot = marketSnapshot,
                    baseOpportunityFloor = thresholds.minOpportunityScore,
                    urgentEntryMode = urgentEntryMode,
                    parallelMomentumBiasActive = thresholds.parallelMomentumBiasActive,
                ) ?: return@mapNotNull null

                if (!selectionContext.bypassRankingFloor && pairScore.rankingScore < thresholds.minRankingScore) return@mapNotNull null

                val selectionScore = scoreEntryCandidate(
                    pairScore = pairScore,
                    quote = quote,
                    marketSnapshot = marketSnapshot,
                    modeSnapshot = modeSnapshot,
                    setupReadiness = setupReadiness,
                    dominantPairId = dominantPairId,
                    targetBudgetIdr = deploymentPlan.suggestedPerPositionBudgetIdr,
                    weeklySummary = weeklySummary,
                    dailyProfitLockActive = thresholds.dailyProfitLockActive,
                    leadLagSignal = leadLagSignal,
                    aiSoftAuditOnly = aiSoftAuditOnly,
                    urgentEntryMode = urgentEntryMode,
                )
                if (selectionScore < scoreFloor) return@mapNotNull null

                CandidateSelection(
                    pairScore = pairScore,
                    quote = quote,
                    setupReadiness = setupReadiness,
                    selectionScore = selectionScore,
                )
            }
            .sortedByDescending { it.selectionScore }
            .take(executionConfig.maxExecutableEntriesPerCycle)

        return chosenCandidates.map { candidate ->
            candidate.toStrategySignal(
                marketSnapshot = marketSnapshot,
                modeSnapshot = modeSnapshot,
                dominantPairId = dominantPairId,
                productiveIdleBiasActive = thresholds.productiveIdleBiasActive,
                parallelMomentumBiasActive = thresholds.parallelMomentumBiasActive,
                leadLagSignal = leadLagSignal,
                aiSoftAuditOnly = aiSoftAuditOnly,
            )
        }
    }

    private fun CandidateSelection.toStrategySignal(
        marketSnapshot: MarketOpportunitySnapshot,
        modeSnapshot: BotModeSnapshot,
        dominantPairId: PairId?,
        productiveIdleBiasActive: Boolean,
        parallelMomentumBiasActive: Boolean,
        leadLagSignal: LeadLagSelectionSignal?,
        aiSoftAuditOnly: Boolean,
    ): StrategySignal {
        val selectedPairScore = pairScore
        val selectedQuote = quote
        val referencePrice = resolveEntryReferencePrice(
            quote = selectedQuote,
            pairScore = selectedPairScore,
            setupReadiness = setupReadiness,
        )
        val stopMultiplier = when {
            selectedPairScore.speculativePocket -> 0.978
            selectedPairScore.preferredHorizon == TradingHorizon.SWING -> 0.96
            else -> 0.985
        }
        val baseTakeProfitMultiplier = when {
            selectedPairScore.speculativePocket -> 1.03
            selectedPairScore.preferredHorizon == TradingHorizon.SWING -> 1.038
            setupReadiness.signalType == StrategySignalType.BREAKOUT_ENTRY -> 1.020
            else -> 1.016
        }
        val minimumTakeProfitMultiplier = 1.0 + ((1.0 - stopMultiplier) * 1.4)
        return StrategySignal(
            pairId = selectedPairScore.pairId,
            signalType = setupReadiness.signalType,
            confidence = selectionScore.coerceIn(0.0, 1.0),
            rationale = buildList {
                add("Pair ${selectedPairScore.pairId.value} masuk shortlist entry yang siap dieksekusi.")
                add(setupReadiness.rationale)
                if (aiSoftAuditOnly) {
                    add("AI sedang limited/offline, jadi keputusan entry tetap mengikuti sinyal teknikal dan momentum.")
                }
                if (dominantPairId == selectedPairScore.pairId) {
                    add("Kandidat ini unggul cukup jauh dari alternatif terdekat, jadi modal tidak dipaksa menyebar.")
                }
                if (selectedPairScore.speculativePocket) {
                    add("Trade ini masuk sleeve spekulatif, jadi eksposurnya dibatasi keras dan tidak boleh jadi posisi utama.")
                }
                if (productiveIdleBiasActive) {
                    add("Modal sedang idle, jadi threshold entry sedikit dilonggarkan pada kandidat yang benar-benar kuat.")
                }
                if (parallelMomentumBiasActive) {
                    add("Slot paralel momentum aktif: masih ada free cash dan shortlist sehat, jadi kandidat kedua boleh ditembak tanpa menunggu holding lama keluar.")
                }
                if (leadLagSignal?.fatigue == true) {
                    add(
                        if (aiSoftAuditOnly) {
                            "KiBot sedang soft-audit, jadi fatigue hanya jadi catatan dan bukan veto keras."
                        } else {
                            "KiBot mulai fatigue, jadi sinyal baru harus diperlakukan lebih ketat."
                        },
                    )
                }
            },
            entryPrice = referencePrice,
            takeProfitPrice = DecimalValue.fromDouble(
                referencePrice.toDoubleOrZero() * maxOf(baseTakeProfitMultiplier, minimumTakeProfitMultiplier),
            ),
            stopPrice = DecimalValue.fromDouble(
                referencePrice.toDoubleOrZero() * stopMultiplier,
            ),
            setupType = setupReadiness.setupType,
            horizon = selectedPairScore.preferredHorizon,
            pairTier = selectedPairScore.pairTier,
            speculativePocket = selectedPairScore.speculativePocket,
            marketRegime = marketSnapshot.regime,
            edgeConfidence = modeSnapshot.edgeConfidence,
            expectedHoldingHours = setupReadiness.expectedHoldingHours,
            expectedNetProfitabilityPct = selectedPairScore.feeAdjustedEdgeScore,
        )
    }

    private fun resolveEntryThresholds(
        modeSnapshot: BotModeSnapshot,
        marketSnapshot: MarketOpportunitySnapshot,
        heldPairs: Set<PairId>,
        weeklySummary: WeeklyLearningSummary? = null,
        dailyRisk: DailyRiskSnapshot? = null,
        urgentEntryMode: Boolean = false,
        parallelMomentumBiasActive: Boolean = false,
    ): EntryThresholds {
        val baseRankingScore = when (modeSnapshot.mode) {
            BotMode.SAFE -> Double.MAX_VALUE
            BotMode.DEFENSIVE -> executionConfig.defensiveMinRankingScore
            BotMode.GROWTH -> executionConfig.growthMinRankingScore
            BotMode.ATTACK -> executionConfig.attackMinRankingScore
        }
        val dailyProfitLockActive = dailyRisk?.let { risk ->
            val opening = risk.openingEquityIdr.toDoubleOrZero().coerceAtLeast(1.0)
            val current = risk.currentEquityIdr.toDoubleOrZero().coerceAtLeast(0.0)
            ((current - opening) / opening) * 100.0 >= riskConfig.dailyProfitLockPct * 100.0
        } ?: false
        val productiveIdleBiasActive = heldPairs.isEmpty() &&
            modeSnapshot.mode in setOf(BotMode.GROWTH, BotMode.ATTACK) &&
            marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC &&
            marketSnapshot.marketOpportunityScore >= 0.57
        val adaptiveParallelBiasActive = productiveIdleBiasActive || parallelMomentumBiasActive
        val learningAggressionBias = when {
            weeklySummary == null -> 0.0
            weeklySummary.falseEntryRate <= 0.18 &&
                weeklySummary.productiveUtilizationPct <= 0.30 &&
                weeklySummary.missedOpportunityRate >= 0.20 -> 0.02
            weeklySummary.falseEntryRate <= 0.24 &&
                weeklySummary.productiveUtilizationPct <= 0.36 &&
                weeklySummary.missedOpportunityRate >= 0.14 -> 0.01
            else -> 0.0
        }
        val breakoutIdleBias = if (
            adaptiveParallelBiasActive &&
            marketSnapshot.marketOpportunityScore >= 0.62 &&
            marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC
        ) {
            0.03
        } else {
            0.0
        }
        val parallelMomentumRankingBias = if (parallelMomentumBiasActive) 0.05 else 0.0
        val parallelMomentumOpportunityBias = if (parallelMomentumBiasActive) 0.05 else 0.0
        val urgentRankingBias = if (urgentEntryMode) 0.06 else 0.0
        val urgentOpportunityBias = if (urgentEntryMode) 0.05 else 0.0
        val lockRankingFloor = if (dailyProfitLockActive) riskConfig.dailyProfitLockRankingScore else 0.0
        val lockOpportunityFloor = if (dailyProfitLockActive) riskConfig.dailyProfitLockOpportunityScore else 0.0
        return EntryThresholds(
            minRankingScore = (
                baseRankingScore -
                    if (adaptiveParallelBiasActive) executionConfig.productiveIdleRankingDelta else 0.0 -
                    learningAggressionBias -
                    breakoutIdleBias -
                    urgentRankingBias -
                    parallelMomentumRankingBias
                )
                .coerceAtLeast(lockRankingFloor),
            minOpportunityScore = (
                executionConfig.minExpectedOpportunityScore -
                    if (adaptiveParallelBiasActive) executionConfig.productiveIdleOpportunityDelta else 0.0 -
                    (learningAggressionBias * 0.75) -
                    (breakoutIdleBias * 0.70) -
                    urgentOpportunityBias -
                    parallelMomentumOpportunityBias
                ).coerceAtLeast(lockOpportunityFloor),
            productiveIdleBiasActive = productiveIdleBiasActive,
            parallelMomentumBiasActive = parallelMomentumBiasActive,
            dailyProfitLockActive = dailyProfitLockActive,
        )
    }

    private fun deriveSetupReadiness(
        pairScore: PairScore,
        quote: MarketQuote,
        marketSnapshot: MarketOpportunitySnapshot,
        baseOpportunityFloor: Double,
        urgentEntryMode: Boolean = false,
        parallelMomentumBiasActive: Boolean = false,
    ): SetupReadiness? {
        val setupType: com.kibot.shared.models.SetupType
        val signalType: StrategySignalType
        val adjustedOpportunityFloor: Double
        val expectedHoldingHours: Double
        val rationale: String

        when {
            parallelMomentumBiasActive &&
                marketSnapshot.regime == MarketRegime.HIGH_VOLATILITY_MOMENTUM &&
                quote.spreadPct <= 1.0 &&
                quote.estimatedSlippagePct <= 1.0 &&
                quote.bidDepthTop5Idr.toDoubleOrZero() >= 25_000.0 &&
                quote.askDepthTop5Idr.toDoubleOrZero() >= 25_000.0 &&
                quote.orderBookStabilityScore >= 0.42 &&
                pairScore.fillQualityScore >= 0.44 &&
                pairScore.trendQualityScore >= 0.46 &&
                quote.recentTradeActivityScore >= 0.42 &&
                pairScore.feeAdjustedEdgeScore >= -0.05 -> {
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - if (urgentEntryMode) 0.10 else 0.08).coerceAtLeast(0.0)
                expectedHoldingHours = 8.0
                rationale = "Slot paralel momentum aktif: spread, slippage, dan depth masih aman, jadi entry kedua boleh ikut arus tanpa menunggu posisi lama selesai."
            }

            pairScore.speculativePocket &&
                marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC &&
                speculativePocketAllowed(marketSnapshot) &&
                quote.shortTermReturnPct in executionConfig.breakoutAggressiveEntryMinShortTermReturnPct..220.0 &&
                quote.mediumTermReturnPct >= executionConfig.breakoutAggressiveEntryMinMediumTermReturnPct &&
                pairScore.fillQualityScore >= 0.58 &&
                pairScore.trendQualityScore >= 0.60 &&
                quote.recentTradeActivityScore >= 0.56 &&
                pairScore.feeAdjustedEdgeScore >= (executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct - 0.04) -> {
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - if (urgentEntryMode) 0.055 else 0.035).coerceAtLeast(0.0)
                expectedHoldingHours = 10.0
                rationale = "Sleeve spekulatif aktif: momentum breakout terlihat dominan, jadi bot boleh lebih cepat masuk dan winner diberi ruang lebih panjang."
            }

            quote.shortTermReturnPct >= executionConfig.breakoutAggressiveEntryMinShortTermReturnPct &&
                quote.mediumTermReturnPct >= executionConfig.breakoutAggressiveEntryMinMediumTermReturnPct &&
                pairScore.fillQualityScore >= 0.60 &&
                pairScore.trendQualityScore >= 0.62 &&
                quote.recentTradeActivityScore >= 0.58 &&
                pairScore.feeAdjustedEdgeScore >= executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct -> {
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = when (marketSnapshot.regime) {
                    MarketRegime.HIGH_VOLATILITY_MOMENTUM -> (baseOpportunityFloor - if (urgentEntryMode) 0.060 else 0.040)
                    MarketRegime.HEALTHY_UPTREND -> (baseOpportunityFloor - if (urgentEntryMode) 0.045 else 0.025)
                    MarketRegime.HEALTHY_SIDEWAYS -> (baseOpportunityFloor - if (urgentEntryMode) 0.030 else 0.015)
                    else -> baseOpportunityFloor
                }.coerceAtLeast(0.0)
                expectedHoldingHours = if (pairScore.preferredHorizon == TradingHorizon.SWING) 30.0 else 12.0
                rationale = if (marketSnapshot.regime == MarketRegime.HIGH_VOLATILITY_MOMENTUM) {
                    "Momentum override aktif: volatilitas tinggi dibaca sebagai arus searah, jadi breakout boleh dieksekusi selama spread dan depth tetap sehat."
                } else {
                    "Momentum eksplosif terlihat bersih, jadi bot boleh ambil breakout lebih tegas selama net edge tetap masuk akal."
                }
            }

            pairScore.preferredHorizon == TradingHorizon.SWING &&
                marketSnapshot.regime == MarketRegime.HEALTHY_UPTREND &&
                pairScore.holdabilityScore >= 0.64 &&
                pairScore.trendQualityScore >= 0.60 &&
                quote.mediumTermReturnPct >= 0.90 -> {
                setupType = com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - if (urgentEntryMode) 0.03 else 0.01).coerceAtLeast(0.0)
                expectedHoldingHours = 72.0
                rationale = "Regime uptrend dan holdability kuat, jadi swing continuation boleh diprioritaskan."
            }

                quote.spreadPct <= 0.38 &&
                quote.estimatedSlippagePct <= 0.38 &&
                pairScore.fillQualityScore >= 0.58 &&
                quote.shortTermReturnPct <= 0.90 &&
                marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC -> {
                setupType = com.kibot.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK
                signalType = StrategySignalType.MEAN_REVERSION_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - if (urgentEntryMode) 0.05 else 0.03).coerceAtLeast(0.0)
                expectedHoldingHours = 7.0
                rationale = "Spread dan fill sehat, jadi pullback taktis boleh dipakai saat harga sedang rehat sehat."
            }

            // Buy-low with confirmation: short-term dip, but medium trend and microstructure still healthy.
            quote.shortTermReturnPct in -3.8..-0.35 &&
                quote.mediumTermReturnPct >= 0.75 &&
                pairScore.trendQualityScore >= 0.57 &&
                pairScore.fillQualityScore >= 0.58 &&
                quote.recentTradeActivityScore >= 0.54 &&
                pairScore.feeAdjustedEdgeScore >= 0.40 &&
                marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC -> {
                setupType = com.kibot.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK
                signalType = StrategySignalType.MEAN_REVERSION_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - if (urgentEntryMode) 0.04 else 0.02).coerceAtLeast(0.0)
                expectedHoldingHours = 9.0
                rationale = "Harga sedang pullback jangka pendek tapi tren menengah tetap sehat, jadi bot boleh akumulasi bertahap saat diskon."
            }

            quote.mediumTermReturnPct >= 0.70 &&
                quote.shortTermReturnPct >= 0.18 -> {
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = when (marketSnapshot.regime) {
                    MarketRegime.HEALTHY_SIDEWAYS -> baseOpportunityFloor + if (urgentEntryMode) 0.0 else 0.015
                    MarketRegime.HIGH_VOLATILITY_UNCLEAR -> baseOpportunityFloor + if (urgentEntryMode) 0.015 else 0.03
                    MarketRegime.HIGH_VOLATILITY_MOMENTUM -> (baseOpportunityFloor - if (urgentEntryMode) 0.020 else 0.005)
                    else -> baseOpportunityFloor
                }.coerceAtMost(1.0)
                expectedHoldingHours = if (pairScore.preferredHorizon == TradingHorizon.SWING) 48.0 else 10.0
                rationale = if (marketSnapshot.regime == MarketRegime.HIGH_VOLATILITY_MOMENTUM) {
                    "Breakout continuation diprioritaskan karena arus momentum sudah jelas, jadi threshold dibuka sedikit tanpa melepas guard spread/depth."
                } else {
                    "Breakout continuation tetap boleh, tapi threshold diperketat saat market belum benar-benar nyaman."
                }
            }

            else -> return null
        }

        if (pairScore.marketOpportunityScore < adjustedOpportunityFloor) return null
        return SetupReadiness(
            setupType = setupType,
            signalType = signalType,
            expectedHoldingHours = expectedHoldingHours,
            rationale = rationale,
        )
    }

    private fun scoreEntryCandidate(
        pairScore: PairScore,
        quote: MarketQuote,
        marketSnapshot: MarketOpportunitySnapshot,
        modeSnapshot: BotModeSnapshot,
        setupReadiness: SetupReadiness,
        dominantPairId: PairId?,
        targetBudgetIdr: Double,
        weeklySummary: WeeklyLearningSummary?,
        dailyProfitLockActive: Boolean,
        leadLagSignal: LeadLagSelectionSignal?,
        aiSoftAuditOnly: Boolean,
        urgentEntryMode: Boolean,
    ): Double {
        val regimeBias = when {
            setupReadiness.setupType == com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION ->
                ((marketSnapshot.swingBiasScore - 0.50) * 0.10).coerceIn(-0.03, 0.03)
            setupReadiness.signalType == StrategySignalType.MEAN_REVERSION_ENTRY ->
                ((marketSnapshot.tacticalBiasScore - 0.50) * 0.10).coerceIn(-0.03, 0.03)
            marketSnapshot.regime == MarketRegime.HIGH_VOLATILITY_MOMENTUM &&
                setupReadiness.signalType == StrategySignalType.BREAKOUT_ENTRY ->
                0.035
            else ->
                if (marketSnapshot.regime == MarketRegime.HEALTHY_UPTREND) 0.02 else 0.0
        }
        val followThroughScore = when (setupReadiness.setupType) {
            com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION ->
                averageOf(pairScore.holdabilityScore, pairScore.trendQualityScore)
            com.kibot.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK ->
                averageOf(pairScore.fillQualityScore, pairScore.spreadScore, pairScore.slippageScore)
            else ->
                averageOf(pairScore.trendQualityScore, pairScore.historicalExpectancyScore)
        }
        val dominanceBonus = if (dominantPairId == pairScore.pairId) 0.035 else 0.0
        val affordableUnits = if (quote.bestAsk.toDoubleOrZero() > 0.0) {
            targetBudgetIdr / quote.bestAsk.toDoubleOrZero()
        } else {
            0.0
        }
        val affordabilityScore = when {
            affordableUnits >= 2_500.0 -> 1.0
            affordableUnits >= 500.0 -> 0.88
            affordableUnits >= 100.0 -> 0.76
            affordableUnits >= 25.0 -> 0.64
            affordableUnits >= 5.0 -> 0.52
            else -> 0.40
        }
        val affordableNominalBias = when {
            pairScore.speculativePocket -> 0.030
            pairScore.preferredHorizon == TradingHorizon.TACTICAL && affordableUnits >= 100.0 -> 0.015
            else -> 0.0
        }
        val setupLearningBias = weeklySummary
            ?.adaptationPlan
            ?.setupBias
            ?.get(setupReadiness.setupType.name)
            ?.coerceIn(-0.10, 0.10)
            ?.times(0.30)
            ?: 0.0
        val dailyLockBias = if (dailyProfitLockActive) {
            -0.24
        } else {
            0.0
        }
        val leadLagBias = when {
            leadLagSignal == null -> 0.0
            leadLagSignal.fatigue -> -0.14
            leadLagSignal.leadSectorFamily != null &&
                leadLagSignal.leadSectorFamily == correlationFamily(pairScore.pairId) -> 0.10
            leadLagSignal.leadPairId?.value?.lowercase() == pairScore.pairId.value.lowercase() -> 0.18
            else -> 0.0
        }
        val urgentEntryBias = when {
            leadLagSignal == null -> 0.0
            leadLagSignal.fatigue -> 0.0
            setupReadiness.signalType != StrategySignalType.BREAKOUT_ENTRY -> 0.0
            leadLagSignal.leadSectorFamily != null &&
                leadLagSignal.leadSectorFamily == correlationFamily(pairScore.pairId) -> 0.12
            leadLagSignal.leadPairId?.value?.lowercase() == pairScore.pairId.value.lowercase() -> 0.15
            else -> 0.04
        }
        val netEdgeScore = (pairScore.feeAdjustedEdgeScore / 2.0).coerceIn(0.0, 1.0)
        val momentumBonus = when {
            setupReadiness.signalType == StrategySignalType.BREAKOUT_ENTRY &&
                quote.shortTermReturnPct >= executionConfig.breakoutAggressiveEntryMinShortTermReturnPct &&
                quote.mediumTermReturnPct >= executionConfig.breakoutAggressiveEntryMinMediumTermReturnPct &&
                pairScore.feeAdjustedEdgeScore >= executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct &&
                modeSnapshot.mode == BotMode.ATTACK ->
                0.18
            setupReadiness.signalType == StrategySignalType.BREAKOUT_ENTRY &&
                quote.shortTermReturnPct >= executionConfig.breakoutAggressiveEntryMinShortTermReturnPct &&
                quote.mediumTermReturnPct >= executionConfig.breakoutAggressiveEntryMinMediumTermReturnPct &&
                pairScore.feeAdjustedEdgeScore >= executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct ->
                0.14
            setupReadiness.signalType == StrategySignalType.BREAKOUT_ENTRY &&
                pairScore.feeAdjustedEdgeScore >= executionConfig.marketEntryMinExpectedNetProfitPct ->
                0.07
            else -> 0.0
        }
        val breakoutAccelerationScore = breakoutAccelerationScore(pairScore, quote, setupReadiness)
        val breakoutAccelerationBonus = when {
            setupReadiness.signalType != StrategySignalType.BREAKOUT_ENTRY -> 0.0
            breakoutAccelerationScore >= 0.90 && modeSnapshot.mode == BotMode.ATTACK -> 0.20
            breakoutAccelerationScore >= 0.82 && modeSnapshot.mode == BotMode.ATTACK -> 0.15
            breakoutAccelerationScore >= 0.84 -> 0.09
            breakoutAccelerationScore >= 0.74 -> 0.05
            else -> 0.0
        }
        val attackVelocityBias = when {
            modeSnapshot.mode != BotMode.ATTACK -> 0.0
            setupReadiness.signalType != StrategySignalType.BREAKOUT_ENTRY -> 0.0
            pairScore.marketOpportunityScore >= 0.70 &&
                pairScore.feeAdjustedEdgeScore >= executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct &&
                quote.recentTradeActivityScore >= 0.72 ->
                0.05
            pairScore.marketOpportunityScore >= 0.64 &&
                quote.recentTradeActivityScore >= 0.66 ->
                0.02
            else -> 0.0
        }

        return weightedAverage(
            pairScore.rankingScore to 0.24,
            pairScore.marketOpportunityScore to 0.17,
            netEdgeScore to 0.22,
            pairScore.fillQualityScore to 0.12,
            pairScore.historicalExpectancyScore to 0.11,
            followThroughScore to 0.10,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.04,
            affordabilityScore to 0.02,
        ).let { base ->
            (
                base +
                    regimeBias +
                    dominanceBonus +
                    affordableNominalBias +
                    momentumBonus +
                    breakoutAccelerationBonus +
                    attackVelocityBias +
                    setupLearningBias +
                    dailyLockBias +
                    leadLagBias +
                    urgentEntryBias
                ).coerceIn(0.0, 1.0)
        }
    }

    private fun breakoutAccelerationScore(
        pairScore: PairScore,
        quote: MarketQuote,
        setupReadiness: SetupReadiness,
    ): Double {
        if (setupReadiness.signalType != StrategySignalType.BREAKOUT_ENTRY) return 0.0
        val shortTermIgnition = (shortTermReturnPct(quote) / 8.0).coerceIn(0.0, 1.0)
        val mediumFollowThrough = (quote.mediumTermReturnPct / 3.0).coerceIn(0.0, 1.0)
        val microstructureReadiness = averageOf(
            pairScore.fillQualityScore,
            pairScore.spreadScore,
            pairScore.slippageScore,
            quote.orderBookStabilityScore.coerceIn(0.0, 1.0),
        )
        val activitySurge = quote.recentTradeActivityScore.coerceIn(0.0, 1.0)
        val netEdgeReadiness = (pairScore.feeAdjustedEdgeScore / 2.6).coerceIn(0.0, 1.0)
        return weightedAverage(
            shortTermIgnition to 0.30,
            mediumFollowThrough to 0.16,
            pairScore.trendQualityScore to 0.18,
            microstructureReadiness to 0.16,
            activitySurge to 0.10,
            netEdgeReadiness to 0.10,
        ).coerceIn(0.0, 1.0)
    }

    private fun StrategySignal.toExecutionPlan(
        balances: List<BalanceSnapshot>,
        positions: List<PositionSnapshot>,
        quoteByPair: Map<PairId, MarketQuote>,
        marketQuotes: List<MarketQuote>,
        deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
        modeSnapshot: BotModeSnapshot,
        rankedByPair: Map<PairId, PairScore>,
    ): ExecutionPlan? {
        val debugPlan = System.getProperty("KIBOT_DEBUG_PLAN") == "true"
        fun fail(reason: String): ExecutionPlan? {
            if (debugPlan) {
                println("PLAN_FAIL ${pairId.value}: $reason")
            }
            return null
        }

        val quote = quoteByPair[pairId] ?: return fail("missing quote")
        val pairScore = rankedByPair[pairId]
        val pairParts = pairId.assets()
        if (pairParts.quoteAsset !in executionConfig.executionAllowedQuoteAssets) return fail("quote asset not allowed")
        val quoteAssetPriceIdr = quoteAssetReferencePrice(pairParts.quoteAsset, marketQuotes) ?: return fail("missing quote asset reference price")
        val quoteBalanceUnits = balances
            .firstOrNull { it.asset.equals(pairParts.quoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: 0.0
        val availableQuoteBudgetIdr = quoteBalanceUnits * quoteAssetPriceIdr
        val rawBudgetIdr = minOf(
            maxOf(deploymentPlan.suggestedPerPositionBudgetIdr, executionConfig.minOrderNotionalIdr),
            availableQuoteBudgetIdr,
        )
        val rotationReserveBudgetIdr = if (
            deploymentPlan.allowRotation &&
            positions.any { it.state != PositionState.CLOSED }
        ) {
            maxOf(
                deploymentPlan.suggestedPerPositionBudgetIdr,
                positions
                    .filter { it.state != PositionState.CLOSED }
                    .minOfOrNull {
                        (
                            (it.quantity.toDoubleOrZero() * it.averageEntryPrice.toDoubleOrZero()) +
                                it.unrealizedPnlIdr.toDoubleOrZero()
                            ).coerceAtLeast(0.0)
                    }
                    ?: 0.0,
            )
        } else {
            0.0
        }
        val rotationFundingActive =
            deploymentPlan.allowRotation &&
                positions.any { it.state != PositionState.CLOSED } &&
                availableQuoteBudgetIdr < executionConfig.minOrderNotionalIdr
        val marketBuySignalEligible = pairParts.quoteAsset == executionConfig.referenceQuoteAsset &&
            setupType == com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION &&
            confidence >= if (speculativePocket) {
                executionConfig.breakoutAggressiveEntryMinRankingScore
            } else {
                executionConfig.marketEntryMinRankingScore
            } &&
            expectedNetProfitabilityPct >= if (speculativePocket) {
                executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct
            } else {
                executionConfig.marketEntryMinExpectedNetProfitPct
            } &&
            quote.spreadPct <= executionConfig.marketEntryMaxSpreadPct &&
            quote.estimatedSlippagePct <= executionConfig.marketEntryMaxSlippagePct &&
            quote.recentTradeActivityScore >= executionConfig.marketEntryMinTradeActivityScore &&
            quote.trendQualityScore >= executionConfig.marketEntryMinTrendScore &&
            quote.quoteVolume24h.toDoubleOrZero() >= 80_000_000.0 &&
            (pairScore?.rankingScore ?: 0.0) >= 0.58 &&
            expectedNetProfitabilityPct >= maxOf(executionConfig.marketEntryMinExpectedNetProfitPct, 0.20) &&
            (speculativePocket || modeSnapshot.mode in setOf(BotMode.GROWTH, BotMode.ATTACK))
        val effectiveRawBudgetIdr = if (rotationFundingActive) {
            maxOf(rawBudgetIdr, rotationReserveBudgetIdr)
        } else {
            minOf(
                maxOf(rawBudgetIdr, rotationReserveBudgetIdr),
                availableQuoteBudgetIdr,
            )
        }
        val portfolioCorrelationPenalty = derivePortfolioCorrelationPenalty(
            pairId = pairId,
            quote = quote,
            positions = positions,
            quoteByPair = quoteByPair,
        )
        if (portfolioCorrelationPenalty >= 0.80) return fail("portfolio correlation penalty")
        val kellySizingMultiplier = pairScore?.kellyFraction
            ?.let { 0.38 + ((it / 0.35).coerceIn(0.0, 1.0) * 0.62) }
            ?: 0.58
        val toxicityBudgetPenalty = pairScore?.toxicityScore?.let { 1.0 - (it.coerceIn(0.0, 1.0) * 0.45) } ?: 1.0
        val portfolioDiversificationPenalty = 1.0 - (portfolioCorrelationPenalty * 0.55)
        val adjustedBudgetIdr = (effectiveRawBudgetIdr * kellySizingMultiplier * toxicityBudgetPenalty * portfolioDiversificationPenalty * (1.0 - executionConfig.entrySpendBufferPct))
            .coerceAtLeast(0.0)
        val bidDepthIdr = quote.bidDepthTop5Idr.toDoubleOrZero().coerceAtLeast(0.0)
        val liquidityImpactCapIdr = if (
            executionConfig.liquidityImpactReducerEnabled &&
            bidDepthIdr > 0.0
        ) {
            minOf(
                bidDepthIdr * executionConfig.liquidityImpactDepthCoverageRatio,
                bidDepthIdr * executionConfig.liquidityImpactMaxBudgetToBidDepthRatio,
            )
        } else {
            Double.POSITIVE_INFINITY
        }
        val budgetIdr = minOf(adjustedBudgetIdr, liquidityImpactCapIdr)
        if (budgetIdr < executionConfig.minOrderNotionalIdr && !marketBuySignalEligible) return fail("budget below min order notional")

        val projectedNetProfitIdr = budgetIdr * (expectedNetProfitabilityPct / 100.0)

        val priceInQuoteAsset = entryPrice?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: return fail("missing entry price")
        val budgetQuoteUnits = if (pairParts.quoteAsset == executionConfig.referenceQuoteAsset) {
            budgetIdr
        } else {
            budgetIdr / quoteAssetPriceIdr
        }
        val strongBreakoutMomentum = setupType == com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION &&
            shortTermReturnPct(quote) >= executionConfig.breakoutAggressiveEntryMinShortTermReturnPct &&
            quote.mediumTermReturnPct >= executionConfig.breakoutAggressiveEntryMinMediumTermReturnPct &&
            expectedNetProfitabilityPct >= executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct
        val microstructureScore = averageOf(
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0),
            quote.orderBookStabilityScore.coerceIn(0.0, 1.0),
            quote.fillQualityScore.coerceIn(0.0, 1.0),
        )
        val effectiveMaxSpreadPct = if (microstructureScore >= 0.72) {
            executionConfig.marketEntryMaxSpreadPct
        } else {
            minOf(executionConfig.marketEntryMaxSpreadPct, executionConfig.marketEntryTightSpreadPct)
        }
        val effectiveMaxSlippagePct = if (microstructureScore >= 0.72) {
            executionConfig.marketEntryMaxSlippagePct
        } else {
            minOf(executionConfig.marketEntryMaxSlippagePct, executionConfig.marketEntryTightSlippagePct)
        }
        val useMarketBuy =
            executionConfig.marketEntryEnabled &&
                pairParts.quoteAsset == executionConfig.referenceQuoteAsset &&
                setupType == com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION &&
                confidence >= if (speculativePocket) {
                    executionConfig.breakoutAggressiveEntryMinRankingScore
                } else {
                    executionConfig.marketEntryMinRankingScore
                } &&
                expectedNetProfitabilityPct >= if (strongBreakoutMomentum || speculativePocket) {
                    executionConfig.breakoutAggressiveEntryMinExpectedNetProfitPct
                } else {
                    executionConfig.marketEntryMinExpectedNetProfitPct
                } &&
                quote.spreadPct <= effectiveMaxSpreadPct &&
                quote.estimatedSlippagePct <= effectiveMaxSlippagePct &&
                quote.recentTradeActivityScore >= executionConfig.marketEntryMinTradeActivityScore &&
                quote.trendQualityScore >= executionConfig.marketEntryMinTrendScore &&
                (
                    speculativePocket ||
                        modeSnapshot.mode in setOf(BotMode.GROWTH, BotMode.ATTACK)
                )
        val useSidewaysMakerMode =
            executionConfig.sidewaysMakerModeEnabled &&
                !useMarketBuy &&
                marketRegime == MarketRegime.HEALTHY_SIDEWAYS &&
                pairParts.quoteAsset == executionConfig.referenceQuoteAsset &&
                quote.spreadPct in executionConfig.sidewaysMakerModeMinSpreadPct..executionConfig.sidewaysMakerModeMaxSpreadPct &&
                kotlin.math.abs(quote.zScoreCurrent) <= executionConfig.sidewaysMakerModeMaxZScoreAbs &&
                kotlin.math.abs(quote.vwapDistancePct) <= executionConfig.sidewaysMakerModeMaxVwapExtensionPct &&
                quote.recentTradeActivityScore >= executionConfig.sidewaysMakerModeMinTradeActivityScore &&
                confidence >= executionConfig.growthMinRankingScore
        val effectivePriceInQuoteAsset = if (useMarketBuy) {
            quote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 } ?: priceInQuoteAsset
        } else if (useSidewaysMakerMode) {
            quote.bestBid.toDoubleOrZero().takeIf { it > 0.0 } ?: priceInQuoteAsset
        } else {
            priceInQuoteAsset
        }
        val estimatedRoundTripCostPct = estimateExecutionRoundTripCostPct(
            quote = quote,
            useMarketBuy = useMarketBuy && !useSidewaysMakerMode,
            speculativePocket = speculativePocket,
        )
        val netEdgeAfterCostsPct = expectedNetProfitabilityPct - estimatedRoundTripCostPct
        val minimumNetEdgeAfterCostsPct = if (rotationFundingActive) {
            executionConfig.minNetEdgeAfterCostsBufferPct - 0.10
        } else {
            executionConfig.minNetEdgeAfterCostsBufferPct
        }
        val marketBuyBudgetOverride = marketBuySignalEligible && projectedNetProfitIdr >= 150.0
        if (netEdgeAfterCostsPct < minimumNetEdgeAfterCostsPct && !marketBuyBudgetOverride) return fail("net edge after costs below floor")
        val estimatedRoundTripCostIdr = budgetIdr * (estimatedRoundTripCostPct / 100.0)
        val minimumProfitToCostMultiplier = if (rotationFundingActive) {
            executionConfig.minProfitToCostMultiplier * 0.72
        } else {
            executionConfig.minProfitToCostMultiplier
        }
        val profitAfterFeesBufferIdr = if (rotationFundingActive) {
            executionConfig.minProfitAfterFeesBufferIdr * 0.55
        } else {
            executionConfig.minProfitAfterFeesBufferIdr
        }
        val dynamicNetProfitFloorIdr = maxOf(
            profitAfterFeesBufferIdr,
            budgetIdr * if (rotationFundingActive) {
                if (speculativePocket) 0.0062 else 0.0042
            } else {
                if (speculativePocket) 0.0085 else 0.0060
            },
            estimatedRoundTripCostIdr * minimumProfitToCostMultiplier,
        )
        val minimumRequiredNetProfitIdr = if (speculativePocket) {
            maxOf(
                if (rotationFundingActive) {
                    executionConfig.minExpectedNetProfitIdrSpeculative * 0.72
                } else {
                    executionConfig.minExpectedNetProfitIdrSpeculative
                },
                dynamicNetProfitFloorIdr,
            )
        } else {
            maxOf(
                if (rotationFundingActive) {
                    executionConfig.minExpectedNetProfitIdr * 0.72
                } else {
                    executionConfig.minExpectedNetProfitIdr
                },
                dynamicNetProfitFloorIdr,
            )
        }
        if (projectedNetProfitIdr < minimumRequiredNetProfitIdr && !marketBuyBudgetOverride) return fail("projected net profit below floor")
        val quantity = budgetQuoteUnits / effectivePriceInQuoteAsset
        if (quantity <= 0.0) return fail("non-positive quantity")

        return ExecutionPlan(
            signal = this,
            side = OrderSide.BUY,
            orderType = if (useMarketBuy) OrderType.MARKET else OrderType.LIMIT,
            quantity = DecimalValue.fromDouble(quantity),
            limitPrice = if (useMarketBuy) null else DecimalValue.fromDouble(effectivePriceInQuoteAsset),
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
            postOnlyPreferred = !useMarketBuy || useSidewaysMakerMode,
            expectedNetEdgePct = expectedNetProfitabilityPct,
            botMode = modeSnapshot.mode,
            riskLadderLevel = modeSnapshot.riskLadderLevel,
            pairRankingScore = confidence,
            speculativePocket = speculativePocket,
        )
    }

    private fun derivePortfolioCorrelationPenalty(
        pairId: PairId,
        quote: MarketQuote,
        positions: List<PositionSnapshot>,
        quoteByPair: Map<PairId, MarketQuote>,
    ): Double {
        val openPositions = positions.filter { it.state != PositionState.CLOSED && it.pairId != pairId }
        if (openPositions.isEmpty()) return 0.0
        val candidateFamily = correlationFamily(pairId)
        return openPositions.maxOf { position ->
            val heldQuote = quoteByPair[position.pairId]
            val sameFamily = candidateFamily == correlationFamily(position.pairId)
            val familyAffinity = if (sameFamily) 1.0 else 0.0
            val sectorAffinity = if (sameFamily) 0.88 else averageOf(
                quote.sectorMomentumScore.coerceIn(0.0, 1.0),
                heldQuote?.sectorMomentumScore?.coerceIn(0.0, 1.0) ?: 0.5,
            )
            val betaAffinity = averageOf(
                quote.globalCorrelationScore.coerceIn(0.0, 1.0),
                heldQuote?.globalCorrelationScore?.coerceIn(0.0, 1.0) ?: 0.5,
            )
            val returnAlignment = heldQuote?.let {
                val shortAlignment = 1.0 - kotlin.math.abs(quote.shortTermReturnPct - it.shortTermReturnPct).coerceAtMost(6.0) / 6.0
                val mediumAlignment = 1.0 - kotlin.math.abs(quote.mediumTermReturnPct - it.mediumTermReturnPct).coerceAtMost(8.0) / 8.0
                averageOf(shortAlignment, mediumAlignment).coerceIn(0.0, 1.0)
            } ?: 0.45
            val assetOverlap = if (pairId.value.substringBefore('_').equals(position.pairId.value.substringBefore('_'), ignoreCase = true)) 1.0 else 0.0
            weightedAverage(
                assetOverlap to 0.10,
                familyAffinity to 0.50,
                sectorAffinity to 0.15,
                betaAffinity to 0.15,
                returnAlignment to 0.10,
            )
        }.coerceIn(0.0, 1.0)
    }

    private fun correlationFamily(pairId: PairId): String {
        val base = pairId.value.substringBefore('_').lowercase()
        return when (base) {
            // Meme coins - high BTC correlation but extreme volatility
            in setOf("doge", "shib", "pepe", "floki", "bonk", "wif", "pippin", "neiro", "turbo", "mog", "bome", "brett", "dog", "popcat") -> "meme"
            // AI/ML tokens - moderate correlation with ETH ecosystem
            in setOf("fet", "agix", "ocean", "render", "tao", "ai16z", "grt", "worldcoin", "rndr") -> "ai"
            // Layer 1/2 - high correlation with ETH
            in setOf("sol", "ada", "avax", "matic", "arb", "op", "eth", "near", "ont", "trx", "xlm", "plpa", "kaito", "dot", "atom", "inj", "sui", "sei", "apt", "ftm", "klay", "cro", "zil") -> "l1_l2"
            // BTC ecosystem
            in setOf("btc", "stx", "ordi", "sats", "rune", "tia") -> "btc"
            // DeFi - high ETH correlation
            in setOf("uni", "aave", "link", "snx", "crv", "mkr", "comp", "ldo", "gmx", "dydx", "1inch", "cake", "sushi", "pendle", "eigen") -> "defi"
            // Gaming/Metaverse
            in setOf("axs", "sand", "mana", "gala", "imx", "ilv", "enjin", "alice", "rmrk", "magic") -> "gaming"
            // Micro-caps (Indodax specific) - uncorrelated but volatile
            in setOf("sto", "drx", "d", "cast", "one", "hot", "reef", "btt", "win", "xec", "luna2", "ustc") -> "microcap"
            else -> base
        }
    }

    private fun estimateExecutionRoundTripCostPct(
        quote: MarketQuote,
        useMarketBuy: Boolean,
        speculativePocket: Boolean,
    ): Double {
        val feePct = when {
            useMarketBuy || speculativePocket -> 0.92
            else -> 0.62
        }
        val slippagePct = quote.estimatedSlippagePct.coerceAtLeast(0.0) * if (useMarketBuy) 1.10 else 0.85
        val spreadPct = quote.spreadPct.coerceAtLeast(0.0) * if (useMarketBuy) 0.65 else 0.50
        val stabilityPenaltyPct = ((1.0 - quote.orderBookStabilityScore.coerceIn(0.0, 1.0)) * 0.24)
        return feePct + slippagePct + spreadPct + stabilityPenaltyPct + 0.12
    }

    private fun buildDistrustLabels(
        health: EngineHealthSnapshot,
        healthDecision: EntryHealthDecision,
        riskDecision: RiskDecision,
        market: MarketOpportunitySnapshot,
        signal: StrategySignal?,
    ): List<DistrustLabel> = buildList {
        if (health.syncHealth == SyncHealth.BROKEN) add(DistrustLabel.SYNC_MISMATCH)
        if (!health.websocketHealthy) add(DistrustLabel.FEED_DEGRADED)
        if (riskDecision.riskLadderLevel in setOf(RiskLadderLevel.STOP_NEW_ENTRIES, RiskLadderLevel.HARD_STOP)) {
            add(DistrustLabel.RISK_LADDER_BLOCKED)
        }
        if (market.edgeConfidence == EdgeConfidence.LOW) add(DistrustLabel.EDGE_CONFIDENCE_LOW)
        if (health.lastError?.contains("ambiguous", ignoreCase = true) == true) add(DistrustLabel.AMBIGUOUS_ORDER_STATE)
        if (signal == null && !healthDecision.tradingAllowed) add(DistrustLabel.EXECUTION_QUALITY_BAD)
    }.distinct()

    private fun buildSummary(
        market: MarketOpportunitySnapshot,
        mode: BotModeSnapshot,
        risk: RiskDecision,
        signal: StrategySignal?,
        liveGateReady: Boolean,
    ): List<String> = buildList {
        add("Regime ${market.regime.name}, mode ${mode.mode.name}, edge ${mode.edgeConfidence.name}.")
        add("Risk ladder ${risk.riskLadderLevel.name}, profit protection ${risk.profitProtectionStatus.name}.")
        if (market.regime == MarketRegime.HIGH_VOLATILITY_MOMENTUM) {
            add("Momentum override aktif: volatilitas tinggi dianggap tren searah karena spread, depth, dan tape masih sehat.")
        }
        if (signal != null) add("Kandidat entry ${signal.pairId.value} ${signal.horizon.name.lowercase()} sudah lolos gate analisa.")
        if (signal == null) add("Belum ada setup yang cukup layak untuk dipakai modal.")
        if (!liveGateReady) add("Gate eksekusi live masih tertutup atau belum cukup aman.")
    }

    private fun applyMomentumOverride(
        marketSnapshot: MarketOpportunitySnapshot,
        rankedPairs: List<PairScore>,
        quoteByPair: Map<PairId, MarketQuote>,
        leadLagSignal: LeadLagSelectionSignal?,
        healthDecision: EntryHealthDecision,
    ): MarketOpportunitySnapshot {
        if (marketSnapshot.regime != MarketRegime.HIGH_VOLATILITY_UNCLEAR) return marketSnapshot
        if (!healthDecision.tradingAllowed) return marketSnapshot
        val overrideCandidate = rankedPairs
            .asSequence()
            .filter { it.allowed }
            .mapNotNull { pairScore ->
                val quote = quoteByPair[pairScore.pairId] ?: return@mapNotNull null
                if (!isMomentumOverrideCandidate(pairScore, quote, leadLagSignal)) return@mapNotNull null
                pairScore.pairId
            }
            .firstOrNull()
            ?: return marketSnapshot
        return marketSnapshot.copy(
            regime = MarketRegime.HIGH_VOLATILITY_MOMENTUM,
            tacticalBiasScore = maxOf(marketSnapshot.tacticalBiasScore, 0.68),
            swingBiasScore = maxOf(marketSnapshot.swingBiasScore, 0.28),
            rationale = (
                marketSnapshot.rationale +
                    "Momentum override aktif pada ${overrideCandidate.value}: lonjakan harga/volume searah dengan spread <= 1% dan orderbook tetap hidup."
                ).distinct(),
        )
    }

    private fun isMomentumOverrideCandidate(
        pairScore: PairScore,
        quote: MarketQuote,
        leadLagSignal: LeadLagSelectionSignal?,
    ): Boolean {
        val spreadHealthy = quote.spreadPct in 0.0..1.0
        val depthHealthy =
            quote.bidDepthTop5Idr.toDoubleOrZero() > 0.0 &&
                quote.askDepthTop5Idr.toDoubleOrZero() > 0.0
        val priceIgnition =
            shortTermReturnPct(quote) >= (executionConfig.breakoutAggressiveEntryMinShortTermReturnPct * 0.72) &&
                quote.mediumTermReturnPct >= maxOf(0.18, executionConfig.breakoutAggressiveEntryMinMediumTermReturnPct * 0.70)
        val tapeHealthy =
            quote.recentTradeActivityScore >= 0.68 &&
                quote.orderBookStabilityScore >= 0.58 &&
                quote.estimatedSlippagePct <= 1.0
        val edgeHealthy =
            pairScore.feeAdjustedEdgeScore >= (executionConfig.marketEntryMinExpectedNetProfitPct * 0.70) &&
                pairScore.trendQualityScore >= 0.54
        val leadLagSupport = when {
            leadLagSignal == null -> true
            leadLagSignal.fatigue -> false
            leadLagSignal.leadPairId?.value?.equals(pairScore.pairId.value, ignoreCase = true) == true -> true
            leadLagSignal.leadSectorFamily != null &&
                leadLagSignal.leadSectorFamily == correlationFamily(pairScore.pairId) -> true
            else -> leadLagSignal.leadMomentumScore >= 0.78
        }
        return spreadHealthy && depthHealthy && priceIgnition && tapeHealthy && edgeHealthy && leadLagSupport
    }

    private fun applySupportHints(
        rankedPairs: List<PairScore>,
        pairSupportHints: List<AiPairSupportHint>,
    ): List<PairScore> {
        if (pairSupportHints.isEmpty()) return rankedPairs
        val hintsByPair = pairSupportHints.associateBy { it.pairId }
        return rankedPairs
            .map { pairScore ->
                val hint = hintsByPair[pairScore.pairId] ?: return@map pairScore
                if (!pairScore.allowed) return@map pairScore
                val netBias = (hint.supportBias - hint.cautionBias)
                    .coerceIn(-MAX_EXTERNAL_SUPPORT_BIAS, MAX_EXTERNAL_SUPPORT_BIAS)
                pairScore.copy(
                    rankingScore = (pairScore.rankingScore + netBias).coerceIn(0.0, 1.0),
                    marketOpportunityScore = (pairScore.marketOpportunityScore + (netBias * 0.8)).coerceIn(0.0, 1.0),
                )
            }
            .sortedWith(
                compareByDescending<PairScore> { it.pairTier == com.kibot.shared.models.PairTier.TIER_A }
                    .thenByDescending { it.rankingScore }
                    .thenByDescending { it.marketOpportunityScore }
                    .thenByDescending { it.fillQualityScore }
                    .thenByDescending { it.historicalExpectancyScore }
                    .thenByDescending { it.spreadScore + it.slippageScore },
            )
    }

    private fun applyWeeklyLearningBias(
        rankedPairs: List<PairScore>,
        weeklySummary: WeeklyLearningSummary?,
        observedAt: kotlinx.datetime.Instant,
    ): List<PairScore> {
        val adaptationPlan = weeklySummary?.adaptationPlan ?: return rankedPairs
        val whitelist = adaptationPlan.whitelistPairs.toSet()
        val blacklist = adaptationPlan.temporaryBlacklistPairs.toSet()
        val bestPairs = weeklySummary.bestPairs.toSet()
        val worstPairs = weeklySummary.worstPairs.toSet()
        val activeHours = adaptationPlan.activeHours.toSet()
        val currentHour = observedAt.toString().substring(11, 13).toIntOrNull() ?: 0
        val outsideActiveHoursPenalty = if (activeHours.isNotEmpty() && currentHour !in activeHours) 0.03 else 0.0

        return rankedPairs
            .map { pairScore ->
                if (!pairScore.allowed) return@map pairScore
                val bias = when (pairScore.pairId) {
                    in whitelist -> 0.05
                    in bestPairs -> 0.02
                    in blacklist -> -0.14
                    in worstPairs -> -0.10
                    else -> 0.0
                } - outsideActiveHoursPenalty
                if (bias == 0.0) return@map pairScore
                pairScore.copy(
                    rankingScore = (pairScore.rankingScore + bias).coerceIn(0.0, 1.0),
                    marketOpportunityScore = (pairScore.marketOpportunityScore + (bias * 0.75)).coerceIn(0.0, 1.0),
                    rejectionReasons = pairScore.rejectionReasons,
                )
            }
            .sortedWith(
                compareByDescending<PairScore> { it.pairTier == com.kibot.shared.models.PairTier.TIER_A }
                    .thenByDescending { it.rankingScore }
                    .thenByDescending { it.marketOpportunityScore }
                    .thenByDescending { it.fillQualityScore }
                    .thenByDescending { it.historicalExpectancyScore }
                    .thenByDescending { it.spreadScore + it.slippageScore },
            )
    }

    private fun hasFundedQuoteAsset(
        pairId: PairId,
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
        targetBudgetIdr: Double,
    ): Boolean {
        val parts = pairId.assets()
        val quoteBalance = balances.firstOrNull { it.asset.equals(parts.quoteAsset, ignoreCase = true) }?.free?.toDoubleOrZero() ?: 0.0
        val quoteAssetPrice = quoteAssetReferencePrice(parts.quoteAsset, marketQuotes) ?: return false
        return quoteBalance * quoteAssetPrice >= maxOf(targetBudgetIdr, executionConfig.minOrderNotionalIdr)
    }

    private fun quoteAssetReferencePrice(asset: String, marketQuotes: List<MarketQuote>): Double? {
        val normalizedAsset = asset.lowercase()
        val referenceQuoteAsset = executionConfig.referenceQuoteAsset.lowercase()
        if (normalizedAsset == referenceQuoteAsset) return 1.0

        val directPair = "${normalizedAsset}_${referenceQuoteAsset}"
        marketQuotes.firstOrNull { it.pairId.value.equals(directPair, ignoreCase = true) }
            ?.midPrice
            ?.toDoubleOrZero()
            ?.takeIf { it > 0.0 }
            ?.let { return it }

        if (referenceQuoteAsset != "idr") {
            marketQuotes.firstOrNull { it.pairId.value.equals("${normalizedAsset}_idr", ignoreCase = true) }
                ?.midPrice
                ?.toDoubleOrZero()
                ?.takeIf { it > 0.0 }
                ?.let { directIdr ->
                    val referenceIdr = marketQuotes.firstOrNull {
                        it.pairId.value.equals("${referenceQuoteAsset}_idr", ignoreCase = true)
                    }?.midPrice?.toDoubleOrZero()?.takeIf { price -> price > 0.0 }
                    if (referenceIdr != null) return directIdr / referenceIdr
                }
        }

        return null
    }

    private fun estimatePortfolioValueReference(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
    ): Double {
        return balances.sumOf { balance ->
            val totalUnits = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (balance.asset.equals(executionConfig.referenceQuoteAsset, ignoreCase = true)) {
                totalUnits
            } else {
                totalUnits * (quoteAssetReferencePrice(balance.asset.lowercase(), marketQuotes) ?: 0.0)
            }
        }.coerceAtLeast(0.0)
    }

    private fun deriveSyntheticPositions(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
    ): List<PositionSnapshot> {
        val now = kotlinx.datetime.Clock.System.now()
        return balances.mapNotNull { balance ->
            if (balance.asset.equals(executionConfig.referenceQuoteAsset, ignoreCase = true)) return@mapNotNull null
            val totalUnits = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (totalUnits <= 0.0) return@mapNotNull null
            val pairId = PairId("${balance.asset.lowercase()}_${executionConfig.referenceQuoteAsset.lowercase()}")
            val quote = marketQuotes.firstOrNull { it.pairId == pairId } ?: return@mapNotNull null
            val markValue = totalUnits * quote.midPrice.toDoubleOrZero()
            if (markValue < executionConfig.minOrderNotionalIdr) return@mapNotNull null
            PositionSnapshot(
                positionId = PositionId("synthetic-${balance.asset.lowercase()}"),
                pairId = pairId,
                baseAsset = balance.asset.lowercase(),
                quoteAsset = executionConfig.referenceQuoteAsset.lowercase(),
                state = PositionState.OPEN,
                quantity = DecimalValue.fromDouble(totalUnits),
                averageEntryPrice = quote.midPrice,
                realizedPnlIdr = DecimalValue.Zero,
                unrealizedPnlIdr = DecimalValue.Zero,
                horizon = if (quote.mediumTermReturnPct >= 1.0) TradingHorizon.SWING else TradingHorizon.TACTICAL,
                openedAt = now,
                updatedAt = now,
            )
        }
    }

    private fun fallbackDailyRisk(equityIdr: Double): DailyRiskSnapshot = DailyRiskSnapshot(
        openingEquityIdr = DecimalValue.fromDouble(equityIdr),
        currentEquityIdr = DecimalValue.fromDouble(equityIdr),
        realizedPnlIdr = DecimalValue.Zero,
        unrealizedPnlIdr = DecimalValue.Zero,
        drawdownPct = 0.0,
        hardDailyLossLimitPct = 0.05,
        hardStopTriggered = false,
        rebasePending = false,
        highWatermarkEquityIdr = DecimalValue.fromDouble(equityIdr),
    )

    private fun trackRecentPairExits(
        currentHeldPairs: Set<PairId>,
        observedAtEpochMs: Long,
    ) {
        val exitedPairs = lastObservedHeldPairs - currentHeldPairs
        exitedPairs.forEach { pairId ->
            recentPairExitTimestampsMs[pairId] = observedAtEpochMs
        }
        lastObservedHeldPairs = currentHeldPairs

        val ttlMs = (executionConfig.pairReentryCooldownSeconds.coerceAtLeast(10) * 1_000L)
        recentPairExitTimestampsMs.entries.removeIf { (_, ts) -> (observedAtEpochMs - ts) > ttlMs * 3 }
    }

    private fun isPairInReentryCooldown(
        pairId: PairId,
        observedAtEpochMs: Long,
    ): Boolean {
        val exitedAt = recentPairExitTimestampsMs[pairId] ?: return false
        val elapsedMs = (observedAtEpochMs - exitedAt).coerceAtLeast(0L)
        return elapsedMs < (executionConfig.pairReentryCooldownSeconds.coerceAtLeast(10) * 1_000L)
    }

    private fun derivePerformanceMomentumScore(
        dailyRisk: DailyRiskSnapshot,
        rankedPairs: List<PairScore>,
    ): Double {
        val drawdownPenalty = (dailyRisk.drawdownPct * 1.5).coerceIn(0.0, 1.0)
        val pairQuality = rankedPairs.take(3).map { it.marketOpportunityScore }.average().takeIf { !it.isNaN() } ?: 0.5
        val givebackPenalty = dailyRisk.givebackPct.coerceIn(0.0, 1.0) * 0.5
        return (pairQuality - drawdownPenalty - givebackPenalty + 0.35).coerceIn(0.0, 1.0)
    }

    private fun speculativePocketAllowed(
        marketSnapshot: MarketOpportunitySnapshot,
    ): Boolean {
        return marketSnapshot.regime in setOf(MarketRegime.HEALTHY_UPTREND, MarketRegime.HEALTHY_SIDEWAYS) &&
            marketSnapshot.edgeConfidence != EdgeConfidence.LOW &&
            marketSnapshot.botHealthScore >= 0.62
    }

    private fun resolveEntryReferencePrice(
        quote: MarketQuote,
        pairScore: PairScore,
        setupReadiness: SetupReadiness,
    ): DecimalValue {
        return when {
            pairScore.speculativePocket || setupReadiness.signalType == StrategySignalType.BREAKOUT_ENTRY ->
                quote.bestAsk.takeIf { it.toDoubleOrZero() > 0.0 } ?: quote.bestBid
            else -> quote.bestBid
        }
    }

    private fun shortTermReturnPct(
        quote: MarketQuote,
    ): Double = quote.shortTermReturnPct

    private fun weightedAverage(vararg entries: Pair<Double, Double>): Double {
        val totalWeight = entries.sumOf { it.second }.coerceAtLeast(0.000001)
        return (entries.sumOf { it.first.coerceIn(0.0, 1.0) * it.second } / totalWeight).coerceIn(0.0, 1.0)
    }

    private fun averageOf(vararg values: Double): Double {
        if (values.isEmpty()) return 0.0
        return values.map { it.coerceIn(0.0, 1.0) }.average().coerceIn(0.0, 1.0)
    }

    private fun List<MarketQuote>.toRegimeHint(): MarketRegime {
        val avgTrend = map { it.mediumTermReturnPct }.average().takeIf { !it.isNaN() } ?: 0.0
        return when {
            avgTrend >= 1.5 -> MarketRegime.HEALTHY_UPTREND
            avgTrend <= -1.5 -> MarketRegime.BREAKDOWN_PANIC
            else -> MarketRegime.HEALTHY_SIDEWAYS
        }
    }
}

private data class EntryThresholds(
    val minRankingScore: Double,
    val minOpportunityScore: Double,
    val productiveIdleBiasActive: Boolean,
    val parallelMomentumBiasActive: Boolean,
    val dailyProfitLockActive: Boolean,
)

private data class SetupReadiness(
    val setupType: com.kibot.shared.models.SetupType,
    val signalType: StrategySignalType,
    val expectedHoldingHours: Double,
    val rationale: String,
)

private data class CandidateSelection(
    val pairScore: PairScore,
    val quote: MarketQuote,
    val setupReadiness: SetupReadiness,
    val selectionScore: Double,
)

private data class PairParts(
    val baseAsset: String,
    val quoteAsset: String,
)

private fun PairId.assets(): PairParts {
    val parts = value.lowercase().split("_")
    return if (parts.size == 2) {
        PairParts(parts[0], parts[1])
    } else {
        val quote = listOf("idr", "usdt", "btc", "eth").firstOrNull { value.lowercase().endsWith(it) }
            ?: error("Unsupported pair format: ${value}")
        PairParts(value.lowercase().removeSuffix(quote), quote)
    }
}

private val activeBuyOrderStatuses = setOf(
    com.kibot.shared.models.OrderStatus.CREATED,
    com.kibot.shared.models.OrderStatus.SUBMITTING,
    com.kibot.shared.models.OrderStatus.OPEN,
    com.kibot.shared.models.OrderStatus.PARTIALLY_FILLED,
    com.kibot.shared.models.OrderStatus.CANCEL_REQUESTED,
    com.kibot.shared.models.OrderStatus.UNKNOWN,
)

private const val MAX_EXTERNAL_SUPPORT_BIAS = 0.08
