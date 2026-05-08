# F6 — MIGRATION_54_55 Dual-Write Shim Retirement — STOP-A Reframe

**Status**: STOP-A (premise wrong) — Phase 2 not entered. No production code edits.
**Date**: 2026-05-08
**Branch**: `claude/retire-dual-write-shim-0GgFQ`
**Base**: `origin/main` @ `8ce17bd` (PR #1192)
**F6 closure delta**: 0 → 0 (item remains open; reframe documented for re-filing)

---

## TL;DR

The prompt's premise — "`MIGRATION_54_55` introduced a Firestore dual-write
shim that pairs an old single-doc path with a new structured-collection path"
— does not match the current `main`. `MIGRATION_54_55` in this codebase is a
forward-only Room schema migration that adds `cloud_id TEXT` +
`updated_at INTEGER` columns to seven previously-unsynced tables, opting them
INTO Firestore sync for the first time. There is no "old path" to read from,
no "new path" to dual-write into, and no shim helper to delete.

The only live "dual-write shim" markers in `app/src/main/` are the **v1.4
medication shim in `SelfCareRepository.kt`** (spec §3.4) — explicitly out of
scope per memory #8 and per this prompt's own scoping guard.

The codebase itself records the reframe at `MigrationTelemetryEvent.kt:43`:
> "Replaces the earlier `dual_write_shim_active` framing — there is no live
> shim; quarantine tables are one-time forensic snapshots."

STOP-A fires per Item 9. Phase 2 not entered. F-series re-filing recommended
once an actual dual-write retirement target exists.

---

## Phase 0 — Base-branch verification

| Check | Result |
|---|---|
| `git fetch origin && git rebase origin/main` | clean |
| PR #1190 visible on `origin/main` | yes — `6de72a6` |
| PR #1191 visible on `origin/main` | yes — `0d9a9b3` |
| PR #1192 visible on `origin/main` | yes — `8ce17bd` (base SHA) |
| Open PRs touching SyncService / sync mappers / sync_metadata | none — `mcp__github__list_pull_requests` returned `[]` |
| STOP-G | clear |

## Phase 0 — DO-NOT-MERGE draft branch search

| Check | Result |
|---|---|
| `git branch -a \| grep -iE "MIGRATION_54_55\|migration-54\|do-not-merge\|54_55\|54-55"` | no matches |
| `git log --all --grep='Migration 54' --oneline` | empty |
| `git log --all --grep='54_55\|54->55\|54→55' --oneline` | empty |
| `git log --all --grep='#657' --oneline` | empty |

**Verdict**: draft branch not recoverable. Path 1 (use draft as starting
point) is infeasible. Per Phase 1 Item 2 default-apply rule, this would
normally collapse to Path 2 (fresh recon) — but Item 1 fresh recon then
surfaces the deeper STOP-A premise reframe below.

## Phase 0 — `MIGRATION_54_55` shape recovery from current source

`grep -rn "MIGRATION_54_55" app/src/`:

- `app/src/main/java/com/averycorp/prismtask/data/local/database/Migrations.kt:1321` — definition.
- `app/src/main/java/com/averycorp/prismtask/data/local/database/Migrations.kt:2421` — registration in the migration array.
- `app/src/androidTest/java/com/averycorp/prismtask/Migration54To55Test.kt` — instrumentation test (5 cases).

Reading the definition (`Migrations.kt:1297-1342`):

```kotlin
/**
 * v54 → v55: opt the seven remaining user-config Room entities into Firestore
 * sync by adding `cloud_id TEXT` (unique-indexed) and `updated_at INTEGER NOT
 * NULL DEFAULT 0`.
 *
 * - `reminder_profiles` (NotificationProfileEntity)
 * - `custom_sounds`     (CustomSoundEntity)
 * - `saved_filters`     (SavedFilterEntity)
 * - `nlp_shortcuts`     (NlpShortcutEntity)
 * - `habit_templates`   (HabitTemplateEntity)
 * - `project_templates` (ProjectTemplateEntity)
 * - `boundary_rules`    (BoundaryRuleEntity)
 *
 * Each table previously had no sync surface — so unlike [MIGRATION_51_52]
 * there is no backfill from `sync_metadata` and no collision resolution:
 * every row's `cloud_id` starts NULL, and `SyncService.doInitialUpload`
 * will assign cloud IDs on the next sign-in.
 */
val MIGRATION_54_55 = object : Migration(54, 55) {
    private val syncableTables = listOf(
        "reminder_profiles", "custom_sounds", "saved_filters",
        "nlp_shortcuts", "habit_templates", "project_templates",
        "boundary_rules"
    )
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in syncableTables) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `cloud_id` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_cloud_id` " +
                    "ON `$table` (`cloud_id`)"
            )
        }
    }
}
```

This is **not** a Strangler-Fig dual-write rollout. It is a one-shot schema
extension: add two columns + one unique index to seven tables. Each table had
**no prior sync surface** ("Each table previously had no sync surface … no
backfill from `sync_metadata` and no collision resolution"). There is no old
Firestore path to read from, no new-vs-old fall-through to maintain, and no
"writeBoth" / "readWithFallback" shim to retire.

## Phase 0 — Codebase-wide dual-write marker sweep

`grep -rn "dual.write\|dualWrite\|dual_write\|writeBoth\|DUAL_WRITE\|DO-NOT-MERGE\|do-not-merge\|writeOldPath\|readWithFallback" app/src/` → only test/comment matches; no runtime helpers.

`grep -rn "dual-write" app/src/main/`:

| File:line | Context |
|---|---|
| `data/repository/SelfCareRepository.kt:164` | `v1.4 dual-write shim (spec §3.4) — medication-specific writes also mirror to medications / medication_doses` |
| `data/repository/SelfCareRepository.kt:666` | `v1.4 dual-write: mirror the dose change to medication_doses.` |
| `data/repository/SelfCareRepository.kt:1098` | `v1.4 dual-write shim (spec §3.4)` block-comment header |
| `data/export/DataImporter.kt:334` | `v1.4 medications — imported after self-care so the dual-write shim's later migration runs see the real names in place.` |

