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
    var usedSec: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("emoji", emoji)
        put("pin", pin); put("usedSec", usedSec)
    }
    companion object {
        fun fromJson(j: JSONObject) = Kid(
            j.getInt("id"), j.getString("name"), j.getString("emoji"),
            j.getString("pin"), j.getLong("usedSec")
        )
    }
}

class Storage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kidtime_prefs", Context.MODE_PRIVATE)

    // Default 3 kids if first run
    fun getKids(): MutableList<Kid> {
        resetIfNewDay()
        val raw = prefs.getString("kids", null)
        if (raw == null) {
            val defaults = mutableListOf(
                Kid(0, "Alex", "🦁", "1111", 0),
                Kid(1, "Sam", "🐼", "2222", 0),
                Kid(2, "Mia", "🦋", "3333", 0)
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

    fun addUsedSeconds(kidId: Int, seconds: Long) {
        val kids = getKids()
        kids.find { it.id == kidId }?.let {
            it.usedSec += seconds
            saveKids(kids)
        }
    }

    fun getDailyLimitSec(): Long = prefs.getLong("daily_limit_sec", 3 * 3600L)
    fun setDailyLimitSec(s: Long) = prefs.edit().putLong("daily_limit_sec", s).apply()

    fun getAdminPin(): String = prefs.getString("admin_pin", "1234") ?: "1234"
    fun setAdminPin(pin: String) = prefs.edit().putString("admin_pin", pin).apply()

    // List of package names to lock. Default: known TV streaming apps
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

    // Currently active kid (the one whose time is being consumed)
    fun getActiveKid(): Int = prefs.getInt("active_kid", -1)
    fun setActiveKid(id: Int) = prefs.edit().putInt("active_kid", id).apply()

    // Reset all time at midnight
    private fun resetIfNewDay() {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val savedDay = prefs.getInt("last_day", -1)
        if (savedDay != today) {
            prefs.edit().putInt("last_day", today).apply()
            // Reset usage for all kids
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
                .apply()
        }
    }
}
