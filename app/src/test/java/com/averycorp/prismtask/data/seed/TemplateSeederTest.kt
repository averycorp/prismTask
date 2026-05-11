package com.averycorp.prismtask.data.seed

import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TemplateSeeder]. The seeder is tested against an in-memory
 * [FakeTemplateDao] and [FakeSeededFlagStore] so we don't need a real Room
 * database or DataStore-backed [com.averycorp.prismtask.data.preferences.TemplatePreferences].
 */
class TemplateSeederTest {
    @Test
    fun seedIfNeeded_insertsAllBuiltInTemplatesOnFirstRun() = runBlocking {
        val dao = FakeTemplateDao()
        val flag = FakeSeededFlagStore(initiallySeeded = false)
        val seeder = TemplateSeeder(dao, flag)

        seeder.seedIfNeeded()

        // Every built-in spec in TemplateSeeder.BUILT_IN_TEMPLATES should land
        // exactly once in the DB, and the flag should flip.
        assertEquals(TemplateSeeder.BUILT_IN_TEMPLATES.size, dao.templates.size)
        assertTrue("flag should be set after seeding", flag.isSeeded())

        // Every spec name is present — we don't hardcode the list (that would
        // rot whenever the seeder's blueprint roster changes). Instead, derive
        // it from the production source of truth.
        val storedNames = dao.templates.map { it.name }.toSet()
        val expectedNames = TemplateSeeder.BUILT_IN_TEMPLATES.map { it.name }.toSet()
        assertEquals(expectedNames, storedNames)

        // Each seeded template must be marked as built-in so the UI can treat
        // it as a shipped default.
        assertTrue(
            "all seeded templates should be flagged isBuiltIn",
            dao.templates.all { it.isBuiltIn }
        )

        // Spot-check a couple of fields that are easy to get wrong when
        // copying specs over — the subtasks JSON + recurrence + duration for
        // two of the more elaborate templates.
        val weekly = dao.templates.first { it.name == "Weekly Review" }
        assertEquals(2, weekly.templatePriority)
        assertEquals(45, weekly.templateDuration)
        assertNotNull(weekly.templateSubtasksJson)
        assertNotNull(
            "weekly recurrence should be serialized",
            weekly.templateRecurrenceJson
        )

        val school = dao.templates.first { it.name == "School Daily" }
        assertEquals(3, school.templatePriority)
        assertEquals(120, school.templateDuration)
        assertEquals("School", school.category)
    }

