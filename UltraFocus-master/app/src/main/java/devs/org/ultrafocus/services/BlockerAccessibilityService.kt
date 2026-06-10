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

    /**
     * Packages that are NEVER subject to keyword or hostname content scanning.
     * These are system-level packages where a false positive would break core
     * device functionality (can't use keyboard, home screen, notifications etc.)
     *
     * Note: these packages CAN still be added to the explicit blocked apps list
     * by the user — the exemption only applies to content scanning.
     */
    private val contentScanExemptPackages = setOf(
        "com.android.systemui",                      // Notification shade, status bar
        "com.android.launcher3",                     // Stock launcher
        "com.google.android.apps.nexuslauncher",     // Pixel launcher
        "com.transsion.xlauncher",                   // Transsion/HiOS launcher
        "com.hihonor.android.launcher",              // Honor launcher
        "com.miui.home",                             // MIUI launcher
        "com.sec.android.app.launcher",              // Samsung launcher
        "com.android.inputmethod.latin",             // AOSP keyboard
        "com.google.android.inputmethod.latin",      // Gboard
        "com.samsung.android.honeyboard",            // Samsung keyboard
        "com.swiftkey.swiftkeyapp",                  // SwiftKey
        "com.transsion.inputmethod"                  // Transsion keyboard
    )

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

    // Browser hostname scans are only allowed for a brief moment after a
    // genuine browser click. This prevents passive OCR/search-result text from
    // triggering a block while still catching an intentionally opened preview.
    private var lastBrowserClickTime: Long = 0L
    private val browserClickArmWindowMs = 1500L

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

    /**
     * Returns true only when the browser is showing an actual web page —
     * i.e., the address bar view exists in the accessibility tree.
     *
     * When false, we're in a browser-internal UI screen (tab switcher, tab
     * groups, new tab page, downloads, history, bookmarks, settings) where
     * blocked domain names appearing as tab titles or history entries must NOT
     * trigger a block — doing so was causing the tab groups false positive.
     */
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

        // Track user-initiated browser clicks. We only permit hostname text scans
        // in browsers shortly after a click, which prevents Google results,
        // tab switchers, OCR, and other passive surfaces from triggering blocks.
        if (browserPackages.contains(packageName) &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastBrowserClickTime = System.currentTimeMillis()
        }

        // 1b. Split screen dangerous-package guard — checked inside the throttled
        // scan loop below so it runs every 50ms rather than relying on
        // TYPE_WINDOWS_CHANGED alone (unreliable on some OEMs).

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
                // Check the live window list every scan tick. Only acts when
                // 2+ windows are visible (split screen / multi-window) so
                // single-window Play Store use is completely unaffected.
                val visibleWindows = windows
                // True split screen is indicated by the presence of a
                // TYPE_SPLIT_SCREEN_DIVIDER window. Checking windows.size >= 2
                // alone is wrong — overlay dialogs (like the uninstall dialog)
                // also produce 2+ windows without being split screen.
                val isRealSplitScreen = !visibleWindows.isNullOrEmpty() &&
                    visibleWindows.any { window ->
                        window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                    }

                if (isRealSplitScreen) {
                    val dangerousPkg = visibleWindows!!
                        .mapNotNull { it.root?.packageName?.toString() }
                        .firstOrNull { isDangerousPackage(it) }
                    if (dangerousPkg != null) {
                        // Step 1: back + home to collapse split screen
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        // Step 2: kill the process so it doesn't linger in recents
                        try {
                            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.killBackgroundProcesses(dangerousPkg)
                        } catch (_: Exception) {}
                        // Step 3: recents → home — reliable OEM-agnostic split screen collapse
                        serviceScope.launch {
                            delay(200)
                            performGlobalAction(GLOBAL_ACTION_RECENTS)
                            delay(200)
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        return
                    }
                }
                // ─────────────────────────────────────────────────────────────
                val rootNode = rootInActiveWindow ?: event.source
                if (rootNode != null) {
                    val rootPkg = rootNode.packageName?.toString().orEmpty()
                    if (rootPkg == this.packageName) return

                    if (browserPackages.contains(packageName)) {
                        // ── Browser scan ──────────────────────────────────────
                        // URL check first — handles normal browsing.
                        if (scanForBlockedUrls(rootNode, packageName)) return

                        // Hostname content check is intentionally gated behind a
                        // very short post-click window. This prevents passive
                        // OCR/search-result/tab-title matches from firing a block,
                        // while still allowing an intentionally opened preview or
                        // clicked result to be caught even if the browser does not
                        // visibly update the address bar.
                        val inPageView = isBrowserInPageView(rootNode, packageName)
                        val browserClickArmed =
                            System.currentTimeMillis() - lastBrowserClickTime <= browserClickArmWindowMs

                        if (inPageView &&
                            browserClickArmed &&
                            scanForBlockedContent(rootNode, packageName, hostnameCheck = true)) {
                            performBlock(packageName)
                            return
                        }
                    } else {
                        // ── Non-browser content / keyword scan ────────────────
                        // Exempt system packages (launcher, keyboard, systemui,
                        // notification shade) — a false keyword match there would
                        // block core device functionality.
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
    private fun containsHostAsToken(text: String, host: String): Boolean {
        val escaped = Regex.escape(host.lowercase())
        return Regex("(?i)(?<![a-z0-9-])$escaped(?![a-z0-9-])").containsMatchIn(text)
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

    /**
     * Recursively scans visible text nodes for blocked keywords and, optionally,
     * blocked hostnames.
     *
     * hostnameCheck is only true when called for a browser that is confirmed to
     * be showing an actual web page (address bar exists). This prevents tab
     * groups, history, search results, and other browser UI from triggering
     * false positives, while still catching reader mode / preview mode where
     * a blocked domain's content is rendered without the URL bar updating.
     *
     * For non-browser packages, hostnameCheck is always false — keyword blocking
     * is intentional for any app, but hostname matching outside a browser context
     * would produce constant false positives.
     */
    private fun scanForBlockedContent(
        node: AccessibilityNodeInfo,
        foregroundPackage: String = "",
        hostnameCheck: Boolean = false
    ): Boolean {
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        // Keyword check — runs for all non-exempt packages
        if (!text.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, text)) return true
        if (!desc.isNullOrEmpty() &&
            ContentBlockManager.containsBlockedContent(this, desc)) return true

        // Hostname check — only in click-armed browser contexts
        if (hostnameCheck && blockedHostsCache.isNotEmpty()) {
            val combined = listOfNotNull(text, desc).joinToString(" ").lowercase()
            if (combined.isNotBlank()) {
                for (host in blockedHostsCache) {
                    if (containsHostAsToken(combined, host)) return true
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
