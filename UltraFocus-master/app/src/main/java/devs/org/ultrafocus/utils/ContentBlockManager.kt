package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.regex.Pattern
import java.util.Calendar

object ContentBlockManager {
    private const val PREF_NAME = "ContentBlockPrefs"
    private const val KEY_BLOCKED_KEYWORDS = "blocked_keywords"
    private const val PREF_SCHEDULE_PREFIX = "sched_key_" // Storage prefix for schedules

    // Cache to make regex faster
    private var cachedPatterns: Map<String, Regex>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getKeywords(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_KEYWORDS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    // UPDATED: Now accepts a schedule string (e.g. "09:00-12:00")
    fun addKeyword(context: Context, keyword: String, schedule: String?) {
        val currentList = getKeywords(context)
        val cleanWord = keyword.trim()
        currentList.add(cleanWord)

        val editor = getPrefs(context).edit()
        editor.putStringSet(KEY_BLOCKED_KEYWORDS, currentList)
        // Save the schedule
        editor.putString(PREF_SCHEDULE_PREFIX + cleanWord, schedule ?: "")
        editor.apply()

        cachedPatterns = null // Clear cache to rebuild
    }

    fun removeKeyword(context: Context, keyword: String) {
        val currentList = getKeywords(context)
        currentList.remove(keyword)

        val editor = getPrefs(context).edit()
        editor.putStringSet(KEY_BLOCKED_KEYWORDS, currentList)
        editor.remove(PREF_SCHEDULE_PREFIX + keyword)
        editor.apply()

        cachedPatterns = null
    }

    fun getSchedule(context: Context, keyword: String): String {
        return getPrefs(context).getString(PREF_SCHEDULE_PREFIX + keyword, "") ?: ""
    }

    fun containsBlockedContent(context: Context, textOnScreen: String?): Boolean {
        if (textOnScreen.isNullOrEmpty()) return false

        // Lazy load patterns + their schedules
        if (cachedPatterns == null) {
            val keywords = getKeywords(context)
            cachedPatterns = keywords.associateWith { keyword ->
                val clean = Pattern.quote(keyword)
                "(?i)\\b$clean\\b".toRegex()
            }
        }

        // 1. Find matching keywords
        val matchedKeywords = cachedPatterns!!.filter { entry ->
            entry.value.containsMatchIn(textOnScreen)
        }.keys

        if (matchedKeywords.isEmpty()) return false

        // 2. Check Time Schedule for the matches
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (keyword in matchedKeywords) {
            val schedule = getSchedule(context, keyword)

            // If empty schedule -> Block 24/7
            if (schedule.isEmpty()) return true

            // Parse ranges: "09:00-12:00,14:00-16:00"
            val ranges = schedule.split(",")
            for (range in ranges) {
                val parts = range.split("-")
                if (parts.size == 2) {
                    try {
                        val start = parseTime(parts[0])
                        val end = parseTime(parts[1])
                        // If we match the text AND the time, block it
                        if (currentMinute in start..end) return true
                    } catch (e: Exception) {}
                }
            }
        }

        return false
    }

    private fun parseTime(t: String): Int {
        val split = t.trim().split(":")
        return split[0].toInt() * 60 + split[1].toInt()
    }
}