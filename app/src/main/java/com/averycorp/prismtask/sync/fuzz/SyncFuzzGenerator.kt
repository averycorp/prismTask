package com.averycorp.prismtask.sync.fuzz

import androidx.annotation.VisibleForTesting
import kotlin.random.Random

/**
 * Deterministic op-sequence generator for sync state-machine fuzzing.
 *
 * Produces a list of [SyncFuzzOp] from a seeded [Random]. Re-running with the
 * same seed reproduces the exact same sequence — required so a CI failure
 * can be replayed locally by pinning the seed in the failing test.
 *
 * Per the audit (`docs/audits/AUTOMATED_EDGE_CASE_TESTING_AUDIT.md`,
 * Tier A1), the generator is hand-rolled rather than using Kotest-property
 * because Firestore-backed harness I/O is incompatible with Kotest's
 * shrinking pass. The harness's `cleanupFirestoreUser()` + `clearAllTables()`
 * lifecycle already provides "minimum failing case" via fixed-seed replay.
 *
 * Generators are intentionally narrow — each [SyncFuzzGenerator] instance
 * targets a single entity type (Task, Habit, Project, Medication, …).
 * Cross-entity scenarios compose multiple generators in the same scope.
 *
 * Lives in `app/src/main/` rather than `androidTest/` because the JVM
 * regression-gate test [SyncFuzzGeneratorTest] is under `app/src/test/`,
 * and the `test/` and `androidTest/` source sets are isolated — neither
 * can see the other's classpath. Putting this under `main/` lets both
 * test source sets reference it. Debug-APK dex cost: ~1–2 KB; release
 * builds strip via R8 since no production code references it.
 */
@VisibleForTesting
class SyncFuzzGenerator(
    private val random: Random,
    private val opTypes: Set<SyncFuzzOpType> = SyncFuzzOpType.entries.toSet(),
    private val devices: Set<SyncFuzzDevice> = setOf(SyncFuzzDevice.A)
) {
    /**
     * Generate a sequence of [length] ops. Insert ops produce fresh local
     * keys (`fuzzKey-N`). Update / Delete ops reference one of the keys
     * already produced by an earlier Insert op in the same sequence —
     * picking from the live key set so generated sequences never reference
     * a key that doesn't exist locally.
     *
     * If the live key set is empty when an Update/Delete is rolled, the op
     * is replaced with an Insert. This preserves the requested [length]
     * while keeping every op semantically valid against the running state.
     */
    fun generate(length: Int): List<SyncFuzzOp> {
        require(length >= 0) { "length must be non-negative; got $length" }
        require(opTypes.isNotEmpty()) { "opTypes must not be empty" }
        require(devices.isNotEmpty()) { "devices must not be empty" }
        val ops = mutableListOf<SyncFuzzOp>()
        val liveKeys = mutableSetOf<String>()
        var nextKey = 0
        repeat(length) {
            val rolledType = opTypes.random(random)
            val device = devices.random(random)
            val effectiveType = if (
                (rolledType == SyncFuzzOpType.UPDATE || rolledType == SyncFuzzOpType.DELETE) &&
                liveKeys.isEmpty()
            ) {
                SyncFuzzOpType.INSERT
            } else {
                rolledType
            }
            val key = when (effectiveType) {
                SyncFuzzOpType.INSERT -> {
                    val k = "fuzzKey-${nextKey++}"
                    liveKeys.add(k)
                    k
                }
                SyncFuzzOpType.UPDATE -> liveKeys.random(random)
                SyncFuzzOpType.DELETE -> liveKeys.random(random).also { liveKeys.remove(it) }
            }
            ops.add(SyncFuzzOp(type = effectiveType, device = device, key = key))
        }
        return ops
    }
}

enum class SyncFuzzOpType { INSERT, UPDATE, DELETE }

enum class SyncFuzzDevice { A, B }

/**
 * One step in a fuzz sequence. [key] is a generator-stable identifier
 * (not a Room id, not a Firestore cloud_id) — scenarios map it onto whatever
 * concrete entity field carries identity for their domain (e.g. Task title,
 * Medication name).
 */
data class SyncFuzzOp(val type: SyncFuzzOpType, val device: SyncFuzzDevice, val key: String)
