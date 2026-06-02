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

    // ── State ────────────────────────────────────────────────────────────────
    private lateinit var appRepository: AppRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isServiceReady = false

    private val currentlyBlockedApps = mutableSetOf<String>()
    private var blockedAppInfos: List<devs.org.ultrafocus.model.AppInfo> = emptyList()

    // Cached set of blocked hostnames — kept in sync with WebsiteBlockManager.
    // Used by scanForBlockedContent so reader/preview mode can't bypass URL detection.
    @Volatile private var blockedHostsCache: Set<String> = emptySet()

    private var lastScanTime: Long = 0
    private val scanIntervalMs = 50L

    private var lastBlockedPackage: String? = null
    private var lastBlockTime: Long = 0
    private val blockCooldownMs = 500L

    // Throttle website blocks
    private var lastBlockedWebsiteKey: String? = null
    private var lastWebsiteBlockTime: Long = 0
    private val websiteBlockCooldownMs = 1500L

    private val escapeKeywords = listOf("Cancel", "Deny", "No", "Close", "Quit", "Back")

    /**
     * Packages that could be used to uninstall UltraFocus or install bypass apps.
     * Only acted on when detected in split screen — single-window use is fine
     * (e.g. updating apps legitimately). Split screen is the specific exploit
     * because it lets these apps run alongside a blocked app simultaneously.
     */
    private val dangerousPackages = setOf(
        "com.android.vending",                   // Play Store
        "com.android.packageinstaller",          // Stock package installer
        "com.google.android.packageinstaller",   // Google package installer
        "com.transsion.packageinstaller",        // Transsion variant
        "com.miui.packageinstaller",             // MIUI variant
        "com.samsung.android.packageinstaller"  // Samsung variant
    )

    private fun isDangerousPackage(pkg: String) =
        dangerousPackages.contains(pkg) ||
        pkg.contains("packageinstaller", ignoreCase = true) ||
        pkg.contains("packagemanager", ignoreCase = true)

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
        // Refresh blocked hostname cache for reader-mode detection.
        // Runs on a background thread since SharedPreferences reads are fast
        // but we don't want to block the main thread on startup.
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

        // 1. Self-immunity
        if (packageName == this.packageName) return

        // 1b. Split screen dangerous-package guard.
        // TYPE_WINDOWS_CHANGED fires whenever the window list changes — including
        // when split screen is entered or an app is added to a split pane.
        // If we see 2+ visible windows and any of them belong to a dangerous
        // package (Play Store, package installer etc.), kill that package's
        // process and go home. We only act in split screen (windows.size >= 2)
        // so single-window Play Store use (e.g. legitimate updates) is unaffected.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val visibleWindows = windows ?: emptyList()
            if (visibleWindows.size >= 2) {
                val dangerousWindow = visibleWindows.firstOrNull { window ->
                    val pkg = window.root?.packageName?.toString() ?: ""
                    isDangerousPackage(pkg)
                }
                if (dangerousWindow != null) {
                    val dangerousPkg = dangerousWindow.root?.packageName?.toString() ?: ""
                    // 1. Exit split screen and go home
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    // 2. Kill the process so it doesn't linger in recents
                    try {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        am.killBackgroundProcesses(dangerousPkg)
                    } catch (_: Exception) {}
                    // 3. Go home again after the kill to ensure clean state
                    serviceScope.launch {
                        delay(300)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }
                    return
                }
            }
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
                val rootNode = rootInActiveWindow ?: event.source
                if (rootNode != null) {
                    if (browserPackages.contains(packageName)) {
                        if (scanForBlockedUrls(rootNode, packageName)) return
                    }
                    val rootPkg = rootNode.packageName?.toString().orEmpty()
                    if (rootPkg != this.packageName &&
                        scanForBlockedContent(rootNode, packageName)) {
                        performBlock(packageName)
                        return
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

    // ── URL scanning ─────────────────────────────────────────────────────────
    private fun scanForBlockedUrls(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ): Boolean {
        val currentUrl = captureBrowserUrl(rootNode, packageName) ?: return false

        // Allowlist mode check — if enabled, block any URL not in the allowlist.
        // This runs before regular block rules so it acts as a global gate.
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

    /**
     * Recursively scans all visible text nodes for:
     * 1. Blocked keywords (existing behaviour).
     * 2. Blocked hostnames — catches reader mode / preview mode / offline cache
     *    where the URL bar doesn't update but the page content is still visible.
     *    Only runs the hostname check when a known browser package is in the
     *    foreground to avoid false positives in other apps.
     */
    private fun scanForBlockedContent(
        node: AccessibilityNodeInfo,
        foregroundPackage: String = ""
    ): Boolean {
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        // Keyword check
        if (!text.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, text)) return true
        if (!desc.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, desc)) return true

        // Hostname check — only in browsers and only when cache is populated
        if (browserPackages.contains(foregroundPackage) &&
            blockedHostsCache.isNotEmpty()) {
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
                if (scanForBlockedContent(child, foregroundPackage)) return true
                child.recycle()
            }
        }
        return false
    }

    /**
     * Central block dispatcher.
     *
     * Soft-blocked apps → SoftBlockActivity (UUID challenge, then access granted).
     * Hard-blocked apps → BlockedAppActivity (full block with emergency override).
     *
     * TemporaryAccessManager is checked first so that a successfully completed
     * soft-block challenge (or emergency override) is always honoured.
     */
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
                        // Soft block: generate a fresh challenge and send it with the intent
                        val challenge = SoftBlockManager.generateChallenge(this@BlockerAccessibilityService, packageName)
                        Intent(this@BlockerAccessibilityService, SoftBlockActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            putExtra("blocked_package", packageName)
                            putExtra("challenge_code", challenge)
                        }
                    } else {
                        // Hard block
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
        serviceScope.cancel()
        isServiceReady = false
        currentlyBlockedApps.clear()
    }
}
