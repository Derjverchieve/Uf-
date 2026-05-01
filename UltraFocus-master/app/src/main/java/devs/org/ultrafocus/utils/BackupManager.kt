package devs.org.ultrafocus.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.content.ContextCompat
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object BackupManager {

    fun exportSettings(context: Context, uri: Uri): Boolean {
        return try {
            val json = JSONObject()

            json.put("screens", JSONArray(SpecificScreenManager.getBlockedScreens(context)))
            json.put("keywords", JSONArray(ContentBlockManager.getKeywords(context)))
            json.put("websites", JSONArray(WebsiteBlockManager.getBlockedSites(context)))
            json.put("apps", exportApps(context))

            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toString(4).toByteArray())
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importSettings(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: return false

            val json = JSONObject(text)

            SpecificScreenManager.clearAll(context)
            ContentBlockManager.clearAll(context)
            WebsiteBlockManager.clearAll(context)
            clearApps(context)

            json.optJSONArray("screens")?.let { screens ->
                for (i in 0 until screens.length()) {
                    SpecificScreenManager.addScreen(context, screens.getString(i), null)
                }
            }

            json.optJSONArray("keywords")?.let { keywords ->
                for (i in 0 until keywords.length()) {
                    ContentBlockManager.addKeyword(context, keywords.getString(i), null)
                }
            }

            json.optJSONArray("websites")?.let { sites ->
                for (i in 0 until sites.length()) {
                    WebsiteBlockManager.addSite(context, sites.getString(i), null)
                }
            }

            json.optJSONArray("apps")?.let { apps ->
                importApps(context, apps)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun exportApps(context: Context): JSONArray {
        val apps = runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(context)
                .blockedAppDao()
                .getAll()
                .first()
        }

        return JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName", app.appName)
                    put("blockedAt", app.blockedAt)
                    put("isBlocked", app.isBlocked)
                    put("fromTime", app.fromTime ?: "")
                    put("toTime", app.toTime ?: "")
                    put("repeatMode", app.repeatMode ?: "")
                })
            }
        }
    }

    private fun importApps(context: Context, apps: JSONArray) {
        runBlocking(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).blockedAppDao()

            for (i in 0 until apps.length()) {
                val entry = apps.get(i)

                val appInfo = when (entry) {
                    is JSONObject -> buildAppInfo(context, entry)
                    is String -> buildLegacyAppInfo(context, entry)
                    else -> null
                }

                if (appInfo != null) {
                    dao.insert(appInfo)
                }
            }
        }
    }

    private fun clearApps(context: Context) {
        runBlocking(Dispatchers.IO) {
            AppDatabase.getDatabase(context).blockedAppDao().deleteAll()
        }
    }

    private fun buildLegacyAppInfo(context: Context, packageName: String): AppInfo? {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isBlank()) return null

        val icon = loadIcon(context, cleanPackage)
        return AppInfo(
            packageName = cleanPackage,
            appName = cleanPackage,
            icon = icon,
            blockedAt = System.currentTimeMillis(),
            isBlocked = true,
            fromTime = null,
            toTime = null,
            repeatMode = null
        )
    }

    private fun buildAppInfo(context: Context, obj: JSONObject): AppInfo? {
        val packageName = obj.optString("packageName").trim()
        if (packageName.isBlank()) return null

        val appName = obj.optString("appName", packageName).ifBlank { packageName }
        val blockedAt = obj.optLong("blockedAt", System.currentTimeMillis())
        val isBlocked = obj.optBoolean("isBlocked", true)

        val fromTime = obj.optString("fromTime").trim().ifBlank { null }
        val toTime = obj.optString("toTime").trim().ifBlank { null }
        val repeatMode = obj.optString("repeatMode").trim().ifBlank { null }

        val icon = loadIcon(context, packageName)

        return AppInfo(
            packageName = packageName,
            appName = appName,
            icon = icon,
            blockedAt = blockedAt,
            isBlocked = isBlocked,
            fromTime = fromTime,
            toTime = toTime,
            repeatMode = repeatMode
        )
    }

    private fun loadIcon(context: Context, packageName: String): Drawable {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
                ?: throw IllegalStateException("Fallback app icon missing")
        }
    }
}
