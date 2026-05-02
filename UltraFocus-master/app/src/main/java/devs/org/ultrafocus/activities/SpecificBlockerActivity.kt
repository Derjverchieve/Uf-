package devs.org.ultrafocus.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot export.", Toast.LENGTH_SHORT).show()
                return@let
            }
            val ok = BackupManager.exportSettings(this, it)
            Toast.makeText(this, if (ok) "Export successful" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Cannot import.", Toast.LENGTH_SHORT).show()
                return@let
            }
            val ok = BackupManager.importSettings(this, it)
            Toast.makeText(this, if (ok) "Import successful" else "Import failed", Toast.LENGTH_SHORT).show()
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

        // Add Web tab dynamically
        val tabContainer = btnTabScreens.parent as LinearLayout
        btnTabWeb = Button(this).apply {
            text = "Web"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(2) }
        }
        tabContainer.addView(btnTabWeb)

        // Accountability button
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
            val itemRaw = adapter.getItem(position) ?: return@setOnItemClickListener
            // Display label is "value [🔒]" — extract just the value
            val itemKey = extractItemKey(itemRaw)
            handleItemDelete(itemKey)
        }

        switchTab(0)
    }

    // ── Delete with per-item strict mode check ───────────────────────────────

    private fun handleItemDelete(itemKey: String) {
        // 1. Check global strict mode first
        if (StrictModeManager.isLocked(this)) {
            Toast.makeText(this, "Global Strict Mode is locked.", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Check per-item strict mode
        if (ItemStrictModeManager.isLocked(this, itemKey)) {
            showItemStrictModeDialog(itemKey)
            return
        }

        // 3. Confirm and delete
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Remove \"$itemKey\"?")
            .setPositiveButton("Delete") { _, _ ->
                deleteItem(itemKey)
                ItemStrictModeManager.clearItem(this, itemKey)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showItemStrictModeDialog(itemKey: String) {
        val reqTime = getSharedPreferences("ItemStrictModePrefs", android.content.Context.MODE_PRIVATE)
            .getLong("strict_req_$itemKey", 0L)

        val builder = AlertDialog.Builder(this)
        builder.setTitle("🔒 Item Locked: $itemKey")

        val statusText = TextView(this).apply {
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            textSize = 16f
            setPadding(40, 40, 40, 40)
            text = ItemStrictModeManager.getStatusText(this@SpecificBlockerActivity, itemKey)
        }

        // Live countdown
        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                if (ItemStrictModeManager.isLocked(this@SpecificBlockerActivity, itemKey)) {
                    statusText.text = ItemStrictModeManager.getStatusText(
                        this@SpecificBlockerActivity, itemKey
                    )
                    h.postDelayed(this, 1000)
                }
            }
        }
        h.post(r)

        builder.setView(statusText)

        val isLocked = ItemStrictModeManager.isLocked(this, itemKey)
        if (isLocked) {
            if (reqTime == 0L) {
                // No request started yet — offer to start timer
                builder.setPositiveButton("Request Unlock") { _, _ ->
                    TypeToAccessDialog.show(this, "Verify to request unlock") {
                        ItemStrictModeManager.requestUnlock(this, itemKey)
                        Toast.makeText(this, "Unlock timer started!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // Timer running — offer to cancel
                builder.setNegativeButton("Cancel Request") { _, _ ->
                    ItemStrictModeManager.cancelRequest(this, itemKey)
                    Toast.makeText(this, "Request cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Timer finished — allow delete
            builder.setPositiveButton("Delete Now") { _, _ ->
                deleteItem(itemKey)
                ItemStrictModeManager.clearItem(this, itemKey)
                refreshList()
            }
        }

        val dialog = builder.create()
        dialog.setOnDismissListener { h.removeCallbacks(r) }
        dialog.show()
    }

    private fun deleteItem(itemKey: String) {
        when (currentTab) {
            0 -> SpecificScreenManager.removeScreen(this, itemKey)
            1 -> ContentBlockManager.removeKeyword(this, itemKey)
            2 -> WebsiteBlockManager.removeSite(this, itemKey)
        }
    }

    // ── Add dialog with per-item strict mode ─────────────────────────────────

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val input = EditText(this).apply {
            hint = when (currentTab) {
                0 -> "Class Name (e.g. com.example.SomeActivity)"
                1 -> "Keyword (exact word to block)"
                2 -> "Website URL (e.g. reddit.com)"
                else -> ""
            }
        }

        val timeInput = EditText(this).apply {
            hint = "Schedule (optional, e.g. 09:00-17:00)"
        }

        val strictInput = EditText(this).apply {
            hint = "Strict mode delay hours (optional, e.g. 24)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        layout.addView(TextView(this).apply { text = "Value:" })
        layout.addView(input)
        layout.addView(TextView(this).apply {
            text = "Schedule (optional):"
            setPadding(0, 16, 0, 0)
        })
        layout.addView(timeInput)
        layout.addView(TextView(this).apply {
            text = "Strict mode delay hours (optional — set to lock this item):"
            setPadding(0, 16, 0, 0)
        })
        layout.addView(strictInput)

        AlertDialog.Builder(this)
            .setTitle("Add Block")
            .setView(layout)
            .setPositiveButton("Block") { _, _ ->
                val text = input.text.toString().trim()
                val schedule = timeInput.text.toString().trim().ifEmpty { null }
                val strictHours = strictInput.text.toString().trim().toIntOrNull() ?: 0

                if (text.isEmpty()) return@setPositiveButton

                when (currentTab) {
                    0 -> SpecificScreenManager.addScreen(this, text, schedule)
                    1 -> ContentBlockManager.addKeyword(this, text, schedule)
                    2 -> WebsiteBlockManager.addSite(this, text, schedule)
                }

                if (strictHours > 0) {
                    ItemStrictModeManager.setStrictMode(this, text, strictHours)
                }

                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private fun refreshList() {
        val rawList: Collection<String> = when (currentTab) {
            0 -> SpecificScreenManager.getBlockedScreens(this)
            1 -> ContentBlockManager.getKeywords(this)
            2 -> WebsiteBlockManager.getBlockedSites(this)
            else -> emptyList()
        }

        // Annotate each item with its strict mode status
        val displayList = rawList.map { item ->
            if (ItemStrictModeManager.isEnabled(this, item)) {
                val locked = ItemStrictModeManager.isLocked(this, item)
                "$item ${if (locked) "🔒" else "🔓"}"
            } else {
                item
            }
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter
    }

    /** Strip the lock emoji suffix added by refreshList to get the raw item key. */
    private fun extractItemKey(displayLabel: String): String {
        return displayLabel
            .removeSuffix(" 🔒")
            .removeSuffix(" 🔓")
            .trim()
    }
}
