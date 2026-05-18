# AI Personal Assistant — End-to-End Design

**Status:** Draft for review
**Author:** Avery Karlin (assistant-collaborated brainstorm, 2026-05-18)
**Scope:** Umbrella spec for raising the AI Coach to parity with the user's own data + action surface, phased.

## North Star

> The AI Personal Assistant should be able to do anything the user is able to do and access any data the user is able to access.

This spec turns that principle into a phased implementation by defining the mechanism, capability surface, gating model, and rollout order. It is an umbrella — each phase ships as its own implementation plan + PR train, but they share the architecture below.

## In Scope

- **Surfaces:** Android in-app chat (`ChatScreen` / `ChatViewModel`), Web chat (`web/src/features/chat`).
- **Mechanism:** Hybrid — Claude tool-use for reads, expanded action-chip JSON for writes.
- **Data:** All user-scoped data already accessible via FastAPI + Firestore (tasks, habits, projects, medications, mood/energy logs, leisure logs, check-ins, weekly reviews, goals, boundary rules, notification profiles, dashboard layout, ND preferences, theme, sync prefs, calendar sync).
- **Writes:** Reversible single-entity ops (tap-apply), multi-entity / compound ops (preview screen), irreversible ops (confirm dialog).
- **Read budget:** Up to 10 read tool calls per conversational turn, then a final reply.
- **Subscription:** All-Pro. Free tier keeps today's 8-chip catalog and curated context bundle, unchanged.

## Out of Scope (hard exclusions)

- Billing / subscription (upgrade, cancel, plan switch, debug-tier override).
- Auth / account (sign in/out, Google account switch, account deletion, factory reset).
- OS / device permissions (notifications, exact-alarm, microphone, camera) — Android policy requires user action.
- External-recipient integrations (Gmail send, Slack post, third-party calendar event creation) — affects non-user systems.
- Voice / hands-free, widget shortcuts, iOS — defer to a separate spec.
- Streaming + tool-use in `chat_stream.py` — defer to Phase 4.

## Current Baseline (what we are extending)

The AI Coach today (per `app/src/main/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModel.kt`, `backend/app/routers/ai/chat.py`, `backend/app/services/ai_productivity.py`):

- **Action surface:** 8 chip types in `ChatViewModel.executeAction` — `complete`, `reschedule`, `reschedule_batch`, `breakdown`, `archive`, `start_timer`, `create_task`, `batch_command`. Batch covers `TASK / HABIT / PROJECT / MEDICATION` mutations via NL → `BatchPreviewScreen`.
- **Data surface:** Curated `load_user_context_bundle` block in `backend/app/routers/ai/context.py` — tasks (overdue/today/planned/upcoming/backlog/recent-completed), habit progress + streaks, active projects, active goals, today + 7-day leisure, today + 7-day medication adherence. Read-only, prefab.
- **Persistence:** Server-authoritative `chat_messages` table. Rolling N=6 history on the client; the server reads it on each turn.
- **Memory:** Up to 15 AI-extracted preferences via `remember_preference` / `forget_preference` tool calls (preserved unchanged).
- **Guardrails:** Crisis pre-filter, forgiveness-first system prompt, per-IP rate limiter (30/min), per-user daily AI budget. Free tier already supported.

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────┐
│ Client (Android ChatViewModel / Web chat feature)             │
│                                                                │
│   - Owns rolling history (N=6 pairs)                          │
│   - Renders write chips (3-tier: tap / preview / confirm)     │
│   - Executes write chips locally against repos (Android)      │
│     or Firestore-direct + REST (Web)                          │
│   - Mirrors chat_messages into Room (Android) / IndexedDB     │
└────────────────────────────────────────────────────────────────┘
                              │
                  POST /ai/chat  (existing endpoint, extended)
                              ▼
