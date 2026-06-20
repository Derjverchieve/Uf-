package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.concurrent.TimeUnit

/**
 * Web allowlist mode — when enabled, ALL websites are blocked except those
 * explicitly added to the allowlist. Google and its subdomains are always
 * implicitly allowed so the user is never stranded.
 *
 * Strict mode works the same way as DownloadBlockPrefs strict mode:
 *   - setStrictMode(hours) arms the lock.
 *   - requestUnlock() starts the countdown.
 *   - isLocked() returns true until the countdown elapses.
 *   - The enabled toggle is gated behind isLocked() so the user cannot
 *     turn off allowlist mode while the strict lock is active.
 */
object WebAllowlistManager {

    private const val PREF_NAME          = "WebAllowlistPrefs"
    private const val KEY_ENABLED        = "enabled"
    private const val KEY_ALLOWED_HOSTS  = "allowed_hosts"
    private const val KEY_STRICT_HOURS   = "strict_hours"
    private const val KEY_STRICT_REQ     = "strict_req"

    // These hosts (and all their subdomains) are always allowed when allowlist
    // mode is on — the user can never accidentally block Google.
    private val ALWAYS_ALLOWED_HOSTS = setOf(
        "google.com",
        "googleapis.com",
        "gstatic.com",
        "googleusercontent.com",
        "accounts.google.com"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Enabled state ─────────────────────────────────────────────────────────

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Toggle allowlist mode. Blocked by strict mode lock if active.
     * Returns false if the change was rejected due to a strict lock.
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!enabled && isLocked(context)) return false
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        return true
    }

    // ── Allowlist management ──────────────────────────────────────────────────

    fun getAllowedHosts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED_HOSTS, mutableSetOf())
            ?.toSet() ?: emptySet()

    fun addAllowedHost(context: Context, url: String) {
        val host = normalizeHost(url).takeIf { it.isNotBlank() } ?: return
        val current = getAllowedHosts(context).toMutableSet()
        current.add(host)
        prefs(context).edit().putStringSet(KEY_ALLOWED_HOSTS, current).apply()
    }

    fun removeAllowedHost(context: Context, url: String) {
        val host = normalizeHost(url).takeIf { it.isNotBlank() } ?: return
        val current = getAllowedHosts(context).toMutableSet()
        current.remove(host)
        prefs(context).edit().putStringSet(KEY_ALLOWED_HOSTS, current).apply()
    }

    /**
     * Returns true if the given URL should be BLOCKED by the allowlist.
     * Returns false if:
     *   - Allowlist mode is off.
     *   - The host is in ALWAYS_ALLOWED_HOSTS.
     *   - The host is in the user's custom allowlist.
     *   - TemporaryAccessManager has granted a grace period for this host.
     */
    fun isBlockedByAllowlist(context: Context, url: String): Boolean {
        if (!isEnabled(context)) return false

        val host = normalizeHost(url).takeIf { it.isNotBlank() } ?: return false

        // Always-allowed check (Google family)
        if (ALWAYS_ALLOWED_HOSTS.any { allowed ->
                host == allowed || host.endsWith(".$allowed")
            }) return false

        // TemporaryAccessManager grace period
        if (TemporaryAccessManager.isAllowed(host)) return false

        // User custom allowlist
        val userAllowed = getAllowedHosts(context)
        if (userAllowed.any { allowed ->
                host == allowed || host.endsWith(".$allowed")
            }) return false

        return true
    }

    private fun normalizeHost(url: String): String {
        var clean = url.trim()
        if (clean.isBlank()) return ""
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return try {
            val uri = Uri.parse(clean)
            uri.host?.trim()?.removePrefix("www.")?.lowercase() ?: ""
        } catch (_: Exception) { "" }
    }

    // ── Strict mode ───────────────────────────────────────────────────────────

    fun getStrictHours(context: Context): Int =
        prefs(context).getInt(KEY_STRICT_HOURS, 0)

    fun isStrictModeEnabled(context: Context): Boolean =
        getStrictHours(context) > 0

    fun setStrictMode(context: Context, hours: Int) {
        if (hours <= 0) {
            clearStrictMode(context)
            return
        }
        prefs(context).edit()
            .putInt(KEY_STRICT_HOURS, hours)
            .putLong(KEY_STRICT_REQ, 0L)
            .apply()
    }

    fun clearStrictMode(context: Context) {
        prefs(context).edit()
            .remove(KEY_STRICT_HOURS)
            .remove(KEY_STRICT_REQ)
            .apply()
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

    fun getStrictStatusText(context: Context): String {
        val hours = getStrictHours(context)
        if (hours <= 0) return "Strict mode: not configured"
        val req = getRequestTimestamp(context)
        if (req == 0L) return "🔒 Strict lock active — allowlist mode cannot be turned off (${hours}h delay)"
        val remaining = getTimeRemaining(context)
        if (remaining <= 0L) return "🔓 Cooldown elapsed — you can now disable allowlist mode"
        val h = TimeUnit.MILLISECONDS.toHours(remaining)
        val m = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(remaining) % 60
        return "⏳ Strict lock lifts in %02d:%02d:%02d".format(h, m, s)
    }
}
