# Phase A Device Testing Runbook — Hybrid (Two Emulators + S25)

> **This runbook is pinned to v1.4.38.** It is preserved as the executed
> runbook for that release. Subsequent releases (v1.4.40 AI time blocking,
> v1.5.0 medication slot system, v1.5.2 web parity, v1.6.0 medication
> reminder modes) ship their own coverage paths in androidTest +
> SyncScenarioTestBase + the manual `docs/SYNC_TESTS_12_13_MANUAL.md`. A
> v1.6.0+ device runbook would be a separate document; do not mutate
> this one to cover newer builds.

**Scope:** Phase A regression coverage for PrismTask **v1.4.38**
(versionCode `682`, Room DB version `56`) plus a smoke pass of the
nine new synced content entities shipped in v1.4.38 and the seven
config entities shipped in v1.4.37. Sibling to — not replacement for —
`PHASE_A_DEVICE_TESTING_RUNBOOK.md` (previous phase, pinned to
v1.4.27).

**Execution window:** Thu 2026-04-23 → Sun 2026-04-26.

**Approach:** Most scenarios run on **two stock Android emulators**
(reproducible, scriptable, snapshottable). A minority run on a
**Samsung Galaxy S25 Ultra** because their pass/fail criteria depend on
OneUI behavior that no emulator reproduces.

---

## Reference documents

- `docs/REMINDERS_TEST_RUNBOOK.md` — the canonical 11-scenario body
  (preconditions, steps, expected, per-device checklist). **Pruned
  from `main` on 2026-04-22 in commit `7e19a3f8` along with the old
  Phase A runbook.** This hybrid doc carries a one-paragraph synopsis
  per scenario so it is self-contained; for the full step list,
  restore the canonical from git:
  ```bash
  git show 7e19a3f8^:docs/REMINDERS_TEST_RUNBOOK.md > /tmp/reminders_runbook.md
  ```
  If the canonical is re-restored to the tree as part of Phase A setup,
  update the cross-references below to point at the file path.
- `docs/PHASE_A_TWO_DEVICE_SIGNIN_VERIFICATION.md` — same prune. Two-
  device sign-in scenarios S1–S5 (row-count invariants, Firestore
  doc-count invariants, expected `PrismSync` sequence). Restore the
  same way if needed:
  ```bash
  git show 7e19a3f8^:docs/PHASE_A_TWO_DEVICE_SIGNIN_VERIFICATION.md \
    > /tmp/two_device_signin.md
  ```

---

## Why hybrid

### Why not pure emulator

Stock Android emulator images ship with stock AOSP. They do **not**
reproduce:

- **OneUI aggressive process-killing.** Samsung's "Put Unused Apps to
  Sleep" / "Deep Sleeping Apps" subsystem kills background processes
  (including foreground service alarms) on a proprietary schedule
  independent of AOSP Doze. AOSP emulators cannot emit this signal.
- **Samsung battery-optimization dialog.** The one-time onboarding
  dialog that deep-links to Samsung's battery-exemption screen is an
  OEM fork of AOSP Settings. The emulator's Settings app doesn't even
  have the target activity.
- **Samsung notification permission manager UI.** Samsung routes
  POST_NOTIFICATIONS revocation through a different Settings page than
  stock; the revocation banner re-appears through a Samsung-specific
  code path on S25.
- **Real Wi-Fi sleep + modem sleep + CPU thermal scaling over 8 hours.**
  `adb shell dumpsys deviceidle force-idle` on an emulator tests the
  Doze **API** (whether `AlarmManager.setExactAndAllowWhileIdle` keeps
  a handle through `IDLE` buckets). It does not exercise the physical
  state where the device has been off-charger, screen-off,
  motion-detector-idle, radios-asleep for 8 hours and the OS has made
  its own escalating power-saver decisions along the way. Scenario 11
  is the one that will actually catch "the notification never fired
  overnight" bugs; it cannot be emulated.

### Why not a Samsung emulator

- **No official image.** Samsung has not published a OneUI system
  image for Android Emulator / AVD Manager. This has been their
  position for the entire Android Emulator lifetime; there is no
  indication it will change.
