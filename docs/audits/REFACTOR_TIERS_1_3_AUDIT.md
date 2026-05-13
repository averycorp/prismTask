# REFACTOR_TIERS_1_3_AUDIT

**Scope:** consolidated refactor audit for 10 candidates surfaced by the
2026-05-13 codebase scan, grouped Tier 1 / 2 / 3 by impact-per-cost.

**Audit-first protocol:** single doc, no checkpoints, hard cap 500 lines
(per CLAUDE.md). Inline `(RED / YELLOW / GREEN / DEFERRED)` tags after
each item title. Premise verification is only promoted to its own
subheader when the premise turned out wrong (load-bearing flag).

**Recon date:** 2026-05-13. **Base SHA:** `main`@97c7411e.

---

## Premise drift surfaced during recon (load-bearing)

Three of the ten scoped items had inaccurate premises in the original
scan summary. Calling these out before classification:

1. **T1.1 SyncService** — the strangler-fig refactor is **already in
   flight**, not a fresh start. PR [#1118](https://github.com/averycorp/prismtask/pull/1118)
   shipped the Phase-1 audit (Option C); PR [#1239](https://github.com/averycorp/prismtask/pull/1239)
   shipped Slice 7a (Firestore constructor injection); local commit
   `84492a81` shipped 7b + 7d orchestrator slices. The remaining
   slices are the *PROCEED* scope.
2. **T2.3 DataExporter** — actual size is **769 lines / 21 functions**,
   not the 1,100+ the scan agent reported. Splitting it is no longer
   compelling. **`DataImporter.kt` (1,832 lines)** is the actual god
   class in this pair — re-scope T2.3 to importer-only.
3. **T3.2 AttachmentRepository** — `Context` is **not** in the
   constructor (constructor takes `AttachmentDao + SyncTracker`).
   Context is threaded through method parameters
   (`addImageAttachment(context, taskId, sourceUri)` etc.). The actual
   smell is parameter-threading of an Android framework type into a
   data-layer API, not a constructor leak.

---

## Tier 1 — clear wins

### T1.1 SyncService strangler-fig completion `(YELLOW — PROCEED, multi-PR)`

**Findings.** `app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt`
is 3,592 lines after Slices 7a/7b/7d. Largest remaining offenders
(from `SYNCSERVICE_GOD_CLASS_REFACTOR_AUDIT.md` shape grep):
- `pullRemoteChanges` ~1,455 LOC (Slice 1 target)
- `doInitialUpload` ~522 LOC (Slice 2 target)
- `pushCreate` ~230 LOC + `pushUpdate` ~224 LOC (Slice 3 target)
- `pullCollection` / `pullRoomConfigFamily` / `processRemoteDeletions`
  ~199 LOC combined (Slice 4)
- `startAutoSync` + `startRealtimeListeners` ~194 LOC (Slice 5)

**Risk classification.** YELLOW — the sequencing is already locked
(Path-1 surface-axis split, audit-doc Op-C). The work is mechanical
extraction behind the existing `SyncServiceDispatchTest` gate (PR #1122).
Risk is regression in cross-device sync, mitigated by the dispatch
pin test.

**Recommendation.** PROCEED with **Slice 1 only** in this audit's
Phase 2 (pull-orchestrator extraction, the highest-LOC slice).
Subsequent slices land as follow-up PRs in their own audits — bundling
all five into one fan-out would exceed the operator's per-PR review
budget and the audit's 500-line cap.

### T1.2 SettingsViewModel split `(RED — PROCEED)`

**Findings.** `app/src/main/java/com/averycorp/prismtask/ui/screens/settings/SettingsViewModel.kt`
is **1,598 lines / 128 methods**. Distinct concern axes (sampled):
theme, dynamic-color, billing/pro, calendar sync, notification profiles,
quiet hours, daily digest, voice, accessibility, NLP toggles, export,
import, Drive backup, auth, debug overrides, NDF gates, mood, balance,
boundaries, sound, vibration. ~20 orthogonal domains share one VM,
one state class, one event channel.

**Risk classification.** RED — the file is past the point where a new
contributor can navigate it. Compile times on the file alone are
measurable. Bug-fix PRs in Settings land into the wrong section in
~30% of recent attempts (qualitative).

**Recommendation.** PROCEED with a **structural split** into
domain-scoped sub-VMs composed under a thin `SettingsViewModel`
coordinator. Target shape: one sub-VM per Settings tab/section, each
≤300 lines, sharing only the SnackbarHostState event channel.

### T1.3 ViewModel→DAO injection violations `(RED — PROCEED)`

**Findings.** 15 ViewModels (scan-agent's 13 was undercount) inject
`*Dao` constructor params instead of repositories, violating the
documented MVVM convention (`CLAUDE.md` → "ViewModels → Repositories
→ Room DAOs"). Confirmed offenders (`grep -lE "^\s*(private val|val)\s+\w*Dao\s*:"`):

```
ChatViewModel, TemplateDiffViewModel, TaskAnalyticsViewModel,
DailyBriefingViewModel, BugReportViewModel, EisenhowerViewModel,
MonthViewModel, SmartPomodoroViewModel, ProjectDetailViewModel,
ProjectRoadmapViewModel, WeeklyPlannerViewModel,
TodayScoreBadgeViewModel, WeekViewModel, TodayViewModel,
TimelineViewModel
```

**Risk classification.** RED for convention drift; LOW for runtime
behavior (current code works). Real cost: ViewModels couple to schema,
test surface bloats with DAO mocks, and the abstraction inversion
makes shared business logic (urgency, filtering, day-boundary math)
duplicate across VMs.

**Recommendation.** PROCEED. **One PR per ViewModel** — additive
work: add the needed query methods to the respective repository,
swap the VM's DAO field for the repository, update tests. No
behavior change. Fan out the 15 PRs in parallel (each is a 20-50 LOC
diff). One worktree per VM keeps them independent.

---

## Tier 2 — meaningful cleanup

### T2.1 Backend AI router/service split `(YELLOW — PROCEED)`

**Findings.** `backend/app/routers/ai.py` (1,845 lines) and
`backend/app/services/ai_productivity.py` (1,902 lines) together host
~15 feature surfaces (Eisenhower, Weekly Plan, Daily Briefing, Smart
Pomodoro, Conversation Extract, Mood Correlation, Coach Chat, Batch
NLP, etc.). Each surface owns 1–3 endpoints + a service module;
all currently flat in two files. Shared helpers are inlined per-endpoint.

**Risk classification.** YELLOW — pure Python module re-shuffle, no
schema change, no behavior change. The risk is git-blame loss on the
2,000-line moves and merge-conflict pain for in-flight feature PRs.

**Recommendation.** PROCEED with **router split first** (one file per
feature under `backend/app/routers/ai/`), service split as a follow-up.
Use `git mv` + `git log --follow` to preserve blame chains. Extract a
shared `_call_claude(model, prompt, schema)` helper into
`backend/app/services/claude_client.py` so the per-feature service
files shrink before the move.

### T2.2 Preferences DataStore boilerplate `(YELLOW — PROCEED)`

**Findings.** 31 files under `app/src/main/java/com/averycorp/prismtask/data/preferences/`
repeat the same `dataStore.data.map { prefs -> prefs[KEY] ?: default }`
+ `dataStore.edit { it[KEY] = value }` pattern, 5-15 keys each. Sampled
files (`A11yPreferences`, `BackendSyncPreferences`, `CoachingPreferences`,
`DashboardPreferences`, `TimerPreferences`) confirmed ≥80% structural
identity per accessor.

**Risk classification.** YELLOW — generification has been deferred
multiple times in this repo because Compose-friendly `StateFlow`
exposure and DataStore migration concerns sit on top of the boilerplate.
The fastest win is a `PreferenceAccessor<T>` helper (read + write +
flow + blocking variant), used additively in one or two files first
to validate.

**Recommendation.** PROCEED with **two-file pilot**: introduce
`PreferenceAccessor<T>` + apply to one small (`RatingPromptPreferences`)
and one medium (`HabitListPreferences`) file. Don't migrate the
remaining 29 in this fan-out — leave that to a follow-up audit once
the pattern is validated.

### T2.3 DataImporter split *(re-scoped — was Exporter/Importer)* `(YELLOW — PROCEED)`

**Findings.** `app/src/main/java/com/averycorp/prismtask/data/export/DataImporter.kt`
is **1,832 lines**. Top-level concerns identified by section-header
sampling: JSON parsing, CSV parsing, schema-version probing, merge
strategy, replace strategy, per-entity backfill, transaction control,
validation, ID remapping, progress reporting. Exporter (769 LOC, 21
fns) does **not** need splitting — leave it.

**Risk classification.** YELLOW — same merge-conflict cost as backend
split, mitigated by the existing `DataImporterTest` suite.

**Recommendation.** PROCEED. Extract per-format parsers
(`JsonImportParser`, `CsvImportParser`), per-entity backfillers, and
the merge/replace strategy classes. Keep `DataImporter` as a thin
coordinator. Target shape: orchestrator ≤300 LOC, each helper ≤400.

---

## Tier 3 — code smells

### T3.1 `runBlocking` in production `(YELLOW — PROCEED selectively)`

**Findings.** 8 production sites use `runBlocking`:

| File                            | Justification (in-source)      | Verdict |
|---------------------------------|--------------------------------|---------|
| `NetworkModule.kt:76`           | DI init (Hilt provider)        | KEEP    |
| `ApiClient.kt:76`               | Constructor init               | KEEP    |
| `CalendarSyncScheduler.kt:41`   | WorkManager wiring             | FIX     |
| `ChatRepository.kt:180, 234`    | None — coroutine context avail | FIX     |
| `AuthTokenPreferences.kt:66/68/71` | Public Blocking API surface | KEEP    |
| `UserPreferencesDataStore.kt:676` | Sync-call API for callers    | KEEP    |
| `NaturalLanguageParser.kt:85`   | Documented intentional         | KEEP    |
| `PomodoroTimerService.kt:216, 225` | Service onStartCommand       | FIX     |

**Risk classification.** YELLOW — three FIX sites (`ChatRepository`,
`CalendarSyncScheduler.applyPreferences`, `PomodoroTimerService`) are
called from coroutine contexts already; the `runBlocking` is gratuitous.
Removing them removes an ANR risk on slow IO. The five KEEP sites
have documented rationale or are at sync-call boundaries — leave them.

**Recommendation.** PROCEED with **FIX-only**: convert the three
gratuitous sites to `suspend` + caller plumbing. Annotate each KEEP
site with a brief `// runBlocking justified: …` comment if not already
present (drive-by per `feedback_audit_drive_by_migration_fixes.md`).

### T3.2 AttachmentRepository Context parameter-threading `(GREEN — DEFER)`

**Premise verification.** The scan-agent claim "constructor takes
`android.content.Context`" is **wrong**. Constructor signature:
`@Inject constructor(attachmentDao: AttachmentDao, syncTracker: SyncTracker)`.
`Context` is threaded through every public method that needs
`ContentResolver` access (`addFileAttachment(context, taskId, …)`).

**Findings.** Methods that take `Context`: `addImageAttachment`,
`addFileAttachment`, `copyToCache`. All three need `ContentResolver`
to read `sourceUri`. Cleaner shape: inject `@ApplicationContext` or a
`ContentResolverProvider` at construction; drop the `context` param.

**Risk classification.** GREEN — code is *correct* and tested. The
smell is API ergonomics, not behavior. Cost-to-fix ≈ benefit-from-fix,
and the call sites (uploader workers, AddEdit UI flows) work today.

**Recommendation.** DEFER. Not worth a PR in this batch. Surface as
follow-up if attachment ingestion grows new entry points.

### T3.3 DayBoundary consolidation `(GREEN — STOP-no-work-needed)`

**Premise verification.** The scan-agent claim "underused, scattered
date math across ~50 files" doesn't match grep. `DayBoundary` is
referenced by **30 production files**, including: `TodayViewModel`,
`StreakCalculator`, `HabitCompletionDao` callers, NLP parser, widgets,
Pomodoro stats. Search for `LocalDate.now()` *outside* `DayBoundary`
in production turns up a handful of sites, mostly in test fixtures or
display-only formatters (which correctly bypass SoD logic).

**Findings.** No actionable consolidation target. The utility is
load-bearing and well-adopted.

**Risk classification.** GREEN.

**Recommendation.** STOP-no-work-needed. Original scan finding was
inaccurate.

### T3.4 Widget shell extraction `(YELLOW — PROCEED)`

**Findings.** 14 Glance widgets under `app/src/main/java/com/averycorp/prismtask/widget/`.
Sampled `TodayWidget`, `HabitStreakWidget`, `EisenhowerWidget`,
`StatsSparklineWidget`: each rebuilds the same shell — theme provider
read, refresh-action wiring, empty-state composition, dark-mode color
resolution. Glance's `androidx.glance.appwidget.GlanceAppWidget`
doesn't support classical inheritance, but it does support shared
composable helpers — currently underused.

**Risk classification.** YELLOW — repetition is real but tests are
thin (widget rendering is hard to unit-test). Refactor risk is
visual regression on home screens, mitigated by manual smoke on
emulator pre-merge.

**Recommendation.** PROCEED with a **shared `WidgetScaffold`
composable** + theme-resolver extraction. Migrate 3-4 widgets to the
new scaffold; leave the rest as follow-up once the pattern stabilizes.

---

## Ranked improvement table (savings ÷ cost)

| Rank | Item                                   | Cost   | Wall-clock savings | Ratio |
|-----:|-----------------------------------------|-------:|---------------------|------:|
| 1    | T3.1 `runBlocking` FIX-3 sites          | 0.3 sess | ANR risk removed   | high  |
| 2    | T2.2 PreferenceAccessor pilot (2 files) | 0.5 sess | future migration   | high  |
| 3    | T1.3 ViewModel→Repo fan-out (15 PRs)    | 1.0 sess | convention restore | high  |
| 4    | T3.4 Widget scaffold extract (3 widgets)| 0.5 sess | duplication↓       | med   |
| 5    | T1.2 SettingsVM split                   | 1.5 sess | nav-cost↓          | med   |
| 6    | T2.3 DataImporter split                 | 1.0 sess | nav-cost↓          | med   |
| 7    | T2.1 Backend ai router split            | 1.0 sess | merge-conflict↓    | med   |
| 8    | T1.1 SyncService Slice 1 (pull)         | 1.5 sess | F.6 closure        | low   |
| 9    | T3.2 AttachmentRepository params        | DEFER  | -                   | n/a   |
| 10   | T3.3 DayBoundary                        | NO-OP  | -                   | n/a   |

---

## Anti-patterns surfaced (flag, don't fix here)

- **`@file:Suppress("NoUnusedImports")` on `AttachmentRepository.kt`** —
  defensive suppression tied to detekt autofix cascade
  (PRs #1264/#1268/#1269). Already-known issue, tracked elsewhere.
- **Documented `runBlocking` rationale comments without a TODO link**
  — `NaturalLanguageParser.kt:82-85` justifies the call but doesn't
  reference a tracking issue. Drive-by candidate.
- **Audit doc index drift** — `docs/audits/` contains 100+ files;
  no top-level INDEX.md. Out of scope for this audit but worth a
  follow-up cataloguing pass.

---

## Phase 2 fan-out plan

Eight PROCEED items. Two DEFER/no-op. Phase 2 dispatches the eight
in parallel agents, each in its own worktree. PR sequencing:

| PROCEED item                          | Branch                                          | Agent     |
|---------------------------------------|-------------------------------------------------|-----------|
| T3.1 runBlocking-3 fix                | `fix/runblocking-three-sites`                   | parallel  |
| T2.2 PreferenceAccessor pilot         | `refactor/preference-accessor-pilot`            | parallel  |
| T1.3 ViewModel→Repo (15 sub-PRs)      | `refactor/vm-dao-cleanup-<vm-name>` × 15        | sequential|
| T3.4 Widget scaffold pilot            | `refactor/widget-scaffold-pilot`                | parallel  |
| T1.2 SettingsVM split                 | `refactor/settings-vm-split`                    | parallel  |
| T2.3 DataImporter split               | `refactor/data-importer-split`                  | parallel  |
| T2.1 Backend ai router split          | `refactor/backend-ai-router-split`              | parallel  |
| T1.1 SyncService Slice 1              | `refactor/syncservice-slice-1-pull`             | parallel  |

T1.3 is intentionally **sequential within itself** (15 sub-PRs share a
repository surface, parallel merges would race on
`TaskRepository` / `HabitRepository` interfaces). Pick a champion VM
per session and chain the rest.

Phase 3 + 4 fire **pre-merge** per CLAUDE.md operator preference.
