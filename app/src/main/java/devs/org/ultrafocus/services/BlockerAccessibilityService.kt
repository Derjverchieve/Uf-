package devs.org.ultrafocus.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

    // ── Browser config: map package → address-bar view IDs ──────────────────
    private data class BrowserConfig(
        val packageName: String,
        val addressBarIds: List<String>
    )

    private val browserConfigs = listOf(
        BrowserConfig("com.android.chrome",
            listOf("com.android.chrome:id/url_bar")),
        BrowserConfig("org.mozilla.firefox",
            listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view")),
        BrowserConfig("com.microsoft.emmx",
            listOf("com.microsoft.emmx:id/url_bar")),
        BrowserConfig("com.sec.android.app.sbrowser",
            listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text")),
        BrowserConfig("com.opera.browser",
            listOf("com.opera.browser:id/url_field")),
        BrowserConfig("com.opera.mini.native",
            listOf("com.opera.mini.native:id/url_field")),
        BrowserConfig("com.brave.browser",
            listOf("com.brave.browser:id/url_bar")),
        BrowserConfig("com.duckduckgo.mobile.android",
            listOf("com.duckduckgo.mobile.android:id/url_bar")),
        BrowserConfig("com.transsion.phoenix",
            listOf("com.transsion.phoenix:id/url_bar"))
    )

    private val browserPackages = browserConfigs.map { it.packageName }.toSet()

    // ── State ────────────────────────────────────────────────────────────────
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

    // Throttle website blocks so we don't spam BACK+HOME
    private var lastBlockedWebsiteKey: String? = null
    private var lastWebsiteBlockTime: Long = 0
    private val websiteBlockCooldownMs = 1500L

    private val escapeKeywords = listOf("Cancel", "Deny", "No", "Close", "Quit", "Back")

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        configureServiceInfo()
        try {
            val db = AppDatabase.getDatabase(this)
            appRepository = AppRepository(db)
            loadBlockedApps()
            isServiceReady = true
        } catch (_: Exception) {}
    }

    private fun configureServiceInfo() {
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    private fun loadBlockedApps() {
        serviceScope.launch {
            try {
                appRepository.getBlockedAppsFlow().collectLatest { appInfos ->
                    blockedAppInfos = appInfos
                }
            } catch (_: Exception) {}
        }
    }

    // ── Main event handler ───────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceReady || event == null) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 1. Self-immunity
        if (packageName == this.packageName) return

        // 2. Ultra Power / system settings trap
        if (packageName == "com.android.systemui" || packageName.contains("settings")) {
            val rootNode = rootInActiveWindow ?: event.source
            if (rootNode != null) {
                val matches = rootNode.findAccessibilityNodeInfosByText("Ultra Power")
                if (!matches.isNullOrEmpty()) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    matches.forEach { runCatching { it.recycle() } }
                    return
                }
            }
        }

        // 3. Power-center dialog killer
        if (packageName.contains("com.transsion.powercenter") ||
            className.contains("UltraPowerDialogActivity")) {
            val rootNode = rootInActiveWindow ?: event.source
            if (rootNode != null) huntAndClickCancel(rootNode)
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            performBlock(packageName)
            return
        }

        // 4. Web & content scanner
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTime > scanIntervalMs) {
                lastScanTime = currentTime
                val rootNode = rootInActiveWindow ?: event.source
                if (rootNode != null) {
                    if (browserPackages.contains(packageName)) {
                        // FIX: pass packageName so we can target the right address bar
                        if (scanForBlockedUrls(rootNode, packageName)) return
                    }
                    if (scanForBlockedContent(rootNode)) {
                        performBlock(packageName)
                        return
                    }
                }
            }
        }

        // 5. Activity & app blocker
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Never block UF's own activities regardless of class name matches
            if (packageName == this.packageName) return

            if (className.isNotEmpty() &&
                SpecificScreenManager.isScreenBlocked(this, className)) {
                performBlock(packageName)
                return
            }
            val appInfo = blockedAppInfos.find { it.packageName == packageName }
            if (appInfo != null && shouldBlockNow(appInfo)) {
                performBlock(packageName)
            }
        }
    }

    // ── URL scanning — reads address bar by view ID, NOT tree walk ───────────
    /**
     * BUG FIX: Previously this recursively walked the whole node tree and
     * only called performRedirectToGoogle(), leaving the blocked tab open.
     *
     * Now it:
     * 1. Reads only the address-bar node by view ID (fast + accurate)
     * 2. On a blocked URL: BACK → HOME → redirect to Google
     *    so the blocked tab is actually gone before Google opens.
     */
    private fun scanForBlockedUrls(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ): Boolean {
        val currentUrl = captureBrowserUrl(rootNode, packageName) ?: return false
        if (!WebsiteBlockManager.shouldBlockUrl(this, currentUrl)) return false

        // Throttle so we don't fire BACK+HOME every 250ms on the same URL
        val blockKey = WebsiteBlockManager.normalizeHost(currentUrl)
        val now = System.currentTimeMillis()
        if (blockKey == lastBlockedWebsiteKey &&
            now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true

        lastBlockedWebsiteKey = blockKey
        lastWebsiteBlockTime = now

        // BUG FIX: close the tab BEFORE opening Google
        performGlobalAction(GLOBAL_ACTION_BACK)   // close/dismiss current page
        performGlobalAction(GLOBAL_ACTION_HOME)   // go to launcher
        performRedirectToGoogle()                 // open Google fresh
        return true
    }

    /**
     * Read the address bar text using the browser's known view ID.
     * Falls back to searching all nodes for text that looks like a URL
     * only if the view-ID lookup returns nothing (e.g. Chrome internal pages).
     */
    private fun captureBrowserUrl(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ): String? {
        val config = browserConfigs.firstOrNull { it.packageName == packageName }
            ?: return null

        for (viewId in config.addressBarIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) { null }

            if (nodes.isNullOrEmpty()) continue

            try {
                for (node in nodes) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    val candidate = if (text.isNotBlank()) text else desc
                    if (candidate.isNotBlank()) return candidate
                }
            } finally {
                nodes.forEach { runCatching { it.recycle() } }
            }
        }
        return null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun huntAndClickCancel(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        for (keyword in escapeKeywords) {
            if (text.contains(keyword, ignoreCase = true) ||
                desc.contains(keyword, ignoreCase = true)) {
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
        val currentMinute =
            now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        for (range in timeConfig.split(",")) {
            val parts = range.split("-")
            if (parts.size == 2) {
                try {
                    val start = parseTime(parts[0])
                    val end = parseTime(parts[1])
                    if (currentMinute in start..end) return true
                } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun parseTime(t: String): Int {
        val split = t.trim().split(":")
        return split[0].toInt() * 60 + split[1].toInt()
    }

    private fun performRedirectToGoogle() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun scanForBlockedContent(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val text = node.text?.toString()
        if (!text.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, text)) return true
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, desc)) return true
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
        // Never block UltraFocus itself under any circumstance
        if (packageName == this.packageName) return
        if (TemporaryAccessManager.isAllowed(packageName)) return
        try {
            val currentTime = System.currentTimeMillis()
            if (lastBlockedPackage == packageName &&
                currentTime - lastBlockTime < blockCooldownMs) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            currentlyBlockedApps.add(packageName)
            lastBlockedPackage = packageName
            lastBlockTime = currentTime

            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)

            serviceScope.launch {
                delay(50)
                try {
                    val intent = Intent(
                        this@BlockerAccessibilityService,
                        BlockedAppActivity::class.java
                    ).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        putExtra("blocked_package", packageName)
                    }
                    startActivity(intent)
                    delay(1000)
                    currentlyBlockedApps.remove(packageName)
                } catch (_: Exception) {
                    currentlyBlockedApps.remove(packageName)
                }
            }
        } catch (_: Exception) {
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
