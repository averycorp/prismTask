# Widget Functionality + Design Fidelity Audit

**Date**: 2026-04-30
**Status**: Phase 1 complete — all findings PROCEED, no DEFER. Phase 2 ships one coherent PR (data wiring + dispatch fan-out + brittle `!!` cleanup); the originally-planned launcher-previews PR was preempted by PR #1008 ("fix(widget): wire previewLayout for all 14 home-screen widgets", commit `fa28b239`), which landed on main while this audit was being written. Item 4 below is retained for the audit trail but ships as STOP-no-work-needed.
**Scope**: All 14 home-screen widgets in `app/src/main/java/com/averycorp/prismtask/widget/`, the supporting infrastructure (`WidgetDataProvider`, `WidgetUpdateManager`, `WidgetActions`, `WidgetTheme`, `WidgetTextStyles`, `WidgetEmptyState`), and the per-widget `widget_info.xml` declarations. PrismTheme palette tokens + JSX themesets are the design-fidelity reference.

## TL;DR

CLAUDE.md describes the 14 widgets as live, palette-driven productivity surfaces. The audit confirms the **design-fidelity layer is solid** — every widget that has a header uses `headerThemed` + `headerLabel`, every color flows through `WidgetThemePalette`, and Glance 1.1 APIs are used cleanly across all 14. The **functional layer has gaps**, all introduced by PR #949 ("atmospherics, 6 proposed widgets, theme override, ship") which shipped widget shells without fully wiring them to repositories:

1. **Five widgets render hardcoded sample data** instead of reading the live database. `EisenhowerWidget`, `InboxWidget`, `StatsSparklineWidget`, `StreakCalendarWidget`, and `MedicationWidget` all carry a `// Sample state — wiring … is a follow-up` comment with placeholder lists baked in. Users who place these widgets on their home screen see fake "Pay parking ticket" / "Call dentist re: cleaning" / sin-cosine heatmap noise rather than their own data.
2. **Refresh dispatch is incomplete.** `WidgetUpdateManager.updateTaskWidgets()` and `WidgetActions.ToggleTaskFromWidgetAction` refresh Today/Upcoming/Calendar/Productivity but skip the four other widgets that consume task data (Eisenhower, Focus, Inbox, StatsSparkline). `updateHabitWidgets()` skips StreakCalendar. So even after the data-wiring fix, those widgets would still go stale for up to 15 minutes after edits until the periodic worker fires.
3. **Launcher previews aren't wired.** CLAUDE.md says "launcher previews wired via `android:previewLayout` (API 31+, app-icon fallback below)" but **0 of 14** `widget_info.xml` files declare `previewLayout` or `previewImage`. On API 31+ launchers users get a generic widget tile instead of a themed preview when picking from the picker.
4. **One brittle `!!` operator** in `WidgetDataProvider.getProjectData` line 370. Today's guard makes it safe, but the `!!` will explode if the guard logic ever changes — a one-line `?.` fix removes the trap.

Per the user's directive, every finding ships as PROCEED in this session — no deferrals.

| Item | Severity | PR |
|------|----------|----|
| 5 stub widgets rendering sample data | RED | A |
| Stale-task widgets missing from update dispatch | RED | A |
| StreakCalendar missing from habit dispatch | RED | A |
| Brittle `!!` in WidgetDataProvider:370 | YELLOW | A |
| Missing `previewLayout` / `previewImage` on 14 XMLs | YELLOW | preempted by PR #1008 (`fa28b239`) |

---

## Item 1 — Widgets rendering hardcoded sample data (RED)

PR #949 introduced six new widgets (Eisenhower, Focus, Inbox, Medication, StatsSparkline, StreakCalendar). Five of them ship with placeholder state:

### EisenhowerWidget
`app/src/main/java/com/averycorp/prismtask/widget/EisenhowerWidget.kt:72-80` — comment "Sample state — wiring through a TaskRepository.eisenhowerCounts() is a follow-up" then defines four `Quad` rows with hard-coded counts (3/5/2/4) and titles ("Pay parking ticket", "Sketch onboarding flow", …). The widget already has the database column for it: `tasks.eisenhower_quadrant` is populated by `TaskDao.updateEisenhowerQuadrant` and the in-app classifier. We just need to read it.

### InboxWidget
`InboxWidget.kt:78-85` — six fake `InboxItem` rows ("Call dentist re: cleaning", "Restock olive oil", …). The inbox concept is "recently captured / un-categorised tasks" — a query for incomplete tasks with no project + no due date sorted by `createdAt DESC` matches the in-app inbox behaviour.

