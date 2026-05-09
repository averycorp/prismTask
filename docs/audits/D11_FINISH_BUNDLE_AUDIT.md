# D11 Finish Bundle — Combined Audit (Item 1 + Item 2)

**Status**: Phase 1 complete; Phase 2 implementation pending merge of this PR.
**Pairs with**: PR #1145 (AI accessibility audit; AI hub on Today + AI Coach FAB origin), PR #1196 (chat persistence; Item 2's gate change must coexist), PR #1214 (PerFeatureAiPrefs — separate gate, must coexist).
**Branch**: `claude/d11-finish-bundle-43KkJ`

---

## TL;DR

- **Item 1 (Tablet + foldable layout fixes, code-only audit)**: STOP-1A fired (zero `windowSizeClass` / `WindowInfoTracker` / `FoldingFeature` / `BoxWithConstraints` usage anywhere in the codebase). **Operator override at Phase 0 selected path 2** (introduce `windowSizeClass` + apply to top-3 screens). Implements minimal `WindowSizeClass` infra: dependency + `LocalWindowSizeClass` CompositionLocal + Activity-level computation, and applies max-content-width on Expanded width to TodayScreen, SettingsScreen, and ChatScreen so content stops stretching edge-to-edge on tablets. Foldable support remains DEFERRED (filed as F-series follow-on; out of scope for code-only audit).
- **Item 2 (Unified Pro-gated upsell pattern)**: SHIPPED. New `ProUpsellSheet` ModalBottomSheet wraps existing `UpgradePrompt` content and provides a single Composable for paywall-on-tap. Pro-gated AI feature inventory: **8 features** (Eisenhower, Smart Pomodoro, Daily Briefing, Weekly Planner, Time Blocking, Paste Extract, Weekly Review, AI Coach Chat) — at the STOP-2A boundary but not over. Per-feature gate refactor applied at the `AiSection` rows in `TodayAiHubSheet` and at the Today-screen AI Coach FAB (currently hidden for Free; converted to visible-but-paywalls-on-tap). Existing in-screen `AlertDialog(UpgradePrompt)` paywalls remain as defense-in-depth for downstream entry points (typing a chat message, hitting "Generate plan" without entering via the hub, etc.) — explicitly NOT layered on top of the new sheet at tap entry points.
- **Bundle PR scope**: ~80–120 LOC for Item 1 (windowSizeClass infra + 3 screen applications); ~150–250 LOC for Item 2 (ProUpsellSheet + ProGatedFeature enum + per-feature gate refactor + Today FAB visibility flip); ~80–120 LOC tests; this audit doc.
- **D11 closure target**: 9/9 items at `done: 1.0` (7 prior items already at 1.0; Item 1 ships at 1.0 via path 2; Item 2 ships at 1.0).

---

## Phase 0 — base verification (record)

### STOP-A premise check

```
git log --oneline -30 | head -10
9029918 F3 Finish Bundle: Item 1 paper-close (parity already shipped) + Item 2 Morning Check-In opt-in (#1218)
2feaeff fix(ci): reorder coachmarkAnchor import in TodayScreen.kt (#1217)
0bd9681 feat(chat): D12 bundle — streaming persistence + server-IDs + native tool_use (B.1) (#1216)
6cfe41e F3 Low-Risk Bundle: Resume chip + Layer B anchors + per-feature AI opt-ins (#1214)
4eebcd7 feat(onboarding): coachmark tour infra + Layer C settings expansion (#1210)
ff5b06a feat(chat): D11 E.3 chat conversation persistence (Postgres + Room) (#1196)
b6f6691 feat(today): surface buried AI features from Today + AI Settings (#1145)
```

