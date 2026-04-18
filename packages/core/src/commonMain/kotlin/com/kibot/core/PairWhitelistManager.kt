package com.kibot.core
import kotlinx.coroutines.launch

/**
 * PairWhitelistManager - Maintains whitelist of high-conviction pairs
 * 
 * High-confidence pairs (STO, DRX, D) get preferential treatment
 * New pairs must prove themselves with 20+ trades before final decision
 * 
 * Soft filtering: Prefer whitelist, but allow new pairs to prove themselves
 */
class PairWhitelistManager(private val controlPlane: ControlPlaneGateway? = null, private val botId: com.kibot.shared.models.BotId? = null) {
    
    // Hard-coded whitelist of proven pairs
    private val hardWhitelist = setOf("STO", "DRX", "D")
    
    // Dynamic tracking: pair -> (wins, totalTrades)
    private val pairStats = mutableMapOf<String, PairStats>()
    
    // Minimum trades before final decision on new pairs
    private val MIN_TRADES_FOR_DECISION = 20
    
    // Win rate threshold to add to dynamic whitelist
    private val WIN_RATE_THRESHOLD = 0.65  // 65%+
    
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
    
    /**
     * Check if pair is whitelisted (hard whitelist + dynamic whitelist)
     * 
     * Returns:
     * - Hard whitelist: always true
     * - Dynamic whitelist (>= 20 trades, 65%+ winrate): true
     * - Probationary (<20 trades): true (soft filtering - allow to prove)
     * - Proven loser (20+ trades, < 65% winrate): false (hard filtering)
     */
    fun isPairWhitelisted(pair: String): Boolean {
        // Hard whitelist: always approve
        if (hardWhitelist.contains(pair)) {
            return true
        }
        
        val stats = pairStats[pair]
        
        // Never traded: allow (soft filtering)
        if (stats == null) {
            return true
        }
        
        // Probationary period: allow new pairs to prove themselves
        if (stats.isProbationary) {
            return true
        }
        
        // After 20+ trades: only approve if 65%+ winrate
        return stats.winRatePercent >= WIN_RATE_THRESHOLD
    }
    
    /**
     * Get pairs in hard whitelist
     */
    fun getHardWhitelist(): Set<String> = hardWhitelist.toSet()
    
    /**
     * Get dynamic whitelist (pairs that proved themselves)
     */
    fun getDynamicWhitelist(): Set<String> {
        return pairStats.entries
            .filter { !it.value.isProbationary && it.value.winRatePercent >= WIN_RATE_THRESHOLD }
            .map { it.key }
            .toSet()
    }
    
    /**
     * Get probationary pairs (< 20 trades)
     */
    fun getProbationaryPairs(): Set<String> {
        return pairStats.entries
            .filter { it.value.isProbationary }
            .map { it.key }
            .toSet()
    }
    
    /**
     * Get blacklisted pairs (proven losers)
     */
    fun getBlacklistedPairs(): Set<String> {
        return pairStats.entries
            .filter { !it.value.isProbationary && it.value.winRatePercent < WIN_RATE_THRESHOLD }
            .map { it.key }
            .toSet()
    }
    
    /**
     * Record a trade result for a pair
     * 
     * @param pair Pair identifier (e.g., "STO", "DRX")
     * @param won True if trade was profitable, false if loss
     */
    fun recordTrade(pair: String, won: Boolean) {
        val stats = pairStats.getOrPut(pair) { PairStats(pair) }
        stats.totalTrades++
        if (won) {
            stats.wins++
        }
        stats.lastUpdated = System.currentTimeMillis()

        // Async persistence to Supabase
        if (controlPlane != null && botId != null) {
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    controlPlane.upsertPairWhitelist(
                        botId,
                        TradeWhitelistRecord(
                            pairId = stats.pair,
                            wins = stats.wins,
                            totalTrades = stats.totalTrades,
                            lastUpdated = kotlinx.datetime.Instant.fromEpochMilliseconds(stats.lastUpdated)
                        )
                    )
                } catch (e: Exception) {
                    // Log error or ignore for non-critical persistence
                }
            }
        }
    }
    
    /**
     * Get statistics for a pair
     */
    fun getPairStats(pair: String): PairStats? = pairStats[pair]
    
    /**
     * Get all pair statistics sorted by win rate (descending)
     */
    fun getAllStats(): List<PairStats> {
        return pairStats.values
            .sortedByDescending { it.winRatePercent }
    }
    
    /**
     * Get summary report
     */
    fun getSummary(): WhitelistSummary {
        val hardWhitelistStats = hardWhitelist
            .mapNotNull { pair -> pairStats[pair]?.let { pair to it.winRatePercent } }
            .toMap()
        
        val dynamicWhitelist = getDynamicWhitelist()
        val probationary = getProbationaryPairs()
        val blacklisted = getBlacklistedPairs()
        
        val totalTrades = pairStats.values.sumOf { it.totalTrades }
        val totalWins = pairStats.values.sumOf { it.wins }
        
        return WhitelistSummary(
            hardWhitelistCount = hardWhitelist.size,
            hardWhitelistStats = hardWhitelistStats,
            dynamicWhitelistCount = dynamicWhitelist.size,
            probationaryCount = probationary.size,
            blacklistedCount = blacklisted.size,
            totalPairsTracked = pairStats.size,

    /**
     * Load stats from Supabase
     */
    suspend fun loadFromSupabase() {
        if (controlPlane == null || botId == null) return
        try {
            val records = controlPlane.fetchPairWhitelist(botId)
            records.forEach { record ->
                pairStats[record.pairId] = PairStats(
                    pair = record.pairId,
                    wins = record.wins,
                    totalTrades = record.totalTrades,
                    lastUpdated = record.lastUpdated.toEpochMilliseconds()
                )
            }
        } catch (e: Exception) {
            // Log error
        }
    }
            totalTrades = totalTrades,
            totalWins = totalWins,
            overallWinRatePercent = if (totalTrades == 0) 0.0 else (totalWins.toDouble() / totalTrades) * 100.0,
        )
    }
    
    /**
     * Reset tracking for a specific pair (for testing/manual override)
     */
    fun resetPairStats(pair: String) {
        pairStats.remove(pair)
    }
    
    /**
     * Reset all dynamic stats (keep hard whitelist)
     */
    fun resetAllStats() {
        pairStats.clear()
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
