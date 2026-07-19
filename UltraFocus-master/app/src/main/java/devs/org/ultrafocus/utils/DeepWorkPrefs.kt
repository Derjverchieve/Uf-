package devs.org.ultrafocus.utils

import android.content.Context

object DeepWorkPrefs {
    private const val PREFS = "deep_work_prefs"
    private const val KEY_PACKAGE = "primary_app_package"
    private const val KEY_NAME = "primary_app_name"
    private const val KEY_MINUTES = "target_minutes"
    private const val KEY_CYCLES_ENABLED = "cycles_enabled"
    private const val KEY_CYCLES_COUNT = "cycles_count"
    private const val KEY_BREAK_MINUTES = "break_minutes"

    // Hard-lock sessions can't be ended or cancelled early, so the ceiling on
    // how long you can lock yourself in for matters. Single source of truth:
    // DeepWorkSessionActivity validates against this same constant rather
    // than a duplicated number.
    const val MAX_TARGET_MINUTES = 120

    // Cycle plans chain multiple work+break pairs with the SAME hard lock
    // applied across the whole sequence — can't cancel until it's over, same
    // as a single session, just extended across all cycles. Worst case with
    // these bounds: MAX_CYCLES × MAX_TARGET_MINUTES work (4×120=8hr) +
    // MAX_CYCLES × MIN_BREAK_MINUTES break at the floor (4×1=4min). The
    // midnight failsafe (DeepWorkSessionManager.midnightCutoffMs) is the
    // other backstop on total elapsed time, independent of these bounds.
    const val MIN_CYCLES = 2
    const val MAX_CYCLES = 4
    const val MIN_BREAK_MINUTES = 1
    const val MAX_BREAK_MINUTES = 60

