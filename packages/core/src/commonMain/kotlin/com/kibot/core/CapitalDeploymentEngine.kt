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
                    speculativePocket = it.speculativePocket,
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
        val baseCapitalUtilizationTargetPct = (1.0 - reservePct).coerceIn(0.0, 1.0)
        val baseTotalCap = (openPositions + risk.maxAllowedAdditionalPositions)
            .coerceAtMost(config.maxConcurrentPositions)
        val firstCandidate = candidates.firstOrNull()
        val secondCandidate = candidates.getOrNull(1)
        val topCandidateGap = ((firstCandidate?.rankingScore ?: 0.0) - (secondCandidate?.rankingScore ?: 0.0))
            .coerceAtLeast(0.0)
        val dominantTierAReady = openPositions == 0 &&
            mode.mode in setOf(BotMode.GROWTH, BotMode.ATTACK) &&
            risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.tier == com.kibot.shared.models.PairTier.TIER_A &&
            firstCandidate.rankingScore >= 0.82 &&
            firstCandidate.marketOpportunityScore >= 0.72 &&
            topCandidateGap >= 0.08
        val speculativePocketReady = openPositions == 0 &&
            risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.speculativePocket == true &&
            firstCandidate.rankingScore >= 0.62 &&
            firstCandidate.marketOpportunityScore >= 0.60
        val secondSlotReady = risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.rankingScore?.let { it >= config.minSecondSlotRankingScore } == true &&
            secondCandidate?.let {
                it.rankingScore >= config.minSecondSlotRankingScore &&
                    it.marketOpportunityScore >= config.minSecondSlotOpportunityScore
            } == true &&
            topCandidateGap <= 0.08
        val effectiveReservePct = if (dominantTierAReady) {
            (reservePct - config.dominantTierAReserveReliefPct)
                .coerceAtLeast(config.dominantTierAMinCashReservePct)
        } else {
            reservePct
        }
        val capitalUtilizationTargetPct = (1.0 - effectiveReservePct).coerceIn(0.0, 1.0)
        val maxActivePositions = when {
            !risk.allowNewEntries || !mode.tradingAllowed -> openPositions
            speculativePocketReady -> maxOf(openPositions, 1)
            secondSlotReady -> baseTotalCap
            else -> maxOf(openPositions, if (risk.maxAllowedAdditionalPositions > 0) 1 else openPositions)
                .coerceAtMost(config.maxConcurrentPositions)
        }
        val hasNewSlotCapacity = maxActivePositions > openPositions
        val dominanceBoost = topCandidateGap.coerceIn(0.0, 0.10) * 0.35
        val budgetMultiplier = when {
            maxActivePositions <= 1 &&
                firstCandidate?.rankingScore?.let { it >= 0.80 } == true &&
                risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) ->
                config.singlePositionBudgetBoostMultiplier + dominanceBoost
            maxActivePositions >= 2 ->
                config.multiPositionBudgetSplitMultiplier
            else -> 1.0
        }
        val perPositionBudget = minOf(
            risk.suggestedPerPositionBudgetIdr * budgetMultiplier,
            currentEquity * (1.0 - effectiveReservePct).coerceIn(0.0, 1.0) * config.maxPerPositionBudgetPct,
            if (speculativePocketReady) currentEquity * config.speculativePocketMaxEquityPct else Double.MAX_VALUE,
        ).coerceAtLeast(0.0)
        val allowRotation = mode.mode != BotMode.SAFE &&
            !speculativePocketReady &&
            openPositions > 0 &&
            candidates.firstOrNull()?.rankingScore?.let { it >= 0.78 } == true &&
            topCandidateGap >= 0.05

        val rationale = buildList {
            if (!risk.allowNewEntries) add("Entry baru diblokir oleh risk engine.")
            if (risk.allowNewEntries && !hasNewSlotCapacity) add("Belum ada slot tambahan yang cukup kuat untuk entry baru.")
            if (mode.mode == BotMode.DEFENSIVE) add("Modal disebar lebih konservatif karena mode DEFENSIVE.")
            if (mode.mode == BotMode.ATTACK) add("Modal boleh sedikit lebih aktif karena market sangat sehat.")
            if (candidates.isEmpty()) add("Belum ada kandidat yang layak memakai modal.")
            if (speculativePocketReady) add("Sleeve spekulatif aktif: pair agresif boleh dimainkan, tapi maksimal 25% equity harian.")
            if (secondSlotReady) add("Slot kedua boleh dibuka karena dua kandidat teratas sama-sama kuat.")
            if (!secondSlotReady && topCandidateGap > 0.12) add("Kandidat teratas terlalu dominan, jadi modal lebih baik difokuskan dulu.")
            if (dominantTierAReady) add("Cash reserve diringankan sedikit karena kandidat tier A terlihat dominan dan market masih sehat.")
            if (allowRotation) add("Boleh pertimbangkan rotasi jika kandidat baru jauh lebih unggul.")
        }

        return CapitalDeploymentPlan(
            allowNewEntries = risk.allowNewEntries && mode.tradingAllowed && hasNewSlotCapacity,
            allowRotation = allowRotation,
            maxActivePositions = maxActivePositions,
            suggestedPerPositionBudgetIdr = perPositionBudget,
            targetCashReservePct = effectiveReservePct,
            capitalUtilizationTargetPct = maxOf(capitalUtilizationTargetPct, baseCapitalUtilizationTargetPct),
            preferredHorizon = candidates.firstOrNull()?.preferredHorizon,
            candidates = candidates.take(6),
            rationale = rationale,
        )
    }
}
