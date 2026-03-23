package com.kibot.android.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            HeartbeatWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            ReconnectWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ReconnectWorker>(15, TimeUnit.MINUTES).build(),
        )

    }
}
