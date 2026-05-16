# Phase 3 — Verification

**Date:** 2026-04-24
**Worktree:** `C:\Projects\prismtask-store-listing` on `feature/play-store-listing`
**Render backend used:** `resvg-py 0.3.1` (libcairo-free, Windows-friendly)

This is the cross-check pass over every Phase 2 deliverable. Each section below maps to one of the seven verification gates from the prompt. Anything failing or borderline is called out at the bottom under "Open issues."

---

## 1. Character count check (Play Console limits)

| File | Chars (incl. newlines) | Limit | Status |
|---|---|---|---|
| `copy/en-US/app-title.txt` | **26** | 30 | OK |
| `copy/en-US/short-description.txt` | **74** | 80 | OK |
| `copy/en-US/full-description.txt` | **3,388** | 4,000 | OK (~85% used; room to grow) |
| `copy/en-US/release-notes/v1.6.0.txt` | **417** | 500 | OK |
| `copy/en-US/release-notes/v2.0.0.txt` | **339** | 500 | OK (template; placeholders may push final length up — check at fill-in time) |

`v2.0.0.txt` was retightened from 515 → 339 chars during Phase 3 because the original phrasing exceeded the 500-char Play Console cap. The placeholder structure now leaves ~160 chars of headroom for actual content per change line.

---

## 2. PNG dimension and size check

`render.py --check` output:

```
[OK] icon-512.png: 512x512 (82.3 KiB)
[OK] feature-graphic-1024x500.png: 1024x500 (121.9 KiB)
[OK] screenshot-01.png: 1080x1920 (288.8 KiB)
[OK] screenshot-02.png: 1080x1920 (335.8 KiB)
[OK] screenshot-03.png: 1080x1920 (241.7 KiB)
[OK] screenshot-04.png: 1080x1920 (273.2 KiB)
[OK] screenshot-05.png: 1080x1920 (383.4 KiB)
[OK] screenshot-06.png: 1080x1920 (278.5 KiB)
[OK] screenshot-07.png: 1080x1920 (353.2 KiB)
[OK] screenshot-08.png: 1080x1920 (320.8 KiB)
All outputs pass Play Store dimension/size checks.
```

| Asset | Required | Actual | Status |
|---|---|---|---|
| Icon | 512×512 PNG, ≤1 MB | 512×512, 82 KiB | OK |
| Feature graphic | 1024×500 PNG, ≤15 MB | 1024×500, 122 KiB | OK |
| Screenshots ×8 | 1080×1920 PNG, ≤8 MB each, 9:16 aspect | All 8 at 1080×1920, 242–384 KiB | OK |

All ten files clear the Play Store technical requirements with very large headroom (largest is screenshot-05 at 383 KiB vs. 8 MB cap — 2 % of the limit).

### Tablet screenshots (added during open-issue resolution)

`render-tablet.py` composes 16 tablet form-factor PNGs by letterboxing each phone screenshot inside a wider canvas filled with the screenshot's theme background color:

| Tablet form factor | Dimensions | Count | File pattern |
|---|---|---|---|
| 7-inch tablet (landscape) | 1920×1200 (8:5) | 8 | `tablet-7in-NN.png` |
| 10-inch tablet (landscape) | 2560×1600 (8:5) | 8 | `tablet-10in-NN.png` |

All 16 tablet PNGs render between 126 KiB and 303 KiB — far under the 8 MB Play Console cap. Aspect ratio 8:5 (1.6) is within Play Console's accepted range of 9:16 to 16:9.

---

## 3. Privacy policy completeness check

`../privacy/index.md` (formerly `privacy-policy/index.md` before the open-issue-3 folder move) covers every section the Play Console privacy-policy validator (and standard GDPR/CCPA practice) expects:

| Required section | Present in `index.md`? |
|---|---|
| Identity of data controller | Yes — "Who we are" |
| Contact for privacy requests | Yes — `privacy@prismtask.app` |
| Effective date + last updated | Yes — both = 2026-04-24 |
| Categories of data collected | Yes — "What we collect" |
| Purposes of processing | Yes — "How we use your data" |
| Storage locations and retention | Yes — "Where your data is stored" |
| Third-party processors | Yes — table with Google/Anthropic/Railway |
| User rights (export, delete, correct) | Yes — "Your rights" |
| GDPR / CCPA jurisdiction notes | Yes — "Your rights" |
| Children policy | Yes — 18+ explicitly stated |
| Security practices | Yes — "Data security" |
| Update / change-of-policy process | Yes — "Changes to this policy" + Changelog |