┌────────────────────────────────────────────────────────────────┐
│ Backend  app/routers/ai/chat.py + new tool dispatcher         │
│                                                                │
│   ┌──────────────────────────────────────────────────────────┐ │
│   │ Single agentic loop (capped 10 read tool calls)         │ │
│   │                                                          │ │
│   │  Claude  ──tool_use──▶  ToolDispatcher  ──result──▶ Claude│ │
│   │   ▲                          │                            │ │
│   │   │                          ▼                            │ │
│   │   │              ToolRegistry (Pro-gate, rate-limit)     │ │
│   │   │                          │                            │ │
│   │   │           ┌──────────────┴──────────────┐            │ │
│   │   │           ▼                              ▼            │ │
│   │   │   Narrow read tools (~6)        Escape-hatch query  │ │
│   │   │   tasks / habits / projects /   query(entity_type,  │ │
│   │   │   meds / mood / leisure         filters, fields)    │ │
│   │   │                                                       │ │
│   │   └─── final reply + action chips (writes only) ───┐    │ │
│   └────────────────────────────────────────────────────│────┘ │
│                                                         ▼      │
│   Persist user + assistant turns to chat_messages              │
│   (incl. tool_use records + tool_result blocks, audit trail)   │
└────────────────────────────────────────────────────────────────┘
```

**Key design moves:**

1. **One endpoint stays canonical** (`POST /ai/chat`). The agentic loop happens server-side inside the existing handler — the client request/response shape is preserved.
2. **Tool dispatcher** is a single new module (`app/routers/ai/tool_registry.py` + per-tool handlers in `app/routers/ai/tools/`) — registers tool schemas for Claude AND maps each tool name to a server-side handler that reads Firestore/Postgres scoped to `current_user`.
3. **Writes never become server tools.** They emit as action-chip JSON in the final assistant reply (existing `ChatActionPayload` shape, expanded). The client executes them under user gate. Preserves forgiveness-first undo + visible audit trail.
4. **Persistence audit trail.** Tool-use blocks are persisted on the assistant row so cross-device history can re-render what the AI looked up. New column on `chat_messages`: `tool_calls JSONB` (nullable).
5. **Free-tier fallback.** When `tier != PRO`, the loop is bypassed — the existing curated context-bundle + 8-chip catalog still runs. Free behavior is byte-identical to today.

## Section 2 — Read Tool Registry (Phase 1)

Six narrow tools that mirror the six panels of the existing `load_user_context_bundle` plus one escape hatch. Schemas live in `app/routers/ai/tool_registry.py`.

| Tool | Args | Returns | Backed by |
|---|---|---|---|
| `get_tasks` | `bucket: overdue\|today\|planned\|upcoming\|backlog\|recently_completed`, `limit≤50`, `project_id?`, `tag?`, `life_category?` | Task summaries (id, title, due, priority, tags, project, life_category, days_overdue) | `firestore_tasks.fetch_*` |
| `get_habits` | `window: today\|last_7d\|last_30d`, `category?`, `include_archived=false` | Per-habit: name, count/target, streak, last7, category, isBuiltIn | `HabitRepository` mirror via existing context loader |
| `get_projects` | `status: active\|archived\|all`, `limit≤50` | id, title, status, progress_pct, done/total, days_until_due, milestones_count | `ProjectRepository` projections |
| `get_medications` | `window: today\|last_7d`, `include_archived=false` | adherence summary + per-med taken_today, missed_today, refill_due | `medications` router |
| `get_mood_logs` | `start: ISO`, `end: ISO`, `aggregate: raw\|daily_avg` | mood/energy points or daily averages | `MoodEnergyLogEntity` |
| `get_leisure_logs` | `window: today\|last_7d\|last_30d`, `category?` | totals by category + entries | `leisure` router |
| **`query`** *(escape hatch)* | `entity_type: TASK\|HABIT\|PROJECT\|MEDICATION\|MOOD_LOG\|LEISURE_LOG\|CHECK_IN\|GOAL`, `filters: dict`, `fields: string[]`, `limit≤100`, `order_by?` | rows matching the filter | Per-entity-type dispatcher with a closed-set filter grammar |

**Filter grammar for `query` (closed-set, validated):** keys are entity-typed (e.g. for TASK: `completed`, `due_before`, `due_after`, `priority_in`, `tag_in`, `project_id`, `life_category_in`, `text_contains`). Unknown keys → tool error result to the model, which retries with valid keys. No raw SQL.

**Always-loaded vs on-demand:** The existing curated state bundle stays as a `system`-prefixed block (cheap, cached); the new tools add *pull-on-demand* depth. Saves tokens on simple turns where the bundle is enough.

**Per-tool rate limit:** Read tools share a per-user budget of 30 calls/minute (the agentic loop already caps a turn at 10). Daily AI rate limiter remains the real budget ceiling.

## Section 3 — Write Surface: Expanded Action Chips + 3-Tier Confirmation

Today's 8 action types graduate to a **closed discriminated union of 35 types** (15 T1 + 12 T2 + 8 T3), partitioned by tier. Tier is encoded in `ChatActionPayload` so the client renders the right surface without re-deriving:

```kotlin
enum class ChatActionTier { TAP_APPLY, PREVIEW, CONFIRM_DIALOG }
data class ChatActionPayload(
    val type: String,        // e.g. "complete_task", "delete_project"
    val tier: ChatActionTier,// server sets, client trusts
    // …existing optional fields, plus per-type unions
)
```

**Tier T1 — tap-apply (one-tap + snackbar Undo).** All reversible single-entity ops. ~15 types: `complete_task`, `uncomplete_task`, `reschedule_task`, `archive_task`, `unarchive_task`, `start_timer`, `stop_timer`, `log_habit`, `unlog_habit`, `log_mood`, `log_leisure`, `take_medication`, `set_priority`, `add_tag`, `remove_tag`.

**Tier T2 — preview screen (existing `BatchPreviewScreen` extended).** Multi-entity or compound. ~12 types: `reschedule_batch`, `archive_batch`, `breakdown` (subtask insertion), `create_task`, `create_habit`, `create_project`, `create_medication`, `create_goal`, `edit_task_fields`, `edit_habit_fields`, `edit_project_fields`, `batch_command` (current NL → mutations preview).

**Tier T3 — confirm-dialog (modal "are you sure", typed-name for >10-child deletes).** Irreversible. ~8 types: `delete_task`, `delete_habit`, `delete_project`, `delete_medication`, `delete_goal`, `bulk_delete`, `import_replace` (JSON import in replace mode), `restore_from_backup`.

**Excluded entirely (per hard-exclusion list):** any chip type that would mutate billing, auth, OS permissions, or non-user systems. The tool registry never registers a handler for these, and the system prompt asserts the negative explicitly.

**Backward compat:** Existing 8 chip types are renamed (`complete` → `complete_task`, etc.) with server-side aliases so persisted chat history with the old names still renders. Old-name → new-name map lives in the `ChatActionPayload` validator.

## Section 4 — Backend Protocol & Tool-Use Loop

**Request shape unchanged.** Existing `ChatRequest` is preserved. The agentic loop is internal to `chat()`.

**Loop pseudocode** (in `app/routers/ai/chat.py`, gated on `tier == PRO`; helper names like `tool_budget_exceeded_user_msg()` are illustrative — final naming lands in the implementation plan):

```python
messages = build_initial_messages(history, user_msg, bundle)  # existing
tool_calls_made = 0
TOOL_CALL_BUDGET = 10

