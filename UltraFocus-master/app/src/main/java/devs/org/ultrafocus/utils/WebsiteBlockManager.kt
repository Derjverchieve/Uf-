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
        // Store just the host if possible (e.g. "youtube.com")
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

    fun getSchedule(context: Context, url: String): String {
        return getPrefs(context).getString(PREF_SCHEDULE_PREFIX + url, "") ?: ""
    }

    fun shouldBlockUrl(context: Context, urlDetected: String?): Boolean {
        if (urlDetected.isNullOrEmpty()) return false

        // 1. Get the domain of the site the user is visiting
        val detectedHost = extractHost(urlDetected)

        val blockedList = getBlockedSites(context)

        // 2. Check if this host matches any blocked host
        val matchedSite = blockedList.find { blockedSite ->
            // Logic: Block if exact match OR if it's a subdomain (e.g., m.youtube.com ends with youtube.com)
            detectedHost.equals(blockedSite, ignoreCase = true) ||
                    detectedHost.endsWith(".$blockedSite", ignoreCase = true)
        } ?: return false

        // 3. Check Temporary Access (Did they type the UUID?)
        if (TemporaryAccessManager.isAllowed(matchedSite)) return false

        // 4. Check Schedule
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
                } catch (e: Exception) {}
            }
        }
        return false
    }

    // Helper: extracts "youtube.com" from "https://www.youtube.com/watch?v=..."
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