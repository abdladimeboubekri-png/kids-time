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
import android.util.Log
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    private lateinit var storage: Storage
    private lateinit var usageStatsManager: UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private var lastKnownForeground: String? = null
    private var lockShowing: Boolean = false
    private var lastLockShownAt: Long = 0L
    private var tickCount: Long = 0L
    private var lastEvent: String = ""

    /**
     * TRANSIENT system overlays that flash briefly during normal app use
     * — they're not the user actually navigating somewhere.
     *
     * On Android TV, when watching a YouTube video, the OS flashes
     * `tvrecommendations` for milliseconds to render "next video" overlays.
     * The user has NOT left YouTube — it's a system UI artifact.
     *
     * These should be IGNORED:
     *   - Don't count time
     *   - Don't kick out kid
     *   - Don't reset anything
     *   - Pretend they didn't happen
     *
     * Importantly, this list does NOT include the home launcher
     * (com.google.android.tvlauncher). Going to home is a real user action
     * that should end the session.
     */
    private val transientOverlayPackages = setOf(
        "com.google.android.tvrecommendations",
        "com.google.android.tvrecommendation",
        "com.android.systemui",
        "android",
        "com.google.android.gms"
    )

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
        startForeground(NOTIF_ID, buildNotification("starting"))
        if (storage.isDebugOverlayEnabled()) {
            DebugOverlay.attach(this)
            DebugOverlay.update("KidTime: started")
        }
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        DebugOverlay.detach()
    }

    private fun tick() {
        tickCount++
        val now = System.currentTimeMillis()
        val fg = getForegroundAppRobust()
        val isOurApp = (fg == packageName)
        val blocked = storage.getBlockedPackages()
        val isBlocked = fg != null && blocked.contains(fg)
        val isTransientOverlay = fg != null && transientOverlayPackages.contains(fg)
        val activeKid = storage.getActiveKid()
        val inGracePeriod = now < storage.getLockGraceUntil()

        // Dynamically attach/detach overlay based on current setting
        val debugEnabled = storage.isDebugOverlayEnabled()
        if (debugEnabled) {
            // Update overlay every tick — show what we're seeing
            val kid = if (activeKid >= 0) storage.getKids().find { it.id == activeKid } else null
            val left = if (kid != null) (kid.dailyLimitSec - kid.usedSec).coerceAtLeast(0) else 0L
            val state = buildString {
                append("T#$tickCount  ")
                append("fg=${fg?.takeLast(22) ?: "?"}  ")
                append("blk=${if (isBlocked) "Y" else if (isTransientOverlay) "ovr" else "N"}  ")
                if (kid != null) {
                    append("${kid.name} ${formatTime(left)} left  ")
                } else {
                    append("noKid  ")
                }
                if (lastEvent.isNotEmpty()) append("\n• $lastEvent")
            }
            DebugOverlay.attach(this)  // no-op if already attached
            DebugOverlay.update(state)
        } else {
            DebugOverlay.detach()  // no-op if already detached
        }

        if (fg == null) return

        if (isOurApp) {
            lockShowing = false
            lastForegroundPackage = fg
            return
        }

        // ===== TRANSIENT OVERLAY: completely ignore =====
        // These are millisecond flashes during normal video playback.
        // We do NOT update lastForegroundPackage so our anchor stays on
        // whatever real app the user was using.
        // We do NOT update used time (that gets recomputed from UsageStats
        // anyway when we next see the real app).
        // We do NOT kick out the kid.
        if (isTransientOverlay) {
            // Skip this tick entirely — pretend we never saw it
            return
        }

        // ===== BLOCKED APP =====
        if (isBlocked) {
            // Always check time first (covers grace-period kids too)
            if (activeKid >= 0) {
                updateUsedTimeFromSession(activeKid)
                val k = storage.getKids().find { it.id == activeKid }
                if (k != null && k.usedSec >= k.dailyLimitSec) {
                    lastEvent = "TIME UP! locking..."
                    Log.d(TAG, "TIME UP for ${k.name}: ${k.usedSec}/${k.dailyLimitSec}s")
                    storage.setActiveKid(-1)
                    clearSession()
                    storage.setLockGraceUntil(0L)
                    forceShowLock(fg)
                    lastForegroundPackage = fg
                    return
                }
            }

            if (inGracePeriod && activeKid >= 0) {
                lastForegroundPackage = fg
                return
            }

            // App really changed (compared to our anchor, which skipped overlays)
            // OR no kid logged in: prompt for PIN
            val realAppChange = lastForegroundPackage != null &&
                                lastForegroundPackage != fg
            if (activeKid < 0 || realAppChange) {
                if (activeKid >= 0) updateUsedTimeFromSession(activeKid)
                storage.setActiveKid(-1)
                clearSession()
                lastEvent = "blocked app: need PIN"
                forceShowLock(fg)
                lastForegroundPackage = fg
                return
            }

            lastForegroundPackage = fg
            return
        }

        // ===== ANYTHING ELSE (home screen, allowed apps, settings) =====
        // The kid genuinely left the blocked app — log them out.
        // Going to home/launcher MUST end the session so re-entering the
        // blocked app requires a fresh PIN (so a different kid can take over).
        if (!inGracePeriod && activeKid >= 0) {
            updateUsedTimeFromSession(activeKid)
            storage.setActiveKid(-1)
            clearSession()
            lastEvent = "left blocked app"
            // Cancel pending alarm since the session is over
            AlarmScheduler.cancel(this)
        }
        lastForegroundPackage = fg
    }

    private fun getForegroundAppRobust(): String? {
        val now = System.currentTimeMillis()

        try {
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                now - 60 * 60 * 1000L,
                now
            )
            if (stats != null && stats.isNotEmpty()) {
                val mostRecent = stats
                    .filter { it.lastTimeUsed > 0 && it.packageName.isNotBlank() }
                    .maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null && (now - mostRecent.lastTimeUsed) < 30_000L) {
                    lastKnownForeground = mostRecent.packageName
                    return mostRecent.packageName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryUsageStats failed", e)
        }

        try {
            val events = usageStatsManager.queryEvents(now - 60 * 60 * 1000L, now)
            val event = UsageEvents.Event()
            var lastForegrounded: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForegrounded = event.packageName
                } else if ((event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                            event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                            event.eventType == UsageEvents.Event.ACTIVITY_STOPPED) &&
                           event.packageName == lastForegrounded) {
                    lastForegrounded = null
                }
            }
            if (lastForegrounded != null) {
                lastKnownForeground = lastForegrounded
                return lastForegrounded
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryEvents wide failed", e)
        }

        return lastKnownForeground
    }

    private fun updateUsedTimeFromSession(kidId: Int) {
        val sessionStart = storage.getSessionStartMs()
        if (sessionStart <= 0) {
            initSession(kidId)
            return
        }
        val now = System.currentTimeMillis()
        val secondsSinceStart = secondsInBlockedAppsSince(sessionStart, now)
        val newUsedSec = storage.getSessionStartUsedSec() + secondsSinceStart
        storage.setUsedSeconds(kidId, newUsedSec)
    }

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
     * Sum time the kid spent in blocked apps since session start.
     *
     * KEY: transient overlay events are SKIPPED entirely so they don't
     * artificially close intervals. Time spent during overlay flashes
     * is naturally rolled into the surrounding blocked-app time.
     */
    private fun secondsInBlockedAppsSince(start: Long, end: Long): Long {
        if (end <= start) return 0L
        val blocked = storage.getBlockedPackages()
        val events = usageStatsManager.queryEvents(start - 60_000L, end + 5000L)
        val event = UsageEvents.Event()
        var totalMs = 0L
        var currentBlockedStart: Long = -1L

        // What was foregrounded at session start?
        val priorEvents = usageStatsManager.queryEvents(start - 60 * 60 * 1000L, start + 1)
        val priorEvent = UsageEvents.Event()
        var priorPackage: String? = null
        while (priorEvents.hasNextEvent()) {
            priorEvents.getNextEvent(priorEvent)
            // Skip overlay events when looking for what was REALLY foregrounded
            if (transientOverlayPackages.contains(priorEvent.packageName)) continue
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

            // Skip transient overlay events entirely — they don't count as
            // either entering or exiting the blocked app.
            if (transientOverlayPackages.contains(pkg)) continue

            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                type == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (blocked.contains(pkg)) {
                    if (currentBlockedStart < 0) currentBlockedStart = ts
                } else {
                    // Real non-blocked app foregrounded -> close interval
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

    private fun forceShowLock(blockedPackage: String) {
        val now = System.currentTimeMillis()
        if (now - lastLockShownAt < 1500L) return
        lastLockShownAt = now
        lockShowing = true

        Log.d(TAG, "forceShowLock: blocked=$blockedPackage")
        lastEvent = "forceShowLock CALLED"

        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)
            lastEvent = "HOME sent OK"
        } catch (e: Exception) {
            Log.e(TAG, "HOME failed", e)
            lastEvent = "HOME FAILED"
        }

        handler.postDelayed({ launchLockActivity(blockedPackage) }, 400)
        handler.postDelayed({ launchLockActivity(blockedPackage) }, 1800)
        handler.postDelayed({ lockShowing = false }, 3000)
    }

    private fun launchLockActivity(blockedPackage: String) {
        val lockIntent = Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_HISTORY
            )
            putExtra("blocked_package", blockedPackage)
        }
        try {
            startActivity(lockIntent)
            lastEvent = "Lock launched OK"
        } catch (e: Exception) {
            Log.e(TAG, "Direct launch failed", e)
            lastEvent = "Lock FAILED"
        }

        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(this, 0, lockIntent, flags)

            val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("⏰ KidTime — PIN required")
                .setContentText("Tap to enter PIN")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ALERT_NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Full-screen notification failed", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "KidTime Monitor",
                NotificationManager.IMPORTANCE_LOW
            ))
            val alertCh = NotificationChannel(
                ALERT_CHANNEL_ID, "KidTime Lock Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown when a kid's time runs out"
                enableLights(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(alertCh)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KidTime is watching")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    private fun formatTime(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return "${m}m${s}s"
    }

    companion object {
        const val TAG = "KidTimeMonitor"
        const val CHANNEL_ID = "kidtime_monitor"
        const val ALERT_CHANNEL_ID = "kidtime_alerts"
        const val NOTIF_ID = 1001
        const val ALERT_NOTIF_ID = 1002

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
