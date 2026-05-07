# Onboarding Holistic Redesign — Phase 1 Audit

**Branch:** `claude/onboarding-holistic-redesign-Z47lp`
**Date:** 2026-05-07
**Scope (operator-clarified):** Drop only redundant pages from the existing 17-page
onboarding (a few at most), and add a dismissible Guided Tour card on Today
that surfaces breadth post-completion. Android only. PR #844 unified
completion contract preserved unchanged.

This is a deliberate **reframe** from the prompt's original "3-4 must-see
screens" framing. Operator clarified during Phase 1 that they wanted a
small trim plus the tour card, not a from-scratch replacement. § F below
designs against that reframed scope.

---

## § A. Current Onboarding Inventory

### A.1 NavGraph entry / exit

`app/src/main/java/com/averycorp/prismtask/ui/navigation/NavGraph.kt:563`
gates the start destination on `hasCompletedOnboarding`:

```kotlin
val startDest = if (hasCompletedOnboarding) PrismTaskRoute.MainTabs.route
                else PrismTaskRoute.Onboarding.route
```

The `Onboarding` route at `NavGraph.kt:570-585` hosts `OnboardingScreen` and
on completion navigates to `MainTabs.route` with `popUpTo(Onboarding) { inclusive = true }`.
`PrismTaskRoute.Onboarding` is defined at `NavGraph.kt:188`.

### A.2 Single-screen flow (17 pages via HorizontalPager)

`app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingScreen.kt`
(2193 lines). `TOTAL_PAGES = 17`, dispatched at lines 129-153:

| Page | Composable                | What it writes (via OnboardingViewModel)                                                  |
|------|---------------------------|-------------------------------------------------------------------------------------------|
| 0    | WelcomePage               | Sign-in (Google / email); may early-exit via `checkExistingUserAndMaybeSkip()`            |
| 1    | ThemePickerPage           | `themePreferences.setThemeMode()`                                                         |
| 2    | SmartTasksPage            | — pure copy                                                                               |
| 3    | ProjectsPage              | — pure copy *(added PR #1161, fa8053e)*                                                   |
| 4    | NaturalLanguagePage       | — pure copy                                                                               |
| 5    | HabitsPage                | `setForgivenessStreaksEnabled`, `setStreakMaxMissedDays` (forgiveness tuning)             |
| 6    | LifeModesPage             | `set{SelfCare,Medication,School,Housework,Leisure}Enabled` (life-category tracking)       |
| 7    | TemplatesPage             | In-memory `_templateSelections`; applied at completion via `applyTemplateSelections()`    |
| 8    | ViewsPage                 | — pure copy                                                                               |
| 9    | BrainModePage             | `set{Adhd,Calm,FocusRelease}Mode` (ND-friendly modes — distinct from LifeModes)           |
| 10   | AccessibilityPage         | `set{ReduceMotion,HighContrast,LargeTouchTargets}`                                        |
| 11   | AiOverviewPage            | — pure copy *(added PR #1161, fa8053e)*                                                   |
| 12   | PrivacyPage               | `setVoiceInputEnabled`, `setAiFeaturesEnabled`                                            |
| 13   | NotificationsPage         | 6 notification toggles + inline `POST_NOTIFICATIONS` permission request (lines 1669-1678) |
| 14   | DaySetupPage              | `setStartOfDay(hour, minute)` + flips `HAS_SET_START_OF_DAY = true`                       |
| 15   | ConnectIntegrationsPage   | — pure copy, no writes, no actions; just info cards pointing to Settings                  |
| 16   | SetupPage                 | Final — fires `viewModel.completeOnboarding()` + `onComplete()` nav                       |

Page composable line numbers: WelcomePage:250, SmartTasksPage:398, ProjectsPage:444,
NaturalLanguagePage:504, HabitsPage:572, TemplatesPage:697, ViewsPage:784, BrainModePage:834,
SetupPage:1040, LifeModesPage:1239, AccessibilityPage:1390, PrivacyPage:1578,
NotificationsPage:1656, DaySetupPage:1771, ConnectIntegrationsPage:1862, ThemePickerPage:2041.

### A.3 Skip behaviour (verified)

`OnboardingScreen.kt:157-167`. Single "Skip" button visible on pages 0..15
(hidden on SetupPage = 16). One tap jumps to LAST_PAGE_INDEX:

```kotlin
if (pagerState.currentPage < LAST_PAGE_INDEX) {
    TextButton(onClick = {
        coroutineScope.launch { pagerState.animateScrollToPage(LAST_PAGE_INDEX) }
    }, ...) { Text("Skip") }
}
```

### A.4 ViewModel — `OnboardingViewModel.kt`

`app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingViewModel.kt`
(503 lines). Injects 16 dependencies. Two completion entry points:

1. **`checkExistingUserAndMaybeSkip()` (lines 212-257)** — fires after sign-in.
   Looks up `users/{uid}/tasks` in Firestore; if non-empty, signals
   `ExistingUserDetected` and writes `setOnboardingCompleted(completedAt)` +
   `canonicalOnboardingSync.writeCompletedAt(uid, completedAt)`. UI navigates
   straight to MainTabs.
   *Comment at lines 224-232 documents the SoD skip-race write-ordering invariant.*
2. **`completeOnboarding()` (lines 384-417)** — fires from SetupPage.
   Writes `setOnboardingCompleted(completedAt)` + canonical mirror, then runs
   `applyTemplateSelections()` for non-critical follow-up.

### A.5 DataStore keys — `OnboardingPreferences.kt`

`app/src/main/java/com/averycorp/prismtask/data/preferences/OnboardingPreferences.kt`:
- `has_completed_onboarding` (Boolean) — gates NavGraph startDest
- `onboarding_completed_at` (Long ms)
- `has_shown_battery_optimization_prompt` (Boolean) — guards Samsung dialog in MainActivity

Onboarding-adjacent key elsewhere:
- `task_behavior` DataStore: `HAS_SET_START_OF_DAY` (gates MainActivity Start-of-Day prompt at lines 374-405).

PR #952 (Apr 2026) deletion verified: `KEY_UI_TIER` and `KEY_TIER_ONBOARDING_SHOWN`
are gone from `app/src/main` (only remaining reference is a fixture key inside
`app/src/androidTest/.../GenericPreferenceSyncServiceEmulatorTest.kt:270` —
defined locally in the test, not production).

### A.6 Theme-onboarding selector

ThemePickerPage (page 1, lines 2041+) lives **inside** the onboarding flow.
Theme selection also exists in Settings; the onboarding page is the
first-pass picker. No standalone "theme onboarding selector" exists outside
this page.

### A.7 RestorePending takeover

`app/src/main/java/com/averycorp/prismtask/ui/screens/auth/AuthScreen.kt:75`
+ `AuthViewModel.kt:31,144`. RestorePending is an **AuthState**, not an
onboarding state — it fires from the Auth screen's account-status check
when an account has a pending-deletion timer. RestorePending users do NOT
flow through `OnboardingScreen`; they're routed to `RestoreAccountScreen`
from AuthScreen. The redesign does not touch this path.

### A.8 Test coverage

- `app/src/test/.../OnboardingViewModelTest.kt`: 3 trivial tests covering
  `SignInState` only (NotSignedIn / SignedIn / Error). No preference-write
  coverage, no `completeOnboarding()` coverage.
- No androidTest suite specifically covers the onboarding flow.

### A.9 DebugOnboardingSection

`app/src/main/java/com/averycorp/prismtask/ui/screens/settings/sections/DebugOnboardingSection.kt`
(42 lines). One action: "Show Tutorial Again" → callback into Settings
`onShowTutorial` → `onboardingPreferences.resetOnboarding()` clears all 3
keys. Must keep working post-redesign (and ideally also reset the new tour
card flags).

---

## § B. Current First-Run Journey (Cold-Start)

1. Cold launch → MainActivity onCreate → DataStore reads
   `hasCompletedOnboarding` (initialValue null shows splash spinner).
2. Once value emits → false → NavGraph startDest = Onboarding.
3. User lands on `WelcomePage` (page 0). Three options:
   - Tap **Sign in with Google / email** → AuthManager call → if existing
     user, `checkExistingUserAndMaybeSkip()` flips completion flag and
     `ExistingUserDetected` triggers `onComplete()` → MainTabs.
   - Tap **Skip** → animate-scroll to page 16 (SetupPage).
   - Tap **continue** → page 1 (ThemePicker).
4. Pages 1..15 — user reads / toggles / skips. Page indicators at the
   bottom; Skip in top-right.
5. SetupPage (page 16) → tap **Get Started** (or sign-in card here) →
   `viewModel.completeOnboarding()` writes flag + canonical mirror +
   applies template selections → `onComplete()` navigates to MainTabs
   with popUpTo(Onboarding, inclusive).
6. MainActivity LaunchedEffects fire: notification permission prompt
   (API 33+, lines 301-311), exact-alarm dialog (API 31-32), Samsung
   battery-optimisation dialog, Start-of-Day picker dialog (only if not
   already set during onboarding), canonical Firestore hydrate.
7. User lands on `MainTabs` → Today tab. RichEmptyState renders ("Nothing
   Planned for Today").

---

## § C. PR #844 Unified Completion Contract — Verbatim

The contract is **`OnboardingViewModel.completeOnboarding()` at lines 384-417**
plus its sibling `checkExistingUserAndMaybeSkip()` at lines 212-257. Both
write the same pair of things:

1. **Local DataStore:**
   `onboardingPreferences.setOnboardingCompleted(timestampMs)` →
   writes `HAS_COMPLETED_ONBOARDING = true` and `ONBOARDING_COMPLETED_AT = ts`
   atomically into `onboarding_prefs`.

2. **Cross-platform canonical Firestore field:**
   `canonicalOnboardingSync.writeCompletedAt(uid, timestampMs)` →
   writes `users/{uid}.onboardingCompletedAt = ts` with `merge: true`.
   Best-effort; failure is logged and does not block.

**Observers:**
- `MainActivity` `hasCompletedOnboarding` collector (lines 242-244,
  549-563) — gates loading vs. NavGraph render.
- `NavGraph.PrismTaskNavGraph` (line 354 param + line 563 startDest).
- `MainActivity` Start-of-Day backfill LaunchedEffect (lines 374-405) keys
  off this signal.
- `MainActivity` canonical-hydrate LaunchedEffect (lines 520-529) — pulls
  `users/{uid}.onboardingCompletedAt` from Firestore on sign-in and stamps
  the local mirror via `OnboardingPreferences.hydrateFromCanonicalCloud()`.
- Web client (per `CanonicalOnboardingSync.kt:21` comment) reads/writes the
  same Firestore field.

**Redesign requirement:** the new flow must call `completeOnboarding()`
(unchanged) at the end of the must-see flow. No code outside
`OnboardingViewModel` writes either of these two stores; the contract is
hermetic to that ViewModel and stays so.

---

## § D. Today-Screen Tour-Card Landing Site

`app/src/main/java/com/averycorp/prismtask/ui/screens/today/TodayScreen.kt`
renders a LazyColumn (lines 302-809) with the following early-render slots
above the task sections:

- 310-325 `RichEmptyState` (when `nothingToday && !allTodayDone`)
- 327-336 `AllCaughtUpCard`
- 338-364 `EnergyCheckInCard`
- 366-380 `TodayBalanceSection` + `CognitiveLoadSection`
- 382-401 `MorningCheckInBanner` (dismissible — see pattern below)
- 404-413 `SelfCareNudgeCard` (dismissible)
- 418-433 `OverloadBanner`
- 439-507 quick action chips row

**Recommended landing slot:** new `GuidedTourCard` item rendered between
the OverloadBanner (line 433) and the quick action chips (line 439). Sits
above the user's main task sections but below higher-priority status banners
(check-in, balance, overload).

**Existing dismissible patterns to model on:**

- `MorningCheckInPreferences.kt` (per-day banner dismissal via ISO date key).
- `DailyEssentialsPreferences.kt` (`HAS_SEEN_HINT` boolean — one-time
  permanent dismissal). **Closest match for the tour card.**

**DataStore key — proposed:** new file
`data/preferences/TourCardPreferences.kt` with DataStore name
`tour_card_prefs`. Keys:
- `tour_card_eligible` (Boolean) — set true only when the user finishes
  onboarding by reaching SetupPage. Returning-user skip-path
  (`checkExistingUserAndMaybeSkip`) does NOT set this. Default false.
- `tour_card_dismissed` (Boolean) — set true on Got it / Don't show again /
  step-N completion. Default false.
- `tour_step_index` (Int) — 0..N. Default 0.

Verified no collisions with existing keys (grep across
`data/preferences/`).

---

## § E. Empty Today State Audit

`RichEmptyState` (rendered at TodayScreen.kt:310-325) shows:

- Icon: ☀️
- Title: **"Nothing Planned for Today"**
- Body: **"That's fine — rest is productive too."**
- Primary CTA: **"Plan Your Day"** → opens plan sheet
- Secondary CTA: **"Create a Task"** → opens task editor sheet

**Verdict:** Acceptable as a landing target. The forgiveness-first voice
("rest is productive too") matches the audit's redesign tone. **No
empty-state polish needed in this PR.** The tour card will give first-run
users action prompts on top of the empty state.

---

## § F. Proposed Redesign

### F.1 — Pages to drop

**One drop:** `ConnectIntegrationsPage` (page 15).

**Rationale:**
- Pure-copy info card — writes nothing.
- Both surfaced integrations (Google Calendar two-way sync, Drive backup)
  are off by default and require Settings → Calendar / Settings → Data &
  Backup navigation to enable. The page does not deep-link, grant scopes,
  or initiate any flow.
- Its content is functionally a tour-card hint ("integrations exist, find
  them in Settings"), which the new tour card surfaces with equivalent
  reach.
- Lowest-value page in the breadth tour by this measure: zero behaviour
  change for users who skip it, and no missing setup step for users who
  read it.

**Pages NOT dropped (decision rationale, brief):**
- Pages 2, 3, 4, 8, 11 — pure-copy showcases of unique features (Smart
  Tasks, Projects, NLP, Views, AI Overview). These directly serve the
  operator-locked "feature breadth overview" goal. Pages 3 and 11 are
  brand-new (PR #1161, fa8053e); deleting them would discard recent
  work-product.
- Pages 5, 6, 9, 10, 12, 13, 14 — write distinct preferences. Removal
  would either lose the configuration step or push it to first-use, which
  is out of scope.
- Page 7 (Templates) — applies non-trivial seeding choices via
  `applyTemplateSelections`. Operator-locked fact (4) says no auto-seeding,
  but the user *opting into* templates here is user choice, not auto-seed.
  Keep.

**New TOTAL_PAGES:** 16 (was 17). All page indices in OnboardingScreen.kt
re-numbered: 15 becomes the new SetupPage (was 16). The pure rename is
local to the `when (page)` block at lines 129-153.

### F.2 — Guided Tour Card

**Composable:** new file
`app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/GuidedTourCard.kt`.
Rendered between OverloadBanner and quick-action chips in TodayScreen
LazyColumn. Card model = `Card` with:

- Icon (left, theme-aware)
- Step title (titleSmall, bold)
- Step body (bodySmall)
- Step counter "Step N of K" (labelSmall, muted)
- Two buttons: **Got it** (primary; advances to next step or dismisses
  on last step) and **Don't show again** (text button; dismisses
  permanently).
- "Skip tour" affordance (text button at top-right of card) — same effect
  as Don't show again.

**State backing:** `TourCardPreferences` (per § D). Visibility flow in
`TodayViewModel`:

```kotlin
val tourCard: StateFlow<TourCardState?> = combine(
    tourCardPreferences.eligible(),
    tourCardPreferences.dismissed(),
    tourCardPreferences.stepIndex()
) { eligible, dismissed, step ->
    if (!eligible || dismissed) null
    else TourCardState(step = step, totalSteps = TOTAL_STEPS)
}.stateIn(...)
```

Where `eligible` is the gate that prevents the card from showing for
returning users (RestorePending or `checkExistingUserAndMaybeSkip`). It is
flipped to `true` only by the SetupPage path
(`OnboardingViewModel.completeOnboarding()`).

**Step content (5 steps, forgiveness-first voice):**

| # | Title                       | Body                                                                                  |
|---|-----------------------------|---------------------------------------------------------------------------------------|
| 1 | Quick Add anything          | Type or speak a task. Try "Buy milk tomorrow at 4pm #shopping" — dates and tags parse. |
| 2 | Tasks, habits, and meds     | Tabs along the bottom. Habits use forgiving streaks — a missed day won't punish you.  |
| 3 | Focus when you need it      | Open the Timer tab for Pomodoro sessions tuned to your energy.                        |
| 4 | AI assistant — optional     | Settings → AI to turn it on. Off by default. Lives where you are, never pushes.       |
| 5 | Make it yours               | Themes, widgets, integrations, exports — all in Settings.                             |

**Auto-hide:** card hides when `step >= TOTAL_STEPS` (last step's "Got it"
sets `dismissed = true`) OR when user taps "Don't show again" at any step.
No "seen N times" cap — operator's "persists until dismissed or all tour
steps are seen" framing maps exactly to those two terminal states.

**RestorePending exclusion:** since `tour_card_eligible` is only set by
`completeOnboarding()` (which the `checkExistingUserAndMaybeSkip` path
deliberately bypasses), returning users on a fresh install never see the
card. Confirmed against `OnboardingViewModel.kt:212-257`.

### F.3 — Strings & content

All strings English-only (i18n out of scope). Voice: warm, non-judgmental.
No "crush your goals" or "10x productivity" language. ADHD-aware framing
where natural ("missed day won't punish you" — no special tokenization).
Operator review of copy in Phase 2 PR description; non-blocking.

### F.4 — Permissions deferral

**Inline permissions inside the new flow: zero changed.**
- `NotificationsPage` (page 13) keeps its inline `POST_NOTIFICATIONS`
  request. MainActivity's defensive request at lines 301-311 still fires
  for Skip-button users. This dual-path is current behaviour and the
  operator-locked "permissions out of must-see flow" rule was scoped to
  the new screens; existing pages keep their semantics under the
  reframed scope.
- Exact-alarm and Samsung battery-optimisation dialogs continue to live
  in MainActivity, post-completion.
- **Recommendation for follow-up (not this PR):** consider lifting the
  inline permission request out of `NotificationsPage` and consolidating
  on MainActivity's path so the page is a pure preference toggle. Filed
  in § G as a follow-up.

### F.5 — Empty Today landing

No-op (per § E). RichEmptyState is good as-is.

---

## § G. Out-of-Scope Explicit List

- Web onboarding — unchanged. PR #844 canonical contract preserves
  cross-platform completion via the unchanged `users/{uid}.onboardingCompletedAt`
  Firestore field.
- RestorePending flow — unchanged.
- Pro/Premium tier positioning — unchanged.
- Permissions sequencing inside the new flow — unchanged (see § F.4).
- Sample/demo data seeding — none added; no existing seeding removed
  beyond what the dropped page already didn't do (it was pure copy).
- Telemetry on onboarding funnel — not added.
- i18n — English only.
- Theme polish beyond keeping ThemePickerPage as page 1.
- Lifting POST_NOTIFICATIONS request out of NotificationsPage —
  recommended as a follow-up; not in this PR.
- Aggressive page reduction (3-4 must-see + tour) — explicitly reframed
  by operator during Phase 1; we do a small drop instead.

---

## § H. LOC Estimate + Risk Register

### LOC

| Component                                            | Est. lines    |
|------------------------------------------------------|---------------|
| Drop ConnectIntegrationsPage + renumber dispatch     | -180 (delete) |
| TourCardPreferences.kt                               | ~120          |
| GuidedTourCard.kt composable                         | ~180          |
| TodayViewModel wiring (1 inject + 1 flow + 2 fns)    | ~25           |
| TodayScreen item slot                                | ~10           |
| OnboardingViewModel.completeOnboarding tour-eligible flip | ~10      |
| DebugOnboardingSection — also reset tour flags       | ~5            |
| Hilt module update for TourCardPreferences (if needed) | ~0 (constructor-injected via @Singleton, no module change) |
| Unit tests (TourCardPreferences, ViewModel state)    | ~120          |
| **Net**                                              | **~290 lines** (impl + test, after the 180-line delete) |

**Risk-of-1.5×-overrun triggers:** rewriting an existing dismissible card
to share infrastructure, plumbing tour state through Hilt-DataStore boilerplate
beyond the established pattern. Mitigation: copy `DailyEssentialsPreferences`
directly as the template — its DataStore + flow + setter shape is the
exact analog.

### Risk register

- **R1:** Returning-user gate via `tour_card_eligible` requires the new
  flag to be flipped exclusively in `completeOnboarding()`, NOT in
  `checkExistingUserAndMaybeSkip()`. If a future commit accidentally
  flips it in both paths, returning users will see the tour. Mitigation:
  unit test `OnboardingViewModelTest` that asserts `tour_card_eligible`
  remains false after the existing-user skip path.
- **R2:** Tour card shows above task sections — competes for attention
  with MorningCheckInBanner / OverloadBanner. If both fire on day 1, the
  user sees a stack. Acceptable: each banner is dismissible; tour is the
  bottom-most so check-in/overload still take priority.
- **R3:** `applyTemplateSelections` runs in `completeOnboarding`. Dropping
  ConnectIntegrationsPage doesn't touch this path. No risk.
- **R4:** Database migrations: not required. New DataStore is a fresh
  preferences file, no Room schema change.
- **R5:** Sync wiring: tour state is device-local. Not synced. No
  Firestore mirror added. Confirmed in PR description.
- **R6:** Detekt / ktlint: per PR #1062 lesson, run both pre-PR-ready.
  GuidedTourCard composable likely to hit the long-arg-list rule if the
  step content list is inlined; pre-mitigate by hoisting the step list
  into a `@Stable` data class at file top.

---

## § I. Operator Decision Points (defaults picked, recap in Phase 4)

1. **Number of pages dropped:** picked **1** (ConnectIntegrationsPage). Could
   reasonably extend to also drop ViewsPage (8) or AiOverviewPage (11) but
   both surface unique features and AiOverviewPage is brand-new (PR #1161).
   Recommend operator review post-ship and trim further later if desired.
2. **Tour step count:** picked **5**. Range was 4-6. Five fits the
   breadth-of-app coverage (quick-add, tasks/habits/meds, timer, AI,
   settings) without padding.
3. **Tour visibility cap:** picked **persist-until-dismissed**, no
   "seen N times" cap. Both terminal states (Got-it on last step,
   Don't-show-again on any step) flip the same `dismissed` flag.
4. **DataStore for tour state:** picked **new file
   `TourCardPreferences`** with name `tour_card_prefs`, not extending
   `OnboardingPreferences`. Keeps onboarding completion contract
   hermetic; simplifies the export/import round-trip (DataExporter doesn't
   need a new field for tour state since it's device-local).
5. **Tour card location:** picked **between OverloadBanner and quick
   action chips** on TodayScreen. Could go above MorningCheckInBanner
   for higher visibility but that subordinates daily check-in to a one-
   time tour; not worth the trade.

---

## STOP-condition disposition

No Phase 1 STOP-conditions hit. PR #844 contract verified intact and
unchanged. Operator-locked facts (1)–(6) all satisfied by the design in
§ F. Proceeding to Phase 2.
