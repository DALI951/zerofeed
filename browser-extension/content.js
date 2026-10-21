/* global ZeroFeedEngine, chrome */
(function () {
  "use strict";

  const STYLE_ID = "zerofeed-dom-shield-style";
  const BADGE_ID = "zerofeed-active-shield-badge";
  let cachedConfig = null;
  let lastCheckedHref = "";

  function injectHideSelectors(selectors) {
    if (!Array.isArray(selectors) || selectors.length === 0) return;
    let styleEl = document.getElementById(STYLE_ID);
    if (!styleEl) {
      styleEl = document.createElement("style");
      styleEl.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(styleEl);
    }
    const cssRules = selectors
      .map((sel) => `${sel} { display: none !important; visibility: hidden !important; pointer-events: none !important; }`)
      .join("\n");
    if (styleEl.textContent !== cssRules) {
      styleEl.textContent = cssRules;
    }
  }

  function renderShieldBadge(platformId) {
    if (!document.body || document.getElementById(BADGE_ID)) return;
    if (!["instagram", "youtube", "facebook", "reddit", "twitter"].includes(platformId)) return;

    const badge = document.createElement("div");
    badge.id = BADGE_ID;
    const label =
      platformId === "instagram"
        ? "ZEROFEED: DM-Only Shield Active"
        : platformId === "youtube"
        ? "ZEROFEED: Zero-Shorts Shield Active"
        : "ZEROFEED: Anti-Scroll Shield Active";

    badge.textContent = label;
    badge.style.cssText = [
      "position: fixed",
      "bottom: 14px",
      "right: 14px",
      "z-index: 2147483646",
      "background: rgba(12, 16, 23, 0.92)",
      "color: #38bdf8",
      "border: 1px solid rgba(56, 189, 248, 0.4)",
      "border-radius: 6px",
      "padding: 5px 10px",
      "font-family: system-ui, -apple-system, sans-serif",
      "font-size: 11px",
      "font-weight: 600",
      "letter-spacing: 0.4px",
      "pointer-events: none",
      "box-shadow: 0 4px 12px rgba(0,0,0,0.4)"
    ].join(";");
    document.body.appendChild(badge);
  }

  function scrubDynamicShortsAndReels(platformId) {
    if (platformId === "youtube") {
      // Disable YouTube autoplay toggle if enabled
      const autoplayBtn = document.querySelector(".ytp-autonav-toggle-button[aria-checked='true']");
      if (autoplayBtn) {
        autoplayBtn.click();
      }
      // Hide any anchor linking to /shorts/
      const shortsLinks = document.querySelectorAll("a[href^='/shorts/'], a[href*='youtube.com/shorts/']");
      for (const link of shortsLinks) {
        const container =
          link.closest("ytd-rich-item-renderer, ytd-video-renderer, ytd-compact-video-renderer, ytd-grid-video-renderer, ytm-shorts-lockup-view-model") ||
          link;
        container.style.setProperty("display", "none", "important");
      }
    } else if (platformId === "instagram") {
      // Hide links to Reels, Explore, or Home Feed on Instagram Web so only Direct Messages remain clickable
      const blockedLinks = document.querySelectorAll("a[href='/'], a[href='/explore/'], a[href^='/reels/'], a[href^='/reel/']");
      for (const link of blockedLinks) {
        link.style.setProperty("display", "none", "important");
      }
    }
  }

  function evaluateCurrentLocation() {
    if (!cachedConfig || typeof ZeroFeedEngine === "undefined") return;
    const currentUrl = window.location.href;
    const evaluation = ZeroFeedEngine.evaluateUrl(currentUrl, cachedConfig);

    if (evaluation.action === "BLOCK") {
      if (lastCheckedHref !== currentUrl) {
        lastCheckedHref = currentUrl;
        chrome.runtime.sendMessage({
          type: "TRIGGER_BLOCK_INTERCEPT",
          url: currentUrl
        });
      }
      return;
    }

    if (evaluation.hideSelectors && evaluation.hideSelectors.length > 0) {
      injectHideSelectors(evaluation.hideSelectors);
    }

    if (document.body && evaluation.platformId) {
      renderShieldBadge(evaluation.platformId);
      scrubDynamicShortsAndReels(evaluation.platformId);
    }
  }

  // Intercept clicks on links leading to /shorts/, /reels/, /explore/ before SPA transition completes
  document.addEventListener(
    "click",
    (e) => {
      if (!cachedConfig || typeof ZeroFeedEngine === "undefined") return;
      const anchor = e.target && e.target.closest ? e.target.closest("a[href]") : null;
      if (!anchor) return;
      let targetUrl;
      try {
        targetUrl = new URL(anchor.getAttribute("href"), window.location.origin).href;
      } catch (_) {
        return;
      }
      const evaluation = ZeroFeedEngine.evaluateUrl(targetUrl, cachedConfig);
      if (evaluation.action === "BLOCK") {
        e.preventDefault();
        e.stopPropagation();
        chrome.runtime.sendMessage({
          type: "TRIGGER_BLOCK_INTERCEPT",
          url: targetUrl
        });
      }
    },
    true
  );

  // Initialize configuration and start lightweight observer
  chrome.runtime.sendMessage({ type: "GET_CONFIG_AND_STATUS" }, (response) => {
    if (chrome.runtime.lastError || !response || !response.config) return;
    cachedConfig = response.config;
    evaluateCurrentLocation();

    let throttleTimer = null;
    const observer = new MutationObserver(() => {
      if (window.location.href !== lastCheckedHref) {
        evaluateCurrentLocation();
      }
      if (!throttleTimer) {
        throttleTimer = setTimeout(() => {
          throttleTimer = null;
          evaluateCurrentLocation();
        }, 350);
      }
    });

    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  });
})();
