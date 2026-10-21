/* global ZeroFeedEngine, chrome */
(function () {
  "use strict";

  const params = new URLSearchParams(window.location.search);
  const blockedUrl = params.get("url") || "";
  const platformName = params.get("platformName") || "Distracting Feed";
  const violationType = params.get("violationType") || "INFINITE_FEED";
  const fallbackUrl = params.get("fallback") || "";

  document.getElementById("blockedUrlBox").textContent = `Intercepted URL: ${blockedUrl}`;

  if (fallbackUrl) {
    const safeBtn = document.getElementById("fallbackSafeBtn");
    safeBtn.style.display = "inline-flex";
    safeBtn.href = fallbackUrl;
    safeBtn.textContent = blockedUrl.includes("instagram.com")
      ? "Open Instagram Direct Messages Only (/direct/inbox/)"
      : "Open Allowed Messaging Route Only";
  }

  document.getElementById("openDashboardBtn").addEventListener("click", () => {
    window.location.href = chrome.runtime.getURL("dashboard.html");
  });

  chrome.runtime.sendMessage({ type: "GET_CONFIG_AND_STATUS" }, (res) => {
    if (!res || !res.config) return;
    const cfg = res.config;
    const callout = ZeroFeedEngine.buildCalloutPayload(
      {
        platformName,
        violationType,
        fallbackSafeUrl: fallbackUrl
      },
      cfg,
      new Date()
    );

    document.getElementById("headlineBadge").textContent = callout.headline;
    document.getElementById("mainTitle").textContent =
      violationType === "SHORT_OR_REEL"
        ? `Caught Opening a Short / Reel on ${platformName}`
        : `Caught Opening a Blocked Feed on ${platformName}`;
    document.getElementById("shameMessage").textContent = callout.shameCallout;
    document.getElementById("bookReminder").textContent = callout.bookReminder;
    document.getElementById("problemReminder").textContent = callout.problemSolvingReminder;
    document.getElementById("adviceBanner").innerHTML = `<strong>Advice of the Day:</strong> ${callout.dailyAdvice}`;

    const streakDays = (cfg.streak && cfg.streak.currentDays) || 1;
    const deflectedToday = (cfg.streak && cfg.streak.todaySlipsDetected) || 1;
    document.getElementById("slipCounter").textContent =
      `Streak Intact: ${streakDays} Day(s) Clean | Distractions Blocked Today: ${deflectedToday}`;

    const listEl = document.getElementById("pendingPlansList");
    listEl.innerHTML = "";
    const plans = (cfg.dailyPlans || []);
    if (plans.length === 0) {
      listEl.textContent = "Read 25 pages of a book and solve 3 coding problems.";
    } else {
      for (const plan of plans) {
        const row = document.createElement("div");
        row.className = "plan-item";
        row.textContent = `${plan.completed ? "[DONE]" : "[TODO]"} ${plan.title}`;
        listEl.appendChild(row);
      }
    }
  });
})();
