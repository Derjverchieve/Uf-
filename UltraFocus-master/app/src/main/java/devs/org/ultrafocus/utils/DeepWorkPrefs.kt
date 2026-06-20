package devs.org.ultrafocus.utils

import android.content.Context

object DeepWorkPrefs {
    private const val PREFS = "deep_work_prefs"
    private const val KEY_PACKAGE = "primary_app_package"
    private const val KEY_NAME = "primary_app_name"
    private const val KEY_MINUTES = "target_minutes"

    fun saveLastPrimaryApp(context: Context, packageName: String, appName: String) {
        prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_NAME, appName)
            .apply()
    }

    fun getLastPrimaryAppPackage(context: Context): String? = prefs(context).getString(KEY_PACKAGE, null)
    fun getLastPrimaryAppName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun saveLastTargetMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MINUTES, minutes).apply()
    }

    fun getLastTargetMinutes(context: Context): Int = prefs(context).getInt(KEY_MINUTES, 45)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
