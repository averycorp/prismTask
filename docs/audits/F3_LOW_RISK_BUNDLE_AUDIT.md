# F3 Low-Risk Bundle Audit

**Scope.** Single bundle PR covering 3 deferred follow-ons from PR #1210
(onboarding holistic redesign, merged at commit `4eebcd7`). All three flagged
risk: low in F3.

**Items.**
1. Layer B coachmark anchors — wire the 9 of 13 anchor targets that are
   currently silently grace-period-skipped.
2. Resume-tour chip on Today screen — UI consumer for the
   `CoachmarkController.resume()` API exposed by PR #1210.
3. Per-feature AI opt-ins — split a portion of the master `aiFeaturesEnabled`
   toggle into per-feature client-side prefs.

**Phase 0 outcome.** PASS — `4eebcd7` (PR #1210) is in `git log`, working
tree clean on `claude/f3-low-risk-bundle-WWOVd`. The premise infrastructure
is on disk:
- `app/src/main/java/com/averycorp/prismtask/ui/coachmark/`
  (CoachmarkController, CoachmarkAnchor, CoachmarkHost, CoachmarkOverlay,
  CoachmarkTourContent).
- `CoachmarkController.resume()` at line 89 of CoachmarkController.kt.

No STOP-A. Proceeding.

---

## § Bundle-decision

### PR shape

Single bundle PR per operator pre-lock. No STOP fired that argues for split.
Branch: `claude/f3-low-risk-bundle-WWOVd`.

### Implementation order (locked from prompt recommendation)

1. **Item 2 — Resume chip** (smallest, fewest cross-cutting concerns;
   landing it first means Item 1's anchor-wiring work can be smoke-tested
   end-to-end via the chip without going through the auto-tryStart path).
2. **Item 1 — Layer B anchors** (mechanical, isolated per-anchor wiring).
3. **Item 3 — Per-feature AI opt-ins** (largest LOC, most call sites).

Each item is a separate commit within the bundle PR for reviewable diffs.

### Cross-item dependencies

None. All three items are independent:
- Item 2 reads `controller.resume()` exposed by PR #1210; doesn't touch
  Item 1's anchors.
- Item 1's per-anchor `Modifier.coachmarkAnchor(id)` invocations are
  independent of the chip and the AI prefs.
- Item 3's per-feature AI prefs are independent of the coachmark surface.

### Total LOC sum (GREEN-verdict scopes)

| Item | Estimated LOC |
| --- | --- |
| Item 2 (Resume chip) | ~70 (chip + ViewModel state + condition + tests) |
| Item 1 (9 anchors GREEN/YELLOW) | ~150 (avg ~17 LOC per anchor incl. NavGraph mapping + 1 SettingsNavRow modifier extension) |
| Item 3 (4 features) | ~350 (4 pref keys + DataStore methods + 4 toggles in AiSection + 4 gate-check sites + tests) |
| **Total** | **~570 LOC** |

Within the prompt's ~210-820 LOC budget. No STOP-1C, no STOP-3D.

---

## § Item 1 — Layer B coachmark anchors (9 of 13 missing)

### 1. Inventory of all 13 coachmark steps

Source: `app/src/main/java/com/averycorp/prismtask/ui/coachmark/CoachmarkTourContent.kt:87`
(`DEFAULT_COACHMARK_TOUR`).

| # | Step (anchor id) | Anchor target | Wired? |
| - | --- | --- | --- |
| 1 | `TODAY_OVERLOAD_BANNER` | Today balance bar | ✓ (TodayBalanceBar.kt:68) |
| 2 | `TODAY_QUICK_ADD` | Today quick-add bar | ✓ (TodayQuickAddBar.kt:55) |
| 3 | `TODAY_HABIT_CHIPS` | Today habit chip row | ✓ (TodayHabitChips.kt:64) |
| 4 | `TODAY_AI_TOOLS_CHIP` | "AI Tools" AssistChip on Today | ✗ |
| 5 | `NAV_TASKS_TAB` | Tasks tab in bottom nav | ✗ |
| 6 | `NAV_HABITS_TAB` | Habits tab in bottom nav | ✗ |
| 7 | `NAV_MEDS_TAB` | Medications tab in bottom nav | ✗ |
| 8 | `OPEN_TIMER_ENTRY` | Timer tab in bottom nav | ✗ |
| 9 | `AI_COACH_FAB` | AI Coach FAB on Today | ✗ |
| 10 | `EISENHOWER_QUADRANT_GRID` | 2×2 quadrant grid on Eisenhower | ✗ |
| 11 | `SETTINGS_APPEARANCE_ENTRY` | "Advanced Appearance" SettingsNavRow | ✗ |
| 12 | `NAV_SETTINGS_TAB` | Settings tab in bottom nav | ✗ |
| 13 | `BOTTOM_NAV_ROW` | Bottom nav row container | ✓ (NavGraph.kt:513) |

Wired count matches the audit's "4 of 13" expectation. No PAPER-CLOSED.

### 2. Wired-anchor reference shape

All four PR #1210 anchors use the same canonical shape:
**`Modifier.coachmarkAnchor(<id>)` chained inline.**

Reference invocations:
```kotlin
// TodayQuickAddBar.kt:55  (hoisted variant)
val anchor = Modifier.coachmarkAnchor(CoachmarkAnchors.TODAY_QUICK_ADD)
// then: Box(modifier = anchor.fillMaxWidth()...)

// TodayBalanceBar.kt:68  (inline mid-chain)
Column(modifier = Modifier.fillMaxWidth().padding(...)
    .clip(MaterialTheme.shapes.medium)
    .coachmarkAnchor(CoachmarkAnchors.TODAY_OVERLOAD_BANNER)
    .clickable(onClick = onClick).padding(...)
)

// TodayHabitChips.kt:64  (inline tail-of-chain)
LazyRow(modifier = Modifier.fillMaxWidth()
    .coachmarkAnchor(CoachmarkAnchors.TODAY_HABIT_CHIPS), ...)

// NavGraph.kt:513  (inline as sole modifier on a wrapper Column)
Column(modifier = Modifier.coachmarkAnchor(CoachmarkAnchors.BOTTOM_NAV_ROW)) {...}
```

The modifier extension itself (`CoachmarkAnchor.kt:58`) wraps
`onGloballyPositioned` and registers the bounds in `LocalCoachmarkAnchorRegistry`.
Future wiring must use `Modifier.coachmarkAnchor(id)` only — no other
callback shape is supported.

**No STOP-1A** — all four wired sites use the canonical shape; no drift.

### 3. Per-missing-anchor verdicts (9)

#### #4 — `TODAY_AI_TOOLS_CHIP` — **GREEN**
- Target: `AssistChip` at `TodayScreen.kt:529-537` (label: "AI Tools",
  onClick opens TodayAiHubSheet).
- AssistChip accepts a `modifier` parameter — wire by passing
  `Modifier.coachmarkAnchor(CoachmarkAnchors.TODAY_AI_TOOLS_CHIP)`.
- LOC: ~3.

#### #5 — `NAV_TASKS_TAB` — **YELLOW (mechanical resolve)**
- Target: `NavigationBarItem` rendered inside the
  `bottomNavItems.forEachIndexed` loop at `NavGraph.kt:523-567`.
- Conditional render: tab is hidden when route is in `hiddenTabs`. Grace
  period in controller handles missing-anchor case (250ms advance).
- Resolution: switch on `item.route` and apply
  `Modifier.coachmarkAnchor(...)` to the `NavigationBarItem`'s `modifier`
  parameter. Mapping: `PrismTaskRoute.TaskList.route` →
  `CoachmarkAnchors.NAV_TASKS_TAB`. Same loop handles items #6, #7, #8, #12.
- LOC: ~10 (route→anchor mapping helper + modifier param) shared across 5
  bottom-nav anchors.

#### #6 — `NAV_HABITS_TAB` — **YELLOW (same loop as #5)**
- Same `NavigationBarItem` loop. Mapping: `PrismTaskRoute.HabitList.route`
  → `CoachmarkAnchors.NAV_HABITS_TAB`. The "Daily" tab is the canonical
  habits surface in current bottom nav (`ALL_BOTTOM_NAV_ITEMS` at
  NavGraph.kt:334).
- LOC: 0 incremental (covered by #5's mapping).

#### #7 — `NAV_MEDS_TAB` — **YELLOW (same loop as #5)**
- Mapping: `PrismTaskRoute.Medication.route` →
  `CoachmarkAnchors.NAV_MEDS_TAB`.
- LOC: 0 incremental.

#### #8 — `OPEN_TIMER_ENTRY` — **YELLOW (same loop as #5)**
- Mapping: `PrismTaskRoute.Timer.route` →
  `CoachmarkAnchors.OPEN_TIMER_ENTRY`.
- LOC: 0 incremental.

#### #9 — `AI_COACH_FAB` — **YELLOW (Pro-conditional)**
- Target: `SmallFloatingActionButton` at `TodayScreen.kt:268-281`.
- Conditional render: only when `viewModel.isPro`. The tour step itself
  does NOT carry `requiresPro=true`, so for Free users the controller
  silently advances on grace timeout — acceptable per the existing
  `isStepEligible` policy. Wire the modifier inside the existing
  `if (viewModel.isPro)` block.
- LOC: ~3.

#### #10 — `EISENHOWER_QUADRANT_GRID` — **GREEN**
- Target: outer `Column` of the 2×2 grid at
  `EisenhowerScreen.kt:184-280` (the else branch when no quadrant is
  expanded).
- Mechanical wiring on the outer Column's modifier chain.
- LOC: ~3.

#### #11 — `SETTINGS_APPEARANCE_ENTRY` — **YELLOW (component signature extension)**
- Target: `SettingsNavRow(title="Advanced Appearance", ...)` at
  `SettingsScreen.kt:196-202`.
- `SettingsNavRow` (defined at
  `app/src/main/java/com/averycorp/prismtask/ui/components/settings/SettingsNavRow.kt:96`)
  does not accept a `modifier` parameter. Resolution: add an optional
  `modifier: Modifier = Modifier` param and apply it to the row's `Row`
  modifier chain at line 108. Pass the anchor modifier from the
  Appearance row's call site.
- The controller's `SETTINGS_APPEARANCE` route maps via
  `MainActivity.handleCoachmarkRoute` to
  `PrismTaskRoute.Settings.route` (top-level Settings) — so anchoring on
  the Appearance row of `SettingsScreen` is correct.
- LOC: ~5 (component signature extension + row call-site).

#### #12 — `NAV_SETTINGS_TAB` — **YELLOW (same loop as #5)**
- Mapping: `PrismTaskRoute.Settings.route` →
  `CoachmarkAnchors.NAV_SETTINGS_TAB`.
- LOC: 0 incremental.

### Per-anchor LOC summary

| # | Verdict | LOC |
| - | --- | --- |
| #4  | GREEN  | 3 |
| #5  | YELLOW (shared) | 10 |
| #6  | YELLOW (shared) | 0 |
| #7  | YELLOW (shared) | 0 |
| #8  | YELLOW (shared) | 0 |
| #9  | YELLOW | 3 |
| #10 | GREEN  | 3 |
| #11 | YELLOW | 5 |
| #12 | YELLOW (shared) | 0 |
| **Item 1 total** | — | **~24 production LOC**, +50 LOC for tests |

### STOP evaluation for Item 1

- **STOP-1A** not triggered — wiring shape consistent with PR #1210.
- **STOP-1B** not triggered — 0 of 9 anchors are RED.
- **STOP-1C** not triggered — total GREEN+YELLOW LOC well under 400.

### Acceptance criteria (Item 1)

- All 9 missing anchors register bounds when their target screen is mounted.
- Tour walk-through on AVD highlights all 13 surfaces (or skips on grace
  for unmounted-conditional ones, e.g. AI_COACH_FAB on Free).
- No regression to the 4 already-wired anchors.

---

## § Item 2 — Resume-tour chip on Today screen

### 1. Resume API verification

`CoachmarkController.resume()` (`CoachmarkController.kt:89`):
```kotlin
suspend fun resume(isProActive: Boolean = true) {
    if (!eligibleForStart()) return
    val storedIndex = tourCardPreferences.coachmarkStepIndex().first()
        .coerceIn(0, tour.lastIndex)
    showStep(storedIndex, isProActive)
}
```

- **Signature**: suspend, returns Unit.
- **Side effects**: reads `coachmarkStepIndex` from `TourCardPreferences`,
  sets `_state.value` to `ShowingStep(...)` via `showStep`. May persist a
  recoerced index if the stored value is out of range.
- **Preconditions**: requires `eligibleForStart()` to be true
  (`tour_card_eligible && !coachmark_tour_completed && !coachmark_tour_dismissed`).
- **Crash behavior**: NO. When the tour has not been started, `resume()`
  returns silently (the eligibility check is a no-op when stale, and
  `showStep` falls through to `finishCompleted()` if no eligible step
  remains). **No STOP-2A.**

### 2. Today chip-row inventory

- **File**: `TodayScreen.kt`
- **Composable**: `TodayScreen` lazy-column item with key `"quick_actions"`
  at line 462-539.
- **Existing chips (in render order)**: Briefing, Focus, Plan Week,
  Matrix, Extract, Review, AI Tools (7 chips).
- **Trigger surface**: hardcoded `AssistChip(...)` calls in a `Row` with
  `horizontalScroll(rememberScrollState())`. The row is intentionally
  horizontally scrollable per
  `docs/audits/AI_TODAY_ACCESS_AUDIT.md` so adding a chip doesn't break
  small-screen layout.
- **Conditional-render rules**: none on the chip row itself; per-chip
  rendering is unconditional. (The "AI Tools" chip is unconditional —
  the master `aiFeaturesEnabled` toggle is enforced downstream by the
  OkHttp interceptor, not the chip.)

**No STOP-2B** — chip row has no max-cap, scrolls horizontally already.

### 3. Resume-chip render condition

The chip should appear ONLY when:
- `tourCardPreferences.eligible()` is true (onboarding done via SetupPage),
- AND `tourCardPreferences.coachmarkCompleted()` is false,
- AND `tourCardPreferences.coachmarkDismissed()` is false,
- AND `tourCardPreferences.coachmarkStepIndex()` > 0.

Rationale for the `> 0` clause: if step index is 0, the user has never
advanced past the first step; the auto-`tryStart` on Today already runs in
`MainActivity.kt:611`. A "Resume Tour" chip in that state would be
redundant and surface ambiguity (resume from where?).

State exposure: extend `TodayViewModel` with a derived `resumeTourVisible:
StateFlow<Boolean>` produced via `combine(eligible, completed, dismissed,
stepIndex) { e,c,d,s -> e && !c && !d && s > 0 }.stateIn(..., false)`.

The `eligible`/`dismissed`/`stepIndex` flows already feed `tourCardState`
on TodayViewModel (lines 161-171), so the new flow reuses the same
preferences instance — no DI churn.

Tap handler: `viewModel.viewModelScope.launch { coachmarkController.resume() }`.
Or, to avoid threading the controller through the chip composable,
expose a `viewModel.resumeTour()` method that delegates to the controller.
Per the prompt's "Don't introduce new infrastructure" non-acceptance,
the simplest route is to inject `CoachmarkController` into `TodayViewModel`
(it's already a Hilt singleton).

### 4. Verdict

**GREEN.** Resume API works as expected, chip-row pattern is mechanical,
render condition is determinable from existing TourCardPreferences flows.

**No STOP-2C** — implementation requires zero changes to
`CoachmarkController` semantics.

### Acceptance criteria (Item 2)

- Chip absent on first Today open (no tour started).
- Chip absent after tour completion.
- Chip absent after explicit dismissal.
- Chip absent at `stepIndex == 0` (auto-tryStart handles that path).
- Chip present at `stepIndex >= 1` while not completed/dismissed.
- Tapping the chip causes `controller.resume()` to fire and the overlay
  re-shows at the persisted step index.

---

## § Item 3 — Per-feature AI opt-ins

### 1. Master toggle inventory

- **Pref key**: `KEY_AI_FEATURES_ENABLED = booleanPreferencesKey("ai_features_enabled")`
  in `UserPreferencesDataStore.kt:224`.
- **Default**: `true` (line 397: `prefs[KEY_AI_FEATURES_ENABLED] ?: true`).
- **Read API**: `aiFeaturePrefs.enabled` (Flow), `isAiFeaturesEnabledBlocking()`
  (sync, line 569).
- **Write API**: `setAiFeaturesEnabled(Boolean)` (line 514).

**Call sites that READ this pref** (grep):
1. `UserPreferencesDataStore.kt:397` — Flow projection.
2. `UserPreferencesDataStore.kt:569` — blocking accessor.
3. `OnboardingViewModel.kt:123, 366-367` — onboarding's PrivacyPage toggle.
4. `OnboardingScreen.kt:1580, 1630` — PrivacyPage UI.
5. `SettingsViewModel.kt:253-254` — settings AI section toggle write.
6. `AiSection.kt:27, 28, 45, 53, 54` — settings AI section UI.
7. `TodayAiHubSheet.kt:77-78` — Today AI Hub bottom-sheet read.
8. **`AiFeatureGateInterceptor.kt:47`** — OkHttp interceptor that gates ALL
   `/api/v1/ai/*`, `/api/v1/tasks/parse`, `/api/v1/syllabus/parse`,
   `/api/v1/integrations/gmail/scan` paths.
9. **`AiActionHandlers.kt:46, 82`** — automation AI action handler short-circuit.

**Backend equivalent**: `require_ai_features_enabled` middleware
(`backend/app/middleware/ai_gate.py`, per `AiFeatureGateInterceptor.kt:96-99`
and `PrismTaskApi.kt:243`). The header
`X-PrismTask-AI-Features: disabled` is stamped by the interceptor when
the master toggle is off; the backend dependency rejects with 451 even
if the request slips past the client interceptor.

### 2. AI feature inventory (currently gated by master toggle)

Sourced from `AiSection.kt` (settings/sections/AiSection.kt):
1. Eisenhower Auto-Classify (already has its own toggle:
   `eisenhowerAutoClassifyEnabled` at `AiSection.kt:25`)
2. Eisenhower Matrix (navigates to AI feature screen)
3. Smart Focus Sessions (Smart Pomodoro)
4. Daily Briefing
5. Weekly Planner
6. Time Blocking
7. Extract Tasks From Text
8. Weekly Review
9. Mood Analytics
10. AI Coach Chat

Of these, the F3 prompt names 5 candidates: AI Chat, Daily Briefing, Smart
Pomodoro, Weekly Planner, Morning Check-In.

**Drift note**: Morning Check-In is *not* currently AI-gated (no
`/api/v1/ai/*` calls from `app/src/main/java/com/averycorp/prismtask/ui/screens/checkin/`,
no master-toggle reads on `MorningCheckIn*`). Per **STOP-3C**, drop
Morning Check-In from in-bundle scope and file as a separate F3 follow-on
(verify whether Morning Check-In was *intended* to be AI-gated or whether
the prompt's enumeration was speculative).

**Final per-feature opt-in scope (4 features, in-bundle):**
1. AI Coach Chat — `KEY_AI_CHAT_ENABLED`
2. Daily Briefing — `KEY_AI_DAILY_BRIEFING_ENABLED`
3. Smart Pomodoro — `KEY_AI_SMART_POMODORO_ENABLED`
4. Weekly Planner — `KEY_AI_WEEKLY_PLANNER_ENABLED`

**No STOP-3A** — feature count = 4, well under the 6 cap.

### 3. Per-feature pref design

- **Pref-key naming**: `KEY_AI_<FEATURE>_ENABLED` (boolean), DataStore
  location: existing `UserPreferencesDataStore`.
- **Default values**: `true` (mirrors the master toggle's default).
- **Migration strategy**: NO explicit DB migration is required — DataStore
  prefs return their default on first read for any unset key. The
  per-feature pref defaults to `true`, so the migration shape is "user
  who upgrades sees all per-feature prefs ON, identical to today's
  master-only behaviour". If the master is OFF, the master-toggle gate
  still wins via the interceptor — per-feature prefs become irrelevant
  in that state.
- **Master toggle semantic**: KEEP the master as the authoritative
  privacy gate. Per-feature prefs layer ON TOP — they hide UI surfaces
  / short-circuit local AI actions, but the master remains the only
  switch that affects the OkHttp interceptor + backend gate. Do NOT
  retire the master.

### 4. Gate-check refactor scope

For each of the 4 features, a per-feature gate is added at:
- The Today chip / hub entry-point (so a disabled feature's UI is hidden).
- The feature screen's ViewModel init / first-AI-call (defense-in-depth).

Per-call-site LOC: ~5 LOC each (one read of the new pref + an `if` guard).
Across 4 features × ~2 sites each = ~40 LOC.

### 5. AiSection UI scope

Add 4 toggle rows under the existing master toggle in `AiSection.kt`,
each rendered as `SettingsToggleRow(title=..., subtitle=..., checked=...,
onCheckedChange=...)`. Estimated ~10 LOC per toggle including ViewModel
plumbing → ~40 LOC for UI.

### 6. STOP evaluation for Item 3

- **STOP-3A** not triggered — 4 features ≤ 6.
- **STOP-3B** evaluation: backend `require_ai_features_enabled` is a
  master gate only; per-feature prefs do NOT need to satisfy it.
  Architecturally, per-feature prefs are a UI/local layer above the
  backend gate. The privacy invariant (no PrismTask data reaches
  Anthropic when master is OFF) is preserved exclusively by the
  master toggle. **NOT triggered.**
- **STOP-3C**: Morning Check-In doesn't consult the master toggle today
  — flagged as separate F3 follow-on (see § Bundle-decision and Phase 4
  summary). The 4 in-scope features all consult the master via either
  the interceptor's URL prefix matching or the `AiActionHandlers`
  blocking checks.
- **STOP-3D** not triggered — total LOC ~150 production + ~50 tests is
  well under the 600 cap.

### 7. Verdict

**GREEN.** All 4 features have identifiable consumer sites; per-feature
gating is additive on top of the existing master toggle; no architectural
surprises.

### Acceptance criteria (Item 3)

- Toggle each per-feature pref off independently → that feature's chip /
  entry hides AND its first AI call short-circuits, while OTHER AI
  features remain functional.
- Master toggle OFF → all 4 per-feature gates become irrelevant
  (interceptor blocks everything anyway).
- No backend behaviour change.
- Migration: existing user with master ON sees all per-feature prefs ON.
- Migration: existing user with master OFF sees all per-feature prefs ON
  (the per-feature prefs are independent; the master is the privacy
  gate).

---

## § Phase 2 commit plan

| Commit | Item | LOC est. |
| --- | --- | --- |
| 1 | Item 2: Resume chip + ViewModel state + render condition | ~70 |
| 2 | Item 1: Layer B anchors (5 nav-loop + 4 standalone) + SettingsNavRow modifier param | ~75 |
| 3 | Item 3: 4 per-feature prefs + AiSection toggles + 4-8 gate sites | ~150 |
| 4 | Tests (unit for ViewModel state, Item 3 pref defaults, Item 2 chip condition) | ~120 |
| 5 | Audit doc (this file + Phase 4 handoff) | this doc |

Total production LOC ~295, tests ~120, total ~415. Well under the
1000-LOC ceiling.

---

## § Phase 4 handoff (PRINTED INTO CC CHAT POST-IMPL)

Phase 4 summary block is emitted directly into the Claude Code chat per
operator convention (memory #16 + memory #18). See the chat output below
the implementation commits.

---

## § Z Appendices

(Reserved — trim first if 1000-line cap approached. Currently empty.)
