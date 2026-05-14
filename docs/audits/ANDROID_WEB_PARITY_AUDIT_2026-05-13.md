# Android ↔ Web Complete-Parity Audit (2026-05-13)

**Trigger:** Operator request: "complete parity between the Android and Web versions."
**Pattern:** Audit-first; "no DEFERRED" rule applies — every gap gets a PROCEED path or formal ACCEPT-AS-DIVERGENCE.
**Baseline:** `docs/audits/ANDROID_WEB_PARITY_AUDIT.md` (2026-04-26, Phase F, 1037 lines, 10 PRs shipped 2026-04-27 as #836-846).
**Version skew:** Android `versionName = 1.9.20` (build 873). Web `version = 1.6.0`. Three minor versions of Android features have shipped since web's last bump.
**Working tree state:** local `main` at `dedc56ce` (post #1314 leisure/school modes).

This audit is **not a re-do** of the 2026-04-26 sweep. It takes that audit as baseline, verifies what shipped, surfaces newly-introduced gaps in the 17 days since (mostly Android-side features added v1.7→v1.9), and re-classifies every remaining gap per the "no DEFERRED" rule (`feedback_no_deferrals_if_not_there_fix_it.md`).

---

## Methodology

For each surface: **state of play** (current verdict) + **gap list** with `(GREEN)`/`(YELLOW)`/`(RED)` inline tags + PROCEED-path or ACCEPT-AS-DIVERGENCE with link to `docs/divergences/web-vs-android.md`. Inline framework, not subheader-per-item, per token-efficiency convention.

Where the prior audit's surfaces still apply, this doc references them by section number (S1–S16) rather than restating.

---

## Section A — Cross-cutting infra (Surfaces 6 + 8 + 9 baseline)

### A.1 Real-time listener wiring (GREEN, partial)
`web/src/hooks/useFirestoreSync.ts` mounts 7 listeners at sign-in: tasks, habits, completions, projects, tags, medication_slot_defs, medication_preferences. **Closes Surface-6 P2.**
**Still unwired:** `subscribeToDependencies` (`taskDependencies.ts`), `subscribeToPhases` (`projectPhases.ts`), `subscribeToRisks` (`projectRisks.ts`), `subscribeToAnchors` (`externalAnchors.ts`), `subscribeToAiFeaturesEnabled` (`aiPreferences.ts`). Plus no listeners exported for `checkInLogs.ts`, `moodEnergyLogs.ts`, `focusReleaseLogs.ts`, `boundaryRules.ts`, `medications.ts` (read-only path today).
**PROCEED — A.1a (small):** wire the 5 already-exported `subscribeTo*` into `useFirestoreSync.ts`. ~30 LOC. Same shape as PR #845.
**PROCEED — A.1b (medium):** add `subscribeTo*` exports for `checkInLogs`, `moodEnergyLogs`, `focusReleaseLogs`, `boundaryRules`, `medications` + wire. ~200 LOC. Each follows the existing `onSnapshot` template in `tasks.ts:284-310`.

### A.2 LWW conflict resolution on web writes (YELLOW)
Web writes via `setDoc(merge)` / `updateDoc()` with no `updatedAt` precondition guard. If Android pushes a stale write after a web edit, last-wire-write wins regardless of staleness. Prior audit triaged this DEFER-TO-G.0 (~2 days).
**PROCEED — A.2:** add `updatedAt` precondition transactions to each `web/src/api/firestore/*.ts` write helper. Pattern: `runTransaction((tx) => { const cur = tx.get(ref); if (cur.updatedAt > local.updatedAt) abort; else tx.set(...) })`. ~3 days; multi-PR. Sequence after A.1 since the listener-fed local state is the input.

### A.3 `web/src/api/sync.ts` dead code (GREEN, decision)
Verified zero callers via grep. 28 LOC HTTP stub for legacy `/sync/push` + `/sync/pull`.
**PROCEED — A.3:** delete it + the `tasks.ts` REST mirror under `web/src/api/tasks.ts` if also unused. Closes Surface-3 T-S8.

### A.4 `cloud_id` dedup on web (YELLOW)
Doc.id IS the cloud id everywhere. PR #1121 + the May-4 canonical-row dedup audit shipped Pattern A (deterministic doc id + `setDoc(merge)`) for `habit_completions`. `checkInLogs` + `moodEnergyLogs` already follow the same pattern. Tasks/habits/projects/tags rely on Firestore-generated doc ids — fine because Android maps `cloud_id = doc.id` on pull.
**ACCEPT-AS-DIVERGENCE — A.4** for entities where Pattern A doesn't apply (no natural composite key). Already covered by `docs/divergences/web-vs-android.md` § "Sync architecture / Cloud-id dedup parity."

### A.5 Settings persistence (YELLOW)
`web/src/stores/settingsStore.ts` localStorage-only except `aiFeaturesEnabled` which is dual-synced via `aiPreferences.ts`. `startOfDayHour` is localStorage-only.
**PROCEED — A.5a:** sync `startOfDayHour` to Firestore at `users/{uid}/prefs/user_prefs.startOfDayHour`, mirror Android `task_behavior_prefs/day_start_hour`. Single-field add to `aiPreferences.ts` (or split into `userPrefs.ts`). ~80 LOC.
**PROCEED — A.5b:** extend to dashboardPreferences, themeStore, a11yStore. ~200 LOC. Same pattern as `aiPreferences.ts` for each.

### A.6 IndexedDB / offline persistence (GREEN by divergence)
Web has no local store by design. `docs/divergences/web-vs-android.md` § "IndexedDB / local persistence" makes this a formal divergence. No re-open trigger.
**ACCEPT-AS-DIVERGENCE — A.6.**

### A.7 Migration model on web (GREEN by divergence)
No schema versioning needed when there's no local store. Same divergence as A.6.
**ACCEPT-AS-DIVERGENCE — A.7.**

---

## Section B — Tasks + Habits + Projects (Surfaces 2 + 3)

### B.1 Tasks round-trip-safe write (GREEN)
Confirmed `taskUpdateToDoc()` at `web/src/api/firestore/tasks.ts:282-370` uses conditional inclusion. PR #839 closed T-S1/2/3, T-F1/2/3.

### B.2 Habits round-trip-safe write (GREEN)
PR #840 + #846 closed H-S1/2/4 + H-F1. Forgiveness-first streak parity shipped via `DailyForgivenessStreakCore` port.

### B.3 Bookable habits + habit_logs (RED)
Web `habits.ts:81-107` still hardcodes `isBookable: false, trackBooking: false, hasLogging: false`. No `habit_logs` collection on web. PR #1297 added a habits-on-Today section on Android — web's Today shows none of it.
**PROCEED — B.3a (medium):** stop hardcoding booking/logging fields; preserve them via merge-mode write. Mirrors PR #840 shape. ~30 LOC.
**PROCEED — B.3b (large):** add `web/src/api/firestore/habitLogs.ts` mirroring Android `HabitLogEntity` + booking dialog UI + history view. ~600 LOC.
**PROCEED — B.3c (medium):** port the new habits-on-Today section (#1297) from `app/.../ui/screens/today/components/HabitsSection.kt` to a new `web/src/features/today/HabitsSection.tsx`. ~200 LOC.

### B.4 Built-in habit identity + version-check UI (YELLOW)
Web ignores `isBuiltIn`/`templateKey`/`source_version` etc. No `BuiltInHabitReconciler` equivalent. No version-check UI for template updates.
**PROCEED — B.4:** port reconciler logic to `web/src/utils/builtInHabitReconciler.ts` + version-check banner UI on `HabitListScreen.tsx`. ~400 LOC.

### B.5 Per-habit Today-skip windows (YELLOW)
Hidden from web habit editor. Android stores `today_skip_*` fields per habit.
**PROCEED — B.5:** expose in `HabitModal.tsx`. ~120 LOC.

### B.6 `task_completions` write path (RED — analytics-blocker)
Web doesn't write per-completion rows; analytics chart-history on web is empty.
**PROCEED — B.6:** add `web/src/api/firestore/taskCompletions.ts` mirror; wire from `toggleTaskComplete` paths in `taskStore.ts`. ~200 LOC.

### B.7 Web task archive + `source_habit_id` preservation (YELLOW)
`archived_at` and `source_habit_id` not written by web edits — already partially covered by PR #839 conditional-include shape but verify.
**PROCEED — B.7:** verify by grep that `taskUpdateToDoc` preserves both. If not, add to the conditional-include list. ~15 LOC if needed.

### B.8 Focus-Release per-task fields (YELLOW)
Web task editor doesn't expose Focus-Release per-task overrides.
**PROCEED — B.8:** add to `TaskEditor.tsx`. ~100 LOC.

### B.9 Multi-task paste in Quick-Add (YELLOW)
Newline-separated paste detection absent on web `QuickAddBar.tsx`.
**PROCEED — B.9:** mirror `Android NaturalLanguageParser` multi-task detect. ~100 LOC.

### B.10 Web task templates Firestore migration (YELLOW)
Web uses REST `templates.ts`; Android uses Firestore. Disjoint stores.
**PROCEED — B.10:** migrate web to `web/src/api/firestore/taskTemplates.ts`. ~300 LOC + deprecate REST callers.

### B.11 Project roadmap / phases / risks / external anchors (GREEN — port shipped May-4)
Per `WEB_PROJECT_ROADMAP_PORT_AUDIT.md`, the web roadmap surface was ported (~2,400 LOC). Listeners not yet wired — covered by A.1a above.

### B.12 Task dependencies (YELLOW — STOP-and-report)
Per `WEB_TASK_DEPENDENCY_PARITY_AUDIT.md`, F.8a is documented but not closed; web has the firestore module + listener export, just not wired (A.1a closes that).
**PROCEED — B.12:** wire listener (A.1a) + UI surface (`DependencyEditor` component) on `TaskEditor.tsx`. ~250 LOC.

---

## Section C — Wellness suite (Surfaces 10–13)

### C.1 Today screen sections (RED — major gap)
Web `TodayScreen.tsx` shows: medication, morning check-in card, boundary banner, plus task feed. **Android-only:** Balance bar, Plan-For-Today sheet, Burnout badge, Self-care nudge, Habits section (#1297 just shipped), Schoolwork-by-class section (#1303 just shipped), full Daily-Essentials 7-card layout, `DashboardPreferences` section ordering + visibility.
**PROCEED — C.1a:** port Balance bar component reading from `BalanceTracker` logic. ~250 LOC.
**PROCEED — C.1b:** port Plan-For-Today sheet. ~300 LOC.
**PROCEED — C.1c:** port Habits section (B.3c above).
**PROCEED — C.1d:** port Schoolwork-by-class section. ~250 LOC.
**PROCEED — C.1e:** port Self-care nudge + Burnout badge. ~200 LOC.
**PROCEED — C.1f:** port `DashboardPreferences` to `web/src/stores/dashboardStore.ts` + section reorder UI. ~400 LOC.

### C.2 Balance / LifeCategory engine (RED)
No `web/src/features/balance/` directory exists. Android has full engine (`BalanceTracker`, `LifeCategoryClassifier`, `OverloadCheckWorker`, `WeeklyBalanceReportScreen`).
**PROCEED — C.2a:** port `LifeCategoryClassifier` keyword logic to TS. ~150 LOC.
**PROCEED — C.2b:** port `BalanceTracker` ratio/overload compute. ~200 LOC.
**PROCEED — C.2c:** port `WeeklyBalanceReportScreen`. ~400 LOC.
**PROCEED — C.2d:** add balance settings (target-ratio sliders, auto-classify toggle, overload-threshold). ~150 LOC.

### C.3 Mood correlation engine (YELLOW)
Web has mood logging + basic analytics. Android has Pearson correlation with tasks/habits/medication adherence/burnout (`MoodCorrelationEngine`).
**PROCEED — C.3:** port correlation math. ~250 LOC.

### C.4 Weekly review persistence + auto-generation (YELLOW)
Web `WeeklyReviewScreen.tsx` recomputes locally each session. No `web/src/api/firestore/weeklyReviews.ts`. No auto-generation worker.
**PROCEED — C.4a:** add Firestore mapper for `weekly_reviews`. ~150 LOC.
**PROCEED — C.4b:** auto-generation cron on backend (FastAPI APScheduler already runs `calendar_periodic_sync`; add `weekly_review_generator`). ~200 LOC backend + ~80 LOC web subscriber.

### C.5 Morning check-in full step flow (YELLOW)
Web check-in is checkbox-only. Android has MOOD_ENERGY / BALANCE / CALENDAR steps + 11am auto-prompt + 90-day history view.
**PROCEED — C.5a:** add the missing step components in `web/src/features/checkin/`. ~500 LOC.
**PROCEED — C.5b:** 11am auto-prompt logic. ~80 LOC.
**PROCEED — C.5c:** 90-day history view. ~200 LOC.

### C.6 Boundaries enforcement UI (YELLOW)
Web has `BoundaryTodayBanner.tsx` + `boundaryEnforcer.ts`. Android has full editor + rule types web doesn't expose.
**PROCEED — C.6:** port full `BoundaryRulesScreen` editor with rule types (work-hours / category limits / etc.). ~400 LOC.

### C.7 ND-friendly modes (YELLOW)
Brain Mode, UI Complexity, Forgiveness Streak knobs, Focus Release tooling. Web has `FocusReleaseScreen.tsx` + onboarding ND-mode picker.
**PROCEED — C.7a:** port `NdPreferences` to Firestore-synced Zustand store. ~150 LOC.
**PROCEED — C.7b:** port `NdFeatureGate` to `useNdPreferences()` hook. ~80 LOC.
**PROCEED — C.7c:** UI Complexity + Brain Mode settings sections. ~200 LOC.
**PROCEED — C.7d:** `GoodEnoughTimerManager` + `ShipItCelebrationManager` + `EnergyAwarePomodoro` integrations. ~300 LOC.

### C.8 Conversation task extraction (GREEN)
Web `ConversationExtractScreen.tsx` exists. Verify backend path parity.

---

## Section D — AI Coach / Chat / Privacy (Surface 9)

### D.1 AI Chat screen — completely absent on web (RED)
Android `ui/screens/chat/ChatScreen.kt` exists. No `web/src/features/chat/` directory. CHANGELOG entries since 2026-04-26 (#1278+ era): batch_command inline action via AI Chat, conversation persistence (D11 E.3 to Postgres `chat_messages` table, `/api/v1/ai/chat/history`), Claude-backed auto-button for Life Category.
**PROCEED — D.1a:** port Chat screen UI (message list, composer, conversation switcher, history fetch). ~800 LOC. Backend already has `/api/v1/ai/chat` + history; just need the web frontend.
**PROCEED — D.1b:** wire batch_command inline action (calls `/api/v1/ai/batch-parse`, then `BatchPreviewScreen`). ~150 LOC.
**PROCEED — D.1c:** wire Claude-backed Life Category auto-button on `TaskEditor.tsx` Organize tab. ~100 LOC.

### D.2 AI features master toggle + Anthropic disclosure (GREEN)
PR #842 shipped both. Verify `client.ts:21-34` 451 gate still active.

### D.3 AI Pomodoro coaching (GREEN)
`PomodoroCoachPanel.tsx` exists.

---

## Section E — Medication (Surface 1)

### E.1 Web medication CRUD (RED)
Web `MedicationScreen.tsx` exists with limited slot editor + tier-state writes. Missing: per-med add/edit/archive, per-med dose toggle, refill management, log/history view, clinical report export, virtual-slot derivation for web-only users.
**PROCEED — E.1a:** wire `web/src/api/firestore/medications.ts` write path + add/edit/archive UI on `MedicationScreen.tsx`. ~600 LOC.
**PROCEED — E.1b:** per-med dose toggle UI. ~150 LOC.
**PROCEED — E.1c:** refill management screen mirroring Android `MedicationRefillScreen`. ~400 LOC.
**PROCEED — E.1d:** log/history view. ~300 LOC.
**PROCEED — E.1e:** clinical report export (PDF/markdown via backend endpoint). ~250 LOC web + reuse Android `ClinicalReportGenerator` via shared backend service. ~150 LOC backend.
**PROCEED — E.1f:** virtual-slot derivation on web (`MedicationSlotList.tsx:75-76` currently says "Web does not derive"). ~120 LOC.

### E.2 `medication_slots` collection-name reconciliation (RED)
Android writes `medication_slots`; web writes `medication_slot_defs`. Cross-platform slot configs don't reconcile.
**PROCEED — E.2:** pick one canonical collection. Recommend **migrating Android** to `medication_slot_defs` because web's deterministic-doc-id scheme is more sound. Requires Room migration + Firestore data migration script. ~3 days.

### E.3 `medication_tier_states` doc-id scheme (RED)
Android uses Firestore-generated doc ids + separate `cloud_id`; web uses `${dateIso}__${slotKey}`. Two-row coexistence per day/slot.
**PROCEED — E.3:** migrate Android to deterministic doc-id scheme; one-time backfill script collapses pre-existing rows. ~2 days.

### E.4 `daily_essential_slot_completions` split-brain (YELLOW)
Android writes to both Firestore + backend REST; web writes only to backend REST.
**PROCEED — E.4:** add Firestore mirror on web; or alternatively, **remove** Firestore writes on Android since the backend is now the authoritative store per recent BackendSyncService precedent. Architecture decision needed. ~1 day either direction.

---

## Section F — Leisure + Schoolwork (new Android features 2026-04-26 → 2026-05-13)

### F.1 Leisure Budget v2.0 (RED — entirely new since prior audit)
Android shipped via #1278 + #1310-1314 (Leisure as a "mode"). UI: `app/.../ui/screens/leisure/` (`LeisurePoolScreen.kt`, `LeisurePoolViewModel.kt`, `LogPastLeisureSheet.kt`) + Today integration + Settings.
**Web has zero leisure surface** — no `features/leisure/`, no `leisureStore.ts`, no `firestore/leisureLogs.ts`.
**PROCEED — F.1a:** port `LeisurePoolScreen` + `LeisurePoolViewModel` to `web/src/features/leisure/`. ~500 LOC.
**PROCEED — F.1b:** port LogPastLeisure sheet. ~150 LOC.
**PROCEED — F.1c:** wire Leisure as a Today mode + Settings → categories/activities editor. ~300 LOC.
**PROCEED — F.1d:** add `web/src/api/firestore/leisureLogs.ts` + listener wiring. ~200 LOC.

### F.2 Schoolwork-by-class (YELLOW — partial; web has SchoolworkScreen but no class-row Today integration)
Android #1303 + #1314 ship: drop leisure-style check-off from Today and surface each class as a checkable habit-style row. Web `SchoolworkScreen.tsx` exists for full screen but Today integration missing.
**PROCEED — F.2:** port class-row Today section. ~250 LOC.

---

## Section G — Account / Onboarding / Settings (Surfaces 7 + 8 + 16)

### G.1 RestorePending UX (GREEN, partial)
PR #843 shipped `RestorePendingGate`. Verify it covers email/password path (prior audit said Google-only).
**PROCEED — G.1:** extend `RestorePendingGate` to wrap email/password sign-in path in `LoginScreen.tsx`. ~30 LOC.

### G.2 Change-password (RED)
Web "change password" button toasts success unconditionally with no API call (`SettingsScreen.tsx:938-955` per prior audit; verify line numbers).
**PROCEED — G.2:** wire to Firebase `updatePassword()` + a backend `/auth/change-password` for the email/password fallback users. ~120 LOC.

### G.3 Change-name (YELLOW)
Fake. Reads from Google profile only.
**PROCEED — G.3:** wire to `updateProfile()` Firebase Auth + backend mirror. ~80 LOC.

### G.4 Delete All Data (YELLOW)
Toasts success without doing the work per prior audit. PR #842 may have hidden it; verify.
**PROCEED — G.4:** wire to backend `/account/delete-all-data` + local Firestore subcollection wipe. ~150 LOC.

### G.5 Onboarding completion path (GREEN)
PR #844 shipped path unification. Onboarding completion now writes to `users/{uid}.onboardingCompletedAt`, hydrated to Android DataStore.

### G.6 Onboarding template picker + animations + sign-in shortcut (YELLOW)
Web shows static bullets; Android shows animated full picker.
**PROCEED — G.6a:** port template picker. ~250 LOC.
**PROCEED — G.6b:** port natural-language demo animation. ~120 LOC.
**PROCEED — G.6c:** add sign-in shortcut on Welcome page. ~40 LOC.

### G.7 Web logout / Firestore offline cache clear (YELLOW)
Web logout doesn't clear Firestore offline cache (per prior audit).
**PROCEED — G.7:** add `await terminate(firestore); await clearIndexedDbPersistence(firestore)` to logout flow. ~20 LOC.

---

## Section H — Confirmed divergences (no action — ACCEPT-AS-DIVERGENCE)

All formalized in `docs/divergences/web-vs-android.md` or covered by prior audit § 17.

- **Voice input** (Android-only; Web Speech API support too patchy).
- **Notification delivery** (Web Push deferred to Phase G; entity sync of `notification_profiles`/`custom_sounds`/escalation/quiet-hours/`VibrationPatterns` doesn't extend to web).
- **Widgets** (web has no widget surface — Android-only).
- **Pomodoro foreground/background continuation** (browser tab can't keep timer alive cross-tab-kill).
- **Email/password auth** (web-only; Android is Google-only).
- **Keyboard shortcuts modal + Install PWA prompt** (web-only).
- **Battery-optimization onboarding prompt** (Android-only).
- **`batch_undo_log`** (per-device by design on both).
- **`sync_metadata`, `usage_logs`, `calendar_sync`, `medication_log_events`, `self_care_logs/steps`** (local-only / external-provider / audit-log / deprecated).
- **`IndexedDB`/migrations model** (per A.6/A.7 above + divergence doc).
- **35+ Android-specific advanced settings** (escalation chains, custom sounds, voice prefs, shake-to-X, debug-tier, Brain Mode picker on Android UI).

---

## Improvement table — ranked by wall-clock-savings ÷ implementation-cost

Sorting by quickest-wins-with-highest-leverage first.

| Rank | Item | Section | Cost | Impact |
|------|------|---------|------|--------|
| 1 | Wire 5 unmounted listeners (A.1a) | A.1a | 1h | Live cross-device sync for projects/dependencies/anchors |
| 2 | Delete dead `web/src/api/sync.ts` (A.3) | A.3 | 30m | Removes architectural confusion |
| 3 | Schoolwork-by-class Today section (F.2) | F.2 | 4h | New Android UX surfaced on web |
| 4 | Schoolwork class-row + Leisure %-as-row Today section (B.3c + C.1c) | C.1c | 4h | New Android UX surfaced on web |
| 5 | Sync `startOfDayHour` to Firestore (A.5a) | A.5a | 2h | Cross-device SoD parity |
| 6 | Habits-on-Today section (B.3c / C.1c) | B.3c | 4h | New Android UX surfaced on web |
| 7 | Stop hardcoding booking/logging fields on habits (B.3a) | B.3a | 1h | Web edits don't clobber Android booking state |
| 8 | RestorePendingGate covers email/pw path (G.1) | G.1 | 30m | Closes data-integrity gap on second sign-in path |
| 9 | Web change-password wired (G.2) | G.2 | 3h | Removes fake-UX (prior audit P0) |
| 10 | `task_completions` write path on web (B.6) | B.6 | 4h | Unblocks analytics history on web |
| 11 | Wire `cleanIndexedDbPersistence` on logout (G.7) | G.7 | 30m | Closes data-leak on shared-device sign-out |
| 12 | Mood correlation engine port (C.3) | C.3 | 1d | Closes mood-screen analytics gap |
| 13 | Weekly review Firestore persistence + auto-gen (C.4) | C.4 | 1.5d | Cross-device review history |
| 14 | Boundaries full editor port (C.6) | C.6 | 1d | Closes boundaries-editor gap |
| 15 | Balance engine port (C.2a-d) | C.2 | 3d | Closes Work-Life Balance feature entirely |
| 16 | AI Chat screen port (D.1a) | D.1a | 4d | Closes the single largest user-visible parity gap |
| 17 | Leisure Budget v2.0 port (F.1a-d) | F.1 | 3d | Closes brand-new feature gap |
| 18 | Medication full CRUD + refills + history + report (E.1a-f) | E.1 | 5d | Closes medication-Android-primary gap |
| 19 | `medication_slots` ↔ `medication_slot_defs` reconciliation (E.2) | E.2 | 3d | Cross-platform slot configs work |
| 20 | Onboarding template picker + animations (G.6) | G.6 | 1d | Onboarding parity |
| 21 | ND-friendly modes full port (C.7) | C.7 | 2d | Closes ND feature gap |
| 22 | LWW timestamp guards (A.2) | A.2 | 3d | Cross-device write-collision safety |
| 23 | Built-in habit reconciler + version-check UI (B.4) | B.4 | 2d | Closes B.4 gap |

**Total tractable scope: ~30 distinct PRs over ~5 weeks of single-engineer work** (parallel-worktree execution cuts this ~5–10×).

---

## Phase 2 batching plan (because complete parity exceeds single-session capacity)

Per CLAUDE.md "Audit doc length cap = ~500 lines, split into batched audits above that": this Phase 1 audit is **strategy + tactical inventory only**. Phase 2 is split into batched fan-outs, each its own audit-first cycle.

**Batch 1 (this session — quick wins ≤ 4h each, ~8 PRs):** rank items 1, 2, 5, 7, 8, 9, 11 from the table above + a verification pass on item 7's pre-existing hardcoded-false claim. Sequencing: listener-wiring → quick-win fixes → small new sections.

**Batch 2 (follow-up audit):** wellness suite ports (C.1–C.7, ~2 weeks).
**Batch 3 (follow-up audit):** AI Chat + AI auto-button (D.1, ~1 week).
**Batch 4 (follow-up audit):** Leisure + Schoolwork full features (F.1–F.2, ~1 week).
**Batch 5 (follow-up audit):** Medication full CRUD + collection reconciliation (E.1–E.4, ~2 weeks).
**Batch 6 (follow-up audit):** Cross-cutting sync hardening (A.2 LWW guards + A.5b extended settings sync, ~1 week).

**Why batched:** Phase 2's hard rule is "small coherent PRs, not megabundles" (feedback `feedback_use_worktrees_for_features.md` + the bundling rule). Trying to ship all 30 items in one session exceeds (a) the practical capacity of parallel worktrees, (b) review bandwidth, and (c) the launch window (Phase F public launch is 2026-05-15 — 2 days). Batch 1 is the only one shippable inside this session.

---

## Anti-patterns flagged (do NOT necessarily fix)

- **Web hardcoding fields-to-false on entity write.** Caught in B.3a — same bug shape as PR #840 fixed for habits-on-tasks. Generalize: any `xxxToDoc()` helper that builds-a-whole-doc instead of conditionally-including-known-fields is suspect.
- **Two-source-of-truth on medication screen** (E.4): backend `daily_essential_slot_completions` + Firestore `medication_tier_states` writing similar data. Architectural smell; address when E.2/E.3 reconciliation lands.
- **Onboarding completion at two Firestore paths historically** (#844 fixed) — flag-shape repeats if a future feature persists "completion" state in `users/{uid}.someField` on web and `users/{uid}/prefs/...` on Android. Pick one canonical path up-front.
- **Dead REST modules** (`web/src/api/sync.ts`, possibly `web/src/api/tasks.ts`): grep before adding new REST sync helpers — Firestore-direct path is the convention.
