# Medication time logging

PrismTask records two distinct moments for every medication tier-state and
mark write:

- **`intended_time`** — wall-clock the user *claims* they took the dose.
  Nullable. NULL means "the user hasn't backdated; treat `logged_at` as
  the de-facto intended time".
- **`logged_at`** — wall-clock when the database row physically landed.
  NOT NULL.

The split lets the app honestly distinguish "I just took this" from "I
took this 2 hours ago and only just opened the app", without forcing the
user to backdate every routine entry.

## Data model

The split lives in three places, in lockstep:

| Layer | Where | Notes |
| --- | --- | --- |
| Android | `medication_tier_states.intended_time` (nullable) + `logged_at` (NOT NULL) | Migration `MIGRATION_60_61` (Room DB v61). Backfills `logged_at = updated_at` on legacy rows so the column is queryable. |
| Web (Firestore) | `users/{uid}/medication_tier_states/{dateIso}__{slotKey}` doc gains `intendedTime` + `loggedAt` fields | Cross-device parity. Camel-cased on the wire to match the existing convention. |
| Backend | `medication_tier_states` SQLAlchemy table | Synced via `/sync/push` (Path 2 from Checkpoint 1). Timezone-aware (`TIMESTAMP WITH TIME ZONE`). |
| Backend | `medication_log_events` (audit) | Append-only. Every `/sync/push` touching a tier-state writes a row inside a SAVEPOINT (audit failures roll back independently and never block sync). Indexed on `(user_id, logged_at)` and `entity_cloud_id`. |

> **Historical note:** an earlier draft of this feature added a separate
> `medication_marks` table on Android (Room) and backend (SQLAlchemy +
> Alembic 019) for per-medication granularity. No production write path
> ever populated it — the per-medication intended_time ended up on
> `medication_tier_states` instead, with the design call documented at
> `MedicationViewModel.kt:132` ("the user edits them at slot
> granularity"). The orphan table was dropped in Room migration
> `MIGRATION_63_64` and Alembic revision **021**. See
> `docs/audits/PHASE_D_BUNDLE_AUDIT.md` Item 3.

Alembic head: revision **021** (`021_drop_medication_marks.py`).
Room DB version: **64**.

## User UX

Two triggers open the time-edit affordance:

| Platform | Trigger | UI |
| --- | --- | --- |
| Android | Long-press on the slot's tier chip | `MedicationTimeEditSheet` (Material 3 `ModalBottomSheet` with `TimePicker`). |
| Web — desktop | Right-click on the tier picker | `MedicationTimeEditModal` (`<input type="time">` inside the shared `Modal`). |
| Web — mobile | 500 ms touch-and-hold on the tier picker | Same modal as desktop. |

Save composes the picked HH:mm against today's local date (or the screen's
selected `dateIso` on web, supporting same-day editing of past days from
the date-shifted view). **Future times are capped to `now`** server-side
on both platforms — backdating only.

### Backlogged indicator

A small clock icon ("backlogged") appears on the tier row when
`intended_time` differs from `logged_at` by more than **60 s**. The
60 s tolerance avoids flicker for the trivial gap between user tap and
DB write. Logic:

- Android: `MedicationSlotTodayState.isBacklogged`
- Web: `isBacklogged(intendedTime, loggedAt)` in `backloggedHelpers.ts`

Both implementations are unit-tested with the same six edge cases
(null inputs, tolerance window, polarity).

## Audit log query

Support / debugging can fetch a user's audit history:

```
GET /api/v1/medications/log-events?since=<ISO-8601>&limit=<1..500>
Authorization: Bearer <jwt>
```

- Auth-scoped to the calling user — a JWT for user A cannot read user B's
  events.
- Returns events newest-first by `logged_at`.
- Includes `sync_received_at` (server-stamped) alongside the
  client-claimed `logged_at` so clock-skew issues are detectable.

## Troubleshooting

**Q: Why is `intended_time` NULL on rows from before this release?**
A: Honest: we don't know when the user actually took those doses. The
backfill copies `updated_at` into `logged_at` so every row is queryable,
but `intended_time` stays NULL. The app must display that as "no user
override" — not as 0 or epoch.

**Q: Why doesn't `setTierState` write `intended_time`?**
A: `intended_time` is a *user intention* about wall-clock. Tier changes
(auto-compute) must not clobber it. The dedicated write paths are
`MedicationSlotRepository.setTierStateIntendedTime` (Android) and
`setTierStateIntendedTime` (web).

**Q: A user reports a mismatch between their claimed time and the audit
log.**
A: Compare `intended_time` (client-claimed) vs `sync_received_at` (server
clock when the row arrived) — large gaps usually mean device-clock skew,
not application bugs.

**Q: Audit log is empty for a user known to use medications.**
A: PR1 wrote the audit hook into `/sync/push`. As of PR2, Android writes
medication entities to Firestore but **not** to `/sync/push` — the
parallel-sync path is intentionally out of PR2 scope. The audit log
will populate once a follow-up wires `BackendSyncService.pushChanges()`
to send medication entities to backend.

## Privacy / Data Safety

`medication_log_events` is server-side audit metadata covered by the
existing health-data disclosures. Client-claimed `intended_time` is
considered user-provided data; `sync_received_at` is server-observed.
Both are user-scoped and join only on `user_id` (no cross-user joins
possible).
