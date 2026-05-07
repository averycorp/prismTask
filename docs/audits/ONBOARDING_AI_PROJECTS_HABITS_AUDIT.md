# Onboarding — AI / Projects / Habits Overview + AI Loading Indicators Audit

**Branch:** `claude/onboarding-ai-features-overview-0rusm`
**Date:** 2026-05-07
**Scope (operator-supplied):**
1. Onboarding should include an overview of all AI features.
2. Onboarding should include an overview of the **Projects** concept.
3. Onboarding should include an overview of the **Habits** concept.
4. All AI features should show a loading indicator while they're processing.

This audit is a single Phase 1 doc; Phase 2 fans out into per-scope PRs.

---

## Baseline — Onboarding skeleton

The onboarding lives at:

- `app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingScreen.kt` (2,030 lines, 15-page `HorizontalPager` driven by `TOTAL_PAGES`, line 110)
- `app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingViewModel.kt` (503 lines; ~30 `StateFlow` fields covering auth, theme, habits, notifications, accessibility, voice/AI flags, SoD, templates)
- `app/src/main/java/com/averycorp/prismtask/data/preferences/OnboardingPreferences.kt` (`HAS_COMPLETED_ONBOARDING` + `ONBOARDING_COMPLETED_AT`)

Page roster (15 total):

| # | Page | Purpose |
|---|------|---------|
| 0 | `WelcomePage` | Animated intro + Google/email sign-in (returning-user detection) |
| 1 | `ThemePickerPage` | Theme mode + accent colour |
| 2 | `SmartTasksPage` | Tasks structure: projects, tags, subtasks, priorities |
| 3 | `NaturalLanguagePage` | Live NLP demo: `Buy groceries tomorrow !high #errands` |
| 4 | `HabitsPage` | Streak counter + Forgiving Streaks toggle / slider |
| 5 | `LifeModesPage` | Self-Care / Medication / Schoolwork / Housework / Leisure toggles |
| 6 | `TemplatesPage` | Leisure templates + routines |
| 7 | `ViewsPage` | Today / Week / Month preview cards |
| 8 | `BrainModePage` | ADHD / Calm / Focus Release ND modes |
| 9 | `AccessibilityPage` | Reduce Motion / Contrast / Touch Targets |
| 10 | `PrivacyPage` | Voice Input + AI Features toggles |
| 11 | `NotificationsPage` | 6 notification toggles + POST_NOTIFICATIONS permission |
| 12 | `DaySetupPage` | Start-of-day picker |
| 13 | `ConnectIntegrationsPage` | Calendar / integration stub |
| 14 | `SetupPage` | Repeat sign-in card + first-task field + finish CTA |

---

## Item 1 — Habits in onboarding (GREEN)

**Findings.**
- `HabitsPage` (page 4 in `OnboardingScreen.kt`, lines 509–630) is a dedicated full page with a streak counter animation, an explanation of streaks, and a Forgiving Streaks toggle backed by `OnboardingViewModel.streakForgivenessEnabled` (`OnboardingViewModel.kt:~120`) plus a max-missed-days slider.
- `LifeModesPage` (lines 1176–1254, page 5) gates which **built-in habit categories** (Self-Care, Medication, Schoolwork, Housework, Leisure) seed during `completeOnboarding()` (`OnboardingViewModel.kt:384–417`). Default is opt-out — every category seeds unless the user toggles it off.
- `NotificationsPage` (page 11) wires habit-streak alerts into the daily / weekly notification toggles.
- "habit" / "Habit" appears 8+ times across the onboarding source. Habits are the most extensively introduced concept after the welcome / sign-in flow.

**Risk:** GREEN — habits are already first-class in onboarding.

**Recommendation:** STOP-no-work-needed. The operator's stated goal ("include the concept of habits") is already met. Do not regress this surface area in Phase 2.

---

## Item 2 — Projects in onboarding (RED)

**Findings.**
- "project" appears exactly **once** in `OnboardingScreen.kt` — line ~401 in `SmartTasksPage` body copy: *"Projects, tags, subtasks, and priorities. Drag to reorder, bulk edit, and quick-reschedule with a tap."* That's it.
- No dedicated Projects page. No `LifeModesPage`-equivalent for project lifecycle (`status`, `theme_color_key`, milestones — all v1.6 additions to `ProjectEntity`).
- The Projects list screen has a non-onboarding empty state at `ui/screens/projects/ProjectListScreen.kt` (`RichEmptyState` with copy *"Projects help you organize related tasks together."*) — but a user who never opens the Projects tab never sees it.
- Projects are a **load-bearing** concept post-v1.6: they own milestones, lifecycle status, theme colour, project streaks (shared `DailyForgivenessStreakCore` with habits), and the dashboard project widget. None of that is mentioned in onboarding.
- Asymmetry vs. habits is stark — Habits get a full animated page + Life Modes gating + notifications wiring. Projects get one comma-clause inside another page.

