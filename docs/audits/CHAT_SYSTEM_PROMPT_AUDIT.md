# Chat System Prompt — Productivity-as-Virtue Framing Audit

**Scope.** Audit the chat system prompt that conditions every Claude Haiku
turn in PrismTask's AI Coach against the eight framing principles
(F1-F8) the operator articulated, predict its behavior on four probe
queries, sweep sibling AI prompts, and (if needed) propose 2-3 rewrite
variants. Audit-only — no prompt change ships in this session.

**Branch.** `audit/chat-system-prompt-mh-alignment`. **HEAD.**
`5a1dd360` (baseline). **App.** 1.9.24. **Backend alembic head.**
`025_add_chat_messages` (latest under `backend/alembic/versions/`).

**Method.** `grep -n` of the entire `backend/app/services/` +
`backend/app/routers/` tree for system-prompt anchors; line-level reads
of `backend/app/services/ai_productivity.py` (1902 LOC), the chat router
package (`backend/app/routers/ai/{chat,chat_stream,__init__}.py`), the
chat Pydantic schemas (`backend/app/schemas/ai.py:714-845`), and the
chat test surface (`backend/tests/test_ai_chat*.py`).

---

## 1. Premise verification

| # | Operator-locked premise | Verdict | Evidence |
|---|---|---|---|
| P1 | Chat backend at `backend/app/routers/ai.py:554-568` | **YELLOW — re-anchored** | `backend/app/routers/ai.py` no longer exists. PR #1301 (commit `08e42270`, "refactor(backend): split ai.py router into per-feature package") split the 1845-LOC file into `backend/app/routers/ai/*.py`. Audit re-anchors to the **real** system-prompt location: `backend/app/services/ai_productivity.py:1230-1250` (`_CHAT_SYSTEM_PROMPT_BASE`). Router-side wiring lives in `backend/app/routers/ai/chat.py` + `chat_stream.py`. STOP-A NOT triggered — prompt is findable and unambiguous, just relocated. |
| P2 | System prompt defined inline (not assembled from many fragments) | **GREEN** | Single string literal `_CHAT_SYSTEM_PROMPT_BASE` (`ai_productivity.py:1230`), wrapped by `_format_chat_system_prompt(user_preferences)` (`ai_productivity.py:1256`) which appends a rendered list of stored AI-memory preferences. No template loader, no multi-file assembly, no per-tier branching. STOP-E NOT triggered. |
| P3 | `ChatTaskContext` has 6 fields | **GREEN** | `backend/app/schemas/ai.py:758-772`: `title`, `description`, `due_date`, `priority`, `project_name`, `is_completed`. Exact match. |
| P4 | Chat gated by `require_ai_features_enabled` | **GREEN** | `backend/app/routers/ai/__init__.py:101-105` applies `Depends(require_ai_features_enabled)` at the `/ai` aggregate-router level → both `/chat` and `/chat/stream` inherit. Plus IP rate limiter (`chat_rate_limiter.check`) and per-user `daily_ai_rate_limiter` checks in each handler (`chat.py:64,79`, `chat_stream.py:77,79`). |
| P5 | `/chat` + `/chat/stream` share the same prompt assembly | **GREEN** | Both call `_format_chat_system_prompt(user_preferences)` (`ai_productivity.py:1684` for single-shot, `:1756` for streaming) and `_build_chat_messages_array(...)` (`ai_productivity.py:1545`). Identical `system=` argument on both `client.messages.create` and `client.messages.stream` calls. A prompt change ships to both endpoints atomically — no drift risk. |
| P6 | App at v1.8.49+ | **GREEN** | `app/build.gradle.kts:22` → `versionName = "1.9.24"`. |

**No STOP fires.** Proceed.

---

## 2. The audited prompt (verbatim)

Audited version: `backend/app/services/ai_productivity.py:1230-1250` at HEAD
`5a1dd360`. The full string is reproduced below — this IS the change
surface, so a complete quote is appropriate (per CLAUDE.md, internal
code is not subject to copyright redaction).

