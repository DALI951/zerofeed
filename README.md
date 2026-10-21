# ZEROFEED — Cross-Device Anti-Scrolling & Deep Work System

**ZEROFEED** is a local-first, cross-device anti-scrolling system engineered for:
- **Samsung Galaxy S23 Ultra (Android 16 / One UI)** (`android-app/`)
- **Windows Desktop PC & Low-RAM (4 GB) Windows Laptop** (`windows-app/`)
- **Google Chrome & Microsoft Edge (Manifest V3 Browser Extension)** (`browser-extension/`)

It eliminates compulsive short-form video loops and algorithmic feeds (**Instagram Reels/Explore/Home Feed, YouTube Shorts/Home Feed, TikTok, Facebook Reels, Reddit Popular, X For-You, Snapchat Spotlight**) while preserving legitimate communication (**Instagram Direct Messages `/direct/inbox/`**), educational YouTube search and lectures, academic research (`*.edu`), and competitive programming (**Codeforces, LeetCode, GitHub**).

---

## Key Features

### 1. Home Dashboard: Streak, Daily Plans & Advice of the Day
Every ZEROFEED surface (Browser Extension Dashboard, Windows Companion, and Android App) opens to a unified Home Dashboard answering:
- **Are my restrictions active?** Real-time status of URL, DOM, and app-level shields.
- **Clean Focus Streak & Slip Detection**: Tracks consecutive days clean from Reels/Shorts/Feeds. Any attempt to open a Reel, Short, or blocked feed is immediately intercepted, increments the slip counter, and resets the clean streak to `0`.
- **Advice of the Day**: Deterministic daily engineering & focus wisdom, with a button to cycle additional advice.
- **Daily Action Plans**: Built-in daily checklist reminding you to **read 25 pages of a book**, **solve 3 algorithmic/coding problems**, and complete deep-work blocks.
- **Active Session & Schedules**: Shows when the current session ends, the next scheduled block (Morning `08:00–13:00`, Evening `18:00–23:30`), and Strict Mode timer locks.

### 2. Hard-Truth Accountability Callout (Reel / Short Attempt Detection)
Whenever you attempt to open a YouTube Short, Instagram Reel, TikTok, or infinite feed (via direct link, in-page click, SPA navigation, or blocked Android app), ZEROFEED intercepts the navigation and displays the **Hard-Truth Accountability Callout Screen**:
- Calls out the compulsive impulse directly.
- Reminds you to **pick up your book** and **solve coding/algorithmic problems** instead.
- Displays your unfinished **Daily Plans** and **Advice of the Day**.
- Provides one-click rescue buttons to open **Instagram Direct Messages Only (`/direct/inbox/`)** or **Codeforces / LeetCode**.
- Configurable between **Hard-Truth Callout Mode** (default) and **Calm Neutral Mode**.

### 3. Instagram: Direct Messages Without the Feed
- **Browser Extension & Android IG Direct Mode**: Allows `https://www.instagram.com/direct/inbox/` and `/direct/t/*` conversations while blocking `/`, `/reels/*`, `/reel/*`, and `/explore/*` at the URL router level and stripping feed/Reels/Explore navigation icons from the DOM.
- **Why not a custom API client?** Meta's Instagram Messaging API is restricted to Business/Creator accounts tied to a Facebook Page; scraping private APIs or stealing session cookies violates terms and risks account bans. Using official Instagram Web DMs inside a route-locked browser/WebView preserves full messaging and media functionality safely.

### 4. YouTube Without Shorts
- **Browser Extension & Android YouTube Study Mode**: Blocks `youtube.com/shorts/*` (including SPA transitions), hides Shorts navigation buttons, Shorts shelves (`ytd-reel-shelf-renderer`, `ytm-reel-shelf-renderer`), and algorithmic home recommendations, disables autoplay, and preserves educational search (`youtube.com/results?search_query=*`) and lecture playback (`youtube.com/watch?v=*`).

---

## Repository Structure

