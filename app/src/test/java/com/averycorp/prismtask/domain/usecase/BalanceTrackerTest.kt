package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.LifeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BalanceTrackerTest {
    private val tracker = BalanceTracker()
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-04-11 00:00 UTC
    private val now = 1_775_779_200_000L
    private val oneDay = 24L * 60 * 60 * 1000

    private fun task(
        id: Long,
        category: LifeCategory?,
        dueDate: Long = now - oneDay
    ): TaskEntity = TaskEntity(
        id = id,
        title = "task $id",
        dueDate = dueDate,
        createdAt = dueDate,
        updatedAt = dueDate,
        lifeCategory = category?.name
    )

    @Test
    fun `empty task list produces empty balance`() {
        val state = tracker.compute(emptyList(), BalanceConfig(), now, utc)
        assertEquals(0, state.totalTracked)
        assertEquals(LifeCategory.UNCATEGORIZED, state.dominantCategory)
        assertFalse(state.isOverloaded)
    }

    @Test
    fun `uncategorized tasks are excluded from counts`() {
        val tasks = listOf(
            task(1, null),
            task(2, null),
            task(3, LifeCategory.SELF_CARE)
        )
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertEquals(1, state.totalTracked)
        assertEquals(LifeCategory.SELF_CARE, state.dominantCategory)
    }

    @Test
    fun `ratios normalize to one`() {
        val tasks = listOf(
            task(1, LifeCategory.WORK),
            task(2, LifeCategory.WORK),
            task(3, LifeCategory.WORK),
            task(4, LifeCategory.SELF_CARE)
        )
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertEquals(0.75f, state.currentRatios[LifeCategory.WORK]!!, 0.001f)
        assertEquals(0.25f, state.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)
        assertEquals(LifeCategory.WORK, state.dominantCategory)
    }

    @Test
    fun `overload triggers when work exceeds target plus threshold`() {
        // 8 work + 2 self-care → 80% work. Target 40% + 10% = 50%, 80 > 50.
        val tasks = (1..8).map { task(it.toLong(), LifeCategory.WORK) } +
            (9..10).map { task(it.toLong(), LifeCategory.SELF_CARE) }
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertTrue(state.isOverloaded)
    }

    @Test
    fun `overload does not trigger when at threshold`() {
        // 5 work + 5 personal → 50% work. Target 40% + 10% = 50%, so NOT > 50%.
        val tasks = (1..5).map { task(it.toLong(), LifeCategory.WORK) } +
            (6..10).map { task(it.toLong(), LifeCategory.PERSONAL) }
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertFalse(state.isOverloaded)
    }

    @Test
    fun `tasks outside the 7 day window are excluded from current ratios`() {
        val tasks = listOf(
            // too old
            task(1, LifeCategory.WORK, dueDate = now - 30 * oneDay),
            task(2, LifeCategory.SELF_CARE, dueDate = now - 2 * oneDay)
        )
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertEquals(1, state.totalTracked)
        assertEquals(1f, state.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)
    }

    @Test
    fun `rolling ratios include older tasks up to 28 days`() {
        val tasks = listOf(
            task(1, LifeCategory.WORK, dueDate = now - 20 * oneDay),
            task(2, LifeCategory.SELF_CARE, dueDate = now - 2 * oneDay)
        )
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        // Rolling window covers both.
        assertEquals(0.5f, state.rollingRatios[LifeCategory.WORK]!!, 0.001f)
        assertEquals(0.5f, state.rollingRatios[LifeCategory.SELF_CARE]!!, 0.001f)
    }

    @Test
    fun `dominant category picks the max ratio`() {
        val tasks = listOf(
            task(1, LifeCategory.WORK),
            task(2, LifeCategory.PERSONAL),
            task(3, LifeCategory.PERSONAL)
        )
        val state = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertEquals(LifeCategory.PERSONAL, state.dominantCategory)
    }

    @Test
    fun `4AM SoD includes tasks on the logical previous day before midnight`() {
        // now = 2026-04-11 02:30 UTC. With dayStartHour = 4, the user is
        // logically still on 2026-04-10. The 7-day window should run from
        // 2026-04-04 04:00 UTC through 2026-04-11 02:30 UTC inclusive.
        val nowAt0230 = now + 2L * 3600 * 1000 + 30L * 60 * 1000
        val cutoff04At0500 = now - 7 * oneDay + 5L * 3600 * 1000 // 2026-04-04 05:00 UTC
        val cutoff04At0300 = now - 7 * oneDay + 3L * 3600 * 1000 // 2026-04-04 03:00 UTC
        val tasks = listOf(
            task(1, LifeCategory.WORK, dueDate = cutoff04At0500),
            task(2, LifeCategory.SELF_CARE, dueDate = cutoff04At0300)
        )

        val sodState = tracker.compute(
            tasks,
            BalanceConfig(),
            now = nowAt0230,
            timeZone = utc,
            dayStartHour = 4
        )
        assertEquals(1, sodState.totalTracked)
        assertEquals(1f, sodState.currentRatios[LifeCategory.WORK]!!, 0.001f)
        assertEquals(0f, sodState.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)

        // Without SoD (default dayStartHour = 0), system midnight at
        // 2026-04-11 00:00 UTC snaps "today" forward, so the 7-day window
        // starts at 2026-04-05 00:00 UTC and excludes both tasks.
        val midnightState = tracker.compute(
            tasks,
            BalanceConfig(),
            now = nowAt0230,
            timeZone = utc
        )
        assertEquals(0, midnightState.totalTracked)
    }

    @Test
    fun `SoD has no effect when current time is past the day-start`() {
        // now = 2026-04-11 06:00 UTC, well past a 4 AM SoD. The 7-day window
        // should start at 2026-04-05 04:00 UTC. A task at 2026-04-05 03:00
        // UTC is excluded; a task at 2026-04-05 05:00 UTC is included.
        val nowAt0600 = now + 6L * 3600 * 1000
        val before = now - 6 * oneDay + 3L * 3600 * 1000 // 2026-04-05 03:00 UTC
        val after = now - 6 * oneDay + 5L * 3600 * 1000 // 2026-04-05 05:00 UTC
        val tasks = listOf(
            task(1, LifeCategory.WORK, dueDate = before),
            task(2, LifeCategory.SELF_CARE, dueDate = after)
        )

        val state = tracker.compute(
            tasks,
            BalanceConfig(),
            now = nowAt0600,
            timeZone = utc,
            dayStartHour = 4
        )
        assertEquals(1, state.totalTracked)
        assertEquals(1f, state.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)
    }

    @Test
    fun `config isValid true when sums to 1`() {
        val config = BalanceConfig(
            workTarget = 0.40f,
            personalTarget = 0.25f,
            selfCareTarget = 0.20f,
            healthTarget = 0.15f
        )
        assertTrue(config.isValid())
    }

    @Test
    fun `config isValid false when sum is wrong`() {
        val config = BalanceConfig(
            workTarget = 0.50f,
            personalTarget = 0.50f,
            selfCareTarget = 0.50f,
            healthTarget = 0.50f
        )
        assertFalse(config.isValid())
    }

    @Test
    fun `habit completions in window contribute to their resolved category`() {
        // Two work tasks + two SELF_CARE habit completions → 50% SELF_CARE.
        val tasks = listOf(
            task(1, LifeCategory.WORK),
            task(2, LifeCategory.WORK)
        )
        val habits = listOf(
            HabitContribution(now - oneDay, LifeCategory.SELF_CARE),
            HabitContribution(now - 2 * oneDay, LifeCategory.SELF_CARE)
        )
        val state = tracker.compute(
            allTasks = tasks,
            config = BalanceConfig(),
            now = now,
            timeZone = utc,
            habitContributions = habits
        )
        assertEquals(4, state.totalTracked)
        assertEquals(0.5f, state.currentRatios[LifeCategory.WORK]!!, 0.001f)
        assertEquals(0.5f, state.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)
    }

    @Test
    fun `leisure sessions count toward SELF_CARE`() {
        val tasks = listOf(task(1, LifeCategory.WORK))
        val leisure = listOf(
            LeisureContribution(now - oneDay),
            LeisureContribution(now - 2 * oneDay),
            LeisureContribution(now - 3 * oneDay)
        )
        val state = tracker.compute(
            allTasks = tasks,
            config = BalanceConfig(),
            now = now,
            timeZone = utc,
            leisureContributions = leisure
        )
        assertEquals(4, state.totalTracked)
        assertEquals(0.25f, state.currentRatios[LifeCategory.WORK]!!, 0.001f)
        assertEquals(0.75f, state.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)
        assertEquals(LifeCategory.SELF_CARE, state.dominantCategory)
    }

    @Test
    fun `habit completions outside the window are excluded`() {
        val habits = listOf(
            HabitContribution(now - 2 * oneDay, LifeCategory.SELF_CARE),
            HabitContribution(now - 30 * oneDay, LifeCategory.SELF_CARE)
        )
        val state = tracker.compute(
            allTasks = emptyList(),
            config = BalanceConfig(),
            now = now,
            timeZone = utc,
            habitContributions = habits
        )
        assertEquals(1, state.totalTracked)
        assertEquals(1f, state.currentRatios[LifeCategory.SELF_CARE]!!, 0.001f)
    }

    @Test
    fun `uncategorized habits are excluded from ratios`() {
        val habits = listOf(
            HabitContribution(now - oneDay, LifeCategory.UNCATEGORIZED),
            HabitContribution(now - oneDay, LifeCategory.HEALTH)
        )
        val state = tracker.compute(
            allTasks = emptyList(),
            config = BalanceConfig(),
            now = now,
            timeZone = utc,
            habitContributions = habits
        )
        assertEquals(1, state.totalTracked)
        assertEquals(1f, state.currentRatios[LifeCategory.HEALTH]!!, 0.001f)
    }

    @Test
    fun `habit and leisure activity can shift work ratio below overload`() {
        // 8 work tasks alone would push work to 80% → overloaded.
        val tasks = (1..8).map { task(it.toLong(), LifeCategory.WORK) }
        val noContrib = tracker.compute(tasks, BalanceConfig(), now, utc)
        assertTrue(noContrib.isOverloaded)
        // Adding 8 leisure sessions + 4 self-care habits dilutes work to 8/20 = 40%.
        val habits = (1..4).map { HabitContribution(now - oneDay, LifeCategory.SELF_CARE) }
        val leisure = (1..8).map { LeisureContribution(now - oneDay) }
        val balanced = tracker.compute(
            allTasks = tasks,
            config = BalanceConfig(),
            now = now,
            timeZone = utc,
            habitContributions = habits,
            leisureContributions = leisure
        )
        assertFalse(balanced.isOverloaded)
        assertEquals(0.4f, balanced.currentRatios[LifeCategory.WORK]!!, 0.001f)
    }
}
