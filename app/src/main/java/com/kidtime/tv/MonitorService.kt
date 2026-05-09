package com.kidtime.tv

import android.app.*
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
    private var lastTickTime: Long = 0
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
        lastTickTime = System.currentTimeMillis()
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
        val deltaMs = now - lastTickTime
        lastTickTime = now

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
            // CRITICAL FIX: during grace period after a successful PIN,
            // do NOT treat this as a fresh app entry. The grace period
            // exists because the system takes ~1-2 seconds to actually
            // launch the blocked app after we close the lock screen,
            // and we don't want that transition to look like an "app
            // switch" that requires another PIN.
            if (inGracePeriod && activeKid >= 0) {
                // Just count time normally
                val secElapsed = (deltaMs / 1000).coerceAtLeast(0)
                if (secElapsed > 0) storage.addUsedSeconds(activeKid, secElapsed)
                checkOutOfTime(activeKid, fg)
                lastForegroundPackage = fg
                return
            }

            // Outside grace period: enforce normal PIN-on-switch logic
            if (activeKid < 0 || appChanged) {
                storage.setActiveKid(-1)
                showLock(fg)
                lastForegroundPackage = fg
                return
            }

            val secElapsed = (deltaMs / 1000).coerceAtLeast(0)
            if (secElapsed > 0) storage.addUsedSeconds(activeKid, secElapsed)
            checkOutOfTime(activeKid, fg)
            lastForegroundPackage = fg
            return
        }

        // Foreground is not our app and not a blocked app:
        // Home, settings, allowed apps, etc.
        // BUT: if we're still in grace period, the kid hasn't reached the
        // blocked app yet - keep them active for a moment longer.
        if (!inGracePeriod && activeKid >= 0) {
            storage.setActiveKid(-1)
        }
        lastForegroundPackage = fg
    }

    private fun checkOutOfTime(activeKid: Int, fg: String) {
        val kid = storage.getKids().find { it.id == activeKid }
        if (kid != null && kid.usedSec >= kid.dailyLimitSec) {
            storage.setActiveKid(-1)
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
        val event = android.app.usage.UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
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
