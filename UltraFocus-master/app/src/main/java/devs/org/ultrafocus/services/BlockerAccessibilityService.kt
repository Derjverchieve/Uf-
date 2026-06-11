package devs.org.ultrafocus.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import devs.org.ultrafocus.activities.BlockedAppActivity
import devs.org.ultrafocus.activities.SoftBlockActivity
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.repository.AppRepository
import devs.org.ultrafocus.utils.ContentBlockManager
import devs.org.ultrafocus.utils.SoftBlockManager
import devs.org.ultrafocus.utils.SpecificScreenManager
import devs.org.ultrafocus.utils.TemporaryAccessManager
import devs.org.ultrafocus.utils.WebAllowlistManager
import devs.org.ultrafocus.utils.WebBlockMode
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

    // ── Chrome preview / ephemeral tab view IDs ─────────────────────────────
    private val chromePreviewIds = listOf(
        "com.android.chrome:id/ephemeral_tab_view",
        "com.android.chrome:id/preview_tab_view",
        "com.android.chrome:id/tab_modal",
        "com.android.chrome:id/open_new_tab_chip"          // floating "Open in new tab" chip
    )

    // IDs that only appear in the tab switcher – we must NOT confuse them with a preview
    private val chromeTabSwitcherIds = listOf(
        "com.android.chrome:id/tab_switcher_toolbar",
        "com.android.chrome:id/tab_switcher_recycler_view",
        "com.android.chrome:id/carousel_tab_switcher",
        "com.android.chrome:id/tab_switcher"
    )

    /**
     * Packages that are NEVER subject to keyword or hostname content scanning.
     */
    private val contentScanExemptPackages = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.transsion.xlauncher",
        "com.hihonor.android.launcher",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.swiftkey.swiftkeyapp",
        "com.transsion.inputmethod"
    )

    // ── State ────────────────────────────────────────────────────────────────
    private lateinit var appRepository: AppRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isServiceReady = false

    private val currentlyBlockedApps = mutableSetOf<String>()
    private var blockedAppInfos: List<devs.org.ultrafocus.model.AppInfo> = emptyList()

    @Volatile private var blockedHostsCache: Set<String> = emptySet()

    private var lastScanTime: Long = 0
    private val scanIntervalMs = 50L

    private var lastBlockedPackage: String? = null
    private var lastBlockTime: Long = 0
    private val blockCooldownMs = 500L

    private var lastBlockedWebsiteKey: String? = null
    private var lastWebsiteBlockTime: Long = 0
    private val websiteBlockCooldownMs = 1500L

    // Job for the delayed scan after a click (catches preview loads)
    private var previewClickJob: Job? = null

    private val escapeKeywords = listOf("Cancel", "Deny", "No", "Close", "Quit", "Back")

    private val dangerousPackages = setOf(
        "com.android.vending",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.transsion.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    private fun isDangerousPackage(pkg: String) =
        dangerousPackages.contains(pkg) ||
        pkg.contains("packageinstaller", ignoreCase = true) ||
        pkg.contains("packagemanager", ignoreCase = true)

    private fun isBrowserInPageView(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ): Boolean {
        val config = browserConfigs.firstOrNull { it.packageName == packageName }
            ?: return false
        for (viewId in config.addressBarIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { runCatching { it.recycle() } }
                return true
            }
        }
        return false
    }

    private fun isChromePreview(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        if (packageName != "com.android.chrome") return false
        for (id in chromeTabSwitcherIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { runCatching { it.recycle() } }
                return false
            }
        }
        for (id in chromePreviewIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { runCatching { it.recycle() } }
                return true
            }
        }
        return false
    }

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
                AccessibilityEvent.TYPE_VIEW_CLICKED or
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
        serviceScope.launch(Dispatchers.IO) {
            try {
                val hosts = WebsiteBlockManager.getRules(this@BlockerAccessibilityService)
                    .filter { it.mode == WebBlockMode.GENERAL }
                    .map { it.host }
                    .toSet()
                blockedHostsCache = hosts
            } catch (_: Exception) {}
        }
    }

    // ── Main event handler ───────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceReady || event == null) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        if (packageName == this.packageName) return

        // ── Preview‑page detection (enhanced) ────────────────────────────────
        if (browserPackages.contains(packageName) &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {

            val root = rootInActiveWindow

            // Immediate text check: "Preview page" / "Open preview" visible anywhere
            if (root != null && findPreviewTriggerFromNode(root)) {
                // ★ NEW: use the preview‑specific close routine instead of generic block
                closePreviewAndExit(packageName)
                return
            }

            // Cancel any pending delayed scan and schedule a new one.
            previewClickJob?.cancel()
            previewClickJob = serviceScope.launch {
                delay(200)
                val delayedRoot = rootInActiveWindow ?: return@launch
                if (delayedRoot.packageName?.toString() == packageName) {
                    if (scanForBlockedUrls(delayedRoot, packageName) ||
                        scanForBlockedContent(delayedRoot, packageName, hostnameCheck = true)) {
                        // ★ Use the same close routine for delayed preview detection
                        closePreviewAndExit(packageName)
                    }
                }
            }
            return
        }

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

                // ── Split screen dangerous-package guard ──────────────────────
                val visibleWindows = windows
                val isRealSplitScreen = !visibleWindows.isNullOrEmpty() &&
                    visibleWindows.any { window ->
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                    }

                if (isRealSplitScreen) {
                    val dangerousPkg = visibleWindows!!
                        .mapNotNull { it.root?.packageName?.toString() }
                        .firstOrNull { isDangerousPackage(it) }
                    if (dangerousPkg != null) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        try {
                            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.killBackgroundProcesses(dangerousPkg)
                        } catch (_: Exception) {}
                        serviceScope.launch {
                            delay(200)
                            performGlobalAction(GLOBAL_ACTION_RECENTS)
                            delay(200)
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        return
                    }
                }
                val rootNode = rootInActiveWindow ?: event.source
                if (rootNode != null) {
                    val rootPkg = rootNode.packageName?.toString().orEmpty()
                    if (rootPkg == this.packageName) return

                    if (browserPackages.contains(packageName)) {
                        if (scanForBlockedUrls(rootNode, packageName)) return

                        val inPageView = isBrowserInPageView(rootNode, packageName)
                        val inPreview = isChromePreview(rootNode, packageName)

                        if ((inPageView || inPreview) &&
                            scanForBlockedContent(rootNode, packageName, hostnameCheck = true)) {
                            // If we’re in a preview, use the close routine; otherwise normal block
                            if (inPreview) {
                                closePreviewAndExit(packageName)
                            } else {
                                performBlock(packageName)
                            }
                            return
                        }
                    } else {
                        if (!contentScanExemptPackages.contains(packageName) &&
                            scanForBlockedContent(rootNode, packageName, hostnameCheck = false)) {
                            performBlock(packageName)
                            return
                        }
                    }
                }
            }
        }

        // 5. Activity & app blocker
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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

    /**
     * ★ New: Closes Chrome’s preview / ephemeral tab by hitting back twice,
     * then returns to home. This guarantees the preview is fully dismissed.
     */
    private fun closePreviewAndExit(packageName: String) {
        if (TemporaryAccessManager.isAllowed(packageName)) return

        // Throttle to avoid rapid successive actions
        val now = System.currentTimeMillis()
        if (lastBlockedPackage == packageName && now - lastBlockTime < blockCooldownMs) {
            // Already closed recently; just ensure we're on home
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        lastBlockedPackage = packageName
        lastBlockTime = now

        // Step 1: Back out of the preview (first back)
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Step 2: Wait a beat, then another back to fully exit the ephemeral tab
        serviceScope.launch {
            delay(150)
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(150)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ── URL scanning ─────────────────────────────────────────────────────────
    private fun scanForBlockedUrls(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ): Boolean {
        val currentUrl = captureBrowserUrl(rootNode, packageName) ?: return false

        if (WebAllowlistManager.isBlockedByAllowlist(this, currentUrl)) {
            val blockKey = WebAllowlistManager::class.java.simpleName
            val now = System.currentTimeMillis()
            if (blockKey == lastBlockedWebsiteKey &&
                now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true
            lastBlockedWebsiteKey = blockKey
            lastWebsiteBlockTime = now
            tryRedirectBrowserTab(rootNode, packageName)
            performGlobalAction(GLOBAL_ACTION_HOME)
            performRedirectToGoogle()
            return true
        }

        if (!WebsiteBlockManager.shouldBlockUrl(this, currentUrl)) return false

        val blockKey = WebsiteBlockManager.normalizeHost(currentUrl)
        val now = System.currentTimeMillis()
        if (blockKey == lastBlockedWebsiteKey &&
            now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true

        lastBlockedWebsiteKey = blockKey
        lastWebsiteBlockTime = now

        tryRedirectBrowserTab(rootNode, packageName)
        performGlobalAction(GLOBAL_ACTION_HOME)
        performRedirectToGoogle()
        return true
    }

    private fun tryRedirectBrowserTab(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ) {
        val config = browserConfigs.firstOrNull { it.packageName == packageName } ?: return
        for (viewId in config.addressBarIds) {
            val nodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) { null }
            if (nodes.isNullOrEmpty()) continue
            try {
                val urlNode = nodes.firstOrNull() ?: continue
                urlNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "https://www.google.com"
                )
                urlNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return
            } finally {
                nodes.forEach { runCatching { it.recycle() } }
            }
        }
    }

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
    private fun containsPreviewTrigger(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("preview page") ||
            normalized.contains("open preview") ||
            normalized.contains("preview")
    }

    private fun findPreviewTriggerFromNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return findPreviewTriggerFromNodeWithVisited(node, HashSet())
    }

    private fun findPreviewTriggerFromNodeWithVisited(
        node: AccessibilityNodeInfo?,
        visited: MutableSet<Int>
    ): Boolean {
        if (node == null) return false
        val key = System.identityHashCode(node)
        if (!visited.add(key)) return false
        val text = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString()
        ).joinToString(" ").trim()
        if (text.isNotBlank() && containsPreviewTrigger(text)) return true
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    if (findPreviewTriggerFromNodeWithVisited(child, visited)) return true
                } finally {
                    child.recycle()
                }
            }
        }
        return false
    }

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

    private fun scanForBlockedContent(
        node: AccessibilityNodeInfo,
        foregroundPackage: String = "",
        hostnameCheck: Boolean = false
    ): Boolean {
        if (!node.isVisibleToUser) return false
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, text)) return true
        if (!desc.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, desc)) return true
        if (hostnameCheck && blockedHostsCache.isNotEmpty()) {
            val combined = listOfNotNull(text, desc).joinToString(" ").lowercase()
            if (combined.isNotBlank()) {
                for (host in blockedHostsCache) {
                    if (combined.contains(host)) return true
                }
            }
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (scanForBlockedContent(child, foregroundPackage, hostnameCheck)) return true
                child.recycle()
            }
        }
        return false
    }

    private fun performBlock(packageName: String) {
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
            val isSoft = SoftBlockManager.isSoftBlocked(this, packageName)
            serviceScope.launch {
                delay(50)
                try {
                    val intent = if (isSoft) {
                        val challenge = SoftBlockManager.generateChallenge(this@BlockerAccessibilityService, packageName)
                        Intent(this@BlockerAccessibilityService, SoftBlockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            putExtra("blocked_package", packageName)
                            putExtra("challenge_code", challenge)
                        }
                    } else {
                        Intent(this@BlockerAccessibilityService, BlockedAppActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            putExtra("blocked_package", packageName)
                        }
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
        previewClickJob?.cancel()
        serviceScope.cancel()
        isServiceReady = false
        currentlyBlockedApps.clear()
    }
}