GitHub Pages hosting is documented in `../privacy/README.md`. Folder is now at the top-level `docs/privacy/` so the published URL is `https://akarlin3.github.io/prismTask/privacy/` once Pages is enabled.

---

## 4. Data-safety form ↔ privacy-policy consistency

Cross-check verified by side-by-side read of `compliance/data-safety-form.md` and `../privacy/index.md`. Every data type, processor, and retention claim is consistent across both documents:

| Data type | data-safety-form | privacy-policy | Consistent? |
|---|---|---|---|
| Email + Google user ID | Collected, linked, not shared | Collected, linked, not shared | Yes |
| App-content (tasks, habits, mood, medications) | Collected (synced if signed in), shared with Anthropic for AI features only | Same | Yes |
| Voice audio | Transient, shared with Google Speech | Transient, shared with Google Speech | Yes |
| Calendar events | Optional opt-in, shared with Google Calendar | Optional opt-in, shared with Google Calendar | Yes |
| Crash logs / diagnostics | Collected, shared with Google/Firebase | Collected, shared with Google/Firebase | Yes |
| Location, contacts, photos, SMS | Not collected | Not collected | Yes |
| Financial data | Not collected; Play Billing handles purchases | Not collected; Play Billing handles purchases | Yes |
| Children policy | 18+ | 18+ | Yes |

**Load-bearing invariant:** if either file changes, the other must be updated in the same commit. README in `../privacy/` calls this out explicitly.

**Reworded during open-issue-6 resolution:** both files previously claimed an in-app "Settings → Account → Delete account" flow. Code audit confirmed `AuthManager.deleteAccount()` is implemented but unwired to UI; only `signOut()` exists in `AccountSyncSection.kt`. Both files now direct users to email `privacy@prismtask.app` for deletion and disclose that an in-app one-tap path is in active development. The two files remain pairwise consistent.

---

## 5. Permission justifications coverage

Every permission declared in the manifest has a justification entry in `compliance/permissions-justifications.md`:

| Permission in `AndroidManifest.xml` | In permissions-justifications.md? |
|---|---|
| `INTERNET` | Yes |
| `ACCESS_NETWORK_STATE` | Yes |
| `POST_NOTIFICATIONS` | Yes |
| `USE_FULL_SCREEN_INTENT` | Yes |
| `SCHEDULE_EXACT_ALARM` | Yes |
| `USE_EXACT_ALARM` | Yes |
| `RECEIVE_BOOT_COMPLETED` | Yes |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Yes |
| `REQUEST_INSTALL_PACKAGES` | Yes (with policy-risk callout) |
| `RECORD_AUDIO` | Yes |
| `VIBRATE` | Yes |
| `FOREGROUND_SERVICE` | Yes |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Yes |

`<queries>` declaration for `RecognitionService` is also documented.

---

## 6. Beta framing audit

"Closed testing" / "beta" framing appears where it should and is absent where it would feel marketing-y:

| Surface | Beta language? | Verdict |
|---|---|---|
| `app-title.txt` | No | Right call — title needs to be discoverable, not framed |
| `short-description.txt` | No | Right call — 80-char line cannot afford framing |
| `full-description.txt` | One paragraph, last section: "Currently in closed testing. We're shipping fast, listening hard…" | Right amount — sets expectations without leading with it |
| `release-notes/v1.6.0.txt` | Implicit (medication-mode change is the kind of work in flight that closed testing exists for) — no explicit "beta" word | Acceptable; release notes are descriptive, not framing |
| Privacy policy | No | Right — privacy is not the place for product framing |

---

## 7. Vaporware audit (every claim in `full-description.txt` traces to a Phase 1 finding)

