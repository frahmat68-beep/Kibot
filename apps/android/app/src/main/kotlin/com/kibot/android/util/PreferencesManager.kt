package com.kibot.android.util

import android.content.Context
import android.content.SharedPreferences
import com.kibot.android.data.ServerConfig

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "kibot_prefs",
        Context.MODE_PRIVATE
    )

    fun getServerConfig(): ServerConfig {
        val host = prefs.getString("server_host", "localhost") ?: "localhost"
        val port = prefs.getInt("server_port", 8787)
        return ServerConfig(host, port)
    }

    fun saveServerConfig(config: ServerConfig) {
        prefs.edit().apply {
            putString("server_host", config.host)
            putInt("server_port", config.port)
            apply()
        }
    }

    fun getLastKnownStatus(): String? {
        return prefs.getString("last_status", null)
    }

    fun saveLastKnownStatus(status: String) {
        prefs.edit().putString("last_status", status).apply()
    }
}
