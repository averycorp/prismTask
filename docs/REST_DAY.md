# Rest day — recovery-first primitive

PrismTask treats deliberate rest as a first-class action.

A user with low energy, a depressive dip, an ADHD-trough day, a flare-up,
or any other reason to step back can mark today as a rest day. Marking
does *not* erase what was planned and it does *not* count as a miss —
it's an explicit "I'm resting today" signal that composes with every
other PrismTask philosophy doc.

This doc exists so that when someone touches rest-day code, writes
rest-day copy, or designs a new surface that consumes the rest-day
signal, they share one definition of what "rest day" means in PrismTask.
See [`FORGIVENESS_FIRST.md`](FORGIVENESS_FIRST.md) and
[`COGNITIVE_LOAD.md`](COGNITIVE_LOAD.md) for the companion philosophy
docs this one composes with.

---

## The core rule

A rest day is **a calendar date the user has explicitly marked as
"resting" via the Today-screen action**. While the flag is on for that
date:

1. **Habit and project streaks stay safe.** The forgiveness-first streak
   core
   ([`DailyForgivenessStreakCore.kt`](../app/src/main/java/com/averycorp/prismtask/domain/usecase/DailyForgivenessStreakCore.kt))
   treats the date as *kept by definition*. It does **not** consume the
   grace window. It does **not** count as a miss.
2. **Non-medication notifications pause for that date.** Task reminders,
   habit reminders, daily briefing/digest, overload check, reengagement
   nudge, and escalation steps all consult
   [`RestDayGate`](../app/src/main/java/com/averycorp/prismtask/notifications/RestDayGate.kt)
   before firing and short-circuit on a rest day.
3. **Medication reminders are unaffected.** Every `showMedication*` /
   `showSlot*` / `showMedSlot*` path in `NotificationHelper` intentionally
   skips the gate. Missed doses break a real chemical chain (refill
   cadence, blood-level stability) — that's a safety-of-life concern that
   overrides the rest-day pause. Audit § G3 spells this out explicitly.
4. **The Today screen replaces the dense list with a soft header**
   ("Resting today — see you tomorrow") plus an explicit *End Rest Day*
   button. Tasks scheduled for today **stay in Room** — they are not
   deleted, not archived, and not auto-rescheduled.

The implementation lives in three small pieces:

- [`RestDayEntity`](../app/src/main/java/com/averycorp/prismtask/data/local/entity/RestDayEntity.kt) /
  [`RestDayDao`](../app/src/main/java/com/averycorp/prismtask/data/local/dao/RestDayDao.kt) —
  one row per ISO date (`yyyy-MM-dd`), SoD-aware.
- [`RestDayRepository`](../app/src/main/java/com/averycorp/prismtask/data/repository/RestDayRepository.kt) —
  date resolution through `DayBoundary`, idempotent mark/unmark.
- [`RestDayGate`](../app/src/main/java/com/averycorp/prismtask/notifications/RestDayGate.kt) —
  the suspend-fn gate every non-medication notification path consults.

## What rest day is not

- **Not a sick-leave certificate.** The flag has no medical meaning, no
  documentation requirement, and no clinical framing. The user is the
  sole authority on whether today is a rest day.
- **Not retroactive editing.** The toggle marks *today* (the user's
  logical, SoD-aware day). Yesterday's missed habit doesn't become a
  rest day after the fact; the streak rules and grace window stand.
  Adding a back-dated rest day would be a different feature; this PR
  doesn't ship one.
- **Not a streak loophole.** Rest day is a deliberate signal, not a
  free pass. The forgiveness-first grace window still exists and still
  absorbs *one* genuine miss inside its rolling window — independently
  of how many rest days the user marks. The two coexist; one does not
  replace the other.
- **Not a notification kill-switch.** Medications still fire.
  Rest day silences non-medication; the user wanting a totally quiet
  day still needs the per-app quiet-hours flow or "Pause for 1h / 4h /
  until tomorrow" (audit § G4).
- **Not the productive-day streak's concern.** The composite-score
  productive-day streak in
  [`ProductiveStreakResolver.kt`](../app/src/main/java/com/averycorp/prismtask/workers/streak/ProductiveStreakResolver.kt)
  is the documented exception to forgiveness-first and is not yet
  wired into the rest-day signal. The reframing options live in
  `FORGIVENESS_FIRST.md` § Open questions; the current behavior is:
  marking a rest day does *not* shield the productive-day streak.

## Why we do this

Productivity apps that lack a deliberate-pause primitive force users
into a binary choice: grind through tasks the user can't actually do,
or miss them and watch the streak break. Neither is good for users
with ADHD, depression, anxiety disorders, chronic illness, autism, or
acute burnout — all populations the PrismTask product intent doc names
explicitly.

The audit (`docs/audits/MENTAL_HEALTH_FIRST_AUDIT.md` § G3) calls this
out as the largest mental-health-first gap pre-v1.7: the forgiveness
core absorbs *one* miss, but a deliberate "I'm resting" was being
absorbed *as* that one miss — burning the grace window every time.
After this PR a rest day is structurally different from a miss.

The voice-aligned framing: rest day is *participation*, not absence.
A user who marks a rest day is still engaging with the app, they're
just engaging by deliberately choosing recovery instead of execution.
The streak rules and the UI both reflect that.

## Day boundaries

Rest days resolve against [`DayBoundary`](../app/src/main/java/com/averycorp/prismtask/util/DayBoundary.kt) —
**not** system midnight. `RestDayRepository.markTodayAsRestDay`
computes the key via `DayBoundary.currentLocalDateString(sodHour,
now, sodMinute)`, so a user with SoD = 04:00 who taps the toggle at
02:30 marks *yesterday's* calendar date — which is correct, because
that's the user's logical day.

