package devs.org.ultrafocus.activities

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import devs.org.ultrafocus.databinding.ActivityDegradedModeBinding
import devs.org.ultrafocus.utils.EscapeManager
import java.util.concurrent.TimeUnit

class DegradedModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDegradedModeBinding
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDegradedModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Trap User
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing - user is trapped
            }
        })

        // 2. Manual Exit Logic (No more auto-closing glitches)
        binding.btnReEnable.setOnClickListener {
            if (isAccessibilityEnabled(this)) {
                // If user fixed it, let them go
                EscapeManager.restoreNormalState(this)
                Toast.makeText(this, "Restored.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // If not fixed, send them to settings
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        startTimer()
        updateBattery()
    }

    override fun onResume() {
        super.onResume()
        // Optional: Only check on Resume (when user comes back from Settings)
        if (isAccessibilityEnabled(this)) {
            EscapeManager.restoreNormalState(this)
            finish()
        }
    }

    private fun startTimer() {
        var remaining = EscapeManager.getTimeRemaining(this)
        if (remaining < 1000) remaining = 600000L // 10 min default

        timer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val h = TimeUnit.MILLISECONDS.toHours(millisUntilFinished)
                val m = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished) % 60
                val s = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60
                binding.tvTimer.text = String.format("%02d:%02d:%02d", h, m, s)
            }

            override fun onFinish() {
                binding.tvTimer.text = "00:00:00"
                binding.tvStatus.text = "ACCESS RESTORED"
                // Allow exit by clicking the button now
                binding.btnReEnable.text = "EXIT (Timer Finished)"
                binding.btnReEnable.setOnClickListener {
                    finish()
                }
            }
        }.start()
    }

    private fun updateBattery() {
        try {
            val bm = applicationContext.getSystemService(BATTERY_SERVICE) as BatteryManager
            val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            binding.tvBattery.text = "Battery: $batLevel%"
        } catch (e: Exception) {
            // Ignore battery errors
        }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains("${context.packageName}/devs.org.ultrafocus.services.BlockerAccessibilityService")
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}