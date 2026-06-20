package devs.org.ultrafocus.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.databinding.ActivityBlockedAppBinding

class BlockedAppActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedAppBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBlockedAppBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val blockedPackage = intent.getStringExtra("blocked_package") ?: ""
        Log.d("BlockedAppActivity", "Blocking user from: $blockedPackage")

        // "Back to Focus" — only action available. No emergency bypass exists.
        // The only way out of a hard block is to wait out the strict mode cooldown
        // via system accessibility settings.
        binding.btnBackToFocus.setOnClickListener {
            goHome()
        }
    }

    private fun goHome() {
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)
        finish()
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Disabled — user cannot press back to return to the blocked app.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
