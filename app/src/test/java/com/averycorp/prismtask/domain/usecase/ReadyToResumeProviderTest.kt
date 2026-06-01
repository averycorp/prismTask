package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyToResumeProviderTest {

    private val oneDay = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun task(
        id: Long,
        recurring: Boolean = true,
        completed: Boolean = false,
        archivedAt: Long? = null,
        lastEngagementAt: Long? = now - 20 * oneDay,
        override: Int? = null
    ): TaskEntity = TaskEntity(
        id = id,
        title = "t$id",
        recurrenceRule = if (recurring) "FREQ=DAILY" else null,
        isCompleted = completed,
        archivedAt = archivedAt,
        lastEngagementAt = lastEngagementAt,
        dormancyThresholdDaysOverride = override
    )

    @Test
    fun `includes only dormant recurring incomplete unarchived tasks`() {
        val tasks = listOf(
            task(1), // dormant recurring → in
            task(2, recurring = false), // not recurring → out
            task(3, completed = true), // completed → out
            task(4, archivedAt = now), // archived → out
            task(5, lastEngagementAt = now - oneDay), // engaged yesterday → not dormant → out
            task(6, lastEngagementAt = null) // never engaged → not dormant → out
        )
        val result = ReadyToResumeProvider.resume(tasks, globalThresholdDays = 7, dismissedTaskIds = emptySet(), nowMillis = now)
        assertEquals(listOf(1L), result.map { it.task.id })
    }

    @Test
    fun `excludes tasks dismissed for today`() {
        val tasks = listOf(task(1), task(2))
        val result = ReadyToResumeProvider.resume(tasks, 7, dismissedTaskIds = setOf(1L), nowMillis = now)
        assertEquals(listOf(2L), result.map { it.task.id })
    }

    @Test
    fun `sorts longest dormant first`() {
        val tasks = listOf(
            task(1, lastEngagementAt = now - 10 * oneDay),
            task(2, lastEngagementAt = now - 40 * oneDay),
            task(3, lastEngagementAt = now - 25 * oneDay)
        )
        val result = ReadyToResumeProvider.resume(tasks, 7, emptySet(), now)
        assertEquals(listOf(2L, 3L, 1L), result.map { it.task.id })
    }

    @Test
    fun `caps at five`() {
        val tasks = (1L..10L).map { task(it, lastEngagementAt = now - (10 + it) * oneDay) }
        val result = ReadyToResumeProvider.resume(tasks, 7, emptySet(), now)
        assertEquals(5, result.size)
    }

    @Test
    fun `per task override suppresses dormancy below its threshold`() {
        // 10 days dormant; override 30 → not dormant → excluded.
        val tasks = listOf(task(1, lastEngagementAt = now - 10 * oneDay, override = 30))
        val result = ReadyToResumeProvider.resume(tasks, 7, emptySet(), now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `days dormant is reported`() {
        val tasks = listOf(task(1, lastEngagementAt = now - 12 * oneDay))
        val result = ReadyToResumeProvider.resume(tasks, 7, emptySet(), now)
        assertEquals(12L, result.single().daysDormant)
    }
}
