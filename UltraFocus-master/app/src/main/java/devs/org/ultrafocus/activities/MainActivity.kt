package devs.org.ultrafocus.activities

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        checkOverlayPermission()
        clickListeners()
        loadSelectedApps()
        updateFocusSwitchState()
        updateDownloadBlockSwitchState()
    }

    override fun onResume() {
        super.onResume()
        loadSelectedApps()
        updateFocusSwitchState()
        updateDownloadBlockSwitchState()
    }

    private fun checkOverlayPermission() {
        // Overlay permission nag is optional — keeping it commented by choice
    }

    private fun updateFocusSwitchState() {
        binding.focusSwitch.isChecked = isAccessibilityServiceEnabled()
    }

    private fun updateDownloadBlockSwitchState() {
        // Mirror the persisted pref — the service sets it to false on destroy,
        // so this correctly reflects reality after a reboot or force-stop.
        binding.downloadBlockSwitch.isChecked = DownloadBlockPrefs.isEnabled(this)
    }

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
        } catch (e: Exception) {
            e.printStackTrace()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

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

    private fun clickListeners() {
        // Add apps
        binding.btnAddApps.setOnClickListener {
            startActivity(Intent(this, SelectAppActivity::class.java))
        }

        // Long-press add apps → advanced blocker (screens / keywords / websites)
        binding.btnAddApps.setOnLongClickListener {
            startActivity(Intent(this, SpecificBlockerActivity::class.java))
            true
        }

        // Focus switch
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

        // Long-press focus switch → device admin (anti-uninstall)
        binding.focusSwitch.setOnLongClickListener {
            askForDeviceAdmin()
            true
        }

        // Download block switch
        binding.downloadBlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleDownloadBlockToggle(isChecked)
        }

        // Time period button
        binding.btnAddTimePeriod.setOnClickListener {
            showGlobalTimeDialog()
        }

        // Toolbar settings icon → permissions & settings
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.done) {
                showSettingsDialog()
                true
            } else false
        }
    }

    // ── Download blocking ─────────────────────────────────────────────────────

    private fun handleDownloadBlockToggle(enable: Boolean) {
        if (enable) {
            // Need All Files Access on Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()) {
                // Snap the switch back — permission not granted yet
                binding.downloadBlockSwitch.isChecked = false
                showDownloadPermissionDialog()
                return
            }
            startDownloadBlockService()
        } else {
            stopDownloadBlockService()
        }
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

    // ── Dialogs ──────────────────────────────────────────────────────────────

    private fun showGlobalTimeDialog() {
        val input = EditText(this)
        input.hint = "09:00-12:00, 14:00-18:00"

        MaterialAlertDialogBuilder(this)
            .setTitle("Global Schedule")
            .setMessage("Enter time ranges separated by commas.\nExample: 09:00-12:00,14:30-20:00")
            .setView(input)
            .setPositiveButton("Save All") { _, _ ->
                val newConfig = input.text.toString()
                lifecycleScope.launch {
                    viewModel.updateBlockedAppsTimePeriod(newConfig, null, "DAILY")
                    Toast.makeText(this@MainActivity, "Updated all apps!", Toast.LENGTH_SHORT).show()
                    loadSelectedApps()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun grantAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All Files Access already granted.", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
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
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "Draw Over Other Apps already granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askForDeviceAdmin() {
        val componentName = ComponentName(this, DeviceAdmin::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents uninstallation while blocking."
            )
        }
        startActivity(intent)
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
}
private fun setupDownloadBlockingUI() {

    val switch = findViewById<android.widget.Switch>(R.id.switchDownloadBlock)
    val strictBtn = findViewById<android.widget.Button>(R.id.btnDownloadStrict)
    val statusText = findViewById<android.widget.TextView>(R.id.txtDownloadStrictStatus)

    // Initial state
    switch.isChecked = DownloadBlockPrefs.isEnabled(this)
    statusText.text = DownloadBlockPrefs.getStatusText(this)

    switch.setOnCheckedChangeListener { _, isChecked ->

        if (!isChecked) {
            // 🚨 STRICT MODE CHECK
            if (DownloadBlockPrefs.isLocked(this)) {
                android.widget.Toast.makeText(
                    this,
                    "Strict mode active — cannot disable yet",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                switch.isChecked = true
                return@setOnCheckedChangeListener
            }
        }

        DownloadBlockPrefs.setEnabled(this, isChecked)

        if (isChecked) {
            startService(Intent(this, DownloadBlockService::class.java))
        } else {
            stopService(Intent(this, DownloadBlockService::class.java))
        }
    }

    strictBtn.setOnClickListener {
        showStrictModeDialog(statusText)
    }
}
