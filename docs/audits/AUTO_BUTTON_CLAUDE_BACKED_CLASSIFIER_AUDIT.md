# Auto Button — Claude-Backed Life-Category Classifier Audit

**Date:** 2026-05-07
**Branch:** `claude/fix-auto-categorization-E1cOH`
**Operator scope (verbatim):** "When the Auto button is clicked under details,
it doesn't use Claude to attempt to classify the activity."
**Symptom:** The Organize-tab "Auto" button (rendered alongside the Life
Category chips) only runs the local keyword `LifeCategoryClassifier` —
it never calls the backend's Claude-Haiku-powered classifier, so any task
whose title/description doesn't match a hardcoded keyword falls back to
`UNCATEGORIZED` regardless of how obvious the category is to a human.

The premise is real (verified end-to-end below). The Eisenhower Auto button
already routes through Claude via `EisenhowerClassifier` →
`/ai/eisenhower/classify_text`; the Life Category Auto button has no
analogous path. This audit scopes the gap, picks an implementation shape
that mirrors the Eisenhower pattern, and explicitly defers the parallel
Task Mode / Cognitive Load Auto buttons until the operator confirms they
want the same treatment.

---

## 1. Auto button on Life Category does not escalate to Claude (RED, PROCEED)

**Findings.**

The Auto button itself is wired correctly to the ViewModel (PR #1131,
`AUTO_BUTTON_AUTO_PICK_AUDIT.md`). The break is one layer down: the
ViewModel's `autoPickLifeCategory(force = true)` only knows how to call
the local keyword classifier.

- Auto button renders at `OrganizeTab.kt:937` (`AutoPickButton`).
  Mounted alongside the Life Category chips at line 827 with
  `onAuto = { viewModel.autoPickLifeCategory(force = true) }`
  (`OrganizeTab.kt:151`).
- ViewModel impl: `AddEditTaskViewModel.autoPickLifeCategory(force)`
  at `AddEditTaskViewModel.kt:657-668` —
  ```kotlin
  val guess = LifeCategoryClassifier
      .withCustomKeywords(lifeCategoryCustomKeywords.value)
      .classify(title, description.ifBlank { null })
  lifeCategory = guess.takeIf { it != LifeCategory.UNCATEGORIZED }
  ```
  Pure local keyword scan. No network call, no Pro check, no
  AI-features check. Classifier-miss → chip stays empty.
- Keyword set: `LifeCategoryClassifier.kt:81-151` — ~60 hardcoded
  keywords across WORK / PERSONAL / SELF_CARE / HEALTH. Realistic miss
  cases the user would expect Claude to catch: "gym session", "haircut",
  "tax filing", "draft Q3 OKRs", "school pickup", "vet appointment",
  "credit card dispute".

**Existing precedent — Eisenhower Auto button is fully Claude-backed.**

The same conceptual button on the Eisenhower screen ships the pattern we
need to mirror:

- `EisenhowerClassifier` (`data/remote/EisenhowerClassifier.kt:28-73`) is
  a `@Singleton` that calls `api.classifyEisenhowerText(...)`, returning
  `Result<Classification>`. Offline-safe — `Result.failure` on no token,
  network error, or malformed response.
- Backend endpoint: `POST /ai/eisenhower/classify_text`
  (`backend/app/routers/ai.py:213-233`). Routes through
  `ai_productivity.classify_eisenhower_text` (`services/ai_productivity.py:106`)
  which calls Claude Haiku via `get_model("eisenhower")`.
- Rate-limited: 20 req/min/user
  (`backend/app/routers/ai.py:80, 227`).
- Gated by the `require_ai_features_enabled` middleware (HTTP 451
  when off — see `UserPreferencesDataStore.kt:121-137` for the client
  half of the contract).

**No equivalent for Life Category.** Searching `backend/` for
`life.category` produces zero classifier endpoints; searching
`PrismTaskApi.kt` for `classifyLife*` likewise finds nothing. The path
must be built end-to-end.

**Why "Claude misses" today are user-visible.**

- Compose state: `lifeCategory = guess.takeIf { it != LifeCategory.UNCATEGORIZED }`
  (`AddEditTaskViewModel.kt:667`). A miss leaves `lifeCategory = null`,
  which renders as no-chip-selected.
- Save path: `resolveLifeCategoryForSave()`
  (`AddEditTaskViewModel.kt:704-708`) re-runs the same local classifier
  if the chip is null — so a miss at button-press is also a miss at
  save. The persisted `life_category` ends up `UNCATEGORIZED`, and the
  Today balance bar / weekly report under-counts the task forever after
  (no reclassification on `updateTask` — see Item 3).

