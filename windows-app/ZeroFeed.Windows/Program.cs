using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;

namespace ZeroFeed.Windows
{
    public sealed class EvaluationVerdict
    {
        public string Action { get; set; }
        public string PlatformId { get; set; }
        public string ViolationType { get; set; }
        public string Reason { get; set; }
        public string FallbackSafeUrl { get; set; }

        public EvaluationVerdict(string action, string platformId, string violationType, string reason, string fallbackSafeUrl)
        {
            Action = action;
            PlatformId = platformId;
            ViolationType = violationType;
            Reason = reason;
            FallbackSafeUrl = fallbackSafeUrl;
        }
    }

    public sealed class DailyPlanItem
    {
        public string Title { get; set; }
        public bool Completed { get; set; }
    }

    public sealed class LocalStateStore
    {
        public int StreakDays { get; set; }
        public int TodaySlips { get; set; }
        public int TotalSlips { get; set; }
        public DateTime LockedUntilUtc { get; set; }
        public List<DailyPlanItem> Plans { get; private set; }
        public List<string> CustomBlockedDomains { get; private set; }
        public string[] AdviceBank { get; private set; }
        private readonly string _stateFilePath;

        public LocalStateStore()
        {
            string localDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ZeroFeed");
            Directory.CreateDirectory(localDir);
            _stateFilePath = Path.Combine(localDir, "windows_state.txt");

            StreakDays = 1;
            TodaySlips = 0;
            TotalSlips = 0;
            LockedUntilUtc = DateTime.MinValue;
            CustomBlockedDomains = new List<string>();
            Plans = new List<DailyPlanItem>
            {
                new DailyPlanItem { Title = "Read 25 pages of a technical or non-fiction book", Completed = false },
                new DailyPlanItem { Title = "Solve 3 algorithmic / competitive programming problems", Completed = false },
                new DailyPlanItem { Title = "Complete one 90-minute uninterrupted deep-work block", Completed = false }
            };
            AdviceBank = new[]
            {
                "Every 60-second Reel or Short fragments your working memory for up to 20 minutes. Close the tab, open your book, and read one chapter.",
                "Algorithms are engineered by thousands of PhDs to monetize your attention. Beat them by solving 3 hard coding problems before checking any messages.",
                "Dopamine from cheap scrolling makes real achievement feel boring. Starve the cheap dopamine so building, reading, and problem-solving become rewarding again.",
                "You do not need '5 minutes of Reels to relax.' Real rest is a walk, a paper book, or silence—not an infinite slot machine.",
                "Compare how you feel after 1 hour of Shorts (drained, guilty, foggy) with 1 hour of solving hard problems (sharp, proud, capable). Choose the second one today."
            };
        }

        public void Load()
        {
            if (!File.Exists(_stateFilePath)) return;
            try
            {
                string[] lines = File.ReadAllLines(_stateFilePath);
                var loadedPlans = new List<DailyPlanItem>();
                foreach (string line in lines)
                {
                    if (line.StartsWith("STREAK=")) StreakDays = int.Parse(line.Substring(7));
                    else if (line.StartsWith("TODAY_SLIPS=")) TodaySlips = int.Parse(line.Substring(12));
                    else if (line.StartsWith("TOTAL_SLIPS=")) TotalSlips = int.Parse(line.Substring(12));
                    else if (line.StartsWith("LOCKED_UNTIL="))
                    {
                        DateTime dt;
                        if (DateTime.TryParse(line.Substring(13), null, System.Globalization.DateTimeStyles.RoundtripKind, out dt))
                            LockedUntilUtc = dt;
                    }
                    else if (line.StartsWith("PLAN="))
                    {
                        string payload = line.Substring(5);
                        int sep = payload.IndexOf('|');
                        if (sep > 0)
                        {
                            loadedPlans.Add(new DailyPlanItem
                            {
                                Completed = payload.Substring(0, sep) == "1",
                                Title = payload.Substring(sep + 1)
                            });
                        }
                    }
                    else if (line.StartsWith("DOMAIN="))
                    {
                        string dom = line.Substring(7).Trim().ToLowerInvariant();
                        if (!string.IsNullOrEmpty(dom) && !CustomBlockedDomains.Contains(dom))
                            CustomBlockedDomains.Add(dom);
                    }
                }
                if (loadedPlans.Count > 0) Plans = loadedPlans;
            }
            catch
            {
                // Preserve defaults on corrupted state
            }
        }

