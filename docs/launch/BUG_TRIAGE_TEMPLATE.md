# Phase F Bug Triage Template

**Source:** `docs/audits/D2_AND_PHASE_F_PREP_MEGA_AUDIT.md` item 7.
**Used by:** Avery, during the 3-week Phase F testing round (kickoff
2026-05-15). The BetaTesting platform's built-in bug intake is the
reporting surface; this template supplies the PrismTask-specific
severity rubric, surface taxonomy, and weekly triage workflow that
the platform's defaults don't enforce.

---

## Severity rubric (P0 / P1 / P2 / P3)

| Severity | Definition | Action window | Examples |
|----------|-----------|---------------|----------|
| **P0** | Data loss, security/privacy regression, crash on launch, or ANY stop-the-line condition from `docs/archive/PHASE_F_KICKOFF_CHECKLIST.md` | Same-day fix + redistribution; halt new builds until resolved | Medication tier write lost; AI gate leaks data when off; sync corrupts other device's row; cold-start crash >5%; Auth wipe |
| **P1** | Core feature broken on a major surface; >25% of testers affected; no workaround | Within 48h; bundled into next planned build | Medication slot reminder fails to fire; habit completion not persisting; weekly review crashes |
| **P2** | Feature degraded but workaround exists; <25% testers affected; visible UX bug | Within the 3-week round; bundled into a regular release | Sort order resets on rotation; quick-add NLP misses a date pattern; widget shows yesterday's data |
| **P3** | Cosmetic, edge-case, or "nice-to-have"; deferrable past Phase F | DEFERRED to G.0 unless trivially fixable | Color contrast on one screen edge; missing TalkBack label on minor button; off-by-one in a count |

**Default severity if unsure:** P2. Avery up- or down-grades during weekly
triage based on tester repro count and surface impact.

---

## Surface taxonomy

Aligned to the Phase F testing checklist + the D2/F audit's scorecard
surfaces. Every bug gets exactly one primary surface label; secondary
labels allowed for cross-surface bugs.