**Risk classification.** RED. The button looks like AI but does not
behave like AI for the user; that's the literal report. The fix is
non-trivial (new backend endpoint + Android remote classifier + Pro
gate + AI-features gate + UX for in-flight + offline fallback) but the
shape is fully prescribed by the Eisenhower precedent.

**Recommendation.** PROCEED. Build a Claude-backed life-category
classifier mirroring the Eisenhower pattern.

### Implementation shape

1. **Backend** (`backend/app/routers/ai.py` + `services/ai_productivity.py`):
   - New endpoint `POST /ai/life_category/classify_text` accepting
     `{title, description}`, returning `{category: "WORK"|"PERSONAL"|"SELF_CARE"|"HEALTH"|"UNCATEGORIZED", reason: str}`.
   - Reuses `get_model("eisenhower")` (Haiku tier) — no new model config.
   - Rate-limit 20/min via existing `RateLimiter` pattern.
   - `require_ai_features_enabled` dependency.
   - Pytest coverage in `backend/tests/test_ai_productivity.py` parallel
     to the existing Eisenhower tests.
2. **Android remote layer** (`data/remote/`):
   - New `LifeCategoryRemoteClassifier` mirroring
     `EisenhowerClassifier.kt` — same `Result<Classification>` pattern,
     same offline-safe semantics.
   - Wire `LifeCategoryClassifyTextRequest` /
     `LifeCategoryClassifyTextResponse` DTOs to `PrismTaskApi.kt`.
3. **ViewModel** (`AddEditTaskViewModel.kt`):
   - Inject `LifeCategoryRemoteClassifier`.
   - Promote `autoPickLifeCategory` to `suspend` (or wrap in
     `viewModelScope.launch`). On Auto-press:
     a. Run local classifier first as instant feedback (current
        behaviour). If it picks a real chip, render it.
     b. Concurrently fire the remote classifier. If it returns a real
        chip and either (i) local missed or (ii) remote disagrees with
        local, overwrite the chip with the remote pick. Set a small
        "Auto-picked by AI" affordance so the user knows it changed.
     c. On remote failure (offline / no Pro / AI-features off / 5xx),
        keep the local pick; never blank out a successful local chip.
   - Same `force` semantic; same `lifeCategoryManuallySet` discipline.
4. **UX**:
   - Loading state: show a small spinner inside the Auto button while
     the remote call is in flight. Existing precedent:
     `EisenhowerScreen.kt:151` (categorize button spinner) +
     `AUTO_BUTTON_AI_FAILURE_AND_UPGRADE_MESSAGES_AUDIT.md` § 1, 2.
   - Free tier: surface the existing upgrade prompt
     (same pattern as `EisenhowerViewModel._showUpgradePrompt`,
     `EisenhowerViewModel.kt:108-130`). The local classifier still
     runs for Free users — no regression vs. today.
   - Failure: surface via `_errorMessages` SharedFlow that
     `AddEditTaskScreen` already drains into a Snackbar — no new
     sealed state needed.

### Tests

- `LifeCategoryRemoteClassifierTest` — happy-path, no-token, network
  failure, malformed response (mirror
  `EisenhowerClassifierTest` if present, otherwise pattern from
  `ClaudeParserServiceTest`).
- `AddEditTaskViewModelTest` — extend with: Auto-press fires both
  classifiers; remote-success overwrites local-miss; remote-failure
  preserves local-pick; Free-tier short-circuits remote; AI-features-off
  short-circuits remote.
- `backend/tests/test_ai_productivity.py` — happy-path, malformed Claude
  response, AI-features-off → 451, rate-limit hit → 429.

### Out of scope (deferred / noted only)

- **Task Mode + Cognitive Load Auto buttons** — same gap, parallel
  fix. Operator said "classify the activity" (Life Category) so this
  PR scopes to Life Category. Open follow-up: replicate for Task Mode
  + Cognitive Load if operator confirms (Item 2 below).
- **`updateTask` re-classification** — separate bug (Item 3).
- **Documentation drift** on the missing Balance auto-classify toggle
  (Item 4).

---

## 2. Task Mode + Cognitive Load Auto buttons share the same local-only gap (YELLOW, DEFER)

**Findings.**

`AddEditTaskViewModel.autoPickTaskMode` (`:671-682`) and
`autoPickCognitiveLoad` (`:685-696`) are byte-for-byte parallel to
`autoPickLifeCategory` — same local keyword classifiers
(`TaskModeClassifier`, `CognitiveLoadClassifier`), same `force`
semantic, same Compose write pattern. Their Auto buttons are mounted
identically at `OrganizeTab.kt:163` and `:175`.