- **Samsung Remote Test Lab (RTL) is session-capped.** The free tier
  caps sessions at 30 minutes with no reconnect window; the paid tier
  extends this modestly but still terminates overnight. Scenarios 6
  and 11 need sustained background time well past any RTL cap. RTL is
  useful for quick UI spot-checks, not Phase A coverage.
- **Unofficial OneUI AVD images are rejected.** Community repos ship
  ripped OneUI images for AVDs. They are (a) licensing-gray so they
  cannot be pinned in CI or shared across contributors, (b) unstable
  across AVD updates, and (c) opaque supply-chain — they are system
  images with root, pulled from unverified uploads. Not suitable for
  any use case that touches Google Sign-In or Firestore from the test
  device.

### Why not pure physical device (the original Phase A plan)

Two physical devices doubles hardware cost, cannot be snapshotted
between scenarios (requires a full uninstall/reinstall + re-sign-in to
reset state — ~10 minutes each time on S25), cannot be cloned to run
variant orderings in parallel, and cannot be scripted past the OAuth
Custom Tab. Emulator snapshots compress a full device reset to
seconds.

### Net: the split

| Device          | Canonical scenarios                     | Rationale                                       |
| --------------- | --------------------------------------- | ----------------------------------------------- |
| Two emulators   | 1, 2, 3, 4, 5, 7, 8, 9, 10 + signin S1–S5 | Stock Android is sufficient; gain reproducibility |
| S25 Ultra only  | 6, 11                                   | Samsung-specific behavior / real 8h Doze        |

The S25 may additionally be used as a **spot-check** for any emulator
scenario that regresses — it is not excluded, just not the primary bed.

---

## Current-state header (fill in at session start)

```
Branch:                <name> @ <sha>
Build:                 v1.4.38 (682)
Room DB:               56
Emu-A identifier:      emulator-5554   (Pixel 8, API 34, Google Play)
Emu-B identifier:      emulator-5556   (Pixel 8, API 34, Google Play)
S25 identifier:        adb-R5CXC2CR4MT-Lm6SCP._adb-tls-connect._tcp
Test account:          averykarlin3@gmail.com
Firestore audit tool:  audit/firestore_audit.py (if present)
Canonical reminders:   docs/REMINDERS_TEST_RUNBOOK.md (restore from 7e19a3f8^ if missing)
Canonical signin:      docs/PHASE_A_TWO_DEVICE_SIGNIN_VERIFICATION.md (same)
```

### Pre-flight — run on all three devices

Export toolchain paths (installed but not on default PATH) before any
Gradle/adb work — see CLAUDE.md and `memory/reference_dev_tooling_paths.md`:

```bash
export ANDROID_HOME="/c/Users/avery_yy1vm3l/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/c/Program Files/GitHub CLI:$PATH"
```

1. **Confirm installed build.** Anything older than v1.4.38 invalidates
   coverage of the 16 new synced entities:
   ```bash
   adb -s emulator-5554 shell \
     "dumpsys package com.averycorp.prismtask | grep -E 'versionName|versionCode' | head -5"
   adb -s emulator-5556 shell \
     "dumpsys package com.averycorp.prismtask | grep -E 'versionName|versionCode' | head -5"
   adb -s adb-R5CXC2CR4MT-Lm6SCP._adb-tls-connect._tcp shell \
     "dumpsys package com.averycorp.prismtask | grep -E 'versionName|versionCode' | head -5"
   ```
   Expected on each: `versionName=1.4.38`, `versionCode=682`.

2. **Device clock ±1 min wall clock.** Confirm via `adb shell date`.
   Scenarios 1, 2, 11 depend on this.

3. **POST_NOTIFICATIONS granted** (re-granted after fresh install),
   except on Emu-A for Scenario 5, which explicitly denies.

4. **Battery-optimization exemption** granted on all three. On S25
   verify via:
   ```bash
   adb -s adb-R5CXC2CR4MT-Lm6SCP._adb-tls-connect._tcp shell \
     dumpsys deviceidle whitelist | grep prismtask
   ```

5. **Capture baseline logcat** immediately before the first scenario
   each day so every scenario's log window starts clean:
   ```bash
   adb -s <device> logcat -c
   ```

---

## Emulator setup (one-time, ~15 min)

### Create Emu-A and Emu-B as identical Pixel 8 API 34 (Google Play) images

