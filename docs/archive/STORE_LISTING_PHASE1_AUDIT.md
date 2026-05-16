# PrismTask — Play Store Listing Audit (Phase 1)

**Audit date:** 2026-04-24
**Worktree:** `C:\Projects\prismtask-store-listing` (branch `feature/play-store-listing`, based on `origin/main` @ `b8a57836`)
**Target track:** Closed testing

---

## ⚠️ CRITICAL — contradiction with prompt context

The setup prompt describes the current state as **v1.3.110 / versionCode 632 / v1.4.0 in progress / v1.4.0 → v1.5.0 → v2.0.0 roadmap**. The actual audit-current state is **~6 months ahead** of that snapshot:

| Field | Prompt claim | Code-of-record |
|---|---|---|
| `versionName` | 1.3.110 | **1.5.3** (`app/build.gradle.kts:26`) |
| `versionCode` | 632 | **689** |
| Latest tag | — | **`v1.5.3`** (Sonnet + Opus bumps already shipped through `v1.5.2`, `v1.5.0`, `v1.4.40`) |
| v1.4.0 status | "in progress" | Fully shipped; v1.4.40 was the last 1.4 release |
| v1.5.0 | "AI suite, planned" | Shipped |
| Web app | not mentioned in current-state | **Shipping feature** — React/TypeScript/Vite/Tailwind in `web/`, served at `app.prismtask.app`, full feature parity plus live AI/NLP backend |

**Resolution adopted for this audit and all downstream copy:** treat `v1.5.3` (versionCode 689) as the current published/closed-testing version. Release notes targeted at the imminent bump are written for the *next* release (**v1.6.0** — minor bump per user direction since medication reminder modes are a real feature addition) rather than a mythical "v1.4.0". The "v2.0.0 production launch" release-notes template is preserved for the eventual store graduation.

---

## ⚠️ CRITICAL — Play Store policy risks

Two items that must be resolved before submission (they will block the listing or draw policy review):

### 1. `REQUEST_INSTALL_PACKAGES` in `AndroidManifest.xml`
`AndroidManifest.xml:39` declares `android.permission.REQUEST_INSTALL_PACKAGES`, used by `AppUpdater.kt` to install sideloaded APK updates (Firebase App Distribution flow). **Google Play disallows this permission for non-file-manager apps** unless the app's "core functionality" is installing apps. Play distribution already uses Google Play's in-app update API (Play Core) — the APK self-updater is only meaningful for sideloaded/FAD builds.

**Recommendation (flag for user — do not edit in Phase 1):** split the permission into a non-Play build variant, or gate its declaration via `tools:node="remove"` in a `playRelease` manifest overlay. The current declaration will produce a Play Console pre-launch warning and may require a permission-declaration form answer. The store listing must still disclose this permission as present if shipped as-is.

### 2. Launcher icon mismatch (Play Store icon ≠ in-app adaptive icon)
- **On-device (adaptive icon, API 26+)** — rainbow-refracting prism, vector, deep near-black background (`drawable/ic_launcher_background.xml` = `#0B0B14`, `drawable/ic_launcher_foreground.xml` = prism + RGB rainbow vectors). This is the correct "PrismTask" brand mark.
- **Play Store icon (raster fallback `mipmap-*/ic_launcher.png`)** — **a purple square with a yellow infinity loop.** This is a leftover placeholder and does **not** match the product name or the in-app adaptive icon.

Because `minSdk=26`, every device that installs PrismTask sees the adaptive icon. But the **Play Store listing** pulls `ic_launcher.png` from `mipmap-xxxhdpi/` (9,446 bytes, 192×192), so the Play listing will currently display the purple infinity placeholder, not the prism.

**Recommendation (open question for user):** generate a new 512×512 Play Store icon that matches the adaptive icon's prism+rainbow brand. Do **not** overwrite the in-app `mipmap-*/ic_launcher.png` in this workstream (separate change, belongs in a follow-up PR once the branding is approved).

