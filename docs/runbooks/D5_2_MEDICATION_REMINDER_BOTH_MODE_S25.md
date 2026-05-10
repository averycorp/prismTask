# D5#2 — Medication Reminder Both-Mode S25 Runbook

**Audit:** `docs/audits/MEDICATION_REMINDERS_BOTH_MODES_AUDIT.md` + this
bundle's audit at `docs/audits/D5_D6_FINISH_BUNDLE_AUDIT.md` §2.
**Phase F gate:** YES — health data; silent reminder failure = missed
dose.
**Owner:** Avery (operator), on Samsung Galaxy S25 Ultra.
**Wall-clock:** ~6 hours active + 24-48h elapsed for INTERVAL chain.
**Closes:** D5#2 + D5#3 arms 2 (real-device E2E) + 4 (reminder-mode).
**Companion runbooks:** `D5_5_PHASE_F_S25_SCENARIOS_RUNBOOK.md`
(Scenario 11 overnight Doze).

---

## Why this runbook exists

CC verification on AVD does not reproduce the failure surface that
matters for medication reminders:

- **Samsung OneUI aggressive process-killing** ("Put Unused Apps to
  Sleep" / "Deep Sleeping Apps") kills background processes on a
  proprietary schedule independent of AOSP Doze. AOSP emulators
  cannot emit this signal.
- **Samsung battery-optimization onboarding dialog** deep-links to a
  Samsung-specific Settings activity that does not exist on AOSP
  emulator's Settings app.
- **Real Doze under real-radio sleep + thermal scaling** — `adb
  shell dumpsys deviceidle force-idle` on emulator tests the Doze
  API; only a real device tests the production Doze chain.
- **Cross-device sync between AVD and S25** — sync correctness under
  burst-write and stale-listener cases needs the production Firestore
  + the live S25 listener loop, not the emulator pair.

The 6 Phase 2 fix PRs (#977 / #979 / #980 / #986 / #991 / #1055) closed
the code-side surfaces; this runbook closes the runtime-on-S25 surface.

## Pre-conditions

- **Device.** Samsung Galaxy S25 Ultra. Production keystore-signed
  build (NOT a debug variant — release build behavior is the gate).
- **Build.** Latest `v1.7.X` release artifact at the head of `main`.
  Pull from Firebase App Distribution as a production user would.
- **Account.** Dedicated test Google account (NOT operator's primary —
  zero risk to real medication data).
- **Companion device.** AVD `emulator-5554` or `emulator-5556`,
  signed into the **same** test account, debug build OK on this side.
- **Logcat capture.** `adb logcat -s MedReminderReceiver:V
  MedicationClockRescheduler:V MedicationIntervalRescheduler:V
  PrismSync:I` open in a separate terminal.
- **Battery charge.** S25 charged to 80%+ at start of session.
- **Notifications enabled.** OS-level notifications + per-channel
  "Medication" channel enabled. Disable any third-party notification
  blocker (some Samsung battery savers do this silently).
- **Time set correctly.** S25 system clock and timezone correct. Do
  NOT manually skew clock for these tests.

## Test 1 — CLOCK mode end-to-end

**What this verifies.** Per-(med, slot) CLOCK alarm path from PR
#1055 + the slot-INTERVAL receiver branch from #979.

### Steps

1. On S25, fresh-install or sign-in to the test account.
2. Settings → Medications → Reminder mode → set **Global mode = CLOCK**.
3. Create a medication "TestMedClock" with a custom slot at
   `Now + 5 min` (e.g. if the time is 2:00 PM, set the slot ideal
   time to 2:05 PM). Set dose schedule to "1 dose at slot ideal time."
4. Lock the screen. Place phone face-down so the proximity sensor
   triggers (Samsung's "lift to wake" can interfere with Doze).
5. Wait 6 minutes. **Do not touch the phone.**
6. Expected: notification fires within 60 seconds of the slot ideal
   time. Notification title: "TestMedClock - {slot name}".
7. Tap the "Take" action on the notification.
8. Capture logcat from time of step 4 to step 7 — save to
   `~/test_runs/D5_2_CLOCK_e2e_{date}.log`.

### Expected logcat trace

```
MedicationClockRescheduler: scheduled CLOCK alarm slotKey={slot.id} fireAt={timestamp}
ExactAlarmHelper: scheduleExact requestCode=700_xxx triggerAt={timestamp}
... (5 min later) ...
MedReminderReceiver: classifyAlarm → SLOT_CLOCK
MedReminderReceiver: showing notification for slot {slot.id} med {medId}
NotificationHelper: showSlotClockReminder posted
```

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| No notification, logcat shows `Alarm fired with neither` | PR #979 dispatch regression | **P0** — capture and file |
| No notification, logcat shows `MedicationClockRescheduler: skipping (no slot for med)` | Slot-medication linkage broken | P1 — file |
| Notification fires but at wrong time (>1 min skew) | Doze caused exact alarm to slip; check `deviceidle` state | P2 — file with Doze evidence |
| Notification fires twice | Double-fire — PR #986 reminderMode-aware guard regression | **P0** — capture and file |
| Crash on alarm fire | New surface, capture stacktrace | **P0** — file |

## Test 2 — INTERVAL mode end-to-end

**What this verifies.** Slot INTERVAL receiver dispatch path from PR
#979 + dose-change-driven reschedule from `MedicationIntervalRescheduler`.

### Steps

1. Settings → Medications → Reminder mode → set **Global mode =
   INTERVAL** with **Interval = 4 hours**.
2. Create a medication "TestMedInterval" with a custom slot. Set the
   slot ideal time to `Now + 4 hours`.
3. Tap "Took dose" for the medication NOW (this anchors the interval
   chain).
4. Capture logcat: `adb logcat -s MedicationIntervalRescheduler:V
   MedReminderReceiver:V > ~/test_runs/D5_2_INTERVAL_anchor_{date}.log`.
5. Expected: logcat shows
   `MedicationIntervalRescheduler: registerAlarmForSlot {slot.id}
   fireAt={anchor + 4h}`.
6. **Wait 4 hours.** Phone can be used normally during this period;
   do not background-restrict the app.
7. At T+4h: notification fires within 60 seconds.
8. Tap "Took dose" on the notification.
9. Expected: logcat shows next alarm registered at `T+8h`.
10. Continue tapping "Took dose" at each firing for the full 24-hour
    window. Capture each firing time.
11. Save the full 24h logcat to
    `~/test_runs/D5_2_INTERVAL_24h_{date}.log`.

### Expected outcome

- 6 firings over 24h (T+4h, T+8h, T+12h, T+16h, T+20h, T+24h)
  each within 60 seconds of expected.
- Each firing logs `classifyAlarm → SLOT_INTERVAL` then
  `NotificationHelper: showSlotIntervalReminder posted`.
- No firings missed; no firings duplicated.

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| First firing missed, subsequent fine | Anchor not set (PR #1055 regression) or Samsung killed app between dose log and reschedule | P1 — capture |
| All firings missed | Receiver dispatch regression OR Samsung killed app permanently | **P0** — file |
| Firings drift > 5 min from expected | Doze pushed the alarm; check Samsung "Adaptive battery" off | P2 — note in known issues |
| Firings double-fire | Both `MedicationIntervalRescheduler` AND legacy `MedicationReminderScheduler` registered (PR #986 guard regression) | **P0** — file |
| Firing fires at exact moment but no notification | Notification channel disabled OR `showSlotIntervalReminder` regression | P1 — file |

## Test 3 — Doze test

**What this verifies.** Real-device Doze does not silently kill the
alarm chain.

### Steps

1. With "TestMedClock" from Test 1 still installed, schedule a
   reminder for `Now + 30 min`.
2. Lock screen. Plug into charger to simulate the "phone on charger
   overnight" pattern.
3. After phone has been screen-off for 5 min, force-idle:
   ```bash
   adb shell dumpsys deviceidle force-idle
   ```
4. Verify: `adb shell dumpsys deviceidle | grep mState` shows
   `mState=IDLE`.
5. Wait the remaining ~25 min. Do NOT wake the phone.
6. Expected: notification fires at the scheduled time; Samsung wakes
   from idle to deliver via `setExactAndAllowWhileIdle`.
7. Capture logcat: should show `IDLE → IDLE_MAINTENANCE → IDLE`
   transitions and the notification posted during a maintenance
   window.

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| No notification | `setExactAndAllowWhileIdle` not used OR Samsung "Adaptive battery" suppressed | **P0** — file |
| Notification delayed > 15 min | Maintenance window timing — acceptable per AOSP Doze spec | document as known issue |
| Notification fires immediately on next user wake | Alarm did NOT fire under Doze; Samsung delivered post-wake | P1 — file |

## Test 4 — Battery optimization test

**What this verifies.** Samsung's per-app battery optimization (NOT
just AOSP Doze) does not break reminders.

### Steps

1. Settings → Apps → PrismTask → Battery → set **Optimize battery
   usage** = ON (this is the aggressive Samsung setting).
2. Force-stop PrismTask.
3. Wait 10 minutes. **Do not open the app or notifications.**
4. Open PrismTask. Schedule a reminder for `Now + 5 min`.
5. **Do not interact with the app for the next 6 minutes.**
6. Expected: notification fires within 60 seconds of expected.

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| No notification | Samsung killed the app + alarm registration | **P0** — file with explicit "Optimize battery usage was ON" annotation |
| Notification fires immediately on next app open | Samsung delivered alarm post-wake; alarm did NOT fire while killed | P1 — file |

## Test 5 — Cross-device test

**What this verifies.** Reminder configuration sync between AVD and
S25 + reminder firing on whichever device the user has at hand.

### Steps

1. AVD (`emulator-5554`): sign in to test account. Wait for sync to
   complete (`adb logcat -s PrismSync:I` shows
   `SyncService.fullSync end`).
2. AVD: create a medication "TestMedCross" with slot at `Now + 10 min`,
   global mode CLOCK.
3. S25: sign in to **same** test account. Wait for sync to complete.
4. S25: open Medications screen. **Verify "TestMedCross" appears with
   the configured slot and ideal time within 60 seconds of step 2.**
5. **Wait 10 minutes**, both devices on.
6. Expected: notification fires on **both** AVD and S25 within 60
   seconds of the slot ideal time.
7. **Tap "Took dose" on S25 only.** Wait 30 seconds.
8. Expected: AVD's notification dismisses (sync of dose state) AND
   AVD's "next firing" reschedule reflects the dose.

### Expected behavior

Both devices fire — production behavior is "every signed-in device on
the same account fires its own local notification independently." Do
not expect AVD to suppress when S25 fires; they are independent
chains.

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| AVD never sees "TestMedCross" | Cross-device sync broken; check D8 closure regression | **P0** — file with PrismSync logcat |
| Notification fires only on one device | Local alarm registration broken on the silent device | P1 — file |
| Tap "Took dose" on S25 doesn't sync to AVD | Dose mutation sync broken | P1 — file |
| Both notifications fire at very different times | Clock skew between devices | P2 — note known issue |

## Test 6 — Slot system test

**What this verifies.** Per-slot independent firing per PR #1055's
per-(med, slot) CLOCK alarm path.

### Steps

1. Configure 3 slots: "Morning" at 8 AM, "Afternoon" at 2 PM,
   "Evening" at 8 PM.
2. Link "TestMedSlots" medication to all 3 slots.
3. **Wait through one full day** (or skew test by setting all 3 slot
   ideal times to `Now + 5 min`, `Now + 10 min`, `Now + 15 min`).
4. Expected: 3 independent notifications fire at each slot time.
5. Tapping "Took dose" on the Morning notification does NOT dismiss
   Afternoon or Evening notifications.

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| Only first slot fires | Per-(med, slot) alarm path regressed (PR #1055) | **P0** — file |
| All 3 fire but at the same time | Slot-time mapping broken | **P0** — file |
| Tapping one dismisses all 3 | Notification grouping bug | P1 — file |

## Test 7 — Re-trigger test

**What this verifies.** Rescheduling a reminder for `Now + 5 min`
does not conflict with a recently-fired reminder.

### Steps

1. Use "TestMedClock" with a slot at `Now + 5 min`.
2. Wait for notification to fire.
3. Tap "Took dose."
4. Immediately reschedule the medication's slot to `Now + 5 min`
   (this simulates re-anchor).
5. Wait 6 minutes.
6. Expected: notification fires at the new time without conflict
   with the prior alarm registration.

### Failure-mode triage

| Symptom | Likely cause | Verdict |
|---|---|---|
| No second notification | New alarm registration cancelled by stale alarm | P1 — file |
| Two notifications fire (one at original time, one at new) | Stale alarm not cancelled before reschedule | P1 — file |

## Closure criteria

D5#2 → 1.0 when:
- All 7 tests run on S25.
- All P0 failures filed and addressed (or NO-GO for Phase F kickoff
  per `docs/release/PRE_PHASE_F_REGRESSION_SWEEP.md` rubric).
- All P1 failures triaged: fix-and-redistribute OR documented as
  known-issues-at-kickoff.
- Logcat captures saved to `~/test_runs/` for audit trail.
- Append outcome to `docs/audits/MEDICATION_REMINDERS_BOTH_MODES_AUDIT.md`
  under Phase 5 - Real-Device Verification.

D5#3 arms 2 + 4 → 1.0 in the same session (Tests 1, 2, 5, 6 cover
arm 2 real-device E2E + arm 4 reminder-mode verification).

## Append-to-audit template

After session, append to
`docs/audits/MEDICATION_REMINDERS_BOTH_MODES_AUDIT.md`:

```markdown
## Phase 5 — Real-Device Verification (S25 Ultra, {date})

| Test | Outcome | Notes / logcat |
|------|---------|----------------|
| 1 — CLOCK E2E | GREEN/YELLOW/RED | {one-liner + path to logcat} |
| 2 — INTERVAL E2E (24h) | GREEN/YELLOW/RED | {one-liner + path to logcat} |
| 3 — Doze | GREEN/YELLOW/RED | {one-liner + Doze evidence} |
| 4 — Battery optimization | GREEN/YELLOW/RED | {one-liner} |
| 5 — Cross-device | GREEN/YELLOW/RED | {one-liner} |
| 6 — Slot system | GREEN/YELLOW/RED | {one-liner} |
| 7 — Re-trigger | GREEN/YELLOW/RED | {one-liner} |

Verdict: GO / GO-WITH-KNOWN-ISSUES / CONDITIONAL / NO-GO.

**D5#2 closure:** 0.9 → 1.0.
**D5#3 arm 2 closure:** 0.9 → 1.0.
**D5#3 arm 4 closure:** 0.9 → 1.0.
```
