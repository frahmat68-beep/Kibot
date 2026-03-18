package com.kibot.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kibot.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BotForegroundService : Service() {
    private val loggerTag = "KiBotService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Engine Android sedang booting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KiBot Engine",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KiBot Engine")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private suspend fun runLoop() {
        val app = application as? com.kibot.android.KiBotApplication ?: return
        val daemon = app.container.androidDaemon
        if (daemon == null) {
            Log.e(loggerTag, "Android daemon tidak tersedia karena config control-plane belum lengkap.")
            updateNotification("Config Android belum lengkap untuk control-plane")
            return
        }

        while (serviceScope.isActive) {
            try {
                if (!app.container.repository.isDesiredOn()) {
                    Log.i(loggerTag, "desired_on=false, foreground service dihentikan.")
                    updateNotification("Bot OFF, service berhenti")
                    stopSelf()
                    break
                }

                val tick = daemon.syncOnce()
                runCatching {
                    app.container.repository.syncNow()
                }.onFailure { syncError ->
                    Log.e(loggerTag, "syncNow gagal setelah daemon tick.", syncError)
                }
                val message = buildString {
                    append(tick.operatingMode)
                    tick.currentPair?.takeIf { it.isNotBlank() }?.let {
                        append(" • ")
                        append(it)
                    }
                    append(" • ")
                    append(tick.statusMessage.take(96))
                }
                Log.i(loggerTag, "tick ok: $message")
                updateNotification(message)
            } catch (error: Throwable) {
                Log.e(loggerTag, "Loop engine Android gagal pada satu iterasi.", error)
                updateNotification("Engine error: ${error.message ?: "unknown"}")
            }

            delay(app.container.runtimeConfig.pollIntervalMillis)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        private const val CHANNEL_ID = "kibot-engine"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.kibot.android.runtime.START"
        private const val ACTION_STOP = "com.kibot.android.runtime.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java)
            intent.action = ACTION_START
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }
}
