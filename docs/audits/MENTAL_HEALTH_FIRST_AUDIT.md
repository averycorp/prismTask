# Mental-Health-First Audit — Phase 1

**Scope.** Verify, across the Android app and FastAPI backend, that every
shipped feature is either *aligned with* or at least *not in conflict with*
PrismTask's mental-health-first philosophy — and identify the missing
features that a depression/ADHD/anxiety-targeted productivity app needs but
PrismTask does not yet ship.

**Branch:** `worktree-audit-mental-health-first`
**Date:** 2026-05-14
**Repo HEAD:** `5a1dd360 docs(audits): append batch-5 medication Phase 3 summary + Phase 4 handoff`
**App version on `main`:** `1.9.26` (`app/build.gradle.kts`)

The three load-bearing philosophy docs anchoring this audit:

- [`docs/FORGIVENESS_FIRST.md`](../FORGIVENESS_FIRST.md) — streaks survive
  a missed day; copy is descriptive and never threatens loss.
- [`docs/WORK_PLAY_RELAX.md`](../WORK_PLAY_RELAX.md) — balance is described,
  never prescribed. "You're working too much" is an explicit anti-example.
- [`docs/COGNITIVE_LOAD.md`](../COGNITIVE_LOAD.md) — start-friction tier
  for ND-friendly task framing; descriptive-only in v1.

---

## TL;DR — PROCEED

**Verdict: PROCEED on 11 items. Phase 2 fires.**

PrismTask has a mature MH-first **foundation** — forgiveness-first streaks
(`DailyForgivenessStreakCore` shared across habits/projects/essentials),
descriptive balance trackers, ND-friendly modes (ADHD / Calm / Focus &
Release), boundaries, burnout scoring, mood + energy + check-in + weekly
review, clinical report, medication refill, quiet hours, escalation
chains, accessibility prefs (reduce motion / high contrast / large
targets), and a chat system prompt that already instructs "warm, never
preachy, avoid moralizing."

What the audit surfaces is **drift at the edges**: load-bearing user copy
that directly contradicts the published philosophy (R1–R4), one
Pro-gate that puts a safety-adjacent feature behind a paywall (R5), and
seven genuinely missing pieces that an MH-first app aimed at depression
and ADHD populations should ship: crisis resources surface, AI chat
safety guidance, rest-day as a first-class action, "pause all
notifications" quick toggle, mental-health data deletion UI, onboarding
ND/MH disclosure to preset defaults, and mood-triggered nudge-cadence
reduction (G1–G7).

The eleven items in the ranked table at the end of this doc fall into
two clusters by cost:

- **Copy / config fixes (R1–R4, R6).** Single-PR, ~30-90 minutes each.
  Highest impact-per-cost. R1 alone (the "Don't break a streak"
  subtitle) is a direct contradiction of a doctrine the codebase
  references by name, so its quality cost is greater than its line
  count suggests.
- **New surfaces (G1–G7, R5).** Each is one focused PR ranging from a
  ~1-hour system-prompt edit (G2) up to a ~1-2-day rest-mode feature
  (G3). None are weeks of work; the audit deliberately scopes minimum-
  viable versions.

The audit deliberately produces **no DEFERRED items** — when a feature
is missing, PROCEED with a documented path rather than parking
(`feedback_no_deferrals_if_not_there_fix_it`).

---

## Methodology

For each item: premise verified against `main` HEAD, exact file paths
and line numbers cited, and an implementation path given. RED items
are direct violations of published doctrine; YELLOW are concerning but
ambiguous; GREEN are confirmations the current implementation is
correct (included only when they materially anchor an adjacent
recommendation). Tags are inline after each title rather than
restating the framework headers per item
(`docs/audits/TOKEN_USAGE_EFFICIENCY_AUDIT.md`).

Two-bucket structure:

- **§ Violations (R1–R7).** Features that ship today and conflict with
  MH-first principles. Most are copy / config fixes.
- **§ Gaps (G1–G7).** Features an MH-first app of this size and target
  population is missing and should add.

---

## § Violations

### R1 — "Don't break a streak" subtitle (RED)

`app/src/main/java/com/averycorp/prismtask/ui/screens/settings/sections/ForgivenessStreakSection.kt:37`

```kotlin
subtitle = "Don't break a streak for a single missed day",
```

Direct contradiction of [`FORGIVENESS_FIRST.md`](../FORGIVENESS_FIRST.md)
lines 85–87, which explicitly **ban** "Don't break your streak!" as a
threat-framing string. The setting itself is correct (it controls the
grace window — a forgiveness-first behavior); only the subtitle copy is
wrong. The user's eye lands here whenever they tour Settings, so it's
high-visibility.

**PROCEED.** Rewrite as descriptive: "Keep a streak going even after a
missed day." or "Forgiveness window: one missed day still counts as part
of the streak." Single-string edit, single PR.

---

### R2 — "Consider blocking time for self-care" prescriptive nudge (RED)

Three sites:

- `app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/TodayBalanceBar.kt:263`
  ```kotlin
  text = "That's above your $targetPct% target. Consider blocking time for self-care.",
  ```
- `app/src/main/java/com/averycorp/prismtask/notifications/OverloadCheckWorker.kt:82`
  ```kotlin
  .setContentText("$workPct% work this week — consider blocking time for self-care.")
  ```
- `app/src/main/java/com/averycorp/prismtask/domain/usecase/BurnoutScorer.kt:100` (docstring example)
  ```
  "Balanced ✨" or "High risk — consider blocking time for rest."
  ```

Each tells the user *what to do* ("consider blocking time"). That is
the exact failure mode [`WORK_PLAY_RELAX.md`](../WORK_PLAY_RELAX.md)
calls out: descriptive, not prescriptive. The push notification is
worst — the app reaches *out* to the user with advice during a moment
the engine has already classified as overload.

**PROCEED.** Rewrite all three sites to describe state without
prescribing action. Example: `"Work was 72% of your week (target 60%)."`
plus a tap-through to the weekly balance report where the user can
self-interpret. Drop the imperative entirely. Single PR; touches three
files and BurnoutScorer's documented example shape.

---

### R3 — Evaluative balance labels with warning emoji (RED)

- `app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/TodayBalanceBar.kt:95`
  ```kotlin
  text = "⚠ Work high",
  ```
- `app/src/main/java/com/averycorp/prismtask/ui/screens/checkin/MorningCheckInScreen.kt:762`
  ```kotlin
  text = "⚠️ Work ratio over target",
  ```

"Work high" and "over target" both code the state as failure. The
warning glyph (⚠) compounds the affect. Both surface during sensitive
moments — daily overview, morning check-in. WPR §"Descriptive, not
prescriptive" rules apply: render the number, let the user interpret.

**PROCEED.** Replace evaluative labels with neutral descriptors:
`"Work-dominant week"` or `"Work: 72%, Personal: 12%, …"` — no glyph,
no "over". Same single PR as R2, since these surfaces co-locate.

---

### R4 — "Should be discussed with your provider" footer (RED)

- `app/src/main/java/com/averycorp/prismtask/ui/screens/settings/sections/ClinicalReportSection.kt:35`
  ```kotlin
  text = "Saved to Downloads. This data is self-reported and should be discussed with your provider.",
  ```
- `app/src/main/java/com/averycorp/prismtask/domain/usecase/ClinicalReportGenerator.kt:127`
  ```kotlin
  "Note: all values are self-reported and should be discussed with your healthcare provider."
  ```

"Should be" is prescriptive medical instruction. The intent is good
(disclaim self-report), but the framing tells a user — possibly in a
depressive episode, possibly stigma-averse — that they have a duty to
share. MH-first reframes as agency-respecting: "if helpful" instead of
"should."

**PROCEED.** Edit both strings to: `"This report is self-reported.
Share with a provider if you'd like to."` Two-string single PR.

---

### R5 — AI Coaching Pro-gated (YELLOW — safety-adjacent paywall)

`app/src/main/java/com/averycorp/prismtask/domain/usecase/ProFeatureGate.kt:58`

```kotlin
AI_EVENING_SUMMARY, AI_COACHING, AI_TASK_BREAKDOWN,
...
AI_CONVERSATIONAL,
...
AI_REENGAGEMENT, AI_DAILY_PLANNING,
```

