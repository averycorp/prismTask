# D6 — AI Proposal Render Gap (AI Time Blocking + Sibling Surfaces) — Audit

**Branch**: `claude/fix-ai-proposal-display-AQLFO` (per session brief — supersedes the
prompt-default `claude/d6-ai-proposal-render-gap-<slug>` slug).
**Base**: `origin/main` @ `1592e09` (v1.8.49 build 847).
**Scope**: AI Time Blocking primary; eight sibling AI proposal surfaces secondary.
**Status**: **STOP-A — premise not reproducible by static review.** No production code changed.

---

## Phase 0 — Base verification + premise recon

### Base branch

`git fetch origin && git status` → already on `origin/main` HEAD `1592e09`. Required
PRs are visible in `git log -25 origin/main`:

| PR | SHA | Subject |
|----|-----|---------|
| #1164 | f44d1bd | fix(chat): chat quality audit + Phase 2 implementation |
| #1165 | ae09fb7 | Host AI hub on Today, remove Settings → AI Features |
| #1167 | 2d85d21 | feat(onboarding): Today guided tour card + drop ConnectIntegrationsPage |
| #1168 | 88ee65a | fix(chat): F8 follow-on bundle |
| #1171 | 2c977e6 | docs(privacy): F8 chat privacy doc update + V2 disclosure re-fire |

PR #1145 referenced by the prompt does not appear in the visible (shallow-clone) log.
The current local clone is shallow (50-commit window); references to "PR #1145
audits" in the prompt are taken as historical context only.

### Premise verification (entry-path trace)

Per prompt: the symptom is "backend creates AI time-blocking schedule successfully,
UI does not display the proposal."

Entry path traced end-to-end:

1. **Today screen** → "AI Tools" assist chip (`TodayScreen.kt:530`) → opens
   `TodayAiHubSheet` (`TodayScreen.kt:894-897`).
2. **AI hub sheet** → "Time Blocking" row (`AiSection.kt:88-92`) → calls
   `navigateAndDismiss(PrismTaskRoute.Timeline.route)` (`TodayAiHubSheet.kt:70`).
3. **NavGraph** wires `Timeline` route to `TimelineScreen(navController)`
   (`TaskRoutes.kt:94-95`).
4. **Timeline top bar** → AutoAwesome `IconButton`
   (`TimelineScreen.kt:142`) → `viewModel.showAutoBlockMyDaySheet()`.
5. **VM** sets `_showHorizonSheet=true` (`TimelineViewModel.kt:429`) →
   `AutoBlockHorizonSheet` renders (`TimelineScreen.kt:599-606`).
6. **User picks horizon + taps Generate** → `runAutoBlockMyDay()`
   (`TimelineViewModel.kt:449`).
7. **VM** flow: `_scheduleUiState=Loading` → `_showHorizonSheet=false` → API call
   `aiTimeBlockUseCase(...)` → on success: build `AiSchedule` →
   `_scheduleUiState=Success(schedule)` → `_showPreviewSheet=true`
   (`TimelineViewModel.kt:455-520`).
8. **TimelineScreen** observes both flows
   (`TimelineScreen.kt:103, 607`): `if (showPreviewSheet && scheduleForPreview != null) AutoBlockPreviewSheet(...)`.

**Conclusion**: render path is structurally intact — the proposal *is* wired to
display. Unit tests in `TimelineViewModelTest.kt` independently verify
`_showPreviewSheet` flips to `true` and `scheduleUiState` is `Success` after a
successful run (lines 115–148). The cited symptom does not reproduce on static
review.

---

## Phase 1 — Audit findings

### Item 1 — Backend response shape

- Endpoint: `POST /api/v1/ai/time-block` (`backend/app/routers/ai.py:567-694`).
- Pydantic response model: `TimeBlockResponse` (`backend/app/schemas/ai.py:276-283`)
  with fields `schedule: list[ScheduleBlock]`, `unscheduled_tasks: list[UnscheduledTask]`,
  `stats: TimeBlockStats`, `proposed: bool = True`, `horizon_days: int`.
- Router enforces `result.setdefault("proposed", True)` and overwrites
  `result["horizon_days"] = horizon_days` before constructing the response
  (`ai.py:691-692`), so the contract is upheld even when the AI service result
  omits these keys.
- `ScheduleBlock` carries `start`, `end`, `type`, optional `task_id: str`,
  `title`, `reason`, optional `date: str` (`schemas/ai.py:255-265`).
- `UnscheduledTask` carries `task_id: str`, `title`, `reason` (`schemas/ai.py:184-187`).
- Empty short-circuit when no incomplete tasks exist returns a fully populated
  zeroed response with `proposed=True` (`ai.py:595-611`) — never silently 200s
  with `null` fields.

**Verdict**: backend response shape is well-defined and self-consistent.

### Item 2 — Client-side response handling

- Retrofit method: `PrismTaskApi.getTimeBlock(@Body request)` returning
  `TimeBlockResponse` (`PrismTaskApi.kt:134-137`).