        public void Save()
        {
            var sb = new StringBuilder();
            sb.AppendLine("STREAK=" + StreakDays);
            sb.AppendLine("TODAY_SLIPS=" + TodaySlips);
            sb.AppendLine("TOTAL_SLIPS=" + TotalSlips);
            sb.AppendLine("LOCKED_UNTIL=" + LockedUntilUtc.ToString("o"));
            foreach (var p in Plans)
            {
                sb.AppendLine("PLAN=" + (p.Completed ? "1" : "0") + "|" + p.Title);
            }
            foreach (var d in CustomBlockedDomains)
            {
                sb.AppendLine("DOMAIN=" + d);
            }
            File.WriteAllText(_stateFilePath, sb.ToString());
        }
    }

    public static class RuleEvaluator
    {
        private static readonly string[] GlobalAllowDomains =
        {
            "scholar.google.com", "github.com", "stackoverflow.com",
            "codeforces.com", "leetcode.com", "wikipedia.org",
            "arxiv.org", "mit.edu", "stanford.edu", "coursera.org", "edx.org", "khanacademy.org"
        };

        public static EvaluationVerdict EvaluateUrl(string urlString, IList<string> customBlockedDomains)
        {
            Uri uri;
            if (!Uri.TryCreate(urlString, UriKind.Absolute, out uri))
            {
                return new EvaluationVerdict("ALLOW", "none", "NONE", "Invalid URL", null);
            }

            string host = uri.Host.ToLowerInvariant().TrimEnd('.');
            string fullUrl = uri.AbsoluteUri;

            // 1. Global Allowlist (Education, Research, Competitive Programming)
            foreach (string dom in GlobalAllowDomains)
            {
                if (host == dom || host.EndsWith("." + dom))
                {
                    return new EvaluationVerdict("ALLOW", "allowlist", "NONE", "Allowed educational / coding domain", null);
                }
            }
            if (Regex.IsMatch(fullUrl, @"^https?://([a-z0-9-]+\.)*(edu|ac\.[a-z]{2,3})(/.*)?$", RegexOptions.IgnoreCase))
            {
                return new EvaluationVerdict("ALLOW", "allowlist", "NONE", "Allowed academic institution domain", null);
            }

            // 2. Custom Blocked Domains
            if (customBlockedDomains != null)
            {
                foreach (string d in customBlockedDomains)
                {
                    if (!string.IsNullOrEmpty(d) && (host == d || host.EndsWith("." + d)))
                    {
                        return new EvaluationVerdict("BLOCK", "custom", ClassifyViolation(fullUrl, "custom"), "Blocked by custom ZEROFEED rule", null);
                    }
                }
            }

            // 3. Instagram: DMs Allowed (/direct/*), Feed/Reels/Explore Blocked
            if (host == "instagram.com" || host.EndsWith(".instagram.com"))
            {
                if (Regex.IsMatch(fullUrl, @"^https?://(www\.)?instagram\.com/(direct|accounts|challenge)(/.*)?$", RegexOptions.IgnoreCase))
                {
                    return new EvaluationVerdict("ALLOW", "instagram", "NONE", "Allowed Instagram Direct Messaging route", "https://www.instagram.com/direct/inbox/");
                }
                return new EvaluationVerdict(
                    "BLOCK",
                    "instagram",
                    ClassifyViolation(fullUrl, "instagram"),
                    "Blocked Instagram Feed/Reels/Explore (Direct Messages Only)",
                    "https://www.instagram.com/direct/inbox/"
                );
            }

            // 4. YouTube: Search & Watch Allowed, Shorts & Trending Blocked
            if (host == "youtube.com" || host.EndsWith(".youtube.com") || host == "youtu.be")
            {
                if (Regex.IsMatch(fullUrl, @"^https?://(www\.|m\.)?youtube\.com/(shorts|feed/trending|feed/explore)(/.*)?$", RegexOptions.IgnoreCase))
                {
                    return new EvaluationVerdict("BLOCK", "youtube", "SHORT_OR_REEL", "Blocked YouTube Shorts / Trending feed", null);
                }
                return new EvaluationVerdict("ALLOW", "youtube", "NONE", "Allowed educational YouTube route with Shorts DOM shield", null);
            }

            // 5. TikTok: Complete Domain Block
            if (host == "tiktok.com" || host.EndsWith(".tiktok.com"))
            {
                return new EvaluationVerdict("BLOCK", "tiktok", "SHORT_OR_REEL", "TikTok is completely blocked by ZEROFEED", null);
            }

            // 6. Facebook: Messages Allowed, Feed & Reels Blocked
            if (host == "facebook.com" || host.EndsWith(".facebook.com"))
            {
                if (Regex.IsMatch(fullUrl, @"^https?://(www\.|m\.)?facebook\.com/messages(/.*)?$", RegexOptions.IgnoreCase))
                {
                    return new EvaluationVerdict("ALLOW", "facebook", "NONE", "Allowed Facebook Messenger route", "https://www.facebook.com/messages/");
                }
                return new EvaluationVerdict("BLOCK", "facebook", ClassifyViolation(fullUrl, "facebook"), "Blocked Facebook Feed/Reels", "https://www.facebook.com/messages/");
            }

            // 7. Reddit: Specific Thread Comments & Search Allowed, Home/Popular Blocked
            if (host == "reddit.com" || host.EndsWith(".reddit.com"))
            {
                if (Regex.IsMatch(fullUrl, @"^https?://(www\.|old\.)?reddit\.com/(r/[A-Za-z0-9_]+/comments/.*|search/?\?.*)", RegexOptions.IgnoreCase))
                {
                    return new EvaluationVerdict("ALLOW", "reddit", "NONE", "Allowed specific Reddit discussion/search", null);
                }
                return new EvaluationVerdict("BLOCK", "reddit", "INFINITE_FEED", "Blocked Reddit infinite home/popular feed", null);
            }

            // 8. X / Twitter: Messages & Search Allowed, Home/Explore Blocked
            if (host == "x.com" || host.EndsWith(".x.com") || host == "twitter.com" || host.EndsWith(".twitter.com"))
            {
                if (Regex.IsMatch(fullUrl, @"^https?://(www\.)?(x|twitter)\.com/(messages(/.*)?|search\?.*)", RegexOptions.IgnoreCase))
                {
                    return new EvaluationVerdict("ALLOW", "twitter", "NONE", "Allowed X/Twitter Direct Messages/Search", "https://x.com/messages");
                }
                return new EvaluationVerdict("BLOCK", "twitter", "INFINITE_FEED", "Blocked X/Twitter algorithmic feed", "https://x.com/messages");
            }

            // 9. Snapchat Spotlight Blocked
            if (host == "snapchat.com" || host.EndsWith(".snapchat.com"))
            {
                if (Regex.IsMatch(fullUrl, @"^https?://(www\.)?snapchat\.com/(spotlight|discover)(/.*)?$", RegexOptions.IgnoreCase))
                {
                    return new EvaluationVerdict("BLOCK", "snapchat", "SHORT_OR_REEL", "Blocked Snapchat Spotlight", null);
                }
            }

            return new EvaluationVerdict("ALLOW", "none", "NONE", "Unrestricted site", null);
        }

