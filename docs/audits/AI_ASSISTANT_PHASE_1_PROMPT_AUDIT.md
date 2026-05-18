# AI Assistant Phase 1 — System Prompt Audit

**Status:** Draft (Phase 1 in progress)
**Spec:** `docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md`
**Convention:** `_CHAT_SYSTEM_PROMPT_BASE` is load-bearing — per
`memory/project_chat_system_prompt_load_bearing.md`, every edit
requires this audit doc before merge.

## Diff Summary (planned)

The Tools section is added to `_CHAT_SYSTEM_PROMPT_BASE` between the
existing "User preferences memory" block and the closing triple-quote.
No other section is touched.

## Forgiveness-First Guardrail Review

- ✅ Crisis pre-filter still runs before the loop in `chat()`.
- ✅ No new chip type introduced that encodes shame / deficit framing.
- ✅ The "Tools" section instructs the model to call READ tools server-side
  but to never invent task IDs or user data — same anti-fabrication rule
  as today's prompt.
- ✅ Free tier behavior unchanged (rate limiter rejects at 403 before any
  tool-use path); flag-off behavior unchanged.

## Canary Test Coverage

- `test_ai_chat.py::test_endpoint_403_for_free_tier` — preserved.
- `test_ai_chat.py::test_loop_disabled_with_flag_off_matches_baseline` — added Task 14.
- `test_ai_chat.py::test_loop_disabled_for_free_tier` — added Task 14.

## Approval

Author: Avery Karlin
Date: 2026-05-18
