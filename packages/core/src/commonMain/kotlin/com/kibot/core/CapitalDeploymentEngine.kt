package com.kibot.core

import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotModeSnapshot
import com.kibot.shared.models.CapitalDeploymentPlan
import com.kibot.shared.models.CandidateOpportunity
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.PortfolioSnapshot
import com.kibot.shared.models.PositionSnapshot
import com.kibot.shared.models.PositionState
import com.kibot.shared.models.RiskLadderLevel
import kotlin.math.absoluteValue

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
                    expectedNetProfitabilityPct = it.feeAdjustedEdgeScore.coerceAtLeast(0.0),
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
        val openPositionValues = portfolio.positions
            .filter { it.state != PositionState.CLOSED }
            .map { position ->
                ((position.quantity.toDoubleOrZero() * position.averageEntryPrice.toDoubleOrZero()) +
                    position.unrealizedPnlIdr.toDoubleOrZero()).coerceAtLeast(0.0)
            }
            .sortedDescending()
        val baseCapitalUtilizationTargetPct = (1.0 - reservePct).coerceIn(0.0, 1.0)
        val deployableEquity = (currentEquity * baseCapitalUtilizationTargetPct).coerceAtLeast(0.0)
        val top1DeployableConcentration = if (deployableEquity > 0.0) {
            openPositionValues.firstOrNull().orZero() / deployableEquity
        } else {
            0.0
        }
        val top2DeployableConcentration = if (deployableEquity > 0.0) {
            openPositionValues.take(2).sum() / deployableEquity
        } else {
            0.0
        }
        val loserHeatPct = if (currentEquity > 0.0) {
            portfolio.positions
                .filter { it.state != PositionState.CLOSED }
                .sumOf { position -> position.unrealizedPnlIdr.toDoubleOrZero().takeIf { it < 0.0 }?.absoluteValue ?: 0.0 } / currentEquity
        } else {
            0.0
        }
        val affordableSlotCap = when {
            currentEquity <= 0.0 -> 1
            else -> kotlin.math.floor(
                ((currentEquity * (1.0 - reservePct)).coerceAtLeast(0.0)) / config.targetMinPositionBudgetIdr,
            ).toInt().coerceAtLeast(1)
        }.let { rawCap ->
            when {
                currentEquity < 120_000.0 -> rawCap.coerceAtMost(2)
                currentEquity < 200_000.0 -> rawCap.coerceAtMost(3)
                else -> rawCap
            }
        }.coerceAtMost(config.maxConcurrentPositions)
        val baseTotalCap = (openPositions + risk.maxAllowedAdditionalPositions)
            .coerceAtMost(config.maxConcurrentPositions)
            .coerceAtMost(affordableSlotCap)
        val firstCandidate = candidates.firstOrNull()
        val secondCandidate = candidates.getOrNull(1)
        val topCandidateGap = ((firstCandidate?.rankingScore ?: 0.0) - (secondCandidate?.rankingScore ?: 0.0))
            .coerceAtLeast(0.0)
        val dominantTierAReady = openPositions == 0 &&
            mode.mode in setOf(BotMode.GROWTH, BotMode.ATTACK) &&
            risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.tier == com.kibot.shared.models.PairTier.TIER_A &&
            firstCandidate.rankingScore >= 0.78 &&
            firstCandidate.marketOpportunityScore >= 0.68 &&
            topCandidateGap >= 0.05
        val speculativePocketReady = openPositions == 0 &&
            risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.speculativePocket == true &&
            firstCandidate.rankingScore >= 0.58 &&
            firstCandidate.marketOpportunityScore >= 0.55
        val secondSlotReady = risk.riskLadderLevel in setOf(RiskLadderLevel.NORMAL, RiskLadderLevel.WARNING) &&
            firstCandidate?.rankingScore?.let { it >= config.minSecondSlotRankingScore } == true &&
            secondCandidate?.let {
                it.rankingScore >= config.minSecondSlotRankingScore &&
                    it.marketOpportunityScore >= config.minSecondSlotOpportunityScore
            } == true &&
            topCandidateGap <= 0.12
        val multiSlotReadyCount = candidates
            .take(baseTotalCap.coerceAtLeast(1))
            .count {
                it.rankingScore >= (config.minSecondSlotRankingScore - 0.05) &&
                    it.marketOpportunityScore >= (config.minSecondSlotOpportunityScore - 0.04)
            }
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
            multiSlotReadyCount >= 2 -> maxOf(openPositions, multiSlotReadyCount.coerceAtMost(baseTotalCap))
            secondSlotReady -> baseTotalCap
            else -> maxOf(openPositions, if (risk.maxAllowedAdditionalPositions > 0) 1 else openPositions)
                .coerceAtMost(config.maxConcurrentPositions)
                .coerceAtMost(baseTotalCap.coerceAtLeast(1))
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
        val rankedByPair = rankedPairs.associateBy { it.pairId }
        val rotatableWinners = portfolio.positions.filter { position ->
            position.state != PositionState.CLOSED &&
                position.hasClearRotationProfit(config) &&
                position.isWeakRotationCandidate(rankedByPair[position.pairId])
        }
        val rotatableLosers = portfolio.positions.filter { position ->
            position.state != PositionState.CLOSED &&
                position.unrealizedPnlIdr.toDoubleOrZero() < 0.0 &&
                position.isWeakRotationCandidate(rankedByPair[position.pairId])
        }
        val topCandidateLooksLikeBreakout = firstCandidate?.let { candidate ->
            candidate.speculativePocket ||
                (
                    candidate.preferredHorizon == com.kibot.shared.models.TradingHorizon.TACTICAL &&
                        candidate.marketOpportunityScore >= 0.66 &&
                        candidate.expectedNetProfitabilityPct >= (config.rotationMinNetUpgradePct + 0.25)
                    )
        } == true
        val allowRotation = mode.mode != BotMode.SAFE &&
            !speculativePocketReady &&
            openPositions > 0 &&
            topCandidateLooksLikeBreakout &&
            candidates.firstOrNull()?.rankingScore?.let { it >= 0.70 } == true &&
            candidates.firstOrNull()?.marketOpportunityScore?.let { it >= 0.60 } == true &&
            candidates.firstOrNull()?.expectedNetProfitabilityPct?.let { it >= (config.rotationMinNetUpgradePct + 0.10) } == true &&
            (rotatableWinners.isNotEmpty() || rotatableLosers.isNotEmpty()) &&
            (
                topCandidateGap >= config.rotationRankingGapMin ||
                    top1DeployableConcentration >= config.top1DeployableConcentrationMaxPct ||
                    loserHeatPct >= config.loserHeatCautionPct
                )

        val rationale = buildList {
            if (!risk.allowNewEntries) add("Entry baru diblokir oleh risk engine.")
            if (risk.allowNewEntries && !hasNewSlotCapacity) add("Belum ada slot tambahan yang cukup kuat untuk entry baru.")
            if (mode.mode == BotMode.DEFENSIVE) add("Modal disebar lebih konservatif karena mode DEFENSIVE.")
            if (mode.mode == BotMode.ATTACK) add("Modal boleh sedikit lebih aktif karena market sangat sehat.")
            if (candidates.isEmpty()) add("Belum ada kandidat yang layak memakai modal.")
            if (speculativePocketReady) add("Sleeve spekulatif aktif: pair agresif boleh dimainkan, tapi maksimal 25% equity harian.")
            if (secondSlotReady) add("Slot kedua boleh dibuka karena dua kandidat teratas sama-sama kuat.")
            if (multiSlotReadyCount >= 2) add("$multiSlotReadyCount kandidat terlihat sama-sama executable, jadi bot boleh menyebar modal lebih aktif.")
            if (!secondSlotReady && topCandidateGap > 0.12) add("Kandidat teratas terlalu dominan, jadi modal lebih baik difokuskan dulu.")
            if (top1DeployableConcentration >= config.top1DeployableConcentrationMaxPct) add("Konsentrasi top-1 sudah tinggi, jadi rotasi lebih diprioritaskan daripada menambah ukuran posisi lama.")
            if (top2DeployableConcentration >= config.top2DeployableConcentrationMaxPct) add("Dua aset teratas sudah mendominasi modal aktif, jadi penyebaran modal harus lebih disiplin.")
            if (loserHeatPct >= config.loserHeatCautionPct) add("Loser heat portofolio sedang naik, jadi entry baru harus benar-benar mengalahkan posisi yang lemah.")
            if (dominantTierAReady) add("Cash reserve diringankan sedikit karena kandidat tier A terlihat dominan dan market masih sehat.")
            if (allowRotation) add("Rotasi dipercepat dari posisi lemah/loser saat ada kandidat baru yang jauh lebih eksplosif.")
        }

        return CapitalDeploymentPlan(
            allowNewEntries = risk.allowNewEntries && mode.tradingAllowed && hasNewSlotCapacity,
            allowRotation = allowRotation,
            maxActivePositions = maxActivePositions,
            suggestedPerPositionBudgetIdr = perPositionBudget,
            targetCashReservePct = effectiveReservePct,
            capitalUtilizationTargetPct = maxOf(capitalUtilizationTargetPct, baseCapitalUtilizationTargetPct),
            preferredHorizon = candidates.firstOrNull()?.preferredHorizon,
            candidates = candidates.take(config.maxConcurrentPositions),
            rationale = rationale,
        )
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    private fun PositionSnapshot.hasClearRotationProfit(config: RiskConfig): Boolean {
        val costBasisIdr = quantity.toDoubleOrZero() * averageEntryPrice.toDoubleOrZero()
        if (costBasisIdr <= 0.0) return false
        val pnlIdr = unrealizedPnlIdr.toDoubleOrZero()
        val pnlPct = (pnlIdr / costBasisIdr) * 100.0
        return pnlIdr >= config.rotationMinClearProfitIdr && pnlPct >= config.rotationMinClearProfitPct
    }

    private fun PositionSnapshot.isWeakRotationCandidate(pairScore: PairScore?): Boolean {
        if (pairScore == null) return true
        return !pairScore.allowed ||
            pairScore.marketOpportunityScore < 0.56 ||
            pairScore.trendQualityScore < 0.54 ||
            pairScore.recentHealthScore < 0.58
    }
}
