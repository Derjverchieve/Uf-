package devs.org.ultrafocus.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import devs.org.ultrafocus.utils.DeepWorkSessionManager

/**
 * Re-arms an active session or cycle plan after a reboot.
 *
 * Just being manifest-registered for BOOT_COMPLETED is most of the job:
 * Android launches an app's process specifically to deliver a broadcast it
 * has declared interest in, and Application.onCreate() — which calls
 * DeepWorkSessionManager.init(), which runs the actual resume logic in
 * recoverOrphanedSession() — always runs before any component (including
 * this receiver's onReceive) executes in a freshly-started process. Calling
 * init() again here is a deliberate, harmless no-op safety net (init()
 * guards on an `initialized` flag); this receiver's real job is guaranteeing
 * the OS starts the process at all, promptly, rather than waiting for
 * something else — like the accessibility service reconnecting — to happen
 * to trigger it first.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        DeepWorkSessionManager.init(context.applicationContext)
    }
}
