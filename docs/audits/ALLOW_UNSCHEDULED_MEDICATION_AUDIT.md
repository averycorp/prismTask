# Allow Unscheduled (As-Needed / PRN) Medication Audit

**Scope:** Operator request — _"It should be allowed to make a medication
that doesn't have a slot, for as needed."_ Today the
`MedicationEditorDialog` Save button is gated on
`name.isNotBlank() && (selections.isNotEmpty() || activeSlots.isEmpty())`
([app/src/main/java/com/averycorp/prismtask/ui/screens/medication/components/MedicationEditorDialog.kt:263](../../app/src/main/java/com/averycorp/prismtask/ui/screens/medication/components/MedicationEditorDialog.kt))
— once any slot exists, the user must pick one. Goal: lift that gate so a
PRN ("pro re nata", as-needed) med can be saved even when scheduled
slots are configured.

**Optimization target:** Operator workflow correctness — the existing
gate forces a workaround (delete every slot, save the med, re-create
slots) that no real user will discover. The "Unscheduled Medications"
section already exists on the Medication screen for exactly this case.

**Suspected failure modes / blast radius:** None new. The downstream
projection, sync, scheduler, and widget paths all already handle
slot-less meds (see § Premise verification). The gate was added in
PR #1141 to fix a different bug — a duplicate-name `SQLiteConstraintException`
crash — that is now fully covered by the ViewModel-layer
`getByNameOnce` pre-flight + `try/catch`. The gate is dead weight wrt
the original crash and actively harms the as-needed workflow.

## Premise verification (load-bearing)

The premise is _"the codebase has the wiring for slot-less meds; only
the dialog blocks creating them."_ Verified:

- **Dialog gate** — `MedicationEditorDialog.kt:263, 276`. The only blocker.
  ```kotlin
  val hasSlotIfRequired = selections.isNotEmpty() || activeSlots.isEmpty()
  …
  enabled = name.isNotBlank() && hasSlotIfRequired
  ```
- **ViewModel insert path** — `MedicationViewModel.kt:362-435`. Accepts
  `slotSelections: List<MedicationSlotSelection>` and `replaceLinksForMedication`
  copes with an empty list (the `slotIds.isNotEmpty()` guard in
  `MedicationSlotRepository.kt:106` is for INSERT batching only — the
  delete-existing-links pass runs unconditionally).
- **Unscheduled rendering** — `MedicationViewModel.kt:228-245`
  (`unslottedMedicationsState` filters `linkedSlotIds.isNotEmpty()` →
  excludes slotted meds, includes the rest); `MedicationScreen.kt:223-247`
  renders `UnslottedMedicationCard` under an "Unscheduled" header with a
  "Record Taken" button.
- **Per-tap PRN dose-log** — `MedicationViewModel.kt:521-534`
  (`recordUnslottedDose` writes `slotKey = "anytime"`, no toggle
  semantics, surfaces "Last taken at HH:mm").
- **Reminder schedulers gracefully no-op** — `MedicationReminderScheduler.kt:76`
  early-returns when there are linked slots (so the slot-less branch
  reaches the interval-mode anchoring path); `MedicationClockRescheduler.kt:141-145`
  iterates linked slot IDs only; `NotificationProjector.kt:244` checks
  `getSlotIdsForMedicationOnce(med.id).isNotEmpty()`.
- **Sync** — `SyncService.kt:1121, 1364, 1564` embed an
  `slotCloudIds: List<String>` per medication; an empty list round-trips
  cleanly through `MedicationSyncMapper`.
- **Widget** — `WidgetDataProvider.kt:636` builds a `med.id → slotIds`
  map; an empty list is a valid value (the widget filters slot-less
  meds out of the per-slot view, which is the intended behavior).

The premise holds. **Implementation is gate-removal + advisory-copy
update + test flip — no new wiring needed.**

## Item-by-item

### 1. Dialog Save-button gate (RED, PROCEED)

`MedicationEditorDialog.kt:263, 276`.

