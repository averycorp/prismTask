# AI Assistant Phase 1 — System Prompt Audit

**Status:** Implemented (Phase 1 PR open)
**Spec:** `docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md`
**Plan:** `docs/superpowers/plans/2026-05-18-ai-assistant-phase-1-tool-use-loop.md`
**Convention:** `_CHAT_SYSTEM_PROMPT_BASE` is load-bearing — per
`memory/project_chat_system_prompt_load_bearing.md`, every edit
requires this audit doc before merge.

## Diff Summary

Two new sections appended to `_CHAT_SYSTEM_PROMPT_BASE` between the
existing "User preferences memory" block and the closing triple-quote.
No other section is touched.

- **Tools (read):** documents the 6 server-executed read tools (5 narrow + `query`)
  and when to call vs when to rely on the curated state bundle.
- **Tools (write):** restates the existing write-chip contract — model
  emits, user taps; model does NOT execute writes server-side.
- Closing anti-fabrication rule reinforces: never invent IDs, fields,
  or filter keys; retry on error rather than loop.

Note: `get_mood_logs` is intentionally absent from the Tools (read)
list — descoped from Phase 1 (no Postgres mirror for
`MoodEnergyLogEntity` yet). Re-added in the Phase 1 follow-up that
lands the backend mirror.

## Forgiveness-First Guardrail Review

- ✅ Crisis pre-filter still runs before the loop in `chat()` (unchanged).
- ✅ No new chip type introduced that encodes shame / deficit framing.
- ✅ The "Tools" section instructs the model to call READ tools server-side
  but to never invent task IDs or user data — same anti-fabrication rule
  as today's prompt.
- ✅ Free tier behavior unchanged (rate limiter rejects at 403 before any
  tool-use path); flag-off behavior unchanged.
- ✅ "Never call more than ~3 reads per turn unless the user asked" is a
  soft guidance; the hard `TOOL_CALL_BUDGET = 10` in the loop is the
  load-bearing cap.

## Canary Test Coverage

- `test_ai_chat.py::test_endpoint_403_for_free_tier` — preserved.
- `test_ai_chat.py::TestLoopFlagOff::test_loop_disabled_with_flag_off_matches_baseline` — added in T14.
- `test_ai_chat.py::TestLoopFlagOff::test_loop_disabled_for_free_tier` — added in T14.

## Approval

Author: Avery Karlin
Date: 2026-05-18
