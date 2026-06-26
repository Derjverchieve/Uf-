package devs.org.ultrafocus.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import devs.org.ultrafocus.adapters.PauseEventAdapter
import devs.org.ultrafocus.adapters.PauseRow
import devs.org.ultrafocus.adapters.SessionHistoryAdapter
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.databinding.ActivityDeepWorkSessionBinding
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.PauseReason
import devs.org.ultrafocus.model.SessionPhase
import devs.org.ultrafocus.model.SessionStatus
import devs.org.ultrafocus.repository.DeepWorkRepository
import devs.org.ultrafocus.services.DeepWorkSessionService
import devs.org.ultrafocus.utils.DeepWorkPrefs
import devs.org.ultrafocus.utils.DeepWorkExportManager
import devs.org.ultrafocus.utils.DeepWorkSessionManager
import devs.org.ultrafocus.utils.DurationFormatter
import devs.org.ultrafocus.utils.FocusScoreCalculator
import devs.org.ultrafocus.utils.KioskPrefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DeepWorkSessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeepWorkSessionBinding
    private lateinit var repository: DeepWorkRepository

    // Must be registered during Activity initialization (not inside onCreate
    // logic) — this satisfies that.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* face detection activates on next session start */ }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            val ok = DeepWorkExportManager.exportSessionsCsv(this, it)
            Toast.makeText(
                this,
                if (ok) "Exported." else "Export failed.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private var selectedAppPackage: String? = null
    private var selectedAppName: String? = null

    private lateinit var pauseLogAdapter: PauseEventAdapter
    private lateinit var breakdownAdapter: PauseEventAdapter
    private lateinit var historyAdapter: SessionHistoryAdapter

    private var pauseLogJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDeepWorkSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = DeepWorkRepository(AppDatabase.getDatabase(this))
        DeepWorkSessionManager.init(applicationContext)
        requestNotificationPermissionIfNeeded()

        setupRecyclerViews()
        restoreLastPrimaryApp()
        clickListeners()
        observeState()
        observeSummary()
        observeHistory()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupRecyclerViews() {
        pauseLogAdapter = PauseEventAdapter(emptyList())
        binding.recyclerPauseLog.layoutManager = LinearLayoutManager(this)
        binding.recyclerPauseLog.adapter = pauseLogAdapter

        breakdownAdapter = PauseEventAdapter(emptyList())
        binding.recyclerBreakdown.layoutManager = LinearLayoutManager(this)
        binding.recyclerBreakdown.adapter = breakdownAdapter

        historyAdapter = SessionHistoryAdapter(emptyList()) { session ->
            lifecycleScope.launch { showHistorySummary(session) }
        }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = historyAdapter
    }

    private fun restoreLastPrimaryApp() {
        val pkg = DeepWorkPrefs.getLastPrimaryAppPackage(this) ?: return
        val name = DeepWorkPrefs.getLastPrimaryAppName(this) ?: pkg
        if (isAppInstalled(pkg)) {
            selectedAppPackage = pkg
            selectedAppName = name
            binding.txtPrimaryAppName.text = name
        }
        binding.inputTargetMinutes.setText(DeepWorkPrefs.getLastTargetMinutes(this).toString())
        updateStartButtonEnabled()
    }

    private fun isAppInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // ── Click listeners ──────────────────────────────────────────────────

    private fun clickListeners() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rowPrimaryApp.setOnClickListener { showAppPicker() }

        binding.btnPreset25.setOnClickListener { binding.inputTargetMinutes.setText("25") }
        binding.btnPreset45.setOnClickListener { binding.inputTargetMinutes.setText("45") }
        binding.btnPreset60.setOnClickListener { binding.inputTargetMinutes.setText("60") }
        binding.btnPreset90.setOnClickListener { binding.inputTargetMinutes.setText("90") }

        binding.txtExportCsv.setOnClickListener {
            exportLauncher.launch("ultrafocus_deep_work_sessions.csv")
        }

        binding.rowLeaderboard.setOnClickListener {
            startActivity(Intent(this, LeaderboardActivity::class.java))
        }

        binding.rowKioskSetup.setOnClickListener {
            startActivity(Intent(this, KioskSetupActivity::class.java))
        }

        binding.btnStartSession.setOnClickListener { startSessionFromInputs() }

        binding.btnPauseResume.setOnClickListener {
            val state = DeepWorkSessionManager.state.value
            when {
                state.phase == SessionPhase.RUNNING -> DeepWorkSessionManager.pauseManually()
                state.phase == SessionPhase.PAUSED && state.currentPauseReason == PauseReason.MANUAL ->
                    DeepWorkSessionManager.resumeManually()
            }
        }

        binding.btnEndSession.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("End session?")
                .setMessage("This locks in your focus score for this session.")
                .setPositiveButton("End") { _, _ -> DeepWorkSessionManager.completeSession() }
                .setNegativeButton("Keep going", null)
                .show()
        }
    }

    private fun startSessionFromInputs() {
        val pkg = selectedAppPackage
        val name = selectedAppName
        if (pkg == null || name == null) {
            Toast.makeText(this, "Choose a primary work app first.", Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = binding.inputTargetMinutes.text.toString().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            Toast.makeText(this, "Enter a target duration in minutes.", Toast.LENGTH_SHORT).show()
            return
        }
        DeepWorkPrefs.saveLastTargetMinutes(this, minutes)

        DeepWorkSessionManager.startSession(pkg, name, TimeUnit.MINUTES.toMillis(minutes.toLong()))

        val intent = Intent(this, DeepWorkSessionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        Toast.makeText(this, "Session started — switch to $name now.", Toast.LENGTH_LONG).show()
    }

    // ── App picker ───────────────────────────────────────────────────────

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
            .associateBy({ it.packageName }, { it.loadLabel(pm).toString() })
            .toList()
            .sortedBy { it.second.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found.", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = apps.map { it.second }.toTypedArray()
        val preselected = apps.indexOfFirst { it.first == selectedAppPackage }

        MaterialAlertDialogBuilder(this)
            .setTitle("Primary work app")
            .setSingleChoiceItems(labels, preselected) { dialog, which ->
                val (pkg, name) = apps[which]
                selectedAppPackage = pkg
                selectedAppName = name
                binding.txtPrimaryAppName.text = name
                DeepWorkPrefs.saveLastPrimaryApp(this, pkg, name)
                updateStartButtonEnabled()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStartButtonEnabled() {
        binding.btnStartSession.isEnabled = selectedAppPackage != null
    }

    // ── Observers ────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            DeepWorkSessionManager.state.collectLatest { state ->
                render(state)
            }
        }
    }

    private fun render(state: devs.org.ultrafocus.model.DeepWorkUiState) {
        when (state.phase) {
            SessionPhase.IDLE -> {
                binding.cardLive.visibility = android.view.View.GONE
                binding.cardSetup.visibility = android.view.View.VISIBLE
                binding.groupPauseLog.visibility = android.view.View.GONE
                pauseLogJob?.cancel()
            }
            SessionPhase.RUNNING, SessionPhase.PAUSED -> {
                binding.cardSetup.visibility = android.view.View.GONE
                binding.cardLive.visibility = android.view.View.VISIBLE
                binding.groupSummary.visibility = android.view.View.GONE
                binding.groupPauseLog.visibility = android.view.View.VISIBLE

                if (state.phase == SessionPhase.RUNNING) {
                    binding.txtLiveState.text = "Focused — ${state.primaryAppName}"
                    binding.txtLiveState.setTextColor(getColor(devs.org.ultrafocus.R.color.unlocked_green))
                    binding.btnPauseResume.text = "Pause"
                    binding.btnPauseResume.isEnabled = true
                } else {
                    val who = state.currentPauseAppName ?: "away"
                    binding.txtLiveState.text = if (state.currentPauseReason == PauseReason.MANUAL)
                        "Paused" else "Paused — $who"
                    binding.txtLiveState.setTextColor(getColor(devs.org.ultrafocus.R.color.soft_block_amber))
                    if (state.currentPauseReason == PauseReason.MANUAL) {
                        binding.btnPauseResume.text = "Resume"
                        binding.btnPauseResume.isEnabled = true
                    } else {
                        binding.btnPauseResume.text = "Return to ${state.primaryAppName} to resume"
                        binding.btnPauseResume.isEnabled = false
                    }
                }

                val remaining = state.targetDurationMs - state.focusedTimeMs
                if (remaining >= 0) {
                    binding.txtLiveTimer.text = DurationFormatter.formatClock(remaining)
                    binding.txtLiveTimer.setTextColor(getColor(devs.org.ultrafocus.R.color.text_primary))
                } else {
                    binding.txtLiveTimer.text = "+" + DurationFormatter.formatClock(-remaining)
                    binding.txtLiveTimer.setTextColor(getColor(devs.org.ultrafocus.R.color.soft_block_amber))
                }
                binding.txtLiveSub.text =
                    "${DurationFormatter.formatCompact(state.focusedTimeMs)} focused · ${state.pauseCount} pause${if (state.pauseCount == 1) "" else "s"}" +
                    if (state.pauseTimeMs > 0) " · ${DurationFormatter.formatCompact(state.pauseTimeMs)} paused" else ""

                if (state.sessionId != null) observeLivePauseLog(state.sessionId)
            }
        }
    }

    private fun observeLivePauseLog(sessionId: Long) {
        if (pauseLogJob != null) return
        pauseLogJob = lifecycleScope.launch {
            repository.observePauseEventsForSession(sessionId).collectLatest { events ->
                val rows = events.sortedByDescending { it.startTime }.map {
                    PauseRow(
                        label = it.appName ?: "Manual pause",
                        durationMs = it.durationMs,
                        ongoing = it.endTime == null
                    )
                }
                pauseLogAdapter.submitList(rows)
            }
        }
    }

    private fun observeSummary() {
        lifecycleScope.launch {
            DeepWorkSessionManager.lastSummary.collectLatest { summary ->
                if (summary == null) return@collectLatest
                if (DeepWorkSessionManager.state.value.phase != SessionPhase.IDLE) return@collectLatest
                pauseLogJob?.cancel(); pauseLogJob = null
                binding.cardLive.visibility = android.view.View.GONE
                binding.groupPauseLog.visibility = android.view.View.GONE
                binding.cardSetup.visibility = android.view.View.VISIBLE
                binding.groupSummary.visibility = android.view.View.VISIBLE

                binding.txtSummaryScore.text = summary.focusScore.toString()
                binding.txtSummaryHeadline.text =
                    if (summary.wasCancelled) "${summary.primaryAppName} · cancelled" else summary.primaryAppName
                binding.txtSummaryStats.text = buildString {
                    append("Wall clock: ${DurationFormatter.formatCompact(summary.wallClockMs)}\n")
                    append("Focused: ${DurationFormatter.formatCompact(summary.focusedTimeMs)}\n")
                    append("Paused: ${DurationFormatter.formatCompact(summary.pauseTimeMs)} (${summary.pauseCount} breaks)\n")
                    append("Avg focus segment: ${DurationFormatter.formatCompact(summary.averageFocusSegmentMs)}")
                }

                val breakdownRows = summary.breakdownByApp.map { PauseRow(it.appName, it.totalMs) }
                breakdownAdapter.submitList(breakdownRows)
                binding.txtBreakdownHeader.visibility =
                    if (breakdownRows.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            repository.observeAllSessions().collectLatest { sessions ->
                val finished = sessions.filter { it.status != SessionStatus.RUNNING }
                historyAdapter.submitList(finished)
                binding.txtNoHistory.visibility =
                    if (finished.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep kiosk status label in sync when returning from KioskSetupActivity
        binding.txtKioskStatus.text = if (KioskPrefs.isKioskEnabled(this)) "On" else "Off"
        // Camera permission — needed for face-presence auto-pause.
        // Request once silently; user can re-prompt from system settings if denied.
        if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
            checkSelfPermission(android.Manifest.permission.CAMERA)) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private suspend fun showHistorySummary(session: FocusSession) {
        if (DeepWorkSessionManager.state.value.phase != SessionPhase.IDLE) {
            Toast.makeText(this, "Finish the current session first.", Toast.LENGTH_SHORT).show()
            return
        }
        val pauses = repository.getPauseEventsForSession(session.id)
        val breakdown = pauses
            .groupBy { pause ->
                pause.appPackage ?: when {
                    !pause.appName.isNullOrBlank() -> pause.appName
                    pause.reason == PauseReason.MANUAL -> "Manual pause"
                    else -> pause.reason.name.lowercase()
                        .replaceFirstChar { it.uppercase() }.replace('_', ' ')
                }
            }
            .map { (_, evts) ->
                val label = evts.firstOrNull { !it.appName.isNullOrBlank() }?.appName
                    ?: if (evts.first().reason == PauseReason.MANUAL) "Manual pause"
                    else evts.first().reason.name.lowercase()
                        .replaceFirstChar { it.uppercase() }.replace('_', ' ')
                PauseRow(
                    label = label,
                    durationMs = evts.sumOf { it.durationMs }
                )
            }
            .sortedByDescending { it.durationMs }

        val score = session.focusScore ?: FocusScoreCalculator.calculate(session.focusedTimeMs, session.pauseTimeMs)
        val segments = (session.pauseCount + 1).coerceAtLeast(1)
        val avgSegment = session.focusedTimeMs / segments
        val wallClock = (session.endTime ?: session.startTime) - session.startTime

        binding.cardLive.visibility = android.view.View.GONE
        binding.groupPauseLog.visibility = android.view.View.GONE
        binding.cardSetup.visibility = android.view.View.VISIBLE
        binding.groupSummary.visibility = android.view.View.VISIBLE

        binding.txtSummaryScore.text = score.toString()
        binding.txtSummaryHeadline.text =
            if (session.status == SessionStatus.CANCELLED) "${session.primaryAppName} · cancelled" else session.primaryAppName
        binding.txtSummaryStats.text = buildString {
            append("Wall clock: ${DurationFormatter.formatCompact(wallClock)}\n")
            append("Focused: ${DurationFormatter.formatCompact(session.focusedTimeMs)}\n")
            append("Paused: ${DurationFormatter.formatCompact(session.pauseTimeMs)} (${session.pauseCount} breaks)\n")
            append("Avg focus segment: ${DurationFormatter.formatCompact(avgSegment)}")
        }
        breakdownAdapter.submitList(breakdown)
        binding.txtBreakdownHeader.visibility =
            if (breakdown.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}
