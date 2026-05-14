package com.averycorp.prismtask.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyEssentialsUseCaseTest {

    @Test
    fun `parseStepIds handles legacy string-array format`() {
        val parsed = DailyEssentialsUseCase.parseStepIds("""["a","b","c"]""")
        assertEquals(setOf("a", "b", "c"), parsed)
    }

    @Test
    fun `parseStepIds handles the rich medication-log object format`() {
        val json = """[
            {"id":"a","note":"","at":0,"timeOfDay":""},
            {"id":"b","note":"x","at":1,"timeOfDay":"morning"}
        ]"""
        assertEquals(setOf("a", "b"), DailyEssentialsUseCase.parseStepIds(json))
    }

    @Test
    fun `parseStepIds returns empty set for null and empty inputs`() {
        assertTrue(DailyEssentialsUseCase.parseStepIds(null).isEmpty())
        assertTrue(DailyEssentialsUseCase.parseStepIds("").isEmpty())
        assertTrue(DailyEssentialsUseCase.parseStepIds("[]").isEmpty())
    }

    @Test
    fun `parseStepIds swallows malformed json`() {
        assertTrue(DailyEssentialsUseCase.parseStepIds("not valid json").isEmpty())
        assertTrue(DailyEssentialsUseCase.parseStepIds("{\"id\":\"a\"}").isEmpty())
    }

    @Test
    fun `empty state is hidden once the hint has been seen`() {
        val state = DailyEssentialsUiState.empty().copy(hasSeenHint = true)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `state with any populated card is not empty`() {
        val state = DailyEssentialsUiState.empty().copy(
            housework = HabitCardState(
                habitId = 1L,
                name = "Housework",
                icon = "\uD83C\uDFE0",
                color = "#10B981",
                completedToday = false
            )
        )
        assertFalse(state.isEmpty)
    }

    @Test
    fun `state with only a housework routine card is not empty`() {
        val routine = RoutineCardState(
            routineType = "housework",
            displayName = "Housework",
            steps = listOf(
                StepState("dishes", "Dishes", completedToday = false, timeOfDay = "")
            )
        )
        val state = DailyEssentialsUiState.empty().copy(houseworkRoutine = routine)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `leisure budget no longer keeps Daily Essentials non-empty`() {
        val state = DailyEssentialsUiState.empty().copy(
            leisureBudget = LeisureBudgetCardState.empty().copy(minutesLogged = 30)
        )
        assertTrue(state.isEmpty)
    }

    @Test
    fun `schoolwork card with only assignments still shows`() {
        val schoolwork = SchoolworkCardState(
            courses = emptyList(),
            assignmentsDueToday = listOf(
                AssignmentSummary(
                    id = 1L,
                    title = "Essay",
                    courseId = 7L,
                    courseName = "History",
                    courseColor = 0,
                    completed = false
                )
            )
        )
        assertTrue(schoolwork.hasContent)
        val state = DailyEssentialsUiState.empty().copy(schoolwork = schoolwork)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `resolveSelectedTier returns the stored tier when valid`() {
        val tierOrder = listOf("survival", "solid", "full")
        assertEquals("full", DailyEssentialsUseCase.resolveSelectedTier("full", tierOrder))
        assertEquals("survival", DailyEssentialsUseCase.resolveSelectedTier("survival", tierOrder))
    }

    @Test
    fun `resolveSelectedTier falls back to the second-to-last tier for null or blank`() {
        val morningOrder = listOf("survival", "solid", "full")
        assertEquals("solid", DailyEssentialsUseCase.resolveSelectedTier(null, morningOrder))
        assertEquals("solid", DailyEssentialsUseCase.resolveSelectedTier("", morningOrder))

        val bedtimeOrder = listOf("survival", "basic", "solid", "full")
        assertEquals("solid", DailyEssentialsUseCase.resolveSelectedTier(null, bedtimeOrder))

        val houseworkOrder = listOf("quick", "regular", "deep")
        assertEquals("regular", DailyEssentialsUseCase.resolveSelectedTier(null, houseworkOrder))
    }

    @Test
    fun `resolveSelectedTier ignores unknown tiers and falls back`() {
        val tierOrder = listOf("quick", "regular", "deep")
        assertEquals("regular", DailyEssentialsUseCase.resolveSelectedTier("bogus", tierOrder))
    }

    @Test
    fun `resolveSelectedTier returns null when tier order is empty`() {
        assertEquals(null, DailyEssentialsUseCase.resolveSelectedTier("solid", emptyList()))
    }

    @Test
    fun `resolveSelectedTier prefers the user-configured default when the log is missing`() {
        val morningOrder = listOf("survival", "solid", "full")
        assertEquals(
            "survival",
            DailyEssentialsUseCase.resolveSelectedTier(null, morningOrder, "survival")
        )
        assertEquals(
            "full",
            DailyEssentialsUseCase.resolveSelectedTier("", morningOrder, "full")
        )
    }

    @Test
    fun `resolveSelectedTier coerces a stale default not in the order back to penultimate`() {
        val morningOrder = listOf("survival", "solid", "full")
        // "ultra" was retired in a hypothetical later build — the stored
        // default is unknown to the current order and should not poison
        // the read.
        assertEquals(
            "solid",
            DailyEssentialsUseCase.resolveSelectedTier(null, morningOrder, "ultra")
        )
    }

    @Test
    fun `resolveSelectedTier ignores the configured default when the log already has a tier`() {
        val morningOrder = listOf("survival", "solid", "full")
        assertEquals(
            "full",
            DailyEssentialsUseCase.resolveSelectedTier("full", morningOrder, "survival")
        )
    }

    @Test
    fun `RoutineCardState allComplete mirrors every step`() {
        val routine = RoutineCardState(
            routineType = "morning",
            displayName = "Morning Routine",
            steps = listOf(
                StepState("a", "Wash face", completedToday = true, timeOfDay = "morning"),
                StepState("b", "Brush teeth", completedToday = true, timeOfDay = "morning")
            )
        )
        assertTrue(routine.allComplete)
        assertFalse(routine.copy(steps = routine.steps.map { it.copy(completedToday = false) }).allComplete)
    }
}
