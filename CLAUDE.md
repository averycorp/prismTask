# CLAUDE.md

## Project Overview

**PrismTask** (`com.averycorp.prismtask`) is a native Android todo list app built with Kotlin and Jetpack Compose. Currently at v1.7+ (May 2026; baseline below covers everything shipped through v1.6 and reflects the current `main` codebase). Roadmap and ship-status of forward-looking work is tracked in [README.md § Roadmap](README.md#roadmap).

**Baseline features (v1.3 era + earlier, all shipped):** full task management, projects, subtasks, tags, recurrence, reminders, notifications, NLP quick-add, voice input (speech-to-task, voice commands, TTS, hands-free mode), accessibility (TalkBack, font scaling, high-contrast, keyboard nav, reduced motion), Today focus screen (compact header, collapsible sections, customizable layout), tabbed task editor (Details/Schedule/Organize), week/month/timeline views, urgency scoring with user-configurable weights, smart suggestions, drag-to-reorder with custom sort, quick reschedule, duplicate task, bulk edit (priority/date/tags/project), configurable swipe actions, flagged tasks, task templates with built-ins and NLP shortcuts, project and habit templates, saved filter presets, advanced recurrence (weekday/biweekly/custom month days/after-completion), notification profiles with quiet hours and daily digest, two-tier pricing (Free/Pro) with Google Play Billing, Firebase cloud sync, Google Sign-In, JSON/CSV data export/import, Google Drive backup/restore, habit tracking with streaks/analytics, bookable habits, productivity dashboard with burndown charts and heatmap, time tracking per task, 14 home-screen widgets — Today, Habit Streak, Quick-Add, Calendar, Productivity, Timer, Upcoming, Project, Eisenhower, Focus, Inbox, Medication, Stats Sparkline, Streak Calendar — themed off the active PrismTheme palette and gated by `WIDGETS_ENABLED = true` in `app/build.gradle.kts`, Gmail/Slack/Calendar/Zapier integrations *(backend endpoints exist; Calendar two-way sync is the only one wired into Android UI today, see § "Integrations" below)*, and a FastAPI web backend with Claude Haiku-powered NLP parsing.

**v1.4.0 — v1.6.0 (shipped):** The releases expanded PrismTask into a wellness-aware productivity layer on top of the v1.3 core:

- **Work-Life Balance Engine (V1)**: `LifeCategory` enum per task (Work/Personal/Self-Care/Health/Uncategorized), keyword-based `LifeCategoryClassifier`, `BalanceTracker` for ratio/overload computation, a Today-screen balance bar section, Organize-tab life-category chips, NLP category tags (`#work`, `#self-care`, `#personal`, `#health`), filter-panel category multi-select, a dedicated `WeeklyBalanceReportScreen`, and a Settings section with target-ratio sliders, auto-classify toggle, balance-bar toggle, and overload-threshold slider. Room migration 32 → 33 adds `tasks.life_category`; `OverloadCheckWorker` runs periodic overload checks.
- **Mood & energy tracking**: `MoodEnergyLogEntity` + `MoodCorrelationEngine` power a dedicated Mood Analytics screen that correlates mood/energy with task completion, habits, and life categories.
- **Morning check-in & weekly review**: `CheckInLogEntity`, `MorningCheckInResolver`, and `WeeklyReviewAggregator` drive guided daily check-ins and end-of-week reflections, surfaced via new `checkin/` and `review/` feature modules and a Check-In Streak settings section.
- **Boundaries & overload protection**: `BoundaryRuleEntity` + `BoundaryRuleParser` + `BoundaryEnforcer` let users declare work-hours / category limits; `BurnoutScorer` and `ProfileAutoSwitcher` auto-adjust notification profiles when overload is detected.
- **Focus Release & ND-friendly modes**: `FocusReleaseLogEntity`, `GoodEnoughTimerManager`, `EnergyAwarePomodoro`, and `ShipItCelebrationManager` provide neurodivergence-friendly focus flows; `NdPreferences` + `NdFeatureGate` gate these features, with Brain Mode / UI Complexity / Forgiveness-Streak settings sections.
- **Medication refills, clinical report, conversation extraction**: `MedicationRefillEntity` + `RefillCalculator` project refill dates; `ClinicalReportGenerator` exports a therapist-friendly summary; `ConversationTaskExtractor` pulls tasks out of chat transcripts (new `extract/` screen).
- **Custom notification sounds + escalation**: `CustomSoundEntity`, `SoundResolver`, `EscalationScheduler`, and `VibrationAdapter` power per-profile custom sounds, vibration patterns, and escalation chains; `ReminderProfile*` was renamed to `NotificationProfile*` and moved under `domain/model/notifications/`.
- **Projects (Phase 1)**: `ProjectEntity` extended with lifecycle columns (`description`, `status`, `start_date`, `end_date`, `theme_color_key`, `completed_at`, `archived_at`); new `MilestoneEntity` + `MilestoneDao` (CASCADE FK to projects); `ProjectRepository` extended additively with status-aware streams, milestone CRUD + reorder, and `ProjectWithProgress` / `ProjectDetail` projections powered by a forgiveness-first project streak. The streak reuses a freshly extracted `DailyForgivenessStreakCore` that `StreakCalculator.calculateResilientDailyStreak` now also delegates to — projects and habits share one implementation. Activity dates for projects are computed at read time via `ProjectDao.getTaskActivityDates`, which joins `task_completions` through `tasks` so subtask completions inherit from their parent's project. Note: the pre-existing `ProjectTemplateEntity` (a scaffold for spawning project-with-tasks bundles) is orthogonal to this feature despite the name overlap.
- **Database**: Room version is `CURRENT_DB_VERSION` in `data/local/database/Migrations.kt` (cumulative `MIGRATION_1_2` through the latest). Read the migration file directly — per-migration intent + backfill SQL lives there, plus the audit doc that landed each one (e.g. `docs/audits/PHASE_D_BUNDLE_AUDIT.md` for v63→v64). Don't restate the migration history in this file — it drifts.
- **Start-of-Day (SoD)**: `DayBoundary` utility (`util/DayBoundary.kt`) resolves "today" relative to a user-configurable `startOfDay` hour (stored in `UserPreferencesDataStore`). Habits, streaks, Today-screen task filter, Pomodoro stats, widgets, and NLP date parsing all derive the logical day from `DayBoundary`.
- **Built-in habit identity**: `HabitEntity` carries `isBuiltIn` and `templateKey` fields (migration 48→49). `BuiltInHabitReconciler` deduplicates cloud-pulled built-in habits after sync; one-time repair flags live in `BuiltInSyncPreferences`.
- **Daily Essentials**: `DailyEssentialsUseCase` + `DailyEssentialsPreferences` surface a daily housework + schoolwork card on Today; `housework_habit_id` / `schoolwork_habit_id` point to user-chosen habits and the use case hides the card gracefully when the habit is deleted or archived.

## Tech Stack

- **Language**: Kotlin 2.3.20 (JVM target 21)
- **UI**: Jetpack Compose with Material 3 (BOM 2024.12.01)
- **DI**: Hilt (Dagger) 2.59.2
- **Database**: Room 2.8.4 with KSP 2.3.6
- **Navigation**: Jetpack Navigation Compose 2.8.5
- **Serialization**: Gson 2.11.0 (for RecurrenceRule JSON)
- **Cloud**: Firebase Auth + Firestore + Storage (BOM 33.16.0), Google Drive API v3
- **Auth**: Credential Manager + Google Identity
- **Drag-to-Reorder**: sh.calvin.reorderable 2.4.3
- **Widgets**: Glance for Compose 1.1.0
- **Billing**: Google Play Billing 7.1.1
- **Testing**: JUnit 4.13.2, kotlinx-coroutines-test 1.9.0, Turbine 1.1.0, MockK 1.13.13, Robolectric 4.13, Hilt Testing 2.59.2
- **Build**: Gradle 9.3.1 with Kotlin DSL, AGP 9.1.1
- **Min SDK**: 26 (Android 8.0) / **Target SDK**: 35 (Android 15)

## Project Structure

Top-level package layout under `app/src/main/java/com/averycorp/prismtask/`.
This overview is intentionally shallow — list the directory directly for
current sub-package contents (it drifts faster than this file is updated).

```
├── data/
│   ├── local/         # Room DAOs, entities, Migrations.kt, converters
│   ├── remote/        # Firebase Auth/Firestore/Drive, backend client, sync
│   ├── repository/    # All repositories (Task, Project, Habit, Medication, …)
│   ├── preferences/   # DataStore preferences (Theme, Pro status, ND, …)
│   ├── billing/       # BillingManager (Google Play Billing two-tier)
│   ├── calendar/      # CalendarManager + CalendarSyncPreferences
│   ├── export/        # JSON full export + CSV; merge/replace import
│   └── seed/          # Built-in content seeders
├── domain/
│   ├── model/         # Pure data types (RecurrenceRule, TaskFilter, LifeCategory, …)
│   └── usecase/       # NLP parser, urgency, suggestion, recurrence engine, …
├── ui/
│   ├── screens/       # Per-feature screens; tabbed editor + section composables
│   ├── components/    # Shared composables (FilterPanel, QuickAddBar, …)
│   ├── navigation/    # NavGraph + FeatureRoutes
│   ├── theme/         # Color, Type, PriorityColors, LifeCategoryColors
│   └── a11y/          # TalkBack, font scaling, contrast helpers
├── notifications/     # Schedulers, receivers, sound resolvers, workers
├── widget/            # 14 Glance widgets + per-instance config datastore
├── workers/           # Background WorkManager workers
├── di/                # Hilt modules
├── diagnostics/       # Crash/event diagnostics helpers
├── util/, utils/      # Shared helpers (DayBoundary, …)
├── MainActivity.kt    # Single-activity entry point, notification permission
└── PrismTaskApplication.kt   # @HiltAndroidApp
```


## Architecture

- **Single Activity**: `MainActivity` with `@AndroidEntryPoint`, notification permission request
- **MVVM**: ViewModels → Repositories → Room DAOs, all connected via Hilt
- **Compose-only UI**: No XML layouts; entire UI is Jetpack Compose
- **Material 3 theming**: Dynamic colors on Android 12+, static light/dark fallback
- **Edge-to-edge**: Uses `enableEdgeToEdge()`
- **Reactive data**: Room returns `Flow<T>`, ViewModels expose `StateFlow<T>` via `stateIn()`
- **Recurrence**: On task completion, `RecurrenceEngine` calculates next due date; a new task is inserted automatically
- **Reminders**: `AlarmManager` schedules `BroadcastReceiver` triggers; notifications have "Complete" action
- **NLP Quick-Add**: `NaturalLanguageParser` extracts dates, tags (#), projects (@), priority (!), recurrence from text
- **Bottom Navigation**: 5 tabs (Today, Tasks, Projects, Habits, Settings); detail screens hide nav bar
- **Today Focus**: Progress ring, overdue/today/planned sections, plan-for-today sheet
- **Urgency Scoring**: `UrgencyScorer` computes 0–1 score from due date, priority, age, subtask progress
- **Smart Suggestions**: `SuggestionEngine` suggests tags/projects based on usage log keyword matching
- **Cloud Sync**: Firebase Firestore for cross-device sync, `SyncService` with push/pull/real-time listeners
- **Auth**: Google Sign-In via Credential Manager, optional (local-only mode supported)
- **Timeline**: Daily view with scheduled time blocks, duration management, current time indicator
- **Export/Import**: JSON full backup (tasks, habits, habit completions, self-care logs/steps, leisure logs, courses, assignments, course completions, all preferences/config) + CSV tasks export; JSON import with merge/replace modes
- **Habits**: Habit tracking with daily/weekly frequency, streaks, analytics, contribution grid, weekly summary notification
- **Widgets**: 14 Glance-based home screen widgets (Today, Habit Streak, Quick-Add, Calendar, Productivity, Timer, Upcoming, Project, Eisenhower, Focus, Inbox, Medication, Stats Sparkline, Streak Calendar) themed off the active PrismTheme palette, with per-instance configuration; launcher previews wired via `android:previewLayout` (API 31+, app-icon fallback below)
- **Dashboard**: Customizable Today section order and visibility via DashboardPreferences DataStore
- **Task Templates**: Reusable blueprints with backend sync
- **Tabbed Editor**: Bottom sheet with Details/Schedule/Organize tabs (extracted into `addedittask/tabs/`)
- **Sort Memory**: Per-screen sort preferences via DataStore
- **Drag-to-Reorder**: Custom sort mode with persistent task order
- **Two-Tier Pricing**: ProFeatureGate checks BillingManager tier (Free / Pro $7.99/mo or $5/mo billed annually at $59.99/yr with a 7-day free trial); Free gets core features, Pro unlocks everything else (cloud sync, AI Eisenhower/Pomodoro, analytics, briefing/planner, time blocking, collaboration, integrations, Drive backup)
- **Billing**: Google Play Billing via BillingManager singleton; tier cached in DataStore for offline access; debug tier override in Settings
- **Voice Input**: `VoiceInputManager` wraps Android SpeechRecognizer for dictation and continuous hands-free mode; `VoiceCommandParser` parses command grammar; `TextToSpeechManager` reads tasks and briefings
- **Accessibility**: `ui/a11y/` helpers expose TalkBack labels, dynamic font scaling, high-contrast mode, keyboard focus traversal, and reduced-motion animation gates
- **Customization**: `UserPreferencesDataStore` centralizes configurable swipe actions, urgency weights, task card fields, accent colors, card corner radius, compact mode, context menu ordering, and Today-screen layout
- **Notification Profiles**: `NotificationProfileRepository` supports multi-reminder bundles with escalation chains (`EscalationScheduler`), custom per-profile sounds (`CustomSoundEntity` + `SoundResolver`), and vibration patterns (`VibrationAdapter`); `QuietHoursDeferrer` defers notifications during quiet hours; `ProfileAutoSwitcher` rotates active profile based on burnout signals; daily digest notification
- **Analytics**: Productivity dashboard with daily/weekly/monthly views, burndown charts, habit-productivity correlation, heatmap visualization, per-task time tracking
- **Task Analytics**: Contribution grid, streak tracking, day-of-week/hour-of-day distributions, completion rate, on-time rate, and per-project filtering for completed tasks via `TaskCompletionEntity` history table (added in migration 37→38 with backfill; current DB version lives on the `CURRENT_DB_VERSION` constant in `Migrations.kt` — read it from there rather than this doc, which drifts)
- **Integrations**: Google Calendar two-way sync (see `CalendarSyncRepository` / `CalendarSyncService`). Gmail / Slack / webhook endpoints exist on the backend but are not wired into the Android UI.
- **Bookable Habits**: Habit logs carry booking state via `HabitLogEntity` for activity history
- **Work-Life Balance**: `LifeCategory` enum on every task; `LifeCategoryClassifier` auto-tags tasks from keywords; `BalanceTracker` computes category ratios and detects overload; `OverloadCheckWorker` runs periodic checks; dedicated Today balance bar and `WeeklyBalanceReportScreen`
- **Mood / Check-In / Review**: `MoodEnergyLogEntity` + `MoodCorrelationEngine` power Mood Analytics; `CheckInLogEntity` + `MorningCheckInResolver` drive morning check-ins with streaks; `WeeklyReviewEntity` + `WeeklyReviewAggregator` drive guided weekly reviews
- **Boundaries**: `BoundaryRuleEntity` + `BoundaryRuleParser` + `BoundaryEnforcer` enforce user-declared work-hours / category limits; `BurnoutScorer` surfaces risk scores
- **ND-Friendly Modes**: `NdFeatureGate` + `NdPreferences` gate Brain Mode, UI Complexity, Forgiveness Streak, and Focus Release (`FocusReleaseLogEntity`, `GoodEnoughTimerManager`, `EnergyAwarePomodoro`, `ShipItCelebrationManager`)
- **Medication Refills + Clinical Report**: `MedicationRefillEntity` + `RefillCalculator` project refill dates; `ClinicalReportGenerator` exports a therapist-friendly summary
- **Conversation Extraction**: `ConversationTaskExtractor` pulls tasks from chat transcripts into a dedicated review inbox
- **Chat Persistence (D11 E.3)**: AI Coach chat is server-authoritative. The `/api/v1/ai/chat` handler writes both user + assistant turns to a `chat_messages` table on commit; clients read via `GET /api/v1/ai/chat/history` (paginated, user-scoped) and mirror into Room (`ChatMessageEntity` + `ChatMessageDao`). Conversation IDs follow the `chat_{ISO_DATE}_{UUID8}` pattern minted client-side; daily rollover is a UI filter, not a destructive event — old conversations remain queryable. Disclosure flag is at V3 (`KEY_AI_CHAT_DISCLOSURE_SHOWN_V3`) per the retention shape change. No Firestore — chat follows the BackendSyncService precedent

## CI Failure Logs

Workflow failures are auto-committed to the `ci-logs` orphan branch of this public repo. Fetch the relevant log directly (no auth needed) instead of asking the user to paste CI output:

- Android:    https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/android-ci/latest.log
- Backend:    https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/backend-ci/latest.log
- Web:        https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/web-ci/latest.log
- Release:    https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/release/latest.log
- Auto-merge: https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/auto-merge/latest.log

Historical failures: `ci-logs/<workflow-slug>/<timestamp>-<run-id>.log` on the same branch. See [`CI_LOGS.md`](CI_LOGS.md) for details.

## Build Commands

**Note:** The Android SDK and JBR (Java 21) are available locally — local
builds and unit tests are supported and preferred for fast iteration. Before
running Gradle from Git Bash, export the toolchain paths (they are installed
but not on the default PATH):

```bash
export ANDROID_HOME="/c/Users/avery_yy1vm3l/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/c/Program Files/GitHub CLI:$PATH"
```

Instrumentation tests (`connectedDebugAndroidTest`) require a running device
or emulator; `adb devices` will list them. CI still runs on every push and
remains the final verification gate.

```bash
# Debug build
./gradlew assembleDebug

# Release build (R8 minification + resource shrinking enabled)
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumentation tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Clean
./gradlew clean
```

## Key Conventions

- **Theme**: Use `PrismTaskTheme` as the root composable wrapper
- **No XML layouts**: All UI must be Jetpack Compose
- **JVM target**: 21 — do not change without updating both `compileOptions` and `kotlinOptions`
- **Entity fields**: Use `@ColumnInfo` with snake_case column names
- **Recurrence**: Stored as JSON string in `TaskEntity.recurrenceRule`, parsed via `RecurrenceConverter` (Gson)
- **Reminders**: `reminderOffset` is millis before due date; scheduling handled by `ReminderScheduler`
- **Priority levels**: 0=None, 1=Low, 2=Medium, 3=High, 4=Urgent; colors in `PriorityColors`
- **Error handling**: ViewModels catch exceptions and surface via `SnackbarHostState` or `SharedFlow<String>`
- **Capitalization**: Use Title Capitalization in all user-facing strings throughout the app (screen titles, tab labels, button labels, section headers, menu items, dialog titles, empty states, notifications, etc.). Capitalize the first letter of each major word.

## Repo conventions

- **Tagging**: every `versionName` bump in `app/build.gradle.kts` gets a matching annotated git tag (`git tag -a vX.Y.Z -m "..."`). Push the tag. The `post-commit` hook in `scripts/hooks/` reminds you if you forget.
- **PRs**: feature work lands via merged PR, not direct push to main. Small changes (docs, version bumps, trivial fixes) may go direct to main. The `pre-push` hook warns on non-merge pushes to main and requires explicit confirmation.
- **Worktrees**: every new feature goes on a dedicated git worktree branched from latest main. Worktree + branch are both removed via `git worktree remove` + `git branch -d/-D` after the PR merges — no manual folder deletion.
- **Fresh clones**: run `.\scripts\hooks\install.ps1` (Windows) or `./scripts/hooks/install.sh` (unix) once. Each script just sets `core.hooksPath = scripts/hooks` so the version-controlled hooks run directly — no per-clone copy step, and edits to `scripts/hooks/*` take effect on the next git command. `core.hooksPath` is a per-clone config (it lives in `.git/config`, not the tree), which is why a fresh clone still needs the one-time invocation.
- **Audit doc length**: cap each Phase at ~500 lines. Above that, split into batches with separate Phase 1 sweeps. The validated single-pass shape (`docs/audits/CONNECTED_TESTS_STABILIZATION_AUDIT.md`, PR #859) is 390 lines; mega-audits (e.g. `PRE_PHASE_F_MEGA_AUDIT.md` at 1,115 lines) cost wall-clock to write *and* to re-read.
- **Audit-first Phase 3 + 4 fire pre-merge.** Override the skill's default ("after Phase 2 PRs merge"). Append Phase 3 (bundle summary) and emit Phase 4 (Claude Chat handoff block) as soon as the implementation PR is opened — don't wait for CI green or merge. Rationale: operator wants the handoff block ready to paste into a follow-up Chat thread without round-tripping back through this session post-merge.

## Important Files

- `build.gradle.kts` — Root build file with plugin versions (AGP 9.1.1, Kotlin 2.3.20, KSP 2.3.6, Hilt 2.59.2)
- `app/build.gradle.kts` — App module dependencies, build config, ProGuard/R8 settings
- `app/proguard-rules.pro` — Keep rules for Room, Gson, domain models
- `app/src/main/AndroidManifest.xml` — Activity, receivers, permissions
- `app/google-services.json` — Firebase config (placeholder — replace with actual)
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 9.3.1
- `app/src/test/` — unit test files covering NLP, recurrence, urgency, suggestion, streak, export/import, repositories (Task, Habit, Project, Tag, Coaching, NotificationProfile, MedLogReconcile, Medication, TaskCompletion), use cases (ParsedTaskResolver, ChecklistParser, TodoListParser, VoiceCommandParser, SmartDefaults, QuietHoursDeferrer, AdvancedRecurrence, TimeBlock, WeeklyPlanner, DailyBriefing, Eisenhower, SmartPomodoro, BookableHabit, BalanceTracker, LifeCategoryClassifier, BurnoutScorer, BoundaryEnforcer, MoodCorrelationEngine, WeeklyReviewAggregator, RefillCalculator, ConversationTaskExtractor), DataStore preferences, notification/reminder scheduling, ViewModels (Today, AddEditTask, TaskList, HabitList, Eisenhower, Onboarding, SmartPomodoro, Mood, CheckIn, Balance), TaskCardDisplayConfig/TaskMenuAction model tests, widget data/config-defaults, accessibility, theme, and calendar manager
- `app/src/androidTest/` — 28 instrumentation test files: Task/Project/Habit/Tag DAO tests, recurrence integration, and smoke suites for Navigation, QoL features, Task editor, Templates, Today screen, Data export/import, Views, Search/archive, Tags/projects, Settings, Recurrence, Multi-select/bulk edit, Habits, and Offline edge cases
- `backend/tests/` — 25 pytest files covering dashboard, export, search, app_update, projects routers; recurrence/urgency/NLP edge-case services; and end-to-end integration workflows and stress tests
