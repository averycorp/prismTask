# D10 Item 2 — Medication v1.4 Dual-Write Shim Retirement Audit

## Scope reconciliation

The original spec requested a 3-PR sequential fan-out (PR-A UI rewire,
PR-B DataImporter migration, PR-C shim delete). Operator override on
2026-05-10 collapsed the scope to a single branch
(`claude/retire-medication-shim-DBW2s`) after Phase 0 disk verification
revealed PR-A and PR-B are effectively no-ops (rewires already done in
prior bundles). This audit covers what would have been PR-A + PR-B + PR-C
findings combined, plus the actual delete.

## Phase 0 findings

### STOP-PHASE-F
Operator-asserted GREEN-GO. `git log` shows no Phase F merge commits
yet (today 2026-05-10, kickoff 2026-05-15). Override accepted per memory #9.

### STOP-A — premise verification
- `MedicationRepository.kt`: present at
  `app/src/main/java/com/averycorp/prismtask/data/repository/MedicationRepository.kt`.
- Shim sites in `SelfCareRepository.kt` confirmed at lines 164–175 (ctor
  deps), 383–385 (`addStep`), 391–398 (`updateStep`), 401–405
  (`deleteStep`), 666–679 (`toggleStep`), and 1097–1205 (helper block).
- Shim sites cleared.

## PR-A scope (UI consumer rewire) — already done

The shim only fires when `routineType == "medication"`. Mapping every
production caller of the medication-tier write paths:

| Consumer | Calls SelfCare medication-tier write? | Verdict |
| --- | --- | --- |
| `MedicationViewModel` | No — uses `MedicationRepository` directly (line 102) | GREEN — already migrated |
| `MedicationLogViewModel` | No — uses `MedicationRepository` | GREEN |
| `MedicationRefillViewModel` | No — uses `MedicationRepository` / `MedicationRefillRepository` | GREEN |
| `SelfCareViewModel` | Generic; receives `routineType` from `SavedStateHandle` route arg. The only `SelfCare.createRoute` call site is `HabitListScreen.kt:263`, which is guarded by `when (listItem.routineType) { "medication" -> navigate(Medication.route) }`. Medication routineType is never reachable. | GREEN — unreachable |
| `TodayViewModel.onToggleRoutineStep` (line 1245) | Generic; only Daily Essentials section calls it (`DailyEssentialsSection.kt`) and only for `"morning"`, `"housework"`, `"bedtime"`. | GREEN — unreachable |
| `HabitListViewModel` (lines 295, 302, 314) | Read-only (`parseTiersByTime`, `getVisibleStepsFromEntities`). Doesn't trigger shim. | GREEN |
| `TemplateBrowserViewModel.seedSelfCareSteps` (line 56) | Calls `seedSelfCareSteps`, not a shim trigger. | GREEN |
| `OnboardingViewModel.seedSelfCareSteps` (line 542) | Same — not a shim trigger. | GREEN |
| `SettingsViewModel.reseedBuiltInDefaults` (line 498) | Not a shim trigger (`reseedBuiltInDefaults` doesn't call the shim mirror methods). | GREEN |

**PR-A verdict**: zero LOC needed. All medication-write UI consumers
already route to `MedicationRepository` (likely in the prior PRs that
established the shim as a transitional bridge, never wired back into UI).

## PR-B scope (DataImporter migration) — already done

`DataImporter.kt` injects `MedicationDao` and `MedicationDoseDao`
directly (lines 183–184) and imports medications via
`importMedications` (line 786 `medicationDao.insert`) and dose history
via `importMedicationDoses` (line 805+).

The only stale residue is the comment block at lines 333–336:

```kotlin
// v1.4 medications — imported after self-care so the
// dual-write shim's later migration runs see the real
// names in place. medication_doses MUST come after
// medications because it FK's to medication_id; we use
// the export-side id as the join key via medIdRemap.
```

The first sentence references the shim — to be retired with the shim.
The FK/ordering rationale stays.

**PR-B verdict**: comment update only (~5 LOC).

## PR-C scope (shim delete)

### Delete inventory

`app/src/main/java/com/averycorp/prismtask/data/repository/SelfCareRepository.kt`:

1. **Constructor params** (lines 163–173 comment + lines 172–173 fields):
   `medicationDao: MedicationDao` and `medicationDoseDao: MedicationDoseDao`
   are only used by the shim helper methods. Delete.
2. **`addStep` shim call** (lines 383–385): `if (routineType == "medication") mirrorUpsertMedication(...)`. Delete.
3. **`updateStep` shim call** (lines 391–398): same pattern. Delete.
4. **`deleteStep` shim call** (lines 401–405): same pattern. Delete.
5. **`toggleStep` mirror dose block** (lines 666–679): `// v1.4 dual-write: mirror the dose change to medication_doses.` Delete.
6. **Helper block** (lines 1097–1205): the comment header + `normalizedMedName`, `mirrorUpsertMedication`, `mirrorArchiveMedication`, `mirrorDoseChange`, `mergeTimeOfDay`. Delete.
7. **Imports** (lines 9–10, 14–15): `MedicationDao`, `MedicationDoseDao`, `MedicationDoseEntity`, `MedicationEntity` only used by the shim. Delete.

**Verdict**: `GREEN-DELETE`. ~115 LOC delete from production code.

### Test deltas

- `SelfCareRepositoryConflictTest.kt:67-79`: drop `medicationDao` /
  `medicationDoseDao` from ctor.
- `SelfCareRepositorySeedingTest.kt:66-78`: same.

Other tests touching SelfCareRepository
(`SelfCareViewModelTierDefaultTest`, `TodayViewModelTest`,
`HabitListViewModelTest`, `MedLogReconcileTest`,
`HiltDependencyGraphTest`) do not construct the repository directly
or reference shim methods — no changes.

### Verification plan

1. Local Gradle build (`assembleDebug` + `testDebugUnitTest`).
2. Smoke: SelfCare/Medication paths preserved.
3. CI green required.
4. Post-merge: 24–48h Crashlytics watch on medication non-fatals.

### Process incidents

- STOP-PHASE-F: fired; operator override accepted.
- STOP-A: cleared.
- STOP-1A (PR-A scope expansion): n/a — zero PR-A code needed.
- STOP-1B (PR-B backup-restore concern): cleared — DataImporter already
  uses MedicationDao directly; no JSON schema change.
- STOP-1A (PR-C external refs): cleared — shim helpers are all `private`
  with no external callers.

### LOC estimate vs actual

Estimate ~30–80 LOC delete. Actual ~115 LOC delete (production) + ~6 LOC
in tests + ~5 LOC comment update. Drift +44% over upper bound, driven
by the helper-block size; not material.

## D10 closure impact

After merge + 24–48h Crashlytics watch:
- D10 Item 2: 0 → 1.0
- D10 Item count at 1.0: 9 of 9 → ★ CLOSED