`AI_COACHING` and `AI_CONVERSATIONAL` (the chat surface) sit in the PRO
tier. That is fine *as a product decision* for proactive AI coaching —
not every user needs an LLM-driven assistant. But because the same
chat surface is the natural place a user in crisis would type "I can't
do this anymore" (see G2), gating *all* chat behind PRO routes those
users to nothing. Free-tier users get no fallback safety surface.

**PROCEED.** Two sub-changes can ship in one PR:

1. Keep AI Coaching / Conversational PRO-gated overall (no product
   change), but allow Free-tier chat *only when* the input matches
   crisis keywords — and route those to the static safety surface
   from G1 (no LLM call, no Pro cost, no Anthropic spend).
2. Add a Settings affordance in the Free tier that surfaces crisis
   resources directly, independent of chat (the G1 surface).

This pattern keeps the paywall while making sure the safety net is
universal. Couples to G1+G2 so the three should likely land in a single
small bundle.

---

### R6 — `NdPreferences.forgivenessStreaks` defaults to false (YELLOW)

`app/src/main/java/com/averycorp/prismtask/data/preferences/NdPreferences.kt:30`

```kotlin
val forgivenessStreaks: Boolean = false,
```

The *global* forgiveness preference (`UserPreferencesDataStore.ForgivenessPrefs.enabled`)
defaults to **true** — so forgiveness-first is on by default for every
user. The `NdPreferences.forgivenessStreaks` field is a separate ADHD
Mode sub-setting whose role is unclear from the type alone: is it a
*stricter* additional grace window, or a redundant duplicate? If
redundant, defaulting it `false` while ADHD users intuit "ADHD Mode
gives me forgiveness" produces the wrong mental model.

**PROCEED.** Either (a) remove `NdPreferences.forgivenessStreaks` if it
truly duplicates the global preference, or (b) default it `true` and
rename to clarify what additional behavior it gates (`extendedGraceWindow`?).
Read `BrainModeScreen.kt` and the streak code paths first to decide
which; the work is the rename + default flip + migration.

---

### R7 — `ReengagementWorker` empathic copy (GREEN — keep as template)

`app/src/main/java/com/averycorp/prismtask/data/preferences/ProductiveStreakPreferences.kt:52`

> "Take care of yourself today — start fresh tomorrow."

This is the gold-standard rephrasing. It's the exemplar to point
implementers of R1–R4 at: descriptive, kind, no threat, no "should",
no glyph. Cited here so the Phase 2 PRs can reference it as a
canonical voice example.

**PROCEED-no-work-needed.** Keep. Reference in Phase 2 PR descriptions.

---

## § Gaps

### G1 — No crisis-resources surface (RED)

`grep -riE "988|crisis|hotline|samaritan|lifeline" app/src/main/ backend/app/`
returns zero real matches in source — only false positives in
dependencies. There is no Settings → Help section linking to 988
Suicide & Crisis Lifeline, Crisis Text Line (US: text HOME to 741741),
Samaritans (UK), or any regional equivalent. A productivity app
targeted at depression / anxiety populations without any visible
crisis fallback is the load-bearing MH-first gap.

**PROCEED.** Minimum viable: a `CrisisResourcesScreen` in Settings
(under Help / Wellness), plus a "If you need help now" link visible
on the Mood Analytics screen footer and at the end of the Weekly
Review. v1 ships **US-only** (988, 741741) with copy that says
"resources for US numbers; tap to see other regions" linking out — this
is honest about scope and unblocks the launch. v2 ships an
internationalized resource list (UK 116 123, AU 13 11 14, CA 988, etc.).
Single PR for v1; do not gate behind Pro.

---

### G2 — AI chat system prompt lacks crisis-safety guidance (RED)

`backend/app/services/ai_productivity.py:1230` (`_CHAT_SYSTEM_PROMPT_BASE`)

The prompt instructs "warm, concise, never preachy" and "avoid
moralizing about productivity" — good. But it has **no instruction**
for the case where the user expresses self-harm, hopelessness,
disordered-eating thoughts, or suicidal ideation. Today, that input
goes through the same tool-emitting productivity flow.

