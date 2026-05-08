# D6 — Foreground-Service Timer Migration Audit

**Status**: Phase 0 STOP-A fired and was resolved by operator picking
**Path C (reuse, don't extract)**. Phase 1 verdicts below reflect Path C.
This doc supersedes the original prompt's Path B framing; Items 1–10 from
the prompt are remapped to Path C-relevant verdicts.

**Branch**: `claude/foreground-service-timer-dEjPL`
**Base**: `origin/main` @ `8ce17bd` (PRs #1190, #1191 visible) ✓

---

## Phase 0 verification results

### ✓ Base-branch verification — PASS

`git fetch && git rebase origin/main` clean. PR #1190 (#1191 docs(audits): D6
AI proposal render gap STOP-A reframe) and PR #1190
(fix(checkin): drop dead shouldPrompt + cutoff validator F9 mega) both visible
at top of `git log origin/main`.

### ⚠️ Premise checks — MULTIPLE REFRAMES

The operator's prompt locks Path B (extract `TimerService` base class from
`PomodoroTimerService`; both Pomodoro and a new non-Pomodoro `TimerService`
extend the base) and forbids re-litigation during audit. Phase 0 file reads
surface five substantive premise mismatches with the locked path. Listed
file:line so the operator can verify directly.

#### Reframe 1 — PomodoroTimerService does NOT expose a StateFlow surface

