package devs.org.ultrafocus.activities

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import devs.org.ultrafocus.R
import devs.org.ultrafocus.adapters.SelectedAppsAdapter
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.databinding.ActivityMainBinding
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.repository.AppRepository
import devs.org.ultrafocus.services.BlockerAccessibilityService
import devs.org.ultrafocus.services.DeviceAdmin
import devs.org.ultrafocus.utils.StrictModeManager
import devs.org.ultrafocus.utils.TypeToAccessDialog
import devs.org.ultrafocus.viewModel.MainViewModel
import devs.org.ultrafocus.viewModel.factory.MainModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding : ActivityMainBinding
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

        options = ActivityOptionsCompat.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        val database = AppDatabase.getDatabase(this)
        val repository = AppRepository(database)
        val factory = MainModelFactory(repository)
        viewModel = factory.create(MainViewModel::class.java)

        // Check Overlay Permission (Required for the Block Screen to show up over other apps)
        checkOverlayPermission()

        clickListeners()
        loadSelectedApps()
        updateFocusSwitchState()
    }

    override fun onResume() {
        super.onResume()
        loadSelectedApps()
        updateFocusSwitchState()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // Optional: Only force this if you really want to nag the user
            // val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            // startActivity(intent)
        }
    }

    private fun updateFocusSwitchState() {
        binding.focusSwitch.isChecked = isAccessibilityServiceEnabled()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "${packageName}/devs.org.ultrafocus.services.BlockerAccessibilityService"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return !TextUtils.isEmpty(enabledServices) && enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
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

        // Remove App Logic (Protected by Strict Mode)
        adapter.setOnItemRemovedListener { appInfo ->
            if (StrictModeManager.isLocked(this)) {
                Toast.makeText(this, "Strict Mode Locked! Request unlock first.", Toast.LENGTH_SHORT).show()
                loadSelectedApps() // Reset list visually
                return@setOnItemRemovedListener
            }
            lifecycleScope.launch {
                if (appInfo != null) viewModel.removeBlockedApp(appInfo)
            }
        }

        // Time Schedule Logic (Protected by Strict Mode)
        adapter.setOnSetTimePeriodListener { appInfo ->
            showTimePeriodDialogForApp(appInfo)
        }
    }

    private fun clickListeners() {
        // Normal Click: Select App
        binding.btnAddApps.setOnClickListener {
            startActivity(Intent(this, SelectAppActivity::class.java))
        }

        // Long Click: Advanced Blocker (Specific Screens / Keywords / Websites)
        binding.btnAddApps.setOnLongClickListener {
            val intent = Intent(this, SpecificBlockerActivity::class.java)
            startActivity(intent)
            true
        }

        binding.focusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isAccessibilityServiceEnabled()) {
                    showMaterialDialog()
                }
            } else {
                Toast.makeText(this, "To disable, turn off UltraFocus in Accessibility Settings.", Toast.LENGTH_LONG).show()
                binding.focusSwitch.isChecked = isAccessibilityServiceEnabled()
            }
        }

        // Long Click Focus Switch: ENABLE DEVICE ADMIN (Anti-Uninstall)
        binding.focusSwitch.setOnLongClickListener {
            askForDeviceAdmin()
            true
        }

        binding.btnAddTimePeriod.setOnClickListener {
            showGlobalTimeDialog()
        }

        // Long Click Time Button: STRICT MODE MANAGER
        binding.btnAddTimePeriod.setOnLongClickListener {
            showStrictModeDialog()
            true
        }
    }

    // --- DIALOGS & LOGIC ---

    private fun showGlobalTimeDialog() {
        if (StrictModeManager.isLocked(this)) {
            Toast.makeText(this, "Strict Mode Locked!", Toast.LENGTH_SHORT).show()
            return
        }

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
        if (StrictModeManager.isLocked(this)) {
            Toast.makeText(this, "Strict Mode Locked!", Toast.LENGTH_SHORT).show()
            return
        }

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

    private fun showStrictModeDialog() {
        val isLocked = StrictModeManager.isLocked(this)
        val isEnabled = StrictModeManager.isStrictModeEnabled(this)
        val requestTime = getSharedPreferences("StrictModePrefs", Context.MODE_PRIVATE).getLong("request_timestamp", 0)

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle("Strict Mode Manager")

        val statusText = TextView(this)
        statusText.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        statusText.textSize = 18f
        statusText.setPadding(40, 40, 40, 40)
        statusText.text = StrictModeManager.getStatusText(this)

        // Live Timer Logic
        val timerHandler = Handler(Looper.getMainLooper())
        val timerRunnable = object : Runnable {
            override fun run() {
                if (StrictModeManager.isStrictModeEnabled(this@MainActivity)) {
                    statusText.text = StrictModeManager.getStatusText(this@MainActivity)
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        timerHandler.post(timerRunnable)

        if (!isEnabled) {
            // SETUP MODE
            val input = EditText(this)
            input.hint = "Enter hours (e.g. 24, 48)"
            input.inputType = InputType.TYPE_CLASS_NUMBER

            val container = LinearLayout(this)
            container.orientation = LinearLayout.VERTICAL
            container.addView(statusText)
            container.addView(input)
            builder.setView(container)

            builder.setPositiveButton("Enable Lock") { _, _ ->
                val hours = input.text.toString().toIntOrNull() ?: 24
                StrictModeManager.setStrictMode(this, true, hours)
                Toast.makeText(this, "Locked for $hours hours", Toast.LENGTH_SHORT).show()
                timerHandler.removeCallbacks(timerRunnable)
            }
        } else {
            // ACTIVE MODE
            builder.setView(statusText)
            if (isLocked) {
                if (requestTime == 0L) {
                    // IDLE: Waiting for request
                    builder.setPositiveButton("Request Unlock") { _, _ ->
                        TypeToAccessDialog.show(this, "Security Verification") {
                            StrictModeManager.requestUnlock(this)
                            Toast.makeText(this, "Timer Started!", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    // TIMER RUNNING
                    builder.setNegativeButton("Cancel Request") { _, _ ->
                        StrictModeManager.cancelRequest(this)
                        Toast.makeText(this, "Request Cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // UNLOCKED (Timer finished)
                builder.setPositiveButton("Disable Strict Mode") { _, _ ->
                    TypeToAccessDialog.show(this, "Security Verification") {
                        StrictModeManager.setStrictMode(this, false, 0)
                        Toast.makeText(this, "Disabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val dialog = builder.create()
        dialog.setOnDismissListener {
            timerHandler.removeCallbacks(timerRunnable)
        }
        dialog.show()
    }

    private fun askForDeviceAdmin() {
        val componentName = ComponentName(this, DeviceAdmin::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Prevents uninstallation while blocking.")
        startActivity(intent)
    }

    private fun showMaterialDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.enable_accessibility_service))
            .setMessage(getString(R.string.permission_message))
            .setPositiveButton(getString(R.string.grant)){ _, _->
                openAccessibilityServiceScreen(BlockerAccessibilityService::class.java)
            }
            .setNegativeButton(getString(R.string.cancel)){ dialog, _ ->null}
            .create().show()
    }
}