```bash
# Pixel 8 hardware profile, API 34, Google Play services (not AOSP —
# Sign-In and Firestore require Play Services)
sdkmanager "system-images;android-34;google_apis_playstore;x86_64"
avdmanager create avd -n phasea-emu-a \
  -k "system-images;android-34;google_apis_playstore;x86_64" \
  -d pixel_8
avdmanager create avd -n phasea-emu-b \
  -k "system-images;android-34;google_apis_playstore;x86_64" \
  -d pixel_8
```

Boot both in parallel on distinct ports:

```bash
emulator -avd phasea-emu-a -port 5554 -no-boot-anim &
emulator -avd phasea-emu-b -port 5556 -no-boot-anim &
```

### Snapshot for fast iteration

Between scenarios, restore from a known-good snapshot instead of
uninstalling + reinstalling + re-signing in:

```bash
# Save clean snapshot (after fresh install + sign-in + permissions granted):
adb -s emulator-5554 emu avd snapshot save clean-signed-in
adb -s emulator-5556 emu avd snapshot save clean-signed-in

# Restore before a scenario that mutates state:
adb -s emulator-5554 emu avd snapshot load clean-signed-in
```

On S25 there is no equivalent — factory-reset of app state is
`uninstall` + `install` + re-sign-in. Plan scenarios 5, 6, 8 to
run sequentially on S25 rather than in parallel to minimize resets.

### Clock skew between emulators

Two emulators with the same `emu avd` origin share the host wall clock
by default. To introduce clock skew (useful if a scenario needs one
device ahead of the other — e.g. verifying last-write-wins on
`updated_at`):

```bash
# Pause clock sync, advance Emu-B by N minutes via Settings → Date & Time
adb -s emulator-5556 shell "settings put global auto_time 0"
adb -s emulator-5556 shell "date -s $(date -d '+5 minutes' +%m%d%H%M%Y.%S)"
```

This is **not** used for the reminder scenarios (they rely on accurate
wall clock) but is relevant to two-device sign-in S3 (concurrent
sign-in) if you want to test Firestore timestamp resolution under
intentional skew.

### Emulator Doze simulation — what it does and does not prove

```bash
adb -s emulator-5554 shell dumpsys deviceidle force-idle
# ... exercise scheduled alarm ...
adb -s emulator-5554 shell dumpsys deviceidle unforce
```

This forces Doze IDLE bucket and is useful for **Scenarios 4, 9, 10**
on emulator to confirm `setExactAndAllowWhileIdle` survives the Doze
API. It is **not** a substitute for Scenario 11. What it does not test:

- Real modem / Wi-Fi sleep (emulator networking is host-routed and
  never sleeps)
- CPU thermal + power scaling over hours (emulator CPU is the host CPU;
  no battery state machine)
- OEM power-saver interactions on top of Doze (Samsung adds its own
  layer above Doze; stock Android does not)

Scenario 11 remains S25-only for these reasons.

---

## Scenario-by-scenario breakdown

Scenario numbering matches `REMINDERS_TEST_RUNBOOK.md` (canonical).
Each section lists: device assignment, a one-paragraph synopsis, any
Phase-A additions beyond the canonical body, and the pass criterion.

### Scenario 1 — Task reminder happy path

- **Run on:** Emu-A **and** Emu-B (independent confirmations on two
  identical emulators; any split result indicates flake vs. real bug)
- **Synopsis:** Create task due in +3 min with reminder "at due time".
  Lock screen. Heads-up notification fires within ±10s. Tap opens
  Today. Complete action marks task complete.
- **Phase A addition:** After the notification fires, capture
  `adb shell dumpsys alarm | grep prismtask` on each emulator and
  confirm the alarm is **removed** post-fire (not just marked fired —
  a lingering ghost alarm indicates a cleanup regression).
- **Pass:** fires ±10s on both Emu-A and Emu-B; alarm cleared post-fire
  on both.

### Scenario 2 — Task reminder with lead-time offset

- **Run on:** Emu-A **and** Emu-B
- **Synopsis:** Create task due in +20 min with reminder offset = 15
  min. Fires at +5 min (20 − 15). Lock screen before fire.
- **Phase A addition:** none.
- **Pass:** fires ±30s of T+5min on both emulators.

