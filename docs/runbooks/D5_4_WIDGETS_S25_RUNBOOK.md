# D5#4 — Widget Phase F S25 Runbook

**Audit:** `docs/audits/WIDGET_FUNCTIONALITY_FIDELITY_AUDIT.md`,
`docs/audits/WIDGET_TAB_PARITY_AUDIT.md`,
`docs/audits/WIDGET_LAUNCHER_PREVIEWS_AUDIT.md`,
`docs/audits/TIMER_WIDGET_BROKEN_AUDIT.md`,
`docs/audits/WIDGET_LAUNCH_ACTION_SEALED_AUDIT.md` + this bundle's
audit at `docs/audits/D5_D6_FINISH_BUNDLE_AUDIT.md` §4.
**Phase F gate:** YES — 14 widgets at 1.0 is a launch promise.
**Owner:** Avery (operator), on Samsung Galaxy S25 Ultra (OneUI
launcher).
**Wall-clock:** ~3 hours active.
**Closes:** D5#4.
**Companion runbooks:** `D5_5_PHASE_F_S25_SCENARIOS_RUNBOOK.md`
(Scenario 4 widget half — cross-references this).

---

## Why this runbook exists

Widget functional layer is fully shipped on main:
- PR #1025 — wired 5 stub widgets to live data + extended dispatch.
- PR #1008 — `previewLayout` for all 14 widgets.
- PR #1042 — Timer widget actually opens timer.
- PR #1044 — Timer + Eisenhower widget parity.

CC verification on AVD covers the static layer (layout, palette,
data wiring). The runtime-on-S25 surface that AVD does NOT cover:

- **Long-press behaviors on OneUI launcher** — Samsung's launcher
  surfaces widget config differently than AOSP launcher.
- **Configuration Activity flow** from launcher widget picker →
  config activity → return to home with picked widget. AVD's AOSP
  launcher does this differently than OneUI's One UI Home.
- **Cold-start render after force-stop** under OneUI background-
  process management — AVD does not model OneUI killing.
- **Real launcher resize behaviors** — `resizeMode="horizontal|vertical"`
  in `widget_info.xml` interacts with OneUI grid in OEM-specific ways.

## Pre-conditions

- **Device.** S25 Ultra. Production keystore-signed `v1.7.X` build.
- **Account.** Test account with realistic data:
  - At least 5 active tasks (mix of overdue / today / planned).
  - At least 3 habits with completion history.
  - At least 1 project with milestones.
  - At least 1 medication with active slots.
  - At least 5 task completions in past week (for analytics widgets).
- **Launcher.** OneUI launcher (default). Do not test with
  Nova/Action launchers for the canonical sweep.
- **Logcat capture.** `adb logcat -s WidgetUpdateManager:V
  WidgetDataProvider:V GlanceAppWidget:V` open.
- **Storage.** Screenshot directory at `~/test_runs/D5_4_widgets_{date}/`.

## The 14 widgets

| # | Widget | Type | Key data dep |
|---|--------|------|--------------|
| 1 | Today | task | TaskRepository, today filter |
| 2 | Habit Streak | habit | HabitRepository |
| 3 | Quick-Add | static | none |
| 4 | Calendar | task | TaskRepository, scheduled-time |
| 5 | Productivity | task analytics | TaskCompletionRepository |
| 6 | Timer | session state | TimerStateDataStore |
| 7 | Upcoming | task | TaskRepository, next-N |
| 8 | Project | project | ProjectRepository, projectId per-instance |
| 9 | Eisenhower | task analytics | TaskRepository quadrant counts |
| 10 | Focus | task | TodayConfig, focus filter |
| 11 | Inbox | task | TaskRepository inbox filter |
| 12 | Medication | health | MedicationSlotRepository, MedicationRepository |
| 13 | Stats Sparkline | task analytics | TaskCompletionRepository weekly |
| 14 | Streak Calendar | habit analytics | HabitCompletionDao range |

## Per-widget verification matrix

For each of the 14 widgets, run all 6 cells. Record outcome in the
matrix at the end.

### Cell A — Picker preview render

1. From a clean home screen state, long-press home → Widgets.
2. Find PrismTask in the picker.
3. Find the target widget. **Verify the preview tile shows the
   widget's themed preview** (per PR #1008 `previewLayout`), NOT a
   generic app icon.
4. Screenshot the picker entry.

**Pass criteria.** Preview tile is themed (palette-aware), shows the
widget's content shape (not just text label).

