package com.kidtime.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Wakeup receiver fired by AlarmManager when a kid's time should run out.
 *
 * This is the KEY mechanism for forcing the lock to appear mid-video:
 * Even when YouTube is fullscreen and the OS has suspended our 1-second
 * tick service, AlarmManager.setExactAndAllowWhileIdle() will still fire
 * this receiver because it has wake-permission privileges (same as alarm
 * clock apps).
 */
class TimeUpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "TimeUpReceiver fired!")

        // Acquire a wake lock so the device doesn't sleep before we're done
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "KidTime:TimeUpWake"
        )
        wl.acquire(10_000L)

        try {
            val storage = Storage(context)
            val activeKid = storage.getActiveKid()
            if (activeKid < 0) {
                Log.d(TAG, "No active kid — alarm fired stale, ignoring")
                return
            }

            // Recompute used time using authoritative UsageStatsManager data
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val sessionStart = storage.getSessionStartMs()
            if (sessionStart > 0) {
                val seconds = secondsInBlockedAppsSince(usm, storage, sessionStart, System.currentTimeMillis())
                val newUsed = storage.getSessionStartUsedSec() + seconds
                storage.setUsedSeconds(activeKid, newUsed)
            }

            val kid = storage.getKids().find { it.id == activeKid }
            if (kid == null) {
                Log.d(TAG, "Active kid not found in storage")
                return
            }

            // Find current foreground app
            val fg = getForegroundApp(usm) ?: ""
            val blocked = storage.getBlockedPackages()

            Log.d(TAG, "Kid ${kid.name}: used=${kid.usedSec}/${kid.dailyLimitSec}, fg=$fg, isBlocked=${blocked.contains(fg)}")

            if (kid.usedSec >= kid.dailyLimitSec) {
                Log.d(TAG, "Time IS up — clearing session and showing lock")
                storage.setActiveKid(-1)
                storage.setSessionStartMs(0L)
                storage.setSessionStartUsedSec(0L)
                storage.setLockGraceUntil(0L)

                // Only force lock if currently in a blocked app
                if (blocked.contains(fg)) {
                    showLockNow(context, fg)
                }
            } else {
                // Time NOT yet up — reschedule the alarm for the new estimated time
                val remaining = kid.dailyLimitSec - kid.usedSec
                Log.d(TAG, "Time not up yet, ${remaining}s remaining. Rescheduling.")
                AlarmScheduler.scheduleTimeUp(context, remaining)
            }
        } finally {
            try { wl.release() } catch (e: Exception) {}
        }
    }

    private fun showLockNow(context: Context, blockedPackage: String) {
        Log.d(TAG, "showLockNow: blocked=$blockedPackage")

        // Step 1: send HOME to break out of YouTube's foreground hold
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(home)
            Log.d(TAG, "HOME sent")
        } catch (e: Exception) {
            Log.e(TAG, "HOME failed", e)
        }

        // Step 2: launch lock activity (slight delay so HOME transition starts)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            try {
                val lockIntent = Intent(context, LockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                    putExtra("blocked_package", blockedPackage)
                }
                context.startActivity(lockIntent)
                Log.d(TAG, "Lock launched from receiver")
            } catch (e: Exception) {
                Log.e(TAG, "Lock launch from receiver failed", e)
            }
        }, 400L)
    }

    private fun getForegroundApp(usm: UsageStatsManager): String? {
        val end = System.currentTimeMillis()
        val start = end - 10_000
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    private fun secondsInBlockedAppsSince(
        usm: UsageStatsManager,
        storage: Storage,
        start: Long,
        end: Long
    ): Long {
        if (end <= start) return 0L
        val blocked = storage.getBlockedPackages()
        val events = usm.queryEvents(start - 60_000L, end + 1000L)
        val event = UsageEvents.Event()
        var totalMs = 0L
        var currentBlockedStart: Long = -1L

        // Detect what was foregrounded at session start
        val priorEvents = usm.queryEvents(start - 60_000L, start + 1)
        val priorEvent = UsageEvents.Event()
        var priorPackage: String? = null
        while (priorEvents.hasNextEvent()) {
            priorEvents.getNextEvent(priorEvent)
            if (priorEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                priorEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                priorPackage = priorEvent.packageName
            } else if ((priorEvent.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                        priorEvent.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        priorEvent.eventType == UsageEvents.Event.ACTIVITY_STOPPED) &&
                       priorEvent.packageName == priorPackage) {
                priorPackage = null
            }
        }
        if (priorPackage != null && blocked.contains(priorPackage)) {
            currentBlockedStart = start
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts < start) continue
            if (ts > end) break

            val pkg = event.packageName
            val type = event.eventType

            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                type == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (blocked.contains(pkg)) {
                    if (currentBlockedStart < 0) currentBlockedStart = ts
                } else {
                    if (currentBlockedStart >= 0) {
                        totalMs += (ts - currentBlockedStart)
                        currentBlockedStart = -1
                    }
                }
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                       type == UsageEvents.Event.ACTIVITY_PAUSED ||
                       type == UsageEvents.Event.ACTIVITY_STOPPED) {
                if (blocked.contains(pkg) && currentBlockedStart >= 0) {
                    totalMs += (ts - currentBlockedStart)
                    currentBlockedStart = -1
                }
            }
        }

        if (currentBlockedStart >= 0) {
            totalMs += (end - currentBlockedStart)
        }
        return (totalMs / 1000L).coerceAtLeast(0)
    }

    companion object {
        const val TAG = "KidTimeAlarm"
        const val ACTION_TIME_UP = "com.kidtime.tv.TIME_UP"
    }
}
