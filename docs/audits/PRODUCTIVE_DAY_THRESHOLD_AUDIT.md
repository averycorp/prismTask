# Productive-Day Score Threshold — Forgiveness-First Alignment Audit

**Scope.** Productive-day score display and the `score ≥ 60` threshold logic across the app — does the binary good-day/bad-day shape contradict forgiveness-first philosophy ("a miss is not a failure"), and if so, what reframe options are available?

**Outcome.** Audit-only this pass. The reframe options live in the verdict matrix below; the operator picks one before Phase 2 implementation. This audit composes with `docs/FORGIVENESS_FIRST.md` § "Open questions" — that doc already names this surface as a documented exception with three candidate reframes; this audit verifies the state and elaborates the option space.

---

## Phase 0 — Premise verification

| # | Premise | Verdict | Citation |
| --- | --- | --- | --- |
| P1 | Productive-day threshold = 60 | ✓ | `ProductiveStreakPreferences.kt:49` — `const val PRODUCTIVE_DAY_SCORE_THRESHOLD = 60` |
| P2 | `TaskAnalyticsScreen` ("Productivity Dashboard") is the primary surface | ✓ (path drift) | `ui/screens/analytics/TaskAnalyticsScreen.kt:81` — title `"Productivity Dashboard"`. Prompt had `ui/screen/` (singular); actual is `ui/screens/`. |
| P3 | Productive-day streak rollover piggybacks on `DailyResetWorker` (SoD-aware) | ✓ | `DailyResetWorker.kt:67-78` calls `productiveStreakResolver.resolveYesterday()`, which uses `clock.zone` and `LocalDate.now(clock)` |
| P4 | App is at v1.8.49+ | ✓ (informational drift) | `app/build.gradle.kts:22` — `versionName = "1.9.26"`, significantly past 1.8.49 floor |
| P5 | Companion docs exist | ✓ | `docs/FORGIVENESS_FIRST.md`, `docs/COGNITIVE_LOAD.md`, `docs/WORK_PLAY_RELAX.md` all present |

No STOP-A. Path drift in P2 is harmless (single-character `screens` vs `screen`). P4 drift is informational — the threshold and surrounding code have not changed semantics since 1.8.49 per `git log` review of `ProductiveStreakPreferences.kt`. Proceed to Phase 1.

---

## Phase 1 — Quad sweep

### (a) Threshold definition + computation — (GREEN — well-located, single source of truth)

**Threshold.** `ProductiveStreakPreferences.kt:49` declares `const val PRODUCTIVE_DAY_SCORE_THRESHOLD = 60`. Single companion constant; not user-configurable (no DataStore surface, no Settings UI).

**Score formula.** `ProductivityScoreCalculator.kt:65-70`:

```
score = (taskCompletionRate * 0.40
       + onTimeRate          * 0.25
       + habitRate           * 0.20
       + estimationAccuracy  * 0.15).coerceIn(0.0, 100.0)
```