**Risk:** RED — operator-stated scope item is genuinely missing, not just under-emphasised.

**Recommendation:** PROCEED. Add a dedicated `ProjectsPage` immediately after `SmartTasksPage` (so the flow becomes Tasks → Projects → NLP → Habits → Life Modes). Page should preview project cards (icon / status badge / progress bar) and explain the milestone + streak relationship in one sentence — match `HabitsPage`'s shape, don't over-design.

---

## Item 3 — AI features overview in onboarding (RED)

**Findings — feature-by-feature mention coverage.**

| AI feature | File | Tier | Mentioned in onboarding? |
|------------|------|------|--------------------------|
| NLP Quick-Add (offline + Claude Haiku) | `domain/usecase/NaturalLanguageParser.kt:69` | Free / Pro for Haiku | ✅ Dedicated `NaturalLanguagePage` (page 3) |
| Voice Input | `domain/usecase/VoiceInputManager.kt:32` | Free | ✅ `PrivacyPage` toggle (line 1416/1457–1460) |
| Voice Commands | `domain/usecase/VoiceCommandParser.kt` | Free | ⚠️ ViewModel flag only, no copy |
| Smart Suggestions (tags / projects) | `domain/usecase/SuggestionEngine.kt:61` | Free | ❌ |
| Smart Defaults | `domain/usecase/SmartDefaultsEngine.kt:17` | Free | ❌ |
| Daily Briefing | `ui/screens/briefing/DailyBriefingViewModel.kt` | Pro | ✅ Listed in `PrivacyPage` AI subtitle (line ~1465) and `NotificationsPage` toggle |
| Eisenhower Auto-Classify | `data/remote/EisenhowerClassifier.kt` | Pro | ✅ AI subtitle + `ViewsPage` mention |
| Smart Pomodoro coaching | `ui/screens/pomodoro/SmartPomodoroViewModel.kt` | Pro | ✅ AI subtitle |
| Conversation Extraction | `domain/usecase/ConversationTaskExtractor.kt` | Pro | ❌ |
| Mood Correlation | `domain/usecase/MoodCorrelationEngine.kt:74` | Free (opt-in) | ✅ `PrivacyPage` opt-in note (line 1472–1478) |
| Burnout Scorer | `domain/usecase/BurnoutScorer.kt:85` | Free | ❌ |
| Life-Category Auto-Classify | `domain/usecase/LifeCategoryClassifier.kt` | Free | ❌ (life categories themselves are not surfaced in onboarding) |
| Profile Auto-Switcher | `domain/usecase/ProfileAutoSwitcher.kt` | Free | ❌ (background; arguably fine to omit) |

**What exists today.** `PrivacyPage` (page 10) carries a single subtitle line listing *"NLP parsing, briefings, Eisenhower auto-classify, Pomodoro coaching"* gated behind one master "AI Features" toggle. That's the only AI overview. There is no page that **enumerates** what AI does for the user, so:
- 5+ features (Smart Suggestions, Smart Defaults, Conversation Extraction, Burnout, Life-Category Classifier) get zero onboarding visibility.
- Tier gating (Free vs Pro) is invisible — users can't tell which AI features need Pro.
- The single master toggle on `PrivacyPage` is the only AI control surface, with no per-feature granularity in onboarding.

**Risk:** RED — operator scope item ("overview of all AI features") is materially incomplete.

**Recommendation:** PROCEED. Add a dedicated `AiOverviewPage` between `NaturalLanguagePage` (page 3) and `HabitsPage` (page 4), or just before `PrivacyPage`. Page should:

1. Group features into 3–4 visual buckets (Capture / Plan / Reflect / Coach), not list 12 items in a flat row.
2. Tag each bucket with Free / Pro chip.
3. End with a CTA reading "Manage details on the next privacy step" so the existing `PrivacyPage` toggle keeps its role.

The existing `PrivacyPage` AI subtitle should be updated to match whatever phrasing the new overview page uses — keep the two surfaces consistent.

---

## Item 4 — Loading indicators on all AI features (YELLOW)

**Findings — current loading-state coverage.**

