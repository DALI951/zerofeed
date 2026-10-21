package com.zerofeed.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private var interceptSourceState by mutableStateOf<String?>(null)
    private var interceptViolationState by mutableStateOf("SHORT_OR_REEL")
    private var requestedTabState by mutableIntStateOf(0)
    private var resumeTriggerTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF080B11),
                    surface = Color(0xFF111724),
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFF22C55E),
                    error = Color(0xFFEF4444)
                )
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

    override fun onResume() {
        super.onResume()
        // Re-check permissions automatically whenever user returns from Android Settings
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

    // Permission states (auto-refreshed on startup and onResume)
    var accessibilityEnabled by remember { mutableStateOf(PermissionHelper.isAccessibilityServiceEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(PermissionHelper.canDrawOverlays(context)) }
    var showPermissionDialog by remember { mutableStateOf(!accessibilityEnabled || !overlayGranted) }

    // Auto-Updater states
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
                    "Up to date! Installed: v${info.currentVersion} (Latest GitHub Release: ${info.latestTag})"
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

    // Automatic Startup Permission Setup Modal when Accessibility or Overlay permission is missing
    if (showPermissionDialog && (!accessibilityEnabled || !overlayGranted)) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            containerColor = Color(0xFF111724),
            titleContentColor = Color(0xFF38BDF8),
            textContentColor = Color.White,
            title = {
                Text(
                    "Permissions Needed to Close Reels/Shorts & Hide Feeds",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "To quit Reels/Shorts inside your real Instagram & YouTube apps and cover their home feeds, grant these 2 permissions:",
                        fontSize = 13.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        "• Samsung One UI Tip: If Accessibility is grayed out ('Restricted setting'), tap Step 1 first → tap ⋮ (top-right) → 'Allow restricted settings'.",
                        fontSize = 12.sp,
                        color = Color(0xFFFBBF24)
                    )
                    Button(
                        onClick = { PermissionHelper.openAppDetailsSettings(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. Open App Info (Allow Restricted Settings)", color = Color.White, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { PermissionHelper.openOverlayPermissionSettings(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (overlayGranted) Color(0xFF22C55E) else Color(0xFF38BDF8)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (overlayGranted) "✓ 2. Appear on Top (Granted)" else "2. Grant 'Appear on Top' (Hide Feeds)",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = { PermissionHelper.openAccessibilitySettings(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (accessibilityEnabled) Color(0xFF22C55E) else Color(0xFFEF4444)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (accessibilityEnabled) "✓ 3. Accessibility Guard (Enabled)" else "3. Turn On Accessibility Guard (Quit Reels/Shorts)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Continue to App", color = Color(0xFF38BDF8))
                }
            }
        )
    }

    // If a Reel, Short, or Blocked Feed was just intercepted, show the Hard-Truth Accountability Callout Screen
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

    // Tabs 1 (Instagram DMs) and 2 (YouTube) run in 100% FULL-SCREEN NATIVE-LOOK MODE
    if (selectedTab == 1) {
        SeamlessNativeLookExperience(
            backgroundColor = Color(0xFF000000),
            initialUrl = "https://www.instagram.com/direct/inbox/",
            injectedCss = """
                a[href='/'], a[href='/explore/'], a[href*='/reels/'], a[href*='/reel/'],
                svg[aria-label='Explore'], svg[aria-label='Reels'], svg[aria-label='Home'],
                div[role='tablist'] a:not([href*='direct']) {
                    display: none !important;
                    pointer-events: none !important;
                }
                body, html { background-color: #000000 !important; }
            """.trimIndent(),
            injectedJs = """
                (function() {
                    if (window.location.pathname === '/' || window.location.pathname.startsWith('/reels') || window.location.pathname.startsWith('/explore')) {
                        window.location.replace('https://www.instagram.com/direct/inbox/');
                    }
                })();
            """.trimIndent(),
            onBlockedNavigation = { blockedUrl, vType ->
                onTriggerIntercept("Instagram Reel / Feed ($blockedUrl)", vType)
            },
            onExitToZeroFeedHome = { selectedTab = 0 }
        )
        return
    }

    if (selectedTab == 2) {
        SeamlessNativeLookExperience(
            backgroundColor = Color(0xFF0F0F0F),
            initialUrl = "https://m.youtube.com",
            injectedCss = """
                ytm-reel-shelf-renderer, ytd-reel-shelf-renderer,
                ytm-shorts-lockup-view-model, ytm-rich-section-renderer,
                a[href^='/shorts'], a[href*='/shorts/'],
                ytm-pivot-bar-item-renderer:has(div.pivot-shorts),
                ytm-pivot-bar-item-renderer:nth-child(2),
                ytm-browse div.tab-content[tab-identifier='FEwhat_to_watch'] ytm-rich-grid-renderer,
                ytm-browse div.tab-content[tab-identifier='FEwhat_to_watch'] ytm-section-list-renderer {
                    display: none !important;
                    visibility: hidden !important;
                    pointer-events: none !important;
                }
                ytm-mobile-topbar-renderer, header {
                    display: flex !important;
                    visibility: visible !important;
                }
                body, html { background-color: #0f0f0f !important; }
            """.trimIndent(),
            injectedJs = """
                (function() {
                    if (window.location.pathname === '/' && !document.getElementById('zf-yt-clean-home')) {
                        var msg = document.createElement('div');
                        msg.id = 'zf-yt-clean-home';
                        msg.style.cssText = 'margin: 90px 24px 20px; padding: 24px; border-radius: 12px; background: #181818; color: #f1f1f1; font-family: Roboto, sans-serif; text-align: center; border: 1px solid #272727;';
                        msg.innerHTML = '<div style="font-size:16px;font-weight:600;margin-bottom:8px;">YouTube Clean Search Mode</div><div style="font-size:13px;color:#aaaaaa;">Home Feed & Shorts are removed. Use the YouTube Search Bar at the top to search for any lecture, tutorial, or video.</div>';
                        document.body.appendChild(msg);
                    } else if (window.location.pathname !== '/') {
                        var existing = document.getElementById('zf-yt-clean-home');
                        if (existing) existing.remove();
                    }
                })();
            """.trimIndent(),
            onBlockedNavigation = { blockedUrl, vType ->
                onTriggerIntercept("YouTube Short ($blockedUrl)", vType)
            },
            onExitToZeroFeedHome = { selectedTab = 0 }
        )
        return
    }

    Scaffold(
        containerColor = Color(0xFF080B11),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111724)) {
                val tabs = listOf("Home", "Instagram", "YouTube", "Rules")
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = {
                            syncState()
                            selectedTab = idx
                        },
                        icon = { Text(label.take(2).uppercase(), fontWeight = FontWeight.Bold) },
                        label = { Text(label, fontSize = 11.sp) }
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SeamlessNativeLookExperience(
    backgroundColor: Color,
    initialUrl: String,
    injectedCss: String,
    injectedJs: String,
    onBlockedNavigation: (String, String) -> Unit,
    onExitToZeroFeedHome: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onExitToZeroFeedHome()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false

                    val applyCleanDom = { view: WebView? ->
                        val script = """
                            (function() {
                                var s = document.getElementById('zerofeed-native-clean-style');
                                if (!s) {
                                    s = document.createElement('style');
                                    s.id = 'zerofeed-native-clean-style';
                                    (document.head || document.documentElement).appendChild(s);
                                }
                                s.textContent = `$injectedCss`;
                                $injectedJs
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
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

                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            super.onPageCommitVisible(view, url)
                            applyCleanDom(view)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null) {
                                val verdict = ZeroFeedRuleEngine.evaluateUrl(url)
                                if (verdict.action == "BLOCK") {
                                    if (view?.canGoBack() == true) view.goBack()
                                    onBlockedNavigation(url, verdict.violationType)
                                    return
                                }
                            }
                            applyCleanDom(view)
                        }
                    }
                    loadUrl(initialUrl)
                }
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color(0xCC111724), CircleShape)
                .border(1.dp, Color(0x6638BDF8), CircleShape)
                .clickable { onExitToZeroFeedHome() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("ZF Home", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            .background(Color(0xFF0A0608))
            .verticalScroll(rememberScrollState())
            .padding(22.dp)
    ) {
        Text(
            text = callout.headline,
            color = Color(0xFFFCA5A5),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Clean Streak Protected: $streakDays Day(s) | Distractions Stopped Today: $todayDeflected (Total: $totalDeflected)",
            color = Color(0xFF86EFAC),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF261014), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                .padding(16.dp)
        ) {
            Text(
                text = callout.shameMessage,
                color = Color(0xFFFECACA),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        CardSection(title = "READ A REAL BOOK INSTEAD") {
            Text(callout.bookReminder, color = Color.White, fontSize = 13.5.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        CardSection(title = "SOLVE ALGORITHMIC / CODING PROBLEMS INSTEAD") {
            Text(callout.problemReminder, color = Color.White, fontSize = 13.5.sp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        CardSection(title = "YOUR DAILY PLANS (DO THESE NOW)") {
            plans.forEach { p ->
                Text(
                    text = "${if (p.completed) "[DONE]" else "[TODO]"} ${p.title}",
                    color = if (p.completed) Color(0xFF94A3B8) else Color(0xFF86EFAC),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        CardSection(title = "ADVICE OF THE DAY") {
            Text(callout.adviceOfTheDay, color = Color(0xFFBAE6FD), fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onOpenIgDirectOnly,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Instagram Direct Messages Only", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onReturnToDashboard,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Return to ZEROFEED Home & Read / Solve Problems", color = Color.Black, fontWeight = FontWeight.Bold)
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
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            text = "ZEROFEED v2.0 // GALAXY S23 ULTRA",
            color = Color(0xFF38BDF8),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "In-App Guard: Quits Reels/Shorts & hides Home Feed inside Instagram and YouTube while keeping DMs & Search.",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Live Permission Status Banner (Prompts immediately if missing!)
        CardSection(title = "IN-APP REEL/SHORT BLOCKER PERMISSIONS") {
            val allGranted = accessibilityEnabled && overlayGranted
            Text(
                text = if (allGranted) {
                    "✓ ALL PERMISSIONS ACTIVE — Native Instagram & YouTube are actively guarded!"
                } else {
                    "⚠ PERMISSION REQUIRED — Enable Accessibility & Overlay below so ZEROFEED can close Reels/Shorts and hide feeds:"
                },
                color = if (allGranted) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "• Accessibility Guard (Quits Reels/Shorts): ${if (accessibilityEnabled) "ENABLED ✓" else "DISABLED ✗"}\n" +
                    "• Appear on Top Overlay (Hides Home Feed): ${if (overlayGranted) "GRANTED ✓" else "MISSING ✗"}",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onOpenPermissionSetup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (allGranted) Color(0xFF1E293B) else Color(0xFFEF4444)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (allGranted) "Review Permission Diagnostics" else "Grant Permissions Now (Required to Block Reels/Shorts)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // GitHub Release Auto-Updater Card
        CardSection(title = "GITHUB RELEASE AUTO-UPDATER") {
            Text(
                text = updateStatusText,
                color = if (updateInfo?.isNewerAvailable == true) Color(0xFF86EFAC) else Color(0xFFBAE6FD),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (downloadProgress in 0..100) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF38BDF8)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onCheckUpdates,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Check GitHub", color = Color(0xFF38BDF8), fontSize = 12.sp)
                }
                Button(
                    onClick = onInstallUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (updateInfo?.isNewerAvailable == true) "Install Update (${updateInfo.latestTag})" else "Download & Install Latest APK",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Quick Launchers for Clean Instagram & Clean YouTube
        CardSection(title = "CLEAN APPS (ORIGINAL LOOK — BAD PARTS STRIPPED)") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onOpenCleanInstagram,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Instagram (DMs Only)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenCleanYouTube,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("YouTube (Search Only)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Streak & Deflected Count
        CardSection(title = "CLEAN FOCUS STREAK (PROTECTED BY SHIELD)") {
            Text(
                text = "$streakDays ${if (streakDays == 1) "Day" else "Days"} Clean Streak",
                color = Color(0xFF86EFAC),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Reels/Shorts Stopped Today: $todayDeflected (Total Stopped: $totalDeflected) — Streak stays intact when ZEROFEED stops you!",
                color = Color(0xFF38BDF8),
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Advice of the Day
        CardSection(title = "ADVICE OF THE DAY") {
            Text(advice, color = Color(0xFFE2E8F0), fontSize = 13.5.sp, lineHeight = 19.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onNextAdvice) {
                Text("Cycle Next Advice", color = Color(0xFF38BDF8), fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Daily Plans
        CardSection(title = "DAILY PLANS (READ A BOOK & SOLVE PROBLEMS)") {
            plans.forEachIndexed { idx, plan ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Checkbox(
                        checked = plan.completed,
                        onCheckedChange = { onTogglePlan(idx, it) }
                    )
                    Text(
                        text = plan.title,
                        color = if (plan.completed) Color(0xFF64748B) else Color.White,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPlanText,
                    onValueChange = { newPlanText = it },
                    placeholder = { Text("Add plan (e.g. Solve 2 graph problems)", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (newPlanText.isNotBlank()) {
                        onAddPlan(newPlanText.trim())
                        newPlanText = ""
                    }
                }) {
                    Text("Add")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Strict Mode & Callout Preview
        CardSection(title = "STRICT MODE & ACCOUNTABILITY TEST") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEngageStrictLock, modifier = Modifier.weight(1f)) {
                    Text(if (strictLocked) "Strict Mode Locked" else "Lock Strict Mode (2h)", fontSize = 12.sp)
                }
                Button(
                    onClick = onSimulateReelAttempt,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Preview Callout", color = Color.White, fontSize = 12.sp)
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
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        CardSection(title = "INTERACTIVE URL RULE TESTER") {
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val v = ZeroFeedRuleEngine.evaluateUrl(testUrl)
                testResult = "Action: ${v.action} | Platform: ${v.platformId} | Type: ${v.violationType}\nReason: ${v.reason}"
            }) {
                Text("Evaluate URL")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(testResult, color = Color(0xFFBAE6FD), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))
        CardSection(title = "FULL-APP BLOCKS (INSTAGRAM & YOUTUBE ARE FILTERED IN-APP INSTEAD)") {
            val apps = listOf(
                "com.zhiliaoapp.musically" to "TikTok Native App (Full Block)",
                "com.snapchat.android" to "Snapchat Native App",
                "com.reddit.frontpage" to "Reddit Native App",
                "com.twitter.android" to "X / Twitter Native App"
            )
            apps.forEach { (pkg, label) ->
                val isBlocked = blockedSet.contains(pkg)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
fun CardSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111724), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF243048), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFF38BDF8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}
