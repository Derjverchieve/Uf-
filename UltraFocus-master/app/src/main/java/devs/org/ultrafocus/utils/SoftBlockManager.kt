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
 */
object SoftBlockManager {

    private const val PREF_NAME = "SoftBlockPrefs"
    private const val PREFIX    = "soft_"

    // Unambiguous alphanumeric charset — excludes 0, O, 1, l, I
    private const val CHARS =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

    // ── State ─────────────────────────────────────────────────────────────────

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

    // ── Challenge generation ──────────────────────────────────────────────────

    /**
     * Generates a fresh random challenge code of 12–16 characters.
     * A new code is produced on every call so users cannot memorise it.
     */
    fun generateChallenge(): String {
        val length = (12..16).random()
        return (1..length).map { CHARS.random() }.joinToString("")
    }
}
