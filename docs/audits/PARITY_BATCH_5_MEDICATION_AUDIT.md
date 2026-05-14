# Parity Batch 5 — Medication (Phase 1 Audit)

**Trigger:** Batch 5 of the Android↔Web complete-parity work plan
(`docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md` § E).
**Scope:** E.1 — Web medication CRUD (six sub-items); E.2 — `medication_slots` ↔
`medication_slot_defs` collection-name reconciliation; E.3 —
`medication_tier_states` doc-id scheme reconciliation; E.4 —
`daily_essential_slot_completions` split-brain.
**Phase 1 only.** Phase 2 fan-out + Phase 3 summary appended after PRs ship.
**Cap:** ≤ 500 lines per CLAUDE.md audit-length convention.
**Working tree:** local `main` at `dedc56ce` (post #1314). Branch `main`.
**Sibling audits referenced:** `ANDROID_WEB_PARITY_AUDIT.md` § Surface 1
(deep baseline), `2026-04-25_migration_json_silent_default.md` (migration
hazards reference), `ALLOW_UNSCHEDULED_MEDICATION_AUDIT.md` (slot semantics).

---

## Premises verified

Read directly (no inference):

- **Web `medicationSlots.ts:128–134`** — slot defs live at
  `users/{uid}/medication_slot_defs`. Deterministic doc id `${dateIso}__${slotKey}`
  for tier-states is at `:228–242`. Confirmed.
- **Web `medications.ts:18–63`** — READ-ONLY today. Only used by batch-ops
  disambiguation picker. **No web write path exists for `medications`**, so
  E.1a starts from zero.
- **Web `medicationPreferences.ts`** — global reminder-mode doc only;
  per-med CRUD absent.
- **Android `SyncDispatchTables.kt:27–31`** — Android writes
  `medication_slots`, `medication_tier_states`. Confirmed collection-name
  divergence.
- **Android `MedicationSyncMapper.kt:134–145`** slot doc shape:
  `{name, idealTime, driftMinutes, sortOrder, isActive, reminderMode,
  reminderIntervalMinutes, …}`. Identifier is the generated Firestore docId
  (kept locally as `cloud_id`).
- **Android `MedicationSyncMapper.kt:196–211`** tier-state doc shape:
  `{medicationCloudId, slotCloudId, logDate, tier, tierSource,
  intendedTime, loggedAt, …}`. Identifier is generated Firestore docId.
- **Web slot-def doc shape (`medicationSlots.ts:84–101`):**
  `{slotKey, displayName, sortOrder, reminderMode,
  reminderIntervalMinutes, createdAt, updatedAt}`. **No `idealTime`,
  `driftMinutes`, or `isActive`.**
- **Web tier-state doc shape (`medicationSlots.ts:103–124`):**
  `{slotKey, dateIso, tier, source, intendedTime, loggedAt, …}`. **No
  `medicationCloudId` and no per-med dimension at all.** Web stores
  per-slot aggregate, not per-(med, slot) like Android.
- **Web `MedicationScreen.tsx:13`** — uses `dailyEssentialsApi` (backend
  REST) for slot toggles. **No Firestore write to
  `daily_essential_slot_completions`** anywhere in `web/src/api/`.
  Confirmed split-brain.
- **Android `BackendSyncService.kt:411–413`** + `SyncService.kt:1359–1362`
  — Android writes `daily_essential_slot_completion` to BOTH backend REST
  (BackendSyncService) AND Firestore (SyncService legacy path).
- **DB version:** `CURRENT_DB_VERSION = 82` in
  `data/local/database/Migrations.kt:2617`. Latest migrations: 80→81, 81→82.
- **Backend:** no `clinical-report` endpoint exists yet; only
  Android-side `domain/usecase/ClinicalReportGenerator.kt`. E.1e needs both
  backend + web work.
- **Web `MedicationSlotList.tsx:71`** comment "Web does not derive virtual
  slots the way Android does" — confirmed; web only displays slots
  materialized by Android push-to-backend.

**No premise mismatches.** No STOP-and-report triggers.

---

## Architectural decisions (recommended, not open questions)

### Decision D-E2 — Slot collection canonicalization → keep **`medication_slots`** (Android-side name); migrate the **web** schema in place

The parent audit's note ("recommend migrating Android to `medication_slot_defs`
because web's deterministic scheme is more sound") **is reversed here** after
reading the actual schemas. Rationale:

- **Identity:** the "soundness" argument was about deterministic IDs.
  Web's `medication_slot_defs` doc IDs are **not** deterministic — they are
  Firestore-generated (`addDoc(...)` at `medicationSlots.ts:182`). The only
  natural key is `slotKey`, but the doc isn't stored at
  `medication_slot_defs/{slotKey}` — it's stored at
  `medication_slot_defs/{random}`. There's no advantage over Android's
  scheme on that axis.
- **Schema breadth:** Android's slot has `idealTime` + `driftMinutes` +
  `isActive` — semantically richer (drives reminder windows + soft-delete
  for tier-state history preservation). Web has neither.
  **Migrating Android to web's schema would lose data.** Migrating web to
  Android's adds fields.
- **Reference count:** Android `medication_slots` is referenced by 3 child
  Room tables (`medication_slot_overrides`, `medication_tier_states`,
  `medication_medication_slots` cross-ref) **with FK constraints**.
  Renaming the collection on Android requires either a Room migration
  rename (cheap) + Firestore-side data migration (per-user batch update) —
  but the **schema-merge** is the dominant cost regardless of direction.
  Doing it in the direction that loses zero columns is the clear win.

**D-E2 action:**
1. Web `medication_slot_defs` collection → **rename to `medication_slots`**.
2. Web write path adds `idealTime` ("HH:mm" string, default `"09:00"`),
   `driftMinutes` (default `180`), `isActive` (default `true`).
3. Web read path tolerates docs **without** those fields for one release
   (fallback defaults match Android Room defaults — see
   `MedicationSlotEntity.kt:46–51`).
4. Firestore data-migration script (one-time, run-once-per-user inside the
   web client on next sign-in): copy each `medication_slot_defs/*` doc into
   `medication_slots/*` with the new fields, then leave the old doc untouched
   (do NOT delete — dual-read window allows older clients to keep reading).
   Old docs garbage-collected after web v1.7.0 has 60 days of telemetry.
5. Android: no Room migration required (schema unchanged). Already writes
   to `medication_slots` — confirmed via `SyncDispatchTables.kt`.

### Decision D-E3 — Tier-state doc-id canonicalization → keep **deterministic** `${cloudMedicationId}__${dateIso}__${cloudSlotId}` (Android migrates to deterministic; web's per-(slot, date) keying is insufficient)

The parent audit said "migrate Android to deterministic scheme." Adopted, with
one shape correction: web's current `${dateIso}__${slotKey}` key omits the
medication dimension, but Android's table is keyed `(medication, slot, date,
time_of_day)`. The deterministic id must include the medication cloudId or
parity is impossible.

**Recommended deterministic id:** `${medicationCloudId}__${dateIso}__${slotCloudId}`
(when web is upgraded to per-med tier rows). For the dual-read interim, web
keeps `${dateIso}__${slotKey}` while a new `medication_tier_states` index
sibling collection grows the per-med rows from Android. Web reads union of
both during transition.

**D-E3 action:**
1. **Android** — extend `SyncService.kt` medication_tier_state push to use
   `userCollection("medication_tier_states").document(detId)` with
   `detId = "${medCloudId}__${logDate}__${slotCloudId}"` instead of
   `.document()` (auto-id). Backfill script: for each existing tier-state
   row where `cloud_id` is set and doesn't match the deterministic form,
   `setDoc(deterministicId, data, {merge: true})` at the new id, then delete
   the old auto-id doc. Track via new `MedicationMigrationPreferences` flag.
2. **Web** — extend `medicationSlots.ts` tier-state writes to optionally
   take a `medicationCloudId` and produce the same deterministic id when
   present. Dual-read until E.1a (web per-med CRUD) lands — without
   medication CRUD on web, web alone cannot produce per-(med, slot) rows.
3. Once both sides write deterministic ids, the two-row coexistence
   resolves to one row per (med, slot, day) — Pattern A from the cloud-id
   dedup audit.

**Migration safety:** Android `MedicationTierStateEntity` has `cloud_id`
column already (line 54). The Room schema does NOT need to change for E.3 —
only the upload helper. **No `ADD COLUMN`, no `DROP COLUMN`.** This means
E.3 is a zero-Room-migration change on Android.

### Decision D-E4 — `daily_essential_slot_completions` split-brain → **remove Firestore writes on Android; backend is authoritative**

Per `BackendSyncService.kt:411–413` Android already pulls from backend for
this entity. The Firestore path in `SyncService.kt:1359–1362` is legacy.
Recent precedent (D11 E.3 chat messages: backend-authoritative, no Firestore)
confirms direction.

**D-E4 action:**
1. Remove `"daily_essential_slot_completion"` from
   `SyncDispatchTables.kt:49` (push side) AND `:94` (collection-name reverse
   lookup). Add a comment pointing at `BackendSyncMappers.kt:170` as the
   active path.
2. Delete the `"daily_essential_slot_completion"` push/pull arms from
   `SyncService.kt:1359` and `:1555` and the delete arm at `:2011`.
3. Leave the Room table + DAO untouched — backend continues to mirror
   into it via `BackendSyncService`.
4. **Do NOT delete Firestore data** in the rollout. Old client devices may
   still write. The orphan rows on Firestore become read-noise that the
   `SyncListenerManager.kt:61` listener can ignore once it's de-wired.
5. Web: nothing changes. Web already uses only REST.

This direction is the cheap one (subtractive on Android, no-op on web).

---

## Improvement table — PR sequence

Ranked by precedence (later PRs depend on earlier).

| # | PR | Section | Cost | Risk |
|---|----|---------|------|------|
| 1 | E.1a — Web medication CRUD baseline (Firestore writes on `medications` + add/edit/archive screen) | E.1a | 2d | Med |
| 2 | E.1b — Per-med dose toggle UI on `MedicationScreen` | E.1b | 0.5d | Low |
| 3 | E.1f — Virtual-slot derivation on web (so empty-state no longer says "set up on Android") | E.1f | 0.5d | Low |
| 4 | E.1c — Refills UI (CRUD on `medication_refills` Firestore + screen) | E.1c | 1.5d | Low |
| 5 | E.1d — Log/history view (Firestore reads on `medication_doses` + `medication_tier_states`) | E.1d | 1d | Low |
| 6 | E.1e — Clinical report export (backend endpoint + web caller + PDF/markdown download) | E.1e | 1.5d | Med |
| 7 | E.2 — Web `medication_slot_defs` → `medication_slots` migration + schema-merge (D-E2) | E.2 | 1.5d | High |
| 8 | E.3 — Android tier-state deterministic doc-ids + one-time backfill (D-E3) | E.3 | 1.5d | High |
| 9 | E.4 — Remove Android Firestore writes for `daily_essential_slot_completions` (D-E4) | E.4 | 0.5d | Low |

**Total nominal:** ~10.5 engineer-days. Single-session capacity ≤ 3 PRs.

---

## Per-PR shape

### PR-1 (E.1a) `feat/web-medication-crud-baseline`
Files: `web/src/api/firestore/medications.ts` (add write helpers,
`createMedication`/`updateMedication`/`archiveMedication`/`unarchiveMedication`
mirroring Android `MedicationSyncMapper.medicationToMap` field set);
new `web/src/features/medication/MedicationEditorDialog.tsx`; wire from
`MedicationScreen.tsx`. ~600 LOC.

### PR-2 (E.1b) `feat/web-medication-dose-toggle`
Files: new `web/src/api/firestore/medicationDoses.ts` (CRUD on
`medication_doses`); per-med dose toggle inside the slot row in
`MedicationScreen.tsx`. ~150 LOC.

### PR-3 (E.1f) `feat/web-virtual-slot-derivation`
Files: new `web/src/features/medication/virtualSlots.ts` deriving virtual
slots from `medications.timesOfDay` / `specificTimes` when no materialized
backend slot exists; wire into `MedicationSlotList.tsx` empty-state path.
Replace the "set up on Android" copy. ~120 LOC.

### PR-4 (E.1c) `feat/web-medication-refills`
Files: new `web/src/api/firestore/medicationRefills.ts`; new
`web/src/features/medication/MedicationRefillScreen.tsx`; wire route. ~400 LOC.

### PR-5 (E.1d) `feat/web-medication-log-history`
Files: new `web/src/features/medication/MedicationHistoryScreen.tsx`
reading `medication_doses` + `medication_tier_states` for last 90 days.
~300 LOC.

### PR-6 (E.1e) `feat/web-medication-clinical-report`
Files: new `backend/app/api/v1/endpoints/medication_clinical_report.py`
(reuses domain summary shape; emits PDF via reportlab + markdown text);
new `web/src/api/medicationReport.ts`; new
`web/src/features/medication/ClinicalReportPanel.tsx`. ~250 web + ~150 backend.

### PR-7 (E.2) `refactor/medication-slots-collection-rename`
Web-only Firestore + schema-merge. **No Android Room migration.** Adds
`idealTime`/`driftMinutes`/`isActive` to web slot reads + writes; renames
collection from `medication_slot_defs` to `medication_slots`; one-time
data-migration script gated behind sign-in. Old collection untouched.
~250 LOC.

### PR-8 (E.3) `refactor/medication-tier-state-deterministic-doc-ids`
Android-only push-side change + one-time backfill helper. **No Room schema
change.** Adds `MedicationMigrationPreferences.isTierStateBackfilled` flag.
Backfill helper iterates `medicationTierStateDao.getAllOnce()` + rewrites
each row's Firestore doc to deterministic id, deletes old auto-id doc.
~200 LOC.

### PR-9 (E.4) `refactor/medication-slot-completions-backend-authoritative`
Android-only subtractive change. Removes Firestore push/pull arms from
`SyncService.kt` for `daily_essential_slot_completion`; updates
`SyncDispatchTables.kt`. Verifies BackendSyncService continues to populate
the Room table. ~60 LOC.

---

## Migration-hazard checklist (referenced for each PR with Android touch)

Per `docs/audits/2026-04-25_migration_json_silent_default.md`:

- **PR-8 (E.3) — no Room column changes.** Only the push helper changes its
  Firestore doc id. The cloud_id column already exists. Backfill writes the
  new deterministic doc and deletes the old auto-id doc, but
  `medication_tier_states.cloud_id` is rewritten to the new id transactionally.
  Add a guard: never run the backfill twice (preference flag). Never run
  before the user has a non-null `firebaseUid`.
- **PR-9 (E.4) — no Room changes.** Pure code-deletion of the Firestore
  push/pull arms. The DAO + table remain. Backend continues to fill the
  table.
- **PR-7 (E.2) — web-only, no Android touch.** Removes the highest-risk
  Android-side concern. Schema merge happens entirely on the JS side.

**Anti-hazard guardrails for all migration PRs:**
- Never `DROP COLUMN`. PR set above has zero column drops.
- Backfills run inside an `if (!isAlreadyBackfilled())` gate stored in a
  dedicated DataStore preference. Re-running is a no-op.
- Firestore writes during backfill use `setDoc(..., {merge: true})` so
  partial-failure retries are idempotent.

---

## Out-of-scope (acknowledged, not punted)

- **`medication_slot_overrides` + `medication_doses` per-med shape on web** —
  E.1a only ships the medication doc; richer dose linkage waits until PR-2
  (E.1b). Both arrive within this batch.
- **`medication_marks` table** — deprecated by MIGRATION_63_64; no web parity
  needed.
- **Built-in medication reconciliation on web** — `BuiltInMedicationReconciler`
  is Android-only and runs after the first cloud pull. Web's CRUD (PR-1)
  ships with no built-in seeds; built-ins land cross-platform once Android
  pushes them and web's listener (already wired) sees them. **No new PR
  needed** — the existing listener wiring carries it.
- **LWW timestamp guards on medication writes** — covered by parent
  audit A.2 (Batch 6).

---

## Phase 3 — Bundle summary

**Shipped this session:**
- PR #1344 — `docs/parity-batch-5-medication-audit`. Merged into `main`
  (squash). Lands this audit doc with D-E2 / D-E3 / D-E4 on record.

**Punted to Batch 5.1 (next session, in dependency order):**
- PR-1 (E.1a) `feat/web-medication-crud-baseline` — ~600 LOC, foundational.
- PR-2 (E.1b) `feat/web-medication-dose-toggle` — ~150 LOC, depends on PR-1.
- PR-3 (E.1f) `feat/web-virtual-slot-derivation` — ~120 LOC, depends on PR-1.
- PR-4 (E.1c) `feat/web-medication-refills` — ~400 LOC, depends on PR-1.
- PR-5 (E.1d) `feat/web-medication-log-history` — ~300 LOC, depends on PR-2.
- PR-6 (E.1e) `feat/web-medication-clinical-report` — ~250 web + ~150
  backend; needs a new `/api/v1/medications/clinical-report` endpoint.
- PR-7 (E.2) `refactor/medication-slots-collection-rename` — ~250 LOC,
  web-only schema-merge per D-E2. **No Android Room touch.**
- PR-8 (E.3) `refactor/medication-tier-state-deterministic-doc-ids` —
  ~200 LOC, Android-only push-helper + one-time backfill per D-E3.
  **No Room schema change.**
- PR-9 (E.4) `refactor/medication-slot-completions-backend-authoritative`
  — ~60 LOC, Android-only subtractive change per D-E4. Touches
  `SyncService.kt`, `SyncDispatchTables.kt`, `SyncListenerManager.kt`,
  `SyncPullOrchestrator.kt`.

**Rationale for punt-not-ship:** Full 9-PR fan-out is ~10.5 engineer-days
nominal; single-session capacity is ≤ 3 PRs. Shipping the audit alone
gets the architectural decisions on record and unblocks parallel
execution across follow-up sessions without re-litigating D-E2/D-E3/D-E4.

**Risk surface preserved by punting:** Zero Room migrations land before
the architectural decisions are reviewed. Zero `DROP COLUMN` operations
exist anywhere in the 9-PR plan — that safety survives the punt because
no implementation has shipped yet.

---

## Phase 4 — Claude Chat handoff block

```
Batch 5 Medication — Phase 1 audit shipped, Phase 2 follow-ups parked.