If the operator wants Claude-backed classification for Life Category, they
will almost certainly want it for Task Mode + Cognitive Load too — the
three chips share one row in the UI. But:

- The operator's report explicitly mentioned only one button ("the Auto
  button … to classify the activity"). Life Category is the literal
  "activity classifier"; Task Mode + Cognitive Load are
  reward / start-friction signals, not activity types.
- Adding two more endpoints triples backend surface and Pro/AI-gate
  test matrix without a confirmed user request.

**Risk classification.** YELLOW. Same shape, same fix, but explicitly
out of scope until operator confirms.

**Recommendation.** DEFER. After Item 1 ships, ask the operator: "Same
treatment for Task Mode + Cognitive Load?" If yes, replicate the Item 1
implementation (same backend pattern, same ViewModel pattern, same UX).

---

## 3. `updateTask` never re-runs the keyword classifier (YELLOW, DEFER)

**Findings.**

`TaskRepository.updateTask` (`TaskRepository.kt:288-306`) does not call
`resolveLifeCategoryForInsert`. Editing a task title from "buy stuff" to
"call doctor" leaves `life_category` at whatever it was set to on
creation. Insert paths (`addTask`, `insertTask`, `addSubtask`,
recurrence-spawn at `:393`) all classify; update does not.

This is orthogonal to the Auto-button fix — the editor's save path
calls `resolveLifeCategoryForSave()` (`AddEditTaskViewModel.kt:704`),
which means a user who *re-opens* a task and taps Auto will pick up
the new value. But programmatic updates (NLP edit-in-place, automation
actions, sync) do not.

**Risk classification.** YELLOW. Pre-existing behaviour, not what the
operator reported.

**Recommendation.** DEFER. Open as a separate audit if the operator
flags it.

---

## 4. Documentation drift: Balance auto-classify toggle claimed but does not exist (GREEN, STOP-no-work-needed)

**Findings.**

- `WorkLifeBalanceSection.kt:31` KDoc lists "Toggle auto-classification
  of new tasks" as a section feature. The composable does not render
  the toggle.
- `CLAUDE.md` (the v1.4 — v1.6 changelog block, "auto-classify toggle"
  in the Work-Life Balance bullet) says the same.
- `UserPreferencesDataStore.kt:75-77` is honest:
  > "Classifier auto-classification is always on — the keyword classifier
  > runs on every task creation path via
  > `TaskRepository.resolveLifeCategoryForInsert`."

The toggle was scoped, never built, and the docs were not retracted.
The only "auto-classify" toggle that actually exists is the
**Eisenhower** one (`AiSection.kt:57-62`,
`UserPreferencesDataStore.KEY_EISENHOWER_AUTO_CLASSIFY = "eisenhower_auto_classify"`).

**Risk classification.** GREEN. Cosmetic doc drift; does not affect
runtime behaviour. The keyword classifier runs unconditionally, which
is the documented backend semantic.

**Recommendation.** STOP-no-work-needed for runtime. If the operator
finds the docs misleading, a tiny follow-up PR can either (a) build
the toggle (cheap — one DataStore key + one composable row + one
guard at `TaskRepository.resolveLifeCategoryForInsert`), or
(b) delete the misleading sentences from `WorkLifeBalanceSection.kt`
KDoc and `CLAUDE.md`. (b) is the lower-cost choice.

---

## Ranked improvements

| Rank | Item | Wall-clock saved | Impl cost | Ratio | Verdict |
|------|------|------------------|-----------|-------|---------|
| 1 | Claude-backed Life Category Auto button (Item 1) | High — every classifier-miss task gets a real category instead of UNCATEGORIZED, which propagates into Today balance bar accuracy + weekly report fidelity for the lifetime of the user's account | Medium — backend endpoint + remote classifier + VM wiring + tests, but the Eisenhower precedent prescribes every line | High | PROCEED |
| 2 | Task Mode + Cognitive Load Auto buttons (Item 2) | Medium — same fidelity argument, narrower-impact metrics | Medium — 2× the Item 1 work | Medium | DEFER (ask operator post-merge) |
| 3 | `updateTask` re-classifies (Item 3) | Low — only matters when programmatic updates rewrite title/description without going through the editor | Low — one line at `TaskRepository.kt:288` | Medium | DEFER |
| 4 | Doc drift on missing Balance toggle (Item 4) | None (runtime); marginal (operator confusion) | Trivial | Low | STOP-no-work-needed |

