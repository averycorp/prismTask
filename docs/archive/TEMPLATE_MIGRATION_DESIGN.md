# Built-in Habit Template Migration — Design

Status: Proposed (PR series feat/template-migration-infra)
Authors: Avery + Claude
Last updated: 2026-04-24
Room version at design time: v61

## 1. Goals + scope

Migrate PrismTask's code-defined "built-in habits" (School, Leisure, Morning Self-Care, Bedtime Self-Care, Medication, Housework) from a **seed-once, immutable-after-accept** system to a **versioned, editable, mergeable** system.

Three layers, all in scope:

1. **Versioning** — every built-in definition carries an integer version. User-accepted instances record the source version.
2. **First-class user instances** — once accepted, the user's habit row is fully editable (it already is — we do not add new gating). What changes is that we *track its lineage*.
3. **Diff / approval / detach UI** — when a built-in version increments and the user holds the prior version, surface the diff. Per-change approval. Explicit detach to silence future prompts.

Out of scope (explicitly):

- Backend-served template definitions (Option C in §3 — kept as a future migration path)
- TaskTemplateEntity / TemplateSeeder.BUILT_IN_TEMPLATES versioning. Those task templates already use `templateKey` + reconciler; they are **not** the focus of this design. We document the parallel path so a follow-up can apply the same shape.
- A user-shareable templates marketplace (Phase I)
- Web parity. Web slice #732 ships custom user template authoring; built-in versioning on web is a follow-up tracked separately.

## 2. Current state inventory

Audited 2026-04-24 against `feat/template-migration-infra` (forked from `origin/main` @ 52a840e4).

### Built-in habits (the focus of this design)

| Identifier | name | Has SelfCareSteps? |
|---|---|---|
| `builtin_school` | School | no |
| `builtin_leisure` | Leisure | no |
| `builtin_morning_selfcare` | Morning Self-Care | yes (8 steps, `routine_type='morning'`) |
| `builtin_bedtime_selfcare` | Bedtime Self-Care | yes (10 steps, `routine_type='bedtime'`) |
| `builtin_medication` | Medication | yes (4 steps, `routine_type='medication'`) — partly migrated to `medications` table in v53→v54 |
| `builtin_housework` | Housework | yes (9 steps, `routine_type='housework'`) |

`HabitEntity` already carries `is_built_in` + `template_key` (migration 48→49). `SelfCareStepEntity` carries a stable `step_id` (e.g., `sc_stretches`) but is **not** linked to a template — it's tied to `routine_type`.

Self-care step definitions live in `domain/model/SelfCareRoutine.kt` (`SelfCareRoutines.morningSteps`, `bedtimeSteps`, `houseworkSteps`, `medicationSteps`).

### Existing reconciliation infrastructure (reused, not replaced)

- `BuiltInHabitReconciler` — dedupes cloud-pulled built-ins post-sync, merging completion history.
- `BuiltInSyncPreferences` — 5 one-time DataStore flags gating reconciliation.
- `BuiltInTaskTemplateBackfiller` / `BuiltInTaskTemplateReconciler` — same shape for task templates. Reused as the design template (we mirror the gate-flag pattern).

### What's missing (this design adds)

