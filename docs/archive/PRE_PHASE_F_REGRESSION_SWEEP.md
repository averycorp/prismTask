# Pre-Phase F Release Build Regression Sweep Methodology

**Source:** `docs/audits/D2_AND_PHASE_F_PREP_MEGA_AUDIT.md` item 8.
**Owner:** Avery (manual, on-device).
**Target window:** 2026-05-12 → 2026-05-14 (3 days before Phase F kickoff).
**Target build:** the v1.7.X release artifact at the head of main on
the sweep day, signed with the production keystore, distributed via
Firebase to Avery's S25 Ultra.

This methodology operationalizes the D2 cleanup TODO ("pre-beta release
build regression") that previously had no documented procedure. It is
the go/no-go gate for the May 15 Phase F kickoff.

---

## Why this exists

Phase F testers will receive every auto-bumped build on every merge
(per PR #796's distribution pipeline + Phase E noise tolerance). That
means a regression introduced in the final pre-kickoff week ships to
testers on day 1 with no manual intervention. Without a documented
sweep methodology, regressions surfaced during Phase F testing are hard
to triage against a baseline.

The sweep produces:
1. A go/no-go decision against an explicit rubric.
2. A frozen "kickoff baseline" the bug-triage workflow can refer to
   when classifying regressions.
3. Documented "known issues at kickoff" that testers don't need to
   re-report.

## What this is NOT

- Not a substitute for `connectedDebugAndroidTest` CI signal — those
  validate code correctness; this validates feature correctness.
- Not the 11-scenario detailed runbook (Avery's hands-on, out of CC
  scope per the audit prompt's non-goal).
- Not a one-off: re-run the abbreviated sweep every Friday during the
  3-week round before any Saturday distribution to catch mid-round
  regressions early.

---

## Setup (~15 min)

### Device + build

- Device: Samsung Galaxy S25 Ultra
- Build: latest v1.7.X release artifact, signed with production
  keystore (NOT a debug build)
- Distribution: pull from Firebase App Distribution as a production
  user would
- Pre-conditions:
  - Fresh install (uninstall any prior version first to clear
    DataStore + Room state)
  - Sign-in with a dedicated test Google account (NOT Avery's primary
    — keeps real-data corruption risk to zero)
  - Disable any notification-blocking system settings before starting

### Recording

- Screen recording for the duration of the sweep — kept locally only,
  not uploaded (PII)
- Notes file: `docs/release/sweeps/YYYY-MM-DD-pre-phase-f-sweep.md`
  with verbatim observations, NOT inferences

---

## Abbreviated 30-min smoke (8-item Phase F checklist)

Each item ≤ 4 min. If an item exceeds 4 min, note it in the report —
slow surfaces are a signal, not a methodology violation.

### 1. Tasks + AI quick-add (~4 min)

- [ ] App opens to Today screen without crash
- [ ] Tap quick-add bar; type: `submit report tomorrow at 5pm @work !2`
- [ ] Verify task created with: tomorrow's date, 5pm time, `work`
      project, priority 2 (Medium)
- [ ] Mark task complete via swipe; verify it disappears from Today
- [ ] Pull-to-refresh; verify state persists after recompose

**Pass:** all 5 sub-items pass. **Fail:** any sub-item fails OR app
crashes.

### 2. Habits + streaks (~4 min)

- [ ] Open Habits tab; verify built-in habits seeded
- [ ] Tap a habit to mark complete for today
- [ ] Open habit detail; verify streak counter incremented
- [ ] Confirm contribution grid shows today as filled
- [ ] Verify streak persists after force-stop + relaunch

**Pass:** all 5 sub-items pass.

### 3. Medication tracking (~4 min)

- [ ] Open Medication tab
- [ ] Add a test medication: name "TestMed", DEFAULT slot, ideal time
      now-30min
- [ ] Mark a tier (e.g., "Within window" green tier) for the slot
- [ ] Verify tier mark persists after recompose
- [ ] Verify slot tier-state visible in MedicationScreen for today
- [ ] Delete the test medication after the sweep

**Pass:** all 6 sub-items pass. **Fail-as-P0:** any data-loss path
(tier mark not persisting; medication disappearing).

### 4. Cross-device sync (~4 min)

- [ ] On the test account, edit a task on the device
- [ ] Open the web app (https://prismtask.app or staging) signed in
      with the same test account
- [ ] Verify the edit appears within 30 seconds (Firestore push
      latency)
- [ ] Edit a different field on the web; verify it lands on device
      within 30s
- [ ] Verify no duplicate rows created

**Pass:** all 5 sub-items pass. **Fail-as-P0:** any sync corruption,
duplicate rows, or row deletion.

### 5. Batch operations (~3 min)

- [ ] Long-press a task to enter multi-select
- [ ] Select 3 more tasks
- [ ] Bulk action: change project to "Test"
- [ ] Verify all 4 tasks updated
- [ ] Undo (within the 24h window) and verify all 4 reverted

**Pass:** all 5 sub-items pass.

### 6. Pomodoro+ focus (~3 min)

- [ ] Start a 25-min Pomodoro on a task
- [ ] Verify timer notification persistent in shade
- [ ] Verify timer survives app backgrounding (lock screen, return)
- [ ] Stop early; verify partial-session log recorded

**Pass:** all 4 sub-items pass.

### 7. Weekly review (~3 min)

- [ ] Open Weekly Review
- [ ] Verify last week's data present (or "no data" for first run)
- [ ] Walk through review prompts; submit the review
- [ ] Verify review persists in WeeklyReviewsList

**Pass:** all 4 sub-items pass.

### 8. Settings + AI gate (~3 min)

- [ ] Open Settings → AI Features
- [ ] Toggle "Use Claude AI for advanced features" OFF
- [ ] Use a feature that would trigger AI (e.g., quick-add NLP)
- [ ] Verify on-device parser fallback used (NLP still works without
      Claude)
- [ ] Re-enable AI; verify Claude path resumes
- [ ] Verify NO outbound network requests to Anthropic when AI off
      (use a packet-trace tool or developer panel if available;
      otherwise rely on PR #816's interceptor unit test as the
      automated proof and skip this sub-item with a note)

**Pass:** all 6 sub-items pass. **Fail-as-P0:** AI gate leak
(packet to Anthropic when toggle off).

---

## Go/no-go rubric

Apply this rubric immediately after the sweep:

| Result pattern | Decision |
|---------------|----------|
| All 8 items pass; no P0 failures | **GO — Phase F kickoff proceeds 2026-05-15** |
| 1-2 items fail at P2/P3 severity; no P0 | **GO with documented "known issues at kickoff"** — testers don't re-report; fix in-round |
| 1+ items fail at P1 severity | **CONDITIONAL GO — fix-and-redistribute before kickoff**; re-run the failing items only after the fix lands |
| ANY item fails as P0 (data loss, AI gate leak, sync corruption, crash on launch) | **NO-GO — kickoff postponed**; full fix + re-sweep required |

The decision goes into the Phase F kickoff checklist's "Pre-kickoff
regression sweep" item with the date and outcome.

## Known-issues-at-kickoff format

Any P2/P3 failures that were waved through under "GO with known issues"
get a single-line entry in `docs/release/sweeps/YYYY-MM-DD-pre-phase-f-sweep.md`:

```
KIK-1: [surface] [one-line description]. Severity: P2/P3. Workaround: [if any]. Fix scheduled: week N.
```

Testers receive these in the day-1 announcement so they don't waste
triage cycles re-reporting. Bug triage demotes any incoming report
matching a KIK entry to "duplicate of KIK-N."

---

## Mid-round re-sweep (Fri before Sat distribution)

During the 3-week round, run an abbreviated re-sweep before the next
distribution if any of these triggers fired in the prior week:

- A P0 fix landed → re-run items 1-8 (full sweep)
- A P1 fix landed → re-run the affected item only + items 3, 4
  (medication + sync, the highest-engineered surfaces)
- 5+ merges to main since last sweep → re-run items 3, 4 minimum
- No triggers → no re-sweep required

Re-sweep results land in `docs/release/sweeps/YYYY-MM-DD-resweep.md`
following the same go/no-go rubric.

---

## Post-Phase-F closeout

After the 3-week round closes, write a sweep retrospective to
`PRE_PHASE_F_MEGA_AUDIT.md` Phase 5:
- Did the rubric catch real regressions?
- Were any "GO with known issues" items the cause of disproportionate
  tester reports?
- Should the abbreviated 30-min sweep be expanded for v2.1 (G.0+)?

---

## Source

Generated from `docs/audits/D2_AND_PHASE_F_PREP_MEGA_AUDIT.md` item 8.
Updates land via PR — methodology evolution is itself audit trail.
