const test = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const exePath = path.resolve(__dirname, "..", "windows-app", "dist", "ZeroFeedWindows.exe");

test("12. Native Windows Companion executable (--verify and --evaluate)", (t) => {
  if (process.platform !== "win32" || !fs.existsSync(exePath)) {
    t.skip("Skipping native Windows binary execution on non-Windows host");
    return;
  }

  const verifyOut = execFileSync(exePath, ["--verify"], { encoding: "utf8" });
  assert.match(verifyOut, /PASS: ZeroFeedWindows\.exe native verification succeeded/);

  const shortsJson = JSON.parse(
    execFileSync(exePath, ["--evaluate", "https://www.youtube.com/shorts/dQw4w9WgXcQ"], { encoding: "utf8" })
  );
  assert.equal(shortsJson.action, "BLOCK");
  assert.equal(shortsJson.platformId, "youtube");
  assert.equal(shortsJson.violationType, "SHORT_OR_REEL");

  const igDmJson = JSON.parse(
    execFileSync(exePath, ["--evaluate", "https://www.instagram.com/direct/inbox/"], { encoding: "utf8" })
  );
  assert.equal(igDmJson.action, "ALLOW");
  assert.equal(igDmJson.platformId, "instagram");
});
