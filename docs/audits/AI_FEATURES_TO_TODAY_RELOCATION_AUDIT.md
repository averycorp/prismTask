# AI Features → Today Relocation Audit

**Scope.** Operator request: *"All AI features should be available from the
today page, not settings."* This is a follow-up to
[`AI_TODAY_ACCESS_AUDIT.md`](AI_TODAY_ACCESS_AUDIT.md) (PR #1145) which
*surfaced* AI features onto Today via a chip row while keeping the
canonical hub in Settings → AI Features. The new directive is a
**relocation**: remove the Settings hub, host the full AI surface on
Today.

**Optimization target.** Single canonical AI surface on Today; no AI
entries left in the Settings tree. Also: preserve discoverability of
the master AI privacy toggle (`ai_features_enabled`), which the
Android↔Web parity audit (`ANDROID_WEB_PARITY_AUDIT.md:522`) flagged
as **SHIP-BEFORE-MAY-15** legal/PII opt-out — today is 2026-05-07,
so the toggle must stay reachable, just not from Settings.

**Suspected failure modes.** Orphaned route, broken back-stack from
deep links, lost privacy toggle, lost auto-classify toggle, lost
disclosure paragraph, regression of the chip row's six existing entries.

---

## 1. Inventory: what currently lives in Settings → AI Features

Verified by reading `SettingsScreen.kt:255-280`, `AiFeaturesScreen.kt`,
`AiSection.kt`, and `SettingsViewModel.kt:227-253`.

The Settings entry (`SettingsScreen.kt:257-264`) opens
`AiFeaturesScreen.kt`, which is a thin wrapper around a single section
composable (`AiSection.kt`). `AiSection` contains:

| # | Item | Kind | Already on Today? | Source |
|---|------|------|-------------------|--------|
| 1 | Privacy disclosure paragraph | text | NO | `AiSection.kt:32-41` |
| 2 | "Use Claude AI for advanced features" — master toggle | toggle | NO | `AiSection.kt:43-55` |
| 3 | "Auto-Classify Tasks (Eisenhower)" toggle | toggle | NO | `AiSection.kt:57-62` |
| 4 | Eisenhower Matrix nav row | nav | YES — chip "Matrix" (`TodayScreen.kt:479-487`) | `AiSection.kt:64-68` |
| 5 | Smart Focus Sessions nav row | nav | YES — chip "Focus" (`TodayScreen.kt:461-469`) | `AiSection.kt:70-74` |
| 6 | Daily Briefing nav row | nav | YES — chip "Briefing" (`TodayScreen.kt:452-460`) | `AiSection.kt:76-80` |
| 7 | Weekly Planner nav row | nav | YES — chip "Plan Week" (`TodayScreen.kt:470-478`) | `AiSection.kt:82-86` |
| 8 | Time Blocking (Timeline) nav row | nav | NO (timeline tab is separate) | `AiSection.kt:88-92` |
| 9 | Extract Tasks From Text nav row | nav | YES — chip "Extract" (`TodayScreen.kt:488-496`) | `AiSection.kt:94-98` |
| 10 | Weekly Review nav row | nav | YES — chip "Review" (`TodayScreen.kt:497-505`) | `AiSection.kt:100-104` |
| 11 | Mood Analytics nav row | nav | conditional only — Energy Check-In card's "View Trends" (`TodayScreen.kt:359-361`) | `AiSection.kt:106-110` |
| 12 | AI Coach Chat (Pro) nav row | nav | YES — Pro-only small FAB (`TodayScreen.kt:262-275`) | `AiSection.kt:112-116` |

**Gap analysis.** Six of the nine nav rows already have an
unconditional one-tap path from Today (chips). AI Coach has the FAB.
**Three things have no unconditional Today path:** Timeline, Mood
Analytics, and (most importantly) the master AI toggle + auto-classify
toggle + privacy disclosure paragraph.

---

## 2. Per-item findings

### 2a. Settings → AI Features nav row (RED — must remove)
`SettingsScreen.kt:257-264`. Single source: navigates to
`settings/ai_features`. Operator's directive removes this. **PROCEED.**

### 2b. `settings/ai_features` route registration (RED — must remove)
`SettingsRoutes.kt:49`. Only call site is the SettingsScreen row above
(verified via `grep -rn "settings/ai_features" app/src --include="*.kt"`
— exactly 2 hits, both being-removed sites). No deep links, no tests
reference the route (`grep -rn "AiFeaturesScreen\|settings/ai_features"
app/src/test app/src/androidTest` — no output). Safe to delete.
**PROCEED.**

### 2c. `AiFeaturesScreen.kt` wrapper (RED — must remove)
74 lines; thin Scaffold wrapping `AiSection`. Once the route is gone
the screen is orphaned. Delete the file. **PROCEED.**

### 2d. `AiSection.kt` composable (YELLOW — relocate, do not delete)
The composable itself is the right shape for the new Today hub: it
already accepts callbacks for all 9 navigation targets and both toggle
states. We can reuse it verbatim from a new Today bottom-sheet host.
Moving it under `ui/screens/today/ai/` would be cleanest, but it lives
under `ui/screens/settings/sections/` today and renaming + moving costs
import churn for zero behavior change. **Recommendation: leave the
file in place; rename only if it survives a second relocation.**
Marked `@Composable` without Settings-specific dependencies, so the
package label is the only thing that smells.

### 2e. Master AI toggle privacy obligation (YELLOW — must stay reachable)
`ANDROID_WEB_PARITY_AUDIT.md:522` flags this toggle as legal/PII
opt-out parity work. The disclosure paragraph at `AiSection.kt:32-41`
is part of the contract. Relocation is fine; *deletion* is not.
The Today hub sheet must include both. **PROCEED — preserve in hub.**

### 2f. Today AI hub trigger (RED — needs new entry point)
The chip row has 6 chips today. Adding three more (Timeline, Mood,
"AI Settings/Hub") would push the row to nine on a horizontally-scrollable
strip — workable but the toggles + disclosure don't belong in a chip.
The clean move is a single new "AI Tools" sparkle chip at the end of
the row that opens a bottom sheet rendering the full `AiSection`.
The 6 existing chips stay in place (don't break muscle memory; the
prior audit explicitly recommended against demoting them). The Pro
AI Coach FAB also stays. **PROCEED — add sparkle chip + bottom sheet.**

### 2g. Today hub bottom-sheet host (RED — new composable)
A new `TodayAiHubSheet` composable mounted in `TodayScreen.kt`'s
content lambda, rendered conditionally on a `showAiHub` state, hosting
`AiSection` with callbacks that both `navController.navigate(…)` and
dismiss the sheet. Toggle state pulled via
`hiltViewModel<SettingsViewModel>()` — same pattern
`AiFeaturesScreen.kt:30-35` used; the ViewModel name is a label, not
a coupling, and the toggles are DataStore-backed. **PROCEED.**

### 2h. Timeline + Mood Analytics chip-row coverage (DEFERRED)
The hub sheet covers both. Adding standalone chips would duplicate
hub entries on a row that already wraps awkwardly per
`AI_TODAY_ACCESS_AUDIT.md:138-142`. **STOP-no-work-needed** — hub
entry suffices for these two; if usage data later shows them as
high-frequency, promote individually.

### 2i. Tests (GREEN)
No unit or instrumentation tests reference `AiFeaturesScreen` or the
`settings/ai_features` route (verified via grep against
`app/src/test` and `app/src/androidTest`). The
`AiFeatureGateInterceptorTest` mention at `:316` is a comment about
the backend gate, unrelated. **No test changes needed beyond any
new ones for the Today hub.**

### 2j. SettingsScreen layout integrity (YELLOW)
Removing the AI Features row leaves the "Productivity" group with
two rows (Brain Mode, Wellbeing). Group is still legitimate at 2
rows — `Integrations` group has 1 row. **No layout fix required.**

### 2k. Branch / fan-out shape (RED — single PR)
The system-prompt branch directive pins development to
`claude/ai-features-today-page-5bQWs`. That's a single coherent
scope ("relocate AI hub from Settings to Today") so a single PR is
correct per the bundling rule. **PROCEED — 1 PR.**

---

## 3. Proposed improvements (ranked by savings ÷ cost)

| Rank | Improvement | Wall-clock savings | Implementation cost | Ratio | Verdict |
|------|-------------|---------------------|----------------------|-------|---------|
| 1 | **Add Today AI hub** — new sparkle "AI Tools" chip at end of `TodayScreen.kt:439-507` chip row + new `TodayAiHubSheet` bottom-sheet composable that renders the existing `AiSection` with nav callbacks. Toggle state via `hiltViewModel<SettingsViewModel>()`. | High — closes the relocation in one move; brings master toggle, auto-classify, privacy disclosure, Timeline, Mood Analytics, AI Chat onto Today | Low (~80 LOC: 1 sparkle chip, 1 sheet host, 1 state hoist) | **High** | **PROCEED** |
| 2 | **Remove Settings entry** — drop the AI Features row in `SettingsScreen.kt:257-264`, delete `"settings/ai_features"` from `SettingsRoutes.kt:49`, delete `AiFeaturesScreen.kt`. | High — fulfills "not settings" directive | Low (~15 LOC removed + 1 file deleted) | **High** | **PROCEED** |
| 3 | **Promote Timeline + Mood to standalone chips** | Low — hub sheet already covers them | Low | Low | DEFER — Roll into hub entry only |
| 4 | **Move `AiSection.kt` to `ui/screens/today/ai/`** (package rename) | Negligible (cosmetic) | Low–Medium (import churn) | Low | DEFER — leave for a future cleanup |
| 5 | **Extract `AiPrefsViewModel`** so the Today hub doesn't reach into `SettingsViewModel` | Negligible (label-only coupling) | Medium | Low | DEFER — over-engineering |

**Bundle decision.** Items 1 + 2 ship together as a single PR on the
assigned branch — they are one coherent scope and the operator's
directive is not satisfied without both halves.

### Anti-patterns to avoid

- **Don't delete the master toggle or the privacy paragraph.** The
  parity-audit deadline (May 15, 2026) and the disclosure copy at
  `AiSection.kt:32-41` are non-negotiable. Move, don't drop.
- **Don't replace existing chips with the hub.** The prior audit's
  rationale (chips = muscle memory for top-3 actions) still holds.
  The hub is *additional*, not a replacement.
- **Don't gate the hub on `aiFeaturesEnabled`.** When the user has
  AI off, they still need the hub visible to find the toggle that
  re-enables it. (Same anti-pattern flagged in the prior audit.)
- **Don't introduce a deep link to `settings/ai_features` for
  back-compat.** Verified: no callers exist outside the two
  removal sites.
- **Don't bundle this with unrelated chip-row changes.** Operator
  asked specifically about the relocation; chip-row redesign is
  a separate decision.

### Out of scope (flagged for later)

- Promoting Timeline / Mood Analytics to standalone chips (item 3).
- Renaming/moving `AiSection.kt` out of the `settings/sections`
  package (item 4).
- Free-tier visibility for the AI Coach FAB (still
  `if (viewModel.isPro)`-gated; pricing decision, not access).
- Web parity for the Today AI hub (Android-only change here).

---

## Phase 2 plan

Single PR on `claude/ai-features-today-page-5bQWs`:

**Files touched:**
1. `app/src/main/java/com/averycorp/prismtask/ui/screens/today/TodayScreen.kt`
   — add sparkle "AI Tools" chip at end of chip row; add `showAiHub`
   state + ModalBottomSheet host rendering the new hub composable.
2. `app/src/main/java/com/averycorp/prismtask/ui/screens/today/ai/TodayAiHubSheet.kt`
   *(new file)* — composable that takes a `NavController` + dismiss
   callback, calls `hiltViewModel<SettingsViewModel>()`, renders
   `AiSection` with nav callbacks that navigate + dismiss.
3. `app/src/main/java/com/averycorp/prismtask/ui/screens/settings/SettingsScreen.kt`
   — remove the AI Features `SettingsNavRow` (`:257-264`).
4. `app/src/main/java/com/averycorp/prismtask/ui/navigation/routes/SettingsRoutes.kt`
   — remove the `"settings/ai_features"` entry (`:49`) and the
   `AiFeaturesScreen` import (`:17`).
5. `app/src/main/java/com/averycorp/prismtask/ui/screens/settings/AiFeaturesScreen.kt`
   *(deleted)* — orphaned.

**Tests:** No existing tests reference removed surfaces. Add a
smoke test for the hub composable only if a clean Compose-test
harness already exists for similar Today components; otherwise
defer (the reused `AiSection` is already exercised indirectly).

**Risks tracked:**
- Master toggle reachability — mitigated: hub renders the full
  `AiSection`.
- Settings layout — verified: Productivity group still has Brain
  Mode + Wellbeing; no empty group.
- Back-stack — verified: no callers of the removed route exist.
- Parity audit deadline — mitigated: toggle relocates, not
  deletes.

---

## Phase 3 — Bundle summary

### Shipped

- **PR #1165** (`feat(today): host AI hub on Today, remove Settings -> AI
  Features`) bundles both PROCEED items into a single PR on the assigned
  branch `claude/ai-features-today-page-5bQWs` per the operator's branch
  directive — single coherent scope, fan-out not required.

### Per-improvement detail

| # | Improvement | Files | Net |
|---|-------------|-------|-----|
| 1 | Today AI hub (sparkle chip + bottom sheet) | `TodayScreen.kt` (+18), `TodayAiHubSheet.kt` (new, +82) | +100 |
| 2 | Settings AI hub removal | `SettingsScreen.kt` (-7), `SettingsRoutes.kt` (-2), `AiFeaturesScreen.kt` (deleted, -73) | -82 |

Total: **+103 / -83**, 1 file deleted, 1 file added.

### Deviations from Phase 1 plan

None. The single-PR shape, the chip-as-trigger choice, and the verbatim
reuse of `AiSection` all match the audit recommendation.

### Memory entry candidates

None — relocation pattern (settings → today bottom sheet) is one-shot
UX work, not a generalizable harness lesson.

### Schedule for next audit

After PR #1165 merges: re-baseline the chip-row width on small phones
(now seven chips wide) and decide whether to migrate to a stacked /
collapsible "More" affordance if usage data shows the AI Tools chip
sitting offscreen in the default scroll position.

---

## Phase 4 — Claude Chat handoff

```markdown
# AI Features → Today relocation (PrismTask)

**Scope.** Operator directive on `averycorp/prismTask`: "All AI features
should be available from the today page, not settings." Relocate the
AI hub from Settings → AI Features onto the Today screen.

**Verdicts table**

| Item | Class | One-line finding |
|------|-------|------------------|
| Settings → AI Features nav row | RED | Single source removed (`SettingsScreen.kt:257-264`); no other callers |
| `settings/ai_features` route | RED | Removed from `SettingsRoutes.kt:49`; grep confirmed zero deep-link references |
| `AiFeaturesScreen.kt` wrapper | RED | Deleted; was a 74-line Scaffold around `AiSection` |
| `AiSection.kt` composable | YELLOW | Reused verbatim from new Today host; package label still under `settings/sections/` (cosmetic only) |
| Master AI privacy toggle | YELLOW | Relocated to Today hub sheet (parity audit `ANDROID_WEB_PARITY_AUDIT.md:522` blocks deletion) |
| Today AI hub trigger | RED→done | New "AI Tools" sparkle `AssistChip` appended to existing chip row |
| `TodayAiHubSheet.kt` | RED→done | New `ModalBottomSheet` host, uses `hiltViewModel<SettingsViewModel>()` for toggle state |
| Timeline + Mood Analytics chips | DEFERRED | Hub covers them; standalone chip promotion is a future call |
| Tests for removed surfaces | GREEN | None existed (verified via grep) |
| Free-tier AI Coach FAB visibility | DEFERRED | Pricing decision, not access |

**Shipped**

- **PR #1165** — Today AI hub + Settings removal, single PR on
  `claude/ai-features-today-page-5bQWs`. +103 / -83.

**Deferred / stopped**

- Standalone Timeline / Mood chips: hub already covers them; chip-row
  real estate is tight at 7 entries.
- `AiSection.kt` package rename to `today/ai/`: cosmetic, would force
  import churn for zero behavior change.
- Extract `AiPrefsViewModel`: over-engineering; the hub reaching into
  `SettingsViewModel` is a label, not a coupling.
- AI Coach FAB Free-tier visibility: pricing decision, separate audit.

**Non-obvious findings**

- The Today chip row was already at 6 chips and 6 of the 9 nav rows
  in `AiSection` were already one-tap-from-Today via those chips
  (Briefing / Focus / Plan Week / Matrix / Extract / Review). The
  relocation's actual delta is the **two toggles + privacy
  disclosure + Timeline + Mood Analytics** that had no
  unconditional Today path before.
- AI Coach Chat sits on a Pro-only `SmallFloatingActionButton`
  (`TodayScreen.kt:262-275`), not a chip. The hub's "AI Coach Chat"
  row gives Pro users a second path and gives Free users a visible
  upgrade affordance for the first time.
- The master AI toggle is a **legal/PII opt-out** flagged in
  `ANDROID_WEB_PARITY_AUDIT.md:522` with a SHIP-BEFORE-MAY-15
  deadline. Today is 2026-05-07, so the relocation must keep the
  toggle reachable, not delete it. The hub satisfies that.
- A prior audit `AI_TODAY_ACCESS_AUDIT.md` (PR #1145) already
  surfaced AI features onto Today via chips while keeping Settings
  as the canonical hub. The new directive supersedes that
  positioning.

**Open questions**

- Should the chip row migrate to a horizontally-scrollable strip with
  a "More AI…" affordance instead of seven chips? Re-evaluate after
  small-phone usage data lands.
- Free-tier visibility for AI Coach Chat: still gated by
  `if (viewModel.isPro)` on the FAB; the hub's "AI Coach Chat" row
  also navigates without the gate, so Free users see it but tap →
  paywall. Deliberate or accidental?
```