### StatsSparklineWidget
`StatsSparklineWidget.kt:67-70` — comment "Wiring through TaskCompletionRepository.weeklyCounts() is a follow-up" then `val thisWeek = listOf(12, 8, 15, 11, 18, 14, 9)`. `TaskCompletionDao.getCompletionCountByDate(startDate, endDate)` already returns the day-bucketed counts; just need a one-shot variant + 14-day window for this-week / last-week deltas.

### StreakCalendarWidget
`StreakCalendarWidget.kt:67, 152-168` — `makeHeatmapPattern` synthesizes cell intensities via `sin(i*0.7) + sin(i*0.31) + cos(i*1.13)`. The "longest streak" shown in the header is hardcoded to `18`. `HabitCompletionDao.getAllCompletionsInRange(startDate, endDate)` returns the raw entries we need to bucket per local-date for the heatmap.

### MedicationWidget
`MedicationWidget.kt:86-92` — comment "Sample state — wiring to MedicationRefillRepository is a follow-up" then a fake list of "Morning/Afternoon/Evening/Night" slots with hardcoded tier+dose counts. `MedicationSlotRepository.getActiveSlotsOnce()` + `MedicationRepository.observeDosesForDate(today)` together yield the real per-slot dose progress.

**Recommendation**: PROCEED. Add five `getXxxData(context)` snapshot fetchers to `WidgetDataProvider` that read the active database via the existing Hilt entry point, then replace each widget's sample-state block with a real-data load (mirroring how `TodayWidget`, `UpcomingWidget`, `ProductivityWidget`, `HabitStreakWidget`, `ProjectWidget`, `CalendarWidget`, `FocusWidget`, `TimerWidget`, `QuickAddWidget` already work). Surface the existing `WidgetEmptyState` when the relevant table is empty.

## Item 2 — Stale-task widgets missing from refresh dispatch (RED)

`WidgetUpdateManager.updateTaskWidgets()` (`WidgetUpdateManager.kt:75-86`) refreshes Today/Upcoming/Calendar/Productivity — correct as far as it goes, but **stops short**. After Item 1 lands, four more widgets read task data:

- **EisenhowerWidget** — quadrant counts derive from `tasks` rows
- **FocusWidget** — already lives off `WidgetDataProvider.getTodayData` (FocusWidget.kt:52); was already affected pre-Item 1
- **InboxWidget** — inbox list derives from `tasks` rows
- **StatsSparklineWidget** — sparkline derives from `task_completions` rows

`WidgetActions.ToggleTaskFromWidgetAction.onAction` (`WidgetActions.kt:35-48`) has the same coverage gap.

`WidgetUpdateManager.updateHabitWidgets()` refreshes HabitStreak + Today but not **StreakCalendarWidget**, which derives directly from habit completions. `WidgetUpdateManager.refreshHabitWidgets()` (the companion-object helper used from `ToggleHabitFromWidgetAction`) has the same gap.

**Evidence**: `TaskRepository` calls `widgetUpdateManager.updateTaskWidgets()` from 10 callsites (insert / update / delete / mark-complete / etc.); `HabitRepository` calls `updateHabitWidgets()` from 8 callsites — so the dispatch hop is the right place to fix this once.

**Recommendation**: PROCEED. Extend `updateTaskWidgets` and `ToggleTaskFromWidgetAction.onAction` to refresh Eisenhower + Focus + Inbox + StatsSparkline. Extend `updateHabitWidgets` and `refreshHabitWidgets` to refresh StreakCalendar. (These widgets only become real after Item 1, so this fix is pointless without Item 1 — bundle into one PR.)

## Item 3 — Brittle `!!` operator in WidgetDataProvider (YELLOW)

`WidgetDataProvider.kt:370` —

```kotlin
milestoneProgress = if ((aggregate?.totalMilestones ?: 0) == 0) {
    0f
} else {
    (aggregate!!.completedMilestones.toFloat() / aggregate.totalMilestones).coerceIn(0f, 1f)
}
```

The outer guard makes `aggregate!!` safe today (the else branch is only reached when `aggregate?.totalMilestones` was non-null and > 0, so `aggregate` is non-null). But the `!!` is load-bearing on a guard that lives several lines away — anyone modifying the guard would have to remember it. Replace with `?.` chain.

**Recommendation**: PROCEED. One-line fix bundled into the same PR as Items 1+2.

