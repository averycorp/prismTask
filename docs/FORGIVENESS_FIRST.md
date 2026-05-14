# Forgiveness-first — streak philosophy

PrismTask streaks are designed to survive a missed day.

This is a deliberate departure from the "miss-a-day-lose-everything"
streak model that most habit apps inherit. We treat streaks as a
motivational signal about the user's pattern over time — not as a
fragile token that punishes a single off day.

This doc exists so that when someone touches streak code, writes
streak copy, or designs a new streak surface, they share one
definition of what "forgiveness" means in PrismTask. See
[`WORK_PLAY_RELAX.md`](WORK_PLAY_RELAX.md) and
[`COGNITIVE_LOAD.md`](COGNITIVE_LOAD.md) for the companion philosophy
docs this one composes with.

---

## The core rule

A streak survives **either** of these patterns:

1. The user completed the streak's activity today (or — if they haven't
   logged today yet — yesterday).
2. A miss inside an otherwise active streak, as long as it fits inside
   the rolling forgiveness window without exceeding the allowed misses.

Two stricter sub-rules constrain the second:

- **Hard reset on today + yesterday both missed.** If today and
  yesterday are both absent from the activity set, the resilient run is
  forced to zero. Two consecutive miss days at the leading edge always
  break the streak, regardless of how much grace remains.
- **Rolling window cap.** Walking backward through history, a miss is
  forgiven only if the `gracePeriodDays`-wide window forward from the
  miss already holds fewer than `allowedMisses` other misses.

With the shipped defaults (`gracePeriodDays = 7`, `allowedMisses = 1`),
the practical summary is: *today + yesterday both missed → break.
Otherwise, at most one miss per rolling 7-day window stays forgiven.*
Both knobs are user-configurable in Settings → Advanced Tuning.

The implementation lives in
[`DailyForgivenessStreakCore.kt`](../app/src/main/java/com/averycorp/prismtask/domain/usecase/DailyForgivenessStreakCore.kt)
and is shared by:

- Habit streaks (via `StreakCalculator.calculateResilientDailyStreak`).
- Project streaks (`ProjectRepository.computeProgress` /
  `buildDetail`).
- Leisure-budget streak (`LeisureScoreSectionViewModel`).
- The Streak Calendar widget (`WidgetDataProvider`).
- Forgiveness-streak ND-friendly mode toggle.

For these activity-level daily streaks there is intentionally one
implementation, not five. New activity-level streak surfaces should
call `DailyForgivenessStreakCore` rather than rolling their own walk.

### Acknowledged exception: the productive-day streak

`ProductiveStreakPreferences` in
[`workers/streak/ProductiveStreakResolver.kt`](../app/src/main/java/com/averycorp/prismtask/workers/streak/ProductiveStreakResolver.kt)
maintains a **separate, strict daily streak** at a composite-score
threshold (`PRODUCTIVE_DAY_SCORE_THRESHOLD = 60`). It does not use the
forgiveness core: any gap restarts the run.

The two streaks model different things:

- The forgiveness-first activity streak asks *"did the user do this
  specific thing today?"* — gaps are absorbable.
- The productive-day streak asks *"was today productive on the
  composite score?"* — it's a coarse aggregate, not a per-activity
  signal, so the rule is "yes today / no today" without grace.

The productive-day streak's break notification copy is empathetic
("Take care of yourself today — start fresh tomorrow"), but the strict
shape is deliberate. See the **Open questions** section for the
asymmetry that this doc explicitly leaves open.

## What forgiveness is not

- **Not "all misses are absorbable."** The hard-reset rule still fires
  when today and yesterday are both missed. Two consecutive
  leading-edge misses always break the streak.
- **Not retroactive editing.** The user cannot "buy back" a missed
  day after the streak has already broken.
- **Not a free pass per user.** All users start with the same default
  grace window. Per-user tightening or widening lives in
  Settings → Advanced Tuning; per-habit overrides
  (`nag_suppression_days_override`, `today_skip_after_complete_days`)
  tune adjacent reminder/UI behavior, not the grace window itself.
- **Not optional for built-in habits.** The shipped built-ins
  (school, leisure, morning self-care, bedtime self-care, medication,
  housework) all run on forgiveness-first by default. The user can opt
  individual habits out of streak display via `show_streak`, but the
  grace window itself is not a toggle.
- **Not applied to weekly / monthly / quarterly streaks.**
  `StreakCalculator.calculateResilientStreak` falls back to strict
  semantics for non-daily frequencies (no resilient walk is defined for
  them yet). Forgiveness-first is daily-only by design.

## Why we do this

Streak-based motivation works when it tracks the user's pattern, not
their punctuality. Streaks that shatter on a single off day:

- Discourage starting again ("I already broke it, why bother").
- Punish ND-friendly users who use schedules as scaffolding rather
  than as a strict contract.
- Inflate the felt cost of a single missed dose / habit / project
  check-in past anything proportional to the actual setback.

