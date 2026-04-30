package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

object StrictModeManager {
    private const val PREF_NAME = "StrictModePrefs"
    private const val KEY_STRICT_ENABLED = "strict_enabled"
    private const val KEY_UNLOCK_DELAY = "unlock_delay_ms"
    private const val KEY_REQUEST_TIMESTAMP = "request_timestamp"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun setStrictMode(context: Context, enabled: Boolean, delayHours: Int) {
        // Prevent changing if currently locked
        if (isLocked(context)) return

        val editor = getPrefs(context).edit()
        editor.putBoolean(KEY_STRICT_ENABLED, enabled)
        // Convert hours to milliseconds
        editor.putLong(KEY_UNLOCK_DELAY, delayHours.toLong() * 3600 * 1000L)
        editor.putLong(KEY_REQUEST_TIMESTAMP, 0) // Reset request
        editor.apply()
    }

    fun isLocked(context: Context): Boolean {
        val isEnabled = getPrefs(context).getBoolean(KEY_STRICT_ENABLED, false)
        if (!isEnabled) return false // Not enabled = Not locked

        val requestTime = getPrefs(context).getLong(KEY_REQUEST_TIMESTAMP, 0)
        val delay = getPrefs(context).getLong(KEY_UNLOCK_DELAY, 0)

        // If no request made (0), we are definitely locked
        if (requestTime == 0L) return true

        // If request made, check if time has passed
        val timePassed = System.currentTimeMillis() - requestTime
        return timePassed < delay
    }

    fun requestUnlock(context: Context) {
        getPrefs(context).edit().putLong(KEY_REQUEST_TIMESTAMP, System.currentTimeMillis()).apply()
    }

    fun cancelRequest(context: Context) {
        getPrefs(context).edit().putLong(KEY_REQUEST_TIMESTAMP, 0).apply()
    }

    // Helper to get raw milliseconds remaining
    fun getTimeRemaining(context: Context): Long {
        val requestTime = getPrefs(context).getLong(KEY_REQUEST_TIMESTAMP, 0)
        val delay = getPrefs(context).getLong(KEY_UNLOCK_DELAY, 0)
        if (requestTime == 0L) return delay // Full time remaining

        val timePassed = System.currentTimeMillis() - requestTime
        return delay - timePassed
    }

    fun getStatusText(context: Context): String {
        val isEnabled = getPrefs(context).getBoolean(KEY_STRICT_ENABLED, false)
        if (!isEnabled) return "Strict Mode: OFF"

        val requestTime = getPrefs(context).getLong(KEY_REQUEST_TIMESTAMP, 0)

        if (requestTime == 0L) {
            val delay = getPrefs(context).getLong(KEY_UNLOCK_DELAY, 0)
            val hours = TimeUnit.MILLISECONDS.toHours(delay)
            return "Status: SECURE 🔒\n(You must 'Request Unlock' to start the $hours hour timer)"
        }

        val timeLeft = getTimeRemaining(context)

        return if (timeLeft <= 0) {
            "Status: UNLOCKED 🔓\n(You can now edit settings)"
        } else {
            val h = TimeUnit.MILLISECONDS.toHours(timeLeft)
            val m = TimeUnit.MILLISECONDS.toMinutes(timeLeft) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(timeLeft) % 60
            String.format("Unlocking in: %02d:%02d:%02d", h, m, s)
        }
    }

    fun isStrictModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STRICT_ENABLED, false)
    }
}