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
)

class StrategyOrchestrator(
    private val pairSelector: PairSelector = PairSelector(),
    private val regimeAnalyzer: MarketRegimeAnalyzer = MarketRegimeAnalyzer(),
    private val healthAdvisor: HealthAdvisor = HealthAdvisor(),
    private val riskEngine: RiskEngine = RiskEngine(),
    private val botModeDecider: BotModeDecider = BotModeDecider(),
    private val deploymentEngine: CapitalDeploymentEngine = CapitalDeploymentEngine(),
    private val executionConfig: StrategyExecutionConfig = StrategyExecutionConfig(),
) {
    fun analyze(
        botId: BotId,
        balances: List<BalanceSnapshot>,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
        dailyRisk: DailyRiskSnapshot?,
        health: EngineHealthSnapshot,
        marketQuotes: List<MarketQuote>,
        pairSupportHints: List<AiPairSupportHint> = emptyList(),
        weeklySummary: WeeklyLearningSummary? = null,
    ): StrategyCycleResult {
        val quoteByPair = marketQuotes.associateBy { it.pairId }
        val equity = estimatePortfolioValueIdr(balances, marketQuotes)
        val syntheticPositions = deriveSyntheticPositions(balances, marketQuotes)
        val portfolio = PortfolioSnapshot(
            botId = botId,
            balances = balances,
            openOrders = openOrders,
            positions = syntheticPositions,
            totalEquityIdr = DecimalValue.fromDouble(equity),
            lastSyncedAt = kotlinx.datetime.Clock.System.now(),
        )
        val resolvedRisk = dailyRisk ?: fallbackDailyRisk(equity)
        val rankedPairs = applyWeeklyLearningBias(
            rankedPairs = applySupportHints(
                rankedPairs = pairSelector.rank(marketQuotes),
                pairSupportHints = pairSupportHints,
            ),
            weeklySummary = weeklySummary,
            observedAt = Clock.System.now(),
        )
        val healthDecision = healthAdvisor.evaluate(health)
        val marketSnapshot = regimeAnalyzer.analyze(
            quotes = marketQuotes,
            rankedPairs = rankedPairs,
            health = health,
            performanceMomentumScore = derivePerformanceMomentumScore(resolvedRisk, rankedPairs),
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
        val deploymentPlan = deploymentEngine.plan(
            portfolio = portfolio,
            rankedPairs = rankedPairs,
            risk = riskDecision,
            mode = modeSnapshot,
        )

        val selectedSignal = buildSignal(
            rankedPairs = rankedPairs,
            quoteByPair = quoteByPair,
            marketQuotes = marketQuotes,
            balances = balances,
            positions = syntheticPositions,
            marketSnapshot = marketSnapshot,
            modeSnapshot = modeSnapshot,
            deploymentPlan = deploymentPlan,
            openOrders = openOrders,
        )
        val executionPlan = selectedSignal?.toExecutionPlan(
            balances = balances,
            quoteByPair = quoteByPair,
            marketQuotes = marketQuotes,
            deploymentPlan = deploymentPlan,
            modeSnapshot = modeSnapshot,
        )

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

    private fun buildSignal(
        rankedPairs: List<PairScore>,
        quoteByPair: Map<PairId, MarketQuote>,
        marketQuotes: List<MarketQuote>,
        balances: List<BalanceSnapshot>,
        positions: List<PositionSnapshot>,
        marketSnapshot: MarketOpportunitySnapshot,
        modeSnapshot: BotModeSnapshot,
        deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): StrategySignal? {
        if (!modeSnapshot.tradingAllowed || openOrders.isNotEmpty()) return null
        val heldPairs = positions
            .filter { it.state != PositionState.CLOSED }
            .map { it.pairId }
            .toSet()

        val thresholds = resolveEntryThresholds(
            modeSnapshot = modeSnapshot,
            marketSnapshot = marketSnapshot,
            heldPairs = heldPairs,
        )
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

        val chosenCandidate = deploymentPlan.candidates
            .take(executionConfig.candidateCount)
            .mapNotNull { candidate ->
                val pairScore = rankedPairs.firstOrNull { it.pairId == candidate.pairId } ?: return@mapNotNull null
                val quote = quoteByPair[candidate.pairId] ?: return@mapNotNull null
                val hasFunding = hasFundedQuoteAsset(
                    pairId = candidate.pairId,
                    balances = balances,
                    marketQuotes = marketQuotes,
                    targetBudgetIdr = deploymentPlan.suggestedPerPositionBudgetIdr,
                )
                if (candidate.pairId in heldPairs || !hasFunding) return@mapNotNull null

                val setupReadiness = deriveSetupReadiness(
                    pairScore = pairScore,
                    quote = quote,
                    marketSnapshot = marketSnapshot,
                    baseOpportunityFloor = thresholds.minOpportunityScore,
                ) ?: return@mapNotNull null

                if (pairScore.rankingScore < thresholds.minRankingScore) return@mapNotNull null

                val selectionScore = scoreEntryCandidate(
                    pairScore = pairScore,
                    quote = quote,
                    marketSnapshot = marketSnapshot,
                    setupReadiness = setupReadiness,
                    dominantPairId = dominantPairId,
                )

                CandidateSelection(
                    pairScore = pairScore,
                    quote = quote,
                    setupReadiness = setupReadiness,
                    selectionScore = selectionScore,
                )
            }
            .maxByOrNull { it.selectionScore }
            ?: return null

        val pairScore = chosenCandidate.pairScore
        val quote = chosenCandidate.quote

        return StrategySignal(
            pairId = chosenCandidate.pairScore.pairId,
            signalType = chosenCandidate.setupReadiness.signalType,
            confidence = chosenCandidate.selectionScore.coerceIn(0.0, 1.0),
            rationale = buildList {
                add("Pair ${chosenCandidate.pairScore.pairId.value} jadi kandidat entry terkuat dari shortlist yang siap dieksekusi.")
                add(chosenCandidate.setupReadiness.rationale)
                if (dominantPairId == chosenCandidate.pairScore.pairId) {
                    add("Kandidat ini unggul cukup jauh dari alternatif terdekat, jadi modal tidak dipaksa menyebar.")
                }
                if (pairScore.speculativePocket) {
                    add("Trade ini masuk sleeve spekulatif, jadi eksposurnya dibatasi keras dan tidak boleh jadi posisi utama.")
                }
                if (heldPairs.isEmpty() && thresholds.productiveIdleBiasActive) {
                    add("Modal sedang idle, jadi threshold entry sedikit dilonggarkan pada kandidat yang benar-benar kuat.")
                }
            },
            entryPrice = quote.bestBid,
            takeProfitPrice = DecimalValue.fromDouble(
                quote.bestBid.toDoubleOrZero() *
                    when {
                        pairScore.speculativePocket -> 1.03
                        pairScore.preferredHorizon == TradingHorizon.SWING -> 1.05
                        else -> 1.02
                    },
            ),
            stopPrice = DecimalValue.fromDouble(
                quote.bestBid.toDoubleOrZero() *
                    when {
                        pairScore.speculativePocket -> 0.972
                        pairScore.preferredHorizon == TradingHorizon.SWING -> 0.96
                        else -> 0.985
                    },
            ),
            setupType = chosenCandidate.setupReadiness.setupType,
            horizon = pairScore.preferredHorizon,
            pairTier = pairScore.pairTier,
            speculativePocket = pairScore.speculativePocket,
            marketRegime = marketSnapshot.regime,
            edgeConfidence = modeSnapshot.edgeConfidence,
            expectedHoldingHours = chosenCandidate.setupReadiness.expectedHoldingHours,
            expectedNetProfitabilityPct = pairScore.marketOpportunityScore,
        )
    }

    private fun resolveEntryThresholds(
        modeSnapshot: BotModeSnapshot,
        marketSnapshot: MarketOpportunitySnapshot,
        heldPairs: Set<PairId>,
    ): EntryThresholds {
        val baseRankingScore = when (modeSnapshot.mode) {
            BotMode.SAFE -> Double.MAX_VALUE
            BotMode.DEFENSIVE -> executionConfig.defensiveMinRankingScore
            BotMode.GROWTH -> executionConfig.growthMinRankingScore
            BotMode.ATTACK -> executionConfig.attackMinRankingScore
        }
        val productiveIdleBiasActive = heldPairs.isEmpty() &&
            modeSnapshot.mode in setOf(BotMode.GROWTH, BotMode.ATTACK) &&
            marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC &&
            marketSnapshot.marketOpportunityScore >= 0.60
        return EntryThresholds(
            minRankingScore = (baseRankingScore - if (productiveIdleBiasActive) executionConfig.productiveIdleRankingDelta else 0.0)
                .coerceAtLeast(0.0),
            minOpportunityScore = (
                executionConfig.minExpectedOpportunityScore -
                    if (productiveIdleBiasActive) executionConfig.productiveIdleOpportunityDelta else 0.0
                ).coerceAtLeast(0.0),
            productiveIdleBiasActive = productiveIdleBiasActive,
        )
    }

    private fun deriveSetupReadiness(
        pairScore: PairScore,
        quote: MarketQuote,
        marketSnapshot: MarketOpportunitySnapshot,
        baseOpportunityFloor: Double,
    ): SetupReadiness? {
        val setupType: com.kibot.shared.models.SetupType
        val signalType: StrategySignalType
        val adjustedOpportunityFloor: Double
        val expectedHoldingHours: Double
        val rationale: String

        when {
            pairScore.speculativePocket &&
                marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC &&
                speculativePocketAllowed(marketSnapshot) &&
                quote.shortTermReturnPct <= 18.0 -> {
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor + 0.01).coerceAtMost(1.0)
                expectedHoldingHours = 3.5
                rationale = "Sleeve spekulatif aktif: momentum meledak masih sehat, tapi budget dan durasi entry dipersempit."
            }

            pairScore.preferredHorizon == TradingHorizon.SWING &&
                marketSnapshot.regime == MarketRegime.HEALTHY_UPTREND &&
                pairScore.holdabilityScore >= 0.64 &&
                pairScore.trendQualityScore >= 0.60 &&
                quote.mediumTermReturnPct >= 0.90 -> {
                setupType = com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - 0.01).coerceAtLeast(0.0)
                expectedHoldingHours = 72.0
                rationale = "Regime uptrend dan holdability kuat, jadi swing continuation boleh diprioritaskan."
            }

            quote.spreadPct <= 0.32 &&
                quote.estimatedSlippagePct <= 0.32 &&
                pairScore.fillQualityScore >= 0.62 &&
                quote.shortTermReturnPct <= 0.55 &&
                marketSnapshot.regime != MarketRegime.BREAKDOWN_PANIC -> {
                setupType = com.kibot.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK
                signalType = StrategySignalType.MEAN_REVERSION_ENTRY
                adjustedOpportunityFloor = (baseOpportunityFloor - 0.02).coerceAtLeast(0.0)
                expectedHoldingHours = 8.0
                rationale = "Spread dan fill sehat, jadi pullback taktis boleh dipakai saat harga sedang rehat sehat."
            }

            quote.mediumTermReturnPct >= 0.85 &&
                quote.shortTermReturnPct >= 0.30 -> {
                setupType = com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
                signalType = StrategySignalType.BREAKOUT_ENTRY
                adjustedOpportunityFloor = when (marketSnapshot.regime) {
                    MarketRegime.HEALTHY_SIDEWAYS -> baseOpportunityFloor + 0.03
                    MarketRegime.HIGH_VOLATILITY_UNCLEAR -> baseOpportunityFloor + 0.04
                    else -> baseOpportunityFloor + 0.01
                }.coerceAtMost(1.0)
                expectedHoldingHours = if (pairScore.preferredHorizon == TradingHorizon.SWING) 48.0 else 10.0
                rationale = "Breakout continuation tetap boleh, tapi threshold diperketat saat market belum benar-benar nyaman."
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
        setupReadiness: SetupReadiness,
        dominantPairId: PairId?,
    ): Double {
        val regimeBias = when {
            setupReadiness.setupType == com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION ->
                ((marketSnapshot.swingBiasScore - 0.50) * 0.10).coerceIn(-0.03, 0.03)
            setupReadiness.signalType == StrategySignalType.MEAN_REVERSION_ENTRY ->
                ((marketSnapshot.tacticalBiasScore - 0.50) * 0.10).coerceIn(-0.03, 0.03)
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
        val dominanceBonus = if (dominantPairId == pairScore.pairId) 0.025 else 0.0

        return weightedAverage(
            pairScore.rankingScore to 0.34,
            pairScore.marketOpportunityScore to 0.26,
            pairScore.fillQualityScore to 0.12,
            pairScore.historicalExpectancyScore to 0.10,
            followThroughScore to 0.10,
            quote.recentTradeActivityScore.coerceIn(0.0, 1.0) to 0.08,
        ).let { base ->
            (base + regimeBias + dominanceBonus).coerceIn(0.0, 1.0)
        }
    }

    private fun StrategySignal.toExecutionPlan(
        balances: List<BalanceSnapshot>,
        quoteByPair: Map<PairId, MarketQuote>,
        marketQuotes: List<MarketQuote>,
        deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
        modeSnapshot: BotModeSnapshot,
    ): ExecutionPlan? {
        val quote = quoteByPair[pairId] ?: return null
        val pairParts = pairId.assets()
        val quoteAssetPriceIdr = quoteAssetPriceIdr(pairParts.quoteAsset, marketQuotes) ?: return null
        val quoteBalanceUnits = balances
            .firstOrNull { it.asset.equals(pairParts.quoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: 0.0
        val rawBudgetIdr = minOf(
            deploymentPlan.suggestedPerPositionBudgetIdr,
            quoteBalanceUnits * quoteAssetPriceIdr,
        )
        val budgetIdr = (rawBudgetIdr * (1.0 - executionConfig.entrySpendBufferPct))
            .coerceAtLeast(0.0)
        if (budgetIdr < executionConfig.minOrderNotionalIdr) return null

        val priceInQuoteAsset = entryPrice?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: return null
        val budgetQuoteUnits = if (pairParts.quoteAsset == "idr") {
            budgetIdr
        } else {
            budgetIdr / quoteAssetPriceIdr
        }
        val useMarketBuy =
            executionConfig.marketEntryEnabled &&
                pairParts.quoteAsset == "idr" &&
                setupType == com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION &&
                confidence >= executionConfig.marketEntryMinRankingScore &&
                expectedNetProfitabilityPct >= executionConfig.marketEntryMinExpectedNetProfitPct &&
                quote.spreadPct <= executionConfig.marketEntryMaxSpreadPct &&
                quote.estimatedSlippagePct <= executionConfig.marketEntryMaxSlippagePct &&
                quote.recentTradeActivityScore >= executionConfig.marketEntryMinTradeActivityScore &&
                quote.trendQualityScore >= executionConfig.marketEntryMinTrendScore &&
                (
                    speculativePocket ||
                        modeSnapshot.mode in setOf(BotMode.GROWTH, BotMode.ATTACK)
                )
        val effectivePriceInQuoteAsset = if (useMarketBuy) {
            quote.bestAsk.toDoubleOrZero().takeIf { it > 0.0 } ?: priceInQuoteAsset
        } else {
            priceInQuoteAsset
        }
        val quantity = budgetQuoteUnits / effectivePriceInQuoteAsset
        if (quantity <= 0.0) return null

        return ExecutionPlan(
            signal = this,
            side = OrderSide.BUY,
            orderType = if (useMarketBuy) OrderType.MARKET else OrderType.LIMIT,
            quantity = DecimalValue.fromDouble(quantity),
            limitPrice = if (useMarketBuy) null else entryPrice,
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
            postOnlyPreferred = !useMarketBuy,
            expectedNetEdgePct = expectedNetProfitabilityPct,
            botMode = modeSnapshot.mode,
            riskLadderLevel = modeSnapshot.riskLadderLevel,
            pairRankingScore = confidence,
            speculativePocket = speculativePocket,
        )
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
        if (signal != null) add("Kandidat entry ${signal.pairId.value} ${signal.horizon.name.lowercase()} sudah lolos gate analisa.")
        if (signal == null) add("Belum ada setup yang cukup layak untuk dipakai modal.")
        if (!liveGateReady) add("Gate eksekusi live masih tertutup atau belum cukup aman.")
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
        val activeHours = adaptationPlan.activeHours.toSet()
        val currentHour = observedAt.toString().substring(11, 13).toIntOrNull() ?: 0
        val outsideActiveHoursPenalty = if (activeHours.isNotEmpty() && currentHour !in activeHours) 0.02 else 0.0

        return rankedPairs
            .map { pairScore ->
                if (!pairScore.allowed) return@map pairScore
                val bias = when (pairScore.pairId) {
                    in whitelist -> 0.02
                    in blacklist -> -0.04
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
        val quoteAssetPrice = quoteAssetPriceIdr(parts.quoteAsset, marketQuotes) ?: return false
        return quoteBalance * quoteAssetPrice >= minOf(targetBudgetIdr, executionConfig.minOrderNotionalIdr)
    }

    private fun quoteAssetPriceIdr(asset: String, marketQuotes: List<MarketQuote>): Double? {
        if (asset == "idr") return 1.0
        return marketQuotes.firstOrNull { it.pairId.value.equals("${asset}_idr", ignoreCase = true) }?.midPrice?.toDoubleOrZero()
    }

    private fun estimatePortfolioValueIdr(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
    ): Double {
        return balances.sumOf { balance ->
            val totalUnits = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (balance.asset.equals("idr", ignoreCase = true)) {
                totalUnits
            } else {
                totalUnits * (quoteAssetPriceIdr(balance.asset.lowercase(), marketQuotes) ?: 0.0)
            }
        }.coerceAtLeast(0.0)
    }

    private fun deriveSyntheticPositions(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
    ): List<PositionSnapshot> {
        val now = kotlinx.datetime.Clock.System.now()
        return balances.mapNotNull { balance ->
            if (balance.asset.equals("idr", ignoreCase = true)) return@mapNotNull null
            val totalUnits = balance.free.toDoubleOrZero() + balance.locked.toDoubleOrZero()
            if (totalUnits <= 0.0) return@mapNotNull null
            val pairId = PairId("${balance.asset.lowercase()}_idr")
            val quote = marketQuotes.firstOrNull { it.pairId == pairId } ?: return@mapNotNull null
            val markValue = totalUnits * quote.midPrice.toDoubleOrZero()
            if (markValue < executionConfig.minOrderNotionalIdr) return@mapNotNull null
            PositionSnapshot(
                positionId = PositionId("synthetic-${balance.asset.lowercase()}"),
                pairId = pairId,
                baseAsset = balance.asset.lowercase(),
                quoteAsset = "idr",
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
        hardDailyLossLimitPct = 0.25,
        hardStopTriggered = false,
        rebasePending = false,
        highWatermarkEquityIdr = DecimalValue.fromDouble(equityIdr),
    )

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

private const val MAX_EXTERNAL_SUPPORT_BIAS = 0.04
