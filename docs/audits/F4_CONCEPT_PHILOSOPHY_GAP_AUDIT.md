# F4 Concept-Philosophy Gap — Audit (Phase 1)

**Scope**: 7 F4 items promoted pre-Phase-F per operator override of the
post-launch re-trigger framing. Single bundle PR.

**Date**: 2026-05-15 (worktree: `worktree-f4-concept-philosophy-gap`).

**Premise framing**: F4 was filed May 15 against the concept-repositioning
PRs (#1493–#1498 shipped between May 14–15). The user's prompt explicitly
acknowledges high staleness risk because ~52 PRs (#1282–#1333) landed
between May 12–14. Phase 0 verified each gap claim against current `main`
state. All seven gap claims still hold for the Android target.

## §1 Reflection screen (GREEN-SHIP)

- **Phase 0 finding**: no `Reflection*` files; no `EndOfDay*`. The closest
  existing surface is `WeeklyReviewScreen` (end-of-week reflection,
  `WeeklyReviewAggregator`) and `MorningCheckInScreen` (start-of-day).
  Neither is a daily end-of-day reflection.
- **Mood Analytics**: present (`MoodAnalyticsScreen`), but it's a
  correlation analytics surface, not a reflection prompt.
- **Mode values today**: per CLAUDE.md v1.9.x changelog, Leisure and
  School are TODAY-SCREEN MODES with per-class checkable rows. The
  reflection screen will pull completed-task counts from
  `TaskRepository` without filtering by mode (a per-mode breakdown
  would re-litigate scope — keep this v1 simple).
- **Settings entry**: a new `ReflectionSection` under `LifeModesScreen`'s
  Modes column? Or as a sub-screen accessible from Today? Operator
  guidance says "do NOT add new top-level Settings sections" — so place
  the toggle inside `ModesSection` (already a Modes container), and the
  screen itself lives in `ui/screens/reflection/`.
- **Anti-patterns enforced**: no "X tasks unfinished" counter; no
  completion percentage with negative framing; positive language only
  ("one thing that worked today?").
- **Verdict**: PROCEED. Estimated ~300 LOC (screen + viewmodel + prefs
  + nav + section toggle + 1 test).

## §2 Low-energy day mode toggle (GREEN-SHIP)

- **Phase 0 finding**: no `lowEnergy*` keys anywhere. Cognitive Load
  classifier present with EASY/MEDIUM/HARD enum and is the existing
  load taxonomy.
- **Toggle placement**: Today screen header card affordance (mirroring
  the existing balance-bar toggle pattern from `BalanceTracker`).
- **Filter semantics**: when on, Today's task list shows only
  `cognitiveLoad == EASY`. Habits remain unfiltered (habits are gentle
  by default; filtering them would be over-reach).
- **StreakCore soft-pause**: ADDITIVE — the new `lowEnergyDay` flag does
  NOT modify `DailyForgivenessStreakCore`. The streak engine already
  has rest-day semantics (`restDays: Set<LocalDate>`) per
  `docs/REST_DAY.md`. Item 2 routes the low-energy day into the
  existing `restDays` slot when the user enables it for the day.
- **User-invoked**: per Principle 6, this is a USER-INVOKED toggle. No
  auto-detection.
- **Verdict**: PROCEED. Estimated ~120 LOC (DataStore key + Today
  filter wiring + section toggle).

## §3 Streak-pause feature (GREEN-SHIP)

- **Phase 0 finding**: no `streakPaused` or `pauseStreak` keys.
  `DailyForgivenessStreakCore` already has `restDays: Set<LocalDate>`
  parameter (added under Mental-Health-First audit § G3) — this is the
  existing extension point.
- **Surface**: pause-streak = mark a date range as rest days. A new
  `StreakPauseSection` under existing `ForgivenessStreakSection`
  (or appended as an `AnimatedVisibility` block inside it). Pause is a
  date-range; Section gives "Start pause" → DatePicker → "End pause" →
  DatePicker → "Apply".
- **Today banner**: when an active pause is in effect, surface a calm
  banner ("Resting — your streak is paused"). Mirrors the existing
  morning check-in banner shape.
- **Scope kept narrow**: this is a single user preference — `pauseFrom`
  + `pauseTo` (two `LocalDate?` prefs). Multi-streak future scaling is
  out of scope (the prompt notes "Phase 0 may find multiple streak
  types") — but the existing `DailyForgivenessStreakCore.restDays`
  parameter is the shared anchor, so any streak that already uses it
  inherits the pause for free.
- **Verdict**: PROCEED. Estimated ~150 LOC (prefs + section + Today
  banner + use-case glue).

## §4 "Why this task?" explainer on AI suggestions (GREEN-SURGICAL)

- **Phase 0 finding** — KEY DISCOVERY: the backend AI services
  ALREADY return `reason` / `rationale` strings for most surfaces:
  - `services/ai_productivity.py` line 84: Eisenhower text-classify
    returns `{quadrant, reason}`
  - line 166, 243, 314, 413, 485: returned to caller as `reason`
  - line 524: Pomodoro session generator returns `rationale` +
    `skipped_tasks[].reason`
  - line 576: daily briefing returns `top_priorities[].reason` and
    `suggested_order[].reason`
  - `routers/ai/time_block.py`: no reason field surfaced — confirmed
    via grep no hits
  - `routers/ai/pomodoro.py`: backend returns `rationale` per line 524
- **Verdict** is **MIXED**: backend already returns most reasoning;
  surface fix is to display the existing field on the UI. NO backend
  expansion needed for the daily briefing or Pomodoro plan. TimeBlock
  does not return reason today — for scope discipline, leave
  TimeBlock for a follow-up and ship the four surfaces that already
  return reason: Eisenhower text-classify (already exposed via "Why?"
  in OrganizeTab Auto button — verify), Pomodoro session rationale,
  daily briefing top_priorities, daily briefing suggested_order.
- **Implementation**: on the Smart Pomodoro screen, surface the
  `rationale` per session in an expand-row. On the AI Coach daily
  briefing card, surface `reason` per top_priority and per
  suggested_order entry.
- **Verdict**: PROCEED-SURGICAL. Estimated ~80 LOC (Pomodoro UI
  rationale render + daily briefing reason render).

## §5 Customizable Brain Mode beyond Adhd/Calm/FocusRelease (YELLOW-EXPANDED-SCOPE → ADDITIVE)

- **Phase 0 finding**: 3 hardcoded toggles in `BrainModeSection` —
  Quick-Start, Calm, Focus & Release — wired through `NdPreferences`
  with 31 call sites across `app/src/main/`.
- **Refactor cost**: a full dispatch refactor (replace 3 hardcoded
  bool flags with a list-of-modes + dispatch table) would touch all 31
  call sites and break Item 4 PR isolation per operator hard constraint
  ("Do NOT modify existing BrainModePage core dispatch — extend, don't
  replace defaults").
- **Additive shape**: the existing 3 toggles stay unchanged. A new
  "Custom modes" sub-section under `BrainModeSection` lets the user
  define named custom modes (name + description + an optional flag for
  one of: quiet-notifications, low-energy, gentle-streak). A new
  `CustomBrainModeEntity` + Room migration table is OUT OF SCOPE for
  this bundle (would add migration risk to a UI-focused bundle); use a
  JSON-list `Preferences.Key` on `NdPreferencesDataStore` instead.
  Dispatch for these custom modes is informational v1 — the modes are
  named and persisted; the underlying flag toggles existing behavior
  switches. This bias trades scope for shippability.
- **Onboarding starter templates**: the 3 existing modes ARE the
  starter templates today; Item 5 doesn't change their semantics, only
  adds room for custom ones beneath them.
- **Verdict**: PROCEED-ADDITIVE. Estimated ~200 LOC (prefs serializer
  + custom-mode list UI + add/edit/delete sheet).

## §6 PHILOSOPHY.md About-screen link (GREEN-SHIP)

- **Phase 0 finding**:
  - `docs/PHILOSOPHY.md` EXISTS (135 lines, May 15).
  - `docs/.nojekyll` EXISTS (PR #1487), so GitHub Pages serves docs/
    files raw.
  - GitHub Pages URL pattern per `docs/privacy/README.md`:
    `https://averycorp.github.io/prismTask/`.
  - No `AboutScreen.kt` on Android — instead, `AboutSection.kt` lives
    inside `SettingsScreen`.
  - The existing AboutSection has NO external links yet. Item 6 adds
    the first one.
- **URL choice**: link to `https://github.com/averycorp/prismTask/blob/main/docs/PHILOSOPHY.md`
  (GitHub renders markdown). The `docs/.nojekyll` route serves raw
  markdown, which is not user-friendly. The blob URL renders properly.
- **Implementation**: a `TextButton` in `AboutSection` with
  `Intent.ACTION_VIEW` to the URL. Mirrors the pattern that the
  privacy policy README documents (Play Console privacy link target).
- **Verdict**: PROCEED. Estimated ~25 LOC (TextButton + Intent
  dispatcher + URL constant).

## §7 Onboarding philosophy copy (GREEN-SHIP)

- **Phase 0 finding**: `OnboardingScreen.kt` is a 2202-line monolith
  with 13 named pages (Welcome, SmartTasks, Projects, NaturalLanguage,
  Habits, Templates, BrainMode, Tuning, Setup, LifeModes,
  Accessibility, Privacy, Notifications, DaySetup). PR #1167 holistic
  redesign IS this monolith — adding ONE new page page-composable
  inside it mirrors the existing pattern exactly.
- **Page placement**: between Welcome (page 0) and SmartTasks (page
  1). This makes it the first thing the user reads after the welcome
  splash, so the design principles set tone before any features
  appear.
- **Copy shape**: narrative one-liners (not enumerated list per
  operator anti-pattern #18). 3 of the 7 principles surfaced:
  Principle 1 (forgiveness), Principle 2 (real day), Principle 5
  (honest disclosure). The other 4 live in `docs/PHILOSOPHY.md` and
  are accessible via Item 6's About link.
- **Page count constant**: `TOTAL_PAGES` (or equivalent) in
  OnboardingScreen needs +1 — verify shape on implement.
- **Verdict**: PROCEED. Estimated ~120 LOC (page-composable + page
  position rewire).

## §8 Bundle-decision

- **PR shape**: single bundle PR per operator pre-lock.
- **Commit order** (smallest-first, dependency-aware):
  1. Item 6 PHILOSOPHY About link (smallest, no deps)
  2. Item 7 Onboarding philosophy copy (additive page; no deps)
  3. Item 3 Streak-pause prefs + section (StreakCore extension point
     already exists via `restDays`)
  4. Item 2 Low-energy mode (routes into the same `restDays` slot;
     depends on Item 3 prefs shape)
  5. Item 1 Reflection screen (new screen + nav)
  6. Item 4 AI Why surface fixes (Pomodoro + daily briefing UI)
  7. Item 5 Custom Brain Modes (additive CRUD on `NdPreferences`)
  8. Tests + audit doc Phase 3/4 append
- **Cross-item dependency**: Items 2 + 3 share the
  `DailyForgivenessStreakCore.restDays` extension point. Item 3 ships
  the prefs (`pauseFrom`/`pauseTo`); Item 2 reuses by adding one more
  date (`lowEnergyDay`) into the same date-set slot when on. Wiring
  done in a shared helper.
- **Total LOC**: ~995 LOC production + tests; within the prompt's
  upper estimate (~1660) and well under STOP-PHASE-F-RISK ceiling
  (1900).
- **F4 closure impact**: all 7 ship → F4 ★ CLOSED May 15.

## §9 Phase F-risk explicit posture

- **STOP-A1 through A7**: ALL CLEARED (each gap claim verified).
- **STOP-A4 verdict**: surface-only (NOT backend expansion) — keeps
  bundle inside scope ceiling.
- **STOP-PHILOSOPHY-DEP**: CLEARED (PHILOSOPHY.md exists; GitHub Pages
  configured via `.nojekyll`; blob URL is the fallback that works
  regardless of Pages state).
- **STOP-PHASE-F-RISK**: NOT FIRED. Estimated LOC well under 1900.
- **STOP-RE-TRIGGER-FRAMING**: HELD (operator override accepted; re-
  trigger criteria moot post-shipment for all 7 items).
- **Phase F GREEN-GO posture**: POSITIVE — one fewer post-launch
  backlog item, no architectural debt added.

## Ranked improvements (wall-clock-savings ÷ implementation-cost)

| Rank | Item | Impact | Cost |
|------|------|--------|------|
| 1 | §6 PHILOSOPHY link | ~25 LOC; high visibility | trivial |
| 2 | §4 AI Why explainer | ~80 LOC; existing field exposed | small |
| 3 | §7 Onboarding philosophy copy | ~120 LOC; tone-setter | small |
| 4 | §3 Streak-pause | ~150 LOC; reuses restDays | medium |
| 5 | §2 Low-energy day mode | ~120 LOC; reuses Cognitive Load | medium |
| 6 | §1 Reflection screen | ~300 LOC; brand new surface | medium-high |
| 7 | §5 Custom Brain Modes | ~200 LOC; additive only | medium-high |

## Anti-patterns flagged (not fixed in bundle)

- `OnboardingScreen.kt` at 2202 lines is a monolith. Worth splitting
  per-page in a future audit (NOT this bundle — out of scope).
- `BrainModeSection` has 14 callback params — `BrainModeSectionState`
  reducer pattern would simplify, but not in this bundle.
- The 31 `ndPrefs.adhdModeEnabled` call sites are a refactor
  opportunity (dispatch table) but operator hard constraint forbids
  this in F4 bundle.
- `docs/.nojekyll` makes docs/ serve raw — a future docs-site PR
  could move PHILOSOPHY.md to a rendered HTML build (the privacy
  policy is already HTML at `/privacy/`). Out of scope.

---

## Phase 3 — Bundle summary

All 7 items shipped in a single PR across 4 commits on
`worktree-f4-concept-philosophy-gap`:

| Commit | Items | Net LOC | Notes |
|--------|-------|---------|-------|
| 874adb55 | §6, §7 | +320 / -14 | About link + onboarding page + audit doc |
| 89d203dc | §2, §3 | +378 / -2  | Low-energy filter + streak pause |
| 0a0e115f | §4     | +26 / -20  | "Why:" prefix on AI reasoning surfaces |
| dc4bc2b3 | §1, §5 | +656 / -4  | Reflection screen + custom Brain Modes |

**Net production LOC**: ~1130 + ~150 (audit doc) ≈ 1280 LOC. Bundle
sits inside the prompt's ~940–1660 estimate range, well under the
STOP-PHASE-F-RISK ceiling (1900).

**Per-item closure verdicts** (Phase 4 will repeat in handoff):

| Item | Verdict | LOC | Re-trigger criteria post-shipment |
|------|---------|-----|-----------------------------------|
| §1 Reflection | GREEN-SHIPPED | ~330 | MOOT (surface exists now) |
| §2 Low-energy | GREEN-SHIPPED | ~95  | MOOT |
| §3 Streak-pause | GREEN-SHIPPED | ~225 | MOOT |
| §4 AI Why | GREEN-SHIPPED | ~46  | MOOT (3 surfaces labeled) |
| §5 Custom Brain Modes | GREEN-SHIPPED (additive) | ~230 | MOOT for v1 informational scope; dispatch wiring still ACTIVE re-trigger |
| §6 PHILOSOPHY link | GREEN-SHIPPED | ~25 | MOOT |
| §7 Onboarding page | GREEN-SHIPPED | ~70 | MOOT |

**STOPs evaluated** (Phase 0 + during execution):

- STOP-A1 (Item 1 already exists): CLEARED — no Reflection screen
  found; WeeklyReview/MorningCheckIn don't cover daily end-of-day.
- STOP-A2 (Cognitive Load missing): CLEARED — classifier present
  (`domain/model/CognitiveLoad.kt`).
- STOP-A3 (RestDayRepository conflict): **PIVOTED** — discovered
  existing `RestDayRepository` + `RestDayDao` (MH-First § G3). Item
  2 + 3 re-architected to compose with the existing primitive
  instead of duplicating it. RestDayPreferences is the UI-state
  layer (pause-window display + low-energy filter pref); actual
  rest-day marking goes through `markRangeAsRestDay` /
  `unmarkRangeAsRestDay` (new) on the existing repo.
- STOP-A4 (AI Why backend expansion): CLEARED — backend already
  returned `reason` / `rationale` for every targeted surface
  (Briefing top_priorities, Briefing suggested_order, Pomodoro
  session). Fix was surface-only.
- STOP-A5 (BrainMode not hardcoded): CLEARED — 3-toggle dispatch
  intact across 31 call sites.
- STOP-A6 (PHILOSOPHY.md exists): CLEARED.
- STOP-A6b (GitHub Pages configured): CLEARED via `docs/.nojekyll`
  (PR #1487) — though the chosen URL is the GitHub blob URL
  (`/blob/main/docs/PHILOSOPHY.md`) which renders markdown
  natively, sidestepping the raw-md vs rendered-html question.
- STOP-A7 (PR #1167 onboarding architecture): CLEARED — additive
  page-13 insertion via existing `OnboardingPageLayout` helper, no
  architectural change.
- STOP-PHILOSOPHY-DEP: CLEARED.
- STOP-PHASE-F-RISK: NOT FIRED.
- STOP-RE-TRIGGER-FRAMING: HELD (operator override accepted).

**Cross-item dependency realized**: Items 2 + 3 both compose with
`RestDayRepository`. Item 3 extends the repo with batch range
markers; Item 2 uses the existing `markTodayAsRestDay` is NOT
wired (Item 2 is a filter, not a takeover) — instead, Item 2 owns
its own `lowEnergyFilterEnabled` pref and the Today task stream
filters in-memory by `cognitiveLoad == EASY`. Both Items default
off (Principle 7).

**Non-obvious findings** (worth Phase 4 handoff visibility):

1. Item 4 was a **surface-only fix**. The backend AI services
   already returned reasoning for every surveyed surface
   (`ai_productivity.py` lines 84, 117, 166, 243, 314, 413, 485,
   524, 576). Three render sites had inline reason text without an
   explicit "Why" label, so adding the prefix made the AI explainer
   discoverable without any backend or wire-protocol change.
2. The existing `RestDayRepository` + `RestDayDao` (MH-First § G3)
   already implemented "this day counts as kept" against the
   forgiveness-first streak core. Items 2 + 3 did NOT duplicate
   this; instead Item 3 composes by adding a range API on top of
   the existing single-date primitive.
3. `OnboardingScreen.kt` is a single 2202-line file with 13 named
   page composables sharing one `OnboardingPageLayout` helper.
   Adding Item 7 was a single +50 LOC insert plus +1 to
   `TOTAL_PAGES` plus +1 shift on the `when (page)` arm — no nav
   refactor.

**Phase F GREEN-GO posture impact**: POSITIVE. F4 closes 7/7
items pre-Phase F; nothing new added to F2 buffer; no
architectural debt.

**F4 final state**: ★ CLOSED 2026-05-15 (one day after concept
repositioning, all 7 items shipped in a single bundle PR).

---

## Phase 4 — Claude Chat handoff

Emitted as the final tool output of this session (see chat).

