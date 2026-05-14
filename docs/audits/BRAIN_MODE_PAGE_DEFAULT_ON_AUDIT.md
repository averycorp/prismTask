# BrainModePage default-on for ND modes + idiom-drift follow-up audit

**Scope.** Two findings being closed in one PR:

1. **PR #1123 OB-2 audit F.8 item** — "BrainModePage `collectAsLocalState`
   idiom drift": local `mutableStateOf(false)` toggles in BrainModePage instead
   of reading ViewModel state.
2. **Operator product decision (2026-05-14)** — for an ADHD-focused app, the
   three neurodivergence modes (ADHD / Calm / Focus & Release) should default
   ON for first-time users. Framing: "presume neurodivergent baseline, let
   users opt out" rather than "presume neurotypical, require users to declare
   themselves."

The audit is recon-first: Phase 1 surfaces what's already shipped, what still
needs work, and migration safety. Phase 2 implements the residual gap as a
single PR per CLAUDE.md "Audit-first Phase 3+4 fire pre-merge" convention.

## Product framing (operator decision, 2026-05-14)

PrismTask is an ADHD-focused app. The three neurodivergence modes (ADHD,
Calm, Focus & Release) default ON for first-time users. The framing is
"presume neurodivergent baseline, let users opt out" rather than "presume
neurotypical, require users to declare themselves." This pairs with the
broader forgiveness-first philosophy: the app meets users where they are
rather than asking them to fit a default. If/when `docs/FORGIVENESS_FIRST.md`
lands, cross-link this audit from its "Companion principles" section.

## Phase 0 — premise verification

