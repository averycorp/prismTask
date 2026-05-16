# Phase 2 Medication Cleanup Runbook

**Status:** DRAFT — migration code exists but NOT wired into
`ALL_MIGRATIONS`. Do NOT land until all three gates below pass.

**Branch holding the draft migration:**
`feat/medications-phase-2-cleanup-migration-DO-NOT-MERGE`

**Spec:** [`docs/SPEC_MEDICATIONS_TOP_LEVEL.md`](./SPEC_MEDICATIONS_TOP_LEVEL.md) §3

---

## Gates — must ALL pass before landing

1. **v54 has been shipping for ≥2 weeks.** The dual-write shim + the
   `MedicationMigrationRunner` need convergence time to catch edge cases
   (devices that haven't opened the app in a while, multi-device users
   where one device is slow to receive sync).
2. **>95% of active-user devices are on v54+.** Crashlytics dashboard
   filter by `versionCode ≥ 676` (the first build that includes v54).
3. **Zero `medication.migration.device_mismatch` warnings in
   PrismSyncLogger for ≥7 consecutive days.** The reconciler surfaces
   these when two devices disagree about which medications exist.

If any gate fails, **wait and re-check**. Don't fudge the timeline — the
migration is rollback-impossible.

---

## What MIGRATION_54_55 does

Four passes in order:

1. **Quarantine snapshot** — `CREATE TABLE ... AS SELECT *` for:
   - `self_care_steps` WHERE `routine_type='medication'`
   - `self_care_logs` WHERE `routine_type='medication'`
   - `medication_refills` (whole table)
   - Built-in `"Medication"` habit row
2. **Row drops** from the source tables.
3. **`medication_refills` table drop** — its data was inlined into
   `medications` at v53→v54.
4. **Drop the v54 quarantine tables** — they're superseded by the
   phase-2 snapshots.

Forensic recovery (via adb + sqlite3) stays possible from the phase-2
quarantine tables. Rollback via v55→v54 is NOT supported.

---

## Landing checklist

When gates pass, a follow-up PR should:

1. **Wire the migration into `ALL_MIGRATIONS`:**
   ```kotlin
   val ALL_MIGRATIONS: Array<Migration> = arrayOf(
       // …existing migrations…
       MIGRATION_53_54,
       MIGRATION_54_55,   // <-- add
   )
   ```

2. **Bump DB version** in `PrismTaskDatabase.kt`:
   ```kotlin
   version = 55,   // was 54
   ```

3. **Remove `MedicationRefillEntity`** from the `@Database(entities = …)`
   array in `PrismTaskDatabase.kt`. Also remove the
   `medicationRefillDao()` abstract accessor.

4. **Delete files:**
   - `app/src/main/java/com/averycorp/prismtask/data/local/entity/MedicationRefillEntity.kt`
   - `app/src/main/java/com/averycorp/prismtask/data/local/dao/MedicationRefillDao.kt`
   - `app/src/main/java/com/averycorp/prismtask/data/repository/MedicationRefillRepository.kt`
   - `app/src/main/java/com/averycorp/prismtask/ui/screens/medication/MedicationRefillScreen.kt`
   - `app/src/main/java/com/averycorp/prismtask/ui/screens/medication/MedicationRefillViewModel.kt`

5. **Remove `MedicationRefillDao` / `MedicationRefillRepository` / `RefillCalculator` from their `@Inject` sites.** These are used by:
   - `DailyEssentialsUseCase` (check for refill references)
   - `MedicationSlotGrouper` (dose-label rendering may reference refill counts)
   - `DatabaseModule.provideMedicationRefillDao` (delete the provider)

6. **Drop `MedicationPreferences` DataStore:**
   - Delete `MedicationPreferences.kt` + `MedicationScheduleMode.kt`
   - Remove all `@Inject` sites (used by `HabitReminderScheduler`,
     `SelfCareRepository`, `MedicationViewModel`, `DataExporter`,
     `DataImporter`, `MedicationMigrationRunner`)
   - Delete the DataStore file itself at first launch (one-shot cleanup)

7. **Remove the dual-write shim from `SelfCareRepository`:**
   - `mirrorUpsertMedication`, `mirrorArchiveMedication`,
     `mirrorDoseChange`, `mergeTimeOfDay` helpers
   - The four mirror call sites in `addStep` / `updateStep` /
     `deleteStep` / `toggleStep`
   - The `MedicationDao` + `MedicationDoseDao` constructor deps
     (still used by other injections though, so check first)

8. **Remove the `disarmLegacyScheduler` logic** from
   `MedicationMigrationRunner` (now unreachable — its inputs are gone).
   The whole runner is likely deletable at this point — check call sites
   in `PrismTaskApplication.onCreate`.

9. **Update `HabitReminderScheduler`** — remove `scheduleSpecificTimes`,
   `scheduleAtSpecificTime`, `cancelSpecificTime`, and the
   `MedicationPreferences` dep.

10. **Update existing tests:**
    - Remove the `medicationRefills` fields from `DataExporter` /
      `DataImporter` tests.
    - Delete `MedLogReconcileTest` if it only tests source-table
      behavior.

11. **Add a `MIGRATION_54_55_Test`** following the `MIGRATION_53_54_Test`
    pattern: seed v54 schema with representative source rows, run the
    migration, assert rows are quarantined and source is empty.

---

## Rollback plan

Rollback is NOT supported — `fallbackToDestructiveMigrationOnDowngrade`
is the default, which would wipe the DB on a v55→v54 downgrade.

Emergency forensic recovery from the phase-2 quarantine tables:

```sql
-- Pull DB via adb:
-- adb shell "run-as com.averycorp.prismtask cp \
--   /data/data/com.averycorp.prismtask/databases/averytask.db \
--   /sdcard/averytask.db"
-- adb pull /sdcard/averytask.db ./recovery.db

-- In sqlite3 recovery.db:
.schema quarantine_phase2_selfcare_steps_medication
SELECT * FROM quarantine_phase2_selfcare_steps_medication;
-- etc.
```

---

## Why this runbook exists (and the migration is DRAFT)

The medication-top-level refactor's v1 scope shipped v53→v54 in the
v1.4 release. The v54 code keeps legacy source data readable for the
dual-write shim + migration-runner safety net. Dropping the source
data early would:

- Break users on older builds still syncing via `self_care_step`
  Firestore docs (cross-device race, spec §3.4)
- Remove the rollback fallback if v54 surfaced a bug requiring a
  regression release
- Invalidate existing `daily_essential_slot_completions` that reference
  `self_care_step` IDs (though the synthetic `source:name` keys mean
  this is less of a concern)

Landing this migration without the gates risks data loss across a
heterogeneous user base. The runbook exists so the maintainer who
eventually lands it doesn't have to rediscover the scope.
