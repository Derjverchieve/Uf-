package devs.org.ultrafocus.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.activities.DegradedModeActivity
import devs.org.ultrafocus.activities.MainActivity
import devs.org.ultrafocus.utils.EscapeManager

class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 2000L
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            // Attempt to promote to Foreground immediately
            if (!startForegroundSafe()) {
                // If we can't go foreground (Android 12 restriction), stop self to avoid crash
                stopSelf()
                return START_NOT_STICKY
            }
            isRunning = true
            checkStatus()
        }
        return START_STICKY
    }

    private fun startForegroundSafe(): Boolean {
        try {
            val channelId = "UltraFocusWatchdog"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Focus Protection", NotificationManager.IMPORTANCE_LOW)
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("UltraFocus Active")
                .setContentText("Monitoring system integrity.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()

            startForeground(999, notification)
            return true
        } catch (e: Exception) {
            // This catches the Android 12 Background Start crash
            e.printStackTrace()
            return false
        }
    }

    private fun checkStatus() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val isAccessOn = isAccessibilityEnabled(applicationContext)

                if (isAccessOn) {
                    if (EscapeManager.getTimeRemaining(applicationContext) > 0) {
                        EscapeManager.restoreNormalState(applicationContext)
                    }
                } else {
                    if (!EscapeManager.isEscapeAllowed(applicationContext)) {
                        EscapeManager.triggerBreach(applicationContext)

                        try {
                            val intent = Intent(applicationContext, DegradedModeActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            // IMPORTANT: Reorder to front if already open
                            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore background start errors
                        }
                    }
                }
                handler.postDelayed(this, checkInterval)
            }
        }, checkInterval)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServices.contains("${context.packageName}/devs.org.ultrafocus.services.BlockerAccessibilityService")
    }
}