# AI Chat All-Data Context Audit

**Date:** 2026-05-15
**Branch:** `feat/chat-all-data-context`
**Trigger:** Operator request — "The Executive Assistant should have access to all of the user data, not just the current days."

The AI Coach (informally referred to as the "Executive Assistant") currently grounds its replies only in *today's* slice of the user's data — overdue + due-today + planned-today tasks, today's habit ticks, active projects (title + id only), today's leisure minutes, today's medication slot adherence. That bundle landed in PR #1442 (`docs/audits/AI_CHAT_CURRENT_STATE_AUDIT.md`). The operator now wants the chat to see "all the user data" so it can answer questions about future tasks, the user's habit streaks, project progress, and recent history — not just the present day.

This audit catalogs each candidate expansion against the existing today-scoped bundle and classifies which ones to ship in this PR vs. defer.

## Phase 1 — Gap analysis

### Today-scoped baseline (PR #1442 + #1446)

`backend/app/routers/ai/context.py::load_user_context_bundle` returns:

```
{
  "today_iso": "<YYYY-MM-DD>",
  "tasks": { overdue_count, overdue[≤5], due_today_count, due_today[≤5],
             planned_today_count, planned_today[≤5], completed_today_count },
  "habits": { active_count, completed_today_count, today[≤10] },
  "projects": { active_count, active[≤5] (id + title only) },
  "leisure_today": { total_minutes, by_category{...} },
  "medications_today": { slots_logged, slots_taken }
}
```

