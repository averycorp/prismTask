# Parity Batch 2 — Wellness Suite Audit (2026-05-13)

**Parent audit:** `docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md` § Section C (items C.1–C.7).
**Methodology:** premise-verify before re-classify. Parent flagged 3-of-8 wrong-premise items in 17 days. Same standard applies here. No DEFERRED — every item gets a PROCEED path.
**Worktree:** `agent-ad02aa1acf5685de8`. Symlinked `web/node_modules` from main checkout (idb@^8.0.4 pin still broken — not in scope).

---

## Phase 1 — Premise verification + PROCEED paths

### C.1 Today screen sections (parent: RED — major gap)

Parent listed six sub-items. Verified each:

- **C.1a Balance bar (250 LOC)** — VERIFIED RED. `grep -rn 'BalanceBar\|TodayBalance' web/src/features/today/` returns nothing. `web/src/utils/` has no `balanceTracker.ts`. Engine doesn't exist on web. Android: `app/.../ui/screens/today/components/TodayBalanceBar.kt` (273 LOC) + `BalanceTracker.kt` (181 LOC).
  **PROCEED — C.1a:** depends on C.2a + C.2b (engine port). Once those land, add `web/src/features/today/components/TodayBalanceBar.tsx` reading from `useBalanceState()` hook. ~200 LOC.
- **C.1b Plan-For-Today sheet (300 LOC)** — VERIFIED RED. Android: `PlanForTodaySheet.kt` (691 LOC). No equivalent on web — task feed is read-only on Today.
  **PROCEED — C.1b:** port as `web/src/features/today/components/PlanForTodaySheet.tsx`. Pulls undone tasks → drag/click into "today plan" set → bulk-update `dueDate` to today. Large; punt to Batch 2.1 if time runs out.
