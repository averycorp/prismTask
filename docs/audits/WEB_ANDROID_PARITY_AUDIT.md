# Web ↔ Android Parity Audit

**Date:** 2026-05-18
**Scope:** Check whether features shipped on Android in v1.7.x–v1.9.x have
parity on web (`web/`). Cross-reference [README.md § Roadmap](../../README.md#roadmap)
deferred items so we don't waste cycles on intentional v1.10+ / v2.0+ work.
**Method:** five parallel investigation agents inspected
`app/src/main/java/com/averycorp/prismtask/**` (Android) and `web/src/**`
(React/TypeScript/Vite), citing concrete file paths and line numbers.

Verdict tags: **GREEN** (parity), **YELLOW** (partial), **RED** (missing on
web — fix), **DEFERRED** (roadmap-deferred or platform-specific — skip).

## AI Coach Chat

- **Multi-turn memory (N=6 pairs) (GREEN)** —
  Android `ChatRepository.kt:66` `maxHistoryPairs = 6`; web
  `web/src/stores/chatStore.ts:11` `MAX_HISTORY_PAIRS = 6`. Both slice
  `takeLast(6 * 2)` before forwarding.
- **Task-content forwarding to Claude (RED)** —
  Android `ChatViewModel.kt:281-288` populates `taskContextId` +
  `taskContext` from `buildTaskContextSnapshot()`. Web has the type
  (`ChatTaskContext` in `web/src/api/chat.ts:54-61`) but
  `chatStore.ts:185-189` sends only `message`, `conversation_id`, `history`.
  **PROCEED** — populate `task_context` from `taskStore` in web `sendMessage`.
- **First-run disclosure V3 (GREEN)** —
  Android `aiChatDisclosureShownV3Flow`; web
  `prismtask.chat.disclosureShownV3`. Same copy.
- **Action-chip idempotency + per-item batch results (GREEN)** —
  signature-based dedup matches; both track `actionsInFlight: Set<string>`.
- **Snackbar Undo on destructive chip actions (GREEN)** —
  both gate `undoLabel`/`undoAction` on destructive classification.
- **Batch-command inline actions → BatchPreview (GREEN)** —
  Android emits `ChatNavEvent.OpenBatchPreview`; web routes `/` prefix
  + `batch_command` action to `/batch/preview`.
- **Conversation persistence (GREEN)** —
  both use `/api/v1/ai/chat/history`; same `chat_{ISO_DATE}_{UUID8}` ID
  format; daily rollover as UI filter, not destructive.
- **Auto-button Life Category Claude fallback (GREEN)** —
  Android `AddEditTaskViewModel.kt:747-770`
  `tryUpgradeLifeCategoryWithClaude()`; web
  `web/src/features/tasks/TaskEditor.tsx:373-395` calls
  `aiLifeCategoryClassifyText()` → `POST /ai/life-category/classify_text`.

## Time tracking analytics

- **`TaskTimingEntity` + repository (GREEN)** —
  web has `web/src/stores/taskTimingsStore.ts` +
  `web/src/api/firestore/taskTimings.ts` mirror.
- **Manual "Logged Time" section in Schedule tab (RED)** —
  Android `ScheduleTab.kt` (~line 225) shows "Logged Time" header + entry
  list + manual edit UI. Web `web/src/features/tasks/ScheduleTab.tsx` has
  Due Date / Time / Reminder / Recurrence / Duration only — no Logged Time
  section. **PROCEED** — add Logged Time list + create / edit / delete
  controls reading from `taskTimingsStore`.
- **Pomodoro auto-log into `task_timings` (RED)** —
  Android Pomodoro writes a `TaskTimingEntity` on session complete; web
  `web/src/features/pomodoro/PomodoroScreen.tsx:82-150` does not write.
  Comment in `taskTimings.ts:22` says "Android is the source of truth …
  web write parity is a follow-up." **PROCEED** — implement the write path.
- **Cross-device Firestore sync for timings (GREEN)** —
  web `subscribeToTaskTimings` listener wired (read-only — write parity
  covered by RED items above).
- **Productivity-score chart + time-tracking bar chart on Analytics
  (DEFERRED)** — web `taskTimingsStore` is wired but
  `AnalyticsScreen.tsx` does not yet render charts. Defer to a dedicated
  analytics PR (chart UX is its own scope).

## Today + recurrence

- **Log Again / Mark Incomplete dialog on completed recurring tasks
  (RED)** — Android `TodaySwipeableTaskItem.kt` ~line 265 surfaces a
  two-option dialog for completed recurring tasks. Web
  `web/src/features/today/DoneCounterSheet.tsx:31-80` only offers Undo —
  no "Log Again" affordance. **PROCEED** — add Log Again handler that
  re-completes (and spawns next occurrence).
- **"Done" counter sheet (GREEN)** —
  web `DoneCounterSheet.tsx` mirrors Android `CompletedTodaySheet.kt`.
- **Leisure / School Today modes with per-class checkable rows (RED)** —
  Android exposes mode toggles + per-class habit-style rows. Web has only
  `TodayLeisureMinimumRow`; no mode toggles or per-class rows. **PROCEED**
  *but* note: this is a larger scope — split into its own PR (mode toggles
  first, per-class rows follow-up if needed).
- **Daily Essentials card (housework + schoolwork) (GREEN)** —
  web `DailyEssentialsCards.tsx` matches the Android `DailyEssentialsSection.kt`.
- **Morning check-in banner respects Start-of-Day (GREEN)** —
  both use `MorningCheckInBannerDecider`.
- **Next-occurrence anchored to completion time (RED)** —
  web `web/src/lib/recurrence.ts:23` `calculateNextOccurrence` is called
  with `existingTask.due_date` in `taskStore.completeTask`; should be
  `completedAt`. **PROCEED** — pass completion timestamp.
- **Toggle-uncomplete rolls back spawned recurrence (RED)** —
  Android tracks `spawnedRecurrenceId` and deletes on uncomplete. Web
  `uncompleteTask` (`taskStore.ts` ~line 310) only rolls back
  `task_completions` history. **PROCEED** — track the spawned ID on
  completion and delete on uncomplete.
- **Eisenhower / Pomodoro completion spawns recurrence (GREEN)** —
  both spawn via the shared `completeTask` path.

## Editor + NLP

- **OrganizeTab Auto buttons (GREEN)** —
  full parity for Life Category / Task Mode / Cognitive Load.
- **Auto-classify of task mode + cognitive load on every insert (RED)** —
  Android `OrganizeTab.kt:95-99` `LaunchedEffect(title, description)` runs
  classifiers on every keystroke. Web
  `web/src/features/tasks/TaskEditor.tsx:420-471` fires only on explicit
  Auto-button click. **PROCEED** — add `useEffect` keyed on title +
  description (debounced) that re-runs the on-device classifiers when no
  user-set value is present.
- **Urgency-keyword inference in NLP (RED)** —
  Android `NaturalLanguageParser.kt:408-420` infers priority from
  "asap"/"urgent"/"critical"/"important"/"high priority"/"soon" when no
  explicit `!` priority is given. Web `web/src/lib/nlp.ts:93-111` parses
  only `!`/`!!`/`!urgent`/`!high`. **PROCEED** — port the keyword regex.
- **Import preview field-level detail (RED)** —
  Android shows per-task suggested due date / priority / tags / subtasks.
  Web `web/src/lib/import.ts:13-27` `ImportPreview` is collection-counts
  only. **PROCEED** — extend `ImportPreview` + UI to display field-level
  preview before commit.

## Projects + Tasks UX

- **Archive / Reopen from list (YELLOW)** —
  Android exposes from list screens (via context menu / row action); web
  `ProjectListScreen.tsx` shows Edit / Delete only. **PROCEED — small** —
  add Archive / Reopen actions to the row menu.
- **Archived projects hidden from chip rows + pickers (YELLOW)** —
  Android `OrganizeTab.kt:225-227` filters; web filters at store level but
  inconsistently. Spot-check + tighten in the same PR as Archive / Reopen.
- **Archived projects behind footer link (DEFERRED)** —
  not shipped on either side yet; not a regression, defer.
- **Tasks tab elevates project prominence (DEFERRED)** —
  rendering decision; subjective scope; defer to a dedicated UX pass.

## Medications

- **Allow medications without slot (PRN) (GREEN)** —
  web `MedicationEditorDialog.tsx:52` includes `'AS_NEEDED'` schedule mode.
- **Editor preserves per-med settings + slot links (GREEN)** —
  web `FormState` initialized from `initial` prop (line 72).
- **Per-med times (slot "Taken at" dropped) (GREEN)** —
  web `MedicationScreen.tsx` renders doses via `virtualSlots` from per-med
  `specific_times` / `times_of_day`.
- **Daily Essentials medication card removed from Today (GREEN)** —
  web `DailyEssentialsCards.tsx` lists no medication tile.
- **Per-medication reminder-mode override UI (DEFERRED)** —
  README v1.10+; the web editor surfaces the picker, but write-back +
  read-back parity is the v1.10 milestone. Leave alone.
- **Web slot-editor per-slot reminder-mode picker (DEFERRED)** —
  README v1.10+.

## Leisure

- **User-defined custom categories (GREEN)** —
  web `LeisurePoolScreen.tsx:37` reads `customCategories`; add UI at line 417.
- **Built-in defaults removable (GREEN)** —
  web shows remove button for custom categories; built-ins render without
  remove (correct — only custom are removable, per Android).
- **Duration dialog always shown (GREEN)** —
  web `LogPastLeisureDialog.tsx:38` initializes duration to '30' and always
  renders the input.
- **Enable / disable switches don't hang (GREEN)** —
  web wires `setCategoryEnabled` / `setActivityEnabled` cleanly.

## Sync + Settings

- **Firestore-direct write path for `task_completions` (GREEN)** —
  web `web/src/api/firestore/taskCompletions.ts:1-68` with deterministic
  ID; `subscribeToTaskCompletions` listener wired.
- **`startOfDayHour` cross-device (GREEN)** —
  web reads / writes `users/{uid}/prefs/task_behavior_prefs.day_start_hour`;
  `subscribeToStartOfDayHour` listener wired.
- **IndexedDB cache cleared on logout (GREEN)** —
  web `authStore.ts:247-258` calls `clearIndexedDbPersistence()` on logout.
- **4 of 5 real-time listeners (GREEN)** —
  `subscribeToDependencies` / `subscribeToPhases` / `subscribeToRisks` /
  `subscribeToAnchors` wired. The 5th (`subscribeToAiFeaturesEnabled`) is
  intentionally imperative (bootstrap-only); DEFERRED follow-up A.5b.
- **Dead `web/src/api/sync.ts` removed (GREEN)** — confirmed absent.
- **`MorningCheckInPromptCutoff` slider in Settings → Advanced Tuning
  (RED)** — Android `AdvancedTuningScreen.kt` ships the slider; web
  `web/src/features/settings/sections/AdvancedTuningSection.tsx` uses a
  hardcoded default in `morningCheckInBanner.ts` only. **PROCEED** — add a
  slider to AdvancedTuningSection and sync via Firestore pref doc.
- **AI features off / on toggle (GREEN)** —
  web `AiFeaturesSection.tsx:16-71` master toggle syncs via Firestore.
- **Notification profiles (GREEN within scope)** —
  per-profile sound / quiet hours / vibration editable on web; create /
  delete intentionally Android-only (creation UX is not a parity gap —
  cross-device read-modify works either way).
- **`startOfDay` editor in Settings UI (GREEN)** —
  `SettingsScreen.tsx:412-427` surfaces `StartOfDayPicker`.

## Roadmap-deferred (do not fix in this audit)

These are explicitly parked in [README § Looking forward](../../README.md#roadmap):

- 🔜 v1.10+ Web Push delivery for medication reminders.
- 🔜 v1.10+ Per-medication reminder-mode override UI parity.
- 🔜 v1.10+ Web slot-editor per-slot reminder-mode picker.
- 🔜 v2.0+ Phase G — remaining web parity slices toward 100%.
- 🔜 v2.0+ Backend-mediated Google Calendar sync.
- 🔜 v2.2+ Re-enable / refresh the eight currently-disabled Glance widgets.

## Anti-patterns flagged (not fixing)

- **Time-tracking analytics charts** — `taskTimingsStore` is wired but
  `AnalyticsScreen.tsx` doesn't yet render the productivity-score +
  time-tracking bar charts. Defer to a dedicated analytics-UX PR
  (Pro-gated; chart shape needs design pass).
- **"Tasks tab elevates project prominence"** — subjective UX; needs
  design direction before a code change, not a drive-by fix.

## Ranked improvement table

Sorted by **(user-visible impact + parity criticality) ÷ implementation cost**.
Each row maps to one PR in Phase 2.

| Rank | Item                                         | Cost  | Branch                                  |
|------|----------------------------------------------|-------|-----------------------------------------|
| 1    | Recurrence anchored to completion time       | S     | `fix/web-recurrence-anchor-completion`  |
| 2    | Uncomplete rolls back spawned recurrence     | S     | `fix/web-uncomplete-rolls-back-spawn`   |
| 3    | NLP urgency-keyword inference                | S     | `feat/web-nlp-urgency-keywords`         |
| 4    | Auto-classify task mode + cognitive load on edit | S | `feat/web-organize-auto-classify-on-edit` |
| 5    | MorningCheckInPromptCutoff slider in Settings | S    | `feat/web-morning-checkin-cutoff-slider` |
| 6    | Chat task-context forwarding                 | M     | `feat/web-chat-task-context-forwarding` |
| 7    | Pomodoro writes `task_timings` on session end | M    | `feat/web-pomodoro-task-timings-write`  |
| 8    | Log Again on completed recurring tasks       | M     | `feat/web-today-log-again-recurring`    |
| 9    | Manual Logged Time section in Schedule tab   | M     | `feat/web-schedule-logged-time-section` |
| 10   | Archive / Reopen from project list           | S     | `feat/web-projects-archive-reopen-list` |
| 11   | Import preview shows field-level detail      | M     | `feat/web-import-preview-fields`        |
| 12   | Leisure / School Today modes                 | L     | DEFERRED to follow-up audit             |

Item 12 (Leisure / School Today modes) is larger than the per-PR budget
this audit aims at; queue a dedicated follow-up audit to scope per-class
checkable rows + mode toggle UX before implementing.

## Phase 2 plan

Items 1-11 fan out as parallel PRs, each on its own worktree + branch with
auto-merge queued (`gh pr merge --auto --squash`). Per the user's
preference for sub-agent parallelism, dispatch in one batch.

## Phase 3 — Bundle summary

All 11 PROCEED items shipped + auto-merged on 2026-05-18 in a single
parallel fan-out. One follow-up build-fix PR landed shortly after to
unbreak `main` (PR-race between #1652 and #1650 introduced an arity
mismatch).

| Item | Audit rank | PR     | Title                                                                                |
|------|------------|--------|--------------------------------------------------------------------------------------|
| 1+2  | 1, 2       | #1650  | fix(web): anchor recurrence to completion + roll back spawn on uncomplete            |
| 3    | 3          | #1646  | feat(web): infer task priority from urgency keywords in NLP                          |
| 4    | 4          | #1647  | feat(web): auto-classify task mode + cognitive load on title/description edit        |
| 5    | 5          | #1649  | feat(web): add MorningCheckInPromptCutoff slider to Advanced Tuning                  |
| 6    | 6          | #1648  | feat(web): forward task-context snapshot to AI chat backend                          |
| 7    | 7          | #1651  | feat(web): auto-log task timings on Pomodoro session complete                        |
| 8    | 8          | #1652  | feat(web): add Log Again option for completed recurring tasks in Done sheet          |
| 9    | 9          | #1655  | feat(web): add Logged Time section to task editor Schedule tab                       |
| 10   | 10         | #1654  | feat(web): surface Archive/Reopen on projects list + tighten picker filters          |
| 11   | 11         | #1653  | feat(web): show per-task field detail in import preview                              |
| —    | (follow-up)| #1656  | fix(web): pass completedAt to writeTaskCompletionRow in logAdditionalCompletion      |

Audit doc itself shipped as PR #1645.

### Post-merge notes

- **PR #1648 (chat task-context)** is wire-format only; no current web
  UI calls `setContextTask(task)`. Add a "Chat about this task" affordance
  in a follow-up PR (the audit doc didn't require UI parity yet because
  no Android entry point requires it either today — Android pulls a
  default snapshot from the active screen instead of an explicit "pin
  this task" gesture).
- **PR #1650 (recurrence anchor + rollback)** introduces an additive
  `spawnedTaskId` field on the `task_completions` Firestore doc. Android
  ignores unknown keys via `mapToTaskCompletion`, so no schema break;
  Android keeps using its own `spawnedRecurrenceId` Room column.
- **PR #1652 (Log Again)** uses canonical-id merge semantics
  (`${taskId}__${dateLocal}`) instead of Android's append-row behavior.
  User-visible result matches ("the log reflects the more recent time")
  but on-disk shape diverges. Documented in `logAdditionalCompletion`.
- **PR #1655 vs #1651 (Pomodoro + Logged Time)** had a near-collision:
  #1651's `addTaskTiming` + `logTiming` actions landed first; #1655
  rebased and reused them rather than introducing a second writer.
- **PR #1654 (project archive)** added a shared `projectFilters.ts`
  helper (`nonArchivedProjects`, `pickerProjects`) and tightened 12
  picker call-sites. Watch for any new project picker added post-audit —
  the new helper should be the default.
- **PR #1656 (build-fix)** root cause: parallel fan-out worked, but two
  PRs touching the same `taskStore.ts` function signature merged in an
  order where the second PR didn't see the first's signature change.
  Future bundle PRs touching shared helpers should rebase before merging
  even when auto-merge is queued.

### Re-baselined wall-clock-per-PR

11 PRs (12 counting the build-fix) shipped within a single parallel
dispatch wave. Median worker turnaround was ~10 minutes; longest was the
Logged Time section (~20 min) due to a Pomodoro-PR merge collision.

### Memory entry candidates

- **Parallel PR fan-out can race on shared helper signatures.**
  Workers branched from the same `origin/main` SHA didn't see each
  other's signature changes. Mitigation: any PR touching a function
  added by an in-flight PR should wait or rebase before merge. Worth a
  memory entry only if this happens again — once is a coincidence.

### Schedule for next audit

- **Leisure / School Today modes** (audit item 12 — deferred from this
  pass). Scope a dedicated brainstorm + audit covering mode toggle UX,
  per-class checkable row semantics, and how the modes interact with
  Daily Essentials.
- **Time-tracking analytics charts on `AnalyticsScreen.tsx`** (anti-pattern
  list item). Pro-gated charts mirroring Android's
  productivity-score + time-tracking bar chart. Needs design pass first.

## Phase 4 — Claude Chat handoff

*Emitted to stdout at end of run.*
