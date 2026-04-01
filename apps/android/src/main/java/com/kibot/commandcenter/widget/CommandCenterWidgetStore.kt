package com.kibot.commandcenter.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.datetime.Clock

class CommandCenterWidgetStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): CommandCenterWidgetState? {
        val title = prefs.getString(KEY_TITLE, null) ?: return null
        return CommandCenterWidgetState(
            title = title,
            totalLabel = prefs.getString(KEY_TOTAL, "Rp0") ?: "Rp0",
            pnlLabel = prefs.getString(KEY_PNL, "+Rp0") ?: "+Rp0",
            pnlPctLabel = prefs.getString(KEY_PNL_PCT, "+0.0%") ?: "+0.0%",
            pingLabel = prefs.getString(KEY_PING, "KiDax -- ms") ?: "KiDax -- ms",
            accentRole = runCatching { CommandCenterWidgetState.AccentRole.valueOf(prefs.getString(KEY_ACCENT, CommandCenterWidgetState.AccentRole.NEUTRAL.name) ?: CommandCenterWidgetState.AccentRole.NEUTRAL.name) }.getOrDefault(CommandCenterWidgetState.AccentRole.NEUTRAL),
            updatedAtEpochMs = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    fun save(state: CommandCenterWidgetState) {
        prefs.edit {
            putString(KEY_TITLE, state.title)
            putString(KEY_TOTAL, state.totalLabel)
            putString(KEY_PNL, state.pnlLabel)
            putString(KEY_PNL_PCT, state.pnlPctLabel)
            putString(KEY_PING, state.pingLabel)
            putString(KEY_ACCENT, state.accentRole.name)
            putLong(KEY_UPDATED_AT, state.updatedAtEpochMs.ifZero(Clock.System.now().toEpochMilliseconds()))
        }
    }

    private fun Long.ifZero(fallback: Long): Long = if (this == 0L) fallback else this

    companion object {
        private const val PREFS = "command_center_widget"
        private const val KEY_TITLE = "title"
        private const val KEY_TOTAL = "total"
        private const val KEY_PNL = "pnl"
        private const val KEY_PNL_PCT = "pnl_pct"
        private const val KEY_PING = "ping"
        private const val KEY_ACCENT = "accent"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}
