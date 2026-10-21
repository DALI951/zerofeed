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
