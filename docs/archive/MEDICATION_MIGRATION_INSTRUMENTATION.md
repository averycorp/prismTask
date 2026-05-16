# Medication Migration Instrumentation

**Status:** ships in v1.6.0 (Phase B safety net for the closed beta — target 2026-05-14).
**Scope:** every Room migration emits start/complete/fail telemetry; v54+ installs emit a once-per-launch shim-age event; the backend's medication audit-emit savepoint emits a structured failure metric.

Pairs with:
- `app/src/main/java/com/averycorp/prismtask/data/diagnostics/MigrationInstrumentor.kt` — the wrapping instrumentor
- `app/src/main/java/com/averycorp/prismtask/data/local/database/InstrumentedMigrations.kt` — the factory wired into `DatabaseModule`
- `app/src/main/java/com/averycorp/prismtask/domain/model/telemetry/MigrationTelemetryEvent.kt` — the closed payload surface
- `app/src/test/java/com/averycorp/prismtask/diagnostics/MigrationTelemetryPiiTest.kt` — the JVM PII grep sentinel
- `backend/app/routers/sync.py` — the audit-emit failure counter
- `docs/MEDICATION_CROSS_DEVICE_MANUAL_RUNBOOK.md` — the manual companion to the automated cross-device tests

## Event surface

### Firebase Analytics events (client-side)

| Event | Parameters | Fires when |
|---|---|---|
| `db_migration_started` | `version_from: Int`, `version_to: Int`, `db_size_bytes: Long` | A `Migration.migrate()` is about to run |
| `db_migration_completed` | `version_from: Int`, `version_to: Int`, `duration_ms: Long`, `cumulative_ms: Long` | The same `migrate()` returned without throwing |
| `db_migration_failed` | `version_from: Int`, `version_to: Int`, `db_size_bytes: Long`, `exception_class: String`, `last_completed_step: String` | The same `migrate()` threw — also recorded as a Crashlytics non-fatal |
| `db_post_v54_install` | `version_from: Int`, `version_to: Int`, `shim_age_days: Long` | Once per app launch, when current schema is ≥ 54. `shim_age_days` is days since the v54 upgrade was applied; `0` on the launch that backfills the timestamp |

### Crashlytics custom keys (client-side)

The instrumentor sets the following keys on every event so a non-fatal crash report carries the active-migration context:

| Key | Set on | Purpose |
|---|---|---|
| `mig_active` | `Started` | `from->to` of the currently-running migration |
| `mig_started_at_ms` | `Started` | `System.currentTimeMillis()` at start |
| `mig_last_completed` | `Completed` | `from->to` of the most-recent successful migration |
| `mig_last_duration_ms` | `Completed` | Duration of the last successful migration |
| `mig_failed_from` / `mig_failed_to` / `mig_db_size_bytes` / `mig_exception_class` / `mig_last_completed_step` | `Failed` | Captured at the moment of failure for the non-fatal report |
| `mig_post_v54_age_days` | `PostV54Install` | Same `shim_age_days` value as the Analytics event |

### Backend log fields (server-side)

The `_emit_audit_events` savepoint failure path at `backend/app/routers/sync.py` writes:

| Log field | Type | Meaning |
|---|---|---|
| `audit_emit_failed` | `bool` (always `true` when set) | Filter key for log-based metrics |
| `entity_type` | `"tier_state"` or `"mark"` | Which audit path lost an event |
| `entity_cloud_id` | string | The medication cloud_id whose audit failed |
| `exception_class` | string | Class name of the SQL exception |
| `audit_emit_failures_total` | int | Per-entity-type counter, process-lifetime |

The same per-entity-type counter is exposed via `app.routers.sync.audit_emit_failures_total: dict[str, int]` for any future `/metrics`-style endpoint.

## What is NOT logged (PII contract)

