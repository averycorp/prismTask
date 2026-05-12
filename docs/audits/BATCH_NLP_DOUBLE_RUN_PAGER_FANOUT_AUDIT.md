# Batch NLP Double-Run — Pager Fan-Out Root Cause Audit

**User claim** (May 12, 2026): *"Every time an NLP batch is done, it shows
the preview, then it is accepted, then it does it a second time. I want
this fixed permanently."*

**Why a fresh audit.** Prior audits closed three layers of defense:
- PR #1049 — terminal `Applied` state + widened `loadPreview` re-entry guard
- PR #1068 — `_isSubmitting` synchronous guard in `QuickAddViewModel.onSubmit`
- PR #1265 — `_isApproving` `AtomicBoolean` in `BatchPreviewViewModel.approve`

`BATCH_NLP_DOUBLE_RUN_REVERIFICATION_AUDIT.md` (May 12) re-verified all
three layers were intact at HEAD and returned **STOP-no-work-needed**.
The user re-filed the symptom against the same HEAD anyway — meaning
the prior audits missed a path. This audit re-walks the same flow with
fresh eyes and finds it.

---

## Root cause — pager fan-out from shared VM

`MainTabs` (`NavGraph.kt:206`, `:633`) wraps the bottom-nav screens in a
`HorizontalPager(beyondViewportPageCount = 1, …)`. The pager keeps the
**adjacent** page composed off-screen so swipes are seamless.

`Today` (idx 0) and `TaskList` (idx 1) are adjacent in
`ALL_BOTTOM_NAV_ITEMS` (`NavGraph.kt:340-347`), so:

- When `Today` is visible, `TaskList` is **also composed** (off-screen).
- When `TaskList` is visible, `Today` is **also composed** (off-screen).

Both screens host a `QuickAddBar`:

- `TodayScreen.kt:251` → `FloatingQuickAddBar` → internally
  `QuickAddBar(viewModel = hiltViewModel())`
- `TaskListScreen.kt:743` → `QuickAddBar(viewModel = hiltViewModel())`

Both `hiltViewModel<QuickAddViewModel>()` calls resolve against the same
`LocalViewModelStoreOwner` — the `MainTabs` `NavBackStackEntry` — because
the pager does **not** introduce a new `ViewModelStoreOwner`. Without a
distinct key, both `hiltViewModel<QuickAddViewModel>()` calls return the
**same VM instance**.

Inside `QuickAddBar.kt:107-116` each bar registers its own
`LaunchedEffect(viewModel)` collector against `viewModel.batchIntents`
and `viewModel.multiCreateIntents`. With two bars sharing one VM, **two
collectors** sit on the same `MutableSharedFlow`.

When the user submits a batch command on the visible bar:

1. The shared VM emits **once** on `_batchIntents`.
2. `MutableSharedFlow` fans the single emit out to **both** collectors.
3. Each collector invokes its host's `onBatchCommand(commandText)` →
   `navController.navigate(BatchPreview.createRoute(commandText))`.
4. **Two `BatchPreview` screens stack** on the back stack. Each runs a
   fresh `BatchPreviewViewModel` with a fresh Haiku parse — and because
   Haiku is non-deterministic, the two parses can return slightly
   different mutation plans.
5. User sees the first preview, taps Approve, it commits, screen pops.
6. The second preview is now on top — same command, possibly different
   mutations. User reports: *"it shows the preview, then it is
   accepted, then it does it a second time."*

The prior audits missed this because
`BATCH_NLP_DOUBLE_RUN_REVERIFICATION_AUDIT.md`'s R3 table assumed
"TaskList route has its own `NavBackStackEntry`; not co-mounted with
TodayScreen." That assumption is false at HEAD: `TaskList` is **not**
registered as a top-level `composable(route = …)`; it is a `when {}`
branch inside the `MainTabs` `HorizontalPager`, sharing the `MainTabs`
entry with `Today`.

The keyed VM in `PlanForTodaySheet.kt:230`
(`hiltViewModel(key = "plan_sheet_quickadd")`) is the precedent — the
sheet's author already hit this gotcha and worked around it. The bar
sites under the pager never got the same treatment.

---

## Fix

Two-layer fix shipped together so a future regression on either layer
remains defended:

### Layer 1 — distinct VM scope per composition site (primary)

- `TodayQuickAddBar.kt:FloatingQuickAddBar` resolves
  `hiltViewModel(key = "today_floating_quickadd")` once and threads the
  same instance into each themed branch's `QuickAddBar` call.
- `TaskListScreen.kt` passes `viewModel = hiltViewModel(key =
  "tasklist_quickadd")` to its `QuickAddBar` call.

Each bar now owns its own `QuickAddViewModel` instance — independent
`inputText`, `isSubmitting`, `batchIntents`, `multiCreateIntents`,
`templateDisambiguation`, and `voiceMessages` state. A submit on the
visible bar only emits into that bar's VM; only that bar's collector
sees the emit; the host navigates exactly once.

### Layer 2 — Channel-backed intent flows (defense-in-depth)

- `QuickAddViewModel._batchIntents` and `_multiCreateIntents` change
  from `MutableSharedFlow<String>(extraBufferCapacity = 1)` to
  `Channel<String>(capacity = Channel.BUFFERED)`, exposed via
  `receiveAsFlow()`.
- `kotlinx.coroutines.channels.Channel.receiveAsFlow()` is multi-
  collector safe but delivers each emission to **exactly one** collector
  (atomic dequeue). If a future composition site regresses on Layer 1
  and two bars accidentally share a VM, the single send still results
  in a single `onBatchCommand` — at most one navigation.

The `_voiceMessages` flow is left as `MutableSharedFlow` because voice
toasts/snackbars are non-navigational and fan-out is desirable: if two
hosts ever ended up rendering, both should show the same confirmation
message.

The change is source-compatible for collectors: `Flow.collect { }`
works identically across `SharedFlow` and `receiveAsFlow()`.

---

## Tests

`QuickAddViewModelBatchSubmitGuardTest` gains two regression cases that
exercise Layer 2 directly:

- `onSubmit_batch_twoCollectors_emitDeliveredToExactlyOne` — two
  concurrent collectors on one VM; one `onSubmit` call; assert the
  total received count across both collectors is **1**, not 2.
- `onSubmit_multiCreate_twoCollectors_emitDeliveredToExactlyOne` — same
  shape for the multi-create path.

Pre-Channel (with `MutableSharedFlow`), both collectors would receive
the emit and the assertion would fail with total = 2.

The three pre-existing tests
(`onSubmit_batch_doubleTap_emitsBatchIntentOnce`,
`onSubmit_multiCreate_doubleTap_emitsMultiCreateIntentOnce`,
`onSubmit_batch_secondSubmitAfterFirstCompletes_isAllowed`) continue to
pass: they each subscribe a **single** collector via Turbine's
`.test {}` block, which is the supported (and only correct) collector
count under Layer 1.

---

## Verdict

The fix is GREEN-MINIMAL. No data-layer changes, no migration. ~25 LOC
production + ~50 LOC test + this audit doc, well under the 500-line
audit cap.

Layer 1 alone closes the symptom. Layer 2 is belt-and-suspenders for
the "future composition site forgets a key" regression class — the
same class that bit the audit chain in the first place.
