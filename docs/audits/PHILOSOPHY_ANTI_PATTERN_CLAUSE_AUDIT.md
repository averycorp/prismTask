# Philosophy Anti-Pattern Enforcement Clause — Audit-First

**Scope.** Add a durable anti-pattern enforcement clause to `CLAUDE.md` that codifies the reject filter for new feature work against `docs/PHILOSOPHY.md`'s 7 design principles. Documentation-only PR, ≤50 LOC net add.

**Date.** 2026-05-15. Branch: `worktree-docs+philosophy-anti-pattern-clause`.

---

## Phase 0 — Staleness sweep

| Check | Result |
| --- | --- |
| `CLAUDE.md` exists at repo root | ✓ 213 lines, last touched 2026-05-15 by PR #1347 (docs refresh for v1.9.x) |
| `docs/PHILOSOPHY.md` shipped | ✓ 136 lines, present in `main` |
| `docs/WORK_PLAY_RELAX.md` shipped | ✓ |
| `docs/COGNITIVE_LOAD.md` shipped | ✓ |
| ED-6 / ED-7 in `CLAUDE.md`? | ✗ they live in `docs/audits/D_SERIES_UX_AUDIT.md` (lines 139–146, 228–229, 314–315, 437) |
| Existing anti-pattern clause in `CLAUDE.md`? | ✗ none (grep for `anti-pattern\|forbidden\|never ship` returns 0 matches) |

**STOP-A condition fired but resolved inline (per "make the reasonable call" directive):**
The prompt's premise that ED-6/ED-7 already live *inside* `CLAUDE.md` is wrong — they're in an audit doc. Decision: leave them where they are (D-series audit retains canonical wording), cross-link from the new clause. No relocation in this PR; that would expand scope.

**STOP-A secondary signal:** ED-6 was *overridden* on 2026-05-08 (per `docs/audits/ONBOARDING_TOUR_EXPANSION_AUDIT.md` §0.1) — coachmark infrastructure shipped. The "no whole-app coachmark system" wording is stale. New clause must not parrot the original ED-6 statement; instead cite the surviving intent: "no one-off coachmark systems introduced as a fix path".

---

## Phase 1 — Section A: Current `CLAUDE.md` structure

| Lines | Section |
| --- | --- |
| 1–7 | Title + project overview header |
| 8–~85 | Project overview prose (baseline → v1.9.x state) |
| ~86–105 | Tech Stack |
| 106–~145 | Project Structure (file tree) |
| ~146–180 | Architecture (bulleted) |
| 182–192 | Key Conventions |
| 194–201 | Repo conventions |
| 203–213 | Important Files |

