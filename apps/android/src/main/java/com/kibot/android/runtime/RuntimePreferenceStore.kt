package com.kibot.android.runtime

import android.content.Context

class RuntimePreferenceStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setDesiredOn(value: Boolean) {
        preferences.edit().putBoolean(KEY_DESIRED_ON, value).apply()
    }

    fun isDesiredOn(): Boolean = preferences.getBoolean(KEY_DESIRED_ON, false)

    fun getOrRememberDailyOpeningEquity(dateKey: String, currentEquityIdr: Double): Double {
        val storedDate = preferences.getString(KEY_DAILY_BASELINE_DATE, null)
        val storedEquity = preferences.getFloat(KEY_DAILY_BASELINE_EQUITY, Float.NaN)
        if (storedDate == dateKey && !storedEquity.isNaN()) {
            return storedEquity.toDouble()
        }

        preferences.edit()
            .putString(KEY_DAILY_BASELINE_DATE, dateKey)
            .putFloat(KEY_DAILY_BASELINE_EQUITY, currentEquityIdr.toFloat())
            .apply()
        return currentEquityIdr
    }

    companion object {
        private const val PREFS_NAME = "kibot_runtime"
        private const val KEY_DESIRED_ON = "desired_on"
        private const val KEY_DAILY_BASELINE_DATE = "daily_baseline_date"
        private const val KEY_DAILY_BASELINE_EQUITY = "daily_baseline_equity"
    }
}
