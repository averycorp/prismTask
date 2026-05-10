# D13 Finish Bundle — Combined Audit

**Branch:** `claude/close-d13-audit-items-PVPEj`
**Date:** 2026-05-10
**Framing:** pre-Phase-F hygiene closure (May 15 kickoff). Discipline > scope-stretch.
**Bundle estimate (prompt):** ~135–545 LOC. Operator-approved verdicts in §Bundle-decision land at ~330 LOC + audit doc.

## Phase 0 — Premise verification

The mega-prompt referenced PRs #1167 (onboarding holistic redesign) and #1168
(chat GREEN-SPLIT) as the origin of Items 1–5. **Neither PR number appears in
this repo's `git log --all`**, and `grep -rn "POST_NOTIFICATIONS|ViewsPage|
AiOverviewPage" docs/audits/` returns zero hits — STOP-A and STOP-PR1167 both
fired at Phase 0.

The operator was surfaced via AskUserQuestion and chose **"Re-scope from
on-disk state"**: re-derive each item's premise from the live code rather than
the prompt's deferral-doc framing. This audit's verdicts are therefore grounded
in surfaces that exist on `main`-as-of-2026-05-10, not in the (unverifiable)
PR #1167/#1168 paper trail.

The per-item premises map cleanly to live code:

- Item 1 attaches to `OnboardingScreen.kt:1645–1685` (`NotificationsPage`)
  and `MainActivity.kt:293–316`.
- Item 2 attaches to `OnboardingScreen.kt:783` (`ViewsPage`) and `:1478`
  (`AiOverviewPage`).
- Item 3 attaches to `ChatViewModel.kt:557` (`handleStartTimer`) and `:577`
  (`handleCreateTask`).
- Item 4 attaches to `ChatScreen.kt:207–225` + `ChatViewModel.kt:361–377`
  (clear-chat dialog).
- Item 5 attaches to `PasteConversationViewModel.kt:126–130` (`reset()`).

## § Item 1 — POST_NOTIFICATIONS request lift

**Surface:** `OnboardingScreen.kt:1656–1681` (`NotificationsPage` Composable
hosts a `rememberLauncherForActivityResult` that fires the runtime permission
ask via a `LaunchedEffect(Unit)` block on first composition).

**Cross-reference:** `MainActivity.kt:291–316` already runs the canonical
request: a Compose-side launcher inside `setContent {}` plus a
`LaunchedEffect(Unit)` that calls `notificationPermissionLauncher.launch(
Manifest.permission.POST_NOTIFICATIONS)` when `checkSelfPermission` returns
`PERMISSION_DENIED`. The MainActivity version also wires a snackbar that
explains the consequence of denial.

The OnboardingScreen comment at `:1652` already concedes the relationship:
*"`MainActivity.kt` keeps a re-check for users who skipped the page entirely."*
The duplication exists because the onboarding ask was added first; the
MainActivity fallback was added second; nobody removed the onboarding copy.

**Verdict: GREEN.** Delete the `permissionLauncher` and its driving
`LaunchedEffect` from `NotificationsPage`. Update the comment header to reflect
that MainActivity is now the single source of truth — the permission is now
requested once on first cold launch regardless of whether the user reaches the
onboarding NotificationsPage. ~15 LOC delete.

**STOP-1A:** N/A (verdict GREEN, well under 50 LOC).
**STOP-1B:** N/A (the duplicate is real — work is not already done).

**Behavior preservation:** New users still get the prompt (MainActivity fires
on first launch, before HorizontalPager is even composed). Existing users who
already accepted/denied won't be re-prompted (`checkSelfPermission` short-
circuits). The "consent context" benefit the original onboarding comment cited
is lost, but the snackbar in MainActivity covers the denial-feedback case.

## § Item 2 — Onboarding page trim

`OnboardingScreen.kt` declares `TOTAL_PAGES = 16` and dispatches each index in
the `when` at `:129–151`. Two pages flagged for trim:

### ViewsPage (index 8, `OnboardingScreen.kt:783–830`)

Pure informational. Three cards (Today / Week / Month) inside an
`OnboardingPageLayout` with the body text *"Today focus, week planner,
calendar, timeline, and Eisenhower matrix. Your tasks, your view."* No state
captured, no preference written, no interactivity beyond the page-flip.
Discoverability of the Today / Week / Calendar surfaces is already covered by
the bottom navigation that the user lands on immediately after onboarding.

**Verdict: DROP.** No critical preference attached, no other onboarding page
covers this content but other onboarding pages don't *need* to — the post-
onboarding bottom nav is the discovery surface. ~48 LOC delete.

### AiOverviewPage (index 11, `OnboardingScreen.kt:1478–1575`)

Pure informational. Four AI buckets (Capture / Plan / Reflect / Protect) with
emoji + tier chip + description. Body text claims *"You can disable any of it
on the next step"* — but the next step (index 12) is `PrivacyPage`, **not** the
LifeModes AI-features toggle which lives at index 6. The body's promise is
already broken on disk. `LifeModesPage` at `:1239` already hosts the `AI
Features` `LifeModeRow` that captures the user's opt-in/opt-out choice; the
overview marketing duplicates context the user has already consumed.