| # | AI feature | ViewModel loading state | UI indicator | Verdict |
|---|------------|-------------------------|--------------|---------|
| 1 | Backend NLP Quick-Add | `QuickAddViewModel._isSubmitting` (`QuickAddViewModel.kt:138`) | **Send button disabled only** (`QuickAddBar.kt:275`) — no spinner | ❌ Gap — RED |
| 2 | Voice Input | `VoiceInputManager._isListening` + `_rmsLevel` pulse | Mic button pulse + "Listening…" placeholder | ✅ |
| 3 | Daily Briefing | `DailyBriefingViewModel._isLoading` (`:58`) | TopAppBar `CircularProgressIndicator` + shimmer skeleton | ✅ |
| 4 | Eisenhower Auto-Classify | Sealed `EisenhowerUiState.Loading` (`:39`) | TopAppBar `CircularProgressIndicator` (`EisenhowerScreen.kt:146–148`) | ✅ |
| 5 | Smart Pomodoro plan | `_planUiState: PomodoroPlanUiState.Loading` (`:214`) | Banner + derived `isLoading` | ✅ |
| 6 | Pre-Session Pomodoro Coaching | `_preSessionCoaching: ...Loading` (`:343`) | Modal spinner + "Getting Your Coach's Take…" copy | ✅ |
| 7 | Smart Suggestions | None — `Flow<TaskSuggestions>` reactive | n/a | DEFERRED — sub-frame, no perceivable wait |
| 8 | Smart Defaults | None — pure synchronous compute | n/a | DEFERRED — same |
| 9 | Conversation Extraction | None — `extract:75` is offline regex | n/a | DEFERRED — synchronous local |
| 10 | Mood Correlation | None — local aggregation | n/a | DEFERRED — milliseconds, in-memory |
| 11 | Burnout Scorer | None — pure function | n/a | DEFERRED — synchronous |
| 12 | Life-Category Classifier | None — synchronous keyword match | n/a | DEFERRED — runs at insert |
| 13 | Profile Auto-Switcher | n/a — background WorkManager | n/a | DEFERRED — non-interactive |

**Synthesis.** Of 12 user-visible AI features, only **one** (Backend NLP Quick-Add via `QuickAddBar`) has a perceivable wait without a loading indicator. The other long-running features (Briefing, Eisenhower, Pomodoro) already render spinners; the rest are local/synchronous and don't justify chrome.

The `_isSubmitting` flag is already plumbed into `QuickAddBar`, so the gap is purely UI — wire a `CircularProgressIndicator` (or `LinearProgressIndicator` strip beneath the input) when `isSubmitting && backendInFlight`.

Edge case: `QuickAddViewModel.onSubmit:492` flips `_isSubmitting` for both the offline-only path **and** the remote-Haiku path. A naive "show spinner whenever `isSubmitting`" will flash for ~10 ms during pure local parses too. Mitigation: gate the spinner on a separate `isRemoteParseInFlight` flag, or accept the flash as harmless feedback. Decide in implementation.

**Risk:** YELLOW — one real gap (NLP Quick-Add); the rest are non-issues. The operator's "all AI features" phrasing is best read as "every AI feature with a perceivable wait."