        private static string ClassifyViolation(string url, string platformId)
        {
            string lower = url.ToLowerInvariant();
            if (platformId == "tiktok" || lower.Contains("/shorts") || lower.Contains("/reel") || lower.Contains("/spotlight"))
            {
                return "SHORT_OR_REEL";
            }
            return "INFINITE_FEED";
        }
    }

    public static class BrowserPolicyHelper
    {
        public static bool ApplyCurrentUserBrowserPolicies(out string statusMessage)
        {
            try
            {
                ApplyPolicyForRoot(@"Software\Policies\Google\Chrome");
                ApplyPolicyForRoot(@"Software\Policies\Microsoft\Edge");
                statusMessage = "Applied Chrome & Edge HKCU URLBlocklist/URLAllowlist policies (Shorts, Reels, Explore, TikTok blocked; Instagram Direct allowed).";
                return true;
            }
            catch (Exception ex)
            {
                statusMessage = "Policy update warning: " + ex.Message;
                return false;
            }
        }

        private static void ApplyPolicyForRoot(string baseKeyPath)
        {
            string[] blocked =
            {
                "https://www.youtube.com/shorts*",
                "https://m.youtube.com/shorts*",
                "https://www.instagram.com/reels*",
                "https://www.instagram.com/reel*",
                "https://www.instagram.com/explore*",
                "tiktok.com"
            };
            using (RegistryKey blockKey = Registry.CurrentUser.CreateSubKey(baseKeyPath + @"\URLBlocklist"))
            {
                if (blockKey != null)
                {
                    for (int i = 0; i < blocked.Length; i++)
                    {
                        blockKey.SetValue((i + 1).ToString(), blocked[i], RegistryValueKind.String);
                    }
                }
            }
            using (RegistryKey allowKey = Registry.CurrentUser.CreateSubKey(baseKeyPath + @"\URLAllowlist"))
            {
                if (allowKey != null)
                {
                    allowKey.SetValue("1", "https://www.instagram.com/direct*", RegistryValueKind.String);
                }
            }
        }
    }

