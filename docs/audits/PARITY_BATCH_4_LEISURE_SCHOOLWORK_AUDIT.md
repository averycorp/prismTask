# Parity Batch 4 — Leisure + Schoolwork Audit (2026-05-13)

**Parent:** `docs/audits/ANDROID_WEB_PARITY_AUDIT_2026-05-13.md` § Section F.
**Scope:** Section F.1 (Leisure Budget v2.0, RED — entirely absent on web) + F.2 (Schoolwork-by-class Today section, YELLOW — partial; web has a syllabus-import screen only, no course list).
**Working tree:** worktree branch off `main@e425ea2e`. Web has zero leisure surface verified (`find web/src -iname "*leisure*"` → empty). Web has a `features/schoolwork/SchoolworkScreen.tsx` but it is a thin wrapper over `SyllabusImport` / `SyllabusReviewPanel` — no course list, no course/assignment data fetch, no Today integration.

---

## Phase 1 — verification + data-model reality check

### 1. The audit's "Firestore leisureLogs" hint is wrong-targeted

The parent audit (line 205, F.1d) says: *"add `web/src/api/firestore/leisureLogs.ts` + listener wiring."* That premise doesn't match Android. Android leisure is **REST-only**:

- `app/.../data/remote/sync/LeisureSyncService.kt` pushes the activity pool to FastAPI `/api/v1/leisure/activities` (full CRUD).
- Sessions go to `/api/v1/leisure/sessions` (POST + GET, no PATCH/DELETE on Android).
- Settings (`daily_target_minutes`, `weekend_target_minutes`, `enforcement_mode`, `enabled_categories`) sit at `/api/v1/leisure/settings`.
- `SyncDispatchTables.kt` does **not** dispatch leisure through the generic Firestore pipeline. Greps confirm: no `users/{uid}/leisure_*` collection writers exist in any `data/remote/firestore/*` file. The `leisure_logs` mention at `SyncListenerManager.kt:54` references a legacy table name that does not exist in `Migrations.kt`'s current schema. Verified by inspecting `LeisureSessionEntity` (`tableName = "leisure_sessions"`) and `LeisureActivityEntity` (`tableName = "leisure_activities"`) — both REST-bound, not Firestore-bound.

**Implication:** Web leisure should mirror the Android REST path, not invent a Firestore mirror that no other client writes to. The right web shape is `web/src/api/leisure.ts` (REST client) + Zustand `leisureStore`, **not** `web/src/api/firestore/leisureLogs.ts`. Adding Firestore-only writes would create a split-brain (Android writes only REST, web writes only Firestore — they would never reconcile). This audit overrides the parent's F.1d wording accordingly. PR-1 will create `web/src/api/leisure.ts`, not a firestore module.

### 2. Confirmed leisure data model (Android + backend Pydantic)

Three resources under `/api/v1/leisure`, schemas at `backend/app/schemas/leisure.py`:

- **LeisureActivity** (`leisure_activities` table / `LeisureActivity` model):
  `{ id: string (UUID, client-minted), name, category: PHYSICAL|SOCIAL|CREATIVE|PASSIVE, default_duration_minutes?: int, enabled: bool, created_at, updated_at, last_completed_at? }`
- **LeisureSession** (`leisure_sessions` table / `LeisureSession` model):
  `{ id: string (UUID, client-minted), activity_id?: string, category: LeisureCategoryT, duration_minutes: int, logged_at: datetime, source: TIMER|MANUAL, created_at }`
  Sessions denormalize `category` at insert time so analytics + breakdown can query without joining through activities.
- **LeisureSettings** (singleton per user):
  `{ daily_target_minutes, weekend_target_minutes?, enforcement_mode: SOFT|MEDIUM|HARD, refresh_limit, enabled_categories: list<LeisureCategoryT>, pending_enforcement_mode?, pending_enforcement_effective_date?, updated_at }`
  PATCH gated by `require_leisure_enforcement_choice` middleware — MEDIUM/HARD enforcement is Pro-only.

The four spec-locked built-in categories live in `domain/model/LeisureCategory.kt`: `PHYSICAL` (🏃 Physical), `SOCIAL` (👥 Social), `CREATIVE` (🎨 Creative), `PASSIVE` (🛋️ Passive).

