---
title: Privacy Policy
description: How PrismTask collects, uses, and protects your data.
---

# PrismTask — Privacy Policy

**Effective date:** 2026-04-24
**Last updated:** 2026-05-07

## Who we are

PrismTask is developed and operated by **Avery Karlin**, a solo developer based in the United States.

- Contact for privacy requests: [privacy@prismtask.app](mailto:privacy@prismtask.app)
- Web app: [https://app.prismtask.app](https://app.prismtask.app)
- Source code: [https://github.com/averycorp/prismTask](https://github.com/averycorp/prismTask)

This policy describes what data PrismTask collects when you use the Android app (package ID `com.averycorp.prismtask`) or the web app at `app.prismtask.app`, how that data is used, where it is stored, and what rights you have over it.

## The short version

- PrismTask works fully offline. If you never sign in, no data ever leaves your device.
- If you sign in with Google, your tasks, habits, and related data sync across your devices via Firebase Firestore. We do not sell or share that data.
- Natural-language task parsing and AI features (Eisenhower, Pomodoro planner, daily briefing, weekly review, NLP batch commands, AI Coach chat) send the relevant text — including medication names you have entered, when batch NLP commands operate on them, and your chat messages plus a snapshot of any task you open chat from — through our backend to Anthropic's Claude API. You can disable all AI processing in **Settings → AI Features → Use Claude AI for advanced features**.
- Crash reports are collected via Firebase Crashlytics to keep the app stable. There is no advertising SDK, no analytics tracker, and no data reseller in the loop.
- You can export all of your data as JSON/CSV from the app at any time. To delete all of your synced data, email `privacy@prismtask.app` (in-app one-tap account deletion is in active development).

## What we collect

### Information you provide directly

- **Google account email and display name** when you sign in with Google Sign-In. Required only if you opt into cloud sync.
- **Content you enter in the app:** task titles, descriptions, due dates, projects, tags, subtasks, habits, habit completions, Pomodoro session metadata, mood and energy logs, morning check-in notes, weekly review notes, medication names and dose history, boundary rules, notification profile settings, custom sounds, chat messages with the in-app coaching assistant, and any other content you create.

### Information collected automatically

- **Firebase Installation ID and Crashlytics install UUID** — used by the Firebase SDKs for core operation and crash triage.
- **Crash reports and diagnostic traces** — collected via Firebase Crashlytics when the app crashes or encounters a non-fatal error. These include your Firebase UID (so we can reproduce a bug in context), the stack trace, device model, OS version, and app state at the time of the crash.
- **Purchase entitlement state** — Google Play Billing tells the app whether you are on the Free or Pro tier. PrismTask never sees card or payment details.

### Information we **do not** collect

- We do not collect your location (no `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`).
- We do not access your contacts, SMS, call history, photos, or files.
- We do not use any third-party advertising or analytics SDKs beyond Firebase Crashlytics for crash diagnostics.

## How we use your data

- **Core functionality:** store your tasks/habits/etc., run reminders and Pomodoro sessions, compute streaks and analytics, and keep everything synchronized across your signed-in devices.
- **AI-powered features (Pro tier, on by default but disable-able):** when you use natural-language quick-add, the Eisenhower matrix auto-classification, AI time blocking, the daily briefing, the weekly review aggregator, the smart Pomodoro planner, the NLP batch-command feature, or the AI Coach chat surface, the relevant text is sent through our backend to Anthropic's Claude API (Claude Haiku for fast NLP and chat; Claude Sonnet for the weekly planner and review). For NLP batch commands such as "skip my medication today", the names of your medications are included in the request so Claude can match the phrase you typed to the correct entity. For AI Coach chat, your chat messages and the last few turns of conversation are forwarded so the AI has multi-turn memory; when chat is opened from a specific task, a snapshot of that task — title, description, due date, priority, project name, and completion state — is also forwarded so the AI can ground its replies in the actual task content. Per [Anthropic's Commercial Terms](https://www.anthropic.com/legal/commercial-terms), Anthropic does not train models on inputs from API customers, and per Anthropic's [API data retention policy](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-personal-data) inputs and outputs are deleted within 30 days of receipt (up to 2 years if a request is flagged for Anthropic's Trust & Safety review). You can turn off all AI processing in **Settings → AI Features → Use Claude AI for advanced features**; when disabled, no PrismTask data is sent to Anthropic and the AI-powered features become unavailable until you re-enable the toggle.
- **Crash diagnostics:** Firebase Crashlytics uses crash reports to help us fix bugs.
- **Voice input (opt-in):** when you tap the microphone, Android's `SpeechRecognizer` processes your speech via Google Speech Services and returns a transcript. PrismTask does not record or retain audio.
- **Calendar sync (opt-in):** when you connect Google Calendar in Settings, the app reads and writes events to your own Google Calendar account.
- **Cross-device sync (opt-in):** when you sign in, your data is stored in Firestore keyed to your Firebase UID. Only your signed-in devices can read it.

## Where your data is stored

- **On your device:** Android Room SQLite database inside the app sandbox. The database name is `prismtask.db`.
- **Firebase Firestore:** in the Google Cloud **`nam5` multi-region** (United States). Firebase project ID: `averytask-50dc5`.
- **Firebase Auth:** Google global infrastructure.
- **Firebase Crashlytics:** Google global infrastructure; retention follows [Firebase's data retention policy](https://firebase.google.com/support/privacy).
- **PrismTask backend (FastAPI):** hosted on [Railway](https://railway.app) in the United States. The backend receives task content for NLP parsing and AI prompts for AI features, forwards them to Anthropic, and returns the structured response. Request payloads are not persisted; access logs contain only routine metadata.
- **Anthropic:** Claude API servers in the United States. Inputs and outputs are deleted within 30 days under Anthropic's standard API retention policy (up to 2 years if a request is flagged for Anthropic Trust & Safety review). Anthropic does not train models on inputs from API customers (Anthropic Commercial Terms § B). PrismTask is currently on the standard API tier; a Zero Data Retention contract upgrade is on the post-launch hardening roadmap.

## Third-party processors

| Processor | Role | What they see |
|---|---|---|
| Google LLC (Firebase Auth, Firestore, Crashlytics, Cloud Storage, Sign-In, Speech Services, Calendar API, Play Billing) | Authentication, cloud sync, crash diagnostics, voice transcription, calendar sync, billing | Your account email, synced content, crash traces, voice audio during recognition, Calendar events you choose to sync, purchase tokens |
| Anthropic, PBC (Claude API) | AI-feature processing | Text you submit for parsing or AI analysis: task titles, descriptions, project names, habit names, schedule data, free-text you paste into NLP / extraction surfaces; for NLP batch commands, your medication names (id + name only; not dosage, frequency, or prescriber); for AI Coach chat, your chat messages plus the last few turns of conversation history, and — when chat is opened from a specific task — a snapshot of that task (title, description, due date, priority, project name, completion state) |
| Railway Corporation | Backend hosting | HTTPS traffic in transit to the FastAPI backend |

No processor listed above is authorized to sell or resell your data. We do not share data with advertisers, data brokers, or analytics firms.

## Your rights

You can, at any time:

- **Export your data** via Settings → Data → Export. This produces a JSON file with all your entities and preferences, plus an optional CSV export of tasks.
- **Import a prior backup** via Settings → Data → Import with either merge or replace semantics.
- **Sign out** from Settings → Account to stop syncing further changes to the cloud. Previously synced data remains in Firestore until you request deletion.
- **Clear local data** via Android Settings → Apps → PrismTask → Storage → Clear storage. This wipes the on-device Room database and DataStore preferences.
- **Delete your account in-app** from the Android app: **Settings → Account & Sync → Delete Account**. The deletion takes effect immediately — you are signed out and all on-device data is wiped — and is reversible for **30 days** by signing back in with the same Google account. After 30 days the deletion is permanent: your backend record is removed, your Firebase Auth record is deleted via Firebase Admin SDK, and any orphaned Firestore data becomes unreachable (a periodic admin sweep removes it on a best-effort schedule). If you cannot access your account or are unable to use the Android app, email [privacy@prismtask.app](mailto:privacy@prismtask.app) and we will wipe your data manually within 30 days.

If you are a resident of the EU/UK (GDPR), California (CCPA/CPRA), or another jurisdiction with data-subject rights, you also have:

- A right to access the data we hold about you.
- A right to correct inaccurate data.
- A right to portability (fulfilled by the JSON export above).
- A right to object to or restrict processing.
- The right to lodge a complaint with your local data protection authority.

To exercise any of these rights, email [privacy@prismtask.app](mailto:privacy@prismtask.app).

## Children

PrismTask is intended for users aged **18 and older**. We do not knowingly collect data from children under 18. If you believe a child under 18 has created an account, contact [privacy@prismtask.app](mailto:privacy@prismtask.app) and we will delete the account and its data.

## Data security

- All network traffic uses HTTPS/TLS.
- `network_security_config.xml` on Android denies cleartext traffic.
- Firebase Auth handles credential storage; PrismTask never sees your Google password.
- Sensitive local preferences are stored via `androidx.security:security-crypto` encrypted DataStore.
- Cloud data access is scoped to your Firebase UID via Firestore Security Rules; no other user can read or write your collection.

## Changes to this policy

When this policy changes, the "Last updated" date at the top of this page changes and a new entry is added to the changelog below. We will not materially expand data collection without updating this page; if we ever do, we will notify active users in-app.

## Changelog

- **2026-05-07** — Disclosed that AI Coach chat now forwards your chat
  messages, the last few turns of conversation history, and — when
  chat is opened from a specific task — a snapshot of that task
  (title, description, due date, priority, project name, completion
  state) to Anthropic's Claude API. This egress shipped in PR #1164;
  this update enumerates the specifics in the privacy policy and the
  Data Safety form, and re-fires the in-app first-run AI Chat
  disclosure dialog so existing users see the updated wording once.
  No change to what data is collected — disclosure update only. As
  with all AI features, the egress can be disabled in
  Settings → AI Features → "Use Claude AI for advanced features".
- **2026-04-26** — Disclosed that medication names are included in
  Anthropic Claude API requests for the NLP batch-command feature
  (e.g. "skip my Adderall today"). Corrected the prior text that
  described Anthropic processing as "zero-retention" — the standard
  API retention is 30 days (up to 2 years if Trust & Safety review is
  triggered). Added a Settings → AI Features master toggle that
  disables all Claude API requests when off. PrismTask backend
  middleware additionally rejects requests carrying a client-supplied
  opt-out header so a stale client cannot accidentally exfiltrate
  after the user has disabled AI features. No change to what data is
  collected — disclosure update only.
- **2026-04-25** — Replaced the email-only deletion path with the in-app
  Settings → Account & Sync → Delete Account flow (30-day grace window,
  reversible by signing back in). Email deletion is preserved as a
  fallback for users who can't access the Android app. No change to
  what data is collected.
- **2026-04-24** — Initial version published.