**Prompt premise** (Item 1 + Item 4): "StateFlow surface: `val state:
StateFlow<TimerState>` consumable by both VM and widget" / "TimerViewModel's
`state: StateFlow<TimerState>` proxies TimerService.state via service
connection."

**Reality**
(`app/src/main/java/com/averycorp/prismtask/notifications/PomodoroTimerService.kt`):

- `onBind()` returns `null` (line 48) — service is **started**, not bound. No
  bound-service interface exists.
- State is pushed via `sendBroadcast(Intent(ACTION_TICK)…)` every second
  (line 238–247) and `sendBroadcast(Intent(ACTION_COMPLETE)…)` on completion
  (line 125–131).
- `SmartPomodoroViewModel` consumes via `BroadcastReceiver` registered on
  `appContext` (line 256–271, 302–322). It does **not** use a service
  connection / `Binder` / shared StateFlow.

**Implication**: "Item 4 — TimerViewModel migration → bound-service pattern"
is not extracting an existing pattern; it is **inventing a new one**.
Re-architecting PomodoroTimerService to expose a StateFlow + Binder breaks
SmartPomodoroViewModel's broadcast-based consumption (or doubles the surface
during transition).

#### Reframe 2 — PomodoroTimerService contains NO Pomodoro-specific cadence

**Prompt premise** (Item 1 + Item 2): "Pomodoro-specific surface (stays in
PomodoroTimerService): Long break / short break cadence … Auto-start next
session preference. Session-count tracking. Pomodoro-specific notification
copy + actions (Skip Break, Skip Work)."

**Reality**: PomodoroTimerService is **already a generic countdown service**.
It accepts a `sessionType` *string* via intent extra and counts down. It
does:

- not own session-count state — `sessionIndex` is just echoed back in tick
  broadcasts (line 45, 68, 243),
- not own cadence logic — the work→break→long-break alternation lives in
  consumers,
- not own auto-start — same,
- not own a "Skip" action — only "Stop" (line 181–185).

The actual Pomodoro cadence logic lives in **two places**:

- `SmartPomodoroViewModel.onTimerComplete()` lines 743–768: the
  `(sessionIndex + 1) % 4 == 0` long-break check, work→break transition,
  next-session start.
- `TimerViewModel.onTimerCompleted()` lines 246–277: a parallel,
  **independent** Pomodoro cadence implementation for the free flow
  (sessionsUntilLongBreak, autoStartBreaks/Work, completedSessions,
  isLongBreak).

**Implication**: There is nothing Pomodoro-specific to "leave in
PomodoroTimerService" while extracting the rest into a base class — the
service is already the base. The Pomodoro vs. non-Pomodoro split lives at
the **ViewModel** layer, not the service layer.

The operator's mental model ("generalize-existing vs. extract-base") was
choosing between two reformulations of code that doesn't exist.

#### Reframe 3 — PomodoroTimerService has NO Pause/Resume on the service itself

**Prompt premise** (Item 1): "Control surface: `start(durationMs: Long)`,
`pause()`, `resume()`, `stop()`."

**Reality** (line 256–264): action surface is `ACTION_START`, `ACTION_STOP`,
`ACTION_TICK` (out), `ACTION_COMPLETE` (out). No PAUSE / RESUME.

`SmartPomodoroViewModel.pauseTimer()` (line 614–620) **stops** the service
and snapshots remaining seconds in `_timerSecondsRemaining`. `resumeTimer()`
(line 622–636) **starts** a fresh service with the remembered remaining
seconds. Pause/Resume is faked at the consumer.

**Implication**: Adding real Pause/Resume is a non-trivial service-level
addition (state machine, notification action wiring, broadcast extension,
SmartPomodoroViewModel migration off the stop-and-restart pattern). Not a
free side-effect of "extracting the base."

#### Reframe 4 — TimerViewModel is NOT a non-Pomodoro flow

**Prompt premise** (Context + Item 3): "TimerViewModel (broken — in-memory
only)" vs. "SmartPomodoroScreen + SmartPomodoroViewModel (Pro feature,
ALREADY uses PomodoroTimerService)" framed as Pomodoro-vs-non-Pomodoro
split. Item 3 specifies "**New TimerService for non-Pomodoro flow** … No
Pomodoro cadence — `Completed` state is terminal."

**Reality** (`TimerViewModel.kt`):

- `TimerMode.WORK` / `TimerMode.BREAK` / `TimerMode.CUSTOM` (line 22).
- Independent Pomodoro state: `pomodoroEnabled`, `completedSessions`,
  `sessionsUntilLongBreak`, `isLongBreak`, `autoStartBreaks`, `autoStartWork`
  (line 24–36).
- `onTimerCompleted()` handles **both** non-Pomodoro auto-mode-switch AND
  Pomodoro cadence including long-break-every-N detection (lines 213–277).

The actual split is:
- **Free flow** (`TimerViewModel`): user-toggleable Pomodoro on/off + a
  user-controllable custom timer. Used by the in-app Timer tab + chat
  deep-link + widget Start button.
- **Pro AI flow** (`SmartPomodoroViewModel`): backend-API-planned multi-session
  Pomodoro with AI coaching. Used by SmartPomodoroScreen.

Both are *Pomodoro-capable*. A "new TimerService for non-Pomodoro flow"
covers only `TimerMode.CUSTOM` (and arguably the auto-mode-switch case);
TimerViewModel's free-Pomodoro path needs the full work/break cadence the
prompt says to keep in PomodoroTimerService.

**Implication**: The two-service split (PomodoroTimerService + new
TimerService) doesn't map onto the two-ViewModel reality. Either:
- (a) TimerViewModel uses PomodoroTimerService directly (what
      SmartPomodoroViewModel already does), no new service needed; or
- (b) Both services are nearly identical, defeating the extraction
      argument.

#### Reframe 5 — Widget controls were never present; "re-introduction" is incorrect framing

**Prompt premise** (Item 5): "Re-introduce widget controls: Pause / Resume /
Stop … `TimerWidgetActions` enum (or equivalent) re-introduces Pause/Resume/Stop
entries (verify against PR #1042 audit which entries were removed)."

**Reality** (`TimerWidget.kt` lines 193–201):

```
// No pause/resume/skip controls here: the live countdown lives
// in TimerViewModel.viewModelScope, which doesn't observe this
// DataStore, so any widget-side mutation is overwritten by the
// next ViewModel sync. The whole widget is clickable to open
// the Timer screen, where the in-app controls work.
```

- Current widget: tap-to-open-Timer (`WidgetLaunchAction.OpenTimer` deep-link)
  + state rendering. **No** WidgetActions enum, **no** ACTION_PAUSE /
  ACTION_RESUME / ACTION_SKIP constants exist anywhere in the widget package
  (`grep` returns nothing).
- Per `git log` history, the deferral note in the source comment matches
  the prompt's premise *that controls don't currently work*. But there are
  no removed enum entries to "re-introduce" — the controls never landed in
  a form the widget could dispatch.

**Implication**: The widget-controls work is greenfield, not a revert of a
PR #1042 deletion. Not a fatal reframe on its own, but it changes the LOC
estimate and the test-coverage surface (Item 9).

### ⚠️ Foreground-service type — already declared as `mediaPlayback`

`AndroidManifest.xml` line 116–118 + 50–51:

- `<service android:name=".notifications.PomodoroTimerService"
  android:foregroundServiceType="mediaPlayback" />`
- `<uses-permission FOREGROUND_SERVICE />` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- Comment at line 46–49 explicitly justifies `mediaPlayback`: "Samsung One
  UI suppresses…" — changing this would regress a documented
  device-specific fix.

Item 6 verdict (had Phase 1 not been blocked) would have inherited
`mediaPlayback` for any new TimerService. `specialUse` is **not** an option
without breaking the Samsung One UI fix.

---

## STOP-A reframe verdict

### Why STOP-A fires

Item 10 STOP-A clause: "PomodoroTimerService API surface is incompatible
with base-class extraction (e.g. Pomodoro-specific logic deeply intertwined
with foreground lifecycle) → STOP, re-evaluate Path A vs Path B with
operator."

The reverse condition holds: PomodoroTimerService has *no Pomodoro-specific
logic intertwined with the lifecycle* (Reframe 2). The whole premise of
Path A vs Path B (generalize-existing vs. extract-base) is choosing between
reformulations of code that doesn't exist. The actual choice is about which
**ViewModel** owns the cadence and how the existing already-generic service
is consumed.

Pre-flight checklist final item explicitly requires this STOP:

> [ ] No premise reframes from prompt — if any, STOP-A and report

### Best-guess reframe — what the migration probably actually looks like

(Not a recommendation to apply unilaterally — flagged for operator review.)

**Path C — "Reuse, don't extract"**:

1. Add `ACTION_PAUSE` / `ACTION_RESUME` to the existing
   `PomodoroTimerService` (it's already a generic countdown service; no
   extraction needed). Pause stashes `secondsRemaining`; Resume relaunches
   the tick loop with the stashed value. Notification gains Pause/Resume
   actions alongside the existing Stop.
2. Migrate `TimerViewModel` to consume `PomodoroTimerService` via
   `BroadcastReceiver` — the same pattern `SmartPomodoroViewModel` already
   uses. TimerViewModel keeps its own cadence logic (free Pomodoro flow)
   but delegates the tick + completion notification to the service.
3. Re-wire widget controls: Glance composable adds Pause/Resume/Stop
   buttons that dispatch `PendingIntent.getService(...)` to
   PomodoroTimerService directly, leveraging its existing intent-action
   surface.
4. SmartPomodoroViewModel's pause/resume continues working unchanged
   (stop-and-restart pattern), or optionally migrates to use the new
   ACTION_PAUSE/RESUME for cleaner semantics — either is non-breaking.

**LOC estimate (rough, Path C)**: 200–400 LOC.

- PomodoroTimerService Pause/Resume: ~80 LOC.
- TimerViewModel migration to BroadcastReceiver pattern: ~120 LOC.
- Widget controls + intent surface: ~80 LOC.
- Tests: ~100 LOC.

This is well below the original 400–700 LOC estimate and below STOP-F
(200 LOC) only if the four items are bundled together — same justification
as the prompt's mega-PR rationale.

### Why I'm not unilaterally applying Path C

1. The prompt explicitly locked Path B and forbade re-litigation. Path C
   is not Path A *or* Path B — it's a third option the locked decision
   framework didn't contemplate.
2. Operator-context-sensitive decisions: the "extract a base class"
   instinct may be coming from a longer-term plan (e.g. anticipating a
   Habit timer, Medication timer, etc., where the base really would
   amortize). Path C trades that future amortization for present
   simplicity — a tradeoff the operator should pick.
3. SmartPomodoroViewModel's broadcast-based consumption is a valid pattern
   but is **not** what the prompt assumed (StateFlow + bound service).
   Operator should confirm whether they meant for the migration to
   *change* SmartPomodoroViewModel's consumption pattern (a much bigger
   PR) or keep it as-is.

---

## Path C — Phase 1 verdicts

Operator picked **Apply Path C (reuse)** at the STOP-A gate. The prompt's
Item structure (which assumed Path B / extract-base) is remapped here.

### Item 1 (remapped) — Service action surface extension

**GREEN**. Add four new actions + one extra to `PomodoroTimerService`:

- `ACTION_PAUSE` (in) — cancels tick loop, snapshots `secondsRemaining`,
  sets `isPaused=true`, updates ongoing notification with Resume action,
  emits `ACTION_PAUSED` broadcast.
- `ACTION_RESUME` (in) — clears `isPaused`, restarts tick loop from current
  `secondsRemaining`, updates notification, emits `ACTION_RESUMED`.
- `ACTION_PAUSED` (out) — broadcast notification VMs can sync to.
- `ACTION_RESUMED` (out) — same.
- `EXTRA_OWNER` (string in/out) — disambiguates broadcasts between
  `OWNER_TIMER` (TimerViewModel) and `OWNER_SMART_POMODORO`
  (SmartPomodoroViewModel). The service stashes the owner from `ACTION_START`
  and echoes it in every outbound broadcast. Without owner filtering, the
  migration introduces a cross-talk bug where TimerVM picks up
  AI-Pomodoro ticks (and vice-versa).

Refactor: extract `runTickLoop()` from `startCountdown()` for reuse in
`resumeCountdown()`. Notification builder takes `isPaused` flag and shows
Pause-or-Resume action accordingly.

### Item 2 (remapped) — SmartPomodoroViewModel impact

**GREEN, minimal**. Two changes:
- `startTimer()` (line 717) adds `EXTRA_OWNER = OWNER_SMART_POMODORO` to the
  start intent.
- BroadcastReceiver (line 256) filters on `EXTRA_OWNER == OWNER_SMART_POMODORO`
  before processing tick/complete broadcasts.

`pauseTimer()` / `resumeTimer()` keep their existing stop-and-restart
pattern unchanged (operator-confirmed). Migrating SmartPomodoroVM to use
the new ACTION_PAUSE/RESUME is out of Path C scope.

### Item 3 (remapped) — No new TimerService class

Path B's "new TimerService for non-Pomodoro flow" is **dropped**. Per
Reframe 2 in this doc, PomodoroTimerService is already a generic countdown
service; TimerViewModel will consume it directly with `OWNER_TIMER`.

### Item 4 — TimerViewModel migration

**GREEN**. Replace in-memory `tickJob` + `delay(1000)` loop with
broadcast-driven consumption.

- `start()`: drop the `viewModelScope.launch { while ... delay(1000) }` body.
  Replace with `PomodoroTimerService.start(appContext, durationSeconds,
  sessionIndex, sessionType, owner = OWNER_TIMER)`. Map `TimerMode` →
  `sessionType`:
  - `WORK` → `SESSION_TYPE_WORK`
  - `BREAK + isLongBreak` → `SESSION_TYPE_LONG_BREAK`
  - `BREAK` → `SESSION_TYPE_BREAK`
  - `CUSTOM` → `SESSION_TYPE_WORK`
- `pause()`: call `PomodoroTimerService.pause(appContext, OWNER_TIMER)`.
- New `resume()`: call `PomodoroTimerService.resume(appContext, OWNER_TIMER)`.
  Replaces the implicit "start while paused" branch in `toggleStartPause()`.
- `reset()` / `resetPomodoro()` / `setMode()` / `skipToNext()` /
  `onCleared()`: any state-changing path that previously cancelled
  `tickJob` now also calls `PomodoroTimerService.stop(appContext)`.
- BroadcastReceiver registered in init, unregistered in onCleared.
  Filters on owner. On `ACTION_TICK`: update `remainingSeconds` + run the
  existing widget-sync-every-30-ticks logic. On `ACTION_PAUSED`: set
  `isRunning=false`. On `ACTION_RESUMED`: set `isRunning=true`. On
  `ACTION_COMPLETE`: invoke existing `onTimerCompleted()` cadence logic
  (this is where the auto-start-next-session branch lives).

**Backward-compat**: existing TimerScreen consumers + chat deep-link (PR
#1168 B.4) + widget Start button observe `uiState: StateFlow<TimerUiState>`,
which keeps the same shape. No consumer-level changes.

### Item 5 — Widget controls

**GREEN**. TimerWidget's active-state branch (line 116–202) currently shows
"Tap to manage" / "Tap to resume" caption. Replace with a row of three
icon buttons: Pause (visible when `isRunning && !isPaused`), Resume (visible
when `isPaused`), Stop (always visible during active state). Each dispatches
via Glance's `actionStartService` to PomodoroTimerService with the
corresponding action + `EXTRA_OWNER = OWNER_TIMER`.

The existing tap-to-open-Timer (`actionStartActivity`
WidgetLaunchAction.OpenTimer) remains on the parent column for non-button
taps.

Widget refresh: TimerViewModel writes TimerWidgetState on broadcast tick.
When VM is alive, widget stays in sync. When VM is dead but service is
alive (process kill scenario), the foreground-service notification provides
the user-visible source of truth — widget may briefly lag until next VM
hydration. Acceptable trade-off for Path C minimum scope.

### Item 6 — Foreground-service permission + Android 14+

**GREEN, no changes**. `mediaPlayback` already declared (manifest line 118)
and matched by `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission (line 51).
Per the manifest comment at line 46, this is a deliberate Samsung One UI
fix; do not change. New TimerService class isn't being introduced
(per Item 3 remapping), so no new manifest entries.

