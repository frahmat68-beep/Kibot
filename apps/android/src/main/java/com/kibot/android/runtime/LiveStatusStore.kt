package com.kibot.android.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.toLocalDateTime

data class LiveHoldingUi(
    val asset: String,
    val amount: String,
    val valueIdr: String,
)

data class LiveLogEntry(
    val timestampEpochMs: Long,
    val category: String,
    val message: String,
)

data class LiveStatusSnapshot(
    val updatedAtEpochMs: Long,
    val activePair: String,
    val totalEquityIdr: String,
    val pnlTodayIdr: String,
    val internetPingMs: Long? = null,
    val scanUniverseCount: Int = 0,
    val radarPairs: List<String> = emptyList(),
    val holdings: List<LiveHoldingUi>,
    val statusMessage: String = "",
    val liveLogEntries: List<LiveLogEntry> = emptyList(),
) {
    companion object {
        val Empty = LiveStatusSnapshot(
            updatedAtEpochMs = 0L,
            activePair = "-",
            totalEquityIdr = "Rp0",
            pnlTodayIdr = "+Rp0",
            internetPingMs = null,
            scanUniverseCount = 0,
            radarPairs = emptyList(),
            holdings = emptyList(),
            statusMessage = "",
            liveLogEntries = emptyList(),
        )
    }
}

class LiveStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<LiveStatusSnapshot> = _state.asStateFlow()

    fun update(snapshot: LiveStatusSnapshot) {
        publish(snapshot = snapshot, event = null)
    }

    fun publish(
        snapshot: LiveStatusSnapshot,
        event: LiveLogEntry?,
    ) {
        val merged = mergeSnapshot(snapshot, event)
        _state.value = merged
        persist(merged)
    }

    fun current(): LiveStatusSnapshot = _state.value

    private fun mergeSnapshot(
        snapshot: LiveStatusSnapshot,
        event: LiveLogEntry?,
    ): LiveStatusSnapshot {
        val current = _state.value
        val effectiveTimestamp = snapshot.updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dateKey = dateKey(effectiveTimestamp)
        val currentDateKey = prefs.getString(KEY_LOG_DATE, null)
        val retainedLogs = if (currentDateKey == dateKey) {
            current.liveLogEntries
        } else {
            emptyList()
        }
        val mergedLogs = appendLog(retainedLogs, event, effectiveTimestamp)
        return snapshot.copy(
            statusMessage = snapshot.statusMessage.ifBlank { current.statusMessage },
            liveLogEntries = mergedLogs,
        )
    }

    private fun appendLog(
        current: List<LiveLogEntry>,
        event: LiveLogEntry?,
        fallbackTimestamp: Long,
    ): List<LiveLogEntry> {
        if (event == null || event.message.isBlank()) return current
        val normalized = event.copy(
            timestampEpochMs = event.timestampEpochMs.takeIf { it > 0L } ?: fallbackTimestamp,
            category = event.category.ifBlank { "STATUS" },
            message = event.message.trim(),
        )
        val existingTop = current.firstOrNull()
        if (
            existingTop != null &&
            existingTop.category == normalized.category &&
            existingTop.message == normalized.message &&
            kotlin.math.abs(existingTop.timestampEpochMs - normalized.timestampEpochMs) <= LOG_DEDUP_WINDOW_MS
        ) {
            return current
        }
        return listOf(normalized) + current.take(MAX_LIVE_LOG_ITEMS - 1)
    }

    private fun persist(snapshot: LiveStatusSnapshot) {
        prefs.edit()
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtEpochMs)
            .putString(KEY_ACTIVE_PAIR, snapshot.activePair)
            .putString(KEY_TOTAL_EQUITY, snapshot.totalEquityIdr)
            .putString(KEY_PNL_TODAY, snapshot.pnlTodayIdr)
            .putLong(KEY_INTERNET_PING_MS, snapshot.internetPingMs ?: -1L)
            .putInt(KEY_SCAN_UNIVERSE_COUNT, snapshot.scanUniverseCount)
            .putString(KEY_RADAR_PAIRS, encodeRadarPairs(snapshot.radarPairs))
            .putString(KEY_HOLDINGS, encodeHoldings(snapshot.holdings))
            .putString(KEY_STATUS_MESSAGE, snapshot.statusMessage)
            .putString(KEY_LIVE_LOGS, encodeLogs(snapshot.liveLogEntries))
            .putString(KEY_LOG_DATE, dateKey(snapshot.updatedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()))
            .apply()
    }

    private fun readSnapshot(): LiveStatusSnapshot {
        val holdings = decodeHoldings(prefs.getString(KEY_HOLDINGS, null))
        return LiveStatusSnapshot(
            updatedAtEpochMs = prefs.getLong(KEY_UPDATED_AT, 0L),
            activePair = prefs.getString(KEY_ACTIVE_PAIR, null).orEmpty().ifBlank { "-" },
            totalEquityIdr = prefs.getString(KEY_TOTAL_EQUITY, null).orEmpty().ifBlank { "Rp0" },
            pnlTodayIdr = prefs.getString(KEY_PNL_TODAY, null).orEmpty().ifBlank { "+Rp0" },
            internetPingMs = prefs.getLong(KEY_INTERNET_PING_MS, -1L).takeIf { it >= 0L },
            scanUniverseCount = prefs.getInt(KEY_SCAN_UNIVERSE_COUNT, 0),
            radarPairs = decodeRadarPairs(prefs.getString(KEY_RADAR_PAIRS, null)),
            holdings = holdings,
            statusMessage = prefs.getString(KEY_STATUS_MESSAGE, null).orEmpty(),
            liveLogEntries = decodeLogs(prefs.getString(KEY_LIVE_LOGS, null)),
        )
    }

    private fun encodeHoldings(holdings: List<LiveHoldingUi>): String {
        val array = JSONArray()
        holdings.forEach { item ->
            array.put(
                JSONObject()
                    .put("asset", item.asset)
                    .put("amount", item.amount)
                    .put("value", item.valueIdr),
            )
        }
        return array.toString()
    }

    private fun decodeHoldings(encoded: String?): List<LiveHoldingUi> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        LiveHoldingUi(
                            asset = item.optString("asset", "-"),
                            amount = item.optString("amount", "-"),
                            valueIdr = item.optString("value", "-"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeRadarPairs(pairs: List<String>): String {
        val array = JSONArray()
        pairs.forEach { array.put(it) }
        return array.toString()
    }

    private fun decodeRadarPairs(encoded: String?): List<String> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).orEmpty()
                    if (item.isNotBlank()) add(item)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeLogs(entries: List<LiveLogEntry>): String {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject()
                    .put("at", item.timestampEpochMs)
                    .put("category", item.category)
                    .put("message", item.message),
            )
        }
        return array.toString()
    }

    private fun decodeLogs(encoded: String?): List<LiveLogEntry> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        LiveLogEntry(
                            timestampEpochMs = item.optLong("at", 0L),
                            category = item.optString("category", "STATUS"),
                            message = item.optString("message", ""),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun dateKey(epochMs: Long): String {
        val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.of("Asia/Jakarta"))
        return local.date.toString()
    }

    companion object {
        private const val PREFS_NAME = "kibot_live_status"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ACTIVE_PAIR = "active_pair"
        private const val KEY_TOTAL_EQUITY = "total_equity"
        private const val KEY_PNL_TODAY = "pnl_today"
        private const val KEY_INTERNET_PING_MS = "internet_ping_ms"
        private const val KEY_SCAN_UNIVERSE_COUNT = "scan_universe_count"
        private const val KEY_RADAR_PAIRS = "radar_pairs"
        private const val KEY_HOLDINGS = "holdings"
        private const val KEY_STATUS_MESSAGE = "status_message"
        private const val KEY_LIVE_LOGS = "live_logs"
        private const val KEY_LOG_DATE = "live_log_date"
        private const val MAX_LIVE_LOG_ITEMS = 12
        private const val LOG_DEDUP_WINDOW_MS = 90_000L
    }
}
