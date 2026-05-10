# D10 Finish Bundle — Audit-First, Maximalist-Ship

**Status**: Phase 1 audit complete; Phase 2 implementation underway.
**Date**: 2026-05-10
**Branch**: `claude/ship-d10-improvements-gadee`
**Base**: `origin/main` @ `7bb646e` (PR #1232 D13 Finish Bundle).
**D10 closure delta target**: best case 9 → 0 open items.

---

## TL;DR

D10 is a heterogeneous overflow bucket from the May 6 + May 8 cap-of-10
cascades. Phase 1 verdicts:

| # | Item | Verdict | LOC | Closure |
|---|---|---|---|---|
| 1 | MIGRATION_54_55 reframe | GREEN-PAPER-CLOSE | 0 | 0 → 1.0 |
| 2 | Medication v1.4 dual-write shim retirement | YELLOW-DEFER | 0 | stay open w/ trigger |
| 3 | Medication conflict resolution (DEFERRED-WITH-RETRIGGER) | GREEN-PAPER-CLOSE | 0 | 0 → 1.0 |
| 4 | Saved Filter Presets UI | GREEN-SHIP-MINIMAL | ~280 | 0 → 1.0 |
| 5 | BrainModePage `collectAsLocalState` idiom drift | GREEN-PAPER-CLOSE | 0 | 0 → 1.0 (already shipped) |
| 6 | Server-side Phase/Risk/Anchor/Dep/Milestone entities | GREEN-PAPER-CLOSE | 0 | 0 → 1.0 |
| 7 | asyncpg URL scheme gotcha | GREEN-SHIP (auto-transform) | ~30 | 0 → 1.0 |
| 8 | `addSubtask` / `reorderSubtasks` no-emit | GREEN-SHIP | ~20 | 0 → 1.0 |
| 9 | SoD canonical API design pass | GREEN-PAPER-CLOSE | 0 | 0 → 1.0 |

Ship LOC sum: ~330 production + ~110 tests. Total ~440. **8/9 items reach
1.0**, Item 2 stays open with a crisp re-trigger criterion. D10 substantially
closes — only one open item remaining.

---

## Phase 0 — Base verification

| Check | Result |
|---|---|
| `git status` | clean |
| `git branch --show-current` | `claude/ship-d10-improvements-gadee` |
| HEAD commit | `7bb646e` (PR #1232 — D13 Finish Bundle, on `main`) |
| `app/src/main/.../onboarding/` | exists (`OnboardingScreen.kt`, `OnboardingViewModel.kt`) — STOP-A clear |
| `backend/app/models.py` | exists (35 KB) — STOP-A clear |
| `scripts/` | exists (`set_admin.py`, `beta_codes.py`, `replace-launcher-icons.py`, hooks) — STOP-A clear |

### STOP-A — premise verification — clear

All three required directories exist; D10 premise checks land cleanly.

### STOP-PR1135 — backend entity state for Item 6

```
$ grep -nE "class (Project|Phase|Risk|Anchor|Dependency|Milestone)" backend/app/models.py
38:class ProjectStatus(str, enum.Enum):
160:class Project(Base):
334:class ProjectMember(Base):
350:class ProjectInvite(Base):
```

No `Phase`, `Risk`, `Anchor`, `Dependency`, or `Milestone` class on the
backend. Memory #13.4 is current — backend has Project (+ membership/invites)
only. Verdict: **Confirmed Project-only**. Item 6 default applies → GREEN-PAPER-CLOSE
with documented re-trigger criteria.

### STOP-PR1193 — MIGRATION_54_55 reframe context

```
$ find docs/audits -iname "*migration*54*55*" -o -iname "*1193*" -o -iname "*F6*"
docs/audits/F6_MIGRATION_54_55_AUDIT.md
```

`F6_MIGRATION_54_55_AUDIT.md` (294 lines) is on disk and explicitly STOP-A
closes the original "Firestore dual-write shim" framing as premise-wrong.
Quoting from that audit:

> The codebase has already done the architectural reframe the prompt is
> asking about: the "dual-write shim" framing was retired in favor of
> "post-v54 install" telemetry, with the explicit note that **there is no
> live shim**.

`grep -rn "MIGRATION_54_55" app/src/main` shows two references — both to the
forward-only Room migration definition — and one androidTest. Nothing to
retire, nothing to migrate. Item 1 default applies → GREEN-PAPER-CLOSE.

### STOP-MED-SHIM — Item 2 gating premise check

```
$ grep -rn "MedicationRepository" app/src/main/java/com/averycorp/prismtask/ui/screens/medication/ | head
MedicationViewModel.kt:13
MedicationViewModel.kt:102
MedicationRefillViewModel.kt:6,25,44
MedicationLogViewModel.kt:9,29,37

$ grep -rn "SelfCareRepository" app/src/main/java/com/averycorp/prismtask/ui/screens/medication/
(no matches)
```

The Medication UI **has been rewired to `MedicationRepository`** — no
`SelfCareRepository` import in any Medication screen. That is the trigger
condition referenced at `SelfCareRepository.kt:1103-1105`:

> Removed by Phase 2 cleanup once the Medication screen is rewired to read
> from `MedicationRepository` directly.

So the *read-side* trigger is met. However, the shim is bidirectional: it
mirrors writes to the new tables. The remaining unknowns:

1. Does any **write** path still flow through `SelfCareRepository.toggleStep`
   / `setTierForTime` / etc. that adjusts a medication-flagged routine? Yes
   — `SelfCareViewModel.toggleStep` still drives the SelfCare medication
   tier toggle from the SelfCare screen. The shim's `mirrorDoseChange`
   keeps `medication_doses` in sync with that path.
2. Does `DataImporter` still seed via SelfCare? Yes — comment at
   `DataImporter.kt:334` flags "v1.4 medications — imported after self-care
   so the dual-write shim's later migration runs see the real names in
   place."

→ Read-side rewiring done; **write-side mirror still required** until
SelfCare's medication routines are decoupled or removed entirely. Item 2
verdict: **YELLOW-DEFER** with a sharper trigger than the original.

---

## § Item 1 — MIGRATION_54_55 reframe

### Verdict — GREEN-PAPER-CLOSE

Reality check (Phase 0 STOP-PR1193): the F6 audit doc on disk
(`docs/audits/F6_MIGRATION_54_55_AUDIT.md`) already documented this item's
reframe in detail and the codebase explicitly retired the "dual-write shim"
framing at `MigrationTelemetryEvent.kt:43`:

> Replaces the earlier "dual_write_shim_active" framing — there is no live
> shim; quarantine tables are one-time forensic snapshots.

There is no MIGRATION_54_55 reframe work for *this* bundle to do — the
reframe has already been performed in code. The only D10 hygiene action is to
mark Item 1 done.

### What was actually shipped (already on main pre-D10)

- `app/src/main/java/com/averycorp/prismtask/data/local/database/Migrations.kt:1297-1342`
  — forward-only Room schema migration adding `cloud_id` / `updated_at`
  columns to seven tables.
- `app/src/main/java/com/averycorp/prismtask/domain/model/telemetry/MigrationTelemetryEvent.kt:40-52`
  — codebase-internal reframe of the telemetry event from
  `dual_write_shim_active` to `PostV54Install`.
- `docs/audits/F6_MIGRATION_54_55_AUDIT.md` — full STOP-A reframe audit (PR #1193).

### STOP conditions

- **STOP-1A** (migration > 200 LOC): n/a — no migration work in this PR.
- **STOP-1B** (reshape rewrites both name AND notes): n/a — no in-tree timeline
  to edit; closure is documented in the bundle audit + Phase 4 summary.

---

## § Item 2 — Medication v1.4 dual-write shim retirement

### Verdict — YELLOW-DEFER

Phase 0 STOP-MED-SHIM finding: read-side rewiring done, write-side mirror
still required. Retiring the shim now risks medication-tier writes from
SelfCare paths silently dropping out of `medications` / `medication_doses`,
which is a **medication data-loss risk** — STOP-2B territory. Out of scope
per memory #8 (medication architecture is its own track).

### Live shim surface (for future re-filing reference)

| File:line | Role |
|---|---|
| `data/repository/SelfCareRepository.kt:164-179` | Constructor doc + `MedicationDao` / `MedicationDoseDao` injection. |
| `data/repository/SelfCareRepository.kt:666` | Inline call site — `mirrorDoseChange` after a SelfCare step toggle. |
| `data/repository/SelfCareRepository.kt:1097-1206` | Block-comment header + four mirror helpers (`mirrorUpsertMedication`, `mirrorArchiveMedication`, `mirrorDoseChange`, `mergeTimeOfDay`, plus the `normalizedMedName` helper). |
| `data/export/DataImporter.kt:334` | Import-order comment: "v1.4 medications — imported after self-care". |

### Re-trigger criteria (sharper than the original "Medication UI rewiring")

File a follow-on F-series PR when **all** of:

1. **Read-side complete** — done. (`SelfCareRepository.kt:1103-1105` rewiring
   trigger satisfied; verified in Phase 0.)
2. **Write-side decoupled** — SelfCare no longer writes to medication-flagged
   routines OR the new MedicationRepository is the canonical write surface
   for all medication-affecting SelfCare operations. Currently false.
3. **DataImporter migration complete** — the v1.4 import-order comment at
   `DataImporter.kt:334` is no longer load-bearing because medications are
   imported via MedicationRepository, not via the SelfCare path. Currently
   false.

When (1) AND (2) AND (3) hold, the shim helpers + injection in
`SelfCareRepository.kt:1097-1206` can be deleted, the constructor's
`MedicationDao` / `MedicationDoseDao` params can be removed, and the
`SelfCareRepository.kt:666` call site at `mirrorDoseChange` can be deleted.
Estimated retirement LOC: ~120 deletions in `SelfCareRepository.kt` + ~10 in
`DataImporter.kt` + ~30 in tests.

### STOP conditions

- **STOP-2A** (retirement requires Medication UI changes in this PR): YES —
  the SelfCare medication routines need to be decoupled or migrated, which is
  not a hygiene-bundle change. → defer (out of scope).
- **STOP-2B** (data-loss risk): YES — retiring the write-side mirror without
  decoupling SelfCare's medication writes drops `medication_doses` rows
  silently. → halt the maximalist ship.

---

## § Item 3 — Medication conflict resolution (DEFERRED-WITH-RETRIGGER)

### Verdict — GREEN-PAPER-CLOSE

The original DEFERRED state had a single re-trigger: "web gets a medication
write surface." Phase 1 verifies this on disk:

```
$ grep -rn "medication" web/src/ | grep -iE "create|update|delete|insert|write|mutation" | head
web/src/api/firestore/medications.ts:12: * users/{uid}/medications/{cloudId}. Web does not currently write to
web/src/features/medication/MedicationSlotEditor.tsx:261:        title="Delete medication slot?"   ← UI text only, slot defs ≠ medications
web/src/features/batch/batchApplier.ts:407:  // Resolve the desired tier per mutation. Web has no per-medication dose
web/src/features/batch/batchApplier.ts:408:  // collection — every medication mutation collapses onto the slot's
web/src/features/batch/batchApplier.ts:444:        reason: 'unsupported medication mutation: ${mutationType}'
```

Web's authoritative comment is at `web/src/api/firestore/medications.ts:12`:

> Web does not currently write to [the medications collection].

`batchApplier.ts:407-408` confirms the deliberate choice that "web has no
per-medication dose collection — every medication mutation collapses onto
the slot's [tier]." `batchApplier.ts:444` returns `unsupported medication
mutation` for any genuine medication write attempt.

Trigger not met. Paper-close with sharpened re-trigger:

### Re-trigger criteria (re-filing)

When ANY of:

- `web/src/api/firestore/medications.ts` adds a non-read function (`addDoc`,
  `setDoc`, `updateDoc`, `deleteDoc`).
- `web/src/features/batch/batchApplier.ts:444` removes the
  `unsupported medication mutation` early-return.
- A web-side feature-build PR introduces a Medication editor that mutates
  `medications` or `medication_doses`.

### STOP conditions

- **STOP-3A** (web medication writes exist + missing conflict resolution):
  not fired — Phase 1 verified web has zero medication write surface.

---

## § Item 4 — Saved Filter Presets UI (largest single ship)

### Verdict — GREEN-SHIP-MINIMAL

Storage layer is fully wired (entity, DAO, sync mapper, sync collection
`saved_filters`, orphan healer family, migration v54→v55). No UI consumer.

### Storage-layer inventory

| Surface | File:line | Status |
|---|---|---|
| `SavedFilterEntity` | `data/local/entity/SavedFilterEntity.kt` | exists, 38 lines |
| `SavedFilterDao` | `data/local/dao/SavedFilterDao.kt` | exists, 49 lines, full CRUD + cloud-id helpers |
| Hilt provider | `di/DatabaseModule.kt:130` | wired |
| Test Hilt provider | `androidTest/.../smoke/TestDatabaseModule.kt:125` | wired (no parity gap) |
| Database registration | `data/local/database/PrismTaskDatabase.kt:120,184` | wired |
| Sync mapper | `data/remote/mapper/SyncMapper.kt:953,963` | wired |
| Sync upload/download | `data/remote/SyncService.kt:640-641,849,1401,1601,2781-2788,3603,3698,3776,3830` | wired |
| Cloud-ID orphan heal | `data/remote/CloudIdOrphanHealer.kt:152,319` | wired |
| Migration | `data/local/database/Migrations.kt:417-450` (create), `1297-1342` (sync opt-in v54→v55) | wired |
| **Repository wrapper** | none | **MISSING** |
| **ViewModel exposure** | none | **MISSING** |
| **UI consumer** | none | **MISSING** |

`grep -rn "SavedFilterRepository\|saveCurrentFilterAsPreset" app/src/main/`
returns zero matches — no Repository, no `TaskListViewModel.savePreset(...)`,
no UI affordance. The infra is dormant.

### TaskFilter shape (load-bearing for JSON serialization)

`domain/model/TaskFilter.kt` (44 lines) carries 9 fields, all
JSON-serializable via Gson with the existing `RecurrenceConverter` precedent:

```kotlin
data class TaskFilter(
    val selectedTagIds: List<Long>,
    val tagFilterMode: TagFilterMode,    // enum ANY/ALL
    val selectedPriorities: List<Int>,
    val selectedProjectIds: List<Long?>,
    val dateRange: DateRange?,           // (start: Long?, end: Long?)
    val showCompleted: Boolean,
    val showArchived: Boolean,
    val searchQuery: String,
    val showFlaggedOnly: Boolean,
    val selectedLifeCategories: List<LifeCategory>  // enum
)
```

Gson handles all of this out-of-box. No custom `JsonAdapter` needed.

### UI consumer shape (chosen)

The `TaskListScreen` already has a filter sheet (`ModalBottomSheet` opening
`FilterPanel` at `TaskListScreen.kt:286-311`). Minimal-viable UI:

1. **Save current filter as preset** — a "Save as Preset…" button at the
   bottom of `FilterPanel`, gated on `currentFilter.isActive()`. Opens a
   small `AlertDialog` for the name. On confirm, calls
   `viewModel.saveCurrentFilterAsPreset(name)`.
2. **Apply / delete preset** — a horizontal row of preset chips at the top
   of `FilterPanel` (above the existing "Active filters" / clear-all bar).
   Tap = apply (replaces `currentFilter`). Long-press = delete confirmation.
3. **Empty state** — when no presets exist, the chip row is hidden so it
   doesn't add visual weight to the unconfigured FilterPanel.

Deferred to follow-on (out of scope for minimal):

- Rename, reorder, custom emoji picker.
- Saved-filter management screen in Settings.
- Per-screen scoping (presets as global vs. per-screen).

### Pattern reuse

- Repository pattern: mirror `TagRepository.kt` (12-line constructor injection
  pattern; small interface).
- JSON serialization: mirror `RecurrenceConverter` (Gson, no custom adapter).
- ViewModel state: mirror `TaskListViewModel`'s `currentFilter: StateFlow<TaskFilter>` —
  add a `savedFilters: StateFlow<List<SavedFilterEntity>>` next to it.
- Sync wiring: zero changes needed; `SyncService` already handles the family.

### Estimated LOC

| Component | Lines |
|---|---|
| `SavedFilterRepository.kt` (new) | ~70 (CRUD wrapper + JSON encode/decode) |
| `TaskListViewModel.kt` additions | ~60 (state, save, apply, delete) |
| `FilterPanel.kt` additions | ~80 (preset chip row + save dialog hookup) |
| Save-preset dialog (in `FilterPanel.kt` or split) | ~40 |
| Tests (`SavedFilterRepositoryTest.kt` + filter VM additions) | ~110 |
| **Total** | **~360** |

LOC < STOP-4C threshold (400). Within budget.

### Pre-existing risks (none surfaced)

- **STOP-4A** (filter UI at UX-density cap): not fired. `FilterPanel.kt`
  (346 lines) has clear breathing room above the existing "Reset" / "Apply"
  bar; a chip row + a button slot in.
- **STOP-4B** (storage-layer assumption mismatch): not fired. `TaskFilter`
  is a flat data class; `filterJson` Gson round-trip is direct.
- **STOP-4C** (LOC > 400): under budget.

---

## § Item 5 — BrainModePage `collectAsLocalState` idiom drift

### Verdict — GREEN-PAPER-CLOSE (already fixed)

`grep -n "collectAsLocalState" OnboardingScreen.kt`:

```
781:    val adhdSelected by collectAsLocalState(viewModel.adhdMode, initial = false)
782:    val calmSelected by collectAsLocalState(viewModel.calmMode, initial = false)
783:    val focusReleaseSelected by collectAsLocalState(viewModel.focusReleaseMode, initial = false)
```

with the inline comment at lines 777-780:

```kotlin
// F8 idiom drift fix: read persisted state via the standard
// collectAsLocalState helper instead of `var ... by remember`. The
// setters now write through the ViewModel; backing up to this page
// shows the persisted selection.
```

The fix is already in tree. The "OnboardingScreen.kt:767-769" line numbers in
the prompt's framing pointed at line numbers from a pre-fix copy of the file;
the fix has since landed (likely as part of the F8 line of work referenced
in the comment). All ~22 onboarding `collectAsLocalState` call sites I
sampled use the canonical idiom. No drift to fix.

### STOP conditions

- **STOP-5A** (deviation has documented reason): n/a — no current deviation.
  The historical `var ... by remember` deviation was explicitly called out
  and corrected.

---

## § Item 6 — Server-side Phase/Risk/Anchor/Dep/Milestone entities

### Verdict — GREEN-PAPER-CLOSE

Phase 0 STOP-PR1135 outcome: **Confirmed Project-only**. Backend has
`Project`, `ProjectMember`, `ProjectInvite` (membership/invites) only. None
of `Phase`, `Risk`, `Anchor`, `Dependency`, `Milestone` exist in
`backend/app/models.py` or in alembic migrations.

Memory #13.4 is current. Android-Room + Firestore-synced is the operational
storage; backend has no read or write surface for these entities yet.

### Re-trigger criteria

File a follow-on backend-migration PR when ANY of:

1. A user-reported feature requires backend-side query of these entities
   (e.g., cross-device search across milestones, server-rendered roadmap
   timeline, ML/AI coach aggregation across projects' phase data).
2. A web feature ships that needs a Phase/Risk/Anchor/Dep/Milestone read
   path (web is already a Firestore client for Project; pulling entity
   children directly from Firestore is fine — backend migration is only
   needed for cross-user / aggregate / search workloads).
3. The Android-only authority of these entities becomes a multi-device sync
   bottleneck (Firestore is currently authoritative for sync; latency or
   conflict reports would surface as the trigger).

None of these triggers fire today. Paper-close.

### STOP conditions

- **STOP-6A** (partial migration surfaced): not fired — backend has none of
  the five entities.
- **STOP-6B** (recent user reports): not surfaced. Recent E/F-series timeline
  shows no requests for backend roadmap / phase queries.

---

## § Item 7 — asyncpg URL scheme gotcha

### Verdict — GREEN-SHIP (auto-transform helper, Option 2)

`grep -n "DATABASE_URL\|create_async_engine" scripts/*.py`:

```
scripts/set_admin.py:14   docstring
scripts/set_admin.py:22   import create_async_engine
scripts/set_admin.py:37   engine = create_async_engine(settings.DATABASE_URL, echo=False)
scripts/beta_codes.py:24  docstring
scripts/beta_codes.py:26  docstring (mirrors set_admin)
scripts/beta_codes.py:35  import create_async_engine
scripts/beta_codes.py:56  engine = create_async_engine(settings.DATABASE_URL, echo=False)
```

Two scripts. `backend/app/config.py:19` defaults DATABASE_URL to
`postgresql+asyncpg://...` for dev — fine. But Railway's auto-injected
`DATABASE_URL` env var uses the bare `postgresql://` scheme, which
SQLAlchemy `create_async_engine` rejects with:

```
sqlalchemy.exc.InvalidRequestError: The asyncio extension requires an
async driver to be used. The loaded 'psycopg2' is not async.
```

### Fix shape

Per STOP-7A's guard ("> 5 scripts → shared helper"), 2 scripts is below the
threshold. But both scripts already declare in their docstring that
`beta_codes.py` "Mirrors `scripts/set_admin.py` for shape" — so a shared
helper is the right reuse pattern even at N=2; the next admin CLI script will
inherit the gotcha for free.

Plan: add a tiny `scripts/_db.py` helper exposing
`async_engine_from_settings()` that calls
`coerce_async_url(settings.DATABASE_URL)` and returns the engine. The coercion
is:

```python
def coerce_async_url(url: str) -> str:
    """Coerce postgres URL to asyncpg driver. Idempotent."""
    if url.startswith("postgresql+asyncpg://"):
        return url
    if url.startswith("postgresql://"):
        return "postgresql+asyncpg://" + url[len("postgresql://"):]
    if url.startswith("postgres://"):  # Railway legacy alias
        return "postgresql+asyncpg://" + url[len("postgres://"):]
    return url
```

`set_admin.py` and `beta_codes.py` swap their `create_async_engine(...)`
call for `async_engine_from_settings()`. Both keep their existing dispose +
session shape unchanged.

### Estimated LOC

| Component | Lines |
|---|---|
| `scripts/_db.py` (new) | ~30 |
| `scripts/set_admin.py` edit | ~3 (import + replace) |
| `scripts/beta_codes.py` edit | ~3 (import + replace) |
| Test (`backend/tests/test_scripts_db_url.py`) | ~25 (table-driven coerce_async_url) |
| **Total** | **~60** |

### STOP conditions

- **STOP-7A** (>5 scripts): not fired — N=2; helper still preferred for
  forward-compat.

---

## § Item 8 — `addSubtask` / `reorderSubtasks` no-emit

### Verdict — GREEN-SHIP

`TaskRepository.kt:163-193` confirms the gap:

```kotlin
suspend fun addSubtask(...): Long {
    ...
    val id = taskDao.insert(task)
    syncTracker.trackCreate(id, "task")
    calendarPushDispatcher.enqueuePushTask(id)
    widgetUpdateManager.updateTaskWidgets()
    classifyInBackground(id)
    return id    // ← no automationEventBus.emit(...)
}

suspend fun reorderSubtasks(parentTaskId: Long, orderedIds: List<Long>) {
    orderedIds.forEachIndexed { index, id ->
        taskDao.updateSortOrder(id, index)
        syncTracker.trackUpdate(id, "task")
        // ← no automationEventBus.emit(...)
    }
}
```

`addTask` (line 274) and `insertTask` (line 213) DO emit
`AutomationEvent.TaskCreated(id)`. `updateTask` (line 294) emits
`TaskUpdated`. `addSubtask` / `reorderSubtasks` are the only insert/update
paths missing the emit, and the audit comment at line 268-273 of `addTask`
references the silent-failure PR #1142 as the canonical fix.

### Pattern reuse

Mirror PR #1142's pattern exactly — same `automationEventBus.emit(
AutomationEvent.TaskCreated(id))` for `addSubtask`,
`AutomationEvent.TaskUpdated(id)` for each reordered id in
`reorderSubtasks`. **Do not** modify the emit pattern itself.

### Estimated LOC

| Component | Lines |
|---|---|
| `TaskRepository.addSubtask` edit | ~3 |
| `TaskRepository.reorderSubtasks` edit | ~3 |
| Tests (`TaskRepositoryTest` additions) | ~50 |
| **Total** | **~56** |

### STOP conditions

- **STOP-8A** (emit triggers automation cascades): low risk. Subtask
  `TaskCreated` would only wake automation rules whose trigger condition
  matches a *subtask* — those rules already match on `addTask` for parent
  tasks, so subtask emission is the natural extension. No feature-flag
  needed.

---

## § Item 9 — SoD canonical API design pass (DayBoundary consolidation)

### Verdict — GREEN-PAPER-CLOSE

`grep -rln "DayBoundary\b" app/src/main/ | wc -l` → 99 call sites across
many surfaces (TaskListViewModel, TodayViewModel, MorningCheckInViewModel,
MedicationReminderScheduler, HabitReminderScheduler, LeisureRepository,
QuickRescheduleFormatter, etc.). Per STOP-9A (>20 call sites → defer), this
is well past the threshold.

The current `DayBoundary` API works. There is no recent SoD-related bug in
the timeline. No pending features require a SoD API redesign. The "design
pass" was originally filed as forward-looking hygiene; without a concrete
bug-driven trigger, the canonicalization risks being a no-op refactor that
churns 99 files for marginal clarity.

### Re-trigger criteria

File a follow-on hygiene PR when ANY of:

1. A SoD-related bug surfaces (off-by-one at midnight, DST handling
   regression, custom-startOfDay hour leakage between widgets and Today, etc.).
2. ≥3 new SoD-consuming features land in a single release window AND each
   independently re-derives "today" — indicating the API is being copied
   rather than reused.
3. A sync bug surfaces around `start_of_day` preference cross-device drift.

### STOP conditions

- **STOP-9A** (>20 call sites): fired (99 sites) → defer.

---

## § Bundle decision

### PR shape

Single bundle PR per operator pre-lock.

### Total ship LOC sum

| Item | Production | Tests | Total |
|---|---|---|---|
| 4. Saved Filter Presets UI | ~250 | ~110 | ~360 |
| 7. asyncpg URL gotcha | ~36 | ~25 | ~61 |
| 8. addSubtask / reorderSubtasks no-emit | ~6 | ~50 | ~56 |
| **Total** | **~292** | **~185** | **~477** |

Below the 1500-LOC scope-confirmation threshold. Above the 50-200 LOC
"paper-close-heavy" floor → mid-band of the prompt's "200-400 LOC mostly-paper-close
with one feature ship" outcome.

### Per-item closure verdicts table

| # | Item | Verdict | Closure |
|---|---|---|---|
| 1 | MIGRATION_54_55 reframe | GREEN-PAPER-CLOSE | 0 → 1.0 |
| 2 | Medication v1.4 dual-write shim retirement | YELLOW-DEFER | stay open w/ sharper trigger |
| 3 | Medication conflict resolution (DEFERRED-WITH-RETRIGGER) | GREEN-PAPER-CLOSE | 0 → 1.0 |
| 4 | Saved Filter Presets UI | GREEN-SHIP-MINIMAL | 0 → 1.0 |
| 5 | BrainModePage idiom drift | GREEN-PAPER-CLOSE | 0 → 1.0 (already shipped) |
| 6 | Server-side roadmap entities | GREEN-PAPER-CLOSE | 0 → 1.0 |
| 7 | asyncpg URL scheme gotcha | GREEN-SHIP | 0 → 1.0 |
| 8 | addSubtask / reorderSubtasks no-emit | GREEN-SHIP | 0 → 1.0 |
| 9 | SoD canonical API design pass | GREEN-PAPER-CLOSE | 0 → 1.0 |

**8/9 closed**, 1 stays open with a sharper re-trigger.

### Implementation order (smallest GREEN-SHIP first)

1. Item 8 — TaskRepository emits (mechanical, ~6 LOC code).
2. Item 7 — `scripts/_db.py` helper + 2 callers (mechanical, ~36 LOC code).
3. Item 5 — already done; no commit.
4. Item 4 — Saved Filter Presets UI (largest ship, ~250 LOC code).
5. Tests for Items 4, 7, 8 (~185 LOC).
6. Audit doc (this file).

Paper-closures (Items 1, 3, 6, 9) are documented in this audit and the Phase
4 chat summary; no code commits required for them.

### Cross-item dependencies

- **Items 4 + 5**: both touch onboarding/UI surfaces in principle, but
  Item 5 has zero edits (already shipped), so the file-level overlap is nil.
- **Items 7 + 8**: orthogonal — different repos (Python scripts vs Kotlin
  Android), no overlap.
- **Items 1, 3, 6, 9**: all paper-close — no file edits.

No cross-item conflicts.

### D10 closure verdict

After this PR merges: **8/9 items closed**. D10 has 1 remaining open item
(Item 2 — Medication shim retirement, YELLOW-DEFER). D10 substantially
closes; full closure deferred until SelfCare medication write paths are
decoupled from the legacy shim (separate medication-architecture track per
memory #8).

---

## Appendix A — Phase 4 (chat summary) is printed inline

Phase 4 hard constraint: summary printed directly into the Claude Code chat
output, not just saved to disk. This audit doc serves Phase 1 only.