### Scenario 3 — Cancel-on-complete (single, bulk, bulk-UNDO, subtask)

- **Run on:** Emu-A (primary)
- **Synopsis:** Covers four permutations: single task completed before
  fire → no fire; bulk-complete → no fire for any; bulk-complete +
  UNDO → alarms rearmed and all fire; subtask completion → subtask
  alarm dropped, parent untouched.
- **Phase A addition:** for the bulk-UNDO sub-scenario, after rearming,
  put PrismTask in the recents list for 30 seconds then relock. The
  rearmed alarms must still fire. This catches a regression where the
  rearm path holds a transient `PendingIntent` that the system drops
  on background swap.
- **Pass:** all four permutations behave as expected; bulk-UNDO alarms
  survive the 30-second background swap.

### Scenario 4 — Recurring task reminder rolls over to next occurrence

- **Run on:** Emu-A
- **Synopsis:** Daily-recurring task with reminder fires today,
  complete via notification action, new next-day task is inserted with
  a newly-registered alarm.
- **Phase A addition:** verify the newly-inserted next-day task's
  `cloud_id` is populated post-sync (not null). This exercises the
  MIGRATION_51_52 cloud-id backfill path through the recurrence
  rollover code:
  ```bash
  adb -s emulator-5554 shell \
    "run-as com.averycorp.prismtask /system/bin/sqlite3 databases/averytask.db \
      'SELECT id,title,cloud_id,due_date FROM tasks ORDER BY id DESC LIMIT 5'"
  ```
  (The canonical runbook used `prismtask.db`; the actual database
  filename is `averytask.db` — see `DatabaseModule.kt:64`.)
- **Pass:** next-day task exists, alarm registered, `cloud_id`
  non-null on all rows.
- **Emulator confidence note:** this scenario is a candidate for an
  S25 spot-check, because recurrence rollover on wake-up is a place
  where Samsung's stricter background execution policy can swallow the
  insert silently. If Scenario 4 passes on emulator, run a single
  S25 spot-check before closing the scenario.

### Scenario 5 — POST_NOTIFICATIONS denial explainer

- **Run on:** Emu-A
- **Synopsis:** Fresh install → deny POST_NOTIFICATIONS at onboarding →
  Settings → Notifications shows red "Notifications Blocked" banner →
  "Allow" re-prompts; "Open Settings" deep-links to App Info. Granting
  in system Settings and returning auto-clears the banner on
  `ON_RESUME`.
- **Phase A addition:** none (the Samsung-variant permission-manager
  UI is deferred to S25 spot-check if needed — it is not a distinct
  canonical scenario).
- **Pass:** banner shows, both buttons work, banner clears
  automatically on resume.

### Scenario 6 — Samsung battery-optimization guidance

- **Run on:** **S25 Ultra only**
- **Synopsis:** Fresh install on Samsung → onboarding dialog appears
  with two paragraphs (exemption pitch + OneUI sleep-list guidance).
  "Open Settings" deep-links to the battery-exemption screen.
  Revoking the exemption re-surfaces a persistent banner in
  Settings → Notifications with Samsung-specific copy and a "Battery
  Settings" button.
- **Why S25-only:** the two paragraphs, the deep-link target, and the
  post-revocation banner copy are all gated on
  `Build.MANUFACTURER == "samsung"` and only meaningful against OneUI's
  battery UX. No emulator reproduces this.
- **Phase A addition:** also exercise OneUI's sleep-list path — after
  granting the exemption, open Samsung Device Care → Battery →
  Background usage limits → confirm PrismTask is not in
  "Deep Sleeping Apps". Toggle it **into** Deep Sleeping, fire a
  reminder via Scenario 1, and record whether it fires (expected: it
  still fires because the exemption bypasses sleep-list; failure here
  would be a Pass-2 finding rather than a blocker since the user was
  warned).
- **Pass:** all onboarding-dialog copy present; deep-link lands on the
  right system screen; banner re-appears on revoke with Samsung copy;
  sleep-list behavior documented.

### Scenario 7 — Summary workers end-to-end (5 workers)

- **Run on:** Emu-B (primary)
- **Synopsis:** Confirm Daily Briefing, Evening Summary, Weekly Habit
  Summary, Balance Alerts, and Re-engagement all enqueue, post under
  their own channels, and unregister/re-register cleanly on toggle.
  Force-runs via `am broadcast` or `cmd jobscheduler run`.