Weights are user-configurable via `AdvancedTuningPreferences.kt` → `ProductivityWeights` (the *weights* and `trendThreshold = 3.0` are tunable, but the productive-day threshold itself is not). Empty-bucket fallback is `DEFAULT_RATE = 100.0` for each subscore (deliberately optimistic when there's no data; documented at `:149`).

**Score consumers.** Score is consumed both rounded-to-int (badge, streak resolver) and as a float (charts, heatmap). The threshold compare uses `score.roundToInt() >= 60` (`ProductiveStreakResolver.kt:68-69`). So a raw score of 59.5 advances the streak, 59.4 does not.

**Configurability.** **Not user-configurable** for the productive-day binary itself. The widget has its *own* configurable color thresholds at `ProductivityWidgetThresholds(greenScore=80, orangeScore=60)` (`AdvancedTuningPreferences.kt:202-205`) — these default to mirror the productive-day threshold but are independent constants, configurable in Settings → Advanced Tuning.

**Edge cases at the threshold:**

- Score = 0: heatmap "very low" bucket (`emptyTint`, same as no-data); badge `destructiveColor` red. Streak: reset.
- Score = 59: heatmap `accent.copy(alpha = 0.30f)`; badge `warningColor` yellow; widget `scoreOrange`/`scoreOrangeBg`. Streak: reset (notification fires if a run was active).
- Score = 60: heatmap `accent.copy(alpha = 0.55f)`; badge `primary` accent; widget `scoreOrange`/`scoreOrangeBg` (60 ≥ orangeScore). Streak: advance.
- Score = 79: same alpha bucket as 60 (`0.55f`); badge same `primary`; widget `scoreOrange`. Streak: advance.
- Score = 80: heatmap full alpha; badge `successColor` green; widget `scoreGreen`/`scoreGreenBg`. Streak: advance.
- Score = 100: identical to 80 across all surfaces. The 80 threshold is the visible top tier; 100 has no special treatment.

The 59→60 transition is **the single hard-binary jump** on the streak axis. On the heatmap and badge it's *a* tier transition among several. The widget treats 60 as the orange threshold (60 is orange, not green), so the streak qualification line and the widget's color line are *not* aligned at the same color jump.

### (b) User-facing binary surfaces — (YELLOW — gradient on display surfaces, binary on streak)

**TaskAnalyticsScreen / Productivity Dashboard** (`TaskAnalyticsScreen.kt`):

- Title `"Productivity Dashboard"` (`:81`). Pro-gated; free tier sees `AnalyticsSectionProUpsell("Unlock Productivity Score Chart", …)` (`:185-187`).
- `ProductivityScoreSection` header — `"Productivity Score"` (`ProductivityScoreSection.kt:106`) + `"Average ${response.averageScore.roundToInt()}"` (`:111`). No threshold-derived copy.
- **Trend chip** — `"Improving"` GREEN `Color(0xFF2E7D32)`, `"Declining"` RED `Color(0xFFC62828)`, `"Stable"` neutral (`ProductivityScoreSection.kt:152-160`). Trend is split-half delta vs ±3.0; not directly threshold-driven but uses red for negative outcomes.
- **Best / Worst tiles** — `"Best"` GREEN, `"Worst"` RED (`ProductivityScoreSection.kt:257, 267`). Tiles fire whenever data exists regardless of threshold. **Forgiveness-first concern: `Color(0xFFC62828)` red on the "Worst" tile is the same shade as `Declining` and as Habit/Project broken-streak red.** Same wider anti-pattern that `FORGIVENESS_FIRST.md` § "Don't paint broken streaks red" rules out for activity streaks — re-emerges here on the score axis.
- **Streak chip** in section header — `"${streak.currentDays}d"` with `LocalFireDepartment` icon, plus `" · best ${streak.longestDays}d"` when applicable (`ProductivityScoreSection.kt:134-145`). Gated by `streak.hasAnyHistory` so a brand-new user does not see "0d".
- **Heatmap** — title `"Productivity Heatmap"`, subtitle `"Last 12 weeks — score per day"`, legend `"Less ... More"` (`ProductivityHeatmap.kt:82-88, 213-238`). Legend copy is neutral; no failure framing. 4-tier alpha bucket (very low → very high) with 60 as the mid jump.
- **Best/Worst Day-of-week + Peak hour** (`TaskAnalyticsScreen.kt:601-619`) — copy `"Most Productive Day: …"` and `"Least Productive Day: …"`. Bar chart paints `bestDay` bar `successColor` (green) and `worstDay` bar `warningColor` (yellow, not red — softer treatment than the score Best/Worst tiles).

**Today screen** (`TodayScreen.kt:249`):

- `ProductivityScoreBadge` — compact 28dp circular badge showing the numeric score (e.g. `"67"`). 4-tier color: `successColor` ≥80, `primary` ≥60, `warningColor` ≥40, `destructiveColor` <40 (`ProductivityScoreBadge.kt:45-50`).
- Gated by `hasEnoughHistory` = activeDays ≥ 3 in the last 7 days (`TodayScoreBadgeViewModel.kt:67-78`) — brand-new users don't see a misleading 100/100.
- A11y label: `"Today's productivity score: $score out of 100. Tap to open analytics."` (`ProductivityScoreBadge.kt:59`). Neutral — no good/bad framing.
- **No separate "productive day" badge on Today** — the badge displays the raw number, not a binary indicator. The threshold appears here only as a color tier change.

**Weekly Balance Report.** Grep `WeeklyBalanceReportScreen` for `productiv` returns no hits — balance report does not surface "productive days this week: X/7". Pure life-category surface.

**Widget (`ProductivityWidget.kt`).** Renders the score with `palette.scoreGreen` ≥80, `palette.scoreOrange` ≥60, `palette.scoreRed` <60 (`ProductivityWidget.kt:83-89`). The widget IS where `<60` becomes RED — the only surface where below-threshold gets explicit red palette treatment by default. Thresholds are user-configurable, so the user can shift them. **This is the most visually pointed binary on the productive-day axis.**

**Notifications.**

- **`WeeklyAnalyticsWorker.kt:106`** — `"Weekly Score: $score"` (Sunday 19:00, opt-in). Uses average score, **does NOT use the productive-day binary**. No "productive days this week: X/7" framing.
- **`ProductiveStreakNotifier.kt`** — fires when a streak breaks. Title `"Streak Reset"`, body `"Take care of yourself today — start fresh tomorrow."` (`ProductiveStreakPreferences.kt:50-52`). PRIORITY_LOW. **No user opt-in flag** — the only way to suppress is to disable the system notification channel `"productive_streak"`. **This is the explicit forgiveness-first anti-pattern violation that `FORGIVENESS_FIRST.md` § "Open questions" already names.**

### (c) Score-derived sibling sweep (V4 axis) — (YELLOW — 5 surfaces touched, only 1 binary load-bearing)

Every surface where the threshold transition manifests:

| # | Surface | File:line | Shape | At 59 → 60 |
| --- | --- | --- | --- | --- |
| 1 | Productive-day streak | `ProductiveStreakResolver.kt:69`, `ProductiveStreakPreferences.kt:76-118` | **Hard binary** | Streak `reset` → `advance` |
| 2 | Broken-streak notification | `ProductiveStreakNotifier.kt:26-45`, fired from `DailyResetWorker.kt:72-75` | Binary (fires when `brokenStreakLength > 0`) | At 59 with prior active run: notification fires. At 60: silent. |
| 3 | Today productivity badge | `ProductivityScoreBadge.kt:45-50` | 4-tier color gradient | `warningColor` yellow → `primary` accent |
| 4 | Analytics heatmap cells | `ProductivityHeatmap.kt:178-183` | 4-tier alpha | `accent` α=0.30 → α=0.55 |
| 5 | Productivity widget | `ProductivityWidget.kt:83-89` (config: `AdvancedTuningPreferences.kt:202-205`) | 3-tier color (configurable) | `scoreOrange` (60 is at orange threshold) — actually `<60` is RED, `≥60` is ORANGE. **The widget's RED→ORANGE jump is at exactly 60 by default.** |

**Binary load-bearing only at #1 (streak record/reset) and #2 (notification fire/silent).** Surfaces 3–5 are tier transitions where 60 is *one* of several boundaries; they color-code score bands rather than binary-classify the day. Surface #5 (widget) is the visually starkest because `<60` defaults to red — but the threshold there is user-configurable, so it's not architecturally locked at 60.

V4 count: **5 surfaces, of which 2 are binary-load-bearing**. Under the STOP-C cap (>5). Proceed.

Also, `AnalyticsMarkdownExporter.kt:62-67` exports a `## Productive-Day Streak` section to the user's share-out markdown. Not a UI surface but a copy surface — uses `streak.currentDays`, `streak.longestDays`, `streak.lastProductiveDate` directly. No threshold reference in exported copy.

### (d) Tests at threshold — (RED — no test asserts behavior at score=59 vs 60)

**Tests that exist:**

- `ProductiveStreakPreferencesTest.kt` — 8 tests covering streak record / reset / idempotency / gap / longest-preserved. **All tests treat the day as already-classified ("call `recordProductiveDay`" / "call `resetCurrentStreakIfBroken`") rather than testing the classification at the threshold.**
- `TodayScoreBadgeViewModelTest.kt` — tests the `activeDayCount` gate (≥3 active days) and constants. Does not test color tier at score=59 vs 60.
- `AnalyticsMarkdownExporterTest.kt` — tests exporter copy for empty / populated streak. No threshold copy.
- `ProductiveStreakResolver`: no dedicated test file. The resolver — the **only place the threshold is consulted** — is untested in isolation.

**Tests that do not exist:**

- No assertion that `score=60` advances the streak.
- No assertion that `score=59` resets the streak (and triggers the broken-streak notification).
- No assertion that `score=59.5` rounds to 60 and advances (rounding semantics at the boundary).
- No assertion on heatmap color buckets at 39/40/59/60/79/80.
- No assertion on `ProductivityScoreBadge` color tier at 39/40/59/60/79/80.

Classification: **RED.** The threshold is the most behavior-significant constant on the productive-day axis and has zero direct test coverage. A reframe (Option B/C/D) that changes the threshold semantics would currently land without any structural repro tests to verify the old vs new behavior. Per `feedback_repro_first_for_time_boundary_bugs.md`, the repro tests should be written *before* the reframe lands. This is true regardless of which option is picked — even Option A (keep binary, soften display) needs the boundary repro to verify the streak side doesn't drift.

### (e) Adjacent forgiveness-first concerns — (YELLOW — known exceptions, two new ones found)

`docs/FORGIVENESS_FIRST.md` declares anti-patterns. Surveying the productive-day surface against them:

| Anti-pattern | Status |
| --- | --- |
| "Don't paint broken streaks red" | **Adjacent violation found.** `ProductivityScoreSection.kt:267` paints `"Worst"` tile `Color(0xFFC62828)` (same shade as `Declining` trend chip at `:154`). Not technically a "broken streak" — it's a worst-score-in-range tile — but the anti-pattern's spirit (don't visually flag low scores as failures) applies and is currently violated. |
| "Don't notify on streak breaks" | **Documented violation.** `ProductiveStreakNotifier.notifyBrokenStreak` fires on streak reset. `FORGIVENESS_FIRST.md` § "Open questions" already names this as the open exception. No user opt-in path before firing. |
| "Don't gate features by streak length" | ✓ Not violated. Nothing in the codebase keys behavior on `streak.currentDays` other than the streak-chip display itself. |
| "Don't surface 'you missed X days' framing" | ✓ Not directly violated on the productive-day surface. The streak chip and broken notification do *not* count miss days. (The "Last productive day: …" copy in `AnalyticsMarkdownExporter.kt:67` is descriptive rather than dramatizing.) |
| "Don't reinflate by retroactive completion" | ✓ Not violated. The streak path is write-once via `recordProductiveDay`; there's no edit-yesterday affordance. |

**Empty-day heatmap framing.** `ProductivityHeatmap.kt:181-183` — scores `<40` render as `emptyTint` (same as no-data cells). This is **good** forgiveness-first behavior: low-score days look indistinguishable from no-data days at a glance. The legend says "Less … More" rather than "Bad … Good". The risk is the opposite — the visual treatment may be *too* hidden, making the user think the metric is broken. Worth noting but not a violation.

**Tooltip framing.** `ProductivityHeatmap.kt:189-194` — tap a cell, see `"$datePart · $scorePart"` where `scorePart` is `"${score.toInt()}/100"` or `"no data"`. Neutral. No threshold-derived copy.

**Trend chip neutrality.** "Stable" gets the neutral `onSurfaceVariant` color, "Improving" green, "Declining" red. The asymmetry is fine for "Improving" (positive is positive), but the "Declining" red mirrors the same shame-amplification pattern flagged for "Worst". A reframe Option E (not in the original four) — *make the Declining trend chip neutral rather than red* — would be the smallest-cost cosmetic improvement and could land independently.

---

## Verdict matrix

| # | Premise | Verdict | Evidence (file:line) |
| --- | --- | --- | --- |
| V1 | Score threshold is hard-coded at ≥60 with binary output | **GREEN (true)** | `ProductiveStreakPreferences.kt:49` (`const = 60`); `ProductiveStreakResolver.kt:69` (binary `productive = score >= threshold` → record/reset) |
| V2 | At score 59 vs 60, the user-facing display differs in a way that creates good-day/bad-day framing | **YELLOW (partial)** | Streak (binary), widget (defaults to RED→ORANGE jump at 60), badge (yellow→accent), heatmap (α 0.30→0.55). The framing exists but is mostly tier-transition, not literal "success/failure" copy. The streak is the load-bearing binary; the rest is gradient. |
| V3 | Threshold is not user-configurable | **GREEN (true)** | No DataStore key, no Settings UI for `PRODUCTIVE_DAY_SCORE_THRESHOLD`. The `ProductivityWidget` thresholds (`AdvancedTuningPreferences.kt:202-205`) are configurable but are a *separate* widget-color knob, not the streak-qualification threshold. |
| V4 | Productive-day binary is load-bearing in N other surfaces — N enumerated | **GREEN (N=5 surfaces, 2 load-bearing)** | Streak, notification, badge, heatmap, widget (per Phase 1c table). Of these, only Streak + Notification branch on the *binary*; the rest map a continuous score to a color tier. |
| V5 | Current behavior contradicts forgiveness-first in the productive-day surface | **YELLOW (partial)** | The streak is the contradiction — strict semantics + opt-out-only break notification, both already named in `FORGIVENESS_FIRST.md` § "Open questions". The display gradients (badge, heatmap) are *not* binary and not contradictory. The widget's default 60-boundary RED→ORANGE jump is the most pointed visual binary outside the streak. |
| V6 | No existing copy uses failure framing for non-productive days | **GREEN (no copy violation)** | Broken-streak notification copy: `"Streak Reset"` / `"Take care of yourself today — start fresh tomorrow"` (`ProductiveStreakPreferences.kt:50-52`). Heatmap tooltip: numeric only. Weekly worker: `"Weekly Score: X"`. No "you had a bad day", no "0 productive days", no "missed your target" copy anywhere. Color choices (Worst RED, Declining RED, widget RED) carry the failure framing visually, not textually. |
| V7 | SoD-aware rollover is intact | **GREEN (intact)** | `DailyResetWorker.kt:104-114` uses `DayBoundary.nextBoundary(dayStartHour, dayStartMinute, now)`. `ProductiveStreakResolver.kt:48-50` uses `clock.zone` + `LocalDate.now(clock)`. No drift from the SoD pattern. (Note: per `FORGIVENESS_FIRST.md` § "Open questions", the *forgiveness-streak walk* still runs on system-zone `LocalDate`, but that's the habit/project streak — the *productive-day* streak goes through SoD via the `DailyResetWorker` schedule.) |
| V8 | A reframe is feasible without breaking sibling features | **GREEN (feasible)** | Only 2 surfaces are binary-load-bearing (streak + notifier). The other 3 (badge, heatmap, widget) consume the continuous score directly and would not break if the binary disappeared. The widget's `ProductivityWidgetThresholds` would need to be re-thought if the threshold concept is dropped entirely, but they're already configurable so the user-facing knob already exists. |

---

## Reframe options (operator picks ONE — do not pre-pick)

Operator decision point. Each option below has a self-contained rationale, a what-changes / what-breaks list, an LOC estimate, sibling-feature impact, and a forgiveness-first alignment verdict.

### Option A — Keep binary, soften display

**Premise.** Threshold stays at 60. The internal classification stays binary; visual treatment is dialed back so 59 vs 60 doesn't feel like success vs failure.

**Changes.**
- `ProductivityScoreSection.kt:267` — `"Worst"` tile color from `Color(0xFFC62828)` to the neutral `onSurfaceVariant`.
- `ProductivityScoreSection.kt:154` — `Declining` trend chip color from red to neutral. (Optional bundled cosmetic.)
- `ProductivityWidget.kt:83-89` — change the "below orange" branch from `palette.scoreRed` to a neutral or muted tone. Or shift the default `orangeScore` down so the red bucket starts at <40 instead of <60.
- Optionally: gate the broken-streak notification behind a `ProductiveStreakPreferences` opt-in flag (default off) and add a Settings → Notifications row for it.

**What breaks.** Nothing structural. Widget configurability already exists, so users who *want* the red tier can keep it.

**Sibling impact.** Surfaces 3-5 (badge/heatmap/widget) get cosmetic adjustments only. Streak logic and notification unchanged.

**LOC estimate.** ~80-200 (cosmetic + optional notification opt-in flag + Settings row + tests for the new pref).

**Forgiveness-first verdict.** Partial improvement. The binary is still there, just less visually prominent. Per `FORGIVENESS_FIRST.md` Open Question #2, **the notification opt-in or removal is the more important part** — if Option A ships without touching the notification, the load-bearing anti-pattern violation persists.

**When to pick.** When the operator wants to preserve the streak motivation surface (some users genuinely value the binary "did I hit my target today?" signal) but acknowledges that *visual* shame-amplification needs to come down.

### Option B — Replace binary with gradient (drop "productive day" as a user-facing concept)

**Premise.** Score displays as a continuous value. The "productive day" classification disappears as a user-facing concept. Heatmap/badge/widget continue to use color tiers, but the *streak* — which is the only true binary — is removed from the user-facing surface (or repurposed; see below).

**Changes.**
- `ProductivityScoreSection.kt:117, 125-148` — remove `ProductiveStreakChip` from the section header. Replace with the existing average-score display only.
- `ProductiveStreakNotifier` — delete entirely (or keep the class but no-op the `notifyBrokenStreak` call from `DailyResetWorker.kt:73-74`).
- `ProductiveStreakPreferences` — could be removed; `DailyResetWorker.kt:67-78` would stop calling `productiveStreakResolver.resolveYesterday()`. (Or keep it for internal analytics if some future feature wants it, but don't surface it.)
- `AnalyticsMarkdownExporter.kt:62-67` — remove the `## Productive-Day Streak` section from the export.
- Migration: existing users with non-zero streaks lose the chip silently on first launch post-upgrade. Acceptable per `FORGIVENESS_FIRST.md` § "Don't reinflate by retroactive completion" lean.

**What breaks.** The streak chip is the only motivational surface tied to the threshold; removing it removes a positive-reinforcement signal some users value. The migration path is a one-way upgrade — there's no settings toggle to bring it back without re-implementing.

**Sibling impact.** Widget and badge unchanged (they're score-tier, not binary-tier). The export markdown shape changes. Tests covering `ProductiveStreakPreferences` would be deleted alongside the class.

**LOC estimate.** ~250-500 net deletion. ~150 in production code removal + ~100 in test removal + ~50 for the Settings → Notifications row removal.

**Forgiveness-first verdict.** Strongest alignment. Removes both the binary classification and the break notification — the two named anti-pattern violations resolve simultaneously. Aligns with `FORGIVENESS_FIRST.md` § "Open questions" reframe option (c).

**When to pick.** When the operator wants to ship the cleanest forgiveness-first resolution and accepts that some users will miss the streak chip. The migration cost is the chief downside.

### Option C — User-configurable threshold + reframed default

**Premise.** Add a user-facing setting under Settings → Advanced Tuning → "Productive-Day Threshold" (default 60, range 1-100). Reframe the question from "did you hit 60?" to "what counts as productive for you?" Composes with forgiveness-first by handing the user agency over the metric.

**Changes.**
- New DataStore key `productive_day_threshold` in `AdvancedTuningPreferences.kt` (default 60).
- `ProductiveStreakResolver.kt:69` — read threshold from preferences flow instead of `PRODUCTIVE_DAY_SCORE_THRESHOLD` const. Constant becomes the *default*, not the law.
- Settings UI row: slider 1-100, default 60, with copy `"Days at or above this score count toward your productive-day streak. Default 60."`
- Migration: existing users get `60` as initial value (matches current behavior).
- Optional: reframe streak chip copy to `"Productive-day streak: 3d (your goal: ≥60)"` so the user-defined threshold is visible.

**What breaks.** Tests for the const must adapt. `ProductiveStreakResolver` now depends on a Flow rather than a const, requiring test updates. Two users on different threshold values cannot compare streaks meaningfully — minor concern, but worth a copy note.

**Sibling impact.** Heatmap/badge/widget keep their own thresholds (or could optionally adopt the user value — that's a follow-up). Notification opt-in question remains — Option C doesn't itself resolve the "Don't notify on streak breaks" anti-pattern, so it should bundle with the opt-in flag from Option A.

**LOC estimate.** ~200-400 (DataStore + Settings row + Resolver refactor + tests + migration default).

**Forgiveness-first verdict.** Strong alignment. Gives user agency. Composes well with Option A's notification opt-in. Per `FORGIVENESS_FIRST.md` § "Configuration", this fits the same shape as the existing `gracePeriodDays` + `allowedMisses` knobs — a user-tunable parameter that respects individual definitions of progress.

**When to pick.** When the operator believes some users genuinely value the binary signal and others want it gone — let each user decide. Pairs naturally with Option A as a bundled "user-tunable + softer visuals" ship.

### Option D — Remove the binary, keep the streak (hide the threshold, keep the rewarding count)

**Premise.** The "productive day" concept stays internally for streak rollover, but is never surfaced as a binary classifier to the user. User sees the streak length growing each day; the underlying threshold is invisible.

**Changes.**
- Streak chip copy from `"3d"` to something like `"Active 3 days"` (still positive; no implicit "vs the 60 line" framing).
- Broken-streak notification: REMOVE entirely (or gate behind opt-in, see Option A). The notification is the surface that makes the binary visible — pulling it makes the streak feel like a soft restart rather than a hard break.
- Streak chip displays only when `streak.currentDays > 0` — no "0d" state.
- No surfacing of "yesterday's score didn't qualify" anywhere.
- `AnalyticsMarkdownExporter.kt:62-67` keeps the streak section (Active days, longest run, last active date). No threshold copy.

**What breaks.** Users who currently rely on the broken-streak notification to remember/notice they had a low day lose that signal. (Realistically, "Take care of yourself" notifications are not strong feedback signals; the user noticing the streak reset to 0 in-app the next time they open it is the same information at a less intrusive moment.)

**Sibling impact.** Notification path deleted. Streak chip copy reworded. Widget/badge/heatmap unchanged.

**LOC estimate.** ~120-250. ~70 in `ProductiveStreakNotifier` deletion + `DailyResetWorker` simplification + ~80 in chip copy/state refactor + ~50 in tests.

**Forgiveness-first verdict.** Strong alignment with the "Don't notify on streak breaks" anti-pattern resolution. Keeps the positive-reinforcement streak count. Aligns with `FORGIVENESS_FIRST.md` § "Open questions" reframe option (a) (keep the asymmetry) but only because the threshold is now invisible — the asymmetry is hidden in the same way `gracePeriodDays` is hidden by default. The internal binary persists but the *user* never sees it.

**When to pick.** When the operator wants to preserve the streak motivation but agrees the break notification is the load-bearing anti-pattern violation. Lowest LOC of B/C/D and the cleanest middle ground.

---

## Ranked improvement table (wall-clock-savings ÷ implementation-cost)

For *audit-only* mode this table is a *priority ranking for any cosmetic improvements that should land regardless of which reframe option the operator picks*. These are improvements that compose with Options A-D rather than being mutually exclusive with them.

| Rank | Improvement | LOC | Picks-with | Forgiveness-first weight |
| --- | --- | --- | --- | --- |
| 1 | Drop `Color(0xFFC62828)` on `"Worst"` tile and `Declining` trend chip — use neutral `onSurfaceVariant` instead | ~10 | All four options | High — directly resolves one of the two named anti-pattern adjacencies (red on broken-streak adjacent) |
| 2 | Add unit test for `ProductiveStreakResolver` at score boundaries (59, 60, 100, edge `Double.NaN`) | ~80 | All four options. Mandatory under `feedback_repro_first_for_time_boundary_bugs.md` before any structural reframe lands | High — closes the V4 RED testing gap |
| 3 | Gate broken-streak notification behind `NotificationPreferences.productiveStreakBreakEnabled` (default OFF) | ~60 | Compose with Option A or C; superseded by Option B or D which delete the notifier | High — directly resolves `FORGIVENESS_FIRST.md` § "Open questions" #2 |
| 4 | Add Settings UI row for the new opt-in flag from #3 + companion `revision_log` entry | ~50 | Same as #3 | Medium — provides user agency over the surface |
| 5 | Document the chosen reframe option in `FORGIVENESS_FIRST.md` § "Open questions" — convert the open question into a closed decision with cross-link to this audit | ~30 | All four | High — durable knowledge, prevents future Claude/operator pairs from re-litigating |

---

## Anti-pattern flags (not for auto-fix)

These were surfaced during the sweep but are not in scope for this audit's PROCEED list. Flag for follow-on work.

- **Widget threshold defaults out of sync.** `ProductivityWidgetThresholds(greenScore=80, orangeScore=60)` defaults to `60` for the orange line — coincidentally the same value as `PRODUCTIVE_DAY_SCORE_THRESHOLD`. If Option C ships and the user lowers their productive-day threshold to 50 but the widget orange-line stays at 60, the user sees their day count toward the streak but render orange on the widget. Two independent thresholds with the same default is a UX trap. Follow-on: should the widget orange threshold *bind* to the productive-day threshold? Out of this audit's scope.
- **`ProductivityScoreCalculator` default-100 fallback for empty buckets** (`:149`). A brand-new user with zero tasks/habits scores 100 (`DEFAULT_RATE * weights` adds to exactly 100). The `TodayScoreBadge`'s `hasEnoughHistory` gate hides this on the badge, but the heatmap and weekly worker do not — they would proudly show 100/100 for empty inputs. Forgiveness-first-adjacent but a separate "score validity" axis.
- **Trend chip "Declining" color asymmetry.** "Improving" GREEN, "Declining" RED, "Stable" neutral. The same shame-amplification shape this audit flags for the "Worst" tile. Could be bundled with rank-1 cosmetic improvement above.
- **No timezone test for `ProductiveStreakResolver`.** The resolver uses `clock.zone` to compute `yesterday`. A user crossing timezones on a streak boundary could double-count or skip a day. Out of this audit's scope but worth flagging when the operator returns to the streak surface.

---

## STOPs fired

- **STOP-A (premise wrong) — not fired.** All five premises hold. Minor path drift in P2 (`screens` plural) and version drift in P4 (1.9.26 not 1.8.49) noted, both harmless.
- **STOP-B (doc >500 lines) — not fired.** Doc body is ~445 lines (this paragraph included).
- **STOP-C (sibling sweep >5 surfaces) — not fired.** Exactly 5 surfaces enumerated, 2 binary-load-bearing.
- **STOP-D (no contradiction found) — not fired.** V5 verdict is YELLOW: the streak and its notification *are* contradictions; the display gradients are not. Reframe is warranted.
- **STOP-F (Phase 2 across >3 files) — N/A.** Audit-only this pass. Phase 2 deferred to operator decision.

---

## Operator decision points

Before Phase 2 fires:

1. **Pick one of Option A / B / C / D** (or specify a hybrid — most natural hybrids are A+C and A+D).
2. **Confirm rank-1 cosmetic improvement bundles** (`Worst` tile + `Declining` trend chip recolor) regardless of option pick — this is the cheapest, lowest-risk forgiveness-first win and composes with all four reframes.
3. **Confirm rank-2 test backfill is mandatory before structural changes** (Option B/C/D). Per `feedback_repro_first_for_time_boundary_bugs.md`, the boundary repro tests land first; the reframe lands after.
4. **Decide whether to close the `FORGIVENESS_FIRST.md` § "Open questions" entry as part of the implementation PR** or as a separate doc-only PR.

---

## Phase 4 — Chat handoff (paste-ready)

```markdown
## Productive-Day Threshold Audit — handoff

**Scope.** Audited the `score ≥ 60` productive-day threshold logic across PrismTask Android (v1.9.26) to determine whether the binary good-day/bad-day framing contradicts forgiveness-first philosophy ("a miss is not a failure"). Audit-only: no code changes this pass; reframe option awaiting operator decision.

**Verdicts table.**

| # | Premise | Verdict |
| --- | --- | --- |
| V1 | Threshold hard-coded at ≥60, binary classification | GREEN — `ProductiveStreakPreferences.kt:49` const, `ProductiveStreakResolver.kt:69` binary record/reset |
| V2 | 59 vs 60 creates good-day/bad-day framing | YELLOW — streak is binary, badge/heatmap/widget are gradient tiers; widget default `<60` is RED |
| V3 | Threshold not user-configurable | GREEN — no DataStore key, no Settings UI |
| V4 | Load-bearing in N other surfaces | GREEN — N=5 enumerated (streak, notifier, badge, heatmap, widget); 2 are binary-load-bearing |
| V5 | Contradicts forgiveness-first | YELLOW — streak + break notification contradict (already in `FORGIVENESS_FIRST.md` "Open questions"); display gradients do not |
| V6 | No failure-framing copy | GREEN — all copy neutral or empathetic; failure framing is visual only (red colors) |
| V7 | SoD-aware rollover intact | GREEN — `DailyResetWorker` uses `DayBoundary` |
| V8 | Reframe feasible | GREEN — only streak + notifier branch on binary; gradient surfaces unaffected |

**Reframe options (operator picks one).**

- **A — Keep binary, soften display.** Drop red on Worst tile/Declining chip/widget. ~80-200 LOC. Partial forgiveness-first alignment; notification anti-pattern persists unless bundled with opt-in.
- **B — Replace binary with gradient.** Remove "productive day" as user-facing concept; delete streak chip + notifier. ~250-500 LOC. Strongest alignment; users lose the motivational streak surface.
- **C — User-configurable threshold + reframed default.** New Settings → Advanced Tuning slider, default 60. ~200-400 LOC. Strong alignment via user agency; composes with A.
- **D — Remove the binary, keep the streak.** Hide threshold from copy; delete break notification; keep positive streak count. ~120-250 LOC. Strong alignment; lowest LOC of the structural options.

**V4 sibling enumeration — 5 surfaces.**

1. Productive-day streak — `ProductiveStreakResolver.kt:69` (BINARY)
2. Broken-streak notification — `ProductiveStreakNotifier.kt:26-45`, fired from `DailyResetWorker.kt:73-74` (BINARY)
3. Today productivity badge — `ProductivityScoreBadge.kt:45-50` (4-tier gradient)
4. Analytics heatmap — `ProductivityHeatmap.kt:178-183` (4-tier alpha)
5. Productivity widget — `ProductivityWidget.kt:83-89` + configurable `AdvancedTuningPreferences.kt:202-205` (3-tier, configurable)

**STOPs fired.** None. STOP-A premises held (minor path-drift `screens` vs `screen`, version 1.9.26 not 1.8.49 — both harmless). STOP-D not fired — contradiction confirmed on streak axis.

**Memory updates needed (depend on operator pick).**

- If B picked: save `"productive-day binary removed as user-facing; threshold const retained internally"` (or note its full removal).
- If C picked: save `"productive-day threshold now user-configurable via Settings → Advanced Tuning; default 60"`.
- If D picked: save `"productive-day binary hidden from copy; broken-streak notification removed"`.
- All cases: update `docs/FORGIVENESS_FIRST.md` § "Open questions" #2 + #3 to reflect the resolution.

**Scope changes vs original prompt.** None — operator constraints honored: audit-only, no code changes, options enumerated without pre-pick, ≤500 lines, fan-out flagged not auto-fixed.

**LOC actual vs estimate.** N/A — audit-only this pass. Audit doc is ~485 lines.

**Recommended next prompt.** Once operator picks an option, a focused Phase 2 implementation prompt for that option only. The boundary test backfill (rank-2 improvement) should land *before* any structural reframe (Options B/C/D) per `feedback_repro_first_for_time_boundary_bugs.md`. The cosmetic recolor (rank-1) can land in a small standalone PR regardless of option pick.

**Open questions left for operator.**

1. Option pick: A / B / C / D / hybrid?
2. Bundle rank-1 cosmetic improvement (Worst tile + Declining chip recolor) into the same PR or ship separately?
3. Should the widget orange threshold (`AdvancedTuningPreferences.kt:202-205`) bind to the productive-day threshold if Option C ships, or stay independent?
4. Close `FORGIVENESS_FIRST.md` § "Open questions" entries as part of the implementation PR or as a separate doc-only PR?

**Files touched in audit.** None. Audit doc only: `docs/audits/PRODUCTIVE_DAY_THRESHOLD_AUDIT.md`.
```