All four sites are the **v1.4 medication dual-write shim**, not anything tied
to `MIGRATION_54_55`. Per memory #8 and the prompt's own scoping guard,
medication architecture is out of scope for this prompt.

## Phase 0 — Authoritative codebase statement

`app/src/main/java/com/averycorp/prismtask/domain/model/telemetry/MigrationTelemetryEvent.kt:40-52`:

```kotlin
/**
 * Fires once per app launch when the installed schema is at or
 * above the v54 medication-top-level migration. Replaces the
 * earlier "dual_write_shim_active" framing — there is no live
 * shim; quarantine tables are one-time forensic snapshots.
 * `shimAgeDays` therefore measures days since v54 was applied,
 * not days the shim has been live.
 */
data class PostV54Install(
    override val versionFrom: Int,
    override val versionTo: Int,
    val shimAgeDays: Long
) : MigrationTelemetryEvent()
```

The codebase has already done the architectural reframe the prompt is asking
about: the "dual-write shim" framing was retired in favor of "post-v54
install" telemetry, with the explicit note that **there is no live shim**.

## Phase 0 — Medication scope guard (per memory #8)

| Check | Result |
|---|---|
| Are medication entities on a Firestore dual-write path? | No — `medication_refills` is a single-write Room-backed entity opted into Firestore sync via MIGRATION_55_56 (`Migrations.kt:1366-1395`). Postgres-backed mirroring (alembic 019) is server-side, not an Android dual-write. |
| Is the v1.4 SelfCareRepository shim on the MIGRATION_54_55 path? | No — it's a Room-to-Room mirror inside the device, gated by spec §3.4, retired by a separate "Phase 2 cleanup migration" (per `Migrations.kt:1291-1293`). Not Firestore dual-write. |
| STOP-A on memory #8? | clear — medication is not a counter-example to the reframe |

---

## Phase 1 Items — verdicts

### Item 1 — Dual-write entity enumeration

**Verdict**: 0 entities currently on a Firestore dual-write shim attributable
to `MIGRATION_54_55`. Total estimated retirement LOC: **0**.

The seven tables `MIGRATION_54_55` opted into sync are sync targets — they
write to one Firestore path each, not two. There is nothing to retire because
nothing was paired in the first place.

| Entity | Sync surface | Dual-write? | Retire-LOC |
|---|---|---|---|
| reminder_profiles | NotificationProfileEntity → Firestore single-collection | no | 0 |
| custom_sounds | CustomSoundEntity → Firestore single-collection | no | 0 |
| saved_filters | SavedFilterEntity → Firestore single-collection | no | 0 |
| nlp_shortcuts | NlpShortcutEntity → Firestore single-collection | no | 0 |
| habit_templates | HabitTemplateEntity → Firestore single-collection | no | 0 |
| project_templates | ProjectTemplateEntity → Firestore single-collection | no | 0 |
| boundary_rules | BoundaryRuleEntity → Firestore single-collection | no | 0 |

### Item 2 — DO-NOT-MERGE draft viability

**Verdict**: Path 1 infeasible (draft branch not recoverable per Phase 0).
Path 2 (fresh recon) was attempted in Item 1 and surfaced the STOP-A premise
reframe — there is nothing to draft against.

### Item 3 — Scope pick

**Verdict**: not applicable — Item 1 enumerated zero retirement targets.

### Item 4 — Migration-shape pick (hard cutover vs soft-retirement)

**Verdict**: not applicable — no migration to shape.

For the record: had a real dual-write retirement been in scope, the
sole-user pre-beta state (operator confirmed at Phase 0 Item 5 below) would
default-apply Path A (hard cutover) per Item 4's rollout-window rule.

