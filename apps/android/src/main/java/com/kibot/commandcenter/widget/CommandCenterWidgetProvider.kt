package com.kibot.commandcenter.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.kibot.commandcenter.R
import com.kibot.commandcenter.data.model.CommandCenterUiState

class CommandCenterWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = CommandCenterWidgetStore(context).load() ?: CommandCenterWidgetState()
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it, state) }
    }

    companion object {
        fun push(context: Context, state: CommandCenterUiState) {
            val widgetState = state.toWidgetState()
            CommandCenterWidgetStore(context).save(widgetState)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CommandCenterWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it, widgetState) }
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CommandCenterWidgetProvider::class.java))
            val state = CommandCenterWidgetStore(context).load() ?: CommandCenterWidgetState()
            ids.forEach { updateWidget(context, manager, it, state) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            state: CommandCenterWidgetState,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_command_center)
            views.setTextViewText(R.id.widget_title, state.title)
            views.setTextViewText(R.id.widget_total, state.totalLabel)
            views.setTextViewText(R.id.widget_pnl, "${state.pnlLabel} • ${state.pnlPctLabel}")
            views.setTextViewText(R.id.widget_ping, state.pingLabel)
            val accent = when (state.accentRole) {
                CommandCenterWidgetState.AccentRole.POSITIVE -> 0xFF5CFFBA.toInt()
                CommandCenterWidgetState.AccentRole.NEGATIVE -> 0xFFFF6B6B.toInt()
                CommandCenterWidgetState.AccentRole.WARN -> 0xFFFFD166.toInt()
                CommandCenterWidgetState.AccentRole.NEUTRAL -> 0xFFB7C3DD.toInt()
            }
            views.setTextColor(R.id.widget_title, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_total, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_pnl, accent)
            views.setTextColor(R.id.widget_ping, accent)
            manager.updateAppWidget(widgetId, views)
        }

        fun renderState(uiState: CommandCenterUiState): CommandCenterWidgetState {
            val snapshot = uiState.kidax.snapshot
            val pnl = snapshot?.pnlTodayIdr ?: uiState.pnlTodayLabel
            val accentRole = when {
                pnl.startsWith("-") -> CommandCenterWidgetState.AccentRole.NEGATIVE
                uiState.systemHealthLabel.contains("HEALTHY", ignoreCase = true) -> CommandCenterWidgetState.AccentRole.POSITIVE
                uiState.systemHealthLabel.contains("BOOT", ignoreCase = true) -> CommandCenterWidgetState.AccentRole.WARN
                else -> CommandCenterWidgetState.AccentRole.NEUTRAL
            }
            return CommandCenterWidgetState(
                title = "KiBot",
                totalLabel = uiState.totalEquityLabel,
                pnlLabel = uiState.pnlTodayLabel,
                pnlPctLabel = uiState.pnlTodayPctLabel,
                pingLabel = if (uiState.latencyLabel.isBlank()) "KiDax -- ms" else uiState.latencyLabel,
                accentRole = accentRole,
                updatedAtEpochMs = snapshot?.updatedAtEpochMs ?: 0L,
            )
        }

        private fun CommandCenterUiState.toWidgetState(): CommandCenterWidgetState = renderState(this)
    }
}
