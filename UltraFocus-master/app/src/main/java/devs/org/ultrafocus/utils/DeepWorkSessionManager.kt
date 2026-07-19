package devs.org.ultrafocus.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.AppBreakItem
import devs.org.ultrafocus.model.DeepWorkUiState
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.PauseEvent
import devs.org.ultrafocus.model.PauseReason
import devs.org.ultrafocus.model.SessionPhase
import devs.org.ultrafocus.model.SessionStatus
import devs.org.ultrafocus.model.SessionSummary
import devs.org.ultrafocus.repository.DeepWorkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth for the active deep-work session.
 *
 * This is a process-wide singleton (not an Android component) so the same
 * instance can be driven from three different places that all run inside
 * this app's single process:
 *  - DeepWorkSessionActivity      (user taps Start / Pause / Resume / End)
 *  - BlockerAccessibilityService  (reports foreground-app changes for auto-pause)
 *  - DeepWorkSessionService       (foreground notification, keeps the process alive)
 *
 * Auto-pause design:
 *  Leaving the primary app does NOT immediately log a pause. A short grace
 *  period (GRACE_PERIOD_MS) starts instead. If the user is back in the
 *  primary app before it elapses, nothing is recorded — the whole blip is
 *  forgiven and counts as continued focus. If the grace period elapses, a
 *  PauseEvent is logged starting from the moment the grace period itself
 *  expires (not from when they first left) — the grace window counts as
 *  continued focus, not as part of the pause.
 *
 *  Exception: system UI and the HiOS launcher (identified by
 *  BlockerAccessibilityService, passed in as immediatePauseLabel) skip this
 *  grace entirely and pause the instant you land there. This is a separate
 *  clock from the kiosk block itself, which still waits a few seconds before
 *  bouncing you back — a quick glance is forgiven from being BLOCKED, it
 *  just isn't counted as focus while it lasts.
 *
 *  Returning to the primary app always resumes immediately — no grace
 *  delay on the way back in.
 *
 *  Switching between two *different* non-work apps while already paused
 *  goes through its own short debounce (SUB_SWITCH_GRACE_MS) before the
 *  breakdown's "current app" actually changes. This absorbs OS-level window
 *  flicker — some launchers/skins fire spurious foreground-window events
 *  for their own UI (gesture handling, edge panels, etc.) without the user
 *  actually navigating anywhere — without inflating the pause count (a
 *  "pause" = one continuous break from work, however many apps, real or
 *  phantom, flicker past during it).
 *
 *  An auto-pause can only be cleared by actually returning to the primary
 *  app — resumeManually() deliberately refuses to clear anything but a
 *  MANUAL pause. Letting a button override the auto-detection would defeat
 *  the point of it being honest by default.
 */
object DeepWorkSessionManager {

    // A pause that's currently open. `id` starts at 0 and is mutated IN
    // PLACE once its INSERT actually completes — see writeQueue below for
    // why that matters. Plain class (not data class) on purpose: nothing
    // should ever .copy() this and silently fork the id away from the
    // shared object the queue updates.
    private class OpenPause(
        var id: Long = 0L,
        val sessionId: Long,
        val startTime: Long,
        val reason: PauseReason,
        val appPackage: String?,
        val appName: String?
    )

    // A scheduled work+break sequence. The in-memory copy here is the fast
    // path (reads/writes work off this directly), but it's mirrored to
    // DeepWorkPrefs on every segment transition specifically so it SURVIVES
    // process death and reboot — see recoverOrphanedSession,
    // resumeWorkSegmentFromRow, and resumeBreakFromPrefs. Rebooting changes
    // nothing about when the plan (or the current segment within it) is
    // actually free to end: work segments resume from their last honest
    // Room checkpoint, and breaks resume against a fixed wall-clock
    // deadline set when they started — never a fresh countdown either way.
    private data class CyclePlan(
        val primaryAppPackage: String,
        val primaryAppName: String,
        val workMinutes: Int,
        val breakMinutes: Int,
        val totalCycles: Int,
        val currentCycleIndex: Int
    )

    private var activeCyclePlan: CyclePlan? = null

    private const val GRACE_PERIOD_MS = 10_000L
    private const val SUB_SWITCH_GRACE_MS = 3_000L
    private const val CHECKPOINT_INTERVAL_TICKS = 30 // checkpoint to DB roughly every ~30s while running

    private lateinit var appContext: Context
    private lateinit var repository: DeepWorkRepository
    private var initialized = false

    // Everything below this point only ever runs on the main thread: this
    // dispatcher, AccessibilityService callbacks, and Activity/Service calls
    // are all delivered on the same main looper, so plain vars are safe
    // without extra locking — see the class doc for why.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DeepWorkUiState())
    val state: StateFlow<DeepWorkUiState> = _state.asStateFlow()

    private val _lastSummary = MutableStateFlow<SessionSummary?>(null)
    val lastSummary: StateFlow<SessionSummary?> = _lastSummary.asStateFlow()

    // In-memory mirror of the active session row — the fast path. Persisted
    // to Room at every real transition plus a periodic checkpoint, but
    // reads/writes during a session work off this copy rather than hitting
    // the DB every time.
    private var currentSession: FocusSession? = null
    private var openPauseEvent: OpenPause? = null

    // Every session/pause DB write goes through here instead of its own
    // independent coroutine. A single consumer drains it, so writes always
    // execute in exactly the order they were enqueued — no matter how fast
    // new ones get added (e.g. a rapid burst of app-switch events from an
    // OS gesture animation, easily faster than a single Room round-trip).
    // This is what makes it safe for a later write to read an OpenPause's
    // `id` at execution time — by the time it runs, the earlier write that
    // resolves that id is guaranteed to have already finished.
    private val writeQueue = Channel<suspend () -> Unit>(capacity = Channel.UNLIMITED)

