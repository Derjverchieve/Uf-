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

            json.put(
                "screens",
                JSONArray(SpecificScreenManager.getBlockedScreens(context))
            )

            json.put(
                "keywords",
                JSONArray(ContentBlockManager.getKeywords(context))
            )

            json.put(
                "websites",
                JSONArray(WebsiteBlockManager.getBlockedSites(context))
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
            val screens = json.getJSONArray("screens")
            for (i in 0 until screens.length()) {
                SpecificScreenManager.addScreen(context, screens.getString(i), null)
            }

            // Keywords
            val keywords = json.getJSONArray("keywords")
            for (i in 0 until keywords.length()) {
                ContentBlockManager.addKeyword(context, keywords.getString(i), null)
            }

            // Websites
            val sites = json.getJSONArray("websites")
            for (i in 0 until sites.length()) {
                WebsiteBlockManager.addSite(context, sites.getString(i), null)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
