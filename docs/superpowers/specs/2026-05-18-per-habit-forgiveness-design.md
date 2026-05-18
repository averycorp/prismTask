# Per-Habit Streak Forgiveness Overrides — Design

**Date:** 2026-05-18
**Status:** Approved, ready for implementation plan
**Scope:** Android + web + backend, habits only (projects keep global)

## Problem

Habit-streak forgiveness ("how many missed days a streak tolerates") is currently a global setting. A user who runs a strict daily habit (e.g. *take medication*) alongside a forgiving one (e.g. *workout 3x/week*) cannot tune them independently — both fall under the same global allowance.

## Goal

Move the three globally-applied "skip days / misses tolerated" knobs to per-habit overrides, keeping the existing global sliders as the default for habits that don't opt in.

| Setting | Current Global | Range | Default | Used By |
|---|---|---|---|---|
| `streakMaxMissedDays` | `HabitListPreferences` | 1–7 | 1 | `StreakCalculator.calculateCurrentStreak` → habit list rows + `HabitAnalyticsViewModel` |
| `ForgivenessPrefs.allowedMisses` | `UserPreferencesDataStore` | 0–5 | 1 | `DailyForgivenessStreakCore` → resilient streak |
| `ForgivenessPrefs.gracePeriodDays` | `UserPreferencesDataStore` | 1–30 | 7 | Same |

Projects continue to use the global `ForgivenessConfig` — no change to project-streak code paths.

## Sentinel: `-1 = inherit global`

Mirror the existing `-1`-means-inherit pattern used by `HabitEntity.todaySkipAfterCompleteDays` / `todaySkipBeforeScheduleDays`. Existing rows get `-1` on migration so behavior is byte-identical until the user opts in per habit. New rows default to `-1`.

For `forgiveness_enabled`, three states are needed because the global toggle is independent of the slider: `-1` inherit, `0` force-off, `1` force-on.

## Android

### Schema

Room migration `vN → vN+1` (increment `CURRENT_DB_VERSION` in `Migrations.kt`). New columns on `habits`:

```sql
ALTER TABLE habits ADD COLUMN streak_max_missed_days INTEGER NOT NULL DEFAULT -1;
ALTER TABLE habits ADD COLUMN forgiveness_enabled INTEGER NOT NULL DEFAULT -1;
ALTER TABLE habits ADD COLUMN forgiveness_allowed_misses INTEGER NOT NULL DEFAULT -1;
ALTER TABLE habits ADD COLUMN forgiveness_grace_period_days INTEGER NOT NULL DEFAULT -1;
```

Corresponding `@ColumnInfo` fields on `HabitEntity` with `defaultValue = "-1"`.

### Resolver

New `domain/usecase/HabitForgivenessResolver.kt` mirroring the shape of `HabitTodayVisibilityResolver`:

```kotlin
object HabitForgivenessResolver {
  fun resolveMaxMissedDays(habit: HabitEntity, globalDefault: Int): Int =
    if (habit.streakMaxMissedDays >= 1) habit.streakMaxMissedDays else globalDefault

  fun resolveForgivenessConfig(habit: HabitEntity, globalConfig: ForgivenessConfig): ForgivenessConfig {
    val enabled = when (habit.forgivenessEnabled) {
      0 -> false
      1 -> true
      else -> globalConfig.enabled
    }
    val allowed = if (habit.forgivenessAllowedMisses >= 0) habit.forgivenessAllowedMisses else globalConfig.allowedMisses
    val window = if (habit.forgivenessGracePeriodDays >= 1) habit.forgivenessGracePeriodDays else globalConfig.gracePeriodDays
    return ForgivenessConfig(enabled = enabled, allowedMisses = allowed, gracePeriodDays = window)
  }
}
```

### Wiring

- `HabitRepository.getHabitsWithFullStatus` (currently passes one global `streakMaxMissedDays` to every habit): resolve per-habit before each `StreakCalculator.calculateCurrentStreak` call.
- `HabitRepository.observeHabitStreak` (currently takes a `config: ForgivenessConfig` param defaulting to `DEFAULT`): combine with `userPreferencesDataStore.forgivenessFlow` and resolve per-habit inside.
- `HabitAnalyticsViewModel` (lines 57, 92, 93): same — resolve via `HabitForgivenessResolver` instead of reading the global directly.
- Sync mappers — `BackendSyncMappers.kt`, `data/remote/mapper/SyncMapper.kt` (Firestore): round-trip the four new fields, default to `-1` when missing so older clients are silent.
- Export/import — `DataExporter.kt`, `ConfigImporter.kt`: include the new fields in the habits JSON section.

### UI: `AddEditHabitScreen`

