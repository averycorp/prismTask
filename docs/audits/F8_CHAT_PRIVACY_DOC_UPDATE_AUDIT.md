# F8 Chat Privacy Doc Update — Audit

**Author:** akarlin3 (Avery)
**Date:** 2026-05-07
**Branch:** `claude/update-privacy-chat-docs-fmQxA`
**Prompt:** `cc_f8_chat_privacy_doc_update_audit_first.md`
**Trigger:** PR #1164 fix #1 broadened the chat surface PII shape — the
`POST /api/v1/ai/chat` route now forwards a `task_context` snapshot and
rolling user/assistant history to Anthropic. CHANGELOG.md `### Privacy`
entry already calls out the new shape and explicitly defers the privacy
doc + Data Safety form follow-on to a future PR. This is that PR.

---

## TL;DR — Recommendation

**PROCEED with single-PR doc + disclosure copy + re-fire mechanism.**

- Update `docs/privacy/index.md` § AI features and § Third-party
  processors to enumerate chat egress (6 task-context fields + rolling
  user/assistant history).
- Update `docs/store-listing/compliance/data-safety-form.md` § Messages
  and § App activity (Other user-generated content) and § Third-party
  processors row 2 to enumerate the same.
- Update `ChatScreen.kt` disclosure copy to enumerate the fields.
- Bump preference key `KEY_AI_CHAT_DISCLOSURE_SHOWN` →
  `KEY_AI_CHAT_DISCLOSURE_SHOWN_V2` so existing users see the new copy
  once after install (Option A from § A.5).
- Two new unit tests pin the re-fire path; existing 3 disclosure tests
  stay GREEN against the new key name.
- CHANGELOG entry under `### Privacy`.

**Schema divergence vs operator prompt:** the operator listed 5 fields
(title, description, due_date, priority, project_name). The actual
`ChatTaskContext` schema in `backend/app/schemas/ai.py:554-568` has **6
fields** — adds `is_completed: Optional[bool]`. The Android client
populates it (`ChatViewModel.kt:472`). Plus rolling history
(`ChatHistoryEntry`: role + content, last N=6 pairs). The audit and
Phase 2 ship the full 6-field + history disclosure rather than the
under-counted 5-field version.

**STOP-conditions evaluated:** none fire. STOP-A1 was a candidate
(`#788` not findable as a standalone commit), but the pattern is
embedded in the existing `docs/privacy/index.md` and `data-safety-form.md`
via the 2026-04-26 medication-disclosure changelog entry. STOP-D was a
candidate (sibling AI features), but they are already disclosed in the
existing docs.

---

## A.1 — PR #788 pattern recon

**Operator prompt cited PR #788 as the load-bearing template.**

Verification:
- `git log --all --grep="788\|disclosure\|privacy"` returns no commit
  whose message contains `#788`. PR #788 cannot be inspected directly
  in the local clone (the clone is partial — only ~10 commits are
  reachable on the relevant branches via `git log`).
- However, `CHANGELOG.md:63` references PR #788 explicitly:
  > "Pairs with the existing PR #788 disclosure path; privacy doc +
  > Data Safety form follow-on planned."
  — so PR #788 did land; the audit cannot inspect its diff directly,
  only its observable footprint in the current tree.
- The **observable footprint** of PR #788 (or whichever PR the doc
  pattern actually came from) is:
  - `docs/privacy/index.md` — Effective 2026-04-24, last updated
    2026-04-26. § AI features at line 51 enumerates: NLP quick-add,
    Eisenhower auto-classification, AI time blocking, daily briefing,
    weekly review, smart Pomodoro planner, NLP batch commands. **Chat
    is NOT enumerated.**
  - `docs/store-listing/compliance/data-safety-form.md` — Section 2
    table-row-per-data-type structure; § Messages → "Other in-app
    messages" (line 65) currently says: *"Coaching chat is stored
    locally and in Firestore if signed in; no third-party messaging
    providers."* This is **stale** post-PR #1164 — chat IS now shared
    with Anthropic.
  - 2026-04-26 changelog entry pattern: *"No change to what data is
    collected — disclosure update only."* This is the exact
    disclosure-update-only convention this PR follows.

