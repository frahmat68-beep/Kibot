package com.kibot.android.widget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WidgetSyncScheduler {
    private const val PERIODIC_NAME = "kibot_widget_sync_periodic"
    private const val IMMEDIATE_NAME = "kibot_widget_sync_immediate"
    private const val PREFS_NAME = "kibot_widget_scheduler"
    private const val KEY_LAST_IMMEDIATE_ENQUEUE_AT = "last_immediate_enqueue_at"
    private const val REPEAT_REQUEST_COOLDOWN_MS = 60_000L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<KiBotWidgetSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleImmediate(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastImmediateEnqueueAt = prefs.getLong(KEY_LAST_IMMEDIATE_ENQUEUE_AT, 0L)
        if (now - lastImmediateEnqueueAt < REPEAT_REQUEST_COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST_IMMEDIATE_ENQUEUE_AT, now).apply()
        val request = OneTimeWorkRequestBuilder<KiBotWidgetSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
