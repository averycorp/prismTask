package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TaskTimingDao
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTimingRepositoryTest {

    private val dao = FakeTaskTimingDao()
    private val syncTracker = mockk<SyncTracker>(relaxed = true)
    private val repo = TaskTimingRepository(dao, syncTracker)

    @Test
    fun `logTime inserts a manual entry with the supplied duration`() = runTest {
        val id = repo.logTime(taskId = 1L, durationMinutes = 25)
        assertNotEquals(0L, id)
        val timings = repo.getTimingsForTaskOnce(1L)
        assertEquals(1, timings.size)
        assertEquals(25, timings[0].durationMinutes)
        assertEquals(TaskTimingEntity.SOURCE_MANUAL, timings[0].source)
    }

    @Test
    fun `logTime defaults source to manual but accepts pomodoro and timer`() = runTest {
        repo.logTime(taskId = 1L, durationMinutes = 10)
        repo.logTime(
            taskId = 1L,
            durationMinutes = 25,
            source = TaskTimingEntity.SOURCE_POMODORO
        )
        repo.logTime(
            taskId = 1L,
            durationMinutes = 5,
            source = TaskTimingEntity.SOURCE_TIMER
        )

        val sources = repo.getTimingsForTaskOnce(1L).map { it.source }.toSet()
        assertEquals(
            setOf(
                TaskTimingEntity.SOURCE_MANUAL,
                TaskTimingEntity.SOURCE_POMODORO,
                TaskTimingEntity.SOURCE_TIMER
            ),
            sources
        )
    }

    @Test
    fun `logTime rejects zero or negative durations`() = runTest {
        runCatching { repo.logTime(1L, 0) }.also { result ->
            assertTrue(
                "expected IllegalArgumentException for 0",
                result.exceptionOrNull() is IllegalArgumentException
            )
        }
        runCatching { repo.logTime(1L, -5) }.also { result ->
            assertTrue(
                "expected IllegalArgumentException for negative",
                result.exceptionOrNull() is IllegalArgumentException
            )
        }
    }

    @Test
    fun `sumMinutesForTask aggregates across multiple entries`() = runTest {
        repo.logTime(1L, 10)
        repo.logTime(1L, 25)
        repo.logTime(1L, 5)
        repo.logTime(2L, 100) // unrelated task

        assertEquals(40, repo.sumMinutesForTask(1L))
        assertEquals(100, repo.sumMinutesForTask(2L))
    }

    @Test
    fun `sumMinutesForTask returns zero when no entries exist`() = runTest {
        assertEquals(0, repo.sumMinutesForTask(99L))
    }

    @Test
    fun `deleteById removes only the targeted entry`() = runTest {
        val id1 = repo.logTime(1L, 10)
        val id2 = repo.logTime(1L, 25)
        repo.deleteById(id1)

        val remaining = repo.getTimingsForTaskOnce(1L)
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining[0].id)
    }

    @Test
    fun `deleteByTaskId removes all entries for that task only`() = runTest {
        repo.logTime(1L, 10)
        repo.logTime(1L, 25)
        repo.logTime(2L, 5)

        repo.deleteByTaskId(1L)

        assertEquals(emptyList<TaskTimingEntity>(), repo.getTimingsForTaskOnce(1L))
        assertEquals(1, repo.getTimingsForTaskOnce(2L).size)
    }

    @Test
    fun `getTimingsInRange filters by created_at window`() = runTest {
        // Manually insert entries with explicit createdAt values
        dao.insert(TaskTimingEntity(taskId = 1L, durationMinutes = 5, createdAt = 100L))
        dao.insert(TaskTimingEntity(taskId = 1L, durationMinutes = 10, createdAt = 200L))
        dao.insert(TaskTimingEntity(taskId = 1L, durationMinutes = 15, createdAt = 300L))

        val collected = (dao as FakeTaskTimingDao).getTimingsInRangeOnce(150L, 250L)
        assertEquals(1, collected.size)
        assertEquals(10, collected[0].durationMinutes)
    }

    @Test
    fun `update replaces an existing timing in place`() = runTest {
        val id = repo.logTime(1L, 10)
        val updated = TaskTimingEntity(
            id = id,
            taskId = 1L,
            durationMinutes = 30,
            notes = "extended"
        )
        repo.update(updated)

        val row = dao.getByIdOnce(id)
        assertEquals(30, row?.durationMinutes)
        assertEquals("extended", row?.notes)
    }

    @Test
    fun `getByIdOnce returns null when the id is unknown`() = runTest {
        assertNull(dao.getByIdOnce(999L))
    }

    @Test
    fun `logTime tracks a create on the sync queue`() = runTest {
        val id = repo.logTime(taskId = 1L, durationMinutes = 25)
        coVerify { syncTracker.trackCreate(id, "task_timing") }
    }

    @Test
    fun `update tracks an update on the sync queue`() = runTest {
        val id = repo.logTime(1L, 10)
        repo.update(TaskTimingEntity(id = id, taskId = 1L, durationMinutes = 30))
        coVerify { syncTracker.trackUpdate(id, "task_timing") }
    }

    @Test
    fun `deleteById tracks a delete on the sync queue`() = runTest {
        val id = repo.logTime(1L, 10)
        repo.deleteById(id)
        coVerify { syncTracker.trackDelete(id, "task_timing") }
    }

    @Test
    fun `deleteByTaskId tracks a delete for every removed timing`() = runTest {
        val id1 = repo.logTime(1L, 10)
        val id2 = repo.logTime(1L, 25)
        repo.deleteByTaskId(1L)
        coVerify { syncTracker.trackDelete(id1, "task_timing") }
        coVerify { syncTracker.trackDelete(id2, "task_timing") }
    }

    /** In-memory DAO fake for JVM-only repository tests. */
    private class FakeTaskTimingDao : TaskTimingDao {
        private val rows = mutableListOf<TaskTimingEntity>()
        private var nextId = 1L

        override suspend fun insert(timing: TaskTimingEntity): Long {
            val id = if (timing.id == 0L) {
                nextId++
            } else {
                timing.id.also { nextId = maxOf(nextId, it + 1) }
            }
            rows.add(timing.copy(id = id))
            return id
        }

        override suspend fun update(timing: TaskTimingEntity) {
            val index = rows.indexOfFirst { it.id == timing.id }
            if (index >= 0) rows[index] = timing
        }

        override suspend fun delete(timing: TaskTimingEntity) {
            rows.removeAll { it.id == timing.id }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteByTaskId(taskId: Long) {
            rows.removeAll { it.taskId == taskId }
        }

        override suspend fun getByIdOnce(id: Long): TaskTimingEntity? =
            rows.firstOrNull { it.id == id }

        override fun getTimingsForTask(taskId: Long): Flow<List<TaskTimingEntity>> =
            flowOf(rows.filter { it.taskId == taskId }.sortedByDescending { it.createdAt })

        override suspend fun getTimingsForTaskOnce(taskId: Long): List<TaskTimingEntity> =
            rows.filter { it.taskId == taskId }.sortedByDescending { it.createdAt }

        override suspend fun getAllOnce(): List<TaskTimingEntity> =
            rows.sortedByDescending { it.createdAt }

        override fun getTimingsInRange(startMillis: Long, endMillis: Long): Flow<List<TaskTimingEntity>> =
            flowOf(getTimingsInRangeOnce(startMillis, endMillis))

        fun getTimingsInRangeOnce(startMillis: Long, endMillis: Long): List<TaskTimingEntity> =
            rows.filter { it.createdAt in startMillis until endMillis }.sortedBy { it.createdAt }

        override suspend fun sumMinutesForTask(taskId: Long): Int =
            rows.filter { it.taskId == taskId }.sumOf { it.durationMinutes }

        override fun observeSumMinutesForTask(taskId: Long): Flow<Int> =
            flowOf(rows.filter { it.taskId == taskId }.sumOf { it.durationMinutes })

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
