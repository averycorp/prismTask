# PrismTask

[![Version](https://img.shields.io/badge/Version-1.6.0-success.svg)](CHANGELOG.md)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-orange.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115-009688.svg)](https://fastapi.tiangolo.com)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791.svg)](https://postgresql.org)
[![Android CI](https://github.com/averycorp/prismTask/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/averycorp/prismTask/actions/workflows/android-ci.yml)
[![Android Integration CI](https://github.com/averycorp/prismTask/actions/workflows/android-integration.yml/badge.svg?branch=main)](https://github.com/averycorp/prismTask/actions/workflows/android-integration.yml)
[![Backend CI](https://github.com/averycorp/prismTask/actions/workflows/backend-ci.yml/badge.svg?branch=main)](https://github.com/averycorp/prismTask/actions/workflows/backend-ci.yml)
[![Web CI](https://github.com/averycorp/prismTask/actions/workflows/web-ci.yml/badge.svg?branch=main)](https://github.com/averycorp/prismTask/actions/workflows/web-ci.yml)
[![Release](https://github.com/averycorp/prismTask/actions/workflows/release.yml/badge.svg)](https://github.com/averycorp/prismTask/actions/workflows/release.yml)

A cross-platform task manager and wellness-aware productivity layer.
Features AI-powered NLP and batch ops, voice input, full accessibility,
deep customization, productivity analytics, Work-Life Balance Engine, mood
& energy tracking, morning check-in, boundary rules, burnout detection,
ND-friendly focus modes, medication tracking with per-slot tiers and
configurable reminder modes (clock or interval), AI time blocking with
horizon selector + mandatory preview, AI-powered Eisenhower / Pomodoro+
coaching / daily briefing / weekly planner, conversation-to-task
extraction, and integrations with Gmail, Slack, and Google Calendar.
Available as a native Android app (Kotlin/Jetpack Compose) and a web app
(React/TypeScript/Vite). Cross-device sync runs through Firebase
Firestore directly; a FastAPI backend on Railway provides AI features,
NLP, app-update metadata, and analytics rollups.

## Download

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.averycorp.prismtask)

## Free vs Pro

PrismTask ships with a two-tier pricing model.

| Feature | Free | Pro ($7.99/mo or $5/mo billed annually at $59.99/yr, 7-day free trial) |
|---------|------|--------------------------|
| Task management, projects, tags, subtasks | Yes | Yes |
| Habit tracking with streaks & analytics | Yes | Yes |
| Templates, widgets, voice, accessibility | Yes | Yes |
| Work-Life Balance Engine | Yes | Yes |
| Mood & energy tracking | Yes | Yes |
| Morning check-in & weekly review | Yes | Yes |
| Boundary rules & burnout detection | Yes | Yes |
| ND-friendly focus modes | Yes | Yes |
| Medication slot system + tier tracking + reminder modes | Yes | Yes |
| Cloud sync across devices | — | Yes |
| AI Eisenhower & Pomodoro+ coaching | — | Yes |
| AI batch ops (NLP-driven multi-task mutations + 30s undo + 24h history) | — | Yes |
| Analytics & time tracking | — | Yes |
| AI briefing, weekly planner, time blocking with horizon selector | — | Yes |
| AI conversation-to-task extraction | — | Yes |
| Collaboration & integrations | — | Yes |
| Google Drive backup/restore | — | Yes |
| Clinical health report export | — | Yes |

AI features run on Claude Haiku; the weekly planner and monthly review use
Claude Sonnet for higher-quality output. Debug builds expose a tier override
in Settings for local development.

## Platforms

### Android

Native app built with Kotlin and Jetpack Compose. See the [Download](#download) section above for Play Store links.

### Web

React + TypeScript + Vite web client with TailwindCSS, deployed at
[`app.prismtask.app`](https://app.prismtask.app). Reads/writes the same
Firestore subtree as the Android app for cross-device sync, and calls the
FastAPI backend for AI features. As of v1.6.0 the web app reaches ~92%
parity with Android across the full feature surface — see
[`docs/WEB_PARITY_GAP_ANALYSIS.md`](docs/WEB_PARITY_GAP_ANALYSIS.md). See
[`web/README.md`](web/README.md) for setup instructions.

### Backend

FastAPI server on Railway, providing AI features (Claude Haiku for NLP,
batch ops, daily briefing, time blocking, classify-text; Claude Sonnet for
weekly planner / monthly review), app-update metadata, analytics rollups,
and feedback inbox. PostgreSQL holds historical CRUD endpoints; the AI
endpoints read directly from Firestore via the Firebase Admin SDK so
they always see the user's live data without a separate ingest path.
Auth is Firebase Auth (with the FastAPI verifying ID tokens). See
[`ARCHITECTURE.md`](ARCHITECTURE.md) for the full data flow.

## Development

### Getting Started

To build and run this project locally, ensure you are using Gradle 9.3.1. The minimum Android SDK level required is 26.

### Firebase Emulator (local testing)

Debug builds connect to a local Firebase Emulator Suite (Firestore + Auth) by default — not production Firestore. Start it with `firebase emulators:start --import=./firebase-emulator-data --export-on-exit=./firebase-emulator-data` before launching a debug APK, or you will see "no data syncing" because the app is talking to `10.0.2.2:8080`. See [`docs/FIREBASE_EMULATOR.md`](docs/FIREBASE_EMULATOR.md) for setup, two-device configs, and troubleshooting. To use production Firestore in a debug build, flip `USE_FIREBASE_EMULATOR` to `false` in `app/build.gradle.kts` before building.

## Roadmap

### v1.6.0 — Shipped

| Area | Feature |
|------|---------|
| Medication | Reminder mode picker (Clock / Interval) with three-level resolver (medication → slot → global default), reactive interval scheduler, synthetic-skip dose anchors, web settings parity |
| Sync hardening | `pushUpdate` delete-wins conflict resolution; full sync test matrix in CI (scenarios 7–11, 14, 15 automated; 12–13 manual runbook) |
| Test infrastructure | StreakCalculator clock-change unit tests (5 cases); orphan healer test cleanup; sync-test harness PR1–3 |
| Repo hygiene | Branch protection on `main`; required status checks renamed to avoid context collisions |

### v1.5.x — Shipped

| Area | Feature |
|------|---------|
| Medication | Slot system with per-day tier states (skipped / essential / prescription / complete), three-level reminder cascade, slot editor in Settings, full A2 #6 + A2 #7 closeout (v1.5.0) |
| Web parity | 22 slices closing the highest-leverage gaps with Android — NLP batch ops, named themes + onboarding, AI briefing + planner, analytics dashboard, conversation extraction, Pomodoro+ coaching, Eisenhower text classifier, task-editor schedule tab, Today polish + Start-of-Day, dedicated medication screen, custom template authoring, mood + energy, morning check-in, boundaries + burnout scorer, focus release + good-enough timer (v1.5.2) |
| Release pipeline | Idempotent GitHub Release creation; backend-upload failure no longer blocks Android publish (v1.5.3) |

### v1.4.x — Shipped

| Area | Feature |
|------|---------|
| Wellness layer | Work-Life Balance Engine, mood + energy tracking, morning check-in + forgiveness streak, weekly review aggregator, boundary rules + burnout scorer, ND-friendly focus modes, medication refill tracking + clinical report, conversation task extractor, custom notification sounds + escalation chains (v1.4.0) |
| Projects (Phase 1) | Lifecycle status (Active / Completed / Archived), milestones, forgiveness-first streak, project widget, NLP create/complete/add-milestone intents (v1.4.0) |
| Sync coverage | Cross-device sync extended to 16 new config + content entity families (v1.4.37 + v1.4.38) |
| AI Time Blocking | Horizon selector (Today / Today+Tomorrow / Next 7 Days), mandatory preview with Approve/Cancel, Eisenhower-aware ranking, hard-constraint routing around existing blocks (v1.4.40) |

### Looking forward

| Status | Area | Feature |
|--------|------|---------|
| 🔜 v1.7+ | Medication reminders | Web Push delivery so reminder settings sync to phone *and* fire on web |
| 🔜 v1.7+ | Medication reminders | Per-medication reminder-mode override UI (touches multiple pickers/dialogs; Android-only landed in v1.6.0) |
| 🔜 v1.7+ | Medication reminders | Web slot-editor per-slot reminder-mode picker (settable from Android only today) |
| 🔜 v1.7+ | Sync test matrix | Tests 12 + 13 (sign-out/sign-in same user; sign-in different user) automated when the OAuth Custom Tab flow becomes UIAutomator-driveable |
| 🔜 v2.0+ | Phase G | Remaining web parity slices toward 100% feature equivalence with Android |
| 🔜 v2.0+ | Calendar | Backend-mediated Google Calendar sync (see `docs/ADR-calendar-sync.md`) |
| 🔜 v2.2+ | Widgets | Re-enable / refresh the eight Glance widgets after Phase G stabilizes |
