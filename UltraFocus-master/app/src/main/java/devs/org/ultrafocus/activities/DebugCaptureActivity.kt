package devs.org.ultrafocus.activities

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import devs.org.ultrafocus.databinding.ActivityDebugCaptureBinding
import devs.org.ultrafocus.utils.DebugCaptureManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugCaptureBinding
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDebugCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(devs.org.ultrafocus.R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.switchDebugEnabled.isChecked = DebugCaptureManager.isEnabled(this)

        binding.switchDebugEnabled.setOnCheckedChangeListener { _, isChecked ->
            DebugCaptureManager.setEnabled(this, isChecked)
            refresh()
        }

        binding.btnArmCapture.setOnClickListener {
            DebugCaptureManager.armCapture(this)
            Toast.makeText(this, "Capture armed for 15 seconds.", Toast.LENGTH_SHORT).show()
            refresh()
        }

        binding.btnRefresh.setOnClickListener { refresh() }

        binding.btnClear.setOnClickListener {
            DebugCaptureManager.clear(this)
            Toast.makeText(this, "Debug captures cleared.", Toast.LENGTH_SHORT).show()
            refresh()
        }

        binding.btnExport.setOnClickListener { exportLog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = DebugCaptureManager.isEnabled(this)
        val armed = DebugCaptureManager.isCaptureArmed(this)
        val entries = DebugCaptureManager.getEntries(this)

        binding.switchDebugEnabled.isChecked = enabled
        binding.txtStatus.text = buildString {
            append("Logging: ")
            append(if (enabled) "ON" else "OFF")
            append("\n")
            append("Capture armed: ")
            append(if (armed) "YES" else "NO")
            append("\n")
            append("Entries: ")
            append(entries.size)
        }

        binding.txtLog.text = if (entries.isEmpty()) {
            "No debug captures yet.\n\nTap \"Arm next capture\" or reproduce the issue with logging enabled."
        } else {
            entries.joinToString(separator = "\n\n") { entry ->
                DebugCaptureManager.formatEntry(this, entry).let {
                    if (entry.treeDump.isNullOrBlank()) it else "$it\n${entry.treeDump}"
                }
            }
        }
    }

    private fun exportLog() {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val folder = File(baseDir, "debug_captures").apply { mkdirs() }
        val output = File(folder, "uf_debug_${fileStamp.format(Date())}.txt")

        output.writeText(DebugCaptureManager.exportText(this))
        Toast.makeText(this, "Exported to ${output.absolutePath}", Toast.LENGTH_LONG).show()
    }
}