Merged this session:
- PR #1344 docs/parity-batch-5-medication-audit — architectural decisions
  D-E2 / D-E3 / D-E4 locked in. Lives at
  docs/audits/PARITY_BATCH_5_MEDICATION_AUDIT.md.

Decisions to honor in follow-up sessions (do NOT re-litigate):
- D-E2: Web migrates to `medication_slots` (NOT the parent audit's
  `medication_slot_defs` direction). Web-only schema-merge; Android
  Room untouched.
- D-E3: Tier-state Firestore doc id is deterministic
  `${medCloudId}__${dateIso}__${slotCloudId}`. Android-side push-helper
  change + one-time backfill. Zero Room migration.
- D-E4: Drop Android's legacy Firestore arms for
  daily_essential_slot_completions; BackendSyncService is authoritative.

Next-session targets (in dependency order — PR-1 is foundational):
1. PR-1 feat/web-medication-crud-baseline       ~600 LOC web         2d
2. PR-2 feat/web-medication-dose-toggle         ~150 LOC web (→PR-1) 0.5d
3. PR-3 feat/web-virtual-slot-derivation        ~120 LOC web (→PR-1) 0.5d
4. PR-4 feat/web-medication-refills             ~400 LOC web (→PR-1) 1.5d
5. PR-5 feat/web-medication-log-history         ~300 LOC web (→PR-2) 1d
6. PR-6 feat/web-medication-clinical-report     ~250 web+~150 backend 1.5d
7. PR-7 refactor/medication-slots-collection-rename
                                                ~250 LOC web         1.5d