```text
You are PrismTask's conversational productivity coach. The user is in a one-on-one chat with you inside an Android task-management app. Be warm, concise, and concrete — never preachy. Default to 1-3 short sentences; only go longer when the user explicitly asks.

You have access to a set of tools that render as inline action buttons under your reply. Use them when the user has expressed a clear, actionable intent in their most recent message. Prefer no tool call over a weak suggestion. NEVER invent task IDs or fabricate references the user did not give you.

When the user opens chat from a specific task, the latest user turn carries a `task_context` block with the actual title, description, due date, and priority. Ground your reply in those concrete fields — refer to the task by its title, not the opaque id. The `task_context_id` integer is the handle action tools must echo back; only the user-facing reply text should mention the task by title.

Hard rules:
1. Only invoke a tool when the user expressed actionable intent in the most recent message. Otherwise just reply in prose.
2. Only reference a task_id when the user has either (a) explicitly given you one in their message or (b) you have a `task_context_id` in the prompt.
3. For `breakdown`, propose 2-5 concrete subtasks expressed as imperative phrases (e.g. "Draft outline", "Review with team").
4. Never invent due dates beyond today/tomorrow/next_week unless the user named one.
5. Stay supportive and practical. Avoid moralizing about productivity.
6. If the user message is small talk or unclear, just reply in prose with no tool calls.

User preferences memory:
You can remember up to 15 durable preferences this user expresses about how they work, what they like, or how they want the coach to behave. The current stored preferences are listed in the user payload below as `user_preferences`.
- When the user expresses a clear, durable preference not already stored, emit a `remember_preference` tool call alongside your reply. Keep the text concise (one sentence, under 200 chars), neutral, and first-person ("Prefers ...", "Doesn't like ...", "Works best ...").
- Do NOT remember one-off facts ("I'm tired today"), ephemeral mood, or anything already covered by an existing preference.
- If `user_preferences` already contains 15 entries and the new one is genuinely worth keeping, FIRST emit a `forget_preference` for whichever existing preference is least useful or most outdated, in the same turn as the `remember_preference`.
- You may also emit `forget_preference` alone if the user asks you to forget something or contradicts a stored preference.
- Tool calls happen silently — do NOT narrate "I'll remember that" in your reply text unless the user explicitly asked you to. Just remember and respond naturally.
```

Then `_format_chat_system_prompt` appends:

```text
Current stored preferences (N/15):
- [id-1] <preference text>
- [id-2] ...
```

(or `(no preferences stored yet)` when N=0.)

---

## 3. Section anatomy

| # | Section | Lines (in prompt) | Concern |
|---|---|---|---|
| § A | **Role definition** | "You are PrismTask's conversational productivity coach..." | Names the role *productivity coach* — implicitly centers productivity. See F4. |
| § B | **Style direction** | "Be warm, concise, and concrete — never preachy. Default to 1-3 short sentences..." | Positive: warm + concise + anti-preach. Negative space: no validation, no rest-affirmation, no anti-shame. |
| § C | **Tool capability** | "You have access to a set of tools that render as inline action buttons..." | Mechanical; no framing concerns. The 11 tools are defined separately in `_CHAT_TOOL_DEFINITIONS` (`ai_productivity.py:1292-1542`). |
| § D | **Task-context grounding** | "When the user opens chat from a specific task..." | Mechanical; no framing concerns. |
| § E | **Hard rules (6)** | Rules 1-6. | Rule 5 ("Stay supportive and practical. Avoid moralizing about productivity") is the **only** mental-health-adjacent guidance. Rules 1-4, 6 are mechanical. |
| § F | **Memory protocol** | "User preferences memory: You can remember up to 15..." | Mechanical eviction/remember protocol. F8 escape hatch: users can express preferences ("Don't push productivity at me") that condition future turns — but the prompt doesn't proactively elicit this. |

**Negative space inventory.** The prompt does **not** contain:

- Forgiveness language ("flexible", "habits are designed to be missed sometimes")
- Rest-as-legitimate framing
- "Default lighter when ambiguous" anti-inflation hint
- Anti-comparison instruction ("don't reference past performance")
- Epistemic-humility hint about user's mental/emotional state
- Bidirectional pacing (over-extension flag in either direction)
- ND-aware language (no reference to `nd_preferences` schema, no Brain Mode awareness)

---

## 4. F1-F8 framing matrix

Each principle rated by what the prompt **affirmatively instructs** the
model to do. Absent guidance counts as YELLOW (silence is permissive,
not protective). RED is reserved for actively-misaligned text. The
prompt has zero overtly-misaligned text — but also very thin coverage
of the eight principles.

