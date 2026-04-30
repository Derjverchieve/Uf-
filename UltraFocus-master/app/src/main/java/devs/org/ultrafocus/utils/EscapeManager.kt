package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

object EscapeManager {
    private const val PREF_NAME = "EscapePrefs"
    private const val KEY_BREACH_COUNT = "breach_count"
    private const val KEY_LAST_BREACH_TIME = "last_breach_timestamp"
    private const val KEY_UNLOCK_READY_TIME = "unlock_ready_timestamp"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // 1. ESCALATION LADDER
    fun getWaitDurationMs(context: Context): Long {
        val count = getPrefs(context).getInt(KEY_BREACH_COUNT, 0)

        // Ladder: 10m -> 30m -> 2h -> 24h
        val minutes = when (count) {
            0 -> 10L
            1 -> 30L
            2 -> 120L // 2 Hours
            else -> 1440L // 24 Hours
        }
        return TimeUnit.MINUTES.toMillis(minutes)
    }

    // 2. TRIGGER A BREACH (Called when Accessibility turns OFF)
    fun triggerBreach(context: Context) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // If we already have a target time set, don't reset it (keep the original timer running)
        if (prefs.getLong(KEY_UNLOCK_READY_TIME, 0) > now) return

        // Check for "7 Clean Days" reset
        val lastBreach = prefs.getLong(KEY_LAST_BREACH_TIME, 0)
        val sevenDaysMs = TimeUnit.DAYS.toMillis(7)
        var count = prefs.getInt(KEY_BREACH_COUNT, 0)

        if (now - lastBreach > sevenDaysMs) {
            count = 0 // Reset ladder
        }

        // Set the new target time
        val waitTime = getWaitDurationMs(context)
        val readyTime = now + waitTime

        prefs.edit()
            .putInt(KEY_BREACH_COUNT, count + 1)
            .putLong(KEY_LAST_BREACH_TIME, now)
            .putLong(KEY_UNLOCK_READY_TIME, readyTime)
            .apply()
    }

    // 3. CHECK STATE
    // Returns TRUE if the timer has finished and user is allowed to be free
    fun isEscapeAllowed(context: Context): Boolean {
        val readyTime = getPrefs(context).getLong(KEY_UNLOCK_READY_TIME, 0)
        if (readyTime == 0L) return false // No breach initiated yet
        return System.currentTimeMillis() > readyTime
    }

    fun getTimeRemaining(context: Context): Long {
        val readyTime = getPrefs(context).getLong(KEY_UNLOCK_READY_TIME, 0)
        val diff = readyTime - System.currentTimeMillis()
        return if (diff < 0) 0 else diff
    }

    // Called when user Re-enables UltraFocus to clear the "Escape" state
    fun restoreNormalState(context: Context) {
        // We keep the breach count (for escalation), but we clear the ready time
        // so next time they breach, they have to wait again.
        getPrefs(context).edit().putLong(KEY_UNLOCK_READY_TIME, 0).apply()
    }
}