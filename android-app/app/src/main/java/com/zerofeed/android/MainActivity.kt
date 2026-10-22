package com.zerofeed.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private var interceptSourceState by mutableStateOf<String?>(null)
    private var interceptViolationState by mutableStateOf("SHORT_OR_REEL")
    private var requestedTabState by mutableIntStateOf(0)
    private var resumeTriggerTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val context = LocalContext.current
            // Follow the phone's wallpaper / system colors using Material You Dynamic Color on Android 12+ (One UI)
            val dynamicColors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    onPrimary = Color(0xFF003549),
                    primaryContainer = Color(0xFF004D68),
                    onPrimaryContainer = Color(0xFFBEE9FF),
                    secondary = Color(0xFF22C55E),
                    background = Color(0xFF0B0F17),
                    surface = Color(0xFF131B28),
                    surfaceVariant = Color(0xFF1E2838),
                    onSurface = Color(0xFFE2E8F0),
                    onSurfaceVariant = Color(0xFF94A3B8)
                )
            }

            MaterialTheme(
                colorScheme = dynamicColors,
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(22.dp),
                    large = RoundedCornerShape(26.dp),
                    extraLarge = RoundedCornerShape(32.dp)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZeroFeedAndroidApp(
                        initialTab = requestedTabState,
                        resumeTick = resumeTriggerTick,
                        interceptSource = interceptSourceState,
                        interceptViolationType = interceptViolationState,
                        onDismissIntercept = { interceptSourceState = null },
                        onTriggerIntercept = { source, vType ->
                            ZeroFeedRepository(this).recordSlip(source)
                            interceptSourceState = source
                            interceptViolationState = vType
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTriggerTick += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val src = intent?.getStringExtra("EXTRA_INTERCEPT_SOURCE")
        if (!src.isNullOrBlank()) {
            interceptSourceState = src
            interceptViolationState = intent.getStringExtra("EXTRA_VIOLATION_TYPE") ?: "SHORT_OR_REEL"
        }
        val mode = intent?.getStringExtra("EXTRA_OPEN_SEAMLESS_MODE")
        if (mode == "IG_DIRECT") {
            requestedTabState = 1
        } else if (mode == "YT_CLEAN") {
            requestedTabState = 2
        }
    }
}

@Composable
fun ZeroFeedAndroidApp(
    initialTab: Int,
    resumeTick: Int,
    interceptSource: String?,
    interceptViolationType: String,
    onDismissIntercept: () -> Unit,
    onTriggerIntercept: (String, String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ZeroFeedRepository(context) }

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var streakDays by remember { mutableIntStateOf(repo.getStreakDays()) }
    var todayDeflected by remember { mutableIntStateOf(repo.getTodaySlips()) }
    var totalDeflected by remember { mutableIntStateOf(repo.getTotalSlips()) }
    var plans by remember { mutableStateOf(repo.getPlans()) }
    var adviceOffset by remember { mutableIntStateOf(0) }
    var strictLocked by remember { mutableStateOf(repo.isStrictLocked()) }

    var accessibilityEnabled by remember { mutableStateOf(PermissionHelper.isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(PermissionHelper.canDrawOverlays(context)) }
    var showPermissionDialog by remember { mutableStateOf(!accessibilityEnabled || !overlayGranted) }

    var updateInfo by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var updateStatusText by remember { mutableStateOf("Checking GitHub Releases for updates...") }
    var downloadProgress by remember { mutableIntStateOf(-1) }

    fun syncState() {
        streakDays = repo.getStreakDays()
        todayDeflected = repo.getTodaySlips()
        totalDeflected = repo.getTotalSlips()
        plans = repo.getPlans()
        strictLocked = repo.isStrictLocked()
        accessibilityEnabled = PermissionHelper.isAccessibilityServiceEnabled(context)
        overlayGranted = PermissionHelper.canDrawOverlays(context)
    }

    fun checkForGithubUpdates() {
        updateStatusText = "Checking GitHub Releases..."
        ZeroFeedAutoUpdater.checkLatestRelease(context) { result ->
            result.onSuccess { info ->
                updateInfo = info
                updateStatusText = if (info.isNewerAvailable) {
                    "NEW UPDATE AVAILABLE: ${info.latestTag} (Installed: v${info.currentVersion})"
                } else {
                    "Up to date! Installed: v${info.currentVersion} (Latest Release: ${info.latestTag})"
                }
            }.onFailure { err ->
                updateStatusText = "Update check note: ${err.message}"
            }
        }
    }

    LaunchedEffect(resumeTick, interceptSource) {
        syncState()
        if (!accessibilityEnabled || !overlayGranted) {
            showPermissionDialog = true
        }
    }

    LaunchedEffect(Unit) {
        checkForGithubUpdates()
    }

    val dayOfYear = remember { Calendar.getInstance().get(Calendar.DAY_OF_YEAR) }

    // Automatic Startup Permission Prompt
    if (showPermissionDialog && (!accessibilityEnabled || !overlayGranted)) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = {
                Text(
                    "Permissions for In-App Reel/Short Quitting",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Grant these permissions so ZEROFEED can quit Reels & Shorts in your official apps and preserve your focus:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Samsung One UI Tip: If Accessibility is grayed out, tap Step 1 first → tap ⋮ (top-right) → 'Allow restricted settings'.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Button(
                        onClick = { PermissionHelper.openAppDetailsSettings(context) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. Open App Info (Allow Restricted Settings)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { PermissionHelper.openOverlayPermissionSettings(context) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (overlayGranted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (overlayGranted) "✓ 2. Appear on Top (Granted)" else "2. Grant 'Appear on Top'",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = { PermissionHelper.openAccessibilitySettings(context) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (accessibilityEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (accessibilityEnabled) "✓ 3. Accessibility Guard (Enabled)" else "3. Turn On Accessibility Guard",
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Continue to App", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Hard-Truth Callout Screen
    if (interceptSource != null) {
        val callout = ZeroFeedRuleEngine.buildHardTruthCallout(interceptSource, interceptViolationType, dayOfYear)
        HardTruthCalloutScreen(
            callout = callout,
            streakDays = streakDays,
            todayDeflected = todayDeflected,
            totalDeflected = totalDeflected,
            plans = plans,
            onOpenIgDirectOnly = {
                onDismissIntercept()
                selectedTab = 1
            },
            onReturnToDashboard = {
                onDismissIntercept()
                selectedTab = 0
            }
        )
        return
    }

    // Tab 1: Clean Instagram Direct Mode (Safe Status Bar insets, Cookie Enabled, No Black Screen)
    if (selectedTab == 1) {
        InstagramDirectScreen(
            onBackToDashboard = { selectedTab = 0 },
            onBlockedNavigation = { blockedUrl, vType ->
                onTriggerIntercept("Instagram Reel / Feed ($blockedUrl)", vType)
            }
        )
        return
    }

    // Tab 2: Clean YouTube with Centered Search Hero & Safe Insets
    if (selectedTab == 2) {
        YouTubeCleanSearchScreen(
            onBackToDashboard = { selectedTab = 0 },
            onBlockedNavigation = { blockedUrl, vType ->
                onTriggerIntercept("YouTube Short ($blockedUrl)", vType)
            }
        )
        return
    }

    // Default: Home Dashboard Scaffold
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                val tabs = listOf("Home", "Instagram", "YouTube", "Rules")
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = {
                            syncState()
                            selectedTab = idx
                        },
                        icon = { Text(label.take(2).uppercase(), fontWeight = FontWeight.Bold) },
                        label = { Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeDashboardTab(
                    streakDays = streakDays,
                    todayDeflected = todayDeflected,
                    totalDeflected = totalDeflected,
                    accessibilityEnabled = accessibilityEnabled,
                    overlayGranted = overlayGranted,
                    onOpenPermissionSetup = { showPermissionDialog = true },
                    updateInfo = updateInfo,
                    updateStatusText = updateStatusText,
                    downloadProgress = downloadProgress,
                    onCheckUpdates = { checkForGithubUpdates() },
                    onInstallUpdate = {
                        val url = updateInfo?.apkDownloadUrl
                            ?: "https://github.com/DALI951/zerofeed/releases/latest/download/ZEROFEED-Android-S23Ultra.apk"
                        downloadProgress = 0
                        updateStatusText = "Downloading latest APK from GitHub Releases..."
                        ZeroFeedAutoUpdater.downloadAndInstallApk(
                            context = context,
                            apkUrl = url,
                            onProgress = { pct ->
                                downloadProgress = pct
                                updateStatusText = "Downloading APK update: $pct%"
                            },
                            onError = { err ->
                                downloadProgress = -1
                                updateStatusText = err
                            }
                        )
                    },
                    advice = ZeroFeedRuleEngine.getAdviceOfTheDay(dayOfYear, adviceOffset),
                    onNextAdvice = { adviceOffset += 1 },
                    plans = plans,
                    onTogglePlan = { idx, checked ->
                        val updated = plans.toMutableList()
                        updated[idx] = updated[idx].copy(completed = checked)
                        repo.savePlans(updated)
                        syncState()
                    },
                    onAddPlan = { title ->
                        val updated = plans + PlanTask(title = title, completed = false)
                        repo.savePlans(updated)
                        syncState()
                    },
                    strictLocked = strictLocked,
                    onEngageStrictLock = {
                        repo.lockStrictForMinutes(120)
                        syncState()
                    },
                    onOpenCleanInstagram = { selectedTab = 1 },
                    onOpenCleanYouTube = { selectedTab = 2 },
                    onSimulateReelAttempt = {
                        onTriggerIntercept("Instagram Reel / YouTube Short", "SHORT_OR_REEL")
                    }
                )
                3 -> RulesAndDiagnosticsTab(repo = repo, strictLocked = strictLocked)
            }
        }
    }
}

/**
 * Clean Instagram Direct Messages Screen:
 * - Proper top Status Bar padding (never renders under the clock/camera cutout)
 * - Cookies & DOM storage enabled so login works reliably and never gets stuck on black screen
 * - Hides feeds & reels while allowing full Direct Messages chatting
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramDirectScreen(
    onBackToDashboard: () -> Unit,
    onBlockedNavigation: (String, String) -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentTitle by remember { mutableStateOf("Instagram Direct Messages") }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBackToDashboard()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Safe Top Bar with smooth pill actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Button(
                onClick = onBackToDashboard,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("← Home", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = currentTitle,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
                    if (intent != null) context.startActivity(intent)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com")))
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("App", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

                        val applyScript = { view: WebView? ->
                            val js = """
                                (function() {
                                    var p = window.location.pathname;
                                    var isAuth = p.includes('/accounts/') || p.includes('/login') || p.includes('/challenge');
                                    if (!isAuth) {
                                        var style = document.getElementById('zf-ig-style');
                                        if (!style) {
                                            style = document.createElement('style');
                                            style.id = 'zf-ig-style';
                                            (document.head || document.documentElement).appendChild(style);
                                        }
                                        style.textContent = `
                                            a[href='/explore/'], a[href*='/reels/'], a[href*='/reel/'],
                                            svg[aria-label='Explore'], svg[aria-label='Reels'],
                                            div[role='tablist'] a:not([href*='direct']) {
                                                display: none !important;
                                                pointer-events: none !important;
                                            }
                                        `;
                                        if (p === '/' || p.startsWith('/reels') || p.startsWith('/explore')) {
                                            window.location.replace('https://www.instagram.com/direct/inbox/');
                                        }
                                    }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url?.toString() ?: return false
                                val verdict = ZeroFeedRuleEngine.evaluateUrl(target)
                                if (verdict.action == "BLOCK") {
                                    onBlockedNavigation(target, verdict.violationType)
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null) {
                                    currentTitle = if (url.contains("/direct/")) "Direct Messages" else "Instagram"
                                }
                                applyScript(view)
                            }
                        }

                        loadUrl("https://www.instagram.com/direct/inbox/")
                    }
                }
            )
        }
    }
}

/**
 * Clean YouTube Search Hero Experience:
 * - When no search is active: Shows a prominent, beautiful, centered search bar right in the middle of the screen!
 * - When searching: displays YouTube search results with Shorts stripped and status bar safe insets
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeCleanSearchScreen(
    onBackToDashboard: () -> Unit,
    onBlockedNavigation: (String, String) -> Unit
) {
    val context = LocalContext.current
    var queryText by remember { mutableStateOf("") }
    var activeUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun executeSearch(query: String) {
        val q = query.trim()
        if (q.isNotBlank()) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            activeUrl = "https://m.youtube.com/results?search_query=$encoded"
        }
    }

    BackHandler {
        if (activeUrl != null) {
            val wv = webViewRef
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                activeUrl = null
            }
        } else {
            onBackToDashboard()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Action Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Button(
                onClick = {
                    if (activeUrl != null) activeUrl = null else onBackToDashboard()
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(if (activeUrl != null) "← Search Home" else "← Home", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "YouTube Clean Study",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (intent != null) context.startActivity(intent)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")))
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("App", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Mode A: Prominent Centered Search Bar (Middle of the Screen)
        if (activeUrl == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Shield Badge
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = "ZERO-SHORTS // CLEAN STUDY",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = "What do you want to learn?",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Feeds and Shorts are removed so your mind stays sharp. Search directly for any lecture, tutorial, or course.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(26.dp))

                    // Prominent Middle Search Bar
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        placeholder = { Text("Search YouTube for lectures, tutorials, music...", fontSize = 13.5.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Button(
                                onClick = { executeSearch(queryText) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text("Search", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Educational Quick-Pills
                    Text(
                        text = "Suggested Educational Topics:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val quickPills = listOf(
                        "Algorithms & Data Structures",
                        "MIT OpenCourseWare",
                        "Calculus & Linear Algebra",
                        "System Design & Architecture",
                        "Deep Work Focus Music"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        quickPills.chunked(2).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowItems.forEach { topic ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape,
                                        modifier = Modifier.clickable {
                                            queryText = topic
                                            executeSearch(topic)
                                        }
                                    ) {
                                        Text(
                                            text = topic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Mode B: Active Search Results / Video Player View
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

                            val cleanCss = """
                                ytm-reel-shelf-renderer, ytd-reel-shelf-renderer,
                                ytm-shorts-lockup-view-model, a[href^='/shorts'], a[href*='/shorts/'],
                                ytm-pivot-bar-item-renderer:has(div.pivot-shorts),
                                ytm-pivot-bar-item-renderer:nth-child(2),
                                ytm-browse div.tab-content[tab-identifier='FEwhat_to_watch'],
                                #related:not(:has(ytd-playlist-panel-renderer)),
                                ytd-watch-next-secondary-results-renderer:not(:has(ytd-playlist-panel-renderer)),
                                ytm-item-section-renderer[section-identifier='related-items'],
                                ytm-single-column-watch-next-results-renderer > .related-items,
                                ytm-watch-next-feed,
                                .ytp-ce-element, .ytp-endscreen-content {
                                    display: none !important;
                                    visibility: hidden !important;
                                }
                                ytd-playlist-panel-renderer, #playlist, ytm-playlist-panel-renderer {
                                    display: flex !important;
                                    visibility: visible !important;
                                }
                                body, html { background-color: #0f0f0f !important; }
                            """.trimIndent()

                            val applyDom = { view: WebView? ->
                                val js = """
                                    (function() {
                                        var s = document.getElementById('zf-yt-clean-style');
                                        if (!s) {
                                            s = document.createElement('style');
                                            s.id = 'zf-yt-clean-style';
                                            (document.head || document.documentElement).appendChild(s);
                                        }
                                        s.textContent = `$cleanCss`;
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(js, null)
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val target = request?.url?.toString() ?: return false
                                    val verdict = ZeroFeedRuleEngine.evaluateUrl(target)
                                    if (verdict.action == "BLOCK") {
                                        onBlockedNavigation(target, verdict.violationType)
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    applyDom(view)
                                }
                            }
                            loadUrl(activeUrl ?: "https://m.youtube.com")
                        }
                    },
                    update = { view ->
                        if (activeUrl != null && view.url != activeUrl) {
                            view.loadUrl(activeUrl!!)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun HardTruthCalloutScreen(
    callout: HardTruthCallout,
    streakDays: Int,
    todayDeflected: Int,
    totalDeflected: Int,
    plans: List<PlanTask>,
    onOpenIgDirectOnly: () -> Unit,
    onReturnToDashboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(22.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = CircleShape,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                text = callout.headline,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Clean Streak Protected: $streakDays Day(s) | Distractions Stopped Today: $todayDeflected (Total: $totalDeflected)",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(14.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = callout.shameMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 21.sp,
                modifier = Modifier.padding(18.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        SmoothCardSection(title = "READ A REAL BOOK INSTEAD") {
            Text(callout.bookReminder, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        SmoothCardSection(title = "SOLVE ALGORITHMIC / CODING PROBLEMS INSTEAD") {
            Text(callout.problemReminder, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        SmoothCardSection(title = "YOUR DAILY PLANS (DO THESE NOW)") {
            plans.forEach { p ->
                Text(
                    text = "${if (p.completed) "[DONE]" else "[TODO]"} ${p.title}",
                    color = if (p.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SmoothCardSection(title = "ADVICE OF THE DAY") {
            Text(callout.adviceOfTheDay, color = MaterialTheme.colorScheme.tertiary, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onOpenIgDirectOnly,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Instagram Direct Messages Only", color = MaterialTheme.colorScheme.onSecondary, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onReturnToDashboard,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Return to ZEROFEED Home & Read / Solve Problems", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeDashboardTab(
    streakDays: Int,
    todayDeflected: Int,
    totalDeflected: Int,
    accessibilityEnabled: Boolean,
    overlayGranted: Boolean,
    onOpenPermissionSetup: () -> Unit,
    updateInfo: ReleaseUpdateInfo?,
    updateStatusText: String,
    downloadProgress: Int,
    onCheckUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    advice: String,
    onNextAdvice: () -> Unit,
    plans: List<PlanTask>,
    onTogglePlan: (Int, Boolean) -> Unit,
    onAddPlan: (String) -> Unit,
    strictLocked: Boolean,
    onEngageStrictLock: () -> Unit,
    onOpenCleanInstagram: () -> Unit,
    onOpenCleanYouTube: () -> Unit,
    onSimulateReelAttempt: () -> Unit
) {
    var newPlanText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "ZEROFEED v2.0",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Galaxy S23 Ultra · Material You Theme",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ) {
                Text(
                    text = "SHIELD ACTIVE",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live Permission Status Banner
        SmoothCardSection(title = "IN-APP PERMISSION STATUS") {
            val allGranted = accessibilityEnabled && overlayGranted
            Text(
                text = if (allGranted) "✓ ALL PERMISSIONS ACTIVE — Reels & Shorts are actively guarded!" else "⚠ ACTION NEEDED — Grant permissions to close Reels/Shorts:",
                color = if (allGranted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = if (accessibilityEnabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                    shape = CircleShape
                ) {
                    Text(
                        text = if (accessibilityEnabled) "Accessibility: ✓" else "Accessibility: ✗",
                        color = if (accessibilityEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Surface(
                    color = if (overlayGranted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                    shape = CircleShape
                ) {
                    Text(
                        text = if (overlayGranted) "Appear on Top: ✓" else "Appear on Top: ✗",
                        color = if (overlayGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onOpenPermissionSetup,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (allGranted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (allGranted) "Review Permission Setup" else "Grant Missing Permissions Now",
                    color = if (allGranted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // GitHub Release Auto-Updater
        SmoothCardSection(title = "GITHUB RELEASE AUTO-UPDATER") {
            Text(
                text = updateStatusText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
            if (downloadProgress in 0..100) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCheckUpdates,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Check GitHub", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onInstallUpdate,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (updateInfo?.isNewerAvailable == true) "Install ${updateInfo.latestTag}" else "Install Latest APK",
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Launchers
        SmoothCardSection(title = "CLEAN APPS (FEED & SHORTS REMOVED)") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onOpenCleanInstagram,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Instagram DMs", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenCleanYouTube,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("YouTube Study", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Clean Streak Card
        SmoothCardSection(title = "CLEAN FOCUS STREAK (STAYS INTACT)") {
            Text(
                text = "$streakDays ${if (streakDays == 1) "Day" else "Days"} Clean Streak",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Reels/Shorts Stopped Today: $todayDeflected (Total: $totalDeflected) — Your streak never resets when stopped!",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Advice of the Day
        SmoothCardSection(title = "ADVICE OF THE DAY") {
            Text(advice, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNextAdvice,
                shape = CircleShape
            ) {
                Text("Cycle Next Advice", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Daily Plans
        SmoothCardSection(title = "DAILY PLANS (READ A BOOK & SOLVE PROBLEMS)") {
            plans.forEachIndexed { idx, plan ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Checkbox(
                        checked = plan.completed,
                        onCheckedChange = { onTogglePlan(idx, it) }
                    )
                    Text(
                        text = plan.title,
                        color = if (plan.completed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPlanText,
                    onValueChange = { newPlanText = it },
                    placeholder = { Text("Add daily task (e.g. Read 25 pages)", fontSize = 12.sp) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newPlanText.isNotBlank()) {
                            onAddPlan(newPlanText.trim())
                            newPlanText = ""
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Add", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Strict Mode Card
        SmoothCardSection(title = "STRICT MODE & ACCOUNTABILITY") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onEngageStrictLock,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (strictLocked) "Locked (2h)" else "Lock Strict Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSimulateReelAttempt,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Preview Callout", color = MaterialTheme.colorScheme.onError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun RulesAndDiagnosticsTab(repo: ZeroFeedRepository, strictLocked: Boolean) {
    var testUrl by remember { mutableStateOf("https://www.youtube.com/shorts/dQw4w9WgXcQ") }
    var testResult by remember { mutableStateOf("Tap 'Evaluate URL' to verify Android rule engine.") }
    var blockedSet by remember { mutableStateOf(repo.getBlockedPackages()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        SmoothCardSection(title = "INTERACTIVE URL RULE TESTER") {
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val v = ZeroFeedRuleEngine.evaluateUrl(testUrl)
                    testResult = "Action: ${v.action} | Platform: ${v.platformId} | Type: ${v.violationType}\nReason: ${v.reason}"
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Evaluate URL", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(testResult, color = MaterialTheme.colorScheme.tertiary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))
        SmoothCardSection(title = "BLOCKED ANDROID APPS") {
            val apps = listOf(
                "com.zhiliaoapp.musically" to "TikTok Native App",
                "com.snapchat.android" to "Snapchat Native App"
            )
            apps.forEach { (pkg, label) ->
                val isBlocked = blockedSet.contains(pkg)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = isBlocked,
                        enabled = !(strictLocked && isBlocked),
                        onCheckedChange = {
                            repo.setPackageBlocked(pkg, it)
                            blockedSet = repo.getBlockedPackages()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SmoothCardSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
