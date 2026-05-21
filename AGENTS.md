# AGENTS.md

Guidance for AI coding agents (such as Google's **Antigravity**, **Jules**, and any other Gemini-based or Claude-based agent systems) working in the PrismTask repo. These agents run async, well-scoped tasks — this file captures the project shape, the rails, and the off-limits areas so a single hand-off prompt is enough to land a clean PR. 

All agents (including Gemini-based agents like Antigravity) **must understand and adhere to the exact same standards, design principles, conventions, and rails as Claude Code**. The broader, interactive-session conventions used by Claude Code are documented in [`CLAUDE.md`](CLAUDE.md), and are fully incorporated below to ensure absolute parity.

---

## Project Overview

**PrismTask** (`com.averycorp.prismtask`) is a productivity suite spanning three modules:

- **Android** — native Kotlin / Jetpack Compose todo + habits + wellness app
- **Web** — React / TypeScript / Vite companion (sign-in, dashboard, marketing)
- **Backend** — FastAPI + PostgreSQL service deployed on Railway, fronting Claude Haiku NLP, sync, integrations, and chat persistence

**Pricing tiers:** Free / Pro ($7.99/mo, or $5/mo billed annually at $59.99/yr with a 7-day free trial). Tier gating runs through `ProFeatureGate` on Android and the equivalent `useProFeature` hook in the web app; the backend honours the same tier on protected endpoints.

**Release pipeline:** solo founder, every-merge ships. Every merge to `main` triggers the release workflow, which builds, tags, and publishes. There is no "batch up changes for next sprint" mode — each PR must be self-contained and green at merge.

---

## Repo Layout

```
prismTask/
├── app/                  # Android module (Kotlin, Compose, Hilt, Room, Glance)
├── web/                  # Web app (React, TypeScript, Vite, Playwright)
├── backend/              # FastAPI service, Alembic migrations, pytest suite
├── .github/workflows/    # CI pipelines (Android, Backend, Web, Release, Auto-merge)
├── docs/                 # Audit docs, architecture notes, roadmaps
├── scripts/              # Git hooks, release scripts, dev tooling
├── CLAUDE.md             # Conventions for Claude Code (interactive)
├── AGENTS.md             # This file — conventions for AI Agents (Jules/Antigravity)
└── README.md             # Project overview + roadmap
```

---

## Tech Stack Per Module

### Android (`app/`)
- **Language**: Kotlin 2.3.20 (JVM target 21)
- **UI**: Jetpack Compose with Material 3 (BOM 2024.12.01) — no XML layouts
- **DI**: Hilt (Dagger) 2.59.2
- **Database**: Room 2.8.4 with KSP 2.3.6
- **Navigation**: Jetpack Navigation Compose 2.8.5
- **Serialization**: Gson 2.11.0 (for RecurrenceRule JSON)
- **Cloud**: Firebase Auth + Firestore + Storage (BOM 33.16.0), Google Drive API v3
- **Widgets**: Glance for Compose 1.1.0 (themed off active PrismTheme, gated by `WIDGETS_ENABLED = true`)
- **Billing**: Google Play Billing 8.3.0
- **Testing**: JUnit 4.13.2, kotlinx-coroutines-test 1.9.0, Turbine 1.1.0, MockK 1.13.13, Robolectric 4.13, Hilt Testing 2.59.2
- **Min SDK**: 26 (Android 8.0) / **Target SDK**: 35 (Android 15)

### Web (`web/`)
- React + TypeScript on Vite
- Playwright for E2E testing
- Talks to the same FastAPI backend used by Android

### Backend (`backend/`)
- FastAPI (Python), Postgres on Railway
- Alembic for schema migrations
- pytest for unit + integration coverage
- Claude Haiku for NLP parsing and AI Coach features

---

## Anti-Pattern Enforcement (Philosophy)

PrismTask's design principles are documented in [`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md). Every feature must obey these 7 mental-health-positive principles. The following anti-patterns are the canonical **reject filter** for new feature work. Any proposed scope containing any of these must be flagged and surfaced to the operator before implementation.

1. **Streak-reset shame UI**: "X days since failure" counters; punitive streak-loss notifications (Principle 1).
2. **End-of-day judgment screens**: "you missed X% of your plan" framing; calendar greying-out of missed time (Principle 2).
3. **Work-only productivity score**: Productivity scores or streaks that count only Work tasks and treat Play / Relax as wasted time (Principle 3).
4. **Single-axis priority**: Single-axis priority as the locked default sort; "eat the frog" defaults (Principle 4).
5. **Fake urgency & pre-checks**: Fake countdowns; pre-checked subscription boxes; retention guilt ("Are you sure? You'll lose your streak!"); dark-grey decline buttons; "free trial" auto-bill without re-confirm (Principle 5).
6. **Locked defaults**: Major features (AI, sync, automations, medication reminders, streak, balance bar, cognitive load) with no Settings-level disable path (Principle 6).
7. **Forced opt-ins**: Onboarding that demands notification permission as a *blocking* step; daily-cadence absence-pings; badge-count gamification (Principle 7).
8. **Diagnosis-language branding**: Using diagnosis-language (ADHD, autism, depression, bipolar, etc.) as the *primary audience descriptor* on Play Store short description, feature graphic, or README first paragraph. Use "every kind of mind" framing instead (Principle 5 honest disclosure).

> [!IMPORTANT]
> **Override Path**: Any apparent violation of these principles requires explicit operator pre-approval AND a documented justification in the PR description. Do not ship a one-off coachmark or help-icon infrastructure as a fix path (ED-6 / ED-7 anti-patterns from `docs/audits/D_SERIES_UX_AUDIT.md`).

---

## Technical Conventions

### General
- **Title Capitalization**: Use Title Capitalization in all user-facing strings throughout the app (screen titles, tab labels, button labels, section headers, menu items, dialog titles, empty states, notifications, push copy, emails, etc.). Capitalize the first letter of each major word.
- **No new top-level docs**: Do not create `*.md` files unless the task description explicitly asks for one — extend or modify existing docs instead.
- **No deprecated shims**: PrismTask is a solo-founder repo with no external consumers — change the call sites directly instead of leaving a deprecated wrapper.

### Android (`app/`)
- **Theme**: Always use `PrismTaskTheme` as the root composable wrapper.
- **No XML layouts**: All UI must be Jetpack Compose.
- **JVM target**: Target JVM 21 — do not change without updating both `compileOptions` and `kotlinOptions`.
- **Entity fields**: Use `@ColumnInfo` with snake_case column names.
- **Recurrence**: Stored as a JSON string in `TaskEntity.recurrenceRule`, parsed via `RecurrenceConverter` (Gson). Recurrence engine anchors the next occurrence to completion time (not original due date); toggle-uncomplete rolls back spawned recurrence; undo/redo must not duplicate recurring tasks.
- **Reminders**: `reminderOffset` is stored as milliseconds before due date; reminder scheduling is handled by `ReminderScheduler`.
- **Priority levels**: 0 = None, 1 = Low, 2 = Medium, 3 = High, 4 = Urgent; colors are mapped in `PriorityColors`.
- **Error handling**: ViewModels must catch exceptions and surface them via `SnackbarHostState` or `SharedFlow<String>`.
- **Migration file naming**: Migration test files MUST follow exact sequential naming: `Migration54To55Test`, `Migration55To56Test`, `Migration56To57Test`, … Skipping numbers or renaming breaks the chain validator. Production migration files mirror the same `MigrationNToM` shape.
- **Hilt test applications**: Every test file annotated with `@HiltAndroidTest` must use `HiltTestApplication` (typically via `HiltTestRunner` configured in the module's `build.gradle.kts`). Never let a Hilt-annotated test fall back to the production `PrismTaskApplication`. 
  - *Load-bearing:* `HiltTestApplication` does **NOT** run `PrismTaskApplication.onCreate`. Application-level init must be mirrored in `HiltTestRunner.onStart`. If you add a new `PrismTaskApi` method, new DAO, or new `@EntryPoint`, mirror it into the corresponding test module (`TestNetworkModule` / `TestDatabaseModule`).
- **Emulator-detection parity**: `PrismTaskApplication.isAndroidEmulator()` and `HiltTestRunner.isAndroidEmulator()` MUST stay in sync. If you change the detection logic in one, change it in the other in the same PR.
- **No Duplication of Heuristics**: Any helper shared between production and test code MUST be extracted to a shared utility under `util/` or `utils/`.
- **Callbacks**: Do not use no-op default lambdas on load-bearing callbacks (i.e. use `onX: (T) -> Unit` for required callbacks, never `= {}`).
- **Compose remember keys**: Never key `remember` on an integer that the composable itself mutates via input + `coerceIn`.

### Web (`web/`)
- **Firestore Direct Writes**: Use Firestore-direct write path for `task_completions`.
- **Local Storage / Cache**: Clear IndexedDB cache on logout to keep different users on the same browser isolated.
- **Sync**: Sync `startOfDayHour` cross-device via `users/{uid}/prefs/task_behavior_prefs.day_start_hour`.

### Backend (`backend/`)
- **Chat Persistence**: AI Coach chat is server-authoritative and uses `chat_messages` Postgres table. Client-side Room mirror uses `ChatMessageEntity` / `ChatMessageDao`. Conversation IDs follow `chat_{ISO_DATE}_{UUID8}`. Old conversations remain queryable (daily rollover is a UI filter, not a destructive event). Disclosure pref is tracked via `KEY_AI_CHAT_DISCLOSURE_SHOWN_V3`. No Firestore for chat.

---

## Repo & Git Conventions

- **Feature PRs**: Feature work lands via merged PR, not direct push to main. Small changes (documentation updates, version bumps, trivial fixes) may go direct to main. The `pre-push` hook warns on non-merge pushes to `main` and requires confirmation.
- **Worktrees**: Every new feature goes on a dedicated git worktree branched from latest `main`. Remove worktree + branch via `git worktree remove` + `git branch -d/-D` after the PR merges. Do not delete directories manually.
- **Tagging**: Every `versionName` bump in `app/build.gradle.kts` gets a matching annotated git tag (`git tag -a vX.Y.Z -m "..."`). Push the tag. The `post-commit` hook in `scripts/hooks/` reminds you if you forget.
- **Audit doc length**: Cap each Phase at ~500 lines. Split into batches with separate Phase 1 sweeps if needed.
- **Audit-first Phase 3 & 4 pre-merge**: Append Phase 3 (bundle summary) and emit Phase 4 (Claude Chat handoff block) as soon as the implementation PR is opened — do not wait for CI green or merge.
- **Fresh clones**: Run `./scripts/hooks/install.sh` (unix) or `.\scripts\hooks\install.ps1` (Windows) once to set `core.hooksPath = scripts/hooks`.

---

## CI Failure Logs

If CI fails, workflow logs are auto-committed to the `ci-logs` branch. You can fetch the latest logs directly without requiring the user to paste them:

- **Android**: [latest.log](https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/android-ci/latest.log)
- **Backend**: [latest.log](https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/backend-ci/latest.log)
- **Web**: [latest.log](https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/web-ci/latest.log)
- **Release**: [latest.log](https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/release/latest.log)
- **Auto-merge**: [latest.log](https://raw.githubusercontent.com/averycorp/prismTask/ci-logs/ci-logs/auto-merge/latest.log)

---

## How to Run Tests & Build

### Android Environment Exports
Before running Gradle tasks, export the toolchain paths if they are not on your active PATH:
```bash
export ANDROID_HOME="/c/Users/avery_yy1vm3l/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/c/Program Files/GitHub CLI:$PATH"
```

### Android Gradle Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build (R8 minification + resource shrinking enabled)
./gradlew assembleRelease

# Unit tests (fast, no device required)
./gradlew testDebugUnitTest

# Instrumented tests (requires a running AVD / emulator or connected device)
./gradlew connectedDebugAndroidTest

# Clean project
./gradlew clean
```

### Web Commands
```bash
cd web
npm install
npm test            # Unit / component tests
npx playwright test # E2E suite
```

### Backend Commands
```bash
cd backend
pip install -r requirements.txt
pytest              # Full suite
pytest tests/test_<module>.py::test_<name>  # Single test
```

---

## Working Style For AI Agents (Antigravity, Jules)

- **One concern per PR**: Async tasks land cleanest when the diff is narrowly scoped. If a request implies two unrelated changes, split them.
- **Run tests before pushing**: A failing CI on every merge directly delays a release because of the every-merge ship pipeline. Run the relevant test suite locally before pushing!
- **Match existing patterns**: Repositories, ViewModels, use cases, and routers all have established shapes — mimic the nearest neighbour rather than introducing a new abstraction.
- **Surface uncertainty**: If a decision could go either way, write the trade-off down in the PR body so the reviewer can redirect quickly.
- **Pushing changes**: Always push completed, verified green commits directly to the remote `main` branch at the end of every successful agent run (using `--no-verify` to bypass interactive TTY pre-push hooks if executing in non-interactive environments). For small changes (docs, version bumps, trivial fixes), direct pushes to `main` are authorized.
