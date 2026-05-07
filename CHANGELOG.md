# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Fixed

- **Morning check-in banner now respects Start-of-Day end-to-end.** The
  Today-screen banner previously gated visibility on a hardcoded
  `Calendar.HOUR_OF_DAY < 11` check that ignored the user's configured
  SoD on both ends: it appeared in the dead-zone between calendar
  midnight and SoD (the same window where the rest of the Today screen
  correctly shows "yesterday"), and it ignored the
  `MorningCheckInPromptCutoff` slider in Settings → Advanced Tuning.
  Replaced with `MorningCheckInBannerDecider`, a pure-function helper
  that anchors the visible window on `[todayStart, todayStart + (cutoff
  - SoD))` with proper wrap-around handling for night-owl SoD values.
  Banner dismissal now records the SoD-aware logical date instead of
  `LocalDate.now()`, fixing a self-healing bug where a dismissal made
  before SoD bounced back until the boundary crossed. Audit:
  `docs/audits/MORNING_CHECKIN_SOD_BOUNDARY_AUDIT.md`.

### Privacy

- **Closed Gmail integration AI-features opt-out gap.** Toggling Settings →
  AI Features → "Use Claude AI for advanced features" off now also blocks
  `POST /integrations/gmail/scan` on both the Android client (the
  `AiFeatureGateInterceptor` short-circuits the request before it hits the
  network) and the FastAPI backend (the `require_ai_features_enabled`
  dependency on the route returns HTTP 451 before any Anthropic call). The
  Gmail integration framework had landed two weeks before the opt-out gate
  shipped (PR #790), and the original audit's endpoint enumeration missed
  it; the 2026-05-01 re-audit
  (`cowork_outputs/pii_leak_surface_reaudit_REPORT.md`) caught the gap.
  Privacy policy and Data Safety form were updated to disclose Gmail
  subjects/snippets/sender addresses as data sent to Anthropic when the
  user opts into the Gmail integration. Audit doc updated with a
  forward-looking checklist for any future `/integrations/*` route that
  egresses to Anthropic. The Android interceptor also now stamps
  `X-PrismTask-AI-Features: disabled` on the synthetic 451's request as
  defense-in-depth against future interceptor reordering. Adds 2 backend
  pytest cases, 4 Android interceptor cases.

### Changed

- **Allow medications without a slot (as-needed / PRN).** The
  `MedicationEditorDialog` Save button no longer requires a slot pick
  when active slots exist. A med saved without any linked slot routes
  through the existing "Unscheduled" section on the Medication screen
  (a "Record Taken" button per row writes `slotKey = "anytime"` doses
  with no toggle semantics, surfacing "Last taken at HH:mm"). The
  duplicate-name `SQLiteConstraintException` crash protection that PR
  #1141 originally bundled into the gate stays — `getByNameOnce`
  pre-flight + outer try/catch in `MedicationViewModel.addMedication`
  are the load-bearing layer.
  See `docs/audits/ALLOW_UNSCHEDULED_MEDICATION_AUDIT.md`.

### Added

- **Cross-device Firestore sync for `task_timings`.** P2-E of the analytics
  C4/C5 implementation (`docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`,
  Path 2). `task_timings` rows now flow through `SyncService` like every other
  synced entity: initial upload (after tasks so FK cloud IDs resolve), incoming
  pulls in `pullEverything()`, real-time listener registration, FK-aware
  `pushCreate()`, collection-name mapping, and remote-deletion reaping.
  `SyncMapper` gains `taskTimingToMap` / `mapToTaskTiming` round-trip mappers
  with caller-resolved task FK bindings; 3 new unit tests exercise the manual,
  pomodoro, and unknown-source-fallback round trips.

- **Pomodoro auto-log into `task_timings`.** P2-D of the analytics C4/C5
  implementation (`docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`,
  Path 2). On focus-session timer completion (`onTimerComplete()`),
  `SmartPomodoroViewModel` writes one `TaskTimingEntity` per
  `SessionTask` in the just-finished session, crediting each task its
  planned `allocatedMinutes` with `source = TaskTimingEntity.SOURCE_POMODORO`.
  The analytics time-tracking chart (P2-C) picks them up automatically.
  Failures are swallowed so the post-session UX stays clean. Adds 3
  unit tests over the auto-log helper.

- **Time-tracking bar chart on `TaskAnalyticsScreen` — Pro-gated.** P2-C of
  the analytics C4/C5 implementation
  (`docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`, Path 2). New
  `TimeTrackingAggregator` use case + `TimeTrackingResponse` /
  `DailyTimeBucket` domain models bucket `TaskTimingEntity` rows into
  per-day totals over the 7d/30d/90d window selected by the existing
  productivity-range selector (the two charts share the range so the
  analytics screen reads as a single coherent view). New `TimeTrackingSection`
  composable renders a Compose Canvas bar chart with totals + active-day
  count + average-minutes-per-active-day; empty days inside the window
  draw as zero-height bars so the time axis stays continuous. Empty-state
  copy points back to the Schedule-tab Log time row from P2-B. Adds 6
  aggregator unit tests.

- **Manual "Logged Time" section in the task editor's Schedule tab.** P2-B
  of the analytics C4/C5 implementation
  (`docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`, Path 2). New chip
  row (`+15m` / `+30m` / `+1h` / `+Custom`) appends a manual
  `TaskTimingEntity` (source = `manual`) and the running total updates
  reactively below the chips. Section is edit-mode only — manual logging
  needs a persisted `taskId` to FK against. Wires
  `TaskTimingRepository` into `AddEditTaskViewModel` and exposes
  `loggedMinutes: StateFlow<Int>` and a `logTime(minutes)` action.

- **`TaskTimingEntity` + `TaskTimingDao` + `TaskTimingRepository` — per-task
  time-tracking data layer.** P2-A of the analytics C4/C5 implementation
  (`docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`, Path 2). New Room
  table `task_timings` (migration 64 → 65) with `cloud_id`, `task_id` (FK
  CASCADE), nullable `started_at` / `ended_at`, `duration_minutes`,
  `source` (`manual` / `pomodoro` / `timer`), `notes`, `created_at`. DAO
  exposes per-task list/observe queries, range-window observe, and total-
  minutes aggregation. Repository wraps insertion, deletion, and updates;
  no sync-tracker wiring yet (cross-device sync ships in P2-D follow-up).
  Adds `Migration64To65Test` (instrumented) covering create/insert/CASCADE
  and `TaskTimingRepositoryTest` (JVM) covering 9 repository contract
  cases.

- **Productivity score chart on `TaskAnalyticsScreen` — Pro-gated.** Slice 3
  of the web PR #715 Android port (`docs/audits/ANALYTICS_PR715_PORT_AUDIT.md`,
  Subset C). Compose Canvas area chart with a 7d/30d/90d range selector,
  trend chip, average score, and best/worst-day callouts. Driven by
  `ProductivityScoreCalculator` (slice 2) — recomputes when range changes
  via a `_productivityRange` `MutableStateFlow`. Adds a new
  `TaskDao.getTasksForAnalyticsRange(start, end)` query that returns tasks
  whose `due_date` OR `completed_at` lands in the window (matches the
  backend's analytics-window semantics).

- **`ProductivityScoreCalculator` use case + `ProductivityScore` domain model.**
  Slice 2 of the web PR #715 Android port (`docs/audits/ANALYTICS_PR715_PORT_AUDIT.md`,
  Subset C). Pure-compute daily productivity score with the same 4-component
  breakdown the backend uses (task completion 40 % / on time 25 % / habit
  completion 20 % / estimation accuracy 15 %), the same trend rule
  (split-half avg ±3), and the same default-100 fallback for empty buckets.
  Estimation accuracy is hard-coded to 100 today because Android Room has no
  `actualDuration` column on `TaskEntity` — swap when time tracking lands.
  Domain layer only; no UI changes (the chart wiring lives in slice 3 /
  `feat/analytics-productivity-chart`).

### Removed

- **Medication reminders on the Today (start) screen.** The Daily
  Essentials medication card is gone — dose tracking lives on the
  dedicated `MedicationScreen` (still reachable from the Habits tab,
  Settings, the morning check-in, and refill projection). The Today-screen
  Habits chip for the Medication habit no longer special-cases a
  navigation route; it now toggles the habit completion the same way
  every other Habits chip does. Background notification reminders
  (`MedicationReminderScheduler`), refill projections, and sync
  round-tripping of `daily_essential_slot_completions` rows are all
  unchanged. Audit:
  `docs/audits/REMOVE_MEDICATION_REMINDERS_FROM_START_SCREEN.md`.

### Added

- **Pro-gated analytics summary tile row on `TaskAnalyticsScreen`.** Phase 2 /
  slice 1 of the web PR #715 Android port (`docs/audits/ANALYTICS_PR715_PORT_AUDIT.md`,
  Subset C). Adds `AnalyticsSummary` domain model + `AnalyticsSummaryAggregator`
  use case computing today/week-trend/streak/habit-completion rates client-side
  from Room (web is server-side via Postgres; Android can't share the data
  path because tasks/habits sync via Firestore). Free-tier users see a single
  upsell card; Pro users see the four-tile row at the top of the screen.
  `HabitCompletionDao` gains a `getAllCompletionsInRange(start, end)` query.
  Adds 9 unit tests covering aggregator branches (today bucket, week trend
  10% threshold, streak counting with gaps, habit rate cap at 1.0, archived
  habit exclusion, zero-active-habits, zero-previous-week edge cases).