**PROCEED.** Append a § "Safety" block to `_CHAT_SYSTEM_PROMPT_BASE`:

> If the user's message indicates self-harm, suicidal thoughts, or
> acute crisis, do NOT emit any productivity tool calls. Reply with a
> short acknowledgement (one or two sentences, warm and unalarmed),
> point them to the in-app crisis resources surface, and stop. Do not
> attempt therapy, do not minimize, do not lecture.

Pair with a server-side keyword pre-filter in `routers/ai/chat.py` that
short-circuits to a static safety response *before* hitting the model
when high-confidence crisis terms are present — defense in depth for
both the model-output case and the model-unavailable case. Single PR;
~1 hour of editing + tests.

---

### G3 — No "rest day" / "low-spoons" first-class action (RED)

`grep -r "restDay\|sickDay\|spoons\|low_energy_day"` returns nothing
relevant. A user with low energy today has two choices: complete
tasks they can't, or miss them and burn into the grace window.
Forgiveness-first absorbs *one* such day, but a deliberate "I'm
resting" should not consume that buffer at all.

**PROCEED.** Add a per-day "Rest Day" mark on the Today screen.
Behavior: marking a day as rest does not break habit streaks (counts
as kept), suppresses non-medication reminders for that day, and shows
a soft "Resting today — see you tomorrow" header instead of the task
list. This composes cleanly with `DailyForgivenessStreakCore` (treat
rest days as "kept" by definition, not as misses), and with the
existing `QuietHoursDeferrer` infrastructure. Larger PR — ~1-2 days,
adds one preference, one Today-screen header state, one streak-core
caller change, one test pass. Document in a new
`docs/REST_DAY.md` alongside the philosophy docs.

---

### G4 — No "pause all notifications" quick toggle (YELLOW)

`app/src/main/java/com/averycorp/prismtask/ui/screens/notifications/NotificationQuietHoursScreen.kt`
configures scheduled quiet hours but offers no ad-hoc "silence the app
for the next hour / until tomorrow" affordance. Settings → Notifications
→ Quiet Hours is several taps deep, which is too far for a user already
in a low-energy state.

**PROCEED.** Add a single-tap "Pause for 1h / 4h / until tomorrow"
control on the Today screen (collapsed by default; expands from a
small icon). Persists in DataStore as `pauseNotificationsUntilEpochMs`;
all schedulers consult it before firing. Composes with quiet hours.
Single small PR.

---

### G5 — No data-deletion UI for sensitive mental-health data (YELLOW)

There is JSON export and JSON import (`data/export/DataExporter.kt` +
`DataImporter.kt`), and the user can wipe Firebase data through their
Google account console — but there is no in-app "delete all mood,
check-in, and clinical data" action. Mental-health data has different
privacy stakes than task data (employer / insurance / family access
risk), and an MH-first app should let the user purge it atomically.

**PROCEED.** Add a Settings → Privacy → "Delete mental-health data"
action that, on confirm, deletes from Room: `mood_energy_logs`,
`check_in_logs`, `weekly_reviews`, `boundary_rules`, `focus_release_logs`,
and any cached clinical-report rows; and from Firestore: the matching
collections. Distinct from "delete account" — this is a partial-wipe.
Single PR; ~3-4 hours including confirmation UX and a test that
verifies the deletion is cross-table atomic.

---

### G6 — Onboarding does not ask about MH/ND context (YELLOW)

`app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingScreen.kt` +
`OnboardingViewModel.kt`. Sweep for "ndDisclosure", "hasAdhd",
"hasDepression", "neurodivergent" — zero matches. Onboarding collects
theme, habit categories, a11y toggles, but does not ask the user
whether they have ADHD / depression / anxiety / autism / are recovering
from burnout. As a result, a new user with depression lands on default
productivity tuning rather than ND-friendly defaults.

