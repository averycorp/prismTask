# Timer Auto-Switch Tab Audit

**Scope.** Operator request: *"When the work timer is turned off, the tab
should automatically switch to the break timer and vice versa, but not
start unless the settings say to."*

This audit follows the standard audit-first workflow
(`docs/audits/CONNECTED_TESTS_STABILIZATION_AUDIT.md` shape).

---

## 1. Auto-switch the work / break tab on completion in non-Pomodoro mode  (YELLOW → PROCEED)

**Premise verification.** The Focus Timer
(`app/src/main/java/com/averycorp/prismtask/ui/screens/timer/`) has two
distinct flows depending on whether **Pomodoro Mode** is toggled on:

- **Pomodoro Mode ON** — `TimerViewModel.onTimerCompleted()` already
  flips `mode` from `WORK` → `BREAK` (or vice versa) when the
  countdown reaches zero, and only auto-starts the next timer when
  `autoStartBreaks` / `autoStartWork` is set
  (`TimerViewModel.kt:210-246`). This already matches the requested
  behaviour.
- **Pomodoro Mode OFF** (default) — same handler returns immediately at
  `TimerViewModel.kt:212` (`if (!state.pomodoroEnabled) return`). The
  ring fills, the notification fires, but the segmented "Work / Break /
  Custom" tab stays put. The user has to tap a different tab and press
  Start manually to begin the next phase.

The "tab" in the operator request refers to `ThemedModeSelector`
(`TimerScreen.kt:472-503`), which is rendered only when Pomodoro Mode is
off (`TimerScreen.kt:175-177`). So the gap is real and lives in
exactly one branch — the non-Pomodoro branch of `onTimerCompleted`.

**Findings.**

- `TimerPreferences` already exposes `getAutoStartBreaks()` and
  `getAutoStartWork()` flags (`TimerPreferences.kt:85-91`), wired into
  `TimerUiState.autoStartBreaks` / `autoStartWork`
  (`TimerViewModel.kt:84-93`) and toggleable from
  `PomodoroSettings` rows in `TimerScreen.kt:570-581`. No new pref keys
  required — the storage and toggle UI are both already shipped.
- The pref descriptions read "Start break timer automatically after
  focus" / "Start focus timer automatically after break"
  (`TimerScreen.kt:572`, `579`). That copy currently only takes effect
  inside Pomodoro Mode. After this change, both descriptions become
  globally accurate; no copy edits required.
- `setMode(...)` (`TimerViewModel.kt:323-339`) already covers the same
  state transition for the user-tap case (cancel tick job, reset
  remaining/total, `isRunning = false`, `isLongBreak = false`). The
  auto-switch path in `onTimerCompleted` should land in the same final
  state so the UI is indistinguishable between "user tapped Break" and
  "work timer ran out and auto-switched to Break".
- `CUSTOM` mode does not participate. The operator only mentioned work
  ↔ break, and `CUSTOM` is a free-form duration with no natural
  counterpart. After a `CUSTOM` timer expires, the tab should stay on
  `CUSTOM` (existing behaviour preserved by the early-return path being
  scoped to `WORK` / `BREAK` only).
- `isLongBreak` is irrelevant in non-Pomodoro mode (the long-break
  cycle is keyed off `completedSessions % sessionsUntilLongBreak`,
  which only increments inside the Pomodoro branch). The non-Pomodoro
  WORK→BREAK auto-switch should always use the short-break duration
  and leave `isLongBreak = false`.
- Widget sync: the Pomodoro branch's surrounding code
  (`TimerViewModel.kt:189-195`) already calls `syncWidgetState()` and
  `widgetUpdateManager.updateTimerWidget()` after the auto-switch
  resolves, so the new non-Pomodoro path inherits that for free —
  no extra call needed inside `onTimerCompleted`.

**Risk classification.** YELLOW — small surface, but it touches a
hot path (timer completion) that runs from a coroutine and writes UI
state. The pre-existing Pomodoro auto-switch is the obvious template;
the diff is structurally a one-branch extension.

