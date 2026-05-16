package com.averycorp.prismtask.sync.fuzz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM unit tests for [SyncFuzzGenerator] — the regression-gate that
 * the generator stays deterministic over time. Runs without the Firebase
 * Emulator so it gates every PR's `testDebugUnitTest` instead of only the
 * integration workflow.
 *
 * Seed stability is the contract that lets a CI failure be replayed locally —
 * if this contract breaks, every fuzz scenario's "replay seed=N" instruction
 * is wrong.
 */
class SyncFuzzGeneratorTest {

    @Test
    fun sameSeed_producesIdenticalSequence() {
        val a = SyncFuzzGenerator(Random(SEED)).generate(LENGTH)
        val b = SyncFuzzGenerator(Random(SEED)).generate(LENGTH)
        assertEquals("Seed=$SEED must reproduce identical op sequences", a, b)
    }

    @Test
    fun differentSeeds_produceDifferentSequences() {
        val a = SyncFuzzGenerator(Random(SEED)).generate(LENGTH)
        val b = SyncFuzzGenerator(Random(SEED + 1)).generate(LENGTH)
        assertNotEquals(
            "Seeds $SEED and ${SEED + 1} should not collide on a 50-op sequence",
            a,
            b
        )
    }

    @Test
    fun firstOp_isAlwaysInsert_whenLiveSetEmpty() {
        repeat(20) { offset ->
            val seq = SyncFuzzGenerator(Random(SEED + offset)).generate(LENGTH)
            assertEquals(
                "Op[0] must be INSERT (live key set empty) for seed=${SEED + offset}",
                SyncFuzzOpType.INSERT,
                seq.first().type
            )
        }
    }

    @Test
    fun updateAndDelete_neverReferenceUnknownKey() {
        val seq = SyncFuzzGenerator(Random(SEED)).generate(LENGTH * 4)
        val live = mutableSetOf<String>()
        seq.forEachIndexed { i, op ->
            when (op.type) {
                SyncFuzzOpType.INSERT -> {
                    assertTrue(
                        "INSERT op[$i] used a key already live: ${op.key}",
                        op.key !in live
                    )
                    live.add(op.key)
                }
                SyncFuzzOpType.UPDATE -> assertTrue(
                    "UPDATE op[$i] referenced unknown key: ${op.key}",
                    op.key in live
                )
                SyncFuzzOpType.DELETE -> {
                    assertTrue(
                        "DELETE op[$i] referenced unknown key: ${op.key}",
                        op.key in live
                    )
                    live.remove(op.key)
                }
            }
        }
    }

    @Test
    fun zeroLength_returnsEmpty() {
        assertEquals(emptyList<SyncFuzzOp>(), SyncFuzzGenerator(Random(SEED)).generate(0))
    }

    @Test
    fun deviceFilter_restrictsOpsToConfiguredDevices() {
        val devices = setOf(SyncFuzzDevice.B)
        val seq = SyncFuzzGenerator(Random(SEED), devices = devices).generate(LENGTH)
        assertTrue(
            "All ops should target device B; got ${seq.map { it.device }.toSet()}",
            seq.all { it.device == SyncFuzzDevice.B }
        )
    }

    @Test
    fun opTypeFilter_restrictsOpsToConfiguredTypes() {
        val types = setOf(SyncFuzzOpType.INSERT)
        val seq = SyncFuzzGenerator(Random(SEED), opTypes = types).generate(LENGTH)
        assertTrue(
            "Insert-only generator must produce INSERT ops; got ${seq.map { it.type }.toSet()}",
            seq.all { it.type == SyncFuzzOpType.INSERT }
        )
    }

    companion object {
        private const val SEED = 42L
        private const val LENGTH = 50
    }
}
