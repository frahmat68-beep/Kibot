package com.kibot.android

import android.app.Application
import android.content.Context
import com.kibot.aisupport.GeminiSupportClient
import com.kibot.aisupport.GeminiSupportCoordinator
import com.kibot.android.data.local.AppDatabase
import com.kibot.android.data.local.AppRepository
import com.kibot.android.runtime.AndroidEngineDaemon
import com.kibot.android.runtime.AndroidPassiveExchangeGateway
import com.kibot.android.runtime.AndroidRuntimeConfig
import com.kibot.android.runtime.AndroidRuntimeConfigLoader
import com.kibot.android.runtime.LiveStatusStore
import com.kibot.android.runtime.RuntimePreferenceStore
import com.kibot.android.security.SecureCredentialStore
import com.kibot.controlplane.SupabaseControlPlaneClient
import com.kibot.indodax.IndodaxGateway

class KiBotApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            appContext = applicationContext,
            database = AppDatabase.build(applicationContext),
            credentialStore = SecureCredentialStore(applicationContext),
            runtimePreferenceStore = RuntimePreferenceStore(applicationContext),
            liveStatusStore = LiveStatusStore(applicationContext),
            runtimeConfig = AndroidRuntimeConfigLoader.load(),
        )
    }
}

data class AppContainer(
    val appContext: Context,
    val database: AppDatabase,
    val credentialStore: SecureCredentialStore,
    val runtimePreferenceStore: RuntimePreferenceStore,
    val liveStatusStore: LiveStatusStore,
    val runtimeConfig: AndroidRuntimeConfig,
) {
    val controlPlaneGateway by lazy {
        runtimeConfig.controlPlane?.let(::SupabaseControlPlaneClient)
    }

    val exchangeGateway by lazy {
        runtimeConfig.indodaxCredentials?.let {
            IndodaxGateway(runtimeConfig.indodaxClientConfig, it)
        } ?: AndroidPassiveExchangeGateway()
    }

    val aiSupportCoordinator by lazy {
        runtimeConfig.aiSupportConfig?.let { GeminiSupportCoordinator(it, GeminiSupportClient(it)) }
    }

    val repository: AppRepository by lazy {
        AppRepository(
            appContext = appContext,
            database = database,
            credentialStore = credentialStore,
            runtimePreferenceStore = runtimePreferenceStore,
            liveStatusStore = liveStatusStore,
            exchangeGateway = exchangeGateway,
            controlPlaneGateway = controlPlaneGateway,
            deviceRegistration = runtimeConfig.device,
            botId = runtimeConfig.controlPlane?.botId ?: com.kibot.shared.models.BotId("main"),
            macLanSyncBaseUrl = runtimeConfig.macLanSyncBaseUrl,
            serverMonitorBaseUrl = runtimeConfig.serverMonitorBaseUrl,
            kinanceMonitorBaseUrl = runtimeConfig.kinanceMonitorBaseUrl,
        )
    }

    val androidDaemon by lazy {
        controlPlaneGateway?.let { controlPlane ->
            AndroidEngineDaemon(
                context = appContext,
                controlPlane = controlPlane,
                exchange = exchangeGateway,
                config = runtimeConfig,
                aiSupportCoordinator = aiSupportCoordinator,
            )
        }
    }
}
