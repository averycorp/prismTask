# Web Pillars & Philosophy Audit — 2026-05-15

**Trigger:** Operator request 2026-05-15 — *"I want to make sure the web
version aligns with the three pillars and the philosophy of the app."*

**Scope:** Validate that `web/src/` reflects the three composable
philosophy primitives PrismTask is built on, plus the cross-cutting
copy / shame-avoidance stance:

1. **Forgiveness-First** streaks — `docs/FORGIVENESS_FIRST.md`
2. **Cognitive Load** (start-friction Easy/Medium/Hard) —
   `docs/COGNITIVE_LOAD.md`
3. **Work / Play / Relax** (task mode) — `docs/WORK_PLAY_RELAX.md`

Plus the fourth composing primitive:

- **Rest Day** — `docs/REST_DAY.md`

And the cross-cutting copy stance shared by all four:
**descriptive, not prescriptive**, **shame-avoidance bias**, **never
inflate Work / difficulty / punctuality**.

This audit is **distinct from feature parity** (covered by Batches 1–7
under `docs/audits/PARITY_BATCH_*`). Parity tracks "does Android feature
X exist on web?"; this audit asks "does web embody the pillar's
*intent*?" — which can fail even when a feature ships.

**Cap:** ≤ 500 lines.
**Base:** `origin/main` @ `8a4dcaee`.

---

## Premises verified

Read directly, not inferred:

- The three philosophy docs cross-reference each other as a composable
  triad. `FORGIVENESS_FIRST.md:13–15`, `WORK_PLAY_RELAX.md:166–172`,
  `COGNITIVE_LOAD.md:105–119` all name the trio explicitly.
- `web/src/types/task.ts:40–46` already carries `task_mode` and
  `cognitive_load` on every task. The Firestore parse/write paths in
  `web/src/api/firestore/tasks.ts:75–76, 215–222, 297–298` round-trip
  both dimensions. Schema parity is real, not aspirational.
- `web/src/utils/streaks.ts:39–49, 128–220` ports the
  `DailyForgivenessStreakCore` algorithm with matching `ForgivenessConfig`
  defaults (`gracePeriodDays = 7`, `allowedMisses = 1`). Parity tests
  exist at `web/src/utils/__tests__/streaks.test.ts:160–228`.
- **Backend has `POST /life-category/classify_text`** at
  `backend/app/routers/ai/eisenhower.py:189`, but **no
  `/task-mode/classify_text` or `/cognitive-load/classify_text`
  endpoints exist**. Auto-button parity for those two dimensions is a
  cross-platform gap — not web-specific.

No premise mismatches that warrant a STOP-and-report.

---

## Pillar 1 — Forgiveness-First (YELLOW overall)

### Algorithm port (GREEN)

`web/src/utils/streaks.ts:39–220` is a faithful port:

- `ForgivenessConfig` defaults match Android (`streaks.ts:39–49`).
- `forgivenessDailyWalk` (`streaks.ts:128–220`) replicates the mid-day
  rule, hard reset, rolling-window walk, and active-day filtering.
- `web/src/utils/__tests__/streaks.test.ts:160–228` carries 11
  *"forgiveness-first parity"* cases naming `DailyForgivenessStreakCore`
  in the preamble.
- `web/src/utils/checkInStreak.ts:33–103` reuses the same semantics for
  morning check-ins.

### Consumer coverage (YELLOW)

Active consumers (calling `DEFAULT_FORGIVENESS` /
`forgivenessDailyWalk`):

- ✅ Habit cards — `web/src/features/habits/HabitListScreen.tsx:339–344`.
- ✅ Habit analytics — `web/src/features/habits/HabitAnalyticsScreen.tsx:128–229`.
- ✅ Morning check-in (computation) — `web/src/utils/checkInStreak.ts`.
- ✅ Habit store — `web/src/stores/habitStore.ts`.

Forgiveness consumers that exist on Android but are **missing** on
web — per `FORGIVENESS_FIRST.md` § *Surfaces that use forgiveness-first*:

- ❌ Weekly Balance Report **burnout band** — `web/src/features/balance/
  WeeklyBalanceReportScreen.tsx` lines 47–52 explicitly defer it
  ("Burnout / cognitive-load sections from the Android screen are out
  of scope").
- ❌ **Project streaks** — no `ProjectRepository.computeProgress`
  analogue on web; project cards do not show a streak.
- ❌ **Daily Essentials card** — surface not ported.
- ❌ **Leisure-budget streak** — leisure pool exists but no streak.
- ❌ **Morning check-in streak (display)** — `checkInStreak.ts` computes
  it but no surface renders it directly. `MorningCheckInCard.tsx:73–78`
  shows a badge only if `streak.current > 0` and the value is passed
  from outside the file.

### Grace knobs in web Settings (RED)

Android exposes `gracePeriodDays` + `allowedMisses` sliders in
Settings → Advanced Tuning → Forgiveness Streak. On web:

- `web/src/features/settings/sections/` lists 13 sections; **none is an
  "Advanced Tuning" section**.
- `NdModesSection.tsx:146–148` only exposes a binary
  `forgivenessStreaks` toggle (ND-mode bundle), **not** the two
  scalar knobs Android exposes. Users on web cannot widen / tighten
  their grace window.

### Anti-patterns (GREEN)

Independently verified by the copy-sweep agent:

- ✅ No red-painted broken streaks. Streak badges render only when
  `streak > 0`; otherwise the badge is hidden. `text-red-*` usage is
  reserved for validation states, not streak loss.
- ✅ No streak-break notifications. `web/src/utils/notifications.ts`
  fires only task reminders.
- ✅ No feature gates on streak length anywhere.
- ✅ No "you missed X days" / "don't break your streak" copy.

### Risk + recommendation

YELLOW. Algorithm + anti-patterns are solid; consumer coverage and the
grace-knobs UI are real gaps. **PROCEED on the grace knobs** (small,
high-impact, single Settings section). **DEFER** project streak / Daily
Essentials / leisure-budget streak / burnout band surfaces to their
respective feature ports.

---

## Pillar 2 — Cognitive Load (YELLOW overall)

### Schema (GREEN)

`web/src/types/task.ts:46, 117, 142, 167` + `web/src/api/firestore/
tasks.ts:75–76, 220–222, 298` — read/write parity, enum mirrored.

### Picker UI (YELLOW)

`web/src/features/tasks/TaskEditor.tsx:1301–1319` renders a `<select>`
dropdown, not a chip row. Per the Android pattern (`LifeCategory`,
`TaskMode`) the Organize-tab convention is **three chip buttons + Auto
button**. The dropdown is functional but breaks the visual + interaction
parity, and the inline copy at `TaskEditor.tsx:1317` ("Leave as
Uncategorized to let *Android* auto-classify") openly acknowledges the
gap.

### Auto button (RED)

No `handleCognitiveLoadAutoClick`. No call to a classifier (local or
AI). The doc says the **AI endpoint is deferred** (`COGNITIVE_LOAD.md:
200–203`), but **the on-device keyword classifier should still ship**
— Android's `CognitiveLoadClassifier.kt:27` is a 100-line keyword bag
that can be ported as-is.

### NLP `-load` hashtags (RED)

`web/src/utils/nlp.ts:85–90` parses generic `#tagname` tags but does
not recognize `#easy-load` / `#medium-load` / `#hard-load`. The doc's
`-load` suffix exists specifically to avoid colliding with LifeCategory
hashtags (`#work`) — adding suffix recognition is a small, local change.

### Settings custom keywords (RED)

No "Advanced Tuning" section on web (same gap as Pillar 1's grace
knobs). Custom keyword input for load tiers cannot exist without it.

### Balance bar load section (DEFERRED)

`TodayBalanceBar.tsx:149` renders LifeCategory only.
`COGNITIVE_LOAD.md:200–203` explicitly defers the load section UI:
*"v1 PR ships the column + sync + tracker + selector but **does not**
add the bar / report UI surfaces — those come in a follow-up once
load data exists in the DB."* DEFERRED on Android too — out of scope.

### Weekly Balance Report load section (DEFERRED)

`WeeklyBalanceReportScreen.tsx:47–52` already names this as out of
scope. Composes with the deferred balance-bar section above.

### Descriptive-only copy (GREEN)

No prescriptive load copy anywhere on web.

### Risk + recommendation

YELLOW. Schema is solid; classifier + NLP + picker-chip parity are real
PROCEED items. **Defer** the load-balance UI sections in lockstep with
the Android decision.

---

## Pillar 3 — Work / Play / Relax (RED overall)

### Schema (GREEN)

`web/src/types/task.ts:40, 111, 141, 163` + tasks.ts round-trip.

### Picker UI (YELLOW)

`web/src/features/tasks/TaskEditor.tsx:1323–1348` renders a `<select>`
dropdown. Same gap as Pillar 2 — needs chip-row UI.

### Auto button (RED)

No `handleTaskModeAutoClick`. No classifier port. Critically: even the
local keyword `TaskModeClassifier` (Android) has no web equivalent. The
AI endpoint `/ai/task-mode/classify_text` does not exist on the backend
yet either, so Auto-button parity requires both a web port of the
keyword classifier **and** a new backend endpoint.

### NLP `-mode` hashtags (RED)

`nlp.ts:85–90` — same gap as Pillar 2: `#work-mode` / `#play-mode` /
`#relax-mode` are not recognized.

### Settings custom keywords (RED)

No Advanced Tuning section; no per-mode keyword input.

### Mode balance bar on Today (RED)

`TodayBalanceBar.tsx:27–192` is hard-coded to the 4 LifeCategory values.
No mode equivalent renders. `WORK_PLAY_RELAX.md:146–149` specifies a
stacked bar or toggle alongside LifeCategory on Today.

### Weekly Balance Report mode section (RED)

`WeeklyBalanceReportScreen.tsx:198–517` renders 5 sections — Distribution,
Targets, Delta, Per-day, 4-week trend — **all LifeCategory-only**. No
mode equivalents. `WORK_PLAY_RELAX.md:147–149` specifies a mode section
*"beneath the existing category section"*.

### Mode-aware streak strictness (RED)

`WORK_PLAY_RELAX.md:78–96` § *Streak strictness* — Play and Relax
default to a **wider** grace window than Work. `web/src/utils/
streaks.ts:39–220` is mode-blind: one global `ForgivenessConfig` applied
uniformly. No branching on `taskMode`. Web users miss the entire
mode-aware-leniency story.

### Tie-break ordering (RED)

No web mode classifier exists, so no `RELAX → PLAY → WORK` tie-break
("never inflate Work") can be honored. Consequence of the missing port.

### Descriptive-only copy (GREEN)

No prescriptive mode copy found.

### Risk + recommendation

RED. Schema is solid but **every consumer surface is missing**. The
mode dimension is the largest philosophy gap on web today.

---

## Composing primitive — Rest Day (RED overall)

`docs/REST_DAY.md` defines the deliberate-pause primitive that
composes with all three pillars. On web:

- ❌ **No Firestore collection.** `web/src/api/firestore/` has 35+
  collections; *zero* mention `restDay`. Compare to Android's
  `RestDayEntity` / `RestDayDao` / `RestDayRepository`.
- ❌ **No Today-screen banner.** No "Resting today — see you tomorrow"
  surface, no "End Rest Day" button.
- ❌ **Structural gap in streak fold.** `web/src/utils/streaks.ts:128`
  signature is `forgivenessDailyWalk(completionMap, today, targetCount,
  activeDays, config)` — *no `restDays` parameter*. Android's
  `DailyForgivenessStreakCore.calculate(completions, today, targetCount,
  activeDays, restDays)` accepts it (`REST_DAY.md:28, 117–123`).
- ❌ **No `restDays` set folded into the activity walk.** Even if a
  rest-day collection landed tomorrow, the streak path could not honor
  it without a signature change.
- ⚪ **Notification gate is N/A.** Web does not fire habit / project /
  balance / digest notifications — only task reminders — so
  `RestDayGate` has nothing to gate. The gate becomes load-bearing only
  if web's notification surface expands.

### Risk + recommendation

RED. Rest Day is the largest *cross-cutting* philosophy gap on web —
it intersects forgiveness streaks, work/play/relax, cognitive load, and
the descriptive-not-prescriptive copy stance simultaneously. **PROCEED**
on a full port: Firestore collection, repository, Today banner,
signature change in `streaks.ts`, fold into all streak consumers.

---

## Cross-cutting — Descriptive, not prescriptive (GREEN)

Independent copy sweep verdict:

- ✅ No "Don't break your streak", "you should", "you need to",
  "schedule a Hard task", "schedule more Play", "you missed X days",
  "you earned this" anywhere in `web/src/`.
- ✅ `SelfCareNudgeEngine` framing is *invitational* ("how about a
  15-minute break?") not prescriptive.
- ✅ `TodayBalanceBar.tsx:107–110` "Work high" is a **factual state
  label**, not a value judgment. Per
  `WORK_PLAY_RELAX.md:63–75` / `COGNITIVE_LOAD.md:84–103` this is the
  intended shape.
- ✅ `WorkLifeBalanceSection.tsx:131–137` red text on target-sum
  validation is *input validation*, not streak shame.

This pillar's stance is **already honored on web**. Any new copy
introduced by Phase 2 PRs must preserve it.

---

## Ranked improvement table

Sorted by **wall-clock-savings ÷ implementation-cost**. PROCEED items
only; DEFERRED items follow the table.

| # | Improvement | Pillar | Est. cost | Impact | Why this rank |
|---|-------------|--------|-----------|--------|----------------|
| 1 | NLP parser: recognize `#work-mode` / `#play-mode` / `#relax-mode` and `#easy-load` / `#medium-load` / `#hard-load` in `web/src/utils/nlp.ts` and surface in `LocalParseResult` | 2, 3 | XS (~30 LOC + tests) | High — both dimensions become user-taggable via quick-add | Tiny diff, two pillars covered, mirrors Android NLP path |
| 2 | Swap `<select>` for chip-row + Auto button skeleton (no AI yet — on-device classifier only) on **TaskMode** and **CognitiveLoad** in `TaskEditor.tsx`; mirror the LifeCategory chip pattern | 2, 3 | S (~150 LOC) | High — picker parity with LifeCategory, "Auto" surfaced even pre-AI | Visible UX delta; classifier port is bounded; backend AI deferred separately |
| 3 | Port Android `TaskModeClassifier` + `CognitiveLoadClassifier` keyword bags to TS under `web/src/utils/` (mirror `streaks.ts` port shape) | 2, 3 | S (~200 LOC + parity tests) | High — Auto buttons (#2) become functional | Unblocks #2; parity tests can mirror Android unit tests |
| 4 | Add Settings → Advanced Tuning section with `gracePeriodDays` + `allowedMisses` sliders, mode/load custom-keyword inputs; wire to `userPreferences` Firestore mirror | 1, 2, 3 | S (~250 LOC) | Med-high — closes the per-user tuning gap across three pillars | Single new section, three pillars unblocked |
| 5 | Mode-aware streak strictness: extend `ForgivenessConfig` shape to accept per-mode overrides; update `forgivenessDailyWalk` to read mode of the activity and apply wider window for Play/Relax | 1, 3 | M (~300 LOC + parity tests) | Med — honors `WORK_PLAY_RELAX.md:78–96` | Touches streaks.ts signature; requires careful test coverage |
| 6 | **Rest Day** end-to-end: Firestore collection (`restDays/{uid}/{isoDate}`), `restDayStore.ts`, `useFirestoreSync` listener, Today-screen banner with "End Rest Day", `streaks.ts` signature change to accept `restDays: Set<string>`, fold into walk as kept-by-definition, plumb through all streak consumers | 1, 4 | L (~600 LOC) | High — closes the largest cross-cutting philosophy gap | Bigger PR; isolated to rest-day code paths so risk is contained |
| 7 | Backend: add `POST /task-mode/classify_text` + `POST /cognitive-load/classify_text` endpoints (mirror existing `/life-category/classify_text`); wire web Auto buttons to call them when AI Features is on | 2, 3 | M (~300 LOC backend + 60 LOC web) | Med — promotes Auto from keyword-only to AI-augmented | Closes the AI-augment leg of the Auto button parity |
| 8 | Project streak display on web project cards — calls `forgivenessDailyWalk` over `task_completions` joined to project | 1 | M (~250 LOC) | Med — closes a forgiveness consumer gap | Independent of pillars work; could batch with project-port follow-up |

---

## Deferred / out-of-scope

- **Cognitive Load balance bar section** on Today + Weekly Balance
  Report. `COGNITIVE_LOAD.md:200–203` explicitly defers this on Android.
  Web should follow when Android ships it.
- **Mode balance bar + Weekly Balance Report mode section.** Larger UI
  work that warrants its own audit and PR — flagged but not bundled
  here so Phase 2 stays scoped.
- **Daily Essentials card** + **Leisure-budget streak** display — port
  tracked under `WEB_PARITY_GAP_ANALYSIS.md` already; not philosophy-
  blocking once the forgiveness algorithm + Rest Day land.
- **Service-worker / web-push** rest-day gate — moot until web's
  notification surface expands. Document in `REST_DAY.md` § Web parity
  when the time comes.
- **Productive-day streak.** Forgiveness doc's open question — not
  ported to web at all today; defer until Android resolves the
  reframing per `FORGIVENESS_FIRST.md` § Open questions.

---

## Anti-pattern list (worth flagging, not necessarily fixing)

- `TaskEditor.tsx:1317, 1349` — both pickers' helper copy says *"Leave
  as Uncategorized to let **Android** auto-classify"*. Once the web
  classifier ports land (#3), strip the Android-specific phrasing.
- `WeeklyBalanceReportScreen.tsx:47–52` comment explicitly names the
  mode + load gaps as out of scope. The comment is honest, but the
  gap it documents is one of the bigger philosophy holes. Update the
  comment as sections land.
- `NdModesSection.tsx:146–148` exposes `forgivenessStreaks` as a binary
  toggle. The Android doc shape is *two scalar knobs*. The ND binary
  is a quick on-ramp, not a substitute — once the Advanced Tuning
  section lands, keep both surfaces and reconcile their interaction.
- All three pickers (LifeCategory chips, TaskMode `<select>`,
  CognitiveLoad `<select>`) live inside `TaskEditor.tsx` and have
  drifted in shape. Consider extracting a `<ClassificationChipRow />`
  primitive when shipping #2.

---

## Phase 2 plan

Phase 2 fires automatically per the audit-first skill (skip the
checkpoint). The PROCEED items above ship as parallel worker PRs on
separate worktrees, each squash-merged via `gh pr merge --auto`.

Recommended dispatch shape: **5 parallel workers** for the foundational
items (NLP hashtags, chip-row UI, classifier port, Advanced Tuning
section, Rest Day end-to-end). Mode-aware strictness (#5) blocks on the
classifier port; backend endpoints (#7) block on a separate backend
audit; project streak (#8) batches with the existing project-port work.

Phase 3 summary + Phase 4 Claude Chat handoff fire pre-merge per
CLAUDE.md § *Audit-first Phase 3 + 4 fire pre-merge*.

---

## Phase 3 — Bundle summary (post-merge)

Phase 2 dispatched 4 parallel workers off `origin/main`. All 4 PRs +
the audit doc itself merged to `main` the same session (2026-05-15):

| # | PR | Title | Files | Tests added | Phase 2 item |
|---|----|----|----|----|----|
| Audit | [#1501](https://github.com/averycorp/prismTask/pull/1501) | `docs(audit): web pillars + philosophy alignment audit (Phase 1)` | `docs/audits/WEB_PILLARS_PHILOSOPHY_AUDIT.md` (385 LOC) | — | — |
| A | [#1502](https://github.com/averycorp/prismTask/pull/1502) | `feat(web/nlp): recognize #work-mode/#play-mode/#relax-mode and #easy-load/#medium-load/#hard-load hashtags` | `web/src/utils/nlp.ts`, `nlp.test.ts` | 20 new (`nlp.test.ts` 67 total) | #1 |
| B | [#1503](https://github.com/averycorp/prismTask/pull/1503) | `feat(web/tasks): chip-row pickers + on-device Auto for Task Mode and Cognitive Load` | `taskModeClassifier.ts`, `cognitiveLoadClassifier.ts`, `ClassificationChipRow.tsx`, `TaskEditor.tsx` | 23 (10 mode + 13 load, mirrored from Android JUnit) | #2 + #3 (bundled) |
| C | [#1504](https://github.com/averycorp/prismTask/pull/1504) | `feat(web/settings): Advanced Tuning — forgiveness knobs + classifier custom keywords` | `advancedTuningPreferences.ts`, `advancedTuningStore.ts`, `AdvancedTuningSection.tsx`, `streaks.ts`/`checkInStreak.ts`/`habitStore.ts` rewired, `useFirestoreSync.ts` (18th listener), `CHANGELOG.md` | 8 (5 component + 3 streak-config) | #4 |
| D | [#1507](https://github.com/averycorp/prismTask/pull/1507) | `feat(web/today): Rest Day end-to-end — Firestore + streak fold + Today banner` | `restDays.ts`, `restDayStore.ts`, `RestDayBanner.tsx`, `streaks.ts`/`checkInStreak.ts` signature change, all consumers rewired, `useFirestoreSync.ts` (19th listener), `TodayScreen.tsx` rest-day branch, `CHANGELOG.md` | 22 (9 store + 7 banner + 6 streak parity) | #6 |

**Aggregate measured impact:**

- **73 new tests** added across the 4 PRs (`nlp.test.ts` 20 +
  classifier ports 23 + advanced-tuning suite 8 + rest-day suite 22),
  all green on every worker's pre-push verification.
- **Web test suite** went from 889 → 943 cases (worker D's `npm run
  test:run` final count) over the four PRs.
- **Two new Firestore listeners** wired into `useFirestoreSync.ts`
  (`advancedTuningPreferences` + `restDays`); listener count now **19**
  (up from 17 at audit time).
- **Two new Firestore collections** under `users/{uid}/`:
  `advanced_tuning_prefs` (single doc) and `restDays/{isoDate}` (one
  doc per logical day).
- **Three pillars + Rest Day** now have first-class web representation:
  - **Forgiveness-First**: grace knobs surfaced in Settings → Advanced
    Tuning; the streak path reads user prefs with `DEFAULT_FORGIVENESS`
    fallback; rest days fold into the walk as kept-by-definition.
  - **Cognitive Load**: chip-row picker, Auto button (on-device
    classifier), NLP `-load` hashtags, Settings custom keywords.
  - **Work/Play/Relax**: chip-row picker, Auto button (on-device
    classifier with `RELAX → PLAY → WORK` tie-break preserved), NLP
    `-mode` hashtags, Settings custom keywords.
  - **Rest Day**: Firestore collection + sync, SoD-aware store, Today
    takeover banner with "Yes, rest today" / "Not yet" dialog, full
    streak fold across all consumers.

**Memory entry candidates (surprising / non-obvious):**

- `feedback_audit_first_phase2_fan_out_speed.md` — 4-worker parallel
  Phase 2 fan-out shipped audit + 4 PRs in a single ~30-minute session;
  validate the parallel-worker shape for future philosophy audits.
- `project_web_pillars_listener_count.md` — `useFirestoreSync.ts`
  listener count is 19 as of 2026-05-15 (added
  `advancedTuningPreferences` and `restDays` on top of the 17 the
  parent audit logged).

**Phase 2 items NOT shipped in this round:**

- **#5 mode-aware streak strictness** — blocked at audit time on
  classifier port. Classifier port is now in main (#1503), so #5 is
  unblocked. Recommend follow-up PR.
- **#7 backend `/ai/task-mode/classify_text` + `/ai/cognitive-load/
  classify_text`** — backend audit needed first (out of scope here).
  On-device classifiers ship today; AI-augmented Auto is a follow-up.
- **#8 project streak display** — batches with existing project-port
  audit work; not pillar-blocking.

**Schedule for next audit:**

- After items #5 + #7 land, re-audit Pillar 3 RED→YELLOW transition.
- After #5 lands, re-audit `WORK_PLAY_RELAX.md` § *Streak strictness*
  coverage end-to-end.
- Burnout band, project streak, Daily Essentials, leisure-budget streak
  surfaces remain DEFERRED — re-audit when their feature ports land.
- Service-worker / web-push gate becomes load-bearing only if web's
  notification surface expands. Re-audit at that boundary.