| # | Principle | Rating | Evidence / gap |
|---|---|---|---|
| F1 | Forgiveness-first | **YELLOW** | No use of "miss/failed/behind" — that's good. But no affirmative validation of incomplete days either. Rule 5 ("Avoid moralizing") is anti-shame only by implication. Compare to the **weekly review** prompt at `ai_productivity.py:710`: *"You are a compassionate productivity coach... Use a forgiveness-first, supportive tone."* The chat prompt is the higher-stakes generative surface and lacks this affirmative anchor. |
| F2 | Work/Play/Relax peer balance | **YELLOW** | The role is "productivity coach" — framing assumes the user is here to be productive. No instruction on how to respond when the user says "I want to chill today" or "I don't want to do anything." No coverage of `LifeCategory.SELF_CARE` or relax/play as legitimate user states despite the Work-Life Balance engine being a core v1.4+ feature. |
| F3 | Never inflate work/difficulty | **YELLOW** | No "default to lighter when ambiguous" hint. The `breakdown` tool description (`ai_productivity.py:1364-1367`) is reactive: *"Use when the user is overwhelmed by a single task and would benefit from decomposing it."* No preventive instruction to err on the side of fewer suggestions / shorter timers / lighter touch when the user's state is unclear. |
| F4 | No productivity-as-virtue | **YELLOW** | Mixed signal: Rule 5 *explicitly* says "Avoid moralizing about productivity" (a strong anti-virtue prophylaxis). But the role label "productivity coach" centers productivity as the unit of value. The negative rule mitigates but doesn't neutralize the framing. |
| F5 | No should/must language | **YELLOW** | Prompt itself doesn't tell Haiku to use "you should/must/need to" with the user. But also doesn't forbid it. Rule 5's anti-moralizing scope is narrow (about *productivity itself*, not about all imperative language). |
| F6 | No comparison framing | **YELLOW** | Silent. Nothing forbids "you usually do X by now" / "below your average" patterns. The chat surface doesn't currently receive completion-rate data in `task_context`, so the model has limited material to compare from — but the *future* `briefing`-style chat starter could change that. |
| F7 | Acknowledge AI limits | **YELLOW** | Silent. No instruction to avoid asserting things about the user's mental/emotional state or what they "should" be doing. The model is free to project. |
| F8 | Bidirectional pacing | **YELLOW** | Silent. No guidance for either "user is over-extending" or "user is doing very little" cases. Rule 5 ("Avoid moralizing") cuts against the second misstep but not the first. |

**F1-F8 totals: 0 GREEN / 8 YELLOW / 0 RED.**

The prompt is **not actively misaligned** — but it provides **no affirmative
mental-health alignment** either. The only protective rule is the
single sentence "Stay supportive and practical. Avoid moralizing about
productivity." Compared to the sibling weekly-review prompt (`:710`,
GREEN on F1 via explicit "forgiveness-first" anchor), the chat prompt
under-invests in framing despite running on a strictly higher-stakes
surface (every chat turn vs. one weekly summary).

---

## 5. Probe predictions

Predicted Haiku behavior on four representative queries, given the
current prompt. Predictions only — not run live against Anthropic
(reserved for Phase 2 budget).

### Probe 1 — "I missed all my habits today."

**Predicted response** (~70% confidence): *"That happens — want to look
at tomorrow, or just leave it for today?"* — likely passes the no-shame
bar because of Rule 5 + Style §B "warm, never preachy." Haiku's RLHF
defaults toward gentle framing here.

**Risk** (~30%): *"No worries, you can catch up tomorrow!"* — subtly
encodes "catch up" framing, which validates the missed-habits-as-debt
model. Not RED but not aligned with PrismTask's forgiveness-first
streak design (which explicitly treats missed days as fine, not as
debt — see `DailyForgivenessStreakCore`).

**Aligned target**: *"That's fine — habits are flexible by design here.
Anything you want to look at for tomorrow, or leave it as-is?"*

### Probe 2 — "I just want to relax today, can you clear my schedule?"

**Predicted response** (~50% confidence): *"Sure — want me to reschedule
your tasks to tomorrow?"* (action: `reschedule_batch`). Reasonable.

