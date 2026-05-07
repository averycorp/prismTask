# Data Safety Form — PrismTask

**Destination:** Play Console → Policy → Data safety.
**Source of truth:** Phase 1 audit §4. This document must stay consistent with `../../privacy/index.md` — if one changes, update the other.

This file answers every question on Play Console's current Data safety form as of 2026-05-07. Copy each answer into the corresponding Play Console field.

---

## 1. Data collection and security

### 1.1 Does your app collect or share any of the required user data types?

**Yes.**

### 1.2 Is all of the user data collected by your app encrypted in transit?

**Yes.** All network traffic uses HTTPS/TLS. Firestore and Firebase Auth SDK default to TLS. The custom FastAPI backend on Railway is served over HTTPS; the Android client uses OkHttp 4 with default TLS. `network_security_config.xml` enforces cleartext denial.

### 1.3 Do you provide a way for users to request that their data be deleted?

**Yes.** Users can:
- **In-app** (Android): Settings → Account & Sync → Delete Account. Takes effect immediately, signs the user out, wipes local data, and schedules permanent deletion in 30 days. Reversible during the grace window by signing back in. After 30 days the backend deletes the Postgres record and Firebase Auth record (via Firebase Admin SDK).
- Sign out from Settings → Account & Sync to stop further cloud sync (without scheduling deletion).
- Clear local data via system Settings → Apps → PrismTask → Storage → Clear storage (wipes the on-device Room database and DataStore).
- Email `privacy@prismtask.app` for users who can't access the Android app — Firestore user collection + Firebase Auth user are wiped within 30 days.

---

## 2. Data types collected

### Personal info

| Data type | Collected? | Optional? | Linked to user? | Shared with third parties? | Processing purpose | Collection reason |
|---|---|---|---|---|---|---|
| Name | Yes | Yes (only when signed in) | Yes | No | App functionality (greeting, task attribution) | Account management |
| Email address | Yes | Yes (local-only mode is supported) | Yes | No | Account management, auth | Account management |
| User IDs | Yes (Firebase UID, Google user ID) | Yes | Yes | No | App functionality, account management | Core SDK operation |
| Address, phone number, race/ethnicity, sexual orientation, religion, political views | **No** | — | — | — | — | — |
| Other personal info | **No** | — | — | — | — | — |

### Financial info

| Data type | Collected? | Notes |
|---|---|---|
| User payment info | **No** | Google Play Billing processes all payments; PrismTask never sees card data. |
| Purchase history | Yes (entitlement signal only) | Linked, not shared, purpose: account management. Play Billing surfaces Pro tier as a boolean to the app. |
| Other financial info | **No** | — |

### Health and fitness

| Data type | Collected? | Optional? | Linked to user? | Shared? | Purpose |
|---|---|---|---|---|---|
| Health info (mood, energy, medications, medication dose history) | Yes | **Yes — entirely optional features** | Yes | **Yes (medication names only) — sent to Anthropic via PrismTask backend when the user invokes the NLP batch-command feature on a medication (e.g. "skip my medication today"). Mood, energy, and dose history are NOT shared.** | App functionality (wellness tracking, reminder scheduling, refill projection, clinical report export, AI batch NLP commands) |
| Fitness info | **No** | — | — | — | — |

**Disclose prominently:** medication data and mood logs are sensitive health information. They live in the user's local Room DB and only leave the device if the user is signed in and cloud sync is enabled. Medication *names* are additionally transmitted to Anthropic's Claude API when a user invokes an AI batch NLP command (Pro feature) — see "Other user-generated content" row below for the AI processing detail. Users can disable all AI processing in Settings → AI Features → "Use Claude AI for advanced features".

### Messages

