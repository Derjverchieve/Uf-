package devs.org.ultrafocus.utils

import android.content.Context

/**
 * Manages soft-blocking for individual apps.
 *
 * A soft-blocked app is not outright blocked — instead, when opened, the user
 * is presented with a randomly generated alphanumeric challenge code they must
 * type in full before access is granted. This creates deliberate friction that
 * discourages mindless, habitual opening without making the app completely
 * inaccessible.
 *
 * Challenge codes are generated fresh on every access attempt so they cannot
 * be memorised. Characters that are visually ambiguous (0/O, 1/l/I) are
 * excluded to ensure the code is legible.
 *
 * New in this version:
 *  - Soft block strict mode: once armed, the soft block toggle itself is locked
 *    behind a countdown — can't be turned off easily.
 *  - Configurable access duration: after passing the challenge the app is
 *    accessible for a user-set number of minutes, then the block rearms.
 *  - Configurable code length: challenge code length is set per-app.
 */
object SoftBlockManager {

    private const val PREF_NAME = "SoftBlockPrefs"
    private const val PREFIX    = "soft_"

    // Per-app access duration (minutes after passing challenge before kicked out again)
    private const val PREFIX_DURATION     = "soft_duration_"
    const val DEFAULT_DURATION_MINUTES    = 5

    // Per-app challenge code length
    private const val PREFIX_CODE_LEN    = "soft_code_len_"
    const val DEFAULT_CODE_LENGTH        = 14
    const val MIN_CODE_LENGTH            = 4
    const val MAX_CODE_LENGTH            = 64

    // Soft block strict mode (locks the soft-block toggle itself)
    private const val PREF_STRICT          = "SoftBlockStrictPrefs"
    private const val PREFIX_STRICT_HOURS  = "sb_strict_hours_"
    private const val PREFIX_STRICT_REQ    = "sb_strict_req_"

    // Unambiguous alphanumeric charset — excludes 0, O, 1, l, I
    private const val CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

    // ── Soft block on/off ─────────────────────────────────────────────────────

