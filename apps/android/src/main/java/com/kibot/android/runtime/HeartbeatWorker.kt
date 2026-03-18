package com.kibot.android.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? com.kibot.android.KiBotApplication ?: return Result.retry()
        application.container.repository.syncNow()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "kibot-heartbeat"
    }
}
