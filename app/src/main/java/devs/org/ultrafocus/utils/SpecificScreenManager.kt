package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences

object SpecificScreenManager {
    private const val PREF_NAME = "SpecificScreenPrefs"
    private const val KEY_BLOCKED_SCREENS = "blocked_screens"
    private const val KEY_DEBUG_MODE = "debug_mode"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getBlockedScreens(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_SCREENS, mutableSetOf()) ?: mutableSetOf()
    }

    fun addScreen(context: Context, screenName: String) {
        val currentList = getBlockedScreens(context)
        currentList.add(screenName)
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_SCREENS, currentList).apply()
    }

    fun removeScreen(context: Context, screenName: String) {
        val currentList = getBlockedScreens(context)
        currentList.remove(screenName)
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_SCREENS, currentList).apply()
    }

    fun isScreenBlocked(context: Context, className: String?): Boolean {
        if (className == null) return false
        val blocked = getBlockedScreens(context)
        // Returns true if the blocked list contains the class name
        return blocked.any { className.equals(it, ignoreCase = true) }
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    fun isDebugMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEBUG_MODE, false)
    }
}