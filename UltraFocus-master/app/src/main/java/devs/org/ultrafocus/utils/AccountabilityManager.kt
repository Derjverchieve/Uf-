package devs.org.ultrafocus.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

object AccountabilityManager {
    private const val PREF_NAME = "AccountabilityPrefs"
    private const val KEY_PARTNER_PHONE = "partner_phone" // Format: 15551234567 (No +)
    private const val KEY_PARTNER_NAME = "partner_name"

    fun setPartner(context: Context, name: String, phone: String) {
        // Simple regex to strip non-digits
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PARTNER_NAME, name)
            .putString(KEY_PARTNER_PHONE, cleanPhone)
            .apply()
    }

    fun getPartnerPhone(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PARTNER_PHONE, null)
    }

    fun getPartnerName(context: Context): String? {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PARTNER_NAME, null)
    }

    fun triggerShameProtocol(context: Context) {
        val phone = getPartnerPhone(context) ?: return // No partner set, skip
        val name = getPartnerName(context) ?: "Accountability Partner"

        val message = "Hi $name. I just disabled my UltraFocus blocker because I lack discipline. Please yell at me."

        try {
            val url = "https://api.whatsapp.com/send?phone=$phone&text=${URLEncoder.encode(message, "UTF-8")}"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.data = Uri.parse(url)
                this.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Install WhatsApp to verify shame.", Toast.LENGTH_SHORT).show()
        }
    }

    fun hasPartner(context: Context): Boolean {
        return getPartnerPhone(context) != null
    }
}