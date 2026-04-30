package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object PunishmentManager {
    private const val PREF_NAME = "PunishmentPrefs"
    private const val KEY_LEVEL = "punishment_level"
    private const val KEY_LAST_DATE = "last_punishment_date"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getTodayDate(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
    }

    fun getCurrentLevel(context: Context): Int {
        val prefs = getPrefs(context)
        val lastDate = prefs.getString(KEY_LAST_DATE, "")
        val today = getTodayDate()

        // If it's a new day, reset level to 0
        if (lastDate != today) {
            resetLevel(context)
            return 0
        }

        return prefs.getInt(KEY_LEVEL, 0)
    }

    fun incrementLevel(context: Context) {
        val prefs = getPrefs(context)
        val current = getCurrentLevel(context)
        val today = getTodayDate()

        // Cap at level 3 (512 characters) because beyond that is just cruel/unusable
        val next = if (current < 3) current + 1 else 3

        prefs.edit()
            .putInt(KEY_LEVEL, next)
            .putString(KEY_LAST_DATE, today)
            .apply()
    }

    fun resetLevel(context: Context) {
        getPrefs(context).edit()
            .putInt(KEY_LEVEL, 0)
            .putString(KEY_LAST_DATE, getTodayDate())
            .apply()
    }

    fun getChallengeLength(context: Context): Int {
        val level = getCurrentLevel(context)
        // Level 0 = 64
        // Level 1 = 128
        // Level 2 = 256
        // Level 3 = 512
        return 64 * (1 shl level) // Bitwise shift to double: 64 * 2^level
    }
}