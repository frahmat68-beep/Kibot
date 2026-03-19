package com.kibot.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kibot.android.R
import com.kibot.android.widget.KiBotWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class BotForegroundService : Service() {
    private val loggerTag = "KiBotService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(currentPair = null, isRunning = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE) {
            serviceScope.launch {
                handleToggle()
            }
            return START_STICKY
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
            "KiBot",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(currentPair: String?, isRunning: Boolean): Notification {
        val snapshot = currentSnapshot()
        val pairLabel = currentPair?.takeIf { it.isNotBlank() }
            ?: snapshot.activePair.takeIf { it.isNotBlank() && it != "-" }
            ?: "scan"
        val stateLabel = if (isRunning) "ON" else "OFF"
        val balanceLine = "${snapshot.totalEquityIdr} • ${snapshot.pnlTodayIdr}"
        val detailLine = buildNotificationDetail(snapshot, stateLabel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KiBot • ${pairLabel.lowercase()}")
            .setContentText(balanceLine)
            .setSubText(detailLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$balanceLine\n$detailLine"))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
            .addAction(toggleAction(if (isRunning) "Turn OFF" else "Turn ON"))
            .build()
    }

    private suspend fun runLoop() {
        val app = application as? com.kibot.android.KiBotApplication ?: return
        val daemon = app.container.androidDaemon
        if (daemon == null) {
            Log.e(loggerTag, "Android daemon tidak tersedia karena config control-plane belum lengkap.")
            updateNotification(currentPair = null, isRunning = false)
            return
        }

        while (serviceScope.isActive) {
            try {
                if (!app.container.repository.isDesiredOn()) {
                    Log.i(loggerTag, "desired_on=false, foreground service dihentikan.")
                    updateNotification(currentPair = null, isRunning = false)
                    stopSelf()
                    break
                }

                val tick = daemon.syncOnce()
                publishLiveStatus(app, tick.liveStatusSnapshot)
                val pairLabel = tick.currentPair?.takeIf { it.isNotBlank() }?.lowercase()
                val isRunning = app.container.repository.isDesiredOn()
                Log.i(loggerTag, "tick ok: ${pairLabel ?: "menunggu pair"} • ${if (isRunning) "ON" else "OFF"}")
                updateNotification(currentPair = pairLabel, isRunning = isRunning)
            } catch (error: Throwable) {
                Log.e(loggerTag, "Loop engine Android gagal pada satu iterasi.", error)
                updateNotification(currentPair = null, isRunning = false)
            }

            delay(app.container.runtimeConfig.pollIntervalMillis)
        }
    }

    private fun updateNotification(currentPair: String?, isRunning: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(currentPair, isRunning))
    }

    private fun currentSnapshot(): LiveStatusSnapshot {
        val app = application as? com.kibot.android.KiBotApplication
        return app?.container?.liveStatusStore?.current() ?: LiveStatusSnapshot.Empty
    }

    private fun toggleAction(label: String): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BotForegroundService::class.java).apply {
                action = ACTION_TOGGLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.stat_sys_upload_done,
            label,
            pendingIntent,
        ).build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, com.kibot.android.MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun handleToggle() {
        val app = application as? com.kibot.android.KiBotApplication ?: return
        val running = app.container.repository.toggleBot()
        if (running) {
            updateNotification(currentPair = app.container.liveStatusStore.current().activePair, isRunning = true)
        } else {
            updateNotification(currentPair = null, isRunning = false)
            stopSelf()
        }
    }

    private fun publishLiveStatus(
        app: com.kibot.android.KiBotApplication,
        snapshot: LiveStatusSnapshot?,
    ) {
        if (snapshot == null) return
        app.container.liveStatusStore.update(snapshot)
        KiBotWidgetProvider.updateAll(this, snapshot)
    }

    private fun buildNotificationDetail(snapshot: LiveStatusSnapshot, stateLabel: String): String {
        val updated = formatTime(snapshot.updatedAtEpochMs)
        val holdings = snapshot.holdings.take(2)
            .joinToString(" • ") { holding -> holding.asset.uppercase() }
            .ifBlank { "tidak ada aset aktif" }
        return "$stateLabel • $updated • $holdings"
    }

    private fun formatTime(epochMs: Long): String {
        if (epochMs <= 0L) return "--:--"
        val local = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.of("Asia/Jakarta"))
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        return "$hh:$mm"
    }

    companion object {
        private const val CHANNEL_ID = "kibot-engine"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.kibot.android.runtime.START"
        private const val ACTION_STOP = "com.kibot.android.runtime.STOP"
        private const val ACTION_TOGGLE = "com.kibot.android.runtime.TOGGLE"

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
