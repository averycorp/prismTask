# Sync Tests 12 & 13 — Manual Runbook

PrismTask's sync-test matrix (Tests 7–15) is automated end-to-end in CI via
`android-integration.yml` except for these two. This runbook covers them.

## Why manual

Tests 12 and 13 both go through Google Sign-In, which opens an **OAuth Custom
Tab** (a Chrome-backed browser view that Android launches outside the app
process). The account-picker and password/2FA steps run inside that tab, which
`adb` cannot inspect or drive — UIAutomator sees the tab as an opaque Chrome
window, and every attempt to automate it has produced tests that are more
flaky than useful. Phase A's S1–S5 sign-in tests hit the same wall.

Two human-operated phones (or one phone + one emulator session with a
signed-in Google account) cover these in ~15 minutes when run by hand. That
beats a week of chasing Custom-Tab-automation flakiness.

## Scope

| Test | Name | Focus |
|---|---|---|
| **12** | Sign-out then sign-in same user | Verify local data persists and cloud data re-hydrates from Firestore |
| **13** | Sign-in as different user | Verify local data of user A is cleanly partitioned from user B and does not leak |

## Pre-requisites

### Hardware
- **Two Android devices** (or one device + one emulator with Play Services,
  Android 8+). Physical Samsung devices are ideal — they match the production
  fleet most closely.
- USB cables + a terminal with `adb devices` listing both.
- Wi-Fi on both; cellular is fine but avoid metered data.

### Google accounts
- **Primary account** — a real Google account used for Test 12. Needs enough
  free Drive space for backup attachments (~10 MB).
- **Secondary account** — a separate real Google account used for Test 13.
  Does NOT need to share anything with the primary. A fresh test account is
  ideal.

Both accounts must have 2-Step Verification configured the way you'd see in
the wild — skipping 2SV changes the Custom-Tab flow and won't match user reality.

### Build
- Latest Pro-tier release APK on both devices — not a debug build. Google
  Sign-In requires the release SHA1 registered in Firebase Console.
- If using an emulator, pick an `AOSP + Google APIs` image, not Play Store —
  the Play Store image rate-limits sign-in during testing.

### State
- **Before starting**: uninstall any prior PrismTask install on both devices
  to clear stale local DBs, sync cursors, and cached OAuth tokens. A
  clean-install baseline eliminates "did we see old data from a previous run?"
  ambiguity.

## Test 12 — Sign-out then sign-in same user

**Goal**: Local data survives sign-out; cloud data re-hydrates on sign-in.

**Expected duration**: ~7 minutes.

### Setup (device A only)
1. Install the release APK. Open app → complete onboarding.
2. Settings → Account → Sign in with Google → **primary** account. Confirm
   avatar + email render in Settings.
3. Wait ~30 s for the initial sync to complete (Settings → Cloud sync should
   say "Up to date").
4. Create known fixtures:
   - 1 task: `"test12-task"` with a due date 1 week out, priority High.
   - 1 habit: `"test12-habit"` with daily frequency; complete it today.
   - 1 project: `"test12-project"`; add `"test12-task"` to it.
   - 1 tag: `"#test12-tag"`; apply it to `"test12-task"`.
5. Wait ~20 s. Settings → Cloud sync says "Up to date".

### The test
6. Settings → Account → **Sign out**. Confirm the dialog. App returns to
   sign-in screen (or Today with empty state, depending on settings).
7. **Check local DB clearing behavior**: navigate through Tasks / Habits /
   Projects / Tags tabs.
   - **Expected (per current spec)**: all four fixtures are still visible.
     PrismTask keeps the local Room DB intact on sign-out — only clears the
     Firebase auth token and stops the Firestore listener. This is by design
     (users who sign back in on the same device shouldn't have to re-sync
     from scratch).
   - **Fail condition**: fixtures are gone. That would indicate the sign-out
     flow is wiping Room, which contradicts the spec.
