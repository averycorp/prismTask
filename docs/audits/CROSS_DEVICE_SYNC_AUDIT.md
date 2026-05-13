# Cross-Device Sync Coverage Audit

**Scope.** Verify that every Room entity, DataStore preference file, and
load-bearing user input is mirrored to the backend so a fresh install on a
second device reconstitutes the user's complete state. The operator's brief
was: "Ensure every possible feature and preference and user input is saved
to the backend, so that cross-device sync works perfectly."

**Method.** Inventoried every `@Entity` under `data/local/`, every DataStore
file under `data/preferences/`, and every sync orchestrator under
`data/remote/`. Cross-referenced inventories against `SyncDispatchTables`,
`PreferenceSyncModule`, `SyncListenerManager`, the dedicated theme/sort
preference services, and `BackendSyncService`. Spot-checked load-bearing
user inputs that are NOT entities (widget config, active timer, task
drafts, search history, recent pickers).

---

## Baseline (synced today)

**Room entities → Firestore via `SyncService` + `SyncDispatchTables`** (41 dispatch entries):
tasks, projects, tags, habits, habit_completions, habit_logs,
task_completions, task_timings, milestones, project_phases,
project_risks, external_anchors, task_dependencies, task_templates,
project_templates, habit_templates, courses, course_completions,
leisure_logs, self_care_steps, self_care_logs, medications,
medication_doses, medication_slots, medication_slot_overrides,
medication_tier_states, medication_refills, notification_profiles,
custom_sounds, saved_filters, nlp_shortcuts, boundary_rules,
automation_rules, check_in_logs, mood_energy_logs, focus_release_logs,
weekly_reviews, daily_essential_slot_completions, assignments,
attachments, study_logs.

**Room entities mirrored from the FastAPI backend (server-authoritative):**
`chat_messages`, `user_ai_preferences` (REST round-trip; not in
Firestore dispatch tables on purpose — see § "Items 14–15"). Leisure
activities/sessions/settings go through `LeisureSyncService` against
`/api/v1/leisure/...`.

**DataStore files → Firestore via `GenericPreferenceSyncService`** (19 specs in
`di/PreferenceSyncModule.kt`):
`a11y_prefs`, `advanced_tuning_prefs`, `archive_prefs`, `coaching_prefs`,
`daily_essentials_prefs`, `dashboard_prefs`, `habit_list_prefs`,
`leisure_budget_prefs`, `medication_prefs`, `morning_checkin_prefs`,
`nd_prefs`, `notification_prefs`, `onboarding_prefs`, `tab_prefs`,
`task_behavior_prefs`, `template_prefs`, `timer_prefs`, `user_prefs`,
`voice_prefs`.

**DataStore files with dedicated sync services:**
`theme_prefs` → `ThemePreferencesSyncService`,
`sort_prefs` → `SortPreferencesSyncService` (translates per-project local
IDs to cloud IDs at the push boundary; reverse at pull).

**Canonical onboarding flag:** `users/{uid}.onboardingCompletedAt` hydrated
locally via `CanonicalOnboardingSync` on top of the generic onboarding sync.

---

## Findings

### 1. `TaskTimingEntity` incremental sync wiring (RED)

**Premise verification.** `TaskTimingEntity` is the per-session work log
(`source = manual | pomodoro | timer`, `duration_minutes`, `started_at`,
`ended_at`, `task_id` FK). Migration v64→v65 added the table with a
`cloud_id` unique index (`Migrations.kt:1879–1896`). The dispatch tables,
listener manager, bulk initial-upload, and pull loop all handle the
`task_timing` entity type (`SyncService.kt:541–558`, `1825–1836`,
`SyncListenerManager.kt:52`, `SyncDispatchTables.kt:21,64`).

The repository, however, intentionally does NOT call `SyncTracker`:

```
data/repository/TaskTimingRepository.kt:9–12
 * Repository wrapper around `TaskTimingDao`. Sync tracking is intentionally
 * NOT wired here — cross-device sync of `task_timings` is a follow-up
 * (P2-D, see `docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`).
```

