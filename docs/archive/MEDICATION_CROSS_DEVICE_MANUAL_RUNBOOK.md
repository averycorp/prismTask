# Medication Cross-Device Manual Runbook

**Pairs with:** `app/src/androidTest/java/com/averycorp/prismtask/sync/scenarios/MedicationCrossDeviceConvergenceTest.kt` (the automated lane).

This document covers scenarios that **cannot** be automated under the current `SyncTestHarness` shape because the harness owns one shared Room DB across both simulated devices and cannot pair Room schemas at different versions in-process. Extending the harness to support paired DBs is a non-trivial refactor that is out of scope for the v1.6.0 medication migration safety-net PR — see the audit report and Phase F.0 follow-up list.

QA must run these on **two physical or virtual Android devices with different APK versions installed** before the v1.4.0 → v1.6.0 closed beta opens (target: 2026-05-14).

---

## Pre-conditions for every scenario

- Two Android devices/emulators (call them `AVD-1` and `AVD-2`) with `adb` reachable.
- Both signed into the **same** Google account so Firestore writes land in the same `users/{uid}/...` subtree.
- Firebase **production** project — these are end-to-end tests. The Firebase Emulator Suite is only used by the automated lane.
- Android Studio's logcat or `adb logcat -s PrismSync:I MigrationInstrumentor:V` open on both devices. The new `db_migration_*` Analytics events emit on `MigrationInstrumentor` log scope; `db_post_v54_install` fires once per launch on any v54+ install.
- A clean install on `AVD-1` running the **older** APK (`v1.4.x`) and a clean install on `AVD-2` running the **newer** APK (`v1.6.0` candidate). Scenarios B and C both reset to this baseline.

---

## Scenario B — v58/v59 slot-table propagation across versions

**Risk class:** highest — `medication_slots`, `medication_slot_overrides`, `medication_medication_slots`, `medication_tier_states` were introduced in v58→v59 and v59→v60. A v1.4.x install pulling a slot doc from a v1.5.0+ device must not crash, and the upgrade path must converge correctly.

### Steps

1. **Install** `v1.4.x` (DB version 56 or 57) on `AVD-1`. Sign in. Open the medication screen — verify it shows whatever the v1.4.x flow expects.
2. **Install** `v1.6.0` candidate on `AVD-2`. Sign in to the **same** account. Wait for the initial sync to complete (`PrismSync` log shows `SyncService.fullSync end`).
3. **On AVD-2**, create a custom slot named `Lunch` at `12:30`. Confirm via UI it appears.
4. **Force-sync AVD-1**: pull-to-refresh on the medication screen, or wait the standard listener interval (~1 min in production). Watch logcat for `pullCollection medication_slots` — AVD-1 must hit the `unknown collection` skip path at `SyncService.kt:1952` and **not** crash. The line will look like `pullCollection medication_slots → applied=0 skipped=1 (unknown subcollection)` or similar; absence of an exception is the contract.
5. **Upgrade AVD-1** to the v1.6.0 candidate APK (do **not** uninstall — exercise the in-place migration path). Open the app. Migrations 57→58 through 62→63 run during DB open; logcat must show `db_migration_started` / `db_migration_completed` events for each one. Look for `mig_active=58→59` immediately followed by `mig_last_completed=58→59`.
6. After the migration completes, AVD-1's next pull must materialize the `Lunch` slot. Open Settings → Medications → Slots — the slot must appear with the correct `idealTime` and `driftMinutes`. Local DB count via `adb shell run-as com.averycorp.prismtask sqlite3 databases/averytask.db "SELECT COUNT(*) FROM medication_slots"` must be `2` (the seeded `Default` from MIGRATION_58_59 plus the synced `Lunch` from AVD-2).
7. **DEFAULT-slot dedup check.** Both devices' `medication_slots` table should now contain exactly one `Default` row (auto-seeded by MIGRATION_58_59 on first upgrade) and one `Lunch` row (synced from AVD-2). Cardinality must match across devices.

### Expected outcome

- AVD-1 in v1.4.x state never crashes when receiving v1.6.0-shape Firestore docs.
- After upgrade, slot tables on both devices have the same row count and the `Lunch` slot is identifiable on both.
- `db_migration_failed` does **not** fire on AVD-1's upgrade path.
- `db_post_v54_install` fires once per app launch on AVD-1 after it crosses v54.

### Failure-mode triage

