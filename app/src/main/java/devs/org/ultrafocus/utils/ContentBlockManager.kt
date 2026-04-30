package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.regex.Pattern

object ContentBlockManager {
    private const val PREF_NAME = "ContentBlockPrefs"
    private const val KEY_BLOCKED_KEYWORDS = "blocked_keywords"

    // Cache the patterns to make scanning 10x faster
    private var cachedPatterns: List<Regex>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getKeywords(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_KEYWORDS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    fun addKeyword(context: Context, keyword: String) {
        val currentList = getKeywords(context)
        currentList.add(keyword.trim())
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_KEYWORDS, currentList).apply()
        cachedPatterns = null // Reset cache
    }

    fun removeKeyword(context: Context, keyword: String) {
        val currentList = getKeywords(context)
        currentList.remove(keyword)
        getPrefs(context).edit().putStringSet(KEY_BLOCKED_KEYWORDS, currentList).apply()
        cachedPatterns = null // Reset cache
    }

    fun containsBlockedContent(context: Context, textOnScreen: String?): Boolean {
        if (textOnScreen.isNullOrEmpty()) return false

        // Lazy load patterns once, reuse forever
        if (cachedPatterns == null) {
            val keywords = getKeywords(context)
            cachedPatterns = keywords.map { keyword ->
                val clean = Pattern.quote(keyword)
                // Regex: Case insensitive, Word Boundary
                "(?i)\\b$clean\\b".toRegex()
            }
        }

        // Fast check
        return cachedPatterns!!.any { it.containsMatchIn(textOnScreen) }
    }
}