| Data type | Collected? | Optional? | Linked to user? | Shared? | Purpose |
|---|---|---|---|---|---|
| Emails (Gmail integration only — subject, snippet, sender address, message date) | Yes | **Yes — entirely optional. Requires the user to (a) be on Pro, (b) connect their Gmail account, and (c) trigger an inbox scan.** | Yes (when scanned) | **Yes — sent to Anthropic for AI task extraction. Email bodies and attachments are NOT sent.** | App functionality (Gmail-to-task suggestion extraction). Disable in Settings → AI Features. |
| SMS / MMS | **No** | — | — | — | — |
| Other in-app messages (AI Coach chat) | Yes | **Yes — entirely optional. Pro feature; AI processing can be disabled in Settings → AI Features.** | Yes (when signed in) | **Yes — chat messages plus the last few turns of user/assistant history are sent to Anthropic for AI coaching responses. When chat is opened from a specific task, a task-content snapshot (title, description, due date, priority, project name, completion state) is also sent so the AI can ground its replies.** | App functionality (AI Coach chat). Disable in Settings → AI Features. |

**Note on email data:** PrismTask does not retain email content. The Gmail scan endpoint reads the inbox window the user requests (`since_hours`, default 24h, max 168h), forwards the subject / snippet / from-address / date of each message to Anthropic's Claude API for extraction, stores only the resulting *suggested tasks* in our database, and then discards the raw email metadata. Email bodies are never read. The full message (in Gmail) is unaffected. Toggling Settings → AI Features → "Use Claude AI for advanced features" off blocks the scan endpoint on both the Android client and the FastAPI backend (returns HTTP 451 before any Anthropic call).

### Photos and videos

| Data type | Collected? | Notes |
|---|---|---|
| Photos | **No** | App has no camera or gallery access. |
| Videos | **No** | — |

### Audio files

| Data type | Collected? | Optional? | Linked to user? | Shared? | Purpose |
|---|---|---|---|---|---|
| Voice or sound recordings | Yes (transient) | Yes (voice dictation is opt-in) | Not retained by PrismTask | Shared with Google Speech Services during the recognition call only | Voice input for quick-add |
| Music files | **No** | — | — | — | — |
| Other audio files | **No** | — | — | — | — |

**Note on voice:** audio is captured via `android.speech.SpeechRecognizer`. PrismTask receives the transcribed string; it does not persist the audio. Google's speech recognizer is the downstream processor. This is Android-native behavior and does not require a separate Anthropic trip.

### Files and docs

| Data type | Collected? | Notes |
|---|---|---|
| Files and docs | **No** | Data export produces a file the user downloads; the app does not read user files from outside its sandbox. |

### Calendar

