package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.MedicationPreferences
import com.averycorp.prismtask.data.preferences.SelfCareTierDefaults
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.util.DayBoundary
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the self-care repository conflict-resolution and
 * data-preservation fixes.
 *
 * setTier previously cleared `completedSteps` when switching tiers on
 * non-medication routines, losing progress. These tests verify that fix.
 *
 * They also verify that every write path stamps `updatedAt` with a non-zero
 * timestamp so the last-write-wins pull-path guards in SyncService have a
 * valid timestamp to compare against.
 */
class SelfCareRepositoryConflictTest {

    private lateinit var selfCareDao: SpySelfCareDao
    private lateinit var habitDao: HabitDao
    private lateinit var habitCompletionDao: HabitCompletionDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var medicationPreferences: MedicationPreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var repo: SelfCareRepository

    @Before
    fun setUp() {
        selfCareDao = SpySelfCareDao()
        habitDao = mockk(relaxed = true)
        habitCompletionDao = mockk(relaxed = true)
        syncTracker = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        medicationPreferences = mockk(relaxed = true)
        advancedTuningPreferences = mockk(relaxed = true)

        coEvery { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        coEvery { advancedTuningPreferences.getSelfCareTierDefaults() } returns flowOf(SelfCareTierDefaults())
        coEvery { habitDao.getHabitByName(any()) } returns null
        coEvery { habitDao.insert(any()) } returns 100L
        coEvery { habitDao.getHabitByIdOnce(any()) } answers {
            HabitEntity(id = firstArg(), name = "stub")
        }
        coEvery { habitCompletionDao.isCompletedOnDateLocalOnce(any(), any()) } returns false

        repo = SelfCareRepository(
            context = mockk(relaxed = true),
            selfCareDao = selfCareDao,
            habitDao = habitDao,
            habitCompletionDao = habitCompletionDao,
            medicationPreferences = medicationPreferences,
            taskBehaviorPreferences = taskBehaviorPreferences,
            gson = Gson(),
            syncTracker = syncTracker,
            medicationDao = mockk(relaxed = true),
            medicationDoseDao = mockk(relaxed = true),
            medicationSlotDao = mockk(relaxed = true),
            medicationTierStateDao = mockk(relaxed = true),
            advancedTuningPreferences = advancedTuningPreferences
        )
    }

    // ── setTier: completedSteps preservation ─────────────────────────────────

    @Test
    fun setTier_preservesCompletedSteps_whenSwitchingTier() = runBlocking {
        val originalSteps = """["step_wash","step_moisturize"]"""
        selfCareDao.seedLog(
            SelfCareLogEntity(
                id = 1,
                routineType = "morning",
                date = selfCareDao.todayEpoch,
                selectedTier = "survival",
                completedSteps = originalSteps,
                isComplete = false
            )
        )

        repo.setTier("morning", "solid")

        val saved = selfCareDao.savedLog ?: error("updateLog was never called")
        assertEquals(
            "completedSteps must not be cleared when switching tiers",
            originalSteps,
            saved.completedSteps
        )
    }

    @Test
    fun setTier_updatesSelectedTier() = runBlocking {
        selfCareDao.seedLog(
            SelfCareLogEntity(
                id = 1,
                routineType = "morning",
                date = selfCareDao.todayEpoch,
                selectedTier = "survival"
            )
        )

        repo.setTier("morning", "full")

        val saved = selfCareDao.savedLog ?: error("updateLog was never called")
        assertEquals("full", saved.selectedTier)
    }

    @Test
    fun setTier_stampsUpdatedAt_onExistingLog() = runBlocking {
        val before = System.currentTimeMillis()
        selfCareDao.seedLog(
            SelfCareLogEntity(
                id = 1,
                routineType = "morning",
                date = selfCareDao.todayEpoch,
                updatedAt = 0L
            )
        )

        repo.setTier("morning", "solid")

        val saved = selfCareDao.savedLog ?: error("updateLog was never called")
        assertTrue(
            "updatedAt must be stamped with current time",
            saved.updatedAt >= before
        )
    }

    @Test
    fun setTier_newLog_stampsUpdatedAt() = runBlocking {
        val before = System.currentTimeMillis()
        // No pre-existing log — repo will insertLog

        repo.setTier("morning", "survival")

        val inserted = selfCareDao.insertedLog ?: error("insertLog was never called")
        assertTrue(
            "new log must have updatedAt stamped",
            inserted.updatedAt >= before
        )
    }

    // ── toggleStep: updatedAt stamping ────────────────────────────────────────

    @Test
    fun toggleStep_seedsSelectedTier_fromUserConfiguredDefault() = runBlocking {
        // No log yet today: a fresh log should pick up the user's
        // configured default tier instead of falling back to the schema-
        // level "solid" entity default. Without this, the user's
        // preference would be silently overridden the moment they tapped
        // a step before tapping a tier chip.
        coEvery { advancedTuningPreferences.getSelfCareTierDefaults() } returns flowOf(
            SelfCareTierDefaults(morning = "survival")
        )
        selfCareDao.seedStep(
            SelfCareStepEntity(
                id = 10,
                stepId = "sc_wash",
                routineType = "morning",
                label = "Wash",
                duration = "2 min",
                tier = "survival",
                phase = "Hygiene"
            )
        )

        repo.toggleStep("morning", "sc_wash")

        val inserted = selfCareDao.insertedLog ?: error("insertLog was never called")
        assertEquals("survival", inserted.selectedTier)
    }

    @Test
    fun toggleStep_seedsSelectedTier_coercesUnknownDefaultBackToPenultimate() = runBlocking {
        // A stale preference value not in the routine's tier order falls
        // back to penultimate-of-order — never written verbatim.
        coEvery { advancedTuningPreferences.getSelfCareTierDefaults() } returns flowOf(
            SelfCareTierDefaults(housework = "ultra_deep_2027")
        )
        selfCareDao.seedStep(
            SelfCareStepEntity(
                id = 11,
                stepId = "hw_dishes",
                routineType = "housework",
                label = "Dishes",
                duration = "5 min",
                tier = "quick",
                phase = "Kitchen"
            )
        )

        repo.toggleStep("housework", "hw_dishes")

        val inserted = selfCareDao.insertedLog ?: error("insertLog was never called")
        assertEquals("regular", inserted.selectedTier)
    }

    @Test
    fun setTierForTime_seedsSelectedTier_fromUserConfiguredDefault() = runBlocking {
        // The medication "tier-for-time" entry point also creates a log
        // when none exists. It must read the user's medication default
        // rather than falling through to "solid" (which is not even a
        // valid medication tier id).
        coEvery { advancedTuningPreferences.getSelfCareTierDefaults() } returns flowOf(
            SelfCareTierDefaults(medication = "essential")
        )
        coEvery { medicationPreferences.getReminderIntervalMinutesOnce() } returns 0

        repo.setTierForTime("morning", "essential")

        val inserted = selfCareDao.insertedLog ?: error("insertLog was never called")
        assertEquals("essential", inserted.selectedTier)
    }

    @Test
    fun toggleStep_stampsUpdatedAt() = runBlocking {
        val before = System.currentTimeMillis()
        selfCareDao.seedLog(
            SelfCareLogEntity(
                id = 1,
                routineType = "morning",
                date = selfCareDao.todayEpoch,
                updatedAt = 0L
            )
        )
        selfCareDao.seedStep(
            SelfCareStepEntity(
                id = 10,
                stepId = "sc_wash",
                routineType = "morning",
                label = "Wash",
                duration = "2 min",
                tier = "survival",
                phase = "Hygiene"
            )
        )

        repo.toggleStep("morning", "sc_wash")

        val saved = selfCareDao.savedLog ?: error("updateLog was never called")
        assertTrue("updatedAt must be stamped on toggleStep", saved.updatedAt >= before)
    }

    // ── resetToday: updatedAt stamping ────────────────────────────────────────

    @Test
    fun resetToday_stampsUpdatedAt() = runBlocking {
        val before = System.currentTimeMillis()
        selfCareDao.seedLog(
            SelfCareLogEntity(
                id = 1,
                routineType = "morning",
                date = selfCareDao.todayEpoch,
                completedSteps = """["sc_wash"]""",
                updatedAt = 0L
            )
        )

        repo.resetToday("morning")

        val saved = selfCareDao.savedLog ?: error("updateLog was never called")
        assertTrue("updatedAt must be stamped on resetToday", saved.updatedAt >= before)
    }

    /**
     * Minimal fake [SelfCareDao] that tracks the last log written and supports
     * a seed for pre-existing data. Only the methods exercised by the tests
     * above are implemented; others throw so accidental usage is visible.
     */
    private class SpySelfCareDao : SelfCareDao {
        val todayEpoch: Long = DayBoundary.startOfCurrentDay(0)

        private val logs = mutableListOf<SelfCareLogEntity>()
        private val steps = mutableListOf<SelfCareStepEntity>()
        private var nextLogId = 1L

        var savedLog: SelfCareLogEntity? = null
        var insertedLog: SelfCareLogEntity? = null

        fun seedLog(log: SelfCareLogEntity) {
            logs.removeAll { it.routineType == log.routineType && it.date == log.date }
            logs.add(log)
        }

        fun seedStep(step: SelfCareStepEntity) {
            steps.removeAll { it.stepId == step.stepId && it.routineType == step.routineType }
            steps.add(step)
        }

        override fun getLogForDate(routineType: String, date: Long): Flow<SelfCareLogEntity?> =
            flowOf(logs.firstOrNull { it.routineType == routineType && it.date == date })

        override suspend fun getLogForDateOnce(routineType: String, date: Long): SelfCareLogEntity? =
            logs.firstOrNull { it.routineType == routineType && it.date == date }

        override suspend fun insertLog(log: SelfCareLogEntity): Long {
            val id = nextLogId++
            val stored = log.copy(id = id)
            logs.add(stored)
            insertedLog = stored
            return id
        }

        override suspend fun updateLog(log: SelfCareLogEntity) {
            val idx = logs.indexOfFirst { it.id == log.id }
            if (idx >= 0) logs[idx] = log
            savedLog = log
        }

        override suspend fun getLogById(id: Long): SelfCareLogEntity? =
            logs.firstOrNull { it.id == id }

        override suspend fun getStepById(id: Long): SelfCareStepEntity? =
            steps.firstOrNull { it.id == id }

        override fun getStepsForRoutine(routineType: String): Flow<List<SelfCareStepEntity>> =
            flowOf(steps.filter { it.routineType == routineType }.sortedBy { it.sortOrder })

        override fun getStepsForRoutineByTimeOfDay(routineType: String, timeOfDay: String): Flow<List<SelfCareStepEntity>> =
            flowOf(steps.filter { it.routineType == routineType && timeOfDay in it.timeOfDay })

        override suspend fun getStepsForRoutineOnce(routineType: String): List<SelfCareStepEntity> =
            steps.filter { it.routineType == routineType }.sortedBy { it.sortOrder }

        override suspend fun getStepCount(): Int = steps.size

        override suspend fun insertStep(step: SelfCareStepEntity): Long {
            val id = steps.size + 1L
            steps.add(step.copy(id = id))
            return id
        }

        override suspend fun insertSteps(steps: List<SelfCareStepEntity>) {
            steps.forEach { insertStep(it) }
        }

        override suspend fun updateStep(step: SelfCareStepEntity) {
            val idx = this.steps.indexOfFirst { it.id == step.id }
            if (idx >= 0) this.steps[idx] = step
        }

        override suspend fun deleteStep(step: SelfCareStepEntity) {
            steps.removeAll { it.id == step.id }
        }

        override suspend fun getMaxSortOrder(routineType: String): Int =
            steps.filter { it.routineType == routineType }.maxOfOrNull { it.sortOrder } ?: -1

        override suspend fun updateSteps(steps: List<SelfCareStepEntity>) {
            steps.forEach { updateStep(it) }
        }

        override suspend fun getAllLogsOnce(): List<SelfCareLogEntity> = logs.toList()

        override fun getLogsForRoutine(routineType: String): Flow<List<SelfCareLogEntity>> =
            flowOf(logs.filter { it.routineType == routineType })

        override suspend fun getAllStepsOnce(): List<SelfCareStepEntity> = steps.toList()

        override suspend fun getStepByStepIdOnce(stepId: String, routineType: String): SelfCareStepEntity? =
            steps.firstOrNull { it.stepId == stepId && it.routineType == routineType }

        override suspend fun getStepByMedicationName(name: String): SelfCareStepEntity? =
            steps.firstOrNull { it.medicationName == name }

        override suspend fun deleteStepById(id: Long) {
            steps.removeAll { it.id == id }
        }

        override suspend fun deleteLogById(id: Long) {
            logs.removeAll { it.id == id }
        }

        override suspend fun deleteStepsByStepIds(routineType: String, stepIds: List<String>) {
            steps.removeAll { it.routineType == routineType && it.stepId in stepIds }
        }
    }
}
