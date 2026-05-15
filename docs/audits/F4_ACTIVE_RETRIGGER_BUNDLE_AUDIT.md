# F4 Active Re-Trigger Bundle Audit (Items 4 & 5 follow-on)

**Date**: 2026-05-15
**Parent**: F4 PR #1506 (Concept-Philosophy Gap bundle)
**Scope candidates**:
- Item 4 follow-on — AI Time-Block reasoning ("Why:" prefix on 4th surface)
- Item 5 follow-on — Custom Brain Mode generic dispatch wiring

**Bundle verdict**: **SHIP ITEM 4 ONLY. DEFER ITEM 5.**

Three Phase-0 STOP-A conditions fired against Item 5's architectural
premise. Item 4 reduces to a GREEN-SURGICAL UI-only ~6 LOC fix.

---

## § 1 — Item 4: Time-Block reasoning ("Why:" prefix)

### 1.1 Phase 0.1 inventory — backend route returns `reason`

`backend/app/services/ai_productivity.py:746` (response schema embedded in
the Haiku prompt) already instructs the model to emit `reason` on every
schedule block:

```json
{"schedule": [{"start": "09:00", "end": "09:30", "type": "task",
  "task_id": 1, "title": "Write report",
  "reason": "Deep work while fresh",
  "date": "<iso>"}], ...}
```

`generate_time_blocks()` (line 648) returns the parsed JSON verbatim;
clients receive `reason` as part of every block.

### 1.2 Android-side model

`app/src/main/java/com/averycorp/prismtask/data/remote/api/ApiModels.kt:596`
already declares the field:

```kotlin
data class ScheduleBlockResponse(
    val start: String,
    val end: String,
    val type: String,
    @SerializedName("task_id") val taskId: String?,
    val title: String,
    val reason: String,                          // ← already wired
    val date: String? = null
)
```

`TimelineViewModel.kt:62` maps it to `AiScheduleBlock.reason: String` on
line 492 (`reason = block.reason`). UI plumbing complete.

### 1.3 UI surface delta vs F4 PR #1506 pattern

`TimelineScreen.kt:1018-1032` (PreviewBlockCard) already renders
`block.reason` with `isNotBlank()` guard, but **without the "Why:"
prefix label** that F4 PR #1506 commit `0a0e115f` introduced on the
other 3 AI surfaces:

```kotlin
// CURRENT (pre-fix)
if (block.reason.isNotBlank()) {
    Text(
        text = block.reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
```

Required delta (mirroring `DailyBriefingScreen.kt` and
`SmartPomodoroScreen.kt` changes in commit `0a0e115f`):

1. Wrap text with `"Why: "` prefix.
2. Bump `maxLines` from 1 → 2 to avoid mid-clause truncation.

### 1.4 STOP-A1 evaluation

**FIRED**. Time-Block backend route already returns `reason`. Per the
prompt's STOP-A1 options, we take **option 3 — ship only the UI 'Why:'
prefix surface-only fix**.

### 1.5 STOP-A2 evaluation

CLEARED. Time-Block UI surface (`TimelineScreen.PreviewBlockCard`)
exists and renders blocks with reason. Wire is intact.

### 1.6 LOC estimate

~6 LOC: one `"Why: ${block.reason}"` interpolation + `maxLines = 2`.
No backend touch. No Pydantic schema delta. No wire-protocol risk
(field already returned).

### 1.7 Item 4 verdict

**GREEN-SURGICAL — UI label only.** Mirror commit `0a0e115f` shape
exactly: keep `isNotBlank()` guard, prefix with `"Why: "`, bump
`maxLines` to 2.

---

## § 2 — Item 5: Custom Brain Mode generic dispatch wiring

### 2.1 Phase 0.3 — dispatch site count

The prompt's premise was "31 dispatch sites for
`BrainMode.Adhd/.Calm/.FocusRelease`". Actual count of
`BrainMode.Adhd|Calm|FocusRelease` references in main: **0**.

Combined count of `adhdModeEnabled|calmModeEnabled|focusReleaseModeEnabled`
references in main: **32** (close to F4 audit's "31 dispatch sites"
phrasing).

