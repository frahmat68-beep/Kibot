package com.kibot.android

import android.app.Application
import com.kibot.android.widget.WidgetSyncScheduler

class KiBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WidgetSyncScheduler.schedule(this)
        WidgetSyncScheduler.scheduleImmediate(this)
    }
}
