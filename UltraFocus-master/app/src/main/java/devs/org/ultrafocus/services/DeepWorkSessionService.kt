package devs.org.ultrafocus.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.activities.DeepWorkSessionActivity
import devs.org.ultrafocus.model.PauseReason
import devs.org.ultrafocus.model.SessionPhase
import devs.org.ultrafocus.utils.DeepWorkSessionManager
import devs.org.ultrafocus.utils.DurationFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a deep-work session is active and shows a
 * persistent notification with the live state, plus quick Pause/Resume/End
 * actions. Purely a thin Android-lifecycle wrapper — all the actual session
 * logic lives in DeepWorkSessionManager.
 */
class DeepWorkSessionService : Service() {

    companion object {
        const val ACTION_PAUSE = "devs.org.ultrafocus.action.SESSION_PAUSE"
        const val ACTION_RESUME = "devs.org.ultrafocus.action.SESSION_RESUME"
        const val ACTION_END = "devs.org.ultrafocus.action.SESSION_END"
        private const val CHANNEL_ID = "UltraFocusDeepWork"
        private const val NOTIFICATION_ID = 1010
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> DeepWorkSessionManager.pauseManually()
            ACTION_RESUME -> DeepWorkSessionManager.resumeManually()
            ACTION_END -> DeepWorkSessionManager.completeSession()
        }

        if (!startForegroundSafe()) {
            stopSelf()
            return START_NOT_STICKY
        }
        observeState()
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(): Boolean {
        return try {
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Deep Work Session", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun observeState() {
        if (observeJob != null) return
        observeJob = serviceScope.launch {
            DeepWorkSessionManager.state.collectLatest { state ->
                if (state.phase == SessionPhase.IDLE) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val state = DeepWorkSessionManager.state.value
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DeepWorkSessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state.phase) {
            SessionPhase.RUNNING -> "Focused — ${state.primaryAppName ?: ""}"
            SessionPhase.PAUSED -> "Paused" + (state.currentPauseAppName?.let { " — $it" } ?: "")
            SessionPhase.IDLE -> "UltraFocus"
        }
        val remaining = (state.targetDurationMs - state.focusedTimeMs).coerceAtLeast(0)
        val pauseInfo = "${state.pauseCount} pause${if (state.pauseCount == 1) "" else "s"}"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (state.phase == SessionPhase.RUNNING) {
            // Native countdown — the system ticks this on its own once set,
            // no per-second updates needed from us. Pull down notifications
            // and it's right there counting down, like a clock app's timer.
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + remaining)
                .setContentText(pauseInfo)
        } else {
            // The system chronometer always ticks in real time once set —
            // it can't be "frozen" — so while paused we show a static
            // remaining-time line instead of a live (and misleading) clock.
            builder.setUsesChronometer(false)
                .setContentText("${DurationFormatter.formatClock(remaining)} left · $pauseInfo")
        }

        when {
            state.phase == SessionPhase.RUNNING ->
                builder.addAction(0, "Pause", actionPendingIntent(ACTION_PAUSE))
            state.phase == SessionPhase.PAUSED && state.currentPauseReason == PauseReason.MANUAL ->
                builder.addAction(0, "Resume", actionPendingIntent(ACTION_RESUME))
        }
        builder.addAction(0, "End Session", actionPendingIntent(ACTION_END))

        return builder.build()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, DeepWorkSessionService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        serviceScope.cancel()
    }
}