**PROCEED.** Add one optional onboarding step: "Which of these
sometimes describe you? (used to set defaults — change anytime)" with
non-clinical phrasing ("I get overwhelmed by long task lists", "I lose
track of time", "I have low-energy days often", "I prefer fewer
animations and quieter colors", "None of these") and multi-select.
Each option maps to a default toggle (Calm Mode, ADHD Mode, ForgivenessStreak
extension, rest-day prompt). Frame as preferences, not diagnoses, to
sidestep stigma and clinical claims. Single PR; ~4 hours including
the mapping logic and tests.

---

### G7 — Mood-low does not reduce nudge cadence (YELLOW)

`app/src/main/java/com/averycorp/prismtask/domain/usecase/MoodCorrelationEngine.kt`
is **read-only** analytics — it correlates mood with completion but
does not feed back into `OverloadCheckWorker`, `ReminderScheduler`, or
`NotificationProfileResolver`. A user who logs mood 1/5 still gets the
4 PM overload notification, the daily digest, and reengagement nudges
at full cadence. The signal exists; nothing consumes it.

**PROCEED.** Wire a `RecentMoodSignal` reader into the three
schedulers. Rule: if the last logged mood within 48h is ≤2/5, defer
non-medication / non-critical notifications by N hours (configurable;
default 24h). No copy change, no new screen — just gating the existing
schedulers behind a mood floor. Single PR; the worker fan-out is
mechanical but needs a unit-test sweep.

---

## § Adjacent observations (not separate items)

- **AI Coach rename in flight.** A worktree
  `.claude/worktrees/rename-ai-coach-to-executive-assistant` exists,
  suggesting "AI Coach" → "Executive Assistant" is being moved. The
  rename helps the framing question (less clinical-sounding) but does
  not change the safety gap from G2; both should land regardless.
- **Two `DayBoundary.kt` files exist** (`util/` and `core/time/`); both
  are referenced from production. Out of scope for this audit; flagging
  as worth a follow-up. (Already flagged in the Cognitive Load audit.)
- **Pomodoro forgiving-pause** was raised by the gap sweep but the
  `SmartPomodoroViewModel` state machine is not strictly "session fails
  on pause"; the actual behavior is closer to "pause counted as part of
  the session." Premise unconfirmed by direct read — not promoted to a
  RED/YELLOW item. Audit again after R5 ships if Pomodoro becomes a
  Free-tier surface.
- **Notification escalation chains** (`EscalationChain.kt`) are powerful
  enough that, misconfigured, they could become a nag loop. Current
  defaults are conservative (escalation off unless user opts in for a
  given profile) and `ReengagementWorker` respects a max-nudges cap.
  Audit again if escalation defaults are ever inverted.

---

## § Ranked improvement table

Ordered by impact-per-hour. "Cost" is rough PR-size — minutes for copy
fixes, hours for new features. "Impact" is qualitative (HIGH = touches
doctrine or safety; MED = touches behavior; LOW = polish).

| # | Item | Type | Cost | Impact | Notes |
|---|------|------|------|--------|-------|
| 1 | R1 — "Don't break a streak" subtitle | Copy fix | ~15 min | HIGH | Directly contradicts published doctrine. |
| 2 | R4 — "Should be discussed with your provider" (2 sites) | Copy fix | ~20 min | HIGH | Two-string edit; safety-adjacent. |
| 3 | G2 — AI chat safety guidance | Prompt edit + filter | ~1 h | HIGH | Edits one Python string + adds keyword pre-filter. |
| 4 | R2 — "Consider blocking time" (3 sites) | Copy + small UX | ~45 min | HIGH | Three-string edit, one notification text. |
| 5 | R3 — "Work high" / "over target" | Copy fix | ~30 min | HIGH | Same PR as #4 if co-located. |
| 6 | G1 — Crisis resources surface (US v1) | New screen | ~2-3 h | HIGH | Single static screen + two entry points. |
| 7 | G5 — Delete mental-health data action | New Settings flow | ~3-4 h | MED | Cross-table delete + confirmation UX. |
| 8 | G4 — Pause-all quick toggle | Small new control | ~3 h | MED | Today-screen control + scheduler gate. |
| 9 | R5 — Free-tier crisis-only chat fallback | Gate split | ~3 h | MED | Couples with G1+G2; bundle together. |
| 10 | R6 — `NdPreferences.forgivenessStreaks` default / rename | Refactor + migration | ~2 h | MED | Decide remove vs rename first. |
| 11 | G7 — Mood-low → nudge-cadence gate | Cross-cutting wire-up | ~4 h | MED | Touches 3 schedulers; mechanical but needs tests. |
| 12 | G6 — Onboarding ND/MH disclosure | New onboarding step | ~4 h | MED | One screen + mapping table + tests. |
| 13 | G3 — Rest Day / low-spoons action | New behavior + doc | ~1-2 d | HIGH | Largest item; ships its own philosophy doc. |

Recommended bundling for Phase 2:

- **PR bundle A — Copy fixes (R1, R2, R3, R4):** single PR, ~2 hours.
  Coherent scope: "Re-align user-facing copy with FORGIVENESS_FIRST and
  WORK_PLAY_RELAX." Largest cost-effectiveness.
- **PR bundle B — Crisis safety (G1, G2, R5):** single PR, ~5-6 hours.
  Coherent scope: "Add crisis-resources surface, gate Free-tier chat to
  safety responses, prompt-level safety guidance." Couples by design.
- **PR — G5 (delete MH data):** ~4 hours, standalone.
- **PR — G4 (pause-all):** ~3 hours, standalone.
- **PR — R6 (NdPrefs):** ~2 hours, standalone (read first, decide).
- **PR — G7 (mood-low → cadence):** ~4 hours, standalone.
- **PR — G6 (onboarding):** ~4 hours, standalone.
- **PR — G3 (rest day) + REST_DAY.md:** ~1-2 days, standalone.

Total Phase 2 wall-clock if shipped sequentially: ~5-7 working days.
First two bundles alone (8 of 13 items, all four RED items, all three
crisis-safety items) ship in ~8 working hours.

---

## § Anti-patterns to flag (not necessarily fix)

Things this audit *noticed* but is **not** recommending Phase 2 work
for. Capturing so they aren't re-discovered next audit.

- **"AI Coach" as a name.** Coaching framing has a clinical adjacent
  flavor; "Executive Assistant" rename in flight (see § Adjacent).
  Recommend completing that rename before the next MH audit.
- **Streak celebrations** in `ShipItCelebrationManager` are MEDIUM
  intensity by default. For some ND users, surprise celebration
  animations are themselves stressors. Calm Mode forces LOW correctly
  (verified `NdPreferences.kt:65`), but the *default* user with no ND
  mode active gets MEDIUM. Worth a UX research item, not a fix.
- **`OverloadCheckWorker` fires at 4 PM by default.** Late-afternoon
  is statistically the trough for energy in depressive populations.
  Worth re-evaluating after G7 ships (mood-low → cadence) because
  G7 likely supersedes the static 4 PM choice.
- **Empty-state copy audit not exhaustive.** `EmptyState.kt` is a
  shared composable; each caller passes its own strings. A future
  audit could grep every call-site and confirm none read like
  guilt-inducing copy ("You haven't…"). Out of scope here.

---

## § What Phase 2 PRs should reference

Every Phase 2 PR description should cite this audit doc + the relevant
philosophy doc:

- R1, R3, R6: cite `FORGIVENESS_FIRST.md`.
- R2, R3: cite `WORK_PLAY_RELAX.md` § "Descriptive, not prescriptive."
- R4, G1, G2: cite this audit + the new `CrisisResourcesScreen` once
  G1 ships (so R5 can link back to it).
- G3: introduces `REST_DAY.md` (new philosophy doc).
- G6, G7: cite both `FORGIVENESS_FIRST.md` and this audit.

Voice exemplar to point reviewers at: R7
(`"Take care of yourself today — start fresh tomorrow."`).

---

## Phase 3 — Bundle summary

Phase 2 fan-out is in flight as of 2026-05-14. Phase 3 is appended
pre-merge per the project's CLAUDE.md "Audit-first Phase 3 + 4 fire
pre-merge" override — Bundle A is the implementation PR opened
immediately after Phase 1 merged.

### Bundle A — Copy fixes (R1, R2, R3, R4)

**Status:** open in this PR (the one you're reading).

Single-PR shape; the four RED items co-locate by file and share the
philosophical justification (FORGIVENESS_FIRST + WORK_PLAY_RELAX). All
seven affected files touched in one commit; no behavior change beyond
visible copy and one warning glyph removed.

Verified post-edit (via grep on this branch's tree):

```
grep -rn "Don't break\|consider blocking\|Work high\|Work ratio over\|should be discussed" app/src/ backend/
```

returns zero matches. Detekt-clean: orphaned `androidx.compose.ui.unit.sp`
import removed from `TodayBalanceBar.kt` after the `⚠` glyph was deleted.

### Items remaining for follow-up Phase 2 PRs

The audit identified 13 items; this bundle ships 4 (the RED-tagged copy
violations). Remaining work, in the order recommended by the ranked
table:

- **Bundle B — Crisis safety (G1, G2, R5):** `CrisisResourcesScreen` +
  `_CHAT_SYSTEM_PROMPT_BASE` safety block + Free-tier chat-safety gate
  split. ~5-6 hours, single coherent PR. Highest-priority follow-up.
- **G5** — Delete mental-health data action. ~3-4 hours.
- **G4** — Pause-all quick toggle. ~3 hours.
- **R6** — `NdPreferences.forgivenessStreaks` decision (rename or
  remove). ~2 hours.
- **G7** — Mood-low → nudge-cadence gate. ~4 hours.
- **G6** — Onboarding ND/MH disclosure step. ~4 hours.
- **G3** — Rest Day / low-spoons action + `REST_DAY.md`. ~1-2 days.

### Memory candidates (only the non-obvious)

- **Copy-fix audits compose with doctrine docs.** When an audit cites a
  published philosophy doc by name (here: `FORGIVENESS_FIRST.md` line
  85-87 banning "Don't break your streak!"), the highest-impact PRs
  are the ones that resolve a *named contradiction* — they cost
  minutes and resolve a doctrine drift that, left alone, signals to
  the next reader that the doctrine isn't load-bearing. Worth a memory
  if not already captured.
- **Removing a warning glyph orphans a `sp` import.** Detekt-tripping
  follow-on edits from removing a single Text("⚠") + fontSize line.
  Not generally memorable, but worth flagging that copy fixes
  sometimes carry detekt-level follow-ons.

### Schedule for next audit

Re-audit after Bundle B + G5 land. The next sweep should focus on:
- AI Coach rename completion ("Executive Assistant").
- Empty-state copy across all screens (not exhausted here).
- Pomodoro forgiving-pause premise re-verification (G3 may obsolete
  this).
- Whether `OverloadCheckWorker` should still fire on a fixed 4 PM
  schedule once G7 (mood-low → cadence) is live.

---

## Phase 4 — Claude Chat handoff

Paste-ready block for a fresh Claude.ai conversation:

```markdown
# PrismTask Mental-Health-First Audit — handoff for a fresh thread

## Scope
Audited PrismTask (Android Kotlin + Compose + FastAPI backend) for
alignment with its published mental-health-first philosophy
(`docs/FORGIVENESS_FIRST.md`, `docs/WORK_PLAY_RELAX.md`,
`docs/COGNITIVE_LOAD.md`). Output is `docs/audits/MENTAL_HEALTH_FIRST_AUDIT.md`
on `main` as of 2026-05-14 (PR #1396 merged via squash to commit
`eff8ce49`).

## Verdicts table

| # | Item | Verdict | One-line finding |
|---|------|---------|------------------|
| R1 | "Don't break a streak" subtitle | RED → SHIPPED | Direct doctrine violation; rewritten to descriptive in Bundle A. |
| R2 | "Consider blocking time for self-care" (3 sites) | RED → SHIPPED | Prescriptive nudges in TodayBalanceBar / OverloadCheckWorker / BurnoutScorer docstring. |
| R3 | "⚠ Work high" / "⚠️ Work ratio over target" | RED → SHIPPED | Evaluative labels with warning glyphs; replaced with descriptive ones. |
| R4 | "Should be discussed with your provider" (2 sites) | RED → SHIPPED | Prescriptive medical instruction in clinical report; reframed agency-respecting. |
| R5 | AI Coaching Pro-gated | YELLOW | Free-tier users have no fallback if they try to chat in crisis. Couples to G1+G2. |
| R6 | `NdPreferences.forgivenessStreaks` default=false | YELLOW | Confusing relative to global `ForgivenessPrefs.enabled=true`. Rename or remove. |
| R7 | `ReengagementWorker` empathic copy | GREEN | "Take care of yourself today — start fresh tomorrow." — gold-standard exemplar. |
| G1 | No crisis-resources surface | RED | Zero references to 988 / Crisis Text Line / Samaritans / Lifeline anywhere in source. |
| G2 | AI chat lacks crisis-safety guidance | RED | `_CHAT_SYSTEM_PROMPT_BASE` instructs tone but has no self-harm / suicidality rules. |
| G3 | No "Rest Day" / low-spoons action | RED | Users must break grace window or force completion. No deliberate-pause primitive. |
| G4 | No pause-all quick toggle | YELLOW | Quiet hours are scheduled only; no ad-hoc silence affordance. |
| G5 | No delete-mental-health-data UI | YELLOW | Atomic purge of mood/check-in/clinical data not exposed. |
| G6 | Onboarding skips ND/MH disclosure | YELLOW | No preference question to pre-set forgiving defaults at first run. |
| G7 | Mood-low does not gate notification cadence | YELLOW | `MoodCorrelationEngine` is read-only; signal not consumed by schedulers. |

## Shipped

- **PR #1396** — audit doc itself (Phase 1).
- **Bundle A copy fixes** — R1, R2, R3, R4 (PR being opened concurrent
  with this handoff; will receive its own number). Re-aligns
  user-facing copy with `FORGIVENESS_FIRST.md` and
  `WORK_PLAY_RELAX.md`. Touches:
  `ForgivenessStreakSection.kt:37`,
  `TodayBalanceBar.kt:95+263`,
  `OverloadCheckWorker.kt:82`,
  `BurnoutScorer.kt:100`,
  `MorningCheckInScreen.kt:762`,
  `ClinicalReportSection.kt:35`,
  `ClinicalReportGenerator.kt:127`.

## Not yet shipped (PROCEED with documented path)

- **Bundle B (G1+G2+R5)** — crisis-resources surface, AI chat safety
  prompt, Free-tier chat safety gate. Highest-priority follow-up.
- **G3** — Rest Day primitive + `REST_DAY.md`. Largest item, 1-2 days.
- **G4-G7** — pause-all, MH data deletion, ND onboarding, mood-cadence
  gate. ~3-4 hours each, standalone PRs.
- **R6** — `NdPreferences.forgivenessStreaks` rename or remove. Read
  `BrainModeScreen.kt` first to decide.

## Non-obvious findings

- The chat system prompt is already pretty good ("warm, never preachy,
  avoid moralizing"). The gap isn't tone — it's the absence of a
  *safety* branch for self-harm / suicidality inputs.
- `DailyForgivenessStreakCore` is shared by habits, projects, daily
  essentials, AND the ND-friendly Forgiveness-Streak mode. Single
  implementation; the global `ForgivenessPrefs.enabled` defaults
  `true`. But `NdPreferences.forgivenessStreaks` (a separate ADHD
  Mode sub-setting) defaults `false` — wrong mental model.
- The `ReengagementWorker` notification copy is already MH-first ideal
  ("Take care of yourself today — start fresh tomorrow."). It exists
  as a voice exemplar; future implementers should reference it.
- A worktree exists for renaming "AI Coach" → "Executive Assistant".
  Worth completing before next MH audit since the framing matters.
- `OverloadCheckWorker` defaults to firing at 4 PM — statistically the
  energy trough for depressive populations. G7 (mood-low → cadence)
  may obsolete the fixed-time choice.

## Open questions for the operator

- Should crisis-resources v1 ship US-only with a "tap for other
  regions" link out, or should v1 already include an international
  resource list (UK 116 123, AU 13 11 14, CA 988, etc.)?
- Should G7 use a 48h mood window and ≤2/5 floor, or should the
  thresholds be user-configurable from the start?
- For G3 (Rest Day): does marking a day as rest also defer
  medication reminders, or only non-medication notifications?
- For R6: is `NdPreferences.forgivenessStreaks` an extended grace
  window (rename it), or a redundant duplicate of `ForgivenessPrefs`
  (delete it)? Needs a read of `BrainModeScreen.kt` to confirm.
```