- **Phase A addition:** for `WeeklyHabitSummaryWorker` specifically,
  confirm the post-rename cleanup migration ran at most once by
  checking the `weekly_habit_summary_migration_run` flag in
  `notifications.preferences_pb`. Toggling the "Weekly Habit Summary"
  switch OFF/ON should not re-trigger the migration (`adb logcat |
  grep WeeklyHabitSummaryMigration` — no new entries after first
  launch).
- **v1.4.38 check:** v1.4.38 added no new summary workers; the 9
  content entities sync in the foreground sync pipeline, not via these
  5 summary workers. Scenario 7 coverage is therefore unchanged by
  the v1.4.38 entity additions — the 5 workers are still the only 5.
- **Pass:** all 5 fire on force-run; unique work is removed on toggle-
  OFF; `ExistingPeriodicWorkPolicy.UPDATE` on toggle-ON.

### Scenario 8 — Permission-aware worker behavior

- **Run on:** Emu-A
- **Synopsis:** With POST_NOTIFICATIONS denied, force-run each
  notification-posting worker and confirm it returns
  `Result.success()` (not `failure` / `retry`) without crashing.
- **Phase A addition:** include v1.4.38's new `WeeklyTaskSummaryWorker`
  (landed in commit `f371a08a`) in the worker list. Expected
  `SecurityException`-safe behavior from
  `NotificationManager.notify()`. If the new worker crashes under
  denied permission it is a blocker.
- **Pass:** each worker — Briefing, Evening, Reengagement, Weekly
  Habit Summary, Weekly Task Summary, OverloadCheck — returns
  `Result.success()` with no crash.

### Scenario 9 — Habit reminder (interval mode)

- **Run on:** Emu-A **and** Emu-B
- **Synopsis:** Habit with `repeat after logging = ON`, interval 1
  min, 2 doses/day. First reminder fires ~1 min after save; logging
  triggers next reminder 1 min later; final dose does not re-arm.
  "Dose 1 of 2" / "Dose 2 of 2" in subtitle.
- **Phase A addition:** run the second dose after forcing Doze on
  Emu-A:
  ```bash
  adb -s emulator-5554 shell dumpsys deviceidle force-idle
  ```
  confirm second dose still fires (`setExactAndAllowWhileIdle` is
  supposed to pass through IDLE). Then `unforce` before moving on.
  This is a belt-and-suspenders check against a Doze-path regression;
  the **real** Doze coverage is Scenario 11.
- **Pass:** first fire + log re-arm + final-dose no-rearm on both
  emulators; second dose fires on forced-IDLE Emu-A.

### Scenario 10 — Habit reminder (daily time mode)

- **Run on:** Emu-A
- **Synopsis:** Habit with Daily Reminder = ON, time = now + 3 min,
  repeat-after-logging OFF. After fire, confirm:
  1. Tomorrow's alarm is registered (request code = `habitId + 900_000`)
  2. Toggling OFF cancels the alarm
  3. Toggling ON re-registers
  4. `adb reboot` → unlock → alarm is re-registered by `BootReceiver`
- **Phase A addition:** step 4 specifically verifies the defensive
  `EntryPointAccessors.fromApplication` guard landed in Phase A last
  time. After `adb reboot`, grep:
  ```bash
  adb -s emulator-5554 logcat -d | grep -E 'BootReceiver|IllegalStateException'
  ```
  Expected: either no `BootReceiver` output (normal path) or the
  benign warn `"Hilt EntryPoints unavailable on boot; skipping
  reschedule"`. **Any `IllegalStateException` crash log is a
  ship-blocker** (see `memory/project_bootreceiver_hilt_test_crash.md`).
- **Pass:** all 4 sub-steps green on Emu-A; no `IllegalStateException`
  in boot logs.

### Scenario 11 — Overnight Doze survival test

- **Run on:** **S25 Ultra only**
- **Synopsis:** At bedtime, create a task with reminder for 8 hours in
  the future. Lock device, off-charger, stationary, face-down. Read
  result in the morning. Expected: fires within **±5 min** of target.
- **Why S25-only:** see "Why not pure emulator" above. Real 8h modem /
  Wi-Fi / CPU sleep is the load-bearing test; emulator cannot do it.
