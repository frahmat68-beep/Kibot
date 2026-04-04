package com.kibot.android.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.state.updateAppWidgetState
import com.kibot.android.data.BotState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val Context.preferencesDataStore by preferencesDataStore(name = "kibot_widget_data")

object KiBotWidgetHelper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * Update widget data when bot state changes
     * This synchronizes real-time data from the app to the widget
     */
    fun updateWidgetData(context: Context, botState: BotState) {
        android.util.Log.i("KiBotWidget", "📊 updateWidgetData called - balance=${botState.balance}, pnl=${botState.pnlToday}, connected=${botState.isConnected}")
        scope.launch {
            try {
                // Update app widget state with new data
                val glanceAppWidgetManager = GlanceAppWidgetManager(context)
                val glanceIds = glanceAppWidgetManager.getGlanceIds(KiBotWidget::class.java)
                android.util.Log.i("KiBotWidget", "📊 Found ${glanceIds.size} widget instances")
                
                val kidaxPrimarySnapshot = botState.connectedBotId.equals("kidax", ignoreCase = true)
                val hasAuthoritativePortfolio =
                    botState.balance > 0.0 ||
                        botState.positions.isNotEmpty() ||
                        botState.assetAllocation.isNotEmpty() ||
                        botState.trades.isNotEmpty()
                val unstableSnapshot =
                    !botState.isConnected ||
                        botState.syncHealth.equals("BROKEN", ignoreCase = true) ||
                        (
                            botState.syncHealth.equals("DEGRADED", ignoreCase = true) &&
                                (
                                    botState.healthSummary.contains("sync", ignoreCase = true) ||
                                        botState.healthSummary.contains("lease", ignoreCase = true) ||
                                        botState.statusMessage.contains("boot", ignoreCase = true)
                                    )
                            )
                val preserveFinancials = !kidaxPrimarySnapshot || (unstableSnapshot && !hasAuthoritativePortfolio)
                
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            val nextBalance = if (preserveFinancials) {
                                this[KiBotWidgetKeys.BALANCE] ?: botState.balance
                            } else {
                                botState.balance
                            }
                            val nextPnlToday = if (preserveFinancials) {
                                this[KiBotWidgetKeys.PNL_TODAY] ?: botState.pnlToday
                            } else {
                                botState.pnlToday
                            }
                            val nextTotalReturn = if (preserveFinancials) {
                                this[KiBotWidgetKeys.TOTAL_RETURN] ?: botState.totalReturn
                            } else {
                                botState.totalReturn
                            }
                            val nextKidaxPing = if (botState.heartbeat.kidax.ping > 0L) {
                                botState.heartbeat.kidax.ping
                            } else {
                                this[KiBotWidgetKeys.KIDAX_PING] ?: 0L
                            }
                            val nextKinancePing = 0L
                            val nextKiBotPing = 0L

                            this[KiBotWidgetKeys.BALANCE] = nextBalance
                            this[KiBotWidgetKeys.PNL_TODAY] = nextPnlToday
                            this[KiBotWidgetKeys.TOTAL_RETURN] = nextTotalReturn
                            this[KiBotWidgetKeys.KIDAX_STATUS] = botState.heartbeat.kidax.status
                            this[KiBotWidgetKeys.KINANCE_STATUS] = botState.heartbeat.kinance.status
                            this[KiBotWidgetKeys.KIBOT_STATUS] = botState.heartbeat.kibot.status
                            this[KiBotWidgetKeys.KIDAX_PING] = nextKidaxPing
                            this[KiBotWidgetKeys.KINANCE_PING] = nextKinancePing
                            this[KiBotWidgetKeys.KIBOT_PING] = nextKiBotPing
                            this[KiBotWidgetKeys.LAST_UPDATE] = botState.lastUpdate.takeIf { it > 0L } ?: System.currentTimeMillis()
                            
                            android.util.Log.i("KiBotWidget", "📊 Updated widget state: balance=$nextBalance, pnl=$nextPnlToday, return=$nextTotalReturn")
                        }
                    }
                }
                KiBotWidget().updateAll(context)
                android.util.Log.i("KiBotWidget", "📊 Widget updateAll() called")
                
            } catch (e: Exception) {
                android.util.Log.e("KiBotWidget", "❌ Error updating widget", e)
                e.printStackTrace()
            }
        }
    }
}