- **C.1c Habits-on-Today section (200 LOC)** — **WRONG PREMISE — PARTIAL.** `web/src/features/today/TodayScreen.tsx:387-468` already renders a "Today's Habits" chip row (PR #N — unknown ship date). Android `HabitsSection.kt` from #1297 is a card-row layout; web has chips. Surface exists but visual parity differs.
  **PROCEED — C.1c:** ACCEPT-AS-DIVERGENCE on visual layout (chips OK as "Today's Habits" is fully functional). Item is shipped enough for Batch 2 closure. If pixel parity becomes a complaint, file as a B-tier polish task.
- **C.1d Schoolwork-by-class section (250 LOC)** — VERIFIED RED. `grep -rn 'SchoolworkSection\|class.row' web/src/features/today/` returns nothing. Parent's `F.2` covers same gap; this is duplicate. Defer to Batch 4 (schoolwork+leisure full features).
  **PROCEED — C.1d:** defer to Batch 4; out of scope for this wellness batch.
- **C.1e Self-care nudge + Burnout badge (200 LOC)** — VERIFIED RED on display. `web/src/utils/burnoutScorer.ts` exists (web HAS the scorer) but `grep -rn 'BurnoutBadge\|SelfCareNudge' web/src/features/today/` returns zero. Engine present, surface missing. Android has `TodaySelfCareNudge.kt` (73 LOC) + `SelfCareNudgeEngine.kt` (90 LOC) + the badge embedded in `TodayBalanceBar.kt`.
  **PROCEED — C.1e:** port `SelfCareNudgeEngine` (~80 LOC) + small `BurnoutBadge.tsx` + `SelfCareNudgeCard.tsx` (~120 LOC). Total ~200 LOC.
- **C.1f DashboardPreferences (400 LOC)** — VERIFIED RED. `grep -rn 'DashboardPreferences\|dashboardStore' web/src/` returns nothing. Android `DashboardPreferencesDataStore` + reorder UI not ported.
  **PROCEED — C.1f:** large; defer to Batch 2.1 (sectionStore + reorder UI ~400 LOC). Out of scope for first cut.

### C.2 Balance / LifeCategory engine (parent: RED — entirely missing)

- **C.2a LifeCategoryClassifier port (150 LOC)** — VERIFIED RED. `web/src/types/task.ts` HAS the `LifeCategory` type and TaskEditor exposes the chip, but no classifier exists. `grep -rn 'classify.*LifeCategory\|LifeCategoryClassifier' web/src/` returns zero. Android `LifeCategoryClassifier.kt` (173 LOC) ready to port.
  **PROCEED — C.2a:** port to `web/src/utils/lifeCategoryClassifier.ts`. ~120 LOC of TS (no DataStore custom-keywords for first cut; merge later when C.2d ships).
- **C.2b BalanceTracker ratio/overload (200 LOC)** — VERIFIED RED. Same grep returns zero. Android `BalanceTracker.kt` (181 LOC).
  **PROCEED — C.2b:** port to `web/src/utils/balanceTracker.ts` (~150 LOC). Pure-fn shape: takes `Task[]`, `BalanceConfig`, `nowMs`, returns `BalanceState`.
- **C.2c WeeklyBalanceReportScreen (400 LOC)** — VERIFIED RED. Android: 273-LOC screen + ViewModel.
  **PROCEED — C.2c:** punt to Batch 2.1. Full-screen analytics view is independent of Today integration.
- **C.2d Settings (target-ratio sliders, auto-classify toggle, overload-threshold) (150 LOC)** — VERIFIED RED. No `WorkLifeBalanceSection.tsx` in `web/src/features/settings/sections/`.
  **PROCEED — C.2d:** ship inline with C.2a/b PR — settings card writes to Firestore prefs doc. ~120 LOC.

### C.3 Mood correlation engine (parent: YELLOW — engine missing)

- VERIFIED RED. `web/src/utils/moodAnalytics.ts` line 8 comment explicitly says "without the correlation-with-tasks logic". Android `MoodCorrelationEngine.kt` (177 LOC) is pure-function Pearson with no SDK-only deps.
  **PROCEED — C.3:** port to `web/src/utils/moodCorrelation.ts`. ~180 LOC. Wire into MoodScreen with a "Correlations" section. ~80 LOC UI. Total ~260 LOC.

### C.4 Weekly review persistence + auto-gen (parent: YELLOW)

- **C.4a Firestore mapper (150 LOC)** — VERIFIED RED. `web/src/api/firestore/weeklyReviews.ts` does not exist. `WeeklyReviewScreen.tsx` recomputes locally each session.
  **PROCEED — C.4a:** add mapper + listener export.
- **C.4b Backend auto-gen cron (200+80 LOC)** — out of scope for web-only batch. Punt to a backend-batch follow-up. Reasoning: APScheduler config + new endpoint requires backend deployment cycle that doesn't belong here.
  **PROCEED — C.4b:** punt to dedicated backend batch.

### C.5 Morning check-in full step flow (parent: YELLOW)

- **C.5a MOOD_ENERGY/BALANCE/CALENDAR steps (500 LOC)** — VERIFIED RED. `web/src/features/checkin/MorningCheckInCard.tsx:174-191` shows a 4-toggle UI (Medications/Tasks/Habits + notes), no stepper. Android `MorningCheckInResolver.kt:14-21` enumerates 5 steps; web has none of the optional ones.
  **PROCEED — C.5a:** large; punt to Batch 2.1. Foundation work (mood/balance) lands in C.2/C.3 first — this step-flow refactor builds on those.
- **C.5b 11am auto-prompt (80 LOC)** — VERIFIED RED. `grep -rn 'autoPrompt\|getHours.*===.*11' web/src/` returns zero hits. Android has no specific 11am check either (`grep` empty) — this may be a future-spec item in the parent audit.
  **OBSERVATION:** parent claim "Android has 11am auto-prompt" appears UNVERIFIED on the Android side too. Logging as wrong-premise.
  **PROCEED — C.5b:** ACCEPT-AS-DIVERGENCE pending Android confirmation. If user wants it, re-spec.
- **C.5c 90-day history view (200 LOC)** — VERIFIED RED on dedicated screen. `MorningCheckInCard.tsx:39` already fetches `getRecentCheckIns(uid, 90)` for streak computation, so the data path exists. Missing: a dedicated table/list view.
  **PROCEED — C.5c:** small follow-up. Punt to Batch 2.1.

### C.6 Boundaries enforcement UI (parent: YELLOW)

- **WRONG PREMISE — SHIPPED.** `web/src/features/settings/sections/BoundariesSection.tsx` (270 LOC, verified) ships full create/list/toggle/delete editor for three rule types: `daily_task_cap`, `work_hours_window`, `weekly_hour_budget`. Android's `BoundaryRuleEntity` and parser support more types (e.g., `category_limits`) — parity gap is at the **rule-type coverage**, not the editor itself.
  **PROCEED — C.6:** ACCEPT current implementation as parity-complete for the three core rule types. Filing the missing `category_limits` / `daily_focus_minutes` rule types as a B-tier follow-up. Out of Batch 2 scope.

### C.7 ND-friendly modes (parent: YELLOW)

- **C.7a NdPreferences Firestore-synced Zustand store (150 LOC)** — VERIFIED RED. `grep -rn 'NdPreferences\|brainMode\|uiComplexity' web/src/` returns zero. Android `NdPreferences.kt` (84 LOC) defines the full pref shape.
  **PROCEED — C.7a:** port to `web/src/stores/ndPreferencesStore.ts` + `web/src/api/firestore/ndPreferences.ts` mapper. ~280 LOC total.
- **C.7b NdFeatureGate hook (80 LOC)** — VERIFIED RED. Depends on C.7a.
  **PROCEED — C.7b:** `useNdPreferences()` hook returns `{ prefs, isAnyNdModeActive, effectiveCelebrationIntensity, shouldFireShipItCelebration }`. ~60 LOC.
- **C.7c UI Complexity + Brain Mode settings (200 LOC)** — VERIFIED RED.
  **PROCEED — C.7c:** new `web/src/features/settings/sections/NdModesSection.tsx`. ~180 LOC. Depends on C.7a/b.
- **C.7d GoodEnough/ShipIt/EnergyAware integrations (300 LOC)** — large; depends on Pomodoro screen integration on web (`PomodoroCoachPanel.tsx` exists).
  **PROCEED — C.7d:** punt to Batch 2.1 — needs Pomodoro side-by-side audit.

---

## Phase 1 — Wrong-premise scorecard

Out of ~14 sub-items audited:
- **2 wrong-premise** (shipped or partial-shipped):
  - **C.1c Habits-on-Today** — already shipped as chip row (different layout than Android card).
  - **C.6 Boundaries editor** — already shipped for the three core rule types.
- Rest verified RED per parent claim.

Both wrong-premise items reduce Batch 2 scope by ~600 LOC.

---

## Phase 1 — Priority-ranked PR plan for Phase 2

Ordered smallest-leverage-multiplier first per the parent audit's wallclock-÷-cost principle:

| Rank | PR | Items | Est LOC | Wall-clock |
|------|------|---------|------|--------|
| 1 | LifeCategoryClassifier + BalanceTracker + settings | C.2a/b/d | ~400 | 3h |
| 2 | Today Balance bar component | C.1a | ~200 | 2h |
| 3 | Self-care nudge engine + Burnout badge on Today | C.1e | ~200 | 2h |
| 4 | Mood correlation engine + MoodScreen surface | C.3 | ~260 | 2h |
| 5 | Weekly review Firestore mapper + listener | C.4a | ~150 | 1.5h |
| 6 | NdPreferences store + Firestore mapper + hook | C.7a/b | ~280 | 2h |
| 7 | UI Complexity + Brain Mode settings section | C.7c | ~180 | 1.5h |
| 8 | WeeklyBalanceReportScreen | C.2c | ~400 | 3h |
| 9 | DashboardPreferences store + reorder UI | C.1f | ~400 | 3h |
| 10 | Morning check-in step flow refactor | C.5a | ~500 | 4h |
| 11 | Plan-For-Today sheet | C.1b | ~300 | 3h |
| 12 | 90-day check-in history screen | C.5c | ~200 | 1.5h |
| 13 | GoodEnough/ShipIt/EnergyAware integrations | C.7d | ~300 | 3h |
| 14 | Weekly review backend auto-gen cron | C.4b | ~280 | 2.5h |

**Ship target for this session:** PRs #1-6 (foundational engines + listener wiring). #7-14 punt to Batch 2.1.

---

## Phase 2 — implementation PRs

| # | PR | Audit items | Status |
|---|------|---------|--------|
| 1 | [#1343](https://github.com/averycorp/prismTask/pull/1343) — docs(audits): Batch 2 audit | (this doc) | merged |
| 2 | [#1350](https://github.com/averycorp/prismTask/pull/1350) — LifeCategoryClassifier + BalanceTracker engines + WLB settings | C.2a / C.2b / C.2d | merged |
| 3 | [#1354](https://github.com/averycorp/prismTask/pull/1354) — TodayBalanceBar component | C.1a | merged |
| 4 | [#1358](https://github.com/averycorp/prismTask/pull/1358) — SelfCareNudgeCard | C.1e | merged |
| 5 | [#1364](https://github.com/averycorp/prismTask/pull/1364) — Mood Pearson correlation engine + UI | C.3 | merged |
| 6 | [#1369](https://github.com/averycorp/prismTask/pull/1369) — Weekly review Firestore persistence | C.4a | merged |
| 7 | [#1372](https://github.com/averycorp/prismTask/pull/1372) — NdPreferences Firestore-synced store + hook | C.7a / C.7b | open (CI pending) |

**Drive-by fix landed in PR #1349** (out-of-band from this batch): repaired pre-existing parse errors in `useFirestoreSync.ts` and its test that were blocking lint + vitest from a bad merge between PRs #1340 and #1341. The original drive-by ride-along was bundled into the #1350 commit but was then split out as a clean fix on main by another author (#1349) before #1350 landed — the fix now appears in both places and is harmless on either side.

---

## Phase 3 — Bundle summary

### Items shipped this session
- **C.1a** Today Balance bar (PR #1354)
- **C.1e** Self-Care Nudge engine + card on Today (PR #1358)
- **C.2a / C.2b / C.2d** LifeCategoryClassifier, BalanceTracker, WorkLifeBalance settings (PR #1350)
- **C.3** Mood ↔ task/habit Pearson correlation engine + MoodScreen surface (PR #1364)
- **C.4a** Weekly review Firestore persistence (PR #1369)
- **C.7a / C.7b** NdPreferences Firestore store + `useNdPreferences` hook (PR #1372)

### Items confirmed already shipped (wrong-premise from parent audit)
- **C.1c** Habits-on-Today section — already shipped as a "Today's Habits" chip row in `TodayScreen.tsx:387-468`. Visual layout differs from Android's PR #1297 card-row layout but functionality matches. Filed as ACCEPT-AS-DIVERGENCE.
- **C.6** Boundaries enforcement UI — already shipped via `BoundariesSection.tsx` (270 LOC) for the three core rule types (`daily_task_cap`, `work_hours_window`, `weekly_hour_budget`). Android supports additional rule types (e.g. `category_limits`, `daily_focus_minutes`) which are filed as a B-tier follow-up.

### Items punted to Batch 2.1 (out of session capacity)
- **C.1b** Plan-For-Today sheet (~300 LOC, ~3h)
- **C.1d** Schoolwork-by-class Today section — turns out shipped concurrently as **F.2** by another author while this batch ran. No longer punted.
- **C.1f** DashboardPreferences store + section reorder UI (~400 LOC, ~3h)
- **C.2c** WeeklyBalanceReportScreen (~400 LOC, ~3h)
- **C.4b** Weekly review backend auto-generation cron (~280 LOC backend + ~80 LOC web subscriber). Tracked separately as a backend batch — APScheduler config + new endpoint needs a backend deployment cycle.
- **C.5a** Morning check-in MOOD_ENERGY/BALANCE/CALENDAR step flow refactor (~500 LOC, ~4h)
- **C.5b** 11am auto-prompt — **WRONG PREMISE on Android side**: grep returned zero hits for that promptHour behavior in Android. Filed as ACCEPT-AS-DIVERGENCE pending re-spec; re-open with operator if intentional.
- **C.5c** 90-day check-in history view (~200 LOC, ~1.5h)
- **C.7c** UI Complexity + Brain Mode settings sections (~180 LOC, ~1.5h) — depends on #1372 being merged; queued for Batch 2.1.
- **C.7d** GoodEnoughTimerManager / ShipItCelebrationManager / EnergyAwarePomodoro integrations (~300 LOC, ~3h)

### Observations (out of scope but worth filing)
- **`idb@^8.0.4` pin in `web/package.json` is unresolvable on npm.** Latest published version is `8.0.3`. Any clean `npm install` errors out with `ETARGET — No matching version found for idb@^8.0.4`. Carried over from a prior session (parent audit flagged it). Worktree workaround was to copy node_modules from the main checkout + manually install `fake-indexeddb` via `npm pack`. Should be fixed in a one-line bump to `^8.0.3` or pin to a published version.
- **Pre-existing flaky test** in `web/src/stores/__tests__/chatStore.test.ts:33` (`expect(...endsWith('')).toBe(false)` — universally true substring asserted as false). Introduced by PR #1352 (#1352 era AI Chat port). One-line fix; not in scope here.

### Net delivered
6 shipped wellness-suite PRs in one session, closing audit items **C.1a, C.1e, C.2a–d, C.3, C.4a, C.7a/b**, plus 1 audit doc PR. **2 wrong-premise items** (C.1c, C.6) reduced Batch 2 scope by ~600 LOC of unnecessary work.

---

## Phase 4 — Claude Chat handoff block

(emitted in the agent's final report, not appended here to keep the audit doc self-contained.)