**Recommendation:** PROCEED on QuickAddBar only. STOP-no-work-needed for the seven local/synchronous features (#7–#13) — adding chrome there would introduce flicker without informing the user. Document the rationale in the Phase 4 handoff so a follow-up Claude Chat doesn't second-guess it.

---

## Ranked improvement table

Sorted by wall-clock-savings ÷ implementation-cost (high → low). Estimates are eyeballs, not measurements.

| # | Improvement | Verdict | Est. cost | Est. user-visible impact |
|---|-------------|---------|-----------|--------------------------|
| 1 | Add `CircularProgressIndicator` to `QuickAddBar` while `isSubmitting` (gated on remote-parse path) | PROCEED | XS (~15 LOC + 1 test) | Removes the only "is the app frozen?" moment in the AI surface |
| 2 | Add dedicated `ProjectsPage` to onboarding (between `SmartTasksPage` and `NaturalLanguagePage`) | PROCEED | S (~120 LOC + bump `TOTAL_PAGES`) | Fixes the asymmetry — Projects get parity with Habits |
| 3 | Add dedicated `AiOverviewPage` to onboarding (3–4 capability buckets, Free/Pro chips) | PROCEED | S–M (~180 LOC + bump `TOTAL_PAGES`) | Surfaces 5+ AI features users currently can't discover until they happen to navigate to the screen |
| 4 | Habits onboarding coverage | STOP — already comprehensive | 0 | n/a |
| 5 | Loading chrome on Smart Suggestions / Smart Defaults / Conversation Extraction / Mood / Burnout / Life-Category | DEFER — synchronous or sub-frame, would flicker | 0 | Negative — flicker without info |

---

## Anti-patterns surfaced (not blocking)

- **Master-toggle-only AI controls.** Onboarding's `PrivacyPage` collapses every AI feature into one toggle. Fine for onboarding compactness, but means the user can't disable Eisenhower while keeping NLP. Out of scope for this audit; flag for a future settings-granularity audit.
- **`OnboardingScreen.kt` is 2,030 lines.** Every page is a private composable inside one file. Adding two more pages pushes it past 2,300. Fine short-term; flag for an extraction-into-`pages/` audit if it crosses 2,500.
- **`completeOnboarding()` mirrors to Firestore via `CanonicalOnboardingSync`.** Any new preference (e.g. dismissed-AI-overview flag) needs to plug into that sync or it'll desync across devices. Implementation note for Phase 2.
- **`OnboardingViewModelTest` covers only the `SignInState` sealed class** (~47 lines, 3 tests). New pages should each ship at least one ViewModel test asserting the new preference round-trips.

---

## Phase 2 plan (auto-fires after this commit)

Three independent PRs, in this order so review queues stack cleanly:

1. **`fix/onboarding-ai-projects-habits-quickadd-spinner`** — minimal: add progress indicator to `QuickAddBar` for the remote-parse path. Includes a Compose test asserting the indicator renders when `isSubmitting && remoteParseInFlight`.
2. **`feat/onboarding-ai-projects-habits-projects-page`** — new `ProjectsPage` composable + bump `TOTAL_PAGES` to 16. Mirror `HabitsPage`'s shape (preview cards + 1-paragraph copy + 1 toggle if any). ViewModel adds at most one `StateFlow` (e.g., `projectsIntroSeen`).
3. **`feat/onboarding-ai-projects-habits-ai-overview-page`** — new `AiOverviewPage` composable + bump `TOTAL_PAGES` to 17. Three or four buckets (Capture / Plan / Reflect / Coach), each with Free / Pro chip and a one-sentence what-it-does line. Update `PrivacyPage` AI subtitle to stay consistent.

Each PR is small enough to land in its own squash-merge with required CI green.

---

## Phase 3 — Bundle summary

**Branch directive override.** The operator-assigned branch
`claude/onboarding-ai-features-overview-0rusm` collapsed the planned three-PR
fan-out into one PR (#1161) with three commits. The Phase 2 plan above was
right in spirit — three coherent units of work — but the branch directive
beat the skill's default fan-out. Worth a memory entry candidate:
*"GitHub-task-automation branch directives outrank the audit-first skill's
fan-out default — bundle into one PR on the assigned branch."*

| Improvement | Verdict | Landed in |
|-------------|---------|-----------|
| `QuickAddBar` `CircularProgressIndicator` while `isSubmitting` | PROCEED | PR #1161 (commit `ebde218`) |
| `ProjectsPage` onboarding page (index 3) | PROCEED | PR #1161 (commit `ebde218`) |
| `AiOverviewPage` onboarding page (index 11, 4-bucket layout w/ Free/Pro chips) | PROCEED | PR #1161 (commit `ebde218`) |
| Habits onboarding coverage | STOP-no-work-needed | n/a — already comprehensive |
| Spinners on synchronous AI features (Suggestions / Defaults / Conversation Extract / Mood / Burnout / Life-Category) | DEFERRED | n/a — would introduce flicker without informing user |

**Re-baselined estimates.** Total wall-clock for the implementation phase
across all three commits: ~25 min (audit + edits + push). The original
"S–M ~180 LOC" estimate for `AiOverviewPage` came in at 99 LOC; the
"S ~120 LOC" for `ProjectsPage` came in at 60 LOC. Visual polish (4-bucket
layout, animated cards) was cheaper than expected because `OnboardingPageLayout`
+ `ChipLabel` were already extracted helpers. Calibration: when an audit
identifies "match shape of existing page X," halve the LOC estimate.

**Memory entry candidates (only if surprising).**
- *Branch-directive vs fan-out:* see above. Worth recording.
- *`OnboardingScreen.kt` size:* file is now 2,191 lines. The "extract pages
  into `pages/` subdirectory" anti-pattern flag in the audit is now closer
  to crossing the 2,500-line threshold — file the extraction audit before
  the next onboarding feature lands, not after.

**Schedule for next audit.** No follow-up audit needed for this scope —
the four operator items are addressed. A separate audit on
*"per-feature granular AI controls in Settings"* (anti-pattern #1 from the
table above) would be the natural next step if the user wants to disable
Eisenhower while keeping NLP enabled.

