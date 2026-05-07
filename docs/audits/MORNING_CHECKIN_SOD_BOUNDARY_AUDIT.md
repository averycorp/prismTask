# Morning Check-In SoD-boundary audit

**Trigger:** User report — "Morning checkin should appear at SoD."

**Scope:** The Today-screen morning check-in banner's visibility window —
where it gets computed, how it interacts with the user's configured
Start-of-Day (SoD) hour, and how dismissal-state cleanup is keyed.

**Bug shape:** The Today screen decides whether to show the morning
check-in banner with a raw wall-clock comparison (`hour < 11`) that
ignores SoD on both ends:

1. Banner can appear *before* SoD has actually been crossed (e.g. at 2 AM
   when SoD = 7 AM, the user's day hasn't started yet — but the banner is
   shown anyway).
2. Banner uses a hardcoded `11` cutoff instead of the user-configurable
   `MorningCheckInPromptCutoff.latestHour` from
   `AdvancedTuningPreferences`. Settings → Advanced Tuning → Morning
   check-in cutoff has no effect on the banner.
3. `MorningCheckInPreferences.dismissBannerToday()` records dismissal
   against wall-clock `LocalDate.now()`, but `TodayViewModel` compares
   it against `logicalDate.toString()` (SoD-aware). Between calendar
   midnight and SoD, the dismissal date and "today" disagree, so a
   dismissal made at 6 AM (SoD = 7) re-appears until 7 AM.

**Concrete repro:** SoD = 07:00. User opens Today at 02:00 (still
"yesterday" logically — task list filter excludes today's tasks
correctly via `LocalDateFlow`). Wall-clock hour = 2 < 11, so the morning
check-in banner shows. The user expects the banner to first appear at
07:00 when their day actually starts.

---

## Triage results — summary

