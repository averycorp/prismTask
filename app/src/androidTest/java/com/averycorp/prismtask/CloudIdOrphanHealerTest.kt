package com.averycorp.prismtask

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.CloudIdOrphanHealer
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for [CloudIdOrphanHealer]. Plants local rows with
 * populated `cloud_id` columns and a synthetic Firestore snapshot, then
 * verifies the healer upserts `sync_metadata` pending-update entries
 * only for rows whose cloud_id is missing from the remote snapshot.
 *
 * The actual Firestore client is never touched — the test supplies its
 * own [CloudIdOrphanHealer.RemoteIdFetcher] via the `healOrphans` param.
 */
@RunWith(AndroidJUnit4::class)
class CloudIdOrphanHealerTest {
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
    fun healOrphans_marksRowAsPendingUpdateWhenCloudIdMissingFromRemote() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "toner",
                routineType = "morning",
                label = "Toner",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "morning",
                cloudId = "orphaned-cloud-id"
            )
        )

        healer.healOrphans(fetcher = fakeFetcher(mapOf("self_care_steps" to emptySet())))

        val meta = metaDao.get(stepId, "self_care_step")
        assertNotNull("sync_metadata should be created for orphan", meta)
        assertEquals("update", meta!!.pendingAction)
        assertEquals("orphaned-cloud-id", meta.cloudId)
    }

    @Test
    fun healOrphans_skipsRowWhenCloudIdFoundInRemote() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "cleanser",
                routineType = "bedtime",
                label = "Cleanser",
                duration = "1m",
                tier = "full",
                note = "",
                phase = "cleanse",
                timeOfDay = "night",
                cloudId = "present-cloud-id"
            )
        )

        healer.healOrphans(
            fetcher = fakeFetcher(mapOf("self_care_steps" to setOf("present-cloud-id")))
        )

        val meta = metaDao.get(stepId, "self_care_step")
        assertNull("no pending action should be created for present cloud_id", meta)
    }

    @Test
    fun healOrphans_skipsRowWithNullCloudId() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "mop",
                routineType = "housework",
                label = "Mop",
                duration = "5m",
                tier = "full",
                note = "",
                phase = "clean",
                timeOfDay = "anytime",
                cloudId = null
            )
        )

        healer.healOrphans(fetcher = fakeFetcher(mapOf("self_care_steps" to emptySet())))

        assertNull(
            "null-cloud_id rows are not the healer's concern",
            metaDao.get(stepId, "self_care_step")
        )
    }

    @Test
    fun healOrphans_skipsFamilyWhenNoLocalRowsHaveCloudIds() = runTest {
        val metaDao = database.syncMetadataDao()
        var fetchedCollections = mutableListOf<String>()
        val fetcher = CloudIdOrphanHealer.RemoteIdFetcher { collection ->
            fetchedCollections.add(collection)
            emptySet()
        }

        healer.healOrphans(fetcher = fetcher)

        // All empty local tables → no Firestore fetch at all for any family.
        assertEquals(
            "healer should short-circuit before Firestore when no local rows qualify",
            0,
            fetchedCollections.size
        )
        assertEquals(0, metaDao.getPendingActions().size)
    }

    @Test
    fun healOrphans_leavesRowAloneWhenFetcherReturnsNull() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "moisturizer",
                routineType = "bedtime",
                label = "Moisturizer",
                duration = "30s",
                tier = "full",
                note = "",
                phase = "moisture",
                timeOfDay = "night",
                cloudId = "maybe-orphan"
            )
        )

        healer.healOrphans(fetcher = CloudIdOrphanHealer.RemoteIdFetcher { null })

        assertNull(
            "fetch error should not enqueue a push — retry on next sync",
            metaDao.get(stepId, "self_care_step")
        )
    }

    @Test
    fun healOrphans_worksAcrossMultipleFamilies() = runTest {
        val stepDao = database.selfCareDao()
        val courseDao = database.schoolworkDao()
        val metaDao = database.syncMetadataDao()

        val stepLocalId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "dust",
                routineType = "housework",
                label = "Dust",
                duration = "5m",
                tier = "full",
                note = "",
                phase = "clean",
                timeOfDay = "anytime",
                cloudId = "step-orphan"
            )
        )
        val courseLocalId = courseDao.insertCourse(
            CourseEntity(
                name = "Math 101",
                code = "MATH101",
                cloudId = "course-orphan"
            )
        )

        healer.healOrphans(
            fetcher = fakeFetcher(
                mapOf(
                    "self_care_steps" to emptySet(),
                    "courses" to emptySet()
                )
            )
        )

        assertEquals("update", metaDao.get(stepLocalId, "self_care_step")!!.pendingAction)
        assertEquals("update", metaDao.get(courseLocalId, "course")!!.pendingAction)
    }

    @Test
    fun healOrphans_preservesExistingCloudIdInMetadataUpsert() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "meditate",
                routineType = "bedtime",
                label = "Meditation",
                duration = "10m",
                tier = "full",
                note = "",
                phase = "mind",
                timeOfDay = "night",
                cloudId = "critical-cloud-id-to-reuse"
            )
        )

        healer.healOrphans(fetcher = fakeFetcher(mapOf("self_care_steps" to emptySet())))

        val meta = metaDao.get(stepId, "self_care_step")!!
        assertEquals(
            "upsert must preserve entity.cloud_id for pushUpdate to target same doc",
            "critical-cloud-id-to-reuse",
            meta.cloudId
        )
    }

    @Test
    fun healOrphans_doesNotDoubleUpsertOnRepeatedRuns() = runTest {
        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "exfoliant",
                routineType = "bedtime",
                label = "Exfoliant",
                duration = "45s",
                tier = "full",
                note = "",
                phase = "treatment",
                timeOfDay = "night",
                cloudId = "still-orphan"
            )
        )

        healer.healOrphans(fetcher = fakeFetcher(mapOf("self_care_steps" to emptySet())))
        val firstTimestamp = metaDao.get(stepId, "self_care_step")!!.lastSyncedAt
        // Ensure enough time passes for last_synced_at to differ.
        Thread.sleep(5)
        healer.healOrphans(fetcher = fakeFetcher(mapOf("self_care_steps" to emptySet())))

        val pendingActions = metaDao.getPendingActions()
        assertEquals(
            "repeated orphan state should keep exactly one metadata row",
            1,
            pendingActions.filter { it.localId == stepId && it.entityType == "self_care_step" }.size
        )
        val secondTimestamp = metaDao.get(stepId, "self_care_step")!!.lastSyncedAt
        // Upsert refreshes the timestamp — confirms the pass ran again.
        assert(secondTimestamp >= firstTimestamp) { "second run should refresh last_synced_at" }
    }

    @Test
    fun healOrphans_returnsEarlyIfNotSignedIn() = runTest {
        every { authManager.userId } returns null

        val stepDao = database.selfCareDao()
        val metaDao = database.syncMetadataDao()
        val stepId = stepDao.insertStep(
            SelfCareStepEntity(
                stepId = "teeth",
                routineType = "morning",
                label = "Brush Teeth",
                duration = "2m",
                tier = "full",
                note = "",
                phase = "oral",
                timeOfDay = "morning",
                cloudId = "signed-out-orphan"
            )
        )
        var fetchCalled = false
        val fetcher = CloudIdOrphanHealer.RemoteIdFetcher {
            fetchCalled = true
            emptySet()
        }

        healer.healOrphans(fetcher = fetcher)

        assertEquals("no fetch should happen when not signed in", false, fetchCalled)
        assertNull(metaDao.get(stepId, "self_care_step"))
    }

    // ── v1.5 medication families (A2 #6 PR1) ──

    @Test
    fun healOrphans_marksMedicationOrphan() = runTest {
        val metaDao = database.syncMetadataDao()
        val medId = database.medicationDao().insert(
            MedicationEntity(name = "Lamotrigine", cloudId = "med-orphan")
        )
        healer.healOrphans(fetcher = fakeFetcher(mapOf("medications" to emptySet())))
        val meta = metaDao.get(medId, "medication")
        assertNotNull(meta)
        assertEquals("update", meta!!.pendingAction)
        assertEquals("med-orphan", meta.cloudId)
    }

    @Test
    fun healOrphans_marksMedicationSlotOrphan() = runTest {
        val metaDao = database.syncMetadataDao()
        val slotId = database.medicationSlotDao().insert(
            MedicationSlotEntity(
                name = "Morning",
                idealTime = "08:00",
                cloudId = "slot-orphan"
            )
        )
        healer.healOrphans(fetcher = fakeFetcher(mapOf("medication_slots" to emptySet())))
        val meta = metaDao.get(slotId, "medication_slot")
        assertNotNull(meta)
        assertEquals("update", meta!!.pendingAction)
        assertEquals("slot-orphan", meta.cloudId)
    }

    @Test
    fun healOrphans_skipsMedicationSlotThatExistsRemotely() = runTest {
        val metaDao = database.syncMetadataDao()
        val slotId = database.medicationSlotDao().insert(
            MedicationSlotEntity(
                name = "Evening",
                idealTime = "20:00",
                cloudId = "slot-present"
            )
        )
        healer.healOrphans(
            fetcher = fakeFetcher(mapOf("medication_slots" to setOf("slot-present")))
        )
        assertNull(metaDao.get(slotId, "medication_slot"))
    }

    private fun fakeFetcher(byCollection: Map<String, Set<String>>): CloudIdOrphanHealer.RemoteIdFetcher =
        CloudIdOrphanHealer.RemoteIdFetcher { collection ->
            byCollection[collection] ?: emptySet()
        }
}
