package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.Calendar

object WebsiteBlockManager {
    private const val PREF_NAME = "WebsiteBlockPrefs"
    private const val KEY_BLOCKED_SITES = "blocked_sites"
    private const val PREF_SCHEDULE_PREFIX = "sched_web_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getBlockedSites(context: Context): MutableSet<String> {
        return getPrefs(context).getStringSet(KEY_BLOCKED_SITES, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    fun addSite(context: Context, url: String, schedule: String?) {
        val currentList = getBlockedSites(context)
        val cleanUrl = extractHost(url)
        currentList.add(cleanUrl)

        val editor = getPrefs(context).edit()
        editor.putStringSet(KEY_BLOCKED_SITES, currentList)
        editor.putString(PREF_SCHEDULE_PREFIX + cleanUrl, schedule ?: "")
        editor.apply()
    }

    fun removeSite(context: Context, url: String) {
        val currentList = getBlockedSites(context)
        currentList.remove(url)
        val editor = getPrefs(context).edit()
        editor.putStringSet(KEY_BLOCKED_SITES, currentList)
        editor.remove(PREF_SCHEDULE_PREFIX + url)
        editor.apply()
    }

    fun clearAll(context: Context) {
        val currentList = getBlockedSites(context)
        val editor = getPrefs(context).edit()

        currentList.forEach { site ->
            editor.remove(PREF_SCHEDULE_PREFIX + site)
        }

        editor.remove(KEY_BLOCKED_SITES)
        editor.apply()
    }

    fun getSchedule(context: Context, url: String): String {
        return getPrefs(context).getString(PREF_SCHEDULE_PREFIX + url, "") ?: ""
    }

    fun shouldBlockUrl(context: Context, urlDetected: String?): Boolean {
        if (urlDetected.isNullOrEmpty()) return false

        val detectedHost = extractHost(urlDetected)
        val blockedList = getBlockedSites(context)

        val matchedSite = blockedList.find { blockedSite ->
            detectedHost.equals(blockedSite, ignoreCase = true) ||
                    detectedHost.endsWith(".$blockedSite", ignoreCase = true)
        } ?: return false

        if (TemporaryAccessManager.isAllowed(matchedSite)) return false

        val schedule = getSchedule(context, matchedSite)
        if (schedule.isEmpty()) return true

        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val ranges = schedule.split(",")

        for (range in ranges) {
            val parts = range.split("-")
            if (parts.size == 2) {
                try {
                    val start = parseTime(parts[0])
                    val end = parseTime(parts[1])
                    if (currentMinute in start..end) return true
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    private fun extractHost(url: String): String {
        var clean = url.lowercase()
        if (!clean.startsWith("http")) {
            clean = "https://$clean"
        }
        return try {
            Uri.parse(clean).host ?: url.lowercase()
        } catch (e: Exception) {
            url.lowercase()
        }
    }

    private fun parseTime(t: String): Int {
        val split = t.trim().split(":")
        return split[0].toInt() * 60 + split[1].toInt()
    }
}
