# D12 Chat Open-Items Bundle Audit

**Branch:** `claude/d12-chat-bundle-audit-6VL00`
**Bundle scope:** the two D12 open items still at `done: 0` after PR #1196's
stale-clone-artifact reframe.

- **Item A** — chat native Anthropic `tool_use` migration (D12 §B.1). Phase F
  GREEN-GO impact: NEUTRAL.
- **Item B** — chat persistence ↔ streaming integration verification, 4
  concrete gates (a)–(d). Phase F GREEN-GO impact: HIGH (regression on
  streaming-path persistence would silently lose post-PR-#1196 chats).

Single bundle PR, single audit doc. Audit-first; Phase 3 + Phase 4 fire
pre-merge per CLAUDE.md repo conventions.

---

## Phase 0 — Base verification (run + outcomes)

### Git state

```text
HEAD branch: claude/d12-chat-bundle-audit-6VL00
working tree: clean
recent main: 5626426 fix(ci): add blank line ... (#1212)
```

### PR-presence in `git log --all` after `git fetch origin`

| PR     | Subject                                                 | Present? |
|--------|---------------------------------------------------------|----------|
| #1164  | `fix(chat): chat quality audit + Phase 2 implementation`| **NO**   |
| #1165  | (D12 follow-on)                                         | **NO**   |
| #1166  | (D12 follow-on)                                         | **NO**   |
| #1167  | (D12 follow-on)                                         | **NO**   |
| #1168  | `fix(chat): F8 follow-on bundle — B.3/B.4/C.3/C.4/C.5/D.3` | yes |
| #1171  | `docs(privacy): F8 chat privacy doc update + V2 disclosure re-fire` | yes |
| #1182  | `fix(chat): close sendMessage dedup race by flipping _isTyping pre-launch` | yes |
| #1192  | `docs(audit): F7 D.1 + F8 D.2 chat streaming + cancel bundle audit` | yes |
| #1196  | `feat(chat): D11 E.3 chat conversation persistence (Postgres + Room)` | yes |
| #1197  | `Claude/chat streaming cancel 6 dwq4` (impl behind #1192 audit)     | yes |
| #1198  | `fix(chat): unbreak lint+detekt+hilt after #1196 chat persistence` | yes |

**STOP-A** does NOT fire — the audit's load-bearing dependencies (#1192's
streaming + #1196's persistence) are present. PRs #1164–#1167 missing is a
Phase 0 drift incident, not a halt condition.

**Stale-clone-artifact pattern (3rd data-point)**: prior occurrences include
PR #1119 prior + PR #1196 audit prior. Surfaced via AskUserQuestion at Phase 0;
operator chose "note in Phase 4 memory candidates, proceed". Pattern reaches
the wait-for-third memorization threshold; flagged in Phase 4 §Memory
candidates.

### STOP-RT verification — Item A re-trigger criteria

D12 §B.1 deferred Item A until either arm of `action-types > 10 OR any input
schema > 5 fields` fires.

**Action-type enumeration** (`backend/app/schemas/ai.py:529-532`,
`_CHAT_ACTION_TYPE_PATTERN`):

| #  | Action type        | Effective input fields                                      | Field count |
|----|--------------------|-------------------------------------------------------------|-------------|
| 1  | `complete`         | `task_id`                                                   | 1           |
| 2  | `reschedule`       | `task_id`, `to`                                             | 2           |
| 3  | `reschedule_batch` | `task_ids`, `to`                                            | 2           |
| 4  | `breakdown`        | `task_id`, `subtasks`                                       | 2           |
| 5  | `archive`          | `task_id`                                                   | 1           |
| 6  | `start_timer`      | `minutes`                                                   | 1           |
| 7  | `create_task`      | `title`, `due`, `priority`, `description`, `tags`, `project`| **6**       |
| 8  | `batch_command`    | `command_text`                                              | 1           |

- Action-types count: **8** — does NOT trigger first arm (8 < 10).
- Largest per-action effective input: **6 fields** (`create_task` —
  `title`, `due`, `priority`, `description`, `tags`, `project`). PLUS the
  discriminator `type` field, which under either reading pushes per-action
  count to 7. Total flat-union schema field count: 13.
- Either reading triggers the second arm (> 5 fields): Trigger **fires** on
  schema-fields arm.

Operator decision at Phase 0 (AskUserQuestion): proceed with Item A as
preemptive infra. Audit Item A in full below.

### Premise files exist

```text
app/src/main/.../ui/screens/chat/ChatViewModel.kt          (669 lines)
app/src/main/.../ui/screens/chat/ChatScreen.kt
app/src/main/.../data/repository/ChatRepository.kt         (290 lines)
app/src/main/.../data/remote/sse/ChatStreamClient.kt       (158 lines)
app/src/main/.../data/local/dao/ChatMessageDao.kt           (44 lines)
app/src/main/.../data/local/entity/ChatMessageEntity.kt     (49 lines)
backend/app/routers/ai.py                                  (chat endpoints 908-1207)
backend/app/services/ai_productivity.py                    (chat service 1161-1473)
backend/app/schemas/ai.py                                  (chat schemas 529-655)
```

STOP-A directory check: PASS.

---

## § Item A — Chat native Anthropic `tool_use` migration (B.1)

Phase 0 outcome: STOP-RT trigger fires (schema-fields arm). Operator approved
proceeding as preemptive infra. Auditing in full.

### A.1 Current protocol inventory

**Generation site** (system prompt + Anthropic call):

| Symbol                              | File:line                                    |
|-------------------------------------|----------------------------------------------|
| `_CHAT_SYSTEM_PROMPT`               | `backend/app/services/ai_productivity.py:1161-1190` |
| `_build_chat_messages_array`        | `:1193-1225` |
| `generate_chat_response` (single)   | `:1330-1391` |
| `generate_chat_response_stream`     | `:1394-1473` |
| `_finalize_chat_payload`            | `:1228-1254` |
| `_extract_partial_message_field`    | `:1260-1327` (regex hack for streaming) |
| `_parse_ai_json`                    | `:51-59` |

**Parsing / validation site:**

| Symbol                              | File:line                                    |
|-------------------------------------|----------------------------------------------|
| `ChatActionPayload` Pydantic model  | `backend/app/schemas/ai.py:535-559` |
| Single-shot validate-and-drop loop  | `backend/app/routers/ai.py:954-966` |
| Streaming validate-and-drop loop    | `backend/app/routers/ai.py:1087-1101` |
| `ChatResponse` schema               | `backend/app/schemas/ai.py:620-624` |

**Current shape (verbatim from system prompt):**

> Hard rules: 1. Output STRICT JSON only. No markdown fences, no prose
> outside JSON. 2. Top-level shape: `{"message": "<your reply>", "actions":
> [<0..N action objects>]}`. 3. `actions` may be an empty list — prefer that
> over emitting weak suggestions.

**JSON delimitation:** `_parse_ai_json` strips ` ```...``` ` markdown fences
defensively, then `json.loads(content.strip())`. The model is instructed to
emit no fences but the parser tolerates them.

**Streaming hack:** `_extract_partial_message_field` finds the `"message":"`
opener via regex, then walks the partial JSON string honoring `\"`, `\\`,
`\n`, `\t`, `\r`, `\b`, `\f`, `\/`, and `\uXXXX` escapes — emitting the
user-visible reply text incrementally while the full envelope is still
arriving. Trailing partial escapes (`\` mid-token, `\u00` mid-codepoint) are
dropped from the current emission and re-emitted on the next delta.

**Failure modes today:**

| Failure                            | Behavior                                    |
|------------------------------------|---------------------------------------------|
| Malformed JSON (single-shot)       | `_finalize_chat_payload` raises `ValueError`; one retry; on second fail → 500 with `"AI returned an invalid response"` |
| Malformed JSON (streaming)         | `error` SSE event emitted; no retry (would double Anthropic billing); stream closes |
| Mid-text JSON / multiple blocks    | `json.loads` raises; treated as malformed (above) |
| Anthropic transport error          | Single-shot: retry once then re-raise; streaming: `error` SSE event |
| Action `type` not in pattern       | Pydantic `ValidationError`; that single action dropped silently with `logger.info` |
| Action with required field missing | Pydantic `ValidationError`; same as above |

### A.2 Anthropic `tool_use` target shape

Target shape per Anthropic docs (https://docs.claude.com/en/docs/build-with-claude/tool-use):

```python
client.messages.create(
    model=model,
    max_tokens=1024,
    system=_CHAT_SYSTEM_PROMPT_TOOL_USE,   # rewritten — prose only, no JSON rules
    tools=[                                 # NEW: native tool definitions
        {
            "name": "create_task",
            "description": "Create a new task on the user's behalf...",
            "input_schema": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "..."},
                    "due": {"type": "string", "enum": ["today", "tomorrow", "next_week"], ...},
                    ...
                },
                "required": ["title"],
            },
        },
        # ... 7 more tool defs, one per action type
    ],
    messages=anthropic_messages,
)
```

Response with `content` blocks:

```python
ai_message.content = [
    TextBlock(type="text", text="Sure, I can help with that. Want me to..."),
    ToolUseBlock(type="tool_use", id="toolu_01ABC", name="create_task",
                 input={"title": "Buy milk", "due": "today"}),
    ToolUseBlock(type="tool_use", id="toolu_01XYZ", name="start_timer",
                 input={"minutes": 25}),
]
```

The handler iterates `content` blocks: text blocks concatenate into `message`;
`tool_use` blocks become entries in `actions`.

**Streaming considerations:**

Anthropic streaming events for tool use:

```text
content_block_start  (block_index=0, type=text)
content_block_delta  (block_index=0, delta="Sure, I can…")
…
content_block_stop   (block_index=0)
content_block_start  (block_index=1, type=tool_use, name="create_task", id=...)
content_block_delta  (block_index=1, partial_json="{\"tit")
content_block_delta  (block_index=1, partial_json="le\":\"Buy")
…
content_block_stop   (block_index=1)
…
message_stop
```

The Android-facing SSE protocol stays the same (Token / Done / Error). The
backend change is internal — instead of regex-extracting the `"message"`
field from the JSON envelope, the backend now reads the text-block deltas
directly and forwards them as `Token` events. `tool_use` block deltas are
buffered until `content_block_stop` then accumulated into the `Done` event's
`actions` list.

This means **`_extract_partial_message_field` can be deleted** entirely
(brittle ~70-line regex hack → 0 lines).

### A.3 Action-type enumeration with target tool defs

| Action             | Required fields | Optional fields                                    |
|--------------------|-----------------|----------------------------------------------------|
| `complete`         | `task_id`       | —                                                  |
| `reschedule`       | `task_id`, `to` | —                                                  |
| `reschedule_batch` | `task_ids`, `to`| —                                                  |
| `breakdown`        | `task_id`, `subtasks` | —                                            |
| `archive`          | `task_id`       | —                                                  |
| `start_timer`      | `minutes`       | —                                                  |
| `create_task`      | `title`         | `due`, `priority`, `description`, `tags`, `project`|
| `batch_command`    | `command_text`  | —                                                  |

All 8 action types map 1:1 to Anthropic tool defs. The
`ChatActionPayload` flat-union Pydantic model stays as the **wire
contract on the response side** (so the Android client's
`ChatActionResponse` deserialization is unaffected) — the migration
changes how the **backend gets** action data from Claude (text-JSON →
`tool_use` blocks), not how it ships it to the client.

### A.4 Cross-item dependency analysis (STOP-A3)

Question: does Item A's `tool_use` migration conflict with Item B's gates?

- **Gate (a) — streaming persistence**: persistence inserts into
  `chat_messages` table from the parsed `done` event payload. Independent of
  whether the backend got the payload from JSON-in-text or `tool_use` blocks.
  **No conflict.**
- **Gate (b) — pullHistory dedup**: Gate (b) is about ID-mismatch between
  client UUIDs and server UUIDs. Independent of action-protocol shape.
  **No conflict.**
- **Gate (c) — V3 disclosure**: V3 disclosure is about retention copy, not
  protocol shape. **No conflict.**
- **Gate (d) — D.3 dedup race**: turnState transitions and Flow observation.
  Independent of action-protocol. **No conflict.**

The streaming-path Token event is the one place where the migration changes
something visible to clients — but only in *fewer* surfacing concerns: text
blocks stream cleanly without the brittle regex extraction. Existing Item B
fixes (Gate a + Gate b) operate on the `done` event *payload*, not the token
mechanics, so they are stable across the migration.

**STOP-A3 verdict: NOT FIRED.** Item A and Item B can be implemented
independently within the bundle. Recommended ordering: **Item B first**
(observation invariants), Item A second (protocol migration on top of stable
invariants). Same as the prompt's default ordering.

### A.5 Migration strategy verdict

| Verdict | Rationale                                                    |
|---------|--------------------------------------------------------------|
| **GREEN** | Clean cutover. Wire contract on response side (`ChatResponse` + `ChatActionPayload` + SSE Token/Done/Error) is unchanged. Only backend-internal generation + parsing changes. Android client requires zero changes (it has always read structured `actions` from the response, not parsed JSON-in-text). |

No backward-compat window required — STOP-A4 NOT fired. (Old/new clients see
the same `ChatResponse` schema either way; the JSON-in-text vs `tool_use`
distinction is invisible past the backend service layer.)

### A.6 Estimated LOC for Item A

| Surface                               | Add | Delete | Net |
|---------------------------------------|-----|--------|-----|
| `_CHAT_SYSTEM_PROMPT` rewrite (no JSON rules; tool descriptions move to tool defs) | ~25 | ~30 | -5 |
| `_CHAT_TOOL_DEFINITIONS` (new constant; 8 tool defs)                              | ~150 | 0 | +150 |
| `generate_chat_response` (read content blocks, extract text+tool_use)             | ~30 | ~10 | +20 |
| `generate_chat_response_stream` (handle `content_block_*` events instead of text deltas) | ~70 | ~30 | +40 |
| `_extract_partial_message_field` deletion                                          | 0   | ~70 | -70 |
| `_finalize_chat_payload` simplification (no JSON parse needed; data already structured) | ~10 | ~25 | -15 |
| Tests: `test_ai_chat.py`, `test_ai_chat_stream.py` migration                       | ~80 | ~40 | +40 |
| Audit doc cross-references                                                          | ~5  | 0    | +5  |
| **Total**                                                                           | **~370** | **~205** | **+165** |

Net production code: ~+125 LOC; tests: ~+40 LOC.

This is below the original 200-400 LOC estimate — STOP-A5 (>600) NOT fired.

### A.7 Item A STOP summary

| STOP    | Outcome   | Note                                          |
|---------|-----------|-----------------------------------------------|
| STOP-A1 | NOT fired | GREEN verdict; clean cutover possible.         |
| STOP-A2 | NOT fired | Action-type count 8 (not at threshold).        |
| STOP-A3 | NOT fired | No cross-item conflict; items independent.     |
| STOP-A4 | NOT fired | No backward-compat window required.            |
| STOP-A5 | NOT fired | Total LOC ~+165 << 600 cap.                    |

---

## § Item B — Chat persistence ↔ streaming integration verification

This is verification + remediation, per the audit prompt's framing. Each of
the 4 gates verdicts as PASS / FAIL / AMBIGUOUS, with fix shape if FAIL.

### Gate (a) — `/chat/stream` done-event handler writes BOTH turns to `chat_messages`

**Static check:**

`/chat/stream` lives at `backend/app/routers/ai.py:1036-1122`. The
done-event branch is at lines 1082-1102:

```python
for event in stream_iter:
    if event.get("type") == "done":
        # Validate each AI-proposed action against ChatActionPayload...
        validated: list[dict] = []
        for raw in event.get("actions", []) or []:
            ...
        event["actions"] = validated
        event["conversation_id"] = data.conversation_id
    yield _format_sse_event(event)