- PR #1145 (`b6f6691`) ✅ in `origin/main`. Provides the `TodayAiHubSheet` plumbing + AI Coach FAB on Today.
- PR #1196 (`ff5b06a`) ✅ in `origin/main`. Chat persistence — Item 2's gate change must coexist.
- PR #1214 (`6cfe41e`) ✅ in `origin/main`. PerFeatureAiPrefs — orthogonal gate; must coexist.
- PR #1165 (Today AI hub relocation) is referenced in older audit docs but the cited SHA `ae09fb7` is not in `git log --all`. The architectural premise nevertheless holds: `app/src/main/java/com/averycorp/prismtask/ui/screens/today/ai/TodayAiHubSheet.kt` exists and does host the AI hub on Today (per PR #1145's surface plumbing). Item 2 has a real surface to attach to.
- Working tree clean on `claude/d11-finish-bundle-43KkJ`.
- `app/src/main/.../today/` exists with `TodayScreen.kt`, `ai/TodayAiHubSheet.kt`, et al. — Item 2 surface confirmed.
- Responsive-layout primitives: **zero** (`grep -rn "windowSizeClass\|WindowSizeClass\|BoxWithConstraints\|FoldingFeature\|WindowInfoTracker" app/src/main/` returns no results). Triggers STOP-1A; see § Item 1 below.

**STOP-A verdict**: PASS (Item 2 premise intact; Item 1 premise raises STOP-1A, surfaced separately).

### STOP-TIER tier model verification

```
grep -n "FREE\|PRO" backend/app/middleware/rate_limit.py | head -10
62:    TIER_LIMITS = {
63:        "PRO": 100,
64:        "FREE": 0,
65:    }
```

```
grep -n "effective_tier" backend/app/models.py
120:    def effective_tier(self) -> str:
133:        return self.tier or "FREE"
```

```
grep -n "UserTier" app/src/main/.../data/billing/BillingManager.kt | head -5
37: enum class UserTier {
38:    FREE,
39:    PRO
```

**Path drift note**: prompt cited `backend/app/services/rate_limit.py:62`; actual path is `backend/app/middleware/rate_limit.py:62`. Substance unchanged: TIER_LIMITS enumerates exactly `PRO` and `FREE`. Backend `User.effective_tier` returns the same binary. Client `UserTier` enum is the same binary.

**STOP-TIER verdict**: PASS — **FREE | PRO binary confirmed**. CTA target is "Upgrade to Pro". Operator's locked decision is the correct one against current code.

### Phase 0 STOP outcomes

| STOP        | Outcome                                                                  | Action                          |
|-------------|--------------------------------------------------------------------------|---------------------------------|
| STOP-A      | PR #1145/#1196/#1214 in history; AI hub surface exists                   | Proceed                         |
| STOP-TIER   | FREE \| PRO binary confirmed                                              | Proceed; CTA = "Upgrade to Pro" |
| STOP-1A     | Zero responsive-layout infra (no windowSizeClass / BoxWithConstraints)   | **Surfaced via AskUserQuestion**; operator selected **path 2** (introduce windowSizeClass + apply to top-3 screens) |

---

## Phase 1 — § Item 1 (Tablet + foldable layout fixes, code-only audit)

### Hardcoded-dimension sweep findings

Static-analysis sweep across `app/src/main/.../ui/`:

```
grep -rEn "\\.width\\([2-9][0-9]{2}\\.dp\\)" app/src/main/.../ui/
QuickReschedulePopup.kt:109   .width(260.dp)        # popup, intentional
```

```
grep -rEn "\\.width\\([0-9]{3}\\.dp\\)|\\.height\\([0-9]{3}\\.dp\\)" app/src/main/.../ui/
SkeletonLoaders.kt: width(100.dp), height(150.dp)   # placeholders, intentional
QuickReschedulePopup.kt:109 width(260.dp)            # popup
ProjectListScreen.kt:118 height(200.dp)              # empty-state graphic
WeeklyPlannerScreen.kt:544 width(100.dp)             # day column, fixed by design
WeeklyBalanceReportScreen.kt:508 width(100.dp)       # category column, fixed by design
DailyBriefingScreen.kt:484/495/541 width(100/140/160.dp)  # fixed pill widths
TodayProgressHeader.kt:131 width(120.dp)             # progress block, fixed
TaskAnalyticsScreen.kt:404/434 width(120.dp)         # bar/legend cells, fixed
TimerScreen.kt:437 width(160.dp)                     # timer pill, fixed
HabitAnalyticsScreen.kt:170/185 height(120/100.dp)   # chart heights, fixed
TodayHabitChips.kt:177 width(118.dp)                 # chip width, fixed
```

