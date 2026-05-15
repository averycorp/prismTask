package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.domain.model.LifeCategory
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-resolver tests for [BalanceContributionsProvider]. The DAO/repository
 * dependencies are mocked because `resolve()` doesn't touch them — the
 * factory functions that hit DAOs are exercised in integration tests.
 */
class BalanceContributionsProviderTest {
    private val provider = BalanceContributionsProvider(
        habitRepository = mockk(relaxed = true),
        leisureSessionDao = mockk(relaxed = true)
    )

    // 2026-04-11 00:00 UTC
    private val now = 1_775_779_200_000L
    private val oneDay = 24L * 60 * 60 * 1000

    private fun habit(id: Long, name: String): HabitEntity =
        HabitEntity(id = id, name = name)

    private fun completion(habitId: Long, at: Long): HabitCompletionEntity =
        HabitCompletionEntity(
            habitId = habitId,
            completedDate = at,
            completedAt = at
        )

    private fun session(at: Long, minutes: Int = 15): LeisureSessionEntity =
        LeisureSessionEntity(
            activityId = null,
            category = "READING",
            durationMinutes = minutes,
            loggedAt = at,
            source = "MANUAL"
        )

    @Test
    fun `habit name resolves to LifeCategory via classifier`() {
        val habits = listOf(
            habit(1, "Morning yoga"),
            habit(2, "Sprint planning")
        )
        val completions = listOf(
            completion(1, now - oneDay),
            completion(2, now - oneDay)
        )
        val out = provider.resolve(habits, completions, emptyList(), now)
        val yogaContribution = out.habits.single { it.completedAt == now - oneDay && it.lifeCategory == LifeCategory.SELF_CARE }
        val sprintContribution = out.habits.single { it.completedAt == now - oneDay && it.lifeCategory == LifeCategory.WORK }
        assertEquals(LifeCategory.SELF_CARE, yogaContribution.lifeCategory)
        assertEquals(LifeCategory.WORK, sprintContribution.lifeCategory)
    }

    @Test
    fun `leisure sessions become leisure contributions with minutes summed for rest signal`() {
        val sessions = listOf(
            session(now - oneDay, minutes = 20),
            session(now - oneDay, minutes = 25),
            // outside the rest window
            session(now - 10 * oneDay, minutes = 30)
        )
        val out = provider.resolve(
            habits = emptyList(),
            completions = emptyList(),
            sessions = sessions,
            now = now,
            restDeficitDays = 2
        )
        assertEquals(3, out.leisure.size)
        assertEquals(3, out.leisureTimestamps.size)
        // Only the two recent sessions count toward the rest signal.
        assertEquals(45, out.leisureMinutesRecent)
    }

    @Test
    fun `self-care habit completions in rest window are counted`() {
        val habits = listOf(habit(1, "Morning yoga"), habit(2, "Standup"))
        val completions = listOf(
            completion(1, now - oneDay),
            completion(1, now - 3 * oneDay),
            completion(2, now - oneDay)
        )
        val out = provider.resolve(
            habits = habits,
            completions = completions,
            sessions = emptyList(),
            now = now,
            restDeficitDays = 2
        )
        // Only the SELF_CARE habit completion inside the 2-day window counts.
        assertEquals(1, out.selfCareHabitCompletionsRecent)
    }

    @Test
    fun `streak break heuristic flags habits with prior-week activity but no last-week activity`() {
        val habits = listOf(habit(1, "Yoga"), habit(2, "Meditate"))
        val completions = listOf(
            // Yoga: completion 10 days ago, nothing since → streak broke
            completion(1, now - 10 * oneDay),
            // Meditate: completion 10 days ago AND yesterday → streak alive
            completion(2, now - 10 * oneDay),
            completion(2, now - oneDay)
        )
        val out = provider.resolve(habits, completions, emptyList(), now)
        assertEquals(1, out.habitStreakBreaks)
    }

    @Test
    fun `empty inputs return EMPTY contributions`() {
        val out = provider.resolve(emptyList(), emptyList(), emptyList(), now)
        assertEquals(BalanceContributions.EMPTY, out)
    }

    @Test
    fun `habit timestamps mirror completion list for cognitive load tracker`() {
        val habits = listOf(habit(1, "Yoga"))
        val completions = listOf(
            completion(1, now - oneDay),
            completion(1, now - 2 * oneDay),
            completion(1, now - 3 * oneDay)
        )
        val out = provider.resolve(habits, completions, emptyList(), now)
        assertEquals(3, out.habitTimestamps.size)
        assertTrue(out.habitTimestamps.containsAll(listOf(now - oneDay, now - 2 * oneDay, now - 3 * oneDay)))
    }
}