| Symptom | Likely cause | Fix path |
|---|---|---|
| AVD-1 v1.4.x crashes on `pullCollection medication_slots` | A SyncService change reverted the unknown-collection skip path | Check `SyncService.kt:1950-1976`; the `pullCollection { … }` body must be tolerant of unknown collection names |
| `db_migration_failed` fires for `58→59` | Local DB has manually-modified medication tables that violate the new FK | Capture the Crashlytics non-fatal stack; the `mig_db_size_bytes` + `mig_last_completed_step` keys point at the failure boundary |
| `Lunch` slot doesn't appear on AVD-1 after upgrade | Slot pull happens before AVD-1's `medication_slots` table exists, then never re-pulls | Force-sync on AVD-1 once more; if still missing, the `pullCollection` short-circuit logic in v1.4.x is suspect |
| `Default` slot count > 1 on AVD-1 after upgrade | MIGRATION_58_59's `WHERE NOT EXISTS` guard didn't fire | Inspect `Migrations.kt:1554-1559`; should be impossible because the test pre-condition is a fresh install |

---

## Scenario C — Migration during active sync

**Risk class:** moderate — Room runs migrations synchronously inside `Room.databaseBuilder().build()`, which executes before any `@Inject SyncService` is reachable. There is **no race window inside one process**. The user-perceived race is across two app launches separated by an APK install.

### Steps

1. **Install** `v1.4.x` on `AVD-1`. Sign in. Create three medications via the v1.4.x flow.
2. **Force-quit** the app while the sync is in flight: `adb shell am force-stop com.averycorp.prismtask` while watching logcat for any `pullCollection` line that hasn't completed.
3. **Install** the `v1.6.0` candidate APK over the top (`adb install -r v1.6.0.apk`). Do **not** clear app data.
4. **Launch** the app. The Room migration runs at first `database.openHelper.writableDatabase` access — typically inside the first DAO injection from the resumed Hilt graph. Watch logcat for `db_migration_started` events.
5. **Verify** that:
   - `db_migration_completed` fires for every step from the pre-quit version up to v63.
   - `MedicationMigrationPreferences.isMigrationPushedToCloud()` is `false` initially, then becomes `true` after the post-migration push completes (gate the second push behind this flag — see `MedicationMigrationRunner` and `data/preferences/MedicationMigrationPreferences.kt`).
   - All three pre-quit medications appear in the v1.6.0 UI with correct names + dose history.
   - Firestore now has `medications` + `medication_doses` documents reflecting all three.

### Expected outcome

- Migration completes without `db_migration_failed`.
- No duplicate medications appear (the `MedicationMigrationPreferences` one-shot flag prevents double-push).
- The dose-backfill Kotlin pass (`MedicationMigrationRunner.backfillDosesIfNeeded`) runs to completion. `db_dose_backfill_done` log line confirms.

### Failure-mode triage

| Symptom | Likely cause | Fix path |
|---|---|---|
| Duplicate medication rows in Firestore | `setMigrationPushedToCloud` flag wasn't set on the prior crashed run | Inspect `MedicationMigrationRunner.preserveScheduleIfNeeded`'s try/catch; the flag should set in `try`, not `finally`, so a mid-run crash retries cleanly |
| `db_migration_failed` for an earlier migration step | Pre-quit DB had inconsistent state | Capture Crashlytics non-fatal; `mig_last_completed_step` says which migration succeeded last |
| `BuiltInMedicationReconciler` deletes a medication user kept | Cross-device dedup picked the wrong winner | The reconciler keeps the row with the most dose history, tiebreak smallest id; if user kept a fresh-but-unimportant copy, document the case for Phase F.0 review |

---

## Reporting

After running both scenarios, post the following to the v1.6.0 launch-readiness thread:

```
Medication Cross-Device Manual Runbook — v1.6.0
Run date: <date>
Devices: AVD-1 = <model> @ <APK>, AVD-2 = <model> @ <APK>

Scenario B: PASS / FAIL (link to logcat, attach Crashlytics ID if any)
Scenario C: PASS / FAIL (link to logcat, attach Crashlytics ID if any)

`db_migration_failed` events observed: <count, with version pairs>
`db_post_v54_install` first-fire shim_age_days: <value on AVD-1 after upgrade>
```

If either scenario fails, the closed beta is **blocked** until the underlying bug is fixed. Failure-mode triage above gives the first-pass diagnostic set; escalate to the v1.6.0 release owner if the symptom doesn't match a row.
