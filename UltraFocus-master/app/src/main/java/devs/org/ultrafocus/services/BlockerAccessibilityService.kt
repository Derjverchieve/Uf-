package devs.org.ultrafocus.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

    // Throttle website blocks
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
                        if (scanForBlockedUrls(rootNode, packageName)) return
                    }
                    val rootPkg = rootNode.packageName?.toString().orEmpty()
                    if (rootPkg != this.packageName && scanForBlockedContent(rootNode)) {
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

    // Matches any URL-like string — used by the tree scan to avoid false
    // positives from plain prose text that happens to mention a domain.
    private val urlLikeRegex = Regex(
        """(?:https?://)?(?:www\.)?([a-zA-Z0-9\-]+(?:\.[a-zA-Z]{2,})+)(?:/\S*)?"""
    )

    private fun scanForBlockedUrls(
        rootNode: AccessibilityNodeInfo,
        packageName: String
    ): Boolean {
        val now = System.currentTimeMillis()

        // ── Primary check: address bar ────────────────────────────────────────
        val currentUrl = captureBrowserUrl(rootNode, packageName)
        if (currentUrl != null) {
            if (WebAllowlistManager.isBlockedByAllowlist(this, currentUrl)) {
                val blockKey = WebAllowlistManager::class.java.simpleName
                if (blockKey == lastBlockedWebsiteKey &&
                    now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true
                lastBlockedWebsiteKey = blockKey
                lastWebsiteBlockTime = now
                tryRedirectBrowserTab(rootNode, packageName)
                performGlobalAction(GLOBAL_ACTION_HOME)
                performRedirectToGoogle()
                return true
            }
            if (WebsiteBlockManager.shouldBlockUrl(this, currentUrl)) {
                val blockKey = WebsiteBlockManager.normalizeHost(currentUrl)
                if (blockKey == lastBlockedWebsiteKey &&
                    now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true
                lastBlockedWebsiteKey = blockKey
                lastWebsiteBlockTime = now
                tryRedirectBrowserTab(rootNode, packageName)
                performGlobalAction(GLOBAL_ACTION_HOME)
                performRedirectToGoogle()
                return true
            }
        }

        // ── Secondary check: full tree scan ───────────────────────────────────
        // Catches browser "preview" / "peek" overlays and Custom Tabs that load
        // page content without updating the address bar. Recursively walks every
        // AccessibilityNodeInfo in the browser window and checks any URL-shaped
        // text against block rules. Uses a regex filter so random prose text that
        // happens to mention a word doesn't produce false positives.
        val treeBlockKey = scanNodeTreeForBlockedUrl(rootNode)
        if (treeBlockKey != null) {
            if (treeBlockKey == lastBlockedWebsiteKey &&
                now - lastWebsiteBlockTime < websiteBlockCooldownMs) return true
            lastBlockedWebsiteKey = treeBlockKey
            lastWebsiteBlockTime = now
            // Cannot rewrite an address bar that isn't visible — just go home.
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            performRedirectToGoogle()
            return true
        }

        return false
    }

    /**
     * Recursively walks the accessibility tree looking for any text that both
     * looks like a URL and matches a block rule. Returns the normalized block key
     * if found, null otherwise.
     *
     * Depth is capped at 20 to avoid stack issues on pathologically deep trees.
     * The urlLikeRegex filter ensures only domain-shaped strings are evaluated —
     * plain prose sentences won't trigger a false positive even if they mention
     * a site name in passing.
     */
    private fun scanNodeTreeForBlockedUrl(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): String? {
        if (depth > 20) return null

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()

        for (candidate in listOf(text, desc)) {
            if (candidate.length < 6) continue
            for (match in urlLikeRegex.findAll(candidate)) {
                val url = match.value
                if (WebAllowlistManager.isBlockedByAllowlist(this, url)) {
                    return WebAllowlistManager::class.java.simpleName
                }
                if (WebsiteBlockManager.shouldBlockUrl(this, url)) {
                    return WebsiteBlockManager.normalizeHost(url)
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = scanNodeTreeForBlockedUrl(child, depth + 1)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
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
                } catch (_: Exception)
