const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const extDir = path.resolve(__dirname, "..", "browser-extension");

test("11. Browser extension Manifest V3 integrity & required files exist", () => {
  const manifestPath = path.join(extDir, "manifest.json");
  assert.ok(fs.existsSync(manifestPath), "manifest.json must exist");

  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  assert.equal(manifest.manifest_version, 3);
  assert.ok(manifest.permissions.includes("storage"));
  assert.ok(manifest.permissions.includes("webNavigation"));

  const requiredFiles = [
    "background.js",
    "content.js",
    "blocked.html",
    "blocked.js",
    "dashboard.html",
    "dashboard.js",
    "popup.html",
    "popup.js",
    "common/default-rules.json",
    "common/rule-engine.js"
  ];

  for (const rel of requiredFiles) {
    const full = path.join(extDir, rel);
    assert.ok(fs.existsSync(full), `Missing required extension file: ${rel}`);
  }
});

test("13. Browser extension content script hides watch-page suggested videos while preserving playlist panels", () => {
  const contentJs = fs.readFileSync(path.join(extDir, "content.js"), "utf8");
  assert.ok(contentJs.includes("ytd-playlist-panel-renderer"), "content.js must protect ytd-playlist-panel-renderer");
  assert.ok(contentJs.includes("playlistPreserveRules"), "content.js must have explicit playlistPreserveRules");
  assert.ok(contentJs.includes("display: flex !important"), "content.js must enforce playlist visibility with flex !important");
  assert.ok(contentJs.includes("calc(100vh - 72px)"), "content.js must expand playlist and mix panels to full height");
  assert.ok(contentJs.includes("removeShieldBadge"), "content.js must remove intrusive floating shield badge");

  const rules = JSON.parse(fs.readFileSync(path.join(extDir, "common", "default-rules.json"), "utf8"));
  const ytSelectors = rules.platforms.youtube.hideSelectors;
  assert.ok(ytSelectors.includes("#related:not(:has(ytd-playlist-panel-renderer))"));
  assert.ok(ytSelectors.includes("ytd-watch-next-secondary-results-renderer:not(:has(ytd-playlist-panel-renderer))"));
  assert.ok(ytSelectors.includes(".ytp-ce-element"));
});
