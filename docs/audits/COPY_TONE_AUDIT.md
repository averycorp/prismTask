# Copy / tone audit — user-facing strings vs forgiveness-first

**Date:** 2026-05-14 · **Branch:** `worktree-audit+copy-tone-pass` ·
**Baseline:** `app/build.gradle.kts:22` versionName `1.9.26`

Scope: every user-facing string surfaced in notifications, Today
banners, streak / habit displays, analytics, errors, chat, onboarding,
settings, and dialogs. Rated against the six anti-shame principles
A1–A6 (failure framing, urgency manufacturing, should/must language,
productivity-as-virtue, comparison framing, empty-state neutrality).
Grounds: `docs/FORGIVENESS_FIRST.md`, `docs/WORK_PLAY_RELAX.md`,
`docs/COGNITIVE_LOAD.md`.

**Audit-only**: no string changes ship from this PR. Rewrite variants
are proposals; operator picks before any code lands.

## Phase 0 — premise verification

| Premise | Result |
|---|---|
| P1: `strings.xml` exists at `app/src/main/res/values/strings.xml` | ✅ single file, 15 entries (all widget descriptions) |
| P2: Hardcoded `Text("[A-Z]"` count > 100 | ⚠️ **877 occurrences** — STOP-C fires (see § Hardcoded sprawl) |
| P3: App at v1.8.49+ per memory | ✅ versionName `1.9.26` |
| P4: `docs/FORGIVENESS_FIRST.md` exists | ✅ + `WORK_PLAY_RELAX.md`, `COGNITIVE_LOAD.md` |

