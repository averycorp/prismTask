package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.domain.model.CognitiveLoad
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class CognitiveLoadBalanceTrackerTest {
    private val tracker = CognitiveLoadBalanceTracker()
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-04-11 00:00 UTC
    private val now = 1_775_779_200_000L
    private val oneDay = 24L * 60 * 60 * 1000

    private fun task(
        id: Long,
        load: CognitiveLoad?,
        dueDate: Long = now - oneDay,
        estimatedDuration: Int? = null
    ): TaskEntity = TaskEntity(
        id = id,
        title = "task $id",
        dueDate = dueDate,
        createdAt = dueDate,
        updatedAt = dueDate,
        cognitiveLoad = load?.name,
        estimatedDuration = estimatedDuration
    )

    @Test
    fun `empty list produces empty load balance`() {
        val state = tracker.compute(emptyList(), now = now, timeZone = utc)
        assertEquals(0, state.totalTracked)
        assertEquals(CognitiveLoad.UNCATEGORIZED, state.dominantLoad)
    }

    @Test
    fun `uncategorized tasks are excluded from counts`() {
        val tasks = listOf(
            task(1, null),
            task(2, null),
            task(3, CognitiveLoad.HARD)
        )
        val state = tracker.compute(tasks, now = now, timeZone = utc)
        assertEquals(1, state.totalTracked)
        assertEquals(CognitiveLoad.HARD, state.dominantLoad)
    }

    @Test
    fun `ratios normalize to one across the three tracked loads`() {
        val tasks = listOf(
            task(1, CognitiveLoad.EASY),
            task(2, CognitiveLoad.EASY),
            task(3, CognitiveLoad.MEDIUM),
            task(4, CognitiveLoad.HARD)
        )
        val state = tracker.compute(tasks, now = now, timeZone = utc)
        assertEquals(0.5f, state.currentRatios[CognitiveLoad.EASY]!!, 0.001f)
        assertEquals(0.25f, state.currentRatios[CognitiveLoad.MEDIUM]!!, 0.001f)
        assertEquals(0.25f, state.currentRatios[CognitiveLoad.HARD]!!, 0.001f)
        assertEquals(CognitiveLoad.EASY, state.dominantLoad)
    }

    @Test
    fun `tasks outside the 7 day window are excluded from current ratios`() {
        val tasks = listOf(
            task(1, CognitiveLoad.HARD, dueDate = now - 30 * oneDay),
            task(2, CognitiveLoad.MEDIUM, dueDate = now - 2 * oneDay)
        )
        val state = tracker.compute(tasks, now = now, timeZone = utc)
        assertEquals(1, state.totalTracked)
        assertEquals(1f, state.currentRatios[CognitiveLoad.MEDIUM]!!, 0.001f)
    }

    @Test
    fun `rolling ratios include older tasks up to 28 days`() {
        val tasks = listOf(
            task(1, CognitiveLoad.HARD, dueDate = now - 20 * oneDay),
            task(2, CognitiveLoad.EASY, dueDate = now - 2 * oneDay)
        )
        val state = tracker.compute(tasks, now = now, timeZone = utc)
        assertEquals(0.5f, state.rollingRatios[CognitiveLoad.HARD]!!, 0.001f)
        assertEquals(0.5f, state.rollingRatios[CognitiveLoad.EASY]!!, 0.001f)
    }

    @Test
    fun `4AM SoD includes tasks on the logical previous day before midnight`() {
        // Mirrors ModeBalanceTrackerTest's 4AM SoD case for cross-axis
        // SoD-pattern parity (PR #1060 fix).
        val nowAt0230 = now + 2L * 3600 * 1000 + 30L * 60 * 1000
        val taskAt0500 = now - 7 * oneDay + 5L * 3600 * 1000
        val taskAt0300 = now - 7 * oneDay + 3L * 3600 * 1000
        val tasks = listOf(
            task(1, CognitiveLoad.MEDIUM, dueDate = taskAt0500),
            task(2, CognitiveLoad.HARD, dueDate = taskAt0300)
        )

        val sodState = tracker.compute(
            tasks,
            now = nowAt0230,
            timeZone = utc,
            dayStartHour = 4
        )
        assertEquals(1, sodState.totalTracked)
        assertEquals(1f, sodState.currentRatios[CognitiveLoad.MEDIUM]!!, 0.001f)

        val midnightState = tracker.compute(tasks, now = nowAt0230, timeZone = utc)
        assertEquals(0, midnightState.totalTracked)
    }

    @Test
    fun `config isValid true when sums to 1`() {
        assertEquals(true, CognitiveLoadBalanceConfig().isValid())
        assertEquals(true, CognitiveLoadBalanceConfig(0.5f, 0.3f, 0.2f).isValid())
    }

    @Test
    fun `config isValid false when sum is wrong`() {
        assertEquals(false, CognitiveLoadBalanceConfig(0.5f, 0.5f, 0.5f).isValid())
    }

    @Test
    fun `habit completions count toward EASY load`() {
        // One HARD task + three habits → 25% HARD, 75% EASY.
        val tasks = listOf(task(1, CognitiveLoad.HARD))
        val state = tracker.compute(
            allTasks = tasks,
            now = now,
            timeZone = utc,
            habitCompletionTimestamps = listOf(now - oneDay, now - oneDay, now - oneDay)
        )
        assertEquals(4, state.totalTracked)
        assertEquals(0.25f, state.currentRatios[CognitiveLoad.HARD]!!, 0.001f)
        assertEquals(0.75f, state.currentRatios[CognitiveLoad.EASY]!!, 0.001f)
        assertEquals(CognitiveLoad.EASY, state.dominantLoad)
    }

    @Test
    fun `leisure sessions count toward EASY load`() {
        val tasks = listOf(task(1, CognitiveLoad.HARD))
        val state = tracker.compute(
            allTasks = tasks,
            now = now,
            timeZone = utc,
            leisureSessionTimestamps = listOf(now - oneDay, now - oneDay)
        )
        assertEquals(3, state.totalTracked)
        assertEquals(0.333f, state.currentRatios[CognitiveLoad.HARD]!!, 0.01f)
        assertEquals(0.667f, state.currentRatios[CognitiveLoad.EASY]!!, 0.01f)
    }

    @Test
    fun `habit and leisure timestamps outside the window are excluded`() {
        val state = tracker.compute(
            allTasks = emptyList(),
            now = now,
            timeZone = utc,
            habitCompletionTimestamps = listOf(now - 30 * oneDay),
            leisureSessionTimestamps = listOf(now - 30 * oneDay)
        )
        assertEquals(0, state.totalTracked)
    }

    @Test
    fun `cognitive load ratios weight tasks by estimatedDuration`() {
        // 1 HARD task at 90 min vs 3 EASY tasks at 10 min each → 90 vs 30 min.
        val tasks = listOf(
            task(1, CognitiveLoad.HARD, estimatedDuration = 90),
            task(2, CognitiveLoad.EASY, estimatedDuration = 10),
            task(3, CognitiveLoad.EASY, estimatedDuration = 10),
            task(4, CognitiveLoad.EASY, estimatedDuration = 10)
        )
        val state = tracker.compute(allTasks = tasks, now = now, timeZone = utc)
        assertEquals(0.75f, state.currentRatios[CognitiveLoad.HARD]!!, 0.001f)
        assertEquals(0.25f, state.currentRatios[CognitiveLoad.EASY]!!, 0.001f)
        // Count-based dominance would say EASY (3 tasks); minute-based says HARD.
        assertEquals(CognitiveLoad.HARD, state.dominantLoad)
        assertEquals(4, state.totalTracked)
    }

    @Test
    fun `tasks without estimatedDuration use the configured default`() {
        val tasks = listOf(
            task(1, CognitiveLoad.HARD, estimatedDuration = 120),
            task(2, CognitiveLoad.EASY, estimatedDuration = null)
        )
        val state = tracker.compute(
            allTasks = tasks,
            now = now,
            timeZone = utc,
            defaultDurationMinutes = 60
        )
        assertEquals(2f / 3f, state.currentRatios[CognitiveLoad.HARD]!!, 0.001f)
        assertEquals(1f / 3f, state.currentRatios[CognitiveLoad.EASY]!!, 0.001f)
    }
}
