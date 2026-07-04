package devs.org.ultrafocus.utils

import android.content.Context

object DeepWorkPrefs {
    private const val PREFS = "deep_work_prefs"
    private const val KEY_PACKAGE = "primary_app_package"
    private const val KEY_NAME = "primary_app_name"
    private const val KEY_MINUTES = "target_minutes"
    private const val KEY_CYCLES_ENABLED = "cycles_enabled"
    private const val KEY_CYCLES_COUNT = "cycles_count"
    private const val KEY_BREAK_MINUTES = "break_minutes"

    // Hard-lock sessions can't be ended or cancelled early, so the ceiling on
    // how long you can lock yourself in for matters — 60 minutes keeps the
    // worst case bounded. Single source of truth: DeepWorkSessionActivity
    // validates against this same constant rather than a duplicated number.
    const val MAX_TARGET_MINUTES = 60

    // Cycle plans chain multiple work+break pairs with the SAME hard lock
    // applied across the whole sequence — can't cancel until it's over, same
    // as a single session, just extended across all cycles. Worst case with
    // these bounds: MAX_CYCLES × MAX_TARGET_MINUTES work (4×60=4hr) +
    // MAX_CYCLES × MIN_BREAK_MINUTES break at the floor (4×10=40min).
    const val MIN_CYCLES = 2
    const val MAX_CYCLES = 4
    const val MIN_BREAK_MINUTES = 10
    const val MAX_BREAK_MINUTES = 60

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

    fun saveCyclesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CYCLES_ENABLED, enabled).apply()
    }

    fun getCyclesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CYCLES_ENABLED, false)

    fun saveCyclesCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_CYCLES_COUNT, count.coerceIn(MIN_CYCLES, MAX_CYCLES)).apply()
    }

    fun getCyclesCount(context: Context): Int =
        prefs(context).getInt(KEY_CYCLES_COUNT, MAX_CYCLES).coerceIn(MIN_CYCLES, MAX_CYCLES)

    fun saveBreakMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_BREAK_MINUTES, minutes.coerceIn(MIN_BREAK_MINUTES, MAX_BREAK_MINUTES)).apply()
    }

    fun getBreakMinutes(context: Context): Int =
        prefs(context).getInt(KEY_BREAK_MINUTES, 15).coerceIn(MIN_BREAK_MINUTES, MAX_BREAK_MINUTES)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
