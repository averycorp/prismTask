# SPEC — Built-In Task Template Recovery (fixing the existing reconciler's blind spot)

**Status:** Proposal — not yet scheduled
**Author:** Scoped via audit on 2026-04-22
**Target Room version:** no schema change required
**Related docs:** `docs/sync-architecture.md`, `docs/SPEC_SELF_CARE_STEPS_SYNC_PIPELINE.md` (sibling out-of-band healer spec, complementary scope)

---

## 0. Reader's note — prompt premise correction

The brief that triggered this spec said: "implement `BuiltInTaskTemplateReconciler` following the `BuiltInHabitReconciler` pattern." Audit found that **the reconciler already exists** at `app/src/main/java/com/averycorp/prismtask/data/remote/BuiltInTaskTemplateReconciler.kt` (98 lines), is wired into `SyncService.fullSync` at line 1292 alongside its habit sibling, is gated by `BuiltInSyncPreferences.isBuiltInTaskTemplatesReconciled`, and is tested by `app/src/androidTest/java/com/averycorp/prismtask/BuiltInTaskTemplateReconcilerTest.kt`.

The +5-surplus bug is real — the reconciler just can't see the duplicates because the pulled rows are mis-shaped (missing `template_key` and likely missing `is_built_in = true`). The reconciler's group-by-`templateKey` filter filters them out of its working set before merging.

This spec fixes the data shape, not the reconciler's code.

---

## 1. Problem statement

The audit on 2026-04-22 showed `task_templates` at count = 13 with a canonical count of 8 — a +5 surplus. The breakdown:

| Subset | Count | `template_key` | `cloud_id` | Notes |
|---|---|---|---|---|
| Locally reseeded built-ins | 5 | set (`builtin_school`, `builtin_medication`, etc.) | NULL | Created fresh by `TemplateSeeder` post-Fix-D |
| Firestore-pulled built-ins | 8 | **NULL** | set | Pulled from docs created before `template_key` column existed |

`BuiltInTaskTemplateReconciler.mergeDuplicateBuiltIns()` (lines 52-89) does:

```
1. builtIns = taskTemplateDao.getBuiltInTemplatesOnce()   // WHERE is_built_in = 1
2. groups = builtIns.filter { !it.templateKey.isNullOrBlank() }
                    .groupBy { it.templateKey!! }
3. For each group with size > 1: merge (keep lex-smallest cloud_id)
```

**Why the 8 pulled rows are invisible to the reconciler:**

- `SyncMapper.mapToTaskTemplate` (`data/remote/mapper/SyncMapper.kt:423`) reads `isBuiltIn = data["isBuiltIn"] as? Boolean ?: false`. Firestore docs created before the `is_built_in` column existed have no `isBuiltIn` field, so pulled rows land with `is_built_in = false` → filtered out by the DAO's `WHERE is_built_in = 1` at TaskTemplateDao.kt:70-71.
- Even if `is_built_in` survives somehow, `templateKey = data["templateKey"] as? String` at SyncMapper.kt:424 reads null from pre-`template_key` docs → filtered out by the reconciler's `.filter { !it.templateKey.isNullOrBlank() }` at line 57-58.

Net: the reconciler's working set contains only the 5 freshly-reseeded rows, each with a unique `templateKey`. `groupBy` yields zero groups with size > 1. No merges fire. The flag flips to `true` on success. The reconciler will never run again on this device.

## 2. Evidence

- Audit count breakdown above, from CLAUDE.md's canonical count (8 built-in templates) vs observed post-Fix-D live state (13 rows across both devices, confirmed 2026-04-22).
- `BuiltInTaskTemplateReconciler.kt:52-97` — merge rule filters on `is_built_in=1` AND non-blank `templateKey`.
- `SyncMapper.kt:401-429` — `mapToTaskTemplate` reads `isBuiltIn` and `templateKey` with `?: false` / `as? String` fallbacks.
- `TaskTemplateDao.kt:70-71` — `getBuiltInTemplatesOnce()` uses `WHERE is_built_in = 1`.
- Part 1b sync log (`test-results/restore-from-backup/20260422T041326Z/RESULTS.md`) — no `reconcile.builtin_template` log lines during the full sync cycle, confirming the reconciler either short-circuited on the idempotency flag or ran with an empty working set.

## 3. Proposed fix — name-based `templateKey` + `is_built_in` backfill, followed by repush

One-shot backfill pass. Different shape from `CloudIdOrphanHealer` (sibling spec) — the issue isn't missing cloud docs, it's mis-shaped local rows.

### 3.1. `BuiltInTaskTemplateBackfiller` (new class)

Runs from `SyncService.startAutoSync` once, gated by a new `BuiltInSyncPreferences.isTaskTemplateBackfillDone` flag (parity with the existing `isBuiltInBackfillDone` for habits at `BuiltInSyncPreferences.kt:30`). Logic:

1. Load the canonical built-in seed table from `TemplateSeeder.BUILT_IN_TEMPLATES` (refactor if needed — extract a `Map<String, TemplateKey>` keyed by canonical `name`).
2. `SELECT * FROM task_templates WHERE template_key IS NULL OR template_key = ""` — rows that are candidates for backfill regardless of their current `is_built_in` status (because we can't trust it either).
3. For each candidate row, match by **name** (exact match, case-insensitive) against the canonical seed table.
   - If match: `UPDATE task_templates SET template_key = :key, is_built_in = 1, updated_at = :now WHERE id = :id`.
   - If no match: leave row alone (user-created template).
4. For each backfilled row, call `syncTracker.trackUpdate(rowId, "task_template")` so the reactive push queue picks it up and pushes the updated fields (`isBuiltIn`, `templateKey`) to the existing Firestore doc via `pushUpdate`. This cures Firestore's doc too — future pulls on other devices will now receive correct values.
5. **After** the backfill finishes (and its pending pushes complete), reset the `isBuiltInTaskTemplatesReconciled` flag to false on this device. The next `fullSync` will re-run the reconciler, which now has a full working set (5 reseeded + 5–8 backfilled matching by templateKey) and will correctly collapse the dupes. The flag gets set back to true after that reconciler pass.
6. Set `isTaskTemplateBackfillDone = true` on success. On partial failure, flag stays false — retry on next launch.

The reset-then-reconcile sequence in step 5 is the key insight: we don't need to change the reconciler's merge rule. We just need to make it run **once more** on a correctly-shaped dataset.

### 3.2. Matching strategy

Match on `name` (exact, trimmed, case-insensitive) rather than `template_title` because `name` is the user-visible and seeder-stable identifier in `TaskTemplateEntity` (line 36). `template_title` is a downstream display field that can diverge per-user customization.

If a future built-in's name is renamed, the matcher must track both old and new names. Suggested encoding: `BUILT_IN_TEMPLATE_NAMES: Map<String, TemplateKey>` where the key side accepts multiple aliases:

```kotlin
val BUILT_IN_TEMPLATE_NAME_ALIASES = mapOf(
    "Study Session" to "builtin_study",
    "Deep Work" to "builtin_study",           // old name, kept for migration
    "Errand" to "builtin_errand",
    // ...
)
```

Single source of truth = the seeder's canonical table + this alias map. Both live in `TemplateSeeder.kt` or a sibling `BuiltInTemplateCatalog.kt`.

### 3.3. Migration strategy

- **No DB schema migration.** Update-only SQL on existing columns.
- **Idempotency:** gated by `isTaskTemplateBackfillDone`. If a user has already run the backfill and later somehow acquires a new unmatched row (unlikely but possible), the next version's change to `BUILT_IN_TEMPLATE_NAME_ALIASES` can carry a preference-flag reset if needed.
- **Backfill ordering:** run the backfiller BEFORE `fullSync()` in `startAutoSync` (so the reconciler sees the fixed dataset on the very first sync after deploy). Specifically: place the call inside `startAutoSync` (`SyncService.kt:1393+`) between `restoreCloudIdFromMetadata()` and `fullSync(...)`.
- **First-run behavior for the audited user:**
  - Backfiller matches 5 of the 8 Firestore-pulled rows to their reseeded siblings → updates `template_key` + `is_built_in` locally, queues updates for Firestore.
  - Reactive push fires, Firestore docs get the updated fields.
  - `isBuiltInTaskTemplatesReconciled` resets to false.
  - Next `fullSync` runs the reconciler; it now sees 13 rows with `is_built_in=1` across 5 groups (each group size = 2 or more, depending on whether extras exist for some templates).
  - Reconciler merges each group: keeper = row with the lex-smallest non-blank cloud_id (which for matched pairs is the pulled-from-Firestore row, since the reseeded row has `cloud_id = NULL`). Losers deleted.
  - End state: 5 merged rows with `cloud_id` set AND `template_key` set AND `is_built_in = 1`, plus 3 "extras" if Firestore had 8 rows matching only 5 built-ins (e.g., legacy renamed/deprecated templates — investigate per-case).

### 3.4. Rollback plan

If the backfiller matches the wrong row (e.g., a user named their custom template "Study Session" before the built-in of that name existed), we'd flag that row as is_built_in=1 incorrectly, and the subsequent reconciler could merge it into a built-in row and delete the user's content.

**Mitigation:** before updating any row, verify the row's other built-in shape markers:
- `created_at` is within 1 day of app install (first-run seeder timing) OR
- `usage_count == 0` AND `last_used_at IS NULL` (untouched by user)

If NEITHER holds, skip that row — it's probably user content that happens to share a name with a built-in. Log `backfill.skipped | reason=user_content_collision | templateKey=X id=Y`.

Alternative rollback: keep a shadow table `task_template_backfill_log(id, pre_template_key, pre_is_built_in, changed_at)` for 30 days. If a user reports missing templates, we can revert their row changes. Lightweight; one insert per backfill. Recommended.

## 4. Effort estimate

**~20-45 min, multi-checkpoint.** Breakdown:

| PR | Scope | Est. time |
|---|---|---|
| 1 | `BuiltInTaskTemplateBackfiller` class + `BUILT_IN_TEMPLATE_NAME_ALIASES` catalog + DI + `SyncService.startAutoSync` integration | 25-40 min |
| 2 | Unit tests for the matcher (name aliases, user-content-collision detection, no-op idempotency) | 15-25 min |

Optional follow-up PR (can be batched into PR1 if review bandwidth allows):
| 3 | Shadow table for rollback safety + 30-day cleanup worker | 15-20 min |

Single-PR total: **~45-60 min**.

## 5. Risks

- **Risk 1 — name collision with user content.** Covered by the `created_at` + `usage_count` guard in 3.4. Acceptable residual risk: user created a template with a built-in name AND never used it AND had it long enough that the created_at heuristic fails. Very unlikely in practice.
- **Risk 2 — alias drift over time.** If we keep renaming built-ins, the alias map grows indefinitely. Alternative: stamp every Firestore built-in doc with a stable `templateKey` going forward, and drop alias matching after 2-3 releases. Deferred decision; not blocking.
- **Risk 3 — reconciler interaction with the orphan healer (sibling spec).** If `CloudIdOrphanHealer` runs first and decides a backfilled row is orphaned, it'll mark it pending-create with a fresh cloud_id, losing the existing Firestore doc link. Mitigation: order matters — backfiller in `startAutoSync` runs before `fullSync`, which runs `pushLocalChanges` → `pullRemoteChanges` → orphan healer → reconcilers. The backfilled row's push completes before the orphan healer gets to it, and push clears the pendingAction. No race.
- **Risk 4 — Firestore rules rejecting the update push.** If prod rules are more restrictive than the emulator stub (`firestore.rules:1-15`), the update writes could fail. Unlikely given existing entities push fine, but worth verifying in the Firebase console after deploy.

## 6. Phase placement recommendation

**Phase A2 or Phase B Wk 1, whichever has capacity first.** Lower urgency than the self-care-steps spec because the damage here is cosmetic (+5 surplus rows in the templates list) rather than data loss. User can still use templates; they just see duplicates in the picker.

Must ship before Phase D, since Phase D introduces user-facing template customization and the surplus would confuse the "which one do I edit?" UX.

## 7. Test plan

Unit (`app/src/test/java/com/averycorp/prismtask/`):
- `BuiltInTaskTemplateBackfillerTest`:
  - `backfill_matchesByExactName_setsTemplateKeyAndIsBuiltIn()`
  - `backfill_matchesByAlias_setsCurrentTemplateKey()`
  - `backfill_withUserContentCollision_skips()` (tests the `usage_count > 0` guard)
  - `backfill_isIdempotent_secondRunNoop()`
  - `backfill_queuesUpdateForSyncTracker()` (asserts `syncTracker.trackUpdate` called)
  - `backfill_resetsReconcilerFlag_afterSuccess()`
- `BuiltInTemplateCatalogTest` (if the catalog gets extracted):
  - `aliases_allMapToValidTemplateKeys()`
  - `canonicalNames_haveNoCollisions()`

Instrumentation:
- `TaskTemplateRecoveryTest`:
  - Setup: seed DB with 5 reseeded built-ins (template_key set, cloud_id NULL) + 8 pulled rows (cloud_id set, template_key NULL, 5 of which match by name to the reseeded set + 3 user-created with non-matching names).
  - Trigger `startAutoSync`.
  - Assert post-backfill: 13 rows still present, 5 pulled rows now have template_key + is_built_in=1.
  - Wait for reactive push to flush.
  - Assert Firestore docs for those 5 were updated (mock Firestore or use emulator).
  - Trigger `fullSync` again.
  - Assert reconciler fires and collapses to 8 rows (5 merged built-ins + 3 user-created pulled).
  - Assert no user content was deleted.

## 8. Open questions

- **Q1.** Is there a canonical source for the built-in template names, or are they scattered across `TemplateSeeder`? Extract to a single constants file as part of PR 1 if they're not already consolidated.
- **Q2.** Why does the canonical count differ (CLAUDE.md says 8 built-ins; audit suggests only 5 get reseeded). Possibilities: the 5 reseeded are a strict subset of the 8 canonical because the other 3 are "archived" built-ins that only come down via Firestore pull; or the canonical is 5 and CLAUDE.md is stale; or the seeder is buggy and only laying down 5 of 8 on fresh install. Resolve before writing the catalog — the answer determines whether the matcher should expect a subset or the full set.
- **Q3.** Should this backfiller also cover `HabitEntity` rows that might have the same `template_key IS NULL` shape from pre-v48 Firestore docs? The habit side has its own `runBackfillIfNeeded()` at `BuiltInHabitReconciler.kt:42-55` which calls `habitDao.backfillAllBuiltIns()`. Verify the habit backfill is also name-based (or sufficiently robust) during PR 1 review.
