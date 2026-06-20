package devs.org.ultrafocus.utils

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

object TypeToAccessDialog {

    // Max characters the challenge code can be (user must TYPE this, no paste)
    private const val MAX_CODE_CHARS = 16

    fun show(context: Context, title: String, onUnlockingSuccess: () -> Unit) {
        val level = PunishmentManager.getCurrentLevel(context)

        // Generate challenge code, capped at MAX_CODE_CHARS (16)
        val rawLength = minOf(PunishmentManager.getChallengeLength(context), MAX_CODE_CHARS)
        var rawCode = ""
        while (rawCode.length < rawLength) {
            rawCode += UUID.randomUUID().toString().replace("-", "")
        }
        rawCode = rawCode.take(rawLength)

        // Format with hyphens every 8 chars: "XXXXXXXX-XXXXXXXX"
        val formattedCode = rawCode.chunked(8).joinToString("-")

        val builder = AlertDialog.Builder(context)
        builder.setTitle("$title (Level $level)")
        builder.setCancelable(false)

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val instructions = TextView(context).apply {
            text = "Penalty Level $level\nType the code EXACTLY as shown (including dashes).\nPasting is disabled — you must type it manually."
            textSize = 14f
        }

        val codeView = TextView(context).apply {
            text = formattedCode
            textSize = 16f
            setPadding(0, 20, 0, 20)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(false)
            isLongClickable = false
        }

        // FIX: Custom EditText that blocks all paste operations
        val input = object : EditText(context) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                // Intercept paste — return false to consume without acting
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    Toast.makeText(context, "Pasting is disabled. Type the code manually.", Toast.LENGTH_SHORT).show()
                    return false
                }
                return super.onTextContextMenuItem(id)
            }
        }.apply {
            hint = "Type the code here..."
            inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            // Prevent long-press context menu from appearing at all
            isLongClickable = false
            // Hard length limit = formattedCode.length (includes dashes)
            filters = arrayOf(InputFilter.LengthFilter(formattedCode.length))
        }

        layout.addView(instructions)
        layout.addView(codeView)
        layout.addView(input)
        scrollView.addView(layout)
        builder.setView(scrollView)

        builder.setPositiveButton("Verify") { _, _ ->
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