**Findings.** The infrastructure exists end-to-end; only the
`SyncTracker.trackCreate/trackUpdate/trackDelete` calls in
`TaskTimingRepository.logTime/update/deleteById/deleteByTaskId` are
missing. The initial-upload loop will push existing rows on first sync,
but every subsequent edit to a time entry on device A never reaches the
`SyncMetadataEntity` `pending_action` queue, so it is never pushed.
Pulls work (listener fires on remote inserts), so the effect is one-way
loss of incremental writes from this device.

Affected user-visible behavior: per-task time analytics, the Productivity
dashboard's per-task minutes column, and Pomodoro session history all
diverge across devices for any entry created after install. Bug surface
since v64 shipped.

**Risk.** RED. Real data loss across the device boundary; high-signal
analytics surfaces affected.

**Recommendation.** PROCEED. Wire `SyncTracker` calls into
`TaskTimingRepository` so every insert/update/delete enters the pending
queue. No schema migration needed (`cloud_id` column + index already
exist). Delete the stale comment.

---

### 2. `CalendarSyncPreferences` user-facing settings (RED)

**Premise verification.** `data/calendar/CalendarSyncPreferences.kt` holds
eight keys in the `gcal_sync_prefs` DataStore:

- `gcal_sync_enabled` — user toggle
- `gcal_sync_calendar_id` — chosen target calendar
- `gcal_sync_direction` — direction (one-way / both)
- `gcal_show_events` — show Calendar events in PrismTask
- `gcal_display_calendar_ids` — multi-select of visible calendars
- `gcal_sync_frequency` — periodic sync cadence
- `gcal_last_sync_timestamp` — per-device watermark (correctly local)
- `gcal_sync_completed_tasks` — should completed tasks push to Calendar

`PreferenceSyncModule` excludes `gcal_sync_prefs` wholesale with the
comment "Google Calendar sync tokens; per-device" (`di/PreferenceSyncModule.kt:38`).
But the file holds **no OAuth tokens** — the Google credential lives in
`GoogleAccountCredential` (system-managed). Seven of the eight keys here
are plain user settings; only `gcal_last_sync_timestamp` is per-device.

**Findings.** A user who configures "sync to Work calendar, both-way,
every 15 min, push completed tasks" on phone A and installs on tablet B
has to repeat every choice. The misclassification is in
`PreferenceSyncModule`'s exclude list, not the file itself.

**Risk.** RED. The user's stated goal explicitly names this case —
"every possible … preference … saved to the backend." This is a clean
example of a setting silently failing to sync.

**Recommendation.** PROCEED. Either (a) split `gcal_last_sync_timestamp`
into a separate `gcal_sync_device_prefs` DataStore and register the
remaining keys in `PreferenceSyncModule`, or (b) extend the existing
generic sync to support per-key exclusion within a file. Option (a)
matches existing precedents (`backend_sync_prefs`, `sync_device_prefs`)
and is the cleaner cut. Update the exclude-list comment in
`PreferenceSyncModule.kt:38` to match reality.

---

### 3. `WidgetConfigDataStore` per-instance config (DEFERRED)

**Premise verification.** Confirmed at
`app/src/main/java/com/averycorp/prismtask/widget/WidgetConfigDataStore.kt`.
Keys are namespaced `widget_{appWidgetId}_{key}` — i.e. keyed by the
launcher-assigned `appWidgetId`. Each placed instance of Today, Inbox,
Calendar, HabitStreak, Project, QuickAdd, etc. carries its own
`showProgress`/`showTaskList`/`maxTasks`/`bgOpacity`/`selectedProjectId`
overrides. Nothing in `data/remote/` references this file.

**Findings.** `appWidgetId` is assigned by the user's launcher when the
widget is dropped on a home screen; it has no stable mapping across
devices (and two devices typically have different widget layouts). A
naive sync of the keyspace would be incoherent. A useful version would
require either a portable widget-instance identity (e.g. UUID minted
when placed, persisted alongside `appWidgetId`) or a per-widget-type
"default config" that newly placed widgets inherit from on device B.

