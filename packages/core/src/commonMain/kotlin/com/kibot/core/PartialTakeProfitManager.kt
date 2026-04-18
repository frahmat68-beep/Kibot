package com.kibot.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * PartialTakeProfitManager - Eksekusi partial exit saat mencapai target bertahap
 */
class PartialTakeProfitManager {
    
    data class TpLevel(
        val triggerProfitPct: Double,  // Profit target (%)
        val exitPortionPct: Double,    // Berapa % dari sisa posisi yang dijual
        val label: String
    )
    
    data class TpAction(
        val pairId: String,
        val sellQuantity: Double,
        val reason: String,
        val levelKey: String
    )

    // Ladder TP berbeda per bucket sesuai KIBOT_AUDIT_AND_FIX_PROMPT
    private val LEAD_LAG_TP_LADDER = listOf(
        TpLevel(0.5, 0.30, "TP1: jual 30% di +0.5%"),
        TpLevel(1.2, 0.50, "TP2: jual 50% di +1.2%")
    )
    
    private val LOCAL_PUMP_TP_LADDER = listOf(
        TpLevel(1.0, 0.25, "TP1: jual 25% di +1%"),
        TpLevel(2.5, 0.40, "TP2: jual 40% di +2.5%"),
        TpLevel(4.5, 0.35, "TP3: jual 35% sisa di +4.5%")
    )

    // Tracking level yang sudah dieksekusi agar tidak double sell
    private val executedLevels = mutableSetOf<String>()

    fun checkTpLevels(
        pairId: String,
        bucketType: String,
        currentProfitPct: Double,
        remainingQuantity: Double
    ): TpAction? {
        val ladder = if (bucketType == "LEAD_LAG") LEAD_LAG_TP_LADDER else LOCAL_PUMP_TP_LADDER
        
        for (level in ladder) {
            val levelKey = "${pairId}_tp_${level.label}"
            if (!executedLevels.contains(levelKey) && currentProfitPct >= level.triggerProfitPct) {
                val sellQty = remainingQuantity * level.exitPortionPct
                return TpAction(
                    pairId = pairId,
                    sellQuantity = sellQty,
                    reason = level.label,
                    levelKey = levelKey
                )
            }
        }
        return null
    }

    fun markExecuted(levelKey: String) {
        executedLevels.add(levelKey)
    }

    fun clearPosition(pairId: String) {
        executedLevels.removeIf { it.startsWith("${pairId}_tp_") }
    }
}
