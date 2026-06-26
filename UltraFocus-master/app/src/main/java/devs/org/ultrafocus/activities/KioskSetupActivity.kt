package devs.org.ultrafocus.activities

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import devs.org.ultrafocus.R
import devs.org.ultrafocus.databinding.ActivityKioskSetupBinding
import devs.org.ultrafocus.utils.KioskPrefs

class KioskSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityKioskSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Kiosk toggle
        binding.switchKiosk.isChecked = KioskPrefs.isKioskEnabled(this)
        updateAllowedCard(KioskPrefs.isKioskEnabled(this))

        binding.switchKiosk.setOnCheckedChangeListener { _, checked ->
            KioskPrefs.setKioskEnabled(this, checked)
            updateAllowedCard(checked)
        }

        binding.btnAddAllowedApp.setOnClickListener { showAppPicker() }

        refreshList()
    }

    private fun updateAllowedCard(enabled: Boolean) {
        binding.groupAllowedApps.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                pm.getLaunchIntentForPackage(it.packageName) != null &&
                it.packageName != packageName
            }
            .map { it.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }

        val labels = apps.map { it.second }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Add allowed app")
            .setItems(labels) { _, which ->
                val (pkg, label) = apps[which]
                KioskPrefs.addAllowedApp(this, pkg, label)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        binding.containerAllowedApps.removeAllViews()
        val apps = KioskPrefs.getAllowedApps(this)
        val pm = packageManager

        if (apps.isEmpty()) {
            binding.txtNoAllowedApps.visibility = View.VISIBLE
            return
        }
        binding.txtNoAllowedApps.visibility = View.GONE

        apps.forEach { (pkg, label) ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.kiosk_app_item, binding.containerAllowedApps, false)

            row.findViewById<ImageView>(R.id.imgKioskAppIcon).apply {
                try { setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) {}
            }
            row.findViewById<TextView>(R.id.txtKioskAppName).text = label
            row.findViewById<TextView>(R.id.txtKioskAppPkg).text = pkg
            row.findViewById<TextView>(R.id.btnRemoveKioskApp).setOnClickListener {
                KioskPrefs.removeAllowedApp(this, pkg)
                refreshList()
            }

            binding.containerAllowedApps.addView(row)

            // Divider between items (not after the last one)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 0, 0, 0) }
                setBackgroundColor(getColor(R.color.card_stroke))
            }
            binding.containerAllowedApps.addView(divider)
        }

        // Remove the last divider
        if (binding.containerAllowedApps.childCount > 0) {
            binding.containerAllowedApps.removeViewAt(
                binding.containerAllowedApps.childCount - 1
            )
        }
    }
}
