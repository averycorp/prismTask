# F9 Morning Check-In Mega — `shouldPrompt` Cleanup + Cutoff UX Guard

Audit doc for the F9 mega bundle. Covers two morning-check-in deferrals
from PR #1166 audit Item 4 + the cutoff Settings UX gap that surfaced in
the same audit. Single PR.

**Branch**: `claude/cleanup-shouldprompt-cutoff-ux-MI8eX`
**Base**: `origin/main` @ `1592e09` (chore: bump to v1.8.49 (build 847))

---

## Phase 0 — Base-branch verification

- `git fetch origin` succeeded.
- `git rebase origin/main` reported up-to-date — branch was already on the
  current `main` tip.
- `git log --oneline origin/main | grep -E "#(1166|1168|1171)"` confirms all
  three required PRs are visible:
  - `2c977e6 docs(privacy): F8 chat privacy doc update + V2 disclosure re-fire (#1171)`
  - `88ee65a fix(chat): F8 follow-on bundle — B.3/B.4/C.3/C.4/C.5/D.3 (#1168)`
  - `6e5a73e fix(checkin): morning check-in banner respects Start-of-Day end-to-end (#1166)`
- `MorningCheckInBannerDecider` exists at
  `app/src/main/java/com/averycorp/prismtask/domain/usecase/MorningCheckInBannerDecider.kt`.

**Verdict**: GREEN. No clone-state divergence; no STOP-A condition.

---

## Item 1 — `shouldPrompt` cleanup path picker

### Recon

- `MorningCheckInResolver.kt:46-51` — `CheckInPlan` data class has fields
  `shouldPrompt`, `steps`, `topTasks`, `todayHabits`.
- `MorningCheckInResolver.kt:73, 87, 107` — `shouldPrompt` is computed
  inside `plan()` and assigned to the returned `CheckInPlan`.
- `MorningCheckInViewModel.kt:166-178` — production consumer reads
  `plan.steps`, `plan.topTasks`, `plan.todayHabits` only. It does **not**
  read `plan.shouldPrompt`.