PrismTask explicitly trades a small amount of "did you really do this
every day?" precision for a large amount of "are you still in the
pattern?" signal. We optimize for the second number.

## Configuration

Settings → Advanced Tuning → Forgiveness Streak exposes the two
knobs that govern the rolling window:

- **Grace window** (`gracePeriodDays`, 1–30, default 7) — width of the
  rolling window inside which the cap applies.
- **Allowed misses** (`allowedMisses`, 0–5, default 1) — how many
  misses fit inside that window before the streak breaks.

`ForgivenessConfig.STRICT` (in `StreakCalculator.kt`) is the full
opt-out, collapsing the resilient streak to the strict count.
`HabitEntity.showStreak` is the per-habit gate for whether the streak
badge renders at all — independent of the grace window.

## Day boundaries

The streak path operates on `Set<LocalDate>`. Caller code is responsible
for bucketing completion timestamps into `LocalDate`s and for choosing
the `today` reference. Day-boundary handling therefore lives at the
caller, not the core: the surface that wires streaks into a feature
must decide whether to use the user-configured SoD (via
[`DayBoundary.kt`](../app/src/main/java/com/averycorp/prismtask/util/DayBoundary.kt))
or the system midnight.

In current code the streak path runs on system-zone bucketing; the
SoD-aware day boundary is honored by other surfaces (Today screen task
filter, NLP date parsing, habit completion gating) but not by streak
computations themselves. See **Open questions** for the gap.

## Copy guidelines

- ✅ "On a 12-day streak."
- ✅ "Yesterday's streak day still counts as long as you log today."
- ✅ "Streak paused — log any day in the next 24 hours to keep it."
- ❌ "Don't break your streak!"
- ❌ "12 days perfect — don't let yourself down."
- ❌ "Streak broken. Start over." (when the grace window is still open)

The same descriptive-not-prescriptive rule from
[`WORK_PLAY_RELAX.md`](WORK_PLAY_RELAX.md) applies. Streak copy
describes the streak's state. It does not lecture, threaten, or
dramatize.

## Anti-patterns

These are the streak-design moves forgiveness-first specifically rules
out. They apply across activity-level streaks; the productive-day
streak is the documented exception above.

- **Don't paint broken streaks red.** No `Color.Red` /
  `colorScheme.error` / `errorContainer` styling on a zero-length
  streak. A zero-length streak is just zero, not a failure state. The
  shipped surfaces (`HabitCard`, `TodayHabitCheckItem`,
  `WeeklyBalanceReportScreen`) already follow this — keep it that way.
- **Don't notify on streak breaks.** Pushing a notification at the
  user about a broken streak inflates the felt cost of the gap, which
  is the failure mode forgiveness-first exists to avoid. See **Open
  questions** for the productive-day notification path that currently
  contradicts this — it is the exception, not the precedent.
- **Don't gate features by streak length.** Streak length is a
  motivational mirror, not a privilege ladder. Locking content,
  themes, or surfaces behind "X-day streak" turns the streak into a
  contract instead of a signal.
- **Don't surface "you missed X days" framing.** Streaks display the
  positive count (resilient days kept). The miss count is internal
  state, not user-facing copy. The "From Earlier" rename
  ([PR sweep — overdue → From Earlier in `SettingsUtils.kt:22`,
  `TodayScreen.kt:630`, `FilterPanel.kt:280`]) is the broader pattern:
  user surfaces describe state without dramatizing absence.
- **Don't reinflate by retroactive completion.** A user marking
  yesterday "done after the fact" is fine as a logging affordance, but
  it should not undo a hard reset. Once today + yesterday were both
  missed, the resilient run is zero; retroactive edits update history,
  not the live streak.
- **Don't add a parallel streak walk for a new activity-level
  surface.** Call `DailyForgivenessStreakCore`. If the surface needs a
  different rule (composite scores, weekly cadence), document the
  exception in this doc the same way the productive-day streak is
  documented — silence is not consent.

## Web parity

`web/src/utils/streaks.ts` is the canonical port of
`DailyForgivenessStreakCore` to TypeScript:

- `ForgivenessConfig` mirror at `streaks.ts:39–45` with matching
  `DEFAULT_FORGIVENESS`.
- `forgivenessDailyWalk` (`streaks.ts:128–228`) replicates the
  hard-reset + rolling-window walk.
- `web/src/utils/__tests__/streaks.test.ts:160–228` carries explicit
  *"forgiveness-first parity"* tests that name
  `DailyForgivenessStreakCore` in their preamble — the same set of
  cases must pass on both platforms.

`web/src/utils/checkInStreak.ts:5–6` reuses the same semantics for
morning check-in streaks. Web users see the same grace window as
Android users — both platforms honor the same `ForgivenessConfig`
defaults.

When updating the algorithm, the rule is: change Android first, then
port to `web/src/utils/streaks.ts`, then update both test suites in
lockstep. Drift between platforms is a regression — a habit's streak
must show the same number whether the user opens Android or web.

