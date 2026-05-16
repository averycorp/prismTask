# SPEC — Self-Care Steps Out-of-Band Sync Recovery

**Status:** Proposal — not yet scheduled
**Author:** Scoped via audit on 2026-04-22
**Target Room version:** no schema change required (may bump for a one-shot state flag DataStore entry, not a DB column)
**Related docs:** `docs/sync-architecture.md`, `docs/PHASE_3_FIX_D_PLAN.md`, `docs/FIREBASE_EMULATOR.md`

---

## 0. Reader's note — prompt premise correction

The brief that triggered this spec asserted that `self_care_steps` is "structurally absent from the sync pipeline" and asked for a new Firestore subcollection + mapper + initialUpload + pullRemoteChanges wiring. Audit on 2026-04-22 found the opposite: **the entire pipeline already exists end-to-end.**

| Concern | Status |
|---|---|
| `SelfCareStepEntity.cloud_id` (UNIQUE index) | Exists (`data/local/entity/SelfCareStepEntity.kt:10,14`) |
| `SyncMapper.selfCareStepToMap` + `mapToSelfCareStep` | Exist (`data/remote/mapper/SyncMapper.kt:527, 543`) |
| `SyncService.doInitialUpload` uploads self_care_steps | Exists (`SyncService.kt:486-504`, inside `maybeRunEntityBackfill`) |
| `SyncService.pullRemoteChanges` pulls self_care_steps | Exists (`SyncService.kt:1146-1189`), with dedup-by-stepId+routineType |
| Real-time listener on `self_care_steps` collection | Exists (`SyncService.kt:1479`) |
| `collectionNameFor("self_care_step")` → "self_care_steps" | Exists (`SyncService.kt:624`) |
| `restoreCloudIdFromMetadata` includes self_care_steps | Exists (`SyncService.kt:1355`) |

The prior audit's conclusion was log-forensic: during a sync cycle, there was no `listener.snapshot | collection=self_care_steps` log line. That absence is explained by **the user's Firestore `self_care_steps` subcollection being currently empty.** The listener fires but `snapshot.documentChanges.isEmpty()` triggers an early return at `SyncService.kt:1493` without emitting a log.

What actually needs to be fixed is a different class of bug, described below.

---

## 1. Problem statement

When a user's Firestore `self_care_steps` collection gets wiped out of band (manual Firestore delete, account reset, phase-3 Fix-D cleanup, etc.), the app has **no recovery path**. The 36 local rows on each device stay locally usable but become permanently unsyncable:

- `initialUpload()` returns early on `isInitialUploadDone == true` (`SyncService.kt:78`).
- `maybeRunEntityBackfill()` returns early on `isNewEntitiesBackfillDone == true` (`SyncService.kt:417`).
- Reactive push only processes rows with a `sync_metadata.pendingAction` entry, which is written by repository methods via `syncTracker`. Direct-SQL writes and pre-existing rows with no pending-action never enter the queue.
- Pull sees the empty collection, applies nothing.
- The listener sees no document changes, emits nothing.

**Fresh-install / uninstall+reinstall is unsafe:** a new device signing in to the same account pulls an empty `self_care_steps` → receives zero steps from the cloud. The local seeder still runs and lays down default built-in steps, but any **user-created or user-edited** medication / self-care step is permanently lost.

This spec's fix also closes the gap for `self_care_logs`, `leisure_logs`, and the other "new entity" families (`courses`, `course_completions`) that share the same `maybeRunEntityBackfill` one-shot guard — they are all vulnerable to the same out-of-band wipe scenario.

## 2. Evidence

- **Audit 2026-04-22, post-Fix-D:** both devices (emulator + Samsung S25) showed `self_care_steps` count = 0 on-device. Pre-Fix-D backup (`backups/pre-fixd-live-20260422T024538Z/emulator/averytask.db`) has 36 rows, all with populated `cloud_id`. Firestore's `self_care_steps` subcollection for this user is empty.
- **Part 1b live-device restore** (`test-results/restore-from-backup/20260422T041326Z/RESULTS.md`, 2026-04-22): restored 36 rows to each device via direct SQL; subsequent `fullSync` logged `pushed=0 pulled=96`; `listener.snapshot | collection=self_care_steps` did NOT appear; no Firestore docs were created. Confirmed the reactive-push bypass and the empty-collection listener early-return.
- The flags `isInitialUploadDone` and `isNewEntitiesBackfillDone` are documented as "only flip true on successful completion so a mid-run failure stays retryable" (`SyncService.kt:76-81`, `BuiltInSyncPreferences.kt:67-73`) — but neither exposes a reset path, so post-success there is no way to trigger re-upload.

## 3. Proposed fix — orphan-cloud-id healer + resumable entity backfill

Two cooperating pieces. Both are additive; no existing code gets behavior-changes beyond what's needed for the new affordance.

