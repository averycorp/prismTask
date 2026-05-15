# PrismTask Philosophy

> **Built for every kind of mind.**
> *A productivity app that works with your brain, not against it.*

PrismTask is a todo list and habit tracker designed for diverse types of minds, built mental-health-positive from the ground up. This document defines what "mental-health-positive" concretely means in product terms — the 7 design principles every feature must obey.

These principles are not aspirational. They are the spec the product already implements. They exist in writing so that every future feature decision can be tested against them.

---

## Principle 1 — Forgiveness over punishment

**Rule:** No feature punishes the user for a missed day, broken streak, or skipped task. Every fail state has a graceful recovery path.

**Why:** Punitive streak mechanics work for some brains and actively harm others. They convert one bad day into a shame spiral that ends the user's relationship with the app entirely. "Works with your brain, not against it" fails the moment the app gets angry at the user.

**Implemented by:** `DailyForgivenessStreakCore` (graceful streak recovery), forgiveness-first streak strictness defaults, no streak-loss notifications, no "you broke your streak!" red banners.

**Anti-patterns ruled out:**
- Streak-reset shame UI
- "X days since last failure" counters
- Punitive notifications
- Gamification that takes progress away

---

## Principle 2 — User reality over user aspiration

**Rule:** The app reflects the day the user actually had, not the day they should have had. Plans bend to reality; reality is never wrong.

**Why:** Productivity apps that assume the user will execute their plan create a daily lie — the plan and reality diverge by 10am, and from then on the app is a monument to failure. Apps that work with the brain accept replanning as the normal state, not the error state.

**Implemented by:** AI Schedule Generator that reschedules from current state, Work/Play/Relax balance bar showing actual not planned, "the day you actually had" framing throughout copy.

**Anti-patterns ruled out:**
- Calendar views that grey out "missed" time
- "You only completed 40% of your plan" daily summaries
- End-of-day judgment screens
- Failure framing for unfinished tasks

---

## Principle 3 — Multiple legitimate modes

**Rule:** Rest, play, and low-output days are first-class states, not "off" states. The app values restoration as much as production.

**Why:** Productivity apps that only measure output teach the user that non-output time is wasted time — which is the core engine of burnout. A brain told it's only valuable when producing eventually breaks.

**Implemented by:** Work/Play/Relax orthogonal task dimension where Play (enjoyment) and Relax (restored energy) are peers to Work, not subordinates. Boundaries/burnout scorer. Mood/energy tracking. Daily forgiveness streak counts ALL completed activity, not just Work.

**Anti-patterns ruled out:**
- Productivity scores that only count Work tasks
- "Wasted time" framing for rest or play
- Anti-rest dark patterns
- Burnout-incentivizing engagement metrics

---

## Principle 4 — Friction calibrated to the brain, not the task

**Rule:** Task difficulty is measured by start-friction (how hard is it to begin), not by importance or time. The app surfaces easy starts when the user is depleted.

**Why:** Traditional "priority" sorts surface the most important task — which is usually the hardest to start — at the exact moment the brain is least able to start it. This is why "just do the most important thing first" fails for many brains. Sorting by start-friction means the app proposes work the user can actually begin given current state.

**Implemented by:** Cognitive Load EASY/MEDIUM/HARD orthogonal dimension, Eisenhower as one lens among many (not the default), Cognitive Load classifier tie-break rule "never inflate difficulty".

**Anti-patterns ruled out:**
- Single-axis priority that forces hardest-first
- "Eat the frog" defaults
- Productivity-bro framing
- Time-pressure sorting as the only sort

---

## Principle 5 — Honest disclosure, no dark patterns

**Rule:** Every AI feature, every data egress, every paid tier limit is disclosed in plain language before the user encounters it. No retention traps, no manufactured urgency.

**Why:** Mental-health-positive means trustworthy. An app that markets itself as kind while using dark patterns is lying about what it is. For users with anxiety in particular, dark patterns are actively harmful — they convert app use into a stress source.

**Implemented by:** V2 AI disclosure re-fire with explicit 6-field + rolling-history enumeration in privacy doc, AI feature gate (`require_ai_features_enabled`), no fake countdown timers on Pro upsell, no pre-checked subscription boxes.

**Anti-patterns ruled out:**
- "Limited time offer!" fake urgency
- Retention guilt ("Are you sure? You'll lose your streak!")
- Pre-checked subscription boxes
- Dark-grey decline buttons
- "Free trial" that auto-bills without re-confirm

---

## Principle 6 — The user is the expert on their own brain

**Rule:** Defaults are gentle, but every behavior is configurable. The app proposes; it does not prescribe. The user can turn off any feature, including the ones the app thinks are essential.

**Why:** Brains vary. Features that help one user actively harm another — strict streaks help some, shame others; reminders help some, anxiety-spike others; AI suggestions help some, feel invasive to others. A mental-health-positive app trusts the user's self-knowledge over its own opinions.

**Implemented by:** Settings → AI features master toggle, per-medication / per-slot reminder overrides, Brain Mode toggles, forgiveness streak strictness configurable, Cognitive Load classifier user-editable, every notification category individually toggleable.

**Anti-patterns ruled out:**
- "We know what's best for you" framing
- Locked defaults
- Forced onboarding paths
- Features that can't be disabled

---

## Principle 7 — Quiet by default

**Rule:** Notifications, badges, sounds, and alerts are off-by-default or minimal-by-default. The app asks permission to interrupt; it does not assume it.

**Why:** Notification overload is one of the most-cited drivers of anxiety, ADHD overwhelm, and depression-related avoidance. An app that opts the user into 8 daily nudges has chosen the app's engagement metrics over the user's nervous system.

**Implemented by:** Permissions deferred to post-onboarding nudge (not required during onboarding), notification preferences per-category, no daily-engagement nudges by default, no badge-count gamification.

**Anti-patterns ruled out:**
- Onboarding that demands notification permission as a step
- Daily "you haven't opened the app today!" guilt-pings
- Badge-count gamification
- Sound-on-by-default alerts

---

## How to use this document

This document is the acceptance test for every future feature. Before shipping anything, ask: *does this feature violate any principle?* If yes, redesign. If no, ship.

It is also the public face of the concept. These 7 principles are what we say in long-form Play Store description, README "Philosophy" section, Reddit posts, and outreach decks. The tagline names the audience and value; these principles prove it.

## Related docs

- `docs/WORK_PLAY_RELAX.md` — Principle 3 implementation detail (orthogonal mode dimension)
- `docs/COGNITIVE_LOAD.md` — Principle 4 implementation detail (start-friction dimension)
- `docs/privacy/index.md` — Principle 5 implementation detail (disclosure copy)