- Any version column on built-in habit definitions or user instances.
- Persisted record of "the user accepted version N of this template".
- Update detection at app start / sync completion.
- Diff computation between accepted version and current version.
- UI for approving / dismissing / detaching.
- "Dismissed version N" persistence (so we don't re-prompt every launch).

### Existing fields available on `HabitEntity`

```
is_built_in INTEGER DEFAULT 0
template_key TEXT NULL          -- "builtin_school", etc.
```

### Existing fields available on `SelfCareStepEntity`

```
step_id TEXT NOT NULL           -- stable per-step identity ("sc_stretches")
routine_type TEXT NOT NULL      -- "morning" | "bedtime" | "housework" | "medication"
sort_order INTEGER DEFAULT 0
updated_at INTEGER DEFAULT 0
```

## 3. Schema design — Option A (chosen)

Three options were considered (embedded version on the habit row, separate built-in tables, backend-served). **Option A wins** because it requires the smallest schema delta, ships in one migration, and preserves the existing reconciler shape. Option B is documented as the upgrade path if backend-served templates ever become valuable; Option A's columns survive that migration unchanged.

### Room migration v61 → v62

Add three columns to `habits`:

```sql
ALTER TABLE habits ADD COLUMN source_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE habits ADD COLUMN is_user_modified INTEGER NOT NULL DEFAULT 0;
ALTER TABLE habits ADD COLUMN is_detached_from_template INTEGER NOT NULL DEFAULT 0;
```

Backfill on migration:

```sql
UPDATE habits
   SET source_version = 1
 WHERE is_built_in = 1 AND template_key IS NOT NULL;
```

(Existing built-in habits are pinned to version 1 — the version they were originally seeded at. Subsequent definition bumps move to ≥2.)

Add one column to `self_care_steps` so step-level diffs survive renames:

```sql
ALTER TABLE self_care_steps ADD COLUMN source_version INTEGER NOT NULL DEFAULT 0;
UPDATE self_care_steps SET source_version = 1;
```

### `HabitEntity` (Kotlin)

```kotlin
@ColumnInfo(name = "source_version", defaultValue = "0")
val sourceVersion: Int = 0

@ColumnInfo(name = "is_user_modified", defaultValue = "0")
val isUserModified: Boolean = false

@ColumnInfo(name = "is_detached_from_template", defaultValue = "0")
val isDetachedFromTemplate: Boolean = false
```

`is_user_modified` is set to `true` the first time a user edits any field on a built-in habit row. The diff computer uses it as a hint — if the user has touched the row, additive merges still apply but field-level overwrites surface a "your edits will be preserved" note rather than silent merge.

`is_detached_from_template` is the explicit user choice. Once true, the row is never re-evaluated for updates and is no longer logically a "built-in".

### Version registry (code-defined)

A new `BuiltInHabitVersionRegistry` (Kotlin object) holds the canonical definitions:

```kotlin
data class BuiltInHabitDefinition(
    val templateKey: String,
    val version: Int,
    val name: String,
    val description: String?,
    val frequency: String,
    val targetCount: Int,
    val activeDaysCsv: String,
    val steps: List<BuiltInStepDefinition> = emptyList(),
    val deprecated: Boolean = false,    // a deprecated definition no longer seeds, but
                                        // existing user instances stay as-is
)

data class BuiltInStepDefinition(
    val stepId: String,                 // stable per-step identity
    val label: String,
    val duration: String,
    val tier: String,
    val phase: String,
    val sortOrder: Int,
)
```

The registry exposes `current(templateKey: String): BuiltInHabitDefinition?` and `versionFor(templateKey: String): Int`.

Versions are monotonically increasing integers. v1 is the originally-seeded definition. Bumping requires editing the registry and incrementing.

### Definition history (in-memory only — not stored)

We do **not** persist a version history table. The user only ever needs to know:

- What version they accepted (`habits.source_version`)
- What version is current (`BuiltInHabitVersionRegistry.versionFor(key)`)

The diff is between those two, and v1's definition is fixed in code (it's what seeded). If we ever need to ship "you skipped v2, here's the v2→v3 diff", we add a `previousDefinitions: Map<Int, BuiltInHabitDefinition>` field to the registry — no schema change.

### Dismissed-version preference

Add to `BuiltInSyncPreferences`:

```kotlin
suspend fun isDismissed(templateKey: String, version: Int): Boolean
suspend fun setDismissed(templateKey: String, version: Int)
```

Persisted as a single `Set<String>` DataStore key, entries shaped `"$templateKey@$version"`. Detach clears all dismissals for the key (the row is gone from "watched" state).

## 4. Diff algorithm

```
input:
  current  : HabitEntity + List<SelfCareStepEntity>     // user's row
  proposed : BuiltInHabitDefinition                     // registry's current

output: TemplateDiff {
  templateKey         : String
  fromVersion         : Int                             // current.sourceVersion
  toVersion           : Int                             // proposed.version
  habitFieldChanges   : List<FieldChange>               // name, description, frequency, etc.
  addedSteps          : List<BuiltInStepDefinition>     // proposed has, current lacks
  removedSteps        : List<SelfCareStepEntity>        // current has, proposed lacks
  modifiedSteps       : List<StepChange>                // same step_id, different fields
  preservedUserSteps  : List<SelfCareStepEntity>        // user-added, no step_id match
}
```

**Step identity**: stable `step_id`. Renames are a *modification*, not a remove + add. User-added steps have no matching `step_id` in any registry version — they go to `preservedUserSteps` and are never suggested for removal.

**Habit field changes**: produced field-by-field for `name`, `description`, `frequency`, `targetCount`, `activeDaysCsv`. Each `FieldChange` carries `fieldName`, `currentValue`, `proposedValue`, and `userModified: Boolean` — the last is set if the user previously edited that field (heuristic: `is_user_modified = true` on the row, no per-field tracking).

**Modification vs removal**: a step is "modified" if `step_id` appears in both; the diff lists every changed sub-field. A step is "removed" if its `step_id` is in the prior registry version but not the new one **and** is still in the user's row. (If the user already deleted it, no diff entry.)

**User-customized steps (added)**: any `SelfCareStepEntity` whose `step_id` does not appear in the registry — for any version of this template — is treated as user-added. These never appear as "removed" in the diff. They are listed in `preservedUserSteps` and are not touched on apply.