- **Phase A addition:** run only after all of 1–10 pass. If any of
  1–10 is a blocker, skip 11 and re-run the full set after the fix
  lands.
- **Scheduling:** create no earlier than **Sat Apr 25 22:30 local**,
  target **Sun Apr 26 ~07:00–07:15**. Confirm exemption is still in
  Unrestricted before locking (Samsung occasionally re-optimizes
  apps silently). Capture the fire time plus
  `adb shell dumpsys deviceidle` output at read-time.
- **Pass:** fires ±5 min; not more than 5 min early (early = Doze not
  engaging); not more than 15 min late (late = Samsung deprioritizing
  despite exemption — likely Pass-2).

---

## Two-device sign-in scenarios (S1–S5)

**Source:** `PHASE_A_TWO_DEVICE_SIGNIN_VERIFICATION.md` (restore
from `7e19a3f8^` if missing).

**Run on:** Emu-A + Emu-B (replaces the Pixel-emu + S25 pairing from
the previous phase).

**Why emulator-pair and not Pixel + S25:** the formal OAuth sign-in
flow is **equally blocked on emulator and physical device** by Google
OAuth running in a Chrome Custom Tab that is not adb-scriptable. No
matter the device class, the human still taps the account-picker.
Emulator wins because (a) two identical Pixel 8 images remove
Pixel-vs-Samsung as a confounder, (b) snapshots make the
"sign out → sign back in" loop ~10× faster than on S25.

### Scenarios (abbreviated — see canonical for full step list)

- **S1** — sign out both; sign in Emu-A first, then Emu-B. Canonical
  row counts, empty Firestore diff, expected PrismSync sequence
  (`auth.signIn.success` → `restoreCloudIdFromMetadata` → either
  `initialUpload.skipped.alreadyRan` or a single
  `initialUpload.start → .complete` → per-collection pull →
  realtime listeners).
- **S2** — reverse order (Emu-B first, then Emu-A). Same pass
  criteria.
- **S3** — concurrent sign-in within 3 wall-clock seconds. Expected:
  on fresh install both emit `initialUpload.start`; Firestore
  de-duplicates via `cloud_id` presence → final doc counts still
  diff-zero.
- **S4** — sign out one while the other is signed in. Other device's
  Room counts must not change. Firestore diff empty.
- **S5** — fresh install sign-in on Emu-A (uninstall, reinstall,
  sign in). Emu-A hydrates from Firestore; `initialUpload.start`
  fires but uploads zero new docs because local Room is empty at
  upload time. **This is the scenario Fix A/B/C was designed to make
  safe.**

### The realistic pass pattern: opportunistic cold-start capture

The formal S1–S5 depend on a human to tap through Custom Tab and a
human to be on-call if Firestore counts drift mid-run. **The single
invariant that matters** — and the one you can actually measure
reliably with adb alone — is:

> On cold-start of a signed-in install, `initialUpload.pushed` must
> be **0**.

That is Fix A's one-shot guard. If it holds across every cold-start
of both emulators, re-duplication cannot happen. Capture pattern:

```bash
# On each cold-start (kill + relaunch app):
adb -s <device> logcat -c
adb -s <device> shell am force-stop com.averycorp.prismtask
adb -s <device> shell am start -n com.averycorp.prismtask/.MainActivity
sleep 10
adb -s <device> logcat -d -s PrismSync:V | \
  grep -E 'initialUpload\.(start|complete|skipped)'
```

Expected: `initialUpload.skipped.alreadyRan` on every cold-start after
the first. If `initialUpload.complete pushed=<N>` with `N != 0`
appears on any cold-start after the first, Fix A regressed → blocker.

Run this capture once at the end of each day across both emulators —
it is the cheap, scriptable proxy for the formal S1–S5 pass criteria.

---

## Execution schedule

Thu Apr 23 → Sun Apr 26. All times local.