**Verdict (GREEN):** the pattern is fully recoverable from the existing
docs even without the PR #788 diff in hand. The 2026-04-26 changelog
entry is the load-bearing template — disclosure-only update with no
schema change.

## A.2 — Current disclosure code path

- Preference key: `KEY_AI_CHAT_DISCLOSURE_SHOWN` (boolean, default
  `false`) — `UserPreferencesDataStore.kt:229`.
- Flow + setter: `aiChatDisclosureShownFlow` /
  `setAiChatDisclosureShown` — `UserPreferencesDataStore.kt:500-506`.
- ViewModel wiring: `ChatViewModel.kt:127-153` — checks the flag in
  `init { }` via `.first()` and surfaces `_showDisclosure` only when
  `false`. `dismissDisclosure()` writes `true`, hides the dialog.
- Composable: `ChatScreen.kt:172-188` — `AlertDialog` with title `"AI
  Chat"`, current copy at lines 178-179:
  > *"Your messages are processed by AI to provide coaching. Chat
  > resets daily and isn't stored permanently."*
- Existing tests:
  - `ChatViewModelDisclosureTest.kt` (3 tests): first-open shows
    dialog, prior-dismissal skips, dismiss persists flag.
  - `ChatViewModelActionTest.kt:83` — test setup pins
    `aiChatDisclosureShownFlow` to `flowOf(true)` to suppress dialog
    during action tests.

**Verdict (GREEN):** existing disclosure path is the right hook for
both the copy update and the re-fire bump.

## A.3 — ChatTaskContext schema (the actual fields)

**Operator prompt enumerated 5 fields. Actual schema has 6 + rolling
history.**

`backend/app/schemas/ai.py:554-568` (canonical):
```python
class ChatTaskContext(BaseModel):
    title: str = Field(min_length=1, max_length=500)
    description: Optional[str] = Field(default=None, max_length=4000)
    due_date: Optional[str] = Field(default=None, max_length=64)
    priority: Optional[int] = Field(default=None, ge=0, le=4)
    project_name: Optional[str] = Field(default=None, max_length=200)
    is_completed: Optional[bool] = None     # ← MISSED BY OPERATOR PROMPT
```

Android side (`ApiModels.kt:555-562`): same 6 fields, mirrored.
Populated by `ChatViewModel.kt:466-473` —
`isCompleted = task.isCompleted` is non-conditional.

Plus `ChatHistoryEntry` (`ai.py:542-551`, `ApiModels.kt:545-548`):
```python
class ChatHistoryEntry(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(min_length=1, max_length=4000)
```
Forwarded as rolling N=6 user/assistant pairs (server cap = 12 entries
max). The user's prior chat turns + the AI's prior replies BOTH egress.

**Verdict (RED→YELLOW after correction):** the operator's "5 fields" was
incorrect. Per memory #22 bidirectional verification: the disclosure copy
+ doc updates ship the full 6-field set + rolling history language, not
the under-counted 5. This is a finding — not a STOP — because it
strictly broadens disclosure (no schema change).

## A.4 — Doc structure recon

`docs/privacy/index.md` (131 lines, 11.6 KB):
- § "The short version" (line 25) — bullet listing AI features. Chat is
  not mentioned.
- § "What we collect" → "Information you provide directly" (line 34) —
  *does* mention "chat messages with the in-app coaching assistant".
- § "How we use your data" → AI-powered features (line 51) — enumerates
  NLP, Eisenhower, time blocking, briefing, weekly review, Pomodoro
  planner, NLP batch commands. Chat is **not** in the list.
- § "Third-party processors" table (line 71) — Anthropic row enumerates:
  task titles, descriptions, project names, habit names, schedule
  data, free-text NLP/extraction, medication names. Chat content +
  history are **not** in the list.
