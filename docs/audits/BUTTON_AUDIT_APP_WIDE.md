# App-Wide Button Audit

**Scope:** All interactive buttons across the Android app — screen buttons, dialog
buttons, bottom-sheet buttons, onboarding buttons — to verify every click handler
is properly wired and not silently a no-op.

**Date:** 2026-05-14  
**Branch:** `fix/restore-purchase-button-wiring`

---

## Phase 1 — Audit

### Sweep methodology

1. Grep `onClick = {}` across all `.kt` files under `ui/`.
2. Grep for `= {}` default lambdas on composable callback params.
3. Validate each: (a) intentional (disabled button, drag handle, display chip) vs.
   (b) load-bearing callback with no real implementation at the call site.
4. Cross-check NavGraph route registration for all `navController.navigate()` calls.
5. Check ViewModel method stubs for empty bodies.

---

### B.1 — "Restore Purchase" button — all ProUpgradePrompt / ProUpsellSheet surfaces (RED)

**Findings.** `UpgradePrompt` (line 113, `ProUpgradePrompt.kt`) renders a
`TextButton("Restore Purchase")` whose `onClick` is `onRestorePurchase`. The param
carries a default `= {}`, and **every current call site omits it**:

| Call site | Composable | `onRestorePurchase` passed? |
|---|---|---|
| `TodayScreen.kt:966` | `ProUpsellSheet` | ❌ no |
| `WeeklyPlannerScreen.kt:122` | `ProUpgradePrompt` | ❌ no |
| `DailyBriefingScreen.kt:126` | `ProUpgradePrompt` | ❌ no |
| `SchoolworkScreen.kt:142` | `ProUpgradePrompt` | ❌ no |

The implementation exists: `BillingManager.restorePurchases()` is a full
`suspend fun` (line 211) and `SettingsViewModel.restorePurchases()` (line 1157)
calls it and emits a snackbar. The `SubscriptionScreen` is the only surface that
properly wires this (line 59). All other paywall surfaces are silent.

**Risk.** HIGH — a Free user who already paid and reinstalled (or switched devices)
presses "Restore Purchase" and nothing happens. No error, no navigation, no
acknowledgement.

**Recommendation.** PROCEED — add `BillingManager` + `restorePurchases()` to the
four affected ViewModels (`TodayViewModel`, `WeeklyPlannerViewModel`,
`DailyBriefingViewModel`, `SchoolworkViewModel`) and wire the callback at each
call site. Follow the `SettingsViewModel` pattern: call
`billingManager.restorePurchases()` in a coroutine, emit a snackbar on success
or failure.

---

### B.2 — Intentional no-op `onClick = {}` instances (GREEN)

All ten remaining `onClick = {}` occurrences are deliberate. Summary:

| File | Line | Reason |
|---|---|---|
| `BetaCodeRedemptionScreen.kt` | 89 | Disabled loading button (`enabled = false`) |
| `AboutSection.kt` | 41 | `combinedClickable` — long-press carries the action |
| `LogPastLeisureSheet.kt` | 120 | `DropdownMenuItem(enabled = false)` — section header |
| `ProjectRoadmapScreen.kt` | 290 | `AssistChip` version-anchor badge (display only) |
| `ProjectRoadmapScreen.kt` | 410 | `AssistChip` risk-level badge (display only) |
| `ProjectRoadmapScreen.kt` | 440 | `AssistChip` anchor-type badge (display only) |
| `ProjectDetailScreen.kt` | 516 | Drag-handle `IconButton` (not meant to be tapped) |
| `TimelineScreen.kt` | 1034 | `AssistChip` block-type badge (display only) |
| `WeeklyPlannerScreen.kt` | 277 | `FilterChip` habit display chip (read-only) |
| `DailyBriefingScreen.kt` | 250 | `AssistChip` habit display chip (read-only) |

**Recommendation.** STOP — no work needed.

---

### B.3 — Default empty lambda callbacks on composable params (GREEN)

Many composables declare optional callbacks with `= {}` defaults. All load-bearing
cases are properly overridden at their call sites:

| Composable param | Call site | Properly wired? |
|---|---|---|
| `AiSection.onNavigateTo*` | `TodayAiHubSheet.kt:80` | ✅ all 9 wired |
| `WorkLifeBalanceSection.onViewReport` | `WellbeingScreen.kt:101` | ✅ |
| `BoundariesSection.onEdit` | `WellbeingScreen.kt:109` | ✅ |
| `SwipeableTaskItem.onReschedule/onDelete/…` | `TodayScreen.kt:638,672,833` | ✅ all 5 wired |
| `TodayBalanceSection.onClick` | `TodayScreen.kt:400` | ✅ |
| `TodayCognitiveLoadSection.onClick` | `TodayScreen.kt:406` | ✅ |
| `CompletedTaskItem.onLogAgain` | `TodayScreen.kt:884` | ✅ |
| `ProjectCard.onArchive/onReopen` | `ProjectListScreen.kt:289-290` | ✅ |

**Recommendation.** STOP — no work needed.

---

### B.4 — Navigation dead ends (GREEN)

All 173+ `navController.navigate(X)` calls resolve to registered composable
destinations in `NavGraph.kt` / `SettingsRoutes.kt` / feature route files.
No dead ends found.

**Recommendation.** STOP — no work needed.

---

### B.5 — ViewModel method stubs (GREEN)

All ViewModel methods triggered by button clicks have real implementations
(coroutine launches, repo calls, preference writes, state updates). No empty
bodies found.

**Recommendation.** STOP — no work needed.

---

### Ranked improvement table

| # | Item | Impact | Cost | Ratio |
|---|---|---|---|---|
| 1 | B.1 — Wire "Restore Purchase" in 4 screens | HIGH (broken billing flow) | Low (inject + 4 call sites) | HIGH |

### Anti-pattern list

- `onRestorePurchase: () -> Unit = {}` is the exact pattern the project memory
  flags as dangerous: default no-op on a load-bearing billing callback. The fix
  for B.1 should also remove the default from `ProUpgradePrompt` to force future
  callers to be explicit.

---

## Phase 2 — Implementation

### PR: fix/restore-purchase-button-wiring

Fix the four screens with broken "Restore Purchase" buttons by adding
`BillingManager` injection to each ViewModel and wiring `onRestorePurchase`
at each call site. Also harden the `ProUpgradePrompt` signature by removing
the `= {}` default from `onRestorePurchase` so future callers can't silently
omit it.

*(Phase 3 bundle summary appended after merge)*
