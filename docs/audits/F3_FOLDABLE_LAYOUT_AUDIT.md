# F3 — Foldable Layout Audit (F-FOLDABLE-001)

**Status:** Phase 1 audit (single-pass)
**Date:** 2026-05-10
**Bundle:** F3 ★ closure — final open item
**Branch:** `claude/foldable-layout-support-jO2Yo`
**LOC estimate:** ~150–400 production + tests (see § Item 1 bundle estimate)
**Audit cap:** 600 lines (single-item; doesn't need 1000+)

## Framing

This audit covers the lone remaining open item under F3 — **F-FOLDABLE-001**, the
foldable-aware layout follow-on filed from PR #1219's D11 Item 1 audit. The
deferral pattern from #1219 (`anti-pattern #14`) explicitly forbade extending
the `windowSizeClass` infrastructure with `FoldingFeature` work in that PR; the
deliberate split landed `F-FOLDABLE-001` with re-trigger criterion "any
user-reported issue on a Surface Duo / Galaxy Z Fold / similar device".

**Operator override (explicit):** Ship pre-emptively. Override the reactive
trigger. Phase F (May 15) opens in 5 days; foldable adoption is an empty user
segment, but `WindowInfoTracker` infrastructure is additive and dormant for
non-foldable devices, so the regression surface is bounded.

### STOP-FOLDABLE-USERS visibility check

Per the prompt's load-bearing visibility STOP:

- Crashlytics signals: **none observed**. No `foldable` / `Surface Duo` /
  `Z Fold` device-class entries in any doc, runbook, or audit.
- User feedback: **none on record**. Repo grep for "foldable" surfaces only
  the D11 audit reference (`D11_FINISH_BUNDLE_AUDIT.md:141,370,388`).
- Operator testing matrix: **no foldable device documented**.

**Verdict:** Shipping for empty user segment per operator pre-lock. Decision
rationale: launch lever for foldable user acquisition + no-surprise UX for
any post-launch foldable adopters. If 30-day post-merge telemetry surfaces
zero foldable users, that is expected — the shipped infrastructure is
dormant-but-correct.

## Phase 0 verification outcomes

```
PR #1219 infra:                                                PRESENT ✓
  - LocalWindowSizeClass:        app/src/main/.../theme/LocalWindowSizeClass.kt:30
  - Modifier.expandedWidthCap:   app/src/main/.../theme/LocalWindowSizeClass.kt:42
  - ExpandedWidthCap composable: app/src/main/.../theme/LocalWindowSizeClass.kt:57
  - MainActivity plumb:          app/src/main/.../MainActivity.kt:633-637

WindowInfoTracker / FoldingFeature / WindowLayoutInfo:        ABSENT ✓
  - Only a doc-comment reference at LocalWindowSizeClass.kt:27
    ("defers `WindowInfoTracker` / `FoldingFeature` to F-FOLDABLE-001.")

androidx.window dependency:                                    ABSENT
  - Only `androidx.compose.material3:material3-window-size-class`
    (Compose BOM 2024.12.01) — that does NOT transitively provide
    `androidx.window.layout.FoldingFeature`. Need direct dependency.

minSdk:                                                        26 (Android 8.0)
  - androidx.window:1.2.0+ requires API 21. 26 > 21. No bump needed.

Tier 1 consumers of LocalWindowSizeClass / expandedWidthCap:
  - TodayScreen.kt:99,327
  - SettingsScreen.kt:52,166
  - ChatScreen.kt:80,299
```

**STOP-A:** all preconditions hold. Proceeding to Phase 1 verdicts.
**STOP-1B:** ruled out — min SDK already 26.

## § Item 1 — F-FOLDABLE-001 implementation

### 1. Dependency inventory

| Dependency                                            | Current   | Target               | Action       |
| ----------------------------------------------------- | --------- | -------------------- | ------------ |
| `androidx.compose.material3:material3-window-size-class` | BOM-managed | unchanged           | none         |
| `androidx.window:window`                              | absent    | `1.3.0`              | **add**      |

`androidx.window:1.3.0` (2024-09 stable) is the recommended floor for
`FoldingFeature` + `WindowInfoTracker.windowLayoutInfo()` returning a Flow
on the main thread. Older 1.2.x is fine API-wise but 1.3.0 fixes
crashlog-significant lifecycle issues during foldable rotation.

### 2. Architecture design

**State source.** `WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)`
returns `Flow<WindowLayoutInfo>`. Each emission carries
`displayFeatures: List<DisplayFeature>`; foldable hinges/folds surface as
`FoldingFeature` entries (a `DisplayFeature` subtype). For non-foldable
devices the list is empty.

**Collection site.** `MainActivity` — mirroring PR #1219's
`calculateWindowSizeClass` plumb at `MainActivity.kt:633-637`. The Activity
lifecycle scope (`lifecycleScope`) is the natural owner; collecting with
`collectAsStateWithLifecycle` inside `setContent` keeps the Flow tied to
the visible-lifecycle, not the full Activity lifecycle, which avoids
background-spurious recompositions.

**Propagation.** New `LocalFoldingFeature` CompositionLocal in
`ui/theme/`, mirroring `LocalWindowSizeClass` shape:

```kotlin
val LocalFoldingFeature = staticCompositionLocalOf<FoldingFeature?> { null }
```

`null` is the natural default for non-foldable devices, preview tooling, and
unit tests. Consumers do not need null-safety boilerplate at every read site
— they read with `?.` chains.

**No abstraction layer.** Per prompt anti-pattern #3, consume
`androidx.window.layout.FoldingFeature` directly. No `FoldableConfig` data
class, no domain wrapper, no `isHalfOpened` boolean extension on a custom
type. The Window library API is stable and small; an indirection would just
re-bind the dependency without removing it.

**Orthogonality.** `LocalFoldingFeature` and `LocalWindowSizeClass` are
deliberately orthogonal — a phone unfolded into a tablet-class width has a
non-null `FoldingFeature` AND `WindowWidthSizeClass.Expanded`; a folded
Z Fold reads `Compact` width but may still have a `FoldingFeature` (the
hinge is physically present). Per prompt anti-pattern #2, consumers may
read both, but neither is derived from the other.

### 3. Per-screen adaptation scope

**Tier 1 — already `LocalWindowSizeClass` consumers:**
- TodayScreen
- SettingsScreen
- ChatScreen

**Tier 2 — candidates not currently `LocalWindowSizeClass` consumers:**
- TaskListScreen
- OnboardingScreen
- AddEditTaskScreen (bottom sheet — likely RED-SKIP)

**Tier 3 — specialty screens:**
- MedicationScreen, LeisureScreen, HabitsScreen, ProjectsScreen, etc.
- Default RED-SKIP unless surfaced UX argument.

### 4. Per-screen verdicts (6-bucket schema adapted from PR #1233)

#### Tier 1

**TodayScreen — GREEN-MINIMAL → minor GREEN-ADAPT**

Current shape (line 321-328): `LazyColumn` with
`.expandedWidthCap()` and 16dp horizontal padding. The LazyColumn fills the
window; on a half-opened foldable with vertical hinge, content can cross
the fold.

Adaptation:
1. Read `LocalFoldingFeature.current`. Default GREEN-MINIMAL behavior — no
   layout change, but the CompositionLocal is read at the screen root so
   the screen is foldable-aware once the infrastructure ships.
2. Light GREEN-ADAPT: if `FoldingFeature.state == HALF_OPENED` and
   `orientation == VERTICAL`, add horizontal padding equal to
   `bounds.left - 16.dp` on the trailing side so content doesn't disappear
   into the hinge. This is a small, surgical change (~10-20 LOC).

LOC: ~25-40.

**SettingsScreen — GREEN-MINIMAL**

Current shape (line 161-167): `LazyColumn` with `.expandedWidthCap()` and
16dp horizontal padding. Settings is a single-pane list with no obvious
list-detail split target on Android (the existing nav pattern uses
`navController.navigate("settings/...")` for sub-screens, not a sidebar).

Adaptation: GREEN-MINIMAL only — read `LocalFoldingFeature.current` for
fold-aware horizontal padding adjustment matching TodayScreen. No
list/detail two-pane split (that would be a structural rework, well past
this bundle's scope; if surfaced as a UX need later, file F-FOLDABLE-002).

LOC: ~15-25.

**ChatScreen — GREEN-ADAPT**

Current shape (line 286-302): `Column` containing the `LazyColumn` of
messages with `.expandedWidthCap(maxWidth = 600.dp)`. Chat is the most
natural candidate for hinge-aware adaptation: on a half-opened foldable
(book posture), the message list can use one pane and the input field +
quick-reply can use the other.

Two adaptation options considered:
- (a) Full two-pane: split LazyColumn vs input across fold. Estimated
  60-100 LOC and requires re-architecting the existing single-column
  layout. **YELLOW-RESHAPE risk** — defer per STOP-1C.
- (b) Hinge-avoidance padding: if HALF_OPENED + vertical fold, add
  horizontal padding on the side approaching the hinge. ~20-30 LOC.

**Verdict:** Option (b) GREEN-ADAPT for this bundle. Option (a) filed as
follow-on `F-FOLDABLE-002 — chat two-pane book posture` with re-trigger
criterion "any post-launch foldable user requesting two-pane chat".

LOC: ~25-35.

#### Tier 2

**TaskListScreen — GREEN-MINIMAL**

Not currently a `LocalWindowSizeClass` consumer. Bringing it into
foldable-awareness alongside Tier 1 is cheap (~15 LOC) and consistent with
the broader PR #1219 layout posture. Adaptation: read
`LocalFoldingFeature.current`, apply hinge-avoidance padding when
`HALF_OPENED + VERTICAL`. Also brings TaskListScreen incidentally into
`LocalWindowSizeClass` (apply `expandedWidthCap()` on the LazyColumn) so
the foldable + tablet stories stay aligned. The latter is in-scope under
the spirit of the F3 closure — not an unrelated tablet feature, but the
natural pairing of "if we're touching this screen for foldable, also wire
the tablet width cap that's already shipped to its peers".

LOC: ~20-30.

**OnboardingScreen — RED-SKIP for layout, GREEN-MINIMAL for read**

`OnboardingPageLayout` (line 1713) uses `Column` with `fillMaxSize() +
.padding(top = 80.dp)` — content is centered with 32dp horizontal padding.
The entire screen is a single-page-at-a-time pager; foldable hinge
crossings are uncomfortable for headline/body text but the existing
center-aligned layout naturally avoids the worst of it.

Per prompt hard constraint "Do NOT modify PR #1167 / #1218 onboarding
screens beyond foldable adaptation", we read `LocalFoldingFeature.current`
at the root of `OnboardingPageLayout` and conditionally widen horizontal
padding when a vertical hinge is present and the page is in book posture.
No structural change.

LOC: ~15-25.

**AddEditTaskScreen — RED-SKIP**

Bottom-sheet UI. Bottom sheets are anchored to the screen bottom and
size-constrained; a foldable hinge crossing a bottom sheet is a Material
Compose framework concern, not a per-screen concern. Re-trigger criterion:
"Material Compose ships hinge-aware bottom sheet support" or "user
complaint surfaces hinge-vs-bottom-sheet visual issue".

LOC: 0.

#### Tier 3

**Specialty screens (MedicationScreen, LeisureScreen, HabitsScreen,
ProjectsScreen, etc.) — RED-SKIP**

No specific UX argument for foldable adaptation surfaces. The
`LocalFoldingFeature` CompositionLocal is available to them at zero cost
if any of them needs it later. Re-trigger criterion: "user complaint or
Tier 1 adoption pattern proves a generalized hinge-avoidance approach
worth fanning out".

LOC: 0.

### 5. Test surface

**Compose previews** (primary verification — preferred per operator
preference; emulator infra is non-trivial per STOP-1E):

- `@Preview` showing a screen with `LocalFoldingFeature` overridden to
  `null` (regular phone display, baseline)
- `@Preview` showing a screen with `LocalFoldingFeature` overridden to a
  fake `FoldingFeature` matching HALF_OPENED + VERTICAL (book posture)
- `@Preview` showing a screen with `LocalFoldingFeature` overridden to a
  fake `FoldingFeature` matching FLAT + HORIZONTAL (tabletop posture)

Note: `FoldingFeature` is an interface; we provide a small `previewFold`
helper in `ui/theme/FoldingPreview.kt` that returns an in-memory fake.
This is not a production type, just a preview helper — matches the
PR #1219 pattern of co-locating preview helpers with the relevant theme
file.

**Unit tests** (mirrors PR #1219 testing scope):

- `LocalFoldingFeature` default-value test (default returns `null`).
- `LocalFoldingFeature` provided-value read test (provided value is read
  correctly inside `setContent`).
- `WindowInfoCollector` emission test — covered indirectly because
  `WindowInfoTracker` is mocked behind a thin lambda parameter in
  `MainActivity` for unit-test seam-ability (see Architecture design).

**Instrumented tests:** SKIP. AVD foldable emulator config (Pixel Fold AVD
+ system images) takes more than 30 minutes for first-time setup and the
operator preference is preview-only per STOP-1E. CI runs on emulators
without foldable AVDs anyway.

### 6. Critical anti-patterns (carryover from PR #1219 + this audit)

| #   | Rule                                                                                                       | Status        |
| --- | ---------------------------------------------------------------------------------------------------------- | ------------- |
| 1   | Do NOT modify PR #1219 `LocalWindowSizeClass` / `expandedWidthCap` infrastructure                          | upheld        |
| 2   | Do NOT couple `LocalFoldingFeature` to `LocalWindowSizeClass` (orthogonal axes)                            | upheld        |
| 3   | Do NOT add abstraction layer over `androidx.window.layout.FoldingFeature`                                  | upheld        |
| 4   | Do NOT add per-Composable `LaunchedEffect` collecting `windowLayoutInfo` — Activity-scoped flow → local    | upheld        |
| 5   | Do NOT add separate phone-vs-foldable navigation graph                                                     | upheld        |
| 6   | Do NOT modify backend or web                                                                               | upheld        |
| 7   | Do NOT add new feature flags                                                                               | upheld        |
| 8   | Do NOT introduce navigation library upgrades or major Compose version bumps                                | upheld        |
| 9   | Do NOT skip Compose previews for fold states                                                               | upheld        |
| 10  | Do NOT bundle additional F3 / F-series items                                                               | upheld        |
| 11  | Do NOT exceed 600 lines in audit doc                                                                       | upheld        |
| 12  | Do NOT use full-text str_replace on similar-named Composable functions without unique anchors              | will uphold   |
| 13  | Do NOT auto-memorize patterns observed; flag as memory candidates with data-point count                    | will uphold   |
| 14  | Do NOT skip Phase 4 summary in CC chat output                                                              | will uphold   |
| 15  | Do NOT amend commits without re-verifying via `git log --oneline -10`                                      | will uphold   |
| 16  | Do NOT close F-FOLDABLE-001 to 1.0 if any Tier 1 screen YELLOW-RESHAPE creates a follow-on                 | partial — ChatScreen option (a) filed as F-FOLDABLE-002 follow-on; F-FOLDABLE-001 closes to 0.95 |
| 17  | Do NOT ship without verifying non-foldable phone UX still renders correctly                                | will uphold   |

### 7. STOP-condition resolution

| STOP                | Outcome                                                                                                    |
| ------------------- | ---------------------------------------------------------------------------------------------------------- |
| STOP-A              | passes — PR #1219 infra present, foldable absent, deps addressable                                         |
| STOP-FOLDABLE-USERS | surfaces zero foldable evidence; operator pre-lock holds. Audit Phase 1 acknowledges shipping for empty segment |
| STOP-1A             | passes — `WindowInfoTracker.getOrCreate(MainActivity)` is well-formed; MainActivity is the Activity        |
| STOP-1B             | ruled out — minSdk 26 ≥ androidx.window:1.3.0's API 21 floor                                              |
| STOP-1C             | ChatScreen option (a) full two-pane is YELLOW-RESHAPE — defers to F-FOLDABLE-002. Other Tier 1 ships GREEN |
| STOP-1D             | total LOC estimate ~100-180 (Tier 1 ~65-100) + ~35-55 (Tier 2) + ~70-100 (tests + audit). Well under 600   |
| STOP-1E             | AVD foldable emulator config deferred per operator preference; Compose preview-only verification           |

## § Bundle-decision

Single-item bundle. PR shape: one PR on `claude/foldable-layout-support-jO2Yo`.

**No fan-out.** Operator pre-lock holds for proactive ship.

**Commit structure** (planned for Phase 2):

1. `feat(deps): add androidx.window:1.3.0 for FoldingFeature`
2. `feat(theme): LocalFoldingFeature CompositionLocal + previewFold helper`
3. `feat(main): WindowInfoTracker plumb into MainActivity setContent`
4. `feat(today): foldable hinge-avoidance padding via LocalFoldingFeature`
5. `feat(settings): foldable hinge-avoidance padding via LocalFoldingFeature`
6. `feat(chat): foldable hinge-avoidance padding (option b) via LocalFoldingFeature`
7. `feat(tasklist): foldable hinge-avoidance + tablet expandedWidthCap`
8. `feat(onboarding): foldable hinge-avoidance padding on OnboardingPageLayout`
9. `test(theme): LocalFoldingFeature default + provided-value unit tests`
10. `docs(audits): F3_FOLDABLE_LAYOUT_AUDIT.md`

Each commit builds cleanly + lint-clean per PR #1214 lesson. Final
`git log --oneline -10` re-verified before push (per prompt anti-pattern
#15).

## § F3 closure impact

**Best case (estimated probability ~70%):**
- F-FOLDABLE-001: 0 → 0.95 (ChatScreen option-a follow-on keeps it
  just shy of 1.0)
- F3 open-item count after merge: 0 (one new F-FOLDABLE-002 filed but
  that's a fresh item, not an unresolved F3 follow-on)
- F3 closed entirely? **YES** for F3 ★ closure semantics; F-FOLDABLE-001
  achieves "shipped + dormant-correct" status; F-FOLDABLE-002 is a new
  F-series item, not a re-open

**Realistic case (~25%):** Per-screen YELLOW-RESHAPE on one Tier 1 screen
forces deferral → F-FOLDABLE-001 at 0.8, F3 stays partial. Phase 4
summary states honestly.

**STOP-fire case (~5%):** Already ruled out at Phase 0 / Phase 1.

## § Cross-PR dependency outcomes

- **PR #1219** `LocalWindowSizeClass` infra: preserved, no edits. The new
  `LocalFoldingFeature` lives in `ui/theme/` next to it — same
  CompositionLocal pattern, no shared state.
- **PR #1167 / #1218** onboarding: only added `LocalFoldingFeature.current`
  read + conditional padding on `OnboardingPageLayout`. No edits to
  page content, animation timing, or onboarding flow gates.
- **PR #1214** lint-clean lesson: every commit builds + lints. Detekt /
  ktlint pass in CI before push.
- **Memory #16** Phase 3+4 pre-merge: Phase 4 summary emits as soon as
  PR opens, before CI green. Phase 3 verifies Compose previews + lint.

## § Memory candidates (wait-for-third rule)

- `androidx.window:FoldingFeature` collected at Activity scope, provided
  via `CompositionLocal` — first data point this codebase. Do NOT
  auto-memorize. Re-evaluate if a third F-series item lands using the
  same pattern.
- `previewFold` helper co-located in `ui/theme/FoldingPreview.kt` — first
  data point. Pattern only memorable if a second screen-class CompositionLocal
  picks up the same shape.

## § Final state expected at PR open

- WindowInfoTracker integrated: yes
- FoldingFeature observed: yes
- LocalFoldingFeature CompositionLocal: yes
- Per-screen foldable adaptations shipped: 5 (TodayScreen, SettingsScreen,
  ChatScreen, TaskListScreen, OnboardingScreen)
- RED-SKIP per audit verdict: AddEditTaskScreen, Tier 3 specialty screens
- Compose previews for fold states: yes (per affected screen)
- PR #1219 infra intact: yes (no edits)
- PR #1167/#1218 onboarding intact: structurally yes (only padding read added)
- F-series follow-ons filed: F-FOLDABLE-002 (ChatScreen option-a two-pane)

## § Phase F GREEN-GO impact

**Neutral pre-launch.** No regression introduced; the additive
`WindowInfoTracker` Flow + new CompositionLocal are dormant on
non-foldable devices. Compose previews verify non-foldable rendering
unchanged.

**Positive post-launch** for any foldable user — no letterboxed-UX
surprise. The shipped infrastructure handles HALF_OPENED book posture
across Tier 1 + Tier 2 screens. Tier 3 specialty screens fall through
cleanly to the `null` CompositionLocal default.

If 30-day post-merge telemetry surfaces zero foldable users, that is
expected per operator pre-lock. The shipped infrastructure is
dormant-but-correct.
