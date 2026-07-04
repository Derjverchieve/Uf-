package devs.org.ultrafocus.model

enum class SessionPhase { IDLE, RUNNING, PAUSED }

/**
 * Live, in-memory view of the active (or about-to-start) session. This is
 * what the UI and the foreground-notification service observe while a
 * session is running. It is NOT the source of truth — DeepWorkSessionManager
 * holds that internally and persists it to Room — this is just a snapshot
 * for display.
 */
data class DeepWorkUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val sessionId: Long? = null,
    val primaryAppPackage: String? = null,
    val primaryAppName: String? = null,
    val targetDurationMs: Long = 0L,
    val focusedTimeMs: Long = 0L,
    val pauseTimeMs: Long = 0L,
    val pauseCount: Int = 0,
    val currentPauseReason: PauseReason? = null,
    val currentPauseAppName: String? = null,
    val sessionStartTime: Long = 0L,
    // Cycle-plan fields — independent of `phase`. onBreak=true implies
    // phase==IDLE (no FocusSession is running) but a scheduled multi-cycle
    // plan is mid-sequence, waiting out a break before the next work
    // segment auto-starts. cycleIndex/totalCycles are ALSO populated during
    // the WORK phase of a cycle plan (so the live card can show "Cycle 2 of
    // 4" while working, not just during break). Both are 0 outside any plan.
    val onBreak: Boolean = false,
    val breakRemainingMs: Long = 0L,
    val cycleIndex: Int = 0,
    val totalCycles: Int = 0
)

data class AppBreakItem(
    val appPackage: String,
    val appName: String,
    val totalMs: Long,
    val count: Int
)

/** The end-of-session report described in the design doc's "Core Timer & Pause Analytics" section. */
data class SessionSummary(
    val sessionId: Long,
    val primaryAppName: String,
    val startTime: Long,
    val endTime: Long,
    val wallClockMs: Long,
    val focusedTimeMs: Long,
    val pauseTimeMs: Long,
    val pauseCount: Int,
    val averageFocusSegmentMs: Long,
    val focusScore: Int,
    val wasCancelled: Boolean,
    val breakdownByApp: List<AppBreakItem>
)
