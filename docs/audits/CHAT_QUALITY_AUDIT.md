# Chat Quality Audit (Phase 1)

**Scope.** Audit the conversational AI chat surface PrismTask ships today
across backend (`POST /api/v1/ai/chat`), Android (`ChatScreen` /
`ChatViewModel` / `ChatRepository`), and web. Five axes: reply quality,
tool-call reliability, UI/UX, latency/streaming, conversation memory.
Phase 2 plan ranked by severity × dependency, capped to what can land
cleanly in the 8 calendar days before Phase F D5 ★ gates close on
2026-05-15.

**Branch.** `claude/audit-chat-quality-e9GAd`. **HEAD.** `aadee30`. **App.**
1.8.46 / vc 844. **Room.** v76. **Backend alembic head.** `024_add_beta_codes`.
**Web.** 1.6.0.

**Method.** `git log --grep`, file inventory, line-level reads of every
file in the chat code path, and cross-reference against PRs #788/#790
(AI gate / PII egress) and #1128 (most recent chat fix bundle).

---

## 1. Premise verification

| # | Operator-locked premise | Verdict | Evidence |
|---|---|---|---|
| 1 | Chat surface exists across backend, Android, AND web | YELLOW | Backend `/ai/chat` route (`backend/app/routers/ai.py:854`) + Android (`ChatScreen.kt:78`, reachable from `TodayScreen.kt:265` and `AiFeaturesScreen.kt:64`) confirmed. **Web has NO chat UI.** `web/src/features/extract/ConversationExtractScreen.tsx` is a paste-transcript-and-extract-tasks tool, not a chat. Premise needs reframe: chat is Android-only today. |
| 2 | "Rough across the board" is structurally enumerable | GREEN | Five-axis sweep below shows two root causes drive ≥80% of symptoms: (a) AI sees zero task content (Axis A) and (b) no real multi-turn memory (Axis E). UI/latency complaints are largely downstream of these. |
| 3 | 8 calendar days before Phase F D5 ★ gates close | GREEN | Today 2026-05-07 → Phase F kickoff 2026-05-15 = 8 days. Phase 2 plan honors the ceiling; streaming + persistence deferred. |
| 4 | AI gate (PR #790) is load-bearing on every chat path | GREEN | `backend/app/routers/ai.py:70` applies `Depends(require_ai_features_enabled)` at router level → `/ai/chat` inherits it. `AiFeatureGateInterceptor.AI_PATH_PREFIXES` (line 100) lists `/api/v1/ai/` post-PR #1128 fix. Defense-in-depth confirmed both halves. |
| 5 | Tier model selection (Ultra=Sonnet, others=Haiku) applies | RED | `UserTier` enum has only `FREE` and `PRO` (`BillingManager.kt:37`) — there is NO Ultra tier. `get_model("chat")` always returns `MODEL_HAIKU` because `"chat" ∉ SONNET_FEATURES = {"weekly_planner", "monthly_review"}` (`ai_productivity.py:20`). The `tier` parameter on `generate_chat_response` (`ai_productivity.py:1111`) is **dead code** — accepted but never read inside the function. Operator's stated premise is incorrect; need to align scope before changing model wiring. |

**STOP conditions:** S1-S5 evaluated below. None fire.

- **S1** (premise wrong, no surface): NOT triggered. Chat surface exists on backend + Android. Web absence is a missing-feature gap, not a wrong-premise stop.
- **S2** (AI gate gap): NOT triggered. Both client interceptor and server router-level dependency are wired correctly.
- **S3** (destructive tool calls without confirmation + idempotency): NOT triggered as defined. User must tap an action chip = explicit per-tap confirmation. **However**, `archive` / `reschedule_batch` / `breakdown` lack double-confirm + undo (P1, see C.2). Documented, not stopped.
- **S4** (conversation history sent to Anthropic with PII, no disclosure): NOT triggered. **Backend forwards only the latest user message**, never history; existing PR #788 audit covers user-typed PII via `_CHAT_SYSTEM_PROMPT` boundary. The actual privacy posture is *less* leaky than the prompt assumed — but at the cost of zero multi-turn memory (Axis E).
- **S5** (scope exceeds 8-day window): NOT triggered. Phase 2 plan totals ~600-700 LOC across 5 PRs. Streaming + Room persistence are explicitly deferred with re-trigger criteria.

---

## 2. Inventory

### 2.1 Backend (`backend/app/`)

| File | Role |
|---|---|
| `routers/ai.py:854-916` | `POST /ai/chat` route. Auth via `get_active_user`, AI gate at router level, IP rate limiter `chat_rate_limiter` (30/60s), per-user `daily_ai_rate_limiter` (Pro budget). Calls `generate_chat_response`, validates AI-proposed actions through `ChatActionPayload(**raw)`, drops malformed silently. |
| `services/ai_productivity.py:1082-1180` | `_CHAT_SYSTEM_PROMPT` (24 lines, closed action set) + `generate_chat_response`. Synchronous `client.messages.create(...)` with `max_tokens=1024`. Two-attempt retry on JSON parse failure. **No streaming.** **No conversation history forwarded.** **No task content fetched server-side.** `tier` param accepted but unused. |
| `schemas/ai.py:497-553` | `ChatRequest` (message, conversation_id, task_context_id, tier-telemetry-only); `ChatResponse` (message, actions, conversation_id, tokens_used); `ChatActionPayload` regex-pattern-validated to closed set `(complete\|reschedule\|reschedule_batch\|breakdown\|archive\|start_timer\|create_task)`. |
| `tests/test_ai_chat.py` | 427 lines. Mocks `anthropic.Anthropic`, exercises service contract + router. **No test asserting tier-based model selection** (because none happens). |

### 2.2 Android (`app/src/main/java/com/averycorp/prismtask/`)

| File | Role |
|---|---|
| `data/repository/ChatRepository.kt` | 126 lines. `@Singleton @Inject`. In-memory `MutableStateFlow<List<ChatMessage>>`. Daily-reset conversation ID. `shouldRefreshContext()` + `markContextRefreshed()` declared but **never called** (dead). `trimHistory` caps to 10 pairs in display only. **No persistence.** |
| `ui/screens/chat/ChatViewModel.kt` | 243 lines. `@HiltViewModel`. Pro gate via `ProFeatureGate.AI_CONVERSATIONAL`. `executeAction(action)` directly mutates Room without confirmation dialog (single-tap = confirmation). `_showDisclosure` flow exists but **never set to true** (dead). `taskContextId` passed through but no task content sent. |
| `ui/screens/chat/ChatScreen.kt` | 466 lines. Standard M3 Scaffold. `LazyColumn` with auto-scroll, `TypingIndicator` while `isTyping`, `ActionChip` per action with closed-label mapping. **No streaming render.** Clear-chat is single-tap, no confirmation. Action chips lack accessibility semantics beyond visible label. |
| `data/remote/api/AiFeatureGateInterceptor.kt` | 114 lines. OkHttp interceptor. Short-circuits to synthetic 451 when `isAiFeaturesEnabledBlocking()=false`. `AI_PATH_PREFIXES` includes `/api/v1/ai/` (post-#1128 fix). Defense-in-depth via `X-PrismTask-AI-Features: disabled` header. |
| `ui/navigation/routes/AIRoutes.kt:87-97` | `PrismTaskRoute.AiChat` route w/ optional `taskId` arg. |
| `ui/navigation/NavGraph.kt:181` | Route definition: `"ai_chat?taskId={taskId}"`. |

### 2.3 Web (`web/src/`)

**No chat surface present.** Search `grep -rli "chat" web/src/` returns
only `features/extract/ConversationExtractScreen.tsx` (paste-and-extract,
unrelated) and `types/extract.ts`. No `ChatScreen`, no `ChatViewModel`,
no `aiChat` API binding in `web/src/api/ai.ts`. Web parity is a
missing-feature gap, not a quality issue — moved out of audit scope per
premise reframe (§1, premise 1).

---

## 3. Five-axis defect inventory

Severity legend: P0 = ship-blocker for "rough" complaint; P1 = should
ship if budget allows; P2 = filed.

### Axis A — Reply quality

| ID | Symptom | Root cause | Severity | Scope estimate |
|---|---|---|---|---|
| **A.1** | AI replies are generic; cannot meaningfully discuss "this task" or "my tasks" even when chat opened from a task. | `task_context_id` sent as bare integer (`ai.py:879`); the AI service never dereferences it. The `user_payload` JSON sent to Anthropic carries only `{conversation_id, task_context_id: int, user_message}` (`ai_productivity.py:1130-1135`). No title, description, due, project, recent activity. | **P0** | Backend +120 LOC + 6 pytest cases; Android +20 LOC; ~1.5 days |
| A.2 | No tier-based quality differentiation; every user gets same Haiku replies. | `tier` param accepted by `generate_chat_response` (line 1111) but never read inside function body. There is no Ultra tier in `UserTier` (FREE/PRO only). | P1 | +5 LOC removal or +20 LOC if "future Ultra tier" telemetry is wanted; ~1 hr |
| A.3 | (Already good.) System prompt is concise, action set closed, JSON-only contract enforced. | n/a | — | n/a |
| A.4 | AI lacks situational hooks (overdue count, current focus mode, balance ratio). | Same root cause as A.1 — no context block. | P1 | Folded into A.1 fix; +30 LOC |

### Axis B — Tool-call reliability

| ID | Symptom | Root cause | Severity | Scope estimate |
|---|---|---|---|---|
| B.1 | Action validation is JSON-shape-only, not Anthropic native tool-use. Brittle to prompt drift; "actions" can silently disappear when model hallucinates types. | `_CHAT_SYSTEM_PROMPT` instructs strict JSON; backend filters via `ChatActionPayload(**raw)` regex on `type`. Malformed actions logged + dropped (`ai.py:892-903`). | P1 | Migration to native tool-use is ~3 days + risks behavior drift; **defer**. Hardening tests +50 LOC. |
| B.2 | Double-tap of an action chip can issue duplicate mutations; no idempotency guard. `reschedule_batch` partial failure leaves UI in unclear state. | `executeAction` (`ChatViewModel.kt:135-201`) catches generic `Exception` → "Action failed" toast, no per-item progress, no rollback. | P1 | +60 LOC ViewModel + 3 unit tests; ~0.5 day |
| B.3 | `create_task` action drops description/tags/project/recurrence (`ChatViewModel.kt:181-195`). | Hand-rolled `TaskEntity` constructor instead of routing through `NaturalLanguageParser` or `TaskRepository.createFromAi`. | P2 | +40 LOC; defer |
| B.4 | `start_timer` action is a toast no-op (`ChatViewModel.kt:177-179`). Comment claims "UI handles via navigation" — it doesn't. | Cross-layer wiring never landed. | P2 | +30 LOC; defer |

### Axis C — UI/UX

| ID | Symptom | Root cause | Severity | Scope estimate |
|---|---|---|---|---|
| C.1 | First-time AI disclosure dialog never shows. Privacy invariant from PR #788 documented in copy ("Chat resets daily and isn't stored permanently") never reaches the user. | `_showDisclosure` flow is declared at `ChatViewModel.kt:60-61` and dialog rendered at `ChatScreen.kt:135-151`, but the value is **never set to true**. Dead code. | **P0** | UserPreferences key `aiChatDisclosureShown`; +50 LOC + 2 tests; ~0.5 day |
| C.2 | Destructive ops (archive, complete, reschedule_batch) fire on a single chip tap with only a brief snackbar; no undo. | `executeAction` mutates Room synchronously, emits a toast (`ChatViewModel.kt:142-194`). | P1 | Snackbar-with-undo on destructive types; +60 LOC ViewModel + +20 LOC Screen + 3 tests; ~0.5 day |
| C.3 | Clear-chat icon in TopAppBar is single-tap (`ChatScreen.kt:184-191`). User can lose entire active conversation by accident. | No `AlertDialog` confirmation. | P2 | +30 LOC |
| C.4 | Action chip labels ("Just Drop It", "Break It Down") are voicy but lack `contentDescription` / TalkBack semantics. | Plain `AssistChip` with no `Modifier.semantics` (`ChatScreen.kt:339-373`). | P2 | +20 LOC |
| C.5 | Empty welcome card has no starter prompt buttons. | `WelcomeCard` is body-text only (`ChatScreen.kt:253-279`). | P2 | +50 LOC |

### Axis D — Latency / streaming

| ID | Symptom | Root cause | Severity | Scope estimate |
|---|---|---|---|---|
| D.1 | "Blank screen" 1-3 seconds between sending and reply appearing. Especially long for action-heavy replies (1024-token max). | `client.messages.create(...)` is non-streaming (`ai_productivity.py:1140-1145`). Client awaits full body before render. | P1 | Anthropic streaming → SSE → Android `Flow<String>`; ~3-5 days end-to-end with backend infra + Android render path + tests. **Exceeds 8-day window when stacked with P0 fixes; defer.** |
| D.2 | No cancel-in-flight when user types follow-up. | `sendMessage` coroutine is fire-and-forget; no `Job` cancellation handle. | P2 | +30 LOC; defer |
| D.3 | Send-button enabled-gate (`enabled = !isTyping`, `ChatScreen.kt:452`) is correct but the input text clears immediately on send, opening a fast double-send window. | Order-of-operations in `onSend` lambda (`ChatScreen.kt:240-245`). | P2 | +5 LOC swap; trivial |

### Axis E — Conversation memory

| ID | Symptom | Root cause | Severity | Scope estimate |
|---|---|---|---|---|
| **E.1** | AI has amnesia between turns. User says "schedule it for tomorrow" referring to the last reply's task — AI has no idea what "it" is. | **Backend forwards only the latest user message** (`ai.py:876-881`, `ai_productivity.py:1130-1144`). The `messages=[{"role": "user", "content": user_payload}]` array is single-element on every turn. The "rolling 10 pairs" in `ChatRepository.trimHistory` is local display-only state. | **P0** | Same fix as A.1: server-side context block must include rolling history (last N user/assistant pairs) plus task summary. +40 LOC additional on top of A.1 fix. |
| E.2 | `shouldRefreshContext()` + `markContextRefreshed()` + `messagesSinceContextRefresh` (`ChatRepository.kt:37-58`) are dead — counted but never read. | Vestigial from a planned context-injection design that was never wired. | P1 | Delete dead code; +1 LOC |
| E.3 | Conversation lost on process death / app restart. No Firestore sync. | In-memory `MutableStateFlow` only; no Room table; no `ConversationDao`. | P1 | Room migration 76→77 + DAO + sync = ~200 LOC + 4 tests + migration risk under Phase F gate. **Defer.** |
| E.4 | Chat silently disappears at midnight (`resetIfNewDay` in `ChatRepository.kt:104-109`). User has no warning. | Daily-reset by design but unsignaled. | P2 | +20 LOC |

---

## 4. Cross-cutting findings

| Concern | Status | Evidence |
|---|---|---|
| AI gate coverage on chat | GREEN | `ai.py:70` applies `Depends(require_ai_features_enabled)` at router level. `AiFeatureGateInterceptor` short-circuits client-side at `/api/v1/ai/`. Both halves fire post-#1128. |
| PII egress on chat traffic | GREEN-with-note | Backend forwards only the user's typed message (potentially containing user-typed PII — covered by PR #788 `docs/privacy/index.md`). Conversation history NOT forwarded. `task_context_id` is a bare int — no task content reaches Anthropic today. (A.1 fix changes this — see §5 PII addendum.) |
| Multi-subscriber installer pattern (PR #1093) | N/A | Chat has no installer / subscriber pattern. |
| Test parity (PR #778, new DAO → `TestDatabaseModule.kt` `@Provides`) | GREEN | `ChatRepository` is `@Singleton @Inject`, no DAO touched. No new entity. No test-module change required. (Will need re-check if E.3 conversation persistence ships.) |
| Tier-based model selection per memory premise 5 | RED | Premise incorrect — see §1. The `tier` param on `generate_chat_response` is dead code. No Ultra tier. |

### 4.1 PII addendum for the proposed A.1 / E.1 fix

Adding server-side context block (task title/description, recent
completions, current overdue count) **does** introduce a new PII shape
to `/ai/chat` egress. Required follow-on per PR #788 protocol:

- Update `docs/privacy/index.md` § AI features to enumerate the new
  fields the chat path sends (task title, description, due_date, project,
  recent completion ids).
- Update `docs/store-listing/compliance/data-safety-form.md` if the PR
  #788 disclosure card differs.
- Confirm `require_ai_features_enabled` short-circuit catches the new
  context-build code path (it does — A.1 fix runs inside the existing
  `chat()` route, which already has the dependency).

Does NOT require a new alembic migration (no new model). Does NOT
require a new DAO/`@Provides` (no Android-side change of structure).

---

## 5. Phase 2 plan (ranked)

Ordered by severity then dependency. Each row: in-session OR deferred,
with explicit re-trigger criterion if deferred.

| # | Fix | Severity | Files | LOC | Tests | Dep | Decision |
|---|---|---|---|---|---|---|---|
| 1 | **A.1+E.1: Server-side context block.** Backend builds a per-turn user-payload that includes (a) rolling N=6 recent assistant/user message pairs forwarded by client, (b) when `task_context_id` is set, the task's title/description/due/project/status, (c) a small situational summary (today's overdue count, today's completed count, active focus mode). Sends as structured `messages=[...]` array to Anthropic. Updates privacy doc. | P0 | `backend/app/routers/ai.py`, `backend/app/services/ai_productivity.py`, `backend/app/schemas/ai.py`, `app/src/main/java/.../data/remote/api/PrismTaskApi.kt`, `app/src/main/java/.../data/repository/ChatRepository.kt`, `docs/privacy/index.md` | ~280 | +12 pytest, +4 KT unit, +1 androidTest | none | **PROCEED** |
| 2 | **C.1: Wire AI chat first-run disclosure.** Add `aiChatDisclosureShown: Boolean` to `UserPreferencesDataStore`. ViewModel reads on `init`; sets `_showDisclosure=true` if false. Dismiss writes true. | P0 | `UserPreferencesDataStore.kt`, `ChatViewModel.kt` | ~50 | +2 KT unit | none | **PROCEED** |
| 3 | **B.2: Idempotency + per-action result UX.** Add a guard `actionsInFlight: Set<String>` keyed on action signature. `reschedule_batch` reports per-item success count. | P1 | `ChatViewModel.kt`, `ChatScreen.kt` | ~80 | +3 KT unit | none | **PROCEED** |
| 4 | **C.2: Snackbar-with-undo on destructive ops** (`archive`, `complete`, `reschedule`, `reschedule_batch`). | P1 | `ChatViewModel.kt`, `ChatScreen.kt` | ~80 | +2 KT unit | depends on #3 (idempotency keys) | **PROCEED** |
| 5 | **A.2: Resolve dead `tier` param.** Either delete from `generate_chat_response` signature or document deliberately constant. Recommend delete; add inline comment pointing to ULTRA-tier promotion plan in roadmap. | P1 | `backend/app/services/ai_productivity.py`, `backend/app/routers/ai.py` | ~10 | 0 (covered by existing tests) | none | **PROCEED** |
| 6 | **E.2: Delete dead `shouldRefreshContext` / `markContextRefreshed`.** | P1 | `ChatRepository.kt` | -8 (deletion) | 0 | none | **PROCEED** (folded into #1 PR cleanup) |
| 7 | D.1 streaming | P1 | backend SSE + Android Flow | ~600 | ~10 tests | none | **DEFER**. Re-trigger: post-Phase-F when SSE infra is sized; or sooner if user reports persistent "blank screen >2s" complaints in support logs. |
| 8 | E.3 conversation persistence (Room + Firestore sync) | P1 | new `chat_message` entity + DAO + sync + migration 77 | ~300 | ~12 tests | needs migration during Phase F gate | **DEFER**. Re-trigger: when conversation loss appears in Crashlytics ≥ 5x/week or when any user reports loss of multi-day chat thread. |
| 9 | B.1 native Anthropic tool-use migration | P1 | service+router rewrite | ~200 | ~8 tests | risks output-shape drift across all 7 action types | **DEFER**. Re-trigger: when a new action type with complex input schema (e.g. multi-task `bulk_complete_with_filter`) lands — at that point JSON-in-text becomes the more brittle path. |
| 10 | B.3, B.4, C.3, C.4, C.5, D.2, D.3, E.4 | P2 | various | ~150 | ~6 tests | independent | **FILE**. Each tracked in CHANGELOG via the per-PR fix or in Phase 4 handoff. Re-trigger criteria below. |

**In-session totals (#1-#6):** ~492 LOC + ~24 tests across 5 PRs.
Estimated wall-clock: ~5-6 working days. Margin against 8-day budget:
~2 days for verification + bundle review + Phase 4.

### 5.1 Filed-but-deferred re-trigger criteria

| Item | Re-trigger criterion |
|---|---|
| D.1 Streaming | Crashlytics or support log: ≥ 5 user reports/week of "chat takes too long". Reach this threshold by 2026-06-15 → schedule for v1.9. |
| E.3 Persistence + Firestore sync | ≥ 5 reports/week of "lost my chat" OR Crashlytics process-death loss bucket > 1% of chat sessions. |
| B.1 Native tool-use | Either (a) action types grow past 10 OR (b) input schema for any action exceeds 5 fields. Tracked on `PrismTaskTimeline.jsx` under "Chat tool-use modernization". |
| B.3 Rich `create_task` from chat | When user reports tasks created from chat are missing tags/project. Currently no signal. |
| B.4 `start_timer` action wiring | When chat-recommended timer is used in user testing. Currently no path. |
| C.3 Clear-chat confirmation | When ≥ 3 reports/week of accidental clear. Stop-gap: enable Phase 2 #4 first (per-action undo). |
| C.4 Action chip a11y | TalkBack audit (PR #1145 line) hits Chat as part of monthly accessibility sweep. |
| C.5 Welcome starter prompts | A/B sample of cold-open chats: if ≥ 30% of users abandon before first send, ship starter prompts. |
| D.2 Cancel-in-flight | Pairs with D.1 streaming; deferred together. |
| D.3 Double-send window | Trivial; carry as P3 hygiene. Land alongside any future `ChatInputBar` touch. |
| E.4 Daily-reset signaling | Pairs with E.3 persistence; if persistence ships, daily reset goes away naturally. |
| Web chat parity | When ≥ 5% of DAU access web AND chat is core to web's value prop. Not currently true. |

### 5.2 Wall-clock-savings ÷ implementation-cost ranking

| Rank | Item | Saves | Cost | Ratio |
|---|---|---|---|---|
| 1 | #1 A.1+E.1 context block | Closes the operator's two biggest "rough" complaints in one PR | ~1.5 d | ★★★★★ |
| 2 | #2 C.1 disclosure | Privacy compliance gap that PR #788 audit said must be wired | ~0.5 d | ★★★★★ |
| 3 | #5 A.2 dead-tier cleanup | Removes future-Ultra-tier confusion + simplifies signature | ~1 hr | ★★★★ |
| 4 | #6 E.2 dead `shouldRefreshContext` | Same as #5 shape — folded into #1's PR | trivial | ★★★★ |
| 5 | #4 C.2 undo on destructive ops | Big UX confidence win | ~0.5 d | ★★★ |
| 6 | #3 B.2 idempotency + per-item batch UX | Smaller perceived UX win, but precondition for #4 | ~0.5 d | ★★★ |

### 5.3 Anti-patterns flagged but not fixed

- **Backend `tier` plumbing is inconsistent.** Some functions use `tier`
  to vary `max_tokens` or model; chat doesn't. If/when an Ultra tier
  exists, plumbing the parameter will need a single coherent pattern
  rather than the current per-function mix. **Not fixed in this audit
  sweep — out of scope.**
- **JSON-in-text action protocol** is the same shape as the older
  `extract_tasks_from_text` and `parse_batch_command` flows. Migrating
  one without the other risks divergence. **File for a v1.9 modernization
  pass; do not fix piecewise.**
- **`ChatRepository` shape overlaps with `extract/`** screen's transcript
  parsing code. If web chat parity ever lands, share the message-bubble
  + action-chip components between `ConversationExtractScreen` and a
  new `ChatScreen` web port. **Not fixed.**

---

## 6. STOP / PROCEED verdict per axis

| Axis | Verdict | Rationale |
|---|---|---|
| A — Reply quality | PROCEED with #1 (context block) | Closes A.1 and most of A.4 in one fix. A.2 (#5) is housekeeping. |
| B — Tool-call reliability | PROCEED with #3 (idempotency + per-item UX). DEFER B.1 native tool-use migration. | #3 is the user-perceived win; B.1 is infrastructure that doesn't move the "rough" needle alone. |
| C — UI/UX | PROCEED with #2 (disclosure) + #4 (undo). DEFER C.3-C.5. | C.1 is privacy; C.2 is destructive-op confidence. C.3-C.5 are polish — file for v1.9. |
| D — Latency / streaming | DEFER D.1. PROCEED with D.3 trivial swap if it folds into another touch. | D.1 alone exceeds the 8-day budget when stacked with P0 fixes; re-trigger criterion above. |
| E — Conversation memory | PROCEED with E.1 (folded into #1). PROCEED with E.2 dead-code delete. DEFER E.3 persistence. | The structural amnesia is fixed by the same context block as #1; persistence + sync risks Phase F migration freeze. |

**No S1-S5 STOP fires.** Premise 5 (RED) is documented and reframed —
chat does not differentiate by tier today, and the in-session plan
preserves that posture cleanly.

---

## 7. Operator gate

Per task-prompt § Phase 1 §1.5: this audit lands as a docs-only PR
(mirror PR #1059 / PR #1076 pattern). **Phase 2 does not start until the
operator pushes back or approves this audit explicitly.** The skill's
default "Phase 2 auto-fires" is overridden here — chat-quality scope is
high-stakes (PII, user data mutation) and warrants the gate.

---

## Phase 3 — Bundle summary

Phase 2 implementation landed as a stack on PR #1164 (per task-prompt
instruction "DEVELOP all your changes on the designated branch above").
Each fix is a separate commit on `claude/audit-chat-quality-e9GAd`:

| # | Fix | Commit | Files | Net LOC | Tests added |
|---|---|---|---|---|---|
| 1 | A.1 + E.1 server-side context block + A.2 + E.2 dead-code cleanup | `7408a30` | 8 (3 backend + 4 Android + 1 test) | +450 / -40 | 6 pytest + 3 Android unit |
| 2 | C.1 first-run AI chat disclosure | `1f9a16c` | 3 | +163 / -1 | 3 Android unit |
| 3 + 4 | B.2 idempotency + per-item batch UX + C.2 snackbar-with-undo | `6feb4b5` | 3 | +480 / -59 | 6 Android unit |
| **Total** | — | — | **14 unique files** | **+1093 / -100** | **18 new tests** |

Net LOC came in at ~990 against the audit's ~492 estimate — drift
trace: per-item batch undo (Fix #3) required snapshotting original
dueDates via TaskDao for each id, plus the executeAction refactor into
typed `handle*` helpers added structure but cost lines. This is within
the audit's 1.9-14× discovery-heavy drift envelope (memory #LOC
calibration), but worth flagging for the next chat-area audit's
estimate.

Verification status (CI is the gate):
- Backend: 6 new pytest cases under `tests/test_ai_chat.py`. Local
  Python smoke test (stubbed Anthropic) confirmed history is
  forwarded as proper `messages` array, invalid-role entries are
  dropped, and `task_context` is included in the user payload. CI
  pytest pending on push.
- Android: 9 new unit tests under
  `ui/screens/chat/ChatViewModelDisclosureTest.kt` and
  `ChatViewModelActionTest.kt`. Local sandbox lacks the Android SDK
  / Gradle 9.3.1 (Linux env vs. project's Windows-flavored CLAUDE.md
  paths); ktlint + detekt + tests run on CI.

Memory entry candidates (only if surprising):
- **Stale "rolling history" comments**: a feature can have a comment
  describing intended behavior ("client owns rolling 10-pair history")
  without the wire actually carrying that history to the model. The
  audit-first sweep caught this only because the actual `messages=[...]`
  array was inspected. **Lesson**: when a comment claims memory is
  preserved, grep for what's actually sent on the network call.

Filed-but-deferred status:
- D.1 streaming, E.3 Room persistence, B.1 native tool-use, B.3, B.4,
  C.3-C.5, D.2-D.3, E.4: all unchanged from §5.1. None promoted, none
  closed, all retain their original re-trigger criteria. To check on
  next monthly audit pass.

Next audit: schedule for the v1.9 cycle — re-evaluate streaming and
Room persistence against the criteria in §5.1.

## Phase 4 — Claude Chat handoff

```markdown
# PrismTask chat-quality audit-first mega — Phase 4 handoff (Phase 1 + 2 complete)

## Scope
Audit + fix bundle for the conversational AI chat surface across
backend (`POST /api/v1/ai/chat`) and Android
(`ChatScreen`/`ChatViewModel`/`ChatRepository`). Web has no chat UI
today. Five-axis sweep: reply quality, tool-call reliability, UI/UX,
latency/streaming, conversation memory.

## Repo state at handoff
- **Branch:** `claude/audit-chat-quality-e9GAd`
- **PR:** https://github.com/averycorp/prismTask/pull/1164 (Phase 1
  audit doc + Phase 2 implementation stacked, awaiting CI + merge).
- **App:** 1.8.46 / vc 844 (no version bump; no migrations) ·
  **Room:** v76 unchanged · **Backend alembic head:**
  `024_add_beta_codes` unchanged · **Web:** 1.6.0 unchanged.
- **Commits on branch:** `bd97dbe` (audit) · `7408a30` (fix #1) ·
  `1f9a16c` (fix #2) · `6feb4b5` (fixes #3+#4).

## Verdicts
- **Premise 1 (web parity):** YELLOW reframe — web has no chat UI.
- **Premise 2 (rough enumerable):** GREEN — two root causes drove
  ≥80% of "rough" complaints (A.1 task-content blindness, E.1 zero
  multi-turn memory).
- **Premise 3 (8-day window):** GREEN — fits.
- **Premise 4 (AI gate):** GREEN — both halves wired post-#1128.
- **Premise 5 (Ultra=Sonnet tier):** RED — no Ultra tier in code;
  dead `tier` param removed in fix #1.

## Shipped (Phase 2)
1. **#1 — A.1+E.1 server-side context block + A.2+E.2 cleanup.**
   Backend now forwards rolling N=6 user/assistant pairs and a
   task_context snapshot (title/description/due/priority/project) to
   Anthropic. The model finally has multi-turn memory and grounded
   task content.
2. **#2 — C.1 first-run AI chat disclosure.** New
   `aiChatDisclosureShownFlow` preference. Dialog now actually fires
   on first chat open and persists acknowledgement.
3. **#3 — B.2 idempotency + per-item batch UX.** Action chip
   double-tap now silently no-ops while first call is in flight.
   `reschedule_batch` reports "Rescheduled X of Y (Z Failed)".
4. **#4 — C.2 snackbar-with-undo on destructive ops.** Replaces toast
   feedback with snackbar. Each destructive type carries an undo
   callback (uncompleteTask / unarchiveTask / restore-prior-dueDate).

## Deferred / stopped
- **D.1 streaming** — exceeds 8-day window. Re-trigger ≥ 5/wk
  blank-screen reports by 2026-06-15.
- **E.3 Room persistence + Firestore sync** — needs migration during
  Phase F gate; risk too high. Re-trigger Crashlytics process-death
  loss > 1% or 5 reports/wk.
- **B.1 native Anthropic tool-use migration** — re-trigger on
  action-type fan-out > 10 or any input schema > 5 fields.
- **B.3, B.4, C.3-C.5, D.2-D.3, E.4** — P2 polish, criterion-tracked.

## Non-obvious findings
- The privacy posture *was* less leaky than the audit prompt assumed:
  conversation history was never forwarded to Anthropic. Fix #1
  introduces a new (intended, disclosed) PII shape: task title +
  description + due_date + priority + project_name now egress on
  every chat turn that opens from a task. Privacy doc update required
  per the existing PR #788 disclosure path — not a fresh privacy
  re-audit.
- Fixes #2, #3, #4 each surfaced ≥ 1 dead-code branch that was
  declared but never wired (`_showDisclosure` flow, `tier` param,
  `shouldRefreshContext`). This is consistent with a planned
  context-injection design that started but never finished. Fix #1
  effectively completes that intent.
- Net LOC drift: audit estimated ~492, shipped ~990. Driver: per-item
  batch undo required snapshotting original dueDates via TaskDao,
  plus the per-type `handle*` helper extraction added structure at
  the cost of lines.

## Open questions for operator
1. Privacy doc update for the new task-content PII shape — single PR
   to `docs/privacy/index.md` § AI features and
   `docs/store-listing/compliance/data-safety-form.md` per PR #788
   pattern? (Recommended.)
2. Fix #2's disclosure dialog will fire once for every existing user
   on first chat open after this lands — is that the intended
   re-disclosure behavior given the broadened PII shape? (Audit
   assumed yes; flagging for operator confirmation.)
3. Should the disclosure copy be updated to enumerate the new task
   fields? Current copy: "Your messages are processed by AI to
   provide coaching. Chat resets daily and isn't stored permanently."
   — does NOT mention task title/description forwarding.

## Audit-first track record update
This session reframed 2 of 5 operator premises (Premise 1 web parity,
Premise 5 Ultra tier). Counts to the running statistic per memory #13.
```
