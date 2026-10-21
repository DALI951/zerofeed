const test = require("node:test");
const assert = require("node:assert/strict");

const defaultRules = require("../common/default-rules.json");
const engine = require("../common/rule-engine.js");

function cloneDefaultRules() {
  return JSON.parse(JSON.stringify(defaultRules));
}

test("1. Blocked Instagram feed, Reels, and Explore URLs", () => {
  const cfg = cloneDefaultRules();
  const homeEval = engine.evaluateUrl("https://www.instagram.com/", cfg);
  assert.equal(homeEval.action, "BLOCK");
  assert.equal(homeEval.platformId, "instagram");

  const reelEval = engine.evaluateUrl("https://www.instagram.com/reels/C9xyz123/", cfg);
  assert.equal(reelEval.action, "BLOCK");
  assert.equal(reelEval.violationType, "SHORT_OR_REEL");

  const exploreEval = engine.evaluateUrl("https://www.instagram.com/explore/", cfg);
  assert.equal(exploreEval.action, "BLOCK");
});

test("2. Allowed Instagram Direct Messaging routes (/direct/inbox and /direct/t/*)", () => {
  const cfg = cloneDefaultRules();
  const inboxEval = engine.evaluateUrl("https://www.instagram.com/direct/inbox/", cfg);
  assert.equal(inboxEval.action, "ALLOW");
  assert.equal(inboxEval.platformId, "instagram");
  assert.ok(Array.isArray(inboxEval.hideSelectors));
  assert.ok(inboxEval.hideSelectors.includes("a[href='/explore/']"));

  const threadEval = engine.evaluateUrl("https://www.instagram.com/direct/t/340282366841710300949128/", cfg);
  assert.equal(threadEval.action, "ALLOW");
});

test("3. Blocked YouTube Shorts URLs (desktop and mobile)", () => {
  const cfg = cloneDefaultRules();
  const shortDesktop = engine.evaluateUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ", cfg);
  assert.equal(shortDesktop.action, "BLOCK");
  assert.equal(shortDesktop.platformId, "youtube");
  assert.equal(shortDesktop.violationType, "SHORT_OR_REEL");

  const shortMobile = engine.evaluateUrl("https://m.youtube.com/shorts/abc123xyz", cfg);
  assert.equal(shortMobile.action, "BLOCK");
  assert.equal(shortMobile.violationType, "SHORT_OR_REEL");
});

test("4. Allowed educational YouTube video and search query with Shorts DOM selectors hidden", () => {
  const cfg = cloneDefaultRules();
  const watchEval = engine.evaluateUrl("https://www.youtube.com/watch?v=8hly31xKli0", cfg);
  assert.equal(watchEval.action, "ALLOW");
  assert.equal(watchEval.platformId, "youtube");
  assert.ok(watchEval.hideSelectors.includes("ytd-reel-shelf-renderer"));

  const searchEval = engine.evaluateUrl(
    "https://www.youtube.com/results?search_query=dynamic+programming+lecture+mit",
    cfg
  );
  assert.equal(searchEval.action, "ALLOW");
  assert.equal(searchEval.platformId, "youtube");
});

test("5. Blocked TikTok page across all subdomains", () => {
  const cfg = cloneDefaultRules();
  const tkMain = engine.evaluateUrl("https://www.tiktok.com/@user/video/123456", cfg);
  assert.equal(tkMain.action, "BLOCK");
  assert.equal(tkMain.platformId, "tiktok");
  assert.equal(tkMain.violationType, "SHORT_OR_REEL");

  const tkShortLink = engine.evaluateUrl("https://vm.tiktok.com/ZM12345/", cfg);
  assert.equal(tkShortLink.action, "BLOCK");
});

test("6. Allowed school (.edu / .ac.uk) and competitive programming websites", () => {
  const cfg = cloneDefaultRules();
  const mitEval = engine.evaluateUrl("https://ocw.mit.edu/courses/6-006-introduction-to-algorithms/", cfg);
  assert.equal(mitEval.action, "ALLOW");

  const universityEval = engine.evaluateUrl("https://cs.ox.ac.uk/teaching/", cfg);
  assert.equal(universityEval.action, "ALLOW");

  const codeforcesEval = engine.evaluateUrl("https://codeforces.com/problemset/problem/4/A", cfg);
  assert.equal(codeforcesEval.action, "ALLOW");
});

test("7. Schedule activation and expiration across time boundaries", () => {
  const cfg = cloneDefaultRules();
  const activeTime = new Date("2026-10-21T09:30:00");
  const activeStatus = engine.evaluateSchedules(cfg.schedules, activeTime);
  assert.equal(activeStatus.active, true);
  assert.equal(activeStatus.currentEndTime, "13:00");

  const inactiveTime = new Date("2026-10-21T15:00:00");
  const inactiveStatus = engine.evaluateSchedules(cfg.schedules, inactiveTime);
  assert.equal(inactiveStatus.active, false);
  assert.ok(inactiveStatus.nextSchedule);
  assert.equal(inactiveStatus.nextSchedule.startTime, "18:00");
});

test("8. Rules survive serialization / application restart and enforce Strict Mode lock", () => {
  const cfg = cloneDefaultRules();
  const futureIso = new Date(Date.now() + 90 * 60 * 1000).toISOString();
  cfg.strictMode.lockedUntilIso = futureIso;
  cfg.customBlockedDomains.push("9gag.com");

  const serialized = JSON.stringify(cfg);
  const restored = JSON.parse(serialized);

  const strictCheck = engine.evaluateStrictMode(restored, new Date());
  assert.equal(strictCheck.locked, true);
  assert.ok(strictCheck.remainingMinutes >= 89);

  const customEval = engine.evaluateUrl("https://9gag.com/gag/123", restored);
  assert.equal(customEval.action, "BLOCK");
});

test("9. Hard-Truth Callout detects Reel/Short opening, shames compulsive act, reminds to read a book & solve problems, and PROTECTS streak (does not count user as slipped)", () => {
  const cfg = cloneDefaultRules();
  cfg.streak.currentDays = 5;
  const shortEval = engine.evaluateUrl("https://www.youtube.com/shorts/xyz987", cfg);
  assert.equal(shortEval.action, "BLOCK");

  const callout = engine.buildCalloutPayload(shortEval, cfg, new Date("2026-10-21T10:01:00Z"));
  assert.equal(callout.violationType, "SHORT_OR_REEL");
  assert.match(callout.headline, /STREAK PROTECTED/);
  assert.match(callout.bookReminder, /book/i);
  assert.match(callout.problemSolvingReminder, /problem/i);
  assert.equal(callout.pendingPlans.length, 3);

  const updatedCfg = engine.recordSlipAndUpdateStreak(cfg, shortEval, new Date("2026-10-21T10:01:00Z"));
  assert.equal(updatedCfg.streak.currentDays, 5); // Streak stays intact!
  assert.equal(updatedCfg.streak.totalSlipsDetected, 1);
  assert.equal(updatedCfg.streak.todaySlipsDetected, 1);
});

test("10. Advice of the Day rotates deterministically and returns actionable advice", () => {
  const cfg = cloneDefaultRules();
  const day1 = engine.getAdviceOfTheDay(cfg, new Date("2026-10-21T12:00:00Z"));
  const day2 = engine.getAdviceOfTheDay(cfg, new Date("2026-10-22T12:00:00Z"));
  assert.ok(day1.advice.length > 20);
  assert.notEqual(day1.index, day2.index);
});
