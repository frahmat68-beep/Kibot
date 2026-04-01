package com.kibot.commandcenter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kibot.commandcenter.service.CommandCenterForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CommandCenterForegroundService.start(context)
    }
}
