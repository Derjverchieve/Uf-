package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object SpecificScreenManager {
    private const val PREF_NAME = "SpecificScreenPrefs"
    private const val KEY_BLOCKED_SCREENS = "blocked_screens"
    private const val KEY_DEBUG_MODE = "debug_mode"
    private const val PREF_SCHEDULE_PREFIX = "sched_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getBlockedScreens(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_SCREENS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    fun addScreen(context: Context, screenName: String, schedule: String?) {
        if (screenName.contains("devs.org.ultrafocus")) return // Self protection

        val currentList = getBlockedScreens(context)
        currentList.add(screenName)

        val editor = getPrefs(context).edit()
        editor.putStringSet(KEY_BLOCKED_SCREENS, currentList)
        editor.putString(PREF_SCHEDULE_PREFIX + screenName, schedule ?: "")
        editor.apply()
    }

    fun removeScreen(context: Context, screenName: String) {
        val currentList = getBlockedScreens(context)
        currentList.remove(screenName)

        val editor = getPrefs(context).edit()
        editor.putStringSet(KEY_BLOCKED_SCREENS, currentList)
        editor.remove(PREF_SCHEDULE_PREFIX + screenName)
        editor.apply()
    }

    fun getSchedule(context: Context, screenName: String): String {
        return getPrefs(context).getString(PREF_SCHEDULE_PREFIX + screenName, "") ?: ""
    }

    fun isScreenBlocked(context: Context, className: String?): Boolean {
        if (className == null) return false
        val blocked = getBlockedScreens(context)

        // Find if this screen is in the list
        val match = blocked.find { className.contains(it, ignoreCase = true) } ?: return false

        // Check Schedule
        val schedule = getSchedule(context, match)
        if (schedule.isEmpty()) return true // Block 24/7

        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val ranges = schedule.split(",")
        for (range in ranges) {
            val parts = range.split("-")
            if (parts.size == 2) {
                try {
                    val start = parseTime(parts[0])
                    val end = parseTime(parts[1])
                    if (currentMinute in start..end) return true
                } catch (e: Exception) { }
            }
        }
        return false
    }

    private fun parseTime(t: String): Int {
        val split = t.trim().split(":")
        return split[0].toInt() * 60 + split[1].toInt()
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    fun isDebugMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEBUG_MODE, false)
    }
}