### 3. SMS / alarm / foreground service disclosure
`USE_FULL_SCREEN_INTENT`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` each require a justification in Play Console. These are all standard for reminder/timer apps but must be explicitly declared in the Permission Declaration form. See `compliance/permissions-justifications.md` (generated in Phase 2).

---

## 1. Version reality (verified)

From `app/build.gradle.kts`:

```
applicationId = "com.averycorp.prismtask"
namespace     = "com.averycorp.prismtask"
compileSdk    = 35
minSdk        = 26 (Android 8.0 Oreo)
targetSdk     = 35 (Android 15)
versionCode   = 689
versionName   = "1.5.3"
```

- JVM target 21, Kotlin 2.3.20, AGP 9.1.0, Jetpack Compose BOM 2024.12.01.
- Release signing keyed off `KEYSTORE_PATH` env var; falls back to debug keystore if unset (CI-only release signing).
- `WIDGETS_ENABLED = false` compile-time flag. All widget receivers have `android:enabled="false"` in `AndroidManifest.xml` (lines 167, 180, 194, 216, 229, 242, 254, 266). Comment: "Disabled for v1.0 release. Re-enable in v1.2 (Phase G)." — **memory says widgets re-enable in v2.2; the manifest comment says v1.2.** Both are aspirational; **widgets are disabled in the shipped build and must not appear in marketing copy.**
- R8 minification + resource shrinking enabled for release builds.
- Firebase App Distribution wired for debug builds (internal tester group).

Latest git tags (descending by creation date): `v1.5.3, v1.5.2, v1.5.0, v1.4.40, v1.3.62 … v1.3.37`. The v1.4 line has 40 patches behind it; v1.5 has 3 releases.

---

## 2. Feature inventory (from shipped code)

Source: `app/src/main/java/com/averycorp/prismtask/ui/screens/` directory listing. Every feature below has a screen package (verified UI-wired, not backend-only) unless explicitly noted. Features in memory/CLAUDE.md but **not** represented by a screen are flagged.

### Core task management (stable, free tier)
- **Today** focus screen with progress ring, overdue/today/planned sections, plan-for-today sheet (`screens/today/`)
- **Task list** with drag-to-reorder, bulk edit, multi-select, configurable swipe actions (`screens/tasklist/`)
- **Add/edit task** — tabbed bottom sheet: Details / Schedule / Organize (`screens/addedittask/tabs/`)
- **Projects** with lifecycle (planning/active/on-hold/completed/archived), milestones, forgiveness streak, Firestore sync (`screens/projects/`)
- **Tags** with color picker and usage stats (`screens/tags/`)
- **Templates** — task + project + habit templates with built-ins (`screens/templates/`)
- **Search** with full-text and saved filters (`screens/search/`)
- **Archive** with restore flow (`screens/archive/`)

### Views
- **Week view** (`screens/weekview/`)
- **Month view** (`screens/monthview/`)
- **Timeline** — daily time-block visualization with current-time indicator (`screens/timeline/`)

### Habits
- Habit list, streak tracking, contribution grid, analytics, weekly summary notification (`screens/habits/`)
- Bookable habits via `HabitLogEntity` for historical activity
- Built-in habit identity + reconciliation (6 known built-ins: Water, Sleep, Exercise, Meditation, Reading, Medication)

### Focus / timing
- **Pomodoro** — energy-aware, AI-planned sessions, foreground service (`screens/pomodoro/` + `screens/timer/`)
- **Focus Release** — neurodivergence-friendly focus flow with "good enough" timer and ship-it celebrations (`screens/checkin/` uses the resolver)

### Wellness (v1.4.x, all shipped)
- **Work-Life Balance** — `LifeCategory` enum per task, balance bar on Today, weekly balance report (`screens/balance/`)
- **Mood & energy tracking** — correlation engine, Mood Analytics screen (`screens/mood/`)
- **Morning check-in** + check-in streak (`screens/checkin/`)
- **Weekly review** — guided end-of-week reflection (`screens/review/`)
- **Boundaries** — user-declared work-hours / category limits, `BoundaryEnforcer`, auto profile-switcher on overload
- **Burnout detection** — `BurnoutScorer` + `OverloadCheckWorker`
- **ND-friendly modes** — Brain Mode, UI Complexity tier, Forgiveness Streak, Shake-to-capture

### Medication (major v1.4/v1.5 cluster)
- Medication list + doses + refill projection + clinical report generator (`screens/medication/`)
- Reminder modes (clock / interval) — Settings + slot editor UI shipping in v1.5.3 (per CHANGELOG Unreleased, PR3 of 4)
- Synthetic-skip doses for interval-mode anchor integrity

### AI-powered (Pro tier only)
- **Eisenhower matrix** — auto-classification with manual-override persistence (`screens/eisenhower/`)
- **AI weekly review aggregator**
- **Smart planner / AI time blocking** (`screens/planner/`)
- **Daily briefing** (`screens/briefing/`)
- **Coaching** (`screens/coaching/`) — uses `CoachingRepository` + `ChatRepository`
- **Conversation task extractor** — pull tasks from chat transcripts via Android share sheet intent-filter `SEND text/plain` (`screens/extract/`)

### Accessibility & customization
- TalkBack labels, dynamic font scaling, high-contrast mode, keyboard focus traversal, reduced-motion gates (`ui/a11y/`)
- Voice input: `VoiceInputManager`, `VoiceCommandParser`, `TextToSpeechManager`
- 35+ settings sections under `screens/settings/sections/`

### Data & sync
- Firebase Auth via Google Sign-In (Credential Manager)
- Firestore for cross-device sync (`SyncService.kt`) — primary sync mechanism
- `GenericPreferenceSyncService` + per-preference sync services (Theme, Sort)
- Full JSON export/import (export format v5 per README), CSV task export
- Google Drive backup/restore (Pro)

### Integrations
- Google Calendar two-way sync (`CalendarSyncService`)
- FastAPI backend (`https://averytask-production.up.railway.app`) for Claude-Haiku NLP parsing (`ClaudeParserService.kt`)
- Note: README claims "first-class integrations with Gmail, Slack, and Google Calendar." **Android UI only wires Google Calendar.** Gmail/Slack endpoints exist on the backend but are not exposed in the Android app. → **Do NOT claim Gmail or Slack integrations in Play Store copy** until their Android UI ships.

### Themes (four shipped, verified in `PrismThemeColors`)
- **Cyberpunk** — deep near-black + cyan primary `#00F5FF`, magenta secondary `#FF00AA`
- **Synthwave** — plum background + hot-pink primary `#FF2D87`, violet secondary `#6E3FFF`
- **Matrix** — near-black green + phosphor primary `#00FF41`, lime secondary `#AAFF00`
- **Void** — neutral near-black + lavender primary `#C8B8FF`, muted-blue secondary `#8888CC`

Each theme ships: 26 palette tokens + 8-entry data-visualization palette + per-theme font pairing. Default system theme (non-Prism) also exists: `Color.kt` `LightColors` / `DarkColors` Material 3 schemes.

### Features present in memory but NOT in Play Store marketing scope
- **Widgets (all 8)** — manifest-disabled, compile-time flag off. Must not appear in copy.
- **Gmail / Slack / Zapier integrations** — backend-only. Must not appear in copy.
- **Google Drive backup/restore** — code exists (`GoogleDriveService.kt`), README calls it a Pro feature. Verify UI is wired before claiming it.

---

## 3. Permissions audit

Every permission declared in `AndroidManifest.xml` (lines 5-52) with a one-line user-facing justification. This feeds `compliance/permissions-justifications.md` and Play Console's permission declaration form.

| Permission | User-facing justification |
|---|---|
| `INTERNET` | Sync tasks across your devices and fetch AI-powered task parsing. |
| `ACCESS_NETWORK_STATE` | Detect when you're offline so sync queues mutations instead of failing. |
| `POST_NOTIFICATIONS` | Show task reminders, habit streak nudges, and Pomodoro completion alerts. |
| `USE_FULL_SCREEN_INTENT` | Display urgent reminders over the lock screen when "Full-Screen Notifications" is on in app settings. |
| `SCHEDULE_EXACT_ALARM` (API 31-32) | Deliver reminders at the exact minute they're due, not minutes late. User-toggleable in system Settings. |
| `USE_EXACT_ALARM` (API 33+) | Same as above, auto-granted on modern Android for reminder apps. |
| `RECEIVE_BOOT_COMPLETED` | Re-register your scheduled reminders after a phone restart so they still fire. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask the user (once) to exempt PrismTask from aggressive OEM battery optimization so reminders aren't silently dropped. |
| `REQUEST_INSTALL_PACKAGES` | **⚠️ Only used by sideloaded/Firebase-App-Distribution builds to install app self-updates. Does not fire on Play Store builds.** See Play policy risk #1 above. |
| `RECORD_AUDIO` | Voice dictation — speak a task instead of typing it. Only active while the mic button is held. |
| `VIBRATE` | Per-profile custom vibration patterns on notifications. |
| `FOREGROUND_SERVICE` | Keep the Pomodoro timer alive when you switch apps so the countdown doesn't die. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Pomodoro service type declared as `mediaPlayback` because Samsung One UI suppresses `specialUse` notifications. |

**Queries:** declares `<intent action="android.speech.RecognitionService" />` so voice input can find an available speech recognizer.

**Intent filters:** `MainActivity` accepts `ACTION_SEND text/plain` to receive shared chat transcripts for `ConversationTaskExtractor`. `BootReceiver` handles `BOOT_COMPLETED` (exported, as required).

---

## 4. Data flow audit (feeds Data Safety form)

### Data types handled

| Data type | Collected | Linked to user | Shared | Optional | Deletion | Purpose |
|---|---|---|---|---|---|---|
| Email address | Yes (via Google Sign-In) | Yes | No | Yes (local-only mode supported) | Sign out + delete account | Account auth |
| Google account user ID | Yes | Yes | No | Yes | Sign out + delete account | Account auth, Firestore row ownership |
| Name (Google profile display name) | Yes | Yes | No | Yes | Sign out + delete account | Greeting in UI |
| App activity — tasks, projects, tags, habits, completions, mood logs, check-ins, medication doses, focus sessions, Pomodoro sessions, weekly reviews | Yes (stored locally always; cloud-synced only when signed in) | Yes (when synced) | No | N/A (core functionality) | Full export + delete-all + account deletion | Core productivity features |
| Task content sent for NLP parsing | Transient (request → response, not retained) | Yes (backend session token attaches user identity) | Shared with Anthropic (Claude Haiku) via backend proxy | Yes — falls back to regex parser when offline or NLP is disabled | N/A (not persisted) | Quick-add natural-language task parsing |
| Crash reports + stack traces | Yes (Firebase Crashlytics) | Yes (user ID attached for triage) | Shared with Google/Firebase | No (cannot be disabled from UI today) | Data aged out by Firebase per their retention | Crash triage |
| Device identifiers (Firebase Installation ID) | Yes (Firebase default) | Yes | Shared with Google/Firebase | No | Firebase TTL | Core SDK operation |
| Approximate / precise location | **No** | — | — | — | — | Not used anywhere in the app |
| Contacts | **No** | — | — | — | — | Not used |
| Photos / videos / files | **No** | — | — | — | — | Not used |
| SMS / call history | **No** | — | — | — | — | Not used |
| Health / fitness | Medication doses + mood/energy values stored locally (and in cloud when synced) | Yes (when synced) | No | Yes (optional feature) | Full export + delete-all | Wellness tracking |
| Financial info | **No** | — | — | — | — | Google Play Billing handles purchase signals; no card data touches the app |
| Audio | Transient capture for voice dictation via `SpeechRecognizer` | No (on-device / Google Speech Services) | Shared with Google's speech recognizer | Yes | N/A (not retained by PrismTask) | Voice-to-task |

### Where data lives
- **Local (always):** Room SQLite DB (v61 per CHANGELOG, named `prismtask.db`) inside app internal storage. Full data works offline.
- **Firebase Firestore:** cloud sync when signed in. Project ID `averytask-50dc5`. **Region not explicit in `google-services.json`** — likely the Firebase-project default (nam5 / us-central1). **→ Open question for user: confirm Firestore region for the privacy policy text.**
- **FastAPI backend:** `averytask-production.up.railway.app` — hosted on Railway (US-East by default). Receives: auth token + NLP parse requests (task content strings) + AI feature requests (Eisenhower classification, Pomodoro planning, briefing, planner, weekly review). Backend calls Anthropic Claude Haiku/Sonnet with the server's API key — **users never supply their own Claude key.**
- **Firebase Storage:** `averytask-50dc5.firebasestorage.app` — used by Crashlytics SDK; no user-generated file uploads from the Android app.

### Third-party processors (must be disclosed)
1. **Google / Firebase** — Auth, Firestore, Crashlytics, Cloud Storage
2. **Anthropic (PBC)** — receives task-content strings for NLP parsing and AI-feature strings via the server-side proxy. Anthropic's [zero-retention terms](https://www.anthropic.com/legal) apply for API traffic.
3. **Railway Corporation** — hosts the FastAPI backend. Sees all traffic in transit; does not process payloads.
4. **Google Play Billing** — Pro subscription purchase signals. No card data touches PrismTask.
5. **Google Calendar API** — two-way calendar sync when the user opts in via Settings.
6. **Google Speech Services** — invoked via `SpeechRecognizer` for voice dictation. Audio briefly touches Google's servers.

### User rights / deletion
- **Export:** Settings → "Export data" produces a full JSON (all entities + preferences) + optional CSV task export. Triggers `DataExporter.kt`.
- **Account deletion:** Settings → "Delete account" wipes Firestore user collection + Firebase Auth user, then clears local Room DB. Confirm the UI path actually exists before claiming this in the policy. (`SyncService.kt` has the delete-user-data entry points.)
- **Local wipe:** Clear app data from system Settings wipes Room + DataStore.

---

## 5. Theme tokens (for SVG generation in Phase 2)

Every hex value below is copied verbatim from `app/src/main/java/com/averycorp/prismtask/ui/theme/ThemeColors.kt`. These drive `docs/store-listing/graphics/theme-tokens.json` in Phase 2.

### Cyberpunk (lines 94-133)
```
background      #0A0A0F   onBackground     #E0F8FF
surface         #0D0D18   onSurface        #A0CCD4
surfaceVariant  #111120   muted            #4A8A9A
border          #1A00F5FF (alpha 10%)
primary         #00F5FF   secondary        #FF00AA
urgentAccent    #FF00AA   urgentSurface    #1A0010
success         #00FF88   warning          #FFCC00
destructive     #FF3366   info             #0099FF
```

### Synthwave (lines 135-174)
```
background      #0D0717   onBackground     #F0D0FF
surface         #130820   onSurface        #B080D0
surfaceVariant  #1A0F2E   muted            #5E3A7A
border          #2E6E3FFF (alpha 18%)
primary         #FF2D87   secondary        #6E3FFF
urgentAccent    #FF2D87   urgentSurface    #1F0015
success         #00EE88   warning          #FFCC00
destructive     #FF2255   info             #44AAFF
```