```text
zerofeed/
├── ARCHITECTURE.md                  # Technical audit, feasibility analysis & platform trade-offs
├── common/
│   ├── default-rules.json           # Shared cross-device rule, schedule, plan & advice schema
│   └── rule-engine.js               # Universal URL, schedule, Strict Mode & callout evaluator
├── browser-extension/               # Chrome & Microsoft Edge Manifest V3 Extension
│   ├── manifest.json
│   ├── background.js                # Navigation & SPA history interceptor + streak state
│   ├── content.js                   # DOM element stripper & link-click guard
│   ├── blocked.html / blocked.js    # Hard-Truth Accountability Callout screen
│   ├── dashboard.html / dashboard.js# Full Home Dashboard (Streak, Plans, Advice, Rules, Tester)
│   └── popup.html / popup.js        # Toolbar quick status popup
├── windows-app/                     # Lightweight Native Windows Companion (<20 MB RAM)
│   ├── ZeroFeed.Windows/Program.cs  # WinForms GUI + CLI (--verify / --evaluate) + Registry Policy helper
│   └── dist/ZeroFeedWindows.exe     # Compiled standalone Windows executable
├── android-app/                     # Native Android App (Kotlin + Jetpack Compose, Android 16 / One UI)
│   └── app/src/main/java/com/zerofeed/android/
│       ├── MainActivity.kt          # Compose Home Dashboard, IG Direct WebView, YT Study WebView
│       ├── ZeroFeedRuleEngine.kt    # Kotlin URL/package/schedule evaluator
│       ├── ZeroFeedRepository.kt    # SharedPreferences persistence
│       ├── ZeroFeedAccessibilityService.kt # Foreground app & Shorts/Reels guard
│       └── BootRecoveryReceiver.kt  # Reboot persistence receiver
├── tests/                           # Automated Node.js & Windows binary verification tests
└── .github/workflows/ci.yml         # GitHub Actions pipeline building Extension, Windows .exe & Android APK
```

---

## Build, Test & Installation Instructions

### 1. Run Automated Cross-Device Tests
```powershell
npm run sync:extension
npm test
```

### 2. Install Chrome / Microsoft Edge Browser Extension
1. Run `npm run sync:extension`.
2. Open Chrome (`chrome://extensions`) or Microsoft Edge (`edge://extensions`).
3. Enable **Developer mode**.
4. Click **Load unpacked** and select the `browser-extension` folder.
5. To prevent impulsive disabling in Edge/Chrome, click **"Enforce Chrome/Edge Policies"** in `ZeroFeedWindows.exe`.

### 3. Build & Run Windows Companion (Desktop PC & 4 GB RAM Laptop)
```powershell
npm run build:windows
.\windows-app\dist\ZeroFeedWindows.exe --verify
.\windows-app\dist\ZeroFeedWindows.exe
```
- Uses native Win32/WinForms with **~15–25 MB RAM** and **<1% CPU**, ideal for a 4 GB RAM laptop.

### 4. Build & Install Android App (Samsung Galaxy S23 Ultra — Android 16 / One UI)
- **Via GitHub Actions Artifact**: Download `zerofeed-android-apk` (`app-debug.apk`) from the **Actions** tab of the GitHub repository, or build locally:
```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```
- **Samsung One UI 7 / Android 16 Sideloaded Accessibility Setup**:
  1. Install `app-debug.apk` on your Galaxy S23 Ultra.
  2. Open **Settings → Apps → ZEROFEED → ⋮ (top-right menu) → Allow restricted settings** (required by Android 13–16 for sideloaded Accessibility services).
  3. Open **Settings → Accessibility → Installed apps → ZEROFEED Anti-Scrolling Guard → Turn On**.

---

## Honest Platform Limitations & Bypass Transparency
- **Administrator Self-Control**: A user who owns Administrator rights on Windows or physical device settings on Android can ultimately uninstall software or clear policies. ZEROFEED adds deliberate friction (Strict Mode timer locks, Chrome/Edge Registry policies, and immediate Hard-Truth Callout screens) to stop impulsive bypassing without breaking OS recovery or locking you out of emergency calls/SMS/authenticators.
- **DNS vs. Path Filtering**: The Windows `hosts` file or DNS blockers can only block whole domains (`tiktok.com`), not paths (`/reels/` vs `/direct/inbox/`). ZEROFEED uses browser-level URL/DOM filtering and Chrome/Edge `URLBlocklist`/`URLAllowlist` policies for path-level precision.
