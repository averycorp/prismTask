# Parity Batch 3 — AI Chat / Coach (Phase 1 audit)

**Trigger:** Section D of `ANDROID_WEB_PARITY_AUDIT_2026-05-13.md`. Web has zero `features/chat/` surface; Android shipped chat in v1.5 era, then layered `batch_command` inline action, conversation persistence (D11 E.3), and a Claude-backed Life Category auto-button (#1278+ era).

**Scope:** D.1a Chat screen UI + history wiring; D.1b `batch_command` inline action → BatchPreviewScreen; D.1c Claude-backed Life Category Auto button on `TaskEditor.tsx`. ~1,050 LOC total per parent audit's PROCEED estimates.

## Verified premises (grep first, claims second)

- `web/src/features/chat/` — **does not exist** (verified `ls`). No `ChatScreen.tsx`, no chat store.
- `web/src/api/ai.ts` has **no chat helpers** (grep `chat|Chat` → 0). Existing `aiApi` object covers eisenhower / pomodoro / extract / briefing / planner only.
- Backend endpoints — **present and ready**:
  - `POST /api/v1/ai/chat` (`backend/app/routers/ai/chat.py:43`).
  - `GET /api/v1/ai/chat/history` (`chat.py:217`); takes optional `conversation_id` + `before` cursor, returns `ChatMessageRecord[]` + `next_before`. **No** `/conversations` list endpoint — clients group client-side from history rows.
  - Schemas at `backend/app/schemas/ai.py:714-848`: `ChatActionPayload`, `ChatRequest`, `ChatResponse` (with `user_message_id` / `assistant_message_id`), `ChatMessageRecord`, `ChatHistoryResponse`.
  - Streaming variant at `backend/app/routers/ai/chat_stream.py` (SSE).
  - `POST /api/v1/ai/life-category/classify_text` exists (`eisenhower.py:187`); schemas at `ai.py:70-86`.
- Android client surface — read end-to-end:
  - `ChatScreen.kt` (696 LOC), `ChatViewModel.kt` (733 LOC), `ChatRepository.kt` (318 LOC).
  - Conversation ID minted client-side: `chat_{YYYY-MM-DD}_{UUID8}` (`ChatRepository.kt:278-281`). New ID on day rollover or Clear-Chat. **Daily rollover is a UI filter** — older conversations stay in storage but the active stream filters to current `conversationId`.
  - `pullHistory()` reconciles cross-device by calling `/chat/history` on init (`ChatViewModel.kt:200-202`). Errors swallowed.
  - First-run disclosure flag is `KEY_AI_CHAT_DISCLOSURE_SHOWN_V3` (`UserPreferencesDataStore`); V3 supersedes V2 to reflect retention shape change (D11 E.3).
  - Rolling history forwarded to backend on each turn: last `maxHistoryPairs = 6` user+assistant pairs (`ChatRepository.kt:114-117`). Backend caps at 12.
- `BatchPreviewScreen.tsx` on web — exists (`web/src/features/batch/BatchPreviewScreen.tsx:57`), routed at `/batch/preview` (`web/src/routes/index.tsx:124`). Triggered by `useBatchStore.setPendingCommand(text)` then `navigate('/batch/preview')` (existing pattern in `NLPInput.tsx:34,162`). **Reusable as-is.**
- TaskEditor Organize tab — `web/src/features/tasks/TaskEditor.tsx:991-1015` renders the Life Category `<select>` with `LIFE_CATEGORY_OPTIONS`. No Auto button yet. The placeholder text already says "Leave as Uncategorized to let Android auto-classify" — ironic; this PR makes web do the classify too.

## Android chat sub-feature inventory (what web parity actually needs)

From reading `ChatScreen.kt` + `ChatViewModel.kt`:

Required (must ship in D.1a):
- Message list with user/assistant bubble alignment + action chips on assistant turns.
- Composer with Send button + Enter-to-send.
- Welcome card with 4 starter prompts when message list is empty.
- First-run disclosure dialog (V3 retention copy); flag persisted in localStorage / settings store.
- `/chat` + `/chat/history` REST wiring. Web does **not** mirror to a local DB — REST-fetch on mount + in-memory store mirrors the Android `pullHistory()` pattern but without the Room cache.
- Action chip dispatch for the same 8 action types Android handles: `complete`, `reschedule`, `reschedule_batch`, `breakdown`, `archive`, `start_timer`, `create_task`, `batch_command`. Reuse existing taskStore mutations on web for the destructive ops where possible.
- ProFeature gate (AI_CHAT) — settings store already has `aiFeaturesEnabled`.
- Disclosure copy is V3 (mention persistence + cross-device + delete-to-forget).
- Clear-Conversation action with confirm dialog (C.3 audit fix) + "Don't ask again" persisted pref.
- Snackbar (toast) for action outcomes + Undo callback on destructive ops.

Deferred to follow-up PRs (acceptable for batch — these are polish):
- **SSE streaming** (Android `ChatStreamClient` + `streamMessage` Flow). REST single-shot `POST /chat` works too and ships the full reply + actions in one response — same backend, just no token-by-token render. PR-5 if time permits.
- **Conversation switcher / history sidebar** — Android UI doesn't expose one either; it relies on day rollover. For web we can leave just the current conversation visible and treat older ones as REST-fetchable history. PR-5.
- **`task_context_id` / `task_context` plumbing** — chat-from-a-task entry point. Android opens chat from task editor with a snapshot. Web has no current entry point besides the sidebar; can land in PR-5 once a "Coach this task" button is added to TaskEditor.
- **`user_preferences` (`remember_preference` tool) sync from `/chat` response** — AI memory feature; mirror to settings store. PR-5.
- **D12 server-assigned IDs for dedup** — moot on web because we don't have a local PK store; REPLACE-on-PK is a Room-specific concern.

### Disclosure flag mapping
Android: `aiChatDisclosureShownV3Flow` in `UserPreferencesDataStore`.
Web equivalent: add `chatDisclosureShownV3: boolean` (default `false`) to `settingsStore.ts` localStorage-only side (no Firestore mirror needed for a per-device first-run gate; matches Android's local-only `DataStore` for V3).

### Conversation ID
Web mints `chat_${ISO_DATE}_${UUID8}` on first send of the day, same as Android (`ChatRepository.kt:278-281`). Day rollover is computed against the user's `startOfDayHour` (already synced cross-device via PR #1340).

## PR plan — Phase 2 fan-out

**PR-1 — `web/src/api/ai/chat.ts` REST helpers.** `aiChat()`, `aiChatHistory()` plus type module `web/src/types/chat.ts` mirroring `ChatActionPayload` / `ChatRequest` / `ChatResponse` / `ChatMessageRecord` / `ChatHistoryResponse`. Unit test like `eisenhowerClassifyText.test.ts`. ~180 LOC. **No Firestore mirror** — chat is server-authoritative per CLAUDE.md "Chat Persistence (D11 E.3)" and Android only uses Room as a cache (no `users/{uid}/chat_messages` collection on Android either, verified by grep).

**PR-2 — `web/src/features/chat/ChatScreen.tsx` core.** Zustand store `web/src/stores/chatStore.ts` (messages array, conversationId, isLoading, disclosure flag), screen component, message bubble + action chip + composer + welcome card + clear-confirm dialog. Route `/chat` + sidebar entry. Reuses settings store for `aiFeaturesEnabled` gate. Action-chip dispatcher for 7 of 8 action types (defers `batch_command` to PR-3). ~700 LOC. Tests: chatStore reducer paths, screen smoke render.

**PR-3 — batch_command inline action.** Add the 8th case to the action-chip dispatcher in `ChatScreen.tsx`. On click: `setPendingCommand(action.commandText)` + `navigate('/batch/preview')`. ~40 LOC + 1 test. (Estimate was 150 LOC; the heavy lifting is already done by the existing batch store.)

**PR-4 — Life Category Auto button.** `aiApi.lifeCategoryClassifyText(...)` helper + new type module, then on the Organize tab next to the `<select>`, render an Auto button that fires the helper, awaits, and writes the result back through `handleLifeCategoryChange(...)`. Failure-soft (toast on error, no overwrite). ~140 LOC + tests.

**PR-5 — IF TIME PERMITS (otherwise deferred to Batch 6).** Conversation history sidebar / day-list, SSE streaming, task_context plumbing, AI memory mirror. ~500 LOC, several distinct features bundled — better as its own batch.

## Open questions (resolved in-doc to unblock execution)

- **Firestore mirror? No.** CLAUDE.md "Chat Persistence (D11 E.3)" is explicit: "No Firestore — chat follows the BackendSyncService precedent." Android writes via `/chat` and mirrors to Room only; web mirrors to in-memory zustand only. Cross-device sync is via REST `/chat/history` on screen mount.
- **Conversation list endpoint?** None. Both clients group history rows client-side by `conversation_id`. Single-conversation focus on day = today is fine for D.1a; multi-day picker is PR-5 material.
- **Disclosure flag location?** Local-only on both platforms. Add to `settingsStore.ts` next to `aiFeaturesEnabled`; don't sync to Firestore.
- **Action chip label parity** — copy verbatim from `ChatScreen.kt:522-543`. Title Capitalization matches CLAUDE.md convention already.

## Risks / non-goals

- **No SSE streaming in D.1a.** The single-shot `/chat` endpoint returns the full assistant reply + actions in one response. UX is "typing dot until reply arrives" instead of token-by-token render. Acceptable for parity-v1 — the chat product still works, just less flashy. Streaming wiring lands in PR-5 / Batch 6.
- **No chat-from-task entry point.** Web TaskEditor will not (yet) have a "Coach this task" button. Sidebar entry is the only entry point in PR-2. Adds in PR-5.
- **`start_timer` action.** Web has `/pomodoro` but no "preset minutes" route param. Implementation: on `start_timer` chip click, navigate to `/pomodoro` and let user manually start. Mark a `pendingTimerMinutes` in `uiStore` if needed — defer wiring to follow-up if it adds friction. Snackbar text still surfaces the minutes.
- **`create_task` recurrence.** Android re-runs the local NLP parser on the AI-emitted `title` to catch recurrence wording. Web `nlpBatch.ts` exists; reuse if convenient, otherwise pass title verbatim to taskStore and skip recurrence parse (PR-5 polish).

## Phase 3 + 4

Per CLAUDE.md repo conventions: **append Phase 3 (bundle summary) + emit Phase 4 (Claude Chat handoff block) as soon as PR-1 through PR-4 are opened** — do not wait for CI green or merge.
