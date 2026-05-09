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
    private var lockShowing: Boolean = false
    private var lastLockShownAt: Long = 0L
    private var tickCount: Long = 0L
    private var lastEvent: String = ""

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
        DebugOverlay.attach(this)
        DebugOverlay.update("KidTime: started")
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
        val fg = getForegroundApp()
        val isOurApp = (fg == packageName)
        val blocked = storage.getBlockedPackages()
        val isBlocked = fg != null && blocked.contains(fg)
        val appChanged = (lastForegroundPackage != null && lastForegroundPackage != fg)
        val activeKid = storage.getActiveKid()
        val inGracePeriod = now < storage.getLockGraceUntil()

        // Update overlay every tick
        val kid = if (activeKid >= 0) storage.getKids().find { it.id == activeKid } else null
        val left = if (kid != null) (kid.dailyLimitSec - kid.usedSec).coerceAtLeast(0) else 0L
        val state = buildString {
            append("T#$tickCount  ")
            append("fg=${fg?.takeLast(22) ?: "?"}  ")
            append("blk=${if (isBlocked) "Y" else "N"}  ")
            if (kid != null) {
                append("${kid.name} ${formatTime(left)} left  ")
            } else {
                append("noKid  ")
            }
            if (lastEvent.isNotEmpty()) append("\n• $lastEvent")
        }
        DebugOverlay.update(state)

        if (fg == null) return

        if (isOurApp) {
            lockShowing = false
            lastForegroundPackage = fg
            return
        }

        if (isBlocked) {
            // Always check time first - even during grace period
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

            if (activeKid < 0 || appChanged) {
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

        if (!inGracePeriod && activeKid >= 0) {
            updateUsedTimeFromSession(activeKid)
            storage.setActiveKid(-1)
            clearSession()
            lastEvent = "left blocked app"
        }
        lastForegroundPackage = fg
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

    private fun secondsInBlockedAppsSince(start: Long, end: Long): Long {
        if (end <= start) return 0L
        val blocked = storage.getBlockedPackages()
        val events = usageStatsManager.queryEvents(start - 60_000L, end + 1000L)
        val event = UsageEvents.Event()
        var totalMs = 0L
        var currentBlockedStart: Long = -1L

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
                    if (currentBlockedStart < 0) currentBlockedStart = ts
                } else {
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

    private fun forceShowLock(blockedPackage: String) {
        val now = System.currentTimeMillis()
        if (now - lastLockShownAt < 1500L) return
        lastLockShownAt = now
        lockShowing = true

        Log.d(TAG, "forceShowLock: blocked=$blockedPackage")
        lastEvent = "forceShowLock CALLED"

        // Step 1: send HOME to break out of YouTube/Netflix
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(home)
            Log.d(TAG, "HOME sent")
            lastEvent = "HOME sent OK"
        } catch (e: Exception) {
            Log.e(TAG, "HOME failed", e)
            lastEvent = "HOME FAILED: ${e.message?.take(30)}"
        }

        // Step 2: launch our LockActivity after a short delay
        handler.postDelayed({
            launchLockActivity(blockedPackage)
        }, 400)

        // Step 3: backup attempt at 1.8s
        handler.postDelayed({
            launchLockActivity(blockedPackage)
        }, 1800)

        handler.postDelayed({ lockShowing = false }, 3000)
    }

    private fun launchLockActivity(blockedPackage: String) {
        Log.d(TAG, "Launching LockActivity")
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
            lastEvent = "Lock FAILED: ${e.message?.take(30)}"
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
