package devs.org.ultrafocus.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugCaptureEntry(
    val timestamp: Long,
    val packageName: String?,
    val eventType: Int,
    val className: String?,
    val windowCount: Int,
    val rootClasses: List<String>,
    val addressBar: String?,
    val browserInPageView: Boolean?,
    val clickArmed: Boolean?,
    val note: String?,
    val treeDump: String?
)

object DebugCaptureManager {
    private const val PREF_NAME = "DebugCapturePrefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CAPTURE_UNTIL = "capture_until"
    private const val KEY_ENTRIES = "entries_json"
    private const val MAX_ENTRIES = 1000
    private const val DEFAULT_ARM_MS = 15_000L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun armCapture(context: Context, durationMs: Long = DEFAULT_ARM_MS) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_CAPTURE_UNTIL, now + durationMs)
            .apply()
    }

    fun isCaptureArmed(context: Context): Boolean {
        return System.currentTimeMillis() <= prefs(context).getLong(KEY_CAPTURE_UNTIL, 0L)
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_ENTRIES)
            .remove(KEY_CAPTURE_UNTIL)
            .apply()
    }

    fun record(
        context: Context,
        packageName: String?,
        eventType: Int,
        className: String?,
        windowCount: Int,
        rootClasses: List<String> = emptyList(),
        addressBar: String? = null,
        browserInPageView: Boolean? = null,
        clickArmed: Boolean? = null,
        note: String? = null,
        treeDump: String? = null
    ) {
        if (!isEnabled(context) && !isCaptureArmed(context)) return

        val entry = DebugCaptureEntry(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            eventType = eventType,
            className = className,
            windowCount = windowCount,
            rootClasses = rootClasses.distinct(),
            addressBar = addressBar,
            browserInPageView = browserInPageView,
            clickArmed = clickArmed,
            note = note,
            treeDump = treeDump
        )

        val current = getEntries(context).toMutableList()
        current.add(entry)
        val trimmed = if (current.size > MAX_ENTRIES) {
            current.takeLast(MAX_ENTRIES)
        } else {
            current
        }
        saveEntries(context, trimmed)
    }

    fun getEntries(context: Context): List<DebugCaptureEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, "") ?: ""
        if (raw.isBlank()) return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(fromJson(obj))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun exportText(context: Context): String {
        val entries = getEntries(context)
        if (entries.isEmpty()) {
            return "No debug captures yet."
        }

        return buildString {
            appendLine("ULTRAFOCUS DEBUG CAPTURE")
            appendLine("Generated: ${dateFormat.format(Date())}")
            appendLine("Entries: ${entries.size}")
            appendLine()

            entries.forEachIndexed { index, entry ->
                appendLine("[$index] ${dateFormat.format(Date(entry.timestamp))}")
                appendLine("package=${entry.packageName.orEmpty()}")
                appendLine("eventType=${eventTypeName(entry.eventType)} (${entry.eventType})")
                appendLine("class=${entry.className.orEmpty()}")
                appendLine("windowCount=${entry.windowCount}")
                if (entry.rootClasses.isNotEmpty()) {
                    appendLine("rootClasses=${entry.rootClasses.joinToString(", ")}")
                }
                if (!entry.addressBar.isNullOrBlank()) {
                    appendLine("addressBar=${entry.addressBar}")
                }
                if (entry.browserInPageView != null) {
                    appendLine("browserInPageView=${entry.browserInPageView}")
                }
                if (entry.clickArmed != null) {
                    appendLine("clickArmed=${entry.clickArmed}")
                }
                if (!entry.note.isNullOrBlank()) {
                    appendLine("note=${entry.note}")
                }
                if (!entry.treeDump.isNullOrBlank()) {
                    appendLine("treeDump:")
                    appendLine(entry.treeDump)
                }
                appendLine()
            }
        }
    }

    fun formatEntry(context: Context, entry: DebugCaptureEntry): String {
        val time = dateFormat.format(Date(entry.timestamp))
        return buildString {
            append(time)
            append(" | ")
            append(entry.packageName.orEmpty())
            append(" | ")
            append(eventTypeName(entry.eventType))
            if (!entry.className.isNullOrBlank()) {
                append(" | class=").append(entry.className)
            }
            append(" | windows=").append(entry.windowCount)
            if (entry.rootClasses.isNotEmpty()) {
                append(" | roots=").append(entry.rootClasses.joinToString(","))
            }
            if (!entry.addressBar.isNullOrBlank()) {
                append(" | url=").append(entry.addressBar)
            }
            if (entry.browserInPageView != null) {
                append(" | inPage=").append(entry.browserInPageView)
            }
            if (entry.clickArmed != null) {
                append(" | clickArmed=").append(entry.clickArmed)
            }
            if (!entry.note.isNullOrBlank()) {
                append(" | ").append(entry.note)
            }
        }
    }

    private fun saveEntries(context: Context, entries: List<DebugCaptureEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun toJson(entry: DebugCaptureEntry): JSONObject {
        return JSONObject().apply {
            put("timestamp", entry.timestamp)
            put("packageName", entry.packageName)
            put("eventType", entry.eventType)
            put("className", entry.className)
            put("windowCount", entry.windowCount)
            put("rootClasses", JSONArray(entry.rootClasses))
            put("addressBar", entry.addressBar)
            put("browserInPageView", entry.browserInPageView)
            put("clickArmed", entry.clickArmed)
            put("note", entry.note)
            put("treeDump", entry.treeDump)
        }
    }

    private fun fromJson(obj: JSONObject): DebugCaptureEntry {
        val roots = buildList {
            val arr = obj.optJSONArray("rootClasses")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "")
                    if (item.isNotBlank()) add(item)
                }
            }
        }

        return DebugCaptureEntry(
            timestamp = obj.optLong("timestamp", 0L),
            packageName = obj.optString("packageName").takeIf { it.isNotBlank() },
            eventType = obj.optInt("eventType", 0),
            className = obj.optString("className").takeIf { it.isNotBlank() },
            windowCount = obj.optInt("windowCount", 0),
            rootClasses = roots,
            addressBar = obj.optString("addressBar").takeIf { it.isNotBlank() },
            browserInPageView = if (obj.has("browserInPageView") && !obj.isNull("browserInPageView")) obj.optBoolean("browserInPageView", false) else null,
            clickArmed = if (obj.has("clickArmed") && !obj.isNull("clickArmed")) obj.optBoolean("clickArmed", false) else null,
            note = obj.optString("note").takeIf { it.isNotBlank() },
            treeDump = obj.optString("treeDump").takeIf { it.isNotBlank() }
        )
    }

    private fun eventTypeName(eventType: Int): String {
        return when (eventType) {
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
            else -> "TYPE_$eventType"
        }
    }
}
