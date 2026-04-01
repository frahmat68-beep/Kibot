package com.kibot.commandcenter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kibot.commandcenter.BuildConfig
import com.kibot.commandcenter.CommandCenterApplication
import com.kibot.commandcenter.MainActivity
import com.kibot.commandcenter.R
import com.kibot.commandcenter.widget.CommandCenterWidgetProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CommandCenterForegroundService : LifecycleService() {
    private var observeJob: Job? = null
    private var lastNotificationText: String? = null
    private var lastNotificationUpdateAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Booting command center..."))
        val app = application as CommandCenterApplication
        app.repository.start(
            kidaxWsUrl = BuildConfig.DEFAULT_KIDAX_WS_URL,
        )
        lifecycleScope.launch {
            app.snapshotCacheStore.load("kidax")?.let {
                app.store.updateServer("kidax", "KiDax", it, com.kibot.commandcenter.data.model.ConnectionState.RECONNECTING, "Cached snapshot restored")
            }
        }
        observeJob = lifecycleScope.launch {
            app.repository.uiState.collectLatest { state ->
                val summary = "${state.totalEquityLabel} • ${state.pnlTodayLabel} • ${state.latencyLabel}"
                val nowMs = System.currentTimeMillis()
                if (summary != lastNotificationText && nowMs - lastNotificationUpdateAtMs >= 7_500L) {
                    lastNotificationText = summary
                    lastNotificationUpdateAtMs = nowMs
                    startForeground(NOTIFICATION_ID, buildNotification(summary))
                }
                CommandCenterWidgetProvider.push(this@CommandCenterForegroundService, state)
            }
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kibot)
            .setContentTitle(BuildConfig.COMMAND_CENTER_TITLE)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kibot.command.center"
        private const val NOTIFICATION_ID = 4201

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "KiBot Command Center",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Live trading status and alerts"
                    },
                )
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, CommandCenterForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
