# F8 Chat-Quality Follow-On Bundle Audit (Phase 1)

**Scope.** Six chat-quality follow-on items deferred from PR #1164's
§ 5.1 with explicit re-trigger criteria: B.3 rich `create_task` fields,
B.4 `start_timer` action wiring, C.3 clear-chat confirmation dialog,
C.4 action-chip TalkBack labels, C.5 empty-state starter prompts, D.3
double-send window guard. Single bundle PR (operator-locked May 6,
post-#1164).

**Branch.** `claude/audit-f8-chat-followon-Ptqzs`. **HEAD before audit.**
`f44d1bd` (PR #1164 squash-merge, 2026-05-07 06:06Z). **App.** 1.8.47 /
vc 845. **Room.** v76 (no migration in scope). **Backend alembic head.**
`024_add_beta_codes`.

**Method.** Single-pass recon-first (memory #18 quad sweep). Read every
chat path file in full, cross-referenced against PR #1164 patterns
(`docs/audits/CHAT_QUALITY_AUDIT.md`), looked at sibling AI surfaces
(extract / planner / briefing / pomodoro) for shared primitive
patterns, and re-grepped chat for fresh dead code post-#1164.

---

## 1. Premise verification

| # | Operator-locked premise | Verdict | Evidence |
|---|---|---|---|
| 1 | PR #1164 shipped chat-quality root-cause fixes (per-type handle\* / `_actionsInFlight` / `ChatActionResult` / `ChatHistoryEntry` + `ChatTaskContext`) | GREEN | All four primitives present in `ChatViewModel.kt` (70-89, 188-235, 237-338) and `ApiModels.kt:545-582`. Local clone needed `git fetch && git rebase origin/main` to see #1164 (merged ~2h before this audit started). |
| 2 | Six items per § 5.1 still unshipped after #1164 | GREEN | `git log` since `f44d1bd` shows no chat-touching commit. `handleCreateTask` (ChatViewModel.kt:324-338) drops description/tags/project; `start_timer` is `ChatActionResult("Timer Started")` inline (line 203); ChatScreen DeleteSweep is single-tap (line 207); AssistChip labels lack task context (lines 381-394); WelcomeCard has no starter prompts (lines 274-301); `sendMessage` has no leading idempotency gate (lines 128-166). |
| 3 | Memory #28 fan-out bundling permits single-PR scope | GREEN | Operator-locked May 6 in #1164 § 5.1. Aggregate LOC well under STOP-B 1000-line ceiling (see § 4 verdict matrix). |
| 4 | F8 placement (post-launch hygiene) is correct | GREEN | None of the six are launch-blockers. Chat-quality root causes (zero memory, no task content forwarded, hidden disclosure, double-tap dupes, no undo) closed in #1164. These six are completeness work. |

**STOP conditions evaluated** — see § 5. STOP-A4 (B.4 needs scope-down)
and STOP-C (C.5 copy needs surfacing) fire as anticipated; both have
default-recommended resolutions that the audit applies inline rather
than blocking on.

---

## 2. A.1 — PR #1164 pattern (the reusable infrastructure)

Re-read post-rebase. The four primitives the bundle will lean on:

### 2.1 Per-type handle\* helpers (ChatViewModel.kt:188-338)
```
executeAction(action) → when (action.type) {
  "complete"          → handleComplete(action)
  "reschedule"        → handleReschedule(action)
  "reschedule_batch"  → handleRescheduleBatch(action)
  "breakdown"         → handleBreakdown(action)
  "archive"           → handleArchive(action)
  "start_timer"       → ChatActionResult("Timer Started")    ← still inline
  "create_task"       → handleCreateTask(action)             ← drops fields
}
```
Both B.3 and B.4 extend these. B.3 enriches `handleCreateTask`. B.4
either gets its own `handleStartTimer` (preferred for symmetry with
others) or routes a navigation event from the inline branch.

### 2.2 `_actionsInFlight` idempotency (ChatViewModel.kt:88-89, 218-235)
Signature derived per-type. The launch wraps the body in
`try/.../finally { _actionsInFlight.value -= signature }`. **D.3
should NOT extend this Set** — the send button is debounce/guard, not
chip-level idempotency. Use a separate `_lastSendInFlight: AtomicBoolean`
or check `_isTyping.value` synchronously at function head.

### 2.3 `ChatActionResult` SharedFlow + snackbar undo
```
data class ChatActionResult(
    val message: String,
    val undoLabel: String? = null,
    val undoAction: (suspend () -> Unit)? = null
)
```
ChatScreen.kt:108-128 collects and renders. B.4's nav event is NOT a
ChatActionResult — it's a separate one-shot SharedFlow ChatScreen
collects to call `navController.navigate(...)`. B.3 fits the
existing shape (no undo on create_task; matches current behavior).

### 2.4 `ChatHistoryEntry` + `ChatTaskContext` (ApiModels.kt:545-562)
Forwarded to backend from ChatRepository.sendMessage. Not directly
touched by B.3-D.3 except B.3 may want backend prompt to enrich
`create_task` proposals (see § 3.3).

---

## 3. Per-item recon (A.3 - A.8)

### 3.1 A.3 — B.3 Rich `create_task` (YELLOW, splittable)

**Current behavior** (ChatViewModel.kt:324-338): only forwards `title`,
`dueDate`, `priority`. ChatActionResponse data class has 9 fields
(ApiModels.kt:572-582); no `description`/`tags`/`project`/`recurrence`.
Backend `_CHAT_SYSTEM_PROMPT` (ai_productivity.py:1089) only allows
`{type, title, due, priority}` for create_task — backend won't propose
richer fields today even if the API model accepted them.

**Spans three layers:**
1. **Backend prompt** — extend the create_task action shape to allow
   `description`, `tags` (array of strings), `project` (string name).
   Add a one-line guidance ("only emit fields the user actually named
   or implied"). Add backend ChatActionPayload schema fields.
2. **Android API model** — extend `ChatActionResponse` with the 3
   fields (Gson tolerates absent fields).
3. **Android handleCreateTask** — switch from `taskRepository.insertTask(TaskEntity(...))`
   to `taskRepository.addTask(title=..., description=..., projectId=..., ...)`
   so behavior parity with QuickAdd. Resolve project name → id via
   `ProjectDao.getProjectByNameOnce` (already injected post-#1164 for
   context snapshot; check helper exists). Resolve tag names → ids via
   `TagRepository.searchTags(name).first()` find-or-create pattern;
   then `TagRepository.setTagsForTask(taskId, tagIds)` post-insert.

**Recurrence — explicit defer.** RecurrenceRule is JSON serialized via
Gson (`RecurrenceConverter`); shape is non-trivial (`type`, `interval`,
`weekdays`, `monthDays`, `endDate`, `afterCompletion`...). Eliciting it
from chat needs prompt expansion + parser robustness + AI hallucination
guards. **Recommend split: B.3 ships description+tags+project; B.3b
recurrence stays deferred.** If operator overrides, scope expands ~80
LOC for the parser + tests.

**Estimate:** 130-200 LOC across backend (~50) + Android (~80-150)
including tests.

**Verdict:** PROCEED with split. Recurrence deferred to F9.

### 3.2 A.4 — B.4 `start_timer` (GREEN, **STOP-A4 accepted**)

**Current behavior** (ChatViewModel.kt:203): `"start_timer" ->
ChatActionResult("Timer Started")`. No navigation, no duration
plumbing. ChatActionResponse has `minutes: Int?` (ApiModels.kt:578) —
field exists but is **never read anywhere in the codebase** (latent
dead field; B.4 closes it).

**STOP-A4 fires.** TimerScreen (`TimerScreen.kt:81`) and
SmartPomodoroScreen (`SmartPomodoroScreen.kt:62`) both take only
`(navController, viewModel)`. `PrismTaskRoute.Timer = "timer"`
(NavGraph.kt:126) is a static route — no nav arguments. Adding
`?duration=N` would require:
- Route-pattern change in NavGraph (deep-link arg)
- TimerViewModel constructor accepting SavedStateHandle and reading
  `duration` to override its DataStore-driven `WorkDurationMinutes`
- Risk of stomping the user's configured timer length

**Per STOP-A4 default**: scope-down to "navigate to Timer screen, user
picks duration manually". The `action.minutes` value is surfaced in the
ChatActionResult message text only ("Starting Timer (25 min)"), so the
user sees what AI suggested even though the timer screen opens at the
user's configured default.

**Wiring:**
- New SharedFlow `_navigationEvents: MutableSharedFlow<NavEvent>` on
  ChatViewModel (sealed class with `OpenTimer` case).
- New `handleStartTimer(action): ChatActionResult` for symmetry with
  other handlers; emits the nav event before returning.
- ChatScreen.kt: new `LaunchedEffect(Unit) { viewModel.navigationEvents.collect { ... navController.navigate(PrismTaskRoute.Timer.route) } }`.

**Estimate:** ~50 LOC (handler + flow + collector + 1 test).

**Verdict:** PROCEED scoped-down. Deep-link param plumbing deferred to
F9 with re-trigger "users complain timer doesn't honor AI-suggested
duration".

### 3.3 A.5 — C.3 Clear-chat confirmation (GREEN, trivial)

**Current behavior** (ChatScreen.kt:206-213): IconButton(DeleteSweep)
directly calls `viewModel.clearConversation()` — no confirmation, no
undo. PR #1164's per-action undo is action-chip-only; doesn't apply to
the destructive bulk clear.

**Convention exists:** `showUpgradePrompt` (ChatScreen.kt:138-155) and
`showDisclosure` (lines 157-173) already render `AlertDialog`. Mirror
that — add `_showClearConfirm: MutableStateFlow<Boolean>` on ViewModel,
flip true on DeleteSweep tap, render AlertDialog with "Clear" + "Cancel"
buttons, only call `clearConversation()` on confirm.

**Operator question — "Don't ask again"?** Recommend NO — single-shot
confirm matches medication/task delete confirmation convention; an
extra preference toggle adds preference cardinality without enough
upside. Default applied; operator can override.

**Estimate:** ~40 LOC (ViewModel state + AlertDialog + 1 test).

**Verdict:** PROCEED.

### 3.4 A.6 — C.4 Action-chip TalkBack labels (YELLOW, polish)

**Current behavior** (ChatScreen.kt:381-394): AssistChip wraps a `Text`
in its `label` slot. Compose maps the Text → chip's accessibility text,
so TalkBack DOES read the visible label ("Mark Complete", "Move to
Today", etc).

**Real gap is task context.** The visible label "Mark Complete" without
saying which task is fine for sighted users (the chat bubble above
provides it visually) but TalkBack reads chips out of bubble order. AI
chips in batch responses ("Reschedule 3 Tasks") similarly lack which
tasks. Two fixes:

**Option A — enrich visible label** (preferred, cheaper):
- For `create_task`: include `action.title` ("Add Task: <title>")
- For `start_timer`: include `action.minutes` ("Start a 25-min Timer")
- For `complete` / `reschedule` / `archive` with task_context: append
  task title from the bubble's parent context (requires threading
  `contextTask` into ChatBubble/ActionChip; small refactor)

**Option B — semantic-only override** (Modifier.semantics on
AssistChip): doesn't change visual but TalkBack reads the override.
More accessibility-purist but harder to verify in screenshot tests.

Recommend Option A for the create_task + start_timer case (zero
threading needed — fields already on `action`) and skip Option B for
context-task chips (the bubble text right above already provides the
task title; TalkBack users read it). Mark the rest of C.4 closed-by-
construction since the visible labels already work for TalkBack today.

**Estimate:** ~20-30 LOC (label `when` block expansion + 1 a11y test).

**Verdict:** PROCEED Option A only.

### 3.5 A.7 — C.5 Starter prompts (GREEN, **STOP-C** anticipated)

**Current behavior** (ChatScreen.kt:274-301): WelcomeCard shows static
intro text, no clickable seeds.

**STOP-C fires** as anticipated — copy is product/UX decision. Phase 1
proposes 4 defensible defaults; operator approves or substitutes
inline. **Default-applied** unless operator overrides:

1. "What should I focus on today?"
2. "Help me reschedule overdue tasks"
3. "Break down my biggest task"
4. "Suggest a 25-minute focus session"

These map to existing AI capabilities (briefing, batch reschedule,
breakdown action, start_timer action), so even before B.3/B.4 ship
they produce useful follow-ups.

**Wiring:** AssistChip row inside WelcomeCard. On tap → call
`viewModel.sendMessage(prompt)` directly (don't pre-fill the input;
makes it harder to send accidentally). Reuses existing send path
including the D.3 guard once that lands.

**Estimate:** ~60 LOC (chip row composable + click → send + 1 test).

**Verdict:** PROCEED with proposed copy. Operator can substitute mid-
implementation.

### 3.6 A.8 — D.3 Double-send guard (GREEN, trivial defensive)

**Current behavior** (ChatViewModel.kt:128-166):
```kotlin
fun sendMessage(text: String) {
    if (text.isBlank()) return
    if (!proFeatureGate.hasAccess(...)) { _showUpgradePrompt.value = true; return }
    viewModelScope.launch {
        _isTyping.value = true   // ← only set INSIDE launch
        ...
    }
}
```
The IconButton has `enabled = !isTyping && value.isNotBlank()`
(ChatScreen.kt:474), so Compose normally blocks the second tap. **The
hole**: between user tap and `_isTyping.value = true` taking effect on
recomposition, a second tap could land. In practice this is ~16ms; a
human cannot double-tap that fast on a touch screen. Still: a leading
defensive guard costs nothing and closes the synchronous race.

**Fix:** add at function head, before the `viewModelScope.launch`:
```kotlin
if (_isTyping.value) return
```
That snapshot read is synchronous and cannot race with itself.

The `KeyboardActions(onSend = { onSend() })` path (ChatScreen.kt:463)
shares the same upstream `enabled = !isTyping`-gated callback path
through `ChatInputBar.onSend`, so the same guard covers IME-Send too.

**Estimate:** ~5 LOC + 1 test.

**Verdict:** PROCEED.

---

## 4. B.1 — Per-item verdict matrix

| # | Item | Verdict | Backend LOC | Android LOC | Tests | Notes |
|---|---|---|---|---|---|---|
| B.3 | Rich `create_task` (description/tags/project) | PROCEED-SPLIT | ~50 | ~150 | 4 | Recurrence deferred (operator can override) |
| B.4 | `start_timer` nav scoped-down | PROCEED | 0 | ~50 | 1 | STOP-A4 accepted; deep-link param defer |
| C.3 | Clear-chat confirmation | PROCEED | 0 | ~40 | 1 | "Don't ask again" deferred per default |
| C.4 | Action-chip TalkBack labels | PROCEED-A | 0 | ~30 | 1 | Option A (label enrichment) only |
| C.5 | Starter prompts | PROCEED | 0 | ~60 | 1 | Default copy, operator can substitute |
| D.3 | Double-send guard | PROCEED | 0 | ~5 | 1 | Defensive leading return |

**Aggregate.** Backend ~50 LOC + Android ~335 LOC + ~9 new tests ≈ 385
LOC + tests. Well under STOP-B 1000-line ceiling. Single-PR viable.
PR #1164 shipped 1526/-100 — this should land closer to 600/-50 net
after R8/proguard rule additions and detekt fixes are accounted for.

---

## 5. STOP-conditions evaluated

| STOP | Condition | Fired? | Resolution |
|---|---|---|---|
| A | Item already shipped | NO | None of 6 shipped between #1164 and audit. |
| **A4** | B.4 Pomodoro/Timer takes no input params | **YES** | Scope-down accepted: nav-only, no deep-link. Defer param plumbing to F9. |
| B | Aggregate LOC > 1000 | NO | ~385 LOC < 1000. Single-PR. |
| **C** | C.5 copy needs operator approval | **YES (anticipated)** | Default 4 prompts proposed; operator can substitute mid-implementation without re-audit. |
| D | Sibling balloon | NO | A.9 surfaced no comparable AI-feature crash class. PasteConversationViewModel `reset()` (line 83) is a candidate clear-affordance check but out of bundle scope; defer. |
| E | A.10 surfaces ≥3 dead-code branches | NO | Only `ChatActionResponse.minutes` is latent (closed by B.4). All 3 branches #1164 listed are verified closed. |
| F | B.4 needs cross-screen plumbing | NO (with A4 scope-down) | Scope-down keeps changes inside chat module. |

Per audit-first hard rule (skip-checkpoints memory), STOP-A4 and STOP-C
resolutions are applied inline and Phase 2 proceeds unless operator
mid-streams a correction.

---

## 6. A.9 — Sibling-primitive (e) axis (deferred, NOT auto-filed)

| Surface | Question | Finding |
|---|---|---|
| `PasteConversationViewModel.reset()` (line 83) | Is the extract-screen "reset" affordance a destructive single-tap? | Need direct ChatScreen-style read; if yes, mirror C.3 confirmation. **F9 candidate.** |
| `DailyBriefingScreen` | Empty welcome card with no starter content? | None observed; briefing has structured day rendering, not chat. **No action.** |
| `WeeklyPlannerScreen` | Same | Same. **No action.** |
| `SmartPomodoroScreen` | Empty welcome card? | Has plan generation + session UI; not a "blank-and-stare" surface. **No action.** |

**Re-trigger criteria.** File F9 audit if user reports surface a
destructive single-tap on extract reset, OR if Crashlytics shows ≥1
"accidental data loss in extract" complaint.

---

## 7. A.10 — Dead-code re-scan in chat

Verified via `grep -rn "shouldRefreshContext|markContextRefreshed|messagesSinceContextRefresh"` (zero hits — all closed by #1164) and `grep -n "tier" ApiModels.kt` (chat block tier-free; lines 44/50/674 are non-chat endpoints).

Latent dead field: `ChatActionResponse.minutes` (ApiModels.kt:578) —
declared but unread anywhere. **Closed by B.4** when handler emits
"Starting Timer (${action.minutes} min)" message. Net: 0 dead-code
branches outside bundle scope post-PR.

**Pattern reinforcement.** Dead-code-completion pattern data points:
- PR #1164 closed `_showDisclosure` + `tier` + `shouldRefreshContext` (3)
- This PR closes `ChatActionResponse.minutes` (1) via B.4

That's a 2nd post-audit instance. Per memory wait-for-third rule, NOT
memorized yet — flag for next session.

---

## 8. Phase 2 scope

**Branch.** Continue on `claude/audit-f8-chat-followon-Ptqzs` (already
checked out, already rebased onto `f44d1bd`).

**Per-PR shape.** Single bundle PR (operator-locked). Commit cadence
within the bundle, in order:

1. **Backend** — `_CHAT_SYSTEM_PROMPT` extension + `ChatActionPayload`
   schema fields for B.3.
2. **Android API model** — `ChatActionResponse` field additions for
   B.3 + nav-event sealed class for B.4.
3. **B.4** — `handleStartTimer` + `_navigationEvents` SharedFlow + ChatScreen
   collector. Closes `action.minutes` latent dead field.
4. **B.3** — `handleCreateTask` enrichment (`addTask` switch + project
   resolution + tag find-or-create + setTagsForTask).
5. **D.3** — leading `if (_isTyping.value) return` guard.
6. **C.3** — `_showClearConfirm` flow + AlertDialog.
7. **C.4** — label enrichment in ActionChip `when` block (Option A).
8. **C.5** — starter-prompt chip row in WelcomeCard.

Tests interleave (unit + 1 backend pytest for B.3 schema). Each commit
keeps the test suite GREEN.

**Anti-patterns to avoid** (carried forward from Phase 0 spec):
- Do not extend `_actionsInFlight` for D.3 — separate concern.
- Do not change `ChatActionResult` shape; B.4 nav event is its own flow.
- Do not change `_CHAT_SYSTEM_PROMPT` in ways that stop eliciting the
  existing 7 action types.
- Do not add deep-link params to TimerScreen / SmartPomodoroScreen
  (STOP-A4 scope-down).
- Do not file the A.9 sibling findings as auto-PRs — operator pre-approval.
- Do not add a "Don't ask again" toggle on C.3 (defer to F9 if asked).

---

## 9. Open questions for operator (default-applied)

1. **B.3 recurrence in scope?** Default: NO — split as B.3b deferred.
   Override-cost: ~80 LOC for prompt + RecurrenceRule parser. _If
   operator wants it in: re-trigger Phase 1 amendment._
2. **C.5 starter-prompt copy.** Default: 4 prompts proposed in § 3.5.
   Operator may substitute inline during Phase 2 without re-audit.
3. **C.3 'Don't ask again' toggle.** Default: NO. Single-shot confirm
   sufficient.

---

## 10. Improvement table (sorted by wall-clock-savings ÷ implementation-cost)

| Rank | Item | Savings | Cost | Notes |
|---|---|---|---|---|
| 1 | D.3 double-send guard | High (silent dup mitigation) | ~5 LOC | 1-line defensive return |
| 2 | C.3 clear-chat confirm | High (data-loss mitigation) | ~40 LOC | Mirror existing AlertDialog |
| 3 | C.5 starter prompts | Medium (activation lift) | ~60 LOC | Default copy ready |
| 4 | C.4 TalkBack labels | Medium (a11y polish) | ~30 LOC | Option A only |
| 5 | B.4 start_timer nav | Medium (closes dead toast + dead field) | ~50 LOC | Scoped-down |
| 6 | B.3 rich create_task | High but spans 3 layers | ~200 LOC | Split (no recurrence) |

---

## 11. Anti-patterns flagged (not necessarily fixed)

- **`ChatActionResponse.subtasks`** is sent for `breakdown` only (per
  prompt). If a future action type also wants `subtasks`-like child
  payload, the JSON shape will need a discriminated union — current
  flat data class won't scale past ~3 more action types. Track for the
  PR #1164 § 5.1 B.1 ("native Anthropic tool-use migration") deferred
  item.
- **Welcome-card text and subtitle "General"** (ChatScreen.kt:163-166)
  may not match the theme grammar (cyberpunk/sunset/brackets variants
  in TimerScreen show theme-aware subtitles). If chat moves to a
  themed top bar later, refactor at that time.
- **`resolveDate(action.due)` ignores `dueTime`** in chat. Tasks
  created from chat are date-only. Fine for now; flag if chat starts
  proposing time-of-day.

---

_Phase 1 complete. Proceeding to Phase 2 implementation per audit-first
default (no checkpoint gate)._
