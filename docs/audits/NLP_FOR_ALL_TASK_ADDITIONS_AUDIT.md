# NLP for All Task Additions — Audit

**Scope.** "I want every method of adding tasks to run through the NLP, same
as quick add." Audit every code path that creates a `TaskEntity`, decide
which should be wired through `NaturalLanguageParser`, and identify the gaps.

**Optimization target.** UX consistency — a user typing
`"Buy milk tomorrow #shop !2"` should get the same auto-parsed result no
matter which surface they typed it into.

**Suspected failure modes.** (a) UX inconsistency between Quick Add and
the full editor; (b) double-parsing structured data (templates, sync,
import) that would corrupt fields; (c) silent token-stripping surprising
users who didn't intend NLP syntax.

---

## Reference: how Quick Add does it today

`ui/components/QuickAddViewModel.kt:485-584` is the canonical pipeline:

1. `parser.parseRemote(text)` (Pro) or `parser.parse(text)` (Free) →
   `ParsedTask` (title, dueDate, dueTime, tags[], projectName, priority,
   recurrenceHint, lifeCategory, taskMode, cognitiveLoad, templateQuery).
2. `resolver.resolve(parsed)` (`domain/usecase/ParsedTaskResolver.kt`) →
   resolves tag names → IDs (creating unmatched), resolves project name
   → ID (creating unmatched), maps recurrenceHint → `RecurrenceRule`.
3. Fallback `LifeCategoryClassifier.classify(title)` if the parser
   didn't pick up a `#category` tag.
4. `TaskEntity(...)` built from resolved fields → `taskRepository.insertTask`.
5. Tags + usage log writes.

That sequence — **parse → resolve → classify-fallback → insert → tag/log**
— is what every other "user-typed-text → task" path needs to mirror to
match the user's stated intent. Internal/structured paths (templates,
sync, import, recurrence copies) intentionally do not have user-typed
text and are out of scope.

---

## Per-entry-point verdicts

### 1. Add/Edit Task form — title field (RED → PROCEED)

`ui/screens/addedittask/AddEditTaskViewModel.kt:937-1022` (`saveTask`).
The form trims `title`, builds a `TaskEntity` from the structured fields
the user filled in via pickers, and inserts. **The title string is
never run through `NaturalLanguageParser`.** A user who types
`"Buy milk tomorrow #shop !2"` in the form's title field gets a literal
title with no date, no tag, no priority.

This is the single biggest gap and is exactly what the scope is asking
about. Every other UI surface either already runs NLP (Quick Add, voice,
multi-create, widget Quick-Add) or doesn't accept user-typed text.

**Design ambiguity** the implementer must resolve:

- **When to parse.** Save-time only (cheapest, least surprising) vs.
  on-type with a preview chip ("we'll set due=tomorrow, tag=shop —
  Apply / Dismiss") vs. on-blur of the title field. Quick Add is
  effectively save-time because the user submits the bar.
- **Override policy.** If the user typed `"...tomorrow"` *and* picked
  a different date in the date picker, which wins? Recommend: **the
  user's manually-set field wins**; NLP only fills fields the user
  left empty. This avoids the worst surprise (silent override).
- **Title stripping.** Quick Add strips parsed tokens from the title
  (`"Buy milk tomorrow #shop"` → title=`"Buy milk"`). The form has a
  visible title field; stripping silently at save would confuse users.
  Recommend: **strip only if NLP found at least one structured field
  to apply, AND show a one-line "Parsed: due tomorrow, tag #shop"
  status under the field** (or a toast on save).
- **Edit vs. create.** When editing an existing task, NLP should be
  off by default — re-parsing a saved title is almost never what the
  user wants and would clobber edits.

Recommendation: **PROCEED**. Apply NLP only on *create* (existing == null
in `saveTask`), only fill fields the user hasn't manually set, and surface
what was parsed. Reuse `ParsedTaskResolver` and the
`LifeCategoryClassifier` fallback so behavior matches Quick Add line-for-line.

### 2. Subtasks created from the editor (YELLOW → PROCEED, scoped)

`AddEditTaskViewModel.kt:998-1013` flushes `pendingSubtasks` into
`TaskEntity(title = sub.title, parentTaskId = ...)` rows directly. Same
gap as #1 but for subtask titles. Subtasks usually inherit the parent's
project / due date, so the value of NLP is narrower (mostly tags,
priority, life-category). Worth doing at the same time as #1 to keep
the pattern consistent — if not, a user who types
`"call vendor !3 #work"` as a subtask gets the literal string.

Recommendation: **PROCEED**, but limit to `parse()` only (offline) and
do not auto-create projects from subtask titles — that lives at the
parent task. Bundle into the same PR as #1 if scope permits.

### 3. Paste Conversation extractor (YELLOW → PROCEED)

`PasteConversationViewModel:47-80` runs `ConversationTaskExtractor`
(pure regex), which produces candidate task titles like
`"Buy milk tomorrow"`, then inserts each candidate **without** running
the result through `NaturalLanguageParser`. A title like
`"send report by Friday !2"` extracted from chat prose would land with
no due date and no priority.

The extractor's job is "find tasks in prose"; the parser's job is
"interpret the structure inside one task line." Running them in series
is the natural composition.

Recommendation: **PROCEED**. After
`ConversationTaskExtractor.extract`, pipe each candidate title through
`parser.parse()` (offline only — extraction is already a local
fallback in some paths) + `resolver.resolve()` before inserting. Do
not call `parseRemote` per candidate — that would N×backend the cost.

### 4. Chat Screen task creation (YELLOW → DEFER)

`ChatViewModel:405-434`. The chat AI (Haiku) already extracts a
structured `title` + fields from conversation. Re-parsing the title
through `NaturalLanguageParser` would either be a no-op (if the AI
already pulled out date/tags into separate fields) or it would
double-strip tokens that the AI left in the title for readability.

Risk of regression > value. The chat AI is strictly more capable than
the regex parser; running the weaker tool downstream of the stronger
tool inverts the abstraction.

Recommendation: **DEFER** unless we see concrete cases where chat
output has unparsed `#tags` or `tomorrow` literals leaking through.
If it does, the right fix is in the chat-AI prompt, not in adding NLP.

### 5. Voice Input (GREEN — already covered)

`QuickAddViewModel:253` — voice transcripts that aren't voice
*commands* fall through to `onSubmit()`, which is the Quick Add
pipeline. No change needed.

Recommendation: **STOP-no-work-needed**.

### 6. Multi-Create / Multi-Task Local (GREEN — already covered)

`MultiCreateViewModel:63-82` (Pro: `extractFromText` → Haiku) and
`QuickAddViewModel:594-670` (Free fallback per-segment `parser.parse`).
Both surfaces already run NLP per task.

Recommendation: **STOP-no-work-needed**.

### 7. Widget Quick-Add (GREEN — already covered)

`widget/QuickAddWidget.kt` launches `MainActivity` into the Quick Add
flow, which then runs through `QuickAddViewModel.onSubmit`. Same
pipeline as in-app Quick Add.

Recommendation: **STOP-no-work-needed**.

### 8. Task Templates instantiation (GREEN — out of scope)

`TaskTemplateRepository:119-160`. Templates are pre-stored
`TaskEntity` blueprints with their date / priority / tags already in
structured fields. There's no user-typed free text to parse. Running
NLP on a template title would either be a no-op or, worse, would strip
deliberate tokens like `"#weekly review"` from the saved template name.

Recommendation: **STOP-no-work-needed**. (Template *picker* shortcuts
typed in Quick Add — `/weekly` — are already handled at
`QuickAddViewModel:447` via `extractTemplateQuery`.)

### 9. Recurrence spawn (GREEN — out of scope)

`TaskRepository:353-441`. When a recurring task completes, the next
occurrence is a copy of the original `TaskEntity` with a recalculated
due date. The original was already NLP-parsed at first insert; the
title at this point has had its tokens stripped. Re-parsing would do
nothing on the first cycle and would risk corruption if the title
happens to look like NLP syntax.

Recommendation: **STOP-no-work-needed**.

### 10. Notification "Complete" action (GREEN — out of scope)

`CompleteTaskReceiver:16-51`. Marks a task complete; if recurrent,
delegates to #9. No new user-typed text.

Recommendation: **STOP-no-work-needed**.

### 11. Undo deletion (GREEN — out of scope)

`TodayViewModel:916-933`. Re-inserts the previously-saved entity. Re-
parsing the original title would re-strip already-stripped tokens —
no-op at best, corruption at worst.

Recommendation: **STOP-no-work-needed**.

### 12. JSON / CSV import (GREEN — out of scope)

`data/export/DataImporter.kt:440-478`. Deserializes structured task
fields from a backup file. Running NLP on imported titles would
silently mutate user data on import — a serious data-integrity risk.

Recommendation: **STOP-no-work-needed**.

### 13. Calendar sync (GREEN — out of scope)

`CalendarSyncRepository` / `CalendarSyncWorker` pulls calendar events
into tasks. Calendar events already have structured start/end times;
running NLP on the event summary could conflict with the calendar's
own date and would surprise users (e.g. a meeting titled
`"Q3 review tomorrow"` would re-set the due date away from the
calendar event time).

Recommendation: **STOP-no-work-needed**. (If a future enhancement
wants to lift `#tags` out of calendar event summaries, scope that as
its own audit — much narrower than full NLP.)

### 14. Backend / Firestore sync pull (GREEN — out of scope)

`SyncService` pulls server-resolved tasks. Server is the source of
truth for these rows. Local NLP would diverge them from the server
copy and cause sync churn.

Recommendation: **STOP-no-work-needed**.

### 15. Self-Care nudge (GREEN — out of scope)

`TodayViewModel:315-328` inserts a hard-coded `"Self-care break"`
title. No user input; nothing to parse.

Recommendation: **STOP-no-work-needed**.

### 16. Onboarding starter tasks / Project templates / Schoolwork (GREEN — out of scope)

`OnboardingViewModel`, `ProjectImporter`, `SchoolworkViewModel`. All
preset / structured content. Same reasoning as #8.

Recommendation: **STOP-no-work-needed**.

---

## Anti-patterns to avoid in implementation

- **Don't NLP-parse on every keystroke in the editor.** Quick Add is a
  one-shot submit; the editor is a multi-field form. On-type parsing
  would fight the user's manual edits.
- **Don't override manually-set fields.** The fundamental contract:
  if the user touched a date picker / priority chip / project picker,
  NLP must not clobber it. NLP fills *empty* fields only.
- **Don't strip the title silently with no feedback.** In the editor,
  the title is visible; if NLP strips `"tomorrow #shop"` from
  `"Buy milk tomorrow #shop"`, the user must see *why*.
- **Don't run `parseRemote` in a tight loop.** The conversation
  extractor and any future bulk-import path should use `parse()` to
  avoid N×backend cost.
- **Don't add NLP to internal / structured paths** (#8–#16) chasing
  "consistency." The premise of the scope is *user-typed free text*,
  and those paths don't have any.

---

## Ranked improvements (savings ÷ cost)

| Rank | Item | Surface | Cost | Savings | Verdict |
|------|------|---------|------|---------|---------|
| 1 | Add/Edit form — title NLP on create | UI | M (~150–250 LOC + design call on stripping/feedback) | High — closes the #1 inconsistency between Quick Add and the editor | **PROCEED** |
| 2 | Paste Conversation — pipe extracted titles through parser | UI | S (~30–60 LOC) | Med — extracted prose often contains dates/tags/priority that currently leak through as literal strings | **PROCEED** |
| 3 | Subtasks — NLP on create (form path) | UI | S (~20–40 LOC, bundled with #1) | Low–Med — subtasks usually inherit parent fields, but tags/priority are still useful | **PROCEED** |
| 4 | Chat screen | UI | S (~10 LOC) | Negative — chat AI is strictly more capable than the regex parser; re-parsing would regress | **DEFER** |
| 5 | Calendar / Sync / Import / Templates / Recurrence / Onboarding / Self-Care | Internal | — | — | **STOP-no-work-needed** (premise doesn't fit; these aren't user-typed text) |

**Implementation order.** Do #1 first as a standalone PR — it has the
biggest UX win, the most design surface, and is the canonical
implementation other paths will follow. Do #2 + #3 as separate small
PRs after #1 lands so they can copy the established pattern.

**Open question for the operator.** The override policy + title-
stripping UX in #1 is genuinely a design choice. The audit recommends
"NLP fills only empty fields, show a one-line parsed-summary on save,"
but a stricter "off by default, opt in via a settings toggle" is
defensible if the goal is zero surprise. Implementer should make the
call when wiring #1.

---

## Phase 3 — Bundle summary

Bundled #1 + #2 + #3 into a single PR (single coherent scope per the
fan-out bundling rule) on branch `claude/nlp-task-additions-EgKhA`:

- **PR #1173** — `feat(nlp): run editor + paste-conversation through
  Quick Add NLP`. Touches:
  - `AddEditTaskViewModel.kt` — injects `NaturalLanguageParser` +
    `ParsedTaskResolver`; adds `enrichWithNlp` (offline only,
    auto-creates unmatched tags/projects, returns null on no
    extraction) + `applyNlpEnrichment` (manual-wins merge); calls
    them on the create path of `saveTask` and per-subtask in the
    pending-subtask flush block.
  - `PasteConversationViewModel.kt` — injects parser + resolver +
    tag/project repos; pipes each extracted candidate through
    `parser.parse()` + `parsedTaskResolver.resolve()` before insert.
  - `AddEditTaskViewModelTest.kt` — adds default no-extraction NLP
    stubs in `setUp()` (so existing tests don't regress) plus three
    new cases: empty-field enrichment, manual-wins on conflict,
    edit-mode skip.

**Implementation choices worth recording:**

1. **Offline parser only on the form.** `parser.parse()` is used
   instead of `parser.parseRemote()` so form save stays offline-safe.
   Quick Add still uses `parseRemote()` for Pro users — this PR
   doesn't change that. Trade-off: Pro users typing in the form get
   the regex parser, not Haiku. Acceptable because the form's
   structured pickers cover most of what the backend parser would
   add, and the form is not the high-volume entry point.

2. **Edit-mode skip is unconditional.** Re-parsing a saved title
   that has already been stripped at first save is almost never what
   the user wants. No setting toggle — if a future user wants
   re-parse-on-edit, they can ask for it.

3. **Title is always replaced, even when other fields are kept
   manual.** This was the load-bearing UX call. Rationale: the user
   sees the title field; leaving raw NLP tokens in the title after
   submit (e.g. `"Buy milk tomorrow #shop"` lingering as the
   persisted title) would be the bigger surprise than the strip.

4. **Subtasks don't carry an NLP-resolved `projectId`.** Subtasks
   inherit the parent's project implicitly via `parentTaskId`;
   passing a different project from a subtask's NLP would split a
   parent and its child across projects. Tags + priority + dates +
   life-category are still applied per-subtask.

**No re-baselining of the wall-clock-per-PR estimate.** This PR is
a single bundle; the 3-PR fan-out estimate from Phase 1's ranked
table doesn't apply. The bundle approach was the right call here —
the three sites share the same enrichment helper, and splitting
would have triplicated the helper or added a new shared module.

**Memory entry candidates.** None. Nothing surprising —
`parsedTaskResolver` was already designed for re-use from any
caller (it's `@Singleton @Inject`-able), so this followed the path
of least resistance.

**Schedule for next audit.** No follow-up audit scheduled. Watch for
user feedback on (a) the edit-mode skip — if users expect re-parse
on edit, revisit; (b) the offline-only choice for the form — if Pro
users complain that their backend NLP doesn't apply on the form,
revisit and gate on `proFeatureGate.hasAccess(AI_NLP)` mirroring
Quick Add's branch.

---

## Phase 4 — Claude Chat handoff

```markdown
# PrismTask: NLP for all task additions — handoff

**Scope.** Wired every user-typed task-title entry point in the
PrismTask Android app through the same `NaturalLanguageParser` +
`ParsedTaskResolver` pipeline that Quick Add has used since v1.0,
because the operator wanted UX consistency: typing
`"Buy milk tomorrow !2 #shop"` should produce the same parsed result
no matter which surface they typed it into.

**Verdicts.**

| Item | Verdict | Finding |
|------|---------|---------|
| Add/Edit task form (`AddEditTaskViewModel.saveTask`) | RED → PROCEED | Title field never ran through NLP; biggest gap |
| Pending subtasks flush (same VM) | YELLOW → PROCEED | Subtask titles inserted as literal strings |
| Paste Conversation (`PasteConversationViewModel`) | YELLOW → PROCEED | `ConversationTaskExtractor` output went straight to insert without `NaturalLanguageParser` |
| Chat screen | YELLOW → DEFER | Chat AI is strictly more capable than the regex parser; re-parsing would regress |
| Voice / Multi-Create / Widget Quick-Add | GREEN | Already use Quick Add pipeline |
| Templates / Recurrence / Sync / Import / Calendar / Onboarding / Self-Care nudge / Undo | GREEN | No user-typed free text — running NLP would mutate structured data |

**Shipped.**
- PR #1173 (`feat(nlp): run editor + paste-conversation through
  Quick Add NLP`) on branch `claude/nlp-task-additions-EgKhA`.
  Bundles all three PROCEED items in a single coherent PR.

**Deferred / stopped.**
- Chat screen: chat AI already produces structured output; piping its
  title through the regex parser would either no-op or destroy
  AI-extracted readability. Right fix lives in chat-AI prompt, not
  here.
- ~12 internal / sync / template / recurrence / import paths:
  premise mismatch — they don't take user-typed free text, and NLP
  on structured data is a corruption risk (calendar event titles
  getting their dates re-overridden, JSON imports getting fields
  silently mutated, etc.).

**Non-obvious findings.**
- `parser.parseRemote()` (backend NLP, used by Quick Add for Pro)
  has a built-in fallback to `parser.parse()` (offline regex), so
  the Pro/Free if/else in `QuickAddViewModel` is a routing choice,
  not a graceful-degrade requirement.
- `ParsedTaskResolver` is already `@Singleton @Inject`-able and
  was designed for reuse from any caller, which is why all three
  PROCEED sites could share one `enrichWithNlp` helper without
  refactoring the resolver.
- The form's `title` / `dueDate` / `priority` / etc. all have
  `private set` — assigning them from inside the VM works fine,
  no public setter needed.
- Subtasks intentionally do **not** carry an NLP-resolved project
  id; subtasks inherit the parent's project via `parentTaskId`, so
  letting a subtask title pull a different project from `@foo`
  would split parent and child across projects. Tags + priority +
  dates + life-category are still applied per-subtask.

**Open questions.**
- Form save uses offline `parser.parse()` only — Pro users typing
  in the form don't get backend Haiku NLP. Reconsider if Pro users
  complain. Quick Add bar still uses `parseRemote()`.
- Edit-mode unconditionally skips NLP. If users expect re-parse on
  edit, revisit.
```