### 2.2 STOP-A3 evaluation

CLEARED. The 32 toggle-flag refs ≈ 31 reported in F4 audit (within
tolerance). The mismatch is in shape (toggle reads vs enum branches),
not count.

### 2.3 Phase 0.4 — BrainMode enum/sealed-class

**No such type exists.** Files:

- `BrainModeScreen.kt` — Settings UI screen
- `BrainModeSection.kt` — Settings section composable
- `CustomBrainModePreferences.kt` — DataStore for user-defined modes
  (data class `CustomBrainMode(name, description, gentleNotifications)`)

There is no `enum class BrainMode { Adhd, Calm, FocusRelease }`.

The "built-in modes" are 3 **independent boolean toggles** on
`NdPreferences`:

```kotlin
data class NdPreferences(
    val adhdModeEnabled: Boolean = true,
    val calmModeEnabled: Boolean = true,
    val focusReleaseModeEnabled: Boolean = true,
    val reduceAnimations: Boolean = true,
    val mutedColorPalette: Boolean = true,
    val quietMode: Boolean = true,
    val reduceHaptics: Boolean = true,
    val softContrast: Boolean = true,
    val checkInIntervalMinutes: Int = 25,
    val completionAnimations: Boolean = true,
    val streakCelebrations: Boolean = true,
    val showProgressBars: Boolean = true,
    val goodEnoughTimersEnabled: Boolean = true,
    // ... ~12 more sub-settings
)
```

The 3 mode toggles each cascade a set of sub-settings when first
enabled, but each sub-setting is independently mutable thereafter.

### 2.4 STOP-A4 evaluation

**FIRED**. `BrainMode` is NOT a sealed-class/enum with 3 values; it's 3
independent boolean toggles on a data class. The generic-replacement
strategy proposed in the prompt — "each dispatch site queries resolver
instead of `BrainMode.Adhd.gentleNotifications` directly" — does not
match the codebase shape. The actual dispatch reads are
`prefs.adhdModeEnabled`, `prefs.calmModeEnabled`,
`prefs.focusReleaseModeEnabled` directly on the data class fields.

### 2.5 Phase 0.5 / 0.7 — built-in mode toggle dispatch shape

Sample dispatch shape (representative; ~32 sites total):

```kotlin
val ndPrefs by ndPreferencesDataStore.ndPreferencesFlow.collectAsState(...)
if (ndPrefs.adhdModeEnabled) { /* ADHD branch */ }
```

Sub-setting reads add another ~50+ refs (`reduceAnimations` 10x,
`mutedColorPalette` 8x, `shipItCelebrationsEnabled` 8x, etc.).

### 2.6 CustomBrainMode v1 schema (F4 PR #1506)

```kotlin
data class CustomBrainMode(
    val name: String,
    val description: String,
    val gentleNotifications: Boolean   // single hint flag, not wired
)
```

Author docstring (line 18-31):

> v1 semantics: custom modes hold a display name + description and a
> single coarse hint flag (`gentleNotifications`). The hint flag is
> surfaced informationally on the BrainMode settings screen so the user
> can think of the mode as having a behavioral character; **the actual
> dispatch wiring beyond the 3 built-in modes is intentionally out of
> scope for this bundle** (preserves the operator's hard constraint that
> the existing BrainModePage core dispatch is not refactored).

### 2.7 STOP-A5 evaluation

**FIRED**. `CustomBrainMode` v1 has one informational flag
(`gentleNotifications`) that is not even wired to dispatch. To support
generic dispatch replacement so custom modes can fully override
built-ins per-toggle, the schema would need extension to cover the ~17
NdPreferences sub-toggles that built-in modes cascade. F4 PR #1506
author explicitly deferred this work as a follow-on.

Per the prompt's STOP-A5 options:
- (1) extend schema (~50-100 LOC) — pushes total bundle past comfort
- (2) ship Item 5 with partial toggle coverage — meaningless without
  schema parity
- (3) defer Item 5 — **chosen**

### 2.8 STOP-A6 evaluation

CLEARED. `NdPreferencesDataStoreTest.kt` exists, with 34 refs to mode
flags across test + androidTest. Coverage exists, though regression
tests specifically for the 3 built-in modes' downstream behavior would
need expansion — not done if Item 5 is deferred.

