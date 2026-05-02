package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * Per-item strict mode.
 * Each blocked item (app package name, keyword, website host, screen class)
 * can have its own unlock delay independent of the global StrictModeManager.
 *
 * Storage keys per item (itemKey = packageName / keyword / host / className):
 *   strict_hours_{itemKey}    → Int   (0 = no strict mode)
 *   strict_req_{itemKey}      → Long  (timestamp of unlock request, 0 = no request)
 */
object ItemStrictModeManager {

    private const val PREF_NAME = "ItemStrictModePrefs"
    private const val PREFIX_HOURS = "strict_hours_"
    private const val PREFIX_REQ   = "strict_req_"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Setup ────────────────────────────────────────────────────────────────

    /** Set or update strict mode hours for a specific item. 0 = disabled. */
    fun setStrictMode(context: Context, itemKey: String, hours: Int) {
        if (hours < 0) return
        getPrefs(context).edit()
            .putInt(PREFIX_HOURS + itemKey, hours)
            .putLong(PREFIX_REQ + itemKey, 0L) // reset any pending request
            .apply()
    }

    /** Remove all strict mode data for a specific item. */
    fun clearItem(context: Context, itemKey: String) {
        getPrefs(context).edit()
            .remove(PREFIX_HOURS + itemKey)
            .remove(PREFIX_REQ + itemKey)
            .apply()
    }

    /** Remove all per-item strict mode data (used by BackupManager.clearAll). */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    // ── State queries ────────────────────────────────────────────────────────

    fun getHours(context: Context, itemKey: String): Int =
        getPrefs(context).getInt(PREFIX_HOURS + itemKey, 0)

    fun isEnabled(context: Context, itemKey: String): Boolean =
        getHours(context, itemKey) > 0

    /**
     * Returns true if this item is strictly locked
     * (enabled AND the unlock countdown has not completed yet).
     */
    fun isLocked(context: Context, itemKey: String): Boolean {
        val hours = getHours(context, itemKey)
        if (hours <= 0) return false // not enabled

        val reqTime = getPrefs(context).getLong(PREFIX_REQ + itemKey, 0L)
        if (reqTime == 0L) return true // enabled, but no unlock request yet → locked

        val delayMs = hours.toLong() * 3_600_000L
        return (System.currentTimeMillis() - reqTime) < delayMs
    }

    // ── Unlock flow ──────────────────────────────────────────────────────────

    /** Start the countdown clock for this item's unlock. */
    fun requestUnlock(context: Context, itemKey: String) {
        getPrefs(context).edit()
            .putLong(PREFIX_REQ + itemKey, System.currentTimeMillis())
            .apply()
    }

    /** Cancel a pending unlock request, re-locking the item immediately. */
    fun cancelRequest(context: Context, itemKey: String) {
        getPrefs(context).edit()
            .putLong(PREFIX_REQ + itemKey, 0L)
            .apply()
    }

    /** Milliseconds remaining until unlock completes. 0 if already unlocked. */
    fun getTimeRemaining(context: Context, itemKey: String): Long {
        val hours = getHours(context, itemKey)
        if (hours <= 0) return 0L
        val reqTime = getPrefs(context).getLong(PREFIX_REQ + itemKey, 0L)
        if (reqTime == 0L) return hours.toLong() * 3_600_000L
        val diff = (hours.toLong() * 3_600_000L) - (System.currentTimeMillis() - reqTime)
        return if (diff < 0) 0L else diff
    }

    /** Human-readable status string for UI display. */
    fun getStatusText(context: Context, itemKey: String): String {
        val hours = getHours(context, itemKey)
        if (hours <= 0) return "No strict mode"

        val reqTime = getPrefs(context).getLong(PREFIX_REQ + itemKey, 0L)
        if (reqTime == 0L) return "🔒 Locked ($hours h delay)"

        val remaining = getTimeRemaining(context, itemKey)
        if (remaining <= 0L) return "🔓 Unlocked — you can now delete this"

        val h = TimeUnit.MILLISECONDS.toHours(remaining)
        val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        return "⏳ Unlocking in %02d:%02d:%02d".format(h, m, s)
    }

    // ── Backup helpers ───────────────────────────────────────────────────────

    /** Returns all item keys that have strict mode set (hours > 0). */
    fun getAllKeys(context: Context): Set<String> {
        val prefs = getPrefs(context)
        return prefs.all.keys
            .filter { it.startsWith(PREFIX_HOURS) }
            .map { it.removePrefix(PREFIX_HOURS) }
            .filter { key -> (prefs.getInt(PREFIX_HOURS + key, 0)) > 0 }
            .toSet()
    }
}

