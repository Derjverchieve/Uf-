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

    // Set by DeepWorkSessionService so it can fire an alert (sound/vibration/
    // notification) the moment the target is hit — kept out of the Manager
    // itself since that's an Android-specific concern, not session logic.
    var onTargetReached: (() -> Unit)? = null

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
            subSwitchGraceJob?.cancel(); subSwitchGraceJob = null
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
        if (_state.value.phase == SessionPhase.IDLE) return

        // Ground-truth correction, before anything else this tick — see the
        // field doc on groundTruthProvider for why this needs to run first.
        groundTruthProvider?.invoke()?.let { truth ->
            if (truth != lastSeenForegroundPackage) {
                onForegroundAppChanged(truth)
            }
        }

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
            enqueueWrite { currentSession?.let { repository.updateSession(it) } }
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