```

There is **no** `db.add(...)`, no `await db.commit()`, no `ChatMessageModel`
construction. The `db: AsyncSession = Depends(get_db)` parameter on line
1041 is captured but never used inside `event_generator`.

Compare to the single-shot `/chat` handler at `:974-1015`, which constructs
`user_row` + `assistant_row`, calls `db.add(...)` twice, and `await
db.commit()`.

**Verdict: FAIL.** Streaming chats are not persisted server-side.
Cross-device sync via `GET /chat/history` returns ZERO rows for any
conversation that used the streaming endpoint exclusively.

**Mechanical evidence chain:**

1. User streams chat on Phone A. SSE events flow; client's local Room is
   populated via `streamMessage` → `commitAssistantTurn` (client UUIDs).
2. User picks up Phone B. `pullHistory` on Phone B calls
   `GET /api/v1/ai/chat/history?conversation_id=...`.
3. `chat_history` SQL query (`:1144-1160`) selects from `chat_messages` where
   `user_id == current_user.id`. **No rows match for streaming-only
   conversations** because nothing was ever inserted server-side.
4. Phone B sees an empty conversation despite Phone A having a populated one.

This is a regression vs the D11 E.3 design intent ("server is source of
truth"; `docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md` § 0.3 reframe).

**Fix shape:**

Convert `event_generator` from a sync generator to an async generator and
add persistence in the done-event branch, mirroring the single-shot
endpoint's shape exactly:

```python
async def event_generator():
    user_msg_id = uuid.uuid4().hex
    assistant_msg_id = uuid.uuid4().hex
    user_persisted = False  # idempotency guard if done fires twice
    try:
        ...
        for event in stream_iter:
            if event.get("type") == "done":
                # validate actions (existing) ...
                # NEW — persist both turns
                if not user_persisted:
                    now = datetime.now(timezone.utc)
                    db.add(ChatMessageModel(
                        id=user_msg_id, user_id=current_user.id, ...,
                        role="user", content=data.message, created_at=now,
                    ))
                    db.add(ChatMessageModel(
                        id=assistant_msg_id, user_id=current_user.id, ...,
                        role="assistant", content=event["message"],
                        actions=validated or None,
                        tokens_input=event["tokens_used"]["input"],
                        tokens_output=event["tokens_used"]["output"],
                        created_at=now + timedelta(microseconds=1),
                    ))
                    await db.commit()
                    user_persisted = True
                event["user_message_id"] = user_msg_id        # for Gate (b)
                event["assistant_message_id"] = assistant_msg_id  # for Gate (b)
                event["conversation_id"] = data.conversation_id
            yield _format_sse_event(event)
    except Exception:
        await db.rollback()
        ...
