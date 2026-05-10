# D6#1 — Runtime Device-State Edge Case Runbook

**Audit:** `docs/audits/AUTOMATED_EDGE_CASE_TESTING_AUDIT.md` (the
parallel test-infrastructure slice, already shipped via PRs
#879/#882/#885/#886) + this bundle's audit at
`docs/audits/D5_D6_FINISH_BUNDLE_AUDIT.md` §6.
**Phase F gate:** No (non-blocking but recommended).
**Owner:** Avery (operator); AVD-runnable subset can engage Cowork or
local AVD.
**Wall-clock:** ~1-2 hours.
**Closes:** D6#1.
**Companion runbooks:** none (orthogonal to medication / widget /
NLP runbooks).

---

## Why this runbook exists

The audit at `AUTOMATED_EDGE_CASE_TESTING_AUDIT.md` shipped Tier A
infrastructure (sync state-machine fuzzer + DayBoundary→TimeProvider
injection) on 2026-04-28 via PRs #879 / #882 / #885 / #886. That work
covers happy-path-bias reduction in the test suite.

The operator's D6#1 framing is a **different slice**: runtime
device-state edge cases (airplane / timezone / low battery / clock
skew). This is observable in production, not test infrastructure.
This runbook covers the runtime slice.

Clock skew was SKIPPED on 2026-04-29 on Cowork non-rooted AVDs because
setting system clock requires root or userdebug AVD. The two AVDs
attached this session (`emulator-5554`, `emulator-5556`) are stock
Google sysimg, also not userdebug — so clock skew remains BLOCKED on
either userdebug AVD or real device.

Recommendation: run all 4 cases in one session on S25 (which is rooted-
adjacent for the dev account: clock skew works via OneUI Settings →
Date and time → Manual). The 3 AVD-runnable cases can engage
emulator-first as belt-and-suspenders.

## Pre-conditions

- **Device.** S25 Ultra is preferred for full coverage. Otherwise
  AVD pair (clock skew BLOCKED on stock sysimg).
- **Account.** Test account.
- **Logcat.** `adb logcat -s PrismSync:I PrismTask:V > ~/test_runs/D6_1_{date}.log`
  in a separate terminal.
- **Time budget.** Reserve 2 hours.

## Case 1 — Airplane mode

### Steps

1. Open PrismTask. Sign-in to test account. Wait for
   `SyncService.fullSync end`.
2. Enable airplane mode:
   ```bash
   adb shell cmd connectivity airplane-mode enable
   ```
   Or via OneUI Settings → toggle Airplane mode.
3. **Create a task offline.** Verify task appears in UI with
   "Offline / pending sync" indicator (or absence of sync confirmation).
4. **Edit a habit offline.** Toggle completion; verify local state
   updates.
5. **Force-quit and re-open** PrismTask while still offline.
6. Verify offline edits persist.
7. Disable airplane mode:
   ```bash
   adb shell cmd connectivity airplane-mode disable
   ```
8. Wait 30 seconds.
9. Verify sync resumes: logcat shows `SyncService: pushing N pending
   mutations`. Pending indicators clear.
10. Verify mutations land on cloud (cross-check from another device or
    Firestore console).

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| App crashes on offline mutation | **P0** — file |
| Offline edit lost on force-quit | **P0** — DataStore / Room write race, file |
| Sync doesn't resume on network return | **P0** — file with logcat |
| Sync duplicates mutation (cloud has 2 copies) | P1 — file |
| Pending indicator stuck after sync | P2 — UI bug, file |

## Case 2 — Timezone change

### Steps

1. Note baseline: open Today screen, observe today's tasks list.
   Take screenshot.
2. Note baseline: a habit with current streak ≥ 2 days.
3. Change timezone forward:
   ```bash
   adb shell settings put global auto_time_zone 0
   adb shell setprop persist.sys.timezone America/Los_Angeles  # or any 8h+ ahead
   ```
   Or via OneUI Settings → Date and time → Manual → pick different timezone.
4. Open PrismTask (force-restart if needed for clean fetch).
5. Verify Today screen re-anchors:
   - Tasks scheduled for "today" reflect the new timezone's "today".
   - SoD boundary recalculates correctly per `core/time/DayBoundary`
     (the audit's A2 PR #885 ensured this).
6. Verify habit streak recalculates correctly without false-loss:
   - Streak should NOT decrement spuriously due to timezone change
     mid-day.
7. Verify scheduled medication reminders re-anchor:
   - Open Medications → check next-firing time. Should reflect the
     new timezone's wall-clock time.
8. Change timezone back:
   ```bash
   adb shell setprop persist.sys.timezone America/New_York
   ```
9. Verify state restores cleanly.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Today screen shows yesterday's tasks after timezone forward | **P0** — DayBoundary regression (PR #885), file |
| Habit streak decrements spuriously | **P0** — streak calculator timezone-naive, file |
| Medication reminder fires at wrong wall-clock time | **P0** — alarm anchor doesn't track timezone |
| Crash on timezone change | **P0** — file |
| Display timestamps off by N hours | P2 — formatting bug (UTC vs local), file |

## Case 3 — Low battery

### Steps

1. Open PrismTask. Note current behavior: animations, sync cadence,
   notification firing.
2. Simulate low battery:
   ```bash
   adb shell dumpsys battery set level 5
   adb shell dumpsys battery set status 3   # discharging
   ```
3. Wait 30 seconds.
4. **Open various screens**: Today, Habits, Projects, Medications,
   AI Coach.
5. Verify each renders without crash.
6. Schedule a reminder for `Now + 5 min`.
7. Wait 6 minutes.
8. Expected on AVD: notification fires (AVD does NOT enable
   battery-saver auto-throttle by default; this is a partial test).
9. Expected on S25: behavior depends on whether OneUI auto-engaged
   "Power Saver" mode. Document outcome.
10. Reset battery:
    ```bash
    adb shell dumpsys battery reset
    ```

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Crash on low battery render | **P0** — file |
| Notification suppressed (S25 only) | YELLOW — Samsung's Power Saver behavior; document workaround (request user opt-out for PrismTask) |
| Animations / atmospherics broken | P2 — visual bug, file |

## Case 4 — Clock skew

**Note:** BLOCKED on stock Google sysimg AVD (no root). Run on S25
or userdebug AVD only.

### Steps (S25)

1. Note baseline: today's tasks, current habit streaks, scheduled
   reminders.
2. **Forward skew (2 hours)**: OneUI Settings → Date and time →
   Manual → set time +2h ahead.
3. Wait 30 seconds for app to detect.
4. Open PrismTask.
5. **Verify reminders that should have fired in the skipped 2 hours**:
   - Per-(med, slot) CLOCK alarm: should NOT double-fire on next
     wake-up (per PR #1055 / #986 anti-double-fire guards).
   - Habit reminder: should NOT spuriously fire if scheduled in the
     skipped window.
6. **Verify SoD boundary**: if skew crossed midnight, today's tasks
   should reflect the post-skew "today".
7. **Backward skew (-2 hours)**: set time 2h earlier.
8. Verify no spurious re-fire of recently-fired reminders.
9. Restore correct time.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Reminders fire multiple times after forward skew | **P0** — anti-double-fire guard regression (PR #1055), file |
| Habit streak decrements on backward skew | P1 — streak calculator clock-skew-naive, file |
| Today screen flickers / doesn't re-anchor | P2 — UI subscription bug, file |
| Crash on time change | **P0** — file |

## Outcome matrix

| Case | Outcome | Notes |
|------|---------|-------|
| 1 — Airplane mode | GREEN/YELLOW/RED |  |
| 2 — Timezone change | GREEN/YELLOW/RED |  |
| 3 — Low battery | GREEN/YELLOW/RED |  |
| 4 — Clock skew (S25 only) | GREEN/YELLOW/RED/BLOCKED |  |

## Closure criteria

D6#1 → 1.0 when:
- All 4 cases run on S25 (or 3 cases on AVD if S25 unavailable).
- All P0 failures filed and addressed.
- All P1 failures triaged.
- Logcat captures saved.
- Outcome appended to this runbook (Phase 4 Outcomes section) and
  to the bundle audit.

## Append-to-audit template

```markdown
## Phase 5 — D6#1 Runtime Edge Case Verification ({device}, {date})

| Case | Outcome | Notes |
|------|---------|-------|
| 1 — Airplane mode | xx | {one-liner} |
| 2 — Timezone change | xx | {one-liner} |
| 3 — Low battery | xx | {one-liner} |
| 4 — Clock skew | xx | {one-liner or "BLOCKED on stock AVD"} |

Verdict: GO / GO-WITH-KNOWN-ISSUES / CONDITIONAL / NO-GO.

**D6#1 closure:** 0.7 → 1.0 (or 0.85 if clock-skew BLOCKED).
```