while True:
    resp = anthropic.messages.create(
        model=CLAUDE_MODEL,
        system=_CHAT_SYSTEM_PROMPT,
        tools=tool_registry.claude_schemas(),
        messages=messages,
    )
    messages.append({"role": "assistant", "content": resp.content})

    tool_use_blocks = [b for b in resp.content if b.type == "tool_use"]
    if not tool_use_blocks:
        break  # final reply

    # Hard cap. If the model tries to keep calling, force-stop and
    # let it produce a final answer with what it has.
    tool_calls_made += len(tool_use_blocks)
    if tool_calls_made > TOOL_CALL_BUDGET:
        messages.append(tool_budget_exceeded_user_msg())
        continue

    tool_results = []
    for block in tool_use_blocks:
        result = tool_registry.dispatch(
            user=current_user,
            tool_name=block.name,
            tool_input=block.input,
        )
        tool_results.append(
            {"type": "tool_result", "tool_use_id": block.id, "content": json.dumps(result)}
        )
    messages.append({"role": "user", "content": tool_results})

# Extract final text + action chips from resp.content
```

**New persistence shape:** `chat_messages.tool_calls JSONB` (nullable). Stores `[{name, input, result_summary}, …]` on the assistant row only — full results dropped (token-heavy). Lets `GET /chat/history` re-render what the AI looked up.

**Tool dispatcher contract:**

```python
class ToolHandler(Protocol):
    name: str
    schema: dict  # Anthropic tool schema
    tier_required: Tier  # PRO for all P1+
    def dispatch(self, user: User, db: AsyncSession, args: dict) -> dict: ...
