# Onboarding Overlap Audit

**Date:** 2026-05-16
**Scope:** Find places in the user-facing onboarding journey where two surfaces ask the same question, teach the same concept, or write the same preference — including the post-onboarding tour stack.
**Method:** Static read of all 16 pager pages in `OnboardingScreen.kt`, both post-onboarding tour systems (`GuidedTourCard` + `CoachmarkTour`), `OnboardingPreferenceMapper.resolve`, and `OnboardingViewModel.applyTuningSelections`.

---

## Surface inventory

Numbering matches the pager order in `OnboardingScreen.kt:138-161`.

| # | Page / surface | File | Key prefs written |
|---|---|---|---|
| 0 | WelcomePage (sign-in) | `OnboardingScreen.kt:258` | sign-in state (transient) |
| 1 | HowWeThinkPage (philosophy) | `OnboardingScreen.kt:421` | none |
| 2 | ThemePickerPage | `OnboardingScreen.kt:2066` | `ThemePreferences.themeMode`, `accentColor` |
| 3 | SmartTasksPage (informational) | `OnboardingScreen.kt:470` | none |
| 4 | ProjectsPage (informational) | `OnboardingScreen.kt:516` | none |
| 5 | NaturalLanguagePage (informational) | `OnboardingScreen.kt:576` | none |
| 6 | HabitsPage (streaks + forgiveness toggle) | `OnboardingScreen.kt:644` | `ForgivenessPrefs.enabled`, `streakMaxMissedDays` |
| 7 | LifeModesPage (5 mode toggles) | `OnboardingScreen.kt:1445` | `HabitListPreferences.*Enabled` |
| 8 | TemplatesPage | `OnboardingScreen.kt:769` | `templateSelections`, `templates_seeded` |
| 9 | BrainModePage (3 ND mode toggles) | `OnboardingScreen.kt:856` | `NdPreferencesDataStore.KEY_ADHD_MODE`, `KEY_CALM_MODE`, `KEY_FOCUS_RELEASE_MODE` |
| 10 | TuningPage (5 tuning options) | `OnboardingScreen.kt:1072` | indirect via `OnboardingPreferenceMapper.resolve` → see § State-write conflicts |
| 11 | AccessibilityPage | `OnboardingScreen.kt:1596` | `A11yPreferences.REDUCE_MOTION`, `HIGH_CONTRAST`, `LARGE_TOUCH_TARGETS` |
| 12 | PrivacyPage (voice + AI opt-out) | `OnboardingScreen.kt:1684` | `VoicePreferences`, `UserPreferencesDataStore.aiFeaturesEnabled` |
| 13 | NotificationsPage (8 channel toggles) | `OnboardingScreen.kt:1759` | `NotificationPreferences.*Enabled` |
| 14 | DaySetupPage | `OnboardingScreen.kt:1902` | `TaskBehaviorPreferences.startOfDay`, `hasSetStartOfDay` |
| 15 | SetupPage (sign-in card + finish) | `OnboardingScreen.kt:1246` | onboarding-completed flags, `TourCardPreferences.markEligible()` |
| T1 | Post-onboarding Guided Tour Card (5 steps) | `GuidedTourCard.kt:53-79` | `TourCardPreferences.TOUR_CARD_*` |
| T2 | Post-onboarding Coachmark Tour (13 steps) | `CoachmarkTourContent.kt:85-168` | `TourCardPreferences.COACHMARK_TOUR_*` |

T1 and T2 both gate on `tour_card_eligible`, set when `SetupPage` finishes (`OnboardingViewModel.kt:604-607`). They are NOT mutually exclusive.

---

## Findings

### 1. Post-onboarding Guided Tour Card and Coachmark Tour fire simultaneously and teach near-identical content (RED)

