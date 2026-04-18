package com.kibot.core

import com.kibot.shared.models.BotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Maintains pair-level whitelist decisions with soft probation for new pairs.
 */
class PairWhitelistManager(
    private val controlPlane: ControlPlaneGateway? = null,
    private val botId: BotId? = null,
    private val scope: CoroutineScope? = null,
) {
    private val hardWhitelist = mutableSetOf("STO", "DRX", "D")
    private val pairStats = mutableMapOf<String, PairStats>()

    private val minTradesForDecision = 20
    private val winRateThresholdPercent = 65.0

    data class PairStats(
        val pair: String,
        var wins: Int = 0,
        var totalTrades: Int = 0,
        var lastUpdated: Long = System.currentTimeMillis(),
    ) {
        val winRatePercent: Double
            get() = if (totalTrades == 0) 0.0 else (wins.toDouble() / totalTrades) * 100.0

        val isProbationary: Boolean
            get() = totalTrades < 20
    }

    fun isPairWhitelisted(pair: String): Boolean {
        if (hardWhitelist.contains(pair)) return true
        val stats = pairStats[pair] ?: return true
        if (stats.isProbationary) return true
        return stats.winRatePercent >= winRateThresholdPercent
    }

    fun recordTrade(pair: String, won: Boolean) {
        val stats = pairStats.getOrPut(pair) { PairStats(pair = pair) }
        stats.totalTrades += 1
        if (won) stats.wins += 1
        stats.lastUpdated = System.currentTimeMillis()

        if (controlPlane != null && botId != null && scope != null) {
            scope.launch {
                runCatching {
                    controlPlane.upsertPairWhitelist(
                        botId = botId,
                        record = TradeWhitelistRecord(
                            pairId = stats.pair,
                            wins = stats.wins,
                            totalTrades = stats.totalTrades,
                            lastUpdated = Instant.fromEpochMilliseconds(stats.lastUpdated),
                        ),
                    )
                }
            }
        }
    }

    suspend fun loadFromSupabase() {
        val cp = controlPlane ?: return
        val resolvedBotId = botId ?: return
        runCatching {
            cp.fetchPairWhitelist(resolvedBotId).forEach { record ->
                pairStats[record.pairId] = PairStats(
                    pair = record.pairId,
                    wins = record.wins,
                    totalTrades = record.totalTrades,
                    lastUpdated = record.lastUpdated.toEpochMilliseconds(),
                )
            }
        }
    }

    fun getHardWhitelist(): Set<String> = hardWhitelist.toSet()

    fun getDynamicWhitelist(): Set<String> =
        pairStats.values
            .filter { !it.isProbationary && it.winRatePercent >= winRateThresholdPercent }
            .map { it.pair }
            .toSet()

    fun getProbationaryPairs(): Set<String> =
        pairStats.values
            .filter { it.isProbationary }
            .map { it.pair }
            .toSet()

    fun getBlacklistedPairs(): Set<String> =
        pairStats.values
            .filter { !it.isProbationary && it.winRatePercent < winRateThresholdPercent }
            .map { it.pair }
            .toSet()

    fun getPairStats(pair: String): PairStats? = pairStats[pair]

    fun getAllStats(): List<PairStats> =
        pairStats.values.sortedByDescending { it.winRatePercent }

    fun getSummary(): WhitelistSummary {
        val totalTrades = pairStats.values.sumOf { it.totalTrades }
        val totalWins = pairStats.values.sumOf { it.wins }
        val hardWhitelistStats = hardWhitelist
            .mapNotNull { pair -> pairStats[pair]?.let { pair to it.winRatePercent } }
            .toMap()

        return WhitelistSummary(
            hardWhitelistCount = hardWhitelist.size,
            hardWhitelistStats = hardWhitelistStats,
            dynamicWhitelistCount = getDynamicWhitelist().size,
            probationaryCount = getProbationaryPairs().size,
            blacklistedCount = getBlacklistedPairs().size,
            totalPairsTracked = pairStats.size,
            totalTrades = totalTrades,
            totalWins = totalWins,
            overallWinRatePercent = if (totalTrades == 0) 0.0 else (totalWins.toDouble() / totalTrades) * 100.0,
        )
    }

    fun resetPairStats(pair: String) {
        pairStats.remove(pair)
    }

    fun resetAllStats() {
        pairStats.clear()
    }

    fun setPairStatsForTesting(
        pair: String,
        wins: Int,
        totalTrades: Int,
    ) {
        pairStats[pair] = PairStats(
            pair = pair,
            wins = wins.coerceAtLeast(0),
            totalTrades = totalTrades.coerceAtLeast(0),
            lastUpdated = System.currentTimeMillis(),
        )
    }

    fun setPairStatsBatchForTesting(statsMap: Map<String, Pair<Int, Int>>) {
        pairStats.clear()
        statsMap.forEach { (pair, stats) ->
            setPairStatsForTesting(pair, stats.first, stats.second)
        }
    }

    fun addHardWhitelistPair(pair: String) {
        hardWhitelist += pair.uppercase()
    }
}

data class WhitelistSummary(
    val hardWhitelistCount: Int,
    val hardWhitelistStats: Map<String, Double>,
    val dynamicWhitelistCount: Int,
    val probationaryCount: Int,
    val blacklistedCount: Int,
    val totalPairsTracked: Int,
    val totalTrades: Int,
    val totalWins: Int,
    val overallWinRatePercent: Double,
)
