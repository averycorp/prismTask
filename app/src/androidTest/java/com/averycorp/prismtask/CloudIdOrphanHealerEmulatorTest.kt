package com.averycorp.prismtask

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.CloudIdOrphanHealer
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-device out-of-band wipe + recovery scenario against the live
 * Firebase Emulator Suite wired by
 * `.github/workflows/android-integration.yml`, using the actual
 * Firestore SDK for all remote reads and writes.
 *
 * Gated by `Assume.assumeTrue(BuildConfig.USE_FIREBASE_EMULATOR)` so
 * the test is a no-op on default debug builds — fires only in the
 * integration-CI environment where the emulator is actually running.
 *
 * Each test uses a unique userId path (`emulator-healer-{ts}`) under
 * `users/{uid}/self_care_steps/` so parallel or back-to-back runs in
 * the same emulator instance don't share state. The emulator's
 * persistence resets on restart, so CI cleanup happens automatically
 * between workflow runs.
 *
 * Scope: exercises the orphan-healer enqueue logic against real
 * Firestore. `simulatePushForPending` here writes via `set()` to
 * model the original "re-create at the same cloud_id" recovery
 * narrative — this is intentionally NOT the post-fix `update()` path
 * exercised by [com.averycorp.prismtask.sync.scenarios.Test10ConcurrentDeleteTest],
 * which covers the production push-update conflict semantics
 * end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class CloudIdOrphanHealerEmulatorTest {
    private lateinit var deviceA: Device
    private lateinit var deviceB: Device
    private lateinit var firestore: FirebaseFirestore
    private lateinit var userId: String

    @Before
    fun setUp() {
        assumeTrue(
            "Requires USE_FIREBASE_EMULATOR=true — skipped on default debug builds.",
            BuildConfig.USE_FIREBASE_EMULATOR
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FirebaseApp.initializeApp(context)
        // `useEmulator` throws if the SDK already ran a query; the
        // FirebaseEmulatorSmokeTest sharing this process may have
        // already routed it. Tolerating that is fine — the emulator
        // target is the same regardless.
        try {
            FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings
                    .Builder()
                    .setPersistenceEnabled(false)
                    .build()
        } catch (_: IllegalStateException) {
            // Already routed — fine.
        }
        try {
            FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, AUTH_PORT)
        } catch (_: IllegalStateException) {
            // Already routed — fine.
        }
        // firestore.rules requires request.auth != null; sign in to the Auth
        // emulator so the real-SDK writes below aren't rejected with
        // PERMISSION_DENIED.
        runBlocking {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
        }
        firestore = FirebaseFirestore.getInstance()
        userId = "emulator-healer-${System.currentTimeMillis()}"
        deviceA = Device.build(context, userId, firestore)
        deviceB = Device.build(context, userId, firestore)
    }

    @After
    fun tearDown() {
        deviceA.close()
        deviceB.close()
    }

    // runBlocking (not runTest) — the .await() calls wait on real Firestore
    // I/O, which progresses in real time; runTest's virtual clock would skip
    // to the withTimeout deadline immediately.
    @Test
    fun outOfBandWipe_realFirestore_deviceAHealsAndDeviceBNoOps() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val stepCloudIds = (1..5).map { "fs-step-$it" }

            // ── Phase 1: seed Firestore + both devices' local Room ──
            stepCloudIds.forEach { cid ->
                userCollection("self_care_steps")
                    .document(cid)
                    .set(mapOf("probe" to "seed"))
                    .await()
            }
            deviceA.seedStepsWithCloudIds(stepCloudIds)
            deviceB.seedStepsWithCloudIds(stepCloudIds)
            assertEquals(5, fetchRemoteStepIds().size)

            // ── Phase 2: out-of-band wipe against real Firestore ──
            fetchRemoteStepIds().forEach { cid ->
                userCollection("self_care_steps").document(cid).delete().await()
            }
            assertEquals(
                "Firestore collection wiped against the live emulator",
                0,
                fetchRemoteStepIds().size
            )

            // ── Phase 3: device A's healer queries real Firestore ──
            deviceA.runHealer()
            val pending = deviceA.pendingActions("self_care_step")
            assertEquals("A queued 5 real-Firestore orphan pushes", 5, pending.size)
            pending.forEach { meta ->
                assertEquals("update", meta.pendingAction)
                assertTrue(
                    "Pending push reuses original cloud_id (no churn for other devices)",
                    meta.cloudId in stepCloudIds
                )
            }

            // ── Phase 4: simulate A's reactive push against real Firestore ──
            deviceA.simulatePushForPending()
            assertEquals(
                "All 5 docs re-created at their original cloud_ids",
                stepCloudIds.toSet(),
                fetchRemoteStepIds()
            )

            // ── Phase 5: device B's healer against real Firestore ──
            deviceB.runHealer()
            assertEquals(
                "Device B sees docs in Firestore → no orphan recovery",
                0,
                deviceB.pendingActions("self_care_step").size
            )
        }
    }

    @Test
    fun divergentOrphans_realFirestoreMergesViaPush() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val aCids = listOf("a-1", "a-2", "a-3")
            val bCids = listOf("b-1", "b-2")
            deviceA.seedStepsWithCloudIds(aCids)
            deviceB.seedStepsWithCloudIds(bCids)
            // Firestore starts empty — all 5 local rows are orphans.

            deviceA.runHealer()
            deviceB.runHealer()
            deviceA.simulatePushForPending()
            deviceB.simulatePushForPending()

            assertEquals(
                "Firestore now has the union of both devices' cloud_ids",
                (aCids + bCids).toSet(),
                fetchRemoteStepIds()
            )
        }
    }

    private fun userCollection(name: String) =
        firestore.collection("users").document(userId).collection(name)

    private suspend fun fetchRemoteStepIds(): Set<String> =
        userCollection("self_care_steps").get().await().documents.map { it.id }.toSet()

    /**
     * Per-device test harness. Each device has its own in-memory Room
     * DB + its own [CloudIdOrphanHealer], but both share the same
     * `userId` — so the healer's real default fetcher queries the same
     * Firestore namespace, modeling two devices signed into the same
     * account.
     */
    private class Device private constructor(
        private val database: PrismTaskDatabase,
        private val healer: CloudIdOrphanHealer,
        private val userId: String,
        private val firestore: FirebaseFirestore
    ) {
        suspend fun seedStepsWithCloudIds(cloudIds: List<String>) {
            val stepDao = database.selfCareDao()
            val metaDao = database.syncMetadataDao()
            cloudIds.forEachIndexed { i, cid ->
                val localId = stepDao.insertStep(
                    SelfCareStepEntity(
                        stepId = "step-$i-${System.identityHashCode(this)}",
                        routineType = "morning",
                        label = "Step $i",
                        duration = "30s",
                        tier = "full",
                        note = "",
                        phase = "cleanse",
                        timeOfDay = "morning",
                        cloudId = cid
                    )
                )
                metaDao.upsert(
                    SyncMetadataEntity(
                        localId = localId,
                        entityType = "self_care_step",
                        cloudId = cid,
                        lastSyncedAt = 1L
                    )
                )
            }
        }

        suspend fun runHealer() {
            // Default fetcher hits real Firestore via the test user's
            // signed-in uid (provided by the mocked AuthManager).
            healer.healOrphans()
        }

        suspend fun pendingActions(entityType: String): List<SyncMetadataEntity> =
            database.syncMetadataDao()
                .getPendingActions()
                .filter { it.entityType == entityType }

        suspend fun simulatePushForPending() {
            val metaDao = database.syncMetadataDao()
            val pending = metaDao.getPendingActions()
                .filter { it.entityType == "self_care_step" }
            for (meta in pending) {
                firestore
                    .collection("users").document(userId)
                    .collection("self_care_steps").document(meta.cloudId)
                    .set(mapOf("probe" to "repushed", "ts" to System.currentTimeMillis()))
                    .await()
                metaDao.clearPendingAction(meta.localId, meta.entityType)
            }
        }

        fun close() {
            database.close()
        }

        companion object {
            fun build(
                context: android.content.Context,
                userId: String,
                firestore: FirebaseFirestore
            ): Device {
                val db = Room
                    .inMemoryDatabaseBuilder(context, PrismTaskDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                val authManager = mockk<AuthManager>().apply {
                    every { this@apply.userId } returns userId
                }
                val logger = mockk<PrismSyncLogger>(relaxed = true)
                val healer = CloudIdOrphanHealer(
                    authManager = authManager,
                    syncMetadataDao = db.syncMetadataDao(),
                    selfCareDao = db.selfCareDao(),
                    schoolworkDao = db.schoolworkDao(),
                    taskDao = db.taskDao(),
                    projectDao = db.projectDao(),
                    tagDao = db.tagDao(),
                    habitDao = db.habitDao(),
                    habitCompletionDao = db.habitCompletionDao(),
                    habitLogDao = db.habitLogDao(),
                    taskCompletionDao = db.taskCompletionDao(),
                    taskTemplateDao = db.taskTemplateDao(),
                    milestoneDao = db.milestoneDao(),
                    notificationProfileDao = db.notificationProfileDao(),
                    customSoundDao = db.customSoundDao(),
                    savedFilterDao = db.savedFilterDao(),
                    nlpShortcutDao = db.nlpShortcutDao(),
                    habitTemplateDao = db.habitTemplateDao(),
                    projectTemplateDao = db.projectTemplateDao(),
                    boundaryRuleDao = db.boundaryRuleDao(),
                    checkInLogDao = db.checkInLogDao(),
                    moodEnergyLogDao = db.moodEnergyLogDao(),
                    focusReleaseLogDao = db.focusReleaseLogDao(),
                    medicationRefillDao = db.medicationRefillDao(),
                    weeklyReviewDao = db.weeklyReviewDao(),
                    dailyEssentialSlotCompletionDao = db.dailyEssentialSlotCompletionDao(),
                    attachmentDao = db.attachmentDao(),
                    medicationDao = db.medicationDao(),
                    medicationDoseDao = db.medicationDoseDao(),
                    medicationSlotDao = db.medicationSlotDao(),
                    medicationSlotOverrideDao = db.medicationSlotOverrideDao(),
                    medicationTierStateDao = db.medicationTierStateDao(),
                    projectPhaseDao = db.projectPhaseDao(),
                    projectRiskDao = db.projectRiskDao(),
                    taskDependencyDao = db.taskDependencyDao(),
                    externalAnchorDao = db.externalAnchorDao(),
                    logger = logger
                )
                return Device(db, healer, userId, firestore)
            }
        }
    }

    companion object {
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val FIRESTORE_PORT = 8080
        private const val AUTH_PORT = 9099
        private const val TEST_TIMEOUT_MS = 60_000L
    }
}