    fun saveLastPrimaryApp(context: Context, packageName: String, appName: String) {
        prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_NAME, appName)
            .apply()
    }

    fun getLastPrimaryAppPackage(context: Context): String? = prefs(context).getString(KEY_PACKAGE, null)
    fun getLastPrimaryAppName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    fun saveLastTargetMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MINUTES, minutes.coerceIn(1, MAX_TARGET_MINUTES)).apply()
    }

    fun getLastTargetMinutes(context: Context): Int =
        prefs(context).getInt(KEY_MINUTES, 45).coerceIn(1, MAX_TARGET_MINUTES)

    fun saveCyclesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CYCLES_ENABLED, enabled).apply()
    }

    fun getCyclesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CYCLES_ENABLED, false)

    fun saveCyclesCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_CYCLES_COUNT, count.coerceIn(MIN_CYCLES, MAX_CYCLES)).apply()
    }

    fun getCyclesCount(context: Context): Int =
        prefs(context).getInt(KEY_CYCLES_COUNT, MAX_CYCLES).coerceIn(MIN_CYCLES, MAX_CYCLES)

    fun saveBreakMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_BREAK_MINUTES, minutes.coerceIn(MIN_BREAK_MINUTES, MAX_BREAK_MINUTES)).apply()
    }

    fun getBreakMinutes(context: Context): Int =
        prefs(context).getInt(KEY_BREAK_MINUTES, 15).coerceIn(MIN_BREAK_MINUTES, MAX_BREAK_MINUTES)

    // ── Reboot-survival state for the active cycle plan ─────────────────
    // An ordinary (non-plan) session needs none of this: its RUNNING
    // FocusSession row in Room already IS its full resume state. A cycle
    // plan needs this extra layer specifically because (a) a BREAK has no
    // Room row at all, and (b) even a work segment's plan context (which
    // cycle, how many total, break length) lives nowhere else. Written on
    // every segment transition, cleared only when the whole plan ends —
    // see DeepWorkSessionManager.recoverOrphanedSession, which is what
    // actually reads this back after a reboot.

    data class PersistedCyclePlan(
        val primaryAppPackage: String,
        val primaryAppName: String,
        val workMinutes: Int,
        val breakMinutes: Int,
        val totalCycles: Int,
        val currentCycleIndex: Int
    )

    private const val KEY_PLAN_ACTIVE = "plan_active"
    private const val KEY_PLAN_PKG = "plan_pkg"
    private const val KEY_PLAN_NAME = "plan_name"
    private const val KEY_PLAN_WORK_MIN = "plan_work_min"
    private const val KEY_PLAN_BREAK_MIN = "plan_break_min"
    private const val KEY_PLAN_TOTAL_CYCLES = "plan_total_cycles"
    private const val KEY_PLAN_CURRENT_CYCLE = "plan_current_cycle"
    private const val KEY_PLAN_SEGMENT = "plan_segment" // "WORK" or "BREAK"
    private const val KEY_PLAN_BREAK_END_AT = "plan_break_end_at"
    private const val KEY_PLAN_MIDNIGHT_CUTOFF = "plan_midnight_cutoff"

    fun isPlanActive(context: Context): Boolean = prefs(context).getBoolean(KEY_PLAN_ACTIVE, false)

    fun getPlanSegment(context: Context): String = prefs(context).getString(KEY_PLAN_SEGMENT, "WORK") ?: "WORK"

    fun getPlanBreakEndAtMs(context: Context): Long = prefs(context).getLong(KEY_PLAN_BREAK_END_AT, 0L)

    // 0L (the default) means "no cutoff was ever persisted" — callers
    // should treat that as "don't apply a midnight failsafe" rather than
    // as a literal timestamp of Jan 1 1970.
    fun getPlanMidnightCutoffMs(context: Context): Long = prefs(context).getLong(KEY_PLAN_MIDNIGHT_CUTOFF, 0L)

    fun readActivePlan(context: Context): PersistedCyclePlan? {
        val p = prefs(context)
        val pkg = p.getString(KEY_PLAN_PKG, null) ?: return null
        val name = p.getString(KEY_PLAN_NAME, null) ?: return null
        return PersistedCyclePlan(
            primaryAppPackage = pkg,
            primaryAppName = name,
            workMinutes = p.getInt(KEY_PLAN_WORK_MIN, 45),
            breakMinutes = p.getInt(KEY_PLAN_BREAK_MIN, 15),
            totalCycles = p.getInt(KEY_PLAN_TOTAL_CYCLES, 1),
            currentCycleIndex = p.getInt(KEY_PLAN_CURRENT_CYCLE, 1)
        )
    }

    fun writeActiveWorkSegment(context: Context, plan: PersistedCyclePlan, midnightCutoffMs: Long) {
        prefs(context).edit()
            .putBoolean(KEY_PLAN_ACTIVE, true)
            .putString(KEY_PLAN_PKG, plan.primaryAppPackage)
            .putString(KEY_PLAN_NAME, plan.primaryAppName)
            .putInt(KEY_PLAN_WORK_MIN, plan.workMinutes)
            .putInt(KEY_PLAN_BREAK_MIN, plan.breakMinutes)
            .putInt(KEY_PLAN_TOTAL_CYCLES, plan.totalCycles)
            .putInt(KEY_PLAN_CURRENT_CYCLE, plan.currentCycleIndex)
            .putString(KEY_PLAN_SEGMENT, "WORK")
            .putLong(KEY_PLAN_MIDNIGHT_CUTOFF, midnightCutoffMs)
            .apply()
    }

    // breakEndAtMs is the wall-clock timestamp the break should conclude at
    // — computed ONCE when the break starts. Resuming after a reboot reads
    // remaining time as (breakEndAtMs - now), never a fresh countdown, so
    // rebooting can't extend a break any more than it can shorten a work
    // segment. midnightCutoffMs is the SAME value written at plan start —
    // carried through unchanged on every segment transition so a reboot
    // mid-break restores the original ceiling, not a recomputed one.
    fun writeActiveBreak(context: Context, plan: PersistedCyclePlan, breakEndAtMs: Long, midnightCutoffMs: Long) {
        prefs(context).edit()
            .putBoolean(KEY_PLAN_ACTIVE, true)
            .putString(KEY_PLAN_PKG, plan.primaryAppPackage)
            .putString(KEY_PLAN_NAME, plan.primaryAppName)
            .putInt(KEY_PLAN_WORK_MIN, plan.workMinutes)
            .putInt(KEY_PLAN_BREAK_MIN, plan.breakMinutes)
            .putInt(KEY_PLAN_TOTAL_CYCLES, plan.totalCycles)
            .putInt(KEY_PLAN_CURRENT_CYCLE, plan.currentCycleIndex)
            .putString(KEY_PLAN_SEGMENT, "BREAK")
            .putLong(KEY_PLAN_BREAK_END_AT, breakEndAtMs)
            .putLong(KEY_PLAN_MIDNIGHT_CUTOFF, midnightCutoffMs)
            .apply()
    }

    fun clearActivePlan(context: Context) {
        prefs(context).edit().putBoolean(KEY_PLAN_ACTIVE, false).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