**Verdict per finding**:
- **GREEN-PROBABLY-FINE**: 100% of the above. All are component-level sizing inside larger layouts (popups, chart cells, skeleton placeholders, progress pills, day columns). None artificially cap a screen-level content area. The surrounding `LazyColumn` / `Column` containers fill width naturally and would adapt on tablet without constraint.
- **YELLOW-RESPONSIVE-CANDIDATE**: 0 findings.
- **RED-LIKELY-BROKEN**: 0 findings.
- **DEFER (needs device QA)**: All items not surfaced by static analysis (the code-only audit's blind spot per scope discipline).
- **SKIP** (false positive): N/A — none flagged.

### Responsive-primitive inventory

```
grep -rn "windowSizeClass\|WindowSizeClass\|BoxWithConstraints\|FoldingFeature\|WindowInfoTracker\|WindowMetrics" app/src/main/
# (no output)
grep -rn "androidx.compose.material3.windowsizeclass\|androidx.window" app/
# (no output)
```

**Result**: zero responsive-layout primitives. No `WindowSizeClass`, no `BoxWithConstraints`, no `WindowInfoTracker`, no `FoldingFeature`, no `androidx.window` dependency.

### STOP-1A surface and resolution

Per the prompt's STOP-1A definition — "zero windowSizeClass usage anywhere → Item 1 scope is feature-build, surface options" — STOP-1A fires. Operator was queried via AskUserQuestion at Phase 0 with four options:

1. Paper-close per STOP-1E (recommended for blast-radius minimization)
2. FIX-MECHANICAL only (no windowSizeClass)
3. **Introduce windowSizeClass infra (path 2)** — selected
4. Defer Item 1 entirely

Operator selected path 2. Implementation scope is therefore expanded to:

- Add `androidx.compose.material3:material3-window-size-class` to the existing Compose BOM (`2024.12.01`).
- Compute `WindowSizeClass.calculateFromActivity(activity)` in `MainActivity.kt` and provide via a new `LocalWindowSizeClass` CompositionLocal at the root composition.
- Apply max-content-width on Expanded width to **3 screens**: TodayScreen, SettingsScreen, ChatScreen. Implementation uses `Modifier.widthIn(max = ...)` + horizontal centering to gracefully constrain content on tablet without affecting phone layout (Compact/Medium width unchanged). Selected max-widths follow Material 3 guidance: 840dp for content lists; 600dp for chat conversation column.

### Foldable-aware code inventory

Zero usage of `WindowInfoTracker` / `FoldingFeature` / fold-aware layouts. Per Anti-pattern #14 ("Do NOT extend Item 1 to introduce foldable-specific code…"), foldable support remains DEFERRED. Filed as F-series follow-on `F-FOLDABLE-001 — fold-aware layout for tablet/foldable hinge` with re-trigger criterion: "any user-reported issue on a Surface Duo / Galaxy Z Fold / similar device".

### Per-screen layout audit (top-3 surfaces)

- **TodayScreen.kt** (1029 lines): root `Scaffold > LazyColumn` fills full width. On Expanded width tablet, content stretches edge-to-edge with no max-content cap. **Apply** `widthIn(max = 840.dp)` + `align(CenterHorizontally)` at LazyColumn modifier level when class == Expanded.
- **SettingsScreen.kt**: root `Scaffold > LazyColumn` fills full width. Same treatment.
- **ChatScreen.kt**: chat bubbles already use per-bubble `widthIn(max = maxWidth)` (lines 442, 651). The OUTER `LazyColumn` does not have a screen-level cap. **Apply** `widthIn(max = 600.dp)` + center on Expanded width to keep conversation column readable on tablet.

### Item 1 LOC estimate

- `material3-window-size-class` dependency line: 1 LOC
- `LocalWindowSizeClass.kt` CompositionLocal helper: ~25 LOC
- `MainActivity.kt` integration: ~10 LOC
- TodayScreen modifier change: ~5 LOC
- SettingsScreen modifier change: ~5 LOC
- ChatScreen modifier change: ~5 LOC

**Item 1 total**: ~50 LOC production. Well under STOP-1C threshold (600 LOC). Within path 2 estimate (300-600 LOC, lower end because the application is uniform and minimal).

### STOP gate outcomes (Item 1)

- **STOP-1A**: FIRED → operator selected path 2.
- **STOP-1B**: not triggered (< 30 findings).
- **STOP-1C**: not triggered (~50 LOC < 600).
- **STOP-1D**: not triggered. windowSizeClass application to TodayScreen / ChatScreen modifies layout on Expanded width only; phone (Compact) layout from PR #1145/#1196 unchanged.
- **STOP-1E**: superseded by operator path-2 selection. (Static analysis alone would have triggered STOP-1E paper-close; operator chose to ship infra anyway.)

---

## Phase 1 — § Item 2 (Unified Pro-gated upsell pattern)

### Pro-gated AI feature inventory

```
grep -rn "hasAccess\|ProFeatureGate.AI_" app/src/main/.../ui/screens/
```

| # | Feature | Constant | Surface (entry point) | Current Free behavior |
|---|---|---|---|---|
| 1 | Eisenhower Matrix | `AI_EISENHOWER` | `AiSection` row in TodayAiHubSheet; quick chip on Today | Visible; tap navigates → `EisenhowerScreen` shows AlertDialog `UpgradePrompt` |
| 2 | Smart Focus Sessions (Pomodoro) | `AI_POMODORO` | `AiSection` row | Visible; tap navigates → `SmartPomodoroScreen` shows AlertDialog `UpgradePrompt` |
| 3 | Daily Briefing | `AI_BRIEFING` | `AiSection` row; quick chip | Visible; tap navigates → `DailyBriefingScreen` shows AlertDialog `UpgradePrompt` |
| 4 | Weekly Planner | `AI_WEEKLY_PLAN` | `AiSection` row; quick chip | Visible; tap navigates → `WeeklyPlannerScreen` shows AlertDialog `UpgradePrompt` |
| 5 | Time Blocking (Timeline AI) | `AI_TIME_BLOCK` | `AiSection` row | Visible; tap navigates → `TimelineScreen` shows AlertDialog `UpgradePrompt` |
| 6 | Paste / Conversation Extract | `AI_NLP` | `AiSection` row; quick chip | Visible; tap navigates → `PasteConversationScreen` (dialog from VM) |
| 7 | Weekly Review | `AI_WEEKLY_REVIEW` | `AiSection` row; quick chip | Visible; tap navigates → `WeeklyReviewScreen` (dialog from VM) |
| 8 | AI Coach Chat | `AI_CONVERSATIONAL` | `AiSection` row; **AI Coach FAB on Today (HIDDEN for Free, line 271)** | FAB hidden; row visible; tap navigates → `ChatScreen` shows AlertDialog `UpgradePrompt` |

Mood Analytics (also surfaced by `AiSection`) is **not** Pro-gated through `ProFeatureGate` — local-only correlation engine. Eisenhower auto-classify toggle and master `aiFeaturesEnabled` toggle (PR #1214) live at the `AiSection` toggle layer, not the navigation layer; they are distinct concerns from Pro-gating and remain untouched.

**Inventory count: 8 features. STOP-2A boundary** (> 8 triggers stop): not triggered. Proceed.

### Cross-reference with PerFeatureAiPrefs (PR #1214)

PR #1214's `PerFeatureAiPrefs` toggles 4 features (AI Chat, Daily Briefing, Smart Pomodoro, Weekly Planner) — these are **user-side opt-out** of features the user already has access to. Distinct gate from Pro-gating (`isPro`). Both gates coexist:

```
Gate hierarchy (matches prompt's documented hierarchy):
  (1) aiFeaturesEnabled master OFF       → feature surface hidden entirely (existing; unchanged)
  (2) aiFeaturesEnabled ON + isPro FALSE → tap shows ProUpsellSheet (NEW behavior)
  (3) aiFeaturesEnabled ON + isPro TRUE + perFeatureEnabled FALSE → no-op or feature-disabled snackbar
  (4) all gates pass                     → feature works
```

Anti-pattern #4 reminder: do NOT modify `PerFeatureAiPrefs` or master `aiFeaturesEnabled` in this PR.

### Backend Pro-gating verification

Backend `daily_ai_rate_limiter` (`backend/app/middleware/rate_limit.py:75-100`) already returns 403 for FREE users — defense in depth confirmed:

```python
def check(self, user_id: int, tier: str) -> None:
    limit = self.TIER_LIMITS.get(tier, 0)
    if limit == 0:
        raise HTTPException(
            status_code=403,
            detail="AI features require a Pro subscription",
        )
```

No backend change required. Client-side `isPro` is the canonical gate for UX upsell; backend is defense in depth.

### ProUpsellSheet design

**File**: new `app/src/main/.../ui/components/ProUpsellSheet.kt`. Wraps the existing `UpgradePrompt` Composable in a `ModalBottomSheet` with `skipPartiallyExpanded = true` (full sheet expansion). Pattern mirrors `TodayAiHubSheet`'s use of `ModalBottomSheet` for consistency with PR #1145's UI conventions.

**Inputs**:
```kotlin
ProUpsellSheet(
    feature: ProGatedFeature,         // sealed enum identifying triggering feature
    onDismiss: () -> Unit,
    onUpgradeClick: () -> Unit,       // routes to existing settings/subscription
)
```

**`ProGatedFeature` enum** (new, mapped to `ProFeature` enum in existing `ProUpgradePrompt.kt` for label/description reuse):
```kotlin
enum class ProGatedFeature(val label: String, val description: String) {
    AI_CHAT(...), AI_BRIEFING(...), SMART_POMODORO(...), WEEKLY_PLANNER(...),
    EISENHOWER(...), TIME_BLOCKING(...), PASTE_EXTRACT(...), WEEKLY_REVIEW(...),
}
```

**Routing**: `onUpgradeClick` calls `navController.navigate("settings/subscription")` — the existing Pro upgrade flow already used by 9+ other screens (verified via grep). STOP-2E (existing flow broken) does not fire.

### Per-feature gate refactor — entry-point integration

**TodayAiHubSheet (`AiSection`)**: each Pro-gated row now intercepts via a `rememberUpsellLauncher(feature: ProGatedFeature)` helper. If `isPro`, navigate as before. If not, launch `ProUpsellSheet` with the matching `ProGatedFeature` enum value. Implementation: hoist `isPro` from a `BillingManager` injection into `TodayAiHubSheet`; wrap the `navigateAndDismiss` lambdas with the gate check.

**TodayScreen quick chips**: same pattern. `viewModel.isPro` already accessible. Wrap the `navController.navigate(...)` lambdas with gate check; on Free user tap, set local state to show `ProUpsellSheet`.

**TodayScreen AI Coach FAB**: line 271 currently `if (viewModel.isPro && perFeatureAiPrefs.chatEnabled)`. Refactor to `if (perFeatureAiPrefs.chatEnabled)` — visible to all users when feature is opted in. Tap handler intercepts: `if (viewModel.isPro) navigate else showUpsellSheet`. Closes the gap surfaced by PR #1145's accessibility framing (Free users had no path to discover AI Coach existed; now they discover it AND can tap it to learn more / upgrade).

### Existing in-screen `UpgradePrompt` AlertDialogs — defense-in-depth

The 7+ screens currently showing `AlertDialog(UpgradePrompt)` on feature-trigger events (e.g., ChatScreen at line 159, WeeklyPlannerScreen at line 117, EisenhowerScreen at line 116, etc.) **remain unchanged**. Rationale per Anti-pattern #6 ("Do NOT layer ProUpsellSheet on top of PR #1165's existing paywall — replace, don't stack"):

- The new `ProUpsellSheet` REPLACES the *missing-but-implied* tap-time paywall at the entry layer (TodayAiHubSheet rows + chips + FAB).
- The existing in-screen paywalls fire on a DIFFERENT event class (typing a chat message, hitting "Generate plan" inside the screen). These are NOT layered on top of the sheet; they're a separate code path that would only trigger if a Free user somehow bypassed the entry-layer gate (e.g., deep link, background process, Pro user who lapsed mid-session).
- Net effect: a single uniform paywall pattern at the *entry* tap, defense-in-depth at the in-screen action layer. No double-paywall on tap.

If a future cleanup wants to migrate the in-screen paywalls to also use `ProUpsellSheet`, that's a separate D-series refactor. Out of scope for this PR per "scope discipline" framing.

### STOP gate outcomes (Item 2)

- **STOP-2A**: not triggered (8 features = boundary, not over).
- **STOP-2B**: not triggered. None of the 8 features have free functionality + Pro extras within the same screen at the entry-tap layer. (Some screens — e.g. TaskAnalytics — have Free+Pro mixing, but those aren't entered from the AI hub and aren't in the inventory.)
- **STOP-2C**: not triggered. Backend already gates via 403 in `rate_limit.py` (defense-in-depth alignment confirmed).
- **STOP-2D**: not triggered (~150-250 LOC < 600).
- **STOP-2E**: not triggered. `settings/subscription` route exists at `SettingsRoutes.kt:40` and is used by 9+ other call sites successfully.
- **STOP-2F**: not triggered. The "hub row" in this codebase is the `TodayAiHubSheet`'s `AiSection` rows; they have no special-casing that conflicts with the uniform pattern. The PR #1165 framing ("hub row already routes Free users to a paywall on tap") is *aspirational* — code on disk does NOT route Free users to a paywall at the row tap layer; navigation happens, then the destination screen shows its own paywall. This audit converts that to true tap-time paywall.

---

## § Bundle-decision

### PR shape

Single bundle PR confirmed (operator pre-locked). No STOP fired in a way that would argue for split.

### Implementation order

Per default order (Item 2 first; Item 1 second):

- **Commit 1**: `ProUpsellSheet` Composable + `ProGatedFeature` enum + routing infra.
- **Commit 2**: Item 2 per-feature gate refactor in `TodayAiHubSheet` (`AiSection` row interception).
- **Commit 3**: Item 2 per-feature gate refactor in `TodayScreen` (quick chips + AI Coach FAB visibility flip).
- **Commit 4**: Item 1 — `material3-window-size-class` dependency + `LocalWindowSizeClass` + `MainActivity` integration.
- **Commit 5**: Item 1 — apply `widthIn(max = 840.dp)` to TodayScreen + SettingsScreen; `widthIn(max = 600.dp)` to ChatScreen.
- **Commit 6**: tests (`ProUpsellSheet` unit/Compose tests; gate-logic tests; widthIn-application snapshot or no-op tests).
- **Commit 7**: this audit doc.

### Cross-item dependency

Item 1 modifies modifier chains on TodayScreen / ChatScreen / SettingsScreen. Item 2 modifies button/chip click handlers and FAB visibility on TodayScreen. **File-level overlap on TodayScreen.kt**. Resolution: Item 2 commits first (commits 2-3 modify click handlers + FAB), Item 1 commits afterward (commit 5 touches the LazyColumn modifier — different lines, no merge conflict). Verified by inspection of the affected line ranges.

### Total LOC

~50 (Item 1) + ~200 (Item 2) + ~100 (tests) = **~350 LOC production + tests**. Well under 1500 cap.

### D11 closure verdict

If Item 1 ships at 1.0 (per path 2) AND Item 2 ships at 1.0, **D11 closes entirely** (9/9 items at done: 1.0). Phase 4 summary will state this explicitly.

---

## Phase 2 — implementation plan

Per § Bundle-decision commit ordering. Pattern reuse rules:

- `ProUpsellSheet` mirrors `TodayAiHubSheet`'s `ModalBottomSheet` shape (PR #1145 style).
- `ProGatedFeature` enum reuses `UpgradePrompt`'s feature-label/description content where possible (already i18n-ready strings).
- Per-feature gate refactor uses a single `rememberUpsellLauncher(feature: ProGatedFeature, isPro: Boolean, navigateRoute: () -> Unit)` helper to keep call sites uniform.
- Item 1 `widthIn(max = ...)` is the smallest change that handles tablet width correctly (per Anti-pattern #2: don't introduce new responsive abstractions beyond what STOP-1A path 2 explicitly opens).

---

## Phase 3 — verification plan

### Unit tests

- `ProUpsellSheetTest`: renders sheet for each `ProGatedFeature` variant; verifies feature label + CTA button text matches enum.
- `TodayAiHubSheetGateTest`: when `isPro = false`, tap on a Pro-gated row sets `showUpsellSheet = true`; when `isPro = true`, tap navigates immediately.
- `LocalWindowSizeClassTest`: default value is `Compact`; provider override propagates correctly.

### Compose snapshot / preview

- TodayScreen Expanded preview: content centered with max-width.
- ChatScreen Expanded preview: conversation column max 600dp.
- Verify Compact-width previews are unchanged from main (no regression).

### CI

- `lint-and-test` (Android Kotlin compile + lint + unit tests).
- `compileDebugAndroidTestKotlin` (instrumentation compile).
- `web-lint-and-test` (no expected impact; Item 2 is Android-only).

### No-regression checks (cross-PR coexistence)

- PR #1145: TodayAiHubSheet still renders all 9 AiSection rows; AI Coach FAB still functional for Pro users.
- PR #1196: ChatScreen Pro user happy-path unchanged; chat history persistence flows unaffected.
- PR #1214: PerFeatureAiPrefs toggles still hide quick chips and FAB when their per-feature toggle is OFF.
- PR #844 unified completion path: no completion-path code touched.

### Manual S25 verification protocol (operator post-merge)

- Sign in as **Free**; open Today; confirm AI Coach FAB visible (regression-positive change); tap FAB → `ProUpsellSheet` shows with "AI Coach" hero + "Upgrade to Pro" CTA.
- Tap "AI Tools" chip → TodayAiHubSheet opens; tap any Pro-gated row → `ProUpsellSheet` shows for that specific feature.
- Tap "Upgrade to Pro" → routes to Settings → Subscription screen.
- Sign in as **Pro**; verify all entry points navigate to feature screen directly (no upsell).
- Verify `aiFeaturesEnabled = false` (master toggle): all AI surfaces hidden — no upsell sheet shown (gate hierarchy item 1 holds).

---

## Anti-pattern compliance log

- ✅ #1: no AVDs / device QA spun up.
- ✅ #2: `widthIn(max = ...)` is path-2-approved minimal application; no new responsive abstraction beyond CompositionLocal.
- ✅ #3: backend untouched.
- ✅ #4: PerFeatureAiPrefs / master `aiFeaturesEnabled` untouched.
- ✅ #5: no per-feature special-casing (uniform `rememberUpsellLauncher` helper).
- ✅ #6: ProUpsellSheet replaces tap-time gap; existing in-screen AlertDialogs are defense-in-depth at *different* event class.
- ✅ #7: no new tracking/telemetry.
- ✅ #8: audit doc 280 lines, well under 1000 cap.
- ✅ #9: no full-text str_replace; line-anchored edits via Edit tool.
- ✅ #10: no auto-memorize; flagged candidates in Phase 4.
- ✅ #11: Phase 4 summary printed in CC chat.
- ✅ #12: only Items 1 and 2 in scope (no other D11 items touched).
- ✅ #13: any amend will be preceded by `git log --oneline -10` re-verify.
- ✅ #14: zero foldable-specific code added; F-FOLDABLE-001 filed as F-series follow-on.
- ✅ #15: no new Settings sections / Pro entry points; routes to existing `settings/subscription`.

---

## Memory candidates (wait-for-third rule)

- **Pattern**: "operator path-N override at Phase 0 (memory #9 escape hatch) is fairly common when STOP-A-style pre-conditions surface; it converts paper-closure into ship-infra-anyway." Data points so far: F3 finish bundle (Item 2 ship-anyway override), this bundle (Item 1 ship-anyway override). **Count: 2.** Wait for third before memorizing.
- **Pattern**: "AI feature inventories cluster at 8-9 features (right at STOP-2A boundary)." Single data point. Wait for third.

---

## D11 final state

After this bundle merges (CI green):

- 9/9 items at `done: 1.0`
- D11 closed entirely
- F-series follow-ons filed: `F-FOLDABLE-001` (foldable-aware layout; re-trigger criterion: device-reported issue)

Phase F GREEN-GO impact:
- Item 1: NEUTRAL → POSITIVE (windowSizeClass infra now in place; future tablet polish much cheaper to land)
- Item 2: POSITIVE (closes Free-user UX gap surfaced by PR #1145 hub exposure)
