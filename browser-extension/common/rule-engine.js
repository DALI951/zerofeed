/**
 * ZEROFEED — Cross-Device Rule Engine & Accountability Evaluator
 * Compatible with Browser Extensions (MV3 Service Worker / Content Scripts) and Node.js.
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    root.ZeroFeedEngine = factory();
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const HARD_TRUTH_MESSAGES = {
    SHORT_OR_REEL: [
      "STOP. You just tried to open a short-form video loop (Reel / Short / TikTok). That is engineered digital junk food designed to wreck your attention span. Close this right now, pick up your book, or go solve 3 algorithmic coding problems.",
      "REALITY CHECK: Nobody ever built a great career, mastered computer science, or achieved deep focus by swiping 30-second videos. Feel the weight of this impulse—reject it, open your book, and read 20 pages.",
      "CAUGHT IN THE ACT: Your brain asked for cheap dopamine and you clicked a Reel/Short. Break the loop immediately. Open Codeforces/LeetCode, solve a hard problem, and earn real satisfaction."
    ],
    INFINITE_FEED: [
      "STOP SCROLLING. You opened an infinite algorithmic feed. There is nothing at the bottom of that feed except regret and wasted hours. Go read a book or work on your daily engineering problems.",
      "ACCOUNTABILITY ALERT: Communication is allowed; mindless feed browsing is not. If you needed to message someone, use Direct Messages only—otherwise get back to reading and problem-solving."
    ]
  };

  const CALM_MESSAGES = {
    SHORT_OR_REEL: [
      "Short-form video feeds are blocked by ZEROFEED. Return to your planned reading or problem-solving session."
    ],
    INFINITE_FEED: [
      "This algorithmic feed is blocked by ZEROFEED to protect your focus."
    ]
  };

  function normalizeHost(hostname) {
    return (hostname || "").toLowerCase().replace(/\.$/, "");
  }

  function hostMatchesDomainList(hostname, domainList) {
    const cleanHost = normalizeHost(hostname);
    if (!cleanHost || !Array.isArray(domainList)) return false;
    for (const d of domainList) {
      const target = normalizeHost(d);
      if (!target) continue;
      if (cleanHost === target || cleanHost.endsWith("." + target)) {
        return true;
      }
    }
    return false;
  }

  function urlMatchesRegexList(urlStr, regexList) {
    if (!Array.isArray(regexList)) return false;
    for (const pattern of regexList) {
      try {
        const re = new RegExp(pattern, "i");
        if (re.test(urlStr)) return true;
      } catch (_) {
        // Ignore malformed custom user regex
      }
    }
    return false;
  }

  function classifyViolationType(urlStr, platformId) {
    const lower = (urlStr || "").toLowerCase();
    if (
      platformId === "tiktok" ||
      lower.includes("/shorts") ||
      lower.includes("/reel") ||
      lower.includes("/spotlight")
    ) {
      return "SHORT_OR_REEL";
    }
    return "INFINITE_FEED";
  }

  /**
   * Evaluates whether a given URL is ALLOWED or BLOCKED, and returns any DOM selectors to hide.
   */
  function evaluateUrl(urlString, config) {
    if (!urlString || typeof urlString !== "string") {
      return { action: "ALLOW", reason: "Empty URL", hideSelectors: [] };
    }

    let parsed;
    try {
      parsed = new URL(urlString);
    } catch (_) {
      return { action: "ALLOW", reason: "Invalid URL", hideSelectors: [] };
    }

    if (!["http:", "https:"].includes(parsed.protocol)) {
      return { action: "ALLOW", reason: "Internal browser protocol", hideSelectors: [] };
    }

    const activeConfig = config || {};
    const hostname = normalizeHost(parsed.hostname);
    const fullUrl = parsed.href;

    // 1. Check Global Educational & Productive Allowlist first
    if (
      hostMatchesDomainList(hostname, activeConfig.globalAllowlistDomains) ||
      urlMatchesRegexList(fullUrl, activeConfig.globalAllowlistPatterns)
    ) {
      return {
        action: "ALLOW",
        platformId: null,
        reason: "Allowed educational / research / coding domain",
        hideSelectors: []
      };
    }

    // 2. Check User Custom Blocked Domains & URL Patterns
    if (
      hostMatchesDomainList(hostname, activeConfig.customBlockedDomains) ||
      urlMatchesRegexList(fullUrl, activeConfig.customBlockedUrlPatterns)
    ) {
      const vType = classifyViolationType(fullUrl, "custom");
      return {
        action: "BLOCK",
        platformId: "custom",
        platformName: "Custom Blocked Site",
        violationType: vType,
        reason: "Blocked by custom ZEROFEED rule",
        fallbackSafeUrl: null,
        hideSelectors: ["body"]
      };
    }

    // 3. Check Platform-specific Rules
    const platforms = activeConfig.platforms || {};
    for (const [platformKey, platform] of Object.entries(platforms)) {
      if (!platform || !platform.enabled) continue;
      if (!hostMatchesDomainList(hostname, platform.domains)) continue;

      const hideSelectors = Array.isArray(platform.hideSelectors) ? platform.hideSelectors : [];

      // Mode: full domain block (e.g., TikTok)
      if (platform.mode === "block_domain") {
        return {
          action: "BLOCK",
          platformId: platformKey,
          platformName: platform.name || platformKey,
          violationType: classifyViolationType(fullUrl, platformKey),
          reason: `${platform.name || platformKey} is completely blocked`,
          fallbackSafeUrl: platform.fallbackSafeUrl || null,
          hideSelectors: ["body"]
        };
      }

      // Explicit blocked URL patterns take priority over general routes
      if (urlMatchesRegexList(fullUrl, platform.blockedUrlPatterns)) {
        return {
          action: "BLOCK",
          platformId: platformKey,
          platformName: platform.name || platformKey,
          violationType: classifyViolationType(fullUrl, platformKey),
          reason: `Blocked distracting route on ${platform.name || platformKey}`,
          fallbackSafeUrl: platform.fallbackSafeUrl || null,
          hideSelectors
        };
      }

      // Explicit allowed URL patterns (e.g., Instagram Direct /direct/inbox/, YouTube /watch?v=...)
      if (urlMatchesRegexList(fullUrl, platform.allowedUrlPatterns)) {
        return {
          action: "ALLOW",
          platformId: platformKey,
          platformName: platform.name || platformKey,
          reason: `Allowed productive/messaging route on ${platform.name || platformKey}`,
          hideSelectors
        };
      }

      // If mode is allow_routes_only (e.g., Instagram, Facebook, X), block everything else on the domain
      if (platform.mode === "allow_routes_only") {
        return {
          action: "BLOCK",
          platformId: platformKey,
          platformName: platform.name || platformKey,
          violationType: classifyViolationType(fullUrl, platformKey),
          reason: `Route not in allowed list for ${platform.name || platformKey}`,
          fallbackSafeUrl: platform.fallbackSafeUrl || null,
          hideSelectors
        };
      }

      // Otherwise (mode === "block_patterns_and_clean_dom", e.g., YouTube root page so search bar remains usable)
      return {
        action: "ALLOW",
        platformId: platformKey,
        platformName: platform.name || platformKey,
        reason: `Allowed with DOM feed/Shorts filtering on ${platform.name || platformKey}`,
        hideSelectors
      };
    }

    return {
      action: "ALLOW",
      platformId: null,
      reason: "Unrestricted site",
      hideSelectors: []
    };
  }

  function parseMinutes(hhmm) {
    const parts = (hhmm || "00:00").split(":");
    const h = parseInt(parts[0], 10) || 0;
    const m = parseInt(parts[1], 10) || 0;
    return h * 60 + m;
  }

  /**
   * Evaluates whether any recurring schedule is currently active.
   */
  function evaluateSchedules(schedules, now = new Date()) {
    const list = Array.isArray(schedules) ? schedules : [];
    const day = now.getDay();
    const currentMinutes = now.getHours() * 60 + now.getMinutes();

    const activeSchedules = [];
    let nextSchedule = null;
    let minMinutesUntilNext = Infinity;

    for (const sched of list) {
      if (!sched || !sched.enabled) continue;
      const days = Array.isArray(sched.daysOfWeek) ? sched.daysOfWeek : [0, 1, 2, 3, 4, 5, 6];
      const startMin = parseMinutes(sched.startTime);
      const endMin = parseMinutes(sched.endTime);

      let isTodayActive = false;
      if (days.includes(day)) {
        if (startMin <= endMin) {
          isTodayActive = currentMinutes >= startMin && currentMinutes < endMin;
        } else {
          // Overnight schedule (e.g., 22:00 -> 06:00)
          isTodayActive = currentMinutes >= startMin || currentMinutes < endMin;
        }
      }

      if (isTodayActive) {
        activeSchedules.push(sched);
      } else if (days.includes(day) && startMin > currentMinutes) {
        const diff = startMin - currentMinutes;
        if (diff < minMinutesUntilNext) {
          minMinutesUntilNext = diff;
          nextSchedule = sched;
        }
      } else if (days.length > 0 && !nextSchedule) {
        nextSchedule = sched;
      }
    }

    return {
      active: activeSchedules.length > 0,
      activeSchedules,
      currentEndTime: activeSchedules.length > 0 ? activeSchedules[0].endTime : null,
      nextSchedule
    };
  }

  /**
   * Evaluates whether Strict Mode currently prevents disabling rules or editing blocklists.
   */
  function evaluateStrictMode(config, now = new Date()) {
    const strict = (config && config.strictMode) || {};
    const schedStatus = evaluateSchedules(config && config.schedules, now);

    if (strict.lockedUntilIso) {
      const untilMs = Date.parse(strict.lockedUntilIso);
      if (!Number.isNaN(untilMs) && untilMs > now.getTime()) {
        const remainingMinutes = Math.ceil((untilMs - now.getTime()) / 60000);
        return {
          locked: true,
          reason: `Strict Mode Timer Lock active (${remainingMinutes} min remaining)`,
          remainingMinutes,
          lockedUntilIso: strict.lockedUntilIso
        };
      }
    }

    const strictSched = schedStatus.activeSchedules.find((s) => s.strictLock);
    if (strictSched) {
      return {
        locked: true,
        reason: `Locked by active schedule: "${strictSched.name}" (until ${strictSched.endTime})`,
        remainingMinutes: null,
        lockedUntilIso: null
      };
    }

    return {
      locked: false,
      reason: "Unlocked — you can lock Strict Mode for a focus session",
      remainingMinutes: 0,
      lockedUntilIso: null
    };
  }

  /**
   * Returns deterministic Advice of the Day (plus index).
   */
  function getAdviceOfTheDay(config, now = new Date(), offset = 0) {
    const bank =
      config && Array.isArray(config.adviceBank) && config.adviceBank.length > 0
        ? config.adviceBank
        : [
            "Every 60-second Reel or Short fragments your working memory. Close the feed, open a book, and solve real problems."
          ];
    const startOfYear = new Date(now.getFullYear(), 0, 0);
    const diff = now - startOfYear;
    const oneDay = 1000 * 60 * 60 * 24;
    const dayOfYear = Math.floor(diff / oneDay);
    const index = Math.abs(dayOfYear + offset) % bank.length;
    return {
      index,
      total: bank.length,
      advice: bank[index]
    };
  }

  /**
   * Builds the Hard-Truth Accountability Callout message when a blocked Reel, Short, or Feed is opened.
   */
  function buildCalloutPayload(evaluation, config, now = new Date()) {
    const tone =
      config && config.strictMode && config.strictMode.calloutTone === "calm"
        ? "calm"
        : "hard_truth";
    const vType = (evaluation && evaluation.violationType) || "INFINITE_FEED";
    const pool = tone === "calm" ? CALM_MESSAGES[vType] : HARD_TRUTH_MESSAGES[vType];
    const messageIndex = now.getSeconds() % pool.length;

    const pendingPlans = ((config && config.dailyPlans) || []).filter((p) => !p.completed);
    const dailyAdvice = getAdviceOfTheDay(config, now).advice;

    return {
      tone,
      violationType: vType,
      platformName: (evaluation && evaluation.platformName) || "Blocked Feed",
      headline:
        vType === "SHORT_OR_REEL"
          ? "SHORTS / REELS ATTEMPT INTERCEPTED — STREAK PROTECTED"
          : "INFINITE FEED ATTEMPT INTERCEPTED — STREAK PROTECTED",
      shameCallout: pool[messageIndex],
      bookReminder:
        "Have you read your 25 pages today? A book builds deep neural pathways; a 30-second video erodes them.",
      problemSolvingReminder:
        "Have you solved your coding & algorithmic problems today? Open your IDE or problem set and do the hard work you are avoiding.",
      pendingPlans,
      dailyAdvice,
      fallbackSafeUrl: (evaluation && evaluation.fallbackSafeUrl) || null,
      timestampIso: now.toISOString()
    };
  }

  /**
   * Records a blocked distraction attempt WITHOUT counting the user as slipped or resetting their Clean Streak!
   * Because the app/extension stopped the Reel/Short before consumption, the streak stays intact.
   */
  function recordSlipAndUpdateStreak(config, evaluation, now = new Date()) {
    const updated = JSON.parse(JSON.stringify(config || {}));
    if (!updated.streak) {
      updated.streak = {
        currentDays: 1,
        cleanSinceIso: now.toISOString(),
        totalSlipsDetected: 0,
        todaySlipsDetected: 0,
        lastSlipReason: null,
        lastSlipIso: null
      };
    }
    // Preserve currentDays and cleanSinceIso! Being stopped by ZEROFEED protects your streak.
    updated.streak.currentDays = Math.max(1, updated.streak.currentDays || 1);
    updated.streak.totalSlipsDetected = (updated.streak.totalSlipsDetected || 0) + 1;
    updated.streak.todaySlipsDetected = (updated.streak.todaySlipsDetected || 0) + 1;
    updated.streak.lastSlipReason =
      (evaluation && evaluation.reason) || "Intercepted & deflected Reel/Short/Feed attempt";
    updated.streak.lastSlipIso = now.toISOString();
    return updated;
  }

  return {
    evaluateUrl,
    evaluateSchedules,
    evaluateStrictMode,
    getAdviceOfTheDay,
    buildCalloutPayload,
    recordSlipAndUpdateStreak
  };
});
