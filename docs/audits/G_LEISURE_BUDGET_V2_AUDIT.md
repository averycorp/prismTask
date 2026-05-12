# G — Leisure Budget v2.0 Audit

**Status:** Phase 1 complete + Phase 2 implementation in flight (single bundle PR per operator pre-lock).
**Branch:** `claude/leisure-budget-v2-SVQLC`
**Date:** 2026-05-12
**Scope:** Promote v2.0 Leisure Budget feature from post-launch G-series spec into pre-Phase-F (Phase F kickoff May 15) bundle. Operator-acknowledged pre-Phase-F timing override; STOP-PHASE-F-RISK explicitly accepted via Phase 0 resolution.

## Phase 0 summary (audit-first risks)

All 5 risks **cleared on disk**; one new STOP (`STOP-LEISURE-MIGRATION`) fired and was resolved by operator selection of **Replace + migrate (scope blowout)**.

| Risk | Verdict | Evidence |
|------|---------|----------|
| Risk 1: `DailyResetWorker` + SoD boundary | CLEARED | `app/src/main/java/com/averycorp/prismtask/workers/DailyResetWorker.kt`; `util/DayBoundary.kt`; reschedules itself at user day-start hour. |
| Risk 2: `TaskAnalyticsScreen` extensible | CLEARED | Section-composable pattern already in use: `ProductivityScoreSection`, `HabitCorrelationsSection`, `TimeTrackingSection`. New `LeisureScoreSection` slots in cleanly. |
| Risk 3: Productivity streak forgiveness | CLEARED | `domain/usecase/DailyForgivenessStreakCore.kt` (shared core; projects + habits already delegate); reused for leisure streak per spec. |
| Risk 4: `SyncMapper` PR #1135 pattern | CLEARED | `data/remote/sync/BackendSyncMappers.kt` uses `entity → SyncOperation { JsonObject }` shape with snake_case keys; pattern is current. |
| Risk 5: Task timer architecture | CLEARED | `notifications/PomodoroTimerService.kt` is a foreground service with `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission and broadcast contract; pattern reusable for leisure timer. |

**STOP-LEISURE-INFRA: CONFIRMED-EXISTS → operator chose Replace + migrate.**

Existing v1.x infrastructure inventory (~3,313 LOC):

| File | LOC | Disposition |
|------|-----|-------------|
| `data/local/entity/LeisureLogEntity.kt` | 47 | DEPRECATE — replaced by `LeisureSessionEntity` + new `leisure_logs` schema |
| `data/local/dao/LeisureDao.kt` | 39 | REPLACE with two DAOs: `LeisureActivityDao`, `LeisureSessionDao` |
| `data/repository/LeisureRepository.kt` | 339 | REPLACE — slot model removed; new session/pool model |
| `data/preferences/LeisurePreferences.kt` | 739 | DEPRECATE (slot keys); MIGRATE custom activities → `leisure_activities` pool |
| `ui/screens/leisure/LeisureScreen.kt` | 394 | REPLACE — pool browser, not slot picker |
| `ui/screens/leisure/LeisureViewModel.kt` | 214 | REPLACE |
| `ui/screens/leisure/settings/LeisureSettingsScreen.kt` | 758 | REPLACE — new pool management + budget settings |
| `ui/screens/leisure/settings/LeisureSettingsViewModel.kt` | 182 | REPLACE |
| `ui/screens/today/dailyessentials/cards/LeisureCard.kt` | 93 | REPLACE — budget progress card, not slot pick |
| `ui/screens/leisure/components/LeisureComponents.kt` | 508 | REPLACE — new pool browser components |

Coupling points (must be rewired in same bundle):
- `DailyEssentialsUseCase` exposes `LeisureCardState(LeisureKind.MUSIC|FLEX, pickedForToday, done)` — replace with `LeisureBudgetCardState(minutesLogged, targetMinutes, scoreToday)`.
- `LeisureRepository.getOrCreateLeisureHabit()` creates a meta-habit "Leisure" fired when all enabled slots done — RETIRE this auto-completion since v2.0 has no slot concept; the meta-habit row remains but auto-fires when the daily target is hit.
- `SyncTracker.trackUpdate(..., "leisure_log")` — old table; new `"leisure_session"` + `"leisure_activity"` + `"leisure_settings"` table names.

**STOP-Q-LOCK held**: Q1 (no tier gate on refresh limit), Q2 (free-text auto-add), Q3 (no contact integration), Q4 (no medication bridge), Q5 (all 4 categories enabled by default) all preserved.

**STOP-PHASE-F-RISK: operator-accepted at Phase 0**. Phase F GREEN-GO posture downgraded to RISK-FLAGGED; bundle proceeds.

---

## §1 — Item 1: Leisure pool management (Settings → Leisure → Activity Pool)

**(GREEN-WITH-PREREQUISITE)** — prerequisite is the v1.x replacement (already in scope).

**Current state.** `LeisureSettingsScreen.kt` (758 LOC) implements per-slot configuration (MUSIC/FLEX/LANGUAGE) with custom activities per slot. No global pool, no categories.

**Shape.** New `LeisurePoolScreen` composable + `LeisurePoolViewModel`. Renders all activities grouped by category (PHYSICAL/SOCIAL/CREATIVE/PASSIVE) with add/edit/delete + enable-toggle per row. Free-text "Add activity" sheet with category dropdown + optional defaultDurationMinutes. Settings entry: `Settings → Leisure → Activity Pool` (replaces existing entry).

**LOC.** ~220 (Composable 140, VM 80).

**STOP-1A**: requires new "Leisure" Settings section — already exists (v1.x), gets re-pointed.

---

## §2 — Item 2: Daily target setting + weekend differentiation

**(GREEN)** — fits inside Item 1 settings screen + onboarding.

**Shape.** Row in `LeisurePoolScreen` (or sibling `LeisureBudgetSettingsScreen`): "Daily target" slider 0–240 min default 60; "Different target on weekends" toggle → reveals `weekendTargetMinutes` slider. Backing field: `LeisureSettingsEntity.dailyTargetMinutes` + `weekendTargetMinutes` (nullable).

**LOC.** ~60.

**STOP-2A**: weekday/weekend resolution uses `DayBoundary.currentLocalDateString(dayStartHour)` → `LocalDate.dayOfWeek` ∈ {SAT, SUN} ⇒ weekend target if set else weekday target. Consistent with productivity score's SoD.

---

## §3 — Item 3: Today's leisure card

**(GREEN-WITH-PREREQUISITE)** — replaces existing `LeisureCard` (slot pick UI).

**Shape.** `LeisureBudgetCard` composable on Today screen. Shows: (a) minutes logged today / target (progress bar), (b) suggested activity name + category emoji from random-pull algorithm, (c) "Start timer" + "Refresh" + "Log past activity" buttons. Empty pool: CTA "Add your first leisure activity" → `LeisurePoolScreen`. Wired into `DailyEssentialsUseCase` replacing `LeisureCardState` with `LeisureBudgetCardState`.

**LOC.** ~180 (composable + state).

**STOP-3A**: Today screen `DailyEssentialsSection` ordering is unconstrained — new card replaces the two existing `LeisureCard(MUSIC|FLEX)` slots.

---

## §4 — Item 4: Leisure timer (foreground service)

**(GREEN)** — mirrors `PomodoroTimerService`.

**Shape.** `LeisureTimerService` (new file). Constructor + onStartCommand handle START/PAUSE/RESUME/STOP. On timer completion (or manual stop), inserts a `LeisureSessionEntity` with `source = TIMER`, `durationMinutes = elapsedSec/60`, `activityId = startedActivityId`. Notification: ongoing channel `leisure_timer_channel`. AndroidManifest registration mirrors Pomodoro service.

**LOC.** ~220 (Service 160, channel/notification 60).

**STOP-4A**: existing FOREGROUND_SERVICE permissions adequate; `FOREGROUND_SERVICE_MEDIA_PLAYBACK` reused for the leisure timer service type (timer is the user's active focus, mirrors pomodoro).
**STOP-4B**: SDK-26 baseline + existing permissions cover Android 14 requirements.

---

## §5 — Item 5: Manual entry modal

**(GREEN)** — bottom sheet.

**Shape.** `LogPastLeisureSheet` ModalBottomSheet: (a) activity dropdown of enabled pool entries + "Free text…" option, (b) free-text field (auto-added to pool with selected category on save per **Q2 lock**), (c) category chip-row (defaults to selected activity's category), (d) duration field (default to activity's `defaultDurationMinutes` or 30), (e) "Today / Earlier" picker (default Today; "Earlier" reveals date+time). Save inserts `LeisureSessionEntity` with `source = MANUAL`.

**LOC.** ~190.

**STOP-5A**: Q2-lock means free-text auto-add to pool. Per spec instruction, **no pool size cap** introduced — operator decision deferred.

---

## §6 — Item 6: Leisure score dashboard (new section in `TaskAnalyticsScreen`)

**(GREEN-WITH-PREREQUISITE)** — extends `TaskAnalyticsScreen`, does not create `AnalyticsDashboardScreen`.

**Shape.** New `LeisureScoreSection` composable mirroring `ProductivityScoreSection.kt` structure. Components: (a) 7-day score sparkline (Compose Canvas line chart, mirrors existing patterns — no chart lib), (b) category variety chart (4-bar grouped chart, last 7 days), (c) current leisure streak badge + best streak, (d) 30-day budget hit rate %. `TaskAnalyticsViewModel` extended with `leisureScoreSection: StateFlow<LeisureScoreSectionState?>`.

**LOC.** ~280 (composable 200, VM 80).

**STOP-6A**: no chart library required; Compose Canvas reuse is consistent with `ProductivityScoreSection`.
**STOP-6B**: streak forgiveness rule comes from `DailyForgivenessStreakCore.calculate(activityDates, today, forgiveness)`; activityDates = days where `dailyTarget` was hit; forgiveness param from `NdPreferences.forgivenessStreaks` (already user-controlled).

---

## §7 — Item 7: `leisure_activities` table

**(GREEN)** — clean new entity.

**Schema (Room).**
```kotlin
@Entity(
  tableName = "leisure_activities",
  indices = [Index("user_id"), Index("category"), Index(value = ["cloud_id"], unique = true)]
)
data class LeisureActivityEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo("cloud_id") val cloudId: String? = null,
  @ColumnInfo("user_id") val userId: String? = null,
  @ColumnInfo("name") val name: String,
  @ColumnInfo("category") val category: String, // PHYSICAL/SOCIAL/CREATIVE/PASSIVE
  @ColumnInfo("default_duration_minutes") val defaultDurationMinutes: Int? = null,
  @ColumnInfo("enabled") val enabled: Boolean = true,
  @ColumnInfo("created_at") val createdAt: Long = System.currentTimeMillis(),
  @ColumnInfo("updated_at") val updatedAt: Long = 0L,
  @ColumnInfo("last_completed_at") val lastCompletedAt: Long? = null
)
```

**Postgres mirror.** New `LeisureActivity(Base)` model, alembic `028_add_leisure_v2_tables.py`.

**LOC.** Entity 35, DAO 40, Postgres model 35, alembic migration 80 = **190**.

**STOP-7A**: `androidTest/smoke/TestDatabaseModule.kt` gets matching `@Provides` for `LeisureActivityDao` in same commit.

---

## §8 — Item 8: `leisure_logs` table (renamed from v1.x slot-pick table)

**(GREEN-WITH-PREREQUISITE)** — old `leisure_logs` is the slot-pick table; new spec wants per-session rows. Old table dropped via migration; new table named **`leisure_sessions`** to avoid name collision sync confusion with existing remote `leisure_log` row-type.

**Schema (Room).**
```kotlin
@Entity(
  tableName = "leisure_sessions",
  indices = [Index("user_id"), Index("activity_id"), Index("logged_at"), Index(value = ["cloud_id"], unique = true)]
)
data class LeisureSessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  @ColumnInfo("cloud_id") val cloudId: String? = null,
  @ColumnInfo("user_id") val userId: String? = null,
  @ColumnInfo("activity_id") val activityId: Long? = null,
  @ColumnInfo("category") val category: String,
  @ColumnInfo("duration_minutes") val durationMinutes: Int,
  @ColumnInfo("logged_at") val loggedAt: Long,
  @ColumnInfo("source") val source: String, // TIMER/MANUAL
  @ColumnInfo("created_at") val createdAt: Long = System.currentTimeMillis()
)
```

**LOC.** Entity 35, DAO 60, Postgres model 35, alembic 30 (in same migration as §7) = **160**.

**STOP-8A**: cloudId pattern mirrors existing entities (TaskEntity, HabitEntity). `BackendSyncMappers.kt` extended with `leisureActivityToOperation` + `leisureSessionToOperation`.

---

## §9 — Item 9: `leisure_settings` table

**(GREEN)** — singleton per user.

**Schema (Room).** Hybrid: persisted via DataStore for fast read on Today screen, mirrored to Postgres via new endpoint. Rationale: settings change rarely; DataStore avoids Room migration noise on toggle.

```kotlin
data class LeisureBudgetSettings(
  val dailyTargetMinutes: Int = 60,
  val weekendTargetMinutes: Int? = null,
  val enforcementMode: String = "SOFT", // SOFT/MEDIUM/HARD
  val refreshLimit: Int = 3,
  val enabledCategories: Set<String> = setOf("PHYSICAL","SOCIAL","CREATIVE","PASSIVE"),
  val pendingEnforcementMode: String? = null,
  val pendingEnforcementEffectiveDate: String? = null
)
```

**Postgres mirror.** `LeisureSettings(Base)` with `user_id` primary key.

**LOC.** Preferences 130, Postgres model 30, alembic 30 = **190**.

**STOP-9A**: enforcement-mode change protocol — setting `enforcementMode` writes to `pendingEnforcementMode` + `pendingEnforcementEffectiveDate = nextLocalDate(dayStartHour)`; `DailyResetWorker` promotes `pending → active` at SoD. Mirrors the deferred-setting-takes-effect-next-day pattern from medication reminder profiles.

---

## §10 — Item 10: Tier gating

**(GREEN)** — new dependency.

**Shape.** New `backend/app/middleware/leisure_gate.py` defining `require_leisure_enforcement_choice` dependency. Checks `current_user.effective_tier == "PRO"` (mirrors `User.effective_tier` from `models.py:122`). Returns 402 Payment Required with detail message. Wired only on `PATCH /api/v1/leisure/settings` when the request body sets `enforcement_mode != "SOFT"` (free users can use SOFT enforcement; only MEDIUM/HARD are gated — refresh limit explicitly NOT gated per **Q1 lock**).

**LOC.** Dependency 70, router wiring 20 = **90**.

**STOP-10A**: tier gate is request-body-conditional (only fires when enforcement_mode is non-default). Pattern is distinct from `require_ai_features_enabled` (which is a header opt-out, not tier gate). Avoids gating refresh limit and avoids gating the leisure feature as a whole.

---

## §11 — Item 11: Onboarding addition

**(YELLOW-DEFER)** — defer to follow-on PR.

**Rationale.** Bundle is already at scope-blowout ceiling per operator Phase 0 selection. Onboarding addition is acceptable to defer: (a) spec's CTA fallback handles the no-onboarding path ("Set up leisure" CTA on Today card when pool is empty), (b) per Q5 operator note "Onboarding can be additive later", (c) onboarding wiring through PR #1167/#1218 completion-flag pattern is delicate and would create regression risk on a critical app-launch path during a feature-build that already has heavy scope.

**Trigger criteria for follow-on:** after v2.0 leisure ships clean + 1 week of telemetry; if Activity Pool conversion rate from Today CTA is <40%, ship onboarding addition.

**LOC if shipped.** ~140.

---

## §12 — Item 12: Leisure score logic

**(GREEN)** — `LeisureScorer` use case.

**Algorithm (per spec).** Daily score 0–100:
- 60 pts: `minutesLoggedToday / dailyTarget * 60`, clamped to 60.
- 20 pts: variety — `distinctCategoriesLoggedToday / 4 * 20`.
- 20 pts: streak — current streak (via `DailyForgivenessStreakCore`) capped at 7 days → `min(streak, 7) / 7 * 20`.

**Daily reset.** `DailyResetWorker` extended: on day-rollover, write yesterday's `LeisureDailyScoreEntity` (or compute on read; chose compute-on-read to avoid extra table). The worker DOES promote pending enforcement mode per §9 STOP-9A.

**Streak.** Activity dates = days where `minutesLogged >= effectiveTarget` (weekday/weekend). Reuses `DailyForgivenessStreakCore.calculate`.

**LOC.** Scorer 90, DailyResetWorker hook 30, score-state flow 40 = **160**.

**STOP-12A**: streak forgiveness rule taken from `DailyForgivenessStreakCore` per Risk 3 verification — not invented.

---

## §13 — Item 13: Random pull algorithm

**(GREEN)** — `LeisureSampler` use case.

**Algorithm (per spec).**
```
candidates = leisure_activities WHERE enabled AND category IN enabledCategories
weight(activity) = max(1, daysSince(lastCompletedAt ?: epoch))
pick = weighted_random(candidates, weights)
```
Refresh limit: per-day refresh counter (DataStore key `leisure_refreshes_consumed_<localDate>`, configurable cap from `leisure_settings.refreshLimit`, default 3, NOT tier-gated per **Q1**). Locks after limit; manual entry/timer-start does NOT consume refresh.

**LOC.** Sampler 60, refresh state 30, ViewModel wiring 30 = **120**.

**STOP-13A**: empty pool — Today card shows "Add your first leisure activity" CTA → `LeisurePoolScreen` (per spec default).

---

## §14 — Bundle decision

**PR shape.** Single bundle PR on `claude/leisure-budget-v2-SVQLC`. Squash merge target main.

**Commit order (revised post-Q4 STOP-LEISURE-MIGRATION resolution):**
1. Audit doc (this file) — Phase 1 deliverable.
2. Backend: alembic `028_add_leisure_v2_tables.py` (3 tables + drop v1 `leisure_logs`).
3. Backend: `models.py` extensions (3 new models) + Pydantic schemas + tier gate dependency.
4. Backend: routers — `/api/v1/leisure/activities`, `/api/v1/leisure/sessions`, `/api/v1/leisure/settings`.
5. Backend: tests (pytest).
6. Room: 3 new entities + DAOs + `LeisureBudgetPreferences` (replaces `LeisurePreferences`).
7. Room: `MIGRATION_81_82` — drops v1 `leisure_logs`; creates `leisure_activities` + `leisure_sessions`; backfills pool from previously-stored custom activities (best-effort).
8. `LeisureRepositoryV2` (single rewritten repo, NOT alongside).
9. `BackendSyncMappers` extensions + `SyncDispatchTables` wiring.
10. `LeisureScorer` + `LeisureSampler` + `DailyResetWorker` hook.
11. `LeisureTimerService` + AndroidManifest registration.
12. UI: `LeisurePoolScreen` + `LeisurePoolViewModel`.
13. UI: `LogPastLeisureSheet`.
14. UI: `LeisureBudgetCard` on Today + `DailyEssentialsUseCase` rewire.
15. UI: `LeisureScoreSection` in `TaskAnalyticsScreen`.
16. Settings entry rewire + delete v1.x screens (`LeisureScreen`, `LeisureSettingsScreen`, etc.).
17. Tests: unit (scorer, sampler, repository) + smoke `TestDatabaseModule.kt` parity.

**Cross-item dependencies satisfied by ordering above.**

**LOC totals (estimated).**

| Item | Est. LOC |
|------|----------|
| Item 7 leisure_activities | 190 |
| Item 8 leisure_sessions | 160 |
| Item 9 leisure_settings | 190 |
| Item 4 timer service | 220 |
| Item 1 pool management UI | 220 |
| Item 2 daily target | 60 |
| Item 5 manual entry | 190 |
| Item 3 Today card | 180 |
| Item 6 dashboard section | 280 |
| Item 10 tier gating | 90 |
| Item 11 onboarding | DEFERRED |
| Item 12 score logic | 160 |
| Item 13 random pull | 120 |
| Migration of v1.x (delete + reroute) | 380 |
| LeisureRepositoryV2 (replaces 339 LOC of v1.x) | 320 |
| Tests | 400 |
| **Total prod + tests** | **≈3160** |

Audit doc (this file): excluded from prod LOC count.

Within bundle ceiling (~3500). YELLOW-DEFER for Item 11 (onboarding) keeps us in window.

---

## §15 — Phase F GREEN-GO explicit posture

**Pre-bundle posture (May 10, per operator pre-lock):** RISK-FLAGGED with D5#1 ongoing.

**Post-Phase-0 posture:** RISK-FLAGGED. Drivers:
- Bundle size (~3160 LOC) at 90% of upper estimate ceiling.
- Replace-not-extend path adds 380 LOC migration + 320 LOC repo replacement (operator-accepted).
- Schema migration risk to PR #1245 defensive shapes — mitigated by (a) PR #1245 not on the do-not-modify hard-constraint list, (b) v1.x prefs file fully deprecated with custom-activity data backfilled to v2.0 pool.
- 5-day window to Phase F kickoff (May 15) — execution starts immediately on this audit's commit.

**Mitigations:**
- Item 11 (onboarding) deferred to keep within ceiling.
- v1.x meta-habit row preserved (continues to fire on daily-target hit, not on slot completion) — no habit-data regression.
- Single bundle PR, not fan-out — minimizes review surface.

**Trigger to escalate to STOP-PHASE-F-RISK fire (post-Phase-0):**
- Total LOC > 4000 at any point during Phase 2.
- Backend schema change breaks any existing route in CI.
- Foreground service notification regression.

Operator may halt at any Phase 2 boundary; STOP-PHASE-F-RISK protocol applies.

---

## Anti-pattern compliance (all 24 anti-patterns)

| # | Rule | Status |
|---|------|--------|
| 1 | Do NOT modify PR #1080/#1082/#1141/#1142/#1191/#1216/#1235/#1240/#1244/#1251 fix shapes | OK — none touched. |
| 2 | Do NOT modify PR #1265 BatchPreviewViewModel | OK — not touched. |
| 3 | Do NOT create AnalyticsDashboardScreen | OK — `LeisureScoreSection` extends `TaskAnalyticsScreen`. |
| 4 | No new feature flags | OK — build constants + user settings only. |
| 5 | Do NOT tier-gate refresh limit (Q1) | OK — refresh limit is plain user setting. |
| 6 | No contact integration (Q3) | OK — social activities are free-text only. |
| 7 | No leisure × medication bridge (Q4) | OK — fully orthogonal. |
| 8 | No widget (out of scope v2.2) | OK. |
| 9 | No AI-suggested activities (out of scope v2.1) | OK. |
| 10 | No calendar block scheduling | OK. |
| 11 | Do not invent streak forgiveness rule | OK — uses `DailyForgivenessStreakCore`. |
| 12 | No leisure content in Crashlytics or analytics | OK — only IDs + counts + minutes logged. |
| 13 | No full-text str_replace on similar names | Process rule, enforced during Phase 2. |
| 14 | No auto-memorize patterns | Process rule. |
| 15 | Do NOT skip Phase 4 summary | Will fire pre-merge per CLAUDE.md repo convention. |
| 16 | Do not amend commits without re-verifying | Process rule, enforced during Phase 2. |
| 17 | Audit doc < 1500 lines | OK — this doc < 500 lines. |
| 18 | Do not ship if Phase F window threatened | Operator-accepted scope; STOP available at any boundary. |
| 19 | No additional G-series items bundled | OK — only leisure v2.0. |
| 20 | Do not skip operator pre-merge gate if STOP-PHASE-F-RISK fires | STOP fired + operator decided → bundle continues. |
| 21 | No new admin CLI scripts | OK. |
| 22 | enforcementMode stays SOFT/MEDIUM/HARD enum | OK. |
| 23 | No same-day enforcement changes | OK — pending-mode-next-day pattern (§9 STOP-9A). |
| 24 | No pool size cap on Q2 free-text auto-add | OK — uncapped (operator decision deferred). |

---

## §16 — Phase 2 execution log

(Appended as commits land.)

---

## §17 — Phase 3 bundle summary

(Appended after final commit pre-push.)

---

## §18 — Phase 4 Claude Chat handoff

See terminal output at session end.