**Risk.** YELLOW. Real user input that doesn't survive a device switch,
but fixing it cleanly requires a small data-model change rather than
just adding to the sync registry.

**Recommendation.** DEFER. Out of stated scope today; cleanest path is
syncing **per-widget-type defaults** (one set of keys per widget kind)
that act as the seed for any newly placed widget. Tracked as
`PROCEED-LATER`. Anti-pattern flag below.

---

### 4. `TimerStateDataStore` active timer state (GREEN)

**Premise verification.** `widget/TimerStateDataStore.kt` stores
`isRunning`, `isPaused`, `remainingSeconds`,
`sessionEndElapsedRealtime`. `elapsedRealtime` is a per-device boot-clock
value; cross-device sync is structurally meaningless here.

**Findings.** Configuration that drives the timer (work duration, break
duration, sessions before long break, auto-start toggles, focus
preference, AI coaching toggles) all live in `TimerPreferences` and are
synced via the generic preference layer. Only the ephemeral *running*
state is local, and that is correct.

**Risk.** GREEN.

**Recommendation.** STOP — no work needed.

---

### 5. Notification snooze state (DEFERRED)

**Premise verification.** Snooze *durations* (the menu the user picks
from) live in `NotificationPreferences.snoozeDurationsCsv` and sync.
Active snooze-until is implemented by re-scheduling the AlarmManager
alarm, not by storing a `snoozedUntil` field on the task — there is no
table or DataStore key for in-flight snoozes.

**Findings.** If the user taps "snooze 15 min" on device A, device B
re-fires the original alarm at its scheduled time. Cross-device snooze
suppression would require either (a) a `task_reminder_state` synced
sub-record, or (b) collapsing snooze into a one-shot `dueAt` mutation
that already syncs through `TaskEntity`. Option (b) is the simpler
approach but would lose the "was snoozed" semantic.

**Risk.** YELLOW. Annoyance, not data loss.

**Recommendation.** DEFER. Worth its own design pass; tracked as
follow-up.

---

### 6. Task drafts / unsaved edit recovery (DEFERRED)

**Premise verification.** `AddEditTaskSheet` has unsaved-changes
detection but no draft persistence layer. No `task_drafts` entity, no
`draft_*` DataStore keys.

**Findings.** This is not a sync gap — there is no draft to lose
because there is no draft to save. Strictly out of the stated scope
("saved to the backend" implies something that is currently saved
locally).

**Risk.** YELLOW (UX). Not a sync correctness issue.

**Recommendation.** DEFER. Out of scope.

---

### 7. Search history / recent emoji / recent location pickers (GREEN)

**Premise verification.** No `search_history` table, no
`recent_emoji_*` DataStore keys, no recent-location picker code.
`ThemePreferences.recentCustomColors` is the only "recent" picker
and it syncs.

**Recommendation.** STOP — no work needed (no data to lose).

---

### 8. Device-local prefs by design (GREEN)

`AuthTokenPreferences`, `ProStatusPreferences`, `BackendSyncPreferences`,
`BuiltInSyncPreferences`, `MedicationMigrationPreferences`,
`RatingPromptPreferences`. Each one is either credentials, a per-device
sync watermark, a one-time migration flag, or a per-device engagement
heuristic. The exclude-list comment in `PreferenceSyncModule.kt:33–44`
matches the code.

**Recommendation.** STOP — verified intentional.

---

### 9. Junction tables (GREEN)

`TaskTagCrossRef` and `MedicationSlotCrossRef`. Both rebuilt from parent
payloads on pull — `TaskEntity` carries the tag cloud-id list,
`MedicationEntity` carries the slot cloud-id list. Verified by reading
`SyncMapper.kt:583+` and the Firestore document shape used for
medications. Tested by spot-checking `TaskRepositoryTest` and
`MedicationRepositoryTest`.

**Recommendation.** STOP — junctions are derived data, not primary state.

---

### 10. Observability logs (GREEN)