| Label | Surface | Audit scorecard state (2026-04-26) |
|-------|---------|-----------------------------------|
| `surface/tasks` | Tasks list, task editor, AI quick-add (NLP) | GREEN |
| `surface/habits` | Habits, streaks, daily check-ins | GREEN |
| `surface/medication` | Medication tracking, slots, tier marks, refills | YELLOW (smoke gap; PR 4b in flight) |
| `surface/sync` | Cross-device sync, Firestore round-trip, cloud-id reconciliation | GREEN |
| `surface/batch` | Batch operations, multi-select bulk edit, undo log | GREEN |
| `surface/pomodoro` | Pomodoro+, focus release, energy-aware timer | YELLOW (no smoke) |
| `surface/review` | Weekly review flow, morning check-in | YELLOW (only list smoke) |
| `surface/ai-gate` | AI feature gate, privacy invariant, settings opt-out | GREEN (PR #816) |
| `surface/widgets` | Home screen widgets | RED — currently disabled (`WIDGETS_ENABLED=false`); reports here likely point at scaffold/dead UI |
| `surface/mood` | Mood/energy logging, correlation engine | RED (zero coverage; PR 4a in flight) |
| `surface/notifications` | Reminder scheduling, escalation chains, custom sounds, quiet hours | not in scorecard (assumed GREEN; surface if regressions) |
| `surface/auth-billing` | Sign-in, billing, Pro tier gate | not in scorecard (assumed GREEN) |
| `surface/settings` | Settings sections, customization, accessibility | GREEN |
| `surface/infra` | Crashes, performance, install/update, build distribution | not in scorecard (treat any P0/P1 here as stop-the-line) |

---

## Repro-confidence tags

Every bug gets exactly one. Triage decisions weight by confidence.

| Tag | Definition |
|-----|------------|
| `repro/verified` | Avery has reproduced locally (emulator or S25 Ultra) |
| `repro/partial` | Reproduces some of the time; flake or environment-dependent |
| `repro/not-yet` | Tester report only; not yet reproduced |
| `repro/cannot` | Tested but cannot reproduce on Avery's setup; needs more tester data |

A `repro/cannot` bug stays open through the round but does NOT block
release unless P0/P1 with multiple independent reports.

---

## Fix-window estimate framework

Optional; helps prioritize within a severity bucket.

| Estimate | Definition |
|----------|------------|
| `fix/quick` | < 30 min implementation, low risk |
| `fix/medium` | 1-3 hours, may need test coverage |
| `fix/large` | > 3 hours OR refactor required OR cross-surface impact |
| `fix/blocked` | Fix requires external dependency (Firebase config change, Play Store review, third-party SDK update) |

Combine with severity: a P1 + `fix/large` is the most-dangerous shape
during a 3-week round — schedule it ASAP so it lands before week 3.

---

## Weekly triage workflow

**Cadence:** end-of-week (Friday/Saturday). One sitting per week, ~60-90
min during the 3-week round.

### Procedure

1. **Pull all new bugs from BetaTesting platform** (or GitHub issues
   with `phase-f` label, depending on which surface was chosen in the
   `docs/archive/PHASE_F_KICKOFF_CHECKLIST.md` decision).
2. **Apply taxonomy:** severity, surface, repro-confidence,
   fix-window estimate. Default severity P2 if unsure.
3. **Stop-the-line check:** any P0? If yes, halt regular triage and
   execute the stop-the-line response from `docs/archive/PHASE_F_KICKOFF_CHECKLIST.md`.
4. **Promote / demote:** review last week's open bugs; promote any with
   new tester reports, demote any single-report bugs that aged into
   `repro/cannot` without additional confirmation.
5. **Schedule:** assign each P0/P1 to a fix milestone (this week, next
   week, week 3 last-call, post-launch hotfix). P2/P3 go to a backlog.
6. **Tester response:** acknowledge each P0/P1 reporter on the platform
   so testers see triage happening (drives daily check-in compliance).
7. **Update scorecard:** if any RED surface accrues 2+ P0/P1 bugs, flag
   for the next mega-audit's scorecard refresh.

### Triage report template

Avery posts a one-paragraph summary to the project log at end of each
triage session:

```
Phase F triage week N — YYYY-MM-DD

Total new: X | P0: 0 | P1: Y | P2: Z | P3: W
Surfaces hit: [list]
Stop-the-line: NO | YES (action: ...)
Notable: [1-3 bullets]
This-week fixes: [list of P0/P1 scheduled]
```

---

## Anti-patterns (do not do)

- **Don't triage daily.** Tester reports cluster around the platform's
  daily check-in cadence; triaging daily risks chasing duplicates.
  Weekly cadence absorbs duplicate-collapse naturally.
- **Don't auto-fix P3s mid-week.** Even if the fix looks trivial.
  Phase F's value is fix-quality data; P3 noise dilutes signal.
  Batch them at end-of-week.
- **Don't mark `repro/cannot` as closed.** Leave open through the round.
  A second tester report flips it to `repro/partial` and may rescue a
  real bug from being lost.
- **Don't promote a P2 to P1 without 2+ tester reports.** Severity
  inflation degrades the rubric over the 3-week round.
- **Don't merge P0 fixes to main without re-running the full smoke
  suite.** P0s are the most likely shape to introduce regression
  elsewhere.

---

## Source + lifecycle

Generated from `docs/audits/D2_AND_PHASE_F_PREP_MEGA_AUDIT.md` item 7.
This template is owned-by-Avery during the 3-week round; updates land
via PR (not in-place edits) so the template's evolution itself is
audit-trail.

After Phase F closes, the template's effectiveness gets a
post-mortem in `PRE_PHASE_F_MEGA_AUDIT.md` Phase 5 — what worked, what
didn't, what to keep for the v2.1 round.