| # | Surface | Verdict | Severity | Migration |
|---|---------|---------|----------|-----------|
| 1 | `TodayViewModel` `beforePromptHour = hour < 11` | RED | HIGH | PROCEED |
| 2 | `TodayViewModel` hardcoded `11` ignores user prefs | RED | MEDIUM | PROCEED (bundled with #1) |
| 3 | `MorningCheckInPreferences.dismissBannerToday` uses wall-clock date | YELLOW | MEDIUM | PROCEED (bundled with #1) |
| 4 | `MorningCheckInResolver.plan().shouldPrompt` ignores SoD | YELLOW | LOW | DEFER — not consumed in production |
| 5 | `MorningCheckInViewModel` time/window math | GREEN | n/a | STOP — already SoD-aware |
| 6 | `LocalDateFlow` SoD-aware ticker | GREEN | n/a | STOP — already correct |

**3 PROCEED (bundled into 1 PR — same banner-visibility decision), 1 DEFER, 2 STOP.**

---

## 1. `TodayViewModel.beforePromptHour = hour < 11` (RED)

**File:** `app/src/main/java/com/averycorp/prismtask/ui/screens/today/TodayViewModel.kt:175-178`

```kotlin
val hour = java.util.Calendar
    .getInstance()
    .get(java.util.Calendar.HOUR_OF_DAY)
val beforePromptHour = hour < 11
```

Sits inside the `combine(...)` block that drives `_showCheckInPrompt`. The
surrounding code already takes SoD into account elsewhere — `todayStart`
is correctly computed from `logicalDate.atTime(sod.hour, sod.minute)`,
and `alreadyCheckedInToday = logs.any { it.date >= todayStart }` is
SoD-aware. The visibility predicate breaks the chain:

```kotlin
enabled && beforePromptHour && !alreadyCheckedInToday && !dismissedToday
```

`beforePromptHour` is the only term computed from raw wall-clock — every
other input is SoD-correct.

**Findings:**

- The `combine(...)` lambda already re-fires when the wall-clock crosses
  SoD (line 165 includes `localDateFlow.observe(sod)` as the 4th
  source), so the visibility flag *would* update correctly at SoD
  rollover *if* the predicate respected SoD. The plumbing is right; the
  predicate is the bug.
- Wall-clock hour comparison fails in two windows:
  - **Pre-SoD (e.g. SoD = 7, now = 2 AM):** `hour=2 < 11=true` →
    banner shows. Expected: banner should not show until SoD passes.
  - **Wraparound SoD (e.g. SoD = 18, now = 1 AM):** `hour=1 < 11=true`
    → banner shows. The user's "morning" started at 18:00 the previous
    wall-clock day; 1 AM the next day is mid-morning for them but the
    raw-hour check treats it as a fresh dawn. (Edge case — SoD past
    noon is rare but supported by the data model.)
- Sole consumer: `TodayScreen.kt:382` reads `showCheckInPrompt` to
  decide whether to render `MorningCheckInBanner`. No other surface
  reads the same flag.

**Risk:** RED. Regular daily-use bug for any user with a non-zero SoD —
the banner appears in the dead-zone between calendar midnight and SoD
every single day. Worse, it's the same dead-zone where `LocalDateFlow`
correctly says "still yesterday," so the rest of the Today screen is
showing yesterday's task buckets while the banner says "Good Morning!"
— internally inconsistent.

**Fix:** Replace the hour-vs-11 check with a window check anchored on
`todayStart` and `cutoffMillis`:

```kotlin
val cutoff = advancedTuningPreferences.getMorningCheckInPromptCutoff().first()
val cutoffMillis = todayStart + (cutoff.latestHour * 60L - sod.hour * 60L - sod.minute) * 60_000L
// or, simpler: cutoff offset = (cutoff.latestHour - sod.hour) clamped to [1, 24] hours
val now = System.currentTimeMillis()
val withinPromptWindow = now in todayStart..<(todayStart + windowMillis)
```

The exact offset arithmetic depends on whether `cutoff.latestHour` is
interpreted as wall-clock-hour or hours-since-SoD. The current
`MorningCheckInResolver` and `MorningCheckInViewModel` treat it as
wall-clock-hour (`LocalTime.of(cutoff.latestHour, 0)`); the fix should
preserve that contract — the predicate becomes "wall-clock now is past
SoD *and* wall-clock now is before `cutoff.latestHour`."

**Migration:** PROCEED. Bundled with #2 + #3 in the same PR.

---

## 2. `TodayViewModel` hardcoded `11` ignores user prefs (RED)

**File:** Same line as #1 — `TodayViewModel.kt:178`.

```kotlin
val beforePromptHour = hour < 11  // ← hardcoded
```

`AdvancedTuningPreferences.getMorningCheckInPromptCutoff()` exists and
returns `MorningCheckInPromptCutoff(latestHour: Int = 11)`. Settings →
Advanced Tuning has a slider for it. `MorningCheckInViewModel` reads it
correctly (`MorningCheckInViewModel.kt:165`):

```kotlin
val cutoff = advancedTuningPreferences.getMorningCheckInPromptCutoff().first()
val plan = resolver.plan(
    ...
    config = MorningCheckInConfig(promptBeforeHour = cutoff.latestHour),
    ...
)
```

But the *banner-display* path in `TodayViewModel` doesn't consume that
preference — it ignores the slider. A user who set the cutoff to 09:00
to get a sharper "morning only" feel still sees the banner until 11:00.

**Risk:** RED. User-visible: every Settings → Advanced Tuning →
"Morning check-in cutoff" slider movement is silently dropped on the
floor for the banner (the screen-level resolver still respects it).

**Fix:** Inject `AdvancedTuningPreferences` into `TodayViewModel`, read
the cutoff inside the `combine(...)` lambda (already a suspend-friendly
context via `.first()`), and use it in the predicate. Bundle with #1.

**Migration:** PROCEED. Bundled with #1 + #3.

---

## 3. `MorningCheckInPreferences.dismissBannerToday` uses wall-clock date (YELLOW)

**File:** `app/src/main/java/com/averycorp/prismtask/data/preferences/MorningCheckInPreferences.kt:39-62`

```kotlin
private fun todayString(): String =
    LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

suspend fun dismissBannerToday() {
    context.morningCheckInDataStore.edit { prefs ->
        prefs[BANNER_DISMISSED_DATE_KEY] = todayString()
    }
}
```

Writes the *wall-clock* current date. `TodayViewModel` consumes the
stored value via `bannerDismissedDate()` and compares it against
`todayIso = logicalDate.toString()` (SoD-aware
`TodayViewModel.kt:173`).

**Findings:**

- Mismatch surfaces in the pre-SoD window. SoD = 7 AM, user dismisses
  banner at 6 AM (wall-clock = 2026-05-07, logical date = 2026-05-06).
  `dismissBannerToday()` stores "2026-05-07". `todayIso` evaluates to
  "2026-05-06". `dismissedToday = "2026-05-07" == "2026-05-06" = false`
  → banner reappears.
- Once SoD passes (7:00 AM), `logicalDate` advances to 2026-05-07 and
  the comparison matches → banner stays hidden. Net: a dismissal made
  in the pre-SoD window only "takes effect" at SoD, leaving the banner
  visible for the intervening hour.
- Symmetric edge case: post-midnight, post-SoD-near-midnight (e.g. SoD
  = 23:00). User dismisses at 22:00 wall-clock, logical date = today.
  Wall-clock midnight passes; user reopens at 00:30. Wall-clock date is
  tomorrow, logical date is still today. `dismissBannerToday()` had
  stored yesterday's wall-clock date; comparison fails; banner shows
  again before SoD = 23:00. Less common but real.

**Risk:** YELLOW. Same root cause as #1 (wall-clock vs SoD), but
narrower exposure — only affects users who dismiss in the pre-SoD or
post-SoD-near-midnight windows. Once SoD passes, behavior self-heals.

**Fix:** Make `dismissBannerToday()` accept the logical-date ISO
string from the caller. `TodayViewModel` already has it
(`logicalDate.toString()`). Keep the wall-clock-based `todayString()`
helper as the default for callers that don't have logical-date context
(none today, but defensive).

```kotlin
suspend fun dismissBannerToday(logicalDateIso: String = todayString()) {
    context.morningCheckInDataStore.edit { prefs ->
        prefs[BANNER_DISMISSED_DATE_KEY] = logicalDateIso
    }
}
```

Update `TodayViewModel.dismissCheckInPrompt()` to read the current
logical date and pass it in. The flow already exposes `logicalDate`
via `localDateFlow.observe(sod).first()`.

**Migration:** PROCEED. Bundled with #1 + #2 — same coherent
"banner is SoD-respecting" fix.

---

## 4. `MorningCheckInResolver.plan().shouldPrompt` ignores SoD (YELLOW, DEFER)

**File:** `app/src/main/java/com/averycorp/prismtask/domain/usecase/MorningCheckInResolver.kt:81-87`

```kotlin
val localNow = java.time.Instant
    .ofEpochMilli(now)
    .atZone(zone)
    .toLocalTime()
val beforeThreshold = localNow.isBefore(LocalTime.of(config.promptBeforeHour, 0))
val alreadyToday = lastCompletedDate != null && lastCompletedDate >= todayStart
val shouldPrompt = beforeThreshold && !alreadyToday
```

Same wall-clock-vs-SoD bug shape, but on the pure-function planner that
`MorningCheckInViewModel` uses *after* the user has tapped through to
the screen. The planner returns `CheckInPlan.shouldPrompt`, but a grep
across the production tree shows `shouldPrompt` is **not consumed**
anywhere — only test code reads it (`MorningCheckInResolverTest`).

**Findings:**

- `MorningCheckInScreen` shows the check-in steps unconditionally once
  the user has tapped the banner. The screen doesn't gate on
  `shouldPrompt`.
- `TodayViewModel` computes its own visibility predicate inline (the
  `beforePromptHour` line audited in #1) and never calls the resolver.
- Net: `shouldPrompt` is a dead field in production. Its 4 dedicated
  tests assert a behavior the app never reads.

**Risk:** YELLOW (low — dead field), but worth flagging because the
test suite gives a misleading impression of coverage. A future
refactor that *does* wire `shouldPrompt` into the banner predicate
would re-introduce the same SoD bug.

**Fix:** Either (a) consume `shouldPrompt` from `TodayViewModel` —
which would require restructuring the combine lambda to call the
resolver, plus making the resolver SoD-aware — or (b) delete
`shouldPrompt` and its tests. (a) is the more durable answer but
overlaps with the #1 fix. Defer to a follow-up cleanup PR after
#1+#2+#3 ships and the inline predicate is correct.

**Migration:** DEFER — not user-visible, separate scope.

---

## 5. `MorningCheckInViewModel` time/window math (GREEN, STOP)

**File:** `app/src/main/java/com/averycorp/prismtask/ui/screens/checkin/MorningCheckInViewModel.kt`

The check-in screen itself is already SoD-aware end-to-end:

- `init {}` reads `taskBehaviorPreferences.getDayStartHour().first()` and
  computes `todayStart = DayBoundary.startOfCurrentDay(dayStartHour)` —
  correct.
- `loadCalendarEvents()` uses `DayBoundary.endOfCurrentDay(dayStartHour)`
  for the upper bound — correct.
- `logMoodEnergy`, `toggleHabit`, `finalize` all use
  `DayBoundary.startOfCurrentDay(dayStartHour)` to stamp logs — correct.
- `balanceState` flow combines with `taskBehaviorPreferences.getStartOfDay()`
  and passes hour + minute to `BalanceTracker.compute(...)` — correct.

**Risk:** None. **Migration:** STOP — already correct.

---

## 6. `LocalDateFlow` SoD-aware ticker (GREEN, STOP)

**File:** `app/src/main/java/com/averycorp/prismtask/core/time/LocalDateFlow.kt`

`LocalDateFlow.observe(sodSource)` correctly emits the current logical
`LocalDate`, re-keys when SoD changes, and re-emits at every logical-day
boundary crossing. `TodayViewModel` consumes it correctly as the 4th
source of the visibility-pipeline `combine(...)`. The bug is *not* here.

**Risk:** None. **Migration:** STOP — already correct.

---

## Ranked improvement table

Sorted by wall-clock-savings ÷ implementation-cost.

| # | Improvement | Cost | Wall-clock saved per affected user | Ratio |
|---|-------------|-----:|-----------------------------------:|------:|
| 1+2+3 | Bundled SoD fix: replace `hour < 11` with `now in [todayStart, todayStart + cutoffOffset)`, inject `AdvancedTuningPreferences`, route logical-date into `dismissBannerToday(logicalDateIso)` | M | High — daily-use bug for every non-zero-SoD user, plus broken Settings slider | High |
| 4 | Consume or delete `MorningCheckInResolver.plan().shouldPrompt` | S | Low — dead field; cleanup only | Low |

**Plan:** Ship (1+2+3) as a single PR. Defer (4) to a follow-up
cleanup once the inline predicate is the canonical visibility source of
truth.

---

## Anti-patterns flagged (not necessarily fixed)

- **`Calendar.getInstance().get(HOUR_OF_DAY)` for "is it morning?" checks.**
  This pattern recurs — `TodayViewModel.computeGreeting()` (line 204) and
  `TodayViewModel.refreshNudge()` (line 272) both use it. Greeting and
  nudge selection are user-visible but lower-stakes than the banner
  predicate; left untouched in this audit but worth a separate sweep.
  Same bug shape as `feedback_repro_first_for_time_boundary_bugs.md`
  warns about.

- **Two parallel "should prompt" code paths** (TodayVM inline predicate +
  `MorningCheckInResolver.plan().shouldPrompt`) with non-overlapping
  bugs. The dead-resolver path is what makes the test suite misleading.
  Consolidating to a single resolver-driven predicate is the durable
  answer; #4 in the table.

- **`LocalDate.now()` inside DataStore writers.** Anywhere a "today"
  string is stamped at write time and compared against an SoD-aware
  reader at read time, this mismatch can recur. Pattern is concentrated
  in dismissal-state DataStores (this file, plus a couple of others
  worth a follow-up grep).

---

## Repro test strategy

Per `feedback_repro_first_for_time_boundary_bugs.md`: write the
structural repro test before the fix.

A pure-function helper makes the predicate testable without a Compose
or coroutine harness. Suggested shape:

```kotlin
object MorningCheckInBannerDecider {
    fun shouldShow(
        now: Long,
        todayStart: Long,           // SoD-anchored start of logical today
        cutoffHour: Int,            // wall-clock hour from MorningCheckInPromptCutoff
        zone: ZoneId,
        featureEnabled: Boolean,
        alreadyCheckedInToday: Boolean,
        dismissedToday: Boolean
    ): Boolean = ...
}
```

Test cases (covering all four edge cases the audit surfaced):

1. SoD=7, now=2 AM — pre-SoD → false (currently true; this is the bug).
2. SoD=7, now=8 AM, cutoff=11 — within window → true.
3. SoD=7, now=12 PM, cutoff=11 — past cutoff → false.
4. SoD=7, now=8 AM, alreadyCheckedInToday=true → false.
5. SoD=7, now=8 AM, dismissedToday=true → false.
6. SoD=7, now=8 AM, featureEnabled=false → false.
7. SoD=23, now=01:00 — wraparound morning, before cutoff → true if
   cutoff > SoD treated as "next-wall-clock-day cutoff"; this is the
   semantic question the fix needs to nail down. **Open question:**
   should `cutoff.latestHour < sod.hour` mean "cutoff lives in the
   wall-clock day after SoD" (so SoD=23, cutoff=3 means 23:00–02:59), or
   "no banner shown" (cutoff degenerate)? Pick the first; matches the
   "morning window starts at SoD" mental model.

Co-locate the helper next to `MorningCheckInResolver`, write the test,
then have `TodayViewModel` consume the helper inside `combine(...)`.

---

## Phase 3 — bundle summary

| # | Item | Verdict | PR |
|---|------|---------|-----|
| 1 | `TodayViewModel.beforePromptHour = hour < 11` | RED | [#1166](https://github.com/averycorp/prismtask/pull/1166) |
| 2 | Hardcoded `11` ignores `MorningCheckInPromptCutoff` slider | RED | [#1166](https://github.com/averycorp/prismtask/pull/1166) (bundled) |
| 3 | `dismissBannerToday()` uses wall-clock `LocalDate.now()` | YELLOW | [#1166](https://github.com/averycorp/prismtask/pull/1166) (bundled) |
| 4 | `MorningCheckInResolver.plan().shouldPrompt` ignores SoD | YELLOW | DEFER — dead field in production; cleanup PR |
| 5 | `MorningCheckInViewModel` time/window math | GREEN | n/a |
| 6 | `LocalDateFlow` SoD-aware ticker | GREEN | n/a |

**PR #1166** ships items 1+2+3 as a single coherent fix:

- New pure-function `MorningCheckInBannerDecider` with 11-case test
  suite covering pre-SoD, in-window, at-SoD/at-cutoff, past-cutoff,
  user-tightened cutoff, feature-disabled, already-checked-in,
  dismissed, wrap-around (SoD past noon), and minute-precision SoD.
- `TodayViewModel` injects `AdvancedTuningPreferences`, swaps the
  inline `hour < 11` predicate for `MorningCheckInBannerDecider.shouldShow(...)`,
  and routes the SoD-aware logical date into
  `morningCheckInPreferences.dismissBannerToday(logicalDateIso)`.
- `MorningCheckInPreferences.dismissBannerToday()` accepts an optional
  `logicalDateIso: String` (defaults to wall-clock for callers
  without SoD context — none today, but defensive).
- `TodayViewModelTest` fixture updated for the new
  `advancedTuningPreferences` constructor parameter.
- CHANGELOG entry under `## Unreleased / ### Fixed`.

**Wall-clock-per-PR estimate (re-baselined):** ~45 minutes.
Audit + structural repro test + fix + tests + PR description.
Faster than the audit-first baseline because all three items
share a single `combine(...)` lambda — bundling didn't add
coordination cost.

**Memory entry candidates:**

- *None new.* The fix re-applies an established pattern
  (`feedback_repro_first_for_time_boundary_bugs.md` already covers the
  "structural repro test first" rule, and the SoD/wall-clock split is
  documented in `MEDICATION_SOD_BOUNDARY_AUDIT.md` and
  `TODAY_LABEL_SOD_BOUNDARY_AUDIT.md`). No surprise findings worth a
  fresh memory entry.

**Schedule for next audit:**

- Item 4 (`MorningCheckInResolver.plan().shouldPrompt` cleanup) — single
  small PR, ~15 minutes. Either delete the field + its 4 tests, or
  fold the resolver into `TodayViewModel` as the single visibility
  source. The latter is the more durable answer but also overlaps
  with future work on the `MorningCheckInScreen` itself; defer until
  that screen needs touched.
- Anti-pattern sweep for `Calendar.getInstance().get(HOUR_OF_DAY)`
  used as a "is it morning?" gate. `TodayViewModel.computeGreeting()`
  and `TodayViewModel.refreshNudge()` are the two recurrences. Lower
  stakes (greeting + nudge selection), but worth a small dedicated
  audit if either surface starts to misbehave.