Add a new **"Streak Forgiveness"** subsection on the Schedule tab, beneath the existing recurrence/frequency fields. Three override blocks, each using the same toggle-reveals-slider pattern as the existing two Today-skip rows on the same screen:

1. **Override Streak Grace Period** (toggle) → slider 1–7 days. Subtitle when off: `"Using global setting (N missed day(s))"`.
2. **Override Forgiveness Window** (toggle) → reveals:
   - Forgiveness on/off (matches `ForgivenessPrefs.enabled`).
   - Allowed Misses slider 0–5.
   - Grace Window slider 1–30.
   The two sliders reveal together because they pair semantically.
3. Footer caption: `"Defaults live in Settings → Habits & Streaks."`

### ViewModel: `AddEditHabitViewModel`

Mirror the existing `globalSkipAfterCompleteDays` / `todaySkipAfterCompleteOverrideEnabled` / `todaySkipAfterCompleteDays` triple pattern, but for the three new fields. On save: store the user's slider value when override-on, store `-1` when override-off.

### Settings copy

- `Settings → Habits → Streak Grace Period` subtitle → `"Default for habits that don't override"`.
- `Settings → Forgiveness-First Streaks` description → append `"Per-habit overrides take precedence."`.

### No gesture change

Long-press on the `HabitCard` circle continues to call `onDecrement()`. Explicit confirmation from operator: do not introduce a new "Skip Today" gesture in this PR.

## Backend

### Models / schemas

- `backend/app/models.py` — add nullable `streak_max_missed_days`, `forgiveness_enabled`, `forgiveness_allowed_misses`, `forgiveness_grace_period_days` columns to the `Habit` model. Nullable on the wire; `None` ↔ `-1` on the Android side (mapper converts).
- `backend/app/schemas/habit.py` — same fields on `HabitCreate` / `HabitUpdate` / `HabitRead` (all `Optional[int]`).

### Migration

- `backend/alembic/versions/015_add_per_habit_forgiveness.py` — adds the four columns to the `habits` table, nullable, no backfill required.

### Router

- `backend/app/routers/habits.py` — pass-through. No business logic changes; the resolver is Android-side.

## Web

### Types + persistence

- `web/src/types/habit.ts` — add optional fields: `streak_max_missed_days?: number`, `forgiveness_enabled?: number` (-1/0/1), `forgiveness_allowed_misses?: number`, `forgiveness_grace_period_days?: number`.
- `web/src/api/firestore/habits.ts` — round-trip the new fields. Older docs without the fields parse as `undefined` ⇒ inherit global.

### Streak computation

- `web/src/utils/streaks.ts` — accept per-habit overrides in the same shape; substitute when present, fall back to the global Zustand store value when absent. Mirror Android's resolver semantics so cross-device results match.

### UI

- `web/src/features/habits/HabitModal.tsx` — add the same three override blocks as Android (override toggle → slider). Reuse the existing Zustand form-state pattern.

## Tests

- **New:** `app/src/test/.../HabitForgivenessResolverTest.kt` — sentinel semantics (`-1` inherit for each axis, forgiveness three-state for the on/off field).
- **Extend:** `StreakCalculatorTest` — fixtures that pin per-habit overrides through `calculateCurrentStreak`.
- **Extend:** `HabitRepositoryTest` — assert per-habit value reaches the streak calculator, not the global.
- **Extend:** `HabitAnalyticsViewModelTest` — same.
- **Backend:** `backend/tests/test_habits.py` — schema round-trip + Alembic upgrade/downgrade.
- **Web:** add a unit test in `web/src/utils/__tests__/streaks.test.ts` for per-habit override fallback. Add a Modal smoke test for the override toggles.

## Out of scope

- New gestures (long-press → "Skip Today") — explicitly deferred.
- Project-side per-entity forgiveness — explicit user choice; projects keep global.
- Bulk-edit screen integration — habit bulk edit doesn't currently expose forgiveness; not adding now.

## Anti-pattern check

Touches Principle 1 (forgiveness streaks). The change *increases* user agency — moving punishment-tolerance into per-habit control — so it stays inside the philosophy. No streak-shame UI is being added.

## Blast radius

Android: `HabitEntity`, `Migrations.kt`, new `HabitForgivenessResolver` + test, `HabitRepository` (2 streams), `HabitAnalyticsViewModel`, `AddEditHabitScreen`, `AddEditHabitViewModel`, `BackendSyncMappers`, `SyncMapper`, `DataExporter`, `ConfigImporter`. ~12 files.

Backend: `models.py`, `schemas/habit.py`, new migration, test. ~4 files.

Web: `types/habit.ts`, `api/firestore/habits.ts`, `utils/streaks.ts`, `features/habits/HabitModal.tsx`. ~4 files.

One bundled PR; expected diff ~700–1000 lines including tests.
