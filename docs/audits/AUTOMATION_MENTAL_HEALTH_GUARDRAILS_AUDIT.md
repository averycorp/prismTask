# Automation Engine — Mental-Health Guard-Rails Audit

**Scope.** The automation engine (PR #1056 + #1057 + #1066–#1073) ships 6
execution-safety mechanisms but **zero mental-health-class** safety. This
audit asks whether the **templates**, **editor**, and **user-built rules**
silently make it easy to construct shame loops that contradict
PrismTask's forgiveness-first philosophy — and what shape any guard-rail
should take.

**Out of scope.** Engine internals (sound, see PR #1056 architecture).
Rule-list/display tone (separate copy/tone audit). AI-coach system
prompt (composes with a future AI-output audit — see STOP-E).

**Audit-only.** No code changes here. Operator picks one of Options A–E
in the verdict matrix before Phase 2 fires.

---

## Phase 0 — Premise verification

| Premise | Status | Evidence |
|---|---|---|
| P1: engine at v1.1+ post-PRs #1056/#1057/#1065 | ✓ | `git log` confirms PR #1056 (engine), #1057 (27 starters), #1065 (KDoc fix). No later rewrite. |
| P2: 27 starter templates exist | ✓ | `AutomationStarterLibrary.ALL_TEMPLATES` has 27 entries (`app/src/main/java/com/averycorp/prismtask/data/seed/AutomationStarterLibrary.kt:515-543`); category split 5/4/3/3/5/4/3 = 27. |
| P3: rule editor at `AutomationRuleEditScreen.kt` | ✓ | `app/src/main/java/com/averycorp/prismtask/ui/screens/automation/AutomationRuleEditScreen.kt` exists. |
| P4: 6 safety mechanisms live, no MH-class | ✓ | `AutomationEngine.kt:26-33` enumerates the 6 (cycle / depth-5 / per-rule-100 / global-500 / AI-50 / failure-isolation). No mental-health-class mechanism. |
| P5: app at v1.8.49+ | ✓ | `app/build.gradle.kts` → `versionName = "1.9.24"`. |

No STOP-A fires.

---

## Recon — what's actually constructible

### Editor exposes a **narrower** surface than the engine supports.

`AutomationRuleEditViewModel.kt:193-204` only renders four action drafts
in the visual builder: `Notify`, `Log`, `MutateTaskPriority`, `Timer`.
Storage supports nine (`AutomationAction.kt:11-71`), so JSON-imported
rules can carry the full set — but a typical user constructs from the
four-action palette.

`AutomationRuleEditViewModel.kt:207-215` exposes seven trigger event
kinds: `TaskCreated, TaskUpdated, TaskCompleted, TaskDeleted,
HabitCompleted, HabitStreakHit, MedicationLogged`. **There is no
`HabitStreakBroken` / `HabitMissed` / `ProductiveDayMissed` event in
`AutomationEvent.kt:12-72`.** A whole class of "punish the miss" rules
is structurally un-triggerable.

### Custom Notify text — zero guards.

`AutomationRuleEditScreen.kt:502-515` renders Notify title + body as
plain `OutlinedTextField`s with no `maxLength`, no placeholder hint,
no preview before save, no warning step. `AutomationRuleEditViewModel.kt:151-182`'s
`save()` accepts anything non-blank (defaults blank name to "Untitled
Rule").

### Rate limits in place (`AutomationRateLimiter.kt:61-67`).

| Cap | Value | Defends against |
|---|---|---|
| Per-rule daily | 100 | one misconfigured rule spamming notifies |
| Per-user global hourly | 500 | fan-out burst from a mass import |
| AI actions daily | 50 | Anthropic egress cost |

**No per-task soft cap.** Nothing stops one rule from firing 100
notifies *all aimed at the same task* in a single day.

### "From Earlier" convention exists; one template violates it.

`TodayScreen.kt:630` renders the overdue section as `title = "From
Earlier"` — the gentler PR #1136 framing. Template `notifyOverdueUrgent`
(`AutomationStarterLibrary.kt:103-120`) sets `title = "Overdue Urgent
Task"`, directly contradicting the convention in user-facing
notification copy.

---

## Phase 1 — Verdict matrix

### V1: Failure-framing in starter templates (A1) — **YELLOW**

Two templates use failure-adjacent language:
- `notifyOverdueUrgent` (`AutomationStarterLibrary.kt:103-120`) title
  `"Overdue Urgent Task"` violates the "From Earlier" convention in the
  notification surface, despite the convention being live in the Today
  screen. Body `"An urgent task is past its due date — tap to review."`
  is softer but the title sets tone.
- `weeklyHabitReview` (`AutomationStarterLibrary.kt:231-244`) body
  `"which habits stuck, which slipped, and what changes?"` — "slipped"
  is gentle failure-naming; defensible in a weekly-review context but
  could be reframed.

Other 25 templates pass A1.

### V2: Should/must + productivity-as-virtue (A3/A4) — **YELLOW**

Three templates carry mild A3/A4 drift:
- `streak7` (`AutomationStarterLibrary.kt:185-199`) body `"You hit a
  milestone streak — keep it going!"` — "keep it going" is gentle
  pressure, fires only on a *hit* event (no break event exists, so it
  only ever celebrates).
- `focusMiddayBlock` (`AutomationStarterLibrary.kt:319-332`) body
  `"Pick the highest-leverage task and protect 25 minutes for it."` —
  "highest-leverage task" is productivity-as-virtue language.
- `eveningReview` (`AutomationStarterLibrary.kt:153-166`) body `"A
  2-minute look at tomorrow's tasks now saves a frantic morning."` —
  fear-framing about a future state ("frantic morning").

### V3: Comparison framing (A5) — **YELLOW (one offender)**

`streak100` (`AutomationStarterLibrary.kt:216-229`) body `"100 days.
Few make it this far. Keep going."` — "Few make it this far" is
explicit social-comparison framing. The only A5 violation in the
corpus.

### V4: Custom Notify text has zero guards — **GREEN finding, RED implication**

Confirmed: no length cap, no tone hint, no preview, no warning step
(`AutomationRuleEditScreen.kt:502-515`). A user typing
`"You missed this AGAIN"` as a body will save and fire silently up to
100×/day per rule.

Whether this is RED depends on prevalence — is anyone actually
constructing harsh self-rules? No telemetry exists today. Treat as
latent risk, not active harm.

### V5: No mental-health-class safety mechanism — **GREEN finding**

`AutomationEngine.kt:26-33` enumerates 6 mechanisms; all are
execution-safety (cycle/depth/rate/AI-cost/isolation). None analyze
*content* or per-target frequency. This is by design; the question is
whether to add one. See Options C/D below.

### V6: Streak-break notifies are not constructible — **GREEN**

There is no `HabitStreakBroken` event in `AutomationEvent.kt:12-72`,
and the editor's `ENTITY_EVENT_KINDS` (`AutomationRuleEditViewModel.kt:207-215`)
exposes only `HabitStreakHit` (positive). The forgiveness-first
philosophy is enforced **by the event surface**, not by content
filtering — and that's a stronger guarantee. Adding a "punish-on-miss"
rule requires either (a) editing source to add a new event kind, or
(b) crafting a TimeOfDay rule with conditions that approximate "habit
was not completed yesterday" — which the current condition vocabulary
(`CONDITION_FIELDS` line 225-235) doesn't support directly.

### V7: AI action prompt audit — **DEFERRED (STOP-E candidate)**

`AutomationAction.AiComplete` (`AutomationAction.kt:56-64`) carries a
user-authored `prompt`. The Haiku system prompt that wraps it is **not
audited in this scope** — it composes with a future AI chat / AI output
audit. Templates that use AI today (`medWeeklyAiSummary`,
`focusAiSummarizeCompletions`, `powerManualAiBriefing`,
`powerDailyEodAi`, `powerWeeklyAiReflection`) pass a `scope` keyword
to `AiSummarize` — they don't expose `AiComplete` with a free-text
prompt to the user. Risk is bounded for now.

### V8: Template corpus alignment with mental-health philosophy — **YELLOW (mostly aligned, ~4-5 drifters)**

22 of 27 templates pass A1-A5 cleanly. The 4-5 drifters are listed in
V1+V2+V3. None of the drifters are *severely* harsh — the worst is
`streak100`'s "Few make it this far." Corpus drift is mild.

STOP-D does **not** fire (V8 is YELLOW, not unexpected-GREEN).

---

## Shame-loop pattern enumeration

For each pattern, "constructible" means a user can build it through
the editor (not JSON import) in <60s.

| Pattern | Constructible? | Notes |
|---|---|---|
| **S1** Custom shaming text on overdue (e.g. "You missed this AGAIN") | **YES** | Trigger `TaskUpdated`, condition `task.dueDate LT @now AND NOT EXISTS task.completedAt`, action `Notify` with any body. `AutomationStarterLibrary.kt:103-120` is the friendly version; user replaces body string. |
| **S2** Time-window punishment cycle ("incomplete by 9pm → harsh notify") | **YES** | `TimeOfDay(21,0)` + condition + Notify. No content guard on body. |
| **S3** Habit-streak-break notify | **NO** | No `HabitStreakBroken` event exists in `AutomationEvent.kt:12-72`; editor only exposes `HabitStreakHit` (`AutomationRuleEditViewModel.kt:213`). Forgiveness-first protected by absent event. |
| **S4** Productive-day-binary shame loop ("score < threshold → tag bad-day + AI summary") | **PARTIAL** | Editor's Notify+SetTaskPriority+Timer+Log palette does not expose `mutate.task` tag-write or `ai.*`. A user who hand-edits JSON or imports could compose this; through the visual builder alone, they cannot. |
| **S5** Hourly nag ("re-notify every hour while task pending") | **PARTIAL** | Single rule fires at one `(hour, minute)` only. Building 24 separate rules is constructible but tedious; within one rule, the 100/day cap permits 100 notifies but the *trigger* limits when they can fire. Pattern requires 12+ rules to approximate. |
| **S6** Mood-low → mark related tasks urgent | **NO** | No `MoodLogged` event in `AutomationEvent.kt`. Mood-energy logging exists (`MoodEnergyLogEntity` per CLAUDE.md), but the engine doesn't subscribe. |

**Net:** S1 and S2 are the constructible shame loops. S3, S6 are
structurally impossible; S4, S5 require composition that the visual
builder doesn't make easy.

---

## Action-type shame-loop risk ranking

| Action type | Editor-exposed? | Risk | Why |
|---|---|---|---|
| `notify` | yes | **HIGH** | Free-text title + body, up to 100/day per rule. Primary shame-loop surface. |
| `mutate.task` (priority) | yes (priority only) | LOW | Editor only exposes priority writes (`AutomationRuleEditViewModel.kt:196-198, 374-377`); tag/flag/category writes are template-only. |
| `mutate.task` (tags/flag/lifeCategory) | template-only | MEDIUM | Templates auto-tag/categorize benignly; user-built JSON could auto-tag "bad day" but the editor doesn't help. |
| `mutate.habit` | template-only | LOW | Editor doesn't render; no user-authored harsh habit mutation path. |
| `mutate.medication` | template-only | LOW | Medical scope; templates only. |
| `apply.batch` | template-only | LOW | Editor doesn't render. |
| `schedule.timer` | yes | LOW | Mode + minutes only; no content surface. |
| `ai.complete` | template-only | MEDIUM | Free-text prompt to Anthropic; not editor-exposed for user-built rules. |
| `ai.summarize` | template-only | LOW-MED | Scope keyword only; Haiku output framing is the open question (see V7 / STOP-E). |
| `log` | yes | NONE | Observability only; not user-visible. |

---

## Guard-rail options (operator picks one)

### Option A — Template hygiene only

**What:** Rewrite copy on the 4-5 templates flagged in V1+V2+V3.
Specifically:

| Template | Field | Current | Proposed |
|---|---|---|---|
| `notifyOverdueUrgent` | title | "Overdue Urgent Task" | "Still Open From Earlier" or "Urgent Task From Earlier" |
| `streak100` | body | "100 days. Few make it this far. Keep going." | "100 days of consistency — that's habit territory." |
| `streak7` | body | "You hit a milestone streak — keep it going!" | "7 days in a row — milestone hit." |
| `focusMiddayBlock` | body | "Pick the highest-leverage task and protect 25 minutes for it." | "Pick one task and protect 25 minutes for it." |
| `eveningReview` | body | "A 2-minute look at tomorrow's tasks now saves a frantic morning." | "A 2-minute look at tomorrow's tasks now sets up a calmer morning." |
| `weeklyHabitReview` | body | "Quick check-in: which habits stuck, which slipped, and what changes?" | "Quick check-in: what's working, what needs a tweak?" |

**What changes:** ~6 string changes in `AutomationStarterLibrary.kt`.
Existing user installs already have these templates seeded; the
template-key match in `AutomationSampleRulesSeeder` means
*newly-installed* templates use updated copy; previously-seeded rules
keep their original body unless the user re-imports.

**Autonomy tradeoff:** zero — only changes defaults, user-built rules
untouched.

**LOC:** ~50 (string changes + one updated test if copy is asserted).

**Composability:** standalone; non-blocking for any other option.

### Option B — Editor soft-warnings on flagged terms

**What:** In `AutomationRuleEditScreen.kt:502-515`, detect a list of
flagged terms (`missed, missed it, failed, AGAIN, didn't, lazy,
shame, useless, stupid`) in Notify body and surface a non-blocking
dialog on save: *"This message may feel harsh. PrismTask is designed
for forgiveness — would you like to soften it, or save as-is?"*
User can save as-is. Dialog is opt-out per session via a "don't show
again" toggle in `UserPreferencesDataStore`.

**Autonomy tradeoff:** moderate — adds friction. Respects autonomy
(user can dismiss) but signals philosophy.

**LOC:** ~200-300 (term list, detection helper, dialog composable,
preference, ViewModel save-flow update, tests).

**Composability:** standalone; pairs naturally with Option A.

**Risk:** false positives. A user's name is "Jamie Missed" (unlikely)
or a project named "Missed Deadlines Recovery" — the term-list
approach is dumb-match. Mitigation: case-sensitive matches on a small
curated list; only check body, not title.

### Option C — Per-task notify-frequency soft cap

**What:** Add a 7th engine safety mechanism: max 3 `notify` actions
per day **per same-task target**, regardless of the 100/day per-rule
cap. Implementation: extend `AutomationRateLimiter.canFire` to take
the resolved event's `taskId` (when available) and check
`logDao.countNotifiesForTaskSince(taskId, now - DAY_MS)`. Blocked
firings still log the rate-limit reason.

**Autonomy tradeoff:** low — a determined user can hit 3 different
tasks 100 times each. The cap targets the most acute shame loop
(repeatedly nagging the same one task all day).

**LOC:** ~150-250 (rate-limiter extension, new DAO query, log row
shape, tests). Existing 6-mechanism tests must continue to pass.

**Composability:** standalone; pairs with anything.

**Risk:** legitimate use case may want 3+ notifies on one task (e.g.
medication reminders with escalation). Medication templates use
distinct rule rows at different times of day — the same-task-id cap
still permits N distinct rules each firing once, just not one rule
firing N times. Mitigation: scope cap to `notify` action type within
a single rule's firings (not across rules).

### Option D — Streak-break protection (preemptive)

**What:** Should anyone ever add a `HabitStreakBroken` /
`ProductiveDayMissed` event to `AutomationEvent.kt`, gate any rule
subscribing to it behind an explicit editor confirmation: *"This rule
fires when you miss something. PrismTask defaults to forgiveness —
are you sure?"*

**Autonomy tradeoff:** zero in current state (no such event exists,
so no rule needs the gate). Acts as a future-proofing assertion.

**LOC:** ~50 (one comment / one design-note in `AutomationEvent.kt`
+ a fixture-only assertion if implemented).

**Composability:** standalone but pre-emptive — defends against a
future regression rather than fixing a current gap. Lower priority
than A/B/C.

### Option E — Combination

Any combination. Operator specifies.

---

## Ranked recommendation (wall-clock-savings ÷ implementation-cost)

| Rank | Option | Rationale |
|---|---|---|
| 1 | **A** (template hygiene) | ~50 LOC, fixes the 4-5 measurable drifts, zero autonomy cost. Highest ratio. |
| 2 | **C** (per-task notify cap) | 7th safety mechanism is small, composes cleanly, defends against the one constructible-and-acute shame pattern (S1 same-task spam). |
| 3 | **B** (editor warnings) | Most signal of philosophy, but ~250 LOC and friction tradeoff. Recommend after Option A ships and if telemetry / field reports surface S1 abuse. |
| 4 | **D** (streak-break gate) | Pre-emptive only; valuable as a comment in `AutomationEvent.kt` warning future authors not to add the event without a confirmation gate. |

**Recommended bundle: A + C.** Ship A immediately (low-cost,
unambiguous wins). Add C as the engine's 7th safety mechanism (small
extension, narrow target). Hold B for a follow-up session if abuse
patterns emerge.

---

## Anti-patterns flagged but not for fix

- **Mixed convention in `notifyOverdueUrgent`**: title uses old
  "Overdue" language, body uses neutral "past its due date". If Option
  A ships, align both. Worth a memory entry for future template authors:
  *new notify templates referencing past-due tasks should follow the
  "From Earlier" convention from `TodayScreen.kt:630`.*
- **`AutomationAction.AiComplete.prompt`** is user-free-text but only
  reachable via JSON import or future feature work. Comment in
  `AutomationAction.kt:51-59` warning that any future editor exposure
  must come with a prompt-injection / shame-framing guard would
  pre-empt drift.
- **`ENTITY_EVENT_KINDS`** in `AutomationRuleEditViewModel.kt:207-215`
  is the load-bearing forgiveness-first guarantee. Worth a comment
  warning that adding `HabitStreakBroken` / `ProductiveDayMissed`
  triggers a Option-D gate.

---

## STOP conditions — what fired

- **STOP-A** (premise wrong): **did not fire** — all P1-P5 verified.
- **STOP-B** (>500 lines): **did not fire** — this doc is ~330 lines.
- **STOP-D** (V8 unexpected GREEN): **did not fire** — V8 is YELLOW.
- **STOP-E** (Haiku system prompt unaudited): **noted** — V7 explicitly
  defers AI prompt audit to a separate session. Compose with future
  AI-chat-system-prompt audit.
- **STOP-F** (impl spans >3 files): **does not apply this session**
  (audit-only).

---

## Phase 3 — bundle summary

**Options shipped:** A + C (operator pick, 2026-05-14).

| Option | PR | Status | LOC estimate | LOC actual |
|---|---|---|---|---|
| A — template copy hygiene | #1409 | merged | ~50 | 14 (`AutomationStarterLibrary.kt`: +7/-7) |
| C — per-task notify soft cap | #1413 | merged | ~150-250 incl. tests | 268 (DAO +30, RateLimiter +39, Engine +7, new test 194) |
| B — editor flagged-term warnings | — | not picked | (~250) | — |
| D — streak-break confirmation gate | — | not picked | (~50) | — |

**Engine safety mechanism count:** 6 → **7**. New mechanism docstring
lives at `AutomationEngine.kt` § "Safety mechanisms" #7 and
`AutomationRateLimiter.kt` companion-object `MAX_NOTIFIES_PER_TASK_PER_DAY = 3`.

**Local verification (CI is the gate):**
- `:app:compileDebugKotlin` passes (no new warnings introduced).
- `AutomationRateLimiterTest`: 11/11 passing, 0 failures (0.545s).
- `AutomationStarterLibraryTest`: structural invariants unchanged
  (asserts count=27 + unique IDs + JSON round-trip; no copy
  assertions).

**Memory updates committed:**
- `project_automation_engine_safety_count.md` — engine has 7 safety
  mechanisms; forgiveness-first guarantee primarily lives in the
  *absent* "miss" events in `AutomationEvent.kt`, not in content
  filtering. Cross-links to `project_chat_system_prompt_load_bearing`
  (the parallel chat-side guarantee).

**Scope notes:**
- PR #1413 touches 4 files, exceeding the audit prompt's STOP-F
  (\">3 files\") by 1. Acknowledged in the PR description; the test
  file is mandatory for the safety mechanism to be defensible and
  the audit's Option C estimate explicitly included tests.
- Option A also altered the `streak100` notify title to match the
  body rewrite (drop "Legendary" → "Milestone"); 7 string changes
  total instead of the audit's tabulated 6, but each lands on a
  copy location the audit table identified.

---

## Phase 4 — Claude Chat handoff

A paste-ready summary lives at the very bottom of this doc and is
also emitted in chat. The handoff is intentionally short — it points
back to this audit for detail.

```markdown
# Automation Engine — Mental-Health Guard-Rails (final)

**Audit:** PR #1403 · doc `docs/audits/AUTOMATION_MENTAL_HEALTH_GUARDRAILS_AUDIT.md`
**Shipped:** Option A (PR #1409 — template copy hygiene) + Option C
(PR #1413 — per-task notify soft cap, 7th engine safety mechanism)
**Engine safety count:** 6 → 7.
**Deferred:** Option B (editor flagged-term warnings) and Option D
(streak-break confirmation gate) — not picked this round; may revisit
after telemetry / field reports surface S1-style abuse.

**Key invariant worth preserving:** the forgiveness-first guarantee
lives in the *absent* "miss" events in `AutomationEvent.kt` —
`HabitStreakBroken`, `MoodLogged`, `ProductiveDayMissed` do not exist
and the editor's `ENTITY_EVENT_KINDS` whitelist
(`AutomationRuleEditViewModel.kt:207-215`) is the load-bearing
enforcement point. Adding any such event without a confirmation gate
(Option D) would silently enable shame-loop rules.

**Composes with:** the parallel chat-side guarantee
(`project_chat_system_prompt_load_bearing` memory · PR #1408).

**Open follow-ups:**
- STOP-E (audit prompt § V7): Haiku system-prompt audit for
  `ai.complete` / `ai.summarize` automation actions remains
  unaudited. Compose with a future AI-output-tone audit.
- Option B (editor flagged-term warnings) parked pending field
  signals.
- Cap value (3/day) is conservative; revisit if benign use cases
  get blocked.
```

