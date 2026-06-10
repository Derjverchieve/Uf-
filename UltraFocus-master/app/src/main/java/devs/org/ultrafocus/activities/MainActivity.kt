package devs.org.ultrafocus.activities

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import devs.org.ultrafocus.R
import devs.org.ultrafocus.adapters.SelectedAppsAdapter
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.databinding.ActivityMainBinding
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.repository.AppRepository
import devs.org.ultrafocus.services.BlockerAccessibilityService
import devs.org.ultrafocus.services.DeviceAdmin
import devs.org.ultrafocus.services.DownloadBlockService
import devs.org.ultrafocus.utils.DownloadBlockPrefs
import devs.org.ultrafocus.viewModel.MainViewModel
import devs.org.ultrafocus.viewModel.factory.MainModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private var handler = Handler(Looper.getMainLooper())
    private var list = mutableListOf<AppInfo>()
    private lateinit var options: ActivityOptionsCompat

    private var ignoreDownloadToggleCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.inflateMenu(R.menu.main_toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        options = ActivityOptionsCompat.makeCustomAnimation(
            this, android.R.anim.fade_in, android.R.anim.fade_out
        )

        val database = AppDatabase.getDatabase(this)
        val repository = AppRepository(database)
        val factory = MainModelFactory(repository)
        viewModel = factory.create(MainViewModel::class.java)

        clickListeners()
        loadSelectedApps()
        updateFocusSwitchState()
        updateDownloadBlockSwitchState()
        updateDownloadStrictState()
    }

    override fun onResume() {
        super.onResume()
        loadSelectedApps()
        updateFocusSwitchState()
        updateDownloadBlockSwitchState()
        updateDownloadStrictState()
    }

    // ── State sync ────────────────────────────────────────────────────────────

    private fun updateFocusSwitchState() {
        binding.focusSwitch.isChecked = isAccessibilityServiceEnabled()
    }

    private fun updateDownloadBlockSwitchState() {
        ignoreDownloadToggleCallback = true
        binding.downloadBlockSwitch.isChecked = DownloadBlockPrefs.isEnabled(this)
        ignoreDownloadToggleCallback = false
    }

    private fun updateDownloadStrictState() {
        // FIX: update both the status text AND the button label so users
        // can see strict mode state without opening the dialog.
        binding.txtDownloadStrictStatus.text = DownloadBlockPrefs.getStatusText(this)
        binding.btnDownloadStrict.text = DownloadBlockPrefs.getStrictButtonLabel(this)
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun clickListeners() {

        binding.btnAddApps.setOnClickListener {
            startActivity(Intent(this, SelectAppActivity::class.java))
        }
        binding.btnAddApps.setOnLongClickListener {
            startActivity(Intent(this, SpecificBlockerActivity::class.java))
            true
        }

        binding.focusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isAccessibilityServiceEnabled()) showMaterialDialog()
            } else {
                Toast.makeText(
                    this,
                    "To disable, turn off UltraFocus in Accessibility Settings.",
                    Toast.LENGTH_LONG
                ).show()
                binding.focusSwitch.isChecked = isAccessibilityServiceEnabled()
            }
        }
        binding.focusSwitch.setOnLongClickListener {
            askForDeviceAdmin()
            true
        }

        binding.downloadBlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreDownloadToggleCallback) return@setOnCheckedChangeListener
            handleDownloadBlockToggle(isChecked)
        }

        binding.btnDownloadStrict.setOnClickListener {
            showDownloadStrictModeDialog()
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.done -> {
                    showSettingsDialog()
                    true
                }
                R.id.debug_capture -> {
                    startActivity(Intent(this, DebugCaptureActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    // ── Download blocking ─────────────────────────────────────────────────────

    private fun handleDownloadBlockToggle(enable: Boolean) {
        if (!enable && DownloadBlockPrefs.isLocked(this)) {
            Toast.makeText(
                this,
                "Download blocking is locked by strict mode.",
                Toast.LENGTH_SHORT
            ).show()
            ignoreDownloadToggleCallback = true
            binding.downloadBlockSwitch.isChecked = true
            ignoreDownloadToggleCallback = false
            return
        }

        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                ignoreDownloadToggleCallback = true
                binding.downloadBlockSwitch.isChecked = false
                ignoreDownloadToggleCallback = false
                showDownloadPermissionDialog()
                return
            }
            startDownloadBlockService()
        } else {
            stopDownloadBlockService()
        }

        updateDownloadStrictState()
    }

    private fun startDownloadBlockService() {
        DownloadBlockPrefs.setEnabled(this, true)
        val intent = Intent(this, DownloadBlockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Download blocking enabled.", Toast.LENGTH_SHORT).show()
    }

    private fun stopDownloadBlockService() {
        DownloadBlockPrefs.setEnabled(this, false)
        stopService(Intent(this, DownloadBlockService::class.java))
        Toast.makeText(this, "Download blocking disabled.", Toast.LENGTH_SHORT).show()
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showDownloadPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("All Files Access Required")
            .setMessage(
                "Download blocking needs \"All Files Access\" permission to watch " +
                    "and delete files in the Downloads folder.\n\n" +
                    "Tap Grant to open the permission screen, then enable it for UltraFocus."
            )
            .setPositiveButton("Grant") { _, _ -> grantAllFilesAccess() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDownloadStrictModeDialog() {
        val key = "download_block"
        val isLocked = DownloadBlockPrefs.isLocked(this)
        val currentHours = DownloadBlockPrefs.getStrictHours(this)
        val prefs = getSharedPreferences("download_block_prefs", Context.MODE_PRIVATE)
        val reqTime = prefs.getLong("strict_req", 0L)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val statusText = TextView(this).apply {
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            textSize = 15f
            setPadding(0, 0, 0, 20)
            text = DownloadBlockPrefs.getStatusText(this@MainActivity)
        }
        layout.addView(statusText)

        // Countdown ticker
        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                statusText.text = DownloadBlockPrefs.getStatusText(this@MainActivity)
                if (DownloadBlockPrefs.isLocked(this@MainActivity)) h.postDelayed(this, 1000)
            }
        }
        h.post(r)

        val builder = AlertDialog.Builder(this).setTitle("Download Strict Mode")

        if (isLocked) {
            // Locked state — show unlock flow, no input field
            builder.setView(layout)
            when {
                reqTime == 0L -> builder.setPositiveButton("Request Unlock") { _, _ ->
                    DownloadBlockPrefs.requestUnlock(this)
                    updateDownloadStrictState()
                    Toast.makeText(this, "Unlock timer started!", Toast.LENGTH_SHORT).show()
                }
                DownloadBlockPrefs.isLocked(this) -> builder.setNegativeButton("Cancel Request") { _, _ ->
                    DownloadBlockPrefs.cancelRequest(this)
                    updateDownloadStrictState()
                    Toast.makeText(this, "Request cancelled.", Toast.LENGTH_SHORT).show()
                }
                else -> builder.setPositiveButton("Disable Now") { _, _ ->
                    DownloadBlockPrefs.clearStrictMode(this)
                    updateDownloadStrictState()
                }
            }
        } else {
            // Not locked — show hours input
            val hoursInput = EditText(this).apply {
                hint = "Hours delay (e.g. 24). Set 0 to disable."
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                if (currentHours > 0) setText(currentHours.toString())
            }
            layout.addView(TextView(this).apply { text = "Lock duration (hours):" })
            layout.addView(hoursInput)
            builder.setView(layout)
            builder.setPositiveButton("Save") { _, _ ->
                val hours = hoursInput.text.toString().toIntOrNull() ?: 0
                if (hours <= 0) {
                    DownloadBlockPrefs.clearStrictMode(this)
                    Toast.makeText(this, "Download strict mode disabled.", Toast.LENGTH_SHORT).show()
                } else {
                    DownloadBlockPrefs.setStrictMode(this, hours)
                    Toast.makeText(this, "Strict mode set: ${hours}h lock on download blocking.", Toast.LENGTH_SHORT).show()
                }
                updateDownloadStrictState()
            }
        }

        builder.setNegativeButton("Close", null)
        val dialog = builder.create()
        dialog.setOnDismissListener { h.removeCallbacks(r) }
        dialog.show()
    }

    private fun showTimePeriodDialogForApp(appInfo: AppInfo) {
        val input = EditText(this)
        input.hint = "09:00-12:00, 14:00-18:00"
        input.setText(appInfo.fromTime)

        MaterialAlertDialogBuilder(this)
            .setTitle("Schedule for ${appInfo.appName}")
            .setMessage("Enter time ranges separated by commas.\nExample: 09:00-12:00,14:30-20:00")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newConfig = input.text.toString()
                lifecycleScope.launch {
                    viewModel.updateSingleAppTimePeriod(appInfo, newConfig, null, "DAILY")
                    loadSelectedApps()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "Grant All Files Access",
            "Grant Draw Over Other Apps",
            "Enable Device Admin (Anti-Uninstall)"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("UltraFocus Permissions")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> grantAllFilesAccess()
                    1 -> grantDrawOverlayPermission()
                    2 -> askForDeviceAdmin()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun grantAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All Files Access already granted.", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            Toast.makeText(
                this,
                "All Files Access not required on Android 10 and below.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun grantDrawOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            Toast.makeText(this, "Draw Over Other Apps already granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askForDeviceAdmin() {
        val componentName = ComponentName(this, DeviceAdmin::class.java)
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Prevents uninstallation while blocking."
                )
            }
        )
    }

    private fun showMaterialDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.enable_accessibility_service))
            .setMessage(getString(R.string.permission_message))
            .setPositiveButton(getString(R.string.grant)) { _, _ ->
                openAccessibilityServiceScreen(BlockerAccessibilityService::class.java)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .create().show()
    }

    // ── Accessibility helpers ─────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent =
            "${packageName}/devs.org.ultrafocus.services.BlockerAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return !TextUtils.isEmpty(enabledServices) &&
            enabledServices.split(":").any {
                it.equals(expectedComponent, ignoreCase = true)
            }
    }

    private fun openAccessibilityServiceScreen(cls: Class<*>) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(this, cls)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            startActivity(intent, options.toBundle())
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun loadSelectedApps() {
        handler.post {
            lifecycleScope.launch {
                list.clear()
                list = viewModel.getBlockedApps()
                setAdapter()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setAdapter() {
        val adapter = SelectedAppsAdapter(this, list)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        adapter.setOnItemRemovedListener { appInfo ->
            lifecycleScope.launch {
                if (appInfo != null) viewModel.removeBlockedApp(appInfo)
            }
        }

        adapter.setOnSetTimePeriodListener { appInfo ->
            showTimePeriodDialogForApp(appInfo)
        }
    }
}