**Verdict: DROP.** ~98 LOC delete.

**Renumbering:** `TOTAL_PAGES` 16 → 14, `LAST_PAGE_INDEX` 15 → 13. Index map
after the drop:

| New | Old | Page                |
|----:|----:|---------------------|
| 0   | 0   | WelcomePage         |
| 1   | 1   | ThemePickerPage     |
| 2   | 2   | SmartTasksPage      |
| 3   | 3   | ProjectsPage        |
| 4   | 4   | NaturalLanguagePage |
| 5   | 5   | HabitsPage          |
| 6   | 6   | LifeModesPage       |
| 7   | 7   | TemplatesPage       |
| 8   | 9   | BrainModePage       |
| 9   | 10  | AccessibilityPage   |
| 10  | 12  | PrivacyPage         |
| 11  | 13  | NotificationsPage   |
| 12  | 14  | DaySetupPage        |
| 13  | 15  | SetupPage (LAST)    |

**STOP-2A:** N/A — neither dropped page captures a preference.
**STOP-2B:** Verified — only `TOTAL_PAGES` and `LAST_PAGE_INDEX` constants
reference page count, no other index hard-coded. The `when` block is the
only dispatch site.
**STOP-2C:** N/A — RESHAPE not selected; both pages are clean DROPs.

Total Item 2: ~150 LOC delete (page bodies + `when` reshuffle + constant flip).

## § Item 3 — Chat B.3 recurrence + B.4 timer duration (NLP-parse path)

This item has the most architectural friction in the bundle. STOP-3B and
STOP-3C both fired during Phase 1; the operator was surfaced via
AskUserQuestion and chose **"Ship both via NLP parse"** despite the
hygiene/feature-build mismatch flag. The verdict is therefore SHIP, with the
caveats below documented for Phase 4 follow-on tracking.

### B.3 — Recurrence on chat-driven `create_task`

`ChatActionResponse` (`ApiModels.kt:589–609`) does not carry a `recurrence`
field. The prompt forbids backend changes, so the field cannot be added on
the wire. The chosen path is to pipe `action.title` through the existing
`NaturalLanguageParser.parse()` after the structured-field extraction
completes, then resolve any `recurrenceHint` via `ParsedTaskResolver`, then
apply via `taskRepository.addTask(recurrenceRule = json)` — mirroring exactly
what `PasteConversationViewModel.createSelected()` already does.

**Inherent limitation:** the AI strips recurrence wording from the title
before emitting `create_task`, so a clean *"take meds"* title yields no
recurrence even when the user said *"every day at 9am"*. The fix only catches
cases where the AI leaves the recurrence keyword inside the title text. The
operator accepted this limitation when choosing this path.

`ChatViewModel` constructor adds `NaturalLanguageParser` + `ParsedTaskResolver`
injections. ~40 LOC.

### B.4 — Timer duration deep-link

`handleStartTimer:557–564` already emits `ChatNavEvent.OpenTimer(minutes =
action.minutes)`. The screen-side handler at `ChatScreen.kt:150–151` drops the
minutes — it just calls `navController.navigate(PrismTaskRoute.Timer.route)`.
The dropping is documented as deliberate in the ViewModel comment at
`:532–540`: *"the timer screen reads its duration from user preferences and we
don't want an AI-suggested duration to silently override the user's
configured length."*

Per operator override, we treat the comment as stale and plumb minutes through
**without** persisting them — the AI suggestion sets the in-flight session
duration only, never writes to `TimerPreferences`. This matches the spirit of
the original concern (no silent persistent override) while still honoring the
chat command.

Implementation:

- `PrismTaskRoute.Timer` becomes `data object Timer : PrismTaskRoute("timer")`
  with a new `createRoute(minutes: Int? = null)` returning `"timer"` or
  `"timer?minutes=N"` and the `arguments = listOf(navArgument("minutes") {
  type = IntType; defaultValue = -1 })` declaration on the composable wiring
  in `NavGraph.kt`.
- `TimerScreen` reads the optional `minutes` from its `NavBackStackEntry` and,
  if non-negative, calls a new `viewModel.applySuggestedDurationMinutes(n)`
  in a one-shot `LaunchedEffect`.
- `TimerViewModel.applySuggestedDurationMinutes(minutes)` updates `_uiState`
  in CUSTOM mode and resets `remainingSeconds` — **does not** write through
  `timerPreferences`. The user's persisted custom duration stays intact.
- `ChatScreen.kt:150–151` switches to `PrismTaskRoute.Timer.createRoute(
  event.minutes?.takeIf { it in 1..480 })`. The bounds match the existing
  ViewModel snackbar guard.

~50 LOC across 4 files.

