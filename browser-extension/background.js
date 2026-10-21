/* global importScripts, ZeroFeedEngine, chrome */
importScripts("common/rule-engine.js");

const STORAGE_KEY = "zerofeed_config_v1";

async function loadDefaultConfig() {
  const url = chrome.runtime.getURL("common/default-rules.json");
  const res = await fetch(url);
  return res.json();
}

async function getConfig() {
  const data = await chrome.storage.local.get([STORAGE_KEY]);
  if (data && data[STORAGE_KEY]) {
    return data[STORAGE_KEY];
  }
  const defaults = await loadDefaultConfig();
  await chrome.storage.local.set({ [STORAGE_KEY]: defaults });
  return defaults;
}

async function saveConfig(cfg) {
  await chrome.storage.local.set({ [STORAGE_KEY]: cfg });
}

function buildBlockedPageUrl(targetUrl, evaluation) {
  const base = chrome.runtime.getURL("blocked.html");
  const params = new URLSearchParams({
    url: targetUrl,
    platformId: evaluation.platformId || "custom",
    platformName: evaluation.platformName || "Blocked Feed",
    violationType: evaluation.violationType || "INFINITE_FEED",
    reason: evaluation.reason || "Blocked by ZEROFEED",
    fallback: evaluation.fallbackSafeUrl || ""
  });
  return `${base}?${params.toString()}`;
}

// Prevent duplicate rapid redirects on the same tab within 1.5s
const recentTabRedirects = new Map();

async function enforceOnTab(tabId, urlString) {
  if (!urlString || urlString.startsWith("chrome") || urlString.startsWith("edge") || urlString.startsWith("about:")) {
    return;
  }
  const blockedBase = chrome.runtime.getURL("blocked.html");
  const dashBase = chrome.runtime.getURL("dashboard.html");
  if (urlString.startsWith(blockedBase) || urlString.startsWith(dashBase)) {
    return;
  }

  const cfg = await getConfig();
  const evaluation = ZeroFeedEngine.evaluateUrl(urlString, cfg);
  if (evaluation.action === "BLOCK") {
    const nowMs = Date.now();
    const lastRedirect = recentTabRedirects.get(tabId) || 0;
    if (nowMs - lastRedirect > 1500) {
      recentTabRedirects.set(tabId, nowMs);
      const updatedCfg = ZeroFeedEngine.recordSlipAndUpdateStreak(cfg, evaluation, new Date(nowMs));
      await saveConfig(updatedCfg);
    }
    const redirectUrl = buildBlockedPageUrl(urlString, evaluation);
    try {
      await chrome.tabs.update(tabId, { url: redirectUrl });
    } catch (_) {
      // Tab may have been closed
    }
  }
}

chrome.runtime.onInstalled.addListener(async () => {
  await getConfig();
  chrome.alarms.create("zerofeed_daily_streak_tick", { periodInMinutes: 60 });
});

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === "zerofeed_daily_streak_tick") {
    const cfg = await getConfig();
    if (cfg.streak && cfg.streak.cleanSinceIso) {
      const cleanSinceMs = Date.parse(cfg.streak.cleanSinceIso);
      if (!Number.isNaN(cleanSinceMs)) {
        const diffDays = Math.floor((Date.now() - cleanSinceMs) / (1000 * 60 * 60 * 24));
        cfg.streak.currentDays = Math.max(0, diffDays);
        await saveConfig(cfg);
      }
    }
  }
});

chrome.webNavigation.onBeforeNavigate.addListener((details) => {
  if (details.frameId !== 0) return;
  enforceOnTab(details.tabId, details.url);
});

chrome.webNavigation.onHistoryStateUpdated.addListener((details) => {
  if (details.frameId !== 0) return;
  enforceOnTab(details.tabId, details.url);
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.url) {
    enforceOnTab(tabId, changeInfo.url);
  }
});

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    if (!msg || !msg.type) return;

    if (msg.type === "GET_CONFIG_AND_STATUS") {
      const cfg = await getConfig();
      const now = new Date();
      const scheduleStatus = ZeroFeedEngine.evaluateSchedules(cfg.schedules, now);
      const strictStatus = ZeroFeedEngine.evaluateStrictMode(cfg, now);
      const dailyAdvice = ZeroFeedEngine.getAdviceOfTheDay(cfg, now, msg.adviceOffset || 0);
      sendResponse({
        config: cfg,
        scheduleStatus,
        strictStatus,
        dailyAdvice
      });
      return;
    }

    if (msg.type === "SAVE_CONFIG") {
      const current = await getConfig();
      const strictStatus = ZeroFeedEngine.evaluateStrictMode(current, new Date());
      if (strictStatus.locked && !msg.allowUnderStrict) {
        sendResponse({
          ok: false,
          error: `Strict Mode is locked: ${strictStatus.reason}`
        });
        return;
      }
      await saveConfig(msg.config);
      sendResponse({ ok: true });
      return;
    }

    if (msg.type === "EVALUATE_URL") {
      const cfg = await getConfig();
      const evaluation = ZeroFeedEngine.evaluateUrl(msg.url, cfg);
      const callout =
        evaluation.action === "BLOCK"
          ? ZeroFeedEngine.buildCalloutPayload(evaluation, cfg, new Date())
          : null;
      sendResponse({ evaluation, callout });
      return;
    }

    if (msg.type === "TRIGGER_BLOCK_INTERCEPT") {
      if (sender && sender.tab && sender.tab.id) {
        await enforceOnTab(sender.tab.id, msg.url);
      }
      sendResponse({ ok: true });
    }
  })();
  return true;
});
