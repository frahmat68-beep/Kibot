package com.kicryp.core

/**
 * Order Execution Strategy Optimizer
 * 
 * Determine kapan harus pakai Limit Order (Maker) vs Market Order (Taker)
 * berdasarkan situasi dan biaya yang akan dihemat/dikeluarkan
 */
class OrderExecutionStrategy {

    enum class OrderType {
        LIMIT,      // Maker - antre di order book
        MARKET,     // Taker - hajar langsung
        STOP_LOSS,  // Emergency exit
    }

    data class ExecutionRecommendation(
        val orderType: OrderType,
        val rationale: String,
        val estimatedFee: Double,
        val expectedFillPercent: Double,  // Chance order fills (0.0 - 1.0)
        val urgency: Int  // 1 = low urgency, 10 = emergency
    )

    /**
     * Strategize ORDER TYPE untuk ENTRY
     * 
     * 80% STABLE COINS: Pake LIMIT untuk hemat fee
     * 20% ANOMALI/PUMP: Pake MARKET untuk speed (jangan telat!)
     */
    fun recommendEntryOrderType(
        isAnomalyCoin: Boolean,  // True = pump gambling, False = stable scalp
        pumpConfidence: Double,   // 0.0 - 1.0
        spreadPercent: Double,    // Bid-Ask spread
        volumeScore: Double       // 0.0 - 1.0 (liquidity)
    ): ExecutionRecommendation {
        
        return when {
            // 🔴 ANOMALI MODE: Harus cepat, jangan telat
            isAnomalyCoin && pumpConfidence > 0.75 -> {
                // Risk kehilangan opportunity >> risk fee
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Pump detected (conf=${pumpConfidence}), use Market to catch peak. Fee sacrifice worth it.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.99,
                    urgency = 9
                )
            }

            // 🟢 STABLE MODE: Hemat fee dengan Limit order
            !isAnomalyCoin && volumeScore > 0.3 && spreadPercent >= 0.008 -> {
                // Spread cukup lebar, limit order pasti isi
                ExecutionRecommendation(
                    orderType = OrderType.LIMIT,
                    rationale = "Stable coin, good spread (${spreadPercent}%), use Limit to save 0.10% fee.",
                    estimatedFee = FeeCalculator.MAKER_FEE_PER_TX,
                    expectedFillPercent = 0.85,
                    urgency = 2
                )
            }

            // 🟡 BORDERLINE: Liquid market but normal spread
            !isAnomalyCoin && volumeScore > 0.5 && spreadPercent in 0.004..0.008 -> {
                // Decent liquidity, bisa coba limit dulu tapi ready fallback ke market
                ExecutionRecommendation(
                    orderType = OrderType.LIMIT,
                    rationale = "Normal spread ($spreadPercent), try Limit first but be ready to Market if needed.",
                    estimatedFee = FeeCalculator.MAKER_FEE_PER_TX,
                    expectedFillPercent = 0.65,
                    urgency = 3
                )
            }

            // 🔴 ILLIQUID: Spread terlalu tipis, bahaya gap
            spreadPercent < 0.004 || volumeScore < 0.2 -> {
                // Spread terlalu tipis, jangan scalp dengan taker
                ExecutionRecommendation(
                    orderType = OrderType.LIMIT,
                    rationale = "Spread too tight ($spreadPercent) or illiquid. Use Limit despite low fill rate to avoid slippage.",
                    estimatedFee = FeeCalculator.MAKER_FEE_PER_TX,
                    expectedFillPercent = 0.40,
                    urgency = 4
                )
            }

            else -> {
                // Default: safer to wait with limit
                ExecutionRecommendation(
                    orderType = OrderType.LIMIT,
                    rationale = "Default to Limit order for fee optimization.",
                    estimatedFee = FeeCalculator.MAKER_FEE_PER_TX,
                    expectedFillPercent = 0.60,
                    urgency = 3
                )
            }
        }
    }

    /**
     * Strategize ORDER TYPE untuk EXIT (exit strategy super penting!)
     * 
     * Goal: Close position dengan NET PROFIT maksimal
     * - Kalau profit target tercapai + spread bagus → LIMIT (ambil harga lebih baik)
     * - Kalau harga mulai turun → MARKET (jangan kehilangan profit)
     * - Kalau rugi + harga terus turun → MARKET (cut loss cepat)
     */
    fun recommendExitOrderType(
        currentProfit: Double,           // Net profit percentage
        targetProfit: Double,            // Target profit to achieve
        priceVelocity: Double,           // Rate of price change (-1.0 to +1.0)
        timeSinceEntry: Long,           // Milliseconds
        spreadPercent: Double,
        volumeScore: Double,
        isAnomalyCoin: Boolean = false   // True for aggressive bucket
    ): ExecutionRecommendation {
        
        val timeHeldMinutes = timeSinceEntry / 60_000L
        val isMomentumUp = priceVelocity > 0.0005
        val isMomentumDown = priceVelocity < -0.0005
        val profitRealized = currentProfit > 0

        return when {
            // ✅ PROFIT TARGET TERCAPAI: Ambil profit dengan Limit (jangan greedy)
            currentProfit >= targetProfit && !isMomentumDown && spreadPercent > 0.006 -> {
                ExecutionRecommendation(
                    orderType = OrderType.LIMIT,
                    rationale = "Profit target reached (${currentProfit}%). Use Limit to get even better price. Momentum neutral.",
                    estimatedFee = FeeCalculator.MAKER_FEE_PER_TX,
                    expectedFillPercent = 0.80,
                    urgency = 2
                )
            }

            // ⚠️ PROFIT MULAI TERANCAM: Ambil profit dengan Market sebelum hilang
            currentProfit > 0 && isMomentumDown && currentProfit < targetProfit * 1.5 -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Price starting to fall (velocity=${priceVelocity}). Secure profit with Market order.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.98,
                    urgency = 7
                )
            }

            // 🔴 HELD TOO LONG: Momentum lost, close dengan Market
            timeHeldMinutes > 30 && !isAnomalyCoin && currentProfit > 0 -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Stable position held >30 min ($timeHeldMinutes min). Momentum likely fading, close with Market.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.98,
                    urgency = 6
                )
            }
            
            // 🔴 AGGRESSIVE HELD TOO LONG: Close aggressive positions faster
            timeHeldMinutes > 45 && isAnomalyCoin && currentProfit > 0 -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Aggressive position held >45 min ($timeHeldMinutes min). Close with Market to rotate capital.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.98,
                    urgency = 7
                )
            }

            // 🔴 HUGE MOMENTUM DOWN: Cut loss fast!
            currentProfit < -0.5 && isMomentumDown -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Stop loss triggered. Cut loss fast with Market order.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.98,
                    urgency = 10  // Emergency!
                )
            }
            
            // 🟠 BREAKEVEN PROTECTION: In profit but momentum turning down
            currentProfit > 0 && currentProfit < targetProfit * 0.5 && isMomentumDown -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Breakeven protection: profit ${currentProfit}% but momentum down. Exit NOW before losing gains.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.98,
                    urgency = 8
                )
            }

            // 🟡 SMALL PROFIT: Could go either way
            currentProfit in 0.003..0.008 -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Small profit ($currentProfit%). Close with Market to secure before momentum shifts.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.97,
                    urgency = 5
                )
            }

            // Default
            else -> {
                ExecutionRecommendation(
                    orderType = OrderType.MARKET,
                    rationale = "Default to Market for safety.",
                    estimatedFee = FeeCalculator.TAKER_FEE_PER_TX,
                    expectedFillPercent = 0.95,
                    urgency = 5
                )
            }
        }
    }

    /**
     * Calculate if breakeven is achievable with current market
     */
    fun canAchieveBreakEven(
        entryPrice: Double,
        currentBidPrice: Double,
        currentAskPrice: Double,
        usingMakerEntry: Boolean
    ): Boolean {
        val bep = FeeCalculator.getBreakEvenPoint(usingMakerEntry)
        val requiredExitPrice = entryPrice * (1.0 + bep)
        return currentBidPrice >= requiredExitPrice
    }

    /**
     * Calculate recommended profit target based on risk level
     * 
     * 80/20 Strategy:
     * - 80% stable: Conservative target (1.5-2%)
     * - 20% anomali: Aggressive target (3-5%)
     */
    fun recommendProfitTarget(
        isAnomalyCoin: Boolean,
        volumeScore: Double,
        volatility: Double
    ): Double {
        return when {
            // Anomali mode: aggressive
            isAnomalyCoin -> {
                when {
                    volatility > 2.0 -> 0.05  // 5% target
                    volatility > 1.0 -> 0.04  // 4% target
                    else -> 0.03               // 3% target
                }
            }
            // Stable mode: conservative
            else -> {
                when {
                    volumeScore > 0.7 -> 0.015  // 1.5% target (high volume = stable)
                    volumeScore > 0.4 -> 0.018  // 1.8% target
                    else -> 0.020                // 2.0% target (low volume = need more margin)
                }
            }
        }
    }
}
