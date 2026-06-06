package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.util.Calendar

enum class WebBlockMode {
    GENERAL,
    SPECIFIC
}

data class WebBlockRule(
    val mode: WebBlockMode,
    val host: String,
    val path: String = ""
)

object WebsiteBlockManager {
    private const val PREF_NAME = "WebsiteBlockPrefs"
    private const val KEY_BLOCKED_RULES = "blocked_web_rules"
    private const val PREF_SCHEDULE_PREFIX = "sched_web_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getRules(context: Context): MutableSet<WebBlockRule> {
        val prefs = getPrefs(context)
        val raw = prefs.getStringSet(KEY_BLOCKED_RULES, mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()
        val decoded = raw.mapNotNull { decodeRule(it) }.toMutableSet()
        if (decoded.size != raw.size) saveRules(context, decoded)
        return decoded
    }

    // Keep this for backward compat with any UI that reads host-only list
    fun getBlockedSites(context: Context): MutableSet<String> {
        return getRules(context)
            .filter { it.mode == WebBlockMode.GENERAL }
            .mapTo(mutableSetOf()) { it.host }
    }

    fun addSite(
        context: Context,
        url: String,
        schedule: String?,
        mode: WebBlockMode = WebBlockMode.GENERAL
    ) {
        val rule = buildRule(url, mode) ?: return
        val currentList = getRules(context)
        // Remove any existing rule with same mode+host+path before adding
        currentList.removeAll {
            it.mode == rule.mode && it.host == rule.host && it.path == rule.path
        }
        currentList.add(rule)
        getPrefs(context).edit()
            .putStringSet(
                KEY_BLOCKED_RULES,
                currentList.mapTo(mutableSetOf()) { encodeRule(it) }
            )
            .putString(PREF_SCHEDULE_PREFIX + ruleKey(rule), schedule?.trim().orEmpty())
            .apply()
    }

    fun removeSite(context: Context, url: String, mode: WebBlockMode = WebBlockMode.GENERAL) {
        val rule = buildRule(url, mode) ?: return
        val currentList = getRules(context)
        currentList.removeAll {
            it.mode == rule.mode && it.host == rule.host && it.path == rule.path
        }
        getPrefs(context).edit()
            .putStringSet(
                KEY_BLOCKED_RULES,
                currentList.mapTo(mutableSetOf()) { encodeRule(it) }
            )
            .remove(PREF_SCHEDULE_PREFIX + ruleKey(rule))
            .apply()
    }

    fun getSchedule(
        context: Context,
        url: String,
        mode: WebBlockMode = WebBlockMode.GENERAL
    ): String {
        val rule = buildRule(url, mode) ?: return ""
        return getPrefs(context).getString(PREF_SCHEDULE_PREFIX + ruleKey(rule), "") ?: ""
    }

    /**
     * Returns the saved schedule for a rule directly by its key — no parseUrl round-trip.
     * Used internally and by the UI to display a schedule indicator next to each entry.
     */
    fun getScheduleForRule(context: Context, rule: WebBlockRule): String =
        getPrefs(context).getString(PREF_SCHEDULE_PREFIX + ruleKey(rule), "") ?: ""

    fun shouldBlockUrl(context: Context, urlDetected: String?): Boolean {
        if (urlDetected.isNullOrBlank()) return false
        val detected = parseUrl(urlDetected) ?: return false
        val detectedHost = detected.host.lowercase()
        val detectedPath = normalizePath(detected.path)
        val blockedRules = getRules(context)

        // --- GENERAL rules: block the entire host ---
        val generalMatch = blockedRules.firstOrNull { rule ->
            rule.mode == WebBlockMode.GENERAL &&
                (detectedHost.equals(rule.host, ignoreCase = true) ||
                    detectedHost.endsWith(".${rule.host}", ignoreCase = true))
        }
        if (generalMatch != null) {
            if (TemporaryAccessManager.isAllowed(generalMatch.host)) return false
            return scheduleAllowsBlock(context, generalMatch)
        }

        // --- SPECIFIC rules: block the host EXCEPT the whitelisted path ---
        val specificRulesForHost = blockedRules.filter { rule ->
            rule.mode == WebBlockMode.SPECIFIC &&
                (detectedHost.equals(rule.host, ignoreCase = true) ||
                    detectedHost.endsWith(".${rule.host}", ignoreCase = true))
        }
        if (specificRulesForHost.isEmpty()) return false

        // If the current path matches a whitelisted specific rule → allow
        val allowedBySpecificRule = specificRulesForHost.any { rule ->
            matchesSpecificAllowRule(rule, detectedHost, detectedPath)
        }
        if (allowedBySpecificRule) return false

        // Current path is NOT in the whitelist → block
        val hostKey = specificRulesForHost.first().host
        if (TemporaryAccessManager.isAllowed(hostKey)) return false
        return scheduleAllowsBlock(context, specificRulesForHost.first())
    }

