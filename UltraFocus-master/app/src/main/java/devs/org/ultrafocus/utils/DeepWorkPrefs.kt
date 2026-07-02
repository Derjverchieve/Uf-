package devs.org.ultrafocus.utils

import android.content.Context

object DeepWorkPrefs {
    private const val PREFS = "deep_work_prefs"
    private const val KEY_PACKAGE = "primary_app_package"
    private const val KEY_NAME = "primary_app_name"
    private const val KEY_MINUTES = "target_minutes"

    // Hard-lock sessions can't be ended or cancelled early, so the ceiling on
    // how long you can lock yourself in for matters — 60 minutes keeps the
    // worst case bounded. Single source of truth: DeepWorkSessionActivity
    // validates against this same constant rather than a duplicated number.
    const val MAX_TARGET_MINUTES = 60

    fun saveLastPrimaryApp(context: Context, packageName: String, appName: String) {
        prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_NAME, appName)
            .apply()
    }

    fun getLastPrimaryAppPackage(context: Context): String? = prefs(context).getString(KEY_PACKAGE, null)
    fun getLastPrimaryAppName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun saveLastTargetMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MINUTES, minutes.coerceIn(1, MAX_TARGET_MINUTES)).apply()
    }

    fun getLastTargetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_MINUTES, 45).coerceIn(1, MAX_TARGET_MINUTES)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