- **`DailyResetWorker.computeNextDelayMs(now)` helper + `now: Long` threading through `WidgetDataProvider`.**
  Phase 2 / Tier A2 of the automated edge-case testing audit
  (`docs/audits/AUTOMATED_EDGE_CASE_TESTING_AUDIT.md`, PR #879). Production
  callers default to `System.currentTimeMillis()` so behavior is unchanged;
  tests can pin `now` deterministically, closing the testability gap that
  previously required monkey-patching the system clock for boundary
  scenarios. Adds 10 unit tests under `app/src/test/.../workers/` covering
  pre-/post-/at-boundary, minute offset, midnight SoD, year-end, leap day,
  negative-clamp, multi-cycle drift, and helper-vs-inline-math regression
  gate.

### Fixed

- **Connected-tests `harness_deviceAOfflineToggleDoesNotBlockDeviceBWrites`
  no longer trips `ConnectivityManager$TooManyRequestsException`.** The
  smoke test's `disableNetwork()`/`enableNetwork()` cycle on device A's
  Firestore re-initialised the production-side `FirestoreClient`, which
  registers a fresh `AndroidConnectivityMonitor` callback each time —
  across the 424-test connected-tests suite this exhausted Android's
  per-UID callback quota (~100) and failed PRs #1015 and #1021 at
  position ~423. Replaced the network-toggle smoke test with a
  structural-orthogonality assertion (distinct FirebaseApp + Firestore
  instances), since real offline-toggle behaviour is still exercised
  by `Test7OfflineEditReconnectTest`, `Test10ConcurrentDeleteTest`, and
  `Test11OfflineDuringRemoteWriteTest`. The follow-on Firebase-test-isolation
  audit (flagged in `docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md`)
  will tackle the underlying Firestore-client churn structurally.

- **Toggle-uncomplete also rolls back the spawned recurrence (Item 2
  residual).** Closes the second duplication path from the
  recurring-tasks audit. `task_completions` now carries a
  `spawned_recurrence_id` column (Room migration 66 → 67) populated by
  `TaskRepository.completeTask` so `uncompleteTask` can find and delete
  the spawned next-instance even when the user un-toggles via the
  checkbox (no Undo snackbar). The completion entry itself is also
  removed on uncomplete so re-completion starts from a clean slate.
  Field round-trips through the Firebase sync mapper. Two new
  connected tests cover toggle-uncomplete-rollback and
  toggle-complete-uncomplete-recomplete-no-duplicate. Audit:
  `docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md` (Item 2
  residual, was DEFERRED).

- **Recurring tasks no longer duplicate on Undo + redo.**
  `TaskRepository.completeTask` now reads the row fresh inside its
  transaction and bails out when `is_completed = 1`, so a rapid
  double-tap or any other re-invocation cannot spawn a second
  next-instance. Snackbar Undo callers (Today swipe, TaskList swipe,
  bulk complete) capture the spawned next-instance id and pass it back
  to a new `uncompleteTask(id, spawnedRecurrenceId)` overload that
  deletes the spawned child before flipping the parent incomplete —
  matching audit
  `docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md`. Three new
  connected tests gate the idempotence guard, the snackbar Undo +
  redo flow, and the legacy toggle-uncomplete behavior.

- **Eisenhower / Pomodoro task completion now spawns recurrences and
  cancels reminders.** `EisenhowerViewModel.completeTask` and
  `SmartPomodoroViewModel.completeTask` previously called
  `taskDao.markCompleted` directly, bypassing `TaskRepository.completeTask`.
  Recurring tasks completed from those screens silently disappeared
  (no next-instance), stale reminder alarms could still fire for the
  finished row, and sync tracking / calendar push / widget updates were
  all skipped. Both view models now route through `taskRepository.completeTask`,
  matching every other complete entry point. Audit:
  `docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md` (Item 3).

- **`HiltTestRunner.isAndroidEmulator()` now detects modern AVDs.** The
  test-runner heuristic copied from `PrismTaskApplication` (around the
  PR #791 era) had drifted out of sync — production picked up
  `Build.HARDWARE in {"ranchu", "goldfish"}` checks, but the test-runner
  copy did not. Modern API 33+ emulators report
  `Build.PRODUCT = "sdk_gphone64_x86_64"` and `Build.MODEL` of the same
  form, none of which the legacy heuristics matched. With
  `isAndroidEmulator()` returning false, `configureFirebaseEmulator()`
  fell through to `host = "localhost"`, and the AVD's loopback can't
  reach the host's Firestore emulator — so cross-device sync tests
  hung on `ECONNREFUSED` retries until the 45-min job-timeout fired.
  This was the actual root cause of the post-#835 cross-device-tests
  cancellation on `c5c0fefc` (run `24974393528`); PR #835's retry-drop
  change was a contributing-but-not-blocking concern. Full audit at
  `docs/audits/D2_CLEANUP_PHASE_F_UNBLOCK_MEGA_AUDIT.md` § 1
  (post-merge correction in commit message of this PR).

### Changed

- **Cross-device-tests CI job: drop shell-level retry.** The
  `cross-device-tests` job in `.github/workflows/android-integration.yml`
  previously ran `run_cross_device_tests || run_cross_device_tests
  --rerun-tasks` inside `reactivecircus/android-emulator-runner@v2`,
  doubling test wall-clock and crossing the 45-minute job timeout
  whenever no concurrent push pre-empted the run (run `24970283404`,
  sha `871c2da8`, exceeded 45m0s). Single-attempt now mirrors the
  connected-tests job's shape (which sustains ~91% success without
  retry). Real flakes will surface as honest failures, not silent
  convergence on a second pass. Full audit at
  `docs/audits/D2_CLEANUP_PHASE_F_UNBLOCK_MEGA_AUDIT.md` § 1.

### Fixed

- **Web mood-energy log writer no longer creates duplicate Firestore
  docs for the same `(dateIso, timeOfDay)` slot.** Previously
  `createLog` in `web/src/api/firestore/moodEnergyLogs.ts` used
  `addDoc` (random doc id) with no uniqueness guard, so a second log
  in the same slot wrote a second cloud doc. On the next Android pull
  that doc would fail Room's unique index `(date, time_of_day)` on
  `mood_energy_logs`, throwing `SQLiteConstraintException` and
  leaving the cloud row orphaned. Fixed with a deterministic doc id
  `${dateIso}__${timeOfDay}` written via
  `setDoc(..., { merge: true })`, mirroring Android's natural-key
  index and the existing convention in `medicationSlots.ts` (tier
  states) and `checkInLogs.ts`. Tier B Phase F parity, audit PR
  #836 § Surface 12.

### Added

- **Auto-update-branch workflow.** A new
  `.github/workflows/auto-update-branch.yml` keeps open PRs current
  with `main` automatically. Fires on `push: main` (catches every
  regular merge), on `workflow_run` of "Version Bump & Firebase
  Distribute" (catches the `[skip ci]` versionCode bump that `push`
  triggers can't see), and on `pull_request: ready_for_review`
  (catches drafts that flip to ready while behind). Updates are
  driven by `AUTOFIX_PAT` so the resulting "Update branch" commit
  retriggers required-status workflows — same shape as PR #777's fix
  inside `auto-merge.yml`, but for the `main`-advances case that PR
  #777 doesn't observe. PRs carrying the new `no-auto-update` label
  are skipped; drafts are auto-updated by default. Merge conflicts
  and `AUTOFIX_PAT` 401s post explanatory PR comments (deduped via
  hidden HTML markers so repeated runs don't spam). Full audit at
  `docs/audits/AUTO_UPDATE_BRANCH_AUDIT.md`.

- **Bulk medication tier marking (Android + web).** A new TopAppBar
  action on `MedicationScreen` (Android) and a header-bar `ListChecks`
  button (web) opens a `BulkMarkDialog` that lets the user mark every
  medication in a slot — or every medication across today — to a
  chosen tier in one action. Two scopes ship: `SLOT` (pick one slot,
  set its tier) and `FULL_DAY` (set every active slot's tier today).
  Tier-scope as a third option was considered and dropped per
  `docs/audits/BULK_MEDICATION_MARK_AUDIT.md` Decision 2 — under the
  uniform-setter interpretation it collapses onto FULL_DAY, so the
  first ship offers two scopes and the dialog stays narrower.
  Android routes through PR #772's existing batch infrastructure with
  fan-out at the apply layer: the bulk caller emits N
  `ProposedMutationResponse` rows (one per affected medication+slot),
  one shared `batch_id` lands in `batch_undo_log`, and the existing
  24h durable history undo (Settings → Batch History) reverses the
  whole bulk action atomically. SKIPPED routes through
  `BatchMutationType.SKIP` so the synthetic-skip dose loop fires per
  medication and re-anchors interval-mode reminders — same behavior
  as `MedicationViewModel.setSkippedForSlot`. Non-SKIPPED tiers route
  through `BatchMutationType.STATE_CHANGE`, which writes only the
  tier-state row and leaves the dose log untouched. Web stores
  per-(slot, date) aggregate state, so the bulk write is N Firestore
  doc writes for FULL_DAY (one per active slot); a new
  `setTierStatesAtomic` helper wraps them in `writeBatch` for
  atomicity (Decision 1) — a network blip mid-bulk no longer leaves
  a torn state. Slot-level long-press from PR #744 (Android) and
  PR #745 (web) is preserved unchanged; bulk-mark uses the
  TopAppBar / header-bar gesture surface so it doesn't collide with
  the existing per-tier-chip long-press.

- **Web-side in-app account deletion.** Settings → Delete Account is now
  a working two-step typed-DELETE confirmation that calls
  `/api/v1/auth/me/deletion` with `initiated_from="web"` (mirroring
  Android's `DeleteAccountSection.kt` idiom) and signs the user out on
  success. Closes the privacy gap from PR #774 PR4b — the previous web
  button was a disabled placeholder pointing users to the Android app.
  New `DeleteAccountSection` + `DeleteAccountModal` components own the
  EXPLAIN → CONFIRM → SUBMITTING → SIGNING-OUT state machine; modal is
  trim-tolerant on the typed `DELETE` match and displays a retry path on
  backend error. The flow also mirrors the deletion fields onto
  Firestore `users/{uid}` so Android's next sign-in `checkDeletionStatus()`
  detects the web-initiated deletion. The shared axios client gains a
  `410 Gone` interceptor branch (the backend's `get_active_user`
  middleware returns 410 on pending-deletion accounts) so any in-flight
  or subsequent mutation request force-clears the JWT pair and signs
  the user out, preventing zombie web sessions on a soft-deleted
  account. Per `docs/audits/PHASE_D_BUNDLE_AUDIT.md` Item 5.

- **Medication batch mutations (NLP / Haiku → BatchPreview → undo)** —
  Natural-language commands like "took my morning meds" or "skip evening
  Adderall" now parse, preview, apply, and undo as a batch — closing the
  Option-C deferral that previously accepted medication mutations from
  the AI plan but refused to write them. `BatchEntityType.MEDICATION` is
  paired with four mutation verbs: `COMPLETE` (insert real
  `MedicationDoseEntity`), `SKIP` (insert synthetic-skip dose so the
  interval-mode rescheduler re-anchors, plus tier-state="skipped"),
  `DELETE` (remove the matching real dose), and a new
  `BatchMutationType.STATE_CHANGE` for manual tier overrides
  (`tier_source = "user_set"`). Every path snapshots its pre-state into
  `batch_undo_log` so the 24h Snackbar undo restores exact prior state.
  `slot_key` from Haiku resolves to a `MedicationSlotEntity` by
  case-insensitive name match; STATE_CHANGE skips the mutation if the
  slot is missing, SKIP gracefully proceeds without the tier-state
  write. Web parity ships in the same change: the Firestore-side
  `users/{uid}/medication_tier_states/{date}__{slot}` doc absorbs every
  medication mutation idempotently — multiple mutations on the same
  slot collapse onto one tier-state row, matching web's slot-level UX
  (web has no per-medication dose collection, so DELETE on MEDICATION
  is rejected on web with a clear reason). The Haiku system prompt + the
  Pydantic `_BATCH_MUTATION_TYPE_PATTERN` regex both gain `STATE_CHANGE`,
  and the BatchPreview row renders a target-tier chip
  ("→ prescription (evening)") for STATE_CHANGE mutations on MEDICATION.
- **Built-in habit template versioning + diff/approve/detach UI** —
  Migrates the 6 code-defined built-in habits (School, Leisure,
  Morning Self-Care, Bedtime Self-Care, Medication, Housework)
  from a seed-once, immutable-after-accept system to a versioned
  + mergeable system. New Room migration v61 → v62 adds
  `habits.source_version`, `habits.is_user_modified`,
  `habits.is_detached_from_template`, and
  `self_care_steps.source_version`; existing built-in rows are
  backfilled to v1. New `BuiltInHabitVersionRegistry` holds the
  canonical definitions; bumping a built-in is now a registry
  edit + version increment, not a behaviour-leaking app release.
  New `BuiltInUpdateDetector` runs after `SyncService.fullSync()`
  and surfaces pending updates on a new `BuiltInUpdatesScreen`
  (entry point in the Templates overflow menu). Per-template diff
  screen offers per-change approval with sensible defaults
  (additive on, removals off, user-modified fields off),
  preserves user-added steps, and supports one-click "Detach" to
  silence future prompts on a row. Detach is sticky cross-device
  via logical-OR sync; dismissed-version state is per-device by
  design. See `docs/TEMPLATE_MIGRATION_DESIGN.md` for the schema
  design and `docs/TEMPLATE_MIGRATION_PR_PLAN.md` for the layer
  breakdown.

- **In-app account deletion with 30-day grace period** — Settings →
  Account & Sync now exposes a "Delete Account" button (Android-only
  for now). Two-step typed-DELETE confirmation matches the existing
  Reset App Data dialog. On confirm, AccountDeletionService marks
  Firestore deletion-pending, signs the user out, and wipes all local
  state (Room via clearAllTables, every DataStore preference file,
  WorkManager workers, posted notifications). Backend gains 4 new
  endpoints under /api/v1/auth/me/deletion (mark, status, cancel,
  purge) plus migration 020 adding deletion-pending columns to
  `users`. Sign-in within the 30-day grace window detects the pending
  state and offers Restore. Sign-in after the grace window triggers
  permanent deletion: backend deletes the Postgres row (CASCADE) and
  the Firebase Auth record via Firebase Admin SDK. Privacy policy
  and Play Console data-safety form updated; the existing email
  deletion path is preserved as a fallback for users who cannot
  access the Android app.

- **Show "Taken at HH:mm" on each medication slot card + bump log
  time visibility** — Previously the MedicationScreen surfaced a
  backlogged-indicator clock icon but never the actual wall-clock
  time the user claimed to have taken a dose. Each slot card now
  renders a "Taken at 8:30 AM" line under the slot name when a
  tier-state row exists for today, preferring `intended_time` (the
  user's declared moment) and falling back to `logged_at` otherwise.
  When backlogged, the line surfaces BOTH moments — "Taken 8:05 AM ·
  Logged 10:30 AM" — so the gap is legible, not hidden behind the
  clock icon. In the medication log, per-dose `takenAt` was promoted
  from `labelSmall` / muted color to `bodySmall` / primary so it
  reads as primary row metadata. `takenTimeLabel` is a pure helper;
  6 unit tests cover the no-data, logged-only, intended-only,
  backlogged-both, and user-override-without-taken edge cases.

- **Medication entities now sync through `/sync/push`** — Wires
  `BackendSyncService.pushChanges()` to push `medication`,
  `medication_slot`, `medication_tier_state`, and `medication_mark`
  rows whose `updatedAt > since`. Tier-state and mark mappers send
  `*_cloud_id` references (resolved server-side); rows whose parents
  haven't yet been assigned a cloud_id are skipped and retried on the
  next sync. Closes the loop on PR1's audit table — every backdated
  intended_time will now produce a `medication_log_events` row in
  the backend Postgres for Data Safety / debugging visibility.

- **Medication time logging — long-press time editor + backlogged
  indicator (PR3 of 4)** — Long-press on a slot's tier chip in the
  Medication screen now opens `MedicationTimeEditSheet`, a Material 3
  bottom-sheet `TimePicker` that stamps an `intended_time` on every
  per-medication tier-state row for the slot. Future times are capped
  to `now` (no forward-dating). When the saved `intended_time` differs
  from the row's `logged_at` by more than 60 s, a small clock icon
  appears on the tier chip — `MedicationSlotTodayState.isBacklogged`
  drives the indicator. Tapping the chip retains the existing skip /
  unskip behaviour. Per-medication mark UX (long-press individual
  medication rows) is deferred to a follow-up PR to keep this
  reviewable.

- **Medication time logging — Room schema + Firestore sync (PR2 of 4)** —
  Bumps Room DB to **v63** via `MIGRATION_62_63`. Adds `intended_time`
  (nullable) and `logged_at` (NOT NULL, backfilled from `updated_at`)
  columns to `medication_tier_states`. Creates the new
  `medication_marks` table for per-medication marks within a slot, with
  cloud-id sync and CASCADE FKs to `medications` + `medication_tier_states`.
  `MedicationSyncMapper` round-trips the new fields on tier-states and
  the new mark entity. `CloudIdOrphanHealer` registers
  `medication_marks` as a synced family. `intended_time` stays NULL on
  legacy rows — the UI must display "we don't know" honestly until a
  user-touch backfills. No UI changes (PR3).
- **Medication time logging — parity with Android (PR4 of 4)** —
  Adds `intended_time` (nullable) + `logged_at` columns to the
  `users/{uid}/medication_tier_states/{dateIso}__{slotKey}` Firestore
  schema. New `setTierStateIntendedTime(uid, dateIso, slotKey, ms)` is
  the dedicated write path for backdating — separate from
  `setTierState` so tier changes can't clobber intended_time.
  `MedicationTimeEditModal` opens via right-click (desktop) or 500 ms
  touch-and-hold (mobile) on the tier picker; HTML5 `<input type="time">`
  composes against the screen's selected `dateIso`. Future times are
  capped to `now` server-side. A clock-icon "backlogged" indicator
  appears on the tier row when `intended_time` differs from
  `logged_at` by more than 60 s, with a tooltip showing the claimed
  HH:mm. Closes the medication time-logging series (PRs #742, #743,
  #744 + this).

- **Batch preview now shows a before/after tag diff for TAG_CHANGE
  mutations, plus regression tests for the existing apply/undo path.**
  When the AI parses a command like "tag all Friday tasks as #personal"
  or "untag #work from overdue items", the BatchPreviewScreen row now
  renders the affected task's current tag list ("From: ...") next to
  the post-mutation list ("To: ..."), with green `+ #name` chips for
  additions and red `− #name` chips for removals. The repository's
  `TAG_CHANGE` apply path (auto-create-missing tags, case-insensitive
  match, untouched tags preserved) and undo path (restore exact prior
  tag list, do not delete auto-created tags) were already implemented
  in PR #697 alongside the rest of the batch ops; this PR closes the
  gaps the audit flagged: a UI diff for the preview row and an
  instrumentation regression net (`BatchOperationsRepositoryTagChangeTest`)
  plus four backend tests covering Haiku-prompt round-trip for add /
  remove / combined add+remove commands, and a system-prompt regression
  asserting `TAG_CHANGE` + `tags_added` / `tags_removed` stay
  documented in `_BATCH_PARSE_SYSTEM_PROMPT`.

- **Medication reminder mode — per-medication overrides (Android).**
  Medication editor (Add / Edit) gains the same Default / Clock / Interval
  picker that ships in the slot editor. Per-medication `reminder_mode` +
  `reminder_interval_minutes` now flow through `addMedication` /
  `updateMedication` to the `medications` table — the resolver and
  reactive scheduler already honored these columns, so opting in
  per-medication immediately wins over the slot's mode + the global
  default.

- **Medication reminder mode — per-slot picker (Web).** Settings →
  Medication Slots editor now exposes a per-slot Default / Clock /
  Interval picker with the same presets row Android uses (2h / 4h /
  6h / 8h + custom 60–1440 minutes). Saving immediately writes
  `reminderMode` + `reminderIntervalMinutes` to the slot's Firestore
  doc; Android picks them up on the next sync. Optimistic update with
  rollback on failure.

### Documentation

- **Android↔web parity audit (Phase F) — Phase 3 bundle summary
  appended.** Documents the 10 implementation PRs that landed for
  Tier A + B re-triage (PRs #837, #838, #839, #840, #842, #843, #844,
  #845, #846, plus the audit doc itself at #836). Per-surface PR
  table with merge SHAs + scope-vs-audit deviations. DEFER-TO-G.0
  list and ACCEPT-AS-DIVERGENCE list captured durably so future
  audits don't re-flag. Phase F readiness gate: web is partial-parity
  with documented divergences (medication is intentionally
  Android-primary; sync architecture is structurally thinner;
  notification delivery is Android-only until Web Push in Phase G;
  voice input is Android-only by design). Memory-entry candidates
  flagged for Avery review.

- **Android↔web parity audit (Phase F).** New
  `docs/audits/ANDROID_WEB_PARITY_AUDIT.md` covers all 15 launch-relevant
  surfaces (medication, habits, tasks, AI quick-add, Pomodoro+, sync
  infrastructure, account, settings, privacy, daily essentials/Today,
  morning check-in, mood/energy, weekly review, voice, notifications)
  on two axes (sync wiring + feature accessibility) with per-gap triage
  classifying each as SHIP-BEFORE-MAY-15, DEFER-TO-G.0, or
  ACCEPT-AS-DIVERGENCE. Deadline-realism check for the 2026-05-15
  Phase F kickoff fires hard: 23 raw SHIP items vs the 8-item STOP
  threshold; recommended Tier A + B re-triage (11 PRs, ~7-8 days)
  preserves launch quality without burning the 19-day window. Headline
  findings: web sync layer is structurally thinner than expected
  (`api/sync.ts` is a 27-line stub with zero callers; real-time
  listeners exist but `App.tsx` never calls them); web write paths in
  `tasks.ts` and `habits.ts` systematically clobber Android-only
  fields; web has no AI opt-out toggle / Anthropic disclosure surface
  (PR #790 only shipped half the cross-platform privacy story); web
  habit streak is strict-consecutive vs Android forgiveness-first;
  three medication-collection mismatches and an onboarding completion
  Firestore-path mismatch break the "complete once per account"
  promise.

### Backend

- **Medication tier_state / mark cross-system FK resolution** — On
  `/sync/push`, `medication_tier_state` and `medication_mark`
  references are now sent by `*_cloud_id` (`medication_cloud_id`,
  `slot_cloud_id`, `tier_state_cloud_id`) rather than backend-local
  integer FKs. The new resolver `_resolve_cloud_fk_for_medication`
  pops each cloud_id from the payload, looks up the matching local
  row scoped to the authenticated user, and writes the integer FK
  into the data dict. Required for Android push to work — local
  Android ids never agree with backend ids. Errors out explicitly
  when a required cloud_id is missing or doesn't resolve to a row
  the user owns.

- **Medication entities + audit log (PR1 of 4 — medication time logging)** —
  Adds first-class backend sync support for `medications`,
  `medication_slots`, `medication_tier_states`, and `medication_marks`
  via `/sync/push` (Alembic rev 019, all five tables timezone-aware).
  Tier-state and mark schemas carry the new `intended_time` (nullable —
  user-claimed wall-clock) and `logged_at` (server-received) columns
  the time-logging feature is built around. Every push that touches a
  `medication_tier_state` or `medication_mark` now writes an
  append-only row to `medication_log_events` (audit) inside a savepoint
  — audit failures don't block sync. New
  `GET /api/v1/medications/log-events?since=&limit=` returns the
  caller's events, newest-first, auth-scoped to `user_id`. Sets up
  PR2 (Android schema) and PR4 (web parity) — Path 2 chosen at
  Checkpoint 1: medication entities sync through the backend in
  parallel to Firestore so the audit log captures every write.

### Changed

- **Connected-tests AVD boot timeout 600s → 1200s.** Both
  `reactivecircus/android-emulator-runner@v2` invocations in
  `.github/workflows/android-integration.yml` now pass
  `emulator-boot-timeout: 1200`. The action's 600s default was hitting
  the wall under GHA's noisy-neighbor load (per PR #817 triage:
  `[EmulatorConsole]: Failed to start Emulator console for 5554`),
  causing the job to CANCEL rather than FAIL — and SKIPPED counts as
  success in branch protection, so every PR was silent-bypassing the
  integration suite for ~17h before the triage doc landed. Closes the
  carry-forward blocker from PR #823's pre-Phase F mega-audit. Audit
  at `docs/audits/EMULATOR_BOOT_TIMEOUT_AUDIT.md`.

- **Privacy disclosure + AI-features opt-out (PII egress audit follow-up).**
  Added a master "Use Claude AI for advanced features" toggle in
  Settings → AI Features (default on for Pro users). When the toggle is
  off, the new `AiFeatureGateInterceptor` short-circuits every
  Anthropic-touching outbound request on-device with a synthetic 451
  response — no PrismTask data reaches the backend or Anthropic. A
  matching backend dependency `require_ai_features_enabled` rejects any
  request that arrives with `X-PrismTask-AI-Features: disabled` (defense
  in depth for non-Android callers and stale builds). Updated
  `docs/privacy/index.md` and
  `docs/store-listing/compliance/data-safety-form.md` to (1) explicitly
  disclose that medication names are included in NLP batch-command
  requests to Anthropic, (2) correct prior text that described
  Anthropic processing as "zero-retention" — Anthropic's standard API
  retention is 30 days (up to 2 years if Trust & Safety review is
  triggered), and Anthropic does not train on inputs (Commercial Terms
  § B), and (3) document the new opt-out path. Per
  `docs/audits/PII_EGRESS_AUDIT.md` Section 6 + Section 7
  (recommendation Option C).

- **Garbage-collect orphan `medication_marks` table.** The medication
  time-logging chain (Android PRs #743, #744; web PR #745) landed with
  `medication_marks` as a planned per-medication mark table, but the UI
  half ended up using `medication_tier_states.intended_time` for slot-
  granularity time editing instead — the design call is documented at
  `MedicationViewModel.kt:132` ("the user edits them at slot
  granularity"). No production write path on Android, web, or the
  backend ever populated the table, leaving it as a sync-protocol
  footgun (a future client could have started writing to it and
  round-tripped rows that no Android version reads). This change drops
  the table everywhere: Room migration `MIGRATION_63_64` (DB version
  bumped to 64), Alembic revision `021_drop_medication_marks`,
  SQLAlchemy `MedicationMark` model, the `medication_mark` entity in
  the `/sync/push` ENTITY_MAP / WRITABLE_FIELDS / cloud-FK resolver /
  AUDIT_ENTITY_TYPES dicts, the Android `MedicationMarkEntity` +
  `MedicationMarkDao`, the entity- and Firestore-side mappers
  (`medicationMarkToOperation`, `MedicationSyncMapper.medicationMarkToMap`
  + `mapToMedicationMark`), and the `CloudIdOrphanHealer` family entry.
  Existing `medication_log_events` rows with `entity_type = "mark"` are
  preserved (audit log is append-only). Per
  `docs/audits/PHASE_D_BUNDLE_AUDIT.md` Item 3.

- **BREAKING (web): Medication tier enum aligned with Android canonical
  model.** Web's medication tier values are now `skipped` / `essential` /
  `prescription` / `complete` (lowercase, 4 values), replacing the
  pre-v1.5.3 `SKIPPED` / `PARTIAL` / `COMPLETE` (uppercase, 3 values).
  This adds the essential vs prescription distinction (must-have meds
  vs prescribed-only meds) so cross-device sync now roundtrips without
  fidelity loss — Android's `AchievedTier` ladder is the authoritative
  spec. The `MedicationTierPicker` UI renders 4 buttons in canonical
  order (skipped → essential → prescription → complete). Firestore
  reads include a one-time normalization helper (in
  `web/src/api/firestore/medicationSlots.ts`) that folds legacy
  uppercase docs into the new enum (`SKIPPED` → `skipped`, `PARTIAL` →
  `essential` conservatively, `COMPLETE` → `complete`) and emits a
  `console.warn` so dev cleanup can be tracked; the helper is removable
  in v1.6.0+ once no legacy docs remain. Pre-existing web tier values
  in any account are normalized on read with a console warning during
  the dev cleanup window.

### Fixed

- **Onboarding completion now uses a single canonical Firestore path
  (`users/{uid}.onboardingCompletedAt`) on both platforms.** Previously
  Android wrote to `users/{uid}/prefs/onboarding_prefs.has_completed_onboarding`
  (via `GenericPreferenceSyncService`) and web wrote to
  `users/{uid}.onboardingCompletedAt` (top-level field on the user doc) —
  completing on one platform did not satisfy the other's check, so a user
  who finished onboarding on web still saw the Android tutorial on first
  launch (and vice versa). Android now reads + writes the canonical web
  path: a new `CanonicalOnboardingSync` Firestore helper writes
  `users/{uid}.onboardingCompletedAt` whenever
  `OnboardingViewModel.completeOnboarding` or the existing-user skip path
  fires, and `MainActivity` hydrates that field into the local DataStore
  mirror once per signed-in session via the new
  `OnboardingPreferences.hydrateFromCanonicalCloud(...)` helper. The
  hydrate is one-way and idempotent: it never overwrites a fresher local
  completion, and it ignores `null`/zero/negative canonical timestamps so
  a Firestore outage cannot flip a real "completed" flag back to
  "pending". Existing per-account `onboarding_prefs` writes from
  `GenericPreferenceSyncService` are kept unchanged so cross-Android sync
  also continues to work; the canonical field is purely additive.
- **Web Settings: removed two fake destructive-action buttons (Phase F
  parity).** The previous "Change Password" button (Settings → Account)
  opened a modal whose submit handler unconditionally toasted "Password
  changed" without ever calling Firebase Auth — Google-sign-in users
  manage their password at `myaccount.google.com`, so the modal was
  removed entirely. The previous "Delete All Data" button (Settings →
  Data) toasted "All data deleted" without writing anything; it is
  removed in favour of pointing users to the existing "Delete Account"
  flow which actually performs the deletion (PR #783). Misleading
  destructive UX fixed; PR-5 of the parity bundle (audit PR #836,
  Surfaces 7 + 9). Bundled into this PR because the affected sections
  in `SettingsScreen.tsx` overlap with the AI opt-out section's
  insertion point below.

- **Web AI features opt-out toggle + Anthropic egress disclosure (Phase F
  privacy parity, P0).** Web previously had no UI, no setting, and no
  client-side gate for the master AI-features opt-out that ships on
  Android (`UserPreferencesDataStore.KEY_AI_FEATURES_ENABLED` +
  `AiSection.kt` disclosure + `AiFeatureGateInterceptor` 451 short-circuit
  on `/ai/*`, `/tasks/parse`, `/syllabus/parse`). Disabling AI on Android
  did not stop the web client's nine `/ai/*` callsites (Eisenhower,
  Pomodoro plan, time-block, weekly review, daily briefing, weekly plan,
  extract-from-text, pomodoro coaching, eisenhower classify-text) from
  calling Anthropic via the backend. This PR closes the gap: a new
  Settings → AI Features section mirrors the Android disclosure copy
  verbatim and binds a toggle to a new `aiFeaturesEnabled` flag in the
  zustand settings store (default `true`, matching Android's opt-out
  semantics). The flag round-trips to Firestore at
  `users/{uid}/prefs/user_prefs.ai_features_enabled` with the
  `__pref_types: { ai_features_enabled: "bool" }` type tag that
  Android's `GenericPreferenceSyncService` pull side expects, so toggling
  on either client propagates to the other on next pull. A new request
  interceptor in `web/src/api/client.ts` rejects any request to the same
  three path prefixes Android gates with a synthetic 451 (an
  `AxiosError` carrying `response.status = 451` and the
  `X-PrismTask-AI-Features: disabled` header — same shape Android's
  OkHttp interceptor returns). The response interceptor surfaces a
  one-line "AI features are disabled. Re-enable them in Settings → AI
  Features." toast for that status. Coverage: 21 new vitest specs across
  the gate (path matching, query-string stripping, prefix-substring
  defense, header stamping, toast wiring), the Firestore round-trip
  (read default, read persisted, write payload shape, type-tag
  presence, last-write-wins timestamp, end-to-end round-trip), and the
  store integration (default-on, optimistic toggle, signed-in vs
  signed-out push behaviour, push-failure tolerance, Firestore-load
  side, full round-trip).
- **Web habit daily streak now uses forgiveness-first semantics.**
  Mirrors Android `DailyForgivenessStreakCore` (one missed day inside
  a rolling 7-day window is forgiven; two missed days — or any miss
  with the grace already spent — terminates the run). Previously
  `web/src/utils/streaks.ts` was strict-consecutive (a single missed
  day broke the streak), so the same habit displayed different streaks
  on phone vs web. New `ForgivenessConfig` interface mirrors Android's
  data class shape; default config matches Android's defaults. 30
  vitest cases cover the threshold edges, active-day rewind, SoD-aware
  day boundary, DST. Tier B Phase F parity (audit PR #836, gap H-F1).
- **Web sign-in now shows a RestorePending takeover when the user has
  a pending account deletion (parity with Android `AuthScreen`
  `RestorePending` state).** Previously a deletion-pending user could
  silently overwrite the deletion mark by web-signing-in: the web
  `signInWithGoogle` flow never called `getDeletionStatus` after the
  Firebase + JWT exchange, so the next sync re-established the user as
  active and erased the grace-window state initiated from Android.
  `authStore` now refreshes the deletion status (via the existing
  `authApi.getDeletionStatus` call, ordered before `fetchUser`) on every
  Google sign-in, every legacy email/password login, and every
  Firebase-auth-state-change re-hydration. A new
  `routes/RestorePendingGate` (sits between `ProtectedRoute` and
  `OnboardingGate`) takes over the entire authed route tree with a
  full-screen `features/auth/RestorePendingScreen` whenever the gate
  resolves to `'pending'` — `Restore Account` calls
  `authApi.cancelAccountDeletion` and flips the gate to `'active'`;
  `Sign Out` abandons the restore (deletion proceeds) and routes back
  to `/login`. Fail-closed if the deletion check throws: the gate stays
  on its splash rather than letting the user leak to the AppShell. Tier
  A Phase F parity, audit PR #836; gap from § Surface 7.
- **Web `tasks.ts` no longer clobbers Android-only task fields on edit
  (`dueTime`, `isFlagged`, `lifeCategory`, `eisenhowerReason`,
  `userOverrodeQuadrant`, Focus-Release fields, `archived_at`,
  `source_habit_id`).** Round-trip data-loss bug fixed: previously every
  call to `updateTask` rebuilt the full Firestore document and every
  `createTask` hardcoded `dueTime: null`, `isFlagged: false`,
  `lifeCategory: null`, etc., which silently destroyed any state set on
  Android (auto-classified life category, manually-pinned Eisenhower
  quadrant, flag, parsed due-time, focus-release counters, etc.) on the
  next web edit. `taskUpdateToDoc` now switches to merge-on-write — only
  fields the caller actually changed are emitted, so unmentioned columns
  stay as Android wrote them. `taskCreateToDoc` similarly omits Android-
  only fields when the web user didn't supply them. The Quick Create
  input now feeds typed text through `parseQuickAdd` so "Standup at 9am"
  correctly populates `due_time` instead of dropping it (PR-1 of the
  joint Q-F3+T-S2 fix). The Task Editor adds a Life Category picker on
  the Organize tab (work/personal/self-care/health/uncategorized) so the
  Work-Life Balance dashboard is finally reachable from web. The
  Eisenhower drag-drop handler now sets `userOverrodeQuadrant: true` on
  manual moves so Android's auto-classifier doesn't undo the user's
  choice on the next sync. Tier A Phase F parity, audit PR #836; gaps
  T-S1/2/3, T-F1/2/3 from § Surface 3.
- **Web `habits.ts` no longer clobbers Android-only habit fields on
  edit (booking, built-in identity, today-skip, nag-suppression,
  multi-reminder cadence). Habit completions now write
  `completedDateLocal` (timezone-neutral day key from Android v50) so
  cross-device DST drift is fixed.** `web/src/api/firestore/habits.ts`
  previously hardcoded `isBookable: false`, `isBooked: false`,
  `bookedDate: null`, `bookedNote: null`, `trackBooking: false`,
  `trackPreviousPeriod: false`, `hasLogging: false`,
  `reminderTimesPerDay: 1`, and `reminderIntervalMillis: null` on every
  `createHabit` call, and silently omitted Android-only fields like
  `showStreak`, `nagSuppressionOverrideEnabled`,
  `nagSuppressionDaysOverride`, `todaySkipAfterCompleteDays`,
  `todaySkipBeforeScheduleDays`, `isBuiltIn`, `templateKey`,
  `sourceVersion`, `isUserModified`, and `isDetachedFromTemplate` —
  meaning a habit created on Android with booking enabled, or a
  built-in habit row, would have its identity and toggle state
  destroyed by the next web-side round-trip. `habitCreateToDoc` now
  emits only the ~10 fields the web user actually owns; merge-only
  semantics on `updateHabit` (which already used `if (… !== undefined)`
  guards) are preserved. Separately, `toggleCompletion`'s addDoc
  payload now writes `completedDateLocal: <YYYY-MM-DD>` derived from
  the caller's `useLogicalToday(startOfDayHour)` value, matching the
  field Android added in Room migration 49→50 for timezone-neutral
  day comparisons; without it, a completion logged on web at
  23:55 local on a DST spring-forward day could decompose to a
  different calendar date when read on Android in a different
  timezone. `docToCompletion` reciprocally prefers
  `completedDateLocal` when present and falls back to the legacy
  epoch only for pre-v50 docs. Tier A+B Phase F parity, audit
  PR #836; gaps H-S2, H-S4 from § Surface 2.

- **Web medication-reminder-mode settings banner copy corrected.**
  Settings → Medication Reminder Mode previously claimed "Settings sync
  to Firestore so your phone picks them up," but Android's
  `MedicationReminderModeResolver` only reads the local
  `MedicationPreferences` DataStore and Room
  `medication_slots.reminder_mode` — there is no path that reads the
  web-side `users/{uid}/medication_preferences/global` doc, so the
  preference round-trips web↔web only. The banner now accurately states
  the setting is device-local today and that cross-device sync to the
  phone will arrive with Web Push (Phase G). Copy-only change; no
  behavior change on either platform.

- **Web now wires Firestore real-time listeners from `App.tsx` for
  tasks, habits, projects, tags, medication slots, and medication
  preferences.** Previously the seven `subscribeTo*` functions in
  `web/src/api/firestore/*.ts` (`subscribeToTasks`,
  `subscribeToProjects`, `subscribeToTags`, `subscribeToHabits`,
  `subscribeToCompletions`, `subscribeToSlotDefs`,
  `subscribeToReminderModePreferences`) were defined and exposed via
  store wrappers but never called from any component or `App.tsx`,
  which meant cross-device updates required a manual page refresh. A
  new `useFirestoreSync(uid)` hook keyed on the Firebase UID kicks
  every subscriber off when the user signs in and cleanly invokes the
  returned unsubscribers when the UID flips to `null` (sign-out) or on
  unmount. Two thin Zustand stores
  (`medicationSlotsStore`, `medicationPreferencesStore`) cache the live
  Firestore data for the medication surfaces — the existing imperative
  reads in `MedicationReminderModeSection` and `MedicationScreen` still
  work, the stores are additive and back the live-sync path. Conflict
  resolution at apply time is intentionally last-write-wins (Firestore
  is source of truth on web); `cloud_id` dedup and LWW-timestamp guards
  remain tracked separately as G.0 follow-ups. Surfaced by the Tier B
  Phase F parity audit (PR #836, § Surface 6).

- **Medication screen day boundary now respects Start-of-Day on Android +
  web.** `MedicationViewModel.todayDate` (Android) and the four
  `const todayIso = logicalToday(Date.now(), startOfDayHour)` web sites
  (`MedicationScreen`, `TodayScreen`, `MoodLogModal`, `MorningCheckInCard`)
  previously snapshotted the logical date at flow / component
  construction time and never refreshed when the wall-clock crossed the
  user's configured SoD boundary. Symptoms on the medication surface
  (the bug report): doses logged before SoD reset would linger on
  today's slot cards as if they were today's marks; new doses after SoD
  would land under yesterday's `medication_tier_states.log_date` because
  five VM write paths (`setSkippedForSlot`, `setIntendedTimeForSlot`,
  `clearUserOverrideForSlot`, `refreshTierState`, `bulkMarkInternal`)
  read the stale `todayDate.value`; the screen would briefly empty out
  at calendar midnight (instead of at SoD) on every fresh subscription
  because the `stateIn` initial value hard-coded SoD = 0. Three other
  web surfaces shared the broken pattern latently and were swept in to
  prevent the same shape shipping on May 15. The fix introduces a
  shared `core.time.LocalDateFlow` (Android, Hilt-singleton) +
  `useLogicalToday` React hook (web) — both wire the SoD source to a
  wall-clock ticker that re-emits at every logical-day boundary.
  `MedicationStatusUseCase.observeDueDosesToday()` is fixed by the same
  intervention, which propagates to the Today screen's Daily Essentials
  card via `DailyEssentialsUseCase`. Persisted dose timestamps were
  always correct (`MedicationRepository.logDose` re-derives
  `taken_date_local` from each dose's own `taken_at`); no migration.
  Audit + reproduction trail at
  `docs/audits/MEDICATION_SOD_BOUNDARY_AUDIT.md`.

- **Today screen day-rendering now SoD-boundary-aware (CRITICAL).**
  Migrated `TodayViewModel.dayStart` / `dayEnd` StateFlows and the
  morning-check-in banner `combine` from the legacy
  `util.DayBoundary.calendarMidnightOfCurrentDay(getDayStartHour())`
  snapshot pattern to `core.time.LocalDateFlow` from PR #798. Same
  failure shape PR #798 fixed for medication: at the user's configured
  SoD (e.g. 4 a.m.), the Today screen's overdue / today / planned
  filters are keyed off the canonical reactive logical date, so a user
  who keeps the app open across the SoD boundary sees their task
  buckets reset at SoD instead of staying locked to yesterday's day.
  Per `docs/audits/UTIL_DAYBOUNDARY_SWEEP_AUDIT.md` § 1 — first PROCEED
  caller of the 3 user-facing snapshot bugs the sweep audit identified.

- **Task list day-grouping now SoD-boundary-aware.**
  Migrated `TaskListViewModel.dayStartFlow` from the legacy
  `getDayStartHour().map { startOfCurrentDay(it) }.stateIn(...)`
  snapshot pattern to `core.time.LocalDateFlow`. Consumers
  (`overdueCount`, `groupByDate`'s "Today" / "Tomorrow" / "Day after"
  / "End of week" buckets) now key off a logical day that advances
  reactively at every SoD boundary. Per
  `docs/audits/UTIL_DAYBOUNDARY_SWEEP_AUDIT.md` § 3 — second of 3
  PROCEED migrations in the sweep.

- **Daily Essentials section now SoD-boundary-aware end-to-end.**
  Migrated `DailyEssentialsUseCase.observeToday()` from the
  four-snapshot `flatMapLatest` pattern (`todayStart`, `todayLocal`,
  `windowStart`, `windowEnd` all locked at upstream emission time) to
  `core.time.LocalDateFlow`. Completes the leaf-by-leaf migration
  PR #798 started — PR #798 fixed only the `medicationStatusUseCase`
  leaf inside the inner combine, leaving the four window epochs
  snapshot-stale and producing an inconsistent state past SoD
  (medication card correct, routine / housework / schoolwork / leisure
  cards still on yesterday's window). Per
  `docs/audits/UTIL_DAYBOUNDARY_SWEEP_AUDIT.md` § 5 — third of 3
  PROCEED migrations in the sweep.

- **Auto version-bump fires reliably on every PR merge.** Three
  silent-skip failure modes in the post-merge bump chain are closed:
  (1) PR #803 added a `commits/{sha}/pulls` fallback in
  `version-bump-and-distribute.yml` for cases where
  `github.event.workflow_run.pull_requests` lands empty (the common
  case for same-repo PRs going through `Auto-merge & Release`),
  (2) PR #805 added a "wait until all required-status checks have
  queued" step to `auto-merge.yml` so `wait-on-check-action` no
  longer exits prematurely on a fast subset of checks (the upstream
  race that, on PR #804, caused version-bump to fire 14 min before
  the actual merge and time out before the squash landed), and
  (3) PR #807 reordered version-bump so the bump + commit run BEFORE
  the keystore decode + signing + Firebase distribution — softening
  the keystore step from HARD-FAIL on missing `KEYSTORE_BASE64` to
  warn-and-skip, so a missing/rotated signing secret no longer
  freezes versionCode (signing + distribution remain best-effort and
  gate on the new `has_keystore` output).

## [1.6.0] — 2026-04-24

> The 1.6.0 entry captures work that landed across several tagged builds
> (`v1.5.0`, `v1.5.2`, `v1.5.3`, and the four 1.6.0 medication-reminder-mode
> PRs) but was never split into per-tag CHANGELOG sections at release time.
> Anchor entries for the intermediate tags point back up to the matching
> subsections below.

### Medication reminder mode — Web settings UI (PR4 of 4)

- **Settings → Medication Reminders** (web) gains a Clock / Interval
  picker + interval presets + custom-minutes field, mirroring Android.
- **Persistent banner**: "Reminder delivery is currently Android-only.
  Settings sync to Firestore so your phone picks them up. Web reminder
  delivery will arrive with Web Push in a future release."
- New Firestore client `web/src/api/firestore/medicationPreferences.ts`
  reads/writes `users/{uid}/medication_preferences/global` with the
  same camelCase keys Android consumes (`reminderModeDefault`,
  `reminderIntervalDefaultMinutes`).
- `MedicationSlotDef` extended with `reminder_mode` +
  `reminder_interval_minutes` so per-slot overrides round-trip through
  Firestore. Web slot editor will gain the per-slot picker UI in a
  follow-up — for now the override is settable from Android only and
  the web slot editor leaves existing values untouched.
- Vitest coverage: 6 cases for the medication preferences read/write
  path, including unknown-mode fallback and interval clamping.

### Medication reminder mode — Android UI (PR3 of 4)

- **Settings → Notifications** gains a "Medication Reminders" subsection
  with a Clock / Interval radio for the global default. When INTERVAL is
  picked the section reveals a presets row (2h / 4h / 6h / 8h) plus a
  custom-minutes field clamped to 60..1440. Saving rewires alarms via
  `MedicationIntervalRescheduler.rescheduleAll()` immediately.
- **Settings → Medication Slots → Slot editor** gains a per-slot
  "Reminder Mode: Default / Clock / Interval" picker plus the same
  interval picker (visible only when Interval is selected). Saving
  passes the resolved `reminder_mode` and `reminder_interval_minutes`
  through to `MedicationSlotsViewModel.create / update`.
- Resolved-mode hint text on every editor explains the cascade ("Uses
  the app default (Clock)", "Reminder fires at 09:00",
  "Reminder fires every 4h after the most recent dose").
- Per-medication overrides deferred to a follow-up — touches multiple
  pickers and dialogs and is independent of the slot+global UI.

### Medication reminder mode — reactive scheduler (PR2 of 4)

- **`MedicationReminderModeResolver`** — pure precedence function for the
  three-level chain (medication → slot → global). Unknown enum strings
  are treated as "inherit." Interval minutes always clamped to
  `[60, 1440]`; an INTERVAL resolution missing a value at every level
  falls back to the global default.
- **Synthetic-skip doses.** `MedicationViewModel.setSkippedForSlot` now
  inserts a `medication_doses` row per affected medication with
  `is_synthetic_skip=true` so the interval rescheduler re-anchors when
  the user explicitly skips a slot. `MedicationLogViewModel` filters
  synthetic rows out of the log UI — they exist only as scheduling
  anchors.
- **`MedicationRepository.logSyntheticSkipDose`** — repository surface
  used by SKIPPED. Sync-tracked like a normal dose so the anchor flows
  to other devices.
- **`MedicationIntervalRescheduler`** — owns AlarmManager registrations
  for INTERVAL-mode slots and per-medication INTERVAL overrides. Uses
  request-code namespace `+500_000` (slots) / `+600_000` (med
  overrides), distinct from the existing `+400_000` per-medication
  scheduler (which still owns CLOCK-mode alarms unchanged). Walks all
  active slots + active medications, resolves mode, computes next
  trigger as `anchor.takenAt + intervalMinutes` (clamped to `now + 1s`),
  cancels prior alarms, enqueues fresh exact alarms via
  `ExactAlarmHelper`. Bootstrap: with no doses yet, fires one interval
  from now.
- **Reactive Flow observer.** `MedicationIntervalRescheduler.start()`
  observes `MedicationDoseDao.observeMostRecentDoseAny()` and runs
  `rescheduleAll()` on every emission. Wired from
  `PrismTaskApplication.onCreate()`. `BootReceiver` also calls
  `rescheduleAll()` on `BOOT_COMPLETED`.

### Medication reminder mode — schema (PR1 of 4)

- **DB v60 → v61.** Adds `reminder_mode` (TEXT, nullable) and
  `reminder_interval_minutes` (INTEGER, nullable) to `medication_slots`
  and `medications`, and `is_synthetic_skip` (INTEGER NOT NULL DEFAULT 0)
  to `medication_doses`. NULL `reminder_mode` means "inherit the next
  level down" — medication NULL → slot NULL → global default (CLOCK,
  stored in `UserPreferencesDataStore`). The resolver lives in PR2.
- **`UserPreferencesDataStore`** gains `medicationReminderModeFlow` +
  `setMedicationReminderMode()` for the global default (mode + interval
  minutes, clamped 60..1440). Stored in DataStore, not Room.
- **DAOs.** `MedicationDoseDao` adds `getMostRecentDoseAnyOnce()` /
  `observeMostRecentDoseAny()` (interval-mode anchor, includes synthetic
  skips) and `getMostRecentRealDoseOnce()` (UI dose history, excludes
  synthetics). `MedicationSlotDao.getIntervalModeSlotsOnce()` and
  `MedicationDao.getIntervalModeMedicationsOnce()` enumerate explicit
  INTERVAL-mode rows for the reactive rescheduler in PR2.
- **Sync.** `MedicationSyncMapper` round-trips the new fields on slots,
  medications, and doses so cross-device sync of reminder-mode settings
  and synthetic-skip anchors works.
- No UI surface yet — every existing user keeps the CLOCK default and
  prior reminder behavior. Settings UI lands in PR3.

### Fixed

- **CI: `cross-device-tests` Firestore emulator routing under
  instrumented tests.** PR #780 unmasked an underlying
  `PERMISSION_DENIED` from production Firestore: the deviceA Firestore
  client (the standard `FirebaseFirestore.getInstance()` that
  `SyncTestHarness.deviceAFirestore` aliases) was talking to production
  Google Cloud, not the local Firebase Emulator Suite. Cause:
  `HiltTestRunner.newApplication` substitutes `HiltTestApplication` for
  the production `PrismTaskApplication`, so the production code's
  `configureFirebaseEmulator()` hook never fires under instrumented
  tests. `BuildConfig.USE_FIREBASE_EMULATOR=true` was set but unused.
  Replicate the production routing logic in `HiltTestRunner.onStart()`
  — same `useEmulator(host, port)` calls on the default
  `FirebaseFirestore` and `FirebaseAuth`, gated on the same
  `BuildConfig.USE_FIREBASE_EMULATOR` flag, with `10.0.2.2` host
  selection inside the Android emulator. Also corrects the misleading
  comment in `SyncTestHarness.kt` that claimed `PrismTaskApplication`
  did the routing.

- **CI: `cross-device-tests` script-execution model.**
  `reactivecircus/android-emulator-runner@v2` runs each line of its
  `script:` input as a fresh `sh -c "$line"`, which means any multi-line
  shell construct (function, `if` block, heredoc) fails to parse: the
  opening `run_cross_device_tests() {` died with
  "Syntax error: end of file unexpected (expecting `}`)" because the
  closing `}` lived on the next `sh -c`. The retry-once installed by
  PR #776 never actually fired. Result: the job failed 3/3 main runs
  (24938962202 / 24937612065 / 24926119046) since it landed in PR #773.
  The runner is now written to `$RUNNER_TEMP/run-cross-device-tests.sh`
  in a preceding workflow step and invoked as a single
  `bash "$RUNNER_TEMP/run-cross-device-tests.sh"` line — bash parses the
  file as one unit, so the function and `||` retry-once work. Required-
  status promotion is gated on five consecutive green main runs of the
  job per `docs/audits/PHASE_D_BUNDLE_AUDIT.md`.

- **BREAKING (data integrity): SyncService.pushUpdate no longer silently
  re-creates deleted Firestore docs.** Concurrent delete-then-edit now
  resolves delete-wins per spec (was edit-wins). `pushUpdate` was calling
  `docRef.set(data).await()` — bare `set` with no merge and no precheck —
  so an offline device's queued update of a row another device had since
  deleted would resurrect the doc on `.set()` (Firestore's `set()` on a
  non-existent path silently creates). Users on two devices with flaky
  network were losing legitimate deletions without any visible signal.
  `pushUpdate` now calls `docRef.update(data).await()`, catches
  `FirebaseFirestoreException` with code `NOT_FOUND` /
  `FAILED_PRECONDITION`, and routes the orphan row through
  [SyncService.processRemoteDeletions] — hard-delete the local row and
  clear its `sync_metadata`. Sync Test 10 (`@Ignore` in PR #751) is now
  `@Test` and passes; CloudIdOrphanHealer's wipe-recovery behavior
  changes from silent re-creation to orphan cleanup (the re-creation
  path was riding the same bug and silently undoing other users'
  deletes — not the intended behavior). Explicit "restore from local
  snapshot" is a separate feature if ever needed.

### Test infrastructure

- **`BatchUndoLogDao.sweep` boundary regression tests.** Three new
  androidTest cases pin the strict-`<` semantics of both arms of the
  sweep predicate (`expires_at < :now` and `undone_at < :undoneCutoff`).
  `sweep_atExactExpiryBoundary_keepsRow` proves a row with
  `expiresAt == now` survives; `sweep_oneMillisPastExpiry_dropsRow`
  pins the regression that PR #707 fixed (an off-anchor mistake where
  the test's "expired" row was actually 86M ms in the future);
  `sweep_undoneAtExactCutoff_keepsRow` pins the same boundary on the
  undone-cutoff arm. Follow-up to #707 per
  `docs/audits/PHASE_D_BUNDLE_AUDIT.md` Item 2.

- **Sync test follow-ups cluster.** Three small follow-ups to the sync-test
  matrix shipped in PRs #741, #749, #750, #751, #753:
  - **Test 8 (multi-device streak sync) — flipped from `@Ignore` to
    `@Test`; sync scenarios automated count moves from 5/9 to 6/9.**
    Earlier "First CI attempt" failure mode (pull does not surface
    device B's `habit_completion` within 15 s) was a wrong test
    assertion, not a production bug. Audit confirmed: SyncMapper push
    and pull use identical field names (`localId`, `habitCloudId`,
    `completedDate`, `completedDateLocal`, `completedAt`, `notes`), and
    `SyncService.pullCollection` does a full
    `userCollection(name).get()` snapshot fetch with no cursor —
    Hypothesis A (field-shape mismatch) and Hypothesis B (cursor-skip
    on equal timestamps) are both ruled out. Real cause: the pull-path
    natural-key dedup at `SyncService.kt:1681-1693` collapses A's pull
    of B's same-day completion into a `sync_metadata` upsert (no second
    Room row inserted), so the test's `waitFor { completions.size == 2 }`
    could never converge. Test rewritten to assert the actual production
    contract: post-pull Room holds exactly one row, both cloud_ids map
    to that row, and streak reads 1.
  - **StreakCalculator clock-change unit tests added (5 cases).**
    Forward jump mid-day, forward jump across midnight, rollback to
    past, completion at exact day boundary (00:00:00), rapid clock
    toggle. Pure JVM tests — vary the `today: LocalDate` argument and
    the per-completion `completedDate` epoch ms to exercise each clock-
    drift scenario. Covers the unit-test slice that PR #751 carved out
    of Test 15's scope (Test 15 was punted at the sync layer because
    `DayBoundary.startOfCurrentDay(now)` already accepts an injectable
    `now: Long`; the remaining behavior to pin down is StreakCalculator
    determinism w.r.t. its `today` parameter, which doesn't need the
    Firebase emulator harness).
  - **CloudIdOrphanHealerTwoDeviceTest deleted as redundant to
    Test10ConcurrentDeleteTest.** The test's `simulatePushForPending`
    stub modeled the pre-fix `docRef.set()` "silently re-create" path
    that PR #753 removed; three of its four scenarios assert "5 docs
    re-created at the same cloud_ids", which is the OPPOSITE of post-
    fix `docRef.update()` semantics (NOT_FOUND → routes through
    `processRemoteDeletions` to hard-delete the orphan locally). The
    fourth scenario (`partialFirestoreWipe_healerOnlyTargetsMissingIds`)
    duplicates `CloudIdOrphanHealerScenarioTest.partialWipe_healsOnlyMissingRows`.
    Test 10 covers the post-fix push-update conflict semantics
    end-to-end against the live Firebase Emulator. Dangling KDoc
    references in `CloudIdOrphanHealerEmulatorTest` and
    `BuiltInTaskTemplateBackfillerTwoDeviceTest` retargeted /
    inlined.

- **Sync tests CI — Tests 12 & 13 manual runbook (PR3 of 3, closes
  sync-test matrix).** Docs-only addition
  `docs/SYNC_TESTS_12_13_MANUAL.md` with a step-by-step human-operated
  procedure for the two scenarios that can't be driven from adb:
  sign-out/sign-in same user (Test 12) and sign-in as different user
  (Test 13). Both depend on the Google OAuth Custom Tab flow — opaque
  to UIAutomator, same limitation Phase A's S1–S5 sign-in tests ran
  into. The runbook is ~15 minutes on two physical devices + two
  Google accounts, scheduled for every Phase C RC build and once
  during Phase B Wk 2. With this in place, the sync-test matrix
  coverage becomes: automated in CI for Tests 7, 11, 14 (live) and
  8, 9, 10, 15 (stubbed pending follow-up); manual runbook for 12, 13.

- **Sync tests CI — scenarios 7, 11, 14 automated (PR2 of 3).** New
  `SyncScenarioTestBase` wraps the harness in a `@HiltAndroidTest` with
  injected `PrismTaskDatabase`, `SyncService`, `AuthManager`, and the
  task/habit/project repositories so scenarios can drive the real
  production sync pipeline end-to-end against the Firebase Emulator
  Suite. Three live scenarios land this PR: **Test 7** (offline edit
  then reconnect — three mutations converge on push), **Test 11**
  (offline during remote write — local pulls device B's task on
  reconnect), and **Test 14** (rapid create/delete leaves no Firestore
  orphan, with a round-trip variant for create-push-delete-push). Tests
  8/9/10 (streak dedup, last-write-wins, delete-vs-edit conflict) land
  as `@Ignore`d stubs with implementation notes pending
  SyncMapper/SyncService deep-dive — per PR2's scope guardrail
  ("do not modify production sync code to make tests pass"), surfacing
  what those tests need rather than coding them blind.

- **Sync tests CI — two-process harness + smoke tests (PR1 of 3).** New
  `SyncTestHarness` in `app/src/androidTest/.../sync/` spins up a named
  `"deviceB"` `FirebaseApp` alongside the default (device A) so two
  independent Firestore/Auth clients point at the same Firebase Emulator
  Suite — rather than booting two AVDs on one `ubuntu-latest` runner
  (infeasible on memory budget). Both devices sign in as a fixed shared
  test user (`sync-tests@prismtask.test`), so their writes land under the
  same `users/{uid}/*` subtree — matching production's
  "same Google account, two phones" topology. Harness primitives:
  `signInBothDevicesAsSharedUser`, `setDeviceAOffline/Online` (via
  `firestore.disableNetwork` on A only, so B's writes stay unblocked),
  `writeAsDeviceB` / `deleteAsDeviceB`, `firestoreDoc` / `firestoreCount`,
  `waitFor` (poll-until-true with timeout), and `cleanupFirestoreUser`.
  Six `SyncTestHarnessSmokeTest` cases cover sign-in stability, B→A
  write visibility, A-offline orthogonality, `waitFor` happy path +
  timeout, and cleanup. Gated by `assumeTrue(BuildConfig.USE_FIREBASE_EMULATOR)`
  so default debug builds skip — runs only under `android-integration.yml`
  (PR #635). Lays the foundation for PR2's seven automated sync scenarios
  (tests 7, 8, 9, 10, 11, 14 + 15 if Clock injection lands).

### Repo hygiene

- Enabled branch protection on `main` via `scripts/setup-branch-protection.sh`
  (from PR #706). Required status checks: `lint-and-test` (Android CI),
  `connected-tests` (Android Integration CI), `test` (Backend CI), and
  `web-lint-and-test` (Web CI). `strict: true` enforces PRs up-to-date with
  `main` before merging; `required_linear_history: true` preserves the
  squash-merge history. Web CI's job was renamed from `lint-and-test` to
  `web-lint-and-test` so its status-check context does not collide with
  Android CI's (branch protection dedups shared context names into a single
  requirement).

### Web

High-leverage parity slices landed ahead of Phase G, targeting the
biggest capability gaps between the web client and Android v1.5.2
without any backend or Android-side changes. See
`docs/WEB_PARITY_GAP_ANALYSIS.md` for the full audit that picked these
and `docs/WEB_PARITY_PHASE_G_PROMPT_TEMPLATE.md` for the remaining
Phase G roadmap.

- **Focus Release + good-enough timer (slice 22)** — wellness
  cluster wrap-up. New `api/firestore/focusReleaseLogs.ts` persists
  per-session logs at `users/{uid}/focus_release_logs` (task id +
  title snapshot, planned + actual minutes, release state, note,
  started/ended). `utils/goodEnoughTimer.ts` is a pure helper: at
  80% of planned elapsed the "good enough" and "ship it" buttons
  unlock. New `/focus` route with a task picker, planned minutes
  input, a progress ring, Pause/Resume, and four release buttons
  (Ship it / Good enough / Save partial / Abandon) each logging a
  different `release_state`. A celebration banner shows the correct
  tone per release state (ship > good enough > partial > abandon,
  zero guilt). Recent sessions list with per-row state badge.
  Sidebar gains a Focus entry. 7 new unit tests for the timer
  helpers.

- **Boundaries + burnout scorer (slice 21)** — wellness cluster,
  continued. New `api/firestore/boundaryRules.ts` persists
  user-declared limits at `users/{uid}/boundary_rules` in three
  forms: daily task cap (warn/alert when active tasks cross), work
  hours window (info nudge outside the declared start/end hours
  — overnight windows supported), and a weekly hour budget used by
  the burnout scorer. `utils/boundaryEnforcer.ts` returns breach
  objects with severity; `utils/burnoutScorer.ts` rolls breaches
  plus recent mood/energy plus task overload into a 0–100 score
  bucketed calm/moderate/risky/burning. A new Boundaries section in
  Settings offers inline CRUD with per-rule enable toggles; a
  `BoundaryTodayBanner` on Today shows the active breaches + burnout
  bucket with color-coded severity, staying silent when everything's
  calm. 10 new unit tests cover the enforcer + scorer.

- **Morning check-in + forgiveness streak (slice 20)** — wellness
  cluster, continued. New `api/firestore/checkInLogs.ts` persists one
  log per logical day at `users/{uid}/check_in_logs` with the ISO
  date as the doc id (idempotent `setDoc(merge)` for re-submits).
  New `utils/checkInStreak.ts` computes streaks with Android's
  forgiveness-first semantics — a single missed day bends the streak,
  two consecutive misses break it. `MorningCheckInCard` pins on
  Today with streak badge, toggleable via a new
  `showMorningCheckIn` preference in Settings. The modal captures
  medications-confirmed / tasks-reviewed / habits-planned booleans
  plus free-form notes. 5 new streak unit tests.

- **Mood & energy tracking (slice 19)** — first wellness-cluster
  slice, Firestore-native. New `api/firestore/moodEnergyLogs.ts` +
  `utils/moodAnalytics.ts` (pure rollup + stats); logs persist at
  `users/{uid}/mood_energy_logs` with per-date ISO key and time-of-day
  bucket. New `/mood` route ships a 7/30/90-day trend view (Recharts
  mood area + energy line), summary tiles (total / avg mood / avg
  energy / up-down-stable trend), recent-entries list with delete,
  and a `MoodLogModal` for quick 1–5 mood + energy capture. Sidebar
  adds a Mood entry. 5 new unit tests cover the rollup + stats.

- **Custom habit + project template authoring (slice 18)** — closes
  the slice-11 deferral Firestore-natively. New
  `api/firestore/userTemplates.ts` provides full CRUD at
  `users/{uid}/habit_templates` and `users/{uid}/project_templates`,
  reusing the existing starter-library types so Use / apply flow is
  identical for both starter and custom templates. New
  `features/templates/UserTemplateEditors.tsx` ships two modals
  (HabitTemplateEditor with name / description / icon / color /
  frequency / target; ProjectTemplateEditor with name / description /
  icon / color + inline task-list builder). Habit and Project tabs
  on TemplateListScreen now show "Your templates" above the starter
  library, a "New … template" button in each tab, and Edit / Delete
  affordances on custom cards. Banner text updated to drop the
  "tracked for Phase G follow-up" caveat.

- **Medication tier picker + slot CRUD (slice 17)** — closes the
  medication deferrals from slice 10 without backend changes by
  going Firestore-native. New `api/firestore/medicationSlots.ts`
  provides CRUD for slot definitions
  (`users/{uid}/medication_slot_defs`) and read/write for daily tier
  states (`users/{uid}/medication_tier_states`, keyed
  deterministically as `${dateIso}__${slotKey}` for single-doc
  writes). On the MedicationScreen, each slot card now carries a
  three-way tier picker (SKIPPED / PARTIAL / COMPLETE) with an
  "Auto" clear button for user-set overrides. In Settings, a new
  "Medication Slots" section mounts `MedicationSlotEditor` — inline
  CRUD that lets users add, rename, and delete slot keys. Backend
  `/daily-essentials/*` completion flow is unchanged; slot
  definitions + tier states live alongside it.

- **Analytics project-progress via client-side compute (slice 16)** —
  unblocks the last analytics endpoint without the backend change.
  New `utils/projectBurndown.ts` mirrors
  `backend/app/services/analytics.py::compute_project_burndown`
  exactly but computes from Firestore `created_at` / `completed_at` /
  `status` fields. `ProjectProgressPanel` on `AnalyticsScreen` renders
  the result as a stacked Recharts chart (remaining line,
  completed-cumulative line, added-per-day bar) with a project picker
  dropdown, velocity + ETA readout, and an "on track / at risk"
  callout. Respects the dashboard's 7/30/90-day range selector. 5 new
  unit tests cover the compute (totals, per-day entries, on-track /
  at-risk branches, all-done case).

- **TAG_CHANGE batch mutation + tag persistence (slice 15)** — closes
  the remaining batch-parse deferral on web. `Task` type gains a
  `tag_ids: string[]` field; `firestore/tasks.ts` reads/writes a
  `tagIds` array on the task doc and exposes a new `setTagsForTask`
  helper. The batch applier's `TAG_CHANGE` branch resolves
  `tags_added` / `tags_removed` NAMES against the current tag store
  (auto-creating missing tags exactly the way Android's
  `applyTagDelta` does in `BatchOperationsRepository.kt`), computes
  the next `tagIds` set, persists via `setTagsForTask`, and snapshots
  the prior list for undo. The undo path restores the prior tag list.
  MEDICATION remains the only deferred branch (still matches Android's
  Option C).

- **Theme shape + decorative flags (slice 14)** — closes the last
  theme-parity gap with Android's `themesets/THEME_SPEC.md`. Every
  theme in `theme/themes.ts` now carries `radius / cardRadius /
  chipShape / density / glow / personality`. `applyThemeToDocument`
  writes `--prism-radius / card-radius / chip-radius / glow` and
  sets `data-personality` + `data-density` on `<html>`. New opt-in
  utility classes in `index.css`: `.prism-card` (per-theme card shape
  + glow + density-scaled padding), `.prism-chip` (sharp vs pill).
  Per-personality pseudo-element treatments: Cyberpunk renders corner
  brackets on `.prism-card`, Matrix prefixes `.prism-display` with a
  muted `>` terminal prompt, Void adds an editorial hairline above
  display headlines, Synthwave paints a tinted radial sunset behind
  `.prism-card`. Utilities are additive — existing components keep
  rendering untouched and migrate on their own schedule.

- **Theme typography (slice 13)** — per-theme fonts ported from
  `themesets/themes.js`. `theme/themes.ts` now carries `fontBody /
  fontDisplay / fontMono / displayUpper / displayTracking` for each
  of the four themes: Cyberpunk (Chakra Petch + Audiowide, uppercase,
  0.06em), Synthwave (Rajdhani + Monoton, uppercase, 0.08em), Matrix
  (Share Tech Mono + VT323, 0.02em), Void (Space Grotesk + Fraunces,
  -0.02em). `applyThemeToDocument` writes them as `--prism-font-body /
  display / mono` + `--prism-display-upper / tracking`. `index.css`
  body uses the body variable (falls back to system stack),
  `code/kbd/pre/samp` uses the mono variable, and a new `.prism-display`
  utility class applies the theme's display font, letter-spacing, and
  uppercase setting — drop it on any hero headline to opt in.
  `index.html` loads the 8 font families from Google Fonts with
  `display=swap` + `preconnect`.

- **Settings sections bundle (slice 12)** — four new Settings sections
  ported from Android to close the visible gap with `settings/sections/`:
  (1) **Accessibility** — font-scale slider (0.9× / 1.0× / 1.1× / 1.25×),
  explicit High-Contrast toggle, explicit Reduced-Motion toggle, all
  persisted per-device via a new `a11yStore` and applied as
  `--font-scale` + root classes; (2) **Help & Feedback** — pre-filled
  links to GitHub bug + feature issues, support email, keyboard-shortcut
  docs; (3) **Maintenance** — "Replay onboarding" (clears the Firestore
  completion flag so the wizard reappears) and "Clear batch history"
  (wipes the 24h batch-undo log in this browser); (4) **About** —
  static version info + privacy / terms / source links. App.tsx applies
  the a11y state on first mount so users land with their chosen settings
  already in effect.

- **Templates parity — habits + projects (slice 11)** — `TemplateListScreen`
  gains a tabbed UI (Tasks / Habits / Projects). The new Habits and
  Projects tabs ship a curated starter library (6 habits, 4 project
  blueprints) defined client-side in `starterLibrary.ts`; tapping
  "Use" creates a live habit or project-with-tasks on Firestore
  directly, refreshing the relevant store on success. Banners in each
  tab call out that custom habit/project template authoring needs
  backend endpoints and is tracked for Phase G follow-up.

- **Medication screen (slice 10)** — new `/medication` route with a
  dedicated per-day view of all slots (card layout + prev/today/next
  day navigation + taken-count summary), using the same
  `/daily-essentials/slots` endpoints the Today row already consumes.
  Respects the slice-9 Start-of-Day hour so late-night users see the
  correct logical day. The per-slot detail modal is reused from Today.
  Sidebar adds **Medication** under Extract.
  **Not included:** per-slot tier picker (SKIPPED/PARTIAL/COMPLETE) and
  slot CRUD — both require backend changes to the
  `/daily-essentials/*` surface that are out of scope for the web
  parity push. The screen shows an empty-state hint when no slots
  exist yet.

- **Today polish (slice 9)** — three additions to the Today surface
  and matching Settings controls: (1) new `utils/dayBoundary.ts`
  utility (start/end/today ISO helpers) matching Android's
  `DayBoundary` semantics, backed by a new `startOfDayHour` preference
  (0–23, default 0) in `settingsStore` so the logical day can roll over
  at a configurable hour; (2) a "Today's Briefing" teaser card pinned
  at the top of Today that deep-links to `/briefing`, toggleable via a
  new `showBriefingCard` preference; (3) the Today header now reads its
  date label from the logical-today ISO so late-night users see the
  correct "today". Settings → Today View section gains a Start-of-Day
  hour slider and the Briefing Card toggle. 5 new vitest cases cover
  the dayBoundary helpers.

- **Task editor tabbed parity (slice 8)** — fills out the previously
  shallow Schedule tab on `TaskEditor`. Adds: wired weekday selector
  for weekly/biweekly recurrence, "biweekly" + "weekdays" types on the
  recurrence dropdown, an "after completion" flag so the next
  occurrence schedules from completion instead of due date (matching
  Android), an end-condition radio group (Never / After N occurrences
  / On date), a dedicated Planned Date input so users can park a task
  on the Today screen for a specific day independent of the due date,
  and a wired Reminder offset dropdown (persisted locally — with a
  note that web notifications are not cross-device yet). All changes
  flow through the existing debounced `autoSave` so edits land via
  one consolidated `recurrence_json` blob per change.

- **Eisenhower classify_text (slice 7)** — wires
  `POST /ai/eisenhower/classify_text`, the text-only variant of the
  Eisenhower endpoint that classifies a draft task (title, optional
  description, due date, priority) without needing a persisted
  Firestore ID. Surfaces as a "Classify Text" button on
  `EisenhowerScreen` opening a small modal that renders the returned
  quadrant (Q1–Q4) with its reason. Pro-gated. Closes the last unwired
  primary AI endpoint on web.

- **Pomodoro+ coaching (slice 6)** — wires `POST /ai/pomodoro-coaching`
  as a self-contained `PomodoroCoachPanel` that mounts on
  `PomodoroScreen`. One component covers all three backend triggers:
  `pre_session` (pre-session focus prompt), `break_activity` (stretch /
  breath suggestions during breaks), `session_recap` (one-line debrief
  after a block). Trigger is inferred from the existing
  `SessionPhase` state so the existing planning/work/break/done flow
  stays untouched — the panel just reads phase + the task arrays it
  needs for each trigger. Pro-gated; FREE users see the existing
  `ProUpgradeModal`. Message is dismissable and can be regenerated.

- **Conversation Extraction (slice 5)** — new `/extract` route wires
  `POST /ai/tasks/extract-from-text`. Paste a chat transcript, meeting
  note, or email (up to 10k chars), and the AI returns proposed task
  candidates with suggested titles, due dates, priorities, projects,
  and confidence scores. The UI renders each as a toggleable row (Apply
  / Skip) with metadata pills and a per-row confidence %. Approved
  candidates are committed directly to Firestore via
  `firestoreTasks.createTask`, using a loose project-name match against
  the user's existing projects (falls back to the first project so
  tasks always land somewhere). Pro-gated; empty-response case surfaces
  a gentle info toast. Sidebar gains an **Extract** entry in the AI arc.

- **Analytics Dashboard (slice 4)** — new `/analytics` route wires four
  of the five backend `/analytics/*` endpoints: `summary`,
  `productivity-score`, `time-tracking`, and `habit-correlations`.
  Renders a summary tile row (Today / This week / Streak / Habits),
  a daily productivity area chart (Recharts, 7/30/90-day range
  selector), a time-tracking bar chart (estimated vs. actual, per-bar
  accuracy coloring, group-by project / tag / priority / day toggle),
  and a habit-correlations list with per-habit direction badges plus an
  AI recommendation callout. Pro-gated; FREE users see an upgrade
  card. Uses `Promise.allSettled` so a single failing endpoint yields
  partial results rather than wiping the whole dashboard.
  **Not wired:** `/analytics/project-progress` — the backend expects
  an integer Postgres project_id, but web projects live in Firestore
  with string doc IDs. Wiring it cleanly needs a backend change to
  accept Firestore IDs; out of scope for this slice.

- **Daily Briefing + Weekly Planner (slice 3)** — wires two previously
  unwired AI endpoints: `POST /ai/daily-briefing` and
  `POST /ai/weekly-plan`. New `/briefing` route renders greeting, top
  priorities with reasons, heads-up items, suggested order with
  times, habit reminders, and a light/moderate/heavy day-type badge.
  New `/planner` route renders a per-day plan (task, suggested time,
  duration, reason, habits list, hours total), an unscheduled bucket
  with reasons, a week summary, and AI tips. Planner ships with a
  Preferences drawer (work days, focus hours/day, front-loading flag)
  matching the backend `WeeklyPlanPreferences` shape. Both screens are
  Pro-gated via `useProFeature`, surface the existing `ProUpgradeModal`
  for FREE users, and treat the backend's 429 rate-limit response
  (weekly plan is capped at one call per 30 minutes per user) with a
  human-readable toast. Sidebar gains **Briefing** and **Planner**
  entries in the AI arc.

- **NLP batch ops ([PR #711](https://github.com/akarlin3/prismTask/pull/711))** —
  the quick-add bar now detects batch-style commands ("reschedule all
  overdue tasks to tomorrow") using the two-signal heuristic Android
  ships in `BatchIntentDetector.kt`. Matches route to a new
  `/batch/preview` screen that calls `POST /ai/batch-parse`, renders the
  diff preview with per-row Apply / Skip toggles, and commits approved
  mutations directly to Firestore with a per-batch undo log. A 30-second
  Sonner toast offers immediate undo; Settings → Recent Batch Commands
  keeps the same batches undoable for 24h (matches Android's
  `UNDO_WINDOW_MILLIS`). Covered mutations: TASK RESCHEDULE / DELETE /
  COMPLETE / PRIORITY_CHANGE / PROJECT_MOVE, HABIT COMPLETE / SKIP /
  ARCHIVE / DELETE, PROJECT ARCHIVE / DELETE. TAG_CHANGE + MEDICATION
  are surfaced but skipped at apply time, matching Android's deferred
  branches.
- **Onboarding + four named themes ([PR #712](https://github.com/akarlin3/prismTask/pull/712))** —
  replaces the pre-parity light/dark + 12-accent-color picker with the
  four shipped themes (`CYBERPUNK`, `SYNTHWAVE`, `MATRIX`, `VOID`). Color
  tokens ported directly from `themesets/themes.js` into
  `web/src/theme/themes.ts` and applied via CSS custom properties + a
  `data-theme` attribute on the root. Existing users migrate
  automatically on first load — legacy accent hex maps to the closest
  named theme, defaulting to VOID. Typography, shape, density, and
  decorative treatments (brackets / terminal / editorial / sunset) are
  deferred to Phase G. Also lands a 9-page onboarding wizard at
  `/onboarding` mirroring Android's page order; completion persists per
  account at `users/{uid}.onboardingCompletedAt` in Firestore so the
  flow shows once per account across devices, gated via a new
  `OnboardingGate`.

### Medication slot system — MedicationScreen rewire (A2 #6 PR3 + A2 #7) — closes A2

- **Main MedicationScreen rewired** from the legacy `SelfCareStepEntity`
  + `self_care_logs.tiers_by_time` JSON path to the v1.5 data model
  (`medications` + `medication_slots` + `medication_tier_states` +
  `medication_doses`). Layout shows one card per active slot, grouped
  for today's date, with auto-computed achieved tier per slot and
  per-medication toggles inside each card.
- **Tap-to-override**: tapping a slot's tier chip drops the achieved
  value to `SKIPPED` and records the row with `tier_source = user_set`.
  Tapping again clears the override and the tier returns to
  auto-compute from today's doses. `USER_SET` rows stick through
  dose changes — a user's explicit skip cannot be auto-upgraded to
  COMPLETE by marking meds.
- **New `MedicationViewModel`** operates purely on
  `MedicationRepository` + `MedicationSlotRepository`. Reactive
  `StateFlow` of `MedicationSlotTodayState` (slot + linked meds +
  `takenMedicationIds` + achieved tier + `isUserSet`) is built by
  combining the active slots, medications, today's doses, and today's
  tier-state rows. The old SelfCareRepository dependency is removed.
- **New `MedicationEditorDialog`** replaces the legacy MedDialog. Uses
  `MedicationTierRadio` (from PR2) for the tier selector, wires the
  new `MedicationSlotPicker` (from PR2) for slot linkage + per-slot
  overrides, and persists to `medications` + junction + overrides on
  save. Empty-state hints guide the user to Settings → Medication
  Slots when no slots exist.
- **Removed**: legacy `MedicationComponents.kt` (`MedDialog`,
  `EditableMedItem`, `MedItem`, `TimePickerDialog`, `formatTime24to12`)
  — the rewired MedicationScreen has no callers left. `HabitListViewModel`
  and `SelfCareRepository` still read `tiers_by_time` for cross-domain
  self-care UI; that column and data stay intact (quarantine pattern).
- **No dual-write to `tiers_by_time`** from the medication path.
  Evaluated and rejected: a dual-write retirement protocol (F.0 §3.4
  referenced in the original plan) is not present in the repo and would
  add risk without a clear consumer. Since the only cross-domain
  consumer (`HabitListViewModel.kt:246`) reads its own self-care log
  rows (not medication-routine rows), medication-tier writes skipping
  `tiers_by_time` has no observable effect outside the medication UI.
- **A2 #6 and A2 #7 closed.** The medication slot system is fully
  shipped end-to-end: schema + backfill (PR1), Settings editor +
  reusable composables (PR2), main screen rewire + dialog replacement
  (this PR).

### Medication slot system — slot editor + reusable pickers (A2 #6 PR2)

- **New Settings screen** `Settings → Tasks & Habits → Medication Slots`
  (route: `settings/medication_slots`). Lists every slot the user has
  created, lets them rename / re-time / change drift, reorder via up/down
  buttons (swapping `sort_order`), soft-delete with confirmation, and
  restore previously deleted slots. Historical tier-state history is
  always preserved — soft-delete flips `is_active = 0`, nothing more.
- **`MedicationSlotsViewModel`**: Hilt-injected, exposes `allSlots`
  `StateFlow` and proxies CRUD to `MedicationSlotRepository`.
- **`MedicationSlotEditorSheet`**: inline create / edit dialog shared by
  the "new slot" and "edit slot" paths. Drift presets cover ±30 / ±60 /
  ±120 / ±180 min plus a custom numeric field; `HH:mm` input is
  character-filtered + length-capped to keep typing smooth without
  strict mid-edit rejection.
- **`MedicationTierRadio`**: reusable Composable for the three-tier
  radio (ESSENTIAL / PRESCRIPTION / COMPLETE) with inline helper text
  explaining each option. Written against the PR1 `MedicationTier` enum
  so it's wire-compatible with the rewire in PR3.
- **`MedicationSlotPicker` + `MedicationSlotSelection`**: reusable
  Composable for picking slots during the medication create / edit flow.
  Each selected slot optionally exposes an inline "Use different time for
  this med" toggle that edits the override fields in place. Purely
  controlled — the parent owns selection state and persists via the
  repository helpers added in PR1.
- **MedDialog / MedicationScreen unchanged**. The new pickers are
  shipped as standalone composables; wiring them into the create / edit
  flow is part of PR3 (which swaps the screen's underlying storage from
  `SelfCareStepEntity` to `MedicationEntity`, the shape that owns the
  slot junction + tier column). This keeps the PR2 diff free of the
  MedDialog rewrite and lets users exercise the slot editor immediately.
- **ProFeatureGate audit**: no new gates. Slots are free — matches the
  existing medication feature tier and Checkpoint 1 §3.5 decision.

### Medication slot system — schema + backfill (A2 #6 PR1)

- **New data model**: `medication_slots` (user-defined time slots),
  `medication_slot_overrides` (per-medication time/drift overrides),
  `medication_medication_slots` (junction), and `medication_tier_states`
  (per-day achieved tier per `(medication, slot)` pair). Three new Room
  entities + one junction cross-ref entity added to PrismTaskDatabase
  (now at version 60, rebased from a planned 57→58/58→59 chain after
  the NLP batch ops PR landed at 57→58 first). FKs CASCADE on both
  sides; unique indexes on `cloud_id` and on the override/tier-state
  identity tuples.
- **New enums**: `MedicationTier` (ESSENTIAL/PRESCRIPTION/COMPLETE),
  `AchievedTier` (SKIPPED + the three medication tiers — skipped only
  valid on achieved states), `TierSource` (COMPUTED/USER_SET). All
  stored as lowercase tokens to match the legacy `medications.tier`
  column data (no data migration required).
- **Migration 58→59**: creates the three slot-system tables, seeds one
  `Default` slot (ideal_time 09:00, ±180 min drift), and links every
  existing `medications` row to it via `INSERT OR IGNORE` (idempotent).
  No `user_id` column on `medication_slots` — Firestore document-path
  tenancy handles per-user isolation.
- **Migration 59→60**: creates `medication_tier_states` and backfills
  from the legacy `self_care_logs.tiers_by_time` JSON column into the
  DEFAULT slot. Highest tier present in each log's JSON wins
  (complete > prescription > essential > skipped). Legacy
  `tiers_by_time` column is preserved (quarantine pattern) until a
  later cleanup migration.
- **Pure auto-compute logic**: `MedicationTierComputer.computeAchievedTier()`
  walks the tier ladder bottom-up and returns the highest rung where
  every med at that rung or below in the slot has been marked taken.
- **Repository**: `MedicationSlotRepository` owns slot/override/junction/
  tier-state CRUD and notifies `SyncTracker` with the new entity types.
  Existing `MedicationRepository` is untouched.
- **Sync integration**: `MedicationSyncMapper` extended with push/pull
  mappers for the three synced families (junction rows are not synced
  directly — `medicationToMap` now embeds a `slotCloudIds` list that
  the pull path rebuilds the junction from, mirroring the `task_tags`
  pattern). `SyncService` push/pull dispatches extended; real-time
  listener registers the three new collections.
- **Orphan healer**: `CloudIdOrphanHealer` now covers 35 families (was
  30): added `medications` + `medication_doses` (pre-existing v1.4.37
  gap) alongside the three new slot-system families.
- **No UI changes**: the main `MedicationScreen` still reads from the
  legacy `self_care_logs.tiers_by_time` JSON path. Rewire lands in PR3
  (A2 #6 + A2 #7 closeout).
- **Tests**: unit tests for the three new enums, `MedicationTierComputer`
  (10 cases covering every ladder edge), sync-mapper round-trips for
  the three new families + `medicationToMap` slot-id embedding, two
  direct-SQL migration tests (58→59 schema + DEFAULT seed + re-run
  idempotency; 59→60 backfill + legacy-column preservation), plus three
  new `CloudIdOrphanHealerTest` cases for medication / slot families.

### NLP batch schedule operations — Settings history + 24hr sweep (A2 pulled-from-H PR3)

- **Settings → Batch Command History** screen lists every batch from
  the last 24 hours, newest first. Each card shows the original
  command text, the number of changes applied, when it ran (relative),
  and either an Undo button or "Undone X minutes ago" if already
  reversed. Tapping Details opens a per-entity detail dialog.
- **24hr durable undo**: the same `BatchOperationsRepository.undoBatch`
  path that powers the 30s post-Approve Snackbar (PR2) backs the
  Settings history Undo button. Undo decodes each entry's saved
  `pre_state_json` and reverses the mutation; partial failures are
  surfaced via Snackbar without aborting the rest.
- **Daily sweep worker** (`BatchUndoSweepWorker`) runs at ~03:00 local
  time via WorkManager. Drops rows where `expires_at < now AND
  undone_at IS NULL` (24h window lapsed) OR `undone_at < now - 7d`
  (already-undone tail window passed). Scheduled from
  `PrismTaskApplication.onCreate` with `ExistingPeriodicWorkPolicy.UPDATE`
  so re-launches are no-ops. No user toggle — pure maintenance.
- **Cross-device behavior**: device-local by design (no `cloud_id`
  on `batch_undo_log`). The mutated entities themselves still sync
  cross-device via the per-entity sync path; only the undo history
  stays on the device that ran the batch. Avoids races where two
  devices try to reverse the same batch simultaneously.



### NLP batch schedule operations — QuickAddBar + preview + snackbar undo (A2 pulled-from-H PR2)

- **QuickAddBar intent router**: a new `BatchIntentDetector` heuristic
  intercepts batch commands (`"Cancel everything Friday"`, `"Move all
  tasks tagged work to Monday"`) BEFORE the existing template / project
  / single-task paths. The detector requires two distinct signal
  categories (quantifier + time range, tag filter + bulk verb, etc.)
  so normal single-task entries like `"Buy milk tomorrow"` stay on
  the fast single-task NLP path.
- **BatchPreviewScreen**: full-screen diff view of the proposed
  mutations. Color-coded per mutation type (reschedule=amber,
  delete=red, complete=green, tag change=blue, etc.), per-row
  inclusion checkbox so the user can opt out individual changes
  before approving. Low-confidence banner (<0.7) and ambiguous-
  entity banner when Haiku flags a phrase it couldn't disambiguate.
- **Approve is transactional**: a single Room transaction snapshots the
  pre-mutation state of every touched entity to `batch_undo_log`,
  then applies the mutations. One shared `batch_id` groups the
  entries so a single tap reverses the whole batch.
- **Snackbar undo on return**: a new `BatchUndoEventBus` singleton
  fires when Approve lands. `BatchUndoListenerViewModel` (instantiated
  by the Today screen) observes the bus and triggers
  `TodayViewModel.showSnackbar("N changes applied", "Undo") { ... }`.
  Undo reverses every entry in the batch using the saved
  `pre_state_json` and marks each row `undone_at = now`.
- **Pro-gated** via the `AI_BATCH_OPS` gate added in PR1. Free-tier
  users see `"Batch commands are a Pro feature — upgrade to use them."`
  as a message on the QuickAdd voice-message surface and the command
  is not sent to Haiku.
- **Scope**: Tasks (RESCHEDULE / DELETE / COMPLETE / PRIORITY_CHANGE
  / TAG_CHANGE / PROJECT_MOVE), Habits (COMPLETE / SKIP / ARCHIVE),
  Projects (ARCHIVE). Medication mutations are accepted from the AI
  plan but skipped at apply time pending coordination with the
  medslots worktree (Option C from the audit — deferred to follow-up).
  Hard delete uses the existing soft-delete path (`archivedAt = now`)
  so undo is a one-column flip instead of subtree reconstruction.
- **Tests**: `BatchIntentDetectorTest` (unit — 11 cases covering empty
  input, single-task false positives, quantifier+time-range,
  tag filter, bulk-verb+plural, case-insensitivity, original-casing
  preservation).

### NLP batch schedule operations — schema + backend (A2 pulled-from-H PR1)

- **Room migration v57 → v58** adds `batch_undo_log`, a device-local
  append-only table that records the pre-mutation state of every entity
  touched by an Approve action so the user can reverse the batch within a
  24-hour window. Indexes on `batch_id`, `created_at`, and
  `(expires_at, undone_at)` mirror the read paths (history list, undo
  lookup, sweep worker).
- **Device-local by design** — no `cloud_id` column, not registered
  with `SyncMapper` or `CloudIdOrphanHealer`. Cross-device undo would
  race two devices undoing the same batch, and the per-entity sync path
  already propagates the mutated entities themselves.
- **New backend endpoint** `POST /api/v1/ai/batch-parse`. Accepts a
  natural-language command (`"Cancel everything Friday"`) plus the
  client's user_context (today's date, timezone, active tasks/habits/
  projects/medications) and returns a structured list of proposed
  mutations across all four entity types, a confidence score, and any
  ambiguous-entity hints. Stateless — no Firestore reads, mirroring the
  WeeklyReviewRequest pattern.
- **Claude Haiku prompt** with hard guardrails: never invent entity IDs,
  return only mutations the user plausibly intended, surface ambiguity
  rather than guess, full date-range parsing rules ("Friday" / "next
  week" / "the weekend"), per-mutation `proposed_new_values` schemas.
  Two-attempt JSON parse retry, same shape as the Eisenhower service.
- **Pro-gated** at the rate limiter (10/hour, mirrors time-block) and
  via a new `AI_BATCH_OPS` ProFeatureGate (PR2 wires the client-side
  gate; backend tier check is already in `daily_ai_rate_limiter`).
- **Tests**: Migration57To58Test (table + indexes + nullable columns +
  insert/query smoke), BatchUndoLogDaoTest (insert/list/sweep/undo
  semantics), test_ai_batch_parse.py (service success + ambiguity +
  retry + 503/500/429/422 router cases).
- **No UI yet** — PR2 wires the QuickAddBar intent router, the
  BatchPreviewScreen diff view, and the 30s Snackbar undo. PR3 ships
  the Settings batch history + 24-hour sweep worker.

### Repo hygiene (v1.4.40)

- Installed git hooks (pre-push warning for direct main pushes, post-commit reminder for versionName bumps without tags)
- Tagged v1.4.40 at current HEAD
- Cleaned up 3 stale branches (fix/integration-tests-per-test-onboarding, feature/ai-time-blocking, fix/time-block-lint) + 2 git-merged branches (claude/sync-duplication-phase2, claude/sync-duplication-phase2.5)
- Removed averyTask-timeblock worktree (AI time blocking shipped to main in v1.4.40)
- Added Repo conventions section to CLAUDE.md
- Promoted the Unreleased section's v1.4.35/v1.4.36/v1.4.37/v1.4.38/v1.4.40 sub-headers to top-level version headers; un-tagged entries between v1.4.0 and v1.4.34 grouped under a new "v1.4.1–v1.4.34 — Interim releases" section for later attribution
- Added androidTest migration coverage for migrations 48→49, 49→50, 50→51, and 52→53 (all other migrations from v47→v57 now have at least one direct-SQL migration test)

## [1.5.3] — 2026-04-23

Release-pipeline-only patch tag. Content is captured in the `[1.6.0]` section
above; the relevant subsections are:

- ci(release): unblock publish on backend-upload failure
- ci(release): make `Create GitHub Release` idempotent

No app or backend code changes — versionCode bumped solely so the release
pipeline could re-run a failed publish step against a non-conflicting tag.

## [1.5.2] — 2026-04-23

Tag captured the Web parity push (slices 1–22) plus the migration-tests
follow-ups. Content is in the `[1.6.0]` section above; relevant subsections:

- `### Web` — slices 1–22 (NLP batch ops on web, named themes & onboarding,
  AI daily briefing + weekly planner, analytics dashboard, conversation
  extraction, Pomodoro+ AI coaching, Eisenhower text classifier, task editor
  schedule-tab parity, Today polish + Start-of-Day, dedicated medication
  screen, templates parity, settings sections bundle, theme typography,
  theme shape + decorative flags, TAG_CHANGE batch + tag persistence,
  client-side analytics project-progress, medication tier picker + slot
  CRUD, custom habit + project template authoring, mood & energy tracking,
  morning check-in + forgiveness streak, boundaries + burnout scorer,
  focus release + good-enough timer)
- `### Repo hygiene` — branch protection on `main`; Web CI job-name
  disambiguation
- Migration tests added for migrations 48→49 / 49→50 / 50→51 / 52→53

## [1.5.0] — 2026-04-23

Tag captured the medication slot system landing end-to-end (A2 #6 + A2 #7).
Content is in the `[1.6.0]` section above; relevant subsections:

- `### Medication slot system — schema + backfill (A2 #6 PR1)` —
  three new entities (`medication_slots`, `medication_slot_overrides`,
  `medication_tier_states`) + junction; migrations 58→59 / 59→60;
  `MedicationTierComputer` auto-compute logic; `CloudIdOrphanHealer`
  expanded to 35 families
- `### Medication slot system — slot editor + tier picker + override
  toggle (A2 #6 PR2)` — `Settings → Medication Slots`,
  `MedicationSlotsViewModel`, reusable `MedicationTierRadio` /
  `MedicationSlotPicker` composables
- `### Medication slot system — MedicationScreen rewire (A2 #6 + #7 PR3)`
  — full screen rewrite reading from `MedicationEntity` + slot junction

Also captured under v1.5.0: `BatchUndoLogDao` test-module wiring,
`StartupCrashDiagnosticTest` updates for DB v58, and three
`MedicationSlotDao` test-module wirings (PR #702).

## [1.4.40] — 2026-04-22 — AI Time Blocking: horizon selector + mandatory preview

### AI Time Blocking — horizon selector + mandatory preview (A2 #5)
- **New "Auto-Block My Day" button** on the Timeline top bar replaces the
  old in-place config flow. Tapping it opens a horizon selector
  (Today / Today + Tomorrow / Next 7 Days) and then runs the AI plan.
- **Mandatory preview sheet** renders the proposed schedule grouped by
  day with Approve / Cancel buttons. Nothing writes to Room until the
  user taps Approve — `proposed=true` is enforced both server-side and
  client-side.
- **Backend schema extension**: `TimeBlockRequest` now accepts
  `horizon_days` (1/2/7), `task_signals` (Eisenhower quadrant + estimated
  Pomodoro sessions / duration per task), and `existing_blocks` (hard
  constraints for already-scheduled PrismTask / Pomodoro blocks in the
  horizon window). All fields are optional; old clients keep working
  with single-day horizon + no extra signals.
- **Haiku prompt** upgraded to rank by Eisenhower quadrant (Q1/Q2 early,
  Q3/Q4 deferred first), size blocks from Pomodoro session counts, and
  route around existing blocks as hard constraints. For `horizon_days>1`
  every returned block carries a `date` so the client can place it on
  the correct calendar day.
- **Rate limit** bumped from 1-per-15-min to 10/hour on `/api/v1/ai/time-block`
  so horizon=7 requests and parse-failure retries don't lock out Pro users.
- **Google Calendar integration is out of scope** — that work is deferred
  to Phase F (v2.1). Existing single-day GCal event passthrough is
  preserved and now spans the horizon window.
- **Pro-gated** via existing `ProFeatureGate.AI_TIME_BLOCK` — no new
  feature flags.
- **No Room migration**: DB stays at version 57. Horizon selection is
  session-only state held in `TimelineViewModel` (matches how
  `timeBlockConfig` is held).
- **New files**: `AiTimeBlockUseCase.kt`, `AiTimeBlockUseCaseTest.kt`,
  `TimelineViewModelTest.kt`. **New DAO queries**:
  `getTasksInHorizonOnce`, `getScheduledTasksInHorizonOnce`.

## [1.4.38] — 2026-04-22 — Room content entities cross-device sync

### Sync — Room content entities cross-device
- **Migration 55 → 56** adds `cloud_id TEXT` (UNIQUE-indexed) to all nine
  remaining user-authored content tables, plus `updated_at INTEGER NOT
  NULL DEFAULT 0` to the seven that lacked it. `medication_refills` and
  `daily_essential_slot_completions` already had `updated_at` from
  earlier migrations so only get `cloud_id`.
- **Entities synced**: `check_in_logs`, `mood_energy_logs`,
  `focus_release_logs` (FK: `task_id`), `medication_refills`,
  `weekly_reviews`, `daily_essential_slot_completions`, `assignments`
  (FK: `course_id`), `attachments` (FK: `taskId`), `study_logs` (FKs:
  `course_pick`, `assignment_pick`). FK-bearing entities translate
  local ↔ cloud IDs at push/pull time via `syncMetadataDao`; rows whose
  parent hasn't synced yet are skipped and retry on the next pass.
- **SyncMapper** grows 18 new functions (one `entityTo*Map` + one
  `mapTo*` per entity), all covered by `SyncMapperContentTest` (11
  cases including null-FK and dual-FK round-trips).
- **SyncService** wires all nine into the standard four paths. The
  five FK-free entities reuse the existing `uploadRoomConfigFamily` /
  `pullRoomConfigFamily` helpers from v1.4.37; the four FK-bearing
  ones get bespoke upload/pull blocks that include FK translation.
  `collectionNameFor`, `pushCreate`/`pushUpdate`/`pushDelete`,
  `startRealtimeListeners`, and `processRemoteDeletions` all get nine
  new branches.
- **Repository SyncTracker wiring**: `CheckInLogRepository`,
  `MoodEnergyRepository`, `MedicationRefillRepository`,
  `WeeklyReviewRepository`, `AttachmentRepository`,
  `DailyEssentialSlotCompletionRepository`, and the assignment CRUD
  path on `SchoolworkRepository` now inject `SyncTracker` and call
  `trackCreate`/`trackUpdate`/`trackDelete` on every write, stamping
  `updated_at` on the way out. `FocusReleaseLogEntity` and
  `StudyLogEntity` have no user-facing write path in the current
  codebase, so their sync contract is bootstrap-on-first-sign-in +
  pull-from-remote; incremental push is a two-line add if a UI ever
  lands.
- **Privacy update for `focus_release_logs`**: the pre-v1.4.38 KDoc
  said "NEVER sent to the backend." That comment predated the current
  request. v1.4.38 syncs focus-release analytics across the user's
  own devices within their own Firebase project — no third-party
  analytics backend. The KDoc is updated to match.
- **Attachment URIs**: image attachments sync the `file://` pointer
  but not the bytes — opening a synced image on a different device
  falls back to the thumbnail until a future content-upload
  extension. Link attachments round-trip cleanly.

## [1.4.37] — 2026-04-22 — Room config entities cross-device sync

### Sync — Room config entities cross-device
- **Migration 54 → 55** adds `cloud_id TEXT` (UNIQUE indexed) and
  `updated_at INTEGER NOT NULL DEFAULT 0` to the seven Room tables that
  back user configuration but were previously local-only:
  `reminder_profiles`, `custom_sounds`, `saved_filters`, `nlp_shortcuts`,
  `habit_templates`, `project_templates`, `boundary_rules`. Every
  existing row starts with `cloud_id = NULL` and `updated_at = 0`;
  the next `SyncService.doInitialUpload` assigns cloud IDs and the
  first local write bumps `updated_at` to a current wall-clock value
  (which beats every remote timestamp, so the first device to migrate
  owns the seed copy cloud-side).
- **SyncMapper** grows 14 new functions — one `entityTo*Map()` and one
  `mapTo*()` per entity — covering every business-visible column. All
  round-trip cleanly under `SyncMapperRoomConfigTest` (8 cases, one per
  entity + a sparse-map defaults test).
- **SyncService** wires each of the seven entities into the standard
  four paths: initial upload (via a new generic
  `uploadRoomConfigFamily` helper), real-time pull (via
  `pullRoomConfigFamily` with last-write-wins on `updated_at`),
  real-time listener (added to the `startRealtimeListeners` collection
  list), and deletion (added to `processRemoteDeletions`'s
  `collection → entityType` dispatch). `collectionNameFor` gets seven
  new branches so `pushCreate`/`pushUpdate`/`pushDelete` can serialize
  the right Firestore collection.
- **Repositories**: `NotificationProfileRepository`,
  `CustomSoundRepository`, and `BoundaryRuleRepository` now inject
  `SyncTracker` and call `trackCreate` / `trackUpdate` / `trackDelete`
  on every user-visible write, stamping `updated_at` on the way out.
  Built-in rows (`isBuiltIn = true`) are not tracked — those are
  seeded, not user-authored. The four DAO-only entities
  (`SavedFilter`, `NlpShortcut`, `HabitTemplate`, `ProjectTemplate`)
  currently have no UI write path, so their sync contract is
  bootstrap-on-first-sign-in + pull-from-remote; if a UI ever lands,
  plumbing `SyncTracker` into it is a two-line change per write site.
- **DAOs**: every one of the seven grows `getByCloudIdOnce`,
  `setCloudId`, `deleteById` (or `getByIdOnce`) where missing, matching
  the contract the generic sync helpers expect.

## [1.4.36] — 2026-04-22 — Preferences backup coverage follow-up

### Preferences — Backup coverage follow-up
- **Closes three backup gaps** identified in the post-v1.4.35 preference
  coverage audit:
  - `OnboardingPreferences` (asymmetry): already exported at
    `DataExporter.kt:578-582` but not imported. `DataImporter` now
    reads back all three keys (`hasCompletedOnboarding`,
    `onboardingCompletedAt`, `hasShownBatteryOptimizationPrompt`) via
    a new `OnboardingPreferences.restoreImportedState` that writes the
    original `completed_at` timestamp verbatim instead of re-stamping
    to `now` (otherwise a restore would look like a fresh onboarding).
  - `CoachingPreferences` (both sides missing): new `exportCoachingConfig`
    writes `lastAppOpen`; `importCoachingConfig` restores it. The five
    day-scoped keys (AI breakdown counter, energy check-in, welcome-
    back dismissal) are intentionally omitted — they reset when the
    calendar date differs from export time so backing them up would
    carry no signal.
  - `SortPreferences` (both sides missing): new `exportSortConfig` /
    `importSortConfig` round-trip every `sort_*` key via the existing
    `snapshot()` / `applyRemoteSnapshot` pair shared with
    `SortPreferencesSyncService`. Global entries (e.g. `sort_today`,
    `sort_all_tasks`) round-trip cleanly; per-project entries
    (`sort_project_<localId>`) reference auto-generated Room IDs and
    so may not survive a fresh-install restore — documented on the
    exporter method.
- Wired `CoachingPreferences`, `SortPreferences`, and a restore-aware
  OnboardingPreferences into the `DataImporter` / `DataExporter` Hilt
  graphs. Existing unit tests updated to pass `mockk(relaxed = true)`
  for the three new constructor parameters.

## [1.4.35] — 2026-04-22 — Universal cross-device preference sync

### Preferences — Universal cross-device sync
- **New `GenericPreferenceSyncService`** syncs any registered DataStore
  preference file to Firestore at `/users/{uid}/prefs/{docName}` with
  document-level last-write-wins. A type-tagged payload
  (`__pref_types` + per-key values) lets the pull side reconstruct
  correctly-typed `Preferences.Key<T>` instances without the service
  needing any compile-time knowledge of the preference class's keys.
- **19 preference files registered**: `a11y_prefs`, `archive_prefs`,
  `coaching_prefs`, `daily_essentials_prefs`, `dashboard_prefs`,
  `habit_list_prefs`, `leisure_prefs`, `medication_prefs`,
  `morning_checkin_prefs`, `nd_prefs`, `notification_prefs`,
  `onboarding_prefs`, `shake_prefs`, `tab_prefs`, `task_behavior_prefs`,
  `template_prefs`, `timer_prefs`, `user_prefs`, `voice_prefs`. The
  pre-existing bespoke `ThemePreferencesSyncService` /
  `SortPreferencesSyncService` keep doing their specialized work
  (per-project cloud-id translation for sort; legacy path for theme).
- **Explicitly excluded** from sync: `auth_token_prefs` (sensitive
  tokens), `pro_status_prefs` (server-authoritative billing cache),
  `backend_sync_prefs` (per-device watermark), `built_in_sync_prefs` +
  `medication_migration_prefs` (one-time migration flags),
  `gcal_sync_prefs` (per-device Google Calendar tokens), and the
  service's own `sync_device_prefs` (device identity).
- **Self-echo guard**: each push stamps the doc with a persisted per-
  install `__pref_device_id`; the pull listener skips snapshots whose
  `__pref_device_id` matches the local value, so the listener's own
  echo of our push never bounces back into DataStore.
- **Lifecycle**: `MainActivity.onCreate` calls `startPushObserver` +
  `ensurePullListener`; `AuthViewModel` calls `startAfterSignIn` /
  `stopAfterSignOut`, mirroring the existing theme/sort sync wiring.
- **Tests**: `PreferenceSyncSerializationTest` (9 cases) round-trips
  every supported DataStore value type (Boolean, Int, Long, Float,
  Double, String, Set&lt;String&gt;), asserts the Firestore number-
  widening behavior on pull, confirms excluded and meta-prefixed keys
  never leak into the payload, and asserts fingerprint stability
  across insertion order and set iteration order.

## [1.4.1]–[1.4.34] — April 2026 — Interim releases

The entries below landed between v1.4.0 and v1.4.34 but were committed to the CHANGELOG without explicit per-version headers. They're grouped here for attribution; individual version boundaries can be reconstructed from git history if needed.

### Medications — Top-level entity (follow-up — MedicationRepository unit tests)
- `MedicationRepositoryTest` (12 cases) covers the repository's write
  contract: insert / update / archive / delete, logDose / unlogDose /
  updateDose, timestamps, `taken_date_local` computation, and
  date-filtered dose counting. Every write asserts the
  corresponding `SyncTracker.trackCreate/Update/Delete` call with the
  correct `"medication"` or `"medication_dose"` entity type — the
  contract that keeps the sync layer picking up changes. Uses in-
  memory fake DAOs in the `FakeMedicationDaoForRepo` /
  `FakeMedicationDoseDaoForRepo` style.
- Full-chain instrumentation suite (API 26 migration, two-device
  Firestore-emulator convergence, Robolectric reminder-continuity)
  still deferred to a dedicated emulator PR.

### Medications — Top-level entity (PR 4.5 — Dual-write shim + scheduler disarm)
- **Dual-write shim.** `SelfCareRepository`'s medication-specific write
  paths (`addStep`, `updateStep`, `deleteStep`, `toggleStep`) now mirror
  to `medications` / `medication_doses` so the new top-level entity
  stays in sync with ongoing user edits during the v54 convergence
  window. Matching rule follows the migration:
  `COALESCE(NULLIF(TRIM(medication_name), ''), label)`. The old
  UI stays unchanged (Compose tree keeps reading `SelfCareStepEntity` /
  `SelfCareLogEntity`); the full Compose-type rewire is deferred to the
  follow-up that also drops the `self_care_*` reads.
- **Legacy scheduler disarm** — after
  `MedicationMigrationRunner.preserveScheduleIfNeeded` writes the
  schedule onto every `MedicationEntity`, it now cancels stale
  `+300_000`-range specific-time `PendingIntent`s, clears
  `MedicationPreferences.specificTimes`, and nulls the built-in
  Medication habit's `reminderIntervalMillis` / `reminderTime` (also
  cancels its `+200_000` / `+900_000` alarms). Without this, a user
  who had `MedicationPreferences.specificTimes = [08:00, 14:00]`
  pre-upgrade would get BOTH legacy AND new alarms at 8:00 + 14:00
  post-v54 boot.

### Medications — Top-level entity (PR 5 / 5 — Unit tests)
- `BuiltInMedicationReconcilerTest` — covers the post-sync dedup-by-name
  pass: no-duplicate case, duplicate-collapse with dose-count winner
  rule, tiebreak on smallest id, case/whitespace-insensitive grouping,
  dose-history reassignment to the keeper, flag idempotency, single-row
  no-op, empty-DB no-op.
- Full-chain instrumentation tests deferred. The spec's two-device
  convergence test + API-26 JSON1 migration test + Robolectric
  reminder-continuity test deserve a dedicated PR — emulator setup and
  test-harness scaffolding are non-trivial and better landed separately
  from unit coverage.

### Medications — Top-level entity (PR 4 / 5 — Nav tile + toggle wiring)
- **New bottom-nav tile.** `Meds` (Material `LocalPharmacy` icon) added
  between `Recurring` and `Timer` in `ALL_BOTTOM_NAV_ITEMS`. Taps route
  to the existing `MedicationScreen` (its ViewModel rewire to
  `MedicationRepository` is deferred to a follow-up to keep this PR
  reviewable).
- **Toggle coupling.** `MainActivity` combines the existing
  `tabPreferences.hiddenTabs` with `habitListPreferences.isMedicationEnabled`.
  When the Medication toggle is off, the route is added to `hiddenTabs`
  so the tile doesn't render. Same single-source-of-truth toggle that
  used to hide the `SelfCareItem("medication")` row.
- **`SelfCareItem("medication")` removal.** `HabitListViewModel.items`
  no longer emits the medication row — users reach medications via the
  new top-level tile instead. `medicationOn` still participates in the
  `allBuiltInNames` filter that hides the underlying "Medication" habit.
- **`SelfCareRepository.ensureHabitsExist`** no longer auto-seeds the
  built-in `"Medication"` habit. Existing users keep their row in Room
  until the Phase 2 cleanup migration drops it.
- **`DataExporter`** now includes `medications` + `medication_doses`
  sections alongside the existing `medicationRefills` section for the
  convergence window.

### Medications — Top-level entity (PR 3 / 5 — Scheduler split)
- **Rename.** `MedicationReminderScheduler` → `HabitReminderScheduler`.
  The class always handled app-wide habit alarms (daily-time, interval,
  follow-up-suppression) — the legacy name was an accident of history.
  All nine call sites updated; the transitional legacy
  `MedicationPreferences.specificTimes` path stays on the renamed class
  until the Phase 2 cleanup drops `MedicationPreferences`.
- **New class.** `MedicationReminderScheduler` (v1.4) — per-medication
  alarms for the top-level Medication entity. Takes `MedicationDao` +
  `MedicationDoseDao`. Dispatches per `MedicationEntity.scheduleMode`:
  `TIMES_OF_DAY` → one alarm per bucket at 08:00/13:00/18:00/21:00;
  `SPECIFIC_TIMES` → one alarm per "HH:mm"; `INTERVAL` → chained alarm
  after the last dose; `AS_NEEDED` → no alarms. Request-code namespace
  is `400_000 + (medicationId % 1000) * 10 + slotIndex`, deliberately
  distinct from the `200_000/300_000/900_000` offsets on the renamed
  habit scheduler so old and new PendingIntents never collide.
- **Receiver dispatch.** `MedicationReminderReceiver` dispatches by
  extra: `medicationId` → new per-med path; `habitId` → legacy path.
  Both coexist during the 2-week convergence window so existing
  scheduled alarms don't get orphaned.
- **Boot.** `BootReceiver` calls `habitReminderScheduler().rescheduleAll()`
  AND `medicationReminderScheduler().rescheduleAll()` so both surfaces
  recover after a device reboot.
- **Tests.** Existing unit tests renamed to `HabitReminderSchedulerTest`
  + `HabitReminderSchedulerDailyTriggerTest`. New
  `MedicationReminderSchedulerTest` covers request-code namespace
  isolation (including legacy-overlap sanity) and the `"HH:mm"`
  validator.

### Medications — Top-level entity (PR 1 / 5 — Room layer only)
- **Scope.** New Room entities `MedicationEntity` + `MedicationDoseEntity`
  and matching DAOs. DB bumps v53 → v54 via `MIGRATION_53_54`. Spec:
  `docs/SPEC_MEDICATIONS_TOP_LEVEL.md`. No user-visible change yet — the
  existing Medication screen still reads from `SelfCareRepository`; PR 4
  rewires it. Quarantine-style staging leaves all source data intact; a
  future Phase 2 cleanup migration drops `self_care_steps`/`self_care_logs`
  rows with `routine_type='medication'`, the `medication_refills` table,
  and the built-in `"Medication"` habit row.
- **Migration shape.** `medications` is name-unique with inline refill
  columns (pharmacy / pill_count / reminder_days_before) subsumed from
  `medication_refills`. `medication_doses` mirrors `habit_completions` —
  `taken_date_local` ISO-8601 timezone-neutral column, FK CASCADE on
  medication delete. Duplicate-name source rows collapse to one row with
  `display_label` = `REPLACE(GROUP_CONCAT(DISTINCT label), ',', ' / ')`.
- **Deferred from the spec's §3.2 to PR 2's Kotlin runner:**
  JSON-parsing dose backfill (`json_each` / `->>` operator availability
  is OEM-dependent on API 26) and schedule-mode preservation from
  `MedicationPreferences` DataStore (Room migrations can't read DataStore).
- **Tests.** `Migration53To54Test` (androidTest, JSON1-free SQL) covers
  distinct-name backfill, duplicate-name collapse, blank-name → label
  fallback, refill merge, quarantine contents, source-tables-unchanged,
  unique(name) enforcement, empty source, non-medication routine filter,
  deferred dose-table emptiness, and FK CASCADE on medication delete.
- **Follow-ups.** PR 2: `MedicationRepository` + sync mapper + migration
  runner + `BuiltInMedicationReconciler`. PR 3: scheduler split. PR 4:
  UI + nav wiring. PR 5: full instrumentation suite.

### Sync — Duplication Fix (Phase 2 + Phase 2.5)
- **Root cause.** Prior builds re-uploaded every local row on every
  sign-in because `initialUpload` had no "already ran" guard and
  nothing on the entity row identified which Firestore document
  mirrored it. Concurrent listeners pulled while the upload was still
  running, producing duplicate documents per entity on every
  sign-in across devices. Symptom on the production account: ~9,656
  tag rows representing ~3 user-authored tag names, ~5,144 task rows
  representing ~19 natural-key groups, ~1,248 habit rows representing
  ~12 canonical habits.
- **Fix A — one-shot upload guard.** `SyncService.initialUpload` now
  skips with `PrismSync` event `initialUpload.skipped.alreadyRan` when
  a persisted preference flag `initial_upload_completed=true` is
  already set for the signed-in account on this install. The flag is
  set at the end of the first successful upload, so a second sign-in
  of the same account on the same device/install cannot re-upload the
  whole local dataset.
- **Fix B — upload/pull serialization.** `initialUpload` and
  `pullRemoteChanges` are now serialized via a `Mutex` so the two
  paths can't race on startup. Before the fix, the pull listener
  could attach mid-upload, pull the partially-uploaded rows, and
  treat them as "new" on a second sign-in — doubling the dataset.
- **Fix C — per-row cloud_id guard.** Every entity write path in
  `initialUpload` now requires a non-null `cloud_id` on the local row
  before pushing. Rows without `cloud_id` (legacy installs that
  haven't had `restoreCloudIdFromMetadata` run yet) are skipped with
  `initialUpload.skipped.rowHasCloudId=false` and retried after the
  pull completes. This closes the gap where a row with no prior
  Firestore identity was being `add()`ed instead of `set()` under its
  existing doc ID.
- **Migration 51 → 52.** Adds a nullable `cloud_id TEXT` column plus
  `CREATE UNIQUE INDEX index_<table>_cloud_id ON <table>(cloud_id)`
  on every syncable entity table. Backfills from the existing
  `sync_metadata.cloud_id` column via a correlated `UPDATE … FROM`.
  When `sync_metadata` has colliding `(entity_type, cloud_id)`
  mappings (one cloud doc pointed at multiple local rows — the
  duplication symptom), the migration keeps the smallest `local_id`
  as the winner and NULLs `cloud_id` on the losers so the unique
  index can hold. See `data/local/database/Migrations.kt` lines
  985-1100 for the full migration body and logging.
- **Phase 2.5 — cloud_id hydration.** `SyncMapper` now populates
  `cloud_id` on every entity constructed from a Firestore pull, so
  rows arriving via `pullRemoteChanges` no longer land in Room with a
  null `cloud_id`. `SyncService.restoreCloudIdFromMetadata()` is a
  one-shot per-install backfill that walks `sync_metadata` and
  repopulates `cloud_id` on any row that was written by
  pre-migration-52 code — covers the window between
  migration 51→52 running and the next sync pulling fresh data. The
  one-shot gate is a preference flag
  (`cloud_id_metadata_restore_ran`), so the restore never fires
  twice on the same install.
- **Data-cleanup plan (Fix D).** `docs/PHASE_3_FIX_D_PLAN.md`
  captures the per-table collapse design: natural-key winner rules
  (`lower(trim(title))`, `trim(name)`, etc.), parent-before-child
  collapse order, FK-repoint-then-delete strategy, and a new
  `task_completions_quarantine` table that isolates the ~2,353
  `task_completions` rows with `task_id IS NULL` for optional future
  triage rather than discarding them. This is a one-time SQL
  operation run against the device's Room DB once v1.4.19+ is
  installed and `restoreCloudIdFromMetadata` has hydrated
  `cloud_id`; it does not ship as code. Expected post-collapse row
  counts are captured from a 2026-04-21 dry-run (tasks 19, tags 3,
  projects 2, habits 12, task_completions 12, task_templates 8).
- **Telemetry.** `PrismSyncLogger` adds structured events for every
  skip/retry/serialized-wait case above, tagged with account UID and
  install ID so the production account's sync-duplication curve can
  be confirmed flat across multiple sign-ins post-install of
  v1.4.19+.
- **Tests.** `SyncMapperCloudIdTest` covers all 14 syncable entities
  including `task_templates` (added in `78d8e3b3`). Regression guards:
  (a) Fix A — signing in twice on the same install only produces one
  `initialUpload.start` event; (b) Fix C — a row with null `cloud_id`
  is skipped, not uploaded with an auto-generated doc ID.
- **Adjacent WLB fix (`e5a01a18`).** A stale `autoClassify`
  preference stored under a no-longer-read key was deleted, removing
  one source of `lifeCategory` null rows. The life-category
  fallback + built-in task-template reconciler were tightened in
  the same commit to avoid re-creating duplicate built-in templates
  during post-sync reconciliation.

### Reminders — v1.4.0 Pass 1
- Verified task + habit-interval reminder happy paths; fixed
  cancel-on-complete across recurrence, bulk-complete, and subtask
  paths. `TaskRepository.completeTask` now cancels the task's alarm
  before the completion transaction and re-registers an alarm for the
  newly-inserted recurrence instance so recurring tasks keep their
  reminder. `uncompleteTask` restores the cancelled alarm so Undo
  (single or bulk) brings it back.
- Scheduled 6 summary workers with per-worker Settings toggles,
  default on: daily briefing (morning hour pref), evening summary
  (evening hour pref), weekly summary (Sunday 7 PM), overload check
  (daily 4 PM), re-engagement (daily, fires conditionally after 2+
  days of absence), and the `WeeklyHabitSummary` helper that
  `WeeklySummaryWorker` delegates to. Added `NotificationWorkerScheduler`
  (@Singleton) to apply toggle state at app startup and on every
  per-worker Settings change; all five workers use UPDATE policy so
  hot toggles don't stack jobs.
- Workers are permission-aware: when POST_NOTIFICATIONS is denied the
  worker still runs its data-gathering logic but silently drops
  `NotificationManager.notify()` via a `SecurityException` try/catch.
  Workers never self-cancel on permission denial.
- Added Samsung battery-optimization guidance. The existing one-time
  onboarding dialog now includes Samsung "Put Unused Apps to Sleep" /
  "Deep Sleeping Apps" sleep-list text. A persistent
  battery-optimization banner in Settings → Notifications surfaces
  the same guidance on every device, with Samsung-specific copy and
  a best-effort Battery Settings deep-link on Samsung builds.
- Added a POST_NOTIFICATIONS denial explainer banner at the top of
  Settings → Notifications (API 33+). Surfaces Allow and Open Settings
  actions and re-checks permission state on resume so granting in
  system Settings hides the banner immediately.
- Refactored `NotificationHelper` to remove Main-thread blocking on
  DataStore preference reads. `showTaskReminder`,
  `showMedicationReminder`, `showMedStepReminder`,
  `showTimerCompleteNotification`, `showTaskReminderFor`, and the
  channel-creation helpers are now `suspend`. `MainActivity`,
  `ReminderBroadcastReceiver`, `MedStepReminderReceiver`, and
  `HabitFollowUpReceiver` were updated to call them from coroutines
  (lifecycleScope or `goAsync` + IO dispatcher).
- Habit reminder editor verified: `AddEditHabitScreen` writes
  `reminderTime`, `reminderIntervalMillis`, and `reminderTimesPerDay`
  correctly, and interval-mode edits trigger
  `MedicationReminderScheduler.rescheduleAll()`.
- Fixed: daily-time habit reminders now fire. Previously
  `MedicationReminderScheduler.rescheduleAll()` only scheduled
  interval-mode habits, so `HabitEntity.reminderTime` was written by
  the editor but no alarm was ever registered. Added a per-habit
  daily-time branch (`scheduleDailyTime`, `cancelDailyTime`,
  `rescheduleAllDailyTime`) and taught the existing
  `MedicationReminderReceiver` to re-register the next day's
  occurrence when a daily-time alarm fires (distinguished from
  interval alarms via an `alarmKind` intent extra). Boot
  re-registration, habit delete/archive, and habit editor save all
  cover the new branch via the new `cancelAll` umbrella method.
- Added `docs/REMINDERS_TEST_RUNBOOK.md` with 10 on-device scenarios
  covering every change above. Target devices: Samsung Galaxy S25
  Ultra + one Pixel.
- Refactored: the weekly habit summary worker was renamed and
  inlined from its former `WeeklyHabitSummary` helper. The
  `WeeklySummaryWorker` wrapper is gone; `WeeklyHabitSummaryWorker`
  is now a proper `@HiltWorker` with its data-aggregation logic
  extracted to a testable `WeeklyHabitSummaryCalculator` object.
  The toggle is labeled "Weekly Habit Summary" and its channel
  keeps the existing ID `prismtask_weekly_summary`, so user OS
  channel customizations and the persisted preference key survive
  the rename. A one-time WorkManager cleanup cancels the stale
  pre-rename unique work so the new class binds cleanly on first
  scheduler run. No user-facing behavior change. A TODO anchor is
  left in the new worker for a future `WeeklyTaskSummaryWorker`
  complement (Phase A2 / v2.1).

### Accessibility — Onboarding Polish (Phase 2)
- Page indicator dots now announce the current page position to TalkBack
  ("Page X of 9") via a `contentDescription` on the indicator `Row`.
- Primary page titles on every onboarding page are now marked as headings so
  TalkBack users can jump between them with heading navigation.
- The sign-in error `Text` on the Welcome page is now a polite live region so
  it is auto-announced when it appears.
- The brain-mode "selected" check icon now carries a meaningful
  `contentDescription` ("Selected") instead of being redundant with the card
  title.

### Fixed — Firestore Existing-User Check No Longer Fails Silently
- When the post-sign-in Firestore lookup that decides whether to skip
  onboarding for returning users fails (e.g., airplane mode, transient
  network), the UI now surfaces a non-blocking message
  ("Couldn't check for existing account — continuing with setup.") instead
  of silently falling through. The flow is never blocked — users can
  continue through onboarding and retry later by signing out and back in.
- A new `SignInState.ExistingUserCheckFailed` variant carries the signed-in
  email so downstream UI can still render the account badge.

### Fixed — Loading Indicator During Existing-User Check
- During the 1–3 second Firestore lookup after sign-in, the Welcome and
  Setup pages now show an inline spinner (and, on the Setup page, a
  "Checking for existing account…" label) instead of appearing frozen.
  Backed by a new `SignInState.CheckingExistingUser` variant that the
  ViewModel sets before the lookup and clears after success or failure.

### Fixed — Whitespace-Only Input on Onboarding Task Field
- Verified that the Setup-page "Create Your First Task" field correctly
  rejects whitespace-only input (both the UI `KeyboardActions.onDone` guard
  and `OnboardingViewModel.createQuickTask` use `isBlank`-family checks, so
  whitespace never reaches task creation).

### Removed — "Set Up Later" Button
- The literal no-op "Set Up Later" button on the Setup-page sign-in card has
  been removed. The page still has a clear forward affordance via the
  "Start Using PrismTask" button at the bottom; users who don't want to
  sign in simply skip the Sign-In card.

### Fixed — Start-of-Day Prompt on Skip Onboarding (pre-fix install race)
- Start-of-day prompt no longer appears on first launch after skipping
  onboarding for pre-fix installs. The earlier two-`LaunchedEffect`
  arrangement (separate `LaunchedEffect(Unit)` backfill + `LaunchedEffect(
  hasCompletedOnboarding)` gate check) ran the two bodies as concurrent
  coroutines whose execution order was not guaranteed by source ordering;
  the gate check could win the race and show the prompt before the
  backfill's DataStore write took effect. The backfill is now collapsed
  into the gate `LaunchedEffect`'s body so it runs sequentially before the
  gate's `.first()` read in the same coroutine — structural ordering
  replaces scheduling luck. The gate's `showStartOfDayPrompt = true` line
  is retained as defensive coverage for future edge cases. Backfill
  removal tracked for v2.2+.

### Fixed — Theme Sync Timestamp Race
- **Theme sync no longer breaks when devices have minor clock skew.** The pull
  path previously overwrote `THEME_UPDATED_AT_KEY` with the remote device's
  timestamp; if the remote clock was slightly ahead, subsequent local changes on
  this device produced smaller timestamps and the push guard silently suppressed
  them. The fix removes that write: `THEME_UPDATED_AT_KEY` is now exclusively
  written by user-action setters, while `THEME_LAST_SYNCED_AT_KEY` is the only
  timestamp advanced on pull.

### Fixed — Theme & Appearance Sync
- **Theme mode (light/dark/system)** now syncs across devices. Previously the
  `theme_mode` preference was written only to local DataStore with no Firestore
  write path or listener; changing it on Device A had no effect on Device B.
- **Accent color, font scale, priority colors, and color overrides**
  (background/surface/error) now sync across devices via the same
  `ThemePreferencesSyncService` that already synced the PrismTheme selection.
- **Theme sync now works on every cold start.** Previously the Firestore pull
  listener was registered only when the user completed an interactive sign-in
  (`AuthViewModel.onGoogleSignIn`); restarting the app while already signed in
  left the pull listener unregistered, so remote changes were never received.
  `MainActivity.onCreate` now calls `ensurePullListener()` so the listener is
  active regardless of how the session began.

### Changed — Default Templates & Routine Steps
- Expanded starter content for the five built-in habit-template categories:
  **School** and **Leisure** now land as parent-with-subtasks templates
  (`School Daily` with 8 subtasks, `Leisure Time` with 7) in
  `TemplateSeeder.BUILT_IN_TEMPLATES`. **Self-Care**, **Housework**, and
  **Medication** now seed as flat `SelfCareStepEntity` rows in
  `SelfCareRoutines` (8 / 9 / 4 steps respectively; Medication was previously
  empty by design).
- Replaced three prior seeds in the process: removed `Assignment` (School),
  `Deep Clean` (Housework), and `Morning Routine` (Self-Care) from
  `TemplateSeeder.BUILT_IN_TEMPLATES`. Housework's prior tiered skincare-style
  `houseworkSteps` list is also replaced by the new 9-chore flat list.
- All new entries use Title Case per CLAUDE.md convention.

### Added — Debug Re-Seed Trigger
- Long-press on the **PrismTask v…** version label in Settings → About now
  wipes seeded built-in templates + Self-Care / Housework / Medication steps
  and re-runs the seeders. `BuildConfig.DEBUG`-gated at both the gesture
  (no `combinedClickable` attached on release builds) and the ViewModel
  handler, so the release APK is unaffected.
- User-created templates and user-added Self-Care steps are untouched —
  built-ins are identified by the existing `isBuiltIn` flag (templates) and
  the hardcoded `stepId` set in `SelfCareRoutines` (steps).

## [1.4.0] — 2026-04-20 — Wellness-Aware Productivity Layer

### Fixed — Sync Reliability (Apr 18–19, PRs #536–557)
- **Habit uncheck cross-device sync**: `processRemoteDeletions()` in `SyncService` was a
  no-op for `habit_completion` entities — remote deletions were acknowledged but the local
  row was never removed. Fixed by adding `HabitCompletionDao.deleteById()` and wiring it
  into the REMOVED-document handler. (PR #557)
- **Habit uncheck ID consistency**: `uncompleteHabit()` in `HabitRepository` used
  `getByHabitAndDateLocal` (returns oldest row) to fetch the ID to track, while
  `deleteLatestByHabitAndDateLocal` deletes the newest row. For multi-dose habits this
  produced a mismatch. Fixed by introducing `getLatestByHabitAndDateLocal` (ORDER BY
  `completed_at` DESC LIMIT 1) and using it in both places. (PR #557)
- **Habit completion pull normalization**: completion dates pulled from Firestore are now
  re-normalized through the device's `dayStartHour` before upsert, preventing mismatched
  local dates when start-of-day is non-midnight. (PR #541)
- **Habit completion push ID lookup**: `pushCreate()` for `habit_completion` now looks up
  completions by their own Room `id` instead of by `(habitId, date)`, eliminating silent
  pushes of the wrong row when a habit has multiple completions on the same day. (PR #540)
- **Cross-device deletion propagation**: real-time Firestore listener now processes
  REMOVED document changes for all entity types, not just MODIFIED. (PR #539)
- **Task completions added to sync pipeline**: `TaskCompletionEntity` records are now
  pushed and pulled through the standard `SyncService` pipeline under
  `users/{uid}/task_completions/`. (PR #543)
- **Reactive push queue**: `SyncService` now observes `sync_metadata` writes reactively
  via `observePending()` with a 500 ms debounce instead of only flushing at app launch.
  Edits on one device appear on the other within seconds. (PR #536)

### Fixed — Onboarding
- Onboarding completion flag is written to `OnboardingPreferences` before template-seeding
  work begins, so a process-death mid-seeding no longer re-shows onboarding on next launch.
  (PR #538)

### Fixed — DB Migration 49→50 — Timezone-neutral habit completion dates (PR #556)
- Added `completed_date_local TEXT` column to `habit_completions`. Backfilled via
  `strftime('%Y-%m-%d', completed_date/1000, 'unixepoch', 'localtime')`. Indexed under
  `index_habit_completions_completed_date_local`.
- All `HabitRepository` queries that previously used epoch-millis `completed_date` for
  day-boundary comparisons now use the pre-computed `completed_date_local` string. This
  fixes a class of bugs where users whose clock crossed midnight between 00:00 and their
  configured start-of-day would see double completions or missing completions.

### Added — DB Migration 48→49 — Built-in habit identity (PR #549–553)
- Added `is_built_in INTEGER NOT NULL DEFAULT 0` and `template_key TEXT` to `habits`.
  Backfills `is_built_in=1` and a stable `template_key` for the six known built-in habits
  (School, Leisure, Morning Self-Care, Bedtime Self-Care, Medication, Housework) on
  upgrade.
- `BuiltInHabitReconciler` runs once after a successful full sync via
  `BuiltInSyncPreferences.builtInsReconciled` guard. It deduplicates built-in habits
  that may have been pulled as duplicate cloud documents and backfills any missing
  `is_built_in` / `template_key` fields. Three one-time flags
  (`builtInsReconciled`, `driftCleanupDone`, `builtInBackfillDone`) in
  `BuiltInSyncPreferences` ensure each repair runs at most once.
- `SyncMapper` infers `isBuiltIn` from `templateKey` when the field is absent in older
  Firestore documents, providing forward-compatible reads of pre-migration cloud data.

### Added — Start-of-Day (SoD) Configurable Day Boundary (PRs #554–555)
- New `DayBoundary` utility (`util/DayBoundary.kt`) that resolves "today" relative to a
  configurable `dayStartHour` (0–23, default 0). A logical day runs from
  `dayStartHour:00` to the same hour the next calendar day, regardless of wall-clock
  midnight.
- `startOfDay` preference added to `UserPreferencesDataStore`; a first-launch picker
  (shown once at onboarding) lets the user pick their preferred day start (midnight,
  3 AM, 4 AM, 5 AM, 6 AM, or a custom hour).
- Habits, streaks, Today-screen task filter, Pomodoro session stats, widgets, and the
  Haiku NLP date parser all derive "today" from `DayBoundary` rather than from the
  device calendar, so tasks due at "tonight 2 AM" stay in today's column until the user's
  configured day flips.
- `MaterialDatePicker` selection (which returns UTC midnight in millis) is corrected to
  local midnight before storage so due-date display matches what the user selected.

### Changed — Per-Theme Visual Design & Palette Reconciliation (PRs #530–537)
- Four `PrismTheme` palettes (Cyberpunk, Synthwave, Matrix, Void) now carry 13 named
  design tokens (`PrismThemeColors`: background, surface, surfaceVariant, border, primary,
  secondary, onBackground, onSurface, muted, urgentAccent, urgentSurface, tagSurface,
  tagText) reconciled against the `themes.js` design spec. Each token maps to a distinct
  Material `ColorScheme` slot so theme-switching is lossless. (PR #537)
- Per-theme visual flourishes (glow effects, gradient headers, decorative overlays) applied
  to Today, Task list, Settings, and shared components. (PRs #530, #534–535)
- Each `PrismTheme` has a dedicated body + display `FontFamily` pairing via `prismThemeFonts`.

### Changed — Pricing: Two-Tier Consolidation
- Consolidated three-tier pricing (Free / Pro $3.99 / Premium $7.99) into
  two-tier pricing (Free / Pro $3.99).
- All Premium-exclusive features (AI briefing/planner/time blocking,
  collaboration, integrations, full analytics, Google Drive backup) merged
  into the Pro tier.
- `ProFeatureGate` and `BillingManager` updated accordingly; the `PREMIUM`
  tier enum and all Premium-gating call sites removed.
- `prismtask_pro_monthly` is the only Play Store subscription product.

### Added — Projects Phase 1 (DB Migration 47→48)
- `ProjectEntity` extended with lifecycle columns: `description`, `status`
  (ACTIVE / COMPLETED / ARCHIVED), `start_date`, `end_date`,
  `theme_color_key`, `completed_at`, `archived_at`. Existing rows default to
  `status='ACTIVE'` with nulls — no backfill needed.
- New `MilestoneEntity` + `MilestoneDao`: title, `is_completed`, `order_index`,
  FK → `projects.id` ON DELETE CASCADE. Milestone CRUD + user-controlled
  reorder via `order_index`.
- `ProjectRepository` extended with status-aware streams, milestone CRUD,
  and `ProjectWithProgress` / `ProjectDetail` projections.
- Forgiveness-first project streak via `DailyForgivenessStreakCore` (shared
  with `StreakCalculator.calculateResilientDailyStreak` for habits). A
  project has *activity* on a day when any task completion, subtask
  completion (inherited via SQL join), or milestone completion occurred.
  `TaskCompletionEntity` rows are never deleted on reopen — activity is an
  event log, not derived state.
- `ProjectDao.getTaskActivityDates` SQL join that inherits subtask
  completions from their parent's `project_id` at read time.
- Tasks-tab `[Tasks | Projects]` segmented toggle with `ProjectsPaneViewModel`;
  filter selection persisted via `SavedStateHandle`.
- NLP project intents (`ProjectIntentParser`): `CreateProject`,
  `CompleteProject`, `AddMilestone`, `CreateTask`-with-project-hint.
- Project home-screen widget (one user-picked project per instance) mirroring
  `ProjectCard` layout; `ProjectWidgetConfigActivity` for placement-time
  project selection.
- Firestore sync for projects + milestones under `users/{uid}/projects/` and
  `users/{uid}/milestones/`; upload order enforces projects before milestones.
- `SyncMapper` defaults all post-v1.3 fields to null/`ACTIVE` for backward-
  compatible reads of pre-v1.4 Firestore documents.
- Room migration **47 → 48**: adds lifecycle columns to `projects`, creates
  `milestones` table.
- New unit tests: `DailyForgivenessStreakCoreTest`, `ProjectRepositoryTest`,
  `ProjectDaoTest` (extended), `Migration47To48Test`,
  `ProjectsPaneViewModelTest`, `ProjectIntentParserTest`.

### Changed — Room Database Migrations (v44–v48)
- **44→45** — Data-integrity hardening: `ON DELETE SET NULL` foreign keys
  backfilled for `study_logs.course_pick`, `study_logs.assignment_pick`, and
  `focus_release_logs.task_id`. Previously these columns used a default
  constraint that could leave orphan references after parent deletion.
- **45→46** — New `daily_essential_slot_completions` table for the Daily
  Essentials Today-screen section (seven virtual cards: Morning Routine,
  Medication, Housework, Schoolwork, Music Leisure, Flex Leisure, Bedtime
  Routine).
- **46→47** — `leisure_logs.custom_sections_state` TEXT column for
  per-slot `LeisureSlotConfig` persistence.
- **47→48** — See "Projects Phase 1" entry above.

### Changed — Export/Import Completeness Audit (2026-04-18)
- Bumped the JSON export format from `v4` to `v5`.
- Export now carries `schemaVersion`, `exportedAtIso`, `deviceModel`, and an
  `includeDerivedData` flag in the top-level metadata.
- Backups now round-trip a much larger preference surface: accessibility toggles,
  voice input, shake-to-bug-report, Pomodoro timer, the full 40+ key
  notification preferences, all neurodivergent-mode toggles, daily essentials,
  morning check-in, calendar sync config, onboarding flags, template seeding
  flags, theme recent-custom-colors, dashboard collapsed sections,
  habit-list streak/skip windows, and user-prefs task menu / card display /
  forgiveness / UI complexity tier.
- Added `daily_essential_slot_completions`, `usage_logs`, and `calendar_sync`
  to the exported entity set. The last two are opt-in under `derived`.
- New `ExportOptions(includeDerivedData)` and `ImportOptions(restoreDerivedData,
  replaceScope)` structs; legacy no-arg overloads preserved for call-site
  back-compat.
- `ReplaceSection` enum lets users replace tasks without losing habit history
  (and vice versa) in REPLACE mode.
- Projects and habits now merge with **last-write-wins** via `updatedAt` so
  "export → tweak locally → import" no longer silently drops the newer row.
- Orphan rows (habit completions with an unknown habit name, etc.) are counted
  as `orphansSkipped` and surfaced in `ImportResult.errors` instead of being
  dropped silently.
- See `docs/export_import_audit_2026-04-18.md` for the full diff, intentional
  exclusions (auth tokens, Firestore state, Play Billing cache), and known
  limitations (binary attachment/sound files remain out-of-band).



### Added — Daily Essentials Section
- New "Daily Essentials" section on the Today screen aggregating seven virtual
  cards (Morning Routine, Medication, Housework, Schoolwork, Music Leisure,
  Flex Leisure, Bedtime Routine) from existing data sources. No new tables,
  no Room migrations.
- `DailyEssentialsPreferences` DataStore stores the Housework and Schoolwork
  habit pointers and the one-time onboarding-hint flag.
- `MedicationStatusUseCase` unifies the three medication scheduling sources
  (interval habits, self-care steps, specific-time slots) into a single
  "is any dose due and untaken" state with a deterministic dedup priority
  (SPECIFIC_TIME > INTERVAL_HABIT > SELF_CARE_STEP).
- `DailyEssentialsUseCase` aggregates all seven cards behind a single
  `StateFlow<DailyEssentialsUiState>` for the Today VM.
- Three narrow DAO additions: `HabitDao.getHabitsActiveForDay`,
  `SchoolworkDao.getAssignmentsDueBetween`, and
  `SelfCareDao.getStepsForRoutineByTimeOfDay`.
- New `DAILY_ESSENTIALS` entry in `TodaySectionId`, visible by default in
  the free tier, ordered right after `HABITS`.
- Settings → Layout now lists the "Daily Essentials" toggle alongside the
  other dashboard sections. A dedicated settings section lets users pick
  the Housework and Schoolwork habits and links to the existing Self-Care,
  Medication, and Leisure management screens.

### Added — Leisure Mode Customization
- Per-slot `LeisureSlotConfig` (music + flex) in `LeisurePreferences`: label,
  emoji, duration, grid columns, auto-complete toggle, enabled flag, and a
  hidden built-ins list. Defaults fall back to the legacy 15-min music /
  30-min flex behavior, so existing users see no change until they opt in.
- New `LeisureSettingsScreen` and `LeisureSettingsViewModel` under
  `ui/screens/leisure/settings/`, reachable via a gear icon on Leisure Mode's
  top bar and a new `PrismTaskRoute.LeisureSettings` route.
- `LeisureScreen` / `LeisureViewModel` now iterate over slot configs instead
  of hardcoding two branches; disabled slots hide entirely and the progress
  card's daily target becomes `0 / 1 / 2` based on enabled slots.
- `DailyEssentialsUseCase` threads the slot config through
  `LeisureCardState` (new `label` + `enabled` fields), so the Today screen's
  leisure cards follow the user's custom label and disappear when a slot is
  disabled.
- Auto-complete at the duration threshold is now a per-slot toggle; off lets
  users finish manually whenever they want.
- Dedicated unit tests for slot-config persistence, bounds coercion, and
  built-in hide/unhide round-trips.

### Added — Work-Life Balance Engine (V1)
- New `LifeCategory` enum (WORK / PERSONAL / SELF_CARE / HEALTH / UNCATEGORIZED)
  with a `life_category` column on `tasks` (migration 32 → 33).
- `LifeCategoryClassifier` — fast, offline keyword-based classifier with
  configurable word lists per category, used as the default auto-classification
  path for new tasks.
- `BalanceTracker` — computes current week + 4-week rolling category ratios,
  overload detection, and dominant category from a task pool.
- `WorkLifeBalancePrefs` in `UserPreferencesDataStore` — target ratios, auto-
  classify toggle, balance bar toggle, overload threshold (5–25%).
- Organize tab: "Life Category" chip selector with Auto + 4 colored category
  chips. Editing a task now persists the life category to Room + Firestore
  sync + JSON export/import.
- Today screen: new compact `TodayBalanceSection` stacked bar above the task
  list showing the week's distribution with an overload warning badge when
  work exceeds target.
- Settings: new "Work-Life Balance" section with auto-classify toggle, balance
  bar toggle, per-category target sliders (with live sum validation), and
  overload threshold slider.
- NLP quick-add: `#work`, `#personal`, `#health`, `#self-care` (and
  `#selfcare`) set `lifeCategory` in addition to being added as regular tags.
  The hyphenated `#self-care` form is handled explicitly so the dash isn't
  dropped.
- QuickAddViewModel now falls back to `LifeCategoryClassifier` for tasks
  created without a manual category tag, so Today's balance bar stays live
  without extra user effort.
- Filter panel: "Life Category" multi-select filter with colored chips; the
  `TaskFilter` model and task-list filtering pipeline both respect it.
- Added 26 unit tests: `LifeCategoryClassifierTest` (11), `BalanceTrackerTest`
  (10), `NaturalLanguageParserTest` life-category additions (5).

### Added — Morning Check-In Resolver (V4 phase 1)
- `MorningCheckInResolver` domain planner that decides whether to
  prompt the user for the morning check-in and which steps to show.
- `CheckInStep` enum (MOOD_ENERGY, MEDICATIONS, TOP_TASKS, HABITS,
  BALANCE, CALENDAR) so the HorizontalPager screen renders steps in
  the vision deck's default order.
- `MorningCheckInConfig` captures the prompt-before-hour threshold
  and per-step visibility toggles. Defaults match the vision deck:
  11am threshold, all steps visible.
- `CheckInPlan` carries the resolved prompt decision, the ordered
  step list, the top 3 highest-urgency tasks for the day, and
  today's habits so the pager can render without re-querying.
- Top task selection: filters out completed + archived + tasks not
  due today, sorts by priority descending then due date ascending,
  takes 3. Priority 4 (Urgent) naturally floats to the top.
- Prompt logic: suppresses the prompt after the configured hour,
  after the user has already completed a check-in today, or when
  the feature is disabled in Settings.
- Habits step is hidden when the user has no habits so the flow
  doesn't show an empty page.
- 9 new unit tests covering the prompt decision truth table, top
  task selection (priority and completion filtering), hiding
  habits when empty, disabling medications via config, and the
  full enabled-step ordering.

Scoped for follow-up:
- CheckInLogEntity Room table + DAO + repository to record the
  "last completed date" the resolver consumes.
- Full MorningCheckInScreen HorizontalPager UI with the six step
  composables.
- Today screen integration: the "Start Your Morning Check-In?"
  banner card.
- Check-in streak analytics ("7-day check-in streak 🔥").

### Added — Boundary Rules (V3 phase 1)
- `BoundaryRule` runtime model with rule type (BLOCK_CATEGORY,
  SUGGEST_CATEGORY, REMIND), target `LifeCategory`, start/end times,
  active days, and enabled flag. Windows can straddle midnight
  (22:00 → 06:00 is a valid night-shift rule).
- `BoundaryEnforcer` evaluates a rule list against a (category, now)
  pair and returns `BoundaryDecision.Allow`, `Block(rule, reason)`,
  or `Suggest(rule, category)`. BLOCK rules win over SUGGEST rules
  so the user's explicit "no" overrides a soft category nudge.
- Two built-in rules seeded on first use: Evening Wind-Down
  (block WORK 20:00 weekdays) and Weekend Rest (suggest SELF_CARE
  all-day Sat/Sun).
- `BoundaryRuleParser.parse` — small regex-based NLP for phrases
  like "No work after 7pm on weekdays", "Block work after 20:00",
  and "No self-care after 10pm every day". Returns null for
  anything that doesn't match so the UI falls back to the manual
  rule editor.
- 11 new unit tests covering BLOCK during/outside window, category
  mismatch, disabled rules, SUGGEST path, BLOCK precedence over
  SUGGEST, the midnight-straddle window, and all three parser
  forms plus the no-match case.

The shared `WeeklyReviewAggregator` added in the V6 commit above
already delivers the V3 weekly-balance-report data layer. The full
WeeklyBalanceReportScreen UI + boundary_rules Room table (with DAO,
repository, and seeded migration) are scoped for a follow-up commit
so this change stays focused on the enforcement algorithm.

### Added — Clinical Report Generator (V8 phase 1)
- New `ClinicalReportGenerator` use case that produces a structured
  `ClinicalReport` with six toggleable sections (Overview, Medication,
  Mood & Energy, Task Completion, Life Balance, Burnout Score Trend)
  plus a plain-text rendering suitable for pasting into a patient
  portal message or sharing via email.
- `ClinicalReportInputs` accepts all sections as optional — any
  missing source (mood tracking disabled, no medication logging)
  renders as "No data for this period" so feature flags never block
  report generation.
- `ClinicalReportSection` enum with toggle support so the config
  dialog can include/exclude individual sections.
- Medication section formats pill count + adherence percentage +
  last refill date per medication, sourced from V10's
  `MedicationRefillEntity`.
- Mood & energy section surfaces averages plus low-mood and
  high-mood day counts.
- Life balance section uses V1's `LifeCategory` to compute
  percentages of completed tasks by category.
- Burnout section (from V2 scores) emits average, peak, and trough.
- Privacy footer: "Generated by PrismTask. Not a medical document."
- 8 new unit tests covering empty inputs, per-section aggregation,
  section filtering, and the plain-text renderer.

Scoped for follow-up: the on-device PDF writer using
`android.graphics.pdf.PdfDocument` that walks the same section
blocks, the "Export Health Report" Settings entry with the date-range
and section-toggle dialog, and the Android share intent.

### Added — Weekly Review Aggregator (V6 + V3 shared core)
- New `WeeklyReviewAggregator` use case: pure-function weekly stats
  rollup shared by the Weekly Balance Report (V3) and the AI Weekly
  Review (V6) so the two features can never drift.
- `WeeklyReviewStats` data class: `weekStart`, `weekEnd`, `completed`,
  `slipped`, `rescheduled`, `byCategory` map, `carryForward` task list,
  plus derived `total` and `completionRate` properties.
- Configurable week-start day (Monday default per ISO-8601, Sunday
  supported for US convention) so it plugs into the existing
  `StartOfWeek` user preference without extra translation.
- Categorizes completions by `LifeCategory` (V1) so the AI Weekly
  Review prompt can say "Work dominated Tue–Thu" from raw stats.
- Approximates rescheduled tasks via `updatedAt > createdAt` with a
  due date pushed past the week's end. Exact tracking would need a
  separate reschedule log — noted as a follow-up.
- 10 new unit tests covering empty input, in-window vs out-of-window
  completions, open-but-due-this-week slippage, archived exclusion,
  reschedule detection, completion rate math, Monday start-of-week
  normalization, 7-day end offset, and uncategorized-task handling.

Scoped for follow-up: the actual `WeeklyBalanceReportScreen` UI with
the donut chart and 4-week sparklines (V3), the AI Weekly Review
screen with Claude-generated wins/misses/suggestions (V6), the
Sunday evening notification, and the `weekly_reviews` Room table
for historical review storage.

### Added — Conversation → Tasks Extractor (V9 phase 1)
- New `ConversationTaskExtractor` domain use case: offline regex-based
  action-item extraction for pasted Claude/ChatGPT/email/meeting text.
  Detects TODO/Action item markers, "I'll", "I will", "I should",
  "I need to", "I have to", "Let's", "Can you", "Could you", and
  imperative markdown bullet lines. Ordered by confidence 0.50–0.95.
- `ExtractedTask` data class with title + confidence + optional
  source label / suggested priority / due date / project — the
  Premium AI path can fill in the latter three fields, the offline
  regex path only sets the title.
- Input guards: max 10,000 chars, title range 3–120 chars. Duplicates
  are deduped by lowercase comparison so "TODO: fix the bug" and
  "I should fix the bug" collapse into one candidate.
- Title post-processing: trim trailing punctuation, title-case the
  first letter, preserve internal casing.
- 14 new unit tests covering empty/oversized input, each major
  pattern, dedup semantics, multi-pattern ordering by confidence,
  short-title rejection, no-match handling, and source propagation.

Scoped for follow-up: the Paste Conversation screen, Android share
intent receiver, Claude Haiku backend `/api/v1/tasks/extract-from-text`
endpoint, and the review-before-create UI.

### Added — Energy-Aware Pomodoro Planner (V11 phase 1)
- New `EnergyAwarePomodoro` use case that adapts the classic Pomodoro
  work/break lengths based on the user's logged energy level (1–5):
  low energy gets 15-min sessions with 10-min breaks, medium gets the
  classic 25/5, and high energy gets 35–45 min deep-work sprints.
- `PomodoroSessionConfig` carries the chosen lengths plus a rationale
  string for the UI ("Classic Pomodoro — you're in the groove", etc.).
- `DefaultPomodoroConfig` fallback for users with no mood/energy
  tracking — the planner returns the classic defaults unchanged so the
  feature rolls out safely without disrupting existing users.
- `planFromLogs` convenience wrapper picks the latest
  `MoodEnergyLogEntity` row and forwards to `plan`.
- 11 new unit tests covering all five energy buckets, null-energy
  fallback, out-of-range clamping, latest-log selection, empty-list
  handling, and rationale presence.

Scoped for follow-up: wiring the planner into `SmartPomodoroScreen`'s
start-session flow, an energy heatmap overlay on the weekly planner,
and the post-session "How's your energy now?" quick-prompt.

### Added — Medication Refill Tracking (V10 phase 1)
- New `medication_refills` Room table (migration 34 → 35) storing per-
  medication pill counts, dosage, pharmacy info, and reminder lead time.
- `MedicationRefillEntity`, `MedicationRefillDao` with upsert/observe/
  get-by-name queries.
- `RefillCalculator` pure-function helper with `forecast`, `applyDailyDose`,
  `applyRefill`, and `adherenceRate`. Forecasts produce a `RefillForecast`
  with `daysRemaining`, `refillDateMillis`, `reminderDateMillis`, and a
  `RefillUrgency` bucket (HEALTHY / UPCOMING / URGENT / OUT_OF_STOCK).
- 16 new unit tests covering: 30 pills @ 1/day, 60 pills @ 2/day, 60 pills
  @ 2-per-dose, refill-date anchoring, 3-day reminder offset, all four
  urgency buckets, daily dose decrement (including multi-dose and the
  zero floor), refill reset semantics, and adherence math.

Scoped for follow-up: UI surface in MedicationScreen for entering
pill counts, pharmacy fields, NLP "refill X in N days" parsing, the
adherence analytics screen, and wiring the refill reminder into
MedicationReminderScheduler.

### Added — Mood & Energy Tracking Foundation (V7 phase 1)
- New `mood_energy_logs` table (Room migration 33 → 34) with
  `(date, time_of_day)` unique index so morning and evening check-ins
  can coexist on the same calendar date.
- `MoodEnergyLogEntity`, `MoodEnergyLogDao`, and `MoodEnergyRepository`
  including an `upsertForDate` helper that replaces an existing entry
  for the same slot instead of creating duplicates.
- `MoodCorrelationEngine` computes Pearson correlation between mood or
  energy and six supported factors (total tasks, work tasks, self-care,
  habit rate, medication adherence, burnout score). Requires at least
  7 daily observations before reporting; below that, returns empty so
  the UI can show a "not enough data" state.
- `CorrelationFactor`, `CorrelationResult`, and `CorrelationStrength`
  domain types plus a `plainEnglish()` helper for the UI.
- `averageByDay` helper folds morning + evening entries into one
  numeric tuple per day so correlation input stays clean.
- 11 new unit tests in `MoodCorrelationEngineTest` (empty data, 7-day
  floor, perfect +/- correlations, flat series zero, sort order,
  day-averaging, strength buckets).

Scoped for follow-up: mood/energy check-in UI (widget + today prompt),
analytics screen with trend lines, morning check-in integration, and
burnout-scorer mood factor.

### Added — Burnout Score & Overload Alert (V2 phase 1)
- New `BurnoutScorer` use case that computes a 0–100 composite score from
  six weighted inputs (work-ratio overshoot, overdue tasks, skipped
  self-care, medication gap, streak breaks, 2-day rest deficit) and maps
  it to a `BurnoutBand` (BALANCED / MONITOR / CAUTION / HIGH_RISK).
- `BurnoutInputs` and `BurnoutResult` data classes with per-component
  contributions so the UI can explain *why* a band was assigned.
- `BurnoutScorer.computeFromTasks` convenience wrapper that derives the
  overdue count, skipped-self-care ratio, and rest deficit directly from
  a TaskEntity list so TodayViewModel can wire it with a single combine.
- `TodayViewModel.burnoutResult` StateFlow exposes the live score to the
  Today screen and feeds `TodayBalanceSection`'s new burnout badge chip.
- `TodayBalanceSection` gains a colored burnout badge next to the
  "Balance" header showing the numeric score in the band's color.
- `OverloadBanner` composable: full-width dismissible card that appears
  on the Today screen when the user's work ratio exceeds their target by
  the configured threshold. Tapping Dismiss hides it for the session.
- 12 new unit tests in `BurnoutScorerTest` covering component ceilings,
  band boundary mapping, all-zero and all-max inputs, and the score
  clamping invariant.

Note: self-care nudge rotation and the daily overload notification
(WorkManager worker) are scoped for a follow-up commit.

### Added — Forgiveness-First Streak System (V5)
- `ForgivenessConfig` + `StreakResult` data classes exposing `strictStreak`,
  `resilientStreak`, `missesInWindow`, `gracePeriodRemaining`, and the list
  of dates that were forgiven so the UI can render them as partial hits.
- `StreakCalculator.calculateResilientDailyStreak` walks backwards from
  today (or yesterday if today isn't complete yet), tolerating up to
  `allowedMisses` misses inside a rolling `gracePeriodDays` window. A run
  of consecutive misses that starts today + yesterday is treated as a
  hard reset. The walk also stops at the earliest known completion so
  pre-habit history doesn't unfairly count as misses.
- `StreakCalculator.calculateResilientStreak` dispatches by frequency:
  daily habits get the full forgiving walk, other frequencies (weekly,
  monthly, bimonthly, quarterly, fortnightly) fall back to strict streaks
  as a follow-up pass.
- `ForgivenessPrefs` in `UserPreferencesDataStore` — enable/disable the
  system, grace period (1–30 days), allowed misses (0–5).
- Settings screen: new "Forgiveness-First Streaks" section with a
  primary toggle plus grace-window and allowed-misses sliders that reveal
  only when the toggle is on.
- 10 new unit tests in `ForgivenessStreakTest` covering the spec's core
  cases (5-on 1-miss 3-on, grace exhausted, classic mode, yesterday-only
  completion, empty history, pre-history truncation, non-daily fallback,
  zero allowance).

## [1.3.0] — 2026-04-11 — Voice, Widgets, Accessibility, Analytics, Integrations & Three-Tier Pricing

Skips the v1.2.0 tag and ships everything developed since v1.1.0 together.

### Added — Voice Input & Accessibility
- Speech-to-task creation via Android SpeechRecognizer
- Voice commands for hands-free task management
- Text-to-speech readback of tasks and briefings
- Hands-free mode with continuous listening
- TalkBack/screen reader support throughout all screens
- Dynamic font scaling respecting system accessibility settings
- High-contrast mode support
- Keyboard navigation for all interactive elements
- Reduced motion option for animations

### Added — Widget Overhaul
- Redesigned Today, Habit Streak, and Quick-Add widgets
- 4 new widgets: Calendar, Productivity, Timer, Upcoming
- Per-instance widget configuration activities
- Widget background opacity and section toggles

### Added — Advanced Analytics & Time Tracking
- Productivity dashboard with daily/weekly/monthly views
- Task completion burndown charts
- Habit-productivity correlation analysis
- Time tracking per task with start/stop logging
- Heatmap visualization for activity patterns
- Time-tracked badge on task cards (configurable)

### Added — API Integrations
- Gmail integration: auto-create tasks from starred emails
- Slack integration: create tasks from Slack messages
- Google Calendar prep tasks: auto-generate prep tasks before meetings
- Webhook/Zapier endpoint for external automations
- Suggestion inbox for reviewing auto-created tasks

### Added — Customization & Personalization
- Centralized UserPreferencesDataStore for all customization settings
- Configurable swipe actions (7 options per direction: complete, delete,
  reschedule, archive, move to project, flag, none)
- Flagged task system with filter support
- Custom accent color picker with hex input and recent colors
- Compact mode and configurable card corner radius
- Customizable task card display fields (12 toggleable metadata fields)
- Minimal card style option
- User-configurable urgency scoring weights with live preview
- Configurable task defaults and smart defaults engine
- Custom NLP shortcuts/aliases with quick-add suggestion chips
- Saved filter presets with quick-apply chips
- Customizable long-press context menu (reorderable, toggleable actions)
- Customizable Today screen section order and visibility
- Advanced recurrence patterns: weekday, biweekly, custom month days,
  after-completion
- Notification profiles with multi-reminder bundles and escalation
- Quiet hours with deferred reminders
- Daily digest notification
- Project and habit template systems with built-in templates

### Added — Three-Tier Pricing *(consolidated to two-tier in v1.4.0)*
- Free: core tasks, habits, templates (local), calendar sync, widgets, all views
- Pro ($3.99/mo): + cloud sync, template sync, AI Eisenhower, AI Pomodoro,
  basic analytics, time tracking, smart defaults, notification profiles,
  unlimited saved filters, custom templates
- Premium ($7.99/mo): + AI briefing/planner/time blocking, collaboration,
  integrations, full analytics, Drive backup
- Debug tier override in Settings (debug builds only)
- **Note:** Premium tier merged into Pro in v1.4.0; see Unreleased section.

### Added — Bookable Habits
- Booking status tracking for habit logs
- Activity history with booking state

### Changed — Code Quality
- Refactored SettingsScreen.kt from ~2,800 to ~300 lines (extracted into
  section composables)
- Refactored TodayScreen.kt from ~2,290 to ~300 lines (section composables)
- Refactored AddEditTaskSheet.kt from ~2,275 to ~250 lines (tab composables)
- Refactored TaskListScreen.kt from ~2,250 to ~350 lines (component extraction)
- Refactored TaskListViewModel.kt into sort/filter/multiselect/grouping helpers
- Refactored SettingsViewModel.kt into focused delegates
- Split NavGraph.kt into navigation group extensions
- Split HabitListScreen, MedicationScreen, SelfCareScreen, DataImporter,
  BackendSyncService, and 8 additional 800+ line files into components
- Extracted Room migrations into grouped files
- No file in the codebase exceeds ~500 lines

### Added — Testing
- Repository unit tests: Task, Habit, Project, Tag (~43 tests)
- Use case tests: ParsedTaskResolver, ChecklistParser, TodoListParser (~30 tests)
- DataImporter unit tests with merge/replace/edge cases (~15 tests)
- Notification/reminder scheduling tests (~22 tests)
- DataStore preferences tests (~24 tests)
- ViewModel unit tests: Today, AddEditTask, TaskList, HabitList, Eisenhower,
  Settings, SmartPomodoro (~76 tests)
- Smoke tests: habits, search/archive, tags/projects, views, settings,
  multi-select, edge cases, offline, recurrence, export/import (~62 tests)
- DAO instrumentation tests: Habit, Tag, Template, Attachment (~24 tests)
- Backend router tests: dashboard, export, search, app_update, projects (~34 tests)
- Backend service tests: recurrence, urgency, NLP edge cases (~28 tests)
- Backend integration workflows and stress tests (~16 tests)

### Infrastructure
- Google Play Billing library for three-tier subscription
- BillingManager singleton with purchase flow and status caching
- ProFeatureGate updated for three-tier access control
- Release signing configuration and App Bundle support
- GitHub Actions release workflow for AAB builds
- kotlinx-coroutines-test, Turbine, and MockK test dependencies

## [1.1.0] — 2026-04-10 — PrismTask Rebrand, AI Productivity, Freemium & Play Store

### Changed — Rebrand
- App renamed from AveryTask to PrismTask
- Package renamed from com.averycorp.averytask to com.averycorp.prismtask
- All UI strings, documentation, and metadata updated

### Added — AI Productivity
- Eisenhower Matrix view: 2x2 grid with AI auto-categorization via Claude Haiku
- Smart Pomodoro: AI-planned focus sessions with configurable work style
- AI settings: auto-categorize toggle, default focus style, Eisenhower badges
- Eisenhower quadrant badges on task cards
- Focus quick-start button on Today screen

### Added — Play Store & Freemium
- Google Play Billing integration for Pro subscription ($3.99/month)
- Freemium feature gating: AI, cloud sync, and collaboration are Pro
- Pro upgrade prompts with feature descriptions and free trial
- Subscription management in Settings
- Privacy Policy and Terms of Service
- Play Store listing metadata and asset specifications
- Release signing configuration and App Bundle support
- Release checklist documentation

### Added — Testing
- 28 automated Android UI smoke tests (Compose testing)
- 28 backend API integration tests
- Test data seeding infrastructure

### Infrastructure
- Google Play Billing library integration
- BillingManager singleton with purchase flow and status caching
- ProFeatureGate for consistent feature access control
- ProGuard rules for Play Billing, Calendar API, and AI models
- Release build with R8 optimization and resource shrinking
- GitHub Actions release workflow for AAB builds

## [1.0.0] — 2026-04-10 — Stable Release

- Bump version to 1.0.0 stable release

## [0.9.0] — 2026-04-09 — UX Overhaul, QoL Features & Task Templates

### Added — UX Overhaul
- Today screen: compact progress header bar replacing large circular ring
- Collapsible sections with remembered expand/collapse state (DataStore-persisted)
- Overdue section visual urgency: red tint background and accent bar
- Habits displayed as horizontal scrollable chips with tap-to-complete
- Floating quick-add bar pinned above navigation (always accessible)
- "All Caught Up" celebration state when all tasks are completed
- Plan-for-Today sheet: inline quick-add, search filter, batch planning, sort options

### Added — Task Editor Redesign
- Task editor converted to full-screen modal bottom sheet
- Three-tab layout: Details / Schedule / Organize
- Title and priority selector always visible in sheet header
- Details tab: inline subtask add, expandable description/notes fields
- Schedule tab: quick date chips, conditional time/reminder visibility, duration presets
- Organize tab: project card selector, inline tag toggle chips, smart context defaults
- Subtask drag-to-reorder with smooth animations
- Subtask swipe-to-complete and swipe-to-delete gestures
- Unsaved changes detection with discard confirmation dialog

### Added — Quality of Life
- Sort preference memory: each screen remembers its last sort mode (DataStore-persisted)
- Drag-to-reorder tasks in "Custom" sort mode with persistent order
- Quick reschedule: long-press any task card for date shortcuts (Today, Tomorrow, Next Week, Pick Date)
- Duplicate task from context menu or editor with optional subtask copying
- Bulk edit extensions: batch change priority, due date, tags for multi-selected tasks
- Move tasks between projects via long-press menu and drag in grouped-by-project view
- "Custom" sort mode added to all sort pickers

### Added — Task Templates
- Template system: save reusable task blueprints with pre-filled fields
- Template CRUD: create, edit, delete templates with icon, category, and all task fields
- Create task from template with pre-filled editor (user can adjust before saving)
- Quick-use: tap template in list to create task instantly
- Save existing task as template from editor overflow menu
- NLP shortcut: type "/templatename" in quick-add bar to use a template
- 6 built-in templates: Morning Routine, Weekly Review, Meeting Prep, Grocery Run, Assignment, Deep Clean
- Template usage tracking (count and last used date)
- Template categories with filter chips

### Added — Backend (Task Templates)
- Template CRUD endpoints (GET/POST/PATCH/DELETE /api/v1/templates)
- Use template endpoint (POST /api/v1/templates/{id}/use)
- Create template from task endpoint (POST /api/v1/templates/from-task/{task_id})
- Templates included in sync push/pull
- Alembic migration for task_templates table

### Changed
- Room database version upgraded (sortOrder column, task_templates table)
- Multi-select bottom action bar redesigned with 6 operation icons
- Task card long-press shows quick reschedule popup

### Infrastructure
- New DataStore preferences: SortPreferences, EditorPreferences, DashboardPreferences
- New Room entities: TaskTemplateEntity
- New screens: TemplateListScreen, AddEditTemplateScreen
- New components: QuickReschedulePopup, collapsible section headers, horizontal habit chips

## [0.8.0] — 2026-04-09 — Backend Integration

### Added

- FastAPI backend with PostgreSQL (deployed on Railway)
- Claude Haiku-powered NLP task parsing via backend API
- Backend sync (push/pull) with manual trigger in Settings
- Cloud export/import (JSON) via backend
- JWT authentication with token refresh
- Self-update system via Firebase App Distribution
- Backend CI pipeline (GitHub Actions)
- API documentation (auto-generated Swagger UI)

### Changed

- NLP parser now tries Claude API first, falls back to local regex when offline
- README updated with backend docs, screenshots, architecture diagram, CI badges

## [0.7.0] - 2026-04-06

### Added

- Housework as built-in habit with tiered routine tracking (quick, standard, deep clean)
- Recurring habits tab with weekly, fortnightly, and monthly frequency options
- Flexible medication scheduling with interval-based or specific times-of-day modes
- Self-care and medication habits displayed on Today screen
- Custom habit category support with user-defined categories
- Self-care habit categories (skincare, grooming, dental, etc.)
- Settings toggles for self-care, medication, school, and leisure modes
- App self-update system: Settings button checks GitHub for new APK and installs updates
- Update detection comparing commit time against app install time
- FastAPI web backend with PostgreSQL, JWT auth, full CRUD, NLP parsing, and search endpoints
- Backend API: tags, habits, sync, and export endpoints with complete data model
- Debug APK CI workflow triggered on pull requests

### Fixed

- Google Sign-In "no credentials available" error
- GitHub API 403 errors by adding required User-Agent header
- Debug build failures from missing debug package in google-services.json
- Debug APK install conflict via `.debug` applicationId suffix
- Update button conflict with unique filenames and ContentResolver cleanup
- Package conflict on update by falling back to debug signing config
- Kotlin compiler failures (DataStore property name clash, missing import)
- Release APK CI curl error by adding signing config
- KeytoolException for tag numbers over 30

### Infrastructure

- Optimized CI performance across all workflows
- Build workflow runs only on pushes to main
- PR-only debug CI workflow for update button tests
- 154 unit tests across 11 test files (up from 137)
- AppUpdater test suite (11 tests)

## [0.6.0] - 2026-04-05

### Added

- Schoolwork tracking: UC Boulder coursework checklists (CSCA 5424, CSCA 5454)
- Self-care and medication tracking modes
- Leisure tracker with daily music practice and flexible activity tracking
- Custom app icon
- GitHub Actions CI workflow for automated debug APK builds

### Infrastructure

- Restored native Compose screens (removed experimental React/WebView layer)
- CI fixes for JDK, Gradle daemon, and SDK configuration

## [0.5.0] - 2026-04-05

### Added

- Habit tracking system: daily/weekly frequency, color, icon, category
- Streak engine: current streak, longest streak, completion rates (7/30/90 day), best/worst day
- Habit analytics screen: GitHub-style contribution grid, weekly trend chart, day-of-week bar chart
- Habits integrated into Today screen with combined progress ring
- Weekly habit summary notification via WorkManager (Sunday 7PM)
- Habit streak and quick-add home screen widgets (Glance for Compose)
- Customizable dashboard section ordering and visibility via DataStore
- Firestore sync for habits and habit completions

### Infrastructure

- Room database v7 with habit entities and migrations 6-7

## [0.4.0] - 2026-04-05

### Added

- Firebase Authentication with Google Sign-In via Credential Manager
- Firestore bidirectional sync for tasks, projects, and tags
- Offline queue with pending action tracking and retry logic
- Real-time snapshot listeners for cross-device updates
- JSON export (full backup) and CSV export (tasks only)
- JSON import with merge (skip duplicates) or replace (delete-all-first) modes
- Sync status indicator component

### Infrastructure

- Room database v6 with sync metadata and calendar sync entities
- ProGuard rules for Firebase and sync models

## [0.3.0] - 2026-04-05

### Added

- Today focus screen: progress ring, overdue/today/planned/completed sections, plan-for-today sheet
- NLP quick-add bar: parse dates, tags (#), projects (@), priority (!), recurrence from text
- Smart suggestions for tags and projects based on usage keyword matching
- Urgency scoring (0-1) based on due date proximity, priority, age, and subtask progress
- Week view: 7-day column layout with task cards and navigation
- Month view: calendar grid with density dots and day detail panel
- Usage logging for suggestion engine

### Infrastructure

- NaturalLanguageParser, ParsedTaskResolver, UrgencyScorer, SuggestionEngine use cases
- Room database v5 with usage log entity

## [0.2.0] - 2026-04-05

### Added

- Tags system: entity, many-to-many relations, management screen, tag selector
- Filter panel with advanced filtering (tags, priorities, projects, date range)
- Full-text search with highlighted results
- Dark/light theming with 12 accent color options and Settings screen
- Task notes and image/link attachments
- Archive system with auto-archive worker, archive screen, and settings
- Accessibility improvements and PriorityColors consistency

### Infrastructure

- Room database v3 with tag and attachment entities, migrations 1-3
- ProGuard rules for new models

## [0.1.0] - 2026-04-17

### Added

- Task management with create, edit, delete, and completion tracking
- Project organization with custom colors and emoji icons
- Subtask support with inline add, nested display, and completion counts
- Recurring tasks (daily, weekly, monthly, yearly) with configurable intervals, day-of-week selection, end conditions, and automatic next-occurrence creation on completion
- Task reminders via Android notifications with quick-select offsets (at due time, 15 min, 30 min, 1 hour, 1 day before)
- Notification actions: tap to open app, "Complete" button to mark done from the notification
- Boot persistence for scheduled reminders
- Upcoming grouped view (Overdue / Today / Tomorrow / This Week / Later / No Date)
- Flat list view with sorting (due date, priority, date created, alphabetical)
- Project filtering via horizontal chip row
- Overdue detection with red card styling, left border, and badge count in the top bar
- Quick-select date chips (Today, Tomorrow, +1 Week, Pick Date) in the task editor
- Smart date labels (Today / Tomorrow / Overdue + formatted date)
- Swipe-to-complete (right, green) and swipe-to-delete (left, red) with undo snackbars
- Priority system (None / Low / Medium / High / Urgent) with colored dots and centralized PriorityColors theme
- Material 3 DatePicker and TimePicker dialogs
- Recurrence selector UI with type chips, interval picker, day-of-week multi-select, and end condition options
- Reminder picker dialog with preset offset options
- Reusable EmptyState component across task list, project list, and filtered views
- Slide + fade navigation transitions (300ms)
- Custom typography scale
- animateContentSize on subtask sections
- Hilt dependency injection throughout
- Room database with TaskEntity, ProjectEntity, TaskDao, ProjectDao
- Foreign keys: task-to-project (SET_NULL), task-to-parent (CASCADE delete)
- Indices on projectId, parentTaskId, dueDate, isCompleted, priority
- POST_NOTIFICATIONS permission request on Android 13+
- SCHEDULE_EXACT_ALARM and RECEIVE_BOOT_COMPLETED permissions
- ProGuard/R8 rules for Room, Gson, and domain models
- Release build with minification and resource shrinking enabled
- Unit tests for RecurrenceEngine (18 tests)
- Integration tests for DAO operations and recurrence completion flow

### Infrastructure

- Single-activity Compose architecture with Jetpack Navigation
- MVVM with ViewModels, Repositories, and Room DAOs
- Material 3 theming with dynamic color support (Android 12+)
- Edge-to-edge display
- Kotlin 2.2.10, Compose BOM 2024.12.01, Gradle 8.13
- Min SDK 26 (Android 8.0), Target SDK 35 (Android 15)

[1.4.0]: https://github.com/akarlin3/prismTask/releases/tag/v1.4.0
[0.7.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.7.0
[0.6.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.6.0
[0.5.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.5.0
[0.4.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.4.0
[0.3.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.3.0
[0.2.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.2.0
[0.1.0]: https://github.com/akarlin3/prismTask/releases/tag/v0.1.0-mvp





