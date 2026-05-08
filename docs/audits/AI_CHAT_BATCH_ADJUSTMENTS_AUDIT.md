# AI Chat Batch Adjustments Audit

**Scope.** User wants to be able to make batch adjustments to projects and
tasks through the AI Chat surface ("Coach"). Today the chat-side action
grammar covers only one batch shape (`reschedule_batch`); everything else
is single-entity. Meanwhile, a fully built natural-language batch pipeline
(`/api/v1/ai/batch-parse` → `BatchPreviewScreen` → `BatchOperationsRepository`
→ undo + history) already exists and ships with QuickAdd.

This audit asks: *what is the minimum, safe change to make the AI Chat
capable of triggering batch adjustments to tasks and projects?*

---

## Item 1 — Chat action grammar coverage (RED)

**Findings.**
`backend/app/schemas/ai.py:530` defines the entire chat action grammar:

```python
_CHAT_ACTION_TYPE_PATTERN = (
    "^(complete|reschedule|reschedule_batch|breakdown|archive|"
    "start_timer|create_task)$"
)
```

The mirror exists on Android in `ChatViewModel.kt:368-376` (`when (action.type)`)
and `ChatScreen.kt:482-501` (chip labels). Nothing in this set covers:

- batch complete, batch archive, batch priority change, batch tag change,
  batch project move on tasks
- *any* project mutations (archive a project, mark a project complete)
- batch operations on habits or medications

So even if a user types "complete every task tagged #errands" or "archive
my Q1 projects" into chat, the AI cannot emit a usable action — the
backend would drop any unknown `type` via `ChatActionPayload` validation
(`backend/app/routers/ai.py:954-966`).

**Risk classification.** RED — this is the core gap blocking the feature.

**Recommendation.** PROCEED, but via Item 2's bridge approach (cheaper
and reuses the mature batch-parse pipeline) rather than re-implementing
N batch primitives inline in chat.

---

## Item 2 — Bridge chat → existing batch-parse pipeline (GREEN)

**Findings.**
The infrastructure to turn a natural-language batch command into a safe,
previewable, undoable mutation set already exists end-to-end:

- Backend: `backend/app/routers/ai.py:858` (`POST /batch-parse`) +
  `backend/app/services/ai_productivity.py:1060` (`parse_batch_command`).
  Supports TASK (RESCHEDULE / DELETE / COMPLETE / PRIORITY_CHANGE /
  TAG_CHANGE / PROJECT_MOVE), HABIT (COMPLETE / SKIP / ARCHIVE), PROJECT
  (ARCHIVE), MEDICATION (COMPLETE / SKIP / DELETE / STATE_CHANGE).
- Android: `BatchOperationsRepository.kt` (1052 lines, mature) +
  `BatchPreviewScreen.kt` + `BatchHistoryScreen.kt` + undo bus.
- Nav: `PrismTaskRoute.BatchPreview` already accepts a `command` query
  param (`NavGraph.kt:192`).
- Trigger today: `QuickAddViewModel.kt:388` runs `BatchIntentDetector`
  on submit and emits `_batchIntents` → screen navigates to
  `PrismTaskRoute.BatchPreview.createRoute(commandText)`.

The cleanest way to give chat batch power is to add ONE new chat action
type, `batch_command`, carrying the user's natural-language phrasing as
`command_text`. On tap, the Android client navigates to
`BatchPreviewScreen` with that command. The user gets the existing
preview-then-apply UX (with ambiguity resolution, per-row diff, undo,
history) for free. No new mutation primitives, no new undo paths, no
new Pro-gating logic — `BatchPreviewViewModel` already enforces
`AI_BATCH_OPS`.

This handles batch adjustments to BOTH tasks AND projects (the user's
stated goal) on day one, plus habits and medications as a side effect.

**Risk classification.** GREEN — small, additive, reuses ~1,500 lines of
already-shipped & well-tested batch infrastructure.

**Recommendation.** PROCEED. One PR, one cohesive scope.

Concrete changes:

1. `backend/app/schemas/ai.py` — extend `_CHAT_ACTION_TYPE_PATTERN` to
   include `batch_command`; add `command_text: Optional[str]` to
   `ChatActionPayload`.
2. `backend/app/services/ai_productivity.py:1161` — extend
   `_CHAT_SYSTEM_PROMPT` to document the new action shape and instruct
   the model to emit it when the user expresses a multi-entity adjustment
   that goes beyond the existing `reschedule_batch`.
3. `app/src/main/java/.../data/remote/api/ApiModels.kt:589` — add
   `commandText: String?` to `ChatActionResponse`.
