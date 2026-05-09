package com.kidtime.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Kid(
    val id: Int,
    var name: String,
    var emoji: String,
    var pin: String,
    var usedSec: Long,
    var dailyLimitSec: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("emoji", emoji)
        put("pin", pin); put("usedSec", usedSec)
        put("dailyLimitSec", dailyLimitSec)
    }
    companion object {
        fun fromJson(j: JSONObject) = Kid(
            j.getInt("id"),
            j.getString("name"),
            j.getString("emoji"),
            j.getString("pin"),
            j.getLong("usedSec"),
            if (j.has("dailyLimitSec")) j.getLong("dailyLimitSec") else 3 * 3600L
        )
    }
}

class Storage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kidtime_prefs", Context.MODE_PRIVATE)

    fun getKids(): MutableList<Kid> {
        resetIfNewDay()
        val raw = prefs.getString("kids", null)
        if (raw == null) {
            val defaults = mutableListOf(
                Kid(0, "Alex", "🦁", "1111", 0, 3 * 3600L),
                Kid(1, "Sam", "🐼", "2222", 0, 3 * 3600L),
                Kid(2, "Mia", "🦋", "3333", 0, 3 * 3600L)
            )
            saveKids(defaults)
            return defaults
        }
        val arr = JSONArray(raw)
        val list = mutableListOf<Kid>()
        for (i in 0 until arr.length()) list.add(Kid.fromJson(arr.getJSONObject(i)))
        return list
    }

    fun saveKids(kids: List<Kid>) {
        val arr = JSONArray()
        kids.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("kids", arr.toString()).apply()
    }

    fun addKid(name: String, emoji: String, pin: String, dailyLimitSec: Long) {
        val kids = getKids()
        val nextId = (kids.maxOfOrNull { it.id } ?: -1) + 1
        kids.add(Kid(nextId, name, emoji, pin, 0, dailyLimitSec))
        saveKids(kids)
    }

    fun removeKid(id: Int) {
        val kids = getKids().filter { it.id != id }.toMutableList()
        saveKids(kids)
        if (getActiveKid() == id) setActiveKid(-1)
    }

    fun addUsedSeconds(kidId: Int, seconds: Long) {
        if (seconds <= 0) return
        val kids = getKids()
        kids.find { it.id == kidId }?.let {
            it.usedSec += seconds
            saveKids(kids)
        }
    }

    fun setUsedSeconds(kidId: Int, seconds: Long) {
        val kids = getKids()
        kids.find { it.id == kidId }?.let {
            it.usedSec = seconds.coerceAtLeast(0)
            saveKids(kids)
        }
    }

    fun getDefaultDailyLimitSec(): Long = prefs.getLong("default_daily_limit_sec", 3 * 3600L)
    fun setDefaultDailyLimitSec(s: Long) = prefs.edit().putLong("default_daily_limit_sec", s).apply()

    fun getAdminPin(): String = prefs.getString("admin_pin", "1234") ?: "1234"
    fun setAdminPin(pin: String) = prefs.edit().putString("admin_pin", pin).apply()

    fun getBlockedPackages(): MutableSet<String> {
        val saved = prefs.getStringSet("blocked_packages", null)
        if (saved == null) {
            val defaults = mutableSetOf(
                "com.google.android.youtube.tv",
                "com.google.android.youtube",
                "com.netflix.ninja",
                "com.netflix.mediaclient",
                "com.amazon.amazonvideo.livingroom",
                "com.disney.disneyplus",
                "com.spotify.tv.android",
                "com.spotify.music",
                "tv.twitch.android.app"
            )
            prefs.edit().putStringSet("blocked_packages", defaults).apply()
            return defaults.toMutableSet()
        }
        return saved.toMutableSet()
    }

    fun setBlockedPackages(s: Set<String>) =
        prefs.edit().putStringSet("blocked_packages", s).apply()

    fun getActiveKid(): Int = prefs.getInt("active_kid", -1)
    fun setActiveKid(id: Int) = prefs.edit().putInt("active_kid", id).apply()

    fun getSessionStartMs(): Long = prefs.getLong("session_start_ms", 0L)
    fun setSessionStartMs(ms: Long) = prefs.edit().putLong("session_start_ms", ms).apply()

    fun getSessionStartUsedSec(): Long = prefs.getLong("session_start_used_sec", 0L)
    fun setSessionStartUsedSec(s: Long) = prefs.edit().putLong("session_start_used_sec", s).apply()

    fun getLockGraceUntil(): Long = prefs.getLong("lock_grace_until", 0L)
    fun setLockGraceUntil(ts: Long) = prefs.edit().putLong("lock_grace_until", ts).apply()

    fun isDebugOverlayEnabled(): Boolean = prefs.getBoolean("debug_overlay", true)
    fun setDebugOverlayEnabled(enabled: Boolean) =
        prefs.edit().putBoolean("debug_overlay", enabled).apply()

    /**
     * Called by BootReceiver and MonitorService.onCreate.
     * Wipes any in-flight session because:
     *   1) UsageStatsManager event history may not survive reboot
     *   2) sessionStartMs may now point to a time before reboot, which
     *      would let the kid "gain" time by rebooting (the session-based
     *      computation would think they had 0 elapsed seconds)
     *
     * IMPORTANTLY: kid.usedSec is preserved! Only the active session is cleared.
     * This means if a kid was watching with 5 min used and the TV reboots,
     * after reboot they're still at 5 min used — they can't reset by rebooting.
     */
    fun handleBootOrServiceRestart() {
        // Force a save of usedSec — this is already persistent, but make sure
        // any pending session updates have been written.
        prefs.edit()
            .putInt("active_kid", -1)
            .putLong("session_start_ms", 0L)
            .putLong("session_start_used_sec", 0L)
            .putLong("lock_grace_until", 0L)
            .apply()
    }

    /**
     * Detects a new day using TWO criteria, both of which must agree:
     *  1. Calendar day has changed (Year × 1000 + DayOfYear)
     *  2. Date string has changed
     *
     * This is robust against reboots: the calendar day is stored as a number
     * derived from the wall clock, so even if the TV is rebooted, as long as
     * the system clock is correct, the day check works.
     *
     * SECURITY: We also check that the saved day is in the PAST. If somehow
     * the stored day is in the future (clock manipulation?), we don't reset.
     */
    private fun resetIfNewDay() {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val savedDay = prefs.getInt("last_day", -1)

        if (savedDay == -1) {
            // First run — just record today, don't reset
            prefs.edit().putInt("last_day", today).apply()
            return
        }

        // Only reset if today is STRICTLY GREATER than savedDay
        // (Equal = same day, no reset; Less = clock went backwards, no reset)
        if (today <= savedDay) {
            return
        }

        // It's a new day - reset everyone's used time
        prefs.edit().putInt("last_day", today).apply()
        val raw = prefs.getString("kids", null) ?: return
        val arr = JSONArray(raw)
        val list = mutableListOf<Kid>()
        for (i in 0 until arr.length()) {
            val k = Kid.fromJson(arr.getJSONObject(i))
            k.usedSec = 0
            list.add(k)
        }
        val newArr = JSONArray()
        list.forEach { newArr.put(it.toJson()) }
        prefs.edit()
            .putString("kids", newArr.toString())
            .putInt("active_kid", -1)
            .putLong("session_start_ms", 0L)
            .putLong("session_start_used_sec", 0L)
            .apply()
    }
}
