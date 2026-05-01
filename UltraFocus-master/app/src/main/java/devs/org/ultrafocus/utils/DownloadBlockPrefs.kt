package devs.org.ultrafocus.utils

import android.content.Context

object DownloadBlockPrefs {

    private const val PREF_NAME = "download_block_prefs"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
