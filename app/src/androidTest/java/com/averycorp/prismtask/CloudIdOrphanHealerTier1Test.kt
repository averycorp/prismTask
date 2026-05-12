package com.averycorp.prismtask

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.HabitLogEntity
import com.averycorp.prismtask.data.local.entity.MilestoneEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.CloudIdOrphanHealer
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coverage for the Tier-1 entity families added to [CloudIdOrphanHealer]
 * per `docs/SPEC_SELF_CARE_STEPS_SYNC_PIPELINE.md` §8 Q1 follow-up.
 *
 * Tier-1 entities (tasks, projects, tags, habits, habit_completions,
 * habit_logs, task_completions, task_templates) ship via
 * [com.averycorp.prismtask.data.remote.SyncService.doInitialUpload] —
 * not the `maybeRunEntityBackfill` path that the original healer scope
 * covered. Both paths are one-shot and share the same out-of-band
 * wipe failure mode, so the healer now covers both uniformly.
 *
 * Each test seeds one orphan per family with an empty simulated
 * Firestore and verifies the healer enqueues the correct
 * `sync_metadata` pending-update row.
 */
@RunWith(AndroidJUnit4::class)
class CloudIdOrphanHealerTier1Test {
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
    fun healOrphans_tasks() = runTest {
        val dao = database.taskDao()
        val metaDao = database.syncMetadataDao()
        val id = dao.insert(
            TaskEntity(
                title = "Ship the thing",
                cloudId = "task-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        val meta = metaDao.get(id, "task")
        assertNotNull(meta)
        assertEquals("update", meta!!.pendingAction)
        assertEquals("task-orphan-id", meta.cloudId)
    }

    @Test
    fun healOrphans_projects() = runTest {
        val dao = database.projectDao()
        val metaDao = database.syncMetadataDao()
        val id = dao.insert(
            ProjectEntity(
                name = "v2.0",
                cloudId = "project-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(id, "project")!!.pendingAction)
    }

    @Test
    fun healOrphans_tags() = runTest {
        val dao = database.tagDao()
        val metaDao = database.syncMetadataDao()
        val id = dao.insert(
            TagEntity(
                name = "urgent",
                cloudId = "tag-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(id, "tag")!!.pendingAction)
    }

    @Test
    fun healOrphans_habits() = runTest {
        val dao = database.habitDao()
        val metaDao = database.syncMetadataDao()
        val id = dao.insert(
            HabitEntity(
                name = "Read",
                targetFrequency = 1,
                frequencyPeriod = "daily",
                color = "#FF0000",
                icon = "📚",
                createdAt = 0L,
                updatedAt = 0L,
                cloudId = "habit-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(id, "habit")!!.pendingAction)
    }

    @Test
    fun healOrphans_habitCompletions() = runTest {
        val habitDao = database.habitDao()
        val completionDao = database.habitCompletionDao()
        val metaDao = database.syncMetadataDao()

        val habitId = habitDao.insert(
            HabitEntity(
                name = "Meditate",
                targetFrequency = 1,
                frequencyPeriod = "daily",
                color = "#000000",
                icon = "🧘",
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        val completionId = completionDao.insert(
            HabitCompletionEntity(
                habitId = habitId,
                completedDate = 1_700_000_000_000L,
                completedAt = 1_700_000_000_000L,
                cloudId = "completion-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(completionId, "habit_completion")!!.pendingAction)
    }

    @Test
    fun healOrphans_habitLogs() = runTest {
        val habitDao = database.habitDao()
        val logDao = database.habitLogDao()
        val metaDao = database.syncMetadataDao()

        val habitId = habitDao.insert(
            HabitEntity(
                name = "Theater",
                targetFrequency = 1,
                frequencyPeriod = "weekly",
                color = "#AA00AA",
                icon = "🎭",
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        val logId = logDao.insertLog(
            HabitLogEntity(
                habitId = habitId,
                date = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                cloudId = "log-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(logId, "habit_log")!!.pendingAction)
    }

    @Test
    fun healOrphans_taskCompletions() = runTest {
        val dao = database.taskCompletionDao()
        val metaDao = database.syncMetadataDao()
        val id = dao.insert(
            TaskCompletionEntity(
                taskId = null,
                projectId = null,
                completedDate = 1_700_000_000_000L,
                completedAtTime = 1_700_000_000_000L,
                priority = 0,
                cloudId = "task-completion-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(id, "task_completion")!!.pendingAction)
    }

    @Test
    fun healOrphans_taskTemplates() = runTest {
        val dao = database.taskTemplateDao()
        val metaDao = database.syncMetadataDao()
        val id = dao.insertTemplate(
            TaskTemplateEntity(
                name = "Morning Review",
                cloudId = "template-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(id, "task_template")!!.pendingAction)
    }

    @Test
    fun healOrphans_milestones() = runTest {
        val projectDao = database.projectDao()
        val milestoneDao = database.milestoneDao()
        val metaDao = database.syncMetadataDao()
        // Milestone has a CASCADE FK to projects — insert a parent first.
        val projectLocalId = projectDao.insert(
            ProjectEntity(name = "Parent", cloudId = "parent-cid")
        )
        val milestoneId = milestoneDao.insert(
            MilestoneEntity(
                projectId = projectLocalId,
                title = "Ship RC1",
                cloudId = "milestone-orphan-id"
            )
        )

        healer.healOrphans(fetcher = { emptySet() })

        val meta = metaDao.get(milestoneId, "milestone")
        assertNotNull(meta)
        assertEquals("update", meta!!.pendingAction)
        assertEquals("milestone-orphan-id", meta.cloudId)
    }

    @Test
    fun healOrphans_milestones_scannedAcrossAllProjects() = runTest {
        // Ensures getAllMilestonesOnce() returns milestones regardless of
        // which project they belong to — the healer must not miss rows
        // just because the DAO also exposes a per-project getter
        // (getMilestonesOnce(projectId)) used elsewhere in the app.
        val projectDao = database.projectDao()
        val milestoneDao = database.milestoneDao()
        val metaDao = database.syncMetadataDao()

        val projectA = projectDao.insert(ProjectEntity(name = "A", cloudId = "pa"))
        val projectB = projectDao.insert(ProjectEntity(name = "B", cloudId = "pb"))
        val milestoneAId = milestoneDao.insert(
            MilestoneEntity(projectId = projectA, title = "A1", cloudId = "cid-a1")
        )
        val milestoneBId = milestoneDao.insert(
            MilestoneEntity(projectId = projectB, title = "B1", cloudId = "cid-b1")
        )

        healer.healOrphans(fetcher = { emptySet() })

        assertEquals("update", metaDao.get(milestoneAId, "milestone")!!.pendingAction)
        assertEquals("update", metaDao.get(milestoneBId, "milestone")!!.pendingAction)
    }

    @Test
    fun healOrphans_allTier1Families_coveredInSingleCall() = runTest {
        // Full sweep: one orphan per Tier-1 family. A single healOrphans
        // call must enqueue updates for all of them, proving the family
        // loop is exhaustive.
        val taskId = database.taskDao().insert(
            TaskEntity(title = "t", cloudId = "cid-t")
        )
        val projectId = database.projectDao().insert(
            ProjectEntity(name = "p", cloudId = "cid-p")
        )
        val tagId = database.tagDao().insert(
            TagEntity(name = "tag", cloudId = "cid-tag")
        )
        val habitId = database.habitDao().insert(
            HabitEntity(
                name = "h",
                targetFrequency = 1,
                frequencyPeriod = "daily",
                color = "#0",
                icon = "H",
                createdAt = 0,
                updatedAt = 0,
                cloudId = "cid-h"
            )
        )
        val completionId = database.habitCompletionDao().insert(
            HabitCompletionEntity(
                habitId = habitId,
                completedDate = 0L,
                completedAt = 0L,
                cloudId = "cid-hc"
            )
        )
        val logId = database.habitLogDao().insertLog(
            HabitLogEntity(
                habitId = habitId,
                date = 0L,
                createdAt = 0L,
                cloudId = "cid-hl"
            )
        )
        val taskCompletionId = database.taskCompletionDao().insert(
            TaskCompletionEntity(
                taskId = null,
                projectId = null,
                completedDate = 0L,
                completedAtTime = 0L,
                priority = 0,
                cloudId = "cid-tc"
            )
        )
        val templateId = database.taskTemplateDao().insertTemplate(
            TaskTemplateEntity(name = "x", cloudId = "cid-tt")
        )

        healer.healOrphans(fetcher = { emptySet() })

        val metaDao = database.syncMetadataDao()
        assertEquals("update", metaDao.get(taskId, "task")!!.pendingAction)
        assertEquals("update", metaDao.get(projectId, "project")!!.pendingAction)
        assertEquals("update", metaDao.get(tagId, "tag")!!.pendingAction)
        assertEquals("update", metaDao.get(habitId, "habit")!!.pendingAction)
        assertEquals("update", metaDao.get(completionId, "habit_completion")!!.pendingAction)
        assertEquals("update", metaDao.get(logId, "habit_log")!!.pendingAction)
        assertEquals("update", metaDao.get(taskCompletionId, "task_completion")!!.pendingAction)
        assertEquals("update", metaDao.get(templateId, "task_template")!!.pendingAction)
    }

    @Test
    fun healOrphans_tier1Family_skipsRowsAlreadyInFirestore() = runTest {
        val dao = database.projectDao()
        val metaDao = database.syncMetadataDao()
        val presentId = dao.insert(ProjectEntity(name = "in-cloud", cloudId = "present-id"))
        val orphanId = dao.insert(ProjectEntity(name = "orphan", cloudId = "orphan-id"))

        healer.healOrphans(fetcher = { collection ->
            if (collection == "projects") setOf("present-id") else emptySet()
        })

        assertEquals(
            "present row was NOT enqueued",
            null,
            metaDao.get(presentId, "project")
        )
        assertEquals(
            "orphan row WAS enqueued",
            "update",
            metaDao.get(orphanId, "project")!!.pendingAction
        )
    }
}
