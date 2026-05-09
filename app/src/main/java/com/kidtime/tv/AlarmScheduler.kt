package com.kidtime.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules an exact wakeup alarm to fire when a kid's time should expire.
 *
 * Why this matters: Android suspends our 1-second-tick foreground service
 * when YouTube goes fullscreen. AlarmManager.setExactAndAllowWhileIdle()
 * is the ONE mechanism that fires reliably even during Doze - it's what
 * alarm-clock apps use. So we use it to guarantee we wake up at the right
 * moment to enforce the time limit.
 */
object AlarmScheduler {
    private const val TAG = "KidTimeAlarm"
    private const val ALARM_REQUEST_CODE = 9001

    fun scheduleTimeUp(context: Context, secondsFromNow: Long) {
        if (secondsFromNow <= 0) {
            Log.d(TAG, "scheduleTimeUp: $secondsFromNow s — firing immediately")
            // Fire immediately by scheduling 1ms in future
            scheduleAt(context, System.currentTimeMillis() + 1)
            return
        }
        val triggerAt = System.currentTimeMillis() + secondsFromNow * 1000L
        Log.d(TAG, "Scheduling alarm in ${secondsFromNow}s (at $triggerAt)")
        scheduleAt(context, triggerAt)
    }

    private fun scheduleAt(context: Context, triggerAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TimeUpReceiver::class.java).apply {
            action = TimeUpReceiver.ACTION_TIME_UP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)

        // Cancel any existing alarm first
        am.cancel(pi)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: must check permission
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                    Log.d(TAG, "setExactAndAllowWhileIdle scheduled (Android 12+)")
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                    Log.w(TAG, "Exact alarms not allowed — using inexact (Android 12+)")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6-11
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                Log.d(TAG, "setExactAndAllowWhileIdle scheduled")
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                Log.d(TAG, "setExact scheduled (pre-M)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm", e)
            // Fallback to inexact
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } catch (e2: Exception) {
                Log.e(TAG, "Inexact fallback also failed", e2)
            }
        }
    }

    fun cancel(context: Context) {
        Log.d(TAG, "Cancelling time-up alarm")
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TimeUpReceiver::class.java).apply {
            action = TimeUpReceiver.ACTION_TIME_UP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
        am.cancel(pi)
    }
}
