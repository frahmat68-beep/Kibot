package com.kibot.android.util

import android.content.Context
import android.content.SharedPreferences
import com.kibot.android.data.ServerConfig

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "kibot_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_BALANCE = "last_balance"
        private const val KEY_LAST_PNL = "last_pnl"
        
        private const val DEFAULT_HOST = "213.35.118.26"
        private const val DEFAULT_PORT = 8787
    }

    fun getServerConfig(): ServerConfig {
        val host = prefs.getString(KEY_SERVER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT)
        return ServerConfig(host, port)
    }

    fun saveServerConfig(config: ServerConfig) {
        prefs.edit().apply {
            putString(KEY_SERVER_HOST, config.host)
            putInt(KEY_SERVER_PORT, config.port)
            apply()
        }
    }

    fun getLastKnownStatus(): String? {
        return prefs.getString(KEY_LAST_STATUS, null)
    }

    fun saveLastKnownStatus(status: String) {
        prefs.edit().putString(KEY_LAST_STATUS, status).apply()
    }
    
    fun getLastBalance(): Double {
        return prefs.getFloat(KEY_LAST_BALANCE, 0f).toDouble()
    }
    
    fun saveLastBalance(balance: Double) {
        prefs.edit().putFloat(KEY_LAST_BALANCE, balance.toFloat()).apply()
    }
    
    fun getLastPnL(): Double {
        return prefs.getFloat(KEY_LAST_PNL, 0f).toDouble()
    }
    
    fun saveLastPnL(pnl: Double) {
        prefs.edit().putFloat(KEY_LAST_PNL, pnl.toFloat()).apply()
    }
}