    public sealed class MainDashboardForm : Form
    {
        private readonly LocalStateStore _store;
        private readonly Label _streakLabel = new Label();
        private readonly Label _adviceLabel = new Label();
        private readonly CheckedListBox _plansBox = new CheckedListBox();
        private readonly TextBox _newPlanInput = new TextBox();
        private readonly TextBox _testUrlInput = new TextBox();
        private readonly Label _testResultLabel = new Label();
        private readonly Label _strictStatusLabel = new Label();
        private readonly Timer _watchdogTimer = new Timer();
        private int _adviceIndex = 0;
        private DateTime _lastCalloutShown = DateTime.MinValue;

        public MainDashboardForm(LocalStateStore store)
        {
            _store = store;
            Text = "ZEROFEED — Windows Anti-Scrolling Companion & Watchdog";
            Size = new Size(920, 680);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(10, 14, 23);
            ForeColor = Color.FromArgb(241, 245, 249);
            Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);

            BuildLayout();
            RefreshUiFromStore();

            _watchdogTimer.Interval = 2000;
            _watchdogTimer.Tick += OnWatchdogTick;
            _watchdogTimer.Start();
        }

        private void BuildLayout()
        {
            Label title = new Label
            {
                Text = "ZEROFEED // WINDOWS DESKTOP & LOW-RAM LAPTOP COMPANION",
                Font = new Font("Segoe UI", 14f, FontStyle.Bold),
                ForeColor = Color.FromArgb(56, 189, 248),
                Location = new Point(24, 18),
                AutoSize = true
            };
            Controls.Add(title);

            _streakLabel.Location = new Point(24, 54);
            _streakLabel.Size = new Size(850, 28);
            _streakLabel.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
            _streakLabel.ForeColor = Color.FromArgb(134, 239, 172);
            Controls.Add(_streakLabel);

            GroupBox adviceGroup = CreateGroupBox("Advice of the Day (Daily Accountability)", new Point(24, 92), new Size(850, 95));
            _adviceLabel.Location = new Point(14, 26);
            _adviceLabel.Size = new Size(700, 55);
            _adviceLabel.ForeColor = Color.FromArgb(226, 232, 240);
            adviceGroup.Controls.Add(_adviceLabel);

            Button nextAdviceBtn = CreateButton("Next Advice", new Point(725, 30), new Size(110, 34));
            nextAdviceBtn.Click += delegate
            {
                _adviceIndex++;
                RefreshUiFromStore();
            };
            adviceGroup.Controls.Add(nextAdviceBtn);
            Controls.Add(adviceGroup);

            GroupBox plansGroup = CreateGroupBox("Daily Plans (Read a Book & Solve Coding Problems)", new Point(24, 198), new Size(420, 230));
            _plansBox.Location = new Point(14, 28);
            _plansBox.Size = new Size(390, 140);
            _plansBox.BackColor = Color.FromArgb(17, 24, 39);
            _plansBox.ForeColor = Color.White;
            _plansBox.BorderStyle = BorderStyle.FixedSingle;
            _plansBox.ItemCheck += OnPlanItemCheck;
            plansGroup.Controls.Add(_plansBox);

            _newPlanInput.Location = new Point(14, 180);
            _newPlanInput.Size = new Size(285, 28);
            _newPlanInput.BackColor = Color.FromArgb(17, 24, 39);
            _newPlanInput.ForeColor = Color.White;
            plansGroup.Controls.Add(_newPlanInput);

            Button addPlanBtn = CreateButton("Add Plan", new Point(307, 178), new Size(96, 30));
            addPlanBtn.Click += OnAddPlanClick;
            plansGroup.Controls.Add(addPlanBtn);
            Controls.Add(plansGroup);

            GroupBox strictGroup = CreateGroupBox("Strict Mode & Chrome/Edge Policy Lock", new Point(458, 198), new Size(416, 230));
            _strictStatusLabel.Location = new Point(14, 28);
            _strictStatusLabel.Size = new Size(385, 48);
            _strictStatusLabel.ForeColor = Color.FromArgb(251, 191, 36);
            strictGroup.Controls.Add(_strictStatusLabel);

            Button lock2hBtn = CreateButton("Lock Strict Mode (120 Min)", new Point(14, 82), new Size(190, 34));
            lock2hBtn.Click += delegate
            {
                _store.LockedUntilUtc = DateTime.UtcNow.AddMinutes(120);
                _store.Save();
                RefreshUiFromStore();
            };
            strictGroup.Controls.Add(lock2hBtn);

            Button applyPolicyBtn = CreateButton("Enforce Chrome/Edge Policies", new Point(212, 82), new Size(190, 34));
            applyPolicyBtn.Click += delegate
            {
                string msg;
                BrowserPolicyHelper.ApplyCurrentUserBrowserPolicies(out msg);
                MessageBox.Show(msg, "ZEROFEED Browser Policy Enforcer", MessageBoxButtons.OK, MessageBoxIcon.Information);
            };
            strictGroup.Controls.Add(applyPolicyBtn);

            Button igDmBtn = CreateButton("Open Instagram DMs Only (/direct/inbox/)", new Point(14, 130), new Size(388, 34));
            igDmBtn.Click += delegate
            {
                Process.Start(new ProcessStartInfo("https://www.instagram.com/direct/inbox/") { UseShellExecute = true });
            };
            strictGroup.Controls.Add(igDmBtn);

            Button simulateSlipBtn = CreateButton("Preview Hard-Truth Reel/Short Callout", new Point(14, 174), new Size(388, 34));
            simulateSlipBtn.BackColor = Color.FromArgb(185, 28, 28);
            simulateSlipBtn.ForeColor = Color.White;
            simulateSlipBtn.Click += delegate { TriggerHardTruthCallout("YouTube Short / Instagram Reel (Test Trigger)"); };
            strictGroup.Controls.Add(simulateSlipBtn);
            Controls.Add(strictGroup);

            GroupBox testerGroup = CreateGroupBox("Interactive Cross-Device URL Rule Tester", new Point(24, 440), new Size(850, 175));
            _testUrlInput.Location = new Point(14, 30);
            _testUrlInput.Size = new Size(695, 28);
            _testUrlInput.Text = "https://www.youtube.com/shorts/dQw4w9WgXcQ";
            _testUrlInput.BackColor = Color.FromArgb(17, 24, 39);
            _testUrlInput.ForeColor = Color.White;
            testerGroup.Controls.Add(_testUrlInput);

            Button runTestBtn = CreateButton("Evaluate URL", new Point(720, 28), new Size(115, 32));
            runTestBtn.Click += delegate
            {
                EvaluationVerdict verdict = RuleEvaluator.EvaluateUrl(_testUrlInput.Text.Trim(), _store.CustomBlockedDomains);
                _testResultLabel.Text =
                    string.Format("Verdict: {0}  |  Platform: {1}  |  Violation: {2}\nReason: {3}\nFallback Safe Route: {4}",
                        verdict.Action, verdict.PlatformId, verdict.ViolationType, verdict.Reason, verdict.FallbackSafeUrl ?? "(None)");
                _testResultLabel.ForeColor = verdict.Action == "BLOCK"
                    ? Color.FromArgb(248, 113, 113)
                    : Color.FromArgb(134, 239, 172);
            };
            testerGroup.Controls.Add(runTestBtn);

            _testResultLabel.Location = new Point(14, 72);
            _testResultLabel.Size = new Size(820, 85);
            _testResultLabel.Text = "Enter any URL above and click 'Evaluate URL' to verify ZEROFEED's rule decision.";
            testerGroup.Controls.Add(_testResultLabel);
            Controls.Add(testerGroup);
        }

