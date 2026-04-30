package devs.org.ultrafocus.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import devs.org.ultrafocus.activities.BlockedAppActivity
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.repository.AppRepository
import devs.org.ultrafocus.utils.ContentBlockManager
import devs.org.ultrafocus.utils.SpecificScreenManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class BlockerAccessibilityService : AccessibilityService() {

    private var blockedApps: List<String> = emptyList()
    private lateinit var appRepository: AppRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isServiceReady = false

    // Blocking Logic
    private var lastBlockedPackage: String? = null
    private var lastBlockTime: Long = 0
    private val blockCooldownMs = 2000L

    // Optimization: Scan faster but smarter
    private var lastScanTime: Long = 0
    private val scanIntervalMs = 250L // 4 checks per second max

    private val currentlyBlockedApps = mutableSetOf<String>()
    private var blockedAppInfos: List<devs.org.ultrafocus.model.AppInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val db = AppDatabase.getDatabase(this)
            appRepository = AppRepository(db)
            loadBlockedApps()
            isServiceReady = true
        } catch (e: Exception) {
            Log.e("BlockerService", "Error in onServiceConnected", e)
        }
    }

    private fun loadBlockedApps() {
        serviceScope.launch {
            try {
                appRepository.getBlockedAppsFlow()
                    .collectLatest { appInfos ->
                        blockedAppInfos = appInfos
                        blockedApps = appInfos.map { it.packageName }
                    }
            } catch (e: Exception) { /* Ignore */ }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceReady || event == null) return

        val packageName = event.packageName?.toString()
        if (packageName == this.packageName || packageName == "com.android.systemui") return

        // 1. CONTENT SCANNER (Optimized)
        // We allow WINDOW_CONTENT_CHANGED but we throttle it heavily
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTime > scanIntervalMs) {
                lastScanTime = currentTime
                // Get the root node of the ACTIVE window only
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    if (scanForBlockedContent(rootNode)) {
                        Log.d("BlockerService", "Blocked Content Detected!")
                        performBlock(packageName ?: "Unknown")
                        return
                    }
                }
            }
        }

        // 2. SPECIFIC CLASS BLOCKER
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (className != null) {
                if (SpecificScreenManager.isDebugMode(this)) {
                    Toast.makeText(this, "Class: $className", Toast.LENGTH_SHORT).show()
                }
                if (SpecificScreenManager.isScreenBlocked(this, className)) {
                    performBlock(packageName ?: "Unknown")
                    return
                }
            }
            // 3. STANDARD BLOCKER
            handleStandardAppBlock(packageName)
        }
    }

    private fun scanForBlockedContent(node: AccessibilityNodeInfo): Boolean {
        // CRITICAL FIX: If the user can't see it, don't block it!
        if (!node.isVisibleToUser) return false

        // Check text
        if (node.text != null && ContentBlockManager.containsBlockedContent(this, node.text.toString())) {
            return true
        }

        // Check description
        if (node.contentDescription != null && ContentBlockManager.containsBlockedContent(this, node.contentDescription.toString())) {
            return true
        }

        // Check children
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (scanForBlockedContent(child)) {
                    return true
                }
                child.recycle()
            }
        }
        return false
    }

    private fun handleStandardAppBlock(packageName: String?) {
        if (packageName == null) return
        val appInfo = blockedAppInfos.find { it.packageName == packageName }
        if (appInfo != null && shouldBlockNow(appInfo)) {
            performBlock(packageName)
        } else {
            currentlyBlockedApps.remove(packageName)
        }
    }

    private fun shouldBlockNow(appInfo: devs.org.ultrafocus.model.AppInfo): Boolean {
        val from = appInfo.fromTime
        val to = appInfo.toTime
        val repeat = appInfo.repeatMode
        if (from.isNullOrEmpty() || to.isNullOrEmpty() || repeat.isNullOrEmpty()) return true

        val now = java.util.Calendar.getInstance()
        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val fromParts = from.split(":").map { it.toIntOrNull() ?: 0 }
        val toParts = to.split(":").map { it.toIntOrNull() ?: 0 }
        val fromMinutes = fromParts[0] * 60 + fromParts[1]
        val toMinutes = toParts[0] * 60 + toParts[1]
        val inTimeRange = if (fromMinutes <= toMinutes) nowMinutes in fromMinutes..toMinutes else nowMinutes >= fromMinutes || nowMinutes <= toMinutes

        return inTimeRange
    }

    private fun performBlock(packageName: String) {
        try {
            val currentTime = System.currentTimeMillis()
            if (lastBlockedPackage == packageName && currentTime - lastBlockTime < blockCooldownMs) return
            if (currentlyBlockedApps.contains(packageName)) return

            currentlyBlockedApps.add(packageName)
            lastBlockedPackage = packageName
            lastBlockTime = currentTime

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(homeIntent)

            serviceScope.launch {
                delay(200)
                try {
                    val intent = Intent(this@BlockerAccessibilityService, BlockedAppActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("blocked_package", packageName)
                    }
                    startActivity(intent)
                    delay(1000)
                    currentlyBlockedApps.remove(packageName)
                } catch (e: Exception) {
                    currentlyBlockedApps.remove(packageName)
                }
            }
        } catch (e: Exception) {
            currentlyBlockedApps.remove(packageName)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isServiceReady = false
        currentlyBlockedApps.clear()
    }
}