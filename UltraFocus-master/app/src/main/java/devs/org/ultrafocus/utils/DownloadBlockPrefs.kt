package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

object DownloadBlockPrefs {

    private const val PREF_NAME       = "download_block_prefs"
    private const val KEY_ENABLED     = "enabled"
    private const val KEY_STRICT_HOURS = "strict_hours"
    private const val KEY_STRICT_REQ  = "strict_req"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Enabled state ─────────────────────────────────────────────────────────

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ── Strict mode ───────────────────────────────────────────────────────────

    fun getStrictHours(context: Context): Int =
        prefs(context).getInt(KEY_STRICT_HOURS, 0)

    fun isStrictModeEnabled(context: Context): Boolean =
        getStrictHours(context) > 0

    fun setStrictMode(context: Context, hours: Int) {
        restoreStrictMode(context, hours, 0L)
    }

    fun restoreStrictMode(context: Context, hours: Int, requestTimestamp: Long) {
        if (hours < 0) return
        val editor = prefs(context).edit()
        if (hours <= 0) {
            editor.remove(KEY_STRICT_HOURS).remove(KEY_STRICT_REQ).apply()
            return
        }
        editor.putInt(KEY_STRICT_HOURS, hours)
            .putLong(KEY_STRICT_REQ, requestTimestamp.coerceAtLeast(0L))
            .apply()
    }

    fun clearStrictMode(context: Context) {
        prefs(context).edit().remove(KEY_STRICT_HOURS).remove(KEY_STRICT_REQ).apply()
    }

    fun getRequestTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_STRICT_REQ, 0L)

    fun requestUnlock(context: Context) {
        if (!isStrictModeEnabled(context)) return
        prefs(context).edit().putLong(KEY_STRICT_REQ, System.currentTimeMillis()).apply()
    }

    fun cancelRequest(context: Context) {
        prefs(context).edit().putLong(KEY_STRICT_REQ, 0L).apply()
    }

    fun isLocked(context: Context): Boolean {
        val hours = getStrictHours(context)
        if (hours <= 0) return false
        val req = getRequestTimestamp(context)
        if (req == 0L) return true
        val delayMs = hours.toLong() * 3_600_000L
        return (System.currentTimeMillis() - req) < delayMs
    }

    fun getTimeRemaining(context: Context): Long {
        val hours = getStrictHours(context)
        if (hours <= 0) return 0L
        val req = getRequestTimestamp(context)
        if (req == 0L) return hours.toLong() * 3_600_000L
        val remaining = (hours.toLong() * 3_600_000L) - (System.currentTimeMillis() - req)
        return if (remaining < 0L) 0L else remaining
    }

    // ── Status text ───────────────────────────────────────────────────────────

    /**
     * FIX: previously returned "Download blocking: OFF" when strict mode wasn't
     * set, which users read as "download blocking is disabled" rather than
     * "strict mode is not configured". Now returns clearly scoped text.
     */
    fun getStatusText(context: Context): String {
        val hours = getStrictHours(context)

        if (hours <= 0) {
            return if (isEnabled(context))
                "Strict mode: not configured"
            else
                "Strict mode: not configured (download blocking is off)"
        }

        val req = getRequestTimestamp(context)
        if (req == 0L) {
            return "🔒 Strict lock active — download blocking cannot be turned off ($hours h delay)"
        }

        val remaining = getTimeRemaining(context)
        if (remaining <= 0L) {
            return "🔓 Cooldown elapsed — you can now disable download blocking"
        }

        val h = TimeUnit.MILLISECONDS.toHours(remaining)
        val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        return "⏳ Strict lock lifts in %02d:%02d:%02d".format(h, m, s)
    }

    /**
     * Short label for the strict mode button itself, so the user can see
     * current state at a glance without opening the dialog.
     */
    fun getStrictButtonLabel(context: Context): String {
        val hours = getStrictHours(context)
        if (hours <= 0) return "Set Download Strict Mode"
        return if (isLocked(context)) "Strict Lock: ${hours}h 🔒" else "Strict Lock: ${hours}h 🔓"
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
