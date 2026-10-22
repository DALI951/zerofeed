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
    const hideRules = selectors
      .map((sel) => `${sel} { display: none !important; visibility: hidden !important; pointer-events: none !important; }`)
      .join("\n");

    const playlistPreserveRules = `
/* Explicit ZEROFEED protection: Never hide playlist panels or playlist queue items */
/* Expand Playlist & Mix panels to the whole available length since there is no feed video */
ytd-playlist-panel-renderer,
#playlist,
#playlist.ytd-watch-flexy,
#playlist.ytd-watch-grid {
  display: flex !important;
  visibility: visible !important;
  opacity: 1 !important;
  pointer-events: auto !important;
  max-height: none !important;
  height: calc(100vh - 72px) !important;
  min-height: calc(100vh - 100px) !important;
  box-sizing: border-box !important;
}

ytd-playlist-panel-renderer #container,
ytd-playlist-panel-renderer #container.ytd-playlist-panel-renderer {
  max-height: none !important;
  height: 100% !important;
  display: flex !important;
  flex-direction: column !important;
}

ytd-playlist-panel-renderer #items,
ytd-playlist-panel-renderer #items.ytd-playlist-panel-renderer {
  max-height: calc(100vh - 160px) !important;
  height: 100% !important;
  flex: 1 1 auto !important;
  overflow-y: auto !important;
}

#secondary-inner.ytd-watch-flexy,
#secondary.ytd-watch-flexy {
  position: sticky !important;
  top: 64px !important;
  height: calc(100vh - 72px) !important;
}

ytd-playlist-panel-renderer[collapsed] #items {
  display: block !important;
}

ytm-playlist-panel-renderer,
ytm-playlist,
ytm-playlist-panel-video-renderer {
  display: flex !important;
  visibility: visible !important;
  opacity: 1 !important;
  pointer-events: auto !important;
  max-height: none !important;
  height: auto !important;
}

ytd-playlist-panel-video-renderer #primary-header,
ytd-playlist-panel-video-renderer #meta,
ytd-playlist-panel-video-renderer #thumbnail {
  visibility: visible !important;
  display: flex !important;
}

/* ZEROFEED Badge removed so it does not distract while watching */
#zerofeed-active-shield-badge {
  display: none !important;
  visibility: hidden !important;
  opacity: 0 !important;
  pointer-events: none !important;
}
`;

    const fullRules = `${hideRules}\n${playlistPreserveRules}`;
    if (styleEl.textContent !== fullRules) {
      styleEl.textContent = fullRules;
    }
  }

  function removeShieldBadge() {
    const badge = document.getElementById(BADGE_ID);
    if (badge) {
      badge.remove();
    }
  }

  // Ensure shield badge is never shown on screen
  removeShieldBadge();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", removeShieldBadge);
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

      // Hide suggested/related videos when watching a video while strictly preserving playlists
      if (window.location.pathname.includes("/watch") || window.location.href.includes("watch?v=")) {
        // 1. Keep playlist & mix panel expanded to full length since there are no feed videos
        const playlistPanels = document.querySelectorAll("ytd-playlist-panel-renderer, #playlist, ytm-playlist-panel-renderer");
        for (const panel of playlistPanels) {
          panel.style.setProperty("display", "flex", "important");
          panel.style.setProperty("visibility", "visible", "important");
          panel.style.setProperty("opacity", "1", "important");
          panel.style.setProperty("pointer-events", "auto", "important");
          panel.style.setProperty("max-height", "none", "important");
          panel.style.setProperty("height", "calc(100vh - 72px)", "important");

          const container = panel.querySelector("#container");
          if (container) {
            container.style.setProperty("max-height", "none", "important");
            container.style.setProperty("height", "100%", "important");
          }

          const items = panel.querySelector("#items");
          if (items) {
            items.style.setProperty("max-height", "calc(100vh - 160px)", "important");
            items.style.setProperty("height", "100%", "important");
            items.style.setProperty("overflow-y", "auto", "important");
          }
        }

        // 2. Hide suggested/related videos container if it doesn't contain a playlist
        const relatedEl = document.getElementById("related");
        if (relatedEl && !relatedEl.querySelector("ytd-playlist-panel-renderer, #playlist")) {
          relatedEl.style.setProperty("display", "none", "important");
          relatedEl.style.setProperty("visibility", "hidden", "important");
        }

        const watchNextSecondary = document.querySelector("ytd-watch-next-secondary-results-renderer");
        if (watchNextSecondary && !watchNextSecondary.querySelector("ytd-playlist-panel-renderer, #playlist")) {
          watchNextSecondary.style.setProperty("display", "none", "important");
          watchNextSecondary.style.setProperty("visibility", "hidden", "important");
        }

        // 3. Hide algorithmic recommendations injected inside playlist panel
        const playlistRecs = document.querySelectorAll(
          "ytd-playlist-panel-renderer ytd-playlist-panel-video-renderer:has([style*='playlist-video-renderer-style-recommended-video'])"
        );
        for (const rec of playlistRecs) {
          rec.style.setProperty("display", "none", "important");
        }
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
      removeShieldBadge();
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
