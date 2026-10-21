/* global chrome */
(function () {
  "use strict";

  document.getElementById("openFullDashBtn").addEventListener("click", () => {
    chrome.tabs.create({ url: chrome.runtime.getURL("dashboard.html") });
  });

  chrome.runtime.sendMessage({ type: "GET_CONFIG_AND_STATUS" }, (res) => {
    if (!res || !res.config) return;
    const cfg = res.config;
    const days = (cfg.streak && cfg.streak.currentDays) || 0;
    const slips = (cfg.streak && cfg.streak.todaySlipsDetected) || 0;

    document.getElementById("popupStreak").textContent = `${days} ${days === 1 ? "Day" : "Days"} Clean`;
    document.getElementById("popupSlips").textContent = `${slips} Reel/Short slips detected today`;
    if (res.dailyAdvice && res.dailyAdvice.advice) {
      document.getElementById("popupAdvice").textContent = res.dailyAdvice.advice;
    }
  });
})();
