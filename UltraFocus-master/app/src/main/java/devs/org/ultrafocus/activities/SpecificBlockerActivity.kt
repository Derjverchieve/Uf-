package devs.org.ultrafocus.activities

import android.content.Context
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
    private lateinit var btnAccountability: Button

    private lateinit var btnTabScreens: Button
    private lateinit var btnTabKeywords: Button
    private lateinit var btnTabWeb: Button
    private lateinit var btnTabAllowlist: Button

    // Allowlist tab views
    private lateinit var layoutAllowlistControls: LinearLayout
    private lateinit var switchAllowlistEnabled: Switch
    private lateinit var btnAllowlistStrict: Button
    private lateinit var txtAllowlistStrictStatus: TextView

    private var currentTab = 0

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            val ok = BackupManager.exportSettings(this, it)
            Toast.makeText(
                this,
                if (ok) "Export successful" else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val ok = BackupManager.importSettings(this, it)
            Toast.makeText(
                this,
                if (ok) "Import successful" else "Import failed",
                Toast.LENGTH_SHORT
            ).show()
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_specific_blocker)

        listView               = findViewById(R.id.listView)
        btnTabScreens          = findViewById(R.id.btnTabScreens)
        btnTabKeywords         = findViewById(R.id.btnTabKeywords)
        btnTabWeb              = findViewById(R.id.btnTabWeb)
        btnTabAllowlist        = findViewById(R.id.btnTabAllowlist)
        fabAdd                 = findViewById(R.id.fabAdd)
        chkDebug               = findViewById(R.id.chkDebug)
        btnExportSettings      = findViewById(R.id.btnExportSettings)
        btnImportSettings      = findViewById(R.id.btnImportSettings)
        btnAccountability      = findViewById(R.id.btnAccountability)
        layoutAllowlistControls = findViewById(R.id.layoutAllowlistControls)
        switchAllowlistEnabled = findViewById(R.id.switchAllowlistEnabled)
        btnAllowlistStrict     = findViewById(R.id.btnAllowlistStrict)
        txtAllowlistStrictStatus = findViewById(R.id.txtAllowlistStrictStatus)

        btnTabScreens.setOnClickListener   { switchTab(0) }
        btnTabKeywords.setOnClickListener  { switchTab(1) }
        btnTabWeb.setOnClickListener       { switchTab(2) }
        btnTabAllowlist.setOnClickListener { switchTab(3) }

        btnExportSettings.setOnClickListener {
            exportLauncher.launch("ultrafocus_backup.json")
        }
        btnImportSettings.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }

        btnAccountability.setOnClickListener { showAccountabilityDialog() }

        chkDebug.isChecked = SpecificScreenManager.isDebugMode(this)
        chkDebug.setOnCheckedChangeListener { _, isChecked ->
            SpecificScreenManager.setDebugMode(this, isChecked)
        }

        // ── Allowlist controls ────────────────────────────────────────────────
        syncAllowlistSwitch()
        switchAllowlistEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Enabling is always allowed
                WebAllowlistManager.setEnabled(this, true)
                Toast.makeText(this, "Allowlist mode ON — only listed sites are accessible.", Toast.LENGTH_SHORT).show()
            } else {
                val accepted = WebAllowlistManager.setEnabled(this, false)
                if (!accepted) {
                    // Snap back — locked by strict mode
                    syncAllowlistSwitch()
                    Toast.makeText(this, "Allowlist mode is locked by strict mode.", Toast.LENGTH_SHORT).show()
                }
            }
            updateAllowlistStrictStatus()
        }

        btnAllowlistStrict.setOnClickListener { showAllowlistStrictDialog() }

        fabAdd.setOnClickListener { showAddDialog() }

        listView.setOnItemClickListener { _, _, position, _ ->
            val itemRaw = adapter.getItem(position) ?: return@setOnItemClickListener
            if (currentTab == 3) {
                handleAllowlistDelete(extractItemKey(itemRaw))
            } else {
                handleItemDelete(extractItemKey(itemRaw))
            }
        }

        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        syncAllowlistSwitch()
        updateAllowlistStrictStatus()
    }

    // ── Allowlist helpers ─────────────────────────────────────────────────────

    private fun syncAllowlistSwitch() {
        switchAllowlistEnabled.setOnCheckedChangeListener(null)
        switchAllowlistEnabled.isChecked = WebAllowlistManager.isEnabled(this)
        switchAllowlistEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                WebAllowlistManager.setEnabled(this, true)
                Toast.makeText(this, "Allowlist mode ON — only listed sites are accessible.", Toast.LENGTH_SHORT).show()
            } else {
                val accepted = WebAllowlistManager.setEnabled(this, false)
                if (!accepted) {
                    syncAllowlistSwitch()
                    Toast.makeText(this, "Allowlist mode is locked by strict mode.", Toast.LENGTH_SHORT).show()
                }
            }
            updateAllowlistStrictStatus()
        }
    }

    private fun updateAllowlistStrictStatus() {
        txtAllowlistStrictStatus.text = WebAllowlistManager.getStrictStatusText(this)
        val hours = WebAllowlistManager.getStrictHours(this)
        btnAllowlistStrict.text = if (hours > 0)
            "Allowlist Strict Lock: ${hours}h ${if (WebAllowlistManager.isLocked(this)) "🔒" else "🔓"}"
        else
            "Set Allowlist Strict Mode"
    }

    private fun showAllowlistStrictDialog() {
        val isLocked = WebAllowlistManager.isLocked(this)
        val currentHours = WebAllowlistManager.getStrictHours(this)
        val reqTime = WebAllowlistManager.getRequestTimestamp(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 20)
            text = WebAllowlistManager.getStrictStatusText(this@SpecificBlockerActivity)
        }
        layout.addView(statusText)

        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                statusText.text = WebAllowlistManager.getStrictStatusText(this@SpecificBlockerActivity)
                if (WebAllowlistManager.isLocked(this@SpecificBlockerActivity)) h.postDelayed(this, 1000)
            }
        }
        h.post(r)

        val builder = AlertDialog.Builder(this).setTitle("Allowlist Strict Mode")

        if (isLocked) {
            builder.setView(layout)
            when {
                reqTime == 0L -> builder.setPositiveButton("Request Unlock") { _, _ ->
                    WebAllowlistManager.requestUnlock(this)
                    updateAllowlistStrictStatus()
                    Toast.makeText(this, "Unlock timer started!", Toast.LENGTH_SHORT).show()
                }
                WebAllowlistManager.isLocked(this) -> builder.setNegativeButton("Cancel Request") { _, _ ->
                    WebAllowlistManager.cancelRequest(this)
                    updateAllowlistStrictStatus()
                    Toast.makeText(this, "Request cancelled.", Toast.LENGTH_SHORT).show()
                }
                else -> builder.setPositiveButton("Disable Now") { _, _ ->
                    WebAllowlistManager.clearStrictMode(this)
                    updateAllowlistStrictStatus()
                }
            }
        } else {
            val hoursInput = EditText(this).apply {
                hint = "Hours delay (e.g. 24). Set 0 to disable."
                inputType = InputType.TYPE_CLASS_NUMBER
                if (currentHours > 0) setText(currentHours.toString())
            }
            layout.addView(TextView(this).apply { text = "Lock duration (hours):" })
            layout.addView(hoursInput)
            builder.setView(layout)
            builder.setPositiveButton("Save") { _, _ ->
                val hours = hoursInput.text.toString().toIntOrNull() ?: 0
                if (hours <= 0) {
                    WebAllowlistManager.clearStrictMode(this)
                    Toast.makeText(this, "Allowlist strict mode disabled.", Toast.LENGTH_SHORT).show()
                } else {
                    WebAllowlistManager.setStrictMode(this, hours)
                    Toast.makeText(this, "Strict mode set: ${hours}h lock on allowlist mode.", Toast.LENGTH_SHORT).show()
                }
                updateAllowlistStrictStatus()
            }
        }

        builder.setNegativeButton("Close", null)
        val dialog = builder.create()
        dialog.setOnDismissListener { h.removeCallbacks(r) }
        dialog.show()
    }

    private fun handleAllowlistDelete(host: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove from allowlist")
            .setMessage("Remove \"$host\" from allowed sites?\n\nThis site will be blocked by allowlist mode.")
            .setPositiveButton("Remove") { _, _ ->
                WebAllowlistManager.removeAllowedHost(this, host)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Delete flow with per-item strict mode ─────────────────────────────────

    private fun handleItemDelete(itemKey: String) {
        if (ItemStrictModeManager.isLocked(this, itemKey)) {
            showItemStrictModeDialog(itemKey)
            return
        }
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
        val prefs = getSharedPreferences("ItemStrictModePrefs", Context.MODE_PRIVATE)
        val reqTime = prefs.getLong("strict_req_$itemKey", 0L)

        val statusText = TextView(this).apply {
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            textSize = 16f
            setPadding(40, 40, 40, 40)
            text = ItemStrictModeManager.getStatusText(this@SpecificBlockerActivity, itemKey)
        }

        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                statusText.text = ItemStrictModeManager.getStatusText(
                    this@SpecificBlockerActivity, itemKey
                )
                if (ItemStrictModeManager.isLocked(this@SpecificBlockerActivity, itemKey)) {
                    h.postDelayed(this, 1000)
                }
            }
        }
        h.post(r)

        val builder = AlertDialog.Builder(this)
            .setTitle("🔒 Locked: $itemKey")
            .setView(statusText)

        when {
            reqTime == 0L -> builder.setPositiveButton("Request Unlock") { _, _ ->
                TypeToAccessDialog.show(this, "Verify to start unlock timer") {
                    ItemStrictModeManager.requestUnlock(this, itemKey)
                    Toast.makeText(this, "Unlock timer started!", Toast.LENGTH_SHORT).show()
                }
            }
            ItemStrictModeManager.isLocked(this, itemKey) -> builder.setNegativeButton("Cancel Request") { _, _ ->
                ItemStrictModeManager.cancelRequest(this, itemKey)
                Toast.makeText(this, "Request cancelled.", Toast.LENGTH_SHORT).show()
            }
            else -> builder.setPositiveButton("Delete Now") { _, _ ->
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

    // ── Add dialog ────────────────────────────────────────────────────────────

    private fun showAddDialog() {
        if (currentTab == 3) {
            // Allowlist tab — just ask for a URL to allow
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 10)
            }
            val input = EditText(this).apply {
                hint = "Website to allow (e.g. wikipedia.org)"
            }
            layout.addView(TextView(this).apply { text = "Allowed site URL:" })
            layout.addView(input)

            AlertDialog.Builder(this)
                .setTitle("Add to Allowlist")
                .setView(layout)
                .setPositiveButton("Allow") { _, _ ->
                    val text = input.text.toString().trim()
                    if (text.isEmpty()) return@setPositiveButton
                    WebAllowlistManager.addAllowedHost(this, text)
                    refreshList()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val input = EditText(this).apply {
            hint = when (currentTab) {
                0 -> "Class name (e.g. com.example.SomeActivity)"
                1 -> "Keyword — exact word only"
                2 -> "Website URL (e.g. reddit.com)"
                else -> ""
            }
        }

        val timeInput = EditText(this).apply {
            hint = "Schedule (optional, e.g. 09:00-17:00)"
        }

        val strictInput = EditText(this).apply {
            hint = "Strict mode delay in hours (optional, e.g. 24)"
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
            text = "Strict mode delay hours (optional):"
            setPadding(0, 16, 0, 0)
        })
        layout.addView(strictInput)

        var selectedMode = WebBlockMode.GENERAL
        if (currentTab == 2) {
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 0)
            }
            val modeLabel = TextView(this).apply {
                text = "Specific page only (allow this path, block rest)"
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val modeToggle = Switch(this).apply {
                isChecked = false
                setOnCheckedChangeListener { _, isChecked ->
                    selectedMode = if (isChecked) WebBlockMode.SPECIFIC else WebBlockMode.GENERAL
                    input.hint = if (isChecked)
                        "Full URL with path (e.g. reddit.com/r/gaming)"
                    else
                        "Website URL (e.g. reddit.com)"
                }
            }
            modeRow.addView(modeLabel)
            modeRow.addView(modeToggle)
            layout.addView(modeRow)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Block")
            .setView(layout)
            .setPositiveButton("Block") { _, _ ->
                val text = input.text.toString().trim()
                val schedule = timeInput.text.toString().trim().ifEmpty { null }
                val strictHours = strictInput.text.toString().trim().toIntOrNull() ?: 0
                if (text.isEmpty()) return@setPositiveButton

                when (currentTab) {
                    0 -> {
                        SpecificScreenManager.addScreen(this, text, schedule)
                        if (strictHours > 0) ItemStrictModeManager.setStrictMode(this, text, strictHours)
                    }
                    1 -> {
                        ContentBlockManager.addKeyword(this, text, schedule)
                        if (strictHours > 0) ItemStrictModeManager.setStrictMode(this, text, strictHours)
                    }
                    2 -> {
                        WebsiteBlockManager.addSite(this, text, schedule, selectedMode)
                        if (strictHours > 0) {
                            val normalizedKey = WebsiteBlockManager.normalizeHost(text)
                            ItemStrictModeManager.setStrictMode(this, normalizedKey, strictHours)
                        }
                    }
                }
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showAccountabilityDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val nameInput  = EditText(this).apply { hint = "Partner Name" }
        val phoneInput = EditText(this).apply { hint = "Phone Number" }
        layout.addView(nameInput)
        layout.addView(phoneInput)

        AlertDialog.Builder(this)
            .setTitle("Accountability")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name  = nameInput.text.toString()
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
        btnTabScreens.alpha   = if (index == 0) 1f else 0.5f
        btnTabKeywords.alpha  = if (index == 1) 1f else 0.5f
        btnTabWeb.alpha       = if (index == 2) 1f else 0.5f
        btnTabAllowlist.alpha = if (index == 3) 1f else 0.5f

        chkDebug.visibility = if (index == 0) android.view.View.VISIBLE else android.view.View.GONE
        layoutAllowlistControls.visibility =
            if (index == 3) android.view.View.VISIBLE else android.view.View.GONE

        fabAdd.text = when (index) {
            0 -> "Add Screen"
            1 -> "Add Keyword"
            2 -> "Add Website"
            3 -> "Add Allowed Site"
            else -> "Add"
        }
        refreshList()
    }

    private fun refreshList() {
        if (currentTab == 3) {
            // Allowlist tab — show user-added allowed hosts
            // Google is always implicitly allowed so we don't show it here
            val hosts = WebAllowlistManager.getAllowedHosts(this).sorted()
            val displayList = if (hosts.isEmpty())
                listOf("(Google is always allowed — add more sites here)")
            else
                hosts
            adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
            listView.adapter = adapter
            return
        }

        val rawList: Collection<String> = when (currentTab) {
            0 -> SpecificScreenManager.getBlockedScreens(this)
            1 -> ContentBlockManager.getKeywords(this)
            2 -> WebsiteBlockManager.getBlockedSites(this)
            else -> emptyList()
        }

        val displayList = rawList.map { item ->
            if (ItemStrictModeManager.isEnabled(this, item)) {
                "$item ${if (ItemStrictModeManager.isLocked(this, item)) "🔒" else "🔓"}"
            } else item
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter
    }

    private fun extractItemKey(displayLabel: String) =
        displayLabel.removeSuffix(" 🔒").removeSuffix(" 🔓").trim()
}
