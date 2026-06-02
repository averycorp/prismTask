# Design Tension: Scaffolding vs. Calm in ND Productivity Tooling

> **Status: open problem.** This document states a real, unresolved design conflict at the core of
> PrismTask. The tension is identified; a non-obvious resolution is not yet worked out. This file is
> a working document, not a claim of a solved contribution — see "What this is and isn't" below.

## The one-sentence problem

The design tools that make a productivity app *work* for an ADHD/neurodivergent brain are largely the
same engagement mechanics that PrismTask's wellness philosophy forbids — so the three philosophies
collide on a single point, and the obvious way to satisfy any two of them violates the third.

## The three philosophies

1. **ADHD / ND-first design.** The user has executive-function challenges: difficulty initiating
   tasks, weak time perception, working-memory limits, and low salience for future/abstract rewards.
   They need *external scaffolding* — prompts, structure, visible cues, and stimulation that pulls
   attention to the right thing at the right time.

2. **Cognitive-load management.** Every decision, notification, and unit of visual complexity spends a
   limited budget. The app should *minimize* friction, choices, and attention demands so the user
   isn't overwhelmed before they even start.

3. **Anti-engagement / wellness.** The app must *not* use the attention-capture playbook: no streaks,
   no manufactured urgency, no gamified dopamine loops, no dark patterns, no guilt nudges. It should
   respect the user's attention and be safe to *not* use.

## Why they collide

The conventional way an ADHD app delivers the scaffolding that philosophy #1 demands **is** the
engagement machinery that philosophy #3 forbids:

- **Salience** for an ADHD user is conventionally manufactured through *push* — notifications, badges,
  escalating reminders, streak pressure. Those are engagement loops. Remove them (per #3) and you
  remove the external salience the ND user needs (per #1).
- **Motivation / initiation** is conventionally bootstrapped through gamification — points, streaks,
  urgency — which exploit the dopamine-seeking that #3 refuses to weaponize. But "use willpower
  instead" fails the ND user, who has reduced access to self-generated drive.
- **Load reduction** (#2) pushes toward *fewer cues and less stimulation*, which can starve the ND
  user of the salience #1 requires — yet adding salience risks both the load budget (#2) and the
  engagement line (#3).

### The pairwise-easy / three-way-hard structure

You can satisfy any two philosophies by sacrificing the third:

| Satisfy | The usual way | Violates |
|---|---|---|
| ND-scaffolding + load-management | push notifications, gamification | **Anti-engagement** |
| Anti-engagement + load-management | quiet, minimal app | **ND-scaffolding** (starves the user of cues) |
| ND-scaffolding + anti-engagement | rich manual cues the user configures/maintains | **Cognitive-load** (blows the budget) |

That no two-out-of-three is free is what makes this a genuine design problem rather than a feature
checklist.

## What a solution must do (success criteria)

A non-obvious resolution must deliver **salience and initiation support to an executive-function-
challenged user** while simultaneously:

- (a) using **no engagement loop** (no streaks, no escalation, no attention capture),
- (b) introducing **no manufactured urgency or guilt**, and
- (c) producing **no net increase** in the user's decision / attention load.

Stated positively: a mechanism for **scaffolding that does not depend on capturing attention.**

## Candidate directions (hypotheses, NOT answers)

Shapes a resolution *might* take. None is worked out; the contribution is in actually solving and
validating one — not in listing them.

- **Pull-based, not push-based salience.** Structure is *present and visible when the user arrives*
  rather than reaching out to drag them in. Salience by placement/design, not by interruption.
- **Decaying, not escalating scaffolding.** Cues that soften over time instead of nagging harder — the
  opposite of a streak. The system never ramps pressure.
- **Structural, not motivational, load reduction.** Lower the *initiation cost* of a task through how
  it is represented/decomposed, so less external motivation is needed in the first place — sidestepping
  the need for engagement hooks.
- **Ambient / low-stimulation salience.** Make the right thing prominent without adding to the decision
  budget or the notification stream.

## What this is and isn't

- **It is:** a real, sharp, three-way design conflict, stated explicitly. (Identifying the tension is
  the rare part.)
- **It is not (yet):** solved. The tension is named; a non-obvious mechanism that resolves it is not
  yet derived.
- **Why it matters:** a genuine resolution, written up, would be the novel core of this project — and a
  real talking point for both industry and research audiences. Absent that, PrismTask remains a strong,
  well-executed instance of a well-understood problem (which is already its standing).

## Working discipline

The contribution is the **resolution**, produced by working the problem — not a clever-sounding framing
reverse-engineered to claim novelty. A retrofitted answer will not survive a reviewer who works in this
space; an honest "here is a real tension, and here is the mechanism I derived to resolve it" will. The
writeup is how the problem gets solved, not how it gets dressed up.

---

### Log (append as the problem is worked)

<!--
Date — what was tried, what the result was, what the open question is now.
Treat negative results as first-class: "tried X, it collapsed condition (c), here's why."
-->

- _(open)_ — tension stated; no resolution yet.