### 3.1. `CloudIdOrphanHealer` (new class)

A one-pass repair runs from `SyncService.fullSync` **after** `pullRemoteChanges()` but **before** `builtInHabitReconciler.reconcileAfterSyncIfNeeded()`. It does:

1. For each syncable table in a curated list (initially: `self_care_steps`, `self_care_logs`, `courses`, `course_completions`, `leisure_logs` — the "new entity" families. Can expand later.):
   - Query local rows whose `cloud_id IS NOT NULL` AND whose `sync_metadata` entry for that entity_type has `last_synced_at < (sync_start_time - N_minutes)`. The time-window filter prevents healing mid-cycle — only genuinely stale rows qualify.
   - For each candidate row, check the just-pulled collection snapshot for a matching cloud_id (we already have this snapshot from `pullRemoteChanges`; either stash it or re-query once per table).
   - If cloud_id is missing from the remote snapshot: mark the row as `pendingAction=create` via `syncTracker.trackInsert`. This routes it through the reactive-push pipeline, which will mint a fresh Firestore doc and upsert sync_metadata. **Important — do NOT reuse the stale `cloud_id`:** the row's existing `cloud_id` column is now meaningless and gets overwritten when the push completes with the new docRef.id. This avoids any correctness hazard from stale cloud_id reuse.