```

Coupling note: the persisted IDs are also surfaced in the done-event
payload. This is what Gate (b)'s fix consumes — pre-allocating IDs lets us
keep them in lockstep without a second round-trip.

LOC: ~45 (production) + ~30 (test_ai_chat_stream.py persistence assertions).
**STOP-B1 not fired** (< 100 LOC).

---

### Gate (b) — `ChatViewModel`'s `pullHistory()` init path doesn't double-write streaming messages

**Static check:**

The Room `chat_messages` PK is `id: String` (`ChatMessageEntity.kt:31-32`).
DAO conflict strategy is `OnConflictStrategy.REPLACE`
(`ChatMessageDao.kt:12,15`).

The D11 audit doc design (verbatim from
`D11_E3_CHAT_PERSISTENCE_AUDIT.md`):

> Line 221: "Postgres PKs are server-generated UUIDs; Room mirrors with same
> PK on receive. No collision possible across devices because each turn is
> server-authored."
> Line 295: "`sendMessage()`: insert user-turn row pre-API-call (matches
> existing optimistic UI), then on response, upsert assistant-turn row (and
> re-upsert user-turn with any server-side fields like `tokens_used`)."
> Line 307: "Cross-device pull: GET-history `upsertAll()` uses
> `OnConflictStrategy.REPLACE` on PK. Since PKs are server-generated UUIDs,
> REPLACE is idempotent: same row on the server, same row in Room, no
> observable change."

This design is **violated by the implementation**:

| Site                                   | File:line                          | ID source           |
|----------------------------------------|------------------------------------|---------------------|
| `sendMessage` user row                 | `ChatRepository.kt:115-122`        | `UUID.randomUUID()` (client) |
| `sendMessage` assistant row            | `ChatRepository.kt:135-146`        | `UUID.randomUUID()` (client) |
| `streamMessage` user row               | `ChatRepository.kt:170-181`        | `UUID.randomUUID()` (client) |
| `commitAssistantTurn` row              | `ChatRepository.kt:198-216`        | `UUID.randomUUID()` (client) |
| Server `/chat` user row                | `ai.py:980-991`                    | `uuid.uuid4().hex` (server) |
| Server `/chat` assistant row           | `ai.py:993-1005`                   | `uuid.uuid4().hex` (server) |
| Server `/chat/stream` user row         | (Gate (a) fix)                      | `uuid.uuid4().hex` (server) |
| Server `/chat/stream` assistant row    | (Gate (a) fix)                      | `uuid.uuid4().hex` (server) |
| `ChatResponse` schema                  | `ai.py:620-624`                    | **does not return server IDs** |
| Streaming `done` event payload         | `ai.py:1082-1102`                  | **does not return server IDs** |

The mechanical race:

1. Single-shot `sendMessage`: client writes `userRow_C1` + `assistantRow_C2`
   (client UUIDs); server writes `userRow_S1` + `assistantRow_S2` (server
   UUIDs). `ChatResponse` returns no IDs, so client cannot reconcile.
2. Phone closes, reopens. `ChatViewModel.init` calls `chatRepository.pullHistory()`
   (line 194-196).
3. `pullHistory` calls `GET /chat/history`, server returns `userRow_S1` +
   `assistantRow_S2`. `chatMessageDao.upsertAll(rows)` REPLACEs on PK.
4. `userRow_S1.id` (server UUID) does NOT match any existing row's PK
   (client UUIDs). REPLACE inserts as new. Same for `assistantRow_S2`.
5. The conversation now has **4 rows** (`C1, C2, S1, S2`) for **1 turn
   pair** — duplicate render in UI.

The streaming path with Gate (a) fixed has the identical class of bug:
client UUIDs from `streamMessage` + `commitAssistantTurn` won't match
server UUIDs allocated in the Gate (a) fix.

There IS no dedup logic. The audit prompt asked for stable-id dedup keyed
off `cloud_id` (or message uuid generated at first streaming token,
persisted with same id on done-event). Neither exists.

**Verdict: FAIL.**

**Fix shape (cleanest, paired with Gate (a)):**

1. **Backend**:
   - Extend `ChatResponse` with `user_message_id: str` and
     `assistant_message_id: str` (echoing what was just persisted).
   - Streaming `done` event payload: same two fields (already wired in the
     Gate (a) fix above — pre-allocate IDs, surface in done event).
2. **Android api**:
   - `ChatResponse` data class: add `userMessageId: String?` and
     `assistantMessageId: String?` (`@SerializedName("user_message_id")` /
     `"assistant_message_id"`).
   - `ChatStreamEvent.Done`: add same two fields.
   - `ChatStreamClient.parseEvent`: extract from `done` event JSON.
3. **Android repo**:
   - `ChatRepository.sendMessage`: re-upsert userRow + assistantRow with
     server IDs after the API response (overwriting the optimistic
     client-UUID rows via REPLACE-by-PK… wait — that's the problem; we
     can't overwrite if PKs differ). **Solution: don't optimistically
     write at all in single-shot path, OR use a deterministic placeholder
     ID and explicitly delete-then-insert.**

   Cleaner approach: change the optimistic write to a *transient
   placeholder* with a known sentinel ID, delete it after API response,
   then insert with server ID. This keeps optimistic UI and ensures only
   one final row per server row.

   Cleanest approach: drop optimistic local writes from sendMessage and
   streamMessage; rely on the existing `messages: Flow<List<ChatMessage>>`
   stream to render the row once it's persisted+pulled. For streaming, the
   `Streaming(partialText=…)` turn-state already renders incrementally
   without needing a Room row, so dropping the optimistic Room write is
   safe — the Room row appears at `commitAssistantTurn`, which now uses the
   server-assigned ID.

   Going with **cleanest** for the bundle. See Phase 2 §B-impl for the
   exact diff plan.
4. **Tests**: ChatRepositoryTest assertions that local Room rows have IDs
   matching the server response IDs.

LOC: ~70 (production: ~50 backend + Android, tests: ~20).
**STOP-B1 NOT fired** (< 100 LOC).

---

### Gate (c) — V3 disclosure gating fires correctly on streaming endpoint usage paths

**Static check:**

V3 disclosure preference key
(`UserPreferencesDataStore.kt:250-251`):

```kotlin
val KEY_AI_CHAT_DISCLOSURE_SHOWN_V3 = booleanPreferencesKey("ai_chat_disclosure_shown_v3")
```

Default value: false (no `?:` override in `aiChatDisclosureShownV3Flow`
beyond `?: false` at `:557`).

V3 is independent of V2 (separate preference key). When V3 ships, every
existing user — including those who already dismissed V2 — sees the
disclosure on next chat-screen open because their `V3` flag is unset.

`ChatViewModel.init` (line 185-190):

```kotlin
viewModelScope.launch {
    val alreadyShown = userPreferencesDataStore.aiChatDisclosureShownV3Flow.first()
    if (!alreadyShown) {
        _showDisclosure.value = true
    }
}
```

The check is at chat-screen *open*. Both single-shot (`sendMessage`) and
streaming (`startStreamingTurn`) paths require the chat screen to be open;
neither can be invoked without first running `ChatViewModel.init`.

`dismissDisclosure` (line 203-213) writes both V2 and V3, so dismissing on
either path is durable.

**Verdict: PASS.** V3 disclosure fires correctly on streaming endpoint usage
paths because every streaming usage transitively passes through
`ChatViewModel.init`, which checks V3.

The original audit prompt's risk ("V3 only fires on init, missing
streaming-endpoint surface") would only matter if streaming could be
triggered *outside* a freshly-opened chat screen (e.g. from a notification
deep-link that bypasses init). Static analysis confirms no such bypass
exists — `streamMessage` is only called from `ChatViewModel.startStreamingTurn`,
which is only called from `ChatViewModel.sendMessage`, which is only called
from `ChatScreen` UI events.

No fix needed. Mark Gate (c) as paper-closed.

---

### Gate (d) — D.3 dedup guard doesn't conflict with PR #1196's DAO-Flow → stateIn observation

**Static check:**

D.3 dedup guard (`ChatViewModel.kt:225`):

```kotlin
fun sendMessage(text: String) {
    if (text.isBlank()) return
    if (_turnState.value !is ChatTurnState.Idle) return  // D.3 guard
    ...
    _turnState.value = ChatTurnState.Streaming(...)
    _isTyping.value = true
    ...
}
```

Per PR #1182, the guard reads `_turnState` synchronously and the
`Streaming` flip happens BEFORE launching the coroutine, closing the
inbound double-tap race.

`messages` StateFlow observation (`ChatViewModel.kt:59-64`):

```kotlin
val messages: StateFlow<List<ChatMessage>> = chatRepository.messages
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
```

Where `chatRepository.messages` (`ChatRepository.kt:75-79`) is:

```kotlin
val messages: Flow<List<ChatMessage>> =
    _conversationId.flatMapLatest { id ->
        chatMessageDao.observeForConversation(id)
            .map { rows -> rows.map { it.toChatMessage() } }
    }
