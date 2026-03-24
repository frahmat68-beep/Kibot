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
import com.kibot.shared.models.BotEffectiveState

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
        val balanceLine = "${snapshot.totalEquityIdr} • ${snapshot.pnlTodayIdr} ${snapshot.derivedPnlPctLabel()}".trim()
        val detailLine = buildNotificationDetail(snapshot, isRunning)
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
            .build()
    }

    private suspend fun runLoop() {
        val app = application as? com.kibot.android.KiBotApplication ?: return
        while (serviceScope.isActive) {
            try {
                app.container.repository.syncNow()
                val state = app.container.repository.uiState.value
                val pairLabel = state.pairAktif.takeIf { it.isNotBlank() && it != "-" }?.lowercase()
                val isRunning = state.effectiveState != BotEffectiveState.STOPPED
                Log.i(loggerTag, "server feed ok: ${pairLabel ?: "menunggu pair"} • ${state.effectiveState.name}")
                updateNotification(currentPair = pairLabel, isRunning = isRunning)
            } catch (error: Throwable) {
                Log.e(loggerTag, "Loop monitor Android gagal pada satu iterasi.", error)
                updateNotification(currentPair = null, isRunning = false)
            }

            delay(10_000L)
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

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, com.kibot.android.MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotificationDetail(snapshot: LiveStatusSnapshot, isRunning: Boolean): String {
        val liveEvent = snapshot.liveLogEntries.firstOrNull()
        if (liveEvent != null) {
            return "${liveEvent.category} • ${formatTime(liveEvent.timestampEpochMs)} • ${liveEvent.message}".take(120)
        }
        val stateLabel = if (isRunning) "ON" else "OFF"
        val updated = formatTime(snapshot.updatedAtEpochMs)
        val fallback = snapshot.statusMessage.ifBlank {
            snapshot.holdings.take(2)
                .joinToString(" • ") { holding -> holding.asset.uppercase() }
                .ifBlank { "tidak ada aset aktif" }
        }
        return "$stateLabel • $updated • $fallback".take(120)
    }

    private fun LiveStatusSnapshot.derivedPnlPctLabel(): String {
        val equity = totalEquityIdr.parseRupiahLabel() ?: return "+0.0%"
        val pnl = pnlTodayIdr.parseRupiahLabel() ?: return "+0.0%"
        val opening = (equity - pnl).takeIf { it > 0.0 } ?: return "+0.0%"
        val pct = kotlin.math.abs((pnl / opening) * 100.0)
        val prefix = if (pnlTodayIdr.trim().startsWith("-") || pnl < 0.0) "-" else "+"
        return "$prefix${"%.1f".format(kotlin.math.abs(pct))}%"
    }

    private fun String.parseRupiahLabel(): Double? {
        val cleaned = trim()
            .replace("~", "")
            .replace("Rp", "")
            .replace(".", "")
            .replace(",", ".")
            .replace("+", "")
        val numeric = cleaned.toDoubleOrNull() ?: return null
        return if (trim().startsWith("-")) -numeric else numeric
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
