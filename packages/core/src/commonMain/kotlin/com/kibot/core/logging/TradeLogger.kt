package com.kibot.core.logging

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class TradeRecord(
    val id: String,
    val timestampUtc: String,
    val pair: String,
    val side: String,              // "BUY" | "SELL"
    val orderType: String,         // "LIMIT" | "MARKET"
    val requestedPrice: Double,
    val filledPrice: Double,
    val filledBaseAmount: Double,
    val filledIdr: Double,
    val feeIdr: Double,
    val feeType: String,           // "MAKER" | "TAKER"
    val grossPnlPct: Double,
    val netPnlPct: Double,
    val netPnlIdr: Double,
    val holdingDurationMs: Long,
    val exitReason: String,        // "TRAILING_STOP"|"PARTIAL_TP_1"|"PARTIAL_TP_2"|"TIME_EXIT"|"HARD_STOP"|"EMERGENCY"|"VOLUME_COLLAPSE"|"PEAK_DETECTED"
    val signalSource: String,      // "LEAD_LAG_BINANCE"|"LEAD_LAG_CRYPTOCOM"|"LOCAL_INDODAX"|"MANUAL"
    val bucket: String,            // "BUCKET_A"|"BUCKET_B"
    val entryConvictionScore: Double,
    val entryKellyFraction: Double,
    val balanceAfterIdr: Double,
    val marketRegimeAtEntry: String,    // "BULLISH"|"BEARISH"|"SIDEWAYS"
    val btcChange1hAtEntry: Double,
    val lossReason: String = ""         // post-mortem jika netPnlPct < 0
)

object TradeLogger {
    private val logPath = File("state/trade_log.jsonl")
    private val summaryPath = File("state/trade_summary.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init { logPath.parentFile?.mkdirs() }

    // ATOMIC WRITE — mencegah corrupt jika crash saat write
    fun record(trade: TradeRecord) {
        try {
            val line = json.encodeToString(trade) + "\n"
            // Append ke file utama (JSONL format)
            logPath.appendText(line, Charsets.UTF_8)
            updateSummary()
        } catch (e: Exception) {
            System.err.println("[TRADE_LOGGER] Write failed for ${trade.pair}: ${e.message}")
        }
    }

    fun readAll(): List<TradeRecord> {
        if (!logPath.exists()) return emptyList()
        return logPath.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<TradeRecord>(it) }.getOrNull() }
    }

    fun getTodayTrades() = readAll().filter {
        it.timestampUtc.startsWith(Instant.now().toString().take(10))
    }

    fun getLast7DaysTrades() = readAll().filter {
        it.timestampUtc >= Instant.now().minusSeconds(7 * 86400).toString()
    }

    fun getLast30DaysTrades() = readAll().filter {
        it.timestampUtc >= Instant.now().minusSeconds(30 * 86400).toString()
    }

    fun getSellTrades() = readAll().filter { it.side == "SELL" }

    private fun updateSummary() {
        data class Stats(
            val count: Int, val winCount: Int, val lossCount: Int,
            val totalNetPnlPct: Double, val totalNetPnlIdr: Double,
            val totalFeeIdr: Double, val marketOrderCount: Int,
            val bucketACount: Int, val bucketBCount: Int,
            val avgHoldingMinutes: Double,
            val topLosers: List<Map<String, Any>>
        )

        fun calcStats(trades: List<TradeRecord>): Stats {
            val sells = trades.filter { it.side == "SELL" }
            return Stats(
                count = sells.size,
                winCount = sells.count { it.netPnlPct > 0 },
                lossCount = sells.count { it.netPnlPct <= 0 },
                totalNetPnlPct = sells.sumOf { it.netPnlPct },
                totalNetPnlIdr = sells.sumOf { it.netPnlIdr },
                totalFeeIdr = trades.sumOf { it.feeIdr },
                marketOrderCount = trades.count { it.orderType == "MARKET" },
                bucketACount = trades.count { it.bucket == "BUCKET_A" },
                bucketBCount = trades.count { it.bucket == "BUCKET_B" },
                avgHoldingMinutes = if (sells.isEmpty()) 0.0
                    else sells.map { it.holdingDurationMs / 60000.0 }.average(),
                topLosers = sells.filter { it.netPnlPct < 0 }
                    .sortedBy { it.netPnlPct }.take(5)
                    .map { mapOf(
                        "pair" to it.pair, "pnl" to it.netPnlPct,
                        "lossReason" to it.lossReason, "exitReason" to it.exitReason,
                        "orderType" to it.orderType, "holdingMin" to it.holdingDurationMs/60000
                    )}
            )
        }

        val summary = mapOf(
            "generatedAt" to Instant.now().toString(),
            "today" to calcStats(getTodayTrades()),
            "last7Days" to calcStats(getLast7DaysTrades()),
            "last30Days" to calcStats(getLast30DaysTrades())
        )

        // ATOMIC write summary
        try {
            val tmp = File("${summaryPath.path}.tmp.${System.nanoTime()}")
            tmp.writeText(Json.encodeToString(summary), Charsets.UTF_8)
            tmp.renameTo(summaryPath)
        } catch (e: Exception) {
            System.err.println("[TRADE_LOGGER] Summary write failed: ${e.message}")
        }
    }
}
