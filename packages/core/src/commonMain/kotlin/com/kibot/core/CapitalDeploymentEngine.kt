package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.CapitalDeploymentPlan
import com.kibot.shared.models.CandidateOpportunity
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.RiskLadderLevel

class CapitalDeploymentEngine(
    private val config: RiskConfig = RiskConfig(),
) {
    fun plan(
        portfolio: PortfolioSnapshot,
        rankedPairs: List<PairScore>,
        risk: RiskDecision,
        mode: BotModeSnapshot,
    ): CapitalDeploymentPlan {
        val candidates = rankedPairs
            .filter { it.allowed }
            .sortedByDescending { it.rankingScore }
            .map {
                CandidateOpportunity(
                    pairId = it.pairId,
                    tier = it.pairTier,
                    preferredHorizon = it.preferredHorizon,
                    rankingScore = it.rankingScore,
                    marketOpportunityScore = it.marketOpportunityScore,
                    expectedNetProfitabilityPct = (it.feeAdjustedEdgeScore * 100.0).coerceAtLeast(0.0),
                    holdabilityScore = it.holdabilityScore,
                    rationale = it.rejectionReasons.ifEmpty {
                        listOf("Pair ${it.pairId.value} lolos filter dan masuk shortlist.")
                    },
                )
            }

        val reservePct = when (mode.mode) {
            BotMode.SAFE -> 1.0
            BotMode.DEFENSIVE -> config.defensiveCashReservePct
            BotMode.GROWTH -> config.minimumCashReservePct
            BotMode.ATTACK -> config.attackCashReservePct
        }
        val currentEquity = portfolio.totalEquityIdr.toDoubleOrZero()
        val openPositions = portfolio.positions.count { it.state != PositionState.CLOSED }
        val capitalUtilizationTargetPct = (1.0 - reservePct).coerceIn(0.0, 1.0)
        val baseTotalCap = (openPositions + risk.maxAllowedAdditionalPositions)
            .coerceAtMost(config.maxConcurrentPositions)
        val firstCandidate = candidates.firstOrNull()
        val secondCandidate = candidates.getOrNull(1)
        val secondSlotReady = risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.rankingScore?.let { it >= config.minSecondSlotRankingScore } == true &&
            secondCandidate?.let {
                it.rankingScore >= config.minSecondSlotRankingScore &&
                    it.marketOpportunityScore >= config.minSecondSlotOpportunityScore
            } == true
        val maxActivePositions = when {
            !risk.allowNewEntries || !mode.tradingAllowed -> openPositions
            secondSlotReady -> baseTotalCap
            else -> maxOf(openPositions, if (risk.maxAllowedAdditionalPositions > 0) 1 else openPositions)
                .coerceAtMost(config.maxConcurrentPositions)
        }
        val hasNewSlotCapacity = maxActivePositions > openPositions
        val budgetMultiplier = when {
            maxActivePositions <= 1 &&
                firstCandidate?.rankingScore?.let { it >= 0.80 } == true &&
                risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) ->
                config.singlePositionBudgetBoostMultiplier
            maxActivePositions >= 2 ->
                config.multiPositionBudgetSplitMultiplier
            else -> 1.0
        }
        val perPositionBudget = minOf(
            risk.suggestedPerPositionBudgetIdr * budgetMultiplier,
            currentEquity * capitalUtilizationTargetPct * config.maxPerPositionBudgetPct,
        ).coerceAtLeast(0.0)
        val allowRotation = mode.mode != BotMode.SAFE &&
            openPositions > 0 &&
            candidates.firstOrNull()?.rankingScore?.let { it >= 0.80 } == true

        val rationale = buildList {
            if (!risk.allowNewEntries) add("Entry baru diblokir oleh risk engine.")
            if (risk.allowNewEntries && !hasNewSlotCapacity) add("Belum ada slot tambahan yang cukup kuat untuk entry baru.")
            if (mode.mode == BotMode.DEFENSIVE) add("Modal disebar lebih konservatif karena mode DEFENSIVE.")
            if (mode.mode == BotMode.ATTACK) add("Modal boleh sedikit lebih aktif karena market sangat sehat.")
            if (candidates.isEmpty()) add("Belum ada kandidat yang layak memakai modal.")
            if (secondSlotReady) add("Slot kedua boleh dibuka karena dua kandidat teratas sama-sama kuat.")
            if (allowRotation) add("Boleh pertimbangkan rotasi jika kandidat baru jauh lebih unggul.")
        }

        return CapitalDeploymentPlan(
            allowNewEntries = risk.allowNewEntries && mode.tradingAllowed && hasNewSlotCapacity,
            allowRotation = allowRotation,
            maxActivePositions = maxActivePositions,
            suggestedPerPositionBudgetIdr = perPositionBudget,
            targetCashReservePct = reservePct,
            capitalUtilizationTargetPct = capitalUtilizationTargetPct,
            preferredHorizon = candidates.firstOrNull()?.preferredHorizon,
            candidates = candidates.take(6),
            rationale = rationale,
        )
    }
}
