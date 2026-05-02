package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.regex.Pattern
import java.util.Calendar

object ContentBlockManager {
    private const val PREF_NAME = "ContentBlockPrefs"
    private const val KEY_BLOCKED_KEYWORDS = "blocked_keywords"
    private const val PREF_SCHEDULE_PREFIX = "sched_key_"

    // Cache compiled patterns for performance
    private var cachedPatterns: Map<String, Regex>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getKeywords(context: Context): MutableSet<String> {
        return getPrefs(context)
            .getStringSet(KEY_BLOCKED_KEYWORDS, mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()
    }

    fun addKeyword(context: Context, keyword: String, schedule: String?) {
        val currentList = getKeywords(context)
        val cleanWord = keyword.trim()
        currentList.add(cleanWord)

        getPrefs(context).edit()
            .putStringSet(KEY_BLOCKED_KEYWORDS, currentList)
            .putString(PREF_SCHEDULE_PREFIX + cleanWord, schedule ?: "")
            .apply()

        cachedPatterns = null // invalidate cache
    }

    fun removeKeyword(context: Context, keyword: String) {
        val currentList = getKeywords(context)
        currentList.remove(keyword)

        getPrefs(context).edit()
            .putStringSet(KEY_BLOCKED_KEYWORDS, currentList)
            .remove(PREF_SCHEDULE_PREFIX + keyword)
            .apply()

        cachedPatterns = null
    }

    fun getSchedule(context: Context, keyword: String): String {
        return getPrefs(context).getString(PREF_SCHEDULE_PREFIX + keyword, "") ?: ""
    }

    fun containsBlockedContent(context: Context, textOnScreen: String?): Boolean {
        if (textOnScreen.isNullOrEmpty()) return false

        // Lazy-load compiled patterns
        if (cachedPatterns == null) {
            val keywords = getKeywords(context)
            cachedPatterns = keywords.associateWith { keyword ->
                // FIX: Use Unicode-aware negative lookarounds instead of \b
                // \b is ASCII-only and misses cases like "TikToker" matching "TikTok"
                // (?<![\\p{L}\\p{N}_]) = not preceded by letter/digit/underscore
                // (?![\\p{L}\\p{N}_])  = not followed by letter/digit/underscore
                // This ensures EXACT whole-word matching only.
                val escaped = Pattern.quote(keyword)
                "(?i)(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])".toRegex()
            }
        }

        val matchedKeywords = cachedPatterns!!.filter { (_, pattern) ->
            pattern.containsMatchIn(textOnScreen)
        }.keys

        if (matchedKeywords.isEmpty()) return false

        // Check time schedule for matched keywords
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (keyword in matchedKeywords) {
            val schedule = getSchedule(context, keyword)
            if (schedule.isEmpty()) return true // No schedule = block 24/7

            val ranges = schedule.split(",")
            for (range in ranges) {
                val parts = range.split("-")
                if (parts.size == 2) {
                    try {
                        val start = parseTime(parts[0])
                        val end = parseTime(parts[1])
                        if (currentMinute in start..end) return true
                    } catch (_: Exception) {}
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