### Item 5 — `sync_metadata` schema impact

**Verdict**: GREEN — no `sync_metadata` changes required. `MIGRATION_54_55`
did not create `sync_metadata` rows for the seven tables (intentional, per
the migration's KDoc: "no backfill from sync_metadata and no collision
resolution"). Cloud IDs are assigned on first `SyncService.doInitialUpload`.

### Item 6 — Rollback / safety net

**Verdict**: GREEN — moot. Sole-user pre-beta means rollback is a local
revert; no rollout-window concern. Plus there's no retirement to roll back.

### Item 7 — Sibling migration sweep (recon-only, NOT bundled)

| Sibling shim | Status | Re-trigger criteria |
|---|---|---|
| **v1.4 medication dual-write** (`SelfCareRepository.kt:164,666,1098`; `DataImporter.kt:334`) | LIVE — explicitly retired by a future "Phase 2 cleanup migration" per `Migrations.kt:1291-1293` and SelfCareRepository.kt:1103-1105 ("Removed by Phase 2 cleanup once the Medication screen is rewired to read from MedicationRepository directly.") | When the Medication screen is rewired to MedicationRepository — file as F-series under medication-architecture umbrella. Out of scope per memory #8. |
| **MIGRATION_59_60 dual-write window** (`Migration59To60Test.kt:282`) | Test-only reference — narrative comment about "dual-write through one release before the cleanup migration"; no runtime shim found in main source. Inert. | None — test annotation only. |
| All other `MIGRATION_*` markers | Forward-only schema migrations — no dual-write shim semantics found. | None. |

No sibling shim bundling per memory #28.

### Item 8 — Test coverage plan

**Verdict**: not applicable — no code change.

Existing instrumentation `Migration54To55Test.kt` (5 cases) is healthy and
covers the actual `MIGRATION_54_55` semantics (column add + unique index +
idempotency + collision behavior). No update needed.

### Item 9 — STOP conditions

| STOP | Status |
|---|---|
| **STOP-A — premise wrong** | **FIRED.** `MIGRATION_54_55` is not a Firestore dual-write shim. It is a forward-only Room schema migration adding `cloud_id` + `updated_at` columns to seven tables. The codebase explicitly retired the "dual_write_shim_active" framing (`MigrationTelemetryEvent.kt:43`). |
| STOP-D (sibling balloon) | not reached — only one live sibling (medication v1.4), explicitly out of scope. |
| STOP-F (>200 LOC, pre-approved to 1500) | not reached — Phase 2 not entered. |
| STOP-MEGA (>1500 LOC) | not reached. |
| STOP-LOC drift | not reached. |
| STOP-G (in-flight conflict) | clear — no open PRs touch sync. |
| STOP-DATA (irreversible data step) | not reached. |

---

## A.5 — Rollout-window verification (kept for future re-filing)

Per CLAUDE.md and timeline: PrismTask is in **sole-user pre-beta phase**. The
operator is the only installed-base. `app/build.gradle.kts` `versionCode` is
the active build only — no Play Console minimum-supported-version policy is
in effect because there is no Play distribution yet.

**Implication**: any future genuine dual-write retirement on `main` can
default-apply hard cutover (Path A) without rollout-window concern, until
beta cohorts ship.

---

## Reframe summary — what the prompt likely meant

The prompt's framing matches what would be expected if `MIGRATION_54_55` had
been a Firestore single-doc-to-structured-collection migration with a
Strangler-Fig rollout. That shape DOES exist in this codebase historically —
just not at v54→v55. Candidates for the prompt's intended target:

1. **v1.4 medication dual-write shim** (`SelfCareRepository.kt` spec §3.4):
   the only live dual-write shim today. Retirement is paired with the
   "Phase 2 cleanup migration" referenced at `Migrations.kt:1291-1293`. Out
   of scope per memory #8.
2. **An earlier sync_metadata-backed migration (e.g. MIGRATION_51_52)**:
   `MIGRATION_54_55`'s KDoc cross-references it as the prior-art that DID
   need backfill + collision resolution. If a Firestore path migration ever
   happened, it would have lived around v51→v52 — pre-PR-#657. Not a current
   retirement target.

Re-trigger criteria for re-filing this audit under a corrected target:

- **Medication shim retirement**: file when the Medication UI is rewired to
  read from `MedicationRepository` directly, removing the SelfCareRepository
  dependency. Per memory #8, that's the legitimate next step on the medication
  architecture timeline.
- **Any newly-introduced Firestore dual-write**: file when `grep -rn
  "dual-write" app/src/main/` surfaces a non-medication runtime shim, or when
  a new `MIGRATION_*` lands a true Strangler-Fig rollout.

---

## Phase 4 (Session Summary) — see chat output

Phase 4 is printed directly into the Claude Code chat per prompt
instruction, NOT just saved to disk. This audit doc serves Phase 1 only.
