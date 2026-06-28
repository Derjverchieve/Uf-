package devs.org.ultrafocus.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import devs.org.ultrafocus.activities.BlockedAppActivity
import devs.org.ultrafocus.activities.SoftBlockActivity
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.repository.AppRepository
import devs.org.ultrafocus.utils.ContentBlockManager
import devs.org.ultrafocus.utils.KioskOverlayManager
import devs.org.ultrafocus.utils.KioskPrefs
import devs.org.ultrafocus.model.SessionPhase
import devs.org.ultrafocus.utils.SoftBlockManager
import devs.org.ultrafocus.utils.SpecificScreenManager
import devs.org.ultrafocus.utils.TemporaryAccessManager
import devs.org.ultrafocus.utils.WebAllowlistManager
import devs.org.ultrafocus.utils.WebBlockMode
import devs.org.ultrafocus.utils.WebsiteBlockManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Calendar

// Window types that are never meaningfully "a different app" for deep-work
// tracking purposes, regardless of which package happens to own them —
// keyboards, accessibility overlays (including this app's own block screen),
// the split-screen divider, and the magnifier. Deliberately excludes
// TYPE_SYSTEM (notification shade) — see DeepWorkSessionManager for why.
private val DEEP_WORK_EXEMPT_WINDOW_TYPES = setOf(
    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY
)

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
        "com.android.chrome:id/tab_modal"
        // open_new_tab_chip intentionally removed: it matches the "new tab" button and
        // caused the preview blocker to fire every time the user opened a new tab.
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

    // Packages exempt from keyword scanning
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
    private lateinit var kioskOverlayManager: KioskOverlayManager

    /**
     * Detects volume changes made via the quick-settings slider (or any other method
     * that changes the actual stream volume — physical buttons, assistant, etc.).
     * Shows the kiosk quick-switch tile whenever the music stream volume changes.
     * Registered in onServiceConnected, unregistered in onDestroy.
     */
    private val volumeObserver by lazy {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (current != lastVolume && KioskPrefs.isKioskEnabled(this@BlockerAccessibilityService)) {
                    lastVolume = current
                    val sessionPkg = devs.org.ultrafocus.utils.DeepWorkSessionManager
                        .state.value.primaryAppPackage
                    kioskOverlayManager.show(alwaysIncludePkg = sessionPkg)
                }
            }
        }
    }

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

        // Independent ground-truth check for the deep work tracker — see
        // DeepWorkSessionManager.groundTruthProvider for why this exists.
        devs.org.ultrafocus.utils.DeepWorkSessionManager.groundTruthProvider = {
            try {
                (windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused })
                    ?.root?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }

        kioskOverlayManager = KioskOverlayManager(this)

        // Observe actual music-stream volume changes so the quick-switch tile appears
        // when the user adjusts volume via quick settings, assistant, or any method
        // other than physical keys (which are handled by onKeyEvent as a fallback).
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )

        // Persistent corner timer: shows lock icon + countdown whenever kiosk
        // and an active session are both running at the same time.
        serviceScope.launch {
            devs.org.ultrafocus.utils.DeepWorkSessionManager.state.collectLatest { state ->
                if (KioskPrefs.isKioskEnabled(this@BlockerAccessibilityService) &&
                    state.phase != SessionPhase.IDLE) {
                    kioskOverlayManager.showPersistentTimer()
                } else {
                    kioskOverlayManager.hidePersistentTimer()
                }
            }
        }
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
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
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

        // ── Deep Work session tracking (additive) ───────────────────────
        // Reports real foreground-app changes to the session manager for
        // auto-pause/auto-resume. Deliberately runs before the self-package
        // early-return below and is fully guarded so it can never affect
        // the existing blocking behaviour.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                val windowType = try {
                    windows.firstOrNull { it.id == event.windowId }?.type
                } catch (_: Exception) {
                    null
                }
                val isExemptWindowType = windowType != null && windowType in DEEP_WORK_EXEMPT_WINDOW_TYPES
                devs.org.ultrafocus.utils.DeepWorkSessionManager.onForegroundAppChanged(
                    packageName, className, isExemptWindowType
                )
            } catch (_: Exception) {}
        }

        if (packageName == this.packageName) return

        // ── Kiosk mode: block any app not on the allowed list immediately ─────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            KioskPrefs.isKioskEnabled(this)) {
            // Exempt system-level packages. Without this, the volume panel, IME,
            // permission dialogs, clipboard toasts, and any other system overlay all
            // fire TYPE_WINDOW_STATE_CHANGED with a package not in the allowed list
            // and get blocked immediately — making kiosk completely unusable.
            val windowType = try {
                windows.firstOrNull { it.id == event.windowId }?.type
            } catch (_: Exception) { null }
            val isSystemOverlay = packageName == "android" ||
                packageName in contentScanExemptPackages ||
                (windowType != null && windowType in DEEP_WORK_EXEMPT_WINDOW_TYPES)

            if (!isSystemOverlay) {
                val sessionPkg = devs.org.ultrafocus.utils.DeepWorkSessionManager
                    .state.value.primaryAppPackage
                val allowed = KioskPrefs.getAllowedPackages(this) + setOfNotNull(sessionPkg)
                if (packageName !in allowed) {
                    performBlock(packageName)
                    return
                }
            }
            // System overlay or allowed app — falls through to normal content blocking
        }
        if (browserPackages.contains(packageName) &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {

            // Guard 1: active-window package — AnkiDroid's embedded WebView fires
            // TYPE_VIEW_CLICKED as "com.android.chrome" even when Anki is foreground.
            val activeRoot = rootInActiveWindow
            if (activeRoot == null || activeRoot.packageName?.toString() != packageName) return

            // Guard 2: ground-truth — catches the bottom-of-button edge case where
            // AnkiDroid's WebView sub-window briefly surfaces as the active window,
            // making rootInActiveWindow look like Chrome even though Anki is foreground.
            val groundTruth = devs.org.ultrafocus.utils.DeepWorkSessionManager
                .groundTruthProvider?.invoke()
            if (groundTruth != null && groundTruth != packageName) return

            // Guard 3: new-tab action — any "new tab" or "add tab" button must never
            // trigger preview blocking, even when the previous tab was a blocked site.
            val clickedId = try { event.source?.viewIdResourceName } catch (_: Exception) { null }
            if (clickedId != null && (
                clickedId.contains("new_tab", ignoreCase = true) ||
                clickedId.contains("add_tab", ignoreCase = true) ||
                clickedId.contains("open_new_tab", ignoreCase = true))) return

            // Immediate text check
            if (findPreviewTriggerFromNode(activeRoot)) {
                closePreviewAndExit(packageName)
                return
            }

            // Delayed scan — longer delay so new-tab transitions fully settle
            previewClickJob?.cancel()
            previewClickJob = serviceScope.launch {
                delay(400)
                val delayedRoot = rootInActiveWindow ?: return@launch
                if (delayedRoot.packageName?.toString() != packageName) return@launch

                // Ground truth at scan time — second chance to catch false events
                val delayedTruth = devs.org.ultrafocus.utils.DeepWorkSessionManager
                    .groundTruthProvider?.invoke()
                if (delayedTruth != null && delayedTruth != packageName) return@launch

                if (!isChromePreview(delayedRoot, packageName)) return@launch

                // New-tab / internal-page URL guard
                val url = captureBrowserUrl(delayedRoot, packageName)
                val isInternalPage = url.isNullOrBlank() ||
                    url.equals("Search or type URL", ignoreCase = true) ||
                    url.startsWith("chrome://", ignoreCase = true) ||
                    url.startsWith("about:", ignoreCase = true)
                if (isInternalPage) return@launch

                if (scanForBlockedUrls(delayedRoot, packageName) ||
                    isAnyBlockedHostCurrentlyBlockable(delayedRoot)) {
                    closePreviewAndExit(packageName)
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

                // Split‑screen guard
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
                    // URL check (normal pages)
                    if (scanForBlockedUrls(rootNode, packageName)) return

                    val inPreview = isChromePreview(rootNode, packageName)

                    // For previews: schedule‑aware hostname check
                    if (inPreview && isAnyBlockedHostCurrentlyBlockable(rootNode)) {
                        closePreviewAndExit(packageName)
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

    // ── Schedule‑aware hostname checker for previews ──────────────────────
    /**
     * Recursively scans visible text nodes for blocked hostnames.
     * For each host found, it checks [WebsiteBlockManager.shouldBlockUrl] with a
     * synthetic URL. Only returns true if at least one host is currently blockable
     * (i.e. within its schedule and not temporarily allowed).
     */
    private fun isAnyBlockedHostCurrentlyBlockable(rootNode: AccessibilityNodeInfo): Boolean {
        if (blockedHostsCache.isEmpty()) return false
        return isAnyBlockedHostRecursive(rootNode, HashSet())
    }

    private fun isAnyBlockedHostRecursive(node: AccessibilityNodeInfo, visited: MutableSet<Int>): Boolean {
        if (!node.isVisibleToUser) return false
        val key = System.identityHashCode(node)
        if (!visited.add(key)) return false

        val combined = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString()
        ).joinToString(" ").lowercase()

        if (combined.isNotBlank()) {
            for (host in blockedHostsCache) {
                if (combined.contains(host) &&
                    WebsiteBlockManager.shouldBlockUrl(this, "https://$host")) {
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isAnyBlockedHostRecursive(child, visited)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
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

    private fun containsPreviewTrigger(text: String): Boolean {
        val norm = text.lowercase()
        // Deliberately narrow: only match explicit preview-page labels.
        // The old bare norm.contains("preview") caused new-tab pages and any
        // element with the word "preview" (e.g. image previews) to fire the blocker.
        return norm.contains("preview page") || norm.contains("open preview")
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

    /**
     * Physical volume key fallback — shows the kiosk tile when hardware buttons work.
     * The ContentObserver (volumeObserver) handles quick-settings slider changes.
     * Always returns false so the volume still changes.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            KioskPrefs.isKioskEnabled(this) &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
             event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val sessionPkg = devs.org.ultrafocus.utils.DeepWorkSessionManager
                .state.value.primaryAppPackage
            kioskOverlayManager.show(alwaysIncludePkg = sessionPkg)
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        try { contentResolver.unregisterContentObserver(volumeObserver) } catch (_: Exception) {}
        previewClickJob?.cancel()
        serviceScope.cancel()
        isServiceReady = false
        currentlyBlockedApps.clear()
        if (::kioskOverlayManager.isInitialized) {
            kioskOverlayManager.hidePersistentTimer()
            kioskOverlayManager.destroy()
        }
        devs.org.ultrafocus.utils.DeepWorkSessionManager.groundTruthProvider = null
    }
}
