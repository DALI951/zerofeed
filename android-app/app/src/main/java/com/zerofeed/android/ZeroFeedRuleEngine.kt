package com.zerofeed.android

import java.net.URI

data class UrlVerdict(
    val action: String,          // "ALLOW" or "BLOCK"
    val platformId: String,
    val violationType: String,   // "SHORT_OR_REEL", "INFINITE_FEED", or "NONE"
    val reason: String,
    val fallbackSafeUrl: String?
)

data class HardTruthCallout(
    val headline: String,
    val shameMessage: String,
    val bookReminder: String,
    val problemReminder: String,
    val adviceOfTheDay: String
)

object ZeroFeedRuleEngine {

    val emergencyAllowedPackages = setOf(
        "com.zerofeed.android",
        "com.samsung.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.settings",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.duosecurity.duomobile",
        "com.sec.android.app.launcher"
    )

    val defaultBlockedAppPackages = setOf(
        "com.zhiliaoapp.musically",     // TikTok
        "com.ss.android.ugc.trill",     // TikTok regional
        "com.instagram.android",        // Native Instagram (redirected to ZEROFEED IG Direct Mode)
        "com.reddit.frontpage",         // Reddit native feed
        "com.twitter.android",          // X / Twitter native feed
        "com.snapchat.android"          // Snapchat Spotlight
    )

    private val globalAllowDomains = listOf(
        "scholar.google.com",
        "github.com",
        "stackoverflow.com",
        "codeforces.com",
        "leetcode.com",
        "wikipedia.org",
        "arxiv.org",
        "mit.edu",
        "stanford.edu",
        "coursera.org",
        "edx.org",
        "khanacademy.org"
    )

    val adviceBank = listOf(
        "Every 60-second Reel or Short fragments your working memory for up to 20 minutes. Close the tab, open your book, and read one chapter.",
        "Algorithms are engineered by thousands of PhDs to monetize your attention. Beat them by solving 3 hard coding problems before checking any messages.",
        "Dopamine from cheap scrolling makes real achievement feel boring. Starve the cheap dopamine so building, reading, and problem-solving become rewarding again.",
        "You do not need '5 minutes of Reels to relax.' Real rest is a walk, a paper book, or silence—not an infinite slot machine.",
        "Compare how you feel after 1 hour of Shorts (drained, guilty, foggy) with 1 hour of solving hard problems (sharp, proud, capable). Choose the second one today."
    )

