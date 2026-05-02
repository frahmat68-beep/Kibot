package com.kibot.core.agents

import com.kibot.core.TradeLogger
import com.kibot.shared.models.BotId
import com.kibot.shared.models.MarketRegime
import org.slf4j.LoggerFactory

/**
 * SystemAnalyst: The autonomous "behavioral analyst" of the KiBot Hydra architecture.
 * Responsible for generating situational reports and performance attribution after trade events.
 */
interface SystemAnalyst {
    /**
     * Conducts a situational analysis of a trade exit.
     */
    fun analyzeExit(
        record: TradeLogger.TradeExitRecord,
        regime: MarketRegime,
        botId: BotId,
    ): SituationalReport

    data class SituationalReport(
        val tradeId: String,
        val pairId: String,
        val pnlPct: Double,
        val regime: MarketRegime,
        val attribution: String,
        val sentiment: String,
    )
}

/**
 * Default implementation of the SystemAnalyst.
 */
class DefaultSystemAnalyst : SystemAnalyst {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var reportCount = 0
    fun getReportCount() = reportCount

    override fun analyzeExit(
        record: TradeLogger.TradeExitRecord,
        regime: MarketRegime,
        botId: BotId
    ): SystemAnalyst.SituationalReport {
        reportCount++
        val pnlPct = record.pnlPct
        
        val (sentiment, attribution) = when {
            pnlPct >= 5.0 -> "EXTREME_BULLISH" to "Strategy perfectly captured the $regime pump. Excellent execution."
            pnlPct >= 1.0 -> "BULLISH" to "Solid capture in $regime market. Profitable and controlled."
            pnlPct > -0.5 && pnlPct < 0.5 -> "NEUTRAL" to "Trade break-even. Efficient use of capital in unclear regime."
            pnlPct <= -3.0 -> "CRITICAL_BEARISH" to "Major loss detected in $regime. Possible strategy-regime mismatch or late entry."
            pnlPct < 0.0 -> "BEARISH" to "Minor drawdown. Stop-loss likely worked as intended to preserve capital."
            else -> "UNKNOWN" to "Data inconclusive. Analyzing regime correlation..."
        }

        val report = SystemAnalyst.SituationalReport(
            tradeId = record.tradeId,
            pairId = record.pairId,
            pnlPct = pnlPct,
            regime = regime,
            attribution = attribution,
            sentiment = sentiment
        )

        logger.info(
            "[ANALYST] Post-Mortem Report for ${record.pairId} [$botId]: " +
            "PnL=${pnlPct.format(2)}%, Regime=$regime, Sentiment=$sentiment. " +
            "Note: $attribution"
        )

        return report
    }

    private fun Double.format(digits: Int) = String.format("%.${digits}f", this)
}
