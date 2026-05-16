# Built-in Habit Template Migration — PR Plan

Status: Approved (per "implement the whole plan" directive 2026-04-24)
Companion to: [TEMPLATE_MIGRATION_DESIGN.md](TEMPLATE_MIGRATION_DESIGN.md)

This series ships in **one branch (`feat/template-migration-infra`) under one PR**, structured as four logically-separable commits so reviewers can read the diff layer-by-layer. Splitting into 4 GitHub PRs would have stalled on inter-PR Room-version conflicts; the single-PR + per-layer-commit shape was the explicit ask.

## PR series overview

| Layer | Commit | Branch | LOC est | Schema |
|---|---|---|---|---|
| 1 — Schema + version registry | `feat(templates): version columns + BuiltInHabitVersionRegistry` | feat/template-migration-infra | ~250 | Room v62 |
| 2 — Update detection + diff | `feat(templates): BuiltInUpdateDetector + diff computer` | same | ~400 | none |
| 3 — Approval UI + detach | `feat(templates): BuiltInUpdatesScreen + detach action` | same | ~600 | none |
| 4 — Tests + CHANGELOG | `test(templates): unit + migration tests; docs` | same | ~680 | none |

PR title: `feat(templates): versioned built-in habits with diff/approve/detach`

## Layer 1 — Schema + version registry

**Files touched (paths only):**
- `app/src/main/java/com/averycorp/prismtask/data/local/database/Migrations.kt` — add `MIGRATION_61_62`, bump `CURRENT_DB_VERSION` to 62
- `app/src/main/java/com/averycorp/prismtask/data/local/database/PrismTaskDatabase.kt` — register migration
- `app/src/main/java/com/averycorp/prismtask/data/local/entity/HabitEntity.kt` — add `sourceVersion`, `isUserModified`, `isDetachedFromTemplate`
- `app/src/main/java/com/averycorp/prismtask/data/local/entity/SelfCareStepEntity.kt` — add `sourceVersion`
- `app/src/main/java/com/averycorp/prismtask/data/seed/BuiltInHabitVersionRegistry.kt` (new) — definitions for 6 built-in habits at v1
- `app/src/main/java/com/averycorp/prismtask/data/remote/mapper/SyncMapper.kt` — wire 4 new fields through `habitToMap` / `mapToHabit` and the self-care step mappers
- `app/src/main/java/com/averycorp/prismtask/data/preferences/BuiltInSyncPreferences.kt` — add dismissed-version set accessors

**Schema changes:** see Design §3.

**Public API additions:**
```kotlin
object BuiltInHabitVersionRegistry {
    fun current(templateKey: String): BuiltInHabitDefinition?
    fun versionFor(templateKey: String): Int
    fun allCurrent(): List<BuiltInHabitDefinition>
}

class BuiltInSyncPreferences {
    suspend fun isDismissed(templateKey: String, version: Int): Boolean
    suspend fun setDismissed(templateKey: String, version: Int)
    suspend fun clearDismissals(templateKey: String)
}
```

**Test surface:** registry definition tests (every key has v1, no duplicate versions); migration test (`Migration61To62Test` — v61 fixture + v62 expected schema).

**Approx LOC:** 250 production, 150 test.

**Dependencies:** none in this branch. Coordinates with `feat/med-reminder-mode-pr1-schema` (currently in flight, also touches Migrations.kt) — this branch must rebase onto that one if it merges first; the migrations are additive so the conflict is mechanical.

## Layer 2 — Update detection + diff

**Files touched (new):**
- `app/src/main/java/com/averycorp/prismtask/domain/usecase/BuiltInUpdateDetector.kt`
- `app/src/main/java/com/averycorp/prismtask/domain/usecase/BuiltInTemplateDiffer.kt`
- `app/src/main/java/com/averycorp/prismtask/domain/model/TemplateDiff.kt` — `TemplateDiff`, `FieldChange`, `StepChange`, `PendingBuiltInUpdate`

**Files touched (existing):**
- `app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt` — call `detector.refreshPendingUpdates()` after reconciler
- `app/src/main/java/com/averycorp/prismtask/di/UseCaseModule.kt` (or DatabaseModule if no UseCaseModule) — provide `BuiltInUpdateDetector` and `BuiltInTemplateDiffer`

**Public API additions:**
```kotlin
class BuiltInTemplateDiffer @Inject constructor() {
    fun diff(
        habit: HabitEntity,
        steps: List<SelfCareStepEntity>,
        proposed: BuiltInHabitDefinition,
    ): TemplateDiff?
}

class BuiltInUpdateDetector @Inject constructor(
    private val habitDao: HabitDao,
    private val selfCareDao: SelfCareDao,
    private val registry: BuiltInHabitVersionRegistry,
    private val differ: BuiltInTemplateDiffer,
    private val prefs: BuiltInSyncPreferences,
) {
    val pendingUpdates: StateFlow<List<PendingBuiltInUpdate>>
    suspend fun refreshPendingUpdates()
    suspend fun applyUpdate(diff: TemplateDiff, accepted: AcceptedChanges)
    suspend fun dismiss(templateKey: String, version: Int)
    suspend fun detach(templateKey: String)
}
```

**Test surface:** unit tests for differ (every diff branch — added/removed/modified/preserved); detector tests with fakes (covers dismissed-version skip, detached skip, no-current-row skip, multi-template).