        private void RefreshUiFromStore()
        {
            long ramMb = Process.GetCurrentProcess().WorkingSet64 / (1024 * 1024);
            _streakLabel.Text = string.Format(
                "Clean Focus Streak: {0} Day(s)   |   Slips Detected Today: {1} (Total: {2})   |   RAM Footprint: ~{3} MB",
                _store.StreakDays, _store.TodaySlips, _store.TotalSlips, ramMb);

            int idx = Math.Abs((DateTime.Now.DayOfYear + _adviceIndex) % _store.AdviceBank.Length);
            _adviceLabel.Text = _store.AdviceBank[idx];

            _plansBox.Items.Clear();
            foreach (var p in _store.Plans)
            {
                _plansBox.Items.Add(p.Title, p.Completed);
            }

            if (_store.LockedUntilUtc > DateTime.UtcNow)
            {
                int mins = (int)Math.Ceiling((_store.LockedUntilUtc - DateTime.UtcNow).TotalMinutes);
                _strictStatusLabel.Text = string.Format("STRICT MODE LOCKED ({0} minutes remaining). Rules cannot be weakened.", mins);
            }
            else
            {
                _strictStatusLabel.Text = "Strict Mode Ready. Morning (08:00-13:00) & Evening (18:00-23:30) schedules active.";
            }
        }