```

Handlers live in `app/routers/ai/tools/{tasks,habits,projects,medications,mood,leisure,query}.py`. Registry import is the single source of truth; a missing handler raises at import time.

**Streaming** (`chat_stream.py`): defer to Phase 4. P1 keeps `chat_stream.py` unchanged for free tier; Pro tier uses non-streaming `chat()` with tool-use loop. SSE + tool_use is non-trivial; descoped from P1.

**Crisis pre-filter** stays at the top of the handler (defense in depth) — runs before the tool loop, short-circuits identically.

**System prompt update:** `_CHAT_SYSTEM_PROMPT_BASE` gets a new section *"Tools"* documenting the registry, when to call vs when to reply directly, and reinforcing "no fabricated IDs" + "writes emit as chips, you do not call write tools." Reuses the existing forgiveness-first + safety blocks unchanged. **Per established convention (memory: chat prompt is load-bearing): requires a Phase-1 audit doc before edits.**

## Section 5 — Surface Execution: Android + Web

**Android.** `ChatViewModel.executeAction` (already a `when (action.type)` switch) extends to ~40 branches via a refactor that swaps the inline switch for a `ChatActionExecutor` registry — one executor per `type`, keyed on a string. Each executor:

```kotlin
interface ChatActionExecutor {
    val type: String
    val tier: ChatActionTier
    suspend fun execute(action: ChatActionResponse, deps: ExecutorDeps): ChatActionResult?
}
```

`ExecutorDeps` carries repository handles (Task/Habit/Project/Medication/Mood/Leisure/Goal). Existing 8 executors lift into this shape unchanged. Tier renders:
- **T1:** existing chip → instant apply + `ChatActionResult` snackbar with Undo.
- **T2:** chip launches `BatchPreviewScreen` (existing) with pre-filled mutations; user approves to commit.
- **T3:** chip opens an `AlertDialog` (typed-name confirm if `affected_count > 10`); commits on confirm.

Existing idempotency (`actionsInFlight` signature) stays — the signature function is extended to cover new types (already pattern-matched). All execution is local-repo (Android already has every entity's repo).

**Web.** `web/src/features/chat` mirror. Today's chat sends to `/ai/chat` but doesn't currently render action chips. The expanded protocol requires:

1. A new `useChatActionExecutor` hook that holds the same registry shape (one executor per type).
2. T1 writes go Firestore-direct (matches the existing `task_completions` Firestore-direct path landed in v1.9.x web sync hardening).
3. T2 reuses a new `BatchPreviewModal` component (no existing equivalent — net new on web).
4. T3 uses a confirm dialog (any existing primitive — likely `Dialog` from the existing web UI kit).
5. Undo snackbar: web doesn't have one today → uses `react-hot-toast` (already a dep) action-button pattern.

**Voice / Widget / iOS:** explicitly out of scope.

## Section 6 — Phasing Roadmap

**Phase 1 — Tool-use loop + read tools (foundation).**
- New modules: `app/routers/ai/tool_registry.py`, `app/routers/ai/tools/{tasks,habits,projects,medications,mood,leisure,query}.py`.
- Loop integrated into `chat.py`, gated on `tier == PRO`. Non-streaming only.
- `chat_messages.tool_calls JSONB` Alembic migration.
- System prompt audit doc (`docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md`) before any `_CHAT_SYSTEM_PROMPT_BASE` edit.
- Free tier behavior byte-identical (regression-guarded by existing chat canary tests).
- Feature flag `AI_ASSISTANT_TOOL_USE_ENABLED` (env var, default off) — flip off if the loop misbehaves in prod; chat falls back to the existing single-shot Haiku call.

**Phase 2 — Write surface expansion (Android).**
- `ChatActionExecutor` registry refactor: the 8 existing executors graduate via the alias map into the 35-type T1/T2/T3 union (15 + 12 + 8).
- `BatchPreviewScreen` extended to accept arbitrary mutation lists (currently bespoke to `batch_command`).
- New chip-renderer that respects `tier` field.
- Server-side `ChatActionPayload` discriminated union update + alias map for back-compat.

**Phase 3 — Write surface parity on Web.**
- `useChatActionExecutor` + Firestore-direct + `BatchPreviewModal` + confirm dialog.
- Action-result toast pattern.

**Phase 4 — Streaming + polish.**
- SSE-compatible tool-use loop in `chat_stream.py`.
- Per-tool latency telemetry, agent-loop debug panel in Settings → Diagnostics.

## Safety, Testing, Migration (cross-cutting)

**Safety alignment** — applies every phase:

- Crisis pre-filter runs *before* the tool loop, unchanged. Tool loop never executes on a crisis turn.
- Forgiveness-first guardrails — no chip type encodes "shame" or "deficit" framing (e.g. no `mark_habit_missed`). Anti-pattern list in `PHILOSOPHY.md` applies — audit-first STOP gate before any chip type that could surface negative-state language.
- Per-task notify soft cap, ProfileAutoSwitcher, and all 7 automation safety mechanisms are unaffected — the assistant goes through the same repository layer the user does, never around it.
- Tool dispatcher authorization: every handler resolves `current_user` server-side, never trusts an `user_id` in tool args. No cross-user data access possible.
- System-prompt audit doc required for every `_CHAT_SYSTEM_PROMPT_BASE` edit (per established convention; chat prompt is load-bearing).

**Testing strategy:**

- **Unit (backend):** one test file per tool handler — happy path + auth boundary + filter validation.
- **Unit (Android):** one test per executor — idempotency, undo correctness, Pro-gating.
- **Integration (backend):** end-to-end test that runs a real Anthropic tool-use round-trip against a mocked Claude (or record/replay cassette) — catches loop breakage end-to-end.
- **Canary:** extend existing `test_ai_chat.py` system-prompt canary tests with two new ones: (a) free tier never invokes tool_use; (b) tool-use disabled (flag off) fall-through still returns a chat-style response.
- **Anti-regression:** `test_chat_action_alias_map.py` covers every legacy type-name → new-name mapping.

**Migration / back-compat:**

- Existing persisted `chat_messages.actions` rows with old type strings (`complete`, etc.) render via the alias map. No data migration needed.
- Free tier preserves today's behavior bit-for-bit (curated context-bundle + 8-chip catalog, no tool_use, no expanded executors).
- Rollback path: flip `AI_ASSISTANT_TOOL_USE_ENABLED` off; chat falls back to single-shot Haiku.

## Open Questions

None blocking spec finalization. Implementation-plan questions (to be resolved during writing-plans):
- Exact Anthropic tool schema dialect (parameters vs input_schema field name; verify against current SDK).
- Whether `chat_messages.tool_calls` should reference `ChatMessageEntity` for Room mirroring or live server-only (recommendation: server-only — clients don't need to re-render tool calls in P1).
- Whether the `query` escape-hatch ships in P1 or moves to P2 (recommendation: P1, gated behind a second feature flag so it can be disabled if the model abuses it).

## Acceptance Criteria (Phase 1 only)

- ✅ A Pro user can ask "Show me my most overdue Self-Care tasks" and the AI invokes `get_tasks(bucket="overdue", life_category="self_care")` and grounds its reply in the result.
- ✅ A free user's chat is byte-identical to today's behavior (no tool_use, same context bundle, same 8 chips).
- ✅ With `AI_ASSISTANT_TOOL_USE_ENABLED=false`, Pro user behavior also reverts to today's.
- ✅ Crisis pre-filter still short-circuits without invoking any tool.
- ✅ Tool calls are persisted to `chat_messages.tool_calls` and retrievable via `GET /chat/history`.
- ✅ All existing `test_ai_chat.py` + canary tests pass unchanged.

Subsequent phases get their own acceptance criteria when their implementation plans are written.
