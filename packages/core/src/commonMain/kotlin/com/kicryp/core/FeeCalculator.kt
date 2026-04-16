package com.kicryp.core

/**
 * Indodax Fee Calculator (2025/2026)
 * 
 * All-In Fee Structure:
 * - Taker (Market Order): 0.33% per tx (0.66% round-trip)
 * - Maker (Limit Order): 0.23% per tx (0.46% round-trip)
 * - Breakdown: Trading Fee + PPN + PPh + CFX
 */
object FeeCalculator {

    // Fee Constants (Indodax 2025/2026)
    const val TAKER_FEE_PER_TX = 0.33 / 100.0  // 0.33% per transaction
    const val MAKER_FEE_PER_TX = 0.23 / 100.0  // 0.23% per transaction
    const val TAKER_ROUND_TRIP_FEE = 0.66 / 100.0  // Buy + Sell
    const val MAKER_ROUND_TRIP_FEE = 0.46 / 100.0  // Buy + Sell

    // Break-Even Points
    const val TAKER_BEP = 0.70 / 100.0  // +0.70% minimum to cover all fees
    const val MAKER_BEP = 0.46 / 100.0  // +0.46% minimum to cover all fees

    // Spread Guardrail
    const val MIN_SAFE_SPREAD_FOR_SCALP = 0.008  // 0.8%

    // Withdrawal Fees
    const val WITHDRAWAL_FEE_IDR = 10_000L  // Rp 10.000 flat
    const val EWALLET_FEE_PERCENT = 0.0167  // 1.67% for GoPay/OVO

    /**
     * Calculate buy fee for entry order
     * @param entryPrice Entry price in IDR
     * @param quantity Quantity to buy
     * @param isMakerOrder True = Limit order (Maker), False = Market order (Taker)
     * @return Fee amount in IDR
     */
    fun calculateBuyFee(
        entryPrice: Double,
        quantity: Double,
        isMakerOrder: Boolean = false
    ): Double {
        val totalValue = entryPrice * quantity
        val feePercent = if (isMakerOrder) MAKER_FEE_PER_TX else TAKER_FEE_PER_TX
        return totalValue * feePercent
    }

    /**
     * Calculate sell fee for exit order
     */
    fun calculateSellFee(
        exitPrice: Double,
        quantity: Double,
        isMakerOrder: Boolean = false
    ): Double {
        val totalValue = exitPrice * quantity
        val feePercent = if (isMakerOrder) MAKER_FEE_PER_TX else TAKER_FEE_PER_TX
        return totalValue * feePercent
    }

    /**
     * Calculate total round-trip fee (entry + exit)
     */
    fun calculateRoundTripFee(
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        entryIsMaker: Boolean = false,
        exitIsMaker: Boolean = false
    ): Double {
        val buyFee = calculateBuyFee(entryPrice, quantity, entryIsMaker)
        val sellFee = calculateSellFee(exitPrice, quantity, exitIsMaker)
        return buyFee + sellFee
    }

    /**
     * Calculate profit AFTER fees
     * @param entryPrice Entry price
     * @param exitPrice Exit price  
     * @param quantity Quantity traded
     * @param entryIsMaker Use Limit order for entry
     * @param exitIsMaker Use Limit order for exit
     * @return Net profit in IDR after all fees
     */
    fun calculateNetProfit(
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        entryIsMaker: Boolean = false,
        exitIsMaker: Boolean = false
    ): Double {
        val grossProfit = (exitPrice - entryPrice) * quantity
        val totalFee = calculateRoundTripFee(entryPrice, exitPrice, quantity, entryIsMaker, exitIsMaker)
        return grossProfit - totalFee
    }

    /**
     * Calculate profit percentage AFTER fees
     * @return Percentage profit (e.g., 0.015 = 1.5%)
     */
    fun calculateNetProfitPercent(
        entryPrice: Double,
        exitPrice: Double,
        entryIsMaker: Boolean = false,
        exitIsMaker: Boolean = false
    ): Double {
        val investmentValue = entryPrice
        val feePercent = if (entryIsMaker && exitIsMaker) {
            MAKER_ROUND_TRIP_FEE
        } else if (!entryIsMaker && !exitIsMaker) {
            TAKER_ROUND_TRIP_FEE
        } else {
            MAKER_FEE_PER_TX + TAKER_FEE_PER_TX
        }
        
        val priceChangePercent = (exitPrice - entryPrice) / entryPrice
        return priceChangePercent - feePercent
    }

