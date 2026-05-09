package devs.org.ultrafocus.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.AppInfo
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
 */
object BackupManager {

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportSettings(context: Context, uri: Uri): Boolean {
        return try {
            val json = JSONObject()

            // ── Blocked apps ──────────────────────────────────────────────────
            // Access the DB synchronously — this is a user-initiated one-shot action
            // on a small table so blocking the main thread briefly is acceptable.
            val blockedApps = runBlocking {
                AppDatabase.getDatabase(context).blockedAppDao().getAll()
                    .let { flow ->
                        // getAll() returns a Flow; collect its first emission
                        kotlinx.coroutines.flow.first(flow)
                    }
            }
            val appsArray = JSONArray()
            for (app in blockedApps) {
                appsArray.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName",     app.appName)
                    put("fromTime",    app.fromTime  ?: "")
                    put("toTime",      app.toTime    ?: "")
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
                put("enabled",      DownloadBlockPrefs.isEnabled(context))
                put("strictHours",  DownloadBlockPrefs.getStrictHours(context))
                put("strictReq",    DownloadBlockPrefs.getStrictRequestTimestamp(context))
            })

            // ── Per-item strict mode ──────────────────────────────────────────
            val strictPrefs = context.getSharedPreferences("ItemStrictModePrefs", Context.MODE_PRIVATE)
            val strictJson  = JSONObject()
            strictPrefs.all.forEach { (k, v) -> strictJson.put(k, v) }
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
                    val packageName = item.optString("packageName").takeIf { it.isNotBlank() }
                        ?: continue

                    // Re-derive the app icon from PackageManager.
                    // If the app is no longer installed, skip it — there is nothing to block.
                    val appIcon = try {
                        pm.getApplicationIcon(packageName)
                    } catch (_: PackageManager.NameNotFoundException) {
                        continue
                    }

                    val appName = item.optString("appName")
                        .takeIf { it.isNotBlank() }
                        ?: try { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                           catch (_: Exception) { packageName }

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
            json.optJSONObject("itemStrictMode")?.let { strictJson ->
                val editor = context.getSharedPreferences(
                    "ItemStrictModePrefs", Context.MODE_PRIVATE
                ).edit()
                strictJson.keys().forEach { key ->
                    when (val v = strictJson.get(key)) {
                        is Int     -> editor.putInt(key, v)
                        is Long    -> editor.putLong(key, v)
                        is Boolean -> editor.putBoolean(key, v)
                        is String  -> editor.putString(key, v)
                        else       -> {}
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
