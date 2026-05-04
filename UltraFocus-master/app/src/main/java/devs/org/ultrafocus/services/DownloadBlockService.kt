package devs.org.ultrafocus.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.DownloadBlockPrefs
import java.io.File

class DownloadBlockService : Service() {

    private var fileObserver: FileObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadBlockPrefs.setEnabled(this, true)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fileObserver?.stopWatching()
        fileObserver = null
        DownloadBlockPrefs.setEnabled(this, false)
    }

    // ─────────────────────────────────────────────────────────
    // File Watcher
    // ─────────────────────────────────────────────────────────

    private fun startWatching() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )

        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val mask = (
            FileObserver.CREATE or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE
        )

        fileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(downloadsDir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return

                    if (event and mask != 0) {
                        deleteQuietly(File(downloadsDir, path))
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(downloadsDir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return

                    if (event and mask != 0) {
                        deleteQuietly(File(downloadsDir.absolutePath, path))
                    }
                }
            }
        }

        fileObserver?.startWatching()
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) {
                val deleted = file.delete()

                if (!deleted) {
                    Thread.sleep(300)
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    // ─────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Blocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while download blocking is active"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Blocking Active")
            .setContentText("New downloads are deleted automatically.")
            .setSmallIcon(R.drawable.ic_delete)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "download_block_channel"
    }
}