4. `app/src/main/java/.../ui/screens/chat/ChatViewModel.kt` — add
   `handleBatchCommand` that emits a new `ChatNavEvent.OpenBatchPreview`
   with `commandText`. Add `batch_command` to the `executeAction` `when`,
   to `actionSignature`, and (no `taskId`/`taskIds` requirements).
5. `app/src/main/java/.../ui/screens/chat/ChatScreen.kt` — handle
   `OpenBatchPreview` via `navController.navigate(PrismTaskRoute
   .BatchPreview.createRoute(commandText))`; add a chip label
   `"Preview Batch"` (or echo a short fragment of the command for
   context, capped to ~30 chars).
6. Tests:
   - `backend/tests/test_ai_chat.py` — assert `batch_command` survives
     `ChatActionPayload` validation; assert an unknown type is still
     dropped.
   - `app/src/test/.../chat/ChatViewModelTest` — assert tapping a
     `batch_command` action emits the new nav event with the command
     text intact.

---

## Item 3 — Inline `complete_batch` / `archive_batch` chat actions (DEFERRED)

**Findings.**
Mirroring `reschedule_batch` for `complete_batch` / `archive_batch` would
let the user finish a batch adjustment without leaving the chat surface
(snackbar undo, no nav). The handler shape is well-trodden — see
`handleRescheduleBatch` at `ChatViewModel.kt:430`.

**Risk classification.** YELLOW — nice-to-have but duplicates a path
Item 2 already covers via BatchPreviewScreen. Adding it without the
preview short-circuits the safety review the user gets on every other
batch command, so the UX cost (an "are you sure you want to complete
all 23 of these?" dialog) is non-trivial.

**Recommendation.** DEFER. Re-evaluate after Item 2 ships and we have
real usage data on whether users want inline-vs-preview for high-confidence
batch verbs.

---

## Item 4 — Project context in chat prompt (DEFERRED)

**Findings.**
Today the chat user-block only carries `task_context` (single-task
snapshot). It does not carry a project list, so the chat AI can only
reference projects by name from the user's message. That's fine for
Item 2 (`batch_command` just echoes the user's words; resolution to
project IDs happens server-side inside `parse_batch_command`, which
*does* receive the project list via the QuickAdd→`BatchUserContextProvider`
path… but that path runs on the Android client, not inside chat).

If we want chat to *directly* emit project-id-bearing actions (e.g.
`archive_project`, `complete_project`) as inline chips, we'd need to
plumb a projects array onto `ChatRequest` and into the system prompt.

**Risk classification.** YELLOW — only matters if Item 3 expands to
project-level inline actions. Item 2's bridge approach sidesteps it.

**Recommendation.** DEFER. Revisit if Item 3 ever lands.

---

## Item 5 — System prompt drift (YELLOW)

**Findings.**
`_CHAT_SYSTEM_PROMPT` (`backend/app/services/ai_productivity.py:1161`)
explicitly enumerates allowed action shapes. Adding `batch_command`
without updating the prompt would mean the model rarely (or never)
emits the new type — the schema would accept it but Haiku wouldn't know
to produce it. Item 2 already includes the prompt update; flagging
here so it doesn't get dropped during implementation.

**Risk classification.** YELLOW — documentation-shaped risk, easy to
miss in PR review since the schema validates regardless.

**Recommendation.** Bundled into Item 2's PR.

---

## Ranked improvement table

| # | Item                                  | Wall-clock saving      | Cost      | Ratio | Verdict   |
|---|---------------------------------------|------------------------|-----------|-------|-----------|
| 1 | Chat → BatchPreview bridge (Item 2+5) | Unlocks the feature    | ~1 PR     | high  | PROCEED   |
| 2 | Inline complete_batch / archive_batch | Save one nav per batch | ~1 PR     | low   | DEFER     |
| 3 | Project context in chat prompt        | Enables Item 2 follow-on | ~1 PR   | low   | DEFER     |

## Anti-patterns flagged (not fixed here)

- The chat action grammar is enforced in three places — backend regex,
  backend `ChatActionPayload`, Android `when (action.type)` — and they
  drift independently. Adding `batch_command` requires touching all
  three. A future tidy-up could centralize the grammar (e.g. emit it
  from a shared schema). Out of scope for this audit.
- `_CHAT_SYSTEM_PROMPT` is a multi-hundred-line raw string. Each new
  action makes it longer; eventually that hurts both readability and
  Haiku token count. Worth revisiting if action count grows past ~10.

---

## Phase 2 plan

One PR (single coherent scope per the audit-first fan-out rule):

- Branch: `feat/ai-chat-batch-adjustments`
- Touch: backend schema + system prompt + Android API model + ChatViewModel
  + ChatScreen + minimal tests on both sides.
- CI green required.