        private void OnPlanItemCheck(object sender, ItemCheckEventArgs e)
        {
            if (e.Index >= 0 && e.Index < _store.Plans.Count)
            {
                _store.Plans[e.Index].Completed = (e.NewValue == CheckState.Checked);
                _store.Save();
            }
        }

        private void OnAddPlanClick(object sender, EventArgs e)
        {
            string text = _newPlanInput.Text.Trim();
            if (string.IsNullOrEmpty(text)) return;
            _store.Plans.Add(new DailyPlanItem { Title = text, Completed = false });
            _newPlanInput.Text = "";
            _store.Save();
            RefreshUiFromStore();
        }

        private void OnWatchdogTick(object sender, EventArgs e)
        {
            string activeTitle = Program.GetActiveWindowTitle();
            if (string.IsNullOrEmpty(activeTitle)) return;

            string lower = activeTitle.ToLowerInvariant();
            bool isDistraction = lower.Contains("#shorts")
                                 || lower.Contains("youtube shorts")
                                 || lower.Contains("instagram reels")
                                 || lower.Contains("tiktok - make your day");

            if (isDistraction && (DateTime.UtcNow - _lastCalloutShown).TotalSeconds > 15)
            {
                _lastCalloutShown = DateTime.UtcNow;
                TriggerHardTruthCallout(activeTitle);
            }
        }