    private fun enqueueWrite(block: suspend () -> Unit) {
        writeQueue.trySend(block)
    }

    private var lastFocusStartTimestamp: Long = 0L
    private var lastSeenForegroundPackage: String? = null
    private var graceJob: Job? = null
    private var subSwitchGraceJob: Job? = null
    private var tickerJob: Job? = null
    private var tickCount = 0
    private var screenReceiver: BroadcastReceiver? = null
    private var targetReachedFired = false

    // Absolute safety ceiling, independent of the reboot-immunity above:
    // the wall-clock timestamp — set ONCE, at the true start of a session
    // or plan, never touched again until it fully ends — past which
    // everything force-terminates regardless of what cycle/segment is in
    // progress. Computed as "the next midnight after start", so it's the
    // same fixed deadline no matter how many times the phone reboots in
    // between; see nextMidnightAfter and forceEndForMidnight.
    private var midnightCutoffMs: Long? = null

    // Set by DeepWorkSessionService so it can show a distinct "ended at
    // midnight" notification, rather than the usual completion one.
    var onMidnightCutoff: (() -> Unit)? = null

    // Set by DeepWorkSessionService so it can fire an alert (sound/vibration/
    // notification) the moment the target is hit — kept out of the Manager
    // itself since that's an Android-specific concern, not session logic.
    var onTargetReached: (() -> Unit)? = null

    // Set by DeepWorkSessionService. Fires every time a work segment
    // begins — the very first one, every cycle-transition after a break,
    // AND on a boot-recovery resume. The very first, manually-started
    // segment ALSO gets launched directly and immediately from the Start
    // button tap in DeepWorkSessionActivity (a live user gesture, always
    // safe); this callback exists for every OTHER case, which has no
    // preceding gesture at all and needs a full-screen-intent notification
    // instead — the only Android-sanctioned way to launch an activity from
    // a background context. See DeepWorkSessionService.fireWorkSegmentStartAlert.
    var onWorkSegmentStarted: (() -> Unit)? = null

    // Set by BlockerAccessibilityService. Returns the package actually
    // active right now (via the accessibility windows list), independent of
    // whatever the last WINDOW_STATE_CHANGED event claimed. Polled every
    // tick (see tick() below) as a correction against event-stream noise —
    // some launchers/skins can report a stale or spurious foreground app
    // for a long stretch (confirmed during testing: minutes, not seconds),
    // and no amount of grace-period tuning can fully cover that since it's
    // not really a "how long do we wait" problem. This catches it directly:
    // whatever's actually active gets confirmed (or corrected to) within
    // about a second, no matter how long the bad signal has been going.
    var groundTruthProvider: (() -> String?)? = null

