package com.zerofeed.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingInterceptIntent(intent)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingInterceptIntent(intent)
    }

    private fun handleIncomingInterceptIntent(intent: Intent?) {
        val src = intent?.getStringExtra("EXTRA_INTERCEPT_SOURCE")
        if (!src.isNullOrBlank()) {
            interceptSourceState = src
            interceptViolationState = intent.getStringExtra("EXTRA_VIOLATION_TYPE") ?: "SHORT_OR_REEL"
        }
    }
}

@Composable
fun ZeroFeedAndroidApp(
    interceptSource: String?,
    interceptViolationType: String,
    onDismissIntercept: () -> Unit,
    onTriggerIntercept: (String, String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ZeroFeedRepository(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var streakDays by remember { mutableIntStateOf(repo.getStreakDays()) }
    var todaySlips by remember { mutableIntStateOf(repo.getTodaySlips()) }
    var totalSlips by remember { mutableIntStateOf(repo.getTotalSlips()) }
    var plans by remember { mutableStateOf(repo.getPlans()) }
    var adviceOffset by remember { mutableIntStateOf(0) }
    var strictLocked by remember { mutableStateOf(repo.isStrictLocked()) }

    fun syncState() {
        streakDays = repo.getStreakDays()
        todaySlips = repo.getTodaySlips()
        totalSlips = repo.getTotalSlips()
        plans = repo.getPlans()
        strictLocked = repo.isStrictLocked()
    }

    LaunchedEffect(interceptSource) {
        syncState()
    }

    val dayOfYear = remember { Calendar.getInstance().get(Calendar.DAY_OF_YEAR) }

    // If a Reel, Short, or Blocked Feed was just opened, show the Hard-Truth Accountability Callout Screen
    if (interceptSource != null) {
        val callout = ZeroFeedRuleEngine.buildHardTruthCallout(interceptSource, interceptViolationType, dayOfYear)
        HardTruthCalloutScreen(
            callout = callout,
            todaySlips = todaySlips,
            totalSlips = totalSlips,
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

    Scaffold(
        containerColor = Color(0xFF080B11),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111724)) {
                val tabs = listOf("Home", "IG Direct", "YT Study", "Rules & Test")
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = {
                            syncState()
                            selectedTab = idx
                        },
                        icon = { Text(label.take(2), fontWeight = FontWeight.Bold) },
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
                    todaySlips = todaySlips,
                    totalSlips = totalSlips,
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
                    onSimulateReelAttempt = {
                        onTriggerIntercept("Instagram Reel / YouTube Short", "SHORT_OR_REEL")
                    }
                )
                1 -> FilteredWebViewScreen(
                    title = "Instagram Direct Messages Only (Feed & Reels Blocked)",
                    initialUrl = "https://www.instagram.com/direct/inbox/",
                    injectedCss = """
                        a[href='/'], a[href='/explore/'], a[href*='/reels/'], a[href*='/reel/'],
                        svg[aria-label='Explore'], svg[aria-label='Reels'], svg[aria-label='Home'] {
                            display: none !important;
                        }
                    """.trimIndent(),
                    onBlockedNavigation = { blockedUrl, vType ->
                        onTriggerIntercept("Instagram Blocked Route ($blockedUrl)", vType)
                    }
                )
                2 -> FilteredWebViewScreen(
                    title = "YouTube Study Mode (Shorts & Home Feed Stripped)",
                    initialUrl = "https://m.youtube.com",
                    injectedCss = """
                        ytm-reel-shelf-renderer, ytd-reel-shelf-renderer, a[href^='/shorts'],
                        ytm-pivot-bar-item-renderer:has(div.pivot-shorts),
                        ytm-browse div.tab-content[tab-identifier='FEwhat_to_watch'] {
                            display: none !important;
                        }
                    """.trimIndent(),
                    onBlockedNavigation = { blockedUrl, vType ->
                        onTriggerIntercept("YouTube Shorts ($blockedUrl)", vType)
                    }
                )
                3 -> RulesAndDiagnosticsTab(repo = repo, strictLocked = strictLocked)
            }
        }
    }
}

@Composable
fun HardTruthCalloutScreen(
    callout: HardTruthCallout,
    todaySlips: Int,
    totalSlips: Int,
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
            text = "Slips Today: $todaySlips (Total: $totalSlips) | Clean Streak Reset to 0 Days",
            color = Color(0xFFF59E0B),
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
            Text("Open Instagram Direct Messages Only (/direct/inbox/)", color = Color.Black, fontWeight = FontWeight.Bold)
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
    todaySlips: Int,
    totalSlips: Int,
    advice: String,
    onNextAdvice: () -> Unit,
    plans: List<PlanTask>,
    onTogglePlan: (Int, Boolean) -> Unit,
    onAddPlan: (String) -> Unit,
    strictLocked: Boolean,
    onEngageStrictLock: () -> Unit,
    onSimulateReelAttempt: () -> Unit
) {
    val context = LocalContext.current
    var newPlanText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text(
            text = "ZEROFEED // GALAXY S23 ULTRA (ONE UI)",
            color = Color(0xFF38BDF8),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Shield Active: Preserve DMs, Research & Coding. Block Reels, Shorts & Feeds.",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Streak & Slips
        CardSection(title = "CLEAN FOCUS STREAK & SLIP DETECTION") {
            Text(
                text = "$streakDays ${if (streakDays == 1) "Day" else "Days"} Clean",
                color = Color(0xFF86EFAC),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Slips Detected Today: $todaySlips  |  Total Intercepted: $totalSlips",
                color = Color(0xFFF59E0B),
                fontSize = 12.5.sp
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

        // Strict Mode & Schedule Status
        CardSection(title = "ACTIVE SESSION, SCHEDULES & STRICT MODE") {
            Text(
                text = if (strictLocked) "STRICT MODE LOCKED — Settings cannot be weakened." else "Schedules Active: Morning (08:00–13:00) & Evening (18:00–23:30)",
                color = Color(0xFFFBBF24),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Accessible Emergency & Useful Tools: Phone Dialer, SMS, Authenticators, IG Direct Tab, YouTube Study Tab, .edu & Coding Platforms.",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEngageStrictLock) {
                    Text("Lock Strict Mode (2h)", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Enable Accessibility Guard", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onSimulateReelAttempt,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Reel/Short Attempt Detection & Shame Callout", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FilteredWebViewScreen(
    title: String,
    initialUrl: String,
    injectedCss: String,
    onBlockedNavigation: (String, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111724))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text = title, color = Color(0xFF38BDF8), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
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
                            val js = """
                                (function() {
                                    var s = document.getElementById('zerofeed-wv-style');
                                    if (!s) {
                                        s = document.createElement('style');
                                        s.id = 'zerofeed-wv-style';
                                        document.head.appendChild(s);
                                    }
                                    s.textContent = `$injectedCss`;
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }
                    }
                    loadUrl(initialUrl)
                }
            }
        )
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
        CardSection(title = "BLOCKED ANDROID NATIVE PACKAGES") {
            val apps = listOf(
                "com.zhiliaoapp.musically" to "TikTok Native App",
                "com.instagram.android" to "Instagram Native App (Use IG Direct Tab instead)",
                "com.reddit.frontpage" to "Reddit Native App",
                "com.twitter.android" to "X / Twitter Native App",
                "com.snapchat.android" to "Snapchat Native App"
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