### Matrix (lines 176-215)
```
background      #010D03   onBackground     #B0FFB8
surface         #010F04   onSurface        #70CC80
surfaceVariant  #021206   muted            #1A5E25
border          #2300FF41 (alpha 14%)
primary         #00FF41   secondary        #AAFF00
urgentAccent    #AAFF00   urgentSurface    #0A1400
success         #00EE33   warning          #BBEE00
destructive     #FF4422   info             #00AA88
```

### Void (lines 217-256)
```
background      #111113   onBackground     #DCDCE4
surface         #161618   onSurface        #A0A0AB
surfaceVariant  #1E1E22   muted            #3E3E4A
border          #802E2E34 (alpha 50%)
primary         #C8B8FF   secondary        #8888CC
urgentAccent    #E8A0A0   urgentSurface    #261616
success         #6EC87A   warning          #D4A843
destructive     #E06060   info             #7090C8
```

---

## 6. Existing branding

### App name
- `res/values/strings.xml:3` → `app_name = "PrismTask"`
- Manifest uses `android:label="@string/app_name"`. No localized overrides.

### Launcher icons
- **Adaptive icon (on-device, API 26+):**
  - `ic_launcher_background.xml`: solid `#0B0B14` near-black
  - `ic_launcher_foreground.xml`: triangular glass prism with an incoming white light beam refracted into a 6-color RGB rainbow fan (red `#FF3B30`, orange `#FF9500`, yellow `#FFCC00`, green `#34C759`, blue `#007AFF`, violet — final stroke truncated in read; confirm before rasterizing). This is the correct brand mark.
- **Play Store / pre-API-26 fallback PNG:** `mipmap-xxxhdpi/ic_launcher.png` = **purple square with yellow infinity loop** (9.4 KB, 192×192). Does not match the brand. See Play Store policy risk #2.

### Taglines / marketing copy found in repo
- **README.md line 16:** "A cross-platform task manager and wellness-aware productivity layer with a Python API backend."
- **`web/index.html:8-9`** (`app.prismtask.app` landing page head):
  - `<title>PrismTask — Smart Task Management</title>`
  - Meta description: *"Smart task management with AI-powered NLP, habit tracking, and productivity analytics. Organize your work and life with PrismTask."*
  - OG description: *"Smart task management with AI-powered NLP, habit tracking, and productivity analytics."*
  - Theme-color meta: `#6366f1` (indigo) — doesn't match any of the four Prism themes, but it's the default fallback for the web favicon.

### Web client tech (context only — not for marketing copy)
- React 19 + TypeScript 6 + Vite 8 + Tailwind 4, Zustand state, React Router 7
- Eight Google Font families (Chakra Petch, Audiowide, Rajdhani, Monoton, Share Tech Mono, VT323, Space Grotesk, Fraunces) to back the four theme font-pairs
- 17 routes covering Today / Tasks / Projects / Habits / Calendar (week/month/timeline) / Eisenhower / Pomodoro / Templates / Archive / Settings + Auth

---

## 7. Screenshot strategy — feasibility

**Feasible.** Every screen in the strategy list is UI-wired on Android, and the theme tokens cover every color needed for faithful mockups. The 8 strongest screens to mock:

1. **Today — Cyberpunk** — progress ring, balance bar, overdue/today sections, quick-add bar
2. **Eisenhower matrix — Synthwave** — 4-quadrant view with manual-override indicator, auto-classification chip
3. **Habits with streak — Matrix** — contribution grid, streak flame, weekly dots, built-in habit icons
4. **Pomodoro — Void** — circular timer, session count, energy level indicator, skip/pause controls
5. **Mood analytics — Cyberpunk** — mood-vs-completion correlation chart, energy trend line, category breakdown
6. **AI time-block planner — Synthwave** — day timeline with AI-suggested blocks, accept/reject affordances
7. **Four-theme picker — composite** — side-by-side quarter-screens showing the same Today row in each theme
8. **Morning check-in — Matrix** — mood + energy sliders, today's intention field, streak badge