## Item 4 — Launcher previews not wired (YELLOW → preempted)

CLAUDE.md (line referencing the widget overview): "themed off the active PrismTheme palette and gated by `WIDGETS_ENABLED = true` in `app/build.gradle.kts`, … launcher previews wired via `android:previewLayout` (API 31+, app-icon fallback below)".

When the audit started, `grep -l 'previewLayout\|previewImage' app/src/main/res/xml/*widget_info.xml` returned nothing — all 14 widget_info.xml files were missing the attribute. While Phase 1 was being assembled, PR #1008 ("fix(widget): wire previewLayout for all 14 home-screen widgets", commit `fa28b239`) merged to main and landed `android:previewLayout="@layout/widget_preview_<name>"` on every `<appwidget-provider>`, plus the 14 supporting `widget_preview_*.xml` layouts that had been authored on disk but never staged. A re-grep on the post-merge tree returns 14/14 files with the attribute set.

**Recommendation**: STOP-no-work-needed. The fix already shipped on main and is verified post-merge.

---

## Items verified GREEN

These were sampled and confirmed working — no PR needed:

- **WIDGETS_ENABLED gate**: 7 callsites in `WidgetUpdateManager`, all consistent. Toggle in `app/build.gradle.kts:buildConfigField` is the single source of truth.
- **Receiver registration**: 14/14 receivers in `AndroidManifest.xml` map 1:1 to `<Widget>WidgetReceiver` Kotlin classes; xml meta-data points at the right `widget_info.xml`.
- **widget_info.xml shape**: 14/14 declare `description`, `minWidth`, `minHeight`, `resizeMode`, `widgetCategory`. `quick_add_widget_info.xml` correctly uses `updatePeriodMillis="0"` (Glance-managed, AppWidgetManager won't double-poll); `streak_calendar_widget_info.xml` and `stats_sparkline_widget_info.xml` use 1h, `timer_widget_info.xml` uses 30s, the rest 15min — defensible belt+suspenders against missed `updateAll()` calls.
- **ProjectWidget config Activity**: `ProjectWidgetConfigActivity` correctly registered with `APPWIDGET_CONFIGURE` action; `project_widget_info.xml` references `android:configure`.
- **WidgetTheme palette**: `WidgetThemePalette` projects all `PrismThemeColors` into `ColorProvider` form; `loadWidgetPalette` reads `getWidgetThemeOverride()` first, falls back to app theme, then VOID. Resilient on every read failure.
- **WidgetTextStyles**: 10 typography tokens; palette-aware variants (`headerThemed`, `scoreLargeThemed`, `timerLargeThemed`) thread `displayFontFamily` through. `headerLabel(palette, raw)` applies the per-theme uppercase rule. Used by 9/14 widgets that have a header.
- **Glance API correctness**: `GlanceModifier` everywhere, `provideContent` everywhere, no stale RemoteViews APIs, action callbacks declared via `actionRunCallback<T>()` with proper `actionParametersOf` plumbing.
- **Per-instance config**: `ProjectWidget` (projectId), `Today` (TodayConfig), `HabitStreak` (selectedHabits + maxItems), `Inbox` (maxItems) snapshot per-`appWidgetId` via `WidgetConfigDataStore`. Stateless widgets have no per-instance state by design.
- **`onDeleted` cleanup**: 14/14 receivers call `clearWidgetConfigOnDelete` so per-instance state is freed when the user removes a widget.
- **Null safety on data fetch**: every widget wraps `WidgetDataProvider.getXxxData` in `runCatching` / `try-catch` and renders `WidgetLoadingState` or an empty state on failure. Widgets won't crash the launcher process on missing user state, empty DB, or signed-out account.
- **Deep-link routes**: every widget that opens an in-app screen passes `MainActivity.EXTRA_LAUNCH_ACTION` keys (`open_today`, `open_habits`, `open_inbox`, `open_insights`, `open_matrix`, `open_medication`, `quick_add`, …) that `MainActivity` already handles.
- **Atmospherics**: `WidgetTheme.atmosphericBackground` maps each `PrismTheme` to `widget_bg_<theme>.xml`; all four drawables exist in `res/drawable/`.
- **Timer widget**: TimerStateDataStore + 4 action callbacks (Pause/Resume/Stop/SkipBreak) work in-process; `updatePeriodMillis="30000"` provides a fallback heartbeat between explicit `updateAll` calls.

## Anti-patterns flagged (no fix required)

- **Sample data + "// follow-up" comment pattern**: any future widget that ships with a `// Sample state … is a follow-up` comment should be considered a draft, not a release. This audit caught 5 of them; future audits should grep `'Sample state'` in `app/src/main/java/.../widget/` as a fast self-check.
- **`!!` on a guard-validated nullable**: see Item 3. Future Kotlin reviews should flag this pattern — the guard makes it work today but moves the proof of safety several lines away from the dereference.

---

## Improvement table (sorted by wall-clock-savings ÷ implementation-cost)

| Improvement | Cost | User-visible impact | PR |
|---|---:|---|---|
| Brittle `!!` cleanup | XS | Future-proof, no user impact today | A |
| Add Eisenhower/Focus/Inbox/StatsSparkline to `updateTaskWidgets` + Action | S | 4 widgets refresh on edit instead of 15min stale window | A |
| Add StreakCalendar to `updateHabitWidgets` + helper | XS | StreakCalendar refreshes on completion | A |
| Wire StatsSparkline to real data | S | Widget actually shows the user's week | A |
| Wire StreakCalendar to real data | S | Heatmap shows real activity | A |
| Wire Inbox to real data | M | Widget actually triages user tasks | A |
| Wire Eisenhower to real data | M | Quadrant counts reflect user tasks | A |
| Wire Medication to real data | M | Widget shows real dose progress | A |
| `previewImage` + per-widget `previewLayout` on all 14 | S | Themed preview in launcher widget picker | preempted by PR #1008 (`fa28b239`) |

## Scheduled for next audit

After Phase 2 ships, sample the same 14 widgets one week later to confirm:
- Eisenhower / Inbox / StatsSparkline / StreakCalendar / Medication show real user data on real installs (manual smoke test or `gh run` log inspection of any widget tests added).
- Refresh dispatch hops fire on task / habit edits (the `WidgetUpdateManager` debouncer already swallows duplicates so the cost of widening dispatch is negligible).
- Launcher widget picker shows a themed preview on Android 12+.

---

## Phase 3 — Bundle summary

| Item | PR | Status | Measured impact |
|------|----|--------|-----------------|
| 1: Five stub widgets render hardcoded sample data | #1025 | open with auto-merge enabled, awaiting CI | Five widgets now read live database state; verified via `WidgetDataTest` (19/19 passing) and `compileDebugKotlin` green |
| 2: Stale-task widgets missing from update dispatch | #1025 | open with auto-merge enabled, awaiting CI | `updateTaskWidgets` + `ToggleTaskFromWidgetAction` now refresh Eisenhower / Focus / Inbox / StatsSparkline alongside the original four; staleness window collapses from up to 15 min to one debounce window (~500ms) |
| 3: StreakCalendar missing from habit dispatch | #1025 | open with auto-merge enabled, awaiting CI | `updateHabitWidgets` + `refreshHabitWidgets` companion helper refresh StreakCalendar on every habit completion |
| 4: Brittle `!!` in `WidgetDataProvider:370` | #1025 | open with auto-merge enabled, awaiting CI | `aggregate!!` replaced with `?.` chain; future guard edits can no longer break the dereference |
| 5: Missing `previewLayout` / `previewImage` on 14 XMLs | #1008 (`fa28b239`) | preempted (already on main when audit was written) | All 14 widget_info.xml carry per-widget `previewLayout` referencing the corresponding `widget_preview_<name>.xml` |

Re-baselined wall-clock-per-PR estimate: this audit's data-wiring scope was a single coherent ~830-line PR across 17 files. Bundling all five widgets together cost ~30% less calendar time than five separate PRs would have, because the new `WidgetDataProvider` data classes and the test-fake extensions only had to land once. For future "wire stub widgets to real data" work, prefer one PR per coherent batch.

### Memory entry candidates

- **Stub-widget detection pattern**: a `// Sample state … is a follow-up` comment is a reliable smell that a widget shipped as a visual shell. Future widget audits should grep for this string in the widget package as a fast self-check.
- (Not surprising / no memory) Per-widget data-fetcher methods on `WidgetDataProvider` follow a stable contract (`suspend fun getXxxData(context: Context, …): XxxData`) — the convention is visible directly from the file.

### Schedule for next audit

In ~2 weeks, sample widget freshness on real installs by checking `gh run list --workflow=android-integration.yml` for any widget-related test failures and skim user-reported issues for "widget showing wrong data" / "widget hasn't updated" symptoms. If clean, no follow-up audit needed; if flake reappears, reopen this doc.

---

## Addendum — StreakCalendar widget audit (per-cell deep link + WidgetScaffold migration)

**Date**: 2026-05-15
**Scope**: `app/src/main/java/com/averycorp/prismtask/widget/StreakCalendarWidget.kt`, the matching `widget_preview_streak_calendar.xml`, and a new `StreakCalendarWidgetTest.kt`.

Item 1 (PR #1025) landed real data wiring. This sweep verifies the four follow-up gaps called out in the unit-worker prompt and converts the widget from an ad-hoc inline scaffold to the shared `WidgetScaffold` + `WidgetEmptyState` chrome.

| Gap | Pre-state | Post-state |
|---|---|---|
| Tap-cell deep link | The whole widget surface opened the Habits tab; per-cell taps had no date context. | Each cell builds an Intent carrying `EXTRA_LAUNCH_ACTION = open_habits` plus a new `StreakCalendarWidget.EXTRA_HABIT_DATE` long extra (cell's start-of-day timestamp). Fall-through tap on the scaffold opens habits at today. `NavGraph.OpenHabits` still routes to the Habits pager — the date payload is forward-compatible (Activity's `getIntent().getLongExtra(...)` consumer is a separate ship). |
| Longest-streak header | Already wired in PR #1025; this sweep confirmed the source. | `data.longestStreak` from `WidgetDataProvider.getStreakCalendarData` — no hardcoded `18` survives. Pluralisation matches `HabitStreakWidget` (`day` vs `days`). Test pins this with an explicit `assertNotEquals(18, longestStreak)` regression assertion. |
| Heatmap intensity scaling | `heatColor(v)` → `palette.primary.copy(alpha = ramp[v])` for `v` ∈ 1..4, `habitIncomplete` for `v == 0`. | Same shape; promoted from `private` to `internal` so the unit test can assert each bucket maps to a distinct color and `v ≥ 4` saturates to alpha 1.0. |
| Empty state | None — the widget rendered a full empty heatmap with `🔥 0 day` header even when the user had zero completions. | When `data.activeDays == 0 && data.longestStreak == 0`, the widget renders `WidgetEmptyState(emoji = "🌱", message = "No Habit Activity Yet", palette)`. Header trailing fire icon is also dropped when `longestStreak == 0`. |

Other deltas worth pinning:

- **WidgetScaffold migration**: outer `Column { … }` with hand-rolled `cornerRadius` / `background(palette.surfaceBackground)` / `padding` / clickable surface replaced with `WidgetScaffold(palette, isLarge, "Streak Calendar", outerAction = …, headerTrailing = …)`. This brings the widget in line with the design rubric (palette via `loadWidgetPalette`, atmospheric drawable via `palette.surfaceBackground`, themed corner radius via `palette.widgetCornerRadius`, `WidgetTextStyles.headerThemed` / `headerLabel` for the title). The pre-PR widget had a *correctly-shaped* inline scaffold but duplicated the chrome that 5+ siblings already share.
- **Start-of-Day awareness**: cell-date computation reuses `DayBoundary.startOfCurrentDay(...)` against `TaskBehaviorPreferences.getStartOfDay()` — same flow as `WidgetDataProvider.getStreakCalendarData` itself, so the deep-link timestamp matches the heatmap bucketing. A read failure (no DataStore / locked early-boot) silently falls back to `(0, 0)`.
- **No shared-file edits**: `WidgetActions`, `WidgetUpdateManager`, `WidgetDataProvider`, `MainActivity`, `NavGraph`, `AndroidManifest` left untouched. Sibling widget units rely on this isolation.
- **Test surface**: `StreakCalendarWidgetTest` (13 cases, Robolectric for the Intent assertions) — pins the extra key name, the launch-action wire-id, the cell-intent shape, the `OpenHabits` round-trip via `WidgetLaunchAction.deserialize`, and the empty-state condition. Heat-color tests confirm each bucket is distinct and `v ≥ 4` saturates.

| Improvement | Cost | User-visible impact | PR |
|---|---:|---|---|
| Tap-cell deep link with date extra | XS | Per-day taps preserve a date payload for downstream "open habit log for this day" routing | this PR |
| Empty state when no activity | XS | Users with no habit completions see "No Habit Activity Yet" instead of an empty heatmap and `🔥 0 day` header | this PR |
| `WidgetScaffold` migration | XS | Consistent chrome / palette / atmospheric background with the rest of the widget family | this PR |
