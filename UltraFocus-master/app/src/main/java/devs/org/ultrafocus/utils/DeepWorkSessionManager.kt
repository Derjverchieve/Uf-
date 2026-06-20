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
 *  PauseEvent is logged retroactively starting from the moment they
 *  actually left (not from when the grace period happened to end), so the
 *  numbers stay honest.
 *
 *  Returning to the primary app always resumes immediately — no grace
 *  delay on the way back in.
 *
 *  Switching between two *different* non-work apps while already paused
 *  doesn't reset or re-trigger the grace period — it just closes the
 *  current PauseEvent and opens a new one for the new app, so the
 *  "which app, for how long" breakdown stays accurate without inflating
 *  the pause count (a "pause" = one continuous break from work, however
 *  many apps you bounce through during it).
 *
 *  An auto-pause can only be cleared by actually returning to the primary
 *  app — resumeManually() deliberately refuses to clear anything but a
 *  MANUAL pause. Letting a button override the auto-detection would defeat
 *  the point of it being honest by default.
 */
object DeepWorkSessionManager {

    private const val GRACE_PERIOD_MS = 10_000L
    private const val CHECKPOINT_INTERVAL_TICKS = 30 // checkpoint to DB roughly every ~30s while running
    // Opening this screen to check on your session shouldn't itself count as
    // leaving the work app — otherwise checking the status is the one thing
    // guaranteed to break the very thing you're checking.
    private const val SESSION_SCREEN_CLASS = "devs.org.ultrafocus.activities.DeepWorkSessionActivity"

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
    private var openPauseEvent: PauseEvent? = null

    private var lastFocusStartTimestamp: Long = 0L
    private var lastSeenForegroundPackage: String? = null
    private var graceJob: Job? = null
    private var tickerJob: Job? = null
    private var tickCount = 0
    private var screenReceiver: BroadcastReceiver? = null
    private var targetReachedFired = false

    // Set by DeepWorkSessionService so it can fire an alert (sound/vibration/
    // notification) the moment the target is hit — kept out of the Manager
    // itself since that's an Android-specific concern, not session logic.
    var onTargetReached: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        repository = DeepWorkRepository(AppDatabase.getDatabase(appContext))
        initialized = true
        registerScreenOffReceiver()
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

    fun hasActiveSession(): Boolean = _state.value.phase != SessionPhase.IDLE

    // ── Crash / process-death recovery ──────────────────────────────────────

    private suspend fun recoverOrphanedSession() {
        try {
            val orphan = repository.getRunningSession() ?: return
            val open = repository.getOpenPauseEvent(orphan.id)
            if (open != null) {
                repository.updatePauseEvent(
                    open.copy(
                        endTime = orphan.updatedAt,
                        durationMs = (orphan.updatedAt - open.startTime).coerceAtLeast(0)
                    )
                )
            }
            // We only trust what was actually persisted before the process
            // died — honest accounting beats guessing what happened during
            // the gap, so we close the session out at its last checkpoint
            // rather than crediting (or penalizing) unmeasured time.
            val score = FocusScoreCalculator.calculate(orphan.focusedTimeMs, orphan.pauseTimeMs)
            repository.updateSession(
                orphan.copy(
                    endTime = orphan.updatedAt,
                    status = SessionStatus.COMPLETED,
                    focusScore = score
                )
            )
        } catch (_: Exception) {
            // Best-effort recovery — if it fails, the orphan row just sits
            // there as RUNNING and gets picked up next launch.
        }
    }

    // ── Public controls (called from the UI / service) ──────────────────────

    fun startSession(primaryAppPackage: String, primaryAppName: String, targetDurationMs: Long) {
        if (!initialized || _state.value.phase != SessionPhase.IDLE) return
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
                sessionStartTime = now
            )

            startTicker()
            evaluateForeground(lastSeenForegroundPackage!!)
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

    // ── Called from BlockerAccessibilityService on every foreground-app change ──

    fun onForegroundAppChanged(packageName: String, className: String? = null, isExemptWindowType: Boolean = false) {
        if (!initialized) return
        if (packageName == appContext.packageName && className == SESSION_SCREEN_CLASS) return
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
        evaluateForeground(packageName)
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

    private fun evaluateForeground(packageName: String) {
        val session = currentSession ?: return
        val now = System.currentTimeMillis()
        val phase = _state.value.phase

        if (packageName == session.primaryAppPackage) {
            graceJob?.cancel(); graceJob = null
            if (phase == SessionPhase.PAUSED) {
                closeCurrentPause(now)
            }
            return
        }

        when (phase) {
            SessionPhase.RUNNING -> {
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
                    switchPauseApp(now, packageName, resolveAppName(packageName))
                }
            }
            SessionPhase.IDLE -> {}
        }
    }

    // ── Internal transition helpers ─────────────────────────────────────────
    // Each of these mutates in-memory state synchronously (so the UI updates
    // instantly) and then fires off the Room write in a child coroutine.

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

        val event = PauseEvent(
            sessionId = session.id,
            startTime = startTime,
            reason = reason,
            appPackage = appPackage,
            appName = appName
        )
        openPauseEvent = event

        _state.value = _state.value.copy(
            phase = SessionPhase.PAUSED,
            focusedTimeMs = currentSession?.focusedTimeMs ?: _state.value.focusedTimeMs,
            pauseCount = currentSession?.pauseCount ?: _state.value.pauseCount,
            currentPauseReason = reason,
            currentPauseAppName = appName
        )

