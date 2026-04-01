package com.kibot.commandcenter

import android.app.Application
import com.kibot.commandcenter.data.remote.CommandCenterWebSocketManager
import com.kibot.commandcenter.data.repository.CommandCenterRepository
import com.kibot.commandcenter.data.repository.CommandCenterStore
import com.kibot.commandcenter.data.repository.CommandCenterSnapshotCacheStore
import com.kibot.commandcenter.service.CommandCenterForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CommandCenterApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store by lazy { CommandCenterStore(this) }
    val snapshotCacheStore by lazy { CommandCenterSnapshotCacheStore(this) }
    val websocketManager by lazy { CommandCenterWebSocketManager(appScope, store, snapshotCacheStore) }
    val repository by lazy { CommandCenterRepository(store, websocketManager) }

    override fun onCreate() {
        super.onCreate()
        CommandCenterForegroundService.ensureChannels(this)
    }
}
