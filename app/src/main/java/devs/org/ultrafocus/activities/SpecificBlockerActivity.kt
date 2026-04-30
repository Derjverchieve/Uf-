package devs.org.ultrafocus.activities

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.ContentBlockManager
import devs.org.ultrafocus.utils.SpecificScreenManager

class SpecificBlockerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var btnTabScreens: Button
    private lateinit var btnTabKeywords: Button
    private lateinit var fabAdd: ExtendedFloatingActionButton
    private lateinit var chkDebug: CheckBox
    private lateinit var adapter: ArrayAdapter<String>

    // False = Screens, True = Keywords
    private var isViewingKeywords = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specific_blocker)

        listView = findViewById(R.id.listView)
        btnTabScreens = findViewById(R.id.btnTabScreens)
        btnTabKeywords = findViewById(R.id.btnTabKeywords)
        fabAdd = findViewById(R.id.fabAdd)
        chkDebug = findViewById(R.id.chkDebug)

        // Init Debug Checkbox
        chkDebug.isChecked = SpecificScreenManager.isDebugMode(this)
        chkDebug.setOnCheckedChangeListener { _, isChecked ->
            SpecificScreenManager.setDebugMode(this, isChecked)
        }

        // Tab Logic
        btnTabScreens.setOnClickListener {
            isViewingKeywords = false
            updateTabUI()
            refreshList()
        }

        btnTabKeywords.setOnClickListener {
            isViewingKeywords = true
            updateTabUI()
            refreshList()
        }

        // Add Button Logic
        fabAdd.setOnClickListener {
            showAddDialog(isViewingKeywords)
        }

        // List Click (Delete) logic
        listView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) ?: return@setOnItemClickListener

            val msg = if (isViewingKeywords) "Unblock keyword '$item'?" else "Stop blocking screen '$item'?"

            AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage(msg)
                .setPositiveButton("Delete") { _, _ ->
                    if (isViewingKeywords) {
                        ContentBlockManager.removeKeyword(this, item)
                    } else {
                        SpecificScreenManager.removeScreen(this, item)
                    }
                    refreshList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateTabUI()
        refreshList()
    }

    private fun updateTabUI() {
        if (isViewingKeywords) {
            btnTabKeywords.alpha = 1.0f
            btnTabScreens.alpha = 0.5f
            fabAdd.text = "Add Keyword"
        } else {
            btnTabKeywords.alpha = 0.5f
            btnTabScreens.alpha = 1.0f
            fabAdd.text = "Add Screen"
        }
    }

    private fun showAddDialog(isKeyword: Boolean) {
        val input = EditText(this)
        input.hint = if (isKeyword) "e.g. 'gambling', 'reels'" else "e.g. com.android.settings.Settings"

        AlertDialog.Builder(this)
            .setTitle(if (isKeyword) "Block Content Keyword" else "Block Activity Class")
            .setMessage(if (isKeyword) "Block any screen containing this exact word/phrase." else "Block a specific system screen ID.")
            .setView(input)
            .setPositiveButton("Block") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (isKeyword) {
                        ContentBlockManager.addKeyword(this, text)
                    } else {
                        SpecificScreenManager.addScreen(this, text)
                    }
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        val list = if (isViewingKeywords) {
            ContentBlockManager.getKeywords(this).toList()
        } else {
            SpecificScreenManager.getBlockedScreens(this).toList()
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list)
        listView.adapter = adapter
    }
}