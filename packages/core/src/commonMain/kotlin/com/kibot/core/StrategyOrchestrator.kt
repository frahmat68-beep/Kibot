package com.kibot.core

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
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.StrategySignal
import com.kibot.shared.models.StrategySignalType
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.TradingHorizon

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
    ): StrategyCycleResult {
        val equity = estimatePortfolioValueIdr(balances, marketQuotes)
        val portfolio = PortfolioSnapshot(
            botId = botId,
            balances = balances,
            openOrders = openOrders,
            positions = emptyList(),
            totalEquityIdr = DecimalValue.fromDouble(equity),
            lastSyncedAt = kotlinx.datetime.Clock.System.now(),
        )
        val resolvedRisk = dailyRisk ?: fallbackDailyRisk(equity)
        val rankedPairs = pairSelector.rank(marketQuotes)
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
            marketQuotes = marketQuotes,
            balances = balances,
            modeSnapshot = modeSnapshot,
            deploymentPlan = deploymentPlan,
            openOrders = openOrders,
        )
        val executionPlan = selectedSignal?.toExecutionPlan(
            balances = balances,
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

    private fun buildSignal(
        rankedPairs: List<PairScore>,
        marketQuotes: List<MarketQuote>,
        balances: List<BalanceSnapshot>,
        modeSnapshot: BotModeSnapshot,
        deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
        openOrders: List<com.kibot.shared.models.OrderSnapshot>,
    ): StrategySignal? {
        if (!modeSnapshot.tradingAllowed || openOrders.isNotEmpty()) return null
        val topCandidate = deploymentPlan.candidates.firstOrNull() ?: return null
        val pairScore = rankedPairs.firstOrNull { it.pairId == topCandidate.pairId } ?: return null
        val quote = marketQuotes.firstOrNull { it.pairId == topCandidate.pairId } ?: return null
        if (!hasFundedQuoteAsset(topCandidate.pairId, balances, marketQuotes, deploymentPlan.suggestedPerPositionBudgetIdr)) {
            return null
        }

        val minRankingScore = when (modeSnapshot.mode) {
            BotMode.SAFE -> Double.MAX_VALUE
            BotMode.DEFENSIVE -> executionConfig.defensiveMinRankingScore
            BotMode.GROWTH -> executionConfig.growthMinRankingScore
            BotMode.ATTACK -> executionConfig.attackMinRankingScore
        }
        if (pairScore.rankingScore < minRankingScore) return null
        if (pairScore.marketOpportunityScore < executionConfig.minExpectedOpportunityScore) return null

        val signalType = when {
            pairScore.preferredHorizon == TradingHorizon.SWING &&
                modeSnapshot.edgeConfidence != EdgeConfidence.LOW &&
                quote.mediumTermReturnPct >= 1.0 ->
                StrategySignalType.BREAKOUT_ENTRY
            quote.spreadPct <= 0.35 && quote.shortTermReturnPct < 0.8 ->
                StrategySignalType.MEAN_REVERSION_ENTRY
            else -> StrategySignalType.BREAKOUT_ENTRY
        }
        val setupType = when {
            pairScore.preferredHorizon == TradingHorizon.SWING -> com.kibot.shared.models.SetupType.SWING_TREND_CONTINUATION
            signalType == StrategySignalType.MEAN_REVERSION_ENTRY -> com.kibot.shared.models.SetupType.HEALTHY_SHORT_TERM_PULLBACK
            else -> com.kibot.shared.models.SetupType.LIGHT_BREAKOUT_CONTINUATION
        }

        return StrategySignal(
            pairId = topCandidate.pairId,
            signalType = signalType,
            confidence = pairScore.rankingScore,
            rationale = listOf(
                "Pair ${topCandidate.pairId.value} masuk kandidat terbaik.",
                "Mode ${modeSnapshot.mode.name} dan regime ${modeSnapshot.edgeConfidence.name} mendukung entry selektif.",
            ),
            entryPrice = quote.bestBid,
            takeProfitPrice = DecimalValue.fromDouble(quote.bestBid.toDoubleOrZero() * if (pairScore.preferredHorizon == TradingHorizon.SWING) 1.05 else 1.02),
            stopPrice = DecimalValue.fromDouble(quote.bestBid.toDoubleOrZero() * if (pairScore.preferredHorizon == TradingHorizon.SWING) 0.96 else 0.985),
            setupType = setupType,
            horizon = pairScore.preferredHorizon,
            pairTier = pairScore.pairTier,
            marketRegime = marketQuotes.toRegimeHint(),
            edgeConfidence = modeSnapshot.edgeConfidence,
            expectedHoldingHours = if (pairScore.preferredHorizon == TradingHorizon.SWING) 72.0 else 8.0,
            expectedNetProfitabilityPct = pairScore.marketOpportunityScore,
        )
    }

    private fun StrategySignal.toExecutionPlan(
        balances: List<BalanceSnapshot>,
        marketQuotes: List<MarketQuote>,
        deploymentPlan: com.kibot.shared.models.CapitalDeploymentPlan,
        modeSnapshot: BotModeSnapshot,
    ): ExecutionPlan? {
        val quote = marketQuotes.firstOrNull { it.pairId == pairId } ?: return null
        val pairParts = pairId.assets()
        val quoteAssetPriceIdr = quoteAssetPriceIdr(pairParts.quoteAsset, marketQuotes) ?: return null
        val quoteBalanceUnits = balances
            .firstOrNull { it.asset.equals(pairParts.quoteAsset, ignoreCase = true) }
            ?.free
            ?.toDoubleOrZero()
            ?: 0.0
        val budgetIdr = minOf(
            deploymentPlan.suggestedPerPositionBudgetIdr,
            quoteBalanceUnits * quoteAssetPriceIdr,
        )
        if (budgetIdr < executionConfig.minOrderNotionalIdr) return null

        val priceInQuoteAsset = entryPrice?.toDoubleOrZero()?.takeIf { it > 0.0 } ?: return null
        val budgetQuoteUnits = if (pairParts.quoteAsset == "idr") {
            budgetIdr
        } else {
            budgetIdr / quoteAssetPriceIdr
        }
        val quantity = budgetQuoteUnits / priceInQuoteAsset
        if (quantity <= 0.0) return null

        return ExecutionPlan(
            signal = this,
            side = OrderSide.BUY,
            orderType = OrderType.LIMIT,
            quantity = DecimalValue.fromDouble(quantity),
            limitPrice = entryPrice,
            quoteBudget = DecimalValue.fromDouble(budgetIdr),
            postOnlyPreferred = true,
            expectedNetEdgePct = expectedNetProfitabilityPct,
            botMode = modeSnapshot.mode,
            riskLadderLevel = modeSnapshot.riskLadderLevel,
            pairRankingScore = confidence,
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

    private fun List<MarketQuote>.toRegimeHint(): MarketRegime {
        val avgTrend = map { it.mediumTermReturnPct }.average().takeIf { !it.isNaN() } ?: 0.0
        return when {
            avgTrend >= 1.5 -> MarketRegime.HEALTHY_UPTREND
            avgTrend <= -1.5 -> MarketRegime.BREAKDOWN_PANIC
            else -> MarketRegime.HEALTHY_SIDEWAYS
        }
    }
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
        val quote = listOf("idr", "usdt", "btc", "eth").firstOrNull { value.lowercase().endsWith(it) }
            ?: error("Unsupported pair format: ${value}")
        PairParts(value.lowercase().removeSuffix(quote), quote)
    }
}
