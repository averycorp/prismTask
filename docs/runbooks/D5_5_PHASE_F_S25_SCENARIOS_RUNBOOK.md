# D5#5 — Phase F S25 Scenarios Runbook (Tests 4-11)

**Audit:** `docs/audits/D5_D6_FINISH_BUNDLE_AUDIT.md` §5.
**Phase F gate:** YES.
**Owner:** Avery (operator), on Samsung Galaxy S25 Ultra.
**Wall-clock:** ~12 hours total (most concentrated in 1 active
session; Scenario 11 is overnight).
**Closes:** D5#5 + D5#1 Test 1.3 / 1.4-1.6 (Scenario 9).
**Companion runbooks:**
- `D5_2_MEDICATION_REMINDER_BOTH_MODE_S25.md` (Scenarios 5, 6, 8 fold
  into this for canonical reminder coverage).
- `D5_4_WIDGETS_S25_RUNBOOK.md` (Scenario 4 cross-references this for
  widget-specific cells).

---

## Why this runbook exists

The canonical reminder-focused 11-scenario runbook at
`docs/REMINDERS_TEST_RUNBOOK.md` was pruned from main on 2026-04-22 in
commit `7e19a3f8`. The hybrid runbook
`docs/PHASE_A_DEVICE_TESTING_RUNBOOK_HYBRID.md` is pinned to v1.4.38
(versionCode 682, DB v56) and explicitly does not cover v1.6.0+
medication reminder modes.

