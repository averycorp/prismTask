# Parity Batch 7 — Complete Android↔Web Parity (Phase 1 audit)

**Trigger:** Operator request 2026-05-15 — *"Create complete parity of
features and complete syncing of information between the Android and Web
apps."* Final consolidation batch on top of Batches 1–6.

**Parent audit:** `docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md`
(382 lines, ~23 ranked open items). Batch audits 2–6 closed roughly 50
PRs (#1336–#1451) across the wellness suite, AI Chat / Coach, Leisure +
Schoolwork, full Medication CRUD, and the LWW + settings-sync hardening
sweep. This batch closes the remaining 16 PROCEED items so the parent
audit has no remaining unresolved PROCEED tag.

**Cap:** ≤ 500 lines per CLAUDE.md audit-length convention.
**Working tree:** worktree `agent-af4e2fa62628287f8` off `main@2420c6d1`.

---

## Premises verified

Read directly, not inferred:

- **Parent audit ranking** still lists 23 open items; cross-referenced
  against `git log --oneline | grep parity` confirms ~16 are still
  unmerged or only partially covered (the rest closed via Batches 1–6).
- **`useFirestoreSync.ts` mounts 17 listeners as of `main@2420c6d1`**
  (counting #1440 + #1441 additions on top of Batch 6's 15). Wired:
  tasks, habits, taskCompletions, projects, tags, medicationSlots,
  medicationPreferences, taskDependencies, projectPhases, projectRisks,
  externalAnchors, aiFeaturesEnabled, startOfDayHour, themePreferences,
  a11yPreferences, dashboardPreferences, boundaryRules (#1441),
  assignments (#1440). **Still unwired on origin/main**: `checkInLogs`,
  `moodEnergyLogs`, `focusReleaseLogs`, `weeklyReviews`, `medications`
  (read), `ndPreferences` (port shipped in #1372, listener wiring needs
  verification).
- **No backend cron exists for weekly review auto-generation**
  (`grep -rn "weekly_review_generator\|APScheduler" backend/app/` →
  zero hits). C.4b remains open.
- **`web/src/features/habits/` has no booking dialog or history view**
  (`find … -iname "*book*" -o -iname "*log*"` empty). B.3b still open.
- **`web/src/utils/builtInHabitReconciler.ts` does not exist** —
  B.4 still open.
- **`HabitModal.tsx` has no `today_skip_*` fields** — B.5 still open.
- **Task templates remain REST-backed** on web (`web/src/api/templates.ts`
  is the live caller, no `web/src/api/firestore/taskTemplates.ts` exists)
  — B.10 still open.
- **`web/src/features/tasks/TaskEditor.tsx`** does not surface a
  dependency editor (only a read-only chip when the parent task field is
  set on import) — B.12 still open, listener-side already wired by PR
  #1341.
- **`web/src/stores/dashboardStore.ts` does not exist;** PR #1371
  shipped only the Firestore mirror — C.1f UI consumer still open.
- **`web/src/features/balance/WeeklyBalanceReportScreen.tsx` does not
  exist** (only `WorkLifeBalanceSection.tsx` settings card) — C.2c open.
- **`MorningCheckInCard.tsx` is single-screen** (`grep -rn
  "MorningCheckInStepper" web/src` empty) — C.5a step-flow still open.
- **`CheckInHistoryScreen.tsx` does not exist** — C.5c open.
- **`NdModesSection.tsx` settings card does not exist** (only the
  underlying `ndPreferencesStore.ts` from PR #1372) — C.7c open.
- **Pomodoro web does not import ND modules** (`grep -rn
  "goodEnoughTimer\|shipItCelebration\|energyAwarePomodoro" web/src` →
  empty) — C.7d open.
- **Schoolwork course/assignment write paths** — `web/src/api/firestore/courses.ts`
  exists (PR #1365) but exposes read-only listeners only; no
  `CourseEditor.tsx` — F.2 follow-up open.
- **Leisure boundary** — `leisureStore.ts` derives "today" from
  `Date.now()` directly, not from `startOfDayHour`. Logical-day drift
  for late-night sessions — open.

**No premise mismatches that warrant a STOP-and-report.** Every gap
above is a real, verifiable hole on `origin/main`.

---

## Open items dispatched in Batch 7 (the 16 worker units)

Each unit ships as its own worker on its own worktree branch, in
parallel. PR numbers fill in as workers complete (TODO markers below).

| # | Title | Audit ref | Files (primary) | Expected LOC |
|---|-------|-----------|-----------------|-------------:|
| 1 | Habit logging + `habit_logs` Firestore collection | B.3b | `web/src/api/firestore/habitLogs.ts` (new), `HabitBookingDialog.tsx`, `HabitLogsScreen.tsx`, `useFirestoreSync.ts` | ~600 |
| 2 | Built-in habit reconciler + version-check UI | B.4 | `web/src/utils/builtInHabitReconciler.ts` (new), `HabitListScreen.tsx` | ~400 |
| 3 | Habit Today-skip windows + Focus-Release per-task fields | B.5 + B.8 | `HabitModal.tsx`, `TaskEditor.tsx` | ~220 |
| 4 | Multi-task paste in Quick-Add | B.9 | `QuickAddBar.tsx`, `web/src/utils/nlp/multiTaskDetect.ts` (new) | ~100 |
| 5 | Task templates Firestore migration (REST→Firestore) | B.10 | `web/src/api/firestore/taskTemplates.ts` (new), `templateStore.ts`, `useFirestoreSync.ts`, `web/src/api/templates.ts` deprecation | ~300 |
| 6 | Task dependency editor UI + Today blocker chips | B.12 | `DependencyEditor.tsx` (new), `TaskEditor.tsx`, `TodayScreen.tsx` | ~350 |
| 7 | DashboardPreferences store + section reorder UI | C.1f | `dashboardStore.ts` (new), `DashboardSection.tsx` (settings), `TodayScreen.tsx`, `useFirestoreSync.ts` | ~400 |
| 8 | Weekly Balance Report Screen | C.2c | `WeeklyBalanceReportScreen.tsx` (new), `AppRoutes.tsx` | ~400 |
| 9 | Weekly review backend auto-gen cron + web subscriber | C.4b | `backend/app/tasks/weekly_review_generator.py` (new), `backend/app/main.py`, `WeeklyReviewScreen.tsx` | ~280 |
| 10 | Morning check-in stepped flow (MOOD / BALANCE / CALENDAR) | C.5a | `MorningCheckInStepper.tsx` (new), `MorningCheckInCard.tsx`, `MorningCheckInBanner.tsx` | ~500 |
| 11 | 90-day check-in history view | C.5c | `CheckInHistoryScreen.tsx` (new), `AppRoutes.tsx`, `CheckInSection.tsx` | ~200 |
| 12 | UI Complexity + Brain Mode settings sections | C.7c | `NdModesSection.tsx` (new), `SettingsScreen.tsx` | ~200 |
| 13 | ND-friendly Pomodoro integrations (GoodEnough / ShipIt / EnergyAware) | C.7d | `PomodoroCoachPanel.tsx`, `goodEnoughTimerManager.ts` (new), `shipItCelebrationManager.ts` (new), `energyAwarePomodoro.ts` (new) | ~300 |
| 14 | Schoolwork: course/assignment CRUD on web | F.2 follow-up | `courses.ts` (write path), `assignments.ts` (write path), `CourseEditor.tsx` (new), `SchoolworkTodayCard.tsx` | ~400 |
| 15 | Leisure `startOfDayHour` boundary integration | (post-audit) | `leisureStore.ts`, `useLogicalToday.ts` (new or reuse) | ~80 |
| 16 | Listener-parity sweep — mood / checkin / focusRelease / weeklyReviews / medications | A.1b residual | `web/src/api/firestore/*.ts` (verify `subscribeTo*` exists), `useFirestoreSync.ts` | ~150 |
| 17 | **This unit:** consolidation audit doc + CHANGELOG entry | (process) | `docs/audits/PARITY_BATCH_7_COMPLETE_PARITY_AUDIT.md` (new), `CHANGELOG.md` | ~300 |

**Total estimated LOC for the implementation units (1–16):** ~4,880.

---

## Methodology

Identical to Batch 6, in summary:

1. **Premise-first.** Each worker re-greps the cited files before
   writing code. If a gap has already shipped, the worker reports
   wrong-premise and marks the PR a no-op rather than rebuilding
   something live. (Memory `feedback_no_deferrals_if_not_there_fix_it`.)
2. **No DEFERRED.** Every gap gets a PROCEED path or formal
   ACCEPT-AS-DIVERGENCE in `docs/divergences/web-vs-android.md`.
3. **Fan-out parallel-worktree.** All 16 implementation units dispatch
   simultaneously in their own worktrees off `main`. Conflicts on
   `useFirestoreSync.ts`, `CHANGELOG.md`, and
   `useFirestoreSync.test.tsx` are resolved with the keep-both regex
   pattern documented in the 2026-05-13 audit's "Process notes" section.
4. **Phase 3 + 4 fire pre-merge** per repo `CLAUDE.md` convention
   override — Phase 3 bundle summary appends here as soon as each
   worker reports `PR: <url>`, Phase 4 handoff block emits when the
   batch is structurally complete (not when CI clears).
5. **Skip e2e.** Workers cannot drive a logged-in cross-device Firestore
   session. Unit + lint + tsc are the verification gate; CI on push is
   the final check.

---

## ACCEPT-AS-DIVERGENCE register (carried forward from prior batches)

These surfaces remain **intentional divergences** and are *not*
re-opened in Batch 7. Restated here as the canonical list so future
parity audits don't churn them.

### Hardware / platform divergences
- **Voice input (Android-only).** `VoiceInputManager` +
  `VoiceCommandParser` + `TextToSpeechManager` rely on Android
  `SpeechRecognizer`. Web Web-Speech-API is browser-vendor-locked +
  inconsistent. ACCEPT.
- **Widgets (Android-only).** 14 Glance widgets are tied to the home
  screen. Web has no equivalent surface and the operator has stated
  PWA badging is out of scope.
- **Battery-optimization onboarding prompt (Android-only).** No web
  analogue.
- **Pomodoro foreground continuation (browser tab kill).** Web Pomodoro
  pauses when the tab is backgrounded by browser policy. Android
  foreground service is the divergence; web Page Visibility API + Wake
  Lock is best-effort.

### Notification delivery
- **Custom sounds + escalation chains + quiet-hours deferrer**
  (`NotificationProfileRepository` + `EscalationScheduler` +
  `QuietHoursDeferrer`). Web Push deferred to Phase G; not in Batch 7
  scope. Browser notification permission model + per-OS support matrix
  is too fragmented to land usefully right now.

### Auth + onboarding surface
- **Email/password auth (web-only).** Android is Google-Sign-In + local
  via Credential Manager; web has a Firebase email/password fallback
  because Google Sign-In is friction-heavy on shared browsers.
- **Keyboard shortcuts modal + Install PWA prompt (web-only).** Android
  doesn't have keyboard shortcuts at this density; PWA install is a
  web-platform construct.

### Sync architecture
- **`batch_undo_log` per-device by design.** Local undo history shouldn't
  cross devices — that's a UX anti-pattern. ACCEPT.
- **`sync_metadata` / `usage_logs` / `calendar_sync`** Android-only
  bookkeeping. `sync_metadata` is the local working-store dirty-flag
  table that web doesn't need (no IndexedDB). `usage_logs` powers
  Android-only suggestion ranking. `calendar_sync_*` tables back
  Calendar two-way sync, which is wired only on Android today.
- **IndexedDB model (no local store on web).** Web is
  Firestore-as-source-of-truth; Android is Room + WorkManager. The two
  converge on the same observable LWW behaviour at different
  architectural layers. Formal in
  `docs/divergences/web-vs-android.md` § "Sync architecture".
- **Cloud-id dedup parity already met** via PR #1121 (canonical-row
  Pattern A: deterministic doc id + `setDoc` merge).
- **Pomodoro+ session sync.** Web Pomodoro reads via `/api/v1/ai/pomodoro`
  (an AI-coach endpoint), not via a `pomodoro_session` Firestore
  collection. Tracking happens on Android. Re-trigger noted in the
  divergences doc.

### Android-only advanced settings (~35 surfaces)
DataStores listed in `PreferenceSyncModule.kt` that have no web UI
consumer because the underlying feature is Android-only or remains in
the Android-only-advanced-settings tier:
`advanced_tuning_prefs`, `archive_prefs`, `coaching_prefs`,
`gcal_sync_prefs`, `habit_list_prefs`, `notification_prefs`,
`tab_prefs`, `template_prefs`, `timer_prefs`, `voice_prefs` (plus
~25 individual feature toggles for surfaces below).

### Tag entity LWW
- **`tags.ts` LWW** (A.2-tags). Android `TagEntity` has no `updatedAt`
  column, so there's nothing to compare. ACCEPT-AS-DIVERGENCE per
  Batch 6 audit STOP-and-report #2.

### Append-only / canonical-id-idempotent writes
- **`focus_release_logs`, `task_completions`, `habit_completions`,
  `medication_tier_states`.** Their `setDoc(merge)` natural-key
  collapse is the deliberate semantics. LWW would defeat it.

---

## Phase 2 — fan-out plan (16 implementation PRs + this consolidation)

Each unit's PR slot is named below. PR # column gets populated as
workers complete and the consolidation Phase 3 table fills in.

| Unit | Branch slot | PR slot |
|------|-------------|--------:|
| 1 | `feat/web-habit-logs-firestore` | TODO |
| 2 | `feat/web-built-in-habit-reconciler-b4` | TODO |
| 3 | `feat/web-habit-skip-focus-release` | TODO |
| 4 | `feat/web-multitask-paste` | TODO |
| 5 | `parity-b10-task-templates-firestore` | TODO |
| 6 | `feat/web-task-dependency-editor` | TODO |
| 7 | `feat/web-today-section-reorder-parity-c1f` | TODO |
| 8 | `feat/web-weekly-balance-report-screen` | TODO |
| 9 | `feat/backend-weekly-review-cron` | TODO |
| 10 | `feat/web-checkin-stepper` | TODO |
| 11 | `feat/web-checkin-history-view` | TODO |
| 12 | `feat/web-nd-modes-section` | TODO |
| 13 | `feat/web-pomodoro-nd-integrations` | TODO |
| 14 | `feat/web-schoolwork-crud` | TODO |
| 15 | `feat/web-leisure-startofday-boundary` | TODO |
| 16 | `feat/web-listener-parity-sweep` | TODO |
| 17 | `docs/parity-batch-7-complete-parity-audit` | this PR |

Workers run their unit-tests + tsc + lint locally, push, and queue
`gh pr merge --auto --squash --delete-branch`. CI runs the integration
gate. Auto-merge resolves the queue as required checks clear.

---

## Phase 3 — bundle summary (TODO: fill at merge time)

This section fills in as the 16 sibling workers complete. The
consolidation worker (Unit 17, this doc) runs in parallel with the
others, so PR numbers + SHAs are populated by a follow-up commit (or
this doc's own post-merge update) — matching the established pattern
in PARITY_BATCH_3_AI_CHAT_AUDIT § Phase 3 and PARITY_BATCH_6 § Phase 3.

| Unit | Title | Audit ref | Branch | PR | SHA | Premise verified |
|------|-------|-----------|--------|----|-----|------------------|
| 1 | Habit logs Firestore | B.3b | `feat/web-habit-logs-firestore` | TODO | TODO | TODO |
| 2 | Built-in habit reconciler + version-check UI | B.4 | `feat/web-built-in-habit-reconciler-b4` | TODO | TODO | TODO |
| 3 | Habit skip windows + Focus-Release per-task | B.5 + B.8 | `feat/web-habit-skip-focus-release` | TODO | TODO | TODO |
| 4 | Multi-task paste in Quick-Add | B.9 | `feat/web-multitask-paste` | TODO | TODO | TODO |
| 5 | Task templates Firestore migration | B.10 | `parity-b10-task-templates-firestore` | TODO | TODO | TODO |
| 6 | Task dependency editor + Today blocker chips | B.12 | `feat/web-task-dependency-editor` | TODO | TODO | TODO |
| 7 | Dashboard section reorder UI | C.1f | `feat/web-today-section-reorder-parity-c1f` | TODO | TODO | TODO |
| 8 | Weekly Balance Report Screen | C.2c | `feat/web-weekly-balance-report-screen` | TODO | TODO | TODO |
| 9 | Weekly review backend cron + web subscriber | C.4b | `feat/backend-weekly-review-cron` | TODO | TODO | TODO |
| 10 | Morning check-in stepped flow | C.5a | `feat/web-checkin-stepper` | TODO | TODO | TODO |
| 11 | 90-day check-in history view | C.5c | `feat/web-checkin-history-view` | TODO | TODO | TODO |
| 12 | UI Complexity + Brain Mode settings | C.7c | `feat/web-nd-modes-section` | TODO | TODO | TODO |
| 13 | ND Pomodoro integrations | C.7d | `feat/web-pomodoro-nd-integrations` | TODO | TODO | TODO |
| 14 | Schoolwork course/assignment CRUD | F.2 follow-up | `feat/web-schoolwork-crud` | TODO | TODO | TODO |
| 15 | Leisure `startOfDayHour` boundary | (post-audit) | `feat/web-leisure-startofday-boundary` | TODO | TODO | TODO |
| 16 | Listener-parity sweep | A.1b residual | `feat/web-listener-parity-sweep` | TODO | TODO | TODO |

### Wrong-premise tracking

Workers report wrong-premise inline. Aggregated count + per-unit
classification fills in alongside the table above. The standard from
the 2026-05-13 sweep was **3 of 8 items wrong-premise**; Batch 6 was 4
of 9 (a third of original PR-fanout dissolved). Batch 7 baseline is
TODO; expect a similar 20–30% rate.

---

## Phase 4 — Claude Chat handoff block (TODO: emit when Phase 3 fills in)

Append at the bottom of this doc once Phase 3 settles — same shape as
PARITY_BATCH_6 § Phase 4. Pattern:

```markdown
# Batch 7 — Complete Android↔Web Parity — DONE

Closing the 2026-05-13 parent audit's remaining 16 open items.

## What shipped (N PRs to main)
1. **#TODO** unit-1 summary line.
2. **#TODO** unit-2 summary line.
…

## What got divergence-classified instead of ported
- Voice input, widgets, Pomodoro foreground service, custom notification
  sounds + escalation, batch_undo_log per-device, sync_metadata,
  usage_logs, calendar_sync — see ACCEPT-AS-DIVERGENCE register in the
  Phase 1 audit.

## What surprised us (wrong-premise items)
- …

## Watch-items / risk register
- …

## Phase 1 audit doc
`docs/audits/PARITY_BATCH_7_COMPLETE_PARITY_AUDIT.md` carries the full
Phase 1 inventory, ACCEPT-AS-DIVERGENCE register, Phase 3 bundle
summary, and this Phase 4 handoff.
```

---

## Outcome

When Phase 3 closes and the 16 implementation PRs ship,
`ANDROID_WEB_PARITY_AUDIT_2026-05-13.md` will have **no remaining
PROCEED tag**. Web will be "feature-complete against Android v1.9.x
baseline" with full Firestore real-time sync coverage on every entity
that has cross-device semantics, plus REST coverage for the
backend-bound surfaces (leisure, syllabus, AI). Any future parity
delta will originate from net-new Android features that don't yet have
a web counterpart, *not* from baseline catch-up. Drive backup,
Calendar Android-side sync, Gmail/Slack integrations, Android widgets,
and voice input remain formal ACCEPT-AS-DIVERGENCE.
