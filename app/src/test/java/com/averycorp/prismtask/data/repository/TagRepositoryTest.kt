package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.remote.SyncTracker
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TagRepository] using an in-memory fake [TagDao].
 */
class TagRepositoryTest {
    private lateinit var tagDao: FakeTagDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var repo: TagRepository

    @Before
    fun setUp() {
        tagDao = FakeTagDao()
        syncTracker = mockk(relaxed = true)
        repo = TagRepository(tagDao, syncTracker)
    }

    @Test
    fun addTag_storesFieldsAndTracksCreate() = runBlocking {
        val id = repo.addTag(name = "urgent", color = "#FF0000")

        val stored = tagDao.tags.single { it.id == id }
        assertEquals("urgent", stored.name)
        assertEquals("#FF0000", stored.color)
        coVerify { syncTracker.trackCreate(id, "tag") }
    }

    @Test
    fun addTag_defaultsColorWhenUnspecified() = runBlocking {
        val id = repo.addTag(name = "work")
        val stored = tagDao.tags.single { it.id == id }
        assertEquals("#6B7280", stored.color)
    }

    @Test
    fun updateTag_persistsChangesAndTracksUpdate() = runBlocking {
        val id = tagDao.insert(TagEntity(name = "home", color = "#000000"))
        val existing = tagDao.tags.single { it.id == id }

        repo.updateTag(existing.copy(name = "HOME", color = "#FFFFFF"))

        val after = tagDao.tags.single { it.id == id }
        assertEquals("HOME", after.name)
        assertEquals("#FFFFFF", after.color)
        coVerify { syncTracker.trackUpdate(id, "tag") }
    }

    @Test
    fun deleteTag_removesTagAndTracksDelete() = runBlocking {
        val id = tagDao.insert(TagEntity(name = "old"))
        val existing = tagDao.tags.single { it.id == id }

        repo.deleteTag(existing)

        assertTrue(tagDao.tags.none { it.id == id })
        coVerify { syncTracker.trackDelete(id, "tag") }
    }

    @Test
    fun addTagToTask_insertsCrossRefLink() = runBlocking {
        repo.addTagToTask(taskId = 10L, tagId = 20L)
        val refs = tagDao.crossRefs
        assertEquals(1, refs.size)
        assertEquals(10L, refs.single().taskId)
        assertEquals(20L, refs.single().tagId)
    }

    @Test
    fun removeTagFromTask_deletesSpecificLink() = runBlocking {
        tagDao.addTagToTask(TaskTagCrossRef(taskId = 10L, tagId = 20L))
        tagDao.addTagToTask(TaskTagCrossRef(taskId = 10L, tagId = 30L))

        repo.removeTagFromTask(taskId = 10L, tagId = 20L)

        assertEquals(1, tagDao.crossRefs.size)
        assertEquals(30L, tagDao.crossRefs.single().tagId)
    }

    @Test
    fun setTagsForTask_replacesAllExistingLinks() = runBlocking {
        // Seed with two initial tags.
        tagDao.addTagToTask(TaskTagCrossRef(taskId = 10L, tagId = 1L))
        tagDao.addTagToTask(TaskTagCrossRef(taskId = 10L, tagId = 2L))

        // Replace with a different set.
        repo.setTagsForTask(taskId = 10L, tagIds = listOf(3L, 4L, 5L))

        val remaining = tagDao.crossRefs.filter { it.taskId == 10L }
        assertEquals(3, remaining.size)
        assertEquals(setOf(3L, 4L, 5L), remaining.map { it.tagId }.toSet())
    }

    @Test
    fun setTagsForTask_emptyListClearsAllLinks() = runBlocking {
        tagDao.addTagToTask(TaskTagCrossRef(taskId = 10L, tagId = 1L))
        tagDao.addTagToTask(TaskTagCrossRef(taskId = 10L, tagId = 2L))

        repo.setTagsForTask(taskId = 10L, tagIds = emptyList())

        assertTrue(tagDao.crossRefs.none { it.taskId == 10L })
    }

    @Test
    fun getAllTags_flowReflectsInsertedTags() = runBlocking {
        tagDao.insert(TagEntity(name = "a"))
        tagDao.insert(TagEntity(name = "b"))

        val all = repo.getAllTags().first()
        assertEquals(2, all.size)
    }

    // ---------------------------------------------------------------------
    // Fake DAO
    // ---------------------------------------------------------------------

    private class FakeTagDao : TagDao {
        val tags = mutableListOf<TagEntity>()
        val crossRefs = mutableListOf<TaskTagCrossRef>()
        private var nextId = 1L

        override suspend fun insert(tag: TagEntity): Long {
            val id = if (tag.id == 0L) nextId++ else tag.id.also { nextId = maxOf(nextId, it + 1) }
            tags.removeAll { it.id == id }
            tags.add(tag.copy(id = id))
            return id
        }

        override suspend fun update(tag: TagEntity) {
            val idx = tags.indexOfFirst { it.id == tag.id }
            if (idx >= 0) tags[idx] = tag
        }

        override suspend fun delete(tag: TagEntity) {
            tags.removeAll { it.id == tag.id }
            crossRefs.removeAll { it.tagId == tag.id }
        }

        override fun getAllTags(): Flow<List<TagEntity>> = flowOf(tags.toList())

        override fun getTagById(id: Long): Flow<TagEntity?> =
            flowOf(tags.firstOrNull { it.id == id })

        override fun getTagsForTask(taskId: Long): Flow<List<TagEntity>> {
            val ids = crossRefs.filter { it.taskId == taskId }.map { it.tagId }.toSet()
            return flowOf(tags.filter { it.id in ids })
        }

        override suspend fun getAllTagsOnce(): List<TagEntity> = tags.toList()

        override suspend fun getTagByIdOnce(id: Long): TagEntity? =
            tags.firstOrNull { it.id == id }

        override suspend fun getTagByNameOnce(name: String): TagEntity? =
            tags.firstOrNull { it.name.equals(name, ignoreCase = true) }

        override suspend fun getTagIdsForTaskOnce(taskId: Long): List<Long> =
            crossRefs.filter { it.taskId == taskId }.map { it.tagId }

        override suspend fun getTagNamesForTaskOnce(taskId: Long): List<String> {
            val ids = crossRefs.filter { it.taskId == taskId }.map { it.tagId }.toSet()
            return tags.filter { it.id in ids }.map { it.name }
        }

        override fun searchTags(query: String): Flow<List<TagEntity>> =
            flowOf(tags.filter { it.name.contains(query, ignoreCase = true) })

        override suspend fun addTagToTask(crossRef: TaskTagCrossRef) {
            // Replace-semantics matching the DAO's INSERT OR REPLACE.
            crossRefs.removeAll { it.taskId == crossRef.taskId && it.tagId == crossRef.tagId }
            crossRefs.add(crossRef)
        }

        override suspend fun removeTagFromTask(taskId: Long, tagId: Long) {
            crossRefs.removeAll { it.taskId == taskId && it.tagId == tagId }
        }

        override suspend fun removeAllTagsFromTask(taskId: Long) {
            crossRefs.removeAll { it.taskId == taskId }
        }

        override suspend fun deleteAll() {
            tags.clear()
        }

        override suspend fun deleteAllCrossRefs() {
            crossRefs.clear()
        }
    }
}
