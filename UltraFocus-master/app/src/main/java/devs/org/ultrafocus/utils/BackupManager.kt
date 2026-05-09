package devs.org.ultrafocus.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.AppInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles full settings backup and restore to/from a JSON file.
 *
 * What is backed up:
 * - Blocked apps (package name + schedule — icon is re-derived from PackageManager on import)
 * - Blocked websites
 * - Blocked keywords
 * - Blocked screens (class names)
 * - Per-item strict mode state (hours + unlock request timestamp)
 * - Download block enabled state + strict mode
 *
 * Bug fixed: previously blocked apps were NOT included in the backup,
 * so importing a backup restored websites/keywords/screens but left the
 * app block list empty.
 *
 * Bug fixed: org.json has no Long type — all numbers parsed from a JSONObject
 * come back as Int at runtime regardless of their actual value. The itemStrictMode
 * import block must NOT use runtime type inference (is Int / is Long) to decide
 * putInt vs putLong. It instead checks the key prefix so strict_req_* keys are
 * always written with putLong and strict_hours_* keys with putInt. Using the wrong
 * type causes a ClassCastException in SharedPreferencesImpl.getLong() the moment
 * the adapter tries to read the value, crashing the app on the main screen after import.
 */
object BackupManager {

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportSettings(context: Context, uri: Uri): Boolean {
        return try {
            val json = JSONObject()

            // ── Blocked apps ──────────────────────────────────────────────────
            // getAll() returns a Flow<List<AppInfo>>. We collect its first emission
            // synchronously via runBlocking — this is a user-initiated one-shot
            // action on a small table so blocking briefly is acceptable.
            val blockedApps: List<AppInfo> = runBlocking {
                AppDatabase.getDatabase(context).blockedAppDao().getAll().first()
            }

            val appsArray = JSONArray()
            for (app in blockedApps) {
                appsArray.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName",     app.appName)
                    put("fromTime",    app.fromTime   ?: "")
                    put("toTime",      app.toTime     ?: "")
                    put("repeatMode",  app.repeatMode ?: "")
                    put("blockedAt",   app.blockedAt)
                    put("isBlocked",   app.isBlocked)
                })
            }
            json.put("blockedApps", appsArray)

            // ── Websites / keywords / screens ─────────────────────────────────
            val websiteArray = JSONArray()
            WebsiteBlockManager.getBlockedSites(context).forEach { websiteArray.put(it) }
            json.put("websites", websiteArray)

            val keywordArray = JSONArray()
            ContentBlockManager.getKeywords(context).forEach { keywordArray.put(it) }
            json.put("keywords", keywordArray)

            val screenArray = JSONArray()
            SpecificScreenManager.getBlockedScreens(context).forEach { screenArray.put(it) }
            json.put("screens", screenArray)

            // ── Download block state ──────────────────────────────────────────
            json.put("downloadBlock", JSONObject().apply {
                put("enabled",     DownloadBlockPrefs.isEnabled(context))
                put("strictHours", DownloadBlockPrefs.getStrictHours(context))
                put("strictReq",   DownloadBlockPrefs.getRequestTimestamp(context))
            })

            // ── Per-item strict mode ──────────────────────────────────────────
            val strictPrefs = context.getSharedPreferences(
                "ItemStrictModePrefs", Context.MODE_PRIVATE
            )
            val strictJson = JSONObject()
            for ((k, v) in strictPrefs.all) {
                strictJson.put(k, v)
            }
            json.put("itemStrictMode", strictJson)

            // ── Write to file ─────────────────────────────────────────────────
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toString(2).toByteArray())
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    fun importSettings(context: Context, uri: Uri): Boolean {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return false

            val json = JSONObject(jsonString)
            val pm   = context.packageManager
            val dao  = AppDatabase.getDatabase(context).blockedAppDao()

            // ── Blocked apps ──────────────────────────────────────────────────
            json.optJSONArray("blockedApps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item        = arr.getJSONObject(i)
                    val packageName = item.optString("packageName")
                        .takeIf { it.isNotBlank() } ?: continue

                    // Re-derive the icon from PackageManager.
                    // Skip apps that are no longer installed — nothing to block.
                    val appIcon = try {
                        pm.getApplicationIcon(packageName)
                    } catch (_: PackageManager.NameNotFoundException) {
                        continue
                    }

                    val appName = item.optString("appName")
                        .takeIf { it.isNotBlank() }
                        ?: try {
                            pm.getApplicationLabel(
                                pm.getApplicationInfo(packageName, 0)
                            ).toString()
                        } catch (_: Exception) { packageName }

                    val app = AppInfo(
                        packageName = packageName,
                        appName     = appName,
                        icon        = appIcon,
                        blockedAt   = item.optLong("blockedAt", System.currentTimeMillis()),
                        isBlocked   = item.optBoolean("isBlocked", false),
                        fromTime    = item.optString("fromTime").takeIf  { it.isNotBlank() },
                        toTime      = item.optString("toTime").takeIf    { it.isNotBlank() },
                        repeatMode  = item.optString("repeatMode").takeIf { it.isNotBlank() }
                    )

                    runBlocking { dao.insert(app) }
                }
            }

            // ── Websites / keywords / screens ─────────────────────────────────
            json.optJSONArray("websites")?.let { arr ->
                for (i in 0 until arr.length()) {
                    WebsiteBlockManager.addSite(context, arr.getString(i), null)
                }
            }

            json.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    ContentBlockManager.addKeyword(context, arr.getString(i), null)
                }
            }

            json.optJSONArray("screens")?.let { arr ->
                for (i in 0 until arr.length()) {
                    SpecificScreenManager.addScreen(context, arr.getString(i), null)
                }
            }

            // ── Download block state ──────────────────────────────────────────
            json.optJSONObject("downloadBlock")?.let { dl ->
                if (dl.optBoolean("enabled")) DownloadBlockPrefs.setEnabled(context, true)
                val strictHours = dl.optInt("strictHours", 0)
                val strictReq   = dl.optLong("strictReq", 0L)
                if (strictHours > 0) {
                    DownloadBlockPrefs.restoreStrictMode(context, strictHours, strictReq)
                }
            }

            // ── Per-item strict mode ──────────────────────────────────────────
            // IMPORTANT: org.json has no Long type. Every number in a parsed JSONObject
            // comes back as Int at runtime, even values that were originally stored as Long.
            // We must NOT use runtime type inference (is Int / is Long) here — it will
            // always match Int and call putInt(), causing getLong() to ClassCastException
            // at read time and crash the app. Instead we use the key prefix to decide
            // the correct SharedPreferences type explicitly.
            json.optJSONObject("itemStrictMode")?.let { strictJson ->
                val editor = context.getSharedPreferences(
                    "ItemStrictModePrefs", Context.MODE_PRIVATE
                ).edit()
                val keys = strictJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when {
                        key.startsWith("strict_req_")   -> editor.putLong(key, strictJson.getLong(key))
                        key.startsWith("strict_hours_") -> editor.putInt(key, strictJson.getInt(key))
                        // ignore any unrecognised keys
                    }
                }
                editor.apply()
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