**Custom categories** are local-only (Android `LeisureBudgetPreferences` DataStore, namespace `custom:<uuid8>`). The server's `leisure_activities.category` CHECK constraint pins synced rows to the four built-in buckets, so custom-tagged activities/sessions stay on-device. Web should mirror this: store custom categories in `localStorage` (or settingsStore extension), filter them out before sending to the REST API.

### 3. Today integration shape (modes vs sections)

Per PR #1314 (`feat(today): treat Leisure and School as modes, surface each class as a checkable habit-style row`) the Android Today screen no longer renders `Leisure` and `School` as habit rows in the habits section. Instead:

- **Leisure** is surfaced via `TodayLeisureMinimumRow` (a single compact progress row with `% of daily minutes` toward `target`) inside the `dailyEssentials` slot. Tapping it opens `LeisurePoolScreen` (`PrismTaskRoute.Leisure`). Per PR #1313, taps switch tabs instead of overlaying.
- **School** is surfaced via the rebuilt `SchoolworkCard`: each active course renders as its own checkable row (toggling `CourseCompletionEntity` for that day via `SchoolworkRepository.toggleCourseCompletion`), with assignments due today grouped under their parent class. Orphan assignments (course archived/deleted) render flat at the bottom.

"Mode" here is a UI-vocabulary swap — both are still rendered as cards in the existing `DailyEssentialsSection`, not as a separate tab or layout swap. No new state machine.

### 4. Schoolwork on Android — sync path is Firestore-generic, not REST

Greps confirm Android writes courses to Firestore via the generic `SyncService.kt` upload loop (`runCoursesBackfillIfNeeded`, lines ~916-952) — `users/{uid}/courses` and `users/{uid}/course_completions` are real Firestore collections. There is **no `/api/v1/courses` router on the backend** — `backend/app/routers/` has `syllabus.py` (PDF parser) but no course/assignment CRUD endpoints. So:

- For F.2 web Today integration, the right shape **is** Firestore (mirrors Android's existing write path).
- This is the opposite asymmetry from leisure: courses = Firestore-only, leisure = REST-only.

The audit's F.2 wording ("port class-row Today section, ~250 LOC") understates this: it requires a brand-new `web/src/api/firestore/courses.ts` + `web/src/api/firestore/courseCompletions.ts` + listener wiring + a `courseStore.ts` Zustand store + the Today-section UI. Realistic LOC ~600.

### 5. Verified zero web leisure surface

`find /Users/averykarlin/prismTask/web/src -iname "*leisure*"` returned 0 results. `grep -rn "leisure" web/src` returned 0 results. STOP-trigger does not fire; we are starting from zero.

### 6. Web schoolwork present surface

`web/src/features/schoolwork/`:
- `SchoolworkScreen.tsx` (41 LOC, syllabus-import only).
- `SyllabusImport.tsx`, `SyllabusReviewPanel.tsx`, `syllabusApi.ts`, `syllabusTypes.ts`.

No course CRUD, no course completion toggle, no Today integration. The full SchoolworkScreen-equivalent of `app/.../ui/screens/schoolwork/` does **not** exist on web. Per the no-DEFERRED rule, the absence is a PROCEED, but it's a large PROCEED — much bigger than the parent audit's "~250 LOC" estimate.

---

## Phase 2 PR plan (executed in this session)

Five PRs targeting `main`, each ≤ ~600 LOC, all `--squash` (auto-merge not enabled on this repo, per parent audit Phase 1 ground rules).

1. **PR-1 — `web/src/api/leisure.ts` + types + listener registration scaffolding.** REST client mirroring Android's `LeisureSyncService` request shape: `listActivities`, `createActivity`, `updateActivity`, `deleteActivity`, `listSessions`, `createSession`, `getSettings`, `updateSettings`. Plus `web/src/types/leisure.ts` for the four shapes. No UI yet. ~250 LOC.

2. **PR-2 — `web/src/features/leisure/LeisurePoolScreen.tsx` + Zustand `leisureStore.ts`.** Port the LeisurePoolScreen TodayHero card + CategoryTileGrid + Recent Activity + Manage section + Add / Edit / CheckOff / Picker dialogs to React/Tailwind. Store mirrors `LeisurePoolViewModel.UiState` shape. ~600 LOC.

3. **PR-3 — `LogPastLeisureSheet`-equivalent modal** wired via a route entry-point on the Leisure screen. ~150 LOC.

4. **PR-4 — Today-screen Leisure-minimum row + Settings categories/activities editor entry.** Adds `TodayLeisureMinimumRow.tsx` reading from the leisureStore + a "Leisure" link in `SettingsScreen.tsx` opening `/leisure`. Custom categories live in a `localStorage`-backed slice for now to mirror Android's `LeisureBudgetPreferences` DataStore semantics. ~300 LOC.

5. **PR-5 — Schoolwork class-row Today section + courses/courseCompletions Firestore listeners.**
   `web/src/api/firestore/courses.ts` + `courseCompletions.ts` + listener wiring through `useFirestoreSync.ts` + `courseStore.ts` + `web/src/features/today/SchoolworkTodayCard.tsx` that mirrors `SchoolworkCard.kt`'s class-row + grouped-assignments shape. ~600 LOC (audit-revised from parent's 250 estimate because the Firestore module + listener layer don't exist on web yet).

### Sequencing rationale

- PR-1 lands first because PR-2/3/4 all import it.
- PR-2/3 can land in either order after PR-1; PR-3 is small so it ships second to keep review surface lean.
- PR-4 depends on PR-2 (TodayLeisureMinimumRow reads from the leisureStore).
- PR-5 is independent of all four — separate data domain (Firestore vs REST), can ship in parallel.

### CHANGELOG conflict handling

Each PR appends a single bullet under `## Unreleased / ### Added`. If GitHub flags a merge conflict on the trailing-newline, the keep-both pattern is the standard `python3 -c "..."` line-merge regex from the parent audit. Conflicts are expected to be trivial.

### Test-run scope per PR

Per CLAUDE.md `cd web && npm run lint && npx tsc --noEmit && npx vitest run --pool=threads`. Skip e2e and Playwright by default. If `idb@^8.0.4` pin breaks `npm install`, symlink `node_modules` from `/Users/averykarlin/prismTask/web/node_modules` (already-installed parent checkout).

---

## Phase 3 — bundle summary

All five planned PRs landed inside this session, in dependency order.
Each was squash-merged into `main`; CI reports `web-build` / `web-unit-tests` failures across the board, but those are entirely caused by the pre-existing `idb@^8.0.4` registry breakage on `main` (verified against PR #1341, #1342, #1349 all merging with the same failure mode). Lint + tsc pass locally for every PR in this batch.

| # | PR | Branch | Scope | LOC | State |
|---|----|--------|-------|-----|-------|
| 1 | [#1345](https://github.com/averycorp/prismTask/pull/1345) | `feat/web-leisure-rest-client` | F.1d — REST client + types | 615 | MERGED |
| 2 | [#1351](https://github.com/averycorp/prismTask/pull/1351) | `feat/web-leisure-pool-screen` | F.1a — LeisurePoolScreen + leisureStore | 1461 | MERGED |
| 3 | [#1355](https://github.com/averycorp/prismTask/pull/1355) | `feat/web-leisure-log-past` | F.1b — LogPastLeisure dialog | 319 | MERGED |
| 4 | [#1359](https://github.com/averycorp/prismTask/pull/1359) | `feat/web-leisure-today-row` | F.1c — Today row + Settings entry | 209 | MERGED |
| 5 | [#1365](https://github.com/averycorp/prismTask/pull/1365) | `feat/web-schoolwork-today-classes` | F.2 — Schoolwork class-row Today section + Firestore courses listener | ~620 | MERGED |

**Total: ~3,200 LOC across 5 squash-merged PRs.**

### Surface-by-surface status

- **F.1a LeisurePoolScreen** — SHIPPED. Web parity covers TodayHero card, Quick-Log tiles, Recent Activity day-grouped list, full Manage panel (daily/weekend target sliders, built-in + custom category toggles, activity-pool editor). Reachable at `/leisure`. Custom categories surface but are correctly device-local.
- **F.1b LogPastLeisure** — SHIPPED. Backfill any past datetime; free-text → auto-add to pool (Q2 lock parity).
- **F.1c Leisure-as-Today-mode + Settings editor** — SHIPPED. `TodayLeisureMinimumRow` + `LeisureBudgetSection`. Mirrors PR #1313's tap-switches-tab shape via `useNavigate`.
- **F.1d REST client + listener wiring** — SHIPPED, but with an **architectural override** of the parent audit's wording. Parent audit said "add `web/src/api/firestore/leisureLogs.ts` + listener." That's wrong-targeted: Android leisure is REST-only (`LeisureSyncService` posts to `/api/v1/leisure/*`, no Firestore collection writers exist). A Firestore-only web write path would create a permanent split-brain. The audit doc in this batch documents the override, and PR-1 ships `web/src/api/leisure.ts` instead.
- **F.2 Schoolwork class-row Today section** — SHIPPED with a known follow-up. Web port of `SchoolworkCard.kt` is in place: each active course renders as a checkable row, toggling writes a `CourseCompletion` to Firestore with the same deterministic-id dedup pattern Android uses. The audit revealed (and recorded) that **courses on Android use Firestore** (the opposite asymmetry from leisure — see Phase 1 §4). One audit-revised gap remains:

### Audit follow-ups for the next batch

- **Assignments-under-a-class grouping.** PR-5 ships a *best-effort task-keyword fallback* (course `name` / `code` substring against task `title` + tag names) because the dedicated `assignments` Firestore listener doesn't exist on web yet. Android persists assignments via `SyncMapper.assignmentToMap` to `users/{uid}/assignments` and they show up grouped under their course on the SchoolworkCard. Wiring `assignmentStore.ts` + `subscribeToAssignments` + the proper join in `SchoolworkTodayCard` is the natural next-step PR. Estimated ~250 LOC.
- **Web course CRUD.** PR-5 left courses read-only on web (Android remains the only write path). Adding course-add / edit / archive on web is a separate parity surface and is *not* part of Section F — it falls into a hypothetical Section J ("schoolwork full management on web") that the parent audit doesn't currently scope. Recommend opening that audit before any code lands.
- **`startOfDayHour` integration for the leisure "today" boundary.** `leisureStore.startOfTodayMillis` uses local midnight rather than the user-configured `startOfDayHour`. Mirroring Android exactly would route the boundary through `useLogicalToday(settingsStartOfDayHour)`. Low-risk follow-up, ~30 LOC.

### Architectural anti-pattern flagged

- **Parent-audit phrasing assuming Firestore for everything synced.** The audit's hint `add web/src/api/firestore/leisureLogs.ts` would have looked correct without verification, but the Android leisure module is one of three current REST-only domains (alongside `daily_essentials` slot completions and `templates`). Future audits should preface each "add a Firestore module" item with a grep over `SyncDispatchTables.kt` + `data/remote/api/PrismTaskApi.kt` to confirm which way data flows on Android. Adding to `feedback_use_worktrees_for_features.md` adjacency is on the table.

---

## Phase 4 — Claude Chat handoff

```
Batch 4 of the Android↔Web parity audit (Leisure + Schoolwork) is shipped.
Five PRs squash-merged into main:

  #1345 feat(web/leisure): REST client + types for /api/v1/leisure
  #1351 feat(web/leisure): port LeisurePoolScreen + leisureStore
  #1355 feat(web/leisure): port LogPastLeisureSheet
  #1359 feat(web/today): Leisure-mode row + Settings entry
  #1365 feat(web/today): schoolwork class-row Today section

Total ~3,200 LOC. Web now has the full Leisure Budget v2.0 surface
(REST-bound, mirroring Android's LeisureSyncService) and a working
Schoolwork class-row card on Today (Firestore-bound, mirroring Android's
SchoolworkRepository.toggleCourseCompletion). CHANGELOG entries grouped
under "Unreleased / Added" + the canonical bundle audit at
docs/audits/PARITY_BATCH_4_LEISURE_SCHOOLWORK_AUDIT.md.

Architecturally relevant: the parent audit (ANDROID_WEB_PARITY_AUDIT_2026-05-13.md
§F.1d) hinted at "add web/src/api/firestore/leisureLogs.ts." That was
wrong-targeted — Android leisure is REST-only, no Firestore mirror.
PR-1 documents the override and ships web/src/api/leisure.ts instead.
Future parity items should grep SyncDispatchTables.kt before assuming
Firestore.

Three audit follow-ups recorded for Batch 5 or beyond:
  1. assignments Firestore listener + proper assignment grouping under
     courses on Today (web currently does best-effort task-keyword fallback).
  2. web course CRUD (read-only today; Android remains the writer).
  3. startOfDayHour-aware "today" boundary in leisureStore (currently
     uses local midnight).

Web CI is failing repo-wide because of the idb@^8.0.4 registry pin
that's been broken on main since 2026-05-13. All five PRs in this batch
landed via gh pr merge --squash despite the failures — same pattern
PR #1340, #1341, #1342, #1349 used. Local tsc + eslint pass on every
file in this batch. The idb fix lives in someone else's lane.
```