**STOP-C fired but audit continues** per prompt directive ("Proceed
with audit but flag in findings"). See § Hardcoded sprawl at end.

## Inventory totals

| Surface | Call sites / strings |
|---|---|
| `strings.xml` entries | 15 |
| Hardcoded UI `Text("[A-Z]"` (UI tree) | 877 |
| `setContentTitle` / `setContentText` / `setSubText` | 46 |
| `showSnackbar(` + `AlertDialog(` | 225 |

## Verdict matrix

| Surface | Audited | GREEN | YELLOW | RED |
|---|---:|---:|---:|---:|
| Notifications — title + body | 22 | 13 | 5 | **4** |
| Notification channel names | 9 | 8 | 1 | 0 |
| Today — banners + section headers | 9 | 6 | 2 | **1** |
| Streak / habit copy | 7 | 6 | 0 | **1** |
| Productive-day score / analytics | 8 | 4 | 3 | **1** |
| Chat AI — disclosure, starters, welcome | 9 | 8 | 1 | 0 |
| Error / snackbar | 11 | 10 | 1 | 0 |
| Settings — forgiveness + advanced tuning | 6 | 3 | 2 | **1** |
| Onboarding (sampled — 16 pages) | 18 | 16 | 2 | 0 |
| **TOTAL** | **99** | **74** | **17** | **8** |

8 RED is below the STOP-D threshold (≥10) — copy drift is concentrated
in three patterns, not a systemic philosophy gap, so this is a
**one-surface-at-a-time** situation, not a write-the-doc-then-rewrite-
everything one.

## Top 5 most-egregious RED strings

1. **`OverloadCheckWorker.kt:82`** — `"$workPct% work this week — consider blocking time for self-care."` Body is **prescriptive** ("consider blocking time for self-care"), violating `WORK_PLAY_RELAX.md`'s explicit banned-example: *"❌ Low Relax this week — try a 10-minute walk."* Notifications fire unprompted, so prescriptive copy hits hardest here. **Preferred rewrite:** *"$workPct% Work this week. Tap to see your full balance."*

2. **`TodayBalanceBar.kt:257-263`** — `"Work is $workPct% of your week"` / `"That's above your $targetPct% target. Consider blocking time for self-care."` Same prescriptive violation as #1, on the Today screen banner. **Preferred rewrite:** title kept; subtitle → *"Above your $targetPct% target. Open Balance for a full split."*

3. **`NotificationHelper.kt:786`** — Escalation headline `EscalationStepAction.FULL_SCREEN -> "Critical"`. "Critical" is unjustified urgency manufacturing (A2) when the underlying signal is *user has a recurring reminder they configured themselves*. **Preferred rewrite:** *"Heads up — final reminder"* or simply *"Final reminder"*. Note: `FULL_SCREEN` style is user-configurable per profile, so the word can stay strong without going full alarm.

4. **`AnalyticsScreen "Worst" label`** (`ProductivityScoreSection.kt:264`) — `label = "Worst"`. Direct comparison framing (A5) and judgment language (A4). Pair label is `"Best"` at line 255. **Preferred rewrite:** *"Lowest"* paired with *"Highest"*, or drop the chip entirely and surface as a range.

5. **`ForgivenessStreakSection.kt:37`** — Setting subtitle `"Don't break a streak for a single missed day"`. This phrase is the **literal banned example** from `FORGIVENESS_FIRST.md` line 85 (*"❌ Don't break your streak!"*). The setting *enables* forgiveness, so the subtitle describes the feature — but the verbatim match to a banned phrase is jarring. **Preferred rewrite:** *"A single missed day won't reset your streak"* or *"Keep your streak through one off day"*.

## Top 3 systemic patterns

**S1. Prescriptive balance copy violates `WORK_PLAY_RELAX.md`'s descriptive-not-prescriptive rule** — `OverloadCheckWorker:82`, `TodayBalanceBar:263`, plus `CognitiveLoadOverloadCheckWorker:104` *("plan some recovery wins.")*. All three tell the user what to do. The doc explicitly forbids this shape: *"❌ You're working too much — schedule more Play time."* Cross-surface rewrite candidate.

**S2. "!" pattern in timer/session-complete notifications** — `PomodoroTimerService:431` (*"Break Complete!"*, *"Session Complete!"*), `NotificationHelper:606-607` (*"Break Complete!"*, *"Timer Complete!"*). Five `!` across two files. A2 (urgency manufacturing) — these are *completion* events, not urgent ones, and the `!` reads as celebration in some apps but uniform-volume across all timers feels insistent. YELLOW individually; pattern is worth a single sweep.

**S3. "missed" framing in habit-streak settings copy** — `HabitsSection.kt:108,109,227-228` repeatedly says *"missed day(s)"* and *"break a streak"*. The forgiveness mechanic survives these days, so describing the tuning surface in terms of *missing* and *breaking* re-imports the failure framing that the streak core deliberately rejects. YELLOW individually; rephrase to *"off days"* and *"reset"* across this section.

---

## P0 — Notification copy (highest mental-health risk)

### RED

**`OverloadCheckWorker.kt:81-82`** — Work-life balance overload alert.
> "Work-life balance is skewing"
> "$workPct% work this week — consider blocking time for self-care."

Context: fires when work-category ratio exceeds user-configured threshold. Notification body, no user opt-in to *this firing* (only to the channel).

**Verdict:** RED. Principles: A1 ✓ / A2 ✓ / **A3 ✗** / **A4 ⚠️** / A5 ✓ / A6 N/A.
"Consider blocking time for self-care" is a directive; `WORK_PLAY_RELAX.md:71-76` explicitly bans this exact shape.

**Rewrite candidates:**
- A (conservative): *"$workPct% Work this week — above your $targetPct% target."*
- B (preferred): *"$workPct% Work this week. Tap to see your full balance."*
- C (warm): *"This week's split is $workPct% Work. Open Balance for the rest."*

**`NotificationHelper.kt:786`** — Escalation FULL_SCREEN headline.
> "Critical"

Context: rendered as `"$headline — $taskTitle"` when escalation step reaches FULL_SCREEN tier (user-configured per profile).

**Verdict:** RED. Principles: A1 ✓ / **A2 ✗** / A3 ✓ / A4 ✓ / A5 ✓.
The reminder originates from a user-set schedule, not a true emergency.

**Rewrite candidates:**
- A: *"Final reminder"*
- B (preferred): *"Heads up — final reminder"* (mirrors med "Heads Up" idiom at NotificationHelper:570)
- C: *"Still on the list"*

**`NotificationHelper.kt:783`** — Escalation LOUD_VIBRATE headline.
> "Action needed"

**Verdict:** RED. A3 ✗. "Needed" is should/must language.

**Rewrite:** *"Still pending"* (already used at the prior tier — collapse two tiers into one phrase or rename to *"Reminder"*).

**`PomodoroTimerService.kt:431-432`** + `NotificationHelper.kt:606-607` — Timer completion titles.
> "Break Complete!"
> "Session Complete!"
> "Timer Complete!"

**Verdict:** RED (pattern). A2 ✗ on each occurrence; individually YELLOW, pattern RED.

**Rewrite:** Drop the `!` uniformly: *"Break complete"*, *"Session complete"*, *"Timer complete"*. Material 3 sentence-case convention already pushes this direction.

### YELLOW

**`HabitFollowUpReceiver.kt:70-71`** — Habit follow-up notification.
> "$habitName — How Did It Go?"
> "Your scheduled time has passed. Tap to log."

YELLOW. "Scheduled time has passed" reads near A1 (failure framing). The intent is *log retroactively*, not *you missed it*. Rewrite: *"$habitName window is winding down. Tap to log."* or *"$habitName — log when you can."*

**`CognitiveLoadOverloadCheckWorker.kt:104`** — Easy-tier overload.
> "$easy% Easy. Open the Balance report to scan your harder items."

YELLOW. A4 borderline ("scan your harder items" is mildly prescriptive but routes to *opening a report*, not *doing a task*). Rewrite: *"$easy% Easy. Open Balance for the full split."*

**`CognitiveLoadOverloadCheckWorker.kt:107`** — Hard-tier overload.
> "$hard% Hard. Open the Balance report to plan some recovery wins."

YELLOW. A4 ✗ (*"recovery wins"* — productivity-as-virtue / prescriptive). Rewrite: *"$hard% Hard this week. Open Balance for the full split."*

**`ReengagementWorker.kt:127`** — `setContentTitle("PrismTask")` with body coming from backend AI (`response.nudge`). Cannot rate the body without server-side prompt review — flag as **DEFERRED** for a follow-up audit that pulls actual backend nudge templates. The title is neutral.

**`BriefingNotificationWorker.kt:81-90`** — Morning briefing.
> "Good Morning"
> single-task: *"You've got one thing today. Start whenever you're ready."*
> multi-task: *"You've got a few things today. Start with just one."*

GREEN. Among the strongest copy in the app — sentence-case, invitational, no urgency. Mark as the **model voice** for other notification rewrites.

### GREEN (sampled)

- `NotificationHelper.kt:328-329` *"$taskTitle is coming up"* / *"Ready when you are."* — pristine.
- `NotificationHelper.kt:570-571` *"$medName — Heads Up"* / *"$medName — whenever you're ready."* — pristine. "Heads Up" is title-cased but content is warm.
- `ProductiveStreakPreferences.kt:50-52` *"Streak Reset"* / *"Take care of yourself today — start fresh tomorrow."* — exemplary forgiveness-first framing for the broken-streak path.
- `WeeklyReviewWorker.kt:101-102`, `WeeklyHabitSummaryWorker.kt:116`, `WeeklyTaskSummaryWorker.kt:93`, `EveningSummaryWorker.kt:120` — all descriptive, no urgency.
- `LeisureTimerService.kt:232-253` — descriptive, factual.

### Channel names

| File | Channel | Verdict |
|---|---|---|
| `ReengagementWorker.kt:51` | "Gentle Nudges" | GREEN — explicitly de-urgent |
| `ProductiveStreakNotifier.kt:53` | "Productive Streak" | GREEN |
| `BriefingNotificationWorker.kt` | "Morning Briefing" | GREEN |
| `OverloadCheckWorker.kt` | "Overload Alerts" | YELLOW — "Alert" implies urgency. Rewrite: *"Balance Nudges"*. |
| `CognitiveLoadOverloadCheckWorker.kt:62` | "Load Balance Alerts" | YELLOW — same issue. Rewrite: *"Load Balance Nudges"*. |
| `PomodoroTimerService.kt:524,526` | "Pomodoro Timer" / "Pomodoro Alerts" | GREEN / YELLOW (rename completion channel to *"Pomodoro Completion"*). |

## P0 — Today screen banners + section headers

**`TodayBalanceBar.kt:257-263`** — Overload banner. RED. See top-5 entry #2.

**`TodayScreen.kt:630`** — Section header `title = "From Earlier"`. GREEN. **Convention preserved** (PR #1136); verbatim match, no reversion. Bank this in memory.

**`TodayScreen.kt:825`** — Section header `title = "Planned"`. GREEN.

**`MorningCheckInBanner.kt`** + `TodayViewModel.checkInSummaryFlow` — Greeting *"Good Morning!"* / *"Good Afternoon!"* + dynamic summary + CTA *"Start Check-In"*. GREEN. The banner is decorative, the summary elides zero counts (*"never reads '0 tasks'"* — `TodayViewModel.kt:764-765`), and the CTA is invitational not prescriptive.

**`TodayBalanceBar.kt:112`** — Empty-state *"Add categories to see your balance"*. GREEN — invitational (A6 ✓).

**`TodayBalanceBar.kt:77,183`** — Section labels *"Balance"* / *"Cognitive Load"*. GREEN.

**`TodayBalanceBar.kt:269`** — Banner dismiss button *"Dismiss"*. GREEN — neutral.

## P0 — Streak / habit copy

**`ForgivenessStreakSection.kt:37`** — Setting subtitle. RED. See top-5 entry #5.

**`ForgivenessStreakSection.kt:69`** — *"How many missed days the grace window tolerates"*. GREEN — factual, no failure framing because the *whole feature* survives missed days.

**`HabitsSection.kt:108-109`** — Slider readout *"1 missed day ends a streak"* / *"$days missed days end a streak"*. YELLOW (S3 pattern). Rewrite: *"1 off day resets the streak"* / *"$days off days reset the streak"*.

**`HabitsSection.kt:227-228`** — Slider help text *"Choose how many consecutive missed days break a daily-habit streak. At 1, any missed day ends the streak (original behavior). Higher values..."*. YELLOW (S3 pattern). Rewrite: *"Choose how many off days reset a daily-habit streak. At 1, the original strict behavior. Higher values let the streak survive a longer pause."*

**Streak chip** (`ProductivityScoreSection.kt:117,125` → `ProductiveStreakChip`) — display copy not in this grep; verify in follow-up that the chip uses *"X-day streak"* not *"X days perfect"* (the doc bans *"perfect"* framing at line 86).

**Onboarding "Forgiving Streaks" card** (`OnboardingScreen.kt:661-666`) — *"Skip a day without losing your streak."* GREEN. Excellent — explicitly inverts the "miss-and-lose" frame.

## P1 — Analytics + chat AI + error copy

### Analytics

**`ProductivityScoreSection.kt:255-264`** — *"Best"* / *"Worst"* labels. RED. See top-5 entry #4.

**`ProductivityScoreSection.kt:153-158`** — Trend chips *"Improving"* / *"Declining"* / *"Stable"*. YELLOW. "Declining" reads as judgmental for a productivity number — but it *is* the literal trend direction; A5 borderline. Rewrite candidate: *"Up"* / *"Down"* / *"Flat"* (more neutral) or leave as-is if "trend" framing is preserved.

**`TaskAnalyticsScreen.kt:603,609`** — *"Most Productive Day"* / *"Least Productive Day"*. YELLOW (A5 — invites comparison). Acceptable on an analytics screen where comparison is the point, but the asymmetry ("Most" / "Least") could read better as *"Top day"* / *"Lightest day"*. Defer to a Phase 2 micro-decision.

**`ProductivityScoreSection.kt:106,111`** — *"Productivity Score"* / *"Average ${score}"*. GREEN.

**`ProductivityScoreSection.kt:81`** — Empty-data state *"Need at least 2 days of data to plot the chart."* GREEN.

### Chat AI

**`ChatScreen.kt:188-199`** — Disclosure dialog body (PR #1171 V2). GREEN. **Operator-approved per prompt directive ("don't reword").** Preserve verbatim.

**`ChatScreen.kt:220-229`** — Clear-chat dialog *"Clear Chat?"* / *"This will delete all messages in the current conversation. This can't be undone."* / *"Don't Ask Again"*. GREEN — factual, no urgency.

**`ChatScreen.kt:417-422`** — Welcome card *"Hey there"* / *"I can help you break down tasks, plan your day, or just talk through what feels stuck. What's on your mind?"* GREEN — exceptional. Model voice for warm copy.

**`ChatScreen.kt:393-398`** — Starter prompts:
1. *"What should I focus on today?"* — GREEN.
2. *"Help me reschedule overdue tasks"* — YELLOW. Uses *"overdue"* against PR #1136 convention. The phrase routes to a backend action so the user *speaks* it as their own — borderline. Rewrite: *"Help me reschedule tasks from earlier"*.
3. *"Break down my biggest task"* — GREEN.
4. *"Suggest a 25-minute focus session"* — GREEN.

**`ChatScreen.kt:265-267`** — Top-bar subtitle *"Talking About: ${title}"* / *"General"*. GREEN.

### Error messages

**`MedicationRefillViewModel.kt:114,125,136,160`** — Repeating *"Couldn't [save medication/record dose/record refill/update tracking]. Please try again."* GREEN. Sentence-case, no blame, action-oriented. The "Please try again" suffix is genuinely a polite ask and the only verb-form that fits.

**`MedicationViewModel.kt:432,499,531`** — Same pattern. GREEN.

**`SettingsViewModelExportImport.kt:21,35,70`** — *"Export failed"* / *"Import failed"*. YELLOW. Bare "failed" lacks recovery context. Rewrite: *"Couldn't export — try again"* / *"Couldn't import — try again"*.

**`SyncSettingsViewModel.kt:112`** — *"Sync failed"*. YELLOW. Same rewrite shape.

**`TodayViewModel.kt:1056,1128,1149,1237`** — *"Couldn't duplicate task"* / *"Couldn't move task"* / *"Couldn't create project"* / *"Couldn't create task from template"*. GREEN — consistent voice.

## P2 — Onboarding + advanced settings (sampled)

### Onboarding (16 pages, sampled — full pass in follow-up)

**`OnboardingScreen.kt:303,311`** — *"Welcome to PrismTask"* / *"Your smart, adaptive productivity companion"*. GREEN.
**`:406-407`** — *"Organize Everything"* / *"Projects, tags, subtasks, and priorities…"* GREEN.
**`:458-459`** — *"Group with Projects"* / *"…track a forgiveness-friendly streak as you make progress."* GREEN — explicitly forgiveness-aware.
**`:522-523`** — *"Type Naturally"* / *"Just type 'Buy groceries tomorrow !high #errands' and PrismTask understands instantly."* GREEN.
**`:661-666`** — *"Forgiving Streaks"* / *"Skip a day without losing your streak."* GREEN. Model copy.
**`:680-681`** — *"Allow up to $streakMaxMissed missed [days]…"* YELLOW (S3 pattern). Rewrite: *"Allow up to $streakMaxMissed off [days]…"*.
**`:816,824`** — *"How Does Your Brain Work?"* / *"Select any that apply — or skip if none fit."* GREEN.
**`:837-878`** — Brain-mode titles *"I Get Distracted Easily"*, *"I Get Overstimulated Easily"*, *"I Have Trouble Letting Go of Tasks"*. GREEN — descriptive of *needs*, not labeling the user.
**`:1583-1584`** — *"Streak Alerts"* / *"Reminder when a streak is about to break."* YELLOW. *"About to break"* is mild loss framing — but it's accurately describing what the alert does. Rewrite: *"Reminder before a streak would reset"*.
**`:1623-1624`** — *"Medication Reminders"* / *"Per-slot dose alerts. Snoozable, not naggy."* GREEN — meta-commentary on design philosophy.
**`:1670-1671`** — *"Habits and streaks roll over at this time. Most people…"* GREEN.

### Settings — Advanced Tuning

**`AdvancedTuningViewModel.kt:241,243`** — Validation message *"Invalid Cutoff — Must Come After Start-of-Day."* YELLOW. "Invalid" is a harsh word for a slider position. Rewrite: *"Cutoff must come after Start-of-Day"* (drop "Invalid", drop Title Case mid-sentence). Sentence-case alignment with Material 3 also helps here.

**`NotificationSettingsSection.kt:280`** + `NotificationTypesScreen.kt:176` — *"Nudge when work-life balance is skewing"*. GREEN — "Nudge" matches the channel-name suggestion above.

**`WorkLifeBalanceSection.kt:114`** — *"Warn when work exceeds target by ${prefs.overloadThresholdPct}%"*. YELLOW. *"Warn"* is a stronger word than the notification it controls (which the audit recommends softening). Rewrite: *"Nudge when Work exceeds target by ${prefs.overloadThresholdPct}%"* to align with the channel rename above.

### Medication

**`MedicationLogScreen.kt:359-362`** — Tier labels *"Skipped"* / *"Essential"* / *"Prescription"* / *"Complete"*. YELLOW. *"Skipped"* is the only one in the failure-framing risk zone; in this domain it's a *medical record*, not a habit miss, so it's likely the right word. Verify intent before changing.

---

## Hardcoded sprawl (STOP-C — surfaced as systemic finding)

`grep -E '<string name=' app/src/main/res/values/strings.xml | wc -l` →
**15** entries (all `widget_description` keys).

`grep -rn 'Text("[A-Z]' app/src/main/java/com/averycorp/prismtask/ui/ | wc -l` →
**877** hardcoded user-facing strings, none externalized.

**Why this matters for *this* audit:** every rewrite recommendation here must be applied as a Kotlin literal change, not a string-resource swap. Future translation, A/B testing of tone variants, or programmatic accessibility-mode rephrasing (e.g., for `NdFeatureGate` / brain-mode users) is **impossible** without an extraction pass first. STOP-C should be reframed as: *"copy hygiene improvements are blocked at the per-line level forever until extraction lands"*. Independent of tone, this is the load-bearing finding.

**Recommendation:** before Phase 2 of *this* audit fires more than one PR, pair-prompt a string-extraction sweep. Suggested order:
1. Notifications (46 sites, all in `notifications/` package — smallest blast radius, highest mental-health stakes).
2. Today-screen banners (Today + check-in + balance — single feature).
3. Dialogs + snackbars (cross-cutting; do last after the convention stabilizes).

Onboarding can stay hardcoded — it ships once per install, low recurrence.

---

## Recommended improvements (ranked by wall-clock-savings ÷ implementation-cost)

| Rank | Improvement | Effort | Wall-clock impact | Ratio |
|---|---|---|---|---|
| 1 | Rewrite **OverloadCheckWorker:81-82** + **TodayBalanceBar:257-263** in one PR (same pattern, two sites) | ~30 min | Removes the #1 prescriptive violation across both Today and notifications | **High** |
| 2 | Sweep **"!" suffix** off `PomodoroTimerService.kt:431-432` + `NotificationHelper.kt:606-607` | ~15 min | Two-file diff, removes systemic urgency pattern | **High** |
| 3 | Rewrite **escalation headlines** at `NotificationHelper.kt:780-786` (Critical / Action needed) | ~20 min | Eliminates strongest urgency manufacturing | **High** |
| 4 | Rewrite **`ForgivenessStreakSection.kt:37`** subtitle (banned phrase match) | ~5 min | Eliminates a verbatim-banned phrase from a settings row | **Very high** |
| 5 | Rename "Worst" → "Lowest" + sweep `HabitsSection.kt` "missed" → "off" | ~25 min | Closes the S3 systemic pattern | Medium |
| 6 | Rename **channels** "Overload Alerts" → "Balance Nudges", "Load Balance Alerts" → "Load Balance Nudges", "Pomodoro Alerts" → "Pomodoro Completion" | ~30 min (channel rename = new channel id, see § Caveats) | Soft urgency reduction at the OS notification-tray layer | Medium |
| 7 | Rewrite starter prompt 2 (*"Help me reschedule overdue tasks"* → *"…tasks from earlier"*) | ~5 min | Aligns with PR #1136 convention; visible on every empty chat | Medium |
| 8 | Soften error messages (*"Export failed"* → *"Couldn't export — try again"*) | ~15 min | Consistent voice across error surfaces | Medium |
| 9 | **String-extraction pass** for notifications package | ~3 hr | Unblocks all future copy work + i18n | High (long-term) |

### Caveats

- **Channel rename = new channel id.** Renaming a notification channel on Android requires either reusing the existing channel id (label-only update on next install) or migrating to a new id (orphans the old channel under "Other" in user settings). The PR should rename labels only; channel ids stay the same.
- **Starter prompt rewrites** route through the backend `/api/v1/ai/chat` handler — the AI's response prompt may need a parallel server-side rewrite if it parses *"overdue"* as a routing keyword. Verify before merging the client change.

---

## Anti-pattern list (flagged, not necessarily fixed)

- **Title-Case in mid-sentence captions** (e.g., AdvancedTuning *"Invalid Cutoff — Must Come After Start-of-Day"*). Misaligns with Material 3 sentence-case for body copy. Project convention in CLAUDE.md *"Capitalization"* section says Title Case for user-facing strings; check whether that applies to mid-sentence validation captions or only to labels/headers. **Operator decision needed** before sweeping.
- **"Heads Up" idiom** (medication: GREEN; escalation rewrite candidate: GREEN). Could become the house style for "this thing is happening" surfaces — but only if the project commits to it as a *voice* convention.
- **Brain-mode toggle copy at `OnboardingScreen.kt:837-878`** uses *"I [pattern]"* first-person framing. Strong inclusive copy, but worth documenting as an intentional voice convention if any other settings screens copy this shape.
- **`ProductivityScoreSection.kt` trend words "Improving" / "Declining"** are accurate but the asymmetric load (one celebratory word, one judgment word) skews tone. Lower priority.
- **`MedicationLogScreen.kt:359` "Skipped"** — medical-record framing, intentionally hard. Don't soften without clinical-context input.

---

## STOPs fired

- **STOP-C** (hardcoded-string sprawl > 100): fired. Resolved by surfacing as a systemic finding with prioritized extraction order; audit proceeded.
- **STOP-A / B / D / F**: not fired. RED count is 8 (below D's ≥10 threshold). Doc length under cap.

## Memory candidates

- *"`docs/FORGIVENESS_FIRST.md:85` and `docs/WORK_PLAY_RELAX.md:71-76` are the load-bearing copy rubrics — cite these by file:line when rejecting prescriptive notification copy."* — worth banking; reviewers need a pointer back to the canonical doc.
- *"Channel labels can be renamed without changing the channel id, but changing the id orphans existing user-configured channel settings under 'Other'."* — worth banking; gotcha for anyone touching `NotificationCompat.Builder`.

## Next prompt (re-trigger candidates)

- **Phase 2 — Notifications surface remediation**: 4 RED + 4 YELLOW notification strings, one systemic "!" pattern. Smallest blast radius.
- **Phase 2 — Today / Balance surface**: 1 RED + WorkLifeBalanceSection settings copy. Pairs with #1 because of the shared prescriptive pattern.
- **String-extraction pass — notifications package**: prerequisite to future copy hygiene work, surfaced here as the load-bearing finding.

Do NOT bundle these — one surface per PR per the prompt's locked constraint.
