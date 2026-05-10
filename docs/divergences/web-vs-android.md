# Web ↔ Android Divergences (intentional)

This doc enumerates surfaces where web and Android **intentionally diverge**
in architecture or feature shape. These are not bugs and are not parity gaps
to close — they are documented design decisions.

When proposing parity work that touches one of these surfaces, treat the
divergence as load-bearing: a parity port requires explicit re-justification,
not a default "match Android" assumption.

---

## Sync architecture (D8 — formalized 2026-05-10)

### Conflict resolution

* **Android:** Last-write-wins via `pushUpdate` delete-wins (v1.6.0 sync
  hardening). `sync_metadata` table tracks last-sync timestamps + dirty flags
  per entity. Local Room DB is the working store; Firestore is the
  cross-device mirror.
* **Web:** Last-write-wins **at apply time**. Firestore is the source of
  truth on web (not a mirror). Optimistic local state is overwritten by the
  remote snapshot. There is no `sync_metadata` table because there is no
  local persistent store to track sync state for. The strategy is documented
  inline at `web/src/hooks/useFirestoreSync.ts:22-27`.
* **Why divergent:** Web's deployment model (browser tab, no offline-first
  requirement, Firestore SDK handles online/offline transparently) makes
  Firestore-as-source-of-truth the simpler shape. Android's model (background
  sync, offline-by-default, multi-device) requires a local working store.
  The two shapes converge on the same observable behavior (LWW) at different
  architectural layers.
* **Cloud-id dedup parity:** Already shipped via PR #1121 (canonical-row
  dedup Pattern A — deterministic doc id + setDoc merge). This is the
  wire-level dedup that closes the only meaningful behavioral gap.
* **Re-trigger for parity port:** Only if web introduces optimistic-local-
  write-then-sync (rather than the current Firestore-direct-write pattern),
  or if Android's conflict-resolution strategy changes to something other
  than LWW (e.g., CRDT, three-way merge).

### IndexedDB / local persistence

* **Android:** Room database with versioned migrations. Currently at
  `CURRENT_DB_VERSION` per `data/local/database/Migrations.kt`. Migrations
  are mandatory for any schema change.
* **Web:** **No local persistent store.** State lives in memory in Zustand
  stores, populated from Firestore real-time snapshots. No IndexedDB usage,
  no schema migration framework, no local versioning.
* **Why divergent:** Web's source-of-truth model means there is nothing to
  migrate. Each session reconstructs state from Firestore on load.
* **Re-trigger for parity port:** Web introduces an offline-first feature,
  draft-locally-then-push pattern, or Firestore read-cost optimization
  requiring an IDB read-through cache.

### `sync_metadata` table

* **Android:** Tracks per-entity last-sync timestamp + dirty flags.
* **Web:** Does not exist. Coupled to the IDB divergence above —
  `sync_metadata` only makes sense when there is a local store with state to
  track.
* **Re-trigger for parity port:** Coupled to IDB re-trigger above. The two
  re-open together or not at all.

---

## Pomodoro+ session sync (D8 — formalized 2026-05-10)

* **Android:** Pomodoro+ session entity with Room persistence and Firestore
  cross-device sync. Sessions are user-writable (start, pause, complete) and
  participate in the cross-device sync pipeline.
* **Web:** Pomodoro+ on web is an **AI-coach surface, not a sync surface.**
  `web/src/features/pomodoro/PomodoroScreen.tsx` reads sessions via
  `aiApi.getPomodoroSessions()` from `/api/v1/ai/pomodoro` (an AI endpoint).
  There is no `PomodoroSessionEntity` mirror, no Firestore mapper, no
  Zustand store for sessions. The 18 entity mappers under
  `web/src/api/firestore/` cover task / project / habit / medication / mood
  / check-in / boundary / focus-release surfaces — but not Pomodoro.
* **Why divergent:** Pomodoro+ on web is presented through the AI coach
  (`PomodoroCoachPanel`) as a recommendation surface, not a tracking
  surface. Tracking happens on Android.
* **Re-trigger for parity port:** Web Pomodoro screen needs to **write**
  session data (e.g., users want to end a session on web that started on
  Android), or backend `pomodoro_session` table is added for analytics
  reasons unrelated to the parity port (becoming a new sync surface that
  web can opportunistically join), or Phase G sweep elevates Pomodoro+ to
  a tier-A web parity slice.

---

## Parity ≠ wholesale code replication

Memory #26 framing applies: **web sync is structurally thinner than
Android by design.** Bringing it to behavioral parity is approximately
3-4 weeks of focused work and is not currently scoped. When parity ports
do land, they should reuse the existing web patterns (Firestore mappers
under `web/src/api/firestore/*.ts`, Zustand stores, real-time snapshot
listeners wired through `useFirestoreSync.ts`) — not replicate the
Android Room + WorkManager + SyncService architecture wholesale.
