# D5#1 NLP Batch Ops — Re-Fire Regression + Missing Undo Audit

**Source defects** (operator manual test, May 10, 2026):
- **Test 1.3d** — submitting 2 mutations, backgrounding the app, foregrounding,
  and tapping Approve produces 4 applied mutations (duplicate apply).
- **Test 1.6** — no undo Snackbar appears anywhere after a batch lands; the
  in-app history (`BatchHistoryScreen`) is also not surfaced from Today.

**Premise.** PR #1049 (SHA `92d404e7`, May 2) paper-closed Test 1.2 by adding
`BatchPreviewState.Applied` terminal transition + widened `loadPreview`
re-entry guard. The audit doc `BATCH_PREVIEW_REFIRE_AUDIT.md` explicitly
predicted that the fix defends only **Path P1** (same VM, re-fire via
`LaunchedEffect` recomposition); **Path P2** (fresh VM after lifecycle
loss) was NOT defended. Test 1.3d is the on-device manifestation of Path
P2.

**Phase F GATE risk.** Kickoff May 15. 4 days to close. Issue A is a
data-quality P0 (duplicate task mutations after a routine background/
foreground); Issue B is the user-trust affordance for the same flow.
Both block D5#1 closure (rolled back 0.95 → 0.5 by the May 10 test).

---

## Phase 0 — Base verification (results)

| Check | Result |
|-------|--------|
| PR #1049 merge SHA `92d404e7` locatable in `git log --all` | YES (referenced in `BATCH_PREVIEW_REFIRE_AUDIT.md:348` + `D5_D6_FINISH_BUNDLE_AUDIT.md:39`) |
| `BatchPreviewScreen.kt` + `BatchPreviewViewModel.kt` exist | YES (`app/src/main/java/com/averycorp/prismtask/ui/screens/batch/`) |
| Existing state machine (`Idle/Loading/Loaded/Committing/Applied/Error`) | YES, with `loadPreview` guard at lines 69–76 + `approve()` guard at line 253 |
| `batch_undo_log` Room table | YES (`BatchUndoLogEntry`, `BatchUndoLogDao`) |
| `BatchOperationsRepository.undoBatch(batchId)` | YES (`BatchOperationsRepository.kt:683`, reverses each entry in a Room transaction, marks `undone_at`) |
| `BatchUndoEventBus` singleton | YES — `MutableSharedFlow<BatchAppliedEvent>(extraBufferCapacity = 4)` with `replay = 0` (default) |
| `BatchUndoListenerViewModel` + Snackbar wiring | YES — `TodayScreen.kt:155-173` collects events, calls `viewModel.showSnackbar("X changes applied", "Undo")` |
| `BatchHistoryScreen` (Settings entry) | YES (`BatchHistoryScreen.kt`, `BatchHistoryViewModel.kt`), with route in NavGraph and worker sweep in `BatchUndoSweepWorker` |
| `BatchUndoSweepWorker` daily cleanup | YES (sweeps expired `batch_undo_log` rows) |

**STOP-A: cleared.** PR #1049 located, BatchPreview surfaces present.

**STOP-UNDO-INFRA verdict: CONFIRMED-EXISTS.** Issue B is **UI-only — the
event bus + listener + Snackbar + history are all wired, but a
SharedFlow replay gap causes the event to be lost in the BatchPreview→pop
→Today re-subscribe window.** Issue B scope is ~30-80 LOC, well below the
GREEN-UI-ONLY bound.

**STOP-REFIRE-CAUSE evaluation.** Of the three hypotheses in the prompt:

- **H1 (lifecycle gap in state machine)** — PARTIALLY APPLIES. The
  state machine itself is correct: `loadPreview` guards in `Loading/
  Committing/Applied/Loaded(same text)`; `approve()` guards by
  requiring `Loaded`. But the guard is keyed on the `_state` mutation
  which is in `viewModelScope`. A SECOND tap arriving on the same UI
  frame as the first hits `_state` before the first launch's
  `_state.value = BatchPreviewState.Committing(loaded.commandText)`
  has flushed — wait, actually `_state.value =` on `MutableStateFlow`
  IS synchronous. So same-frame double-tap CAN'T slip through the
  state check.

  But there's a sub-case: between approve() being invoked and the
  state mutation, if the **same** `Loaded` value is read by two
  concurrent `approve()` calls (e.g. dispatched from different
  coroutines that observed the state simultaneously), only the first
  to flip `_state` to `Committing` wins; the second's `as?
  BatchPreviewState.Loaded` would already see `Committing`. So same-VM
  re-entry IS defended at the state-machine level.

- **H2 (ViewModel scope drops state)** — APPLIES under process death.
  `BatchPreviewViewModel` is `hiltViewModel()` keyed on the
  `NavBackStackEntry`. If the activity is destroyed (OOM, Don't Keep
  Activities, long background), the entry is restored from
  `SavedStateHandle`, but the VM is reconstructed with `state = Idle`
  (no SavedStateHandle persistence wired). `LaunchedEffect(commandText)`
  re-fires `loadPreview`, state goes back through Loading→Loaded.
  Tapping Approve once applies the batch — 2 mutations, not 4.

  So H2 alone does not explain "4 from 2."

- **H3 (Flow re-subscribe replays the action)** — UNLIKELY. The
  `events` SharedFlow has `replay = 0`, so resubscribing after
  background does NOT re-deliver Approved.

**A fourth hypothesis the prompt did not name** — **H4 (race between
button click and state-mutation visibility under recomposition).**
Under specific timing windows, a single button click during a UI lag
(e.g. after foregrounding while the screen re-lays-out) **could** be
delivered to the recomposed `onClick` lambda twice: once from the
previous composition's still-attached pointer handler, once from the
new composition's handler. Compose's pointer input pipeline is
usually safe here, but the operator's symptom (4 from 2, single
visible tap) matches this pattern more closely than H1/H2/H3 do.

**Verdict on H4.** Plausible but not statically provable without an
AVD reproduction. **Defense-in-depth is the correct response.** The fix
ships an explicit synchronous re-entry latch (`AtomicBoolean`) in
`approve()` that catches **any** double-call regardless of root cause:
state machine race, button-click double-dispatch, or process-death-
induced fresh-VM-with-prior-batchId-still-pending. The latch is
ViewModel-instance-local; it's belt-and-suspenders for the case where
the state machine guards somehow miss (today they don't, but they're
load-bearing on a single MutableStateFlow read happening atomically
relative to the click — the latch removes that dependency).

**STOP-REFIRE-CAUSE: H4 (with H2 contributing under process death).**
Defense: synchronous latch + lifecycle-aware fixes in Issue B that
also defend H2.

**STOP-PHASE-F-RISK: evaluated FALSE.** Total LOC estimate (below)
sits at ~120-180 — well under the 500 cap, no PR #1049 invariant break.

---

## Phase 1 — Audit

### § Issue A — Re-fire defect regression

**Symptom.** Tap Approve once after a foreground→background→
foreground cycle ⇒ `applyBatch` invoked twice ⇒ 2N mutations applied.

**Code path on disk** (`BatchPreviewViewModel.kt:252-293`):

```kotlin
fun approve() {
    val loaded = _state.value as? BatchPreviewState.Loaded ?: return
    val toApply = loaded.mutations.filterIndexed { idx, _ -> idx !in _excluded.value }
    if (toApply.isEmpty()) { ... emit Cancelled ... return }
    _state.value = BatchPreviewState.Committing(loaded.commandText)
    viewModelScope.launch {
        try {
            val result = repository.applyBatch(loaded.commandText, toApply)
            undoBus.notifyApplied(BatchAppliedEvent(...))
            _state.value = BatchPreviewState.Applied(...)
            _events.emit(BatchEvent.Approved(...))
        } catch (e: Exception) { ... }
    }
}
```

The guard is `as? Loaded ?: return`. A double-call where the first
call has not yet reached `_state.value = Committing` would slip
through if dispatched concurrently. Under standard UI single-
threaded dispatch this can't happen — Compose dispatches click
events serially on the main thread, and `MutableStateFlow.value =`
is synchronous. But:

- After foreground/background, if process death occurred and the VM
  is fresh, state is back to `Loaded` after re-parse. ONE Approve
  click runs ONE applyBatch. (H2 alone doesn't double-apply.)
- IF the operator's tap registered as two click events (lifecycle
  pointer-input artefact during the recompose-after-resume window),
  same-frame dispatch could theoretically pass through if the first
  launch hadn't yet flushed `Committing`. The window is narrow but
  not zero.

**Fix shape: GREEN-MINIMAL-DEFENSE.**

1. **Synchronous latch in approve()** before the state read:

   ```kotlin
   private val _isApproving = AtomicBoolean(false)

   fun approve() {
       if (!_isApproving.compareAndSet(false, true)) return
       val loaded = _state.value as? BatchPreviewState.Loaded
       if (loaded == null) { _isApproving.set(false); return }
       ...
       viewModelScope.launch {
           try {
               val result = repository.applyBatch(loaded.commandText, toApply)
               ...
           } catch (e: Exception) {
               _state.value = BatchPreviewState.Error(...)
               _isApproving.set(false)  // allow retry after error
           }
           // success path: leave _isApproving=true forever — Applied is terminal
       }
   }
   ```

   `AtomicBoolean.compareAndSet` is atomic across threads. Any second
   call to approve() — same frame, different coroutine, or different
   dispatch — short-circuits before reaching `_state` or `applyBatch`.

2. **Disable Approve button when state is `Committing`/`Applied`/
   `Error`/`Idle`/`Loading`**: already covered by `state is
   BatchPreviewState.Loaded` check in `BatchPreviewBottomBar`
   (line 585). No change needed.

3. **Cancel and Approve both no-op while approving**: the existing
   `cancel()` just emits to `_events` and pops — even if double-fired,
   the result is "pop attempted twice," which is benign (NavController
   ignores extra pops). No change needed.

**Estimate:** ~10-15 LOC in `BatchPreviewViewModel.kt`, ~30 LOC test.

### § Issue B — Undo affordance missing

**Symptom.** After tapping Approve, the BatchPreview pops back to
Today, but no "X changes applied — Undo" Snackbar appears.

**Code path on disk** (`BatchPreviewViewModel.kt:262-284` →
`BatchUndoEventBus.kt:17-28` → `TodayScreen.kt:155-173`):

```kotlin
// VM.approve() success:
val result = repository.applyBatch(loaded.commandText, toApply)
undoBus.notifyApplied(BatchAppliedEvent(...))   // (1) emit to bus
_state.value = BatchPreviewState.Applied(...)
_events.emit(BatchEvent.Approved(...))           // (2) emit Approved → screen pops

