package devs.org.ultrafocus.utils

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

object TypeToAccessDialog {

    fun show(context: Context, title: String, onUnlockingSuccess: () -> Unit) {
        val requiredLength = PunishmentManager.getChallengeLength(context)
        val level = PunishmentManager.getCurrentLevel(context)

        // Generate raw string
        var rawCode = ""
        while (rawCode.length < requiredLength) {
            rawCode += UUID.randomUUID().toString().replace("-", "")
        }
        rawCode = rawCode.take(requiredLength)

        // Add hyphens for "readability" (groups of 8)
        // This makes it look like a serial key: A1B2C3D4-E5F6G7H8...
        val formattedCode = rawCode.chunked(8).joinToString("-")

        val builder = AlertDialog.Builder(context)
        builder.setTitle("$title (Level $level)")
        builder.setCancelable(false)

        // 1. SCROLL VIEW WRAPPER (Fixes the 512 char overflow issue)
        val scrollView = ScrollView(context)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val instructions = TextView(context).apply {
            text = "Penalty Level $level\nType the code EXACTLY as shown (including dashes):"
            textSize = 14f
        }

        val codeView = TextView(context).apply {
            text = formattedCode
            textSize = 16f
            setPadding(0, 20, 0, 20)
            setTypeface(android.graphics.Typeface.MONOSPACE)

            // 2. DISABLE COPYING
            setTextIsSelectable(false)
            isLongClickable = false
        }

        val input = EditText(context).apply {
            hint = "Type the code here..."
            // Visible Password type usually disables auto-correct/suggestions
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }

        layout.addView(instructions)
        layout.addView(codeView)
        layout.addView(input)

        scrollView.addView(layout)
        builder.setView(scrollView)

        builder.setPositiveButton("Verify") { _, _ ->
            // Check against formatted code (user must type hyphens)
            if (input.text.toString().trim() == formattedCode) {
                PunishmentManager.incrementLevel(context)

                if (AccountabilityManager.hasPartner(context)) {
                    Toast.makeText(context, "Verifying via WhatsApp...", Toast.LENGTH_LONG).show()
                    AccountabilityManager.triggerShameProtocol(context)
                    onUnlockingSuccess()
                } else {
                    onUnlockingSuccess()
                }
            } else {
                Toast.makeText(context, "Incorrect. Access Denied.", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}