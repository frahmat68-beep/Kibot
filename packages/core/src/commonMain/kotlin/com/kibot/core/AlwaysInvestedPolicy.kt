package com.kibot.core

/**
 * AlwaysInvestedPolicy — "Pantang Nganggur & Anti-Penakut"
 *
 * Filosofi: saldo menganggur = opportunity cost.
 * Entry diblokir hanya jika ekspektasi net setelah biaya masih di bawah ambang minimum.
 */
class AlwaysInvestedPolicy(
    private val indodaxFeePercent: Double = 0.51,
    private val maxIdleCapitalPercent: Double = 0.15,
    private val maxIdleMinutes: Int = 30,
) {
    data class EntryDecision(
        val allowed: Boolean,
        val breakEvenPercent: Double,
        val expectedNetPercent: Double,
        val rationale: String,
    )

    fun shouldEnter(
        expectedMovePercent: Double,
        spreadPercent: Double = 0.1,
        slippagePercent: Double = 0.05,
        feePercent: Double = indodaxFeePercent,
        bucketType: String = "LOCAL_PUMP",
    ): EntryDecision {
        val totalEntryCost = feePercent + (slippagePercent / 2.0)
        val totalExitCost = feePercent + (slippagePercent / 2.0)
        val breakEven = totalEntryCost + totalExitCost + spreadPercent
        val expectedNet = expectedMovePercent - breakEven
<<<<<<< HEAD
=======
        
>>>>>>> 64081d79 (TRINITY ULTIMATE: Fix Daily PnL Reset, Capital Allocation, Fee Gate, and Sync Reporting)
        val minNet = if (bucketType == "LEAD_LAG") 0.10 else 0.15
        val isAllowed = expectedNet >= minNet

        return EntryDecision(
            allowed = isAllowed,
            breakEvenPercent = breakEven,
            expectedNetPercent = expectedNet,
            rationale = if (isAllowed) {
                "ENTER: Expected +${String.format("%.2f", expectedNet)}% after fees"
            } else {
                "BLOCK: Net +${String.format("%.2f", expectedNet)}% < min $minNet%"
<<<<<<< HEAD
            },
=======
            }
>>>>>>> 64081d79 (TRINITY ULTIMATE: Fix Daily PnL Reset, Capital Allocation, Fee Gate, and Sync Reporting)
        )
    }

    fun shouldForceEntry(
        freeCapitalPercent: Double,
        idleMinutes: Int,
    ): Boolean {
        return freeCapitalPercent > maxIdleCapitalPercent && idleMinutes > maxIdleMinutes
    }
}