**Approx LOC:** 400 production, 250 test.

**Dependencies:** Layer 1.

## Layer 3 — Approval UI + detach action

**Files touched (new):**
- `app/src/main/java/com/averycorp/prismtask/ui/screens/builtinupdates/BuiltInUpdatesScreen.kt` — list of pending updates
- `app/src/main/java/com/averycorp/prismtask/ui/screens/builtinupdates/BuiltInUpdatesViewModel.kt`
- `app/src/main/java/com/averycorp/prismtask/ui/screens/builtinupdates/TemplateDiffScreen.kt` — per-template diff with checkboxes
- `app/src/main/java/com/averycorp/prismtask/ui/screens/builtinupdates/TemplateDiffViewModel.kt`
- `app/src/main/java/com/averycorp/prismtask/ui/components/BuiltInUpdatesBanner.kt` — Today-screen lightweight banner

**Files touched (existing):**
- `app/src/main/java/com/averycorp/prismtask/ui/navigation/NavGraph.kt` — register `builtinUpdates` and `builtinUpdates/{templateKey}` routes
- `app/src/main/java/com/averycorp/prismtask/ui/screens/settings/sections/TemplatesSection.kt` (or equivalent) — add "Built-in updates available (N)" entry; only shown when count >0
- `app/src/main/java/com/averycorp/prismtask/ui/screens/today/TodayScreen.kt` — render `BuiltInUpdatesBanner` when count >0 and not session-dismissed

**Public API additions:**
- Two new Compose screens, two ViewModels. No new repository methods (ViewModel calls `BuiltInUpdateDetector` directly).

**Test surface:** ViewModel unit tests only (composables tested manually in Phase A device runbook). Tests cover: pending list state, apply with partial selection, dismiss, detach.

**Approx LOC:** 600 production, 150 test.

**Dependencies:** Layer 2.

## Layer 4 — Tests + CHANGELOG

**Files touched:**
- `app/src/androidTest/java/com/averycorp/prismtask/Migration61To62Test.kt` (new) — Room migration verification
- `app/src/test/java/com/averycorp/prismtask/data/seed/BuiltInHabitVersionRegistryTest.kt` (new)
- `app/src/test/java/com/averycorp/prismtask/domain/usecase/BuiltInTemplateDifferTest.kt` (new)
- `app/src/test/java/com/averycorp/prismtask/domain/usecase/BuiltInUpdateDetectorTest.kt` (new)
- `app/src/test/java/com/averycorp/prismtask/ui/screens/builtinupdates/BuiltInUpdatesViewModelTest.kt` (new)
- `app/src/test/java/com/averycorp/prismtask/ui/screens/builtinupdates/TemplateDiffViewModelTest.kt` (new)
- `CHANGELOG.md` — add entry under `## [Unreleased]` → `### Added`

**CI/CD impact:** ~6 new test files; runtime impact <30s. No new branch protection checks needed — existing Android CI gate covers all new tests.

**Approx LOC:** 680 test.

## Risk register

| Layer | Risk | Mitigation |
|---|---|---|
| 1 | Migration v62 collides with `feat/med-reminder-mode-pr2-scheduler`'s additions | Rebase before merge; both migrations are additive ALTER TABLEs, no logical conflict |
| 1 | Sync mapper adds fields cloud peers don't yet send → reverse mapper must default safely | `mapToHabit` defaults `sourceVersion=0`, `isUserModified=false`, `isDetachedFromTemplate=false` — same shape as the existing `isBuiltIn` default |
| 2 | Detector runs at sync time on slow devices and blocks UI | All work on `Dispatchers.IO`; only the StateFlow update touches Main |
| 2 | Diff for Medication built-in needs steps from `medications` table, not `self_care_steps` | `MedicationStepProjection` adapter inside `BuiltInTemplateDiffer` — special-cased on `templateKey == "builtin_medication"` |
| 3 | Banner clutters Today screen at launch (every existing user "should" see no banner since all rows pinned at v1) | Banner only renders when `pendingUpdates.size > 0`; v1 → v1 yields 0 |
| 3 | UX iteration likely needed on diff screen | Composables are kept simple; no component library churn — Material 3 defaults |
| All | `BuiltInHabitReconciler` interaction not unit-tested for "device A applies v2, device B has v1" | Layer 4 adds an integration test in `BuiltInUpdateDetectorTest` covering the post-reconcile state |

## Cross-cutting decisions

- **Web parity is out of scope.** Built-in versioning on web is a follow-up. Web slice #732 already covers user-authored templates; built-in versioning rides on top.
- **Schema option chosen: A (embedded version on habits row).** Documented in Design §3.
- **Dismissed-version state is per-device.** Documented in Design §7.
- **Detach is sticky cross-device.** Logical OR on sync (Design §7).
- **Single-PR delivery, four commits.** Inter-PR Room version coordination is more painful than one cohesive review.

## Out of scope (explicit non-goals)

- Versioning the 5 task templates in `TemplateSeeder.BUILT_IN_TEMPLATES`. Same shape applies; follow-up PR.
- Backend-served template definitions (Option C). Migration path documented; no work this series.
- A user-shareable templates marketplace (Phase I).
- Web parity for built-in versioning.
- Notification or modal interrupt on detected updates. Discovery is opt-in via Settings + Today banner only.
