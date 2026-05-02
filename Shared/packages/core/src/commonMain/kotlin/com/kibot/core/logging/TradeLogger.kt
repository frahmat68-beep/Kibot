package com.kibot.core.logging

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

@Serializable
data class TradeRecord(
    val ts: String,
    val event: String, // TRADE_BUY, TRADE_SELL
    val pair: String,
    val side: String,
    val orderType: String,
    val filledPrice: Double,
    val filledIdr: Double,
    val netPnlPct: Double = 0.0,
    val exitReason: String = "",
    val bucket: String = "BUCKET_A"
)

class TradeLogger(private val rootPath: String) {
    private val logPath = "$rootPath/state/analyst/trade_log.jsonl".toPath()
    private val json = Json { ignoreUnknownKeys = true }

    fun record(record: TradeRecord) {
        try {
            val line = json.encodeToString(record)
            FileSystem.SYSTEM.appendingSink(logPath).buffer().use {
                it.writeUtf8(line + "\n")
            }
        } catch (e: Exception) {
            println("[ERROR] Failed to record trade: ${e.message}")
        }
    }
}