    // Set by BlockerAccessibilityService. Given a package name, returns the
    // friendly immediate-pause label if it's system UI or the HiOS launcher,
    // null otherwise — lets tick()'s ground-truth correction path apply the
    // exact same immediate-pause treatment as the primary accessibility-event
    // path (see onForegroundAppChanged), even on the rare occasion ground
    // truth is what first catches a systemui/launcher transition. Keeps this
    // file free of hardcoded OEM package names — that list lives in
    // BlockerAccessibilityService's kioskGracePackages, the single source of truth.
    var kioskGraceLabelProvider: ((String) -> String?)? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        repository = DeepWorkRepository(AppDatabase.getDatabase(appContext))
        initialized = true
        registerScreenOffReceiver()
        managerScope.launch {
            for (write in writeQueue) {
                try { write() } catch (_: Exception) {}
            }
        }
        managerScope.launch { recoverOrphanedSession() }
    }

    // Locking the phone doesn't change which app/window is "foreground" from
    // the accessibility service's point of view, so TYPE_WINDOW_STATE_CHANGED
    // alone won't catch it. This is the actual, reliable signal for it.
    private fun registerScreenOffReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiver = receiver
    }

    private fun onScreenOff() {
        if (_state.value.phase != SessionPhase.RUNNING) return
        currentSession ?: return
        // No grace period here — you can't be working if you can't see the
        // screen. Resuming still requires actually being back in the primary
        // app afterward (handled normally once the screen wakes up and the
        // accessibility service reports it), not just the screen turning on.
        graceJob?.cancel(); graceJob = null
        lastSeenForegroundPackage = null
        openNewPause(System.currentTimeMillis(), PauseReason.APP_SWITCH, appPackage = null, appName = "Screen off")
    }

    fun hasActiveSession(): Boolean = _state.value.phase != SessionPhase.IDLE || _state.value.onBreak

    // ── Crash / process-death recovery ──────────────────────────────────────

    private suspend fun recoverOrphanedSession() {
        try {
            val orphan = repository.getRunningSession()
            val planActive = DeepWorkPrefs.isPlanActive(appContext)

            if (orphan != null) {
                // A work segment was RUNNING when the process died (crash,
                // reboot, whatever caused it). Resume it exactly where its
                // last checkpoint left off — reconstructing exact
                // wall-clock focused time across the gap is unknowable (we
                // have no way to know if the person was actually in the app
                // the whole time the phone was off), so crediting or
                // debiting a guess would be worse than honestly resuming
                // from the last real checkpoint, same principle this
                // function already followed before — just resuming instead
                // of finalizing now.
                resumeWorkSegmentFromRow(orphan, planActive)
            } else if (planActive && DeepWorkPrefs.getPlanSegment(appContext) == "BREAK") {
                // Process died mid-break — there's no Room row for that at
                // all (breaks aren't a FocusSession), so there's nothing to
                // find as an "orphan"; the break lives entirely in the
                // persisted plan state instead.
                resumeBreakFromPrefs()
            } else if (planActive) {
                // Persisted state says a plan should be active but there's
                // no RUNNING row and no BREAK segment recorded — an
                // inconsistent combination we can't safely resume into.
                // Clear it rather than risk getting stuck in an undefined
                // configuration.
                DeepWorkPrefs.clearActivePlan(appContext)
            }
        } catch (_: Exception) {
            // Best-effort recovery — if it fails, whatever's inconsistent
            // just doesn't resume; it doesn't take the app down with it.
        }
    }

    private fun resumeWorkSegmentFromRow(orphan: FocusSession, planActive: Boolean) {
        val now = System.currentTimeMillis()
        currentSession = orphan
        openPauseEvent = null // any pause that was open died with the process too; resume as running
        lastFocusStartTimestamp = now
        lastSeenForegroundPackage = appContext.packageName
        tickCount = 0
        targetReachedFired = false

        var cycleIndex = 0
        var totalCycles = 0
        if (planActive) {
            DeepWorkPrefs.readActivePlan(appContext)?.let { restored ->
                activeCyclePlan = CyclePlan(
                    primaryAppPackage = restored.primaryAppPackage,
                    primaryAppName = restored.primaryAppName,
                    workMinutes = restored.workMinutes,
                    breakMinutes = restored.breakMinutes,
                    totalCycles = restored.totalCycles,
                    currentCycleIndex = restored.currentCycleIndex
                )
                cycleIndex = restored.currentCycleIndex
                totalCycles = restored.totalCycles
            }
            midnightCutoffMs = DeepWorkPrefs.getPlanMidnightCutoffMs(appContext).takeIf { it > 0L }
        } else {
            // Standalone session — derive the SAME cutoff it would have had
            // originally, straight from when it actually started. No
            // separate persistence needed for this simpler case since Room
            // already durably captured startTime.
            midnightCutoffMs = nextMidnightAfter(orphan.startTime)
        }

        // The phone could have been off long enough that the cutoff already
        // passed before we even got this far — don't resume into an
        // already-expired state, end it right here instead.
        midnightCutoffMs?.let { cutoff ->
            if (now >= cutoff) {
                forceEndForMidnight()
                return
            }
        }

        _state.value = DeepWorkUiState(
            phase = SessionPhase.RUNNING,
            sessionId = orphan.id,
            primaryAppPackage = orphan.primaryAppPackage,
            primaryAppName = orphan.primaryAppName,
            targetDurationMs = orphan.targetDurationMs,
            focusedTimeMs = orphan.focusedTimeMs,
            pauseTimeMs = orphan.pauseTimeMs,
            pauseCount = orphan.pauseCount,
            sessionStartTime = orphan.startTime,
            cycleIndex = cycleIndex,
            totalCycles = totalCycles
        )

        startTicker()
        startResumedService()
        onWorkSegmentStarted?.invoke()
    }

    private fun resumeBreakFromPrefs() {
        val restored = DeepWorkPrefs.readActivePlan(appContext) ?: run {
            DeepWorkPrefs.clearActivePlan(appContext)
            return
        }
        val plan = CyclePlan(
            primaryAppPackage = restored.primaryAppPackage,
            primaryAppName = restored.primaryAppName,
            workMinutes = restored.workMinutes,
            breakMinutes = restored.breakMinutes,
            totalCycles = restored.totalCycles,
            currentCycleIndex = restored.currentCycleIndex
        )
        activeCyclePlan = plan
        midnightCutoffMs = DeepWorkPrefs.getPlanMidnightCutoffMs(appContext).takeIf { it > 0L }

        // Checked before anything else resumes — the phone could have been
        // off long enough that the cutoff already passed.
        midnightCutoffMs?.let { cutoff ->
            if (System.currentTimeMillis() >= cutoff) {
                forceEndForMidnight()
                return
            }
        }

        // Anchored to the wall-clock deadline set when the break started —
        // never a fresh countdown. Reboot any number of times and this
        // still reads the same true remaining time (or none at all).
        val endAt = DeepWorkPrefs.getPlanBreakEndAtMs(appContext)
        val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)

        if (remaining <= 0L) {
            // The break's own deadline already passed while the phone was
            // off — don't grant a bonus fresh break, just move straight to
            // whatever comes next.
            advanceCyclePlanAfterBreak(plan)
            return
        }

        _state.value = DeepWorkUiState(
            onBreak = true,
            breakRemainingMs = remaining,
            cycleIndex = plan.currentCycleIndex,
            totalCycles = plan.totalCycles,
            primaryAppPackage = plan.primaryAppPackage,
            primaryAppName = plan.primaryAppName
        )
        startTicker()
        startResumedService()
    }

    // Foreground services don't restart on their own after process death —
    // only the accessibility service and this manager's own init() do that
    // automatically. Starting from BOOT_COMPLETED (which is how recovery
    // gets triggered promptly after a reboot — see BootCompletedReceiver)
    // is an explicit, Android-documented exemption from the usual
    // background foreground-service-start restriction.
    private fun startResumedService() {
        try {
            ContextCompat.startForegroundService(
                appContext, Intent(appContext, devs.org.ultrafocus.services.DeepWorkSessionService::class.java)
            )
        } catch (_: Exception) {}
    }

    // ── Public controls (called from the UI / service) ──────────────────────

    // The next occurrence of 00:00 local time strictly after `timestamp`.
    // Deliberately computed once at true session/plan start and never
    // recomputed from a later segment's own start time — see
    // midnightCutoffMs's field doc.
    private fun nextMidnightAfter(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun startSession(primaryAppPackage: String, primaryAppName: String, targetDurationMs: Long) {
        if (!initialized || _state.value.phase != SessionPhase.IDLE || _state.value.onBreak) return
        activeCyclePlan = null
        midnightCutoffMs = nextMidnightAfter(System.currentTimeMillis())
        startWorkSegment(primaryAppPackage, primaryAppName, targetDurationMs, cycleIndex = 0, totalCycles = 0)
    }

    /**
     * Schedules a work+break sequence: totalCycles repetitions of
     * (workMinutes of locked focus, then breakMinutes fully unlocked), back
     * to back, with no manual step needed between segments. The whole
     * sequence carries the SAME hard lock as a single session — can't be
     * cancelled until it's over — just extended across every cycle instead
     * of one. Bounds are re-validated here defensively even though the
     * caller (DeepWorkSessionActivity) already checks them.
     */
    fun startCyclePlan(primaryAppPackage: String, primaryAppName: String, workMinutes: Int, breakMinutes: Int, totalCycles: Int) {
        if (!initialized || _state.value.phase != SessionPhase.IDLE || _state.value.onBreak) return
        val plan = CyclePlan(
            primaryAppPackage = primaryAppPackage,
            primaryAppName = primaryAppName,
            workMinutes = workMinutes.coerceIn(1, DeepWorkPrefs.MAX_TARGET_MINUTES),
            breakMinutes = breakMinutes.coerceIn(DeepWorkPrefs.MIN_BREAK_MINUTES, DeepWorkPrefs.MAX_BREAK_MINUTES),
            totalCycles = totalCycles.coerceIn(DeepWorkPrefs.MIN_CYCLES, DeepWorkPrefs.MAX_CYCLES),
            currentCycleIndex = 1
        )
        activeCyclePlan = plan
        midnightCutoffMs = nextMidnightAfter(System.currentTimeMillis())
        startWorkSegment(
            plan.primaryAppPackage, plan.primaryAppName,
            plan.workMinutes * 60_000L, plan.currentCycleIndex, plan.totalCycles
        )
    }

    // Shared by both startSession (cycleIndex=0, totalCycles=0 — "not part
    // of a plan") and startCyclePlan/advanceCyclePlanAfterBreak (cycleIndex
    // 1-based within an active plan). Deliberately does NOT touch
    // activeCyclePlan itself — callers own that decision.
    private fun startWorkSegment(
        primaryAppPackage: String,
        primaryAppName: String,
        targetDurationMs: Long,
        cycleIndex: Int,
        totalCycles: Int
    ) {
        managerScope.launch {
            val now = System.currentTimeMillis()
            val session = FocusSession(
                primaryAppPackage = primaryAppPackage,
                primaryAppName = primaryAppName,
                targetDurationMs = targetDurationMs,
                startTime = now,
                updatedAt = now,
                status = SessionStatus.RUNNING
            )
            val id = repository.insertSession(session)
            currentSession = session.copy(id = id)
            openPauseEvent = null
            lastFocusStartTimestamp = now
            graceJob?.cancel(); graceJob = null
            subSwitchGraceJob?.cancel(); subSwitchGraceJob = null
            tickCount = 0
            targetReachedFired = false
            // Best guess at "where we are right now" — almost always our own
            // app, since the user is looking at the Start button. See the
            // class doc: this flows through the exact same evaluateForeground
            // path as any later app switch, so no special-casing is needed.
            lastSeenForegroundPackage = appContext.packageName

            _state.value = DeepWorkUiState(
                phase = SessionPhase.RUNNING,
                sessionId = id,
                primaryAppPackage = primaryAppPackage,
                primaryAppName = primaryAppName,
                targetDurationMs = targetDurationMs,
                sessionStartTime = now,
                cycleIndex = cycleIndex,
                totalCycles = totalCycles
            )

            // Persist enough to resume this exact segment (and the plan
            // around it, if any) after a reboot — see recoverOrphanedSession.
            // An ordinary (non-plan) session needs nothing extra here: the
            // FocusSession row Room just wrote above IS its full resume
            // state on its own.
            val plan = activeCyclePlan
            if (plan != null) {
                DeepWorkPrefs.writeActiveWorkSegment(
                    appContext,
                    DeepWorkPrefs.PersistedCyclePlan(
                        plan.primaryAppPackage, plan.primaryAppName,
                        plan.workMinutes, plan.breakMinutes, plan.totalCycles, plan.currentCycleIndex
                    ),
                    midnightCutoffMs ?: 0L
                )
            } else {
                DeepWorkPrefs.clearActivePlan(appContext)
            }

            startTicker()
            evaluateForeground(lastSeenForegroundPackage!!)
            onWorkSegmentStarted?.invoke()
        }
    }

    fun pauseManually() {
        if (_state.value.phase != SessionPhase.RUNNING) return
        currentSession ?: return
        graceJob?.cancel(); graceJob = null
        openNewPause(System.currentTimeMillis(), PauseReason.MANUAL, appPackage = null, appName = null)
    }

    fun resumeManually() {
        if (_state.value.phase != SessionPhase.PAUSED) return
        if (openPauseEvent?.reason != PauseReason.MANUAL) return
        closeCurrentPause(System.currentTimeMillis())
    }

    fun completeSession() = finalizeSession(SessionStatus.COMPLETED)

    fun cancelSession() = finalizeSession(SessionStatus.CANCELLED)

    // ── Physical-presence callbacks (called by FacePresenceDetector via DeepWorkSessionService) ──

    /**
     * Face detected again after an AUTO_AWAY absence — auto-resume ONLY if the
     * pause was caused by face-absence. APP_SWITCH and MANUAL pauses require their
     * own specific actions to clear and must never be overridden by the camera.
     */
    fun onFacePresent() {
        if (_state.value.phase != SessionPhase.PAUSED) return
        val open = openPauseEvent ?: return
        if (open.reason != PauseReason.AUTO_AWAY) return
        closeCurrentPause(System.currentTimeMillis())
    }

    /**
     * No face detected for the full grace period — auto-pause if currently
     * RUNNING. If already paused for any other reason, leave that reason intact.
     * Also cancels any pending app-leave grace: you can't simultaneously be
     * "switching away from the app" and "walking away from the screen".
     */
    fun onFaceAbsent() {
        if (_state.value.phase != SessionPhase.RUNNING) return
        currentSession ?: return
        graceJob?.cancel(); graceJob = null
        openNewPause(System.currentTimeMillis(), PauseReason.AUTO_AWAY, appPackage = null, appName = "Away from screen")
    }

    // ── Called from BlockerAccessibilityService on every foreground-app change ──

    fun onForegroundAppChanged(
        packageName: String,
        className: String? = null,
        isExemptWindowType: Boolean = false,
        immediatePauseLabel: String? = null
    ) {
        if (!initialized) return
        // Any part of OUR OWN app — not just the session screen — is exempt.
        // This used to be scoped to just the session screen's exact class
        // name, but that's brittle: some OEM skins don't report activity
        // class names reliably, which made checking your own session status
        // look exactly like leaving the work app. Broader but safer: being
        // anywhere in UltraFocus never counts as a departure.
        if (packageName == appContext.packageName) return
        if (isCurrentInputMethod(packageName)) return
        // A window the OS itself classifies as IME / accessibility overlay /
        // split-screen divider / magnifier — never really "a different app",
        // regardless of what package happens to own it. Deliberately doesn't
        // cover TYPE_SYSTEM (notification shade) — a flicker and a genuine
        // multi-second check report the same window type, so duration (the
        // grace period) is the only thing that can tell those apart.
        if (isExemptWindowType) return
        if (_state.value.phase == SessionPhase.IDLE) return
        if (packageName == lastSeenForegroundPackage) return
        lastSeenForegroundPackage = packageName
        evaluateForeground(packageName, immediatePauseLabel)
    }

    // Soft keyboards (Gboard, Samsung Keyboard, SwiftKey, etc.) can register
    // as their own accessibility window separate from the app you're typing
    // into, which would otherwise look exactly like leaving the primary app.
    // Whichever IME is currently active is never treated as a departure.
    private fun isCurrentInputMethod(packageName: String): Boolean {
        return try {
            val imeId = android.provider.Settings.Secure.getString(
                appContext.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
            )
            imeId != null && imeId.substringBefore("/") == packageName
        } catch (_: Exception) {
            false
        }
    }

    private fun evaluateForeground(packageName: String, immediatePauseLabel: String? = null) {
        val session = currentSession ?: return
        val now = System.currentTimeMillis()
        val phase = _state.value.phase

        if (packageName == session.primaryAppPackage) {
            graceJob?.cancel(); graceJob = null
            subSwitchGraceJob?.cancel(); subSwitchGraceJob = null
            if (phase == SessionPhase.PAUSED) {
                closeCurrentPause(now)
            }
            return
        }

        when (phase) {
            SessionPhase.RUNNING -> {
                if (immediatePauseLabel != null) {
                    // System UI / HiOS launcher while locked: stop crediting
                    // focus time the moment you land here — skip the normal
                    // GRACE_PERIOD_MS entirely. This is deliberately a
                    // different clock than the kiosk block itself (see
                    // BlockerAccessibilityService.KIOSK_GRACE_MS), which still
                    // waits a few seconds before actually bouncing you back.
                    // A quick glance still gets forgiven from being BLOCKED;
                    // it just doesn't get counted as focus while it lasts.
                    graceJob?.cancel(); graceJob = null
                    openNewPause(now, PauseReason.APP_SWITCH, packageName, immediatePauseLabel)
                    return
                }
                if (graceJob == null) {
                    graceJob = managerScope.launch {
                        delay(GRACE_PERIOD_MS)
                        graceJob = null
                        val stillAway = lastSeenForegroundPackage != session.primaryAppPackage
                        if (stillAway) {
                            val pkg = lastSeenForegroundPackage ?: packageName
                            // Pause starts now — i.e. the grace period itself
                            // is forgiven and counts as continued focus, not
                            // as part of the pause.
                            openNewPause(System.currentTimeMillis(), PauseReason.APP_SWITCH, pkg, resolveAppName(pkg))
                        }
                    }
                }
                // else: a grace job from an earlier departure is already
                // counting down — let it run, it isn't tied to a specific app.
            }
            SessionPhase.PAUSED -> {
                val open = openPauseEvent
                if (open?.reason != PauseReason.MANUAL && open?.appPackage != packageName) {
                    // Debounce, don't commit immediately: rapid, very short
                    // flickers between apps while already paused — including
                    // ones the OS itself generates (launcher edge-gesture
                    // handling, system overlays) rather than the user
                    // actually navigating anywhere — would otherwise
                    // fragment the breakdown into dozens of near-zero
                    // entries. Every new differing package cancels and
                    // restarts this; only once something holds steady for
                    // the full debounce does the switch actually commit.
                    subSwitchGraceJob?.cancel()
                    subSwitchGraceJob = managerScope.launch {
                        delay(SUB_SWITCH_GRACE_MS)
                        subSwitchGraceJob = null
                        if (lastSeenForegroundPackage == packageName && openPauseEvent === open) {
                            switchPauseApp(System.currentTimeMillis(), packageName, resolveAppName(packageName))
                        }
                    }
                } else {
                    subSwitchGraceJob?.cancel(); subSwitchGraceJob = null
                }
            }
            SessionPhase.IDLE -> {}
        }
    }

    // ── Internal transition helpers ─────────────────────────────────────────
    // Each of these mutates in-memory state synchronously (so the UI updates
    // instantly) and enqueues the Room write rather than persisting inline.

    // Always represents a genuine RUNNING → PAUSED transition (the one other
    // case — switching between two non-work apps while already paused — goes
    // through switchPauseApp below instead, which doesn't touch focus time
    // or pauseCount at all).
    private fun openNewPause(startTime: Long, reason: PauseReason, appPackage: String?, appName: String?) {
        val session = currentSession ?: return

        val focusedDelta = (startTime - lastFocusStartTimestamp).coerceAtLeast(0)
        currentSession = session.copy(
            focusedTimeMs = session.focusedTimeMs + focusedDelta,
            pauseCount = session.pauseCount + 1,
            updatedAt = System.currentTimeMillis()
        )

        val record = OpenPause(
            sessionId = session.id, startTime = startTime, reason = reason,
            appPackage = appPackage, appName = appName
        )
        openPauseEvent = record

        _state.value = _state.value.copy(
            phase = SessionPhase.PAUSED,
            focusedTimeMs = currentSession?.focusedTimeMs ?: _state.value.focusedTimeMs,
            pauseCount = currentSession?.pauseCount ?: _state.value.pauseCount,
            currentPauseReason = reason,
            currentPauseAppName = appName
        )

        enqueueWrite {
            val id = repository.insertPauseEvent(
                PauseEvent(
                    sessionId = record.sessionId, startTime = record.startTime,
                    reason = record.reason, appPackage = record.appPackage, appName = record.appName
                )
            )
            record.id = id // mutated in place — any later write referencing this SAME record sees the real id
            currentSession?.let { repository.updateSession(it) }
        }
    }

    // Switching between two different non-work apps while already paused.
    // Closes the old pause row and opens a new one, in ONE enqueued write
    // with the close ordered before the open. Reads `open.id` at EXECUTION
    // time (not when this function is called) — by the time this write
    // actually runs, the FIFO queue guarantees the earlier write that
    // resolved `open`'s real id has already completed, even if this
    // transition fired only milliseconds after that one. That ordering is
    // what fixes a real bug: a fire-and-forget close could previously
    // capture a still-zero placeholder id, silently fail to match any row,
    // and leave the old pause permanently stuck open.
    private fun switchPauseApp(now: Long, newPackage: String, newAppName: String) {
        val open = openPauseEvent ?: return
        val session = currentSession ?: return
        val duration = (now - open.startTime).coerceAtLeast(0)

        currentSession = session.copy(pauseTimeMs = session.pauseTimeMs + duration, updatedAt = now)
        val newRecord = OpenPause(
            sessionId = session.id, startTime = now, reason = PauseReason.APP_SWITCH,
            appPackage = newPackage, appName = newAppName
        )
        openPauseEvent = newRecord

        _state.value = _state.value.copy(
            pauseTimeMs = currentSession?.pauseTimeMs ?: _state.value.pauseTimeMs,
            currentPauseAppName = newAppName
        )

        enqueueWrite {
            if (open.id != 0L) {
                repository.updatePauseEvent(
                    PauseEvent(
                        id = open.id, sessionId = open.sessionId, startTime = open.startTime,
                        endTime = now, durationMs = duration, reason = open.reason,
                        appPackage = open.appPackage, appName = open.appName
                    )
                )
            }
            val id = repository.insertPauseEvent(
                PauseEvent(
                    sessionId = newRecord.sessionId, startTime = newRecord.startTime,
                    reason = newRecord.reason, appPackage = newRecord.appPackage, appName = newRecord.appName
                )
            )
            newRecord.id = id
            currentSession?.let { repository.updateSession(it) }
        }
    }

    // Always closes a pause AND resumes focus — the other case (switching
    // between two non-work apps while still paused) goes through
    // switchPauseApp instead, which never resumes.
    private fun closeCurrentPause(now: Long) {
        val open = openPauseEvent ?: return
        val session = currentSession ?: return
        val duration = (now - open.startTime).coerceAtLeast(0)
        openPauseEvent = null

        currentSession = session.copy(pauseTimeMs = session.pauseTimeMs + duration, updatedAt = now)

        lastFocusStartTimestamp = now
        _state.value = _state.value.copy(
            phase = SessionPhase.RUNNING,
            pauseTimeMs = currentSession?.pauseTimeMs ?: _state.value.pauseTimeMs,
            currentPauseReason = null,
            currentPauseAppName = null
        )

        enqueueWrite {
            if (open.id != 0L) {
                repository.updatePauseEvent(
                    PauseEvent(
                        id = open.id, sessionId = open.sessionId, startTime = open.startTime,
                        endTime = now, durationMs = duration, reason = open.reason,
                        appPackage = open.appPackage, appName = open.appName
                    )
                )
            }
            currentSession?.let { repository.updateSession(it) }
        }
    }

    private fun finalizeSession(status: SessionStatus) {
        val session = currentSession ?: return
        if (_state.value.phase == SessionPhase.IDLE) return
        val now = System.currentTimeMillis()

        graceJob?.cancel(); graceJob = null
        subSwitchGraceJob?.cancel(); subSwitchGraceJob = null
        tickerJob?.cancel(); tickerJob = null

        var finalSession = session
        val openSnapshot = openPauseEvent
        var pendingDuration: Long? = null

        when (_state.value.phase) {
            SessionPhase.PAUSED -> {
                if (openSnapshot != null) {
                    val duration = (now - openSnapshot.startTime).coerceAtLeast(0)
                    pendingDuration = duration
                    finalSession = finalSession.copy(pauseTimeMs = finalSession.pauseTimeMs + duration)
                }
            }
            SessionPhase.RUNNING -> {
                val focusedDelta = (now - lastFocusStartTimestamp).coerceAtLeast(0)
                finalSession = finalSession.copy(focusedTimeMs = finalSession.focusedTimeMs + focusedDelta)
            }
            SessionPhase.IDLE -> {}
        }

        val score = FocusScoreCalculator.calculate(finalSession.focusedTimeMs, finalSession.pauseTimeMs)
        finalSession = finalSession.copy(endTime = now, status = status, focusScore = score, updatedAt = now)

        currentSession = null
        openPauseEvent = null
        lastSeenForegroundPackage = null
        _state.value = DeepWorkUiState()

        enqueueWrite {
            if (openSnapshot != null && pendingDuration != null && openSnapshot.id != 0L) {
                repository.updatePauseEvent(
                    PauseEvent(
                        id = openSnapshot.id, sessionId = openSnapshot.sessionId, startTime = openSnapshot.startTime,
                        endTime = now, durationMs = pendingDuration, reason = openSnapshot.reason,
                        appPackage = openSnapshot.appPackage, appName = openSnapshot.appName
                    )
                )
            }
            repository.updateSession(finalSession)
            _lastSummary.value = buildSummary(finalSession, status, now)

            // Auto-export after every ORDINARY (non-plan) session. Cycle
            // plans export once instead, when the WHOLE plan finishes — see
            // advanceCyclePlanAfterBreak. activeCyclePlan is non-null for
            // every cycle segment (intermediate or final; it isn't cleared
            // until the trailing break ends), so checking it here correctly
            // limits this to standalone sessions only.
            if (activeCyclePlan == null) {
                managerScope.launch(Dispatchers.IO) { DeepWorkExportManager.autoExportBackup(appContext) }
            }
        }
    }

    private fun labelFor(pause: PauseEvent): String = when {
        !pause.appName.isNullOrBlank() -> pause.appName
        pause.reason == PauseReason.MANUAL -> "Manual pause"
        else -> pause.reason.name.lowercase()
            .replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    private suspend fun buildSummary(session: FocusSession, status: SessionStatus, now: Long): SessionSummary {
        val pauses = repository.getPauseEventsForSession(session.id)

        // Group by package name for app-switch pauses, or by a human label for
        // null-package pauses (AUTO_AWAY = "Away from screen", MANUAL = "Manual pause",
        // screen-off = "Screen off"). Previously filtered to appPackage != null,
        // which silently dropped all physical-absence pauses from the breakdown.
        val breakdown = pauses
            .groupBy { it.appPackage ?: labelFor(it) }
            .map { (_, events) ->
                AppBreakItem(
                    appPackage = events.first().appPackage ?: labelFor(events.first()),
                    appName = labelFor(events.first()),
                    totalMs = events.sumOf { it.durationMs },
                    count = events.size
                )
            }
            .sortedByDescending { it.totalMs }

        val segments = (session.pauseCount + 1).coerceAtLeast(1)
        val avgSegment = session.focusedTimeMs / segments

        return SessionSummary(
            sessionId = session.id,
            primaryAppName = session.primaryAppName,
            startTime = session.startTime,
            endTime = now,
            wallClockMs = now - session.startTime,
            focusedTimeMs = session.focusedTimeMs,
            pauseTimeMs = session.pauseTimeMs,
            pauseCount = session.pauseCount,
            averageFocusSegmentMs = avgSegment,
            focusScore = session.focusScore ?: FocusScoreCalculator.calculate(session.focusedTimeMs, session.pauseTimeMs),
            wasCancelled = status == SessionStatus.CANCELLED,
            breakdownByApp = breakdown
        )
    }

    // ── Live ticker — UI display only, never the source of truth ───────────
    // currentSession.focusedTimeMs / pauseTimeMs are only ever updated at
    // real transitions or a checkpoint below; the ticker always derives the
    // displayed value FROM that committed base plus elapsed time, so it
    // never feeds its own output back in as input.

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = managerScope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    private fun tick() {
        // Absolute failsafe, checked before anything else — applies during
        // work AND break alike. See midnightCutoffMs's field doc.
        midnightCutoffMs?.let { cutoff ->
            if (System.currentTimeMillis() >= cutoff) {
                forceEndForMidnight()
                return
            }
        }

        if (_state.value.phase == SessionPhase.IDLE) {
            if (_state.value.onBreak) tickBreak(_state.value)
            return
        }

        // Ground-truth correction, before anything else this tick — see the
        // field doc on groundTruthProvider for why this needs to run first.
        groundTruthProvider?.invoke()?.let { truth ->
            if (truth != lastSeenForegroundPackage) {
                onForegroundAppChanged(truth, immediatePauseLabel = kioskGraceLabelProvider?.invoke(truth))
            }
        }

        val session = currentSession ?: return
        val phase = _state.value.phase
        if (phase == SessionPhase.IDLE) return
        val now = System.currentTimeMillis()

        val liveFocused = if (phase == SessionPhase.RUNNING)
            session.focusedTimeMs + (now - lastFocusStartTimestamp).coerceAtLeast(0)
        else session.focusedTimeMs

        // Hard-lock sessions release the instant the target is reached — no
        // overtime. Fire the completion alert first (service still shows its
        // notification/vibration), then finalize immediately. This is also
        // what fully releases kiosk mode, since kiosk is now tied directly to
        // session phase (see BlockerAccessibilityService's phase-observer).
        //
        // If this segment is part of a cycle plan, snapshot the plan BEFORE
        // completeSession() runs (it doesn't touch activeCyclePlan itself,
        // but reading it first keeps the two steps clearly separate), then
        // immediately start the break — a plan releases kiosk exactly the
        // same way a finished single session does, it just re-locks for the
        // next segment once the break's own countdown reaches zero.
        if (!targetReachedFired && session.targetDurationMs > 0 && liveFocused >= session.targetDurationMs) {
            targetReachedFired = true
            onTargetReached?.invoke()
            val plan = activeCyclePlan
            completeSession()
            if (plan != null) startBreak(plan)
            return
        }

        val open = openPauseEvent
        val livePause = if (phase == SessionPhase.PAUSED && open != null)
            session.pauseTimeMs + (now - open.startTime).coerceAtLeast(0)
        else session.pauseTimeMs

        _state.value = _state.value.copy(focusedTimeMs = liveFocused, pauseTimeMs = livePause)

        tickCount++
        if (phase == SessionPhase.RUNNING && tickCount % CHECKPOINT_INTERVAL_TICKS == 0) {
            currentSession = session.copy(focusedTimeMs = liveFocused, updatedAt = now)
            // Critical: without this, the next tick re-measures from the
            // ORIGINAL segment start on top of the value we just saved,
            // double-counting everything since then. Every checkpoint must
            // reset the baseline exactly like a real resume does.
            lastFocusStartTimestamp = now
            enqueueWrite { currentSession?.let { repository.updateSession(it) } }
        }
    }

    // ── Cycle-plan break handling ────────────────────────────────────────
    // Breaks are NOT a FocusSession — they're not "focus time" and don't
    // belong in the leaderboard/analytics. They're tracked purely as extra
    // fields on DeepWorkUiState (onBreak/breakRemainingMs/cycleIndex/
    // totalCycles) riding on top of phase==IDLE. This is deliberate: it
    // means BlockerAccessibilityService's kiosk guard (which triggers on
    // phase != IDLE) needs ZERO changes to correctly NOT lock during a
    // break — kiosk is fully released, exactly like ordinary idle time,
    // for the whole break. What stays true throughout — work AND break — is
    // that there's no UI control anywhere to cancel the plan; the only
    // thing a break's countdown can do is reach zero and auto-start the
    // next work segment (or end the plan, on the last cycle).

    private fun startBreak(plan: CyclePlan) {
        val breakEndAtMs = System.currentTimeMillis() + plan.breakMinutes * 60_000L
        _state.value = DeepWorkUiState(
            onBreak = true,
            breakRemainingMs = plan.breakMinutes * 60_000L,
            cycleIndex = plan.currentCycleIndex,
            totalCycles = plan.totalCycles,
            primaryAppPackage = plan.primaryAppPackage,
            primaryAppName = plan.primaryAppName
        )
        // Anchored to a wall-clock deadline, persisted immediately — see
        // DeepWorkPrefs.writeActiveBreak's doc. Rebooting during a break
        // can't extend it any more than rebooting during work can shorten
        // the lock; both read back against a fixed timestamp, never a
        // fresh countdown.
        DeepWorkPrefs.writeActiveBreak(
            appContext,
            DeepWorkPrefs.PersistedCyclePlan(
                plan.primaryAppPackage, plan.primaryAppName,
                plan.workMinutes, plan.breakMinutes, plan.totalCycles, plan.currentCycleIndex
            ),
            breakEndAtMs,
            midnightCutoffMs ?: 0L
        )
        // completeSession() (via finalizeSession) just cancelled the ticker
        // — restart it so the break countdown actually progresses.
        startTicker()
    }

    private fun tickBreak(state: DeepWorkUiState) {
        val plan = activeCyclePlan
        if (plan == null) {
            // Shouldn't happen, but don't get stuck if it does.
            tickerJob?.cancel(); tickerJob = null
            _state.value = DeepWorkUiState()
            return
        }
        val remaining = state.breakRemainingMs - 1000L
        if (remaining <= 0L) {
            advanceCyclePlanAfterBreak(plan)
        } else {
            _state.value = state.copy(breakRemainingMs = remaining)
        }
    }

    private fun advanceCyclePlanAfterBreak(plan: CyclePlan) {
        if (plan.currentCycleIndex >= plan.totalCycles) {
            // That was the last cycle's break — the whole plan is done.
            activeCyclePlan = null
            tickerJob?.cancel(); tickerJob = null
            _state.value = DeepWorkUiState()
            DeepWorkPrefs.clearActivePlan(appContext)
            // One export for the whole plan, here, rather than one per
            // cycle — see the matching comment in finalizeSession.
            managerScope.launch(Dispatchers.IO) { DeepWorkExportManager.autoExportBackup(appContext) }
            return
        }
        val nextIndex = plan.currentCycleIndex + 1
        activeCyclePlan = plan.copy(currentCycleIndex = nextIndex)
        startWorkSegment(plan.primaryAppPackage, plan.primaryAppName, plan.workMinutes * 60_000L, nextIndex, plan.totalCycles)
    }

    /**
     * Called once the absolute midnight ceiling is crossed, work or break,
     * however many cycles remain — the whole thing ends here, full stop,
     * not just the current segment. "No matter what" means no matter what:
     * a bug corrupting the cycle count, a plan that happens to span past
     * midnight under ordinary bounds, all of it ends here regardless.
     */
    private fun forceEndForMidnight() {
        midnightCutoffMs = null
        val wasOnBreak = _state.value.onBreak
        if (!wasOnBreak && currentSession != null) {
            // A work segment was running — finalize it exactly like a
            // normal completion, crediting whatever was honestly
            // checkpointed. activeCyclePlan is cleared FIRST so
            // finalizeSession's own bookkeeping (including its auto-export
            // check) correctly treats this as "nothing further to resume
            // into", the same as any other final segment.
            activeCyclePlan = null
            finalizeSession(SessionStatus.COMPLETED)
        } else {
            // On break, or nothing currently running — nothing to finalize
            // as a FocusSession; just fully clear everything.
            activeCyclePlan = null
            tickerJob?.cancel(); tickerJob = null
            _state.value = DeepWorkUiState()
            managerScope.launch(Dispatchers.IO) { DeepWorkExportManager.autoExportBackup(appContext) }
        }
        DeepWorkPrefs.clearActivePlan(appContext)
        onMidnightCutoff?.invoke()
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val pm = appContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
