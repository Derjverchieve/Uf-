package devs.org.ultrafocus.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.*

class SpecificBlockerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var fabAdd: ExtendedFloatingActionButton
    private lateinit var chkDebug: CheckBox
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var btnExportSettings: Button
    private lateinit var btnImportSettings: Button
    private lateinit var btnGrantAllFiles: Button
    private lateinit var switchDownloadBlock: Switch

    private lateinit var btnTabScreens: Button
    private lateinit var btnTabKeywords: Button
    private lateinit var btnTabWeb: Button

    private var currentTab = 0

    // ================= EXPORT =================
    private val exportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let {
                if (StrictModeManager.isLocked(this)) {
                    toast("Strict Mode Locked! Cannot export.")
                    return@let
                }

                val success = BackupManager.exportSettings(this, it)
                toast(if (success) "Export successful" else "Export failed")
            }
        }

    // ================= IMPORT =================
    private val importLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                if (StrictModeManager.isLocked(this)) {
                    toast("Strict Mode Locked! Cannot import.")
                    return@let
                }

                val success = BackupManager.importSettings(this, it)
                toast(if (success) "Import successful" else "Import failed")

                refreshList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specific_blocker)

        listView = findViewById(R.id.listView)
        fabAdd = findViewById(R.id.fabAdd)
        chkDebug = findViewById(R.id.chkDebug)

        btnTabScreens = findViewById(R.id.btnTabScreens)
        btnTabKeywords = findViewById(R.id.btnTabKeywords)
        btnTabWeb = findViewById(R.id.btnTabWeb)

        btnExportSettings = findViewById(R.id.btnExportSettings)
        btnImportSettings = findViewById(R.id.btnImportSettings)
        btnGrantAllFiles = findViewById(R.id.btnGrantAllFiles)
        switchDownloadBlock = findViewById(R.id.switchDownloadBlock)

        // ===== Tabs =====
        btnTabScreens.setOnClickListener { switchTab(0) }
        btnTabKeywords.setOnClickListener { switchTab(1) }
        btnTabWeb.setOnClickListener { switchTab(2) }

        // ===== Debug =====
        chkDebug.isChecked = SpecificScreenManager.isDebugMode(this)
        chkDebug.setOnCheckedChangeListener { _, isChecked ->
            SpecificScreenManager.setDebugMode(this, isChecked)
        }

        // ===== Export =====
        btnExportSettings.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                toast("Strict Mode Locked!")
                return@setOnClickListener
            }
            exportLauncher.launch("ultrafocus_backup.json")
        }

        // ===== Import =====
        btnImportSettings.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                toast("Strict Mode Locked!")
                return@setOnClickListener
            }
            importLauncher.launch(arrayOf("application/json"))
        }

        // ===== Download Blocker =====
        switchDownloadBlock.isChecked = DownloadBlockPrefs.isEnabled(this)

        switchDownloadBlock.setOnCheckedChangeListener { _, checked ->
            if (StrictModeManager.isLocked(this)) {
                toast("Strict Mode Locked!")
                switchDownloadBlock.isChecked = DownloadBlockPrefs.isEnabled(this)
                return@setOnCheckedChangeListener
            }

            DownloadBlockPrefs.setEnabled(this, checked)

            if (checked && needsAllFilesPermission()) {
                toast("Grant All Files Access")
                openAllFilesAccessSettings()
            }

            updatePermissionUI()
        }

        btnGrantAllFiles.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                toast("Strict Mode Locked!")
                return@setOnClickListener
            }
            openAllFilesAccessSettings()
        }

        // ===== Add button =====
        fabAdd.setOnClickListener {
            if (StrictModeManager.isLocked(this)) {
                toast("Strict Mode Locked!")
                return@setOnClickListener
            }
            showAddDialog()
        }

        // ===== Delete =====
        listView.setOnItemClickListener { _, _, position, _ ->
            if (StrictModeManager.isLocked(this)) {
                toast("Strict Mode Locked!")
                return@setOnItemClickListener
            }

            val item = adapter.getItem(position)?.split("\n")?.get(0)?.trim() ?: return@setOnItemClickListener

            AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Remove $item?")
                .setPositiveButton("Delete") { _, _ ->
                    when (currentTab) {
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
        updatePermissionUI()
    }

    // ================= PERMISSION =================
    private fun needsAllFilesPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
    }

    private fun updatePermissionUI() {
        btnGrantAllFiles.visibility =
            if (needsAllFilesPermission()) View.VISIBLE else View.GONE

        val locked = StrictModeManager.isLocked(this)
        switchDownloadBlock.isEnabled = !locked
        btnGrantAllFiles.isEnabled = !locked
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                toast("Cannot open settings")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
    }

    // ================= UI =================
    private fun switchTab(index: Int) {
        currentTab = index

        btnTabScreens.alpha = if (index == 0) 1f else 0.5f
        btnTabKeywords.alpha = if (index == 1) 1f else 0.5f
        btnTabWeb.alpha = if (index == 2) 1f else 0.5f

        fabAdd.text = when (index) {
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

        input.hint = when (currentTab) {
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

                when (currentTab) {
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
        val list = when (currentTab) {
            0 -> SpecificScreenManager.getBlockedScreens(this)
            1 -> ContentBlockManager.getKeywords(this)
            2 -> WebsiteBlockManager.getBlockedSites(this)
            else -> emptyList()
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.toList())
        listView.adapter = adapter
    }

    private fun showAccountabilityDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val nameInput = EditText(this)
        val phoneInput = EditText(this)

        layout.addView(nameInput)
        layout.addView(phoneInput)

        AlertDialog.Builder(this)
            .setTitle("Accountability")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                AccountabilityManager.setPartner(
                    this,
                    nameInput.text.toString(),
                    phoneInput.text.toString()
                )
                toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