**STOP-3A:** Combined Item 3 LOC ≈ 90 — under the 400 threshold.
**STOP-3B:** Verified — none of the changes touch the PR #1216 tool_use
protocol invariants. `ChatActionResponse` is unchanged on the wire; the
parse-the-title fallback runs purely client-side after the action arrives.
**STOP-3C:** Operator-overridden. NLP parsing extension is ≤40 LOC (re-using
the existing `NaturalLanguageParser` + `ParsedTaskResolver` pipeline). Filed
as a follow-on observation: the limitation that AI strips recurrence wording
from titles caps the practical reach of this fix.

## § Item 4 — Clear-chat 'Don't ask again' toggle

**Surface:** `ChatScreen.kt:207–225` (the `AlertDialog` for clear-chat
confirm, gated by `viewModel.showClearConfirm`).

Implementation:

- New pref `KEY_CHAT_CLEAR_SKIP_CONFIRMATION` (booleanPreferencesKey,
  default `false`) in `UserPreferencesDataStore` with `chatClearSkipConfirmation()`
  flow + `setChatClearSkipConfirmation(value)` setter.
- `ChatViewModel.requestClearConversation()` reads the pref synchronously
  via the existing `userPreferencesDataStore` injection. When `true`, skip
  the `_showClearConfirm` flag and call `clearConversation()` directly.
  When `false`, behavior is unchanged from PR #1168 C.3.
- `ChatScreen.kt` adds a `Checkbox` row inside the dialog body. Tapping
  Confirm with the box checked persists the pref before clearing.

**Reset path:** None of the existing Settings sections currently host a
"reset chat preferences" affordance. Per the prompt's explicit authorization
*"if no such Settings affordance exists, defer reset path to follow-on"*,
the reset path is paper-deferred to F-series (re-trigger criteria: when a
Settings reset button is added for any other always-confirm dialog, fold this
toggle into the same surface).

~50 LOC.

**STOP-4A:** Resolved by ship-without-reset, deferred reset path documented.
**STOP-4B:** Verified — the C.3 dialog is exclusive to clear-chat; no other
clear-style action shares it.

**Test impact:** `ChatViewModelActionTest:443–453` exercises
`requestClearConversation` + `showClearConfirm` directly. The test predates
the toggle and assumes pref = false. New behavior preserves that path; new
test adds the pref=true skip path.

## § Item 5 — `PasteConversationViewModel.reset()` destructive affordance

**Critical finding:** `grep -rn "PasteConversationViewModel|\\.reset()"
app/src/main/` confirms `viewModel.reset()` is **never called** from any UI
surface, NavGraph hook, or background job. The only references are the class
declaration itself, the NavGraph comment block at `:380–386`, and
`hiltViewModel()` injection at `PasteConversationScreen.kt:43`. The
`PasteConversationScreen` body never reaches into `viewModel.reset()`.

The destructive affordance the original D13 ticket was concerned about
**does not exist on disk**. There is no risk profile to mitigate.

**Verdict: PAPER-CLOSE + dead-code delete.** Operator chose to delete the
dead method as part of the bundle. ~5 LOC delete.

**STOP-5A:** N/A — single (zero) call site profile.
**STOP-5B:** N/A — `reset()` only touches `MutableStateFlow` in-memory state
even if it were called.
**STOP-5C:** N/A — no FIX-UNDO path needed.

## § Bundle-decision

**PR shape:** single bundle PR, per operator pre-lock. No Phase 1 finding
argues for split.

**Implementation order (smallest-first per prompt):**

1. Item 1 — POST_NOTIFICATIONS lift (~15 LOC delete)
2. Item 5 — delete dead `reset()` method (~5 LOC delete)
3. Item 2 — drop ViewsPage + AiOverviewPage + reshuffle (~150 LOC delete)
4. Item 4 — Don't ask again toggle (~50 LOC add)
5. Item 3 — B.3 NLP recurrence + B.4 minutes plumb (~90 LOC add)
6. Tests — ChatViewModel pref-bypass test, B.4 plumbing test
7. Audit doc commit (this file)

**Cross-item dependencies:** none. Item 4 and Item 3 both modify
`ChatViewModel.kt` but on disjoint regions (Item 4 in `requestClearConversation`,
Item 3 in `handleCreateTask` + constructor injections). Items 1, 2 land in
`OnboardingScreen.kt`. Item 5 lands in `PasteConversationViewModel.kt`.
File-level overlap on `ChatViewModel.kt` and `OnboardingScreen.kt` is
intentional — disjoint regions, clean diffs.

**Total LOC estimate:** ~310 LOC production + tests, well under the 700 / 1000
escalation thresholds. No mandatory split.

**Test parity rule:** no new DAOs introduced — TestDatabaseModule unchanged.

**D13 closure:** if all 5 items land cleanly, D13 reaches 8/8 closed entirely
pre-Phase-F. Item 3 ships with a documented limitation (AI strips recurrence
wording) — counted as 1.0 closure with a follow-on observation, not a partial.

**Phase F GREEN-GO impact:** NEUTRAL per item; bundle's contribution is
POSITIVE via hygiene closure (D13 reaches 0 open items pre-launch).
