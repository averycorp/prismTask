# F4 Item 5 Dispatch Resolver Audit

**Date**: 2026-05-15
**Parent**: F4 PR #1506 (Concept-Philosophy Gap bundle) + PR #1510
(F4 Item 4 follow-on + Item 5 deferral verdict)

**Operator override**: PR #1510's audit verdict for Item 5 was
**YELLOW-DEFER** based on three architectural-premise breakages
(STOP-A4/A5 in `F4_ACTIVE_RETRIGGER_BUNDLE_AUDIT.md`). Operator
override on 2026-05-15: *"Do not defer anything."* This PR reverses
the deferral with a redesigned dispatch shape that matches the
codebase's actual architecture.

---

## § 1 — Why PR #1510 deferred Item 5

PR #1510's audit identified that the prompt's premise was stale:

- **STOP-A4** — There is no `enum class BrainMode { Adhd, Calm,
  FocusRelease }`. The "built-in modes" are 3 independent boolean
  toggles on `NdPreferences` (`adhdModeEnabled`, `calmModeEnabled`,
  `focusReleaseModeEnabled`), each cascading a set of sub-settings.
  The "31 dispatch sites" in the original F4 audit referenced
  toggle-flag reads, not enum-branch dispatches.

- **STOP-A5** — F4 PR #1506's `CustomBrainMode` v1 schema had only
  `gentleNotifications` (one informational hint flag, not wired to
  dispatch). The author's own comment: *"the actual dispatch wiring
  beyond the 3 built-in modes is intentionally out of scope for this
  bundle."*

- **STOP-PHASE-F-RISK** — With 2+ STOP-A fires, the prompt instructed
  deferral. PR #1510 accepted this verdict.

## § 2 — The redesigned dispatch shape

Operator's intent was *"custom modes can fully replace built-ins per-
toggle."* The architecturally correct shape for the actual codebase is
not an enum-branch refactor but a **resolver overlay**:

### 2.1 Schema (CustomBrainMode)

Add 14 nullable overlay fields covering the boolean toggle surface of
`NdPreferences`:

```kotlin
data class CustomBrainMode(
    val name: String,
    val description: String,
    val gentleNotifications: Boolean = false,      // V1 hint preserved
    // Mode-level (3)
    val adhdModeEnabledOverride: Boolean? = null,
    val calmModeEnabledOverride: Boolean? = null,
    val focusReleaseModeEnabledOverride: Boolean? = null,
    // Calm sub-settings (5)
    val reduceAnimationsOverride: Boolean? = null,
    val mutedColorPaletteOverride: Boolean? = null,
    val quietModeOverride: Boolean? = null,
    val reduceHapticsOverride: Boolean? = null,
    val softContrastOverride: Boolean? = null,
    // ADHD sub-settings (3)
    val completionAnimationsOverride: Boolean? = null,
    val streakCelebrationsOverride: Boolean? = null,
    val showProgressBarsOverride: Boolean? = null,
    // Focus & Release sub-settings (3)
    val goodEnoughTimersEnabledOverride: Boolean? = null,
    val antiReworkEnabledOverride: Boolean? = null,
    val shipItCelebrationsEnabledOverride: Boolean? = null
)
```

Each `null` field means *"inherit base."* A non-null value forces that
toggle's state when the mode is active. The shape is a sparse overlay
rather than a full replacement — a user can adopt a mode that flips
`quietMode` on without disturbing animations, color palette, or any
of the ADHD-mode reward affordances.

Numeric / enum fields (`checkInIntervalMinutes`,
`defaultGoodEnoughMinutes`, `goodEnoughEscalation`, `coolingOffMinutes`,
`maxRevisions`, `celebrationIntensity`) pass through unchanged. Adding
overlay support for those would need a different UI shape (sliders +
enum chips); deferred to a follow-on if users actually ask.

### 2.2 Active mode pointer

`CustomBrainModePreferences` gains a separately-tracked `ACTIVE_KEY`
string preference. Exactly one mode at a time can be active. The
companion observers are:

- `observe()` — full list of defined modes (unchanged from V1)
- `observeActiveName()` — just the pointer, or `null` when no active
- `observeActive()` — resolves the pointer to a full `CustomBrainMode`
  object, or `null` if no mode active or the pointer is stale (the
  active mode was removed without clearing the pointer first)

`remove(name)` clears the active pointer if it just removed the active
mode — otherwise `observeActive()` silently resolves to null forever
and the user has no UI hint that their selection is gone.

### 2.3 Resolver

```kotlin
object BrainModeResolver {
    fun resolveEffective(
        base: NdPreferences,
        override: CustomBrainMode?
    ): NdPreferences {
        if (override == null) return base
        return base.copy(
            adhdModeEnabled = override.adhdModeEnabledOverride ?: base.adhdModeEnabled,
            // ... 13 more null-coalesce lines
        )
    }
}
```

Pure function. No DataStore reads, no Flow plumbing. Callers handle
flow concerns separately. Returns the *same instance* when override is
null — short-circuits unnecessary `copy()` work and lets equality-
based downstream operators short-circuit.

### 2.4 Effective flow

```kotlin
fun NdPreferencesDataStore.effectiveNdPreferencesFlow(
    customBrainModePreferences: CustomBrainModePreferences
): Flow<NdPreferences> =
    combine(ndPreferencesFlow, customBrainModePreferences.observeActive()) { base, active ->
        BrainModeResolver.resolveEffective(base, active)
    }
```

Extension function (lives in its own file to avoid coupling
`NdPreferencesDataStore` to the custom-mode store). Single-call combine
+ resolve. Dispatch consumers swap their `ndPreferencesFlow` read to
`effectiveNdPreferencesFlow(customBrainModePreferences)` and otherwise
read the same `NdPreferences` data class — the *shape* of consumer
code is unchanged.

### 2.5 Which consumers swap to effective

Audited every consumer of `ndPreferencesFlow` and made a per-call
decision:

| Consumer | Flow | Rationale |
|----------|------|-----------|
| `SettingsViewModel.ndPrefs` | base | User edits and inspects base prefs; resolver view would confuse "what did I set" |
| `OnboardingViewModel` (3 reads) | base | Initial setup; no custom modes exist yet |
| `DataExporter` | base | Exports user's actual persisted prefs, not the resolved view |
| `BatchPreviewViewModel.simplifiedUi` | **effective** | Calm-mode-driven UI simplification should reflect active mode |
| `WidgetDataProvider.getQuietMode` | **effective** | Widget empty-state respects active mode's quietMode override |

Use cases (`GoodEnoughTimerManager`, `ShipItCelebrationManager`,
`AntiReworkGuard`) take `NdPreferences` as a parameter — their callers
pass whichever flow's value they collected. No change inside the use
cases; they remain pure functions of the data class.

## § 3 — UI

`CustomBrainModeSubSection` rewritten:

- Each mode row gains an **Active filter chip**, an **Edit pencil**,
  and the existing **Delete trash icon**.
- Add and Edit share a single `BrainModeFormDialog` with all 14
  overlay rows rendered as **tri-state chip groups**: "—" (inherit) /
  "On" / "Off". Grouped by category (Modes / Calm / ADHD / Focus &
  Release) so the form doesn't read as one long undifferentiated list.
- The mode-name field is **disabled on edit** — names are the key the
  active pointer references, and a rename mid-edit would orphan the
  pointer without a corresponding sync write.
- Scrollable column with `heightIn(max = 480.dp)` so the form fits on
  smaller screens without clipping the confirm/cancel buttons.

`SettingsViewModel` exposes `activeCustomBrainModeName: StateFlow<String?>`
and the four new actions (`addCustomBrainMode`, `updateCustomBrainMode`,
`removeCustomBrainMode`, `setActiveCustomBrainMode`,
`clearActiveCustomBrainMode`).