**Proposed insertion point.** After line 201 (end of "Repo conventions" §), before line 203 (## Important Files §). New section heading: `## Anti-pattern enforcement (philosophy)`. Rationale: lives alongside the other durable cross-PR enforcement rules (audit-doc length cap, Phase 3+4 pre-merge override, worktree convention).

**Cross-reference targets verified to resolve:**
- `docs/PHILOSOPHY.md` ✓
- `docs/WORK_PLAY_RELAX.md` ✓
- `docs/COGNITIVE_LOAD.md` ✓
- `docs/audits/D_SERIES_UX_AUDIT.md` ✓

---

## Phase 1 — Section B: 9 anti-pattern verifications vs. current `main`

Each anti-pattern checked against the codebase. Findings drive the clause-wording refinements in Section C.

### AP1 — Streak-loss notifications (Principle 1) → **YELLOW**

**Finding.** `app/src/main/java/com/averycorp/prismtask/notifications/ProductiveStreakNotifier.kt:27` defines `suspend fun notifyBrokenStreak(brokenLength: Int)`. Called from `workers/DailyResetWorker.kt:74` after the daily streak reset is computed.

Copy (`ProductiveStreakPreferences.kt`):
- Title: `"Streak Reset"`
- Body: `"Take care of yourself today — start fresh tomorrow."`

Channel description: `"Forgiveness-first nudges when a productive-day streak resets"`. Notification priority is `PRIORITY_LOW`.

**Tension.** PHILOSOPHY.md L18 lists "no streak-loss notifications" as a Principle 1 implementation point, but its anti-pattern list (L21–24) names specifically "Streak-reset shame UI" / "Punitive notifications" / "Gamification that takes progress away" — none of which describe an empathetic low-priority reset note. The clause must mirror the anti-pattern list, not the looser implementation summary line, otherwise it contradicts shipped reality.

**Verdict.** YELLOW with refinement. Clause wording: rule out **shame-framed** streak-loss notifications and "X days since failure" counters — *not* the existing empathetic reset notification.

### AP2 — Judgment screens / "you missed X%" framing (Principle 2) → **GREEN**

`grep -rln "you missed\|you only completed\|missed % of"` returns 0 matches in `app/src/main` or `web/src`. `EveningSummaryWorker.kt:32` doc-block explicitly notes "non-judgmental, one-sentence summary"; only fires if ≥1 task was completed (no zero-completion ping). `WeeklyBalanceReportScreen.kt` exists but no failure framing surfaced.

### AP3 — Productivity score scope (Principle 3) → **GREEN**

Files: `domain/usecase/ProductivityScoreCalculator.kt`, `domain/usecase/BalanceTracker.kt`. The Work/Play/Relax balance bar (PR #1061 era) treats Play and Relax as peers per `docs/WORK_PLAY_RELAX.md`. `DailyForgivenessStreakCore` counts all completed activity, not just Work tasks. No "Work-only" scoring found.

### AP4 — Single-axis priority as default sort (Principle 4) → **GREEN**

`data/preferences/TaskBehaviorPreferences.kt:75` — `DEFAULT_SORT` falls back to `"DUE_DATE"`, **not** `EISENHOWER` or any priority lens. Eisenhower is a tab/lens, not the default.

### AP5 — Retention dark patterns (Principle 5) → **GREEN**

`grep -in "countdown\|limited\|expire\|hurry\|prechecked\|defaultChecked"` against `SubscriptionScreen.kt` returns 0 matches. `ProUpgradePrompt.kt` reviewed — no fake urgency, no pre-checked subscription boxes, no dark-grey decline button patterns. Pricing UI (per PR #864) restated cleanly.

### AP6 — Notification permission as onboarding step (Principle 7) → **YELLOW**

`MainActivity.kt:357–378` registers `RequestPermission()` for `POST_NOTIFICATIONS` and immediately invokes `notificationPermissionLauncher.launch(...)` inside a top-level `LaunchedEffect(Unit)` on cold launch (API 33+).

`OnboardingScreen.kt:1701–1704` comment explicitly disclaims onboarding ownership of the ask: *"The runtime POST_NOTIFICATIONS ask is owned exclusively by MainActivity.kt's cold-launch LaunchedEffect — onboarding is no longer a re-prompt site."*

**Tension.** Strict reading of "deferred to post-onboarding nudge" (PHILOSOPHY.md L114) is contradicted by the cold-launch ask, which fires *before* the user finishes onboarding pages. But onboarding doesn't *block* on the result — the user can deny and proceed. The ask is non-blocking.

**Verdict.** YELLOW with refinement. Clause wording: rule out notification permission as a **blocking** onboarding step / page — explicitly compatible with the non-blocking cold-launch ask. If operator wants to additionally rule out the cold-launch ask, that's a separate code PR (move the launcher behind a "Settings → Enable notifications" entry point), tracked as deferred follow-on rather than blocking this clause.

### AP7 — Daily-engagement nudges by default (Principle 7) → **YELLOW**

Two relevant workers, both default-ON:

- `notifications/ReengagementWorker.kt:40–44` — fires after **2 days** of absence (default), rate-limited to **1 nudge per absence period**. Gated by `AI_REENGAGEMENT` tier. Pref `REENGAGEMENT_ENABLED` defaults to `true` (`NotificationPreferences.kt:352`).
- `notifications/EveningSummaryWorker.kt` — daily, but **only fires if ≥1 task completed**. Pref `eveningSummaryEnabled` defaults to `true` (per `OnboardingViewModel`).

PHILOSOPHY.md L119 rules out **"Daily 'you haven't opened the app today!' guilt-pings"**. Neither worker matches that pattern exactly — Reengagement is multi-day-cadence and rate-limited; EveningSummary is content-conditional and only fires on the days the user *did* engage.

**Verdict.** YELLOW with refinement. Clause wording: mirror PHILOSOPHY.md exactly — rule out **daily-cadence absence-pings** (not all default-on notifications, not multi-day-rate-limited reengagement). Operator decision: tighten via separate audit if needed.

### AP8 — Features that can't be disabled (Principle 6) → **GREEN**

Verified disable paths:
- AI features: `KEY_AI_FEATURES_ENABLED` (`UserPreferencesDataStore.kt:261`) + backend `require_ai_features_enabled` honor it.
- Sync: Google Sign-In sign-out + local-only mode is supported.
- Balance bar: `showBalanceBar` (`UserPreferencesDataStore.kt:93`).
- Cognitive Load: classifier user-editable per `AdvancedTuningPreferences.kt:144,529`.
- Streak strictness: forgiveness-streak settings section ships.
- Each notification category: individually toggleable in `NotificationPreferences.kt`.

### AP9 — Diagnosis-language as primary audience descriptor → **YELLOW (verification-blocked)**

`README.md` first paragraph: "wellness-aware productivity layer" framing. No ADHD/autism/depression/bipolar as audience descriptors. ✓
`docs/PHILOSOPHY.md` uses "ADHD overwhelm" / "depression-related avoidance" *only* in the rationale (Why-context), not as audience descriptors. ✓
Play Store assets (short description, feature graphic, long description): **not in the repo** — managed via Play Console. Cannot verify from the codebase. PHILOSOPHY.md L3 tagline "Built for every kind of mind" is the canonical descriptor.

**Verdict.** YELLOW only because Play Console isn't in scope; in-repo state is consistent.

---

## Phase 1 — Section C: Clause draft

To be inserted in `CLAUDE.md` after line 201 (end of "Repo conventions"):

```markdown
## Anti-pattern enforcement (philosophy)

PrismTask's design principles are documented in [`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md). Each principle ends with an explicit anti-pattern list; those lists are the canonical reject filter for new feature work. Audit-first prompts that surface any of them as proposed scope must flag and surface to operator before Phase 2.

Headline anti-patterns (full list in PHILOSOPHY.md):

1. Streak-reset shame UI; "X days since failure" counters; punitive streak-loss notifications (Principle 1).
2. End-of-day judgment screens; "you missed X% of your plan" framing; calendar greying-out of missed time (Principle 2).
3. Productivity scores or streaks that count only Work tasks and treat Play / Relax as wasted time (Principle 3).
4. Single-axis priority as the locked default sort; "eat the frog" defaults (Principle 4).
5. Fake countdowns; pre-checked subscription boxes; retention guilt; dark-grey decline buttons; "free trial" auto-bill without re-confirm (Principle 5).
6. Locked defaults; major features (AI, sync, automations, medication reminders, streak, balance bar, cognitive load) with no Settings-level disable path (Principle 6).
7. Onboarding that demands notification permission as a *blocking* step; daily-cadence absence-pings; badge-count gamification (Principle 7).
8. Diagnosis-language (ADHD, autism, depression, bipolar, etc.) as the *primary audience descriptor* on Play Store short description, feature graphic, or README first paragraph. Use "every kind of mind" framing instead. Rationale: Apr 25 2026 Play Console reclassification + Principle 5 honest disclosure.

Override path: any apparent violation requires operator pre-approval AND a documented justification in the PR description. The audit-first STOP protocol is the load-bearing gate.

Pairs with: ED-6 / ED-7 (no one-off coachmark or help-icon *infrastructure introduced as a fix path*) from the D-series UX audit — see [`docs/audits/D_SERIES_UX_AUDIT.md`](docs/audits/D_SERIES_UX_AUDIT.md). Note: ED-6 was selectively overridden 2026-05-08 for the onboarding-tour expansion; the surviving anti-pattern is "don't ship a *one-off* coachmark system as a fix path".
```

**Line count:** ~24 lines content + 2 blank line separators = ≤26 lines net add. Well under the 50-line budget.

---

## Phase 1 — Section D: Scope verdict — **GREEN-GO**

| Aspect | Decision |
| --- | --- |
| LOC | ≤30 net add, doc-only |
| Files changed | `CLAUDE.md` only (insertion at line 202) |
| ED-6/ED-7 relocation | **Leave split.** Canonical wording stays in D-series audit; new clause cross-links. Relocating into CLAUDE.md would expand scope and require re-validating ED-6 override semantics. Deferred follow-on. |
| PHILOSOPHY.md cross-link direction | **One-way.** Principles stand on their own; PHILOSOPHY.md does not need to know about CLAUDE.md enforcement. |
| Anti-pattern numbering vs Principle numbering | Numbered 1–8 in the clause; each line tags its Principle parenthetically. Anti-pattern #8 (Play Store / diagnosis-language) is a *cross-cutting* concern not owned by a single principle — left unnumbered against Principles. |

---

## Phase 1 — STOP triggers — outcome

| Trigger | Fired? | Resolution |
| --- | --- | --- |
| STOP-A: premise wrong | YES (ED-6/ED-7 not in `CLAUDE.md`; ED-6 overridden 2026-05-08) | Resolved inline per "no clarifying questions" directive. Audit doc surfaces both points. |
| STOP-B: shipped contradiction | YES (AP1 partial, AP6 partial, AP7 partial) | Resolved by **refining clause wording** to mirror PHILOSOPHY.md's actual anti-pattern lists (which are already tighter than the prompt-draft wording). |
| STOP-C: scope blowout | NO | ~30 LOC, well under 100 |

---

## Improvement ranking (wall-clock-savings ÷ implementation-cost)

| # | Improvement | Savings | Cost | Verdict |
| --- | --- | --- | --- | --- |
| 1 | Insert anti-pattern enforcement clause in `CLAUDE.md` (this PR) | High — every future audit-first run inherits the reject filter | Low — single doc edit, ~30 LOC | **PROCEED** |

## Deferred follow-ons (re-trigger criteria documented)

| # | Item | Re-trigger criterion |
| --- | --- | --- |
| F1 | Relocate ED-6/ED-7 from `docs/audits/D_SERIES_UX_AUDIT.md` into the new CLAUDE.md clause for single-source enforcement | Operator explicit ask, or a 3rd "where does ED-6 live" question. Memory #30 defer-minimization applies — only file if asked. |
| F2 | Move cold-launch `POST_NOTIFICATIONS` ask out of `MainActivity.kt:357–378` and into a Settings entry point + post-onboarding nudge | Operator decision to tighten Principle 7 enforcement. Code PR, separate scope. |
| F3 | Audit Play Console listing assets against AP9 wording | Next Play Store metadata update or AP9-related rejection. Requires Play Console access (not in repo). |

## Anti-pattern findings worth flagging (not blocking this PR)

- **ProductiveStreakNotifier** ships "Streak Reset" as a notification title. Even with empathetic body copy, the title word "Reset" is a signal. If the team later decides the title is too close to PHILOSOPHY.md L18 "no streak-loss notifications", a follow-up could re-word to "A gentle restart" or similar. Not blocking; flagged because PHILOSOPHY.md's implementation-summary line and its anti-pattern list disagree on strictness.
- **EveningSummaryWorker + ReengagementWorker** both default-ON. Defensible under PHILOSOPHY.md L119's exact wording (daily *absence*-pings) but a strict reading of "quiet by default" could push these to default-OFF. Defer to operator preference.

---

## Phase 3 — Bundle summary

| # | PR | Title | LOC | Impact |
| --- | --- | --- | --- | --- |
| 1 | [#1495](https://github.com/averycorp/prismTask/pull/1495) | docs(claude-md): philosophy anti-pattern enforcement clause | +19 CLAUDE.md, +193 audit doc | Merged 2026-05-15. Every future audit-first run inherits the 8-anti-pattern reject filter without operator re-citing. |

**Wall-clock to ship.** ~25 min from prompt receipt to merged PR (Phase 0 sweep → 9-pattern verification → audit doc → CLAUDE.md edit → PR open → auto-merge → merged). Single-pass shape, no operator round-trips, no checkpoint pauses.

**Memory entry candidates (wait-for-third rule applies — none filed this session):**
- *Candidate (1/3)*: "Refine clause wording to mirror PHILOSOPHY.md's anti-pattern lists rather than prompt-draft wording when they disagree." — Worth memorializing if pattern recurs in a second philosophy-derived clause.
- *Candidate (1/3)*: "ED-6 was overridden 2026-05-08; cite the *surviving* anti-pattern (one-off coachmark systems as a fix path), not the original blanket statement." — Worth memorializing if a third audit prompt parrots the original ED-6 wording.

**Next-audit schedule.** No follow-up audit scheduled. F1/F2/F3 deferred follow-ons re-trigger only on operator ask, code change pressure, or Play Console rejection.