The gate originated in PR #1141 (audit:
`D_MEDICATION_ADD_CRASH_AUDIT.md` § 3) as one of three independent
fixes for a duplicate-name `SQLiteConstraintException` crash. The other
two fixes — `try/catch` + `errorMessages` SharedFlow in
`addMedication` (`MedicationViewModel.kt:373-434`), and the
`getByNameOnce` pre-flight (`:375-407`) — are the actual crash
mitigations. The dialog gate was a UX add-on ("a slot-less med is
invisible on Today") that was already false at PR-#1141 time, because
the "Unscheduled" section had already shipped.

> Drop the `hasSlotIfRequired` clause. Save remains gated on
> `name.isNotBlank()`. The duplicate-name and exception-handling
> coverage stays untouched.

LOC: ~3 changed.

### 2. Advisory copy (YELLOW, PROCEED)

`MedicationEditorDialog.kt:224-230`.

> "Pick at least one slot so the medication appears on the Today screen."

This is wrong-in-spirit once #1 lands: a slot-less med is _legitimately_
intended for the Unscheduled section. Replace with neutral
copy that explains the as-needed flow.

> Suggested rewrite: "No slot picked — this medication will appear in
> the Unscheduled section as an as-needed dose."

LOC: ~3 changed.

### 3. Comment-block doc-rot (YELLOW, PROCEED)

`MedicationEditorDialog.kt:256-262`.

The comment block defends the gate by referencing
`D_MEDICATION_ADD_CRASH_AUDIT.md` and the operator's "no slot
selected" repro. After #1 lands, the comment is a tombstone for code
that no longer exists.

> Replace the comment with a one-liner pointing at this audit, or
> delete it (per CLAUDE.md "Default to writing no comments"). The
> ViewModel-layer crash-protection comment block at
> `MedicationViewModel.kt:341-360` already documents the duplicate-name
> guard rationale and stays correct.

LOC: ~7 deleted.

### 4. Compose regression test flip (YELLOW, PROCEED)

`app/src/androidTest/java/com/averycorp/prismtask/ui/screens/medication/components/MedicationEditorDialogTest.kt:67-85, 30`.

`saveDisabled_whenNamePresentButSelectionsEmpty_andActiveSlotsPresent`
explicitly pins the gate. After #1 the assertion contradicts the
intended behavior.

> Rename to `saveEnabled_whenNamePresentButSelectionsEmpty_andActiveSlotsPresent_unscheduled`
> and flip `assertIsNotEnabled()` → `assertIsEnabled()`. Update the
> docstring header (`PR #1141` → cite this audit, with a
> short note that the gate was lifted to support PRN/as-needed meds).
> The other four cases stay as-is — name-blank + bootstrap path are
> orthogonal.

LOC: ~10 changed (one assertion flip, one rename, doc-comment refresh).

### 5. ViewModel insert path (GREEN, STOP-no-work-needed)

`MedicationViewModel.kt:362-435`.

Already accepts `slotSelections: List<MedicationSlotSelection>` of any
length. `replaceLinksForMedication(id, emptyList())` is a tested
no-link path
([MedicationViewModelAddMedicationTest.kt](../../app/src/test/java/com/averycorp/prismtask/ui/screens/medication/MedicationViewModelAddMedicationTest.kt)
`addMedication_emptySelections_persistsWithEmptyLinks`). The duplicate-name
guard, the `SharedFlow<String>` error surface, and the outer `try/catch`
all stay — those are the actual crash-protection layer.

No work needed.

### 6. Sync, schedulers, projector, widget (GREEN, STOP-no-work-needed)

All read paths already handle empty slot links — see § Premise
verification. No work needed.

### 7. Today screen impact (GREEN, STOP-no-work-needed)

The Today screen does not directly render the per-slot medication
cards; `TodayViewModel.kt:585-655` only consumes `hasMedications` (a
boolean for the "you have …" summary) via
`MedicationRefillRepository.observeAll().map { it.isNotEmpty() }`. A
slot-less med flips that flag the same way a slotted med does. No
divergence.

No work needed.

### 8. Built-in / seed content (DEFERRED)

`AutomationStarterLibrary.kt`, `BuiltInHabitVersionRegistry.kt`,
`SelfCareRoutine.kt` reference medication-related automations and
self-care routines. None of them auto-create medications, so this
audit's scope (lifting the dialog gate) does not require seed-content
changes. If a future iteration wants a "tap to add an as-needed med"
quick-action onboarding card, it can land separately.

### 9. Sibling: `MedicationRefillScreen` add-medication flow (DEFERRED — out of scope)

`MedicationRefillScreen` and `MedicationRefillViewModel` carry their
own add-medication path, anchored to the refill calculator rather than
slots. The refill flow has no slot gate and is unaffected by this
audit. Mentioning here so a future sweep doesn't double-count.

## Improvement table (ranked by wall-clock-savings ÷ implementation-cost)

| # | Improvement | Files touched | LOC | Verdict | Priority |
|---|---|---|---|---|---|
| 1 | Drop dialog Save gate | `MedicationEditorDialog.kt` | ~3 | RED → PROCEED | **P0** |
| 2 | Update advisory copy | `MedicationEditorDialog.kt` | ~3 | YELLOW → PROCEED | **P0** |
| 4 | Flip regression test assertion + rename | `MedicationEditorDialogTest.kt` | ~10 | YELLOW → PROCEED | **P0** |
| 3 | Strip dead comment block | `MedicationEditorDialog.kt` | ~7 del | YELLOW → PROCEED | **P0** |
| 5–7 | ViewModel / sync / schedulers / projector / widget / Today | — | 0 | GREEN | STOP-no-work-needed |
| 8 | Seed content updates | — | — | DEFERRED | post-audit |
| 9 | Refill-screen add path | — | — | DEFERRED (out of scope) | — |

**Aggregate:** ~23 LOC delta in two files; one PR (single coherent
scope = "allow PRN meds"). No schema migration, no new tests, no DI
changes.

## Anti-pattern callouts

- **Tombstone-comments referencing closed-out audits.** The comment
  block at `MedicationEditorDialog.kt:256-262` documented PR #1141's
  fix rationale. Once the code it defends is gone, the comment misleads
  future readers. CLAUDE.md "Default to writing no comments" already
  guards against this in new code; old comments rot the same way.
- **Two-layer gates for one bug class.** PR #1141 stacked three
  independent fixes (try/catch, dup-name guard, dialog gate) for one
  crash. The first two are robust crash protection; the third was a
  speculative UX shim that turned into a workflow bug. Future
  multi-layer fixes deserve a "is each layer load-bearing?" check.
- **Test docstrings that pin a transient policy.** The `MedicationEditorDialogTest`
  docstring quotes the exact gate expression. Rewriting policy-quoting
  test docstrings is fine; just notice this pattern shows up whenever a
  test doc names a specific fix-PR — that's the rot signal.

## STOP-conditions evaluated

- **STOP-A1** (reproduction failed): not fired. Premise verified
  structurally; the gate is right there at `MedicationEditorDialog.kt:263`
  and a unit test (`saveDisabled_whenNamePresentButSelectionsEmpty…`)
  exercises it.
- **STOP-A2** (different layer): not fired. Single-layer change.
- **STOP-B** (5+ null-safety gaps): not fired. Zero null-safety gaps;
  this is policy lift, not crash hardening.
- **STOP-D** (sibling read-path crashes): not fired. Verified all
  downstream consumers handle empty slot links.
- **STOP-E** (schema change required): not fired. No DB or sync
  schema change.
- **STOP-F** (LOC > 1500): not fired. ~23 LOC.

## Phase 2 plan

Single PR (single coherent scope):

- Branch: `claude/allow-unscheduled-medication-iiVBK` (already current).
- One commit, no migration, no test additions (only the existing
  Compose test gets its assertion flipped).
- Auto-merge squash via `gh pr merge --auto --squash`.