### Item 7 — Process-death survival

**Option A applied** (no DataStore-persisted service-restart-from-cold). On
process death (force-stop), the foreground service dies with the process;
user expectation is timer ends. On low-memory pressure, the foreground
service keeps the process alive. Path C does not introduce restart-from-cold
state recovery; that is a separate, larger feature.

### Item 8 — Sibling foreground-service surfaces sweep

Recon-only, none bundled. Candidates surveyed:
- Habit completion: no in-memory tickers — uses Room + WorkManager. No FGS
  candidate.
- Medication reminders: AlarmManager-based, fires single notifications.
  Different shape — no FGS candidate.
- Sync: `SyncService` uses WorkManager periodic worker. Different shape.
- Voice input recording (`VoiceInputManager`): runs SpeechRecognizer
  bound-service-style. Could be an FGS candidate if long-form recording
  is added; not currently a problem.

**Verdict**: 0 sibling FGS candidates needing migration. No F-series
follow-ons filed.

### Item 9 — Test coverage

- Existing `TimerViewModelTest` rewrites: drop `runCurrent` /
  `advanceTimeBy` reliance on the in-memory tick loop (which previously
  caused PR #1185, #1187, #1189 flakiness fixes). Replace with direct
  invocation of broadcast-handler entry points + verification of
  `PomodoroTimerService.start/pause/resume/stop` static calls (already
  guarded against unit-test stubs at lines 287–309 of the service).
- New: state-machine unit tests for `pauseCountdown` / `resumeCountdown` /
  notification-action-routing. Guarded similarly.
- Existing `SmartPomodoroViewModelTest` regression: owner-filter doesn't
  break existing assertions (broadcasts emitted include
  `OWNER_SMART_POMODORO` by default).
- Manual on-device per Phase 3.

### Item 10 — STOP conditions

- STOP-A: **fired and resolved** (Path C picked).
- STOP-D: 0 sibling candidates (Item 8). N/A.
- STOP-F: pre-approved exception. Path C estimate < 1,000 LOC.
- STOP-MEGA: estimate well below 1,500 LOC. N/A.
- STOP-G: no in-flight conflict on target files (verified `git log`).
- STOP-SmartPomodoro-break: Item 2 GREEN, ~10 LOC delta. N/A.
- STOP-LOC drift: monitor mid-Phase-2.

### LOC estimate

| Component | Added | Modified | Removed |
|---|---|---|---|
| PomodoroTimerService | ~120 | ~30 | 0 |
| TimerViewModel | ~120 | ~80 | ~50 |
| SmartPomodoroViewModel | ~15 | ~5 | 0 |
| TimerWidget | ~80 | ~10 | ~5 |
| TimerViewModelTest | ~50 | ~80 | ~50 |
| **Total** | **~385** | **~205** | **~105** |

Net delta: ~485 LOC. Well below STOP-MEGA (1,500) and below the prompt's
hard ceiling (1,000).

---

## Phase 2 — Implementation summary

Landed in this branch / PR. Files touched:

| File | Change |
|---|---|
| `app/src/main/java/com/averycorp/prismtask/notifications/PomodoroTimerService.kt` | Added `ACTION_PAUSE` / `ACTION_RESUME` / `ACTION_PAUSED` / `ACTION_RESUMED` action constants. Added `EXTRA_OWNER`, `OWNER_TIMER`, `OWNER_SMART_POMODORO`. Added `pauseCountdown()` / `resumeCountdown()` handlers + `runTickLoop()` extraction. Added `pause(context)` / `resume(context)` companion entry points. Notification builder gains a Pause/Resume action that flips on `isPaused`. All outbound broadcasts now carry `EXTRA_OWNER`. |
| `app/src/main/java/com/averycorp/prismtask/ui/screens/timer/TimerViewModel.kt` | Replaced in-memory `tickJob` + `delay(1000)` countdown with `BroadcastReceiver` consumption of `PomodoroTimerService` broadcasts filtered on `OWNER_TIMER`. `start()` / `pause()` / new `resume()` dispatch to the service. `reset()` / `resetPomodoro()` / `setMode()` / `skipToNext()` stop the service before mutating local state. `toggleStartPause()` now has three branches (idle → start, running → pause, paused → resume). `onCleared()` no longer cancels a tick job — instead it unregisters the receiver and only clears widget state when no session is running. |
| `app/src/main/java/com/averycorp/prismtask/ui/screens/pomodoro/SmartPomodoroViewModel.kt` | `BroadcastReceiver` filters on `EXTRA_OWNER == OWNER_SMART_POMODORO` (with backward-compat default). `startTimer()` passes `owner = OWNER_SMART_POMODORO` explicitly. No other behavior change. |
| `app/src/main/java/com/averycorp/prismtask/widget/TimerWidget.kt` | Active-state UI replaces the "Tap to manage" caption with a `TimerControlRow` of Pause/Resume + Stop buttons. Each dispatches via `actionRunCallback<TimerControlFromWidgetAction>` with the corresponding `TIMER_CONTROL_*` parameter. |
| `app/src/main/java/com/averycorp/prismtask/widget/WidgetActions.kt` | Added `WidgetActionKeys.TIMER_CONTROL` parameter key + `TIMER_CONTROL_PAUSE` / `_RESUME` / `_STOP` constants. Added `TimerControlFromWidgetAction` ActionCallback that routes to `PomodoroTimerService.pause/resume/stop`. Added `timerControlParams(control)` helper. |
| `app/src/test/java/com/averycorp/prismtask/ui/screens/timer/TimerViewModelTest.kt` | Removed legacy `tickJob`-leak mitigations (`toggleStartPauseIfRunning` in `@After`, "pause before returning" comments). Added three new tests for `toggleStartPause` idle / running / `reset()` flows. |

### Cross-talk bug prevention

The migration introduces a real cross-talk risk: both `TimerViewModel` and
`SmartPomodoroViewModel` now register `BroadcastReceiver`s on the *same*
broadcast actions. Without disambiguation, a Smart-Pomodoro tick broadcast
would update Timer's `remainingSeconds` and vice-versa. `EXTRA_OWNER`
filtering closes that hole at both consumer sides.

Automation-driven timers (`SimpleActionHandlers.ScheduleTimerActionHandler`)
omit the owner extra, so they default to `OWNER_SMART_POMODORO` —
preserving the pre-D6 automation behavior (only SmartPomodoroVM reacted
because TimerVM had no receiver at all). Documented but not changed.

### What did not land in this PR (deferred F-series candidates)

- Migration of SmartPomodoroVM's `pauseTimer()` / `resumeTimer()` from
  stop-and-restart to the new `ACTION_PAUSE` / `ACTION_RESUME` semantic.
  Operator-confirmed out-of-scope for Path C.
- Process-death state recovery (Item 7 Option B). Not needed; foreground
  service keeps the process alive through low-memory pressure, and
  force-stop kills the user-expected timer.
- Sibling foreground-service migrations (Item 8 returned 0 candidates).

---

## Phase 0 pre-flight checklist status

- [x] Base-branch verification passed (PRs #1190, #1191 visible)
- [x] PomodoroTimerService premise verified — IS a foreground service (line
      75–82: `startForeground(NOTIFICATION_ID_ONGOING, notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`) ✓
- [x] TimerViewModel TODO marker confirmed (line 180–183)
- [x] Widget current state documented (display-only, no removed controls
      to re-introduce; tap-to-open-Timer only)
- [x] SmartPomodoroViewModel surface inventory complete
      (BroadcastReceiver pattern, `pauseTimer()` does
      `PomodoroTimerService.stop()`, `resumeTimer()` does fresh `start()`)
- [x] Android 14+ FOREGROUND_SERVICE_TYPE compliance verified
      (`mediaPlayback` declared + corresponding permission granted)
- [ ] Audit doc Items 1–10 verdicts written — **DEFERRED on STOP-A**
- [ ] Item 7 process-death survival decision — **DEFERRED on STOP-A**
- [ ] Item 8 sibling sweep — **DEFERRED on STOP-A**
- [x] STOP-MEGA check — N/A, halted before LOC estimate
- [x] STOP-G check — no in-flight PRs touching the affected files (verified
      via `git log` on `origin/main` not surfacing any open work in the
      target paths)
- [x] STOP-SmartPomodoro-break check — N/A, halted before refactor
- [x] **No premise reframes from prompt — REFRAME COUNT: 5 → STOP-A**