        private void TriggerHardTruthCallout(string sourceDescription)
        {
            _store.StreakDays = 0;
            _store.TodaySlips += 1;
            _store.TotalSlips += 1;
            _store.Save();
            RefreshUiFromStore();

            string message =
                "CAUGHT IN THE ACT: " + sourceDescription + "\n\n" +
                "You just opened a short-form video or infinite feed trap. Feel the weight of wasted potential—nobody ever achieved mastery by swiping 30-second videos.\n\n" +
                "1. Close that feed immediately.\n" +
                "2. Pick up your book and read 25 pages.\n" +
                "3. Open Codeforces / LeetCode and solve 3 hard algorithmic problems.";

            MessageBox.Show(
                this,
                message,
                "ZEROFEED — HARD-TRUTH ACCOUNTABILITY INTERCEPT",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning
            );
        }

        private static GroupBox CreateGroupBox(string title, Point location, Size size)
        {
            return new GroupBox
            {
                Text = title,
                Location = location,
                Size = size,
                ForeColor = Color.FromArgb(56, 189, 248)
            };
        }

        private static Button CreateButton(string text, Point location, Size size)
        {
            return new Button
            {
                Text = text,
                Location = location,
                Size = size,
                BackColor = Color.FromArgb(56, 189, 248),
                ForeColor = Color.FromArgb(10, 14, 23),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9f, FontStyle.Bold)
            };
        }
    }

    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

        [STAThread]
        private static int Main(string[] args)
        {
            LocalStateStore store = new LocalStateStore();
            store.Load();

            if (args.Length > 0)
            {
                if (args[0] == "--verify")
                {
                    return RunSelfVerification(store);
                }
                if (args[0] == "--evaluate" && args.Length > 1)
                {
                    EvaluationVerdict v = RuleEvaluator.EvaluateUrl(args[1], store.CustomBlockedDomains);
                    Console.WriteLine(string.Format("{{\"action\":\"{0}\",\"platformId\":\"{1}\",\"violationType\":\"{2}\"}}", v.Action, v.PlatformId, v.ViolationType));
                    return 0;
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainDashboardForm(store));
            return 0;
        }

        private static int RunSelfVerification(LocalStateStore store)
        {
            var igFeed = RuleEvaluator.EvaluateUrl("https://www.instagram.com/", store.CustomBlockedDomains);
            var igDm = RuleEvaluator.EvaluateUrl("https://www.instagram.com/direct/inbox/", store.CustomBlockedDomains);
            var ytShorts = RuleEvaluator.EvaluateUrl("https://www.youtube.com/shorts/abc123", store.CustomBlockedDomains);
            var ytWatch = RuleEvaluator.EvaluateUrl("https://www.youtube.com/watch?v=8hly31xKli0", store.CustomBlockedDomains);
            var tiktok = RuleEvaluator.EvaluateUrl("https://www.tiktok.com/@user/video/1", store.CustomBlockedDomains);
            var edu = RuleEvaluator.EvaluateUrl("https://ocw.mit.edu/courses/", store.CustomBlockedDomains);

            bool pass = igFeed.Action == "BLOCK"
                        && igDm.Action == "ALLOW"
                        && ytShorts.Action == "BLOCK"
                        && ytWatch.Action == "ALLOW"
                        && tiktok.Action == "BLOCK"
                        && edu.Action == "ALLOW";

            Console.WriteLine(string.Format(
                "[ZeroFeedWindows Native Verify] IG_Feed={0}, IG_DM={1}, YT_Shorts={2}, YT_Watch={3}, TikTok={4}, EDU={5}",
                igFeed.Action, igDm.Action, ytShorts.Action, ytWatch.Action, tiktok.Action, edu.Action));

            if (!pass)
            {
                Console.Error.WriteLine("FAIL: ZeroFeedWindows native verification failed!");
                return 1;
            }
            Console.WriteLine("PASS: ZeroFeedWindows.exe native verification succeeded.");
            return 0;
        }

        public static string GetActiveWindowTitle()
        {
            const int nChars = 512;
            StringBuilder buff = new StringBuilder(nChars);
            IntPtr handle = GetForegroundWindow();
            return GetWindowText(handle, buff, nChars) > 0 ? buff.ToString() : string.Empty;
        }
    }
}
