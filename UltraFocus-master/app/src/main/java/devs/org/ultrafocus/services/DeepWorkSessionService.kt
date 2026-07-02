package devs.org.ultrafocus.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.activities.DeepWorkSessionActivity
import devs.org.ultrafocus.model.PauseReason
import devs.org.ultrafocus.model.SessionPhase
import devs.org.ultrafocus.utils.DeepWorkSessionManager
import devs.org.ultrafocus.utils.DurationFormatter
import devs.org.ultrafocus.utils.FacePresenceDetector
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
        private const val TARGET_CHANNEL_ID = "UltraFocusTargetReached"
        private const val TARGET_NOTIFICATION_ID = 1011
        private val ALARM_PATTERN = longArrayOf(0, 400, 200, 400, 200, 400)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var faceDetector: FacePresenceDetector? = null
    private var sessionActive = false

    override fun onCreate() {
        super.onCreate()
        DeepWorkSessionManager.onTargetReached = { fireTargetReachedAlert() }
    }

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
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Deep Work Session", NotificationManager.IMPORTANCE_LOW)
            )
            // Separate, high-importance channel just for the target-reached
            // alert — the ongoing session channel is deliberately LOW
            // importance (silent, no heads-up) so it doesn't interrupt you
            // every time the notification updates; this one is the opposite,
            // on purpose, since this is the one moment it should grab you.
            val targetChannel = NotificationChannel(
                TARGET_CHANNEL_ID, "Focus Target Reached", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = ALARM_PATTERN
            }
            nm.createNotificationChannel(targetChannel)
        }
    }

    private fun observeState() {
        if (observeJob != null) return
        observeJob = serviceScope.launch {
            DeepWorkSessionManager.state.collectLatest { state ->
                if (state.phase == SessionPhase.IDLE) {
                    if (sessionActive) {
                        stopFaceDetector()
                        sessionActive = false
                    }
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    if (!sessionActive) {
                        sessionActive = true
                        startFaceDetector()
                    }
                    notificationManager().notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    private fun startFaceDetector() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (faceDetector != null) return
        faceDetector = FacePresenceDetector(
            context = this,
            absentGraceMs = 5_000L,
            onFacePresent = { DeepWorkSessionManager.onFacePresent() },
            onFaceAbsent = { DeepWorkSessionManager.onFaceAbsent() }
        )
        faceDetector?.start()
    }

    private fun stopFaceDetector() {
        faceDetector?.stop()
        faceDetector = null
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
        // No "End Session" action: hard-lock sessions auto-complete the
        // instant they hit target (no overtime), so there's never a valid
        // moment for a manual end from the notification shade either.

        return builder.build()
    }

    private fun fireTargetReachedAlert() {
        val state = DeepWorkSessionManager.state.value
        val targetStr = DurationFormatter.formatCompact(state.targetDurationMs)
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, DeepWorkSessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, TARGET_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Session complete — $targetStr")
            .setContentText("${state.primaryAppName ?: "Session"} · target reached, session ended.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        notificationManager().notify(TARGET_NOTIFICATION_ID, notification)
        vibrate()
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(ALARM_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ALARM_PATTERN, -1)
            }
        } catch (_: Exception) {
            // Vibration is a nice-to-have on top of the notification itself
            // (which already carries sound) — not worth crashing over.
        }
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
        DeepWorkSessionManager.onTargetReached = null
        stopFaceDetector()
        observeJob?.cancel()
        serviceScope.cancel()
    }
}
