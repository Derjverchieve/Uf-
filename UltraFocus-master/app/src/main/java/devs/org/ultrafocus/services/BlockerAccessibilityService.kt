package devs.org.ultrafocus.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import devs.org.ultrafocus.activities.BlockedAppActivity
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.repository.AppRepository
import devs.org.ultrafocus.utils.ContentBlockManager
import devs.org.ultrafocus.utils.SpecificScreenManager
import devs.org.ultrafocus.utils.TemporaryAccessManager
import devs.org.ultrafocus.utils.WebsiteBlockManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Calendar

class BlockerAccessibilityService : AccessibilityService() {

    private lateinit var appRepository: AppRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isServiceReady = false

    private val currentlyBlockedApps = mutableSetOf<String>()
    private var blockedAppInfos: List<devs.org.ultrafocus.model.AppInfo> = emptyList()

    private var lastScanTime: Long = 0
    private val scanIntervalMs = 250L

    private var lastBlockedPackage: String? = null
    private var lastBlockTime: Long = 0
    private val blockCooldownMs = 500L

    private val browserPackages = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.brave.browser",
        "com.duckduckgo.mobile.android", "com.transsion.phoenix"
    )

    private val escapeKeywords = listOf("Cancel", "Deny", "No", "Close", "Quit", "Back")

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val db = AppDatabase.getDatabase(this)
            appRepository = AppRepository(db)
            loadBlockedApps()
            isServiceReady = true
        } catch (e: Exception) { }
    }

    private fun loadBlockedApps() {
        serviceScope.launch {
            try {
                appRepository.getBlockedAppsFlow().collectLatest { appInfos ->
                    blockedAppInfos = appInfos
                }
            } catch (e: Exception) { }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceReady || event == null) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. SELF IMMUNITY
        if (packageName == this.packageName) return

        // ⚡ 2. ULTRA POWER & NOTIFICATION TRAP
        if (packageName == "com.android.systemui" || packageName.contains("settings")) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // Native search for "Ultra Power" text
                val matches = rootNode.findAccessibilityNodeInfosByText("Ultra Power")
                if (matches != null && matches.isNotEmpty()) {
                    // Close Shade / Edit Mode immediately
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    matches.forEach { it.recycle() }
                    return
                }
            }
        }

        // ⚡ 3. DIALOG KILLER (Power Center)
        if (packageName.contains("com.transsion.powercenter") ||
            className.contains("UltraPowerDialogActivity")) {

            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                huntAndClickCancel(rootNode)
            }
            // THE REQUESTED SEQUENCE:
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)

            performBlock(packageName)
            return
        }

        // 4. WEB & CONTENT SCANNER
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTime > scanIntervalMs) {
                lastScanTime = currentTime
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    if (browserPackages.contains(packageName)) {
                        if (scanForBlockedUrls(rootNode)) return
                    }
                    if (scanForBlockedContent(rootNode)) {
                        performBlock(packageName)
                        return
                    }
                }
            }
        }

        // 5. ACTIVITY & APP BLOCKER
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (className.isNotEmpty() && SpecificScreenManager.isScreenBlocked(this, className)) {
                performBlock(packageName)
                return
            }
            val appInfo = blockedAppInfos.find { it.packageName == packageName }
            if (appInfo != null && shouldBlockNow(appInfo)) {
                performBlock(packageName)
            }
        }
    }

    private fun huntAndClickCancel(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        for (keyword in escapeKeywords) {
            if (text.contains(keyword, ignoreCase = true) || desc.contains(keyword, ignoreCase = true)) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (huntAndClickCancel(child)) return true
                child.recycle()
            }
        }
        return false
    }

    private fun shouldBlockNow(appInfo: devs.org.ultrafocus.model.AppInfo): Boolean {
        val timeConfig = appInfo.fromTime
        if (timeConfig.isNullOrEmpty()) return true
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val ranges = timeConfig.split(",")
        for (range in ranges) {
            val parts = range.split("-")
            if (parts.size == 2) {
                try {
                    val start = parseTime(parts[0])
                    val end = parseTime(parts[1])
                    if (currentMinute in start..end) return true
                } catch (e: Exception) {}
            }
        }
        return false
    }

    private fun parseTime(t: String): Int {
        val split = t.trim().split(":")
        return split[0].toInt() * 60 + split[1].toInt()
    }

    private fun scanForBlockedUrls(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (text.isNotEmpty() && WebsiteBlockManager.shouldBlockUrl(this, text)) {
            performRedirectToGoogle()
            return true
        }
        if (desc.isNotEmpty() && WebsiteBlockManager.shouldBlockUrl(this, desc)) {
            performRedirectToGoogle()
            return true
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (scanForBlockedUrls(child)) return true
                child.recycle()
            }
        }
        return false
    }

    private fun performRedirectToGoogle() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) { }
    }

    private fun scanForBlockedContent(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.text != null && ContentBlockManager.containsBlockedContent(this, node.text.toString())) return true
        if (node.contentDescription != null && ContentBlockManager.containsBlockedContent(this, node.contentDescription.toString())) return true

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (scanForBlockedContent(child)) return true
                child.recycle()
            }
        }
        return false
    }

    private fun performBlock(packageName: String) {
        if (TemporaryAccessManager.isAllowed(packageName)) return

        try {
            val currentTime = System.currentTimeMillis()
            if (lastBlockedPackage == packageName && currentTime - lastBlockTime < blockCooldownMs) {
                // If we are spamming, just hit back again to be sure
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }

            currentlyBlockedApps.add(packageName)
            lastBlockedPackage = packageName
            lastBlockTime = currentTime

            // --- THE REQUESTED SEQUENCE ---
            performGlobalAction(GLOBAL_ACTION_BACK) // 1. Close current activity
            performGlobalAction(GLOBAL_ACTION_HOME) // 2. Go to home screen
            // -----------------------------

            serviceScope.launch {
                delay(50)
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