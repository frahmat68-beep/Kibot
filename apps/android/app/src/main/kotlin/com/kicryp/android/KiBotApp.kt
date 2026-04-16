package com.kicryp.android

import android.app.Application
import com.kicryp.android.widget.WidgetSyncScheduler

class KiCrypApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WidgetSyncScheduler.schedule(this)
        WidgetSyncScheduler.scheduleImmediate(this)
    }
}
