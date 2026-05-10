package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.SavedFilterDao
import com.averycorp.prismtask.data.local.entity.SavedFilterEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.model.TagFilterMode
import com.averycorp.prismtask.domain.model.TaskFilter
import com.google.gson.Gson
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SavedFilterRepositoryTest {
    private lateinit var dao: FakeSavedFilterDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var repo: SavedFilterRepository

    @Before
    fun setUp() {
        dao = FakeSavedFilterDao()
        syncTracker = mockk(relaxed = true)
        repo = SavedFilterRepository(dao, syncTracker, Gson())
    }

    @Test
    fun savePreset_insertsNewRowAndTracksCreate() = runBlocking {
        val filter = TaskFilter(
            selectedPriorities = listOf(3, 4),
            showFlaggedOnly = true,
            selectedLifeCategories = listOf(LifeCategory.WORK)
        )

        val id = repo.savePreset(name = "Urgent Work", filter = filter)

        val stored = dao.rows.single()
        assertEquals(id, stored.id)
        assertEquals("Urgent Work", stored.name)
        assertNotNull(stored.filterJson)
        coVerify { syncTracker.trackCreate(id, "saved_filter") }
    }

    @Test
    fun savePreset_overwritesByName_andTracksUpdate() = runBlocking {
        val first = repo.savePreset("Today", TaskFilter(showFlaggedOnly = true))
        val second = repo.savePreset(
            "Today",
            TaskFilter(showFlaggedOnly = false, selectedPriorities = listOf(4))
        )

        assertEquals(first, second)
        assertEquals(1, dao.rows.size)
        coVerify { syncTracker.trackUpdate(first, "saved_filter") }
    }

    @Test
    fun savePreset_trimsAndRejectsBlank() = runBlocking {
        try {
            repo.savePreset("   ", TaskFilter())
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
        assertTrue(dao.rows.isEmpty())

        val id = repo.savePreset("  Padded  ", TaskFilter())
        assertEquals("Padded", dao.rows.single { it.id == id }.name)
    }

    @Test
    fun savePreset_assignsIncrementingSortOrder() = runBlocking {
        val a = repo.savePreset("A", TaskFilter())
        val b = repo.savePreset("B", TaskFilter())
        val c = repo.savePreset("C", TaskFilter())
        assertEquals(0, dao.rows.single { it.id == a }.sortOrder)
        assertEquals(1, dao.rows.single { it.id == b }.sortOrder)
        assertEquals(2, dao.rows.single { it.id == c }.sortOrder)
    }

    @Test
    fun decode_roundTripsAllTaskFilterFields() = runBlocking {
        val filter = TaskFilter(
            selectedTagIds = listOf(1, 2, 3),
            tagFilterMode = TagFilterMode.ALL,
            selectedPriorities = listOf(2, 4),
            selectedProjectIds = listOf(7L, null),
            showCompleted = true,
            showArchived = true,
            searchQuery = "groceries",
            showFlaggedOnly = true,
            selectedLifeCategories = listOf(LifeCategory.SELF_CARE, LifeCategory.HEALTH)
        )

        val id = repo.savePreset("Everything", filter)
        val stored = dao.rows.single { it.id == id }

        val decoded = repo.decode(stored)
        assertEquals(filter, decoded)
    }

    @Test
    fun decode_returnsNullOnUnparseableJson() {
        val bad = SavedFilterEntity(
            id = 99,
            name = "broken",
            filterJson = "{not json"
        )
        assertNull(repo.decode(bad))
    }

    @Test
    fun deletePreset_removesRowAndTracksDelete() = runBlocking {
        val id = repo.savePreset("Temp", TaskFilter())
        assertEquals(1, dao.rows.size)

        repo.deletePreset(id)

        assertTrue(dao.rows.isEmpty())
        coVerify { syncTracker.trackDelete(id, "saved_filter") }
    }

    @Test
    fun getAll_emitsCurrentRows() = runBlocking {
        repo.savePreset("Alpha", TaskFilter())
        repo.savePreset("Beta", TaskFilter())
        val all = repo.getAll().first()
        assertEquals(setOf("Alpha", "Beta"), all.map { it.name }.toSet())
    }

    private class FakeSavedFilterDao : SavedFilterDao {
        val rows = mutableListOf<SavedFilterEntity>()
        private var nextId = 1L

        override fun getAll(): Flow<List<SavedFilterEntity>> =
            flowOf(rows.sortedBy { it.sortOrder }.toList())

        override suspend fun getAllOnce(): List<SavedFilterEntity> =
            rows.sortedBy { it.sortOrder }.toList()

        override suspend fun getByName(name: String): SavedFilterEntity? =
            rows.firstOrNull { it.name == name }

        override suspend fun insert(filter: SavedFilterEntity): Long {
            val id = if (filter.id == 0L) nextId++ else filter.id.also { nextId = maxOf(nextId, it + 1) }
            rows.removeAll { it.id == id }
            rows.add(filter.copy(id = id))
            return id
        }

        override suspend fun update(filter: SavedFilterEntity) {
            val idx = rows.indexOfFirst { it.id == filter.id }
            if (idx >= 0) rows[idx] = filter
        }

        override suspend fun delete(filter: SavedFilterEntity) {
            rows.removeAll { it.id == filter.id }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun count(): Int = rows.size

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun getByIdOnce(id: Long): SavedFilterEntity? =
            rows.firstOrNull { it.id == id }

        override suspend fun getByCloudIdOnce(cloudId: String): SavedFilterEntity? =
            rows.firstOrNull { it.cloudId == cloudId }

        override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
        }
    }
}