**Performance**: diff is O(steps) per template, per call. With 6 templates × ≤10 steps each, computation is sub-millisecond. Done synchronously on update detection, no worker needed. Detection itself runs on `Dispatchers.IO` after `SyncService.fullSync()` and on a Settings screen pull-to-refresh.

## 5. UI design

Single new screen: **Built-in Updates** (`BuiltInUpdatesScreen`).

### Entry points

1. **Settings → Templates → "Built-in updates available (N)"** — only shown when the detector finds ≥1 pending update. Tapping opens the screen.
2. **Today screen banner** (lightweight, dismissible) — "School template has new content. Review →" — only when the detected count is >0 *and* the user has not dismissed the banner this session.
3. No notification. No modal interrupt. Discovery is opt-in.

### Screen layout

```
┌─────────────────────────────────────────────┐
│  ← Built-in Updates                          │
├─────────────────────────────────────────────┤
│                                              │
│  School               v1 → v2                │
│  Updated 2026-04-15                          │
│                                              │
│   + Added: 3 new tasks                       │
│   ~ Changed: target frequency 1×/day → 2×/day│
│                                              │
│   [ Review changes ]  [ Dismiss ]  [ Detach ]│
│                                              │
├─────────────────────────────────────────────┤
│                                              │
│  Morning Self-Care    v1 → v3                │
│   + Added: 2 steps                           │
│   ~ Changed: 1 step renamed                  │
│                                              │
│   [ Review changes ]  [ Dismiss ]  [ Detach ]│
│                                              │
└─────────────────────────────────────────────┘
```

Tapping **Review changes** opens the per-template diff screen:

```
┌─────────────────────────────────────────────┐
│  ← School: v1 → v2                           │
├─────────────────────────────────────────────┤
│                                              │
│  HABIT FIELDS                                │
│  ☑ Frequency: 1×/day → 2×/day                │
│      (you haven't edited this — safe)        │
│                                              │
│  ADDED STEPS                                 │
│  ☑ Office Hours Or Study Group               │
│  ☑ Plan Next Week's Schedule                 │
│  ☑ Submit Due Assignments                    │
│                                              │
│  CHANGED STEPS                               │
│  ☑ "Read Chapter" → "Read Assigned Chapter"  │
│                                              │
│  REMOVED STEPS                               │
│  ☐ Old Quiz Step                             │
│      (still on your habit — uncheck to keep) │
│                                              │
│  YOUR ADDED STEPS (always preserved)         │
│  • My Custom Step                            │
│                                              │
│  [ Apply selected ]    [ Cancel ]            │
└─────────────────────────────────────────────┘
```

### Approval granularity

