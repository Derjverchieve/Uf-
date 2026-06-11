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

    private data class BrowserConfig(
        val packageName: String,
        val addressBarIds: List<String>
    )

    private val browserConfigs = listOf(
        BrowserConfig("com.android.chrome", listOf("com.android.chrome:id/url_bar")),
        BrowserConfig("org.mozilla.firefox", listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view")),
        BrowserConfig("com.microsoft.emmx", listOf("com.microsoft.emmx:id/url_bar")),
        BrowserConfig("com.sec.android.app.sbrowser", listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text")),
        BrowserConfig("com.opera.browser", listOf("com.opera.browser:id/url_field")),
        BrowserConfig("com.opera.mini.native", listOf("com.opera.mini.native:id/url_field")),
        BrowserConfig("com.brave.browser", listOf("com.brave.browser:id/url_bar")),
        BrowserConfig("com.duckduckgo.mobile.android", listOf("com.duckduckgo.mobile.android:id/url_bar")),
        BrowserConfig("com.transsion.phoenix", listOf("com.transsion.phoenix:id/url_bar"))
    )

    private val browserPackages = browserConfigs.map { it.packageName }.toSet()

    // Chrome preview / ephemeral tab IDs
    private val chromePreviewIds = listOf(
        "com.android.chrome:id/ephemeral_tab_view",
        "com.android.chrome:id/preview_tab_view",
        "com.android.chrome:id/tab_modal",
        "com.android.chrome:id/open_new_tab_chip"
    )

    private val chromeTabSwitcherIds = listOf(
        "com.android.chrome:id/tab_switcher_toolbar",
        "com.android.chrome:id/tab_switcher_recycler_view",
        "com.android.chrome:id/carousel_tab_switcher",
        "com.android.chrome:id/tab_switcher"
    )

    private val previewCloseIds = listOf(
        "com.android.chrome:id/close_button",
        "com.android.chrome:id/ephemeral_tab_close",
        "com.android.chrome:id/preview_close",
        "com.android.chrome:id/action_close"
    )
    private val previewCloseDescriptions = listOf("close", "dismiss", "exit preview", "x")

    // Packages that must never be scanned for keywords
    private val contentScanExemptPackages = setOf(
        "com.android.systemui", "com.android.launcher3",
        "com.google.android.apps.nexuslauncher", "com.transsion.xlauncher",
        "com.hihonor.android.launcher", "com.miui.home",
        "com.sec.android.app.launcher", "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
        "com.swiftkey.swiftkeyapp", "com.transsion.inputmethod"
    )

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
    private val blockCooldownMs = 800L

    private var lastBlockedWebsiteKey: String? = null
    private var lastWebsiteBlockTime: Long = 0
    private val websiteBlockCooldownMs = 1500L

    private var previewClickJob: Job? = null

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceReady || event == null) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        if (packageName == this.packageName) return

        // ── Preview‑page detection ────────────────────────────────────────
        if (browserPackages.contains(packageName) &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {

            val root = rootInActiveWindow
            if (root != null && findPreviewTriggerFromNode(root)) {
                closePreviewAndExit(packageName)
                return
            }

            previewClickJob?.cancel()
            previewClickJob = serviceScope.launch {
                delay(200)
                val delayedRoot = rootInActiveWindow ?: return@launch
                if (delayedRoot.packageName?.toString() == packageName) {
                    if (scanForBlockedUrls(delayedRoot, packageName) ||
                        scanForBlockedContent(delayedRoot, packageName, hostnameCheck = true)) {
                        closePreviewAndExit(packageName)
                    }
                }
            }
            return
        }

        // ── Main scanning loop ────────────────────────────────────────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScanTime > scanIntervalMs) {
                lastScanTime = currentTime

                // ── Split‑screen guard: block any blocked app ──────────────
                val visibleWindows = windows
                val isRealSplitScreen = !visibleWindows.isNullOrEmpty() &&
                    visibleWindows.any { window ->
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                    }

                if (isRealSplitScreen) {
                    val blockedPkg = visibleWindows!!
                        .mapNotNull { it.root?.packageName?.toString() }
                        .firstOrNull { pkg ->
                            pkg != this.packageName &&
                            blockedAppInfos.any { it.packageName == pkg && shouldBlockNow(it) }
                        }
                    if (blockedPkg != null) {
                        // Kill the blocked app's process so it can't linger in recents
                        try {
                            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.killBackgroundProcesses(blockedPkg)
                        } catch (_: Exception) {}
                        performBlock(blockedPkg)
                        return
                    }
                }

                val rootNode = rootInActiveWindow ?: event.source ?: return
                val rootPkg = rootNode.packageName?.toString().orEmpty()
                if (rootPkg == this.packageName) return

                if (browserPackages.contains(packageName)) {
                    if (scanForBlockedUrls(rootNode, packageName)) return

                    val inPageView = isBrowserInPageView(rootNode, packageName)
                    val inPreview = isChromePreview(rootNode, packageName)

                    if ((inPageView || inPreview) &&
                        scanForBlockedContent(rootNode, packageName, hostnameCheck = true)) {
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

        // Activity & specific screen blocker
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

    // ── Preview close logic ───────────────────────────────────────────────
    private fun closePreviewAndExit(packageName: String) {
        if (TemporaryAccessManager.isAllowed(packageName)) return

        val now = System.currentTimeMillis()
        if (lastBlockedPackage == packageName && now - lastBlockTime < blockCooldownMs) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        lastBlockedPackage = packageName
        lastBlockTime = now

        val root = rootInActiveWindow
        if (root != null && findAndClickPreviewClose(root)) {
            serviceScope.launch {
                delay(300)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        serviceScope.launch {
            delay(350)
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(350)
            if (rootInActiveWindow?.packageName?.toString() == packageName) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(200)
            }
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun findAndClickPreviewClose(rootNode: AccessibilityNodeInfo): Boolean {
        for (id in previewCloseIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }
        }
        return findCloseByDescription(rootNode, HashSet())
    }

    private fun findCloseByDescription(node: AccessibilityNodeInfo, visited: MutableSet<Int>): Boolean {
        val key = System.identityHashCode(node)
        if (!visited.add(key)) return false

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (previewCloseDescriptions.any { desc.contains(it) || text.contains(it) }) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findCloseByDescription(child, visited)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // ── Browser helpers ────────────────────────────────────────────────────
    private fun isBrowserInPageView(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        val config = browserConfigs.firstOrNull { it.packageName == packageName } ?: return false
        for (viewId in config.addressBarIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Exception) { null }
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
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { runCatching { it.recycle() } }
                return false
            }
        }
        for (id in chromePreviewIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                nodes.forEach { runCatching { it.recycle() } }
                return true
            }
        }
        return false
    }

    private fun captureBrowserUrl(rootNode: AccessibilityNodeInfo, packageName: String): String? {
        val config = browserConfigs.firstOrNull { it.packageName == packageName } ?: return null
        for (viewId in config.addressBarIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Exception) { null }
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

    // ── URL scanning ───────────────────────────────────────────────────────
    private fun scanForBlockedUrls(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        val currentUrl = captureBrowserUrl(rootNode, packageName) ?: return false

        if (WebAllowlistManager.isBlockedByAllowlist(this, currentUrl)) {
            val blockKey = WebAllowlistManager::class.java.simpleName
            val now = System.currentTimeMillis()
            if (blockKey == lastBlockedWebsiteKey && now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true
            lastBlockedWebsiteKey = blockKey
            lastWebsiteBlockTime = now
            tryRedirectBrowserTab(rootNode, packageName)
            performGlobalAction(GLOBAL_ACTION_HOME)
            performRedirectToGoogle()
            return true
        }

        if (!WebsiteBlockManager.shouldBlockUrl(this, currentUrl)) return false

        val blockKey = WebsiteBlockManager.normalizeHost(currentUrl)
        if (blockKey == lastBlockedWebsiteKey &&
            System.currentTimeMillis() - lastWebsiteBlockTime < websiteBlockCooldownMs) return true
        lastBlockedWebsiteKey = blockKey
        lastWebsiteBlockTime = System.currentTimeMillis()

        tryRedirectBrowserTab(rootNode, packageName)
        performGlobalAction(GLOBAL_ACTION_HOME)
        performRedirectToGoogle()
        return true
    }

    private fun tryRedirectBrowserTab(rootNode: AccessibilityNodeInfo, packageName: String) {
        val config = browserConfigs.firstOrNull { it.packageName == packageName } ?: return
        for (viewId in config.addressBarIds) {
            val nodes = try { rootNode.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Exception) { null }
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

    // ── Preview trigger text search ────────────────────────────────────────
    private fun containsPreviewTrigger(text: String): Boolean {
        val norm = text.lowercase()
        return norm.contains("preview page") || norm.contains("open preview") || norm.contains("preview")
    }

    private fun findPreviewTriggerFromNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return findPreviewTriggerFromNodeWithVisited(node, HashSet())
    }

    private fun findPreviewTriggerFromNodeWithVisited(node: AccessibilityNodeInfo?, visited: MutableSet<Int>): Boolean {
        if (node == null) return false
        val key = System.identityHashCode(node)
        if (!visited.add(key)) return false
        val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()
        if (text.isNotBlank() && containsPreviewTrigger(text)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findPreviewTriggerFromNodeWithVisited(child, visited)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // ── Content scanning (keywords & hostnames) ────────────────────────────
    private fun scanForBlockedContent(
        node: AccessibilityNodeInfo,
        foregroundPackage: String = "",
        hostnameCheck: Boolean = false
    ): Boolean {
        if (!node.isVisibleToUser) return false
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrEmpty() && ContentBlockManager.containsBlockedContent(this, text)) return true
        if (!desc.isNullOrEmpty() && ContentBlockManager.containsBlockedContent(this, desc)) return true
        if (hostnameCheck && blockedHostsCache.isNotEmpty()) {
            val combined = listOfNotNull(text, desc).joinToString(" ").lowercase()
            if (combined.isNotBlank() && blockedHostsCache.any { combined.contains(it) }) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scanForBlockedContent(child, foregroundPackage, hostnameCheck)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    // ── App blocking helpers ───────────────────────────────────────────────
    private fun shouldBlockNow(appInfo: devs.org.ultrafocus.model.AppInfo): Boolean {
        val timeConfig = appInfo.fromTime
        if (timeConfig.isNullOrEmpty()) return true
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_BROWSABLE)
            })
        } catch (_: Exception) {}
    }

    private fun performBlock(packageName: String) {
        if (packageName == this.packageName) return
        if (TemporaryAccessManager.isAllowed(packageName)) return
        try {
            val currentTime = System.currentTimeMillis()
            if (lastBlockedPackage == packageName && currentTime - lastBlockTime < blockCooldownMs) {
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