    fun isSoftBlocked(context: Context, packageName: String): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFIX + packageName, false)

    fun setSoftBlock(context: Context, packageName: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREFIX + packageName, enabled)
            .apply()
    }

    fun getSoftBlockedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).all
            .filter { it.value == true && it.key.startsWith(PREFIX) }
            .map { it.key.removePrefix(PREFIX) }
            .toSet()

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ── Access duration (minutes) ─────────────────────────────────────────────

    /** How many minutes of access are granted after passing the challenge. */
    fun getAccessDurationMinutes(context: Context, packageName: String): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(PREFIX_DURATION + packageName, DEFAULT_DURATION_MINUTES)

    fun setAccessDurationMinutes(context: Context, packageName: String, minutes: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PREFIX_DURATION + packageName, minutes.coerceAtLeast(1))
            .apply()
    }

    // ── Code length ───────────────────────────────────────────────────────────

    fun getCodeLength(context: Context, packageName: String): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(PREFIX_CODE_LEN + packageName, DEFAULT_CODE_LENGTH)

    fun setCodeLength(context: Context, packageName: String, length: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PREFIX_CODE_LEN + packageName, length.coerceIn(MIN_CODE_LENGTH, MAX_CODE_LENGTH))
            .apply()
    }

    // ── Challenge generation ──────────────────────────────────────────────────

    /**
     * Generates a fresh random challenge code using the configured length for
     * this package (falls back to [DEFAULT_CODE_LENGTH] if not set).
     * A new code is produced on every call so users cannot memorise it.
     */
    fun generateChallenge(context: Context, packageName: String): String {
        val length = getCodeLength(context, packageName)
        return (1..length).map { CHARS.random() }.joinToString("")
    }

    // ── Soft block strict mode ────────────────────────────────────────────────

    private fun getStrictPrefs(context: Context) =
        context.getSharedPreferences(PREF_STRICT, Context.MODE_PRIVATE)

    /**
     * Arms strict mode on the soft block toggle for [packageName].
     * Once set, the soft block cannot be turned off until [hours] hours have
     * elapsed after an unlock request (verified via TypeToAccess challenge).
     * Cannot reduce the delay on an already-locked item.
     */
    fun setSoftBlockStrict(context: Context, packageName: String, hours: Int) {
        if (hours <= 0) {
            clearSoftBlockStrict(context, packageName)
            return
        }
        if (isSoftBlockLocked(context, packageName)) {
            val current = getSoftBlockStrictHours(context, packageName)
            if (hours <= current) return   // can only strengthen, never weaken
        }
        getStrictPrefs(context).edit()
            .putInt(PREFIX_STRICT_HOURS + packageName, hours)
            .putLong(PREFIX_STRICT_REQ + packageName, 0L)
            .apply()
    }

    /** Fully removes soft block strict mode (call when soft block is disabled after unlock). */
    fun clearSoftBlockStrict(context: Context, packageName: String) {
        getStrictPrefs(context).edit()
            .remove(PREFIX_STRICT_HOURS + packageName)
            .remove(PREFIX_STRICT_REQ + packageName)
            .apply()
    }

    /**
     * True while the soft block toggle is locked and cannot be disabled.
     * Locked = hours configured AND (no unlock requested OR cooldown not elapsed).
     */
    fun isSoftBlockLocked(context: Context, packageName: String): Boolean {
        val hours = getSoftBlockStrictHours(context, packageName)
        if (hours <= 0) return false
        val req = getStrictPrefs(context).getLong(PREFIX_STRICT_REQ + packageName, 0L)
        if (req == 0L) return true
        val elapsed   = System.currentTimeMillis() - req
        val cooldownMs = hours * 60L * 60L * 1000L
        return elapsed < cooldownMs
    }

    /** True if strict mode is configured (whether locked or in cooldown). */
    fun isSoftBlockStrictEnabled(context: Context, packageName: String): Boolean =
        getSoftBlockStrictHours(context, packageName) > 0

    fun getSoftBlockStrictHours(context: Context, packageName: String): Int =
        getStrictPrefs(context).getInt(PREFIX_STRICT_HOURS + packageName, 0)

    /** Starts the unlock countdown. Must have passed TypeToAccess first. */
    fun requestSoftBlockUnlock(context: Context, packageName: String) {
        getStrictPrefs(context).edit()
            .putLong(PREFIX_STRICT_REQ + packageName, System.currentTimeMillis())
            .apply()
    }

    /** Cancels a pending unlock without clearing strict mode. */
    fun cancelSoftBlockUnlockRequest(context: Context, packageName: String) {
        getStrictPrefs(context).edit()
            .putLong(PREFIX_STRICT_REQ + packageName, 0L)
            .apply()
    }

    /** Human-readable countdown string for the soft block lock dialog. */
    fun getSoftBlockStatusText(context: Context, packageName: String): String {
        val hours = getSoftBlockStrictHours(context, packageName)
        if (hours <= 0) return "No strict mode set."
        val req = getStrictPrefs(context).getLong(PREFIX_STRICT_REQ + packageName, 0L)
        if (req == 0L) return "🔒 Soft block locked for $hours hour(s). No unlock requested yet."
        val elapsed    = System.currentTimeMillis() - req
        val cooldownMs = hours * 60L * 60L * 1000L
        val remaining  = cooldownMs - elapsed
        return if (remaining > 0) {
            val h = remaining / 3_600_000
            val m = (remaining % 3_600_000) / 60_000
            val s = (remaining % 60_000) / 1_000
            "🔒 Soft block unlock in %02dh %02dm %02ds".format(h, m, s)
        } else {
            "🔓 Unlock cooldown elapsed. You may disable soft block now."
        }
    }
}