| Data type | Collected? | Optional? | Linked? | Shared? | Purpose |
|---|---|---|---|---|---|
| Calendar events | Yes (only when Google Calendar sync is enabled) | Yes | Yes | Shared with Google (user's own Calendar account) | Two-way calendar sync — tasks with times sync to Calendar and back |

### Contacts

| Data type | Collected? | Notes |
|---|---|---|
| Contacts | **No** | No READ_CONTACTS permission declared. |

### App activity

| Data type | Collected? | Optional? | Linked? | Shared? | Purpose |
|---|---|---|---|---|---|
| App interactions | Yes (Crashlytics session signals) | No | Yes | Shared with Google/Firebase | Diagnostics |
| In-app search history | **No** | — | — | — | — |
| Installed apps | **No** | — | — | — | — |
| Other user-generated content (task titles, descriptions, project names, habit names, mood log notes, medication names, chat with coaching assistant) | Yes | Yes (all user-generated content features are optional; AI processing is disable-able in Settings → AI Features) | Yes (when signed in) | Task / habit / project / medication names sent to Anthropic via PrismTask backend for NLP parsing and AI features. AI Coach chat additionally sends chat messages, the last few turns of user/assistant history, and — when chat is opened from a specific task — a task-content snapshot (title, description, due date, priority, project name, completion state). Anthropic does not train on inputs (Anthropic Commercial Terms § B). Anthropic standard API retention is 30 days (up to 2 years if a request is flagged for Trust & Safety review) | App functionality |
| Other app activity (task completion history, habit streak data, Pomodoro session counts) | Yes | No (core functionality) | Yes (when synced) | No | App functionality, analytics inside the user's own dashboard |

### Web browsing

| Data type | Collected? | Notes |
|---|---|---|
| Web browsing history | **No** | — |

### App info and performance

| Data type | Collected? | Optional? | Linked? | Shared? | Purpose |
|---|---|---|---|---|---|
| Crash logs | Yes | No | Yes (Firebase UID attached for triage) | Shared with Google/Firebase | Analytics (crash triage) |
| Diagnostics (ANRs, performance traces) | Yes | No | Yes | Shared with Google/Firebase | Analytics |
| Other app performance data | **No** | — | — | — | — |

### Device or other IDs

| Data type | Collected? | Optional? | Linked? | Shared? | Purpose |
|---|---|---|---|---|---|
| Device IDs (Firebase Installation ID, Crashlytics install UUID) | Yes | No | Yes | Shared with Google/Firebase | Core SDK operation |

---

## 3. Third-party processors (disclose in policy + security-practices section)

| Processor | Data seen | Purpose |
|---|---|---|
| Google (Firebase Auth, Firestore, Cloud Storage, Crashlytics, Google Sign-In, Google Speech Services, Google Calendar API, Google Play Billing) | All synced user data; voice audio during recognition; calendar events on user's own Calendar; crash reports | Auth, cloud sync, diagnostics, voice input, calendar sync, billing |
| Anthropic PBC (Claude Haiku and Claude Sonnet, via PrismTask backend) | Task content strings submitted for NLP parsing; AI-feature prompts and context (Eisenhower, Pomodoro planning, briefing, weekly review, NLP batch commands); medication names (id + name only — not dosage, frequency, or prescriber) when AI batch NLP commands operate on medications; **email subjects, snippets, sender addresses, and message dates from the user's Gmail inbox when they invoke the Gmail-to-task scan (Pro + opt-in; gated by Settings → AI Features)**; **AI Coach chat messages, rolling user/assistant conversation history (last few turns), and — when chat is opened from a specific task — a task-content snapshot (title, description, due date, priority, project name, completion state)** | AI features. Anthropic does not train on inputs (Commercial Terms § B). Anthropic standard API retention is 30 days (up to 2 years if a request is flagged for Trust & Safety review) |
| Railway Corp | In-transit traffic to the FastAPI backend | Hosting |

---

## 4. Security practices statement (free-text in Play Console)

> Data is encrypted in transit using TLS. Android users can delete their account in-app from Settings → Account & Sync → Delete Account, with a 30-day grace period during which signing back in restores the account. After the grace window the backend permanently deletes the Postgres user record and the Firebase Auth account via Firebase Admin SDK. Users who cannot access the Android app can request deletion by emailing privacy@prismtask.app, or wipe local-only data by uninstalling the app and clearing storage. Users can export a full JSON backup of their data at any time from Settings. PrismTask does not sell user data, does not use tracking SDKs beyond Firebase Crashlytics for crash diagnostics, and does not serve ads. AI features process task content (and, for NLP batch commands, medication names; for Gmail integration, email subjects/snippets/sender addresses; for AI Coach chat, chat messages plus rolling conversation history and — when chat is opened from a task — a task-content snapshot covering title, description, due date, priority, project name, and completion state) through the PrismTask backend which calls Anthropic's Claude API under Anthropic's standard commercial terms — Anthropic does not train models on API inputs (Commercial Terms § B) and inputs/outputs are deleted within 30 days (up to 2 years if a request is flagged for Anthropic Trust & Safety review). Users can disable all AI processing in Settings → AI Features → "Use Claude AI for advanced features"; when disabled, the app makes no Anthropic calls (including no Gmail scans and no chat coaching calls) and the AI-powered features become unavailable.

---

## 5. Target audience confirmation

PrismTask is intended for adults (18+). See `compliance/categorization.md` for the reasoning behind the age band.