8. PR-8 refactor/medication-tier-state-deterministic-doc-ids
                                                ~200 LOC Android,
                                                no Room migration    1.5d
9. PR-9 refactor/medication-slot-completions-backend-authoritative
                                                ~60 LOC Android,
                                                subtractive          0.5d

Migration-hazard reminders:
- Zero DROP COLUMN across the whole batch. PR-7/PR-8/PR-9 are all
  no-Room-migration designs.
- Backfills (PR-8) MUST be guarded by a one-shot preference flag and
  use setDoc(..., {merge: true}) so retries are idempotent.
- Web idb@^8.0.4 is broken on npm — node_modules symlink is the workaround.
- Repo does NOT have GitHub auto-merge — use `gh pr merge <num> --squash`.

To resume: read docs/audits/PARITY_BATCH_5_MEDICATION_AUDIT.md, then
start PR-1. PR-1 is foundational — every other E.1.x PR depends on
medications-write-path being live on web.
```

---

## Batch 5.x execution log (2026-05-13)

All 9 enumerated PRs shipped + squash-merged this session. Phase 3
bundle summary and Phase 4 handoff block updated below to reflect the
actual landed state.

| # | PR | Branch | Status | Scope deviation |
|---|----|--------|--------|-----------------|
| 1 | #1376 | `feat/web-medication-crud-baseline` | Shipped & merged | None |
| 2 | #1377 | `feat/web-medication-dose-toggle` | Shipped & merged | None |
| 3 | #1378 | `feat/web-virtual-slot-derivation` | Shipped & merged | None |
| 4 | #1379 | `feat/web-medication-refills` | Shipped & merged | None |
| 5 | #1380 | `feat/web-medication-log-history` | Shipped & merged | None |
| 6 | #1381 | `feat/web-medication-clinical-report` | Shipped & merged | **Backend endpoint deferred** — client-side markdown + plain-text generation only. PDF emission and backend reuse-of-Android-domain skipped. Tracked as 5.y follow-up. |
| 7 | #1382 | `refactor/medication-slots-collection-rename` | Shipped & merged | None — web-only schema-merge per D-E2. **No Android Room touch.** |
| 8 | #1384 | `refactor/medication-tier-state-deterministic-doc-ids` | Shipped & merged | None — Android-only push helper + backfill per D-E3. **No Room migration.** |
| 9 | #1385 | `refactor/medication-slot-completions-backend-authoritative` | Shipped & merged | None — subtractive per D-E4. |

**Notable implementation details**
- PR-7 backfill flag stored in `localStorage` (`prismtask.med_slots_backfill_v1.<uid>`) rather than a Firestore preference — keeps the migration per-device and zero-risk for SSR / private-browsing.
- PR-8 backfill flag added to existing `MedicationMigrationPreferences` (`tier_state_doc_id_backfill_done`) so the medication-prefs DataStore stays the single owner of medication-migration one-shot flags.
- PR-9 retained the pull-side `processRemoteDeletions` arm for `daily_essential_slot_completion` — defensive cleanup for any in-flight listener emits from older clients before the listener de-wires.

**Deferred follow-ups (Batch 5.y)**
- Backend `/api/v1/medications/clinical-report` endpoint with reportlab PDF emission. Client-side markdown covers the patient-portal-paste workflow today; the PDF path remains nice-to-have.
- Firestore `firestore.rules` update to allow read on the legacy `medication_slot_defs` collection during the 60-day dual-read window — only needed if existing rules tightened access. Spot-check before web v1.7.0 ships.

---

## Phase 4 — Claude Chat handoff block (refresh, 2026-05-13)

```
Batch 5 Medication — full 9-PR fan-out shipped + merged.