- **No medication names.** The `MigrationTelemetryEvent` sealed class declares only `Int` / `Long` / `String` fields whose values are version pairs, durations, exception classes, byte counts, day counts, or class-name strings.
- **No user identifiers.** Crashlytics already carries the user UID via `MainActivity.kt:740`'s `setUserId` call; migration events never re-encode it.
- **No SQL payloads.** Migration exceptions surface SQL/constraint diagnostics, never row content. `messageFirst120` truncates at 120 chars to bound payload size, and `MigrationTelemetryPiiTest` greps the source for forbidden tokens (`medication_name`, `dose`, `user_id`, `email`, `title`, `notes`, `label`) inside `setCustomKey` / `putString` / `logEvent` calls and fails the build if any appear.

The PII contract is enforced two ways:
1. **Type-shape:** the sealed class allowlist (Int / Long / String / String?) blocks shape-level violations.
2. **Grep sentinel:** `MigrationTelemetryPiiTest` runs in `testDebugUnitTest` on every PR.

If a future change needs to add a new event field, the field must be added to `MigrationTelemetryEvent` first (via the sealed class) — there is no untyped string-shaped API on the instrumentor.

## Reading the data in Firebase Console

### Migration health (last 7 days)

1. Open Firebase Console → Analytics → Events
2. Filter to the four `db_migration_*` events
3. Plot `db_migration_failed.count` vs `db_migration_completed.count` over time. The ratio is the migration-failure rate.
4. Drill into `db_migration_failed` to see which `version_from / version_to` pair fails — the audit identified `53→54`, `58→59`, `59→60`, and `62→63` as the highest-risk pairs.

### Shim age distribution

1. Filter to `db_post_v54_install`
2. Group by `shim_age_days`
3. The histogram shows the install-base age distribution. Beta dashboards plot `count where shim_age_days = 0` to track new upgrades; production dashboards plot the long tail.

### Crashlytics non-fatals

1. Open Crashlytics → Issues → filter by exception class `MigrationFailedException` (the wrapped class set in `MigrationInstrumentor.recordException`)
2. Each issue's stack carries the original cause underneath
3. The Crashlytics custom keys (`mig_failed_from`, `mig_db_size_bytes`, `mig_last_completed_step`) appear in the issue detail view

### Backend audit-emit failures

1. Cloud Logging → log-based metric on `jsonPayload.audit_emit_failed = true`
2. Group by `jsonPayload.entity_type` to see which audit path is dropping events
3. Plot alongside the client-side `db_migration_failed.count` — silent loss on either side is a beta-blocker signal

## Operational checklist for the closed beta

- [ ] **3-5 days before May 14:** dogfood on a small Pro group; confirm `db_migration_started` + `db_migration_completed` fire on real device cold boots.
- [ ] **Migration failure rate baseline:** establish the rate during dogfood. If `db_migration_failed` events appear at all during baseline, that is a **launch-blocker** — fix before D start.
- [ ] **Manual runbook execution:** run `docs/MEDICATION_CROSS_DEVICE_MANUAL_RUNBOOK.md` Scenarios B + C across two physical devices on the candidate APK. Both must pass.
- [ ] **Audit emit failure rate baseline:** confirm the backend `audit_emit_failures_total` stays at 0 across normal sync traffic.
- [ ] **Beta opens:** monitor the four dashboards listed above daily for the first week.

## Out of scope (Phase F.0 follow-ups)

- **PII review of medication-name egress to Anthropic.** `backend/app/services/ai_productivity.py:777,842` ships medication names to Claude Haiku. This is a separate Data Safety / privacy concern surfaced by the Phase B audit; not part of the migration safety net but blocks the closed beta on a different axis.
- **Pre-v47 migration tests.** Migrations 10→11, 11→12, 19→20, 34→35, 36→37, 45→46 have no test coverage. They are pre-test-era and the source-of-truth tables they create have been stable for years; the safety net targets the high-risk recent migrations instead.
- **Per-table row counts in `db_migration_completed`.** Adding row counts requires editing 56 migration bodies; cost greatly exceeds value for v1 of the safety net. Defer to v2 if `db_size_bytes` proves insufficient.
- **`SyncTestHarness` paired-Room-different-versions extension.** The harness can pair `FirebaseApp` instances but not Room schemas; supporting cross-version automated tests requires a non-trivial harness refactor. The manual runbook covers the gap.
- **Backend `medication_log_events` retention/purge job.** The audit log table is append-only with no TTL today; either add a purger or document indefinite retention in Data Safety.