**Premise verification.** Confirmed in code:
- `MainActivity.kt:666` calls `coachmarkController.tryStart(isProActive = true)` on first Today entry.
- `TodayScreen.kt:524-530` renders `GuidedTourCard` from `TodayViewModel.tourCard` state.
- Both gate on `TourCardPreferences.tourCardEligible` (`CoachmarkController.kt:42-44` + `TourCardPreferences.kt:48-94`). They check separate dismissal flags (`TOUR_CARD_DISMISSED` vs `COACHMARK_TOUR_DISMISSED`).
- Result: the new user finishes 16 onboarding pages and lands on Today with **both** an in-flow card (5 steps) **and** an overlay coachmark tour (13 steps) primed to fire.

**Content overlap.**

| Concept | Guided Tour Card | Coachmark Tour |
|---|---|---|
| Quick-add NLP demo | Step 1 — "Buy milk tomorrow at 4pm #shopping" | Step 2 — "Buy milk tomorrow at 4pm #shopping" (verbatim example) |
| Tab navigation overview | Step 2 — "Tasks, Habits, Leisure, Meds" | Steps 4-7 — individual `Tasks tab` / `Habits tab` / `Leisure` / `Medications` cards |
| Pomodoro / focus timer | Step 3 — "Focus when you need it … Short or long, your call" | Step 8 — "Focus timer … Short or long sessions" |
| AI assistant disclosure | Step 4 — "AI assistant — optional … off by default" | Step 3 — "AI helpers — optional … Off by default" + Step 9 (AI Coach FAB) |
| Themes / widgets / make it yours | Step 5 — "Make it yours … Themes, widgets, automations, integrations" | Step 11 (Appearance & widgets) + Step 12 (Automations) |
| Forgiveness framing | Step 2 — "streaks forgive a missed day" | Step 5 — "A missed day won't punish you" |

The comment at `CoachmarkTourContent.kt:82-83` literally acknowledges the duplication:
> "13 surfaces; voice & tone match existing GUIDED_TOUR_STEPS in today/components/GuidedTourCard.kt."

Phrasing was deliberately mirrored, but the redundancy was never resolved.

**Risk classification.** RED. Two parallel walkthrough systems that fire back-to-back after a 16-page pager is a clear philosophy violation of Principle 6 ("no locked defaults / no required tutorials") and of Principle 2 ("respect attention"). Users will hit ~34 instructional surfaces in their first session.

**Recommendation.** PROCEED — retire `GuidedTourCard` and keep the Coachmark Tour as the single post-onboarding walkthrough. The coachmark tour is a strict superset of the guided card's content and anchors to actual UI elements (more useful for orientation). The guided card lives only on the Today route and duplicates breadth without depth.

Retirement scope (single PR):
- Delete `GuidedTourCard.kt` + `GUIDED_TOUR_STEPS` + the Today-screen render block at `TodayScreen.kt:519-535`.
- Delete `TodayViewModel.advanceTourCard` + the `tourCard` flow + supporting calls into `TourCardPreferences` for `TOUR_CARD_DISMISSED` / `TOUR_STEP_INDEX`. Keep `TOUR_CARD_ELIGIBLE` — the coachmark tour reads it.
- Update tests: `GuidedTourCardTest`, `TodayViewModelTourCardTest`, any screenshot baselines.
- Leave persistence keys for `TOUR_CARD_DISMISSED` / `TOUR_STEP_INDEX` in `TourCardPreferences` as dead-but-safe (clears on next reset); no migration needed because they are local DataStore booleans.

### 2. TuningPage silently overrides explicit BrainMode toggles set on the previous page (RED)

**Premise verification.** Confirmed at `OnboardingViewModel.kt:400-435` and `OnboardingPreferenceMapper.kt:109-148`.

`applyTuningSelections` runs after the user finishes onboarding and writes only `setX(true)` — never `setX(false)`. The mapping table:

| TuningPage option | ND-mode flag forced to TRUE |
|---|---|
| LOSE_TRACK_OF_TIME | `adhdMode` |
| FEWER_ANIMATIONS_QUIETER_COLORS | `calmMode` + `reduceAnimations` + `mutedColorPalette` |
| OVER_POLISH | `focusReleaseMode` + `goodEnoughTimers` |
| LOW_ENERGY_DAYS | `ForgivenessPrefs.enabled` + `restDayPrimed` |
| OVERWHELMED_BY_LONG_LISTS | `compactMode` |

**Failure case.** User on page 9 (BrainModePage) explicitly turns OFF `adhdMode`. User on page 10 (TuningPage) picks "I lose track of time often". The TuningPage selection persists. On `completeOnboarding`, `applyTuningSelections` calls `setAdhdMode(true)`, silently re-enabling the mode the user just turned off, plus its sub-flags cascade.

The same write-through happens for:
- Page 9 `calmMode` toggle vs. page 10 `FEWER_ANIMATIONS_QUIETER_COLORS`.
- Page 9 `focusReleaseMode` toggle vs. page 10 `OVER_POLISH`.
- Page 6 `forgivenessStreaks` Switch vs. page 10 `LOW_ENERGY_DAYS`.

The KDoc at `OnboardingViewModel.kt:406-409` calls out the cascade ordering, but assumes Tuning is the **first** time these flags are touched. It is not — pages 6 and 9 already touched them.

**Risk classification.** RED. This is a state-management defect, not just UX redundancy: the app overwrites user-explicit choices without warning. Failure surfaces are silent (no error, no toast). Discovery happens only if the user notices, in Settings, that a toggle they set is in the wrong state.

**Recommendation.** PROCEED — two clean options, pick one:

(a) **Make TuningPage additive only.** Change `applyTuningSelections` to read the current value first and only flip a flag from its default to `true`, not from an explicit user `false` to `true`. Concretely: skip `setAdhdMode(true)` if `adhdMode` already has a non-default value (requires an "explicitly set" companion flag per pref, or a snapshot taken at BrainModePage commit).

