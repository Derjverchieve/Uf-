package devs.org.ultrafocus.utils

import android.content.Context
import android.text.InputFilter
import android.text.Spanned
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText

/**
 * A TextInputEditText that completely blocks paste and clipboard access:
 *
 *  1. onTextContextMenuItem — intercepts the long-press context menu paste items.
 *  2. customSelectionActionModeCallback — hides paste from the floating toolbar.
 *  3. isLongClickable = false — suppresses the context menu before it can appear.
 *  4. InputFilter — detects when more than one character is inserted in a single
 *     operation (the fingerprint of a keyboard's dedicated paste button) and
 *     rejects the insertion.
 *
 * This covers:
 *  - Long-press → Paste in context menu
 *  - Three-dot overflow → Paste
 *  - Keyboard paste key / clipboard toolbar button
 *  - Programmatic setText / append that inserts > 1 char at once
 */
class NoPasteTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    init {
        isLongClickable = false

        // Block floating-toolbar paste action
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = true
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.removeItem(android.R.id.paste)
                menu?.removeItem(android.R.id.pasteAsPlainText)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }

        // Block keyboard paste-button: any insertion of more than 1 char at once
        // is treated as a paste and rejected.
        filters = arrayOf(object : InputFilter {
            override fun filter(
                source: CharSequence?,
                start: Int,
                end: Int,
                dest: Spanned?,
                dstart: Int,
                dend: Int
            ): CharSequence? {
                val insertLen = end - start
                if (insertLen > 1) {
                    post {
                        Toast.makeText(
                            context,
                            "Pasting is disabled — type the code manually.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return ""   // reject the insertion
                }
                return null     // allow single-char input normally
            }
        })
    }

    /** Intercepts context-menu paste items before they can act. */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            Toast.makeText(context, "Pasting is disabled — type the code manually.", Toast.LENGTH_SHORT).show()
            return false
        }
        return super.onTextContextMenuItem(id)
    }
}
