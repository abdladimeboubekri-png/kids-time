package com.kidtime.tv

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    private lateinit var storage: Storage
    private lateinit var usageStatsManager: UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private var lockShowing: Boolean = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        storage = Storage(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        startForeground(NOTIF_ID, buildNotification())
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val fg = getForegroundApp() ?: return
        val isOurApp = (fg == packageName)
        val blocked = storage.getBlockedPackages()
        val isBlocked = blocked.contains(fg)
        val appChanged = (lastForegroundPackage != null && lastForegroundPackage != fg)
        val activeKid = storage.getActiveKid()
        val inGracePeriod = now < storage.getLockGraceUntil()

        if (isOurApp) {
            lockShowing = false
            lastForegroundPackage = fg
            return
        }

        if (isBlocked) {
            // During grace period right after PIN success: just update tracking
            // and don't trigger another lock.
            if (inGracePeriod && activeKid >= 0) {
                updateUsedTimeFromSession(activeKid)
                checkOutOfTime(activeKid, fg)
                lastForegroundPackage = fg
                return
            }

            // Outside grace period: enforce normal PIN-on-switch logic
            if (activeKid < 0 || appChanged) {
                // Before locking, finalize the previous session's time
                if (activeKid >= 0) {
                    updateUsedTimeFromSession(activeKid)
                }
                storage.setActiveKid(-1)
                clearSession()
                showLock(fg)
                lastForegroundPackage = fg
                return
            }

            // Active kid is using a blocked app: update their time
            updateUsedTimeFromSession(activeKid)
            checkOutOfTime(activeKid, fg)
            lastForegroundPackage = fg
            return
        }

        // Foreground is not our app and not a blocked app
        // (home, allowed app, settings, etc.)
        if (!inGracePeriod && activeKid >= 0) {
            // Finalize the kid's time before logging them out
            updateUsedTimeFromSession(activeKid)
            storage.setActiveKid(-1)
            clearSession()
        }
        lastForegroundPackage = fg
    }

    /**
     * THE KEY FIX: instead of accumulating tick deltas (which drift and miss
     * time when the service is throttled), we ask Android's UsageStatsManager
     * for the authoritative count of how many seconds the kid has spent in
     * BLOCKED apps since their session started.
     *
     * Then: usedSec = sessionStartUsedSec + secondsInBlockedAppsSinceSessionStart
     */
    private fun updateUsedTimeFromSession(kidId: Int) {
        val sessionStart = storage.getSessionStartMs()
        if (sessionStart <= 0) {
            // No active session — initialize one starting now
            initSession(kidId)
            return
        }

        val now = System.currentTimeMillis()
        val secondsSinceStart = secondsInBlockedAppsSince(sessionStart, now)
        val newUsedSec = storage.getSessionStartUsedSec() + secondsSinceStart
        storage.setUsedSeconds(kidId, newUsedSec)
    }

    /**
     * Initialize a fresh session for the active kid.
     * Called when a kid PINs in (via LockActivity) or when the service first
     * detects an active kid without a session.
     */
    private fun initSession(kidId: Int) {
        val now = System.currentTimeMillis()
        val kid = storage.getKids().find { it.id == kidId } ?: return
        storage.setSessionStartMs(now)
        storage.setSessionStartUsedSec(kid.usedSec)
    }

    private fun clearSession() {
        storage.setSessionStartMs(0L)
        storage.setSessionStartUsedSec(0L)
    }

    /**
     * Use Android's UsageStatsManager event stream to compute the EXACT
     * number of seconds spent in blocked apps between [start] and [end].
     *
     * This is far more accurate than counting our own tick deltas, because:
     * - It captures time even when the system throttles our service
     * - It captures time the user spent in YouTube before our service noticed
     * - It naturally excludes home screen / allowed apps from the count
     */
    private fun secondsInBlockedAppsSince(start: Long, end: Long): Long {
        if (end <= start) return 0L
        val blocked = storage.getBlockedPackages()
        // Query a slightly wider window so the very first event before [start]
        // is included if the user was already in a blocked app at session start
        val events = usageStatsManager.queryEvents(start - 60_000L, end + 1000L)
        val event = UsageEvents.Event()

        // Walk the event stream and accumulate time spent in blocked apps.
        // We track the "currently foregrounded blocked app" and its start time;
        // when it goes to background we add the delta.
        var totalMs = 0L
        var currentBlockedStart: Long = -1L

        // Find what was in foreground at [start] by looking at events before it
        val priorEvents = usageStatsManager.queryEvents(start - 60_000L, start + 1)
        val priorEvent = UsageEvents.Event()
        var priorPackage: String? = null
        while (priorEvents.hasNextEvent()) {
            priorEvents.getNextEvent(priorEvent)
            if (isForegroundEvent(priorEvent.eventType)) {
                priorPackage = priorEvent.packageName
            } else if (isBackgroundEvent(priorEvent.eventType) &&
                       priorEvent.packageName == priorPackage) {
                priorPackage = null
            }
        }
        if (priorPackage != null && blocked.contains(priorPackage)) {
            // User was already in a blocked app when session started
            currentBlockedStart = start
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts < start) continue
            if (ts > end) break

            val pkg = event.packageName
            val type = event.eventType

            if (isForegroundEvent(type)) {
                if (blocked.contains(pkg)) {
                    if (currentBlockedStart < 0) {
                        currentBlockedStart = ts
                    }
                } else {
                    // Switched to non-blocked app -> close the running interval
                    if (currentBlockedStart >= 0) {
                        totalMs += (ts - currentBlockedStart)
                        currentBlockedStart = -1
                    }
                }
            } else if (isBackgroundEvent(type)) {
                if (blocked.contains(pkg) && currentBlockedStart >= 0) {
                    totalMs += (ts - currentBlockedStart)
                    currentBlockedStart = -1
                }
            }
        }

        // If a blocked app is still in foreground at [end], close the interval
        if (currentBlockedStart >= 0) {
            totalMs += (end - currentBlockedStart)
        }

        return (totalMs / 1000L).coerceAtLeast(0)
    }

    private fun isForegroundEvent(type: Int): Boolean {
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
               type == UsageEvents.Event.ACTIVITY_RESUMED
    }

    private fun isBackgroundEvent(type: Int): Boolean {
        return type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
               type == UsageEvents.Event.ACTIVITY_PAUSED ||
               type == UsageEvents.Event.ACTIVITY_STOPPED
    }

    private fun checkOutOfTime(activeKid: Int, fg: String) {
        val kid = storage.getKids().find { it.id == activeKid }
        if (kid != null && kid.usedSec >= kid.dailyLimitSec) {
            storage.setActiveKid(-1)
            clearSession()
            storage.setLockGraceUntil(0L)
            showLock(fg)
        }
    }

    private fun showLock(blockedPackage: String) {
        if (lockShowing) return
        lockShowing = true
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_HISTORY
            )
            putExtra("blocked_package", blockedPackage)
        }
        startActivity(intent)
        handler.postDelayed({ lockShowing = false }, 2000)
    }

    private fun getForegroundApp(): String? {
        val end = System.currentTimeMillis()
        val start = end - 10_000
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (isForegroundEvent(event.eventType)) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "KidTime Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KidTime is watching")
            .setContentText("Monitoring kids' TV time")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "kidtime_monitor"
        const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