(b) **Remove the brain-mode flag writes from TuningPage entirely.** Keep the Tuning page (it teaches sub-flags that aren't covered elsewhere — `compactMode`, `goodEnoughTimers`, `restDayPrimed`, `checkInIntervalMinutes`), but strip the `setAdhdMode` / `setCalmMode` / `setFocusReleaseMode` calls from `OnboardingPreferenceMapper.Result`. Sub-flag writes (reduceAnimations, mutedColorPalette, goodEnoughTimers, forgivenessStreaks) stay; the parent-mode cascade is what conflicts.

(b) is simpler, lower-risk, and surfaces a clearer mental model: "BrainModePage = which mode you're in; TuningPage = specific behaviors I want, independently". Recommended.

### 3. TuningPage `FEWER_ANIMATIONS_QUIETER_COLORS` and AccessibilityPage `reduceMotion` write to two different prefs for the same concept (YELLOW)

**Premise verification.** Two distinct DataStore keys exist:
- `A11yPreferences.kt:25` — `REDUCE_MOTION` (`booleanPreferencesKey("reduce_motion")`).
- `NdPreferencesDataStore.kt:35` — `KEY_REDUCE_ANIMATIONS` (`booleanPreferencesKey("nd_reduce_animations")`).

The TuningPage option `FEWER_ANIMATIONS_QUIETER_COLORS` (page 10) writes `reduceAnimations = true` to the ND store. The AccessibilityPage's "Reduce motion" Switch (page 11) writes `REDUCE_MOTION` to the A11y store. Both are read independently by Compose animation gates downstream.

**Risk classification.** YELLOW. Not a functional bug per se — both prefs exist for different reasons (the A11y store is the user-facing toggle; the ND store is the mode-driven cascade), and consumers may read them with different semantics. But from the user's perspective, the journey looks like: "I just said 'fewer animations' on page 10, and the very next page asks me to toggle 'reduce motion' — did I not already answer that?"

**Recommendation.** PROCEED with a doc + UI fix. Two parts:

- Add a one-line caption on AccessibilityPage's reduce-motion row noting that it stacks with calm-mode reduce-animations. Example: "Stacks with any Brain Mode preferences from the previous step." Cheap, makes the two prefs intentional rather than confusing.
- (Optional, not blocking) consider whether the AccessibilityPage Switch should pre-check itself when `NdPreferencesDataStore.reduceAnimations` is true. This is a UX call; flag for product review.

### 4. SmartTasksPage and ProjectsPage are consecutive informational pages that both centre on projects (YELLOW)

**Premise verification.** Confirmed at `OnboardingScreen.kt:474-477` and `OnboardingScreen.kt:526-529`:

- Page 3 (SmartTasksPage) headline "Organize Everything", body: **"Projects, tags, subtasks, and priorities. Drag to reorder, bulk edit, and quick-reschedule with a tap."**
- Page 4 (ProjectsPage) headline "Group with Projects", body: **"Bundle related tasks into a project, set milestones, and track a forgiveness-friendly streak as you make progress."**

Page 3 lists projects as the first feature; page 4 is wholly dedicated to projects. The illustrations differ (task cards vs. project progress cards), so the visual is not literally duplicated, but the concept airtime on projects spans two consecutive informational pages.

**Risk classification.** YELLOW. Three consecutive zero-input information pages (3, 4, 5) is a known UX smell — users either skip the section or zone out. Page 4 could be merged into page 3 (or removed entirely) without losing functional information, since the project-streak detail is reinforced naturally when the user opens the Projects tab.

**Recommendation.** PROCEED with a small content edit: fold page 4 into page 3 by retitling SmartTasksPage to "Tasks & Projects" with a 2-row illustration (top row: tasks; bottom row: projects), and drop the standalone ProjectsPage. Pager count goes from 16 → 15. Low risk.

### 5. PrivacyPage AI opt-out toggle and AI Chat Disclosure V3 disclose the same thing twice (YELLOW)

**Premise verification.**
- PrivacyPage (page 12) at `OnboardingScreen.kt:1731-1737` (per inventory) exposes an "AI Features" opt-out toggle (default ON).
- `ChatViewModel` (line ~234, per inventory) checks `KEY_AI_CHAT_DISCLOSURE_SHOWN_V3` and shows a separate first-use disclosure modal when the user opens AI Chat.

**Risk classification.** YELLOW — but intentional, with a real reason: the V3 modal carries the retention-shape change that the onboarding gate's coarser language doesn't cover (per `CLAUDE.md` "Chat Persistence (D11 E.3)" entry). The two disclosures are not redundant in legal/disclosure terms; they are redundant in **user perception** if the user has already opted in during onboarding.

**Recommendation.** STOP — no work needed. The V3 disclosure is load-bearing (per the chat-system-prompt + retention-shape memory) and the onboarding toggle is the opt-out path. Two surfaces, two purposes. Worth keeping documented here as "investigated, intentional" so future audits don't keep re-flagging it.

### 6. HabitsPage forgiveness Switch is also driven by TuningPage `LOW_ENERGY_DAYS` (YELLOW, subsumed by #2)

**Premise verification.** Same write-conflict pattern as finding #2:
- Page 6 (HabitsPage) exposes `setForgivenessStreaksEnabled` (`OnboardingScreen.kt:741-744`).
- Page 10 (TuningPage) `LOW_ENERGY_DAYS` cascades to `userPreferencesDataStore.setForgivenessPrefs(ForgivenessPrefs(enabled = true))` at `OnboardingViewModel.kt:420-427`.

If the user turns the Forgiving Streaks Switch OFF on page 6, then picks LOW_ENERGY_DAYS on page 10, forgiveness gets silently re-enabled at `completeOnboarding`.

**Risk classification.** YELLOW (would be RED if standalone, but subsumed by finding #2's recommendation).

**Recommendation.** PROCEED — covered by finding #2's recommendation (b). If we strip the brain-mode cascade writes from TuningPage, also strip the `forgivenessStreaks` write — leave that to HabitsPage where the user already explicitly answered. The `primeRestDay` + `checkInIntervalMinutes` + `compactMode` + sub-flag writes stay.

### 7. WelcomePage and SetupPage both offer sign-in (GREEN)

**Premise verification.** Confirmed at `OnboardingScreen.kt:258-418` (WelcomePage) and `OnboardingScreen.kt:1246-1435` (SetupPage). Both call `EmailAuthSection` + Google sign-in.

**Risk classification.** GREEN — intentional "second chance" pattern. The Welcome page is high-friction for users who want to look at the app first; SetupPage is the final-page closer. The `signInState` is a single source of truth, so a user who signs in on Welcome simply sees the SetupPage sign-in card render in its "signed-in" state. No write conflict.

**Recommendation.** STOP — no work needed.

### 8. Brain Modes / Life Modes / Accessibility / Notifications onboarding pages all have Settings counterparts (GREEN)

**Premise verification.** Confirmed across all four pages — every preference written in onboarding has a corresponding Settings entry that re-reads and re-writes the same DataStore key.

**Risk classification.** GREEN — desired pattern. Settings access for revision is a Principle 6 requirement (no locked defaults). The onboarding pages even tell the user where to find each one (e.g., LifeModesPage line 1478: "you can flip these back on anytime in Settings → Life Modes").

**Recommendation.** STOP — no work needed.

---

## Ranked improvement table

Sorted by wall-clock-savings ÷ implementation-cost. "Wall-clock savings" here measures user-visible time saved + bug-class eliminated, not engineering time.

| Rank | Item | Risk | Effort | Payoff |
|---|---|---|---|---|
| 1 | Strip TuningPage cascade writes for `adhdMode` / `calmMode` / `focusReleaseMode` / `forgivenessPrefs.enabled` (finding #2, recommendation b — also fixes #6) | RED | S (1 file, `OnboardingPreferenceMapper.kt`; 1 test file) | Eliminates a silent state-overwrite class affecting 4 prefs |
| 2 | Retire `GuidedTourCard` in favour of `CoachmarkTour` (finding #1) | RED | M (3 file deletes + Today VM + 2 test files; preserve `TOUR_CARD_ELIGIBLE` key) | New users go from 16 + 5 + 13 = 34 instructional surfaces down to 16 + 13 = 29 |
| 3 | Fold `ProjectsPage` into `SmartTasksPage` (finding #4) | YELLOW | S (page count change + 1 deleted composable + page-index tests) | Cuts pager from 16 → 15 |
| 4 | Add "stacks with Brain Mode" caption on AccessibilityPage reduce-motion row (finding #3) | YELLOW | XS (1-line string add) | Removes the "did I not already answer this?" confusion |

---

## Anti-patterns surfaced but not in scope

These are not overlap bugs but worth flagging:

- The 16-page pager itself is long. Skip button mitigates this (line 165-176), but the page count drift is monotonic — every "parity batch" tends to add a page. Consider a soft cap (10-12) and merging adjacent informational pages whenever a new preference page lands.
- `OnboardingPreferenceMapper.applyTuningSelections` writes-on-truthy-only is fragile pattern; if any future Tuning option needs to set a flag to `false`, the current architecture silently breaks. Worth replacing with an explicit `(key, newValue)` pair list and a typed `Override`/`Cascade` distinction.

---

## Phase 2 plan

Auto-firing PRs for the four PROCEED items. Per the project's "max 10 subagents per prompt" cap, all four ship as parallel `prism-android-worker` dispatches in a single message. Each branch follows `<type>/<scope>` naming with squash auto-merge.

PR fan-out:

1. `fix/onboarding-tuning-mode-cascade` — finding #2 + #6
2. `chore/onboarding-retire-guided-tour-card` — finding #1
3. `chore/onboarding-merge-projects-into-smart-tasks` — finding #4
4. `chore/onboarding-a11y-reduce-motion-caption` — finding #3