### Cell B — Initial render after pick

1. Tap the widget in picker. Drag/drop to home screen.
2. **For widgets with config activity** (Project, Today, HabitStreak,
   Inbox): config activity opens. Pick a sensible config option
   (e.g. for Project: pick a project; for Today: default config; for
   HabitStreak: select 2 habits).
3. **For static widgets** (Quick-Add, Timer, Calendar, Productivity,
   Upcoming, Eisenhower, Focus, Medication, Stats Sparkline,
   Streak Calendar): widget renders directly to home.
4. **Verify widget renders within 3 seconds** with the active
   PrismTheme palette colors.
5. Screenshot the rendered widget on home.

**Pass criteria.** Widget shows real user data (not sample data per
the operator's prompt; PR #1025 closed this on code side); palette
colors match the active theme; no "loading" stuck state past 3
seconds.

### Cell C — Cold-start render after force-stop

1. With widget on home, force-stop PrismTask:
   ```bash
   adb shell am force-stop com.averycorp.prismtask
   ```
2. **Verify widget continues to render** the data it had pre-force-stop
   (Glance caches state).
3. After 30 seconds, **interact with the widget** (tap a row, etc.).
4. Expected: widget responds + opens app + on next refresh shows
   updated data.
5. Screenshot before + after.

**Pass criteria.** Widget does not show "loading" or empty state on
force-stop; tap action revives app; subsequent refresh shows updated
data.

### Cell D — Data update propagation

1. Open the app from the widget tap (or from home icon).
2. **Make a data mutation** that should affect the widget:
   - Today / Calendar / Upcoming / Inbox / Focus / Eisenhower / Productivity / Stats Sparkline: complete a task.
   - Habit Streak / Streak Calendar: complete a habit.
   - Project: edit project's milestone count.
   - Medication: tap "Took dose" on a medication.
   - Timer: start a Pomodoro session.
   - Quick-Add: N/A (static widget).
3. **Background the app** (Home button).
4. Wait 5 seconds.
5. **Verify widget reflects the mutation** within 5 seconds (per
   PR #1025's extended `WidgetUpdateManager` dispatch).
6. Screenshot widget post-mutation.

**Pass criteria.** Widget reflects mutation within 5 seconds without
manual refresh.

### Cell E — Long-press / resize behavior on OneUI launcher

1. Long-press the widget on home.
2. Verify OneUI's widget options appear (Resize handle, Remove).
3. **For resizable widgets** (per `widget_info.xml resizeMode`):
   resize horizontally and vertically. Verify widget content
   reflows / scales appropriately.
4. **Tap "Resize"** or drag handles. Make widget 25% larger and 25%
   smaller than default. Screenshot each.

**Pass criteria.** Resize works without crash; content reflows
correctly at +/-25% from default size; OneUI's option menu is
correct.

### Cell F — Configuration Activity revisit

1. **For widgets with config activity** (Project, Today, HabitStreak,
   Inbox): long-press → "Edit" or equivalent.
2. Config activity reopens with current values pre-selected.
3. Change config (e.g. Today: switch tab; HabitStreak: change
   selected habits; Project: pick different project).
4. Save config.
5. Widget re-renders with new config within 3 seconds.

**Pass criteria.** Config activity reopens with current state
pre-filled; saves correctly; widget reflects new config without
manual refresh.

**For widgets without config activity:** Cell F is N/A; mark as
N/A in matrix.

## Cross-cutting verifications

After running all 14 × 6 cells:

### Atmospherics theme switch

1. Settings → Theme → switch to a different PrismTheme (e.g.
   PRISM_VOID → PRISM_DAWN).
2. **All 14 widgets refresh palette within 30 seconds** (Glance's
   debounce window plus the periodic worker tick).
3. Optionally trigger immediate refresh: any task mutation forces a
   `WidgetUpdateManager.updateAll`.
4. Screenshot each widget pre + post theme switch.

**Pass criteria.** Atmospherics drawable + theme palette swap
correctly across all 14.

### Notification permission revoke

Samsung-specific:
1. Disable POST_NOTIFICATIONS for PrismTask via OneUI Settings.
2. Re-render Today widget by tapping a row.
3. Widget should still render (rendering does not require
   notification permission); only the in-app notification
   re-ask flow should engage on next app open.

**Pass criteria.** Widget rendering decoupled from notification
permission state.

### Sign-out behavior

1. Sign out of test account.
2. **All 14 widgets render the empty-state** (per the `WidgetEmptyState`
   convention sampled in `WIDGET_FUNCTIONALITY_FIDELITY_AUDIT.md`),
   NOT crash.
3. Sign back in.
4. Widgets repopulate within 30 seconds.

**Pass criteria.** No crash on sign-out; clean empty state; clean
recovery.

## Outcome matrix

Fill in as you run. Cell values: GREEN / YELLOW (P2/P3) / RED (P0/P1)
/ N/A.

| # | Widget | A picker | B initial | C cold-start | D data update | E resize | F config |
|---|--------|----------|-----------|--------------|---------------|----------|----------|
| 1 | Today |  |  |  |  |  |  |
| 2 | Habit Streak |  |  |  |  |  |  |
| 3 | Quick-Add |  |  |  |  |  | N/A |
| 4 | Calendar |  |  |  |  |  | N/A |
| 5 | Productivity |  |  |  |  |  | N/A |
| 6 | Timer |  |  |  |  |  | N/A |
| 7 | Upcoming |  |  |  |  |  | N/A |
| 8 | Project |  |  |  |  |  |  |
| 9 | Eisenhower |  |  |  |  |  | N/A |
| 10 | Focus |  |  |  |  |  | N/A |
| 11 | Inbox |  |  |  |  |  |  |
| 12 | Medication |  |  |  |  |  | N/A |
| 13 | Stats Sparkline |  |  |  |  |  | N/A |
| 14 | Streak Calendar |  |  |  |  |  | N/A |

Plus:
| Cross-cutting | Atmospherics theme switch |  |
| Cross-cutting | Notification permission revoke |  |
| Cross-cutting | Sign-out behavior |  |

## Failure-mode triage matrix

| Symptom | Likely cause | Verdict |
|---|---|---|
| Picker shows generic app icon for any widget | PR #1008 regression (some widgets lost `previewLayout`) | P1 — file by widget name |
| Widget shows hardcoded sample data | PR #1025 regression (data wiring undone) | **P0** — file by widget name |
| Widget shows "Loading…" stuck state | `WidgetDataProvider.getXxxData` hung or threw; check runCatching wrap | P1 |
| Widget crashes launcher process | Glance API misuse; null deref in renderer | **P0** — capture launcher process logcat |
| Widget palette doesn't match theme | `WidgetThemePalette` reading wrong palette source | P2 |
| Cold-start render after force-stop shows empty | Glance state cache regression | P2 |
| Tap doesn't open app from widget | Action callback registration broken | P1 |
| Data mutation doesn't propagate | `WidgetUpdateManager.updateXxxWidgets` missing the widget; cross-check `WidgetActions.kt` ToggleXxxFromWidgetAction.onAction | P1 — file with widget + mutation type |
| Resize crashes | `widget_info.xml resizeMode` mismatch with renderer | P1 |
| Config activity doesn't pre-fill | `WidgetConfigDataStore.loadXxxConfig(appWidgetId)` regression | P2 |
| All 14 widgets break on theme switch | `WidgetTheme.atmosphericBackground` or `WidgetThemePalette` regression | **P0** |
| Widget crashes on sign-out | Null DAO from signed-out state not handled | P1 |

## Closure criteria

D5#4 → 1.0 when:
- All 14 widgets × 6 cells (less the 7 N/A cells for non-config
  widgets) = 77 cells executed.
- All 3 cross-cutting verifications run.
- All P0 failures filed and addressed.
- All P1 failures triaged: fix-and-redistribute OR documented as
  known-issues-at-kickoff.
- Screenshots saved to `~/test_runs/D5_4_widgets_{date}/`.
- Outcome matrix appended to `docs/audits/WIDGET_FUNCTIONALITY_FIDELITY_AUDIT.md`
  under Phase 4 - Real-Device Verification.

## Append-to-audit template

After session, append to
`docs/audits/WIDGET_FUNCTIONALITY_FIDELITY_AUDIT.md`:

```markdown
## Phase 4 — Real-Device Verification (S25 Ultra OneUI, {date})

77 cells executed across 14 widgets × 6 verification cells (less
7 N/A for non-config widgets). Plus 3 cross-cutting checks.

| Outcome | Count |
|---------|------:|
| GREEN | xx |
| YELLOW (P2/P3) | xx |
| RED (P0/P1) | xx |
| N/A | 7 |

Verdict: GO / GO-WITH-KNOWN-ISSUES / CONDITIONAL / NO-GO.

**D5#4 closure:** 0.9 → 1.0.
```