The ISO string column avoids the timezone-shift footgun: even when the
device crosses DST or the user travels, the row stays meaningful
because it identifies the user's logical day on the original device.

## Composes with

- **Forgiveness streak** (`FORGIVENESS_FIRST.md`). Rest days are folded
  into the activity set inside
  `DailyForgivenessStreakCore.calculate(restDays = ...)` so the same
  resilient walk treats them as kept. Crucially: a rest day **does
  not** consume the rolling-window grace cap — the cap still has its
  full budget for genuine misses.
- **Quiet hours / notification profiles**. Quiet hours apply on a
  schedule (start/end time); rest day applies on a date. Both gate the
  same fan-out; rest day short-circuits *before* the quiet-hours
  per-trigger check.
- **ND-friendly modes** (`NdPreferences`). Rest day is available to
  every user, not just those who opted into Brain Mode. It is *also*
  the first-class deliberate-pause primitive that the ND mode docs
  pointed at as a missing piece — see
  `docs/audits/MENTAL_HEALTH_FIRST_AUDIT.md` § G3.
- **Onboarding ND hint** (audit § G6, separate PR). Users who self-ID
  as having low-energy days during onboarding will see the rest-day
  toggle exposed prominently; the toggle itself exists for everyone
  regardless.
- **Medications** (`SPEC_MEDICATIONS_TOP_LEVEL.md`). Explicitly out of
  scope. Medications are the one non-negotiable. The audit answer is
  documented in this doc and in `RestDayGate`'s KDoc so future contributors
  don't accidentally collapse the two paths.

## Copy guidelines

Voice exemplar:
[`ProductiveStreakPreferences.BROKEN_STREAK_NOTIFICATION_BODY`](../app/src/main/java/com/averycorp/prismtask/data/preferences/ProductiveStreakPreferences.kt)
— *"Take care of yourself today — start fresh tomorrow."*

Descriptive, non-clinical, non-shaming. The app states what is
happening; it does not prescribe behavior.

- ✅ "Resting today — see you tomorrow."
- ✅ "Habit streaks stay safe. Non-medication notifications pause."
- ✅ "Mark today as a rest day?" (question; non-leading)
- ✅ "End rest day" (action; symmetric with mark)
- ❌ "You should rest today." (prescriptive)
- ❌ "You need a break." (prescriptive + diagnostic)
- ❌ "Take a sick day." (clinical framing)
- ❌ "Don't be lazy." (shaming)
- ❌ "You earned this." (gamification — rest is not a reward)
- ❌ "You missed too many days." (links rest to failure)

The "Yes, rest today" / "Not yet" dialog button pair is deliberate:
"Not yet" is softer than "Cancel" and preserves the rest-day option
without framing the dismissal as rejection.

## When you'd add a new rest-day-aware surface

- Call `RestDayRepository.isRestDayToday()` (one-shot) or
  `observeIsRestDayToday()` (reactive). Do not roll your own date
  bucketing — the repo already wires SoD via `DayBoundary`.
- For non-medication notifications: call
  `RestDayGate.shouldSuppress(context)` at the **firing** seam (inside
  the worker or `NotificationHelper.show*` fn), not at the scheduling
  seam. Alarms can be scheduled hours / days in advance; the rest-day
  flag can flip in between. Checking at fire time is the only way to
  honor the flag for already-scheduled alarms.
- For medication paths: **do not call the gate.** Confirm by code
  review that the path is medication-only before merging.
- For new streak surfaces: thread `restDays: Set<LocalDate>` through to
  `DailyForgivenessStreakCore.calculate`. Don't reimplement the fold.
- Write copy following the **Copy guidelines** above.

## Open questions

The audit
[`docs/audits/MENTAL_HEALTH_FIRST_AUDIT.md`](audits/MENTAL_HEALTH_FIRST_AUDIT.md)
keeps the following as open shape questions:

- **Cross-device sync.** The current shape lands only in Room. Cloud
  sync follows the `CheckInLogEntity` precedent — `cloud_id` +
  `updated_at` columns are present so an additive Firestore mirror
  doesn't need another schema bump. Wiring `SyncMapper` + push/pull
  is a follow-up, not blocking.
- **Past dates.** This PR ships only "mark today / unmark today" UI.
  Back-dating ("Tuesday last week was a rest day") is deliberately not
  in scope — the audit shape is forward-looking, and retroactive marks
  would interact with already-broken streaks in ways the forgiveness
  doc explicitly rules out ("Don't reinflate by retroactive completion").
- **Rest-week.** No multi-day primitive ships in this PR. The audit
  shape is per-day; multi-day rest framing is a Phase-3 concern.
- **Productive-day streak.** As noted above, rest day does not shield
  the productive-day strict streak. `FORGIVENESS_FIRST.md` § Open
  questions tracks the reframing decision — this doc inherits whatever
  resolution lands there.

## What this doc does not cover

- The migration shape (`MIGRATION_82_83`) — that's KDoc in
  `Migrations.kt` next to the migration itself.
- The Today-screen UI takeover internals — see `TodayScreen.kt`
  conditional and the
  [`RestDayBanner.kt`](../app/src/main/java/com/averycorp/prismtask/ui/screens/today/components/RestDayBanner.kt)
  component.
- The Settings → Pause Notifications quick toggle from audit § G4 —
  that's a separate PR. Rest day is the date primitive; pause is the
  time-window primitive. They cover different recovery shapes.
