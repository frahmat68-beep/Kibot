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
                    setTextViewText(R.id.widget_title, "KiBot Live")
                    setTextViewText(R.id.widget_pair, snapshot.activePair.lowercase())
                    setTextViewText(R.id.widget_count, "${snapshot.holdings.size} aset")
                    setTextViewText(R.id.widget_equity, snapshot.totalEquityIdr)
                    setTextViewText(R.id.widget_pnl, snapshot.pnlTodayIdr)
                    setTextViewText(R.id.widget_holdings, formatHoldings(snapshot))
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

        private fun formatHoldings(snapshot: LiveStatusSnapshot): String {
            if (snapshot.holdings.isEmpty()) return "Tidak ada holdings yang aktif."
            val lines = snapshot.holdings.take(3).map { holding ->
                "${holding.asset.uppercase()} ${holding.amount} • ${holding.valueIdr}"
            }
            return lines.joinToString("\n")
        }
    }
}

private class WidgetSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun write(snapshot: LiveStatusSnapshot) {
        prefs.edit()
            .putString(KEY_ACTIVE_PAIR, snapshot.activePair)
            .putString(KEY_TOTAL_EQUITY, snapshot.totalEquityIdr)
            .putString(KEY_PNL_TODAY, snapshot.pnlTodayIdr)
            .putString(KEY_HOLDINGS, snapshot.holdings.joinToString("\u001F") {
                listOf(it.asset, it.amount, it.valueIdr).joinToString("\u001E")
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
                )
            }
            .orEmpty()
        return LiveStatusSnapshot(
            updatedAtEpochMs = 0L,
            activePair = prefs.getString(KEY_ACTIVE_PAIR, null).orEmpty().ifBlank { "-" },
            totalEquityIdr = prefs.getString(KEY_TOTAL_EQUITY, null).orEmpty().ifBlank { "Rp0" },
            pnlTodayIdr = prefs.getString(KEY_PNL_TODAY, null).orEmpty().ifBlank { "+Rp0" },
            holdings = holdings,
        )
    }

    companion object {
        private const val PREFS_NAME = "kibot_widget"
        private const val KEY_ACTIVE_PAIR = "active_pair"
        private const val KEY_TOTAL_EQUITY = "total_equity"
        private const val KEY_PNL_TODAY = "pnl_today"
        private const val KEY_HOLDINGS = "holdings"
    }
}