```

**Race analysis:**

The audit prompt postulated: "If a write happens DURING a turnState
transition, stateIn may emit a stale value that the dedup guard then
rejects as 'still streaming' — silent message loss OR silent duplicate."

This race requires a closed feedback loop: turnState → Flow trigger → guard
re-read. Static analysis confirms **no such loop exists**:

- The dedup guard reads `_turnState` once, at the top of `sendMessage`. It
  does NOT observe the `messages` StateFlow.
- The `messages` StateFlow is observed only by `ChatScreen` (for rendering)
  and never read by `sendMessage` or `cancelInFlight`.
- `_turnState` transitions (`Idle → Streaming` at `:236-239`,
  `Streaming → Idle` at `:300` via `finishTurn`) happen on `viewModelScope`
  (Main dispatcher). All concurrent access is dispatcher-serialized.
- Room writes from `commitAssistantTurn` (`ChatRepository.kt:198-216`) flow
  out through `chatMessageDao.observeForConversation` — pure observation,
  doesn't feed back into turnState.

The two paths (turnState transitions and DAO Flow emissions) are
genuinely independent. The dedup guard is single-point-read; there is no
re-evaluation triggered by Flow emissions.

**Verdict: PASS.** No conflict possible.

**Caveat for Phase 3 verification:** rapid double-send during streaming +
done event should be exercised as a regression check (per
`Phase 3 verification` in the prompt) — the static analysis confirms
correctness, but the manual S25 protocol catches concurrent edge cases the
static analysis can't (e.g. dispatcher-related quirks).

No fix needed. Mark Gate (d) as paper-closed.

---

### Gate summary

| Gate | Verdict | Fix LOC | STOP fired? |
|------|---------|---------|-------------|
| (a) Streaming done persistence | **FAIL** | ~75 (45 prod + 30 test) | None |
| (b) pullHistory dedup           | **FAIL** | ~70 (50 prod + 20 test) | None |
| (c) V3 disclosure gating        | **PASS** | 0       | None        |
| (d) D.3 dedup vs DAO-Flow       | **PASS** | 0       | None        |

| STOP    | Outcome   | Note                                                |
|---------|-----------|-----------------------------------------------------|
| STOP-B1 | NOT fired | Per-gate fixes ≤ 100 LOC each (largest 75).         |
| STOP-B2 | NOT fired | Static analysis decisive on all 4 (no AMBIGUOUS).   |
| STOP-B3 | NOT fired | Not all 4 PASS (a + b are FAIL). Real fix work.     |
| STOP-B4 | NOT fired | No new failure mode found outside (a)–(d).          |

---

## § Bundle-decision

### B-decision.1 — PR shape

**Single bundle PR.** Operator pre-locked. STOP-A3 NOT fired so bundling is
not *required* but it's the operator's preferred shape and the audit data
does NOT argue meaningfully for split.

### B-decision.2 — Implementation order within Phase 2

Default order from the prompt: Item B first, then Item A. Audit data
confirms this is correct because:

1. Gate (a) fix introduces server-side ID allocation in the streaming done
   event. Gate (b) fix consumes those IDs (and the analogous IDs from
   single-shot `/chat`). Gate (a) → Gate (b).
2. Item A's `tool_use` migration changes only how the backend *gets* the
   payload from Claude. It does NOT touch the persistence layer, the SSE
   protocol, or the client wire contract. Item A is orthogonal to Gates
   (a)/(b) and safe to land last.

Final commit ordering:

| # | Commit subject                                             | Surface         |
|---|------------------------------------------------------------|-----------------|
| 1 | `fix(chat): persist streaming done-turn server-side (Gate a)` | backend         |
| 2 | `fix(chat): use server-assigned IDs for chat row dedup (Gate b)` | backend + Android |
| 3 | `feat(chat): migrate to native Anthropic tool_use protocol (B.1)` | backend         |
| 4 | `test(chat): bundle tests + audit doc cross-refs`           | tests + audit doc |

Each commit compiles + lints cleanly. (Repo discipline; intermediate
commits don't need to be *runtime-correct* on their own.)

### B-decision.3 — Cross-item dependencies

**Listed:**

1. Gate (a) fix surfaces `user_message_id` + `assistant_message_id` in the
   streaming done event. Gate (b) fix consumes those.
2. Gate (b) fix mirrors the same fields on `ChatResponse` for the
   single-shot path. Single-shot persistence (lines 974-1015) doesn't
   currently allocate IDs ahead of time — it does so during `db.add`. We
   move ID allocation up to `uuid.uuid4().hex` ahead of `db.add` so they
   can be returned in the response.
3. Item A (`tool_use` migration) is orthogonal to Gates (a)/(b). No
   re-verification of Gates (a)/(b) needed post-Item-A because the
   persistence path operates on the parsed `done` event payload, not on the
   action-protocol shape. The `done` event payload field set
   (`message`, `actions`, `tokens_used`, `conversation_id`,
   `user_message_id`, `assistant_message_id`) is identical pre and
   post-migration.

### B-decision.4 — Total LOC

| Surface                              | Add | Delete | Net |
|--------------------------------------|-----|--------|-----|
| Gate (a) — streaming persistence     | ~50 | ~5     | +45 |
| Gate (b) — server-ID dedup           | ~85 | ~30    | +55 |
| Item A — `tool_use` migration        | ~370| ~205   | +165|
| Audit doc                            | ~870| 0      | +870|
| **Total (excluding audit doc)**       | **~505** | **~240** | **+265** |
| **Total (with audit doc)**            | **~1375**| **~240** | **+1135** |

Production + tests: ~265 LOC net. Comfortably below the 800 LOC bundle
budget. Below the prompt's ~250-600 LOC estimate sum (lower because the
deletion of `_extract_partial_message_field` (-70) offsets a chunk of Item
A's add).

---

## § Phase 2 implementation plan (binding)

### B-impl.1 — Gate (a): streaming server-side persistence

File: `backend/app/routers/ai.py`.

1. Convert `event_generator` from `def` to `async def`.
2. Capture `current_user`, `data`, `db` in closure (already captured).
3. Pre-allocate `user_msg_id = uuid.uuid4().hex` and `assistant_msg_id =
   uuid.uuid4().hex` outside the loop.
4. Add a `persisted = False` guard so a duplicate `done` event (theoretical
   from upstream) doesn't insert twice.
5. Inside the `if event.get("type") == "done":` branch, after action
   validation, before yielding: insert `user_row` + `assistant_row` mirroring
   the single-shot endpoint (`:980-1008`) and `await db.commit()`.
6. Add `event["user_message_id"] = user_msg_id` and
   `event["assistant_message_id"] = assistant_msg_id` to the done payload
   (consumed by Gate (b)).
7. Wrap in `try/except` mirroring single-shot's failure handling: log,
   `await db.rollback()`, but DO NOT swallow the error before the SSE
   error event fires — surface a `parse_error`-like SSE error frame.

Test additions in `backend/tests/test_ai_chat_stream.py`:

- `test_chat_stream_persists_both_turns_on_done`: assert two rows in
  `chat_messages` after a successful streaming turn.
- `test_chat_stream_no_persist_on_error`: error path leaves table empty.
- `test_chat_stream_no_persist_on_cancel`: client-disconnect mid-stream
  (before done) leaves table empty.
- `test_chat_stream_done_event_carries_message_ids`: SSE done payload
  includes `user_message_id` + `assistant_message_id`.

### B-impl.2 — Gate (b): server-assigned IDs end-to-end

Backend (`backend/app/routers/ai.py` + `backend/app/schemas/ai.py`):

1. `ChatResponse`: add `user_message_id: Optional[str]` and
   `assistant_message_id: Optional[str]` (Optional so old tests with
   mocks-without-IDs don't break).
2. `/chat` single-shot handler (`:908-1022`): pre-allocate UUIDs *before*
   `db.add(user_row)`; pass them as `id=` to the model constructors (already
   doing this — line 981, 994 — but allocate ahead of construction so we
   can return them); include both IDs in the returned `ChatResponse`.

Android (`app/src/main/java/.../data/remote/api/`):

3. `ChatResponse` data class: add `@SerializedName("user_message_id")
   userMessageId: String?` and `@SerializedName("assistant_message_id")
   assistantMessageId: String?`.
4. `ChatStreamEvent.Done`: add `userMessageId: String?` and
   `assistantMessageId: String?`.
5. `ChatStreamClient.parseEvent` "done" branch: extract both fields.

Android repo (`app/src/main/java/.../data/repository/ChatRepository.kt`):

6. `sendMessage`:
   - DELETE the optimistic local userRow upsert (lines 115-123). The user
     types, the input clears (already happens in UI from sendMessage call
     site), and the row appears once the API response lands and we upsert
     using server ID.
   - Build the userRow + assistantRow using `response.userMessageId ?:
     UUID.randomUUID().toString()` (fallback for old mocks) and
     `response.assistantMessageId ?: UUID.randomUUID().toString()`.
   - Single upsertAll(listOf(userRow, assistantRow)) AFTER the API call.
7. `streamMessage`:
   - DELETE the optimistic local userRow upsert (lines 170-181). Streaming
     UI renders from `_turnState.value.partialText` for the in-flight turn,
     not from the Room row, so the user-bubble visible state is unchanged.
   - User row gets persisted via `commitAssistantTurn`'s extension below.
8. `commitAssistantTurn`:
   - Add parameters `userMessageId: String?` and `assistantMessageId: String?`.
   - Persist both userRow (with `userMessageId ?: UUID.randomUUID()` content
     = original user message — passed through from ChatViewModel) and
     assistantRow (with `assistantMessageId ?: UUID.randomUUID()`).
   - This means `commitAssistantTurn` now needs the original user message
     text. Adjust signature accordingly.

ChatViewModel (`app/src/main/java/.../ui/screens/chat/ChatViewModel.kt`):

9. `sendMessage` retains the user-text in the closure when calling
   `startStreamingTurn(text, snapshot)`.
10. `startStreamingTurn`: on `ChatStreamEvent.Done`, pass `event.userMessageId`,
    `event.assistantMessageId`, and the original user-text into
    `commitAssistantTurn`.
11. `cancelInFlight`: same — pass the IDs (which may be null since done
    didn't fire) and let `commitAssistantTurn` fall back to fresh UUIDs.

Test additions:

- `test_ai_chat.py::test_chat_response_includes_message_ids`.
- `test_chat_stream.py::test_chat_stream_done_includes_message_ids` (already
  in B-impl.1).
- `ChatRepositoryTest::sendMessage_uses_server_ids_for_local_writes`.
- `ChatRepositoryTest::pullHistory_idempotent_after_sendMessage` — assert
  that calling `pullHistory()` after `sendMessage` doesn't grow the row
  count beyond 2.
- `ChatViewModelStreamTest::commits_with_server_ids_on_done`.

### B-impl.3 — Item A: native `tool_use` migration

File: `backend/app/services/ai_productivity.py`.

1. Replace `_CHAT_SYSTEM_PROMPT` JSON-protocol rules (current lines
   1182-1190) with prose-only behavioral rules. Tool descriptions move to
   the `description` field on each tool def.
2. Add `_CHAT_TOOL_DEFINITIONS` constant: 8 tool defs covering all action
   types. Per-tool `input_schema` is the strict per-action shape (no flat
   union).
3. `generate_chat_response`:
   - Pass `tools=_CHAT_TOOL_DEFINITIONS` to `client.messages.create`.
   - Iterate `ai_message.content` blocks. Concatenate text-block `text`
     fields into `message`. Convert `tool_use` blocks to action dicts:
     `{"type": block.name, **block.input}`.
   - Drop the `_finalize_chat_payload` JSON parse — payload is already
     structured.
4. `generate_chat_response_stream`:
   - Use `client.messages.stream` (already does).
   - Switch from `text_stream` to per-event handling:
     `stream.__iter__()` yields `MessageStartEvent`,
     `ContentBlockStartEvent`, `ContentBlockDeltaEvent`,
     `ContentBlockStopEvent`, `MessageDeltaEvent`, `MessageStopEvent`.
   - For text blocks: yield `Token` events as `ContentBlockDeltaEvent`
     deltas of type `text_delta` arrive.
   - For tool_use blocks: accumulate `partial_json` deltas into a per-block
     buffer keyed by `block_index`. On `ContentBlockStopEvent`, parse the
     buffer as JSON, build the action dict.
   - On `MessageStopEvent`: yield `Done` event with the accumulated
     `message`, `actions`, and `tokens_used`.
5. Delete `_extract_partial_message_field` (~70 lines) — no longer needed.

Test additions in `backend/tests/test_ai_chat.py` and
`test_ai_chat_stream.py`:

- Mock `anthropic.Anthropic.messages.create` to return a `Message` with
  text + tool_use blocks; assert correct extraction.
- Mock `anthropic.Anthropic.messages.stream` to yield a sequence of typed
  events; assert correct Token / Done emission.
- All existing chat tests must continue to pass (no behavioral change at
  the wire contract layer).

### B-impl.4 — Test parity rule

No new DAO or `PrismTaskDatabase` member added in this bundle, so the
`androidTest/smoke/TestDatabaseModule.kt` parity rule does not apply.
(`ChatMessageDao` already exists since PR #1196 and is already wired in.)

### B-impl.5 — Non-acceptance reminders

- No backend protocol-version field for backward-compat (STOP-A4 not
  fired; over-engineering risk).
- No chat-handler architectural rewrite; both items surgical.
- No PR #1192 streaming protocol semantic changes.
- No new disclosure version (V4) — V3 is the version under audit.
- No additional D12 P2 items bundled — only Items A and B per scope-fit.

---

## § Phase 3 verification protocol

### For Item A (post-migration)

- Backend tests (mocked Anthropic):
  - `test_ai_chat.py::test_chat_uses_tool_use_protocol` — assert
    `tools=[...]` parameter, assert response parsing handles text+tool_use blocks.
  - `test_ai_chat_stream.py::test_stream_handles_content_block_events` —
    assert correct Token / Done emission from typed event sequence.
- Backend integration test (gated `RUN_AI_INTEGRATION_TESTS=1`, ~$0.05/run):
  - `test_ai_chat_real_anthropic.py::test_create_task_action_round_trip` —
    real Haiku call, assert at least one `tool_use` block returned for a
    prompt that should clearly elicit `create_task`.
- AVD smoke (each of 8 action types exercised):
  - "Add 'buy milk' to my list" → `create_task` action chip appears, tap
    → task created.
  - "Start a 25-minute timer" → `start_timer` action chip → timer screen.
  - "Complete this task" (with task_context) → `complete` action chip.
  - "Move this to next week" → `reschedule` chip.
  - "Move all my overdue work tasks to Friday" → `reschedule_batch` chip.
  - "Break this down into 3 subtasks" → `breakdown` chip.
  - "Archive this" → `archive` chip.
  - "Complete all tasks tagged #errands" → `batch_command` chip → batch
    preview screen.
- Regression: V3 disclosure copy unchanged (no new egress fields added by
  tool_use migration; the `tools` parameter is request-side only).

### For Item B per gate

- **Gate (a) verification**:
  - Send chat under streaming endpoint on Phone A.
  - Direct DB inspection (Railway Data tab):
    `SELECT * FROM chat_messages WHERE conversation_id = '<id>'` — assert 2
    rows (one user, one assistant).
  - On Phone B (or app reinstall): open chat, assert history reflects the
    streamed turn.
- **Gate (b) verification**:
  - Cold-start chat with existing history. Send 1 message single-shot. Send
    1 message streaming. Close app. Reopen app.
  - Assert local Room row count for the conversation == 4 (not 8). Use
    `adb shell "run-as com.averycorp.prismtask sqlite3 ... 'SELECT COUNT(*)
    FROM chat_messages WHERE conversation_id = ?'"` or in-app debug screen.
  - Assert UI renders 4 bubbles (2 user, 2 assistant), no duplicates.