`AutomationLogEntity` (auto-purged after 30 days), `UsageLogEntity`,
`BatchUndoLogEntity` (explicit comment: "intentionally has no
`cloud_id` column and is not registered with `SyncMapper`"). Each is
local-by-design — cross-device undo would race; usage analytics are a
device-local signal; automation logs are debug output.

**Recommendation.** STOP — verified intentional.

---

### 11. `CalendarSyncEntity` task↔calendar-event binding (GREEN)

Calendar event IDs and etags are issued by the Google Calendar API
against the user's Google account, not by PrismTask. The event itself
*is* visible on every device via Google's own sync; PrismTask's binding
metadata is a per-device-device cache of "which local task corresponds
to which Calendar event."

**Recommendation.** STOP — verified intentional.

---

### 12. Drive backup state / last-backup timestamp (DEFERRED)

No Android-side state tracks "when was the last Drive backup taken." The
backend writes the file; the user has to open Settings → Backup to see
status. Not a sync gap per se; surfacing a synced `last_drive_backup_at`
timestamp would be a UX win but is a feature addition, not a fix.

**Recommendation.** DEFER. Feature-level work, not a sync gap.

---

### 13. Pomodoro session log (GREEN — covered by Item 1)

`SmartPomodoroViewModel.completeSession` writes `TaskTimingEntity` rows
with `source = "pomodoro"`. So the same SyncTracker gap covers this —
no separate finding.

**Recommendation.** Folded into Item 1.

---

### 14. Dual-write coherence: Firestore vs FastAPI backend (DEFERRED)

`BackendSyncService` handles tasks/projects/tags/habits/habit_completions/
task_templates/medications/slots/tier_states/daily_essential_slot_completions
against the FastAPI backend, and `SyncService` syncs the same entities to
Firestore. The two paths use independent IDs (Firestore docId vs backend
PK) and independent pending-queues. No explicit reconciliation between
them.

This is a known-shape duplication, not a sync coverage gap — both writes
appear to succeed independently. Flagged as an anti-pattern because the
operator's question ("every input saved to the backend") could plausibly
be read as "stop writing to two places." Out of scope for THIS audit
(no premise that one of the two paths is failing).

**Recommendation.** DEFER. Worth a dedicated audit of its own.

---

### 15. `ChatMessageEntity` / `UserAiPreferenceEntity` (GREEN)

Server-authoritative via `/api/v1/ai/chat` and `/api/v1/ai/memory`.
Mirrored into Room as a cache; intentionally not in
`SyncDispatchTables`. Behavior documented in CLAUDE.md § "Chat
Persistence (D11 E.3)". Verified.

**Recommendation.** STOP — verified intentional.

---

## Ranked improvements (savings ÷ cost)

Sorted by user-visible-impact ÷ implementation-cost. Wall-clock estimates
are session-time for a focused PR.

| # | Item | Risk | Effort | Impact | Recommendation |
|---|------|------|--------|--------|----------------|
| 1 | Wire `SyncTracker` into `TaskTimingRepository` (Finding 1) | RED | ~30 min | Restores cross-device time analytics + Pomodoro history; data loss eliminated | PROCEED |
| 2 | Split `gcal_sync_prefs` and sync user settings (Finding 2) | RED | ~45 min | Calendar sync settings carry across devices; matches user's literal ask | PROCEED |
| 3 | Per-widget-type default-config sync (Finding 3) | YELLOW | ~2–4 hr (data-model change) | New device installs widgets pre-configured to the user's preferences | DEFERRED |
| 4 | Synced notification snooze state (Finding 5) | YELLOW | ~2 hr | Snoozing on A suppresses re-fire on B | DEFERRED |
| 5 | `last_drive_backup_at` surfaced + synced (Finding 12) | YELLOW | ~1 hr | UX, not correctness | DEFERRED |
| 6 | Dedicated audit of Firestore↔Backend dual-write (Finding 14) | YELLOW | ~half-day audit | Reduces ops surface, avoids future divergence | DEFERRED |

Phase 2 will fan out PRs for #1 and #2.

---

## Anti-patterns flagged (not necessarily fixed)

- **Exclude-list comments that drift from reality.** `PreferenceSyncModule`'s
  `gcal_sync_prefs` exclusion claims it holds tokens; it holds user
  settings. When an exclude list is the source of truth, its rationale
  comment must match the code or the exclusion gets cargo-culted forward.
- **"Follow-up" repository comments.** `TaskTimingRepository`'s "sync
  tracking is intentionally NOT wired here — cross-device sync of
  `task_timings` is a follow-up" lasted a full release cycle without the
  follow-up landing. When the rest of the sync infrastructure for an
  entity ships green, the repository call site is the single load-bearing
  line — leaving it disabled by comment is a silent failure.
- **`appWidgetId`-keyed sync.** Any DataStore keyed by a launcher-issued
  ID is structurally unsyncable; either mint a portable ID alongside or
  sync only the per-widget-type defaults. Worth flagging in any future
  widget-config additions.
- **Two sync paths for the same entity.** Firestore + FastAPI backend
  writing the same task / project / habit creates a future-divergence
  hazard. Not fixed here, but a dedicated audit is warranted.
- **`__pref_device_id` self-echo suppression.** Already in place for
  generic preference sync. Apply the same convention to any future
  preference sync service (sort + theme do this correctly today).

---

## Out of scope (acknowledged, not pursued)

- Designing sync semantics for active timer state, snooze state, drafts.
  These are real product questions but not "missing sync wiring" — each
  needs its own design.
- Migrating off Firestore (or off the FastAPI sync surface). Two write
  paths is a known shape; reconciling is its own audit.
- Adding new persistence layers (search history, recent emojis, etc.).
  No data exists today to lose; cross-device-sync of nothing is still
  nothing.

---

## Phase 3 — Bundle summary

Per CLAUDE.md ("Audit-first Phase 3 + 4 fire pre-merge"), this section
lands while the implementation PRs are still in-flight rather than after
merge. The two PROCEED items shipped as separate, focused PRs:

- **PR #1292 — `fix(sync): wire SyncTracker into TaskTimingRepository`.**
  Adds the four missing `trackCreate / trackUpdate / trackDelete` calls
  on the mutating paths. `deleteByTaskId` snapshots affected ids before
  the bulk delete so each row enters the queue with a stable identity.
  Unit tests assert each tracker call happens on the matching op.
  Measured impact (post-merge, expected): per-task time entries now
  push to Firestore on edit, restoring per-task time analytics,
  productivity dashboard minutes, and Pomodoro session history across
  devices.
- **PR #1293 — `fix(sync): sync Google Calendar user settings across
  devices`.** Registration-only change: `gcal_sync_prefs` joins the
  generic sync set with `excludeKeys = setOf("gcal_last_sync_timestamp")`
  so user-facing settings push/pull while the per-device pull watermark
  stays local. No new code path — `PreferenceSyncSerialization` already
  honours per-spec `excludeKeys` (verified by the existing
  `PreferenceSyncSerializationTest`). Visibility flip on
  `Context.calendarSyncDataStore` (private → internal) and a doc-comment
  rewrite in `PreferenceSyncModule.kt` are the only secondary edits.
  Measured impact (post-merge, expected): a fresh install on device B
  inherits the user's calendar choice, direction, frequency, display
  calendars, and completed-task push setting from device A.

**Re-baselined wall-clock-per-PR estimate.** Both items landed under
the ~30–45 min targets from the ranked table. PR #1292 was ~30 min
(repo edit + 4 new tests + run). PR #1293 was ~15 min (turned out to
be a registration-only change once `excludeKeys` was discovered to
already be wired through). The cheaper-than-estimated path came from
reading the dispatch infrastructure end-to-end before recommending
schema or DataStore-split work.

**Memory entry candidates.** None surprising enough to keep. The
"audit before splitting a DataStore" lesson is already covered by
existing memory and the audit-first skill; no new entry needed.

**Schedule for next audit.** The four DEFERRED items each merit a
follow-up pass when scheduled:
- Per-widget-type default-config sync (Finding 3).
- Synced notification snooze state (Finding 5).
- Surfaced + synced last-Drive-backup timestamp (Finding 12).
- Firestore-vs-backend dual-write reconciliation (Finding 14) — the
  largest of the four; merits its own dedicated audit pass.