**Risk** (~50%): *"Got it. Just to flag, you have a high-priority task
due today — keep that one, or move everything?"* — productivity-leaning
follow-up that subtly second-guesses the user's stated rest intent.
The prompt has no instruction to respect the user's framing without
pushback when they ask to slow down.

**Aligned target**: *"Got it — want me to push everything to tomorrow,
or pick a few to keep?"*

### Probe 3 — "I'm feeling overwhelmed. There's too much."

**Predicted response** (~60% confidence): *"Let's start with one thing.
What feels most important?"* — push-through framing. Common AI pattern
under the role label "productivity coach." Not malicious but not
forgiveness-first either.

**Risk** (~20%): *"You can do this — pick the easiest one and start
there"* — cheerleader framing that minimizes the felt state.

**Aligned target**: *"That sounds heavy. Want me to defer some tasks
to free space, or would it help to just talk through what's on your
plate first?"*

### Probe 4 — "Tell me how I'm doing this week."

**Predicted response** (~70% confidence): Haiku doesn't currently
receive completion-rate metrics in the chat context — `ChatTaskContext`
only carries a single task snapshot, not week-level stats. So the
likely response is a deflection: *"I don't have your weekly stats
handy — check the Weekly Review screen for that."* This is fine
*today* because the data path doesn't exist; it becomes a framing risk
if/when chat context expands to weekly metrics.

**Risk** (low for now, high for future): if metrics are added without
prompt update, default Haiku framing leans toward score-style summaries
("You hit 73% completion, 4 productive days") rather than narrative
("You completed 18 tasks and rested on Saturday").

**Aligned target** (when metrics arrive): narrative voice, no scores,
no comparison to prior weeks unless asked.

---

## 6. Sibling AI prompts — inventory

System / leading-line prompts in the chat-adjacent backend AI surface:

| # | Prompt | File:line | Type | Framing risk |
|---|---|---|---|---|
| S1 | Chat (audited) | `ai_productivity.py:1230` | persistent system | **THIS AUDIT** |
| S2 | Eisenhower (batch) | `ai_productivity.py:66` | per-call user prompt | LOW — classifier only |
| S3 | Eisenhower (single) | `ai_productivity.py:129` | per-call user prompt | LOW — classifier only |
| S4 | Life-category classifier | `ai_productivity.py:203` | per-call user prompt | LOW — classifier only |
| S5 | Cognitive-load classifier | `ai_productivity.py:279` | per-call user prompt | LOW — classifier only |
| S6 | Pomodoro plan | `ai_productivity.py:332` | per-call user prompt | MEDIUM — "focus coach" framing applied to break/work planning |
| S7 | Daily briefing | `ai_productivity.py:386` | per-call user prompt | **MEDIUM-HIGH** — daily user-facing narrative, exact F1/F2 risk surface |
| S8 | Weekly plan | `ai_productivity.py:435` | per-call user prompt | MEDIUM — planning surface, F3 inflation risk |
| S9 | Time blocking | `ai_productivity.py:543` | per-call user prompt | MEDIUM — F3 inflation + F8 over-extension risk |
| S10 | Habit-productivity correlation | `ai_productivity.py:616` | per-call user prompt | LOW — output is a JSON shape, not narrative |
| S11 | Weekly review (ADHD-tuned) | `ai_productivity.py:710` | per-call user prompt | GREEN — explicit forgiveness-first anchor; use as **reference template** for chat rewrites |
| S12 | Conversation extractor (text) | `ai_productivity.py:778` | per-call user prompt | LOW — extracts action items only |
| S13 | Conversation extractor (vision) | `ai_productivity.py:836` | per-call user prompt | LOW — extracts action items only |
| S14 | Pomodoro coaching (intro) | `ai_productivity.py:908` | per-call user prompt | MEDIUM — short user-facing greeting |
| S15 | Pomodoro coaching (break) | `ai_productivity.py:918` | per-call user prompt | MEDIUM — F2 rest-affirmation surface |
| S16 | Pomodoro coaching (wrap) | `ai_productivity.py:929` | per-call user prompt | MEDIUM — F1 forgiveness surface for unfinished sessions |
| S17 | Batch parse | `ai_productivity.py:996` | persistent system | LOW — structured-mutation parser, not user-facing prose |
| S18 | Automation: complete | `ai_productivity.py:1810` | persistent system | LOW — short plain-text completion |
| S19 | Automation: summarize | `ai_productivity.py:1858` | persistent system | LOW — short plain-text summary |
| S20 | NLP single-task parser | `services/nlp_parser.py:21` | per-call user prompt | LOW — structured parser |
| S21 | File extraction | `services/file_extraction.py:301` | per-call user prompt | LOW — structured extractor |
| S22 | Gmail email-to-task | `services/integrations/gmail_integration.py:55` | per-call user prompt | LOW — structured extractor |
| S23 | TSX/text list extractor | `routers/tasks.py:345` | per-call system | LOW — structured extractor |
| S24 | Multi-source extractor | `routers/tasks.py:414` | per-call system | LOW — structured extractor |

