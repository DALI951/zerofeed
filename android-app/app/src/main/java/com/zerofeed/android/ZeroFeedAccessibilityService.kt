package com.zerofeed.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class ZeroFeedAccessibilityService : AccessibilityService() {

    private var lastInterceptEpochMs: Long = 0L
    private var lastAutoClickDmEpochMs: Long = 0L
    private var windowManager: WindowManager? = null
    private var calloutBannerOverlay: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own overlay or system UI events
        if (pkg == "com.android.systemui" || pkg == "com.zerofeed.android") return

        if (ZeroFeedRuleEngine.isEmergencyAllowedPackage(pkg)) {
            return
        }

        val repo = ZeroFeedRepository(applicationContext)
        val blockedPackages = repo.getBlockedPackages()

        // 1. Completely blocked apps (e.g., TikTok) -> Quit back to Home + show Callout Banner
        if (blockedPackages.contains(pkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            triggerInAppCallout(repo, "Blocked App ($pkg)")
            return
        }

        val rootNode = rootInActiveWindow ?: return

        // 2. Official Native Instagram App (com.instagram.android):
        //    - Quit Reels & Explore immediately inside the app
        //    - Auto-jump to Direct Messages when opening Home Feed
        if (pkg == "com.instagram.android") {
            handleNativeInstagram(rootNode, repo)
            return
        }

        // 3. Official Native YouTube App (com.google.android.youtube):
        //    - Quit Shorts immediately inside the app cleanly (no glitching overlay!)
        if (pkg == "com.google.android.youtube") {
            handleNativeYouTube(rootNode, repo)
            return
        }
    }

    private fun handleNativeInstagram(root: AccessibilityNodeInfo, repo: ZeroFeedRepository) {
        val state = inspectInstagramWindow(root)

        // A. If user opened a Reel or Explore inside Instagram -> Quit immediately via Back action!
        if (state.isReelsOrExplore) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            triggerInAppCallout(repo, "Instagram Reel / Explore")
            return
        }

        // B. If user is inside Direct Messages inbox or a DM conversation -> Allow cleanly!
        if (state.isInDirectMessages) {
            return
        }

        // C. If user is on the Instagram Main Home Feed:
        //    Auto-click the Direct Messages icon so Instagram jumps straight to DMs without any glitchy overlay!
        val now = System.currentTimeMillis()
        val dmBtn = state.dmButtonNode
        if (dmBtn != null && now - lastAutoClickDmEpochMs > 3000L) {
            lastAutoClickDmEpochMs = now
            dmBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun handleNativeYouTube(root: AccessibilityNodeInfo, repo: ZeroFeedRepository) {
        val state = inspectYouTubeWindow(root)

        // If user opened a YouTube Short inside the native YouTube app -> Quit immediately via Back action!
        // No glitching overlay over the home feed!
        if (state.isShortsPlayer) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            triggerInAppCallout(repo, "YouTube Short")
        }
    }

    private data class IgWindowState(
        var isReelsOrExplore: Boolean = false,
        var isInDirectMessages: Boolean = false,
        var isHomeFeed: Boolean = false,
        var dmButtonNode: AccessibilityNodeInfo? = null
    )

    private fun inspectInstagramWindow(root: AccessibilityNodeInfo): IgWindowState {
        val state = IgWindowState()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val id = (node.viewIdResourceName ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val text = (node.text?.toString() ?: "").lowercase()

            // Detect Direct Messages screen
            if (id.contains("direct_thread") ||
                id.contains("thread_list") ||
                id.contains("row_inbox") ||
                id.contains("direct_inbox") ||
                text == "messages" && id.contains("title")
            ) {
                state.isInDirectMessages = true
            }

            // Detect top-right Direct Messages button on Home Feed
            if (id.contains("action_bar_inbox_button") ||
                desc == "messenger" ||
                desc == "direct" ||
                desc.contains("unread message") ||
                desc == "messages"
            ) {
                if (node.isClickable) {
                    state.dmButtonNode = node
                } else if (node.parent?.isClickable == true) {
                    state.dmButtonNode = node.parent
                }
            }

            // Detect Reels or Explore
            if (id.contains("clips_viewer") ||
                id.contains("reel_viewer") ||
                id.contains("explore_grid") ||
                (node.isSelected && (desc == "reels" || desc == "search and explore"))
            ) {
                state.isReelsOrExplore = true
            }

            // Detect Home Feed
            if (id.contains("feed_view_pager") ||
                id.contains("main_feed") ||
                id.contains("row_feed_photo") ||
                (node.isSelected && desc == "home")
            ) {
                state.isHomeFeed = true
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        walk(root)
        return state
    }

    private data class YtWindowState(
        var isShortsPlayer: Boolean = false,
        var isSearchingOrWatchingVideo: Boolean = false,
        var isHomeFeedTab: Boolean = false,
        var searchButtonNode: AccessibilityNodeInfo? = null
    )

    private fun inspectYouTubeWindow(root: AccessibilityNodeInfo): YtWindowState {
        val state = YtWindowState()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val id = (node.viewIdResourceName ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val cls = (node.className?.toString() ?: "").lowercase()

            // Detect Shorts player or selected Shorts tab
            if (id.contains("reel_player_page_container") ||
                id.contains("reel_recycler") ||
                id.contains("reel_watch_fragment_root") ||
                id.contains("shorts_player") ||
                (node.isSelected && desc == "shorts")
            ) {
                state.isShortsPlayer = true
            }

            // Detect active search input, search results, or active video player
            if (id.contains("search_edit_text") ||
                id.contains("search_type_icon") ||
                id.contains("watch_while_time_bar") ||
                id.contains("player_view") ||
                (cls.contains("edittext") && node.isFocused)
            ) {
                state.isSearchingOrWatchingVideo = true
            }

            // Detect top YouTube Search icon/button
            if (id.contains("menu_item_search") || desc == "search" || desc == "search youtube") {
                if (node.isClickable) {
                    state.searchButtonNode = node
                } else if (node.parent?.isClickable == true) {
                    state.searchButtonNode = node.parent
                }
            }

            // Detect selected Home tab
            if (node.isSelected && desc == "home") {
                state.isHomeFeedTab = true
            }

            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }
        walk(root)
        return state
    }

    /**
     * Shows a smooth floating pill callout when a Reel or Short is quit,
     * shaming the impulse and reminding the user to read a book & solve coding problems WITHOUT resetting their streak.
     */
    private fun triggerInAppCallout(repo: ZeroFeedRepository, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastInterceptEpochMs < 3500L) return
        lastInterceptEpochMs = now

        // Protects streakDays (does not reset to 0) while recording the deflected impulse
        repo.recordSlip(source)

        mainHandler.post {
            val wm = windowManager ?: return@post
            calloutBannerOverlay?.let {
                try { wm.removeView(it) } catch (_: Exception) {}
            }

            val density = resources.displayMetrics.density

            // Smooth rounded pill background
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#1C0A0E"))
                cornerRadius = 24 * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#EF4444"))
            }

            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape
                setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
                elevation = 16 * density
            }

            val header = TextView(this).apply {
                text = "STOP! $source QUIT (Streak Intact: ${repo.getStreakDays()}d)"
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13.5f
                paint.isFakeBoldText = true
            }
            val body = TextView(this).apply {
                text = "Short-form loops wreck your focus. Pick up your book and read 25 pages, or go solve 3 coding problems!"
                setTextColor(Color.WHITE)
                textSize = 12.5f
                setPadding(0, (5 * density).toInt(), 0, (8 * density).toInt())
            }

            val buttonShape = GradientDrawable().apply {
                setColor(Color.parseColor("#EF4444"))
                cornerRadius = 16 * density
            }

            val dismissBtn = Button(this).apply {
                text = "Close & Focus"
                background = buttonShape
                setTextColor(Color.WHITE)
                textSize = 12f
                isAllCaps = false
                paint.isFakeBoldText = true
                setOnClickListener {
                    try { wm.removeView(banner) } catch (_: Exception) {}
                    calloutBannerOverlay = null
                }
            }

            banner.addView(header)
            banner.addView(body)
            banner.addView(dismissBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (60 * density).toInt()
                width = (resources.displayMetrics.widthPixels * 0.94f).toInt()
            }

            try {
                wm.addView(banner, params)
                calloutBannerOverlay = banner
                mainHandler.postDelayed({
                    if (calloutBannerOverlay === banner) {
                        try { wm.removeView(banner) } catch (_: Exception) {}
                        calloutBannerOverlay = null
                    }
                }, 4500L)
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        calloutBannerOverlay?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        calloutBannerOverlay = null
    }
}
