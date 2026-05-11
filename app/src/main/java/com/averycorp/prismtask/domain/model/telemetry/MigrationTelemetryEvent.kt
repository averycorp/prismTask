package com.averycorp.prismtask.domain.model.telemetry

/**
 * Closed event surface for Room migration telemetry.
 *
 * Every field here is a primitive or a known-safe summary string.
 * No row content, user identifiers, or free-text app data may ever
 * be added — see `MigrationTelemetryPiiTest` for the enforced grep
 * sentinel that rejects forbidden field shapes at unit-test time.
 *
 * `messageFirst120` is bounded because Room migration exceptions
 * surface SQL/constraint diagnostics, never row contents.
 */
sealed class MigrationTelemetryEvent {
    abstract val versionFrom: Int
    abstract val versionTo: Int

    data class Started(override val versionFrom: Int, override val versionTo: Int, val dbSizeBytes: Long) : MigrationTelemetryEvent()

    data class Completed(override val versionFrom: Int, override val versionTo: Int, val durationMs: Long, val cumulativeMs: Long) :
        MigrationTelemetryEvent()

    data class Failed(
        override val versionFrom: Int,
        override val versionTo: Int,
        val dbSizeBytes: Long,
        val exceptionClass: String,
        val messageFirst120: String,
        val lastCompletedStep: String?
    ) : MigrationTelemetryEvent()

    /**
     * Fires once per app launch when the installed schema is at or
     * above the v54 medication-top-level migration. Replaces the
     * earlier "dual_write_shim_active" framing — there is no live
     * shim; quarantine tables are one-time forensic snapshots.
     * `shimAgeDays` therefore measures days since v54 was applied,
     * not days the shim has been live.
     */
    data class PostV54Install(override val versionFrom: Int, override val versionTo: Int, val shimAgeDays: Long) : MigrationTelemetryEvent()
}