    /**
     * Calculate required exit price to achieve target profit
     * @param entryPrice Entry price
     * @param targetProfitPercent Target profit percentage (e.g., 0.015 = 1.5%)
     * @param entryIsMaker Entry order type
     * @param exitIsMaker Exit order type
     * @return Required exit price
     */
    fun calculateRequiredExitPrice(
        entryPrice: Double,
        targetProfitPercent: Double,
        entryIsMaker: Boolean = false,
        exitIsMaker: Boolean = false
    ): Double {
        val feePercent = if (entryIsMaker && exitIsMaker) {
            MAKER_ROUND_TRIP_FEE
        } else if (!entryIsMaker && !exitIsMaker) {
            TAKER_ROUND_TRIP_FEE
        } else {
            MAKER_FEE_PER_TX + TAKER_FEE_PER_TX
        }

        // target = (exitPrice/entryPrice - 1) - fees
        // exitPrice/entryPrice = target + fees + 1
        // exitPrice = entryPrice * (target + fees + 1)
        return entryPrice * (targetProfitPercent + feePercent + 1.0)
    }

    /**
     * Check if trade is profitable AFTER fees
     * @param entryPrice Entry price
     * @param exitPrice Exit price
     * @param isMakerEntry Use Limit order for entry
     * @param isMakerExit Use Limit order for exit
     * @return True if net profit > 0 after all fees
     */
    fun isProfitable(
        entryPrice: Double,
        exitPrice: Double,
        isMakerEntry: Boolean = false,
        isMakerExit: Boolean = false
    ): Boolean {
        val netProfitPercent = calculateNetProfitPercent(entryPrice, exitPrice, isMakerEntry, isMakerExit)
        return netProfitPercent > 0
    }

    /**
     * Get break-even point based on order type
     */
    fun getBreakEvenPoint(isMaker: Boolean): Double {
        return if (isMaker) MAKER_BEP else TAKER_BEP
    }

    /**
     * Validate if spread is safe for scalping
     * @param bidPrice Best bid price
     * @param askPrice Best ask price
     * @return True if spread > 0.8%, safe for scalping
     */
    fun isSpreadSafeForScalp(bidPrice: Double, askPrice: Double): Boolean {
        if (askPrice <= bidPrice) return false
        val spreadPercent = (askPrice - bidPrice) / bidPrice
        return spreadPercent >= MIN_SAFE_SPREAD_FOR_SCALP
    }

    /**
     * Summary of fees for a trade
     */
    data class FeeSummary(
        val entryPrice: Double,
        val exitPrice: Double,
        val quantity: Double,
        val entryFee: Double,
        val exitFee: Double,
        val totalFee: Double,
        val grossProfit: Double,
        val netProfit: Double,
        val profitPercent: Double,
        val isUseMaker: Boolean
    )

    /**
     * Generate fee summary for analysis
     */
    fun summarizeFees(
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
        isMaker: Boolean = false
    ): FeeSummary {
        val entryFee = calculateBuyFee(entryPrice, quantity, isMaker)
        val exitFee = calculateSellFee(exitPrice, quantity, isMaker)
        val totalFee = entryFee + exitFee
        val grossProfit = (exitPrice - entryPrice) * quantity
        val netProfit = grossProfit - totalFee
        val profitPercent = calculateNetProfitPercent(entryPrice, exitPrice, isMaker, isMaker)

        return FeeSummary(
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            quantity = quantity,
            entryFee = entryFee,
            exitFee = exitFee,
            totalFee = totalFee,
            grossProfit = grossProfit,
            netProfit = netProfit,
            profitPercent = profitPercent,
            isUseMaker = isMaker
        )
    }
}
