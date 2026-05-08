# AGENTS.md

Guidance for the **Jules async coding agent** (Google) working in the PrismTask
repo. Jules runs async, well-scoped tasks — this file captures the project
shape, the rails, and the off-limits areas so a single hand-off prompt is
enough to land a clean PR. For the broader, interactive-session conventions
used by Claude Code, see [`CLAUDE.md`](CLAUDE.md).

## Project Overview

**PrismTask** (`com.averycorp.prismtask`) is a productivity suite spanning
three modules:

- **Android** — native Kotlin / Jetpack Compose todo + habits + wellness app
- **Web** — React / TypeScript / Vite companion (sign-in, dashboard, marketing)
- **Backend** — FastAPI + PostgreSQL service deployed on Railway, fronting
  Claude Haiku NLP, sync, integrations, and chat persistence

**Pricing tiers:** Free / Pro ($3.99) / Premium ($7.99). Tier gating runs
through `ProFeatureGate` on Android and the equivalent guard in the web app;
the backend honours the same tier on protected endpoints.

**Release pipeline:** solo founder, every-merge ships. Every merge to `main`
triggers the release workflow, which builds, tags, and publishes. There is no
"batch up changes for next sprint" mode — each PR must be self-contained and
green at merge.

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
├── AGENTS.md             # This file — conventions for Jules (async)
└── README.md             # Project overview + roadmap
```

## Tech Stack Per Module

### Android (`app/`)
- Kotlin 2.3.20 (JVM target 21), Min SDK 26 / Target SDK 35
- Jetpack Compose with Material 3 (no XML layouts)
- Hilt (Dagger) for DI, Room for persistence, Glance for home-screen widgets
- AlarmManager + WorkManager for reminders / periodic jobs
- Firebase Auth + Firestore + Storage for cloud sync
- Google Play Billing for tier purchases

### Web (`web/`)
- React + TypeScript on Vite
- Playwright for E2E testing
- Talks to the same FastAPI backend used by Android

### Backend (`backend/`)
- FastAPI (Python), Postgres on Railway
- Alembic for schema migrations
- pytest for unit + integration coverage
- Claude Haiku for NLP parsing and AI Coach features

## Conventions

- **Migration file naming (Android Room).** Migration test files MUST follow
  exact sequential naming: `Migration54To55Test`, `Migration55To56Test`,
  `Migration56To57Test`, … Skipping numbers or renaming breaks the chain
  validator. Production migration files mirror the same `MigrationNToM` shape.
- **`versionCode` bumps are handled by the auto-merge bot.** Do NOT modify
  the `versionCode` logic in `app/build.gradle.kts`. Bump `versionName` only
  when the task explicitly calls for a release tag.
- **CI workflow files are off-limits.** Anything in `.github/workflows/` is
  owned by the release pipeline maintainer; Jules must not touch these.
- **Hilt test applications.** Every test file annotated with
  `@HiltAndroidTest` must use `HiltTestApplication` (typically via
  `HiltTestRunner` configured in the module's `build.gradle.kts`). Never let
  a Hilt-annotated test fall back to the production `PrismTaskApplication`.
- **Emulator-detection parity.** `PrismTaskApplication.isAndroidEmulator()`
  and `HiltTestRunner.isAndroidEmulator()` MUST stay in sync. If you change
  the detection logic in one, change it in the other in the same PR — they
  exist as a pair so connected tests and prod code agree on what counts as
  an emulator.
- **Title Capitalization** in all user-facing strings (Android, web, push
  copy, emails). Capitalize the first letter of each major word.
- **Compose-only Android UI.** No new XML layouts. Prefer extracting
  composables over inflating views.
- **No new top-level docs.** Don't create `*.md` files unless the task
  description asks for one — extend an existing doc instead.

## Off-Limits Areas (Do Not Modify)

Unless the task description explicitly names the file as in-scope, leave
these alone:

- `.github/workflows/*.yml` — CI pipelines, auto-merge, release automation
- `app/build.gradle.kts` — `versionCode` bump logic (the auto-merge bot owns it)
- Android migration files matching `Migration*To*Test.kt` or
  `Migration*To*.kt` — sequential chain integrity is enforced by CI
- `backend/alembic/versions/*.py` — DB schema migrations on the deployed
  Postgres instance; off-limits unless the task is a schema change
- `app/google-services.json` and any other credential / key files

If a task seems to require touching one of these, stop and surface the
conflict in the PR description rather than editing silently.

## How to Run Tests

### Android
```bash
# Unit tests (fast, no device required)
./gradlew test

# Debug-variant unit tests only
./gradlew testDebugUnitTest

# Instrumented tests (requires a running AVD / emulator or connected device)
./gradlew connectedDebugAndroidTest
```

Instrumented tests need an Android Virtual Device (AVD) booted via
`emulator -avd <name>` or a physical device visible to `adb devices`.

### Web
```bash
cd web
npm install
npm test            # Unit / component tests
npx playwright test # E2E suite
```

### Backend
```bash
cd backend
pip install -r requirements.txt
pytest              # Full suite
pytest tests/test_<module>.py::test_<name>  # Single test
```

## Working Style For Jules

- **One concern per PR.** Async tasks land cleanest when the diff is
  narrowly scoped. If a request implies two unrelated changes, split them.
- **Run the relevant test command before pushing.** A failing CI on every
  merge directly delays a release because of the every-merge ship pipeline.
- **Match existing patterns.** Repositories, ViewModels, use cases, and
  routers all have established shapes — mimic the nearest neighbour rather
  than introducing a new abstraction.
- **Don't add backwards-compat shims.** Solo-founder repo, no external
  consumers — change the call site instead of leaving a deprecated wrapper.
- **Surface uncertainty in the PR body.** If a decision could go either
  way, write the trade-off down so the reviewer can redirect quickly.
