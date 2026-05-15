# Remove SoD Backfill — Audit

**Filed**: 2026-05-15 (pre-empted from Phase H Wk 3 / Oct-Nov 2026 via single-user override)
**Scope**: remove the one-shot SoD migration backfill that healed pre-v1.4.0 installs landing in the transitional `hasCompletedOnboarding=true && hasSetStartOfDay=false` state.
**Hard constraint**: do NOT touch the SoD race-fix code itself (write reorder in `OnboardingViewModel.checkExistingUserAndMaybeSkip`, gate-check `LaunchedEffect` body). Only the backfill block.
**Premise verification**: operator override grounded in single-user pre-launch reality. Avery's current install + Phase E3 tester installs all post-date PR #583 + #585 (Apr 20 2026), so the pre-fix-install population is empty.

---

## § 1 — Class inventory  (GREEN, re-classified)

**Premise verification**: prompt assumed a dedicated `SodBackfill` class. **Premise wrong** — the backfill was never a class, it was an inline migration block inside `MainActivity.kt`'s SoD-prompt `LaunchedEffect`. Re-classification per memory `feedback_no_deferrals_if_not_there_fix_it.md`: PROCEED with actual code location.

**Findings**:
- No file matching `*SodBackfill*` / `*StartOfDayBackfill*` / `*DayBoundaryBackfill*`.
- No class declaration matching `class.*SodBackfill`.
- Backfill landed inline via PR #583 (commit `22311c40`, Fix 3) and was merged into a single `LaunchedEffect(hasCompletedOnboarding)` body via PR #585 (commit `4e854b36`).
- Live shape: `app/src/main/java/com/averycorp/prismtask/MainActivity.kt:437-475`.

**Actual removal target**: lines 437-475 of `MainActivity.kt` (the comment preamble explaining the merge + the backfill block inside the `LaunchedEffect`). Gate-check block (lines 466-474) is preserved with light comment trimming.

**Recommendation**: PROCEED with inline removal.

---

## § 2 — Caller inventory  (GREEN)

**Findings**:
- The backfill has no callers outside its own `LaunchedEffect` — it is the LaunchedEffect body itself (composed at `MainActivity.kt:444`, executed on `hasCompletedOnboarding` key flips).
- `PrismTaskApplication.kt` does not invoke any SoD backfill (only `AutomationDuplicateBackfiller` is wired there, which is PR #1077 and out of scope).
- `BuiltInSyncPreferences` does not declare nor read any SoD-specific flag.

**Recommendation**: PROCEED — caller-site removal is the same as code-site removal.

---

## § 3 — BuiltInSyncPreferences flag inventory  (GREEN — nothing to remove)

**Premise verification**: prompt assumed a dedicated guard flag (e.g. `sod_backfill_done`). **Premise wrong** — backfill was implicitly idempotent on the value of `hasSetStartOfDay` itself:

```kotlin
val sodSetBefore = taskBehaviorPreferences.getHasSetStartOfDay().first()
if (!sodSetBefore) {
    taskBehaviorPreferences.setHasSetStartOfDay(true)
}
```

After the first execution, `sodSetBefore` reads `true`, so the write is skipped. No flag needed.

**Findings**:
- `BuiltInSyncPreferences` declares 15 flags (`BUILT_INS_RECONCILED`, `DRIFT_CLEANUP_DONE`, `BUILT_IN_BACKFILL_DONE`, `NEW_ENTITIES_BACKFILL_DONE`, `INITIAL_UPLOAD_DONE`, `CLOUD_ID_RESTORE_DONE`, `LIFE_CATEGORY_BACKFILL_DONE`, `BUILT_IN_TASK_TEMPLATES_RECONCILED`, `TASK_TEMPLATE_BACKFILL_DONE`, `AUTOMATION_DUP_BACKFILL_DONE`, five `*_BACKFILL_DONE` per-family flags, `DISMISSED_BUILT_IN_UPDATES`). None are SoD-related.
- `taskBehaviorPreferences.hasSetStartOfDay` is the LIVE user-facing setting (Settings → Global Defaults → Start of Day) — must NOT be removed.

**Verdict**: GREEN-DELETE for the backfill code; nothing to remove from `BuiltInSyncPreferences`.

---

## § 4 — Test inventory  (GREEN — nothing to delete)

**Findings**:
- No test files matching `*SodBackfill*` / `*StartOfDayBackfill*`.
- `app/src/androidTest/java/com/averycorp/prismtask/smoke/SmokeTestBase.kt:65` references `taskBehaviorPreferences.setHasSetStartOfDay(true)` only as test-setup to bypass the SoD prompt during smoke tests — independent of the backfill, must be preserved.
- `OnboardingViewModelTest.kt` has zero references to backfill/`hasSetStartOfDay`. No tests cover the v1.4.0 write-reorder race fix behavior directly (an existing testing gap, but not in scope here).

**Recommendation**: no test deletions; no new tests needed for delete-only inert-code removal.

---

## § 5 — SoD race fix verification  (GREEN — load-bearing code intact)

**Critical**: confirms the LOAD-BEARING fixes are NOT being touched.

**Findings**:
1. **Write reorder** (`OnboardingViewModel.checkExistingUserAndMaybeSkip:285-315`): intact. Order is `setHasSetStartOfDay(true)` → `setOnboardingCompleted(completedAt)` → `_signInState.value = ExistingUserDetected`, matching commit `22311c40` Fix 1 invariant. KDoc invariant comment at lines 297-305 is intact.
2. **Gate-check LaunchedEffect** (`MainActivity.kt:443-475`): intact. Will retain `LaunchedEffect(hasCompletedOnboarding)` keying, `if (hasCompletedOnboarding != true) return@LaunchedEffect` guard, and the `if (!alreadySet) showStartOfDayPrompt = true` gate.
3. **`DayBoundary` utility** (`util/DayBoundary.kt`): untouched — not in scope; PR #1077 / UTIL_DAYBOUNDARY_SWEEP_AUDIT.md territory.
4. **MedicationScreen SoD-boundary fix** (PR #798): not touched; preserved.
5. **Widget SoD routing** (PR #1042 era — 8 today-view-shaped widgets): not touched; preserved.
6. **PR #1077 AutomationDuplicateBackfiller**: not touched; preserved.

**Verdict**: STOP-A3 cleared. Only the backfill block + its merge-rationale comment are removed.

---

## § 6 — Removal plan

**Files to edit (no full-file deletes)**:

1. `app/src/main/java/com/averycorp/prismtask/MainActivity.kt`
   - Remove preamble comment block (lines 437-442) explaining why backfill + gate are collapsed — no longer applicable once backfill is gone.
   - Remove backfill block + KDoc (lines 447-464):
     - The 14-line comment ("Migration backfill for v1.4.0 SoD skip-race …")
     - The 4 lines of code reading + writing `hasSetStartOfDay`.
   - Trim gate-check comment (lines 466-472) to remove backfill references — the gate stands on its own merits.
   - Net: ~28 LOC deletes, ~3 LOC comment trim.

**Files to delete**: none.

**Tests to delete / add**: none.

**Total LOC**: ~28-31 deletions, 0-3 additions (minor comment edit). Well under the 300-LOC STOP-1A threshold; well within the prompt's 50-200 LOC estimate (low end).

---

## § 7 — Bundle-decision

- **PR shape**: single PR, single commit. Smallest viable surgical delete.
- **Branch**: `chore/remove-sod-backfill` (worktree: `.claude/worktrees/remove-sod-backfill`).
- **Auto-merge**: `gh pr merge --auto --squash --delete-branch`.
- **Timeline closure impact**: Phase H "Remove SoD backfill (if pre-fix-install population empty)" item closes from 0 → 1.0; preempted from Oct-Nov 2026 to May 14-15 2026 under single-user override.
- **Phase F GREEN-GO impact**: NEUTRAL — delete-only cleanup, no behavior change.

---

## Ranked improvement table

| Item | Wall-clock saved | Cost | Verdict | Notes |
|------|------------------|------|---------|-------|
| Remove SoD backfill | clears 1 H-Wk-3 hygiene item; ~28 LOC dead code gone | ~10 min | PROCEED | Inline in MainActivity.kt; no class/flag/tests |

## Anti-pattern flags (not-fixing, just noting)

- **Backfill-by-comment-anchor pattern**: backfill was tagged with `TODO(v2.2): remove once the pre-fix install population has rolled over` — useful, but the date floated forward (v2.1 → v2.2). Single-user-override happened to make it deletable 5 months ahead of schedule. Pattern: if you ship a backfill with a TODO, also file the removal as a concrete timeline item rather than version-tagged.
- **Backfill structure was idempotent without a flag** — clean. Don't be tempted to add a `BuiltInSyncPreferences` flag for one-shot backfills that can be cheaply re-read from live data (it would just be more code to clean up later).
- **Gate-check comment couples to backfill** — pre-removal text said "backfill above wrote true". Trim to stand-alone after the removal. The gate-check itself is still defensive coverage worth keeping.

---

## Phase 3 — Bundle summary

**PR**: `chore/remove-sod-backfill` → main (single-commit squash auto-merge).
**Files touched**: 2 (`MainActivity.kt`, audit doc itself).
**Net LOC**: -34 / +0 on `MainActivity.kt`.
**Drift vs estimate**: prompt estimated ~50-200 LOC; actual -34 — within the "low end of estimate" prediction (no backfill class, no flag, no tests existed, so the only delete-able surface was the inline block).

**Per-item closure**:
- Class file: re-classified (none existed) → inline removal in `MainActivity.kt:437-475` complete.
- Caller site: same as code-site; no separate caller to delete.
- BuiltInSyncPreferences flag: re-classified (none existed) → no change to preferences.
- Test file: re-classified (none existed) → no test deletes.

**Scope changes vs prompt**: none structural; three premise re-classifications (class / flag / tests) per memory `feedback_no_deferrals_if_not_there_fix_it.md` — PROCEED with actual code path rather than DEFER on missing-class premise.

**Process incidents**: none. All STOP conditions cleared cleanly:
- STOP-A1 (class findable): re-classified to inline, PROCEED.
- STOP-A2 (flag has only backfill callers): re-classified (no flag), PROCEED.
- STOP-A3 (race fix code intact): CLEARED.
- STOP-INSTALL-DATE-A (test device install dates): CLEARED via single-user override.
- STOP-PHASE-F-RISK / STOP-1A / STOP-1B: not fired.

**Memory candidates (wait-for-third)**:
- 1 data point: "Backfill blocks tagged `TODO(v_X.Y)` deserve a concrete timeline filing too — version anchors float forward, single-user-override can deletable them years ahead of the version target."
- 1 data point: "When a prompt assumes a dedicated class/flag/test trio, but the live shape is inline code, re-classify each missing premise as PROCEED with the actual surface (don't DEFER)."

Neither passes the 3-data-point auto-memorize bar; flagging only.

**Re-baselined wall-clock**: small-scope delete-only audit-to-PR cycle ≈ 15-25 min wall-clock for inline-removal shape (no fixture rebuilds needed).

**Schedule for next audit**: none queued.

---

## Phase 4 — Claude Chat handoff

```markdown
# SoD Backfill Removal — handoff

**Scope**: removed the one-shot v1.4.0 SoD migration backfill from `MainActivity.kt` ahead of its filed Phase H Wk 3 (Oct-Nov 2026) slot, under single-user pre-launch override.

**Verdicts**

| Item | Verdict | Finding |
|------|---------|---------|
| SoD backfill class | GREEN (re-classified) | Never a class — inline code in `MainActivity.kt:437-475` |
| Caller site | GREEN | Caller-site == code-site; `PrismTaskApplication.kt` does not wire any SoD backfill |
| `BuiltInSyncPreferences` flag | GREEN (re-classified) | No SoD-specific flag; backfill was implicitly idempotent on `hasSetStartOfDay` itself |
| Tests | GREEN (re-classified) | No tests reference the backfill; `SmokeTestBase.kt:65` uses `setHasSetStartOfDay(true)` only as test-setup, unaffected |
| Race-fix code (write reorder + gate `LaunchedEffect`) | GREEN | `OnboardingViewModel.checkExistingUserAndMaybeSkip:285-315` write order intact; `MainActivity.kt` gate-check `LaunchedEffect(hasCompletedOnboarding)` intact |

**Shipped**
- 1 PR: `chore/remove-sod-backfill` — `MainActivity.kt` -34 LOC, 0 additions; preserves write-reorder fix (PR #583) and gate-check shape (PR #585).

**Deferred / stopped**: none.

**Non-obvious findings**
- The backfill was *inline* inside the same `LaunchedEffect` as the gate check (PR #585 merged them deliberately to defeat a Compose coroutine race between two separate `LaunchedEffect`s). With backfill gone, the "merged" structure is no longer load-bearing — the gate check alone, keyed on `hasCompletedOnboarding`, is a clean standalone defensive check.
- Backfill had no dedicated `BuiltInSyncPreferences` flag — it idempotently no-op'd by re-reading `hasSetStartOfDay` before writing. Clean pattern; don't add flags for one-shot backfills where live data is the natural gate.
- `taskBehaviorPreferences.setHasSetStartOfDay(true)` calls in `SmokeTestBase.kt` and `OnboardingViewModel` are NOT backfill leftovers — they're load-bearing test setup and the v1.4.0 race fix respectively.

**Open questions**: none.

**Hard constraints honored**
- No change to write reorder in `OnboardingViewModel.checkExistingUserAndMaybeSkip`
- No change to `LaunchedEffect(hasCompletedOnboarding)` gate-check body (keying, guard, gate check itself preserved)
- No change to `DayBoundary.startOfCurrentDay()` / MedicationScreen SoD fix (PR #798) / widget SoD routing / `AutomationDuplicateBackfiller` (PR #1077)
- No new feature flags introduced
- `BuiltInSyncPreferences` untouched
- Branch + worktree teardown queued post-merge
```

---

## H closure target

After PR merges (CI green):
- Phase H "Remove SoD backfill (if pre-fix-install population empty)" item: 0 → 1.0
- H open-item count drops by 1
- Phase H still has 4 other open items (Wear OS, Android wellness port, Notification profiles, Cross-device sync validation)
- Phase F GREEN-GO impact: NEUTRAL