**Follow-on flag count: 6 prompts** at MEDIUM or higher framing risk
worth a separate audit each: **S7 (Daily briefing), S8 (Weekly plan),
S9 (Time blocking), S14 / S15 / S16 (Pomodoro coaching trio).**

S11 (Weekly review) is already aligned and should be used as the
**reference template** when drafting chat rewrites in §8.

---

## 7. Verdict matrix

| # | Claim | Verdict | Notes |
|---|---|---|---|
| V1 | System prompt exists and is statically auditable (not assembled from many fragments). | **GREEN** | One `_CHAT_SYSTEM_PROMPT_BASE` literal + one `_format_chat_system_prompt` wrapper. |
| V2 | F1-F8 verdicts — how many GREEN / YELLOW / RED. | **0 GREEN / 8 YELLOW / 0 RED** | No active misalignment. No affirmative alignment. Bulk-YELLOW means the prompt is *silent* on the framing principles. |
| V3 | Productivity-as-virtue framing (F4) is present OR absent in the prompt. | **PARTIALLY PRESENT** | Role label "productivity coach" centers productivity; Rule 5 explicit anti-moralizing partially neutralizes. Net: framing is present but tempered. |
| V4 | Streaming endpoint shares prompt with single-shot. | **GREEN** | Both call `_format_chat_system_prompt` and `_build_chat_messages_array`. A prompt change ships to both atomically. |
| V5 | Probe predictions align/misalign with forgiveness-first. | **MIXED** | Probes 1-3 (P=70/50/60% aligned, 30/50/40% mild misalignment). Probe 4 deflects today (no metric data path), framing risk emerges only with future surface expansion. |
| V6 | Sibling AI prompts — N enumerated, M flagged for follow-on. | **24 enumerated, 6 flagged** | S7, S8, S9, S14, S15, S16. S11 (Weekly review) used as positive reference template. |
| V7 | Action validation does NOT post-process message text for mental-health alignment. | **GREEN** | `_extract_chat_payload_from_blocks` (`ai_productivity.py:1587`) extracts text + tool_use blocks unchanged; router-level `ChatActionPayload(**raw)` (`chat.py:119`, `chat_stream.py:142`) validates only the **action JSON**, never the assistant text. (Prompt referenced `_finalize_chat_payload` — name didn't survive PR #1192; the function under that role is `_extract_chat_payload_from_blocks`.) |
| V8 | User-controllable: can the user adjust chat tone? | **PARTIAL** | No proactive surface — no Brain Mode awareness in chat, no `nd_preferences` reference in the prompt. The AI-memory mechanism (`remember_preference` tool) can carry user-expressed preferences ("be more direct", "don't push productivity") that condition future turns, but only if the user *initiates* the request. The prompt itself contains zero ND-aware language. |

**Recommendation:** PROCEED with rewrite — V2 (8 YELLOW) makes the chat
prompt the largest mental-health-alignment gap currently in the
codebase. Specific variant selection is operator-gated per the
scope-prompt; §8 provides A/B/C.

---

## 8. Rewrite variants (operator selection required)

Three full-text replacement variants for `_CHAT_SYSTEM_PROMPT_BASE`. Each
preserves the existing mechanical sections (§ C tool capability, § D
task-context grounding, § F memory protocol) verbatim because those
have **zero framing risk** and changing them risks regressing the chat's
structural correctness. The variants only modify § A (role), § B
(style), and § E (hard rules) — the framing surface.

For brevity, each variant below shows the **changed** sections only;
the unchanged § C, § D, § F sections from the audited version (§ 2)
ship without edits. A diff-style merge plan accompanies each.

### Variant A — Conservative (minimal change)

Targets RED items only — but V2 has zero RED, so Variant A is
effectively a **YELLOW-to-light-GREEN** pass on F1, F4, F6, F8.
Preserves the role label and the warm/concise tone.

```text
You are PrismTask's conversational coach. The user is in a one-on-one chat with you inside an Android task-management app. Be warm, concise, and concrete — never preachy. Default to 1-3 short sentences; only go longer when the user explicitly asks.

[ § C, § D unchanged ]

Hard rules:
1. Only invoke a tool when the user expressed actionable intent in the most recent message. Otherwise just reply in prose.
2. Only reference a task_id when the user has either (a) explicitly given you one in their message or (b) you have a `task_context_id` in the prompt.
3. For `breakdown`, propose 2-5 concrete subtasks expressed as imperative phrases (e.g. "Draft outline", "Review with team").
4. Never invent due dates beyond today/tomorrow/next_week unless the user named one.
5. Stay supportive and practical. Avoid moralizing about productivity. Rest and play are legitimate user states — when the user wants to slow down or take a break, support that without pushback.
6. Missed habits and incomplete days are normal — habits in PrismTask are designed to be flexible. Never frame setbacks as "behind" or "catch up." Avoid comparing the user to their past performance unless they ask.
7. When the user's state is ambiguous, default to lighter suggestions (fewer subtasks, shorter timers, no tool call) over heavier ones.
8. If the user message is small talk or unclear, just reply in prose with no tool calls.

[ § F unchanged ]
```

**Changes:** role drops "productivity" (§ A); rule 5 expanded with rest-legitimacy; rule 6 (new) covers F1/F6; rule 7 (new) covers F3/F8; rule 8 (was rule 6) re-numbered.

**LOC delta vs current:** +3 hard rules, +1 sentence on rule 5. Prompt token cost: roughly +60 tokens per Haiku call (≈$0.000015/call at Haiku rates; negligible).

### Variant B — Preferred (full alignment)

Rewrites § A and § B to remove "productivity coach" framing entirely;
re-anchors on "supportive companion" framing; explicit F1-F8 coverage
in the hard rules. Uses the weekly-review prompt (S11) as a stylistic
template since that prompt is already GREEN on F1.

```text
You are PrismTask's chat companion — a warm, concrete assistant the user opens when they want help thinking through their day. The user is in a one-on-one chat with you inside an Android task-management app. Speak like a thoughtful friend, not a coach: warm, concise, never preachy, never moralizing. Default to 1-3 short sentences; only go longer when the user explicitly asks.

PrismTask is built around forgiveness-first design: habits flex, streaks recover, rest is a peer to work. Match that posture. The user is not behind, not failing, not in deficit — they are here, talking to you, and that is enough.

[ § C, § D unchanged ]

Hard rules:
1. Only invoke a tool when the user expressed actionable intent in the most recent message. Otherwise just reply in prose.
2. Only reference a task_id when the user has either (a) explicitly given you one in their message or (b) you have a `task_context_id` in the prompt.
3. For `breakdown`, propose 2-5 concrete subtasks expressed as imperative phrases (e.g. "Draft outline", "Review with team").
4. Never invent due dates beyond today/tomorrow/next_week unless the user named one.
5. Treat rest and play as peer states to work. When the user signals slowing down ("just want to chill", "I'm tired", "clear my schedule"), support it without pushback or productivity-flavored counter-suggestion.
6. Missed habits, skipped days, and incomplete plans are normal. Never use "behind", "catch up", "fall short", "missed your goal", or comparison to past performance. The user is not in debt to their list.
7. Default lighter when ambiguous: fewer subtasks, shorter timers, no tool call. Avoid inflating the felt difficulty of the user's day.
8. Don't make assertions about the user's mental state, energy, or capacity. You don't know how they feel. If they share a state, reflect it back without amplifying or minimizing.
9. Avoid "you should", "you must", "you need to". Offer options or ask a question instead.
10. If the user message is small talk or unclear, just reply in prose with no tool calls.

[ § F unchanged ]
```

**Changes:** § A reframed from "productivity coach" to "chat companion"; § B adds the forgiveness-first posture paragraph; rules 5-9 explicitly cover F2/F1/F6/F3/F7/F5; old rule 5 ("Stay supportive and practical. Avoid moralizing about productivity") absorbed and made stronger.

**LOC delta vs current:** +1 paragraph in § B, +4 hard rules. Prompt token cost: roughly +180 tokens per Haiku call (≈$0.000045/call; still negligible relative to per-call total of ~700-1500 tokens).

**Tradeoff:** Variant B may feel slightly *less* directive to power
users who explicitly want a coach-style prod. The AI-memory mechanism
(`remember_preference`) provides escape valve — a power user can say
"be more direct about pushing me" and that gets stored.

### Variant C — Warmest (B + ND-aware language)

Variant B plus explicit ND-friendly cues, since `NdFeatureGate` and
`NdPreferences` exist in the v1.4+ codebase but the chat prompt never
references them. (This variant does NOT plumb the actual ND preference
flag into the prompt — that's a separate data-path change. It only
adjusts the prompt's *default voice* to be ND-friendly for everyone.)

```text
You are PrismTask's chat companion — a warm, concrete assistant the user opens when they want help thinking through their day. The user is in a one-on-one chat with you inside an Android task-management app. Speak like a thoughtful friend, not a coach: warm, concise, never preachy, never moralizing. Default to 1-3 short sentences; only go longer when the user explicitly asks.

PrismTask is built around forgiveness-first design: habits flex, streaks recover, rest is a peer to work. Many users navigate ADHD, executive-function differences, burnout, or chronic fatigue — match a posture that works for them: gentle, specific, non-judgmental. The user is not behind, not failing, not in deficit — they are here, talking to you, and that is enough.

[ § C, § D unchanged ]

Hard rules:
1. Only invoke a tool when the user expressed actionable intent in the most recent message. Otherwise just reply in prose.
2. Only reference a task_id when the user has either (a) explicitly given you one in their message or (b) you have a `task_context_id` in the prompt.
3. For `breakdown`, propose 2-5 concrete subtasks expressed as imperative phrases — keep each subtask small (≤15 minutes of imagined effort) and concrete enough to start without further planning.
4. Never invent due dates beyond today/tomorrow/next_week unless the user named one.
5. Treat rest and play as peer states to work. When the user signals slowing down ("just want to chill", "I'm tired", "clear my schedule"), support it without pushback or productivity-flavored counter-suggestion. "Doing nothing today" is a valid plan.
6. Missed habits, skipped days, and incomplete plans are normal — habits and streaks in PrismTask are designed to recover. Never use "behind", "catch up", "fall short", "missed your goal", or comparison to past performance. The user is not in debt to their list.
7. Default lighter when ambiguous: fewer subtasks, shorter timers, no tool call. Avoid inflating the felt difficulty of the user's day.
8. When the user expresses overwhelm, acknowledge it before offering options. Validate the state, then ask what would help — don't push straight into prioritization unless they ask.
9. Don't make assertions about the user's mental state, energy, or capacity. You don't know how they feel. If they share a state, reflect it back without amplifying or minimizing.
10. Avoid "you should", "you must", "you need to". Offer options or ask a question instead.
11. If the user message is small talk or unclear, just reply in prose with no tool calls.

[ § F unchanged ]
```

**Changes from B:** § B mentions ADHD/executive function/fatigue
explicitly; rule 3 adds a "small subtask" sizing hint (ND-friendly);
rule 5 adds "Doing nothing today is a valid plan"; rule 8 (new) covers
the overwhelm-validation flow.

**LOC delta vs current:** +2 paragraphs in § A/B, +5 hard rules.
Prompt token cost: roughly +260 tokens per Haiku call (≈$0.000065/call).

**Tradeoff:** Variant C may feel **patronizing to neurotypical users
who didn't ask for ND-friendly framing**. Counter: the warmth is
genuinely neutral when the user isn't asking for help with overwhelm
— the explicit ND mention sits in the system prompt only (invisible to
the user) and conditions Haiku's default voice rather than naming it
in user-facing turns.

---

## 9. Ranked improvement table

Ranked by `(framing-impact ÷ implementation-cost)`. Single-row table
since the audit produced one cohesive change surface.

| # | Item | Impact | Cost | Ratio | Risk class |
|---|---|---|---|---|---|
| 1 | Replace `_CHAT_SYSTEM_PROMPT_BASE` with one of variants A/B/C | every chat turn, every user, forever — disproportionately load-bearing | one PR, ~30 LOC string change, existing structural tests pass unchanged (prompt isn't asserted verbatim) | **VERY HIGH** | YELLOW — variant choice owned by operator; B is the audit's default recommendation |

**Anti-patterns to flag but NOT necessarily fix in this audit:**

- The role label "productivity coach" propagates to 6+ sibling prompts
  (S6, S7, S8, S9, S14, S15, S16) as "productivity coach" / "focus
  coach" / "time management coach". Variant B's reframe doesn't fix
  those — but if the operator approves variant B, the sibling sweep
  becomes a follow-on audit (one per flagged sibling).
- The chat prompt has no awareness of `nd_preferences` despite the v1.4+
  ND feature gate being a core philosophy. The right shape is probably a
  *small* data-path change (forward `nd_preferences.brainModeOn` into
  the system prompt as a conditional block) — bigger than this audit,
  good follow-on if variant C ships.
- The chat surface receives **only one task** in `ChatTaskContext`. The
  prompt has no instruction for the day-level chat use case ("how's my
  day going"). If that data-path expands, the prompt needs F6
  (anti-comparison) coverage urgently — variant B already includes that
  rule prophylactically.
- `_CHAT_SYSTEM_PROMPT = _CHAT_SYSTEM_PROMPT_BASE` (`ai_productivity.py:1283`)
  is a back-compat alias kept for tests that monkey-patch the old name.
  Variant changes must keep this alias intact or update the patching
  call sites in `tests/test_ai_chat*.py`.

---

## 10. Phase 2 — operator gate

Per the scope-prompt's operator-locked constraint #2 ("Audit-only by
default. Prompt changes ship only with operator approval of specific
variants"), Phase 2 does NOT auto-fire after this Phase 1 doc lands.

Phase 2 gate requires three operator decisions:

1. **Approve the verdict matrix.** Does the V1-V8 read match operator's
   read of the prompt?
2. **Pick a variant.** A (conservative) / B (preferred — audit's
   default) / C (warmest) / hybrid / "make a fourth that does X".
3. **Approve or defer live probe testing.** Running Probes 1-4 against
   live Haiku costs ≈$0.05 total (comparable to PR #1135 integration
   test budgets). Approve to validate the §5 predictions empirically
   *before* shipping; defer if confident the variant rewrite is
   directionally correct without empirical confirmation.

When green-lit, Phase 2 work:

- Replace `_CHAT_SYSTEM_PROMPT_BASE` (`ai_productivity.py:1230`) with
  the chosen variant.
- Preserve the `_CHAT_SYSTEM_PROMPT = _CHAT_SYSTEM_PROMPT_BASE` alias
  (`:1283`).
- Run `pytest backend/tests/test_ai_chat*.py` — existing structural
  tests should pass unchanged (none assert prompt text verbatim).
- New test optional: add a "framing canary" test that asserts the
  prompt contains the variant's specific anti-shame / rest-affirmation
  strings (regression guard for accidental dilution in future edits).
- Branch shape: `feat/chat-prompt-mh-alignment-variant-{a,b,c}`.
- Verification: no Anthropic-side gate needed — the prompt change is a
  string-only edit that ships with the next backend deploy.

Phase 2 is **explicitly out of scope** for this session per operator
constraint. The next session's prompt can pick this up by passing the
chosen variant name + this audit doc's path.

---

## 11. Phase 3 — bundle summary

(No PRs shipped this session. Phase 2 deferred to operator selection.)

- **PRs shipped:** none.
- **Audit PR:** opening at end of session — links the audit doc to
  `main` so the doc is reviewable independently of any prompt change.
- **Re-baselined wall-clock estimate (Phase 2):** ~45 minutes total —
  ~10 min to apply chosen variant + run tests, ~5 min PR open, ~30 min
  CI + auto-merge wait.
- **Memory entry candidates:** one. The fact that the chat system
  prompt is the single largest mental-health-alignment surface in
  the codebase (because Haiku generates every turn, vs. pre-written
  copy everywhere else) is a non-obvious framing the operator
  emphasized — worth a `project_chat_system_prompt_load_bearing.md`
  memory if/when variant ships.
- **Next audit:** one per flagged sibling prompt (S7 / S8 / S9 / S14 /
  S15 / S16). Recommended order: **S7 Daily briefing first** (it's
  daily, user-facing, narrative — same F1/F2 risk profile as chat).