8. Settings → Account → Sign in with Google → **primary** account (same one).
9. Wait ~20 s for sync cursor to reconcile.
10. Walk through fixtures again. Verify:
    - `"test12-task"` is present, still tagged `"#test12-tag"`, still in
      `"test12-project"`.
    - `"test12-habit"` is present; today's completion is still checked.
    - No duplicate rows anywhere.
    - Settings → Cloud sync says "Up to date" within 30 s.

### Cleanup
11. Delete the four fixtures. Confirm deletions reach Firestore (Cloud sync
    "Up to date"). This sets a clean baseline for Test 13.

### What to file if it fails
- Screenshot of the failing tab (Tasks / Habits / Projects / Tags).
- `adb logcat -d -s "SyncService:D" "AuthManager:D"` dump captured between
  steps 6 and 10.
- The exact sequence of taps that reproduced the drift.

## Test 13 — Sign-in as different user

**Goal**: Device A signed in as user B sees ONLY user B's cloud data. User
A's local rows are partitioned off and must not leak into user B's view.

**Expected duration**: ~8 minutes.

### Setup
1. **Device A**: from Test 12's clean state. If Test 12 wasn't just run,
   uninstall + reinstall PrismTask first, then sign in as primary account and
   let initial sync finish.
2. **Device B** (fresh): install PrismTask. Onboard, **do not sign in yet**.

### The test
3. **Device A**: create 2 fixtures — task `"user-A-task"`, habit
   `"user-A-habit"`. Wait for sync.
4. **Device A**: Settings → Account → Sign out.
5. **Device A**: Sign in with Google → pick the **secondary** account (NOT
   primary). Wait 30 s.
6. **Walk through Tasks / Habits / Projects / Tags on device A**. Verify:
   - Primary-account fixtures from step 3 are **NOT visible**. The user
     switch partitions cloud views correctly.
   - The view is either empty (if secondary account has no PrismTask data
     yet) OR shows only secondary-account data from a prior run.
   - **Fail condition**: primary-account tasks/habits/projects/tags are
     showing under the secondary account's session. That's a data-leak bug.
7. **Device A (still signed in as secondary)**: create a fixture —
   `"user-B-task"`. Wait for sync.
8. **Device B**: sign in with Google → **primary** account (the same one
   device A used in step 3). Wait 30 s for initial sync.
9. **Device B**: verify it sees `"user-A-task"` and `"user-A-habit"` (from
   primary account's cloud data) and does NOT see `"user-B-task"` (secondary
   account's data).

### Cleanup
10. Delete `"user-A-task"` and `"user-A-habit"` on device B. Delete
    `"user-B-task"` on device A. Sign out on both devices. Uninstall.

### What to file if it fails
- Which fixture leaked across accounts, on which device.
- `adb logcat -d -s "SyncService:D" "AuthManager:D" "FirebaseAuth:D"` from
  both devices covering the sign-out/sign-in window.
- Whether a subsequent force-sync-pull resolves the leak (would indicate a
  listener-cleanup timing bug) vs. persists (would indicate a cloud-side
  partitioning bug, higher-severity).

## When to run this runbook

- **Before every Phase C release-candidate build.** The Pro-tier sign-in flow
  is in scope for that RC, and this runbook is the only coverage.
- **Once during Phase B Wk 2.** Baseline expectation that Tests 12 and 13 are
  green before beta rollout begins; re-running them post-beta is a full
  regression check against user-observed issues.
- **After any change to `AuthManager`, `PrismTaskApplication.configureFirebaseEmulator`,
  or `SyncService.startRealtimeListeners`**. Those three classes own the
  sign-in and listener-lifecycle paths these tests probe.

## See also

- Automated sync tests (7, 8, 9, 10, 11, 14, 15): `app/src/androidTest/.../sync/scenarios/`
  and their workflow `android-integration.yml`.
- Phase A sign-in runbook (S1–S5): same OAuth Custom Tab limitation —
  reference for the "why manual" question.
