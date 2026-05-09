package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages per-item strict mode for the SpecificBlockerActivity list.
 *
 * How it works:
 * - setStrictMode(key, hours): arms the lock. Until requestUnlock() is called
 *   and the cooldown expires, the item cannot be deleted.
 * - requestUnlock(key): records the current timestamp. The item unlocks only
 *   after [strictHours] hours have elapsed since this timestamp.
 * - isLocked(key): returns true if the item is armed AND the cooldown has not yet passed.
 *
 * Bug fixed: previously setStrictMode() could be called again on an already-locked
 * item with a smaller hours value (e.g. 1 instead of 24), effectively bypassing the
 * lock by resetting the cooldown to a much shorter period. Now setStrictMode() silently
 * ignores any call that would weaken an active lock.
 */
object ItemStrictModeManager {

    private const val PREFS_NAME   = "ItemStrictModePrefs"
    private const val PREFIX_HOURS = "strict_hours_"
    private const val PREFIX_REQ   = "strict_req_"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Arms strict mode for [itemKey] with a [hours]-hour unlock delay.
     *
     * If the item is already locked, this call is ignored unless the new
     * [hours] value is strictly greater than the current one — you can only
     * make a lock stronger, never weaker.
     */
    fun setStrictMode(context: Context, itemKey: String, hours: Int) {
        if (hours <= 0) return

        // Bug fix: if already locked, only allow increasing the delay.
        // Without this check a user could re-add an item with hours=1 to bypass
        // a 24-hour lock by resetting the cooldown to a trivially short period.
        if (isLocked(context, itemKey)) {
            val currentHours = getHours(context, itemKey)
            if (hours <= currentHours) return
        }

        restoreStrictMode(context, itemKey, hours, 0L)
    }

    /** Used by BackupManager to restore a lock including its original timestamp. */
    fun restoreStrictMode(
        context: Context,
        itemKey: String,
        hours: Int,
        requestTimestamp: Long
    ) {
        if (hours < 0) return
        val editor = getPrefs(context).edit()
        if (hours == 0) {
            editor.remove(PREFIX_HOURS + itemKey)
                  .remove(PREFIX_REQ   + itemKey)
                  .apply()
            return
        }
        editor.putInt (PREFIX_HOURS + itemKey, hours)
              .putLong(PREFIX_REQ   + itemKey, requestTimestamp.coerceAtLeast(0L))
              .apply()
    }

    /** Starts the unlock countdown for [itemKey]. */
    fun requestUnlock(context: Context, itemKey: String) {
        getPrefs(context).edit()
            .putLong(PREFIX_REQ + itemKey, System.currentTimeMillis())
            .apply()
    }

    /** Cancels a pending unlock request without disabling strict mode. */
    fun cancelRequest(context: Context, itemKey: String) {
        getPrefs(context).edit()
            .putLong(PREFIX_REQ + itemKey, 0L)
            .apply()
    }

    /** Fully removes strict mode for [itemKey] (call after the item is deleted). */
    fun clearItem(context: Context, itemKey: String) {
        getPrefs(context).edit()
            .remove(PREFIX_HOURS + itemKey)
            .remove(PREFIX_REQ   + itemKey)
            .apply()
    }

    /** True if strict mode is armed for [itemKey] (regardless of lock state). */
    fun isEnabled(context: Context, itemKey: String): Boolean =
        getHours(context, itemKey) > 0

    /**
     * True if the item is currently locked and cannot be deleted.
     *
     * Locked means:
     * - strict mode is armed (hours > 0), AND
     * - either no unlock has been requested (req == 0), OR
     *   the cooldown period has not yet elapsed.
     */
    fun isLocked(context: Context, itemKey: String): Boolean {
        val hours = getHours(context, itemKey)
        if (hours <= 0) return false

        val req = getPrefs(context).getLong(PREFIX_REQ + itemKey, 0L)
        if (req == 0L) return true // armed, unlock not yet requested

        val elapsedMs    = System.currentTimeMillis() - req
        val cooldownMs   = hours * 60L * 60L * 1000L
        return elapsedMs < cooldownMs
    }

    /** Human-readable status string shown in the lock dialog. */
    fun getStatusText(context: Context, itemKey: String): String {
        val hours = getHours(context, itemKey)
        if (hours <= 0) return "No strict mode set."

        val req = getPrefs(context).getLong(PREFIX_REQ + itemKey, 0L)
        if (req == 0L) return "🔒 Locked for $hours hour(s). No unlock requested yet."

        val elapsedMs  = System.currentTimeMillis() - req
        val cooldownMs = hours * 60L * 60L * 1000L
        val remaining  = cooldownMs - elapsedMs

        return if (remaining > 0) {
            val h = remaining / 3_600_000
            val m = (remaining % 3_600_000) / 60_000
            "🔒 Unlock in ${h}h ${m}m"
        } else {
            "🔓 Unlock cooldown elapsed. You may delete now."
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun getHours(context: Context, itemKey: String): Int =
        getPrefs(context).getInt(PREFIX_HOURS + itemKey, 0)
}
