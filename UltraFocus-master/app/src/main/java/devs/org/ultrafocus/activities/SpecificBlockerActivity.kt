package devs.org.ultrafocus.activities

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.AccountabilityManager
import devs.org.ultrafocus.utils.ContentBlockManager
import devs.org.ultrafocus.utils.SpecificScreenManager
import devs.org.ultrafocus.utils.StrictModeManager
import devs.org.ultrafocus.utils.WebsiteBlockManager

class SpecificBlockerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var fabAdd: ExtendedFloatingActionButton
    private lateinit var chkDebug: CheckBox
    private lateinit var adapter: ArrayAdapter<String>

    // UI Tabs
    private lateinit var btnTabScreens: Button
    private lateinit var btnTabKeywords: Button
    private lateinit var btnTabWeb: Button

    // 0=Screens, 1=Keywords, 2=Websites
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specific_blocker)

        listView = findViewById(R.id.listView)
        btnTabScreens = findViewById(R.id.btnTabScreens)
        btnTabKeywords = findViewById(R.id.btnTabKeywords)
        fabAdd = findViewById(R.id.fabAdd)
        chkDebug = findViewById(R.id.chkDebug)

        // Dynamically add the "Web" tab button
        val tabContainer = btnTabScreens.parent as LinearLayout
        btnTabWeb = Button(this).apply {
            text = "Web"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(2) }
        }
        tabContainer.addView(btnTabWeb)

        // Dynamically add the "Accountability" button at the top of the list
        val btnAccountability = Button(this).apply {
            text = "Set Accountability Partner (Rose)"
            setOnClickListener { showAccountabilityDialog() }
        }
        (listView.parent as LinearLayout).addView(btnAccountability, 0)

        // Init Debug Mode
        chkDebug.isChecked = SpecificScreenManager.isDebugMode(this)
        chkDebug.setOnCheckedChangeListener { _, isChecked ->
            SpecificScreenManager.setDebugMode(this, isChecked)
        }

        // Tab Click Listeners
        btnTabScreens.setOnClickListener { switchTab(0) }
        btnTabKeywords.setOnClickListener { switchTab(1) }

        // Add Button Listener
        fabAdd.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot add new blocks.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddDialog()
        }

        // Delete Item Listener
        listView.setOnItemClickListener { _, _, position, _ ->
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot remove blocks.", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            val itemRaw = adapter.getItem(position) ?: return@setOnItemClickListener
            // Clean display item to get the ID (remove the clock emoji part)
            val item = itemRaw.split("\n")[0].trim()

            AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Remove $item?")
                .setPositiveButton("Delete") { _, _ ->
                    when(currentTab) {
                        0 -> SpecificScreenManager.removeScreen(this, item)
                        1 -> ContentBlockManager.removeKeyword(this, item)
                        2 -> WebsiteBlockManager.removeSite(this, item)
                    }
                    refreshList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Start on first tab
        switchTab(0)
    }

    private fun showAccountabilityDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val nameInput = EditText(this).apply { hint = "Partner Name (e.g. Rose)" }
        val phoneInput = EditText(this).apply {
            hint = "Number with Country Code (e.g. 1555000)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }

        layout.addView(nameInput)
        layout.addView(phoneInput)

        AlertDialog.Builder(this)
            .setTitle("The Rose Protocol")
            .setMessage("If you break Strict Mode, a WhatsApp message will be prepared for this contact.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val phone = phoneInput.text.toString()
                if (name.isNotEmpty() && phone.isNotEmpty()) {
                    AccountabilityManager.setPartner(this, name, phone)
                    Toast.makeText(this, "Accountability Set.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchTab(index: Int) {
        currentTab = index
        // Visual feedback for tabs
        btnTabScreens.alpha = if(index==0) 1f else 0.5f
        btnTabKeywords.alpha = if(index==1) 1f else 0.5f
        btnTabWeb.alpha = if(index==2) 1f else 0.5f

        fabAdd.text = when(index) {
            0 -> "Add Screen"
            1 -> "Add Keyword"
            2 -> "Add Website"
            else -> "Add"
        }
        refreshList()
    }

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val input = EditText(this)
        input.hint = when(currentTab) {
            0 -> "Class Name"
            1 -> "Keyword (e.g. reels)"
            2 -> "Website (e.g. facebook.com)"
            else -> ""
        }
        layout.addView(input)

        // Time Input for ALL tabs
        val timeInput = EditText(this)
        timeInput.hint = "Time: 09:00-12:00,14:00-16:00"
        layout.addView(timeInput)

        AlertDialog.Builder(this)
            .setTitle("Add Block")
            .setView(layout)
            .setPositiveButton("Block") { _, _ ->
                val text = input.text.toString().trim()
                val schedule = timeInput.text.toString().trim()

                if (text.isNotEmpty()) {
                    // Prevent blocking self
                    if (text.contains("devs.org.ultrafocus")) {
                        Toast.makeText(this, "Cannot block the blocker!", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // Add to correct manager
                    when(currentTab) {
                        0 -> SpecificScreenManager.addScreen(this, text, if (schedule.isEmpty()) null else schedule)
                        1 -> ContentBlockManager.addKeyword(this, text, if (schedule.isEmpty()) null else schedule)
                        2 -> WebsiteBlockManager.addSite(this, text, if (schedule.isEmpty()) null else schedule)
                    }
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        val list = when(currentTab) {
            0 -> SpecificScreenManager.getBlockedScreens(this).map {
                val s = SpecificScreenManager.getSchedule(this, it)
                if(s.isNotEmpty()) "$it\n(⏰ $s)" else it
            }
            1 -> ContentBlockManager.getKeywords(this).map {
                val s = ContentBlockManager.getSchedule(this, it)
                if(s.isNotEmpty()) "$it\n(⏰ $s)" else it
            }
            2 -> WebsiteBlockManager.getBlockedSites(this).map {
                val s = WebsiteBlockManager.getSchedule(this, it)
                if(s.isNotEmpty()) "$it\n(⏰ $s)" else it
            }
            else -> emptyList()
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list)
        listView.adapter = adapter
    }
}