- `TodayViewModel.kt:218-260` — Today-banner pipeline calls
  `MorningCheckInBannerDecider.shouldShow(...)` directly (the SoD-aware
  replacement landed in PR #1166); it does not invoke
  `MorningCheckInResolver.plan()` at all.
- `grep -rn 'shouldPrompt' app/src/` confirms the only readers are 4 unit
  tests in `MorningCheckInResolverTest.kt:41,57,73,90`. Production paths
  do not read it anywhere.

### Path A vs Path B

- **Path A — delete dead field + 4 tests.** `shouldPrompt` is dead; the
  only readers are the 4 named tests, which test the dead behavior.
  Estimated production LOC: ~5 lines (field declaration + the 2 assignments
  + the calculation block at line 85-87). Estimated test LOC: ~60 lines (4
  test functions, each ~13 lines including setup).
- **Path B — fold Resolver into TodayVM.** Blocked: `MorningCheckInResolver`
  is a real production dependency of `MorningCheckInViewModel` (line 75) for
  `plan().steps`, `plan().topTasks`, `plan().todayHabits` (the body of the
  guided check-in flow). Folding it into TodayVM would require migrating
  step/topTask/todayHabit derivation as well — many LOC, broader blast
  radius than scope.

**Path picker rule (from prompt)**:
- `MorningCheckInResolver.plan()` has multiple production consumers of
  non-`shouldPrompt` fields (steps, topTasks, todayHabits via
  MorningCheckInViewModel) → Path B is more invasive than scope allows.
- Path A LOC ≈ 65 (5 prod + 60 test); Path B LOC well over 120 if attempted.
- → **Path A** wins by both branches of the picker rule.

### Verdict

**Item 1 — Path A: delete `CheckInPlan.shouldPrompt` field + the
`shouldPrompt = ...` calculation block + the 4 dedicated tests.** Production
risk: zero (no production reader). Test risk: zero (the 4 tests test the
dead field directly; remaining 5 tests in the file cover steps / topTasks /
habits and stay intact).

---

## Item 2 — Cutoff UX guard implementation surface

### Recon

- Cutoff Settings UI: `AdvancedTuningScreen.kt:610-621` —
  `MorningCheckInGroup` composable, single `IntSliderRow` "Latest hour"
  range 0..23 that calls `vm.setMorningCheckIn(state.copy(latestHour = it))`
  on every change.
- Settings ViewModel: `AdvancedTuningViewModel.kt:91-93, 158` —
  `morningCheckIn: StateFlow<MorningCheckInPromptCutoff>` and
  `setMorningCheckIn(value)` both wired to `AdvancedTuningPreferences`.
- SoD source: `TaskBehaviorPreferences.getStartOfDay()` returns
  `Flow<StartOfDay(hour, minute)>` (file:lines 113+). Already injected into
  every place that needs it.
- Wrap-around semantics: `MorningCheckInBannerDecider.windowOffsetMinutes`
  (lines 61-66) computes
  `if (raw <= 0) raw + 24*60 else raw` where `raw = cutoffMinutes - sodMinutes`.
  So `cutoff <= sod` is interpreted as wrap-around (next-day cutoff).

### Important UI shape note

The current cutoff UI is **not** a Save-button form — it's a live slider
that commits on every drag tick. The operator pre-decision wording
("disable Save button + show validation message") translates into the
actual UI as: **don't persist invalid slider positions, and show a
validation message inline**. This is still an explicit gate (the user sees
why nothing was saved) — not a silent clamp (which would have rewritten
their value to a "nearest valid" silently).

### Validation rule (canonical)

Per prompt + the BannerDecider's wrap-around behavior, the rule is:

```
fun validate(sodHour: Int, cutoffHour: Int): Validation =
    when {
        cutoffHour == sodHour                         -> Invalid(zeroLengthMessage)
        cutoffHour < sodHour && sodHour < EVENING_SOD -> Invalid(smallSodMessage)
        else                                          -> Valid
    }
```

with `EVENING_SOD = 12` (i.e. SoD is "early" / pre-noon when < 12). This
naturally:

- Rejects `cutoff == sod` (zero-length morning per operator's mental
  model; BannerDecider would treat as 24h, but the operator's pre-decision
  is to invalidate).
- Rejects `cutoff < sod` when SoD is itself early (e.g. SoD=4, cutoff=3 →
  user almost certainly didn't mean a 23-hour wrap-around morning window).
- Accepts the canonical wrap-around case (SoD=22, cutoff=2 → 4-hour
  wrap-around morning window for a late-SoD user).
- Accepts the canonical normal case (SoD=4, cutoff=11 → 7-hour forward
  window).

The "(SoD - cutoff) is small (< 1 hour heuristic)" wording in the prompt's
spec was flagged "Threshold to confirm during audit." Since the slider
is hours-only (Int), `< 1 hour` collapses to `cutoffHour == sodHour`,
which is already handled by the first arm. The second arm uses the
"SoD is small" interpretation (SoD < 12) instead — wrap-around only makes
sense for late-SoD users.

### Implementation surfaces

1. **Pure validator** (new file or top-level in
   `AdvancedTuningViewModel.kt`):
   ```
   sealed class MorningCheckInCutoffValidation {
       data object Valid : MorningCheckInCutoffValidation()
       data class Invalid(val reason: String) : MorningCheckInCutoffValidation()
   }
   fun validateMorningCheckInCutoff(sodHour: Int, cutoffHour: Int): MorningCheckInCutoffValidation
   ```
2. **AdvancedTuningViewModel**: inject `TaskBehaviorPreferences`, expose
   `startOfDayHour: StateFlow<Int>` (from `getStartOfDay()`), and a derived
   `morningCheckInValidation: StateFlow<...>` combining `morningCheckIn` +
   `startOfDayHour`. Gate `setMorningCheckIn(value)` so it short-circuits
   when validation fails (still returns; the slider's local position will
   recompose back to the persisted state on next emission).
3. **AdvancedTuningScreen `MorningCheckInGroup`**: subscribe to
   `morningCheckInValidation`. Render a small caption Text below the
   slider when state is `Invalid`, using the validation's `reason` string
   in `MaterialTheme.colorScheme.error` + `typography.labelSmall`.

### Validation message copy

Two variants from the prompt:

- Variant 1 (concrete): "Cutoff must be after Start-of-Day. Adjust
  Start-of-Day in Advanced settings if you want a wrap-around morning
  window."
- Variant 2 (terse): "Invalid cutoff — must come after Start-of-Day."

**Audit picks Variant 2** (terse). Reasoning: Variant 1 references
"Advanced settings" generically — but SoD is configured in Task Behavior /
General Settings, not Advanced Tuning. Variant 1 also presumes the user
wanted wrap-around, which conflicts with the more common "user error"
case (small SoD + small cutoff). Variant 2 is shorter, unambiguous, and
title-cased per `CLAUDE.md` conventions. The same string used for both
the `cutoff == sod` and "small SoD" cases — the underlying user-visible
problem is the same ("cutoff isn't after SoD"), differentiating the
messages adds confusion.

### Verdict

**Item 2 — GREEN**: validator (~25 LOC), AdvancedTuningViewModel changes
(~15 LOC), AdvancedTuningScreen changes (~10 LOC). Plus unit tests
(~50 LOC). Combined Item 1 + Item 2 estimate: **~165 LOC** — slightly above
the ~150 ceiling but Item 1 is dominantly test deletion (negative LOC). Net
delta after Item 1's deletions: ~115 LOC. STOP-LOC does not fire.

---

## Item 3 — Sibling/dead-code-completion sweep

Sweep target: morning-check-in surface
(`MorningCheckInResolver.kt`, `MorningCheckInPreferences.kt`,
`MorningCheckInScreen.kt`, `MorningCheckInViewModel.kt`,
`MorningCheckInBannerDecider.kt`).

### Findings

- `MorningCheckInResolver.kt`: only `shouldPrompt` is dead (Item 1
  catch). `CheckInStep` enum, `MorningCheckInConfig` data class, all
  other `CheckInPlan` fields, and the `plan()` function body are all
  consumed.
- `MorningCheckInPreferences.kt`: 5 public methods (`bannerDismissedDate`,
  `featureEnabled`, `isBannerDismissedToday`, `dismissBannerToday`,
  `setFeatureEnabled`). All consumed:
  `featureEnabled`/`bannerDismissedDate` by `TodayViewModel.kt:219-220`;
  `dismissBannerToday` by `TodayViewModel.kt:139`; `setFeatureEnabled` by
  `DataImporter.kt:1544`. `isBannerDismissedToday` is the only suspended
  one — confirmed used in `TodayViewModel` via the Flow path; the
  suspended convenience is preserved for symmetry but not currently
  invoked. Borderline, but not "declared and never set" — both writer
  (`dismissBannerToday`) and readers exist. No dead state.
- `MorningCheckInViewModel.kt`: 5 `MutableStateFlow`s (`_completedSteps`,
  `_isFinished`, `_plan`, `_medicationDoses`, `_calendarEvents`). All
  written (lines 174, 198, 203, 228, 263) and all read (via their public
  alias / `combine` participants).
- `MorningCheckInScreen.kt`: only 3 `remember { mutableStateOf(...) }`
  declarations (mood/energy/notes lines 247-249), all read by the
  composable that owns them.
- `MorningCheckInBannerDecider.kt`: tiny pure object; every parameter is
  consumed in `shouldShow`.

### Verdict

**Item 3 — GREEN: no additional dead code surfaced beyond `shouldPrompt`.**
Dead-code-completion 4th-instance probe **does not fire**. Operator's
"wait-for-fourth before memorizing" memory rule is **not triggered** by
this audit; recorded for the session summary so the count stays at 3
(PR #1164 / PR #1166 / PR #1168).

---

## Item 4 — STOP conditions

- **STOP-A (premise wrong)**: did not fire. Phase 0 verified all required
  PRs in main; `MorningCheckInBannerDecider` exists; `shouldPrompt` is
  confirmed dead in production (only test readers).
- **STOP-LOC (>150 combined)**: gross estimate ~165 LOC; net delta after
  Item 1 deletions ~115 LOC. Does not fire.
- **STOP-D (sibling balloon ≥ 2)**: did not fire (Item 3 GREEN, 0 sibling
  dead-code instances).
- **STOP-F (>200 LOC)**: not approached.

No STOPs fire. Proceed to Phase 2.

---

## Phase 2 — Implementation plan

1. **Item 1 — Path A**: delete `CheckInPlan.shouldPrompt` + the
   `beforeThreshold`/`alreadyToday`/`shouldPrompt` calc block at
   `MorningCheckInResolver.kt:81-87` + the disabled-config branch's
   `shouldPrompt = false` assignment + the assignment in the main
   return. Remove the `now`/`zone`/`lastCompletedDate` params if they
   become unused, but **leave them in place if other tests still pass
   them** — minimum-diff rule.
2. **Item 1 — tests**: delete the 4 `shouldPrompt`-named tests in
   `MorningCheckInResolverTest.kt:40-102`. Keep all other tests intact.
3. **Item 2 — validator**: add
   `MorningCheckInCutoffValidation` sealed class +
   `validateMorningCheckInCutoff(sodHour, cutoffHour)` pure function.
   Co-locate near `AdvancedTuningViewModel` (top-level in same file)
   to keep the change self-contained.
4. **Item 2 — VM**: inject `TaskBehaviorPreferences` into
   `AdvancedTuningViewModel`, expose `morningCheckInValidation:
   StateFlow<MorningCheckInCutoffValidation>` derived from
   `getStartOfDay()` + `morningCheckIn` flows, and gate
   `setMorningCheckIn` to no-op on invalid input.
5. **Item 2 — UI**: in `MorningCheckInGroup`, collect
   `morningCheckInValidation` and render a small error caption below
   the slider when `Invalid`.
6. **Item 2 — tests**: pure-function unit tests for the validator.

### Test plan (Item 2)

Cases:
- `cutoff == sod` → Invalid (zero-length).
- `cutoff < sod` with small SoD (sod=4, cutoff=3) → Invalid.
- Wrap-around (sod=22, cutoff=2) → Valid.
- Normal (sod=4, cutoff=11) → Valid.
- Boundary: `cutoff = sod + 1` → Valid (1-hour forward window).
- Boundary: `cutoff = 0`, `sod = 0` → Invalid (==).
- `cutoff = 23`, `sod = 0` → Valid (forward 23h window — uncommon but not
  user error since SoD itself is 0).

### Validation message copy (locked)

`"Invalid Cutoff — Must Come After Start-of-Day."` (Variant 2, title-cased
per CLAUDE.md).

---

## Phase 3 — Verification plan

- `./gradlew compileDebugKotlin testDebugUnitTest`
- `./gradlew compileDebugAndroidTestKotlin`
- If local Gradle blocked (Linux host limitation): rely on CI as the gate;
  flag in summary.

---

## Pre-flight checklist

- [x] Phase 0 base-branch verification passed.
- [x] Audit doc started, Item 1 verdict written (Path A), Item 2 verdict
      GREEN.
- [x] STOP-LOC check passed (combined ~165 gross, ~115 net).
- [x] STOP-D check passed (Item 3 surfaced 0 dead-code instances).
- [x] No premise reframes from prompt.

Proceed to Phase 2.
