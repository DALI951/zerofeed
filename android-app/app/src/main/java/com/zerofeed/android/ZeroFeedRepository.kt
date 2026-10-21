package com.zerofeed.android

import android.content.Context

data class PlanTask(val title: String, val completed: Boolean)

class ZeroFeedRepository(context: Context) {
    private val prefs = context.getSharedPreferences("zerofeed_android_prefs_v2", Context.MODE_PRIVATE)

    fun getStreakDays(): Int = prefs.getInt("streak_days", 1)

    fun getTodaySlips(): Int = prefs.getInt("today_deflected", 0)

    fun getTotalSlips(): Int = prefs.getInt("total_deflected", 0)

    /**
     * Records that ZEROFEED intercepted and quit a Reel/Short/Feed WITHOUT counting the user as slipped!
     * Your Clean Focus Streak stays intact when ZEROFEED stops the distraction.
     */
    fun recordSlip(reason: String) {
        val currentStreak = getStreakDays().coerceAtLeast(1)
        prefs.edit()
            .putInt("streak_days", currentStreak)
            .putInt("today_deflected", getTodaySlips() + 1)
            .putInt("total_deflected", getTotalSlips() + 1)
            .putString("last_intercept_reason", reason)
            .apply()
    }

    fun getLockedUntilEpochMs(): Long = prefs.getLong("locked_until_ms", 0L)

    fun isStrictLocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        return getLockedUntilEpochMs() > nowMs
    }

    fun lockStrictForMinutes(minutes: Int, nowMs: Long = System.currentTimeMillis()) {
        val target = nowMs + minutes * 60_000L
        prefs.edit().putLong("locked_until_ms", target).apply()
    }

    fun getBlockedPackages(): Set<String> {
        return prefs.getStringSet("blocked_packages", ZeroFeedRuleEngine.defaultBlockedAppPackages)
            ?: ZeroFeedRuleEngine.defaultBlockedAppPackages
    }

    fun setPackageBlocked(packageName: String, blocked: Boolean) {
        val current = getBlockedPackages().toMutableSet()
        if (blocked) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet("blocked_packages", current).apply()
    }

    fun getPlans(): List<PlanTask> {
        val raw = prefs.getString(
            "daily_plans_v1",
            "0|Read 25 pages of a technical or non-fiction book\n" +
                "0|Solve 3 algorithmic / competitive programming problems\n" +
                "0|Complete one 90-minute uninterrupted deep-work block"
        ) ?: ""
        return raw.lines().mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx > 0) {
                PlanTask(
                    title = line.substring(idx + 1),
                    completed = line.substring(0, idx) == "1"
                )
            } else null
        }
    }

    fun savePlans(plans: List<PlanTask>) {
        val serialized = plans.joinToString("\n") { "${if (it.completed) "1" else "0"}|${it.title}" }
        prefs.edit().putString("daily_plans_v1", serialized).apply()
    }
}
