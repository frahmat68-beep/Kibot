package com.kibot.core

/**
 * ProfitLockManager - Amankan persentase modal dari profit riil
 */
class ProfitLockManager(private val lockRatioPct: Double = 0.30) {
    
    private var lockedProfitIdr: Double = 0.0
    private var todayTotalProfitIdr: Double = 0.0
    
    data class ProfitLockResult(
        val locked: Double,
        val reDeployable: Double
    )

    /**
     * Saat profit terealisasi, kunci sebagian (default 30%)
     */
    fun onProfitRealized(profitIdr: Double): ProfitLockResult {
        if (profitIdr <= 0) return ProfitLockResult(locked = 0.0, reDeployable = profitIdr)
        
        val locked = profitIdr * lockRatioPct
        val reDeployable = profitIdr * (1 - lockRatioPct)
        
        lockedProfitIdr += locked
        todayTotalProfitIdr += profitIdr
        
        return ProfitLockResult(locked = locked, reDeployable = reDeployable)
    }
    
    fun getStatus(): Map<String, Double> = mapOf(
        "lockedToday" to lockedProfitIdr,
        "totalProfitToday" to todayTotalProfitIdr
    )
    
    fun resetDaily() {
        lockedProfitIdr = 0.0
        todayTotalProfitIdr = 0.0
    }
}