        managerScope.launch {
            val id = repository.insertPauseEvent(event)
            // Guard against a fast follow-up transition already having
            // replaced openPauseEvent before this insert returned.
            if (openPauseEvent === event) {
                openPauseEvent = event.copy(id = id)
            }
            currentSession?.let { repository.updateSession(it) }
        }
    }

    // Switching between two different non-work apps while already paused —
    // e.g. launcher to a different app to System UI. Closes the old pause
    // row and opens a new one for the new app, in ONE coroutine with the
    // close awaited before the open starts. This is the fix for a real bug:
    // closeCurrentPause and openNewPause used to each fire their own
    // independent coroutine for their DB write, with no guaranteed order
    // between them — Room's actual write completion time doesn't follow
    // launch order, so on fast back-to-back switches the new row could get
    // INSERTed before the old row's UPDATE (closing it) finished, leaving
    // two rows simultaneously open. Doing both writes sequentially in one
    // coroutine makes that impossible.
    private fun switchPauseApp(now: Long, newPackage: String, newAppName: String) {
        val open = openPauseEvent ?: return
        val session = currentSession ?: return
        val duration = (now - open.startTime).coerceAtLeast(0)
        val closed = open.copy(endTime = now, durationMs = duration)

        currentSession = session.copy(pauseTimeMs = session.pauseTimeMs + duration, updatedAt = now)
        val newEvent = PauseEvent(
            sessionId = session.id,
            startTime = now,
            reason = PauseReason.APP_SWITCH,
            appPackage = newPackage,
            appName = newAppName
        )
        openPauseEvent = newEvent

        _state.value = _state.value.copy(
            pauseTimeMs = currentSession?.pauseTimeMs ?: _state.value.pauseTimeMs,
            currentPauseAppName = newAppName
        )

        managerScope.launch {
            repository.updatePauseEvent(closed)               // close the old row first...
            val id = repository.insertPauseEvent(newEvent)    // ...only then create the new one
            if (openPauseEvent === newEvent) {
                openPauseEvent = newEvent.copy(id = id)
            }
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
        val closed = open.copy(endTime = now, durationMs = duration)
        openPauseEvent = null

        currentSession = session.copy(
            pauseTimeMs = session.pauseTimeMs + duration,
            updatedAt = now
        )

        lastFocusStartTimestamp = now
        _state.value = _state.value.copy(
            phase = SessionPhase.RUNNING,
            pauseTimeMs = currentSession?.pauseTimeMs ?: _state.value.pauseTimeMs,
            currentPauseReason = null,
            currentPauseAppName = null
        )

        managerScope.launch {
            repository.updatePauseEvent(closed)
            currentSession?.let { repository.updateSession(it) }
        }
    }

    private fun finalizeSession(status: SessionStatus) {
        val session = currentSession ?: return
        if (_state.value.phase == SessionPhase.IDLE) return
        val now = System.currentTimeMillis()

        graceJob?.cancel(); graceJob = null
        tickerJob?.cancel(); tickerJob = null

        var finalSession = session
        var closedPauseEvent: PauseEvent? = null

        when (_state.value.phase) {
            SessionPhase.PAUSED -> {
                val open = openPauseEvent
                if (open != null) {
                    val duration = (now - open.startTime).coerceAtLeast(0)
                    closedPauseEvent = open.copy(endTime = now, durationMs = duration)
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

        managerScope.launch {
            closedPauseEvent?.let { repository.updatePauseEvent(it) }
            repository.updateSession(finalSession)
            _lastSummary.value = buildSummary(finalSession, status, now)
        }
    }

    private suspend fun buildSummary(session: FocusSession, status: SessionStatus, now: Long): SessionSummary {
        val pauses = repository.getPauseEventsForSession(session.id)
        val breakdown = pauses
            .filter { it.appPackage != null }
            .groupBy { it.appPackage }
            .map { (pkg, events) ->
                AppBreakItem(
                    appPackage = pkg!!,
                    appName = events.firstOrNull { !it.appName.isNullOrBlank() }?.appName ?: pkg,
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
        val session = currentSession ?: return
        val phase = _state.value.phase
        if (phase == SessionPhase.IDLE) return
        val now = System.currentTimeMillis()

        val liveFocused = if (phase == SessionPhase.RUNNING)
            session.focusedTimeMs + (now - lastFocusStartTimestamp).coerceAtLeast(0)
        else session.focusedTimeMs

        val open = openPauseEvent
        val livePause = if (phase == SessionPhase.PAUSED && open != null)
            session.pauseTimeMs + (now - open.startTime).coerceAtLeast(0)
        else session.pauseTimeMs

        _state.value = _state.value.copy(focusedTimeMs = liveFocused, pauseTimeMs = livePause)

        if (!targetReachedFired && session.targetDurationMs > 0 && liveFocused >= session.targetDurationMs) {
            targetReachedFired = true
            onTargetReached?.invoke()
        }

        tickCount++
        if (phase == SessionPhase.RUNNING && tickCount % CHECKPOINT_INTERVAL_TICKS == 0) {
            currentSession = session.copy(focusedTimeMs = liveFocused, updatedAt = now)
            // Critical: without this, the next tick re-measures from the
            // ORIGINAL segment start on top of the value we just saved,
            // double-counting everything since then. Every checkpoint must
            // reset the baseline exactly like a real resume does.
            lastFocusStartTimestamp = now
            managerScope.launch { currentSession?.let { repository.updateSession(it) } }
        }
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