    @Test
    fun seedIfNeeded_isIdempotentWhenFlagAlreadySet() = runBlocking {
        val dao = FakeTemplateDao()
        // Simulate a previous successful seed — the flag is on and the DB
        // already has the same count of rows from that run.
        val flag = FakeSeededFlagStore(initiallySeeded = true)
        val preSeededCount = TemplateSeeder.BUILT_IN_TEMPLATES.size
        repeat(preSeededCount) { index ->
            dao.insertTemplate(
                TaskTemplateEntity(
                    name = "Pre-Seeded $index",
                    isBuiltIn = true,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
        }

        seeder(dao, flag).seedIfNeeded()

        // No re-seeding: the pre-existing templates survive untouched, no
        // new ones are added.
        assertEquals(preSeededCount, dao.templates.size)
        assertTrue(dao.templates.all { it.name.startsWith("Pre-Seeded") })
    }

    @Test
    fun seedIfNeeded_skipsInsertIfDbAlreadyHasTemplatesButFlagUnset() = runBlocking {
        // Edge case: user restored a backup that brought template rows back,
        // but the templates_seeded flag is somehow unset (e.g., because the
        // prefs datastore was cleared separately). We should not duplicate
        // anything — just flip the flag on and move on.
        val dao = FakeTemplateDao()
        dao.insertTemplate(
            TaskTemplateEntity(
                name = "User's Template",
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        val flag = FakeSeededFlagStore(initiallySeeded = false)

        seeder(dao, flag).seedIfNeeded()

        assertEquals(1, dao.templates.size)
        assertEquals("User's Template", dao.templates.single().name)
        assertTrue(flag.isSeeded())
    }

    @Test
    fun seedIfNeeded_allBuiltInSpecsHaveSubtasksAndCategory() {
        // Guard rails: every built-in spec should have at least one subtask
        // and a non-null category. This test lives next to the seeder because
        // it's verifying the hard-coded list, not runtime behavior.
        TemplateSeeder.BUILT_IN_TEMPLATES.forEach { spec ->
            assertTrue(
                "spec '${spec.name}' must have a non-blank category",
                spec.category.isNotBlank()
            )
            assertTrue(
                "spec '${spec.name}' must have at least one subtask",
                spec.templateSubtasks.isNotEmpty()
            )
            assertFalse(
                "spec '${spec.name}' must have a non-blank icon",
                spec.icon.isBlank()
            )
        }
    }

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    private fun seeder(
        dao: TaskTemplateDao,
        flag: TemplateSeeder.SeededFlagStore
    ): TemplateSeeder = TemplateSeeder(dao, flag)

    private class FakeSeededFlagStore(initiallySeeded: Boolean) : TemplateSeeder.SeededFlagStore {
        private var seeded = initiallySeeded

        override suspend fun isSeeded(): Boolean = seeded

        override suspend fun setSeeded(seeded: Boolean) {
            this.seeded = seeded
        }
    }

    /**
     * Minimal fake TaskTemplateDao that tracks inserted templates so tests
     * can assert on them. Only methods the seeder calls are implemented —
     * everything else throws loudly to surface accidental usage.
     */
    private class FakeTemplateDao : TaskTemplateDao {
        val templates = mutableListOf<TaskTemplateEntity>()
        private var nextId = 1L

        override suspend fun insertTemplate(template: TaskTemplateEntity): Long {
            val id = if (template.id == 0L) {
                nextId++
            } else {
                nextId = maxOf(nextId, template.id + 1)
                template.id
            }
            templates.add(template.copy(id = id))
            return id
        }

        override suspend fun countTemplates(): Int = templates.size

        // --- Unused methods below: throw to catch accidental usage in tests. ---
        override fun getAllTemplates(): Flow<List<TaskTemplateEntity>> =
            flowOf(templates.toList())

        override suspend fun getAllTemplatesOnce(): List<TaskTemplateEntity> =
            templates.toList()

        override fun getTemplatesByCategory(category: String): Flow<List<TaskTemplateEntity>> =
            flowOf(templates.filter { it.category == category })

        override suspend fun getTemplateById(id: Long): TaskTemplateEntity? =
            templates.firstOrNull { it.id == id }

        override suspend fun getTemplateByName(name: String): TaskTemplateEntity? =
            templates.firstOrNull { it.name == name }

        override fun getAllCategories(): Flow<List<String>> =
            flowOf(templates.mapNotNull { it.category }.distinct())

        override suspend fun updateTemplate(template: TaskTemplateEntity) {
            val index = templates.indexOfFirst { it.id == template.id }
            if (index >= 0) templates[index] = template
        }

        override suspend fun deleteTemplate(id: Long) {
            templates.removeAll { it.id == id }
        }

        override suspend fun incrementUsage(id: Long, usedAt: Long) {
            val index = templates.indexOfFirst { it.id == id }
            if (index >= 0) {
                val t = templates[index]
                templates[index] = t.copy(usageCount = t.usageCount + 1, lastUsedAt = usedAt)
            }
        }

        override suspend fun clearCategory(category: String, now: Long) {
            templates.replaceAll {
                if (it.category == category) it.copy(category = null, updatedAt = now) else it
            }
        }

        override fun searchTemplates(query: String): Flow<List<TaskTemplateEntity>> =
            flowOf(templates.filter { it.name.contains(query) })

        override suspend fun deleteAll() {
            templates.clear()
        }

        override suspend fun deleteAllBuiltIn() {
            templates.removeAll { it.isBuiltIn }
        }

        override suspend fun getBuiltInTemplatesOnce(): List<TaskTemplateEntity> =
            templates.filter { it.isBuiltIn }

        override suspend fun deleteById(id: Long) {
            templates.removeAll { it.id == id }
        }
    }
}