Closed this session (#1376 → #1385):
- PR-1 feat/web-medication-crud-baseline                     (#1376)
- PR-2 feat/web-medication-dose-toggle                       (#1377)
- PR-3 feat/web-virtual-slot-derivation                      (#1378)
- PR-4 feat/web-medication-refills                           (#1379)
- PR-5 feat/web-medication-log-history                       (#1380)
- PR-6 feat/web-medication-clinical-report                   (#1381)
- PR-7 refactor/medication-slots-collection-rename           (#1382)
- PR-8 refactor/medication-tier-state-deterministic-doc-ids  (#1384)
- PR-9 refactor/medication-slot-completions-backend-authoritative
                                                              (#1385)

All architectural decisions D-E2 / D-E3 / D-E4 honored — no Room
migrations landed, no DROP COLUMN, backfills are idempotent + flag-
gated.

Deferred to Batch 5.y:
- Backend /api/v1/medications/clinical-report endpoint + reportlab PDF
  emission (PR-6 shipped client-side only).
- firestore.rules cleanup if needed for the medication_slot_defs
  60-day dual-read window (PR-7).

Premise verification along the way:
- PR-3: MedicationSlotList.tsx:71-73 still carried the "Web does not
  derive virtual slots" copy from the audit, premise OK.
- PR-7: medication_slot_defs was still the active write target before
  the rename, premise OK.
- PR-8: medication_tier_states still used collection.document() auto-ids
  pre-PR-8, premise OK.
- PR-9: SyncService.kt:1359 + :1555 + :2011 (D-E4 sites) all still had
  Firestore-writing code, premise OK.

Web idb@^8.0.4 pin is still broken — symlink + manual fake-indexeddb
install was the workaround during this session.

No further parity Batch 5 work needed. Batch 6 (sync hardening) has
its own audit and is unchanged by this batch.
```
