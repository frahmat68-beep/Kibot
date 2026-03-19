package com.kibot.android.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MacLanDiscovery(
    context: Context,
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)

    suspend fun discoverBaseUrl(timeoutMs: Long = 1_800L): String? {
        val manager = nsdManager ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var finished = false

                fun complete(result: String?) {
                    if (finished || !continuation.isActive) return
                    finished = true
                    continuation.resume(result)
                }

                lateinit var discoveryListener: NsdManager.DiscoveryListener
                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        runCatching { manager.stopServiceDiscovery(this) }
                        complete(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                        runCatching { manager.stopServiceDiscovery(this) }
                    }

                    override fun onDiscoveryStarted(serviceType: String?) = Unit

                    override fun onDiscoveryStopped(serviceType: String?) = Unit

                    override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!serviceInfo.serviceType.contains("_kibot._tcp")) return
                        if (!serviceInfo.serviceName.contains("KiBot", ignoreCase = true)) return

                        @Suppress("DEPRECATION")
                        manager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

                                override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                                    val host = resolvedServiceInfo.host?.hostAddress ?: return
                                    runCatching { manager.stopServiceDiscovery(discoveryListener) }
                                    complete("http://$host:${resolvedServiceInfo.port}")
                                }
                            },
                        )
                    }
                }

                runCatching {
                    manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                }.onFailure {
                    complete(null)
                }

                continuation.invokeOnCancellation {
                    runCatching { manager.stopServiceDiscovery(discoveryListener) }
                }
            }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_kibot._tcp."
    }
}