Phone portrait frame spec: 1080×1920 viewBox, rounded corners (radius ~48), status bar (time + battery + signal icons) with 90px height, bottom nav bar with 5 icons (Today/Tasks/Projects/Habits/Settings) at 144px height. Caption strip at bottom 15% with high-contrast overlay.

All of this is feasible as pure SVG + cairosvg rasterization. No emulator or device required. Time estimate to hand-author 8 SVGs: moderate but well within one session.

### Open issue — AI weekly review / onboarding swap
The original prompt suggested "AI Weekly Review" and "Onboarding". Weekly Review does exist (`screens/review/`); Onboarding is minimal and less differentiated than Mood Analytics, so I'm substituting Mood Analytics (item 5) for Onboarding. Will confirm the final 8 in Phase 2 via the caption file; swap is trivial if user prefers.

---

## 8. Open questions requiring user input (from the prompt's hard rules)

These will block Phase 2 completion; all other Phase 2 work proceeds in parallel.

| # | Question | Default / recommendation |
|---|---|---|
| 1 | **Privacy contact email:** `avery.karlin@gmail.com` or `averykarlin3@gmail.com`? | Recommend `averykarlin3@gmail.com` (matches Git author + session email). |
| 2 | **Firestore region** for the privacy policy? | Unclear from `google-services.json`. Check Firebase console → project settings → "Default GCP resource location." Unknown default is `nam5` (multi-region US). |
| 3 | **Is the GitHub repo public?** Determines whether GitHub Pages is free. | `git remote -v` → `https://github.com/akarlin3/prismTask.git`. Visit the repo — if the page loads without login, it's public. |
| 4 | **Play Store icon replacement** — generate a new 512×512 prism-themed icon to replace the purple-infinity placeholder in the Play listing only (not in-app)? | Recommend yes. Generate in `docs/store-listing/graphics/out/icon-512.png`; leave in-app `mipmap-*` untouched. |
| 5 | **Feature-graphic tagline** — pick one of these 5 candidates: | Recommend **3** for beta framing; **1** if skewing mass-market. |
|   | 1. "Plan. Focus. Reflect." | |
|   | 2. "Clear days, calmer weeks." | |
|   | 3. "The productivity app that respects your limits." | |
|   | 4. "Tasks, habits, and the space to do them well." | |
|   | 5. "Built for focus. Made for humans." | |
| 6 | **`REQUEST_INSTALL_PACKAGES` disposition** — leave declared (and justify in Play Console) or split into non-Play build variant? | Recommend splitting before Play submission. Non-blocking for Phase 2 listing copy, but flagged for follow-up engineering PR. |
| 7 | **Version for `release-notes/v?.?.?.txt`** — v1.5.4 (next patch from current 1.5.3) or something else? | **Resolved → v1.6.0.** User picked minor bump because medication reminder mode is a real feature addition. Populated from CHANGELOG Unreleased — medication reminder modes + delete-wins sync fix + sync test follow-ups. |

---

## 9. Phase 2 proceed plan

With user answers to open questions 1, 2, 5 (and confirmations for 3, 4, 6, 7), Phase 2 can generate:

1. `copy/en-US/` — all text copy using v1.5.3 / v1.6.0 versioning
2. `copy/_localization-template/` — mirrored locale scaffolding
3. `graphics/theme-tokens.json` — populated from section 5 above
4. `graphics/src/*.svg` — 8 screenshots + icon + feature graphic
5. `graphics/render.py` — cairosvg rasterization pipeline
6. `compliance/{data-safety-form,content-rating-answers,categorization,permissions-justifications}.md` — populated from sections 3, 4, and user answers
7. `../privacy/{index.md,README.md,_config.yml}` — privacy policy + GitHub Pages setup docs (placed at top-level `docs/privacy/` so the published URL is `/privacy/`, not nested)

**Phase 3** verification will then cross-check char counts, PNG dimensions, policy completeness, data-safety / privacy-policy consistency, and vaporware-scrubbing.

— end of Phase 1 —
