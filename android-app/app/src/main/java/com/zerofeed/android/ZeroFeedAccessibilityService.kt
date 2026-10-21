package com.zerofeed.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZeroFeedAccessibilityService : AccessibilityService() {

    private var lastInterceptEpochMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Never block emergency dialer, SMS, Settings, authenticators, or ZEROFEED itself
        if (ZeroFeedRuleEngine.isEmergencyAllowedPackage(pkg)) return

        val repo = ZeroFeedRepository(applicationContext)
        val blockedPackages = repo.getBlockedPackages()

        // 1. Check if the entire native app is blocked (e.g., TikTok, or native Instagram redirected to IG Direct Mode)
        if (blockedPackages.contains(pkg)) {
            triggerIntercept(repo, "Blocked App ($pkg)", "SHORT_OR_REEL")
            return
        }

        // 2. If native YouTube is opened, detect Shorts player shelf or Reel view IDs
        if (pkg == "com.google.android.youtube") {
            val rootNode = rootInActiveWindow ?: return
            if (containsShortsPlayerNode(rootNode)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                triggerIntercept(repo, "YouTube Native App Shorts Player", "SHORT_OR_REEL")
            }
        }
    }

    private fun containsShortsPlayerNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.contains("reel_player_page_container") || viewId.contains("reel_recycler")) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (containsShortsPlayerNode(node.getChild(i))) return true
        }
        return false
    }

    private fun triggerIntercept(repo: ZeroFeedRepository, source: String, violationType: String) {
        val now = System.currentTimeMillis()
        if (now - lastInterceptEpochMs < 2000L) return
        lastInterceptEpochMs = now

        repo.recordSlip(source)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("EXTRA_INTERCEPT_SOURCE", source)
            putExtra("EXTRA_VIOLATION_TYPE", violationType)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        // Required override; no-op
    }
}
