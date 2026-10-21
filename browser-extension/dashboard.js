/* global ZeroFeedEngine, chrome */
(function () {
  "use strict";

  let currentConfig = null;
  let adviceOffset = 0;

  function renderDashboard(data) {
    currentConfig = data.config;
    const sched = data.scheduleStatus;
    const strict = data.strictStatus;
    const adviceObj = data.dailyAdvice;

    // 1. Streak & Slips
    const days = (currentConfig.streak && currentConfig.streak.currentDays) || 0;
    const todaySlips = (currentConfig.streak && currentConfig.streak.todaySlipsDetected) || 0;
    const totalSlips = (currentConfig.streak && currentConfig.streak.totalSlipsDetected) || 0;
    document.getElementById("streakDays").textContent = `${days} ${days === 1 ? "Day" : "Days"} Clean`;
    document.getElementById("slipSummary").textContent =
      `${todaySlips} Reel/Short slips today | ${totalSlips} total intercepted`;

    // 2. Advice of the Day
    document.getElementById("adviceBox").textContent = adviceObj.advice;

    // 3. Schedule & Session Status
    const sessionEl = document.getElementById("sessionStatusText");
    const nextEl = document.getElementById("nextScheduleText");
    if (sched.active) {
      sessionEl.textContent = `Active Focus Session: "${sched.activeSchedules[0].name}" (Ends at ${sched.currentEndTime})`;
      sessionEl.style.color = "#86efac";
    } else {
      sessionEl.textContent = "Core Anti-Scrolling Rules Active 24/7 (Between Scheduled Deep-Work Blocks)";
      sessionEl.style.color = "#38bdf8";
    }
    if (sched.nextSchedule) {
      nextEl.textContent = `Next Scheduled Block: "${sched.nextSchedule.name}" (${sched.nextSchedule.startTime} – ${sched.nextSchedule.endTime})`;
    } else {
      nextEl.textContent = "Daily recurring schedules configured.";
    }

    // 4. Daily Plans
    const plansContainer = document.getElementById("plansContainer");
    plansContainer.innerHTML = "";
    (currentConfig.dailyPlans || []).forEach((plan, idx) => {
      const row = document.createElement("div");
      row.className = "plan-row";

      const label = document.createElement("label");
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = !!plan.completed;
      checkbox.addEventListener("change", () => {
        currentConfig.dailyPlans[idx].completed = checkbox.checked;
        saveConfig(true);
      });
      const span = document.createElement("span");
      span.textContent = plan.title;
      if (plan.completed) {
        span.style.textDecoration = "line-through";
        span.style.color = "#64748b";
      }
      label.appendChild(checkbox);
      label.appendChild(span);

      const delBtn = document.createElement("button");
      delBtn.className = "secondary";
      delBtn.textContent = "Remove";
      delBtn.addEventListener("click", () => {
        currentConfig.dailyPlans.splice(idx, 1);
        saveConfig(true);
      });

      row.appendChild(label);
      row.appendChild(delBtn);
      plansContainer.appendChild(row);
    });

    // 5. Strict Mode
    const strictBanner = document.getElementById("strictStatusBanner");
    strictBanner.textContent = strict.reason;
    document.getElementById("toneSelect").value =
      (currentConfig.strictMode && currentConfig.strictMode.calloutTone) || "hard_truth";

    // 6. Platforms
    const platformsContainer = document.getElementById("platformsContainer");
    platformsContainer.innerHTML = "";
    Object.entries(currentConfig.platforms || {}).forEach(([key, plat]) => {
      const item = document.createElement("div");
      item.className = "platform-item";

      const title = document.createElement("span");
      title.textContent = plat.name || key;

      const toggle = document.createElement("input");
      toggle.type = "checkbox";
      toggle.checked = !!plat.enabled;
      toggle.disabled = strict.locked && plat.enabled;
      toggle.addEventListener("change", () => {
        currentConfig.platforms[key].enabled = toggle.checked;
        saveConfig(false);
      });

      item.appendChild(title);
      item.appendChild(toggle);
      platformsContainer.appendChild(item);
    });

    // 7. Custom Blocked Domains
    const tagsEl = document.getElementById("customDomainsTags");
    tagsEl.innerHTML = "";
    (currentConfig.customBlockedDomains || []).forEach((dom) => {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = dom;
      tagsEl.appendChild(tag);
    });
  }

  function refresh() {
    chrome.runtime.sendMessage({ type: "GET_CONFIG_AND_STATUS", adviceOffset }, (res) => {
      if (res && res.config) {
        renderDashboard(res);
      }
    });
  }

  function saveConfig(allowUnderStrict) {
    chrome.runtime.sendMessage(
      {
        type: "SAVE_CONFIG",
        config: currentConfig,
        allowUnderStrict: !!allowUnderStrict
      },
      (res) => {
        if (res && !res.ok && res.error) {
          alert(res.error);
        }
        refresh();
      }
    );
  }

  document.getElementById("nextAdviceBtn").addEventListener("click", () => {
    adviceOffset += 1;
    refresh();
  });

  document.getElementById("addPlanBtn").addEventListener("click", () => {
    const input = document.getElementById("newPlanInput");
    const title = input.value.trim();
    if (!title || !currentConfig) return;
    currentConfig.dailyPlans = currentConfig.dailyPlans || [];
    currentConfig.dailyPlans.push({
      id: "plan-" + Date.now(),
      title,
      category: "custom",
      completed: false
    });
    input.value = "";
    saveConfig(true);
  });

  document.getElementById("engageStrictLockBtn").addEventListener("click", () => {
    if (!currentConfig) return;
    const mins = parseInt(document.getElementById("lockMinutesInput").value, 10) || 120;
    const until = new Date(Date.now() + mins * 60 * 1000).toISOString();
    currentConfig.strictMode.lockedUntilIso = until;
    saveConfig(true);
  });

  document.getElementById("toneSelect").addEventListener("change", (e) => {
    if (!currentConfig) return;
    currentConfig.strictMode.calloutTone = e.target.value;
    saveConfig(true);
  });

  document.getElementById("addCustomDomainBtn").addEventListener("click", () => {
    const input = document.getElementById("customDomainInput");
    const dom = input.value.trim().toLowerCase();
    if (!dom || !currentConfig) return;
    currentConfig.customBlockedDomains = currentConfig.customBlockedDomains || [];
    if (!currentConfig.customBlockedDomains.includes(dom)) {
      currentConfig.customBlockedDomains.push(dom);
    }
    input.value = "";
    saveConfig(true);
  });

  document.getElementById("runTestUrlBtn").addEventListener("click", () => {
    const url = document.getElementById("testUrlInput").value.trim();
    chrome.runtime.sendMessage({ type: "EVALUATE_URL", url }, (res) => {
      if (!res || !res.evaluation) return;
      const ev = res.evaluation;
      const lines = [
        `Verdict: ${ev.action}`,
        `Reason: ${ev.reason}`,
        `Platform: ${ev.platformName || "None"}`,
        `DOM Selectors Hidden: ${(ev.hideSelectors || []).length}`
      ];
      if (res.callout) {
        lines.push(`Callout Headline: ${res.callout.headline}`);
        lines.push(`Accountability Prompt: ${res.callout.shameCallout}`);
      }
      document.getElementById("testUrlOutput").textContent = lines.join("\n");
    });
  });

  refresh();
})();
