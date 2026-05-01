package devs.org.ultrafocus.activities

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.*
import androidx.activity.result.contract.ActivityResultContracts

class SpecificBlockerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var fabAdd: ExtendedFloatingActionButton
    private lateinit var chkDebug: CheckBox
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var btnExportSettings: Button
    private lateinit var btnImportSettings: Button

    private lateinit var btnTabScreens: Button
    private lateinit var btnTabKeywords: Button
    private lateinit var btnTabWeb: Button

    private var currentTab = 0

    private val exportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let {
                if (StrictModeManager.isLocked(this)) {
                    Toast.makeText(this, "Strict Mode Locked! Cannot export.", Toast.LENGTH_SHORT).show()
                    return@let
                }

                val success = BackupManager.exportSettings(this, it)

                Toast.makeText(
                    this,
                    if (success) "Export successful" else "Export failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val importLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                if (StrictModeManager.isLocked(this)) {
                    Toast.makeText(this, "Strict Mode Locked! Cannot import.", Toast.LENGTH_SHORT).show()
                    return@let
                }

                val success = BackupManager.importSettings(this, it)

                Toast.makeText(
                    this,
                    if (success) "Import successful" else "Import failed",
                    Toast.LENGTH_SHORT
                ).show()

                refreshList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specific_blocker)

        listView = findViewById(R.id.listView)
        btnTabScreens = findViewById(R.id.btnTabScreens)
        btnTabKeywords = findViewById(R.id.btnTabKeywords)
        fabAdd = findViewById(R.id.fabAdd)
        chkDebug = findViewById(R.id.chkDebug)

        btnExportSettings = findViewById(R.id.btnExportSettings)
        btnImportSettings = findViewById(R.id.btnImportSettings)

        btnExportSettings.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot export.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportLauncher.launch("ultrafocus_backup.json")
        }

        btnImportSettings.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot import.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            importLauncher.launch(arrayOf("application/json"))
        }

        val tabContainer = btnTabScreens.parent as LinearLayout
        btnTabWeb = Button(this).apply {
            text = "Web"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(2) }
        }
        tabContainer.addView(btnTabWeb)

        val btnAccountability = Button(this).apply {
            text = "Set Accountability Partner (Rose)"
            setOnClickListener { showAccountabilityDialog() }
        }
        (listView.parent as LinearLayout).addView(btnAccountability, 0)

        chkDebug.isChecked = SpecificScreenManager.isDebugMode(this)
        chkDebug.setOnCheckedChangeListener { _, isChecked ->
            SpecificScreenManager.setDebugMode(this, isChecked)
        }

        btnTabScreens.setOnClickListener { switchTab(0) }
        btnTabKeywords.setOnClickListener { switchTab(1) }

        fabAdd.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot add new blocks.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddDialog()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot remove blocks.", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }

            val itemRaw = adapter.getItem(position) ?: return@setOnItemClickListener
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

        switchTab(0)
    }

    private fun showAccountabilityDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val nameInput = EditText(this).apply { hint = "Partner Name" }
        val phoneInput = EditText(this).apply { hint = "Phone Number" }

        layout.addView(nameInput)
        layout.addView(phoneInput)

        AlertDialog.Builder(this)
            .setTitle("Accountability")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString()
                val phone = phoneInput.text.toString()

                if (name.isNotEmpty() && phone.isNotEmpty()) {
                    AccountabilityManager.setPartner(this, name, phone)
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchTab(index: Int) {
        currentTab = index

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
        val timeInput = EditText(this)

        input.hint = when(currentTab) {
            0 -> "Class Name"
            1 -> "Keyword"
            2 -> "Website"
            else -> ""
        }

        timeInput.hint = "Schedule (optional)"

        layout.addView(input)
        layout.addView(timeInput)

        AlertDialog.Builder(this)
            .setTitle("Add Block")
            .setView(layout)
            .setPositiveButton("Block") { _, _ ->
                val text = input.text.toString().trim()
                val schedule = timeInput.text.toString().trim()

                if (text.isEmpty()) return@setPositiveButton

                when(currentTab) {
                    0 -> SpecificScreenManager.addScreen(this, text, schedule.ifEmpty { null })
                    1 -> ContentBlockManager.addKeyword(this, text, schedule.ifEmpty { null })
                    2 -> WebsiteBlockManager.addSite(this, text, schedule.ifEmpty { null })
                }

                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        val list = when(currentTab) {
            0 -> SpecificScreenManager.getBlockedScreens(this)
            1 -> ContentBlockManager.getKeywords(this)
            2 -> WebsiteBlockManager.getBlockedSites(this)
            else -> emptyList()
        }

        adapter = ArrayAdapter<String>(
    this,
    android.R.layout.simple_list_item_1,
    list.toList()
)

        listView.adapter = adapter
    }
}