- **Gate (c) verification (regression — verdict was PASS)**:
  - Clear V2 + V3 disclosure flags via in-app debug or `adb shell
    am clear-data`.
  - Open chat. Assert disclosure dialog appears.
  - Dismiss. Assert flag set.
  - Open chat again. Assert no dialog.
  - Send a message via streaming. Assert disclosure does NOT re-fire on
    streaming endpoint usage (would be a bug — V3 should fire ONCE per
    install/upgrade).
- **Gate (d) verification (regression — verdict was PASS)**:
  - Rapid double-tap Send during streaming. Assert no message loss + no
    duplicate row in chat_messages.
  - Tap Stop mid-stream. Assert exactly one assistant row with "(cancelled)"
    suffix.
  - Tap Stop AND immediately tap Send. Assert second message goes through
    (turn-state returned to Idle) without duplicating the cancelled row.

### Bundle-level CI

- `lint-and-test` (Android Gradle): green.
- `compileDebugAndroidTestKotlin`: green.
- `web-lint-and-test`: green (no web changes; should stay green).
- Backend `test`: green including new tests.
- No regression in PR #1192 streaming UX — Stop button still emits
  `(cancelled)` suffix; partial commit on cancel still works.
- No regression in PR #1196 single-shot persistence path.
- No regression in PR #1171 V2 disclosure (V2-dismissed + V3-not-yet-set
  users see V3 once; V3-already-set users not double-prompted).

