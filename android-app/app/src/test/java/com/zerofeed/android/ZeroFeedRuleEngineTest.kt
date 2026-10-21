package com.zerofeed.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroFeedRuleEngineTest {

    @Test
    fun blocksInstagramFeedAndReelsWhileAllowingDirectMessages() {
        val feed = ZeroFeedRuleEngine.evaluateUrl("https://www.instagram.com/")
        assertEquals("BLOCK", feed.action)
        assertEquals("instagram", feed.platformId)

        val reel = ZeroFeedRuleEngine.evaluateUrl("https://www.instagram.com/reels/C9xyz123/")
        assertEquals("BLOCK", reel.action)
        assertEquals("SHORT_OR_REEL", reel.violationType)

        val dmInbox = ZeroFeedRuleEngine.evaluateUrl("https://www.instagram.com/direct/inbox/")
        assertEquals("ALLOW", dmInbox.action)
        assertEquals("instagram", dmInbox.platformId)
    }

    @Test
    fun blocksYouTubeShortsWhileAllowingEducationalVideosAndSearch() {
        val shorts = ZeroFeedRuleEngine.evaluateUrl("https://m.youtube.com/shorts/dQw4w9WgXcQ")
        assertEquals("BLOCK", shorts.action)
        assertEquals("SHORT_OR_REEL", shorts.violationType)

        val watch = ZeroFeedRuleEngine.evaluateUrl("https://www.youtube.com/watch?v=8hly31xKli0")
        assertEquals("ALLOW", watch.action)
        assertEquals("youtube", watch.platformId)
    }

    @Test
    fun allowsNativeInstagramAndYouTubePackagesForInAppFilteringWhileBlockingTikTok() {
        assertFalse(ZeroFeedRuleEngine.defaultBlockedAppPackages.contains("com.instagram.android"))
        assertFalse(ZeroFeedRuleEngine.defaultBlockedAppPackages.contains("com.google.android.youtube"))
        assertTrue(ZeroFeedRuleEngine.defaultBlockedAppPackages.contains("com.zhiliaoapp.musically"))

        assertTrue(ZeroFeedRuleEngine.isEmergencyAllowedPackage("com.samsung.android.dialer"))
        assertTrue(ZeroFeedRuleEngine.isEmergencyAllowedPackage("com.samsung.android.messaging"))
    }

    @Test
    fun buildsHardTruthCalloutRemindingBookAndProblemsAndProtectingStreak() {
        val callout = ZeroFeedRuleEngine.buildHardTruthCallout("YouTube Short", "SHORT_OR_REEL", 294)
        assertTrue(callout.headline.contains("STREAK PROTECTED"))
        assertTrue(callout.bookReminder.lowercase().contains("book"))
        assertTrue(callout.problemReminder.lowercase().contains("problem"))
    }
}