    fun isEmergencyAllowedPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        return emergencyAllowedPackages.contains(packageName)
    }

    fun evaluateUrl(urlString: String, customBlockedDomains: Set<String> = emptySet()): UrlVerdict {
        val uri = try {
            URI(urlString.trim())
        } catch (_: Exception) {
            return UrlVerdict("ALLOW", "none", "NONE", "Invalid URI", null)
        }

        val host = (uri.host ?: "").lowercase().trimEnd('.')
        if (host.isEmpty()) {
            return UrlVerdict("ALLOW", "none", "NONE", "Internal or relative URI", null)
        }
        val fullUrl = urlString.trim()

        // 1. Global Allowlist (Education, Research, Coding)
        if (globalAllowDomains.any { host == it || host.endsWith(".$it") }) {
            return UrlVerdict("ALLOW", "allowlist", "NONE", "Allowed educational / coding domain", null)
        }
        if (Regex("""^https?://([a-z0-9-]+\.)*(edu|ac\.[a-z]{2,3})(/.*)?$""", RegexOption.IGNORE_CASE).matches(fullUrl)) {
            return UrlVerdict("ALLOW", "allowlist", "NONE", "Allowed academic domain", null)
        }

        // 2. Custom Blocked Domains
        if (customBlockedDomains.any { it.isNotBlank() && (host == it || host.endsWith(".$it")) }) {
            return UrlVerdict("BLOCK", "custom", classifyViolation(fullUrl, "custom"), "Blocked by custom domain rule", null)
        }

        // 3. Instagram: DMs Allowed (/direct/*), Feed/Reels/Explore Blocked
        if (host == "instagram.com" || host.endsWith(".instagram.com")) {
            val allowedIg = Regex("""^https?://(www\.)?instagram\.com/(direct|accounts|challenge)(/.*)?$""", RegexOption.IGNORE_CASE)
            if (allowedIg.matches(fullUrl)) {
                return UrlVerdict("ALLOW", "instagram", "NONE", "Allowed Instagram Direct Messaging route", "https://www.instagram.com/direct/inbox/")
            }
            return UrlVerdict(
                "BLOCK",
                "instagram",
                classifyViolation(fullUrl, "instagram"),
                "Blocked Instagram Feed/Reels/Explore (Direct Messages Only)",
                "https://www.instagram.com/direct/inbox/"
            )
        }

        // 4. YouTube: Search & Watch Allowed, Shorts & Trending Blocked
        if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be") {
            val blockedYt = Regex("""^https?://(www\.|m\.)?youtube\.com/(shorts|feed/trending|feed/explore)(/.*)?$""", RegexOption.IGNORE_CASE)
            if (blockedYt.matches(fullUrl)) {
                return UrlVerdict("BLOCK", "youtube", "SHORT_OR_REEL", "Blocked YouTube Shorts / Trending feed", "https://m.youtube.com")
            }
            return UrlVerdict("ALLOW", "youtube", "NONE", "Allowed educational YouTube route with Shorts shield", "https://m.youtube.com")
        }

        // 5. TikTok: Complete Block
        if (host == "tiktok.com" || host.endsWith(".tiktok.com")) {
            return UrlVerdict("BLOCK", "tiktok", "SHORT_OR_REEL", "TikTok is completely blocked by ZEROFEED", null)
        }

        // 6. Facebook: Messages Allowed, Feed & Reels Blocked
        if (host == "facebook.com" || host.endsWith(".facebook.com")) {
            if (Regex("""^https?://(www\.|m\.)?facebook\.com/messages(/.*)?$""", RegexOption.IGNORE_CASE).matches(fullUrl)) {
                return UrlVerdict("ALLOW", "facebook", "NONE", "Allowed Facebook Messenger", "https://www.facebook.com/messages/")
            }
            return UrlVerdict("BLOCK", "facebook", classifyViolation(fullUrl, "facebook"), "Blocked Facebook Feed/Reels", "https://www.facebook.com/messages/")
        }

        // 7. Reddit: Specific Thread Comments & Search Allowed, Home/Popular Blocked
        if (host == "reddit.com" || host.endsWith(".reddit.com")) {
            if (Regex("""^https?://(www\.|old\.)?reddit\.com/(r/[A-Za-z0-9_]+/comments/.*|search/?\?.*)""", RegexOption.IGNORE_CASE).matches(fullUrl)) {
                return UrlVerdict("ALLOW", "reddit", "NONE", "Allowed specific Reddit thread/search", null)
            }
            return UrlVerdict("BLOCK", "reddit", "INFINITE_FEED", "Blocked Reddit infinite home/popular feed", null)
        }

        // 8. X / Twitter: Messages & Search Allowed, Home/Explore Blocked
        if (host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")) {
            if (Regex("""^https?://(www\.)?(x|twitter)\.com/(messages(/.*)?|search\?.*)""", RegexOption.IGNORE_CASE).matches(fullUrl)) {
                return UrlVerdict("ALLOW", "twitter", "NONE", "Allowed X/Twitter Messages/Search", "https://x.com/messages")
            }
            return UrlVerdict("BLOCK", "twitter", "INFINITE_FEED", "Blocked X/Twitter algorithmic feed", "https://x.com/messages")
        }

        return UrlVerdict("ALLOW", "none", "NONE", "Unrestricted site", null)
    }

    fun isScheduleActive(hourOfDay: Int, minute: Int): Boolean {
        val currentMinutes = hourOfDay * 60 + minute
        // Morning Deep Work: 08:00 (480) - 13:00 (780)
        // Evening Study & Reading: 18:00 (1080) - 23:30 (1410)
        val inMorning = currentMinutes in 480 until 780
        val inEvening = currentMinutes in 1080 until 1410
        return inMorning || inEvening
    }

    fun getAdviceOfTheDay(dayOfYear: Int, offset: Int = 0): String {
        val idx = kotlin.math.abs(dayOfYear + offset) % adviceBank.size
        return adviceBank[idx]
    }

    fun buildHardTruthCallout(sourceName: String, violationType: String, dayOfYear: Int): HardTruthCallout {
        val headline = if (violationType == "SHORT_OR_REEL") {
            "SHORTS / REELS ATTEMPT DETECTED ($sourceName)"
        } else {
            "INFINITE FEED ATTEMPT DETECTED ($sourceName)"
        }
        val shame =
            "STOP. You just tried to open $sourceName. That is engineered digital junk food designed to wreck your attention span. Feel the weight of wasted potential—close this right now, pick up your book, and solve your coding problems."
        return HardTruthCallout(
            headline = headline,
            shameMessage = shame,
            bookReminder = "Read 25 pages of a real book today. Deep reading builds focus; 30-second videos destroy it.",
            problemReminder = "Solve 3 algorithmic / coding problems today. Earn real satisfaction through hard problem-solving.",
            adviceOfTheDay = getAdviceOfTheDay(dayOfYear)
        )
    }

    private fun classifyViolation(url: String, platformId: String): String {
        val lower = url.lowercase()
        return if (platformId == "tiktok" || lower.contains("/shorts") || lower.contains("/reel") || lower.contains("/spotlight")) {
            "SHORT_OR_REEL"
        } else {
            "INFINITE_FEED"
        }
    }
}
