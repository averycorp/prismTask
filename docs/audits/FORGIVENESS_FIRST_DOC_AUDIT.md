# `docs/FORGIVENESS_FIRST.md` — extension audit

**Date:** 2026-05-14
**Branch:** `docs/forgiveness-first-extend`
**Scope:** verify the existing `docs/FORGIVENESS_FIRST.md` against current
`DailyForgivenessStreakCore` + callers + sibling streak implementations,
then close gaps versus the `WORK_PLAY_RELAX.md` / `COGNITIVE_LOAD.md`
template structure.

---

## Phase 0 — premise verification

The commissioning prompt's load-bearing premise was:

> "no canonical `docs/FORGIVENESS_FIRST.md` doc exists. The concept lives
> in `DailyForgivenessStreakCore.kt` + scattered references only."

**P1 — FAILED.** `docs/FORGIVENESS_FIRST.md` already exists on `origin/main`,
114 lines, added by commit `77e12911` ("feat(mode): Work / Play / Relax —
orthogonal task-mode dimension (#1061)") on 2026-05-02 — the **same day**
as the audit finding the prompt cites (PR #1059, the WPR audit that
landed FORGIVENESS_FIRST.md alongside WORK_PLAY_RELAX.md in its
implementation PR #1061). The prompt was authored from a snapshot stale
by 12 days.

**P2 — PASSED.** Both template docs exist (`docs/WORK_PLAY_RELAX.md` 172
LOC; `docs/COGNITIVE_LOAD.md` 210 LOC).

**P3 — PASSED.** `app/src/main/java/com/averycorp/prismtask/domain/usecase/DailyForgivenessStreakCore.kt`
present.

**P4 — PASSED.** `app/build.gradle.kts:22` → `versionName = "1.9.24"` (≥
v1.8.49).

### STOP-A pivot

The prompt's STOP-A guidance explicitly names this case's resolution:
*"different audit needed (extend / refactor existing doc)."* Operator's
"make the reasonable call and continue" override applies. This audit
pivots from **commission new doc** to **audit the existing doc against
code reality + extend where gaps versus the template structure are real**.

Verdicts V1–V8 are still the right shape — they audit code-vs-doc
alignment, which is independent of whether the doc existed at the start
of the session.

---

## Phase 1 — verdict matrix

| # | Premise | Verdict |
|---|---------|---------|
| V1 | `DailyForgivenessStreakCore` is THE single source of truth for streak semantics. | YELLOW |
| V2 | Streaks survive a configurable number of misses without resetting. | YELLOW (doc drift) |
| V3 | "Miss" is defined by absence, NOT by an explicit failure event. | GREEN |
| V4 | Streak rollover is SoD-aware. | YELLOW |
| V5 | Web parity holds (PR #846 port). | GREEN |
| V6 | Strictness is user-configurable. | GREEN |
| V7 | No user-facing surface uses red/X/danger framing for a broken streak. | YELLOW |
| V8 | Productive-day score ≥60 threshold composes with forgiveness-first. | YELLOW (deliberate; doc silent) |

### V1 — single source of truth (YELLOW)

Production call sites of `DailyForgivenessStreakCore.calculate`:

- `StreakCalculator.kt:94` — habit daily streaks via
  `calculateResilientDailyStreak`.
- `ProjectRepository.kt:290,315` — project streaks
  (`computeProgress` / `buildDetail`).
- `LeisureScoreSectionViewModel.kt:99` — leisure-budget streak.
- `WidgetDataProvider.kt:405` — widget surface.

All four call paths share `ForgivenessConfig` and the core's
`Set<LocalDate>` input. ✓

**The yellow:** `ProductiveStreakPreferences.kt:76-94` maintains a
*separate* strict streak (gap → restart, no grace window) outside the
core, fired from `DailyResetWorker` via `ProductiveStreakResolver`. The
existing doc says (line 38–39): *"There is intentionally one
implementation, not five. New streak surfaces should call
`DailyForgivenessStreakCore`, not roll their own."* — but the productive-day
streak does exactly that, justified silently by scope ("composite-score
threshold" vs "did the user do this activity?"). The doc is correct for
activity-level streaks but is silent on the productive-day exception.

**Fix shape:** acknowledge productive-day streak in the doc as a
*deliberately non-forgiveness-first* signal with a different semantic
target, so silence stops doing the work of consent.

### V2 — configurable misses (YELLOW — doc drift)

`ForgivenessConfig` defaults (`StreakCalculator.kt:25-33`):

```kotlin
data class ForgivenessConfig(
    val enabled: Boolean = true,
    val gracePeriodDays: Int = 7,
    val allowedMisses: Int = 1,
)
```

Algorithm (`DailyForgivenessStreakCore.kt:84-99`): for each cursor date
walking backward, a miss is tolerable iff the rolling
`gracePeriodDays`-wide window forward from the cursor holds fewer than
`allowedMisses` misses. Plus a hard-reset rule
(`DailyForgivenessStreakCore.kt:62-65`): if **today and yesterday are
both missed**, the resilient streak is forced to 0 regardless of grace
remaining.

**The yellow:** the existing doc (lines 41–46) says *"Not a free pass for
the whole week. Two missed days in a row break the streak. The window is
one day, not seven."* This conflates two distinct rules:

- The **hard-reset rule** (today + yesterday both missed → break) is a
  one-day boundary.
- The **rolling forgiveness window** (`gracePeriodDays = 7` by default,
  `allowedMisses = 1`) is a seven-day window — exactly the "seven" the
  doc explicitly disclaims.

Both rules are correct; the doc's framing is wrong. With current
defaults, the right summary is: *"Today + yesterday both missed → break.
Otherwise, at most one miss per rolling 7-day window stays forgiven."*

**Fix shape:** rewrite the relevant paragraph to describe both rules
accurately, and link the `gracePeriodDays`/`allowedMisses` knobs to the
Settings surface so users can find the defaults.

### V3 — miss = absence, not failure event (GREEN)

`DailyForgivenessStreakCore.calculate` takes
`activityDates: Set<LocalDate>` — there is no failure-event input. The
core never sees "the user skipped" or "the user opted out." Misses are
inferred from set membership. ✓

### V4 — SoD-aware rollover (YELLOW)

`DailyForgivenessStreakCore` takes `today: LocalDate` and operates on a
caller-supplied set. SoD-awareness lives in the caller's `today` derivation
and in the activity-date bucketing. Sampled callers:

- `LeisureScoreSectionViewModel.kt:99` passes `today = today` derived
  upstream from `LocalDate.now()` — system zone.
- `ProjectRepository.toLocalDateSet(..., zone: ZoneId = ZoneId.systemDefault())`
  — system zone bucketing of completion timestamps.
- `StreakCalculator.calculateResilientDailyStreak` defaults
  `today: LocalDate = LocalDate.now()` and buckets via
  `it.completedDate.toLocalDate()` — system zone.

`DayBoundary.kt` exists per CLAUDE.md and is referenced by the existing
doc (line 76) as the SoD utility, but no caller in this sweep uses it on
the streak path. A completion logged at 02:30 with a 4 AM SoD lands on
the calendar day of the timestamp's system zone, **not** on the
SoD-shifted day the doc claims.

**The yellow:** the doc's SoD claim (line 75) is aspirational for the
streak path, even if `DayBoundary` is honored elsewhere (Today screen's
task filter, NLP date parsing per CLAUDE.md). Either the streak path
should adopt `DayBoundary` or the doc should narrow its SoD claim to the
surfaces that actually honor it.

**Fix shape:** narrow the doc claim. Don't change code in this PR — flag
for a separate operator decision.

### V5 — web parity (GREEN)

`web/src/utils/streaks.ts:107-228` ports the algorithm. Specifically:

- `ForgivenessConfig` mirror at `streaks.ts:39-45` with matching
  `DEFAULT_FORGIVENESS`.
- `forgivenessDailyWalk` at `streaks.ts:128-228` with the same hard-reset
  + rolling-window logic.
- `web/src/utils/__tests__/streaks.test.ts:160-228` carries explicit
  *"forgiveness-first parity"* tests that name `DailyForgivenessStreakCore`
  in their preamble.

The web `checkInStreak` util (`web/src/utils/checkInStreak.ts:5-6`) also
mirrors the same semantics for morning check-ins. ✓

### V6 — user-configurable strictness (GREEN)

`AdvancedTuningPreferences` exposes both knobs through
`ForgivenessStreakSection.kt`:

- `gracePeriodDays` slider (1–30) at `ForgivenessStreakSection.kt:55–57`.
- `allowedMisses` slider (0–5) at `ForgivenessStreakSection.kt:74–76`.

`ForgivenessConfig.STRICT` (`StreakCalculator.kt:31`) provides a full
opt-out collapsing resilient = strict. The
`HabitEntity.showStreak` per-habit toggle (sampled at
`HabitListViewModel.kt:34,274,313` and `HabitCard.kt:203,207`) gates
whether the streak badge renders at all. ✓

### V7 — no red/danger framing on broken streaks (YELLOW)

UI sweep — `grep` for `Color.Red` / `colorScheme.error` / `errorContainer`
intersected with streak surfaces under `ui/screens/today/` and
`ui/screens/habits/` returned **no matches**. Streak surfaces render in
the active PrismTheme palette with no error styling. ✓

**The yellow:** `DailyResetWorker.kt:73–74` fires
`productiveStreakNotifier.notifyBrokenStreak(outcome.brokenStreakLength)`
when the productive-day streak ends. The notification copy
(`ProductiveStreakPreferences.kt:50–52`):

```
title: "Streak Reset"
body:  "Take care of yourself today — start fresh tomorrow."
```

The copy itself is empathetic and avoids "broken" / "lost" / "failure"
language. But the *act* of notifying on streak end is what the
forgiveness-first philosophy would naturally rule out — pushing a
notification at the user about a broken streak inflates the felt cost of
the gap in exactly the way the doc's "Why we do this" section (lines
57–70) argues against.

**Fix shape:** add an explicit "do not notify on streak breaks" anti-pattern
to the doc, and flag the existing productive-day notification as an open
question for operator decision (rather than a unilateral remediation).

### V8 — productive-day ≥60 composes vs contradicts (YELLOW — pre-flagged)

This is the verdict the commissioning prompt deliberately pointed at.

`ProductiveStreakPreferences.PRODUCTIVE_DAY_SCORE_THRESHOLD = 60`
(`ProductiveStreakPreferences.kt:49`). A "productive day" is any day the
user's composite productivity score (calculated by
`ProductivityScoreCalculator`) rounds to ≥ 60.

The streak walks day-by-day from `DailyResetWorker` via
`ProductiveStreakResolver.resolveYesterday`:

- `recordProductiveDay` (line 76) advances the run by 1 if yesterday was
  the immediate next day; otherwise restarts at 1 (line 87 — *"gap —
  restart the run"*). **No grace window.**
- `resetCurrentStreakIfBroken` (line 102) zeroes the current run on any
  non-productive day strictly after `lastProductiveDate`.

**The contradiction:** the productive-day streak is a **strict daily
streak** sitting alongside forgiveness-first daily streaks for activity
counts. They share the surface (Settings, analytics, notifications)
without the doc explaining how they relate. The doc claims a single
implementation; the productive-day path is a parallel implementation.

**Defensible read:** the two streaks model different things. The
forgiveness-first activity streak asks *"did the user do this specific
thing today?"* The productive-day streak asks *"was today productive on
average?"* You can argue they're different semantic targets that
warrant different rules — but the doc is silent on this distinction, so
the user has no way to reconcile the two when they encounter both
surfaces.

**Fix shape:** name the productive-day streak as an acknowledged
exception in the extended doc, with a short explanation of the
semantic-target difference, then flag the strict-vs-forgiveness
asymmetry as an **open question** for operator decision.

---

## Phase 2 — fixes shipping in this PR

### Doc additions (extending `docs/FORGIVENESS_FIRST.md`)

| Section | Reason |
|---|---|
| Fix V2 framing — "two consecutive days hard-reset + rolling window" | V2 drift |
| Add "Web parity" section | V5 GREEN — make parity visible |
| Add "Companion principles" cross-ref to both templates | Template parity (both WPR/Cognitive Load cross-ref this doc; reciprocal needed) |
| Expand anti-patterns: don't gate features by streak length, don't notify on streak breaks, don't use red/danger framing | V7 caveats + explicit anti-pattern list per commissioning prompt |
| Add "Acknowledged exception" subsection for productive-day streak | V1 + V8 — stop being silent |
| Narrow SoD claim to "Day boundaries are SoD-aware everywhere the surrounding feature is — see DayBoundary doc" | V4 honesty |
| Add "What this doc does not cover" closing section (template parity) | Mirror WPR/Cognitive Load convention |
| Add "Open questions" section | V4 + V7 + V8 surfaced for operator decision |

### No code changes in this PR

V4 (SoD bucketing on the streak path) and V7 (productive-day broken-streak
notification) are real misalignments. They are flagged in the doc and
listed under Open questions. Per the commissioning prompt's re-trigger
criteria, these need separate operator-approved prompts before
remediation; the doc makes their existence load-bearing rather than
letting silence carry them.

---

## Phase 3 — bundle summary (appended post-PR-open)

Appended after `gh pr create` returns the PR number.

Improvement: extend `docs/FORGIVENESS_FIRST.md` from 114 LOC to
~210–250 LOC, mirroring the WPR / Cognitive Load template shape.

Measured impact: doc-only; CI is the markdown lint + link-check gate.

Memory entry candidates (only if non-obvious post-merge):

- *"`DailyForgivenessStreakCore` is not the sole streak path —
  `ProductiveStreakPreferences` runs a parallel strict streak at
  threshold ≥60 with no grace window. Doc now names this exception
  explicitly."* — surprising; worth recording so future audits don't
  retread the "single source of truth" claim.

Schedule for next audit: V4 and V7/V8 each deserve a separate
operator-approved prompt before code remediation.

---

## Phase 4 — Claude Chat handoff

Emitted at session end as a fenced ```markdown block in the chat.
