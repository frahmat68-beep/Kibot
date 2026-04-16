package com.kibot.core

import com.kibot.shared.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/**
 * KiCryp TradeLogger — Persistence & Learning Bridge
 * 
 * Logs every entry and exit to:
 * 1. Local JSONL file (audit trail)
 * 2. Supabase trade_history (cloud dashboard)
 * 
 * Includes "Trinity v7.0" specific fields: pump_phase, pump_score, bucket_type.
 */
class TradeLogger(
    private val scope: CoroutineScope,
    private val controlPlane: ControlPlaneGateway,
    private val logFilePath: String = "state/kibot_trade_log.jsonl"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val logFile = File(logFilePath)

    init {
        logFile.parentFile.mkdirs()
    }

    @Serializable
    data class TradeEntryRecord(
        val tradeId: String,
        val pairId: String,
        val category: String,
        val entryPrice: DecimalValue,
        val budgetIdr: DecimalValue,
        val pumpPhase: String,
        val pumpScore: Double,
        val orderTypeEntry: String,
        val bucketType: String,
        val entryAt: String,
        val status: String = "OPEN"
    )

    @Serializable
    data class TradeExitRecord(
        val tradeId: String,
        val pairId: String,
        val category: String,
        val entryPrice: DecimalValue,
        val exitPrice: DecimalValue,
        val budgetIdr: DecimalValue,
        val pnlIdr: DecimalValue,
        val pnlPct: DecimalValue,
        val pumpPhase: String,
        val pumpScore: Double,
        val orderTypeEntry: String,
        val orderTypeExit: String,
        val holdMinutes: Long,
        val win: Boolean,
        val exitReason: String,
        val bucketType: String,
        val entryAt: String,
        val exitAt: String,
        val status: String = "CLOSED"
    )

    fun recordEntry(
        pairId: String,
        entryPrice: DecimalValue,
        budgetIdr: DecimalValue,
        category: String,
        pumpPhase: String,
        pumpScore: Double,
        orderType: String,
        bucketType: String
    ): String {
        val tradeId = UUID.randomUUID().toString().take(8)
        val record = TradeEntryRecord(
            tradeId = tradeId,
            pairId = pairId,
            category = category,
            entryPrice = entryPrice,
            budgetIdr = budgetIdr,
            pumpPhase = pumpPhase,
            pumpScore = pumpScore,
            orderTypeEntry = orderType,
            bucketType = bucketType,
            entryAt = Clock.System.now().toIsoString(),
        )

        appendToFile(json.encodeToString(record))
        println("[TRADELOG] ENTRY $pairId @ $entryPrice Rp$budgetIdr [$tradeId]")
        
        return tradeId
    }

    fun recordExit(
        tradeId: String,
        exitPrice: DecimalValue,
        exitReason: String,
        orderTypeExit: String = "LIMIT"
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val openRecord = findOpenRecord(tradeId) ?: run {
                    println("[TRADELOG][WARN] Could not find open trade $tradeId for exit")
                    return@launch
                }

                val pnlGains = (exitPrice - openRecord.entryPrice) / openRecord.entryPrice
                val feeCost = 0.007 // Round-trip fee estimate (~0.7%)
                val netPct = pnlGains - feeCost
                val pnlIdr = openRecord.budgetIdr * netPct
                
                val entryAt = Instant.parse(openRecord.entryAt)
                val exitAt = Clock.System.now()
                val holdMinutes = (exitAt - entryAt).inWholeMinutes

                val exitRecord = TradeExitRecord(
                    tradeId = tradeId,
                    pairId = openRecord.pairId,
                    category = openRecord.category,
                    entryPrice = openRecord.entryPrice,
                    exitPrice = exitPrice,
                    budgetIdr = openRecord.budgetIdr,
                    pnlIdr = pnlIdr,
                    pnlPct = DecimalValue.fromDouble(netPct),
                    pumpPhase = openRecord.pumpPhase,
                    pumpScore = openRecord.pumpScore,
                    orderTypeEntry = openRecord.orderTypeEntry,
                    orderTypeExit = orderTypeExit,
                    holdMinutes = holdMinutes,
                    win = pnlIdr > 0.0,
                    exitReason = exitReason,
                    bucketType = openRecord.bucketType,
                    entryAt = openRecord.entryAt,
                    exitAt = exitAt.toIsoString()
                )

                appendToFile(json.encodeToString(exitRecord))
                println("[TRADELOG] EXIT ${openRecord.pairId} PnL=Rp${pnlIdr.toFormattedString(0)} (${(netPct * 100.0).toFormattedString(2)}%) reason=$exitReason [$tradeId]")
                
                syncToSupabase(exitRecord)
            } catch (e: Exception) {
                println("[TRADELOG][ERR] Failed to record exit: ${e.message}")
            }
        }
    }

    fun readAll(): List<TradeExitRecord> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString<TradeExitRecord>(line) }.getOrNull()
        }
    }

    private fun appendToFile(line: String) {
        synchronized(this) {
            logFile.appendText(line + "\n")
        }
    }

    private fun findOpenRecord(tradeId: String): TradeEntryRecord? {
        if (!logFile.exists()) return null
        return logFile.useLines { lines ->
            lines.mapNotNull { line ->
                try {
                    val node = json.parseToJsonElement(line)
                    if (node.asObject()["tradeId"]?.asPrimitive()?.contentOrNull == tradeId &&
                        node.asObject()["status"]?.asPrimitive()?.contentOrNull == "OPEN") {
                        json.decodeFromString<TradeEntryRecord>(line)
                    } else null
                } catch (e: Exception) { null }
            }.lastOrNull()
        }
    }

    private fun syncToSupabase(record: TradeExitRecord) {
        scope.launch {
            try {
                controlPlane.submitTradeLog(
                    TradeLogSubmission(
                        tradeId = record.tradeId,
                        pairId = record.pairId,
                        category = record.category,
                        entryPrice = record.entryPrice,
                        exitPrice = record.exitPrice,
                        budgetIdr = record.budgetIdr,
                        pnlIdr = record.pnlIdr,
                        pnlPct = record.pnlPct,
                        orderTypeEntry = record.orderTypeEntry,
                        orderTypeExit = record.orderTypeExit,
                        pumpPhase = record.pumpPhase,
                        pumpScore = record.pumpScore,
                        holdMinutes = record.holdMinutes,
                        win = record.win,
                        exitReason = record.exitReason,
                        bucketType = record.bucketType,
                        entryAt = Instant.parse(record.entryAt),
                        exitAt = Instant.parse(record.exitAt),
                    ),
                )
            } catch (e: Exception) {
                println("[TRADELOG][CLOUD][ERR] Supabase sync failed: ${e.message}")
            }
        }
    }

    private fun Instant.toIsoString(): String = this.toString()
    
    // Helper extension for simple JSON parsing without heavy boilerplate
    private fun kotlinx.serialization.json.JsonElement.asObject() = jsonObject
    private fun kotlinx.serialization.json.JsonElement.asPrimitive() = jsonPrimitive
}