| Day            | Morning / Afternoon                                                              | Evening                                                                 |
| -------------- | -------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| **Thu Apr 23** | Emulator setup (one-time). Pre-flight on Emu-A + Emu-B + S25. Scenarios **1, 2, 3, 4**. Sign-in **S1, S2**. | Snapshot clean state on both emulators. File any blockers before EoD. |
| **Fri Apr 24** | Scenarios **7, 9, 10** on emulator. Sign-in **S3, S4, S5**. Start S25-only: **5**, **6**, **8**. | S25 spot-check of Scenario 4 recurrence rollover (emulator confidence note). |
| **Sat Apr 25** | Triage blockers from Thu/Fri. Re-run any regressed scenarios after fix.          | **22:30 local — fire Scenario 11** on S25. Lock, face-down, off-charger. |
| **Sun Apr 26** | **07:00–07:15 local — read Scenario 11** on S25. Capture `dumpsys deviceidle` state. | Final triage, Phase A sign-off, file Pass-2 tickets.                   |

Canonical note: Scenario 5 (denial banner) on the old runbook was
labeled Thu; in the hybrid it moves to Fri because S25 is the primary
bed (Emu-A runs it too, but Fri is when the S25 is already in the
rotation for 6 and 8).

---

## Triage template

One row per failure.

| # | Device    | Scenario | Symptom | Logcat first frame | Category |
| - | --------- | -------- | ------- | ------------------ | -------- |
|   |           |          |         |                    |          |

**Categories** (default is BLOCKER; demote only with written
justification in the Symptom column):

- **BLOCKER** — ship-blocker for v1.4.39. Examples: notification
  silently drops with no logcat; crashes on fire; BootReceiver
  `IllegalStateException`; Firestore doc count increases on any
  sign-in; new-entity (v1.4.37 config or v1.4.38 content) uploads
  a null `cloud_id`; cross-device Room row count desync after sync
  converges.
- **PASS-2** — defer to next reminders pass. Examples: overnight fire
  delayed 10–15 min on S25 despite exemption (not a silent drop,
  just drift); Samsung sleep-list re-adds app after idle-for-N-days
  despite exemption; alarm precision drift on non-exact fallback
  path when app is force-stopped via OEM task-killer.
- **COSMETIC** — non-functional. Typo, banner spacing, icon glyph
  wrong, notification subtitle plural-form wrong ("1 doses").

---

## Known limitations — what the emulator bed cannot catch

Keep these in mind when interpreting a green emulator run:

1. **Samsung process killing beyond exemption.** Emulator cannot
   reproduce OneUI's "Background usage limits → Sleeping apps →
   Deep sleeping apps" subsystem. Covered by Scenario 6 (S25).
2. **Real battery curve.** Emulator battery is a slider; real thermal
   throttling, low-battery-saver kick-in, and
   `BATTERY_LOW`-triggered job suppression are not reproducible.
   Partial coverage from Scenario 11 (S25 overnight at real battery
   state).
3. **Real Wi-Fi / modem sleep.** Emulator networking is always
   host-routed. Radio sleep does not happen. Covered by Scenario 11.
4. **OAuth Custom Tab edge cases** (device-account-picker flows,
   account add/remove, enterprise SSO). Not scripted; blocked on
   emulator **and** physical device by Custom Tab scriptability. We
   use the cold-start capture pattern above as a proxy.
5. **Manufacturer-specific Notification UI.** Samsung routes the
   revocation + re-grant path through a different Settings surface;
   Scenario 5's Samsung variant lives on S25.
6. **Widget behavior at OEM-specific launchers.** Not part of Phase A
   but worth knowing — OneUI launcher and stock launcher render
   Glance widgets differently; if widget cells appear in regression
   reports, spot-check on S25.

---

## Integration with v1.4.37 / v1.4.38 new synced entities

**v1.4.37 added sync for 7 config entities** (commit `c9e082c6`):
`NotificationProfileEntity`, `ProjectTemplateEntity`,
`HabitTemplateEntity`, `NlpShortcutEntity`, `SavedFilterEntity`,
`BoundaryRuleEntity`, `CustomSoundEntity`.

**v1.4.38 added sync for 9 content entities** (commit `aacd33dc`):
`AttachmentEntity`, `CheckInLogEntity`,
`DailyEssentialSlotCompletionEntity`, `FocusReleaseLogEntity`,
`MedicationRefillEntity`, `MoodEnergyLogEntity`,
`StudyLogEntity`/`CourseCompletionEntity`/`AssignmentEntity` (the
"Schoolwork" bundle), `WeeklyReviewEntity`.

