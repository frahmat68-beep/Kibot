package com.kibot.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.kibot.android.MainActivity
import com.kibot.android.R
import com.kibot.android.runtime.BotForegroundService
import com.kibot.android.runtime.LiveStatusSnapshot
import kotlinx.datetime.toLocalDateTime

class KiBotWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = WidgetSnapshotStore(context).read()
        updateWidgets(context, appWidgetManager, appWidgetIds, snapshot)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = Unit

    companion object {
        fun updateAll(context: Context, snapshot: LiveStatusSnapshot) {
            WidgetSnapshotStore(context).write(snapshot)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KiBotWidgetProvider::class.java))
            updateWidgets(context, manager, ids, snapshot)
        }

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            snapshot: LiveStatusSnapshot,
        ) {
            if (appWidgetIds.isEmpty()) return
            appWidgetIds.forEach { widgetId ->
                val views = RemoteViews(context.packageName, R.layout.widget_kibot_status).apply {
                    val dailyReturnPct = derivePnlPct(snapshot)
                    val live = snapshot.updatedAtEpochMs > 0L &&
                        (System.currentTimeMillis() - snapshot.updatedAtEpochMs) <= 90_000L
                    val pingLabel = snapshot.internetPingMs?.let { "${it} ms" } ?: "--"
                    setTextViewText(R.id.widget_title, "KiBot")
                    setTextViewText(R.id.widget_status_chip, "PING")
                    setTextColor(
                        R.id.widget_status_chip,
                        if (live) 0xFF2DD881.toInt() else 0xFFA8B7D7.toInt(),
                    )
                    setInt(
                        R.id.widget_status_chip,
                        "setBackgroundResource",
                        if (live) R.drawable.widget_pnl_positive else R.drawable.widget_pnl_neutral,
                    )
                    setTextViewText(R.id.widget_meta, "Ping $pingLabel")
                    setTextViewText(R.id.widget_equity, snapshot.totalEquityIdr)
                    setTextViewText(R.id.widget_daily_return_value, dailyReturnPct)
                    setTextViewText(R.id.widget_pnl_value, snapshot.pnlTodayIdr)
                    setTextColor(R.id.widget_daily_return_value, pnlTextColor(dailyReturnPct))
                    setTextColor(R.id.widget_pnl_value, pnlTextColor(snapshot.pnlTodayIdr))
                    setInt(R.id.widget_daily_return_value, "setBackgroundResource", pnlBadgeBackground(dailyReturnPct))
                    setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
                }
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun pnlTextColor(label: String): Int {
            return when {
                label.trim().startsWith("-") -> 0xFFEF4444.toInt()
                label.trim() == "+Rp0" || label.trim() == "Rp0" -> 0xFFE5E7EB.toInt()
                else -> 0xFF2DD881.toInt()
            }
        }

        private fun pnlBadgeBackground(label: String): Int {
            return when {
                label.trim().startsWith("-") -> R.drawable.widget_pnl_negative
                label.trim() == "+Rp0" || label.trim() == "Rp0" -> R.drawable.widget_pnl_neutral
                else -> R.drawable.widget_pnl_positive
            }
        }

        private fun formatUpdated(epochMs: Long): String {
            if (epochMs <= 0L) return "--:--"
            val local = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
                .toLocalDateTime(kotlinx.datetime.TimeZone.of("Asia/Jakarta"))
            val hh = local.hour.toString().padStart(2, '0')
            val mm = local.minute.toString().padStart(2, '0')
            return "$hh:$mm"
        }

        private fun derivePnlPct(snapshot: LiveStatusSnapshot): String {
            val equity = snapshot.totalEquityIdr.parseRupiahLabel() ?: return "+0.0%"
            val pnl = snapshot.pnlTodayIdr.parseRupiahLabel() ?: return "+0.0%"
            val opening = (equity - pnl).takeIf { it > 0.0 } ?: return "+0.0%"
            val pct = kotlin.math.abs((pnl / opening) * 100.0)
            val prefix = if (snapshot.pnlTodayIdr.trim().startsWith("-") || pnl < 0.0) "-" else "+"
            return "$prefix${"%.1f".format(kotlin.math.abs(pct))}%"
        }

        private fun String.parseRupiahLabel(): Double? {
            val cleaned = trim()
                .replace("~", "")
                .replace("Rp", "")
                .replace(".", "")
                .replace(",", ".")
                .replace("+", "")
            val numeric = cleaned.toDoubleOrNull() ?: return null
            return if (trim().startsWith("-")) -numeric else numeric
        }
    }
}

private class WidgetSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun write(snapshot: LiveStatusSnapshot) {
        prefs.edit()
            .putLong(KEY_UPDATED_AT, snapshot.updatedAtEpochMs)
            .putString(KEY_ACTIVE_PAIR, snapshot.activePair)
            .putString(KEY_TOTAL_EQUITY, snapshot.totalEquityIdr)
            .putString(KEY_PNL_TODAY, snapshot.pnlTodayIdr)
            .putLong(KEY_INTERNET_PING_MS, snapshot.internetPingMs ?: -1L)
            .putString(KEY_RADAR_PAIRS, snapshot.radarPairs.joinToString("\u001F"))
            .putString(KEY_HOLDINGS, snapshot.holdings.joinToString("\u001F") {
                listOf(it.asset, it.amount, it.valueIdr, it.pnlIdr, it.pnlPctLabel).joinToString("\u001E")
            })
            .apply()
    }

    fun read(): LiveStatusSnapshot {
        val holdings = prefs.getString(KEY_HOLDINGS, null)
            ?.takeIf { it.isNotBlank() }
            ?.split("\u001F")
            ?.mapNotNull { chunk ->
                val parts = chunk.split("\u001E")
                if (parts.size < 3) return@mapNotNull null
                com.kibot.android.runtime.LiveHoldingUi(
                    asset = parts[0],
                    amount = parts[1],
                    valueIdr = parts[2],
                    pnlIdr = parts.getOrElse(3) { "" },
                    pnlPctLabel = parts.getOrElse(4) { "" },
                )
            }
            .orEmpty()
        return LiveStatusSnapshot(
            updatedAtEpochMs = prefs.getLong(KEY_UPDATED_AT, 0L),
            activePair = prefs.getString(KEY_ACTIVE_PAIR, null).orEmpty().ifBlank { "-" },
            totalEquityIdr = prefs.getString(KEY_TOTAL_EQUITY, null).orEmpty().ifBlank { "Rp0" },
            pnlTodayIdr = prefs.getString(KEY_PNL_TODAY, null).orEmpty().ifBlank { "+Rp0" },
            internetPingMs = prefs.getLong(KEY_INTERNET_PING_MS, -1L).takeIf { it >= 0L },
            radarPairs = prefs.getString(KEY_RADAR_PAIRS, null)
                ?.split("\u001F")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            holdings = holdings,
        )
    }

    companion object {
        private const val PREFS_NAME = "kibot_widget"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ACTIVE_PAIR = "active_pair"
        private const val KEY_TOTAL_EQUITY = "total_equity"
        private const val KEY_PNL_TODAY = "pnl_today"
        private const val KEY_INTERNET_PING_MS = "internet_ping_ms"
        private const val KEY_RADAR_PAIRS = "radar_pairs"
        private const val KEY_HOLDINGS = "holdings"
    }
}
