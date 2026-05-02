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

            // ── Screens ──────────────────────────────────────────────────────
            val screensArr = JSONArray()
            SpecificScreenManager.getBlockedScreens(context).forEach { screen ->
                val obj = JSONObject()
                obj.put("value", screen)
                obj.put("schedule", SpecificScreenManager.getSchedule(context, screen))
                obj.put("strictHours", ItemStrictModeManager.getHours(context, screen))
                screensArr.put(obj)
            }
            json.put("screens", screensArr)

            // ── Keywords ─────────────────────────────────────────────────────
            val keywordsArr = JSONArray()
            ContentBlockManager.getKeywords(context).forEach { keyword ->
                val obj = JSONObject()
                obj.put("value", keyword)
                obj.put("schedule", ContentBlockManager.getSchedule(context, keyword))
                obj.put("strictHours", ItemStrictModeManager.getHours(context, keyword))
                keywordsArr.put(obj)
            }
            json.put("keywords", keywordsArr)

            // ── Websites ─────────────────────────────────────────────────────
            val sitesArr = JSONArray()
            WebsiteBlockManager.getRules(context).forEach { rule ->
                val obj = JSONObject()
                obj.put("host", rule.host)
                obj.put("path", rule.path)
                obj.put("mode", rule.mode.name)
                val ruleKey = rule.host + rule.path
                obj.put("schedule", WebsiteBlockManager.getSchedule(context, ruleKey, rule.mode))
                obj.put("strictHours", ItemStrictModeManager.getHours(context, ruleKey))
                sitesArr.put(obj)
            }
            json.put("websites", sitesArr)

            // ── Write file ───────────────────────────────────────────────────
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

            // ── Screens ──────────────────────────────────────────────────────
            json.optJSONArray("screens")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    when (item) {
                        // New format: object with value/schedule/strictHours
                        is JSONObject -> {
                            val value = item.optString("value").takeIf { it.isNotBlank() } ?: continue
                            val schedule = item.optString("schedule").takeIf { it.isNotBlank() }
                            val strictHours = item.optInt("strictHours", 0)
                            SpecificScreenManager.addScreen(context, value, schedule)
                            if (strictHours > 0) ItemStrictModeManager.setStrictMode(context, value, strictHours)
                        }
                        // Legacy format: plain string
                        is String -> SpecificScreenManager.addScreen(context, item, null)
                        else -> {}
                    }
                }
            }

            // ── Keywords ─────────────────────────────────────────────────────
            json.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    when (item) {
                        is JSONObject -> {
                            val value = item.optString("value").takeIf { it.isNotBlank() } ?: continue
                            val schedule = item.optString("schedule").takeIf { it.isNotBlank() }
                            val strictHours = item.optInt("strictHours", 0)
                            ContentBlockManager.addKeyword(context, value, schedule)
                            if (strictHours > 0) ItemStrictModeManager.setStrictMode(context, value, strictHours)
                        }
                        is String -> ContentBlockManager.addKeyword(context, item, null)
                        else -> {}
                    }
                }
            }

            // ── Websites ─────────────────────────────────────────────────────
            json.optJSONArray("websites")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    when (item) {
                        is JSONObject -> {
                            val host = item.optString("host").takeIf { it.isNotBlank() } ?: continue
                            val path = item.optString("path")
                            val mode = try {
                                WebBlockMode.valueOf(item.optString("mode", "GENERAL"))
                            } catch (_: Exception) { WebBlockMode.GENERAL }
                            val schedule = item.optString("schedule").takeIf { it.isNotBlank() }
                            val strictHours = item.optInt("strictHours", 0)
                            val url = if (path.isNotBlank()) "$host$path" else host
                            WebsiteBlockManager.addSite(context, url, schedule, mode)
                            if (strictHours > 0) {
                                ItemStrictModeManager.setStrictMode(context, host + path, strictHours)
                            }
                        }
                        // Legacy format: plain host string
                        is String -> WebsiteBlockManager.addSite(context, item, null)
                        else -> {}
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
