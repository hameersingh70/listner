package com.recall.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.recall.app.service.CallRecorderService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // Re-start the foreground monitor service on device boot
            CallRecorderService.startMonitor(context)
        }
    }
}
