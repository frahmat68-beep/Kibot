package com.kibot.commandcenter.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kibot.shared.models.CommandCenterLiveSnapshot
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.commandCenterSnapshotCacheStore by preferencesDataStore(name = "command_center_snapshot_cache")

class CommandCenterSnapshotCacheStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun key(serverKey: String) = stringPreferencesKey("snapshot_$serverKey")

    suspend fun save(serverKey: String, snapshot: CommandCenterLiveSnapshot) {
        context.commandCenterSnapshotCacheStore.edit { prefs ->
            prefs[key(serverKey)] = json.encodeToString(snapshot)
        }
    }

    suspend fun load(serverKey: String): CommandCenterLiveSnapshot? {
        val prefs = context.commandCenterSnapshotCacheStore.data.firstOrNull() ?: return null
        return prefs[key(serverKey)]?.let { runCatching { json.decodeFromString<CommandCenterLiveSnapshot>(it) }.getOrNull() }
    }
}
