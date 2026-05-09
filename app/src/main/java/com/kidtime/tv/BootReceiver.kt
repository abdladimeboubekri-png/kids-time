package com.kidtime.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("KidTimeBoot", "BootReceiver fired: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            // CRITICAL: clear any in-flight session.
            // This forces any kid who was watching before reboot to enter
            // their PIN again. Without this, a kid could reboot the TV and
            // potentially exploit the session-based time tracker to gain
            // "free" time.
            //
            // Their kid.usedSec is preserved, so they can't reset their
            // daily quota by rebooting.
            val storage = Storage(context)
            storage.handleBootOrServiceRestart()

            // Restart the monitoring service
            MonitorService.start(context)
        }
    }
}