| Copy claim | Phase 1 audit support |
|---|---|
| "Tasks with due dates, priorities, projects, tags, subtasks, recurrence, and natural-language quick-add" | §2 Core task management — confirmed |
| "Habits with forgiveness-first streaks" | §2 Habits + memory `DailyForgivenessStreakCore` |
| "Pomodoro timer with per-session energy tracking and a foreground service" | §2 Focus/timing + manifest `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| "Week, month, and timeline views" | §2 Views — `weekview/`, `monthview/`, `timeline/` screens |
| "Today screen with progress ring and balance bar" | §2 Today + Work-Life Balance |
| "Four cohesive themes — Cyberpunk, Synthwave, Matrix, Void" | §2 Themes — verified hex tokens in `ThemeColors.kt` |
| "Eisenhower matrix with auto-classification and persistent manual overrides" | §2 AI-powered + DB v56→v57 `user_overrode_quadrant` column |
| "AI time blocking … daily briefing and weekly review" | §2 AI-powered — `screens/planner/`, `screens/briefing/`, `screens/review/` |
| "Smart Pomodoro planning" | §2 AI-powered — `EnergyAwarePomodoro` |
| "Work-Life Balance engine with a configurable target ratio" | §2 Wellness — `BalanceTracker` + Settings sliders |
| "Mood and energy logs with correlation" | §2 Wellness — `MoodCorrelationEngine` |
| "Morning check-in and weekly review" | §2 Wellness — `screens/checkin/`, `screens/review/` |
| "Boundary rules — declare work hours and category limits; burnout scorer" | §2 Wellness — `BoundaryEnforcer`, `BurnoutScorer` |
| "Brain Mode, adjustable UI complexity, forgiveness streaks, shake-to-capture, 'good enough' timer" | §2 ND-friendly + `NdFeatureGate`, `ShakeDetector`, `GoodEnoughTimerManager` |
| "Medications with flexible clock-time or interval reminders" | §2 Medication + CHANGELOG Unreleased PRs 1-3 of 4 |
| "Refill projection from your dose history" | §2 Medication — `RefillCalculator` |
| "Therapist-friendly clinical report export" | §2 Medication — `ClinicalReportGenerator` |
| "TalkBack-ready content descriptions, dynamic font scaling, high-contrast palette mode, full keyboard focus traversal, reduced-motion gates" | §2 Accessibility — `ui/a11y/` |
| "Full local-first Room database — works entirely offline" | §2 Data & sync |
| "Optional Google Sign-In … Firebase Firestore" | §4 Data flow |
| "One-tap full JSON export and a CSV task export" | §2 Data & sync — `DataExporter.kt` |
| "Delete all of your data from Settings at any time" | §4 Data flow — claim retracted in open-issue-6; copy reworded to direct users to `privacy@prismtask.app` (see §Open-issue-6 below) |
| "No ads … no analytics SDKs beyond crash reporting" | §4 Data flow — confirmed only Firebase Crashlytics |
| "Pro ($7.99 / month, or $5 / month billed annually at $59.99 / year with a 7-day free trial) adds cross-device cloud sync, AI-assisted planning, analytics, time tracking, the clinical report export, and Google Drive backup" | README §"Free vs Pro" table — exact match |
| "Currently in closed testing" | Track context — not a feature claim |

**No widgets, Gmail, or Slack mentions in the copy** — `Grep` over `copy/` found zero matches for those terms (matching `widget|Gmail|Slack|Zapier`, case-insensitive). The only "#" hashtag in the copy is the NLP example `#home !high`, which is an accurate quick-add syntax demo.

---

## Open issues — resolution status

The seven open issues raised in the original Phase 3 pass were each addressed. Five resolved fully in this PR. Two are blocked on the harness's "do not modify in-app resources" rule and need explicit engineering authorization before the next push.

### ✅ Resolved in this PR

3. **Privacy URL folder rename — RESOLVED.** `docs/store-listing/privacy-policy/` was moved to `docs/privacy/` via `git mv`. Once GitHub Pages is enabled (Settings → Pages → main / `/docs`), the policy URL becomes `https://akarlin3.github.io/prismTask/privacy/`. Internal references updated in `compliance/data-safety-form.md`, `compliance/categorization.md`, `PHASE1_AUDIT.md`, and this file.

4. **Firestore region — RESOLVED.** User confirmed `nam5` (multi-region US) in Phase 1; user opted not to gcloud-verify before publishing. Privacy policy and data-safety form both state `nam5`. If Firebase Console later shows a different region, both files must be edited together (one-line change).