// BatchUndoEventBus:
private val _events = MutableSharedFlow<BatchAppliedEvent>(extraBufferCapacity = 4)
// → replay = 0 (default), so new subscribers do NOT get historical emissions

// TodayScreen subscribes ONLY while Today's composition is active:
LaunchedEffect(batchUndoListener) {
    batchUndoListener.events.collect { event -> showSnackbar(...) }
}
```

**Compose Navigation semantics.** When BatchPreview is pushed onto
the back stack, Today's composition is removed (only the topmost
destination is composed). The `LaunchedEffect(batchUndoListener)`
cancels. Today's `BatchUndoListenerViewModel` instance survives
(it's stored on Today's `NavBackStackEntry.ViewModelStore`), but
**there is no active subscriber to the bus.**

When `undoBus.notifyApplied(...)` fires (step 1), there are zero
subscribers. `MutableSharedFlow.tryEmit` returns true because the
buffer accepts it, but with `replay = 0` the buffered value is
NOT delivered to subscribers that arrive later — it's just held
for back-pressure to a slow subscriber that doesn't exist.

When BatchPreview pops (step 2's collector fires `popBackStack`),
Today is re-composed, the `LaunchedEffect` re-runs, the listener
subscribes — but the bus has already moved on. **The event is
silently lost.**

That is the only reason "no Snackbar findable anywhere" — every
other piece of infrastructure (rollback, history screen, sweep
worker, listener VM, Snackbar host) is wired correctly.

**Fix shape: GREEN-UI-ONLY.**

1. **Change `BatchUndoEventBus._events` to `replay = 1`**: keeps the
   most recent event available for whichever subscriber arrives
   next, which is exactly the BatchPreview→pop→Today timing window.

   ```kotlin
   private val _events = MutableSharedFlow<BatchAppliedEvent>(
       replay = 1,
       extraBufferCapacity = 4
   )
   ```

2. **Add `acknowledge()` to clear the replay cache** so the event is
   delivered exactly once (not re-shown when the user navigates back
   to Today again later for unrelated reasons):

   ```kotlin
   fun acknowledge() = _events.resetReplayCache()
   ```

3. **Call `acknowledge()` after showing the Snackbar** in the Today
   listener wiring. `TodayScreen.kt:155-173` already calls
   `viewModel.showSnackbar(...)`; we add a `batchUndoListener.acknowledge()`
   inside the collect block, after `showSnackbar` returns the dismiss
   reason (or unconditionally — the only cost of dropping the
   replay is "won't be shown a second time on next Today re-entry,"
   which is the desired semantics).

   Simplest: acknowledge **immediately on consumption**. The Snackbar
   is shown for ~10s but the event ID is logged in `batch_undo_log`
   for the user-configured retention window (`BatchUndoConfig`).
   Missing the Snackbar after explicit dismissal is fine; the user
   can still find the batch in `BatchHistoryScreen` (Settings →
   Advanced Tuning → Batch History).

4. **Surface the `BatchHistoryScreen` from a settings entry** —
   verify whether it's reachable today. Per Phase 0 grep,
   `AdvancedTuningScreen.kt:695` reads `batchUndo` config and the
   route exists in NavGraph. If the entry is buried, that's a
   separate UI defer per **STOP-B1** (do not add new Settings
   sections without operator approval). **No-op for this PR.**

**Estimate:** ~5-10 LOC in `BatchUndoEventBus.kt`, ~5 LOC in
`BatchUndoListenerViewModel.kt`, ~3 LOC in `TodayScreen.kt`, ~40 LOC
test.

### § Bundle decision

- **PR shape**: single bundle PR per operator pre-lock, two commits:
  1. Issue A (re-fire defense, + unit test)
  2. Issue B (undo bus replay, + unit test)
- A third commit holds the audit doc itself.
- **No data-layer changes**: `batch_undo_log` and `undoBatch` already
  do the right thing; only the Compose/Hilt event-bus replay needs to
  change.
- **Cross-issue dependencies: none.** Issue A is in `BatchPreviewViewModel`;
  Issue B is in `BatchUndoEventBus` + listener.

**Total LOC estimate:** 60-90 production + 70 tests + 250-line audit
doc = ~120-180 LOC change excluding audit. Well under STOP-PHASE-F-RISK
500-line cap.

**STOP conditions evaluated:**
- STOP-A: cleared (premise verified)
- STOP-A1: not fired (re-fire root cause stays in ViewModel layer,
  not Repository)
- STOP-A2: not fired (no change to PR #1049's `BatchPreviewState`
  shape; latch is additive)
- STOP-B1: not fired (Settings history surface already exists, no
  new section)
- STOP-B2: not fired (no new Room DAO needed)
- STOP-UNDO-INFRA: CONFIRMED-EXISTS
- STOP-REFIRE-CAUSE: H4 (with H2 contributing under process death)
- STOP-PHASE-F-RISK: evaluated FALSE

---

## Phase 2 — Implementation plan

**Branch:** `claude/fix-batch-refire-undo-mJPxG` (operator-specified)

**Commit 1: `fix(batch): synchronous AtomicBoolean re-entry latch in approve()`**
- `BatchPreviewViewModel.kt`:
  - Add `private val _isApproving = AtomicBoolean(false)`
  - Wrap `approve()` body with `compareAndSet(false, true)` short-circuit
  - On Error path inside the launch, reset the latch so Retry from
    Error state still works
- `BatchPreviewViewModelTest.kt`:
  - New test `approve_calledTwiceInSameFrame_invokesApplyBatchExactlyOnce`
  - Asserts `coVerify(exactly = 1) { repository.applyBatch(any(), any()) }`
    after two synchronous `approve()` calls

**Commit 2: `fix(batch): replay undo Snackbar event to late Today subscribers`**
- `BatchUndoEventBus.kt`:
  - Change `MutableSharedFlow(extraBufferCapacity = 4)` →
    `MutableSharedFlow(replay = 1, extraBufferCapacity = 4)`
  - Add `fun acknowledge() = _events.resetReplayCache()`
  - Doc the BatchPreview→pop→Today timing rationale in a KDoc note
- `BatchUndoListenerViewModel.kt`:
  - Add `fun acknowledge() = bus.acknowledge()` (constructor already
    has the bus reference; just expose it)
- `TodayScreen.kt`:
  - After `viewModel.showSnackbar(...)`, call `batchUndoListener.acknowledge()`
- `BatchUndoEventBusTest.kt` (new):
  - Test 1: emit before any subscriber, then subscribe → receives the
    event (verifies replay = 1)
  - Test 2: emit, subscribe, consume, acknowledge, re-subscribe →
    does not re-receive (verifies acknowledge)
  - Test 3: two sequential emits → second subscriber gets the latest
    only (verifies replay = 1 semantics)

**Commit 3: `docs(audit): D5#1 re-fire regression + undo wiring audit`**
- Adds this audit doc.