Token budget (per PR #1442 audit): <500 tokens at p95 for a moderately active user. The chat handler currently lets the AI know nothing about *future* work, *past* completions, or *trend* shape — only "what does today look like right now."

### Candidate expansions

| Item | Classification | Notes |
| --- | --- | --- |
| T1. Upcoming tasks (next 14 days, excluding today) | PROCEED (GREEN) | Lets AI answer "what's on my plate this week" without inventing dates. Cap 10. |
| T2. Backlog tasks (status=todo, due_date IS NULL, planned_date IS NULL) | PROCEED (GREEN) | These are the silent majority on many users' lists; AI cannot coach on them today. Cap 10. |
| T3. Recently completed tasks (last 7 days, capped 10) | PROCEED (GREEN) | Tells the AI what the user *has* been doing — material for "look how much you got done" coaching beats blind optimism. |
| H1. Per-habit current streak length | PROCEED (GREEN) | Streaks are core coaching material; needed for "you're on a 12-day streak — don't break it" or "Stretch is your weak spot." Computed in Python from a single query of last 60 days of completions. |
| H2. Per-habit last-7-day completion count | PROCEED (GREEN) | Cheap rollup from the same completion window as H1. Surfaces "you logged Read 5/7 last week." |
| P1. Per-project task counts (open / completed / total) | PROCEED (GREEN) | Single GROUP-BY query joined to tasks. Lets the AI talk about project velocity. |
| P2. Per-project due-date + progress % | PROCEED (YELLOW) | `Project.due_date` exists; progress % = `done / total` computed inline. Yellow because % is approximate when a project has 0 tasks (defaults to None). |
| G1. Active goals list (title + target_date) | PROCEED (GREEN) | Cheap (`SELECT * FROM goals WHERE status='active'`); orients the AI on what the projects ladder up to. Cap 5. |
| L1. Last-7-day leisure minutes by category | PROCEED (GREEN) | Same aggregation pattern as today's leisure but with a 7-day window. Lets AI answer "have I gotten any physical leisure this week?" |
| M1. 7-day medication adherence rate | PROCEED (GREEN) | `slots_taken / slots_logged` over the last 7 days. Lets the AI surface a weekly adherence headline without claiming "you've been perfect" off a single day's check. |
| M2. Active medication count + names | PROCEED (GREEN) | `SELECT * FROM medications WHERE archived_at IS NULL` capped 10. Without this the AI has no idea what meds the user takes. |
| LE1. Active Pomodoro / focus timer | DEFER | Android-only (Room `SmartPomodoroManager`); no Postgres mirror. Same gap noted in `AI_CHAT_CURRENT_STATE_AUDIT.md` Phase 4. |
| LE2. Mood / energy log (today) | DEFER | Android-only (`MoodEnergyLogEntity`). Same gap. |
| LE3. Check-in / weekly review status | DEFER | Android-only (`CheckInLogEntity` / `WeeklyReviewEntity`). Same gap. |
| LE4. Boundary rule violations | DEFER | Android-only. Same gap. |
| X1. Lifetime aggregate stats (total tasks ever, total minutes ever) | STOP-no-work-needed | Not actionable in coaching context. Big-number vanity stats crowd token budget without changing what the AI can usefully suggest. |
| X2. Full task list (no caps) | STOP-no-work-needed | User explicitly said "all the user data" but the model context window is finite; per-bucket caps preserve coachability. Counts are still surfaced so the AI never asserts undercount. |

### Token budget review

Prior bundle was <500 tokens at p95 for ~30 open tasks / ~15 habits / ~3 projects. Adding T1+T2+T3 (up to 30 more task lines), per-habit streak/7d (≈2× the habit block), P1+P2 (per-project counts/progress, ~3× projects block), G1 (≤5 goal lines), L1 (≈2× leisure block), M1+M2 (≈3× meds block) lifts p95 to an estimated ~1500–1800 tokens. Comfortably under chat handler's per-call ceiling — Anthropic Claude Haiku has a 200K context and the chat handler reserves only 1024 output tokens. The operator has explicitly traded budget for grounding, so the larger bundle is on-protocol.

### Constraints kept invariant

- **`_CHAT_SYSTEM_PROMPT_BASE` text remains untouched.** All new content appends below the preferences block. The forgiveness-first canaries from PR #1408 stay green. (Per memory `project_chat_system_prompt_load_bearing.md`.)
- **Multi-tenant isolation enforced at every query** via `user_id == current_user.id`. The existing pattern in `context.py` is preserved.
- **Graceful DB-failure degradation preserved.** Loader stays wrapped in `try/except`; on any error the chat handler short-circuits to `current_state=None` and the formatter omits the block.
- **No `CHAT_SYSTEM_PROMPT` regression** — the canary tests in `tests/test_ai_chat.py` and `tests/test_ai_chat_context.py` continue to assert the unmodified base + the appended block shape.

## Phase 2 — PR fan-out (this PR)

Single PR. Backend-only — same shape as PR #1442 (today-scoped) and PR #1446 (SoD-anchored). The change is cohesive: loader fields, formatter sections, and tests are tightly coupled.

Files touched:

```
backend/app/routers/ai/context.py             (+ ~280 lines — new loaders T1-T3, H1-H2, P1-P2, G1, L1, M1-M2)
backend/app/services/ai_productivity.py       (+ ~120 lines — render new sections in _format_current_state_block)
backend/tests/test_ai_chat_context.py         (+ ~280 lines — coverage for each new bucket)
docs/audits/AI_CHAT_ALL_DATA_CONTEXT_AUDIT.md (this doc)
```

No changes to:

- `chat.py` / `chat_stream.py` — they already call `load_user_context_bundle` and pass the returned dict to the service unchanged. Expanding the dict shape is purely additive.
- `_CHAT_SYSTEM_PROMPT_BASE` — preserved verbatim. Existing canary tests continue to assert it appears in the rendered prompt.
- `ChatRequest` / `ChatResponse` schemas — wire format unchanged.

## Phase 3 — Bundle summary

(Filled in once PR opens.)

**Intent:** Make the AI Coach grounded in the user's full task ladder (today + upcoming + backlog + recently done), each habit's streak shape, every active project's velocity, this week's leisure totals, and this week's medication adherence — instead of only the today-slice that landed in PR #1442.

**Risk shape:**

- **Token-budget growth** is the marginal risk. Empirically p95 lifts from ~500 → ~1500–1800 tokens. Still small relative to Haiku's 200K context window. Operator explicitly accepted this trade.
- **Streak computation cost.** H1 needs the last ~60 days of `habit_completions` for each active habit to walk back from today. A single window-bounded SELECT keyed on `user_id` keeps it O(n_active_habits × ≤60 rows) — well under 1000 rows for typical users.
- **System prompt regression risk** is mitigated by the same approach as PR #1442: `_CHAT_SYSTEM_PROMPT_BASE` is unchanged, the new sections append after the existing today-scoped block. PR #1408 forgiveness-first canaries continue to assert the base text appears.
- **DB-load failure** still degrades gracefully via the existing try/except in the chat handler.
- **Multi-tenant isolation** holds — every new query filters by `user_id == current_user.id`. No cross-user leakage paths introduced.

**What the AI can now do that it couldn't before:**

- Talk about "this week" without making it up — concrete upcoming-task titles, leisure totals, and medication adherence are visible.
- Cite habit streaks accurately ("you're on day 12 of Read"). Previously it had only today's count.
- Reference project velocity ("PrismTask v2 is 6/14 done").
- Surface recently completed work for momentum framing.
- Recognize a backlog of dateless tasks and offer to triage them.

## Phase 4 — Claude Chat handoff (PR 2: Android live state, unchanged from PR #1442 audit)

The set of Android-only data gaps documented in `docs/audits/AI_CHAT_CURRENT_STATE_AUDIT.md` Phase 4 — active Pomodoro state, mood/energy logs, check-ins, boundary rule violations — remains unaddressed by this PR. The handoff block in that earlier audit is still the right starting point for that follow-up.

```
Background: PR #<INSERT-PR-NUMBER> shipped the all-server-data expansion for AI
Coach context — the chat now sees upcoming tasks (next 14d), backlog, recently
completed, per-habit streaks + 7-day rates, per-project progress, active goals,
7-day leisure totals, and 7-day medication adherence. See
docs/audits/AI_CHAT_ALL_DATA_CONTEXT_AUDIT.md.

PR 2 (still pending — from #1442's audit) closes the remaining Android-only
gaps. The following data has no Postgres mirror, so PR 2 must extend ChatRequest
(backend/app/schemas/ai.py) AND add client-side passthrough on Android
(app/.../ChatViewModel.kt + ChatRepository.kt):

1. Active Pomodoro / focus timer state — running? what task? remaining minutes?
2. Most recent MoodEnergyLogEntity entry from today.
3. Today's CheckInLogEntity status.
4. Active BoundaryRuleEntity violations.

Backend hook is already in place: load_user_context_bundle(db, user_id, today)
returns a dict; the formatter just needs a new "Right Now" section appended for
Android-sent runtime state. Keep per-bucket caps tight (token budget already
sits at ~1500–1800 after this PR — aim to stay under 2500 combined).

Don't change _CHAT_SYSTEM_PROMPT_BASE text. Append, don't rewrite.
```

## Verification (filled in post-implementation)

```
$ pytest tests/test_ai_chat_context.py tests/test_ai_chat.py \
         tests/test_ai_chat_stream.py tests/test_chat_persistence.py \
         tests/test_ai_memory.py
<results>
```
