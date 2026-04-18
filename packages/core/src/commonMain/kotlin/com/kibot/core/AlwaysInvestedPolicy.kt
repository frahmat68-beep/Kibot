package com.kibot.core

import kotlin.time.Duration.Companion.minutes

/**
 * AlwaysInvestedPolicy — "Pantang Nganggur & Anti-Penakut"
 * 
 * Filosofi: Saldo menganggur = kerugian waktu
 * Bot WAJIB entry jika perhitungan matematik positif
 */
class AlwaysInvestedPolicy(
    private val indodaxFeePercent: Double = 0.51, // maker + taker average
    private val maxIdleCapitalPercent: Double = 0.15,
    private val maxIdleMinutes: Int = 30,
) {
    data class EntryDecision(
        val allowed: Boolean,
        val breakEvenPercent: Double,
        val expectedNetPercent: Double,
        val rationale: String,
    )
    
    /**
     * Hitung apakah entry mathematically profitable
     * Entry HANYA diblokir jika GUARANTEED LOSS
     */
    fun shouldEnter(
        expectedMovePercent: Double,
        spreadPercent: Double = 0.1,
        slippagePercent: Double = 0.05,
        feePercent: Double = 0.4211,
    ): EntryDecision {
        val totalEntryCost = feePercent + (slippagePercent / 2)
        val totalExitCost = feePercent + (slippagePercent / 2)
        val breakEven = totalEntryCost + totalExitCost + spreadPercent
        val expectedNet = expectedMovePercent - breakEven
        
        return EntryDecision(
            allowed = expectedNet >= 0.0, // ANY positive = GO
            breakEvenPercent = breakEven,
            expectedNetPercent = expectedNet,
            rationale = if (expectedNet >= 0) {
                "ENTER: Expected +${String.format("%.2f", expectedNet)}% after fees"
            } else {
                "BLOCK: Guaranteed loss of ${String.format("%.2f", -expectedNet)}%"
            }
        )
    }
    
    /**
     * Force rotation jika cash idle terlalu lama
     */
    fun shouldForceEntry(
        freeCapitalPercent: Double,
        idleMinutes: Int,
    ): Boolean {
        return freeCapitalPercent > maxIdleCapitalPercent && idleMinutes > maxIdleMinutes
    }
}
