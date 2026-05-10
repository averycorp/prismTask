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
import com.averycorp.prismtask.domain.model.SelfCareRoutines
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for [SelfCareRepository.seedSelfCareTier] and
 * [SelfCareRepository.seedSelfCareSteps]. These are the public seams used by
 * the onboarding template picker + Settings → Browse Templates, so they must
 * be idempotent and must line up with the step lists declared in
 * [SelfCareRoutines].
 */
class SelfCareRepositorySeedingTest {
    private lateinit var selfCareDao: FakeSelfCareDao
    private lateinit var habitDao: HabitDao
    private lateinit var habitCompletionDao: HabitCompletionDao
    private lateinit var medicationPreferences: MedicationPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var repo: SelfCareRepository

    @Before
    fun setUp() {
        selfCareDao = FakeSelfCareDao()
        habitDao = mockk(relaxed = true)
        habitCompletionDao = mockk(relaxed = true)
        medicationPreferences = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        advancedTuningPreferences = mockk(relaxed = true)
        coEvery { advancedTuningPreferences.getSelfCareTierDefaults() } returns flowOf(SelfCareTierDefaults())

        // Return a different id on each insert so we can distinguish inserted
        // habits from stubs; the repository just needs a valid HabitEntity back.
        val insertedHabit = slot<HabitEntity>()
        var nextHabitId = 100L
        coEvery { habitDao.getHabitByName(any()) } returns null
        coEvery { habitDao.insert(capture(insertedHabit)) } answers {
            nextHabitId++
        }
        coEvery { habitDao.getHabitByIdOnce(any()) } answers {
            val id = firstArg<Long>()
            HabitEntity(id = id, name = "stub-$id")
        }

        repo = SelfCareRepository(
            context = mockk(relaxed = true),
            selfCareDao = selfCareDao,
            habitDao = habitDao,
            habitCompletionDao = habitCompletionDao,
            medicationPreferences = medicationPreferences,
            taskBehaviorPreferences = taskBehaviorPreferences,
            gson = Gson(),
            syncTracker = mockk(relaxed = true),
            medicationDao = mockk(relaxed = true),
            medicationDoseDao = mockk(relaxed = true),
            medicationSlotDao = mockk(relaxed = true),
            medicationTierStateDao = mockk(relaxed = true),
            advancedTuningPreferences = advancedTuningPreferences
        )
    }

    @Test
    fun seedSelfCareTier_morningSolid_insertsSurvivalAndSolidSteps() = runBlocking {
        repo.seedSelfCareTier("morning", "solid")

        val inserted = selfCareDao.stepsForRoutine("morning").map { it.stepId }.toSet()
        val expected = SelfCareRoutines.morningSteps
            .filter { it.tier == "survival" || it.tier == "solid" }
            .map { it.id }
            .toSet()

        assertEquals(expected, inserted)
        // "full" steps must NOT be seeded by a "solid" pick.
        assertTrue(
            "full-tier steps must be skipped when tier = solid",
            inserted.none { id -> SelfCareRoutines.morningSteps.first { it.id == id }.tier == "full" }
        )
    }

    @Test
    fun seedSelfCareTier_isIdempotent() = runBlocking {
        repo.seedSelfCareTier("morning", "solid")
        val afterFirst = selfCareDao.stepsForRoutine("morning").size
        assertTrue("first seed should insert rows", afterFirst > 0)

        repo.seedSelfCareTier("morning", "solid")
        val afterSecond = selfCareDao.stepsForRoutine("morning").size

        assertEquals(
            "seedSelfCareTier must skip existing stepIds on repeat calls",
            afterFirst,
            afterSecond
        )
    }

    @Test
    fun seedSelfCareTier_unknownTier_isNoop() = runBlocking {
        repo.seedSelfCareTier("morning", "not_a_tier")
        assertEquals(0, selfCareDao.stepsForRoutine("morning").size)
    }

    @Test
    fun seedSelfCareTier_medicationEssential_insertsDefaultMedicationSteps() = runBlocking {
        // v1.4.0 default-template expansion: medication is no longer empty
        // by design — the essential tier seeds four generic daily doses so
        // first-time users have a sensible starting checklist.
        repo.seedSelfCareTier("medication", "essential")

        val inserted = selfCareDao.stepsForRoutine("medication").map { it.stepId }.toSet()
        val expected = SelfCareRoutines.medicationSteps
            .filter { it.tier == "essential" }
            .map { it.id }
            .toSet()

        assertEquals(expected, inserted)
        assertTrue(
            "at least one default medication step should have been seeded",
            inserted.isNotEmpty()
        )
    }

    @Test
    fun seedSelfCareSteps_onlyInsertsRequestedIds() = runBlocking {
        repo.seedSelfCareSteps("housework", listOf("hw_dishwasher", "hw_trash"))

        val inserted = selfCareDao.stepsForRoutine("housework").map { it.stepId }
        assertEquals(setOf("hw_dishwasher", "hw_trash"), inserted.toSet())
    }

    @Test
    fun seedSelfCareSteps_ignoresUnknownIds() = runBlocking {
        repo.seedSelfCareSteps("morning", listOf("sc_water", "bogus_step_that_does_not_exist"))

        val inserted = selfCareDao.stepsForRoutine("morning").map { it.stepId }
        assertEquals(listOf("sc_water"), inserted)
    }

