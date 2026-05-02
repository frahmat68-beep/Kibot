package com.kibot.android.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.kibot.android.data.BotState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object KiBotWidgetHelper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS_NAME = "kibot_widget_prefs"
    private const val MIN_UPDATE_INTERVAL_MS = 3_000L
    @Volatile
    private var lastUpdateSignature: String? = null
    @Volatile
    private var lastUpdateAtMs: Long = 0L
    
    /**
     * Update widget data when bot state changes
     * Uses SharedPreferences for reliable cross-process data sharing
     */
    fun updateWidgetData(context: Context, botState: BotState) {
        val signature = buildString {
            append(botState.balance.toLong())
            append('|')
            append(botState.pnlToday.toLong())
            append('|')
            append(botState.totalReturn.toInt())
            append('|')
            append(botState.heartbeat.KiBot.status)
            append('|')
            append(botState.heartbeat.KiBot.status)
            append('|')
            append(botState.heartbeat.kibot.status)
            append('|')
            append(botState.heartbeat.KiBot.ping)
        }
        val now = System.currentTimeMillis()
        if (signature == lastUpdateSignature && now - lastUpdateAtMs < MIN_UPDATE_INTERVAL_MS) {
            return
        }
        lastUpdateSignature = signature
        lastUpdateAtMs = now

        android.util.Log.i("KiBotWidget", "📊 updateWidgetData called - balance=${botState.balance}, pnl=${botState.pnlToday}, connected=${botState.isConnected}")
        
        try {
            // Save to SharedPreferences (reliable and fast)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putFloat("balance", botState.balance.toFloat())
                putFloat("pnl_today", botState.pnlToday.toFloat())
                putFloat("total_return", botState.totalReturn.toFloat())
                putString("KiBot_status", botState.heartbeat.KiBot.status)
                putString("KiBot_status", botState.heartbeat.KiBot.status)
                putString("kibot_status", botState.heartbeat.kibot.status)
                putLong("KiBot_ping", botState.heartbeat.KiBot.ping)
                putLong("last_update", System.currentTimeMillis())
                apply() // async commit
                
                android.util.Log.i("KiBotWidget", "📊 Saved to SharedPreferences: balance=${botState.balance}, pnl=${botState.pnlToday}")
            }
            
            // Trigger widget update
            scope.launch {
                try {
                    KiBotWidget().updateAll(context)
                    android.util.Log.i("KiBotWidget", "📊 Widget updateAll() triggered")
                } catch (e: Exception) {
                    android.util.Log.e("KiBotWidget", "❌ Error triggering widget update", e)
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.e("KiBotWidget", "❌ Error updating widget data", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Read widget data from SharedPreferences
     */
    fun getWidgetData(context: Context): WidgetData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WidgetData(
            balance = prefs.getFloat("balance", 0f).toDouble(),
            pnlToday = prefs.getFloat("pnl_today", 0f).toDouble(),
            totalReturn = prefs.getFloat("total_return", 0f).toDouble(),
            KiBotStatus = prefs.getString("KiBot_status", "offline") ?: "offline",
            KiBotStatus = prefs.getString("KiBot_status", "offline") ?: "offline",
            kibotStatus = prefs.getString("kibot_status", "offline") ?: "offline",
            KiBotPing = prefs.getLong("KiBot_ping", 0L),
            lastUpdate = prefs.getLong("last_update", 0L)
        )
    }
}

data class WidgetData(
    val balance: Double,
    val pnlToday: Double,
    val totalReturn: Double,
    val KiBotStatus: String,
    val KiBotStatus: String,
    val kibotStatus: String,
    val KiBotPing: Long,
    val lastUpdate: Long
)