---

## § Z — Appendices (trim first if cap approached)

### Z.1 — Memory candidates surfaced (wait-for-third rule)

| Pattern observed                                             | Data points so far |
|--------------------------------------------------------------|--------------------|
| Stale-clone-artifact at Phase 0 (PRs absent in `git log --all` despite being merged) | **3** (PR #1119 prior, PR #1196 prior, this session: #1164–#1167) — reaches wait-for-third threshold; eligible for memorization (operator decision in Phase 4) |
| Optimistic local Room writes diverge from server-authoritative IDs (PR #1196 violated its own audit doc design at line 221/295/307) | 1 (this session) — note but do not memorize yet |
| Async generator vs sync generator confusion in FastAPI streaming endpoints (Gate (a) root cause) | 1 (this session) — note but do not memorize yet |

### Z.2 — Cross-references

- D11 E.3 chat persistence audit: `docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md`
- F7 D.1 + F8 D.2 streaming + cancel audit: `docs/audits/F7_F8_CHAT_STREAMING_BUNDLE_AUDIT.md`
- F8 chat privacy doc update: `docs/audits/F8_CHAT_PRIVACY_DOC_UPDATE_AUDIT.md`
- F8 chat quality follow-on bundle: `docs/audits/F8_CHAT_QUALITY_FOLLOWON_BUNDLE_AUDIT.md`

### Z.3 — Retrigger criteria (preserved)

For future audit-first sessions:

- **Item A re-trigger** — already fired this session (schema-fields arm).
  Migration completes in Phase 2; closes this trigger.
- **D13 follow-on candidates** (filed if surfaced post-merge):
  - Token-bucket rate-limit alignment between `/chat` and `/chat/stream`
    (currently both use the same `daily_ai_rate_limiter` keyed off
    `(user_id, tier)`; sanity-check post-Item-A).
  - V4 disclosure if any new egress fields added by future Item A
    iterations (none added in this bundle).
  - Cross-device chat history conflict resolution if multi-device
    write-write conflicts surface (currently single-device-per-account
    assumption holds — `ChatMessageEntity` docstring line 19-20).

---

**End of Phase 1 audit.** Phase 2 implementation begins on the same branch
`claude/d12-chat-bundle-audit-6VL00` per repo branch policy.
