package devs.org.ultrafocus.utils

import android.content.Context

/**
 * Persistent storage for kiosk mode configuration.
 *
 * Allowed apps are stored as a flat string "pkg|label,pkg|label,..." so that
 * both the package set AND the display labels survive across restarts without
 * needing a separate DB table.
 */
object KioskPrefs {
    private const val PREFS_NAME = "ultrafocus_kiosk"
    private const val KEY_ENABLED = "kiosk_enabled"
    private const val KEY_ALLOWED_LABELS = "allowed_labels"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isKioskEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setKioskEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Returns a set of just the package names — fast for the blocker's membership check. */
    fun getAllowedPackages(ctx: Context): Set<String> =
        getAllowedApps(ctx).keys

    /** Returns a package → label map for all apps the user has added to the allowed list. */
    fun getAllowedApps(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(KEY_ALLOWED_LABELS, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull { entry ->
                val idx = entry.indexOf('|')
                if (idx < 1) null else entry.substring(0, idx) to entry.substring(idx + 1)
            }
            .toMap()
    }

    fun setAllowedApps(ctx: Context, apps: Map<String, String>) {
        val raw = apps.entries.joinToString(",") { "${it.key}|${it.value}" }
        prefs(ctx).edit().putString(KEY_ALLOWED_LABELS, raw).apply()
    }

    fun addAllowedApp(ctx: Context, packageName: String, label: String) {
        val current = getAllowedApps(ctx).toMutableMap()
        current[packageName] = label
        setAllowedApps(ctx, current)
    }

    fun removeAllowedApp(ctx: Context, packageName: String) {
        val current = getAllowedApps(ctx).toMutableMap()
        current.remove(packageName)
        setAllowedApps(ctx, current)
    }
}