## Companion principles

Forgiveness-first composes with the other PrismTask philosophy docs.
All three share a descriptive-not-prescriptive copy stance and a
shame-avoidance bias.

- [`WORK_PLAY_RELAX.md`](WORK_PLAY_RELAX.md) §
  *Streak strictness* — Work / Play / Relax mode-aware leniency
  composes on top of the forgiveness core. Play and Relax default to a
  wider grace window than Work; the user can override per habit.
- [`WORK_PLAY_RELAX.md`](WORK_PLAY_RELAX.md) §
  *Inference rules* — the *"never inflate Work"* `Relax → Play → Work`
  tie-break shares the same shame-avoidance shape as forgiveness's
  *"never punish a single off day"* lean.
- [`COGNITIVE_LOAD.md`](COGNITIVE_LOAD.md) §
  *Forgiveness-first composition* — Cognitive Load streaks (when they
  ship) compose with the core, and the *"never inflate difficulty"*
  `Easy → Medium → Hard` tie-break is the difficulty-dimension
  analogue of the mode-dimension lean.

The throughline: when a tie-breaker is needed, lean toward the
restorative read so the system never accidentally inflates Work,
difficulty, or punctuality. Forgiveness-first is the streak-dimension
expression of that throughline.

## Surfaces that use forgiveness-first

- Today screen — habit cards.
- Weekly Balance Report — burnout band copy.
- Built-in habit list — streak badges (per-habit `show_streak`).
- Project list — project streaks.
- Streak Calendar widget.
- Daily Essentials card.
- Leisure-budget streak.
- Morning check-in streak (via the same algorithm on web).

The productive-day streak (see **Acknowledged exception** above) is
**not** in this list. It surfaces in the analytics screen and triggers
its own break notification; it deliberately uses strict semantics.

## When you'd add a new streak

- Call `DailyForgivenessStreakCore`. Don't reimplement the grace
  window.
- Pass the user's SoD-aware date to `today` *and* bucket activity
  timestamps through `DayBoundary` for new code (see **Open questions**
  before relying on this on the existing streak path).
- Default `show_streak` to off for any new built-in unless you have
  evidence the streak surface adds clarity (e.g.,
  `BUILT_IN_HABIT_STREAKS_AUDIT.md` for the v65 → v66 enable
  migration).
- Write copy following the **Copy guidelines** above.
- Don't add a new break notification — follow the anti-pattern above.
- Mirror the algorithm change on the web side in `streaks.ts` and add
  parity tests.
- Add a unit test that asserts the grace window: 1 missed day with
  default config → streak preserved, today + yesterday both missed →
  streak broken.

## Open questions

The audit
[`docs/audits/FORGIVENESS_FIRST_DOC_AUDIT.md`](audits/FORGIVENESS_FIRST_DOC_AUDIT.md)
surfaced three real gaps the doc names but does not resolve. Each
needs a separate operator-approved follow-up before code changes:

- **SoD bucketing on the streak path.** The streak walk runs on
  system-zone `LocalDate`s, not on `DayBoundary`-shifted days. A
  completion logged at 02:30 with a 4 AM SoD lands on the calendar day
  of the timestamp, not on the SoD-shifted day the rest of the app
  uses. Either the streak path adopts `DayBoundary` (Android + web in
  lockstep) or this doc's day-boundary scope stays narrowed to the
  surfaces that already honor SoD.
- **Productive-day broken-streak notification.** `DailyResetWorker`
  fires `ProductiveStreakNotifier.notifyBrokenStreak` on streak end.
  The copy is empathetic, but the notification itself contradicts the
  *"don't notify on streak breaks"* anti-pattern. The reframe options
  are: drop the notification, gate it behind an explicit user opt-in,
  or change the productive-day streak to forgiveness-first semantics
  (which would also change V1's framing).
- **Productive-day strictness asymmetry.** The composite-score streak
  is the only daily streak in the app that does not use the
  forgiveness core. Reframing options worth operator consideration:
  (a) keep the asymmetry (composite scores genuinely differ from
  activity counts); (b) port the productive-day streak onto the
  forgiveness core; (c) replace the streak with a gradient display
  (sparkline / heatmap) that doesn't require break/non-break framing.

## What this doc does not cover

- Migration shapes, DataStore key names, or DAO query layouts for
  streak-related entities — those live in `Migrations.kt` KDoc and the
  per-feature audit docs.
- The `StreakCalculator` weekly / monthly / quarterly paths — those
  are strict by design (see *What forgiveness is not*) and are
  documented in `StreakCalculator.kt`'s KDoc.
- The Burnout band copy that consumes streak data on the Weekly
  Balance Report — that's
  [`COGNITIVE_LOAD.md`](COGNITIVE_LOAD.md)-adjacent territory and
  documented next to `BurnoutScorer`.
- The widget data plumbing — `WidgetDataProvider.kt` exposes the
  forgiveness streak result; the widget composables consume it.