Per-change checkboxes. Defaults:
- Added steps: checked
- Modified steps: checked, unless `is_user_modified = true` on the habit row → unchecked with note "you edited this — uncheck to keep your version"
- Removed steps: **unchecked** (we don't surprise-delete)
- Habit field changes: checked, same user-edit guard

### Dismiss vs detach

- **Dismiss** — records `(templateKey, toVersion)` in dismissed-versions. The screen will not surface this update again. If a higher version ships later, it surfaces fresh.
- **Detach** — sets `is_detached_from_template = true`. Permanently severs the link; no future updates surface for this row. Also clears all dismissed entries for this key.

### Edge cases handled in UI

- User has 0 updates → screen empty state: "Your built-in habits are up to date."
- User detached, then a new version ships → no entry, by design.
- User has the habit row deleted entirely → `BuiltInHabitReconciler` already handles re-creation if the template was never accepted; if the user actively deleted it, we treat that as detach. Detection skips templates with no matching habit row.

## 6. Edge cases

| Scenario | Behavior |
|---|---|
| User accepted v1, removed 2 of its steps, then v2 adds 3 steps | Diff shows 3 added (suggested checked); 0 removed (already gone); user's deletions are preserved |
| User detached, then v2 ships | No prompt. Detached state is sticky |
| User has phone + tablet, both accept v2 on different days | First device's apply syncs `source_version=2` + step changes through Firestore; second device sees `source_version=2` already and skips detection. No re-prompt |
| Dismissed v2 on phone, opens tablet, also has v1 | Tablet still prompts (dismissals are per-device by default; see §7). User can dismiss again or apply |
| BUILT_IN_TEMPLATES code definitions change between v1.5.3 and v1.6.0 | Existing users get prompt on update; new users get the latest version with no prompt (registry seeds at current version) |
| Built-in template gets *removed* from the registry | Registry returns `null` for `current(key)`. Existing user rows are not touched and become functionally detached. We do *not* set `is_detached_from_template=true` automatically — leave the row marked as built-in so reconciler still works if the template comes back. Detector treats `null current` as "no diff" |
| User edits a field, then a new version changes that same field | Diff entry has `userModified=true`, default unchecked, with note. User makes the call |
| A v1 user upgrades to v3 (skipped v2) | Diff is computed v1 → v3 directly. We do not show v2 separately. Future enhancement: show "this update incorporates changes from v2 and v3" in subtitle |

## 7. Sync implications

### What syncs

- `habits.source_version` — included in `SyncMapper.habitToMap`. Last-write-wins via `updated_at` (already present).
- `habits.is_user_modified` — included. Last-write-wins.
- `habits.is_detached_from_template` — included. **Special rule**: detach is sticky cross-device. If either device sets it, the merged value is `true` (logical OR, not LWW). Implemented as: `pull` path sets local `true` if remote is `true`, regardless of `updated_at`.
- `self_care_steps.source_version` — included alongside the existing sync mapping for the table.

### What doesn't sync

- `BuiltInSyncPreferences` dismissed-version set is **per-device by default**. Rationale: dismissal is a UI-state decision, not a content decision. A user dismissing on phone may genuinely want to revisit on tablet. Documented as a tradeoff; revisit if users complain.
- The registry itself never syncs (it's code).

### Ordering with existing reconciliation

`SyncService.fullSync()` order becomes:

1. Push local changes
2. Pull remote
3. `BuiltInHabitReconciler.reconcileAfterSyncIfNeeded()` (existing — dedupes built-ins)
4. **NEW**: `BuiltInUpdateDetector.refreshPendingUpdates()` — recomputes the "pending updates" cache the UI reads from
5. `BuiltInTaskTemplateReconciler.reconcileAfterSyncIfNeeded()` (existing)

Step 4 only writes to a local in-memory `StateFlow`/DataStore cache. No DB writes, no Firestore writes.

## 8. Effort estimate per layer

Estimates are solo-dev with the existing infra to lean on. LOC includes whitespace + boilerplate.

| Layer | Production LOC | Test LOC | Migrations | Phase fit |
|---|---|---|---|---|
| Versioning schema + registry | ~250 | ~150 | 1 Room (v62) | Phase B |
| Update detection + diff | ~400 | ~300 | 0 | Phase B |
| Approval UI + ViewModel | ~600 | ~150 (VM only) | 0 | Phase B |
| Detach action + sync rules | ~80 | ~80 | 0 | Phase B |
| **Total** | **~1330** | **~680** | **1** | **Phase B** |

Web parity for built-in versioning: ~+800 LOC, deferred. Web slice #732 already ships custom user template authoring; built-in versioning rides on top of that and is independently scopable.

## 9. Timing recommendation

**Recommendation: Phase B (this PR series).**

Rationale:
- The schema migration is small (3 columns + 1) and lands cleanly with the existing v61 → v62 medication-reminder migration window.
- Defaulting `source_version=1` means existing users see no behavior change until a v2 of any template ships — this is purely *infrastructure*, not a content change.
- Phase F.0 carries risk: if any built-in needs to change between now and post-launch, users on the old code get the awkward "new install vs upgrade install have different built-ins forever" state.
- Phase I (marketplace) builds on this same shape — punting now means redoing it later under marketplace deadlines.
- Avery's bridge-job risk argues against deferral: shipping the foundation now means future template content updates are a 5-minute registry edit + version bump, not a "build the migration system first" task.

Beta urgency: this PR is ~1300 LOC over 4 PRs. With the audit done and the design locked, implementation is mechanical. Risk to beta date is low.

## 10. Open questions / risks

1. **Dismissals: per-device or per-user?** Default per-device, documented in §7. Revisit on first user complaint.
2. **Banner placement** on Today screen — does it compete with morning check-in banner? Banner is dismissible per session and only appears when count >0. A11y label included. Will live-test in Phase A device runbook.
3. **Self-care step migration in Medication** — the v53→v54 medication migration moved data out of `self_care_steps` for the medication routine. The Medication built-in habit's diff needs to source steps from the `medications` table, not `self_care_steps`. PR1 handles this with a `MedicationStepProjection` adapter.
4. **`BuiltInTaskTemplateBackfiller` parallel** — this design does not version the 5 task templates. Documented gap; follow-up PR (not in this series) applies the same shape to `TaskTemplateEntity`. The `BuiltInHabitVersionRegistry` is structured to make that follow-up a copy-with-renames.
5. **Reconciler interaction** — `BuiltInHabitReconciler` merges duplicates by picking the row with most completions. After a user applies a v2 update, the row's `updated_at` bumps and the loser-side completions are merged. Need a test for: device A applies v2, device B has v1 unmodified, sync runs — outcome should be a single row at v2 with no data loss.
6. **Risk: user confusion** — "what is a built-in?" The screen copy must avoid the word "template" in favor of "built-in habit", to align with how users encounter these.