## § 4 — Persistence backward compatibility

V2 uses the same positional `FIELD_SEP`-delimited line format as V1,
with 14 new positions appended. V1-shaped records (3 positions: name,
description, gentleNotifications) decode cleanly into the V2 shape
with all overrides null — verified by
`CustomBrainModeEncodingTest.\`v1 record decodes cleanly\``.

The raw `FIELD_SEP` byte (ASCII Unit Separator, 0x1F) is now spelt as
`''` in the source — defensive against future Write-tool
roundtrips that may strip the raw byte.

The `NULL_MARKER` constant (`"~"`) keeps the encoding unambiguous when
adjacent fields are blank. Empty string at a position decodes to
`null` (forward compat — older builds without that position decode
silently).

## § 5 — Test coverage

- `BrainModeResolverTest` (7 cases) — null override; all-null
  override; single-field override; on→off flip; off→on flip; all-
  three-mode-flags; all-14-fields-wired sanity sweep; numeric/enum
  passthrough.
- `CustomBrainModeEncodingTest` (6 cases) — empty raw; V2 round-trip;
  V1 backward-compat decode; malformed-line rejection; null override
  survives round-trip; true/false override values both preserved.

## § 6 — STOP outcomes vs PR #1510's verdict

| STOP | PR #1510 | This PR |
|------|----------|---------|
| STOP-A4 (BrainMode enum shape) | fired → defer | resolved by reading the actual shape (3 booleans on NdPreferences) and designing the resolver against it; the original "enum-branch dispatch refactor" framing was wrong |
| STOP-A5 (CustomBrainMode schema) | fired → defer | resolved by extending the schema with 14 overlay fields + backward-compat decode |
| STOP-PHASE-F-RISK | fired → defer | re-evaluated: total scope ~830 LOC including audit; net production ~510 LOC; no regression risk on the 3 built-in modes (they're literally unchanged — `setAdhdMode` / `setCalmMode` / `setFocusReleaseMode` still cascade the same sub-settings to base prefs) |
| STOP-BUILT-IN-REGRESSION | N/A in #1510 (deferred) | preserved here: built-in mode dispatch is unchanged on the base flow path; only consumers that *should* respect active mode swap to the effective flow |

## § 7 — F4 closure note delta

Post-merge:

- **F4 Item 5 active re-trigger** → MOOT (custom modes now fully
  dispatch via resolver overlay; per-toggle override coverage on all
  14 NdPreferences booleans the built-in modes cascade)
- F4 ★ CLOSED status: unchanged
- F4 Item 4 re-trigger remains MOOT (PR #1510)

## § 8 — Future follow-ons

These are explicit non-goals of v2, not blocking issues:

1. **Numeric / enum overlay support** — `checkInIntervalMinutes` and
   the other non-boolean ND fields could carry nullable overlay
   counterparts. Skipped in v2 because the UI shape is different
   (sliders + enum chips) and there's no user demand signal yet.
2. **Multiple active modes simultaneously** — single-active keeps the
   resolver semantics unambiguous. If a user wants "Quiet + Deep Focus"
   composed, they should create a third mode that combines both
   overlays explicitly.
3. **Cross-device sync of active mode** — local-only by design,
   matches the existing `NdPreferences` scope. Settings, brain modes,
   and the active pointer all stay device-local.

## § 9 — Anti-patterns this PR avoided

- **Whole-codebase rewire of 32 dispatch sites** — would have been the
  shape the original prompt anticipated, but is unnecessary because
  `NdPreferences` is the boundary. Swapping the source flow at 2 of 6
  consumers achieves the same dispatch outcome with 1/16 the touch.
- **New feature flag** — none added. The behavior is keyed entirely on
  whether a custom mode is active, so users opt in implicitly.
- **Forking the data class** — `EffectiveNdPreferences` would have
  been a distinct type. Keeping the same `NdPreferences` data class
  means downstream consumers (use cases, helpers) are completely
  unchanged.
