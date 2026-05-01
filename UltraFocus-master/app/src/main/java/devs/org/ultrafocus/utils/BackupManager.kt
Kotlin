package devs.org.ultrafocus.utils

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object BackupManager {

    private const val PREFS = "UltraFocusPrefs"

    fun exportSettings(context: Context, uri: Uri): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val json = JSONObject()

            json.put("blockedScreens",
                JSONArray(SpecificScreenManager.getBlockedScreens(context)))

            json.put("keywords",
                JSONArray(ContentBlockManager.getKeywords(context)))

            json.put("websiteRules",
                JSONArray(
                    WebsiteBlockManager.getRules(context).map {
                        JSONObject().apply {
                            put("host", it.host)
                            put("path", it.path)
                            put("mode", it.mode.name)
                        }
                    }
                )
            )

            json.put("downloadBlockEnabled",
                DownloadBlockPrefs.isEnabled(context))

            json.put("strictModeEnabled",
                StrictModeManager.isLocked(context))

            json.put("allowedApps",
                JSONArray(KioskModeManager.getAllowedApps(context)))

            json.put("accountabilityName",
                prefs.getString("accountability_name", ""))

            json.put("accountabilityPhone",
                prefs.getString("accountability_phone", ""))

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

        // 🔒 STRICT MODE PROTECTION (CRITICAL)
        if (StrictModeManager.isLocked(context)) {
            return false
        }

        return try {
            val input = context.contentResolver.openInputStream(uri)
            val text = BufferedReader(InputStreamReader(input)).readText()

            val json = JSONObject(text)

            // Screens
            SpecificScreenManager.clearAll(context)
            val screens = json.getJSONArray("blockedScreens")
            for (i in 0 until screens.length()) {
                SpecificScreenManager.addScreen(context, screens.getString(i), null)
            }

            // Keywords
            ContentBlockManager.clearAll(context)
            val keywords = json.getJSONArray("keywords")
            for (i in 0 until keywords.length()) {
                ContentBlockManager.addKeyword(context, keywords.getString(i), null)
            }

            // Websites
            WebsiteBlockManager.clearAll(context)
            val sites = json.getJSONArray("websiteRules")
            for (i in 0 until sites.length()) {
                val site = sites.getJSONObject(i)

                WebsiteBlockManager.addSite(
                    context,
                    site.getString("host") + site.getString("path"),
                    null,
                    WebBlockMode.valueOf(site.getString("mode"))
                )
            }

            // Download blocker
            DownloadBlockPrefs.setEnabled(
                context,
                json.getBoolean("downloadBlockEnabled")
            )

            // Strict mode (only re-enable, never disable)
            if (json.getBoolean("strictModeEnabled")) {
                StrictModeManager.enableStrictMode(context)
            }

            // Allowed apps
            val allowed = json.getJSONArray("allowedApps")
            val allowedList = mutableSetOf<String>()

            for (i in 0 until allowed.length()) {
                allowedList.add(allowed.getString(i))
            }

            KioskModeManager.saveAllowedApps(context, allowedList)

            // Accountability
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("accountability_name", json.optString("accountabilityName"))
                .putString("accountability_phone", json.optString("accountabilityPhone"))
                .apply()

            true

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}