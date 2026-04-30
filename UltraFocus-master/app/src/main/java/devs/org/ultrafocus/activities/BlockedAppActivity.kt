package devs.org.ultrafocus.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.databinding.ActivityBlockedAppBinding
import devs.org.ultrafocus.utils.TemporaryAccessManager
import devs.org.ultrafocus.utils.TypeToAccessDialog

class BlockedAppActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedAppBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBlockedAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle Window Insets (Edge-to-Edge display)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 1. Identify what was blocked (Package Name or URL)
        val blockedPackage = intent.getStringExtra("blocked_package") ?: ""
        Log.d("BlockedAppActivity", "Blocking user from: $blockedPackage")

        // 2. "Back to Focus" Button (Go Home)
        binding.btnBackToFocus.setOnClickListener {
            goHome()
        }

        // 3. EMERGENCY OVERRIDE (Long Press)
        // Triggers the "Type to Access" challenge (Exponential Punishment + Rose Protocol)
        binding.btnBackToFocus.setOnLongClickListener {
            TypeToAccessDialog.show(this, "Emergency Override") {
                // This code runs ONLY if the user passes the challenge

                // A. Grant 5 minutes of access
                TemporaryAccessManager.grantAccess(blockedPackage)

                // B. Notify user
                Toast.makeText(this, "Access Granted for 5 minutes.", Toast.LENGTH_LONG).show()

                // C. Close this screen so they can use the app
                finish()
            }
            true // Return true to indicate we handled the long press
        }
    }

    private fun goHome() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
        finish()
    }

    // 4. Disable the Back Button (Trap the user)
    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Do nothing. This prevents the user from pressing 'Back' to return to the blocked app.
        // They must press 'Home' or the 'Back to Focus' button.
    }

    // Ensure new intents update the screen properly
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}