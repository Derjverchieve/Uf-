package devs.org.ultrafocus.utils

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object BackupManager {

    fun exportSettings(context: Context, uri: Uri): Boolean {
        return try {
            val json = JSONObject()

            // Screens
            json.put(
                "screens",
                JSONArray(SpecificScreenManager.getBlockedScreens(context))
            )

            // Keywords
            json.put(
                "keywords",
                JSONArray(ContentBlockManager.getKeywords(context))
            )

            // Websites
            json.put(
                "websites",
                JSONArray(WebsiteBlockManager.getBlockedSites(context))
            )

            // ✅ APPS (NEW)
            json.put(
                "apps",
                JSONArray(AppBlockManager.getBlockedApps(context))
            )

            context.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toString(4).toByteArray())
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importSettings(context: Context, uri: Uri): Boolean {
        return try {
            val input = context.contentResolver.openInputStream(uri)
            val text = BufferedReader(InputStreamReader(input)).readText()
            val json = JSONObject(text)

            // Screens
            val screens = json.optJSONArray("screens")
            if (screens != null) {
                for (i in 0 until screens.length()) {
                    SpecificScreenManager.addScreen(context, screens.getString(i), null)
                }
            }

            // Keywords
            val keywords = json.optJSONArray("keywords")
            if (keywords != null) {
                for (i in 0 until keywords.length()) {
                    ContentBlockManager.addKeyword(context, keywords.getString(i), null)
                }
            }

            // Websites
            val sites = json.optJSONArray("websites")
            if (sites != null) {
                for (i in 0 until sites.length()) {
                    WebsiteBlockManager.addSite(context, sites.getString(i), null)
                }
            }

            // ✅ APPS (NEW)
            val apps = json.optJSONArray("apps")
            if (apps != null) {
                for (i in 0 until apps.length()) {
                    AppBlockManager.addApp(context, apps.getString(i))
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