    @Test
    fun seedSelfCareSteps_isIdempotent() = runBlocking {
        // "bedtime" still uses the skincare-flavored defaults, which are
        // untouched by the v1.4.0 default-template expansion.
        repo.seedSelfCareSteps("bedtime", listOf("cleanser", "moisturizer"))
        val first = selfCareDao.stepsForRoutine("bedtime").map { it.stepId }.toSet()

        repo.seedSelfCareSteps("bedtime", listOf("cleanser", "moisturizer"))
        val second = selfCareDao.stepsForRoutine("bedtime").map { it.stepId }.toSet()

        assertEquals(first, second)
    }

    @Test
    fun seedSelfCareSteps_createsHabitOnce() = runBlocking {
        // First call: habit doesn't exist, so insert is triggered.
        repo.seedSelfCareSteps("morning", listOf("sc_water"))
        coVerify(atLeast = 1) { habitDao.insert(any()) }
    }

    @Test
    fun seedSelfCareSteps_emptyList_doesNotTouchDatabase() = runBlocking {
        repo.seedSelfCareSteps("morning", emptyList())
        assertEquals(0, selfCareDao.stepsForRoutine("morning").size)
        // No habit should be created for a no-op seed either.
        coVerify(exactly = 0) { habitDao.insert(any()) }
    }

    /**
     * In-memory fake implementation of [SelfCareDao]. Only the methods the
     * seeding paths exercise are meaningfully implemented; the rest throw so
     * accidental usage surfaces loudly in tests.
     */
    private class FakeSelfCareDao : SelfCareDao {
        private val steps = mutableListOf<SelfCareStepEntity>()
        private var nextId = 1L

        fun stepsForRoutine(routineType: String): List<SelfCareStepEntity> =
            steps.filter { it.routineType == routineType }

        override fun getLogForDate(routineType: String, date: Long): Flow<SelfCareLogEntity?> =
            flowOf(null)

        override suspend fun getLogForDateOnce(routineType: String, date: Long): SelfCareLogEntity? =
            null

        override suspend fun insertLog(log: SelfCareLogEntity): Long = error("not needed")

        override suspend fun updateLog(log: SelfCareLogEntity) = error("not needed")

        override fun getStepsForRoutine(routineType: String): Flow<List<SelfCareStepEntity>> =
            flowOf(stepsForRoutine(routineType))

        override fun getStepsForRoutineByTimeOfDay(
            routineType: String,
            timeOfDay: String
        ): Flow<List<SelfCareStepEntity>> = flowOf(
            stepsForRoutine(routineType).filter { timeOfDay in it.timeOfDay }
        )

        override suspend fun getStepsForRoutineOnce(routineType: String): List<SelfCareStepEntity> =
            stepsForRoutine(routineType).sortedBy { it.sortOrder }

        override suspend fun getStepCount(): Int = steps.size

        override suspend fun insertStep(step: SelfCareStepEntity): Long {
            val id = nextId++
            steps.add(step.copy(id = id))
            return id
        }

        override suspend fun insertSteps(stepList: List<SelfCareStepEntity>) {
            for (s in stepList) {
                insertStep(s)
            }
        }

        override suspend fun updateStep(step: SelfCareStepEntity) {
            val idx = steps.indexOfFirst { it.id == step.id }
            if (idx >= 0) steps[idx] = step
        }

        override suspend fun deleteStep(step: SelfCareStepEntity) {
            steps.removeAll { it.id == step.id }
        }

        override suspend fun getMaxSortOrder(routineType: String): Int =
            stepsForRoutine(routineType).maxOfOrNull { it.sortOrder } ?: -1

        override suspend fun updateSteps(stepList: List<SelfCareStepEntity>) {
            stepList.forEach { updateStep(it) }
        }

        override suspend fun getAllLogsOnce(): List<SelfCareLogEntity> = emptyList()

        override fun getLogsForRoutine(routineType: String): Flow<List<SelfCareLogEntity>> =
            flowOf(emptyList())

        override suspend fun getAllStepsOnce(): List<SelfCareStepEntity> = steps.toList()

        override suspend fun getStepByMedicationName(name: String): SelfCareStepEntity? =
            steps.firstOrNull { it.medicationName == name }

        override suspend fun getStepByStepIdOnce(
            stepId: String,
            routineType: String
        ): SelfCareStepEntity? =
            steps.firstOrNull { it.stepId == stepId && it.routineType == routineType }

        override suspend fun deleteStepById(id: Long) {
            steps.removeAll { it.id == id }
        }

        override suspend fun deleteLogById(id: Long) {
            // Logs aren't tracked in this fake.
        }

        override suspend fun deleteStepsByStepIds(routineType: String, stepIds: List<String>) {
            steps.removeAll { it.routineType == routineType && it.stepId in stepIds }
        }

        override suspend fun getLogById(id: Long): SelfCareLogEntity? = null

        override suspend fun getStepById(id: Long): SelfCareStepEntity? =
            steps.firstOrNull { it.id == id }
    }
}