2. Idempotency: no gate flag. The healer is cheap (at most ~50 rows per table for this app's size), and running it every fullSync is correct behavior — if a row has a cloud_id that IS in Firestore, nothing happens. The only cost is one Firestore `.get()` per tracked table per sync, amortized against pulls that already happen. Log a `healer.self_care_steps | status=no_op | detail=checked=36 orphaned=0` summary each run so we can watch for regressions.
3. Edge cases to explicitly handle:
   - Row has `cloud_id = NULL` → not an orphan, skip.
   - Row has `cloud_id` but no `sync_metadata` entry → insert sync_metadata with `pendingAction=create`, old cloud_id will be overwritten on push.
   - Row has `cloud_id` matching a remote doc → no-op.
   - Row was soft-deleted locally → skip (deletion already has its own sync path).

### 3.2. `maybeRunEntityBackfill` becomes resumable

The `isNewEntitiesBackfillDone` flag is replaced by a per-entity-family table of "last fully-backfilled" timestamps stored in `BuiltInSyncPreferences`:

```
entity_backfill_state:
  self_care_steps: last_completed_at=<ts> or null
  self_care_logs: last_completed_at=<ts> or null
  courses: last_completed_at=<ts> or null
  ...
```

Each family's upload loop uses its own flag. A family whose flag is null OR whose flag is older than a configurable TTL (initially: never re-run without an explicit trigger, so no TTL — but the shape supports adding one) re-enters the upload loop on the next `startAutoSync`. The existing `if (getCloudId(…) != null) continue` per-row guard already prevents duplicate pushes, so running the loop again is safe.

This is strictly **additive** on the existing per-row guards: the loop still skips already-synced rows and only pushes ones missing from sync_metadata. The change is that the loop runs again at all.

### 3.3. Migration strategy

- No DB schema migration. The state lives in `BuiltInSyncPreferences`.
- **Backfill path for existing users:** on first launch after this patch ships, `CloudIdOrphanHealer` runs during the first `fullSync`. For the user in our audit, this will catch all 36 self_care_step orphans and all 7 "truly lost" habit orphans from Part 1b (if we expand the healer's table list to include `habits`). Each gets a new Firestore doc created with the existing content.
- **Order matters:** run the healer AFTER the pull (so we have a fresh snapshot to check against), BEFORE the reconcilers (so the reconcilers operate on post-healing state — if a local row gets a new cloud_id from the healer, the reconciler's group-by-cloud_id logic will see it).
- **Rollback plan:** if the healer misfires and creates duplicate Firestore docs, the existing `pullRemoteChanges` path has dedup logic for `self_care_steps` (by stepId+routineType, `SyncService.kt:1151-1165`) that will re-link on next pull. For other entity types without dedup, rollback is to disable the healer via a remote-config flag (to be added) and manually clean Firestore. Recommended: gate the initial rollout behind a debug setting before enabling for all users.

## 4. Effort estimate

**~45-75 min, multi-file.** Breakdown:

| PR | Scope | Est. time |
|---|---|---|
| 1 | `CloudIdOrphanHealer` class + DI wiring + `SyncService.fullSync` integration + unit tests | 30-45 min |
| 2 | `BuiltInSyncPreferences` per-family flags + `maybeRunEntityBackfill` resumability | 15-25 min |
| 3 | Instrumentation test: fresh-install-then-signin on two devices to prove data round-trips | 30-40 min |

Single-PR alternative: ~60-90 min. Prefer split so the healer lands first and can be reverted independently.

## 5. Risks

- **Risk 1 — duplicate Firestore docs on race.** Two devices run the healer simultaneously, both detect the same orphan, both push. Mitigation: the pull-side dedup for `self_care_steps` (stepId+routineType) already handles this. For other families that lack dedup, add a pre-push Firestore query: `userCollection(X).whereEqualTo("localCloudHint", row.cloudId).limit(1)` with a defensive skip-if-found. Cheap; acceptable.
- **Risk 2 — healer blind spot on truly-orphaned remote docs.** If a Firestore doc has no matching local cloud_id (e.g., created on another device, pulled here, then locally deleted without tracking), the healer can't do anything — that's not its job. Listener + `processRemoteDeletions` already handles the reverse direction.
- **Risk 3 — runaway re-uploads if the time-window filter is too aggressive.** If `sync_start_time - N_minutes` is too short, healer treats every pull-candidate as stale and pushes it back. Mitigation: initial `N = 1 hour`, gated behind telemetry. Watch for `healer.X | status=healed | orphaned=N` with N > 0 in steady state → suggests a bug.
- **Risk 4 — first-run surge on existing users.** The first post-deploy sync on the audited user's devices will push ~43 rows (36 steps + 7 habits). This is well within Firestore's per-minute write budget. Not a real concern for typical userbases; worth watching in the initial rollout.

## 6. Phase placement recommendation

**Phase B Wk 1 — pre-beta blocker.**

Rationale: losing user-created self-care / medication step data during beta testing would be reputationally devastating. The bug exists today for every user whose Firestore collection becomes empty for any reason, and right now the audited user is one uninstall away from permanently losing their 36 restored steps. The healer is cheap, additive, and risk-bounded. It should ship before any beta testers join.

Alternative if capacity is tight: ship Phase A2 as Phase B Wk 1's first-in, before other Phase B work starts.

## 7. Test plan

Unit (`app/src/test/java/com/averycorp/prismtask/`):
- `CloudIdOrphanHealerTest`:
  - `heal_withOrphan_marksRowAsPendingCreate()`
  - `heal_withNoOrphans_isNoOp()`
  - `heal_withNullCloudId_skips()`
  - `heal_withSoftDeletedRow_skips()`
  - `heal_afterRun_pushRespectsPendingQueue()`
- `MaybeRunEntityBackfillTest`:
  - `backfill_withFlagNull_runsUpload()`
  - `backfill_withFlagSet_skips()`
  - `backfill_perFamilyFlags_independent()` (self_care_steps re-runs while courses stays done)

Instrumentation (`app/src/androidTest/java/com/averycorp/prismtask/`):
- `SelfCareStepsOutOfBandRecoveryTest`:
  - Setup: two devices signed in to same account, both have 5 seeded self_care_steps, Firestore has matching 5 docs.
  - Wipe Firestore `self_care_steps` subcollection.
  - Trigger `fullSync` on device 1.
  - Assert: device 1's 5 rows re-pushed; Firestore has 5 new docs.
  - Trigger `fullSync` on device 2.
  - Assert: device 2's 5 rows converge with device 1 (no duplicates via stepId+routineType dedup).
  - Assert: sync_metadata on both devices matches Firestore doc IDs.
- `FreshInstallSigninSurvivesDataTest`:
  - Setup: device 1 has 36 user-edited self_care_steps in Firestore.
  - Fresh install on device 2; sign in.
  - Assert: device 2 pulls all 36 steps with correct `step_id` + `medication_name` + `tier`.
  - Edit step on device 2; assert push path works (via existing repository tracker, not the healer).

Manual QA:
- Run Phase A device testing runbook (`docs/PHASE_A_DEVICE_TESTING_RUNBOOK.md`) once after this ships — specifically the "Fresh install + sign-in convergence" section. Confirm no regressions.

## 8. Open questions

- **Q1.** Should the healer also cover `habits`, `tasks`, and `projects` (the older Tier-1 entities)? Those are not in `maybeRunEntityBackfill` and instead use the `doInitialUpload` path, so their one-shot behavior is identical. Scope expansion is trivial code-wise but doubles the review surface. Recommendation: start with the new-entity families only, add Tier-1 in a follow-up PR once the healer has proven stable for a release.
- **Q2.** Does `SelfCareLogEntity.completed_steps` JSON need any migration? It references `self_care_steps.step_id` TEXT (not integer PK). Restoring steps repopulates the lookup, no migration needed for the JSON itself. Confirmed during Part 1a audit. Just noting here so future readers don't wonder.
- **Q3.** Should `firestore.rules` get an explicit `match /users/{userId}/self_care_steps/{doc}` clause? The repo only contains an emulator stub; prod rules live in the Firebase console and currently permit authenticated access to all subcollections. Worth checking the console once this ships to confirm no tightening is needed.
