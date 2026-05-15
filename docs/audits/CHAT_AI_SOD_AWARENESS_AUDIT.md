# AI Coach / Executive Assistant — Start-of-Day awareness

**Trigger:** Operator report that "the executive assistant AI doesn't know
about the SoD principle." Verified: every other date surface in PrismTask
(Today screen, habits, widgets, NLP date parsing, Pomodoro stats)
resolves "today" through `util.DayBoundary` + the user-configurable
Start-of-Day (`day_start_hour` / `day_start_minute`), but the AI Coach
chat anchored on `datetime.now(timezone.utc).date()` server-side and on
`Calendar.getInstance()` client-side. A user with SoD = 4 AM messaging
the coach at 2 AM landed in the next calendar day, contradicting every
other surface in the app.

## Scope (single PR — code already shipped alongside this doc)

1. **Wire schema** — `ChatRequest` gains an optional `user_context`
   block carrying `today` / `tomorrow` / `day_start_hour` /
   `day_start_minute` / `timezone`. Mirrors the
   `BatchUserContext` pattern already used for `/batch-parse`; backward
   compatible (legacy clients omit it).
2. **Backend prompt** — `_format_chat_system_prompt` renders the
   anchor into a dedicated "User's logical day" block and instructs
   the model to interpret today/tomorrow/yesterday relative to it. The
   payload is also echoed into the user-message JSON so the dates are
   in plain view alongside `user_message`.
3. **Backend bundle** — `load_user_context_bundle`'s `today` argument
   (which drives the Today/Habits/Leisure/Medication rollup in the
   system prompt) now derives from `_resolve_logical_today(...)` rather
   than UTC.
4. **Android repository** — `ChatRepository.sendMessage` /
   `streamMessage` build the payload from `TaskBehaviorPreferences`
   (the SoD hour/minute store) + `DayBoundary.currentLocalDate(...)`.
5. **Android rollover** — `ChatRepository.resetIfNewDay` /
   `clearConversation` / `generateConversationId` migrate off
   `LocalDate.now()` so the conversation thread flips at the user's
   SoD, not at calendar midnight. (`conversationDate` seed at
   construction stays best-effort; the first send/stream call
   re-anchors it.)
6. **Android action resolver** — `ChatViewModel.resolveDate` honors SoD
   when it turns AI-emitted `to: "today" | "tomorrow"` strings into
   due-date millis. Previously a 2 AM tap on a "Reschedule to today"
   chip with SoD = 4 AM landed on the wrong calendar day.

## What deliberately stayed out

- **`load_user_context_bundle` internals.** Sub-loaders that bucket
  tasks/habits/leisure by date now receive the SoD-anchored `today`
  argument and behave correctly. The way each sub-loader compares
  against `date` columns is unchanged — `today_iso` was already the
  contract.
- **Other AI surfaces.** Daily Briefing, Weekly Plan, Time Block,
  Weekly Review, Pomodoro coaching all accept `date` arguments today.
  They are not currently wrong (the client picks the date), but they
  *could* be tightened to forward the same SoD anchor for symmetry. Not
  part of this PR.
- **`_BATCH_PARSE_SYSTEM_PROMPT` is already SoD-aware** via the
  `BatchUserContext.today` field. No change needed.
- **Conversation history rendering** still uses raw `created_at`
  timestamps; "today's" filtering of older chats is a UI-side cosmetic
  and not part of the SoD-equality contract.

## Verification

### Backend pytest

New class `TestChatSodAnchor` in `backend/tests/test_ai_chat.py`:

- `test_user_context_renders_into_system_prompt` — full prompt-render
  smoke test with every SoD field set.
- `test_user_context_echoed_into_user_payload` — the dates also appear
  on the user-message JSON, so the model sees them next to
  `user_message`.
- `test_missing_user_context_keeps_legacy_prompt_shape` — older
  clients still get a clean prompt with no garbled anchor block.
- `test_router_resolves_logical_today_from_user_context` — happy path.
- `test_router_falls_back_to_utc_when_no_user_context` — back-compat
  with the previous behavior.
- `test_router_ignores_malformed_today_string` — defensive: a junk
  payload doesn't 500, falls back to UTC.

Existing 55-test chat + persistence + stream coverage still green
(`pytest tests/test_ai_chat.py tests/test_ai_chat_stream.py
tests/test_chat_persistence.py` — 61 passed).

### Android JVM

- `ChatRepositoryPersistenceTest` + `ChatRepositoryTest` updated to
  pass the new `TaskBehaviorPreferences` constructor argument via a
  helper fake. The fake returns `StartOfDay()` (midnight defaults) so
  legacy-behavior expectations hold.
- `ChatViewModelActionTest` requires no change — existing
  `coVerify` of `clearConversation` still resolves now that the
  function is `suspend`.

## Risk

- **Stream + single-shot both touch the new path** — same helper, so
  the contract can't drift.
- **The Android `_conversationId` seed at construction** uses
  `LocalDate.now()`. There is a small window between class
  instantiation and the first send/stream call where, if SoD = 4 AM
  and the device crosses calendar midnight at 0 AM, the conversation
  ID briefly carries the wrong date. `resetIfNewDay()` on the first
  send/stream call corrects it before any data is persisted under the
  misnamed key. Accepted as a vanishingly small window in exchange
  for a non-suspending field initializer.
- **`load_user_context_bundle.today` is now user-controlled.** The
  helper already trusted the caller-supplied date (the prior
  `datetime.now(timezone.utc).date()` was also caller-supplied). No
  new injection surface — `_resolve_logical_today` validates ISO
  format and clamps to UTC on malformed input.

## Phase 3 — bundle summary

One PR, three files of behaviour change + four files of test/audit:

- `backend/app/schemas/ai.py` — `ChatUserContext` schema + plumbed
  `ChatRequest.user_context`.
- `backend/app/routers/ai/chat.py` — `_resolve_logical_today` +
  forwarded `user_context` into the service call.
- `backend/app/routers/ai/chat_stream.py` — same plumbing for the SSE
  variant.
- `backend/app/services/ai_productivity.py` — `_format_user_context_block`,
  signature additions on `_build_chat_messages_array` /
  `_format_chat_system_prompt` / `generate_chat_response` /
  `generate_chat_response_stream`.
- `app/src/main/java/.../data/remote/api/ApiModels.kt` —
  `ChatUserContext` data class + `ChatRequest.userContext`.
- `app/src/main/java/.../data/repository/ChatRepository.kt` — payload
  builder + SoD-aware rollover.
- `app/src/main/java/.../ui/screens/chat/ChatViewModel.kt` —
  `resolveDate` honors SoD; `clearConversation` becomes coroutine
  launch.
- `backend/tests/test_ai_chat.py` + the two Android repository test
  files updated for the new constructor signature.

## Phase 4 — Claude Chat handoff

> Title: AI Coach SoD-awareness — follow-up surfaces
>
> Scope of this thread: PR #_TBD_ taught the conversational AI Coach
> to anchor "today/tomorrow/yesterday" on the user's Start-of-Day
> rather than server UTC. The other AI surfaces (Daily Briefing,
> Weekly Plan, Time Block, Weekly Review, Pomodoro coaching) still
> accept a free-form `date` argument that the client supplies, so
> they aren't wrong — but they don't enforce the contract either.
>
> Ask: spot-check whether each of those endpoints needs the same
> `user_context` block, or whether their existing `date` field already
> carries the SoD-anchored value the client computes. If the latter,
> add a one-paragraph contract note to each schema docstring (mirror
> the `BatchUserContext.today` precedent). If the former, queue a
> follow-up PR per endpoint, audit-first, capped at 500 lines.
