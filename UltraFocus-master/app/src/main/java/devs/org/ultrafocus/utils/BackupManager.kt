// main/java/devs/org/ultrafocus/utils/BackupManager.kt
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

            val downloadObj = JSONObject().apply {
                put("enabled", DownloadBlockPrefs.isEnabled(context))
                put("strictHours", DownloadBlockPrefs.getStrictHours(context))
                put("strictRequestedAt", DownloadBlockPrefs.getRequestTimestamp(context))
            }
            json.put("downloadBlock", downloadObj)

            val screensArr = JSONArray()
            SpecificScreenManager.getBlockedScreens(context).forEach { screen ->
                val obj = JSONObject()
                obj.put("value", screen)
                obj.put("schedule", SpecificScreenManager.getSchedule(context, screen))
                obj.put("strictHours", ItemStrictModeManager.getHours(context, screen))
                obj.put("strictRequestedAt", ItemStrictModeManager.getRequestTimestamp(context, screen))
                screensArr.put(obj)
            }
            json.put("screens", screensArr)

            val keywordsArr = JSONArray()
            ContentBlockManager.getKeywords(context).forEach { keyword ->
                val obj = JSONObject()
                obj.put("value", keyword)
                obj.put("schedule", ContentBlockManager.getSchedule(context, keyword))
                obj.put("strictHours", ItemStrictModeManager.getHours(context, keyword))
                obj.put("strictRequestedAt", ItemStrictModeManager.getRequestTimestamp(context, keyword))
                keywordsArr.put(obj)
            }
            json.put("keywords", keywordsArr)

            val sitesArr = JSONArray()
            WebsiteBlockManager.getRules(context).forEach { rule ->
                val obj = JSONObject()
                obj.put("host", rule.host)
                obj.put("path", rule.path)
                obj.put("mode", rule.mode.name)
                val ruleKey = rule.host + rule.path
                obj.put("schedule", WebsiteBlockManager.getSchedule(context, ruleKey, rule.mode))
                obj.put("strictHours", ItemStrictModeManager.getHours(context, ruleKey))
                obj.put("strictRequestedAt", ItemStrictModeManager.getRequestTimestamp(context, ruleKey))
                sitesArr.put(obj)
            }
            json.put("websites", sitesArr)

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
            val text = context.contentResolver.openInputStream(uri)
                ?.let { BufferedReader(InputStreamReader(it)).readText() }
                ?: return false

            val json = JSONObject(text)

            json.optJSONObject("downloadBlock")?.let { item ->
                val importedEnabled = item.optBoolean("enabled", false)
                val importedHours = item.optInt("strictHours", 0)
                val importedReq = item.optLong("strictRequestedAt", 0L)

                val currentEnabled = DownloadBlockPrefs.isEnabled(context)
                if (importedEnabled || currentEnabled) {
                    DownloadBlockPrefs.setEnabled(context, true)
                }

                val currentHours = DownloadBlockPrefs.getStrictHours(context)
                if (currentHours <= 0 && importedHours > 0) {
                    DownloadBlockPrefs.restoreStrictMode(context, importedHours, importedReq)
                } else if (importedHours > currentHours) {
                    DownloadBlockPrefs.restoreStrictMode(context, importedHours, importedReq)
                }
            }

            json.optJSONArray("screens")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (val item = arr.get(i)) {
                        is JSONObject -> {
                            val value = item.optString("value").takeIf { it.isNotBlank() } ?: continue
                            val schedule = item.optString("schedule").takeIf { it.isNotBlank() }
                            val importedHours = item.optInt("strictHours", 0)
                            val importedReq = item.optLong("strictRequestedAt", 0L)

                            SpecificScreenManager.addScreen(context, value, schedule)

                            val currentHours = ItemStrictModeManager.getHours(context, value)
                            if (currentHours <= 0 && importedHours > 0) {
                                ItemStrictModeManager.restoreStrictMode(context, value, importedHours, importedReq)
                            } else if (importedHours > currentHours) {
                                ItemStrictModeManager.restoreStrictMode(context, value, importedHours, importedReq)
                            }
                        }

                        is String -> SpecificScreenManager.addScreen(context, item, null)
                    }
                }
            }

            json.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (val item = arr.get(i)) {
                        is JSONObject -> {
                            val value = item.optString("value").takeIf { it.isNotBlank() } ?: continue
                            val schedule = item.optString("schedule").takeIf { it.isNotBlank() }
                            val importedHours = item.optInt("strictHours", 0)
                            val importedReq = item.optLong("strictRequestedAt", 0L)

                            ContentBlockManager.addKeyword(context, value, schedule)

                            val currentHours = ItemStrictModeManager.getHours(context, value)
                            if (currentHours <= 0 && importedHours > 0) {
                                ItemStrictModeManager.restoreStrictMode(context, value, importedHours, importedReq)
                            } else if (importedHours > currentHours) {
                                ItemStrictModeManager.restoreStrictMode(context, value, importedHours, importedReq)
                            }
                        }

                        is String -> ContentBlockManager.addKeyword(context, item, null)
                    }
                }
            }

            json.optJSONArray("websites")?.let { arr ->
                for (i in 0 until arr.length()) {
                    when (val item = arr.get(i)) {
                        is JSONObject -> {
                            val host = item.optString("host").takeIf { it.isNotBlank() } ?: continue
                            val path = item.optString("path")
                            val mode = try {
                                WebBlockMode.valueOf(item.optString("mode", "GENERAL"))
                            } catch (_: Exception) {
                                WebBlockMode.GENERAL
                            }

                            val schedule = item.optString("schedule").takeIf { it.isNotBlank() }
                            val importedHours = item.optInt("strictHours", 0)
                            val importedReq = item.optLong("strictRequestedAt", 0L)
                            val url = if (path.isNotBlank()) "$host$path" else host

                            WebsiteBlockManager.addSite(context, url, schedule, mode)

                            val ruleKey = host + path
                            val currentHours = ItemStrictModeManager.getHours(context, ruleKey)
                            if (currentHours <= 0 && importedHours > 0) {
                                ItemStrictModeManager.restoreStrictMode(context, ruleKey, importedHours, importedReq)
                            } else if (importedHours > currentHours) {
                                ItemStrictModeManager.restoreStrictMode(context, ruleKey, importedHours, importedReq)
                            }
                        }

                        is String -> WebsiteBlockManager.addSite(context, item, null)
                    }
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