### Coverage requirements through Phase A scenarios

The reminder scenarios don't directly exercise these entities, but
some scenarios can — and should — be steered through at least one
new-entity code path:

- **Scenario 3 (cancel-on-complete):** when creating the "parent + subtask"
  pair, attach one of the new content-entity types to the parent
  (e.g. a `MoodEnergyLog` entry via the mood sheet, or a
  `FocusReleaseLog` via the focus-release UI). On bulk-complete + UNDO,
  confirm the attached content entity is not double-inserted on UNDO
  (Fix A one-shot on attached children would be the regression
  surface).
- **Scenario 7 (summary workers):** already confirmed unaffected — the
  5 summary workers read Room, not Firestore, and do not touch the
  new entity-sync path. **Explicit no-op here is the right outcome.**
- **Sign-in S5 (fresh install):** the strongest new-entity coverage.
  A fresh install on Emu-A must hydrate all 16 new entity tables
  from Firestore with correct `cloud_id` population. Spot-check
  afterward:
  ```bash
  adb -s emulator-5554 exec-out run-as com.averycorp.prismtask \
    cat databases/averytask.db > /tmp/em-postS5.db
  for t in notification_profiles project_templates habit_templates \
           nlp_shortcuts saved_filters boundary_rules custom_sounds \
           attachments check_in_logs daily_essential_slot_completions \
           focus_release_logs medication_refills mood_energy_logs \
           study_logs course_completions assignments weekly_reviews; do
    nulls=$(sqlite3 /tmp/em-postS5.db \
      "SELECT COUNT(*) FROM $t WHERE cloud_id IS NULL")
    total=$(sqlite3 /tmp/em-postS5.db "SELECT COUNT(*) FROM $t")
    echo "$t: $total rows, $nulls null cloud_id"
  done
  ```
  Expected: every row in every new-entity table has a non-null
  `cloud_id`. Any null is a blocker (Fix C's reject-on-null would
  then prevent that row from ever re-uploading).

### If the canonical scenario body does not mention the new entities

That is expected — canonical was pinned to v1.4.27, which predates all
16. The hybrid doc is the coverage surface for the new entities. Do
not edit canonical to add them; let the hybrid carry it.

---

## Integration with the medication refactor (scope boundary)

Migration 53→54 (commit on main) moved medications out of
`self_care_steps` into a new top-level `medications` + `medication_doses`
schema. **These Phase A scenarios do not exercise the medication
refactor's two-device sync migration path.** Specifically:

- The medication refactor's two-device migration integration lives in
  **Phase B**, not Phase A, per the roadmap. A Phase A pass here says
  nothing about whether a user who was on v1.4.27 (old schema) and
  upgrades to v1.4.38 on one device while another device is still at
  v1.4.27 converges correctly post-migration. That is Phase B's job.
- Scenario 11's medication-reminder path uses the new schema on both
  ends (both devices at v1.4.38, both migrated), so a Scenario 11 pass
  is evidence that the migration endpoints are functional, **not**
  that the migration transition is safe.

Flag this boundary explicitly in the Phase A sign-off summary so
nobody reads Phase A green as Phase B green. If a user hits a
medication-refactor regression, it will not show in Phase A reports;
point the investigation at Phase B coverage and the quarantine-table
row counts (see `PHASE_2_MEDICATION_CLEANUP_RUNBOOK.md`).

---

## Reporting template

At Phase A close:

```
Branch:       <name> @ <sha>
Build:        v1.4.38 (682)
DB:           v56
Emu-A:        phasea-emu-a (Pixel 8, API 34, Google Play)
Emu-B:        phasea-emu-b (Pixel 8, API 34, Google Play)
S25 Ultra:    Android <X>, OneUI <Y>, patch <YYYY-MM>
Scenarios:    <N>/11 passed
Signin:       <M>/5 passed
Blockers:     <list of # from triage table>
Pass-2s:      <list>
Cosmetics:    <list>
Overnight:    Started <ISO-8601>, fired <ISO-8601>, delta <±N min>
New-entity hydration check (post-S5):  <PASS | null-cloud-id rows found: ...>
Medication refactor boundary:         NOT TESTED (Phase B scope)
```

Attach full `adb bugreport` zips for every BLOCKER row.