- Client DTO: `TimeBlockResponse` (`ApiModels.kt:448-456`) with matching
  `@SerializedName` annotations: `schedule`, `unscheduled_tasks` (→
  `unscheduledTasks`), `stats`, `proposed` (default `true`), `horizon_days` (→
  `horizonDays`).
- `ScheduleBlockResponse` (`ApiModels.kt:425-438`) maps `task_id` →
  `taskId: String?` and includes optional `date: String?`.
- `UnscheduledTaskResponse` (`ApiModels.kt:325-332`) maps `task_id` →
  `taskId: String`.
- `TimeBlockStatsResponse` (`ApiModels.kt:440-446`) maps all snake_case backend
  fields to camelCase client fields with `@SerializedName`.

**Verdict**: every backend field has a client counterpart and a matching
`@SerializedName` where casing differs. **No DTO drift.**

State plumbing path:
`AiTimeBlockUseCase.invoke()` → `api.getTimeBlock(request)` →
`TimelineViewModel.runAutoBlockMyDay` resolves cloud→local IDs and constructs
`AiSchedule` (`TimelineViewModel.kt:477-510`) → `_scheduleUiState =
Success(schedule)` → `_showPreviewSheet = true` (lines 517-520). Both flows are
plain `MutableStateFlow` (no `WhileSubscribed` lifecycle window on the writes).

The derived `aiSchedule: StateFlow<AiSchedule?>` (`TimelineViewModel.kt:135-137`)
*does* use `WhileSubscribed(5000)` and is collected by the screen at
`TimelineScreen.kt:101` but is never read by the rendering logic — it is dead
state in the screen and could be removed in a follow-up cleanup. **Not the
bug** since the preview sheet reads the canonical `_scheduleUiState` directly.

### Item 3 — Compose render path

- `TimelineScreen.kt:103` — `val scheduleUiState by
  viewModel.scheduleUiState.collectAsStateWithLifecycle()`.
- `TimelineScreen.kt:607` — `val showPreviewSheet by
  viewModel.showPreviewSheet.collectAsStateWithLifecycle()`.
- `TimelineScreen.kt:608` — `val scheduleForPreview = (scheduleUiState as?
  AiScheduleUiState.Success)?.schedule`.
- `TimelineScreen.kt:609-615` — `if (showPreviewSheet && scheduleForPreview !=
  null) AutoBlockPreviewSheet(...)`.
- `AutoBlockPreviewSheet` (`TimelineScreen.kt:907-993`) renders header, stats
  row, day-grouped block list, deferred footer, and Approve/Cancel buttons.
- `if (schedule.blocks.isEmpty()) Text("No blocks proposed.")` is inside the
  sheet body (line 941), so even an empty schedule still renders the sheet
  (only fully-empty `blocks + unscheduled` short-circuits earlier to
  `AiScheduleUiState.Empty` and skips the sheet).
- No `LaunchedEffect`/lifecycle wiring is required for the preview sheet — the
  `if (showPreviewSheet && …)` predicate alone gates composition.

Unit tests verifying this exact path:

- `runAutoBlockMyDay_success_opensPreview_without_writing`
  (`TimelineViewModelTest.kt:115-148`): asserts `vm.showPreviewSheet.value ==
  true` and `vm.scheduleUiState.value is Success` after the API call.
- `commitProposedSchedule_writes_each_task_block` (lines 151-202): asserts
  `vm.showPreviewSheet.value == false` after `commitProposedSchedule()`.
- `cancelProposedSchedule_does_not_write` (lines 205-236): asserts the cancel
  path returns to `Idle`.

**Verdict**: render path is intact. No early-return or null-swallow bug
identified. Unit tests cover the success → preview-shown transition.

### Item 4 — Sibling AI proposal surfaces (widened sweep)