    // Extracted host string for use in throttling keys
    fun normalizeHost(urlDetected: String): String {
        return parseUrl(urlDetected)?.host?.lowercase() ?: urlDetected.lowercase()
    }

    private fun scheduleAllowsBlock(context: Context, rule: WebBlockRule): Boolean {
        // Read the schedule directly using the rule's own key — avoids going through
        // buildRule/parseUrl which could produce a subtly different key (e.g. trailing
        // slash) and silently return "" (always-block) instead of the real schedule.
        val raw = getPrefs(context).getString(PREF_SCHEDULE_PREFIX + ruleKey(rule), "") ?: ""

        // Normalise all dash variants to ASCII hyphen before parsing.
        // Phone keyboards sometimes auto-replace "-" with an en-dash (–) or
        // em-dash (—), causing split("-") to return a single element,
        // parts.size == 2 to be false, and the range to be silently skipped.
        // When every range is skipped the function returns false (don't block)
        // even during the scheduled hours — making the schedule appear broken.
        val schedule = raw
            .replace('\u2013', '-')  // en-dash
            .replace('\u2014', '-')  // em-dash
            .replace('\u2212', '-')  // minus sign
            .trim()

        if (schedule.isEmpty()) return true   // no schedule → always block

        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (range in schedule.split(",")) {
            val trimmedRange = range.trim()
            // Accept both "HH:MM-HH:MM" and "HH:MM - HH:MM" (spaces around dash)
            val parts = trimmedRange.split("-")
            if (parts.size == 2) {
                try {
                    val start = parseTime(parts[0])
                    val end   = parseTime(parts[1])
                    if (currentMinute in start..end) return true
                } catch (_: Exception) { /* malformed range — skip */ }
            }
        }
        return false
    }

    private fun matchesSpecificAllowRule(
        rule: WebBlockRule,
        detectedHost: String,
        detectedPath: String
    ): Boolean {
        if (!detectedHost.equals(rule.host, ignoreCase = true) &&
            !detectedHost.endsWith(".${rule.host}", ignoreCase = true)) return false
        val rulePath = normalizePath(rule.path)
        if (rulePath.isBlank() || rulePath == "/") return true
        val path = if (detectedPath.isBlank()) "/" else detectedPath
        return path == rulePath || path.startsWith(rulePath.trimEnd('/') + "/")
    }

    private fun buildRule(url: String, mode: WebBlockMode): WebBlockRule? {
        val parsed = parseUrl(url) ?: return null
        val host = parsed.host.lowercase()
        if (host.isBlank()) return null
        return when (mode) {
            WebBlockMode.GENERAL -> WebBlockRule(mode = mode, host = host, path = "")
            WebBlockMode.SPECIFIC -> WebBlockRule(
                mode = mode,
                host = host,
                path = normalizePath(parsed.path)
            )
        }
    }

    private data class ParsedUrl(val host: String, val path: String)

    private fun parseUrl(url: String): ParsedUrl? {
        var clean = url.trim()
        if (clean.isBlank()) return null
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        val uri = try { Uri.parse(clean) } catch (_: Exception) { null } ?: return null
        val host = uri.host?.trim().orEmpty().removePrefix("www.").lowercase()
        if (host.isBlank()) return null
        return ParsedUrl(host = host, path = normalizePath(uri.path))
    }

    private fun normalizePath(path: String?): String {
        val p = path?.trim().orEmpty()
        if (p.isBlank()) return ""
        return if (p.startsWith("/")) p else "/$p"
    }

    private fun encodeRule(rule: WebBlockRule): String {
        val path = rule.path.replace("|", "%7C")
        return "${rule.mode.name}|${rule.host}|$path"
    }

    private fun decodeRule(raw: String): WebBlockRule? {
        val parts = raw.split("|")
        if (parts.size < 2) return null
        return try {
            val mode = WebBlockMode.valueOf(parts[0])
            val host = parts[1].trim().lowercase()
            val path = if (parts.size >= 3)
                parts.drop(2).joinToString("|").replace("%7C", "|")
            else ""
            if (host.isBlank()) null
            else WebBlockRule(mode = mode, host = host, path = normalizePath(path))
        } catch (_: Exception) { null }
    }

    private fun ruleKey(rule: WebBlockRule): String {
        return when (rule.mode) {
            WebBlockMode.GENERAL -> rule.host
            WebBlockMode.SPECIFIC -> rule.host + rule.path
        }
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_BLOCKED_RULES)
            .apply()
    }

    private fun saveRules(context: Context, rules: Set<WebBlockRule>) {
        getPrefs(context).edit()
            .putStringSet(KEY_BLOCKED_RULES, rules.mapTo(mutableSetOf()) { encodeRule(it) })
            .apply()
    }

    private fun parseTime(t: String): Int {
        val split = t.trim().split(":")
        return split[0].toInt() * 60 + split[1].toInt()
    }
}