| # | Premise | Verified | Notes |
|---|---------|----------|-------|
| P1 | `OnboardingScreen.kt` exists at the canonical onboarding path | ✓ | `app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingScreen.kt` |
| P2 | BrainModePage near lines 767–769 (per PR #1123 audit citation) | ✓ (drifted) | Now at `OnboardingScreen.kt:786`. Drift of +19, within ±50 tolerance. |
| P3 | The three ViewModel setters exist | ✓ | `OnboardingViewModel.kt:336,340,344` |
| P4 | Read-side StateFlows exist on the ViewModel | ✓ | `OnboardingViewModel.kt:125–136` (added since PR #1123) |
| P5 | DataStore defaults are findable | ✓ | `NdPreferencesDataStore.kt:73–75` with `?: false` fallbacks; data-class defaults in `NdPreferences.kt:16–18` |
| P6 | App version ≥ v1.8.49 | ✓ | `app/build.gradle.kts:22` reports `1.9.26` |

**Premise overturned in good way.** Per P2/P4 sweeps, the F.8 idiom drift is
**already fixed** in current `main`: `OnboardingScreen.kt:787–793` reads
`viewModel.adhdMode/calmMode/focusReleaseMode` via the inline
`collectAsLocalState` helper (definition at `OnboardingScreen.kt:1408–1420`),
and the inline comment at lines 787–790 explicitly cites "F8 idiom drift fix."
The OB-2 audit (`docs/audits/OB2_SKIP_COMMIT_ON_CHANGE_AUDIT.md:189`)
confirmed this bug at lines 767–769; it was closed at some point between PR
#1123 and now. Phase 2 scope therefore **reduces to the default-on flip
only** — the idiom work is done.

## Phase 1 — verdict matrix

### V1 — Idiom drift status (PR #1123 F.8 item) — **GREEN**

BrainModePage at `OnboardingScreen.kt:786–891` already reads the three ND
mode flags from `OnboardingViewModel` via `collectAsLocalState`:

```
val adhdSelected by collectAsLocalState(viewModel.adhdMode, initial = false)
val calmSelected by collectAsLocalState(viewModel.calmMode, initial = false)
val focusReleaseSelected by collectAsLocalState(viewModel.focusReleaseMode, initial = false)
```

No `mutableStateOf(false)` exists for the three flags. PR #1123 F.8 item
("BrainModePage collectAsLocalState idiom drift") is closed at the
implementation level. The audit-tracking line in `OB2_SKIP_COMMIT_ON_CHANGE_AUDIT.md:189`
should be updated to reflect closure when this PR lands.

The only residual concern is the `initial = false` argument — for the
default-on world this should be `initial = true` so the 1-frame flash before
the StateFlow's first emission matches the persisted value. Folded into V3.

### V2 — Read-side StateFlows on the ViewModel — **GREEN**

`OnboardingViewModel.kt:125–136`:

```
val adhdMode: StateFlow<Boolean> = ndPreferencesDataStore
    .ndPreferencesFlow
    .map { it.adhdModeEnabled }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
// (calmMode + focusReleaseMode same shape)
```

Read-side exists. **STOP-D-1 (no read-side StateFlow) does not fire.**

The `stateIn` initial value `false` is a 1-frame pre-emission placeholder
analogous to V1 — folded into V3.

### V3 — Current first-time-user default — **RED (needs flip)**

The load-bearing default lives in `NdPreferencesDataStore.ndPreferencesFlow`
at `NdPreferencesDataStore.kt:73–75`:

```
adhdModeEnabled = prefs[KEY_ADHD_MODE] ?: false,
calmModeEnabled = prefs[KEY_CALM_MODE] ?: false,
focusReleaseModeEnabled = prefs[KEY_FOCUS_RELEASE_MODE] ?: false,
```

Mirrored in the `NdPreferences` data class defaults at `NdPreferences.kt:16–18`.

Per operator product decision, these should default `?: true`. Cascading
sub-settings should also flip so a fresh install's state is self-consistent
with the `setAdhdMode(true)` / `setCalmMode(true)` cascade semantics in
`NdPreferencesDataStore.kt:113–137`:

- ADHD cascade flips `KEY_COMPLETION_ANIMATIONS`, `KEY_STREAK_CELEBRATIONS`,
  `KEY_SHOW_PROGRESS_BARS`, `KEY_FORGIVENESS_STREAKS` — these need `?: true`
  defaults.
- Calm cascade flips `KEY_REDUCE_ANIMATIONS`, `KEY_MUTED_COLOR_PALETTE`,
  `KEY_QUIET_MODE`, `KEY_REDUCE_HAPTICS`, `KEY_SOFT_CONTRAST` — these need
  `?: true` defaults.
- F&R cascade flips `KEY_GOOD_ENOUGH_TIMERS = true`, `KEY_ANTI_REWORK = true`,
  `KEY_SOFT_WARNING = true`, `KEY_COOLING_OFF = false`, `KEY_REVISION_COUNTER
  = false`, `KEY_SHIP_IT_CELEBRATIONS = true` — already match these defaults
  in `NdPreferencesDataStore.kt:86–101` and `NdPreferences.kt:33–45`. **No
  changes needed on F&R sub-settings.**

### V4 — Migration safety — **YELLOW (D-2 nuance, acceptable per operator intent)**

`?:` semantics: `prefs[KEY_X] ?: <default>` returns the stored value when
the key is **present** (even if the stored value is `false`), and returns
`<default>` only when the key is **absent**. Behavior under the flip:

| User class | DataStore state | Reads as (before) | Reads as (after) |
|---|---|---|---|
| Fresh install | Key absent | `false` | `true` (intent) |
| v1.x user, BrainModePage shown, toggled ON then OFF | Key present, value `false` | `false` | `false` (preserved) |
| v1.x user, BrainModePage shown, currently ON | Key present, value `true` | `true` | `true` (preserved) |
| v1.x user, BrainModePage shown, never tapped any of three toggles | Key absent | `false` | `true` (**FLIP**) |
| v1.x user, BrainModePage skipped via "Skip" button | Key absent | `false` | `true` (**FLIP**) |

The two FLIP rows are the **D-2 nuance**: an absent key is indistinguishable
from "user actively chose false" if the v1.x BrainModePage initialized local
state to `false`. Looking at the historical BrainModePage (now-fixed, but
behavior at PR #1123 horizon): local `var adhdSelected by remember {
mutableStateOf(false) }` toggled via `onToggle = { viewModel.setAdhdMode(!adhdSelected) }`.
A user who never tapped any toggle never wrote to DataStore, so the key
stayed absent. A user who tapped a toggle once wrote `true`; tapping again
wrote `false`. So **explicit-OFF via UI is recoverable** (key present,
value `false`).

Per operator constraint #3: "Migration must not flip OFF→ON for users who
**explicitly turned a mode off** in v1.x." Strictly interpreted, "never
touched the toggle" is not "explicitly turned off." The flip is consistent
with this constraint.

**STOP-D-2 does not fire — operator intent matches the flip semantics.**

### V5 — Sibling code paths that branch on the three flags — **YELLOW (consistent with operator intent)**

`grep -rn 'adhdModeEnabled|calmModeEnabled|focusReleaseModeEnabled|KEY_ADHD_MODE|KEY_CALM_MODE|KEY_FOCUS_RELEASE_MODE' app/src/main/java/`:

| Site | Behavior with default-on |
|---|---|
| `ui/screens/settings/sections/BrainModeSection.kt:49,56,63` — Settings page renders three toggles | All three render ON on fresh install. Matches operator intent. |
| `ui/screens/settings/sections/BrainModeSection.kt:68–70,83` — conflict banners + F&R sub-controls visibility | Fresh install shows F&R sub-controls expanded and an `allThree` conflict banner. Noisy but intentional given default-on. |
| `ui/screens/settings/sections/FocusReleaseSubSettings.kt:166` — `calmModeActive = ndPrefs.calmModeEnabled` | Calm mode mutes F&R celebration intensity on fresh install. Intended. |
| `ui/screens/batch/BatchPreviewViewModel.kt:70` — `.map { it.calmModeEnabled }` | Batch preview renders the calm variant on fresh install. Intended. |
| `data/preferences/NdPreferences.kt:66` — `effectiveCelebrationIntensity` returns LOW when calm on | Celebration intensity is muted on fresh install. Intended. |
| `data/preferences/NdPreferences.kt:77` — `shouldFireShipItCelebration` | Fires on fresh install (F&R + ship-it both on). Intended. |
| `data/preferences/NdPreferences.kt:83` — `isAnyNdModeActive` | Always true on fresh install. Intended. |
| `domain/usecase/GoodEnoughTimerManager.kt:68` — `if (!ndPrefs.focusReleaseModeEnabled) return null` | Active on fresh install. Intended. |
| `domain/usecase/AntiReworkGuard.kt:15,50` — gates the guard | Active on fresh install. Intended. |
| `domain/usecase/ShipItCelebrationManager.kt:77,105` — gates the celebration | Active on fresh install. Intended. |

All sibling paths shift their behavior in the direction the operator's "ND
baseline" framing wants. **No sibling assumes false-by-default in a way that
contradicts the flip.** Per operator constraint #1 (scope: BrainModePage +
its ViewModel-layer defaults only), no sibling fix lands in this PR — the
flip propagates to siblings via the DataStore flow naturally.

**STOP-D-3 does not fire.**

### V6 — Returning-user state preservation post-fix — **GREEN**

`collectAsLocalState` (definition at `OnboardingScreen.kt:1408–1420`)
initializes the inner `mutableStateOf` with `initial`, then overwrites it on
each `flow.collect`. Once the DataStore emission lands (sub-frame on warm
caches, low single-digit ms on cold opens), the StateFlow value wins. For
users with persisted DataStore values, the persisted value is what the
toggle ultimately renders.

The `initial = false` placeholder pre-emission flashes briefly. Flipping it
to `initial = true` for the default-on world removes a visible-OFF flash on
fresh installs, and produces an equally-brief visible-ON flash for users
whose persisted value is `false`. Both are sub-frame and inconsequential,
but matching `initial` to the new dominant default is cleaner. Folded into
the implementation alongside the `stateIn(... false)` flip.

### V7 — Test coverage — **YELLOW**

Existing test in `app/src/test/java/com/averycorp/prismtask/data/preferences/NdPreferencesDataStoreTest.kt:49–65`:

```
@Test
fun `defaults have all three modes off and all sub-settings off`() = runTest {
    val prefs = ndPrefs.ndPreferencesFlow.first()
    assertFalse(prefs.adhdModeEnabled)
    assertFalse(prefs.calmModeEnabled)
    assertFalse(prefs.focusReleaseModeEnabled)
    assertFalse(prefs.reduceAnimations)
    // ... etc (all asserts ND sub-settings are false)
}
```

This test pins the **old** product behavior — it will fail after the flip.
The fix is to rename + invert the assertions to match the new product
intent. The companion `enabling ADHD mode flips all ADHD sub-settings on`
test (lines 71–80) and `disabling ADHD mode flips all ADHD sub-settings off`
test (lines 94+) remain valid as written — they test the cascade behavior,
not the defaults.

Add a new test that locks in returning-user preservation: write `false` to
all three keys, read the flow, assert all three are `false` (not flipped to
true by the default fallback).

No tests exist for the BrainModePage Composable's `collectAsLocalState`
binding. Adding a Compose-UI test here is out-of-budget for this PR
(Robolectric setup for the onboarding screen is heavy); the ViewModel-level
test already exercises the read path that BrainModePage hooks into.

### V8 — Web parity — **YELLOW (file as web follow-up)**

Web has the identical data shape and same false-default:

- `web/src/api/firestore/ndPreferences.ts:37–39` — `NdPreferences` interface with the three flags
- `web/src/api/firestore/ndPreferences.ts:69–71` — `DEFAULT_ND_PREFERENCES` with all three `false`
- `web/src/features/onboarding/OnboardingScreen.tsx:33` — comment: "7. BrainMode (light intro — ND features are backend-gated on web)"
- `web/src/stores/ndPreferencesStore.ts:81–105` — three setter cascades mirroring Android's `setX(true)` semantics

Per operator constraint #5 ("No fan-out"), web parity is **filed as a
follow-up, not bundled** into this PR. A fresh user installing both clients
would see Android default-on and web default-off until the web follow-up
lands. Firestore sync converges them once either client writes; the window
is "before the user opens either client's settings."

**Re-trigger criterion**: web BrainModePage fix prompt with the same shape
(flip `DEFAULT_ND_PREFERENCES` defaults + mirror cascade defaults in
`ndPreferencesStore`).

## STOP conditions — none fired

| STOP | Status |
|---|---|
| STOP-A (Phase 0 premise wrong) | Cleared. P2 line-number drift within tolerance. Idiom-drift premise was wrong **in a good way** (already fixed) — Phase 2 scope shrinks accordingly, not stops. |
| STOP-B (audit + diff > 500 LOC) | Cleared. Audit doc ~250 LOC, implementation ~30 LOC code + ~30 LOC test. |
| STOP-D-1 (no read-side StateFlow) | Cleared. Read-side exists at OnboardingViewModel.kt:125–136. |
| STOP-D-2 (absent ≡ explicit-false) | Cleared. v1.x explicit-false-via-UI writes the key; absent-key is reserved for "never tapped." |
| STOP-D-3 (sibling sweep dirty) | Cleared. Siblings shift consistent with operator intent. |
| STOP-F (>3 files outside expected scope) | Cleared. Expected scope = OnboardingScreen + ViewModel + DataStore + tests. Actual scope = OnboardingScreen, OnboardingViewModel, NdPreferencesDataStore, NdPreferences data class, NdPreferencesDataStoreTest — `NdPreferences.kt` is the same-package data class for `NdPreferencesDataStore`, counts as "DataStore" category. |

## Ranked improvement table

| # | Improvement | Wall-clock savings | Cost | Notes |
|---|---|---|---|---|
| 1 | Flip the three top-level + ADHD/Calm cascade sub-setting defaults to `?: true` in `NdPreferencesDataStore.ndPreferencesFlow`; mirror in `NdPreferences.kt` data class field defaults. | High (delivers operator product intent) | ~25 LOC | Load-bearing change. |
| 2 | Flip `stateIn(... false)` to `... true` for the three flows in `OnboardingViewModel.kt:125–136`. | Low (1-frame flash) | ~3 LOC | Cosmetic, removes pre-emission OFF flash on fresh installs. |
| 3 | Flip `collectAsLocalState(..., initial = false)` to `... = true` for the three BrainModePage reads at `OnboardingScreen.kt:791–793`. | Low (1-frame flash) | ~3 LOC | Same rationale as #2. |
| 4 | Update `NdPreferencesDataStoreTest.kt:49–65` defaults test to assert the new defaults; add an explicit "explicit-false is preserved" test. | High (regression fence) | ~30 LOC | Required for V7. |
| 5 | Close out PR #1123 OB-2 F.8 item by updating `OB2_SKIP_COMMIT_ON_CHANGE_AUDIT.md:189` to mark the idiom drift closed. | Low (admin) | ~2 LOC | Bookkeeping. |
| 6 | Web BrainModePage / `DEFAULT_ND_PREFERENCES` flip — **follow-up PR**. | (deferred) | — | Per operator no-fan-out. V8 re-trigger. |

## Anti-patterns to NOT do

- **Do NOT add a Room/DataStore migration** to flip OFF→ON for users whose
  keys are explicit-false. The `?:` fallback gives the new default only for
  absent keys — exactly what we want.
- **Do NOT add a "has-user-set-this" sentinel flag** to distinguish absent
  from explicit-false. V4 establishes that v1.x explicit-OFF-via-toggle is
  already distinguishable (key present, value `false`). The sentinel would
  be net-new complexity for an already-handled case.
- **Do NOT change copy/labels** ("ADHD mode", "Calm mode", "Focus Release
  mode") — operator-locked constraint, copy/tone audit is a separate
  prompt.
- **Do NOT bundle the Web parity fix** — operator constraint #5 (no
  fan-out). File as follow-up.
- **Do NOT touch sibling code paths** (BrainModeSection, BatchPreviewViewModel,
  use cases) — they observe the flow and shift correctly without code
  changes.