**Recommendation.** PROCEED. Land as a single small PR.

### Implementation sketch

```kotlin
// TimerViewModel.kt — onTimerCompleted()
private fun onTimerCompleted() {
    val state = _uiState.value

    if (state.pomodoroEnabled) {
        // existing Pomodoro auto-switch (unchanged)
        ...
        return
    }

    // Non-Pomodoro: WORK <-> BREAK auto-switch. CUSTOM stays put.
    when (state.mode) {
        TimerMode.WORK -> {
            val breakDuration = breakDurationSeconds.value
            _uiState.value = state.copy(
                mode = TimerMode.BREAK,
                isLongBreak = false,
                remainingSeconds = breakDuration,
                totalSeconds = breakDuration,
                isRunning = false
            )
            if (state.autoStartBreaks) start()
        }
        TimerMode.BREAK -> {
            val workDuration = workDurationSeconds.value
            _uiState.value = state.copy(
                mode = TimerMode.WORK,
                isLongBreak = false,
                remainingSeconds = workDuration,
                totalSeconds = workDuration,
                isRunning = false
            )
            if (state.autoStartWork) start()
        }
        TimerMode.CUSTOM -> Unit
    }
}
```

### Test plan

`TimerViewModel` has no existing unit-test file (verified via
`grep -rn "TimerViewModel\b" app/src/test app/src/androidTest`).
Land a fresh `TimerViewModelTest.kt` under
`app/src/test/java/com/averycorp/prismtask/ui/screens/timer/` covering:

1. Non-Pomodoro `WORK` timer reaching zero with `autoStartBreaks = false`
   → mode flips to `BREAK`, `remainingSeconds == breakDurationSeconds`,
   `isRunning == false`.
2. Non-Pomodoro `WORK` timer reaching zero with `autoStartBreaks = true`
   → mode flips to `BREAK`, `isRunning == true`.
3. Non-Pomodoro `BREAK` timer reaching zero with `autoStartWork = false`
   → mode flips to `WORK`, `isRunning == false`.
4. Non-Pomodoro `BREAK` timer reaching zero with `autoStartWork = true`
   → mode flips to `WORK`, `isRunning == true`.
5. Non-Pomodoro `CUSTOM` timer reaching zero → mode unchanged
   (`CUSTOM`), `isRunning == false`.
6. Pomodoro Mode ON regression — existing WORK→BREAK auto-switch +
   `autoStartBreaks` gating still behaves as before (simple round-trip
   test to lock in the unchanged branch).

The countdown loop (`start()`'s `delay(1000L)` inside
`viewModelScope.launch`) is not test-friendly. Drive the transition
directly by seeding state and exposing a thin testing seam — either by
making `onTimerCompleted` `internal`+`@VisibleForTesting`, or by
ticking via `runTest` + `StandardTestDispatcher` and
`advanceTimeBy(1000)`. Prefer the `@VisibleForTesting` path: matches
the precedent set by `SmartPomodoroViewModel.autoLogPomodoroSessionTime`
(`SmartPomodoroViewModel.kt:795-796`) and avoids depending on the
1-second tick loop in unit tests.

---

## Improvement ranking

| # | Improvement | Wall-clock saved (per occurrence) | Implementation cost | Ratio |
|---|---|---|---|---|
| 1 | Non-Pomodoro auto-switch + auto-start gating (this audit) | ~2-3s per timer end (one tap saved per cycle, eliminates "where did the timer go?" confusion) | ~30 min (one branch in `onTimerCompleted` + ~6 unit tests) | High |

Single-item audit — no anti-pattern list this round.

---

## Phase 3 — Bundle summary

_Filled in after Phase 2 PR is opened._

- PR: _(filled in after open)_
- Memory candidates: none anticipated — the change is a localised
  bug-fill rather than a new convention.
- Schedule for next audit: ad-hoc; nothing in this scope blocks
  follow-up work.
