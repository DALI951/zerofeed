package com.zerofeed.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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
    private var feedShieldOverlay: LinearLayout? = null
    private var calloutBannerOverlay: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own overlay / system UI events so we don't prematurely hide the shield
        if (pkg == "com.android.systemui") return

        if (ZeroFeedRuleEngine.isEmergencyAllowedPackage(pkg)) {
            hideFeedShieldOverlay()
            return
        }

        val repo = ZeroFeedRepository(applicationContext)
        val blockedPackages = repo.getBlockedPackages()

        // 1. Completely blocked apps (e.g., TikTok, Snapchat) -> Quit back to Home + show Callout Banner
        if (blockedPackages.contains(pkg)) {
            hideFeedShieldOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
            triggerInAppCallout(repo, "Blocked App ($pkg)")
            return
        }

        val rootNode = rootInActiveWindow ?: return

        // 2. Official Native Instagram App (com.instagram.android):
        //    - Quit Reels & Explore immediately inside the app
        //    - Auto-jump to Direct Messages or cover the Home Feed while keeping top DM button accessible
        if (pkg == "com.instagram.android") {
            handleNativeInstagram(rootNode, repo)
            return
        }

        // 3. Official Native YouTube App (com.google.android.youtube):
        //    - Quit Shorts immediately inside the app
        //    - Hide the Home recommendation feed while keeping the top YouTube Search Bar 100% usable
        if (pkg == "com.google.android.youtube") {
            handleNativeYouTube(rootNode, repo)
            return
        }

        hideFeedShieldOverlay()
    }

    private fun handleNativeInstagram(root: AccessibilityNodeInfo, repo: ZeroFeedRepository) {
        val state = inspectInstagramWindow(root)

        // A. If user opened a Reel or Explore inside Instagram -> Quit immediately via Back action!
        if (state.isReelsOrExplore) {
            hideFeedShieldOverlay()
            performGlobalAction(GLOBAL_ACTION_BACK)
            triggerInAppCallout(repo, "Instagram Reel / Explore")
            return
        }

        // B. If user is inside Direct Messages inbox or a DM conversation -> Allow 100% uncovered!
        if (state.isInDirectMessages) {
            hideFeedShieldOverlay()
            return
        }

        // C. If user is on the Instagram Main Home Feed:
        //    First try to auto-click the Direct Messages icon so Instagram opens straight to DMs!
        val now = System.currentTimeMillis()
        if (state.dmButtonNode != null && now - lastAutoClickDmEpochMs > 2500L) {
            lastAutoClickDmEpochMs = now
            hideFeedShieldOverlay()
            state.dmButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        //    Otherwise, cover the Instagram Home Feed posts in-app while leaving the top bar (DM icon) uncovered!
        if (state.isHomeFeed) {
            showFeedShieldOverlay(
                title = "INSTAGRAM HOME FEED HIDDEN BY ZEROFEED",
                subtitle = "Tap the Direct Messages icon at the top-right to chat.\nReels, Stories & Feed scrolling are disabled.\nGo read 25 pages of your book or solve 3 coding problems!",
                actionText = "Jump to Instagram Direct Messages",
                onActionClick = {
                    val freshRoot = rootInActiveWindow
                    val dmNode = freshRoot?.let { inspectInstagramWindow(it).dmButtonNode }
                    if (dmNode != null) {
                        hideFeedShieldOverlay()
                        dmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        hideFeedShieldOverlay()
                        openSeamlessWebMode("IG_DIRECT")
                    }
                }
            )
        } else {
            hideFeedShieldOverlay()
        }
    }

    private fun handleNativeYouTube(root: AccessibilityNodeInfo, repo: ZeroFeedRepository) {
        val state = inspectYouTubeWindow(root)

        // A. If user opened a YouTube Short inside the native YouTube app -> Quit immediately via Back action!
        if (state.isShortsPlayer) {
            hideFeedShieldOverlay()
            performGlobalAction(GLOBAL_ACTION_BACK)
            triggerInAppCallout(repo, "YouTube Short")
            return
        }

        // B. If user is searching, viewing search results, or watching a normal long-form video -> Allow uncovered!
        if (state.isSearchingOrWatchingVideo) {
            hideFeedShieldOverlay()
            return
        }

        // C. If user is sitting on the YouTube Home recommendation feed ->
        //    Cover the Home feed while leaving the top YouTube Search Bar 100% visible & clickable!
        if (state.isHomeFeedTab) {
            showFeedShieldOverlay(
                title = "YOUTUBE HOME FEED & SHORTS HIDDEN",
                subtitle = "Use the real YouTube Search Bar at the top of the screen to search for any lecture or educational video.\nAlgorithmic Home recommendations & Shorts are blocked.",
                actionText = "Tap Search Bar at Top (or Click Here to Search)",
                onActionClick = {
                    val freshRoot = rootInActiveWindow
                    val searchNode = freshRoot?.let { inspectYouTubeWindow(it).searchButtonNode }
                    if (searchNode != null) {
                        hideFeedShieldOverlay()
                        searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        hideFeedShieldOverlay()
                    }
                }
            )
        } else {
            hideFeedShieldOverlay()
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
     * Shows an in-app Accessibility Overlay covering ONLY the middle feed region (leaving the top Search/DM bar
     * and bottom navigation bar accessible) so the user stays inside the real YouTube/Instagram app.
     */
    private fun showFeedShieldOverlay(
        title: String,
        subtitle: String,
        actionText: String,
        onActionClick: () -> Unit
    ) {
        mainHandler.post {
            val wm = windowManager ?: return@post
            if (feedShieldOverlay != null) return@post

            val density = resources.displayMetrics.density
            val topBarOffsetPx = (72 * density).toInt()     // Leaves top Search Bar / DM icon uncovered!
            val bottomBarOffsetPx = (68 * density).toInt()  // Leaves bottom navigation bar uncovered!
            val screenHeight = resources.displayMetrics.heightPixels
            val overlayHeight = (screenHeight - topBarOffsetPx - bottomBarOffsetPx).coerceAtLeast(400)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0F0F0F"))
                setPadding((24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
            }

            val badgeView = TextView(this).apply {
                text = "ZEROFEED IN-APP SHIELD (STREAK INTACT)"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 11f
                gravity = Gravity.CENTER
            }
            val titleView = TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            }
            val subView = TextView(this).apply {
                text = subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (18 * density).toInt())
            }
            val actionBtn = Button(this).apply {
                text = actionText
                setBackgroundColor(Color.parseColor("#38BDF8"))
                setTextColor(Color.parseColor("#080B11"))
                setOnClickListener { onActionClick() }
            }

            container.addView(badgeView)
            container.addView(titleView)
            container.addView(subView)
            container.addView(actionBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = topBarOffsetPx
            }

            try {
                wm.addView(container, params)
                feedShieldOverlay = container
            } catch (_: Exception) {
                // Ignore if overlay cannot be attached during window transition
            }
        }
    }

    private fun hideFeedShieldOverlay() {
        mainHandler.post {
            val wm = windowManager ?: return@post
            feedShieldOverlay?.let {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {}
            }
            feedShieldOverlay = null
        }
    }

    /**
     * Triggers an immediate in-app Hard-Truth Callout Banner when a Reel or Short is quit,
     * shaming the impulse and reminding the user to read a book & solve coding problems WITHOUT resetting their streak.
     */
    private fun triggerInAppCallout(repo: ZeroFeedRepository, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastInterceptEpochMs < 1800L) return
        lastInterceptEpochMs = now

        // Protects streakDays (does not reset to 0) while recording the deflected impulse
        repo.recordSlip(source)

        mainHandler.post {
            val wm = windowManager ?: return@post
            calloutBannerOverlay?.let {
                try { wm.removeView(it) } catch (_: Exception) {}
            }

            val density = resources.displayMetrics.density
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#2A0A10"))
                setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt())
            }

            val header = TextView(this).apply {
                text = "STOP! $source QUIT BY ZEROFEED (Streak Intact: ${repo.getStreakDays()}d)"
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
            }
            val body = TextView(this).apply {
                text = "You just clicked a short-form dopamine trap. Feel the weight of wasted potential—close this, read 25 pages of your book, and solve 3 coding problems!"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                setPadding(0, (6 * density).toInt(), 0, (10 * density).toInt())
            }
            val dismissBtn = Button(this).apply {
                text = "Got It — Back to Focus"
                setBackgroundColor(Color.parseColor("#EF4444"))
                setTextColor(Color.WHITE)
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
                gravity = Gravity.TOP
                y = (48 * density).toInt()
            }

            try {
                wm.addView(banner, params)
                calloutBannerOverlay = banner
                mainHandler.postDelayed({
                    if (calloutBannerOverlay === banner) {
                        try { wm.removeView(banner) } catch (_: Exception) {}
                        calloutBannerOverlay = null
                    }
                }, 6500L)
            } catch (_: Exception) {}
        }
    }

    private fun openSeamlessWebMode(mode: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_OPEN_SEAMLESS_MODE", mode)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        hideFeedShieldOverlay()
    }
}