| # | Surface | Verdict | Notes |
|---|---------|---------|-------|
| 1 | AI Time Blocking (Timeline) | **GREEN** | Render path intact (Items 1–3). |
| 2 | Daily Briefing (`ui/screens/briefing/`) | **GREEN** | Sealed-state pattern; Briefing UseCase + ViewModel present, screen subscribes via `collectAsStateWithLifecycle`. No DTO drift vs `BriefingResponse`. |
| 3 | Smart Pomodoro AI extras (`ui/screens/pomodoro/`) | **GREEN** | `PomodoroPlanUiState` mirrors `AiScheduleUiState` (Idle/Loading/Success/Empty/Error) at `SmartPomodoroViewModel.kt:58-63`; identical render-gating idiom. |
| 4 | Weekly Planner (`ui/screens/planner/`) | **GREEN** | `WeeklyPlanResponse` DTO matches backend `WeeklyPlanResponse` field-for-field via `@SerializedName`. |
| 5 | Morning Check-In (`ui/screens/checkin/`) | **GREEN** | Uses `MorningCheckInResolver` + DataStore; AI summary (when present) is a regular flow surface. |
| 6 | AI Coach Chat actions (`ui/screens/chat/`) | **GREEN** | `ChatRepository.sendMessage` writes the response actions onto the assistant `ChatMessage` (`ChatRepository.kt:92-97`); `ChatScreen.kt:429-446` renders chips when `message.actions.isNotEmpty()`. |
| 7 | Mood Analytics (`ui/screens/mood/`) | **GREEN** | Read-mostly surface; AI insights block renders from `MoodCorrelationEngine` results, no proposal-style sheet. |
| 8 | Eisenhower Matrix (`ui/screens/eisenhower/`) | **GREEN** | `EisenhowerUiState` sealed type with `UiStateBanner` empty/error rendering (`EisenhowerScreen.kt:123, 194-200`). |
| 9 | Paste-to-Extract (`ui/screens/extract/`) | **GREEN** | `ConversationTaskExtractor` flow surfaces extracted candidates into a review inbox; no preview-sheet pattern. |
| 10 | Weekly Review (`ui/screens/review/`) | **GREEN** | `WeeklyReviewUiState` sealed type at `WeeklyReviewViewModel.kt:40-63`; same Idle/Loading/Success/Empty pattern. |

No surface was found to be RED. **STOP-D not triggered** (≤1 RED).

### Item 5 — Architectural staleness check

Re-verified the architectural facts cited by the prompt against current source:

- **Backend endpoint path** `/api/v1/ai/time-block`: confirmed at
  `backend/app/routers/ai.py:567`.
- **AI Tools hub navigation** (PR #1165): confirmed at
  `TodayScreen.kt:530-531, 894-897` and `TodayAiHubSheet.kt`. The hub is hosted
  on Today, not Settings — Settings comment at `SettingsScreen.kt:257`
  acknowledges the relocation.
- **`ChatActionResponse` schema** (PRs #1164/#1168): present at
  `ApiModels.kt:589` and consumed by `ChatRepository.sendMessage` and
  `ChatViewModel.executeAction`. The schema is used to render action chips,
  not to drive a separate "proposal sheet" — the chat surface does **not**
  use the same preview-sheet idiom as Time Blocking.
- **Onboarding flow** (PR #1167): doesn't intersect AI proposal rendering;
  ConnectIntegrationsPage was dropped, no replacement-induced regression on
  the Time Blocking entry path.
- **Privacy V2 disclosure** (PR #1171): chat-only; not on the Time Blocking
  surface.

No stale architectural fact is being relied on by this audit's conclusions.

### Item 6 — STOP conditions

- **STOP-A (premise wrong)**: **TRIGGERED.** The symptom "backend creates
  schedule successfully, UI does not display the proposal" cannot be
  reproduced by static code review of `main` at `1592e09`. The render path
  reads canonical `_scheduleUiState` (not `WhileSubscribed`) and unit tests
  cover the success path. Reframing required before any production change.
- **STOP-D (sibling balloon)**: not triggered (no RED siblings).
- **STOP-F (>200 LOC)**: not applicable — no implementation attempted.
- **STOP-LOC drift**: not applicable.

---

## Recommended next step (operator)

Production code changes are **not** justified by this audit. To advance, the
operator needs to supply *runtime* evidence:

1. **Crashlytics / logcat capture** of an in-the-wild repro: was
   `_showPreviewSheet` actually flipped (`TimelineVM` log of "Auto-block my day
   failed" not present)? Or did the catch path fire and we mis-classified the
   symptom (Error banner shown, not the preview sheet)?
2. **Network HAR or backend log** confirming the response really was 200 with
   a non-empty `schedule[]` — the `_log_empty_short_circuit` path
   (`ai.py:596`) returns 200 with empty `schedule` and no `unscheduled_tasks`,
   which the client would route to `AiScheduleUiState.Empty` ("Nothing to
   schedule right now."), not to the preview sheet. **An operator misreading
   "Empty banner" as "no proposal rendered" is the most likely premise
   reframe.**
3. **User tier** at the time of the repro: a FREE-tier user hitting the
   AutoAwesome icon falls into `_showUpgradePrompt = true` (`TimelineVM.kt:404,
   426, 451`) — the upgrade dialog renders, not the preview sheet. Another
   plausible reframe.

Once any of these data points narrow the scope, a targeted fix prompt can land
without scanning all sibling surfaces a second time.

---

## Pre-flight checklist (recap)

- [x] Phase 0 base-branch verification passed (PRs #1164/#1165/#1167/#1168/#1171 visible).
- [x] Phase 0 premise verification — **failed**, STOP-A fired.
- [x] Audit doc with Items 1–4 verdicts written.
- [x] Item 4 sibling sweep complete (10 surfaces, all GREEN).
- [x] Item 5 staleness sweep complete (no stale facts relied on).
- [x] STOP-D check passed (0 sibling RED).
- [x] No code edits attempted — STOP-A blocks Phase 2 by design.