`docs/release/PRE_PHASE_F_REGRESSION_SWEEP.md` (PR #828) covers the
30-min abbreviated sweep with go/no-go rubric — but explicitly
disclaims being the 11-scenario detailed runbook.

This runbook fills the gap for v1.7.X: 11 scenarios, mixing the
canonical reminder coverage with the operator's prompt framing.
Scenarios that overlap with companion runbooks are cross-referenced
rather than duplicated, so each test runs once.

## Pre-conditions

- **Device.** S25 Ultra. Production keystore-signed `v1.7.X` build.
- **Account.** Test account with realistic mixed data (per D5#4
  pre-conditions: 5+ tasks, 3+ habits, 1+ project, 1+ medication,
  5+ recent completions).
- **Companion AVD pair.** `emulator-5554` + `emulator-5556` for
  cross-device scenarios.
- **Time budget.** Block out a 6-hour active session + overnight for
  Scenario 11 + 2-hour buffer for triage.
- **Logcat capture.** `adb logcat > ~/test_runs/D5_5_full_{date}.log`
  in a separate terminal for full session.

## The 11 scenarios

Numbered to match canonical 11-scenario reminder runbook structure
where applicable; gap-filled where canonical and operator prompt
framings diverged.

| # | Scenario | Companion runbook | Wall-clock |
|---|----------|-------------------|-----------:|
| 1 | Pre-flight: AI gate parity (Phase F surface) | this | 30 min |
| 2 | Pre-flight: Cross-device sign-in convergence | this | 30 min |
| 3 | Reminder fires from cold (CLOCK) | D5#2 Test 1 | covered |
| 4 | Widget cold-start render | D5#4 Cell C | covered |
| 5 | Reminder under OneUI Deep Sleep (S25-specific) | this | 1 hour |
| 6 | Reminder under Samsung Battery Optimization (S25-specific) | D5#2 Test 4 | covered |
| 7 | Reminder under permission revoke + re-grant | this | 30 min |
| 8 | Reminder mode swap mid-cycle (S25-specific) | this | 1 hour |
| 9 | NLP batch ops smoke (operator's reframe) | D5#1 Tests 1.3/1.4-1.6 | 30 min |
| 10 | Cross-device convergence under burst-write | this | 1 hour |
| 11 | Overnight Doze (8+ hours) | this | overnight + morning check |

Scenarios 3, 4, 6 cross-reference companion runbooks. Run them in
the companion runbook's session; mark this runbook's table as
"covered" with reference. Scenarios 1, 2, 5, 7, 8, 9, 10, 11 are
canonical to this runbook.

## Scenario 1 — Pre-flight: AI gate parity

**What this verifies.** AI features behave identically across
on-device entry points (Coach, Task editor, Smart Pomodoro) and
respect the user's per-feature opt-in flags from PR #1218.

### Steps

1. Settings → AI → set per-feature opt-ins:
   - AI Coach chat: ON
   - Smart Pomodoro: ON
   - Morning check-in: OFF (test the OFF case)
2. Open AI Coach. Send: "What should I do today?"
3. Expected: AI Coach responds.
4. Trigger Smart Pomodoro start.
5. Expected: Smart Pomodoro suggestion shown.
6. Trigger Morning Check-in flow (manually or wait until tomorrow).
7. Expected: prompt does NOT appear (per OFF flag).
8. Toggle Morning Check-in: ON.
9. Trigger flow.
10. Expected: prompt appears.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| AI feature ignored opt-in flag (fired despite OFF) | **P0** — file |
| AI feature suppressed despite ON | **P0** — file |

## Scenario 2 — Pre-flight: Cross-device sign-in convergence

**What this verifies.** D8 finish-bundle's Strangler Fig sync
orchestrators (PR #1240) preserve cross-device convergence.

### Steps

1. AVD-1 (`emulator-5554`): sign-in to test account fresh-install.
   Note Firestore baseline (logcat: `SyncService.fullSync end`).
2. AVD-2 (`emulator-5556`): sign-in to **same** account fresh-install.
3. Both devices: log baseline counts via Settings → Diagnostics →
   "Show row counts" (or use `adb shell run-as com.averycorp.prismtask
   sqlite3 databases/averytask.db "SELECT COUNT(*) FROM tasks"` etc.).
4. **AVD-1**: create 5 tasks, 2 habits, 1 medication, 3 task
   completions.
5. **AVD-2**: pull-to-refresh OR wait standard listener interval.
6. Verify AVD-2 row counts match AVD-1 within 60 seconds.
7. **AVD-2**: complete one task, edit one habit, log one medication
   dose.
8. **AVD-1**: verify mutations appear within 60 seconds.

### Expected logcat (sample)

```
SyncService.fullSync start
SyncService: pushing 11 pending mutations
push.summary: tasks=5 habits=2 medications=1 task_completions=3
SyncService: pulling
... (no new mutations from cloud since AVD-2 didn't write yet) ...
SyncService.fullSync end
```

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Convergence > 60 seconds for either direction | P1 — file with logcat |
| Row count mismatch after sync settles | **P0** — D8 closure regression |
| FK constraint violation on AVD-2 receive | **P0** — file |

## Scenario 5 — Reminder under OneUI Deep Sleep (S25-specific)

**What this verifies.** Samsung's "Deep Sleeping Apps" subsystem does
not silently kill medication reminder alarms.

### Steps

1. Settings (S25) → Battery → Background usage limits → "Deep Sleeping
   Apps" → **add PrismTask**.
2. Force-stop PrismTask:
   ```bash
   adb shell am force-stop com.averycorp.prismtask
   ```
3. Wait 30 minutes. Phone unplugged, screen off, phone face-down.
4. Open PrismTask. Schedule a CLOCK-mode medication reminder for
   `Now + 5 min`. Force-stop again.
5. Wait 6 minutes.
6. Expected: notification fires within 60 seconds of expected.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| No notification | **P0** — Samsung killed alarm registration. Document workaround (request user opt-out of Deep Sleeping Apps for PrismTask in onboarding) |
| Notification > 5 min delayed | P1 — Doze + Deep Sleep interaction; document |

## Scenario 7 — Reminder under permission revoke + re-grant

**What this verifies.** Permission state changes do not orphan or
duplicate reminders.

### Steps

1. Schedule a medication reminder for `Now + 30 min`.
2. **Revoke** POST_NOTIFICATIONS via OneUI Settings.
3. Wait until reminder time.
4. Expected: alarm fires (logcat shows `MedReminderReceiver:
   classifyAlarm`) but no notification posted (`NotificationHelper:
   permission denied`).
5. **Re-grant** POST_NOTIFICATIONS.
6. **Schedule a new reminder** for `Now + 5 min`.
7. Wait.
8. Expected: notification fires; no leak from suppressed prior
   alarm.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Crash on alarm fire while permission revoked | **P0** — file |
| Two notifications fire on re-grant (orphaned + new) | P1 — file |

## Scenario 8 — Reminder mode swap mid-cycle (S25-specific)

**What this verifies.** Switching global mode CLOCK ↔ INTERVAL
mid-cycle doesn't strand alarms.

### Steps

1. Settings → Medications → Reminder mode → **Global = CLOCK**.
2. Schedule a medication "TestMedSwap" with slot at `Now + 30 min`.
3. Wait 5 minutes.
4. Settings → Medications → Reminder mode → **Global = INTERVAL,
   4 hours**.
5. Wait until original CLOCK time (T+30min from step 2).
6. Expected: NO notification at T+30min (CLOCK alarm correctly
   cancelled per PR #980's settings save also re-arming clock side).
7. **Tap "Took dose"** on the medication.
8. Expected: INTERVAL chain anchors at T+0 (now); next firing at
   T+4h.
9. Switch back to CLOCK mode. Expected: INTERVAL alarms cancelled,
   CLOCK alarms re-armed.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Stale CLOCK alarm fires after mode switch to INTERVAL | **P0** — PR #980 regression, file |
| INTERVAL chain doesn't anchor on dose log | P1 — file |
| Both modes fire concurrently | **P0** — PR #986 reminderMode-aware guard regression, file |

## Scenario 9 — NLP batch ops smoke (operator's reframe; D5#1 Tests 1.3 + 1.4-1.6)

**What this verifies.** Per D5#1 §1 in the bundle audit — Test 1.3
edge cases (empty / single / 50+ items) and Tests 1.4-1.6 regression
under PR #1049's terminal state machine.

### Steps

#### Test 1.3a — empty batch

1. AI Coach quick-add: "Mark today's tasks as complete" (when no
   incomplete tasks today).
2. Expected: BatchPreviewScreen shows empty state, NOT crash.
3. Tap Apply (or Close).
4. Expected: returns to prior screen cleanly.

#### Test 1.3b — single item

1. Have exactly 1 incomplete task today.
2. AI Coach quick-add: "Mark today's tasks as complete".
3. Expected: BatchPreviewScreen renders 1 mutation.
4. Tap Apply.
5. Expected: task completes; navigation pops; no re-fire (per
   PR #1049 `Applied` terminal state).

#### Test 1.3c — 50+ items

1. Have 50+ incomplete tasks (use seed data or temporary mass-create).
2. AI Coach quick-add: "Show me all my incomplete tasks for tomorrow"
   (or a batch op that hits 50+ items).
3. Expected: BatchPreviewScreen renders within 5 seconds; scrollable
   list; no UI freeze.

#### Tests 1.4-1.6 regression

1. Standard batch ops: "complete 3 tasks tagged #work".
2. Standard batch ops: "schedule all #urgent tasks for tomorrow".
3. Standard batch ops: "delete completed tasks from last week".

For each: verify mutation set matches user intent, Apply succeeds,
no double-fire on re-render.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Crash on empty batch | **P0** — file |
| Re-fire after Apply (50+ items) | **P0** — PR #1049 regression, file |
| Single-item batch hangs | P1 — file |
| Standard batch ops mutation set wrong | **P0** — file with intended vs actual |

## Scenario 10 — Cross-device convergence under burst-write

**What this verifies.** D8 absorption preserves convergence under
concurrent multi-device writes.

### Steps

1. Both AVD-1 and S25 signed into same account.
2. **Simultaneous burst** (within 3 seconds of each other):
   - AVD-1: complete 10 tasks via batch ops.
   - S25: complete 5 tasks via individual taps.
3. Wait 60 seconds for convergence.
4. **Verify both devices show the same set of completed tasks**.
5. **Verify no FK constraint violations** in either logcat.
6. **Verify task_completions count matches** between devices.

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Devices disagree on completed-set | **P0** — D8 sync regression, file |
| FK constraint violation on receive | **P0** — file with logcat |
| Convergence > 60 seconds | P1 — file |

## Scenario 11 — Overnight Doze (8+ hours)

**What this verifies.** Real Doze under real-radio sleep + thermal
scaling does not silently kill medication reminders or cross-device
sync.

### Steps

1. Schedule a CLOCK-mode medication reminder for tomorrow morning at
   8:00 AM (or whenever operator naturally wakes).
2. Schedule a habit reminder for tomorrow at 7:30 AM.
3. **Plug phone into charger**, screen-off, do not touch.
4. Wait overnight (~8 hours).
5. **In the morning, before unlocking the phone**, observe:
   - Did the medication notification fire at expected time?
   - Did the habit notification fire?
6. Unlock phone. Capture full overnight logcat:
   `adb logcat -d -t '08:00:00' > ~/test_runs/D5_5_overnight_{date}.log`
7. **Verify both notifications fired within 60 seconds of expected**.
8. **Verify no cross-device sync regression** (e.g. listener didn't
   silently disconnect).

### Failure-mode triage

| Symptom | Verdict |
|---|---|
| Notification fires only on next user wake (not at scheduled time) | **P0** — Doze regression, file with logcat |
| One notification fires, other doesn't | P1 — single-channel regression |
| Cross-device listener disconnected overnight | P1 — file with PrismSync logcat |
| Notification fires within 5 min of expected | acceptable per Doze maintenance window |

## Outcome matrix

| # | Scenario | Outcome | Notes |
|---|----------|---------|-------|
| 1 | AI gate parity | GREEN/YELLOW/RED |  |
| 2 | Cross-device sign-in convergence | GREEN/YELLOW/RED |  |
| 3 | Reminder fires from cold (CLOCK) | covered in D5#2 Test 1 |  |
| 4 | Widget cold-start render | covered in D5#4 Cell C |  |
| 5 | OneUI Deep Sleep | GREEN/YELLOW/RED |  |
| 6 | Samsung Battery Optimization | covered in D5#2 Test 4 |  |
| 7 | Permission revoke + re-grant | GREEN/YELLOW/RED |  |
| 8 | Mode swap mid-cycle | GREEN/YELLOW/RED |  |
| 9 | NLP batch ops smoke | GREEN/YELLOW/RED |  |
| 10 | Cross-device burst convergence | GREEN/YELLOW/RED |  |
| 11 | Overnight Doze | GREEN/YELLOW/RED |  |

## Closure criteria

D5#5 → 1.0 when:
- Scenarios 1, 2, 5, 7, 8, 9, 10, 11 executed canonically here.
- Scenarios 3, 4, 6 executed via companion runbooks (D5#2, D5#4).
- All P0 failures filed and addressed.
- All P1 failures triaged: fix-and-redistribute OR known-issues-at-kickoff.
- Logcat captures saved to `~/test_runs/D5_5_*_{date}.log`.
- Outcome matrix appended to this file under "Phase 4 Outcomes" section.

D5#1 → 1.0 in same session via Scenario 9 (Tests 1.3 + 1.4-1.6).

## Append-to-audit template

After session, append to
`docs/audits/D5_D6_FINISH_BUNDLE_AUDIT.md`:

```markdown
## Phase 5 — D5#5 + D5#1 Real-Device Verification (S25 Ultra, {date})

11 scenarios executed (3 via companion runbooks). See outcome matrix
in `docs/runbooks/D5_5_PHASE_F_S25_SCENARIOS_RUNBOOK.md`.

| Outcome | Count |
|---------|------:|
| GREEN | xx |
| YELLOW (P2/P3) | xx |
| RED (P0/P1) | xx |

Verdict: GO / GO-WITH-KNOWN-ISSUES / CONDITIONAL / NO-GO.

**D5#5 closure:** 0.5 → 1.0.
**D5#1 closure:** 0.95 → 1.0.
```