## Anti-patterns flagged but not fixing here

- The local `LifeCategoryClassifier` keyword set at
  `LifeCategoryClassifier.kt:81-151` is small and English-only.
  Expanding it is *not* the right fix — Claude is the answer for
  unbounded vocabulary. Don't grow the keyword list as a workaround.
- The three parallel `autoPick*` methods in `AddEditTaskViewModel`
  are copy-paste; abstracting them prematurely is tempting but
  blocks Item 1's narrow scope. After Items 1 + 2 ship, the third
  copy is the pivot point for an abstraction (one
  `AutoPickRunner<T>(local, remote, ...)` instead of three).

---

## Phase 3 — Bundle summary

**Item 1 (PROCEED, RED) — Claude-backed Life Category Auto button.**
Shipped as PR #1176 (`feat(ai): route OrganizeTab Auto button on Life
Category through Claude`). Single coherent scope, single PR — no
fan-out. Branch `claude/fix-auto-categorization-E1cOH`.

Files touched (11):

- Backend (4):
  - `backend/app/services/ai_productivity.py` — new
    `classify_life_category_text()` mirroring
    `classify_cognitive_load_text` exactly (Haiku, 256 tokens,
    retry-once-on-malformed).
  - `backend/app/routers/ai.py` — new
    `POST /ai/life-category/classify_text` endpoint, rate-limited
    20/min via `life_category_classify_text_rate_limiter`, gated by
    `require_ai_features_enabled`.
  - `backend/app/schemas/ai.py` — new
    `LifeCategoryClassifyTextRequest` / `LifeCategoryClassifyTextResponse`.
  - `backend/tests/test_ai_productivity.py` — 4 new tests
    (`TestLifeCategoryClassifyText`).
- Android (6):
  - `app/.../data/remote/LifeCategoryRemoteClassifier.kt` (new) —
    offline-safe `Result<Classification>` mirroring
    `EisenhowerClassifier`.
  - `app/.../data/remote/api/ApiModels.kt` — DTOs.
  - `app/.../data/remote/api/PrismTaskApi.kt` — `classifyLifeCategoryText`.
  - `app/.../ui/screens/addedittask/AddEditTaskViewModel.kt` —
    inject classifier, add `lifeCategoryAutoPickInFlight` state,
    add `tryUpgradeLifeCategoryWithClaude()` private method called
    from `autoPickLifeCategory(force = true)`.
  - `app/.../ui/screens/addedittask/tabs/OrganizeTab.kt` —
    `AutoPickButton` swaps icon for spinner while `loading = true`.
  - `app/src/test/.../AddEditTaskViewModelTest.kt` — 5 new tests
    covering all branches (force-fires-Claude, non-force-skips-Claude,
    AI-features-off, remote-failure-preserves-local, mid-flight-manual-pick-wins).
- `CHANGELOG.md` — Added entry under Unreleased.

**Items 2–4 (DEFER / STOP-no-work-needed).** Not shipped this batch by
design. Ranked table at the top of this doc captures the cost/value
case for picking them up later.

**Re-baselined wall-clock-per-PR estimate.** Audit doc Phase 1 + Phase
2 implementation + Phase 2 tests + Phase 3 doc-back-fill, single
session: roughly 30 minutes of agent wall-clock for a one-item audit
when an analogous backend endpoint (`classify_eisenhower_text`,
`classify_cognitive_load_text`) and an analogous Android remote
classifier (`EisenhowerClassifier`) already prescribe every line.
That's the "cheap end" of the audit-first envelope; mega-audits
(`PRE_PHASE_F_MEGA_AUDIT.md`) are the expensive end.

**Memory entry candidate (surprising / non-obvious).**
The backend already shipped a `classify_cognitive_load_text` endpoint
(`backend/app/routers/ai.py:255`) that the Android client never wired
up. Two independent "AI upgrade over keyword classifier" features
(Cognitive Load, Life Category) were both half-built — backend ready,
Android local-only. Worth a memory entry: when you're about to add a
Claude-backed classifier endpoint, grep `routers/ai.py` first — it
may already exist.

**Schedule for next audit.** Open question Item 2 (Task Mode +
Cognitive Load Auto buttons): operator should explicitly say "yes,
same fix" or "no, leave them" before scheduling. If yes, batch them
into one follow-up audit (single doc, two PROCEED items, one PR per
backend endpoint + one combined Android PR — three PRs total) to
avoid relitigating the rationale per item.

---