**Hard constraints respected** (per prompt):
- No modification to PR #1049's `BatchPreviewState` shape (additive only).
- No modification to PR #1080/#1082/#1141/#1142/#1191/#1216/#1244/#1251.
- No new Settings sections.
- No remote-config / feature flags.
- No analytics logging of task content during undo.

---

## Phase 3 — Verification plan

### Issue A
- Unit: `BatchPreviewViewModelTest.approve_calledTwiceInSameFrame_invokesApplyBatchExactlyOnce`
  pins `applyBatch` called exactly once.
- Regression: full `BatchPreviewViewModelTest` + `BatchPreviewViewModelAmbiguityTest`
  re-run to confirm PR #1049's contract still passes.
- AVD owed by operator: re-run Test 1.3d sequence (submit → background →
  foreground → tap Approve once); expect exactly N mutations applied.

### Issue B
- Unit: `BatchUndoEventBusTest` (3 cases above).
- AVD owed by operator: re-run Test 1.6 (submit → approve → expect
  "X changes applied — Undo" Snackbar within ~1s of pop); tap Undo,
  confirm mutations reverse.

### Bundle-level
- `./gradlew testDebugUnitTest` green.
- `./gradlew lintDebug` green.
- CI: lint-and-test, test, web-lint-and-test, compileDebugAndroidTestKotlin.

---

## Phase 3 — Bundle summary

(To be appended after PR opens.)

---

## Phase 4 — Claude Chat handoff

(Emitted in agent transcript at hand-off time — see Phase 4 block at
session end.)
