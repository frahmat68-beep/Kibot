package com.kibot.android.runtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveHoldingUi(
    val asset: String,
    val amount: String,
    val valueIdr: String,
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
        )
    }
}

class LiveStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<LiveStatusSnapshot> = _state.asStateFlow()

    fun update(snapshot: LiveStatusSnapshot) {
        _state.value = snapshot
        persist(snapshot)
    }

    fun current(): LiveStatusSnapshot = _state.value

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
    }
}
