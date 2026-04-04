package com.kibot.android.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.kibot.android.data.BotState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private val Context.preferencesDataStore by preferencesDataStore(name = "kibot_widget_data")

object KiBotWidgetHelper {
    
    /**
     * Update widget data when bot state changes
     * This synchronizes real-time data from the app to the widget
     */
    fun updateWidgetData(context: Context, botState: BotState) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Update app widget state with new data
                val glanceAppWidgetManager = GlanceAppWidgetManager(context)
                val glanceIds = glanceAppWidgetManager.getGlanceIds(KiBotWidget::class.java)
                
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[KiBotWidgetKeys.BALANCE] = botState.balance
                            this[KiBotWidgetKeys.PNL_TODAY] = botState.pnlToday
                            this[KiBotWidgetKeys.TOTAL_RETURN] = botState.totalReturn
                            this[KiBotWidgetKeys.KIDAX_STATUS] = botState.heartbeat.kidax.status
                            this[KiBotWidgetKeys.KINANCE_STATUS] = botState.heartbeat.kinance.status
                            this[KiBotWidgetKeys.KIBOT_STATUS] = botState.heartbeat.kibot.status
                            this[KiBotWidgetKeys.KIDAX_PING] = botState.heartbeat.kidax.ping
                            this[KiBotWidgetKeys.LAST_UPDATE] = System.currentTimeMillis()
                        }
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
