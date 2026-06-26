package devs.org.ultrafocus.utils

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import devs.org.ultrafocus.R
import devs.org.ultrafocus.model.SessionPhase

/**
 * Manages the kiosk quick-switch overlay — a floating row of app icons drawn
 * using TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed; accessible
 * from the AccessibilityService that owns this manager).
 *
 * Call [show] from the volume-key handler. The tile auto-hides after 5 seconds.
 * [destroy] must be called in the service's onDestroy.
 */
class KioskOverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var timerTickRunnable: Runnable? = null

    private val hideRunnable = Runnable { hide() }

    /**
     * Shows the tile. If already visible, resets the 5-second hide timer.
     * [alwaysIncludePkg] is always added (typically the active session's primary app)
     * even if it isn't in the persisted allowed-apps list.
     */
    fun show(alwaysIncludePkg: String? = null) {
        val apps = KioskPrefs.getAllowedApps(context).toMutableMap()

        // Always include the active focus app so you can return to it from the tile
        if (!alwaysIncludePkg.isNullOrBlank() && alwaysIncludePkg !in apps) {
            try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(alwaysIncludePkg, 0)
                apps[alwaysIncludePkg] = pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {}
        }

        if (apps.isEmpty()) return

        if (overlayView != null) {
            // Already showing — just reset the auto-hide timer
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, AUTO_HIDE_MS)
            return
        }

        val view = LayoutInflater.from(context).inflate(R.layout.kiosk_overlay, null)
        val container = view.findViewById<LinearLayout>(R.id.kioskAppContainer)

        apps.forEach { (pkg, label) -> addAppIcon(container, pkg, label) }
        if (container.childCount == 0) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 160  // dp offset from screen bottom
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            startTimerTicker(view.findViewById(R.id.kioskTimerText))
            mainHandler.postDelayed(hideRunnable, AUTO_HIDE_MS)
        } catch (_: Exception) {}
    }

    fun hide() {
        mainHandler.removeCallbacks(hideRunnable)
        timerTickRunnable?.let { mainHandler.removeCallbacks(it) }
        timerTickRunnable = null
        val view = overlayView ?: return
        try { windowManager.removeView(view) } catch (_: Exception) {}
        overlayView = null
    }

    fun destroy() = hide()

    private fun startTimerTicker(timerText: TextView) {
        timerTickRunnable?.let { mainHandler.removeCallbacks(it) }
        val ticker = object : Runnable {
            override fun run() {
                if (overlayView == null) return
                val state = DeepWorkSessionManager.state.value
                timerText.text = when {
                    state.phase == SessionPhase.IDLE -> ""
                    state.targetDurationMs <= 0L ->
                        DurationFormatter.formatClock(state.focusedTimeMs)
                    else -> {
                        val remaining = state.targetDurationMs - state.focusedTimeMs
                        if (remaining > 0L) "${DurationFormatter.formatClock(remaining)} left"
                        else "+${DurationFormatter.formatClock(-remaining)} overtime"
                    }
                }
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        timerTickRunnable = ticker
        mainHandler.post(ticker)
    }

    private fun addAppIcon(container: LinearLayout, packageName: String, label: String) {
        val pm = context.packageManager

        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 12, 20, 12)
            isClickable = true
            isFocusable = true
            // Ripple on dark background
            background = android.util.TypedValue().also { tv ->
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            }.resourceId.takeIf { it != 0 }?.let {
                context.getDrawable(it)
            }
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(72, 72)
            try { setImageDrawable(pm.getApplicationIcon(packageName)) }
            catch (_: Exception) { setImageResource(R.mipmap.ic_launcher) }
        }

        val labelView = TextView(context).apply {
            text = label.take(10)
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        }

        item.addView(icon)
        item.addView(labelView)

        item.setOnClickListener {
            hide()
            try {
                val intent = pm.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return@setOnClickListener
                context.startActivity(intent)
            } catch (_: Exception) {}
        }

        container.addView(item)
    }

    companion object {
        private const val AUTO_HIDE_MS = 5_000L
    }
}