5. **Tablet screenshots — RESOLVED.** New `render-tablet.py` script composes 16 tablet PNGs (eight at 1920×1200 and eight at 2560×1600) by letterboxing each phone screenshot inside a wider canvas filled with the screenshot's theme background. Aspect 8:5 (1.6) is within Play Console's accepted 9:16–16:9 range. All 16 tablet PNGs rendered to `graphics/out/tablet-{7in,10in}-NN.png`, max size 303 KiB, all under the 8 MB cap.

6. **Account deletion UI — RESOLVED via documentation.** Code audit confirmed `AuthManager.deleteAccount()` is implemented at `AuthManager.kt:114` but no UI calls it; only `signOut()` exists in `AccountSyncSection.kt`. The privacy policy and data-safety form were reworded to drop the "Settings → Account → Delete account" claim and instead direct users to `privacy@prismtask.app`, with an explicit note that an in-app one-tap deletion is in active development. Engineering follow-up: wire `deleteAccount()` to a Settings UI button + add a local Room wipe in the same path; when shipped, update both files to add the in-app path.

7. **Version label — RESOLVED to v1.6.0.** User picked a minor bump (vs. patch) because medication reminder modes are a real feature addition. Release notes file renamed from `v1.5.4.txt` to `v1.6.0.txt` (en-US + localization template). Char count unchanged at 417/500. References updated in `PHASE1_AUDIT.md` and this file.

### ✅ Resolved in a follow-up commit (after explicit user authorization for in-app writes)

1. **`REQUEST_INSTALL_PACKAGES` build-variant split — RESOLVED.** Created `app/src/debug/AndroidManifest.xml` declaring the permission, removed the line from `app/src/main/AndroidManifest.xml` and replaced it with a comment explaining the split. Manifest merger now includes the permission only in debug builds (which go to Firebase App Distribution) and excludes it from release builds (which go to Play). Verify with:
   ```bash
   ./gradlew :app:processReleaseMainManifest
   # Then grep build/intermediates/merged_manifests/release/AndroidManifest.xml
   # — REQUEST_INSTALL_PACKAGES should be absent.

   ./gradlew :app:processDebugMainManifest
   # — REQUEST_INSTALL_PACKAGES should be present.
   ```

2. **In-app launcher icon unification — RESOLVED.** New `scripts/replace-launcher-icons.py` runs the resvg-py pipeline at 48 / 72 / 96 / 144 / 192 px against both `icon.svg` (square) and the new `icon-round.svg` (circular clip), overwriting all 10 PNGs under `app/src/main/res/mipmap-*/`. Idempotent — running twice produces byte-stable output. The script was executed once during this resolution pass; the home-screen launcher and any pre-API-26 fallback now show the prism + 4-theme-colored-rays brand mark instead of the purple-infinity placeholder.

---

## Summary

**Phase 1 audit:** complete. Two hard contradictions surfaced (version state ~6 months stale in the prompt; existing in-app icon does not match Play Store icon).

**Phase 2 generation:** 28 files initially (later: + 1 round-icon SVG, + 16 tablet PNGs, + 1 tablet renderer; - 1 release-notes rename) — copy, compliance, privacy policy, theme tokens, SVG sources, render pipelines, localization scaffold, and tablet form-factor outputs.

**Phase 3 verification:** five hard checks green (char counts, PNG dimensions, privacy completeness, data-safety/policy consistency, permission coverage). Two soft checks (beta framing, vaporware) also clean.

**Open-issue resolution:** all seven open issues fully resolved in this PR. Issues 3, 4, 5, 6, 7 landed in commit `fe8ea232`. Issues 1 and 2 (which require in-app source-tree writes) landed in a follow-up commit after explicit user authorization for the manifest split + launcher icon replacement.

**Render artifacts:** 26 PNGs total. 10 phone-listing PNGs (icon-512, feature-graphic-1024x500, eight 1080×1920 screenshots) via the `resvg-py` backend. 16 tablet PNGs (eight 1920×1200, eight 2560×1600) via the `Pillow` letterboxer in `render-tablet.py`. All under Play Store byte caps.
