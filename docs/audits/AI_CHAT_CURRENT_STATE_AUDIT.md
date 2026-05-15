# AI Chat Current-State Context Audit

**Date:** 2026-05-14
**Branch:** `feat/ai-chat-current-state-context`
**Trigger:** Operator request — "The AI chat should be able to see everything (tasks, habits, timer, etc)."

## Phase 1 — Gap analysis

### What the chat saw before this change

The chat handler (`backend/app/routers/ai/chat.py`, `chat_stream.py`) loaded essentially nothing about the user's current state:

| Source | Lines | What it provided |
| --- | --- | --- |
| `_load_user_preferences` | `chat.py:80–83`, `chat_stream.py:106–109` | Up to 15 stored AI-memory preferences. |
| `data.task_context` | `chat.py:104–108`, `chat_stream.py:139–144` | A single task snapshot **only when chat was opened from a task**. |
| `data.history` | `chat.py:109`, `chat_stream.py:144` | The last six user/assistant pairs, persisted by the client. |

So unless the user opened chat from a specific task, the AI saw zero state about today's work. Coaching replies could only be informed by what was in the user's literal message — no grounding in due dates, habit streaks, project load, leisure totals, or medication adherence.

### What is now visible (PR 1, this PR)

The chat handler now also loads, on every turn, a "current state" bundle via `backend/app/routers/ai/context.py::load_user_context_bundle`. The bundle is rendered as a `## Current State` block appended to the system prompt by `_format_current_state_block` in `ai_productivity.py`. The base prompt text (`_CHAT_SYSTEM_PROMPT_BASE`) is **unchanged** — the new block is additive, so the canary tests guarding the forgiveness-first revision (PR #1408) stay green.

| Bundle section | Source table(s) | Cap |
| --- | --- | --- |
| Open tasks bucketed into `overdue` / `due_today` / `planned_today` (with title, id, priority, days-overdue) | `tasks` | 5 per bucket; full counts always present |
| `completed_today_count` | `tasks` where `status='done' AND date(completed_at)=today` | scalar |
| Active habits + each habit's today completion count + target | `habits` outer-joined to `habit_completions` on `date=today` | 10 listed; full `active_count` + `completed_today_count` present |
| Active projects | `projects` where `status='active'` | 5 listed; full `active_count` present |
| Today's leisure minutes aggregated by category | `leisure_sessions` where `date(logged_at)=today` | full per-category breakdown |
| Today's Daily Essentials medication slot adherence | `daily_essential_slot_completions` where `date=today` | `slots_logged` + `slots_taken` |

Token budget: empirically <500 tokens at p95 for a moderately active user (~30 open tasks, ~15 habits, ~3 projects). Stays well inside the chat handler's 1024-token output budget.

### What is still NOT visible (Phase 4 — PR 2 work)

These entities are Android-only and have no Postgres mirror, so the backend cannot load them:

| Gap | Where it lives | Why it matters |
| --- | --- | --- |
| **Active Pomodoro / focus timer state** | Runtime only on Android (`SmartPomodoroManager`) | "I'm in a deep-work block right now" should change the AI's suggestions. |
| **Recent mood / energy log** | `MoodEnergyLogEntity` (Room only) | Mood is a major signal for coaching tone. |
| **Today's check-in status** | `CheckInLogEntity` (Room only) | "Have you checked in today?" affects whether to surface a check-in prompt. |
| **Boundary rule violations** | `BoundaryRuleEntity` (Room only) | Active violations should constrain what the AI proposes. |
| **Per-user Start-of-Day hour** | `UserPreferencesDataStore.startOfDay` (Room only) | Backend "today" currently uses UTC date; per-user SoD needs the client to forward the hour. |

These five gaps require the Android client to send the data in `ChatRequest`. That's a follow-up — keeping it out of this PR preserves the no-client-changes shape and isolates the system-prompt diff.

## Phase 2 — PR fan-out (this PR)

Single PR. The change is backend-only and lands as one cohesive unit because the loader, the system-prompt formatter, the chat handler wiring, and the tests have tight coupling.

Files touched:

```
backend/app/routers/ai/context.py        (new, 230 LOC)
backend/app/routers/ai/chat.py           (+24 lines — loader call + plumb)
backend/app/routers/ai/chat_stream.py    (+22 lines — loader call + plumb)
backend/app/services/ai_productivity.py  (+115 lines — _format_current_state_block + signature additions)
backend/tests/test_ai_chat_context.py    (new, 440 LOC, 11 tests)
docs/audits/AI_CHAT_CURRENT_STATE_AUDIT.md (this doc)
```

## Phase 3 — Bundle summary

**Intent:** Make the AI chat substantially more grounded by injecting "what the user is doing today" into the system prompt on every turn. Coaching replies stop being purely message-driven and start being state-driven.

**Risk shape:**

- **System prompt regression risk** is mitigated by leaving `_CHAT_SYSTEM_PROMPT_BASE` text **completely unchanged** — the current-state block appends after the preferences block. Existing canary tests for the forgiveness-first revision continue to pass (see `tests/test_ai_chat*.py` — 67 passing tests).
- **Token-budget risk** is mitigated by per-bucket caps (5 tasks per bucket, 10 habits, 5 projects). Counts are still surfaced for the AI's awareness so it never asserts "you only have N tasks" when more exist behind the cap.
- **DB-load failure** degrades gracefully: the loader is wrapped in `try/except` and a load failure short-circuits to `current_state=None`, which the formatter handles by skipping the block entirely. The chat reply still goes through; only the grounding is missing.
- **Multi-tenant isolation** holds — every query in `context.py` filters by `user_id == current_user.id`. No cross-user leakage paths.
- **PII** — the bundle is generated from data the user already owns and persisted server-side. It is never written back to disk; it lives only in the system prompt for the duration of the request.

**What the AI can now do that it couldn't before:**

- Reference the user's actual due-today tasks by title without being told which ones they are.
- Know whether the user has logged any habits today, and which ones.
- Know how many active projects exist and their names.
- Know whether the user has had any leisure time today (and how much, by category).
- Know whether the user has logged their medications today.
- Notice when the user is asking about "today" but has nothing on their plate.

## Phase 4 — Claude Chat handoff (PR 2: Android live state)

Paste into a fresh Claude Chat thread to pick up PR 2.

```
Background: PR #<INSERT-PR-NUMBER> shipped the backend half of "AI chat should see
everything" — chat now loads today's tasks, habits, projects, leisure totals, and
medication adherence from Postgres on every turn and renders them as a "## Current
State" block in the system prompt. See docs/audits/AI_CHAT_CURRENT_STATE_AUDIT.md.

PR 2 closes the remaining gaps. The following data is Android-only with no
Postgres mirror, so PR 2 must extend ChatRequest (backend/app/schemas/ai.py) AND
add client-side passthrough on Android (app/.../ChatViewModel.kt + ChatRepository.kt
+ ChatRequest.kt or the equivalent network model):

1. Active Pomodoro / focus timer state — running? what task? remaining minutes?
2. Most recent MoodEnergyLogEntity entry from today (mood code + energy code +
   timestamp, capped to today only).
3. Today's CheckInLogEntity status (logged yes/no + timestamp).
4. Active BoundaryRuleEntity violations (rule label + which category triggered).
5. The user's UserPreferencesDataStore.startOfDay hour — once present, the
   backend should compute "today" relative to it instead of UTC midnight (the
   pattern is already used by routers/tasks.py and routers/nlp.py — wire chat.py
   the same way: minutes_sod = sod_hour*60 + sod_minute).

The backend already has the hook: load_user_context_bundle(db, user_id, today) is
SoD-aware via its `today: date` argument. Extending it to merge in the client-sent
runtime state goes inside _format_current_state_block (probably a new section
called "Right Now" or similar) — keep the per-bucket caps tight and budget the
combined output to <750 tokens.

Test pattern: add an Android instrumented test that opens chat with an active
Pomodoro and asserts the request body carries the runtime state; backend tests
should mirror TestChatEndpointWiresCurrentState in test_ai_chat_context.py for
the new request fields.

Don't change _CHAT_SYSTEM_PROMPT_BASE text. Append, don't rewrite.
```

## Verification

```
$ pytest tests/test_ai_chat_context.py
11 passed in 3.70s

$ pytest tests/test_ai_chat.py tests/test_ai_chat_stream.py \
         tests/test_chat_persistence.py tests/test_ai_memory.py
67 passed in 22.18s
```
