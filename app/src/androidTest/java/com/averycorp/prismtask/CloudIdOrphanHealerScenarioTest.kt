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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scenario-level test for [CloudIdOrphanHealer] covering the
 * out-of-band Firestore wipe + recovery flow end-to-end. Simulates:
 *
 *  1. Steady state: local rows match Firestore, healer is a no-op.
 *  2. Out-of-band wipe: Firestore loses all docs; healer enqueues
 *     pending updates for every local row carrying a stale cloud_id.
 *  3. Simulated push completion: `clearPendingAction` on each, and the
 *     fake Firestore fetcher now "sees" the re-pushed docs.
 *  4. Post-push healer: no-op again.
 *
 * Firestore is not used — the test supplies its own
 * [CloudIdOrphanHealer.RemoteIdFetcher] whose returned set changes between
 * phases to model Firestore state transitions.
 */
@RunWith(AndroidJUnit4::class)
class CloudIdOrphanHealerScenarioTest {
    private lateinit var database: PrismTaskDatabase
    private lateinit var healer: CloudIdOrphanHealer
    private lateinit var authManager: AuthManager
    private lateinit var logger: PrismSyncLogger

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room
            .inMemoryDatabaseBuilder(context, PrismTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authManager = mockk()
        every { authManager.userId } returns "test-user"

        logger = mockk(relaxed = true)

        healer = CloudIdOrphanHealer(
            authManager = authManager,
            syncMetadataDao = database.syncMetadataDao(),
            selfCareDao = database.selfCareDao(),
            schoolworkDao = database.schoolworkDao(),
            taskDao = database.taskDao(),
            projectDao = database.projectDao(),
            tagDao = database.tagDao(),
            habitDao = database.habitDao(),
            habitCompletionDao = database.habitCompletionDao(),
            habitLogDao = database.habitLogDao(),
            taskCompletionDao = database.taskCompletionDao(),
            taskTemplateDao = database.taskTemplateDao(),
            milestoneDao = database.milestoneDao(),
            notificationProfileDao = database.notificationProfileDao(),
            customSoundDao = database.customSoundDao(),
            savedFilterDao = database.savedFilterDao(),
            nlpShortcutDao = database.nlpShortcutDao(),
            habitTemplateDao = database.habitTemplateDao(),
            projectTemplateDao = database.projectTemplateDao(),
            boundaryRuleDao = database.boundaryRuleDao(),
            checkInLogDao = database.checkInLogDao(),
            moodEnergyLogDao = database.moodEnergyLogDao(),
            focusReleaseLogDao = database.focusReleaseLogDao(),
            medicationRefillDao = database.medicationRefillDao(),
            weeklyReviewDao = database.weeklyReviewDao(),
            dailyEssentialSlotCompletionDao = database.dailyEssentialSlotCompletionDao(),
            attachmentDao = database.attachmentDao(),
            medicationDao = database.medicationDao(),
            medicationDoseDao = database.medicationDoseDao(),
            medicationSlotDao = database.medicationSlotDao(),
            medicationSlotOverrideDao = database.medicationSlotOverrideDao(),
            medicationTierStateDao = database.medicationTierStateDao(),
            projectPhaseDao = database.projectPhaseDao(),
            projectRiskDao = database.projectRiskDao(),
            taskDependencyDao = database.taskDependencyDao(),
            externalAnchorDao = database.externalAnchorDao(),
            logger = logger
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun outOfBandWipeAndRecovery_fullScenario() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()

        // ── Phase 1: steady state. 5 local rows, 5 matching Firestore docs. ──
        val localIds = (1..5).map { i ->
            val id = stepDao.insertStep(
                SelfCareStepEntity(
                    stepId = "step$i",
                    routineType = "morning",
                    label = "Step $i",
                    duration = "30s",
                    tier = "full",
                    note = "",
                    phase = "cleanse",
                    timeOfDay = "morning",
                    cloudId = "firestore-doc-$i"
                )
            )
            metaDao.upsert(
                SyncMetadataEntity(
                    localId = id,
                    entityType = "self_care_step",
                    cloudId = "firestore-doc-$i",
                    lastSyncedAt = 1L
                )
            )
            id
        }
        val remoteState =
            mutableSetOf("firestore-doc-1", "firestore-doc-2", "firestore-doc-3", "firestore-doc-4", "firestore-doc-5")

        healer.healOrphans(fetcher = { collection ->
            if (collection == "self_care_steps") remoteState.toSet() else emptySet()
        })

        localIds.forEach { id ->
            assertNull(
                "steady state: no pending actions should appear",
                metaDao.get(id, "self_care_step")!!.pendingAction
            )
        }

        // ── Phase 2: out-of-band Firestore wipe. All docs gone. ──
        remoteState.clear()

        healer.healOrphans(fetcher = { collection ->
            if (collection == "self_care_steps") remoteState.toSet() else emptySet()
        })

        localIds.forEach { id ->
            val meta = metaDao.get(id, "self_care_step")!!
            assertEquals("all 5 rows now pending update", "update", meta.pendingAction)
            assertTrue(
                "cloud_id preserved across healer's upsert",
                meta.cloudId.startsWith("firestore-doc-")
            )
        }
        assertEquals(
            "all 5 orphans in the push queue",
            5,
            metaDao.getPendingActions().count { it.entityType == "self_care_step" }
        )

        // ── Phase 3: simulate push completion. Each pushed row gets its
        // cloud_id "appear" in Firestore (via remoteState) and its pending
        // action cleared. Mirrors what pushLocalChanges + Firestore's set()
        // would do after the reactive push observer fires. ──
        for (id in localIds) {
            val meta = metaDao.get(id, "self_care_step")!!
            remoteState.add(meta.cloudId)
            metaDao.clearPendingAction(id, "self_care_step")
        }

        // ── Phase 4: post-push healer run. Firestore state now matches local,
        // so the healer must be a no-op again. ──
        healer.healOrphans(fetcher = { collection ->
            if (collection == "self_care_steps") remoteState.toSet() else emptySet()
        })

        localIds.forEach { id ->
            assertNull(
                "post-recovery: pending actions should stay cleared",
                metaDao.get(id, "self_care_step")!!.pendingAction
            )
        }
    }

    @Test
    fun partialWipe_healsOnlyMissingRows() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()

        val survivorId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s1",
                routineType = "morning",
                label = "Step 1",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = "cid-survivor"
            )
        )
        val orphanId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s2",
                routineType = "morning",
                label = "Step 2",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = "cid-orphan"
            )
        )

        healer.healOrphans(fetcher = { collection ->
            if (collection == "self_care_steps") setOf("cid-survivor") else emptySet()
        })

        assertNull(
            "survivor row stays untouched",
            metaDao.get(survivorId, "self_care_step")
        )
        val orphanMeta = metaDao.get(orphanId, "self_care_step")
        assertNotNull("orphan row enqueued for update", orphanMeta)
        assertEquals("update", orphanMeta!!.pendingAction)
        assertEquals("cid-orphan", orphanMeta.cloudId)
    }

    @Test
    fun healOrphans_upsertDoesNotClobberLastSyncedAt_onlyBumpsForward() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s1",
                routineType = "morning",
                label = "Step 1",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = "cid-1"
            )
        )
        metaDao.upsert(
            SyncMetadataEntity(
                localId = stepId,
                entityType = "self_care_step",
                cloudId = "cid-1",
                lastSyncedAt = 100L,
                retryCount = 5
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        val meta = metaDao.get(stepId, "self_care_step")!!
        assertEquals("cloud_id preserved", "cid-1", meta.cloudId)
        assertEquals("pendingAction set to update", "update", meta.pendingAction)
        assertTrue(
            "lastSyncedAt bumped forward to healer's wall-clock time",
            meta.lastSyncedAt >= 100L
        )
        // Note: the healer's upsert uses SyncMetadataEntity's default retryCount=0,
        // which resets the prior 5. Documented as intended behavior in the spec —
        // an orphan detection event is a good reason to restart retry budget.
        assertEquals("retry_count reset on healer re-enqueue", 0, meta.retryCount)
    }

    @Test
    fun healOrphans_emptyRemoteCollection_marksEveryLocalRowAsOrphan() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()

        val ids = (1..10).map { i ->
            stepDao.insertStep(
                SelfCareStepEntity(
                    stepId = "step$i",
                    routineType = "bedtime",
                    label = "Step $i",
                    duration = "30s",
                    tier = "full",
                    note = "",
                    phase = "cleanse",
                    timeOfDay = "night",
                    cloudId = "cid-$i"
                )
            )
        }

        healer.healOrphans(fetcher = { emptySet() })

        ids.forEach { id ->
            assertEquals(
                "every row with a cloud_id becomes an orphan when Firestore is empty",
                "update",
                metaDao.get(id, "self_care_step")!!.pendingAction
            )
        }
        assertEquals(10, metaDao.getPendingActions().size)
    }

    @Test
    fun healOrphans_mixedNullAndNonNullCloudIds_onlyProcessesNonNull() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()

        val withCloudId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s1",
                routineType = "morning",
                label = "Step 1",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = "cid-1"
            )
        )
        val withoutCloudId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s2",
                routineType = "morning",
                label = "Step 2",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = null
            )
        )
        val withBlankCloudId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s3",
                routineType = "morning",
                label = "Step 3",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = ""
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals(
            "non-null cloud_id row enqueued",
            "update",
            metaDao.get(withCloudId, "self_care_step")!!.pendingAction
        )
        assertNull(
            "null cloud_id: healer ignores — no sync_metadata created",
            metaDao.get(withoutCloudId, "self_care_step")
        )
        assertNull(
            "blank cloud_id: healer ignores as equivalent to null",
            metaDao.get(withBlankCloudId, "self_care_step")
        )
    }

    @Test
    fun healOrphans_signedOutMidScenario_doesNothing() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "s1",
                routineType = "morning",
                label = "Step 1",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = "cid-1"
            )
        )

        every { authManager.userId } returns null
        var fetcherCalled = false
        healer.healOrphans(fetcher = {
            fetcherCalled = true
            emptySet()
        })

        assertFalse("signed-out short-circuits before fetcher", fetcherCalled)
        assertNull(
            "no sync_metadata written when signed out",
            metaDao.get(stepId, "self_care_step")
        )
    }
}