- § Changelog (line 112) — last entry 2026-04-26 (medication
  disclosure). The doc-and-disclosure-only-update convention is
  established here.

`docs/store-listing/compliance/data-safety-form.md` (155 lines):
- § 2 "Data types collected" — table-row-per-data-type structure.
- § Messages → "Other in-app messages" (line 65): *"Coaching chat is
  stored locally and in Firestore if signed in; no third-party
  messaging providers."* — stale.
- § App activity → "Other user-generated content" (line 111) lists
  "chat with coaching assistant" but the **Shared?** column claims
  only task/habit/project/medication names go to Anthropic; chat
  egress is omitted.
- § 3 Third-party processors (line 141) — Anthropic row mirrors the
  privacy doc's; missing chat content + history.
- § 4 Security practices statement (line 148) — same gap.

**Verdict (PROCEED):** all four call sites need updating in lockstep.
Both files explicitly cross-reference each other (header at line 4 of
data-safety-form.md: *"This document must stay consistent with
`../../privacy/index.md`"*).

## A.5 — Re-fire mechanism options

Operator Q2 (May 6): re-fire dialog for **all** existing users on first
chat open after this lands.

| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| **A** | Bump key: `KEY_AI_CHAT_DISCLOSURE_SHOWN` → `_V2` | Clean, idempotent, no migration code, future-proof for next bump | One orphan preference (V1) lives in DataStore forever; harmless |
| B | Keep key, write `false` once via a migration flag | No orphan key | Requires a separate migration-flag preference that itself becomes orphan; more moving parts |
| C | Add separate `KEY_TASK_CONTENT_DISCLOSURE_SHOWN` additive to existing | Coexists with v1 dialog | Doubles dialog count for new users (saw v1, then v2); confusing UX |

**Verdict (Option A):** cleanest. Existing users with the old key set
to `true` see the new dialog once after install, dismiss persists
`KEY_AI_CHAT_DISCLOSURE_SHOWN_V2 = true`, never shown again.

Per memory `feedback_audit_drive_by_migration_fixes.md`: orphan V1
preference key — `git log -p -S "KEY_AI_CHAT_DISCLOSURE_SHOWN"` confirms
the key was added in PR #1164 (`fix(chat): chat quality audit + Phase 2
implementation`). Touching only the bump-pattern (no removal of the V1
key) avoids a drive-by migration. The V1 key is deprecated as a doc
comment but the entry remains so older clients on a back-revved DB
don't crash on the missing key (DataStore is forgiving but the
discipline is consistent with the rest of the codebase).

## A.6 — Sibling-primitive scan: other AI features that egress task content

Per `docs/audits/PII_EGRESS_AUDIT.md` § Section 1, the production call
sites that forward task content to Anthropic are:

| # | Endpoint | Task content forwarded? | Disclosed in privacy doc? |
|---|---|---|---|
| 1 | `categorize_eisenhower` | yes (titles + dates) | yes (line 51) |
| 2 | `classify_eisenhower_text` | yes | yes |
| 3 | `plan_pomodoro` | yes | yes |
| 4 | `generate_daily_briefing` | yes | yes |
| 5 | `generate_weekly_plan` | yes | yes |
| 6 | `generate_time_blocks` | yes | yes |
| 7 | `analyze_habit_correlations` | habit content | yes (habit names) |
| 8 | `generate_weekly_review` | yes | yes |
| 9 | `extract_tasks_from_text` | user-pasted text | yes (NLP/extraction) |
| 10 | `generate_pomodoro_coaching` | task titles | yes (Pomodoro) |
| 11 | `parse_batch_command` | yes + medication names | yes (medication line) |
| 12 | `parse_task_input` | yes (NLP quick-add) | yes |
| 13 | `_call_haiku` (parse-import + parse-checklist) | yes | yes (NLP) |
| 14 | `parse_syllabus` | yes | yes (NLP) |
| 15 | `scan_gmail` | email subjects/snippets/sender | yes (line 71, Anthropic row) |
| **16** | **`generate_chat_response`** (`ai.py:582` chat route) | **yes (PR #1164 fix #1)** | **NO — this PR fixes that** |

**Verdict (GREEN-except-#16):** every egress site **except chat** is
already disclosed. STOP-D does not fire. Chat is the one outstanding
gap, exactly as the operator prompt scoped.

## A.7 — Premise verification (D from prompt)

| Premise | Verified? | Citation |
|---|---|---|
| D.1: ChatTaskContext schema fields | **CORRECTED** — 6, not 5 | `ai.py:554-568` |
| D.2: PR #788 pattern established docs+copy convention | YES (from observable footprint) | `CHANGELOG.md:63` + 2026-04-26 entry |
| D.3: KEY_AI_CHAT_DISCLOSURE_SHOWN exists | YES | `UserPreferencesDataStore.kt:229` |
| D.4: Operator Q1+Q2+Q3 May 6 decisions in effect | YES | Per prompt header |

---

## B.1 — Doc update plan

### `docs/privacy/index.md`

1. § "The short version" (line 25): mention chat coaching as an AI
   feature that egresses content.
2. § AI-powered features (line 51): add chat coaching to the
   enumerated list. State that the rolling N=6 user/assistant turns
   plus the task-content snapshot (6 fields) are forwarded when chat
   is opened from a specific task.
3. § Third-party processors → Anthropic row (line 71): append "chat
   messages and a task-content snapshot (title, description, due
   date, priority, project name, completion state) when the user
   opens chat from a specific task; rolling user/assistant
   conversation history (last few turns)".
4. § Changelog: prepend a 2026-05-07 entry following the
   2026-04-26 pattern. State no schema change — disclosure update
   only — and re-fire of the in-app disclosure dialog.

### `docs/store-listing/compliance/data-safety-form.md`

1. § Messages → Other in-app messages (line 65): replace the stale
   "no third-party messaging providers" with the actual chat egress.
2. § App activity → Other user-generated content (line 111): revise
   the **Shared?** column to include chat messages and the
   task-content snapshot.
3. § 3 Third-party processors (line 141) — Anthropic row: mirror the
   privacy doc's expanded list.
4. § 4 Security practices statement (line 148): mirror.

### Cross-reference check

Both docs already declare lockstep (data-safety-form.md:4). Phase 3 has
a static gate to grep for the new field-enumeration string in both.

## B.2 — Disclosure copy variants

Current (line 178-179, ChatScreen.kt):
> *"Your messages are processed by AI to provide coaching. Chat resets
> daily and isn't stored permanently."*

| Variant | Wording | Trade-off |
|---|---|---|
| **A** (operator's proposal) | *"Your messages are processed by AI to provide coaching. The AI also sees your task title, description, due date, priority, and project name to give relevant suggestions. Chat resets daily and isn't stored permanently."* | Under-discloses by 1 field (no `is_completed`) and omits rolling history |
| **B (RECOMMENDED, ships in Phase 2)** | *"Your messages are processed by AI to provide coaching, along with the last few turns of conversation for context. When chat is opened from a task, the AI also sees that task's title, description, due date, priority, project name, and completion state. Chat resets daily and isn't stored permanently."* | Matches schema 1:1 |
| C (terse, defensive) | *"Your messages — and the task you're chatting about (title, description, due date, priority, project, completion state) — are sent to our AI provider. Recent turns are also sent for context. Chat resets daily and isn't stored permanently."* | Reads more legalese; less warm |

**Verdict:** Variant B ships. It's only ~30 words longer than A, reads
naturally, and matches the schema 1:1 — which is the load-bearing
property here per memory #22 bidirectional verification (under-disclosure
is the bigger risk than verbosity in a one-time first-run dialog).

Operator can course-correct mid-stream if they prefer A or C.

## B.3 — Re-fire mechanism (per A.5 verdict, Option A)

In `UserPreferencesDataStore.kt`:
- Add `val KEY_AI_CHAT_DISCLOSURE_SHOWN_V2 = booleanPreferencesKey("ai_chat_disclosure_shown_v2")`.
- Add `aiChatDisclosureShownV2Flow` + `setAiChatDisclosureShownV2`
  setter, mirroring V1.
- Comment block notes V1 is deprecated and intentionally left in place
  to avoid migration risk for back-revved clients.

In `ChatViewModel.kt:127-153`:
- Replace V1 references with V2.

In `ChatScreen.kt:172-188`:
- Update copy to Variant B.

## B.4 — Test enumeration

Existing 3 tests in `ChatViewModelDisclosureTest.kt` get retargeted at
the V2 flow (renames only, no logic changes — the test names already
describe the contract correctly).

Add 2 new tests:
- `existing_user_with_v1_set_true_sees_v2_dialog_once` — pins
  `aiChatDisclosureShownFlow` (V1) = `flowOf(true)` AND
  `aiChatDisclosureShownV2Flow` = `flowOf(false)`. Confirms dialog still
  fires (re-fire path).
- `dismiss_persists_v2_only_leaves_v1_alone` — confirms `setAiChatDisclosureShown`
  (V1) is **never** called; only `setAiChatDisclosureShownV2(true)`.

`ChatViewModelActionTest.kt:83` already pins V1 = true; it needs to also
pin V2 = true to keep the dialog suppressed during action tests.
Otherwise unrelated action assertions fail because the dialog flag
state leaks.

## C — STOP-conditions evaluated

| ID | Condition | Fired? | Notes |
|---|---|---|---|
| STOP-A1 | PR #788 not findable | NO | Pattern recoverable from observable footprint (see § A.1) |
| STOP-A4 | Doc files don't exist or have radically different structure | NO | Both exist with expected structure (§ A.4) |
| STOP-C | Disclosure copy variants need operator approval | NO (skipped) | Per `feedback_skip_audit_checkpoints.md`. Variant B ships; operator can course-correct mid-stream |
| STOP-D | Sibling AI features egress without disclosure | NO | All 15 sibling endpoints already disclosed (§ A.6) |
| STOP-E | Re-fire conflicts with existing prefs architecture | NO | Option A is additive (§ A.5) |
| STOP-F | Phase 2 LOC > 200 | NO (forecast) | See § Phase 2 scope below |

## D — Phase 2 scope (LOC forecast vs STOP-F 200 ceiling)

| File | Action | LOC est. |
|---|---|---|
| `docs/privacy/index.md` | Edit § AI features + § Third-party processors + § Changelog (~20 lines added) | +25 |
| `docs/store-listing/compliance/data-safety-form.md` | Edit § Messages, § App activity row, § 3 processors, § 4 security statement | +20 |
| `app/src/main/java/com/averycorp/prismtask/data/preferences/UserPreferencesDataStore.kt` | Add V2 key + flow + setter | +18 |
| `app/src/main/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModel.kt` | V1 → V2 reference swap | +0 (net) |
| `app/src/main/java/com/averycorp/prismtask/ui/screens/chat/ChatScreen.kt` | Disclosure copy update (Variant B) | +5 |
| `app/src/test/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModelDisclosureTest.kt` | 3 existing tests retarget to V2; +2 new tests | +50 |
| `app/src/test/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModelActionTest.kt` | Pin V2 = true alongside V1 | +1 |
| `CHANGELOG.md` | New `### Privacy` entry | +20 |

**Forecast total: ~140 LOC.** Under STOP-F ceiling. PROCEED.

## E — Anti-patterns avoided

- Not modifying `ChatTaskContext` schema (out of scope; PR #1164's
  shipped contract).
- Not removing `KEY_AI_CHAT_DISCLOSURE_SHOWN` (V1) — leaving the
  orphan key in place avoids a drive-by migration on back-revved
  clients (per memory `feedback_audit_drive_by_migration_fixes.md`).
- Not silently broadening disclosure to other AI features (already
  disclosed per § A.6 — STOP-D would have been the right halt if not).
- Not skipping the re-fire test — it's the load-bearing acceptance
  gate per the prompt.
- Not adding privacy boilerplate beyond the 6 actual fields + history.

## F — Open questions for operator

- **Disclosure copy variant pick.** Audit ships Variant B; operator
  may swap to A or C mid-stream. (No STOP-C halt per
  `feedback_skip_audit_checkpoints.md`.)
- **Whether V1 key should also be cleaned up at some future point.**
  Audit recommends leaving it in place; the orphan footprint is one
  boolean preference, harmless.

## G — Ranked improvement table (wall-clock-savings ÷ implementation-cost)

This audit is single-improvement-scope (the F8 follow-on bundle), so
ranking is degenerate — one row.

| Item | Savings | Cost | Ratio | Status |
|---|---|---|---|---|
| F8 chat privacy doc + disclosure re-fire | Closes the disclosure gap PR #1164 left open; unblocks Phase F GREEN-GO compliance posture | ~140 LOC, 1 PR | high | PROCEED |

---

## Phase 3 — Bundle summary (pre-merge per CLAUDE.md)

### Per-improvement

- **F8 chat privacy doc update + disclosure re-fire**
  - Branch: `claude/update-privacy-chat-docs-fmQxA`
  - PR: filed at end of this session — single PR, no fan-out.
  - Files touched (8):
    - `docs/privacy/index.md` — § short version, § AI-powered features, § processors table, § changelog (2026-05-07 entry).
    - `docs/store-listing/compliance/data-safety-form.md` — § Messages row, § App activity row, § 3 processors row, § 4 security statement, header date.
    - `app/src/main/java/com/averycorp/prismtask/data/preferences/UserPreferencesDataStore.kt` — `KEY_AI_CHAT_DISCLOSURE_SHOWN_V2` + flow + setter (V1 retained).
    - `app/src/main/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModel.kt` — V1 → V2 in init + dismiss.
    - `app/src/main/java/com/averycorp/prismtask/ui/screens/chat/ChatScreen.kt` — disclosure copy Variant B.
    - `app/src/test/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModelDisclosureTest.kt` — 3 retargeted to V2 + 2 new tests (re-fire + V2-only-write).
    - `app/src/test/java/com/averycorp/prismtask/ui/screens/chat/ChatViewModelActionTest.kt` — pin V2 = true.
    - `CHANGELOG.md` — § Privacy entry under Unreleased.
  - LOC: `git diff --stat` → 132 insertions, 22 deletions across 8 files. Under STOP-F (200 LOC) ceiling.
- Verification status:
  - Local Gradle build blocked — no Android SDK on this Linux host (per CLAUDE.md, the toolchain paths described are the Windows dev env). Falling back to CI as the verification gate per CLAUDE.md "CI still runs on every push and remains the final verification gate."
  - File-level sanity checks GREEN: `grep` confirms V2 wiring across both `ChatViewModel.kt` and the two test files; trailing newlines verified on all 4 docs.

### Memory entry candidates

- **None.** No surprising findings. The schema-vs-prompt divergence
  (5-field operator prompt vs 6-field actual schema) is exactly the
  failure mode memory #22 (bidirectional verification) already
  guards against — this is a confirmation of that memory, not a new
  data point.

### Schedule for next audit

Roll forward into the next F8 follow-on as the next disclosure-shape
change lands (no audit currently scheduled).

## Phase 4 — Claude Chat handoff

(Emitted at end of session per CLAUDE.md "Audit-first Phase 3 + 4 fire
pre-merge". The handoff block is printed in the operator's terminal at
session end — it is not committed to this audit doc.)
