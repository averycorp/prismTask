package com.averycorp.prismtask.data.privacy

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.BoundaryRuleEntity
import com.averycorp.prismtask.data.local.entity.CheckInLogEntity
import com.averycorp.prismtask.data.local.entity.FocusReleaseLogEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity
import com.averycorp.prismtask.data.remote.AuthManager
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cross-table atomicity test for the Mental-Health-First § G5 partial wipe.
 *
 * Seeds rows in every mental-health table AND in the load-bearing
 * non-MH tables (`tasks`, `habits`, `projects`). Asserts that
 * `wipeMentalHealthData()`:
 *   - empties every MH table,
 *   - drops the matching `sync_metadata` rows,
 *   - leaves `tasks`, `habits`, `projects` untouched.
 *
 * The "untouched tables" half is the invariant the audit explicitly
 * calls out — without it a future refactor could quietly turn the
 * partial wipe into a full wipe.
 *
 * Auth-manager userId is stubbed to null so the cloud-side step is
 * skipped (no Firebase needed in this test). The Firestore client is
 * passed as a relaxed mock — the wiper's null-uid guard means it's
 * never invoked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MentalHealthDataWiperTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: PrismTaskDatabase
    private lateinit var wiper: MentalHealthDataWiper
    private lateinit var authManager: AuthManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room
            .inMemoryDatabaseBuilder(context, PrismTaskDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authManager = mockk()
        // Force the wiper down the LocalOnly branch — no Firebase access.
        every { authManager.userId } returns null
        wiper = MentalHealthDataWiper(
            database = database,
            transactionRunner = DatabaseTransactionRunner(database),
            authManager = authManager,
            firestore = mockk<FirebaseFirestore>(relaxed = true)
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `wipe empties every MH table and leaves tasks habits projects intact`() = runTest {
        seedMentalHealthRows()
        seedUntouchedRows()
        seedSyncMetadata()

        // Sanity: everything is present before the wipe.
        assertEquals(2, database.moodEnergyLogDao().getAllOnce().size)
        assertEquals(2, database.checkInLogDao().getAllOnce().size)
        assertEquals(2, database.weeklyReviewDao().getAllOnce().size)
        assertEquals(2, database.boundaryRuleDao().getAllOnce().size)
        assertEquals(2, database.focusReleaseLogDao().getAllOnce().size)
        assertEquals(3, database.taskDao().getAllTasksOnce().size)
        assertEquals(3, database.habitDao().getAllHabitsOnce().size)
        assertEquals(3, database.projectDao().getAllProjectsOnce().size)

        val result = wiper.wipeMentalHealthData()

        // userId == null so we expect LocalOnly, not Success. Either way the
        // load-bearing local wipe must have completed.
        assertTrue(
            "Expected LocalOnly when not signed in, got $result",
            result is MentalHealthDataWiper.WipeResult.LocalOnly
        )

        // Every MH table is empty.
        assertEquals(
            "mood_energy_logs must be empty after wipe",
            0,
            database.moodEnergyLogDao().getAllOnce().size
        )
        assertEquals(
            "check_in_logs must be empty after wipe",
            0,
            database.checkInLogDao().getAllOnce().size
        )
        assertEquals(
            "weekly_reviews must be empty after wipe",
            0,
            database.weeklyReviewDao().getAllOnce().size
        )
        assertEquals(
            "boundary_rules must be empty after wipe",
            0,
            database.boundaryRuleDao().getAllOnce().size
        )
        assertEquals(
            "focus_release_logs must be empty after wipe",
            0,
            database.focusReleaseLogDao().getAllOnce().size
        )

        // Untouched tables retain every row. This is the invariant the audit
        // calls out — losing this assertion would silently turn the partial
        // wipe into a full wipe.
        assertEquals(
            "tasks must NOT be touched by the MH wipe",
            3,
            database.taskDao().getAllTasksOnce().size
        )
        assertEquals(
            "habits must NOT be touched by the MH wipe",
            3,
            database.habitDao().getAllHabitsOnce().size
        )
        assertEquals(
            "projects must NOT be touched by the MH wipe",
            3,
            database.projectDao().getAllProjectsOnce().size
        )

        // Sync-metadata for MH entity types is gone so a future push won't
        // try to operate on rows that no longer exist; sync-metadata for
        // non-MH types (here, "task") is preserved.
        val metadataDao = database.syncMetadataDao()
        MentalHealthDataWiper.MH_ENTITY_TYPES.forEach { entityType ->
            assertTrue(
                "sync_metadata must be empty for entity type=$entityType after wipe",
                metadataDao.getAllCloudIdsForType(entityType).isEmpty()
            )
        }
        assertEquals(
            "sync_metadata for non-MH entity types must NOT be touched",
            1,
            metadataDao.getAllCloudIdsForType("task").size
        )
    }

    private suspend fun seedMentalHealthRows() {
        val moodDao = database.moodEnergyLogDao()
        moodDao.insert(MoodEnergyLogEntity(date = 1_000L, mood = 3, energy = 4, timeOfDay = "morning"))
        moodDao.insert(MoodEnergyLogEntity(date = 2_000L, mood = 4, energy = 3, timeOfDay = "evening"))

        val checkInDao = database.checkInLogDao()
        checkInDao.upsert(CheckInLogEntity(date = 1_000L, stepsCompletedCsv = "MOOD,TASKS"))
        checkInDao.upsert(CheckInLogEntity(date = 2_000L, stepsCompletedCsv = "MOOD,BALANCE"))

        val weeklyDao = database.weeklyReviewDao()
        weeklyDao.upsert(WeeklyReviewEntity(weekStartDate = 1_000L, metricsJson = "{}"))
        weeklyDao.upsert(WeeklyReviewEntity(weekStartDate = 2_000L, metricsJson = "{}"))

        val boundaryDao = database.boundaryRuleDao()
        boundaryDao.insert(
            BoundaryRuleEntity(
                name = "Work hours",
                ruleType = "TIME_WINDOW",
                category = "WORK",
                startTime = "09:00",
                endTime = "17:00",
                activeDaysCsv = "1,2,3,4,5"
            )
        )
        boundaryDao.insert(
            BoundaryRuleEntity(
                name = "No-meetings Friday",
                ruleType = "TIME_WINDOW",
                category = "WORK",
                startTime = "00:00",
                endTime = "23:59",
                activeDaysCsv = "5"
            )
        )

        val focusDao = database.focusReleaseLogDao()
        focusDao.insert(FocusReleaseLogEntity(eventType = "stuck_detected"))
        focusDao.insert(FocusReleaseLogEntity(eventType = "good_enough_shipped"))
    }

    private suspend fun seedUntouchedRows() {
        val taskDao = database.taskDao()
        taskDao.insert(TaskEntity(title = "Buy groceries"))
        taskDao.insert(TaskEntity(title = "Call dentist"))
        taskDao.insert(TaskEntity(title = "Refill prescription"))

        val habitDao = database.habitDao()
        habitDao.insert(HabitEntity(name = "Drink water"))
        habitDao.insert(HabitEntity(name = "Walk"))
        habitDao.insert(HabitEntity(name = "Read"))

        val projectDao = database.projectDao()
        projectDao.insert(ProjectEntity(name = "House"))
        projectDao.insert(ProjectEntity(name = "Work"))
        projectDao.insert(ProjectEntity(name = "Side project"))
    }

    private suspend fun seedSyncMetadata() {
        val dao = database.syncMetadataDao()
        // One MH-flavored metadata row for every entity type the wiper claims
        // to handle — this is what asserts the per-type cleanup loop covers
        // the right strings (a typo in `MH_ENTITY_TYPES` would leak a row).
        MentalHealthDataWiper.MH_ENTITY_TYPES.forEachIndexed { idx, entityType ->
            dao.upsert(
                SyncMetadataEntity(
                    localId = (idx + 1).toLong(),
                    entityType = entityType,
                    cloudId = "cloud-$entityType",
                    lastSyncedAt = 0L,
                    pendingAction = null
                )
            )
        }
        // One non-MH row that MUST survive the wipe.
        dao.upsert(
            SyncMetadataEntity(
                localId = 99L,
                entityType = "task",
                cloudId = "cloud-task",
                lastSyncedAt = 0L,
                pendingAction = null
            )
        )
    }
}
