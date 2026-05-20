package devs.org.ultrafocus.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.SoftBlockManager
import devs.org.ultrafocus.utils.TemporaryAccessManager

class SoftBlockActivity : AppCompatActivity() {

    private lateinit var blockedPackage: String
    private lateinit var challengeCode: String

    private lateinit var tvCode: TextView
    private lateinit var etInput: EditText
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_soft_block)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.softBlockRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        blockedPackage = intent.getStringExtra("blocked_package") ?: run { goHome(); return }
        challengeCode  = intent.getStringExtra("challenge_code")  ?: run { goHome(); return }

        tvCode    = findViewById(R.id.tvChallengeCode)
        etInput   = findViewById(R.id.etChallengeInput)
        tvError   = findViewById(R.id.tvChallengeError)
        val btnSubmit = findViewById<MaterialButton>(R.id.btnSubmitChallenge)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancelSoftBlock)

        // Belt-and-suspenders paste blocking on top of NoPasteTextInputEditText
        etInput.isLongClickable = false

        renderChallenge()

        btnSubmit.setOnClickListener { handleSubmit() }
        btnCancel.setOnClickListener { goHome() }

        // Allow submitting via IME Done action
        etInput.setOnEditorActionListener { _, _, _ ->
            handleSubmit()
            true
        }
    }

    private fun renderChallenge() {
        // Display in groups of 4 chars separated by spaces for readability.
        // User must type the raw code without spaces — comparison is against challengeCode.
        tvCode.text = challengeCode.chunked(4).joinToString("   ")
        tvError.visibility = android.view.View.GONE
        etInput.text?.clear()
    }

    private fun handleSubmit() {
        val input = etInput.text.toString().trim()
        if (input == challengeCode) {
            // Correct — grant access for the per-app configured duration, then launch.
            val durationMs = SoftBlockManager.getAccessDurationMinutes(this, blockedPackage) * 60_000L
            TemporaryAccessManager.grantAccess(blockedPackage, durationMs)

            val launch = packageManager.getLaunchIntentForPackage(blockedPackage)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
            finish()
        } else {
            tvError.visibility = android.view.View.VISIBLE
            tvError.text = "Incorrect — try again"
            etInput.text?.clear()
            val shake = AnimationUtils.loadAnimation(this, R.anim.shake)
            etInput.startAnimation(shake)
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    // If the accessibility service fires again for the same soft-blocked app while
    // this screen is already showing, onNewIntent delivers a new challenge code.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newPackage = intent.getStringExtra("blocked_package")
        val newCode    = intent.getStringExtra("challenge_code")
        if (newCode != null && newPackage != null) {
            blockedPackage = newPackage
            challengeCode  = newCode
            renderChallenge()
        }
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        goHome()
    }
}