### 2.9 STOP-A7 evaluation

CLEARED. May 12-14 batch (#1282-#1333) commits did not modify the 32
dispatch sites (NdPreferences mode-flag reads remained stable).

### 2.10 STOP-PHASE-F-RISK evaluation

**FIRED** by virtue of "2+ STOP-A conditions fire" (STOP-A1, A4, A5).
The prompt instructs HALT and operator surface; we apply the user's
in-session autonomy override and proceed with the *anticipated partial*
outcome from the prompt's own distribution: **"Item 4 only; Item 5
deferred"**.

### 2.11 STOP-BUILT-IN-REGRESSION evaluation

N/A — Item 5 deferred; no refactor of dispatch sites. Built-in modes
remain unmodified.

### 2.12 Item 5 verdict

**YELLOW-DEFER**. Three architectural premise breakages (STOP-A4 enum
shape, STOP-A5 schema gap, F4 author's explicit deferral) make this
unsafe to ship in a single bundle with Item 4. Re-triggers when:

1. CustomBrainMode schema extended to mirror NdPreferences toggle
   surface (likely needs its own design pass + audit).
2. `EffectiveBrainModeResolver` introduced with merge semantics
   (NdPreferences + active CustomBrainMode overrides).
3. Regression tests for each of 3 built-in modes' downstream behavior
   added to lock parity before refactor.

These are F4 Item 5 phase-2 follow-on work, not phase-1 follow-on.

---

## § 3 — Bundle decision

### 3.1 PR shape

Single bundle PR. Effective scope reduces to Item 4 only.

Commit structure:

- Commit 1: Item 4 — "Why:" prefix on `TimelineScreen.PreviewBlockCard`
- Commit 2: Audit doc (this file)

### 3.2 LOC vs estimate

Prompt estimate: ~250-650 LOC. Actual ship: ~6 LOC production +
~200 LOC audit doc. Drift: -97% (Item 5 entirely deferred).

### 3.3 F4 closure impact

- F4 Item 4 active re-trigger: **MOOT** once Item 4 ships (all 4 AI
  reasoning surfaces will carry the "Why:" prefix).
- F4 Item 5 active re-trigger: **STILL ACTIVE**, now blocked on
  CustomBrainMode schema extension + resolver design.
- F4 ★ CLOSED status: unchanged (already closed via PR #1506).
- F4 closure note delta:
  - Item 4: "ACTIVE for Time-Block" → "MOOT (all 4 surfaces labeled)"
  - Item 5: re-triggers refined to "schema extension + dispatch
    resolver required before re-trigger fires"

---

## § 4 — Phase F-risk explicit posture

Phase F kickoff target May 16-17; production rollout May 25.

**Post-bundle posture**: POSITIVE.

- Item 4 ships clean UI label change. No backend, no schema, no wire
  protocol risk. No dispatch site touched. No regression surface.
- Item 5 deferred without scope creep — bundle stays small.
- Phase F window not threatened.

If Item 5 had proceeded with schema extension, resolver introduction,
and 32 dispatch site rewires, the bundle would have landed at
~450-660 LOC with non-trivial regression risk on 3 live-shipped
behavioral modes. Better deferred until operator can dedicate a focused
audit pass on schema design.

---

## § 5 — Anti-patterns observed

None new. The F4 PR #1506 author's explicit deferral comment on
`CustomBrainModePreferences.kt:28-31` is doing its job — the load-bearing
"intentionally out of scope" annotation surfaced through Phase 0 and
prevented an attempt to merge the schema extension into an unrelated
bundle.

---

## § 6 — Memory candidates

- **Architectural-premise validation by code-existence check**: Phase 0
  greps for the named type (`BrainMode` enum) before trusting the
  prompt's count claim ("31 sites"). When the type doesn't exist, the
  whole strategy is wrong. (1 data point — flag, don't memorize yet.)
- **Author's explicit deferral comments are load-bearing**: PR #1506's
  "intentionally out of scope" annotation matched the audit conclusion
  exactly. Worth noticing the pattern. (1 data point.)
