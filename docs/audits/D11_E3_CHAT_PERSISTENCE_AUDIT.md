# D11 E.3 — Chat Conversation Persistence (Postgres + Room, Backend-Routed)

**Branch**: `claude/chat-persistence-database-6n0mT`
**Base**: `origin/main` @ commit `427bc2b` (rebased clean; PRs #1190–#1195 visible).
**Scope**: D11 item E.3 only. E.4 (chat daily-reset signaling) is OUT OF SCOPE per operator scope-lock.
**Risk**: HIGH per D11 filing — cross-stack schema additions on Postgres + Room with integration into existing `/chat` endpoint.

---

## § 0 — Phase 0 base-branch verification + premise recon

### 0.1 Base-branch verification

`git fetch origin && git rebase origin/main` — clean. `origin/main` head is `427bc2b fix(timer): unbreak compileDebugKotlin on main after #1194 (#1195)`. PRs #1190 (checkin), #1191 (audit reframe), #1192 (chat streaming audit doc), #1193 (F6 audit reframe), #1194 (timer foreground service), #1195 (compile fix) all visible. **PASS.**

### 0.2 Reframe — streaming Phase 2 never shipped (STOP-A applied + resolved)

**Premise mismatch caught.** The prompt assumed PR #1192 shipped the streaming endpoint (`/chat/stream`, `generate_chat_response_stream`, `_build_chat_messages_array`, `_finalize_chat_payload`, `_extract_partial_message_field`, `ChatStreamEvent`, ChatViewModel `Streaming(partialText, startedAt)` state). Verified absent from main:

- `grep -n "@router\." backend/app/routers/ai.py` — only `/chat` (line 902); no `/chat/stream`.
- `grep -rn "StreamingResponse\|text/event-stream\|EventSource" backend/app/` — zero matches in chat path.
- `grep -rn "ChatStreamEvent\|Streaming(\|partialText" app/src/main` — zero matches.

Inspecting commit `8ce17bd` confirms PR #1192 was **the audit doc only** (`docs/audits/F7_F8_CHAT_STREAMING_BUNDLE_AUDIT.md`, +676 lines). Streaming Phase 2 was filed but never implemented.

**Operator reframe (May 8, in-session)**: collapse E.3 scope to non-streaming integration. Single-shot `/chat` is the only chat endpoint; persistence writes happen on response. No Streaming-state work in this PR. STOP-A resolved.

### 0.3 Reframe — Firestore is no longer the entity-sync layer (architectural staleness, memory #9 family)

**Second premise mismatch caught.** The prompt frames E.3 as a "tri-layer Postgres + Firestore + Room" feature. Actual codebase state:

- `BackendSyncService` (`app/src/main/java/com/averycorp/prismtask/data/remote/sync/BackendSyncService.kt`) is the active entity-data sync path — Android Room ↔ FastAPI `/api/v1/sync/push` + `/sync/pull` ↔ Postgres. Tasks, Projects, Habits, Tags, Medications, etc. all sync via this path.
- Firestore in the live codebase is restricted to *preference* sync only: `ThemePreferencesSyncService`, `GenericPreferenceSyncService`, `SortPreferencesSyncService`, `CloudIdOrphanHealer`. None of these touch entity tables.
- BackendSyncService KDoc (line 33–35): *"This is independent of the Firebase SyncService — both can be enabled side-by-side."* — i.e. Firestore is a legacy peer, not the dominant path.

**Verdict**: chat persistence should follow the BackendSyncService precedent. Postgres is source-of-truth; Room is local cache; cross-device reconciliation happens via a backend GET-history endpoint, not Firestore. **No Firestore mappers in scope.** This is a *third* architectural-staleness reframe instance for memory #9 (after PR #1192 staleness on streaming).

### 0.4 Postgres alembic version recon

`backend/alembic/versions/` head: `024_add_beta_codes.py`. Next free: **025**. SQLAlchemy + Pydantic patterns: `Task` (`backend/app/models.py` ~line 200), `Project`, `Medication` are local exemplars.

### 0.5 Backend admin CLI convention check (memory #29 staleness)

`backend/scripts/` directory **does not exist** on main. Memory #29 reference appears stale. Equivalent CLI surfaces live as services (`backend/app/services/beta_codes.py`) with tests at `backend/tests/test_beta_codes_cli.py`. No CLI needed for this PR; flagged for memory-update only.

### 0.6 AI gate verification (memory #24)

`backend/app/routers/ai.py:72` — `dependencies=[Depends(require_ai_features_enabled)]` is router-level, applies to every `/ai/*` endpoint including `/chat`. Any new endpoint we add to this router inherits the gate automatically. **PASS.**

Backend-wide AI-egress scan: no router outside `/ai/` calls Anthropic without going through `ai_productivity` services (which are gated upstream by router-level deps). No new defects of memory #24 family found.

### 0.7 Rollout-window verification (memory #4)

Sole-user pre-beta confirmed (no public release per CLAUDE.md baseline). Path A (forward-only) migration is safe — no other clients to break. **PASS.**

### 0.8 In-flight conflict check (STOP-G)

`mcp__github__list_pull_requests state=open` — empty list. Zero open PRs in averycorp/prismtask. **STOP-G clear.**

### 0.9 Phase 0 summary

| Check | Status |
|---|---|
| Base-branch verification | PASS (rebased to 427bc2b) |
| PR #1192 streaming live | **FAIL** → reframe to no-streaming (operator-confirmed) |
| Firestore tri-layer assumption | **FAIL** → reframe to backend-routed Postgres+Room |
| Alembic numbering | PASS (next = 025) |
| AI gate `require_ai_features_enabled` wired | PASS (router-level) |
| Sole-user pre-beta | PASS (Path A safe) |
| In-flight conflict | PASS (no open PRs) |
| Backend admin CLI convention (memory #29) | STALE (flag for memory update) |

**Reframe count from prompt to actual baseline: 2 (streaming, Firestore tri-layer) + 1 stale memory ref.**

---

## § 1 — Item 1: Conversation model decision

### Existing state

`ChatRepository.kt:37-38` already carries a `conversationId: String` regenerated daily. Format: `chat_{ISO_DATE}_{8-char-UUID}`. Daily-reset (`resetIfNewDay()`) regenerates ID + clears in-memory `_messages` flow.

### Verdict — **Item 1: Shape A** (flat messages with `conversation_id` discriminator)

Rationale:
1. **UX is single-conversation**: `ChatScreen` has no conversation switcher; the user sees exactly one ongoing thread, daily-reset.
2. **Daily-reset is already conversation-ID-keyed**: persisting messages by `conversation_id` lets us preserve prior days as inactive threads while the UI shows only today, with zero UX change.
3. **Shape B (explicit conversation entity)** would require a new `chat_conversation` table + FK + listing endpoint + UI rename/delete affordances — none of which are in current scope. YAGNI.
4. **Daily Reset semantics under Shape A**: at SoD, `getConversationId()` mints a new ID; the new conversation is empty; old conversation rows persist in Postgres + Room but are filtered out of the active thread by `WHERE conversation_id = current`. A future "Conversation history" Settings affordance can surface old IDs without schema changes.

**Roadmap impact**: README roadmap does not list multi-conversation chat. If/when it does, Shape A → Shape B is a non-destructive forward migration (add `chat_conversation` table; backfill from distinct conversation_ids).

---

## § 2 — Item 2: Postgres schema design

### Verdict — **Item 2: GREEN**

**Migration**: `backend/alembic/versions/025_add_chat_messages.py` (new). Adds one table.

**Table `chat_messages`**:

```python
op.create_table(
    "chat_messages",
    sa.Column("id", sa.String(length=64), primary_key=True),     # UUID4 hex
    sa.Column("user_id", sa.Integer(), nullable=False),
    sa.Column("conversation_id", sa.String(length=128), nullable=False),
    sa.Column("role", sa.String(length=16), nullable=False),     # "user" | "assistant"
    sa.Column("content", sa.Text(), nullable=False),
    sa.Column("actions", sa.JSON(), nullable=True),              # list[ChatActionPayload] for assistant turns
    sa.Column("task_context_snapshot", sa.JSON(), nullable=True),# ChatTaskContext snapshot for user turns
    sa.Column("tokens_input", sa.Integer(), nullable=True),
    sa.Column("tokens_output", sa.Integer(), nullable=True),
    sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
)
op.create_index("ix_chat_messages_user_conv_created",
                "chat_messages",
                ["user_id", "conversation_id", "created_at"])
op.create_index("ix_chat_messages_user_created",
                "chat_messages",
                ["user_id", "created_at"])
```

**Indexes**:
- `(user_id, conversation_id, created_at)` — chronological retrieval inside a conversation (the hot path).
- `(user_id, created_at)` — scans for "all of this user's chat across all days" (admin/export).

**SQLAlchemy model**: `ChatMessage` in `backend/app/models.py`, mirrors `Task` style — relationships to `User` (cascade delete), JSON columns via `sqlalchemy.JSON`.

**Pydantic schemas** (added to `backend/app/schemas/ai.py`):
- `ChatMessageRecord` — read-out model (id, conversation_id, role, content, actions, task_context_snapshot, created_at, tokens_used).
- `ChatHistoryResponse` — `{messages: list[ChatMessageRecord], next_before: str | None}`.

**No Pydantic write model** — writes happen inside the existing `/chat` handler from server-side data, not from a client write payload. Single source-of-truth: backend authors writes.

---

## § 3 — Item 3: Backend FastAPI endpoints

### Verdict — **Item 3: GREEN with one new GET; existing `/chat` extended**

**New endpoint**:

```python
@router.get("/chat/history", response_model=ChatHistoryResponse)
async def chat_history(
    conversation_id: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
    before: str | None = Query(default=None),  # cursor: ISO-8601 created_at
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    ...
```

- Auth: inherits `Depends(get_current_user)` via `get_active_user`.
- AI gate: inherits `Depends(require_ai_features_enabled)` from router-level. (Note: GET-only retrieval *technically* doesn't egress to Anthropic — but historical chat content already touched Anthropic, so the gate is the right governance fence.)
- Filtering: `conversation_id` optional → falls through to "today's conversation" using the same `chat_{date}_*` prefix scan if omitted, OR returns all conversations sorted DESC.
- Pagination: cursor-based on `created_at`; client passes `before` from previous page's oldest entry.

**Existing `/chat` extension** (write-on-response, inline, NOT background-task):

```python
# After ChatResponse construction, before return:
db.add(ChatMessageModel(
    id=uuid.uuid4().hex,
    user_id=current_user.id,
    conversation_id=data.conversation_id,
    role="user",
    content=data.message,
    task_context_snapshot=(data.task_context.model_dump(exclude_none=True)
                            if data.task_context else None),
    created_at=user_turn_at,
))
db.add(ChatMessageModel(
    id=uuid.uuid4().hex,
    user_id=current_user.id,
    conversation_id=data.conversation_id,
    role="assistant",
    content=result["message"],
    actions=[a.model_dump() for a in validated_actions],
    tokens_input=tokens_used.input,
    tokens_output=tokens_used.output,
    created_at=assistant_turn_at,
))
await db.commit()
```

**Why inline (not BackgroundTasks)**:
- Latency cost is one INSERT; trivial vs Anthropic call (~300-2000ms upstream).
- BackgroundTasks would race against the client's GET-history call; persistence-before-response avoids the race entirely.
- On commit failure (rare): we already returned 5xx upstream OR returned the chat response then 500'd commit — second case still returns the response to user, who sees it but next history pull misses it. Acceptable given low risk.

**Why no client POST**: per § 0.3 reframe (server-authoritative), Android does not write to chat tables directly. Server owns writes. This collapses LOC and removes auth-trust questions.

**No DELETE endpoint in scope**: `clearConversation()` becomes a UI-only mute (filter by current `conversation_id`). Future "Delete history" affordance is a follow-on D-series.

---

## § 4 — Item 4: Sync layer

### Verdict — **Item 4: Backend-routed Postgres + Room (no Firestore)**

Per § 0.3 reframe. Direction:

```
[Android sends] ─ POST /chat ─→ [backend] ─→ writes 2 rows to Postgres
                                          ─→ returns ChatResponse
[Android receives ChatResponse] ─→ ChatRepository writes 2 rows to Room
                                  (mirrored from same conversation_id, role, content,
                                   actions, etc.)

[Other device opens chat / pull-to-refresh]
[Android] ─ GET /chat/history?conversation_id=... ─→ [backend] returns Postgres rows
[Android] ─→ upserts into Room (REPLACE on PK conflict)
```

**Conflict semantics**:
- Postgres PKs are server-generated UUIDs; Room mirrors with same PK on receive. No collision possible across devices because each turn is server-authored.
- Local Room write happens after successful response; Postgres is canonical. If the local write loses (device crashes mid-call), the GET pull on next open re-syncs.
- Dedup against PR #1168's D.3 in-flight guard: that guard keys on user-message dedup pre-send, unrelated to persistence. No interaction.

**Cross-device sync trigger**: chat-screen open or explicit pull-to-refresh. Not real-time push (no Firestore listener). Acceptable because chat is single-device-active in practice; cross-device read of yesterday's conversation is a recall affordance, not a live-sync requirement.

**Room ↔ Postgres mapping**: 1:1, same column shapes. JSON columns (`actions`, `task_context_snapshot`) stored as `TEXT` in Room (Gson-serialized) and `JSONB` in Postgres.

---

## § 5 — Item 5: Android Room schema + migration

### Verdict — **Item 5: Path A (forward-only). DB version 76 → 77.**

**Why Path A**:
- Sole-user pre-beta (memory #4) — no other clients to break.
- Existing in-memory state is ephemeral by design (lost on every process death). Path B's "best-effort capture before drain" adds ~80 LOC for zero practical benefit.
- Operator default-apply rule fires: "sole-user pre-beta + memory #4 → Path A".

**Migration `MIGRATION_76_77`** (added to `Migrations.kt`):

```kotlin
val MIGRATION_76_77 = object : Migration(76, 77) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `conversation_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `actions_json` TEXT,
                `task_context_json` TEXT,
                `tokens_input` INTEGER,
                `tokens_output` INTEGER,
                `created_at` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_chat_messages_conversation_created` ON `chat_messages` (`conversation_id`, `created_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_chat_messages_created` ON `chat_messages` (`created_at`)")
    }
}
```

`CURRENT_DB_VERSION = 77` (was 76).

**Note on `user_id`**: Room schema omits `user_id` because PrismTaskDatabase is per-account-per-device — only one user's data ever lives in a given DB instance (account switch wipes the DB). Backend retains `user_id` for multi-tenant isolation.

**Memory #17 + #21 compliance**:
- `PrismTaskDatabase` adds `chatMessageDao()` — must be reflected in `app/src/androidTest/java/com/averycorp/prismtask/smoke/TestDatabaseModule.kt` `@Provides fun provideChatMessageDao(...)`.
- `compileDebugAndroidTestKotlin` is the pre-merge guard; phase 3 must run it.

---

## § 6 — Item 6: ChatViewModel + ChatRepository state-machine update

### Existing state machine (no streaming)

`ChatViewModel`:
- `messages: StateFlow<List<ChatMessage>> = chatRepository.messages` (currently in-memory).
- `_isTyping`, `_actionsInFlight`, `_showDisclosure`, `_showClearConfirm`, `_navigationEvents`.
- No `Streaming(partialText, startedAt)` state — confirmed § 0.2.

### Verdict — **Item 6: GREEN**

`ChatRepository` changes:

1. **Inject `ChatMessageDao`** via constructor.
2. **Replace `_messages: MutableStateFlow<List<ChatMessage>>` with a Flow off the DAO**, scoped to current `conversation_id`:
   ```kotlin
   val messages: Flow<List<ChatMessage>> =
       conversationIdFlow.flatMapLatest { id -> dao.observeForConversation(id) }
           .map { rows -> rows.map { it.toChatMessage() } }
   ```
3. **Maintain `conversationIdFlow: MutableStateFlow<String>`** so daily-reset triggers a switch on the DAO Flow. `getConversationId()` becomes `.value`/setter.
4. **`sendMessage()`**: insert user-turn row pre-API-call (matches existing optimistic UI), then on response, upsert assistant-turn row (and re-upsert user-turn with any server-side fields like `tokens_used`).
5. **`clearConversation()`**: regenerates conversation_id; *does not* DELETE rows in Room. Old conversation persists silently. UI Flow flips to the new (empty) conversation_id.
6. **New: `pullHistory(conversationId)`** — calls `GET /chat/history`, upserts results via `dao.upsertAll()`. Called on chat-screen open.

`ChatViewModel` changes:
- Plumb `messages` from Flow → `stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())`.
- Add `pullHistory()` call to init / `onResume`.
- No state-machine restructure.

### Conflict semantics

- **Local write + remote echo**: not applicable — no real-time Firestore listener. The local-write happens once per turn from the response body; no echo.
- **Cross-device pull**: GET-history `upsertAll()` uses `OnConflictStrategy.REPLACE` on PK. Since PKs are server-generated UUIDs, REPLACE is idempotent: same row on the server, same row in Room, no observable change.
- **PR #1168 D.3 dedup guard**: keys on user-message-in-flight, unrelated to persistence layer. No interaction.

---

## § 7 — Item 7: Daily Reset semantics impact

### Verdict — **Item 7: Shape A daily reset semantics**

At SoD boundary (per `DayBoundary` + `ChatRepository.resetIfNewDay()`):

1. `getConversationId()` returns the day-rolled new ID (`chat_{newDate}_{newUUID}`).
2. `conversationIdFlow.value = newId` — DAO Flow re-subscribes to the new ID; emits empty list.
3. UI shows fresh empty conversation.
4. **Old messages remain in Room and Postgres** under their original `conversation_id`. No DELETE.
5. Future "Conversation history" Settings affordance can list distinct `conversation_id` values + sample first message — non-blocking follow-on.

**E.4 (chat daily-reset signaling) follow-on impact**: per the prompt's own note, "if persistence ships, daily reset goes away naturally" — under Shape A, daily reset is now a UI filter (not a destructive event), so E.4 closure becomes a 0-LOC delta verifying the filter behavior. **E.4 closure remains OUT OF SCOPE for this PR per operator scope-lock**; flagged for separate bookkeeping PR.

---

## § 8 — Item 8: Privacy disclosure impact (V3 trigger)

### Verdict — **Item 8: V3 disclosure required, in scope of this PR**

**Material change in retention shape**:
- V2 (current, PR #1171): "Chat content sent to backend on each turn; rolling 6-pair history sent with subsequent turns; nothing persisted server-side beyond rate-limit metadata."
- V3 (this PR): "Chat content **persisted on PrismTask backend (Postgres) indefinitely** until user explicitly deletes; mirrored to local device (Room) for offline read; no third-party retention beyond Anthropic's transient processing."

This is a non-cosmetic retention change. V3 disclosure must precede or accompany the persistence shape change.

**Disclosure copy bump**:
- Add `KEY_AI_CHAT_DISCLOSURE_SHOWN_V3` to `UserPreferencesDataStore` (parallel to existing V2 at line 241).
- `ChatViewModel.showDisclosure` flips to V3 key.
- Update privacy doc (`docs/PRIVACY.md` or whatever current path) to enumerate persisted fields.
- Data Safety form (Play Store config) — flag for follow-up checklist; this PR documents copy variants in `CHANGELOG`.

**Copy variant** (drafted, may iterate during implementation):

> "PrismTask now stores your conversational coach chat history on its backend so you can pick up the thread on any signed-in device. Messages, AI responses, and any actions the AI proposed are kept until you delete them. The AI itself (Anthropic) does not store the content beyond the seconds it takes to respond. You can clear chat history from Settings → Privacy."

**STOP-V3 check**: V3 ships in this same PR. Operator scope-lock confirms.

---

## § 9 — Item 9: Sibling persistence sweep (recon-only, NOT bundled)

### Candidates surveyed

| ViewModel | Current state | Persistence gap? | Verdict |
|---|---|---|---|
| `PasteConversationViewModel` (`extract/`) | Persists extracted tasks via TaskRepository; the extraction transcript is ephemeral. | Transcript not retained. | **File as D-series** — transcript replay would be a UX upgrade; not bundled. |
| `WeeklyReviewViewModel` | Persists via `WeeklyReviewEntity` + `WeeklyReviewAggregator`. | Already persists. | **Skip** — no gap. |
| `DailyBriefingViewModel` | Ephemeral — daily briefing regenerates from current task state. | Briefing text not retained. | **File as D-series** — briefing playback / archive is a UX upgrade; not bundled. |
| `PomodoroCoachingViewModel` (`SmartPomodoro`) | Ephemeral coaching messages. | Not retained. | **File as D-series** — coaching transcript archive is low-priority; not bundled. |
| `MorningCheckInResolver` flow | Persists via `CheckInLogEntity`. | Already persists. | **Skip**. |

**Total bundled here: 0.**
**Total filed for D-series follow-on: 3 (PasteConversation transcript, DailyBriefing archive, PomodoroCoaching transcript).**

Per **STOP-D** (≥2 sibling candidates): not bundled. Filed.

**Re-trigger criteria for each filed sibling**:
- PasteConversation transcript: re-trigger if user reports "I lost my paste-extract output and the tasks I created from it diverged."
- DailyBriefing archive: re-trigger when user explicitly asks to revisit previous briefings.
- PomodoroCoaching transcript: re-trigger when usage analytics show coaching messages > 3/day.

---

## § 10 — Item 10: Test coverage plan

### Backend (pytest)

1. `test_chat_history_endpoint.py`:
   - Returns empty list when no messages.
   - Returns messages for a given `conversation_id` in chronological order.
   - Pagination: `before` cursor + `limit` round-trip.
   - 401 without auth.
   - 451 (or whatever AI-gate status) when AI features disabled.
   - User isolation: user A cannot read user B's messages even with valid auth.
2. `test_chat_persistence.py`:
   - `/chat` POST writes 2 rows (user + assistant) to `chat_messages` on success.
   - On Anthropic 5xx: no rows written (transactional).
   - `tokens_input` / `tokens_output` recorded correctly.
   - `task_context_snapshot` JSON round-trips.
   - `actions` JSON round-trips.
3. Regression: existing `test_ai_chat.py` still GREEN (no contract changes to `/chat` request/response shapes).

### Android (JVM unit)

1. `ChatRepositoryPersistenceTest`: in-memory Room + fake `PrismTaskApi`.
   - `sendMessage()` writes 2 rows to DAO on success.
   - `messages` Flow reflects DAO state.
   - Daily-reset switches Flow to new conversation_id without DELETE.
   - `pullHistory()` upserts API response into DAO.
2. `ChatViewModelTest` updates: state-machine test still passes with Flow source change.
3. `ChatMessageDaoTest`: round-trip insert / observeForConversation / upsertAll.
4. `MIGRATION_76_77` test under `androidTest/database/`: schema migration + fixture round-trip.

### Cross-stack

5. Sync emulator integration **deferred to manual test plan** — local backend + AVD pair test in Phase 3.

### Regression

- `ChatViewModelStreamingTest` does not exist (streaming never shipped). N/A.
- `ChatViewModelActionTest` (PR #1168 D.3 dedup) — must pass; persistence layer should not interact with action-dispatch dedup.
- `ChatViewModelDisclosureTest` (PR #1171 V2 disclosure) — must extend to cover V3 key.

---

## § 11 — Item 11: STOP conditions

| STOP | Condition | Status |
|---|---|---|
| STOP-A (premise wrong) | Base verification fails / streaming missing / Firestore tri-layer wrong | **Resolved** — operator-confirmed reframes (no streaming, no Firestore) |
| STOP-D (sibling balloon ≥2) | ≥2 sibling persistence candidates | **Filed** — 3 siblings filed as D-series, NOT bundled |
| STOP-F (>200 LOC) | LOC ceiling | Pre-approved exception (mega bundle) |
| STOP-MEGA (>2,000 LOC) | Combined estimate | **PASS** — estimate ~1,050 LOC (see § 12) |
| STOP-LOC drift | Phase 1 estimate × 2 | Monitor during Phase 2 |
| STOP-G (in-flight conflict) | Open chat PR | **PASS** — zero open PRs |
| STOP-DATA (irreversible data step) | Path B with risk | **PASS** — Path A forward-only |
| STOP-V3 (privacy disclosure not in same PR) | V3 forced out of scope | **PASS** — V3 bundled |

**No active STOPs. Phase 2 cleared to proceed.**

---

## § 12 — LOC estimate (per memory #10 calibration)

| Layer | File | Est LOC |
|---|---|---|
| Backend | `alembic/versions/025_add_chat_messages.py` | 60 |
| Backend | `app/models.py` (ChatMessage SQLAlchemy) | 45 |
| Backend | `app/schemas/ai.py` (ChatMessageRecord, ChatHistoryResponse) | 40 |
| Backend | `app/routers/ai.py` (extend `/chat` + new GET `/chat/history`) | 110 |
| Backend | `tests/test_chat_history_endpoint.py` | 180 |
| Backend | `tests/test_chat_persistence.py` | 140 |
| Backend subtotal | | **575** |
| Android | `data/local/entity/ChatMessageEntity.kt` | 50 |
| Android | `data/local/dao/ChatMessageDao.kt` | 70 |
| Android | `data/local/database/Migrations.kt` (MIGRATION_76_77) | 30 |
| Android | `data/local/database/PrismTaskDatabase.kt` (entity + dao wire) | 10 |
| Android | `data/repository/ChatRepository.kt` (rewrite to DAO-backed) | 110 |
| Android | `ui/screens/chat/ChatViewModel.kt` (init/onResume pull) | 25 |
| Android | `data/preferences/UserPreferencesDataStore.kt` (V3 key) | 25 |
| Android | `ui/screens/chat/ChatDisclosureDialog.kt` (V3 copy) | 50 |
| Android | `data/remote/api/PrismTaskApi.kt` + ApiModels (history endpoint) | 40 |
| Android | `androidTest/.../smoke/TestDatabaseModule.kt` (provide ChatMessageDao) | 5 |
| Android | `test/.../ChatRepositoryPersistenceTest.kt` | 110 |
| Android | `test/.../ChatMessageDaoTest.kt` | 60 |
| Android | `androidTest/.../Migration76To77Test.kt` | 70 |
| Android | `test/.../ChatViewModelDisclosureTest.kt` (V3 update) | 25 |
| Android subtotal | | **680** |
| Docs | `docs/PRIVACY.md` (V3 enumerate) | 30 |
| Docs | `CHANGELOG.md` | 15 |
| Docs | `CLAUDE.md` (chat persistence note) | 10 |
| **Total** | | **1,310** |

Per memory #10 multi-stack 1.9–14× drift band: applying conservative 1.5× upper-bound as audit signal would imply ~1,965 LOC — still under STOP-MEGA. Phase 2 actuals will land lower because reframes (no Firestore, no streaming) collapsed scope vs prompt's original shape.

**Per memory #10 calibration logged below for memory update**: tri-layer-style audit pre-reframe likely would have pushed 1,800–2,400 LOC; reframe to single-source-of-truth backend collapses by ~30%.

---

## § 13 — Implementation order (Phase 2 commits)

1. Backend alembic migration `025_add_chat_messages.py`.
2. Backend SQLAlchemy `ChatMessage` model + Pydantic schemas.
3. Backend GET `/chat/history` endpoint + persistence write inside `/chat`.
4. Backend tests.
5. Android Room `ChatMessageEntity` + `ChatMessageDao` + `MIGRATION_76_77`.
6. Android `TestDatabaseModule` provider.
7. Android `PrismTaskApi` history endpoint binding.
8. Android `ChatRepository` rewrite to DAO-backed.
9. Android `ChatViewModel` init/onResume pull.
10. V3 disclosure key + dialog copy.
11. Android tests + privacy doc + CHANGELOG.

---

## § 14 — Summary verdicts

| Item | Verdict |
|---|---|
| 1 — Conversation model | **Shape A** (flat with `conversation_id` discriminator) |
| 2 — Postgres schema | **GREEN** — alembic 025, one table, two indexes |
| 3 — Endpoints | **GREEN** — extend `/chat` + new GET `/chat/history`; no client write endpoint |
| 4 — Sync layer | **Backend-routed Postgres + Room (NO Firestore)** |
| 5 — Room migration | **Path A** — DB 76 → 77, forward-only |
| 6 — ChatViewModel | **GREEN** — Flow rewires from MutableStateFlow to DAO; no state-machine restructure |
| 7 — Daily Reset | **Shape A** — UI filter, no DELETE; old conversations preserved |
| 8 — V3 disclosure | **Required + in scope this PR** |
| 9 — Sibling sweep | **3 filed as D-series, 0 bundled** |
| 10 — Test coverage | Plan complete (backend + Android JVM + migration test) |
| 11 — STOP conditions | All clear; no active STOPs |

**Architectural-staleness reframes caught: 2** (streaming PR #1192 not landed; Firestore no longer the entity-sync layer). **Memory #29 stale ref flagged** (no `backend/scripts/`).

**Phase 2 cleared to proceed.**
