package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MilestoneDao
import com.averycorp.prismtask.data.local.dao.ProjectAggregateRow
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.ProjectPhaseDao
import com.averycorp.prismtask.data.local.dao.ProjectRiskDao
import com.averycorp.prismtask.data.local.dao.ProjectWithCount
import com.averycorp.prismtask.data.local.entity.MilestoneEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.ProjectStatus
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end style tests for [ProjectRepository] using in-memory fakes for
 * both [ProjectDao] and [MilestoneDao]. SyncTracker is relaxed-mocked so we
 * can verify the side effects without caring about its internals.
 */
class ProjectRepositoryTest {
    private lateinit var projectDao: FakeProjectDao
    private lateinit var milestoneDao: FakeMilestoneDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var repo: ProjectRepository

    @Before
    fun setUp() {
        projectDao = FakeProjectDao()
        milestoneDao = FakeMilestoneDao()
        projectDao.milestoneSource = milestoneDao
        syncTracker = mockk(relaxed = true)
        // PR-1 (#1085) added projectPhaseDao + projectRiskDao to the
        // ProjectRepository constructor. These tests don't exercise the
        // phase/risk paths, so relaxed mocks keep the call sites that
        // do compile while phase/risk-specific tests live in their own
        // files (see ProjectPhaseDaoTest / ProjectRiskDaoTest).
        repo = ProjectRepository(
            projectDao,
            syncTracker,
            milestoneDao,
            mockk<ProjectPhaseDao>(relaxed = true),
            mockk<ProjectRiskDao>(relaxed = true)
        )
    }

    // ---------------------------------------------------------------------
    // Legacy API (unchanged)
    // ---------------------------------------------------------------------

    @Test
    fun addProject_storesFieldsAndTracksCreate() = runBlocking {
        val id = repo.addProject(
            name = "Launch",
            color = "#FF0000",
            icon = "\uD83D\uDE80"
        )

        val stored = projectDao.projects.single { it.id == id }
        assertEquals("Launch", stored.name)
        assertEquals("#FF0000", stored.color)
        assertEquals("\uD83D\uDE80", stored.icon)
        coVerify { syncTracker.trackCreate(id, "project") }
    }

    @Test
    fun addProject_defaultColorAndIconWhenUnspecified() = runBlocking {
        val id = repo.addProject(name = "Inbox")
        val stored = projectDao.projects.single { it.id == id }
        assertEquals("#4A90D9", stored.color)
        assertEquals("\uD83D\uDCC1", stored.icon)
        assertEquals("ACTIVE", stored.status)
    }

    @Test
    fun updateProject_bumpsUpdatedAtAndPersistsFields() = runBlocking {
        val id = projectDao.insert(ProjectEntity(name = "Old", createdAt = 1L, updatedAt = 1L))
        val existing = projectDao.projects.single { it.id == id }

        repo.updateProject(existing.copy(name = "New"))

        val after = projectDao.projects.single { it.id == id }
        assertEquals("New", after.name)
        assertTrue("updatedAt should advance after update", after.updatedAt > existing.updatedAt)
        coVerify { syncTracker.trackUpdate(id, "project") }
    }

    @Test
    fun deleteProject_removesFromStoreAndTracksDelete() = runBlocking {
        val id = projectDao.insert(ProjectEntity(name = "Throw away"))
        val existing = projectDao.projects.single { it.id == id }

        repo.deleteProject(existing)

        assertTrue(projectDao.projects.none { it.id == id })
        coVerify { syncTracker.trackDelete(id, "project") }
    }

    @Test
    fun getAllProjects_flowReflectsInsertedProjects() = runBlocking {
        projectDao.insert(ProjectEntity(name = "Alpha"))
        projectDao.insert(ProjectEntity(name = "Beta"))
        val list = repo.getAllProjects().first()
        assertEquals(2, list.size)
        assertTrue(list.any { it.name == "Alpha" })
        assertTrue(list.any { it.name == "Beta" })
    }

    @Test
    fun getProjectById_flowReturnsTheMatchingProjectOrNull() = runBlocking {
        val id = projectDao.insert(ProjectEntity(name = "Findable"))

        val hit = repo.getProjectById(id).first()
        assertNotNull(hit)
        assertEquals("Findable", hit?.name)

        val miss = repo.getProjectById(9999L).first()
        assertEquals(null, miss)
    }

    @Test
    fun searchProjects_filtersByNameSubstring() = runBlocking {
        projectDao.insert(ProjectEntity(name = "Home Renovation"))
        projectDao.insert(ProjectEntity(name = "Work Tasks"))
        projectDao.insert(ProjectEntity(name = "Homework"))

        val results = repo.searchProjects("home").first()
        assertEquals(2, results.size)
        assertTrue(results.all { it.name.contains("Home", ignoreCase = true) })
    }

    @Test
    fun multipleProjectsWithSameName_areAllPersisted() = runBlocking {
        // The repository/DAO doesn't enforce name uniqueness — duplicates should
        // coexist rather than silently dropping. This documents that contract.
        val a = repo.addProject(name = "Copy")
        val b = repo.addProject(name = "Copy")
        assertTrue(a != b)
        assertEquals(2, projectDao.projects.count { it.name == "Copy" })
    }

    // ---------------------------------------------------------------------
    // v1.4.0 Projects API
    // ---------------------------------------------------------------------

    @Test
    fun addProject_v14_persistsStatusDescriptionDatesAndThemeColorKey() = runBlocking {
        val id = repo.addProject(
            name = "AAPM Abstract",
            description = "Submission for the symposium",
            status = ProjectStatus.ACTIVE,
            startDate = 100L,
            endDate = 200L,
            themeColorKey = "primary"
        )
        val stored = projectDao.projects.single { it.id == id }
        assertEquals("Submission for the symposium", stored.description)
        assertEquals("ACTIVE", stored.status)
        assertEquals(100L, stored.startDate)
        assertEquals(200L, stored.endDate)
        assertEquals("primary", stored.themeColorKey)
        // Legacy hex kept dual-written for back-compat.
        assertEquals("#4A90D9", stored.color)
    }

    @Test
    fun observeProjects_filtersByStatusAndAllWhenNull() = runBlocking {
        repo.addProject("Active-A", null, ProjectStatus.ACTIVE, null, null, null)
        repo.addProject("Active-B", null, ProjectStatus.ACTIVE, null, null, null)
        repo.addProject("Done", null, ProjectStatus.COMPLETED, null, null, null)
        repo.addProject("Gone", null, ProjectStatus.ARCHIVED, null, null, null)

        assertEquals(2, repo.observeProjects(ProjectStatus.ACTIVE).first().size)
        assertEquals(1, repo.observeProjects(ProjectStatus.COMPLETED).first().size)
        assertEquals(1, repo.observeProjects(ProjectStatus.ARCHIVED).first().size)
        assertEquals(4, repo.observeProjects(null).first().size)
    }

    @Test
    fun completeProject_setsStatusAndCompletedAtAndBumpsUpdatedAt() = runBlocking {
        val id = repo.addProject("Finish me", null, ProjectStatus.ACTIVE, null, null, null)
        val before = projectDao.projects.single { it.id == id }

        repo.completeProject(id)

        val after = projectDao.projects.single { it.id == id }
        assertEquals("COMPLETED", after.status)
        assertNotNull(after.completedAt)
        assertNull(after.archivedAt)
        assertTrue(after.updatedAt >= before.updatedAt)
        coVerify { syncTracker.trackUpdate(id, "project") }
    }

    @Test
    fun archiveProject_setsStatusAndArchivedAt() = runBlocking {
        val id = repo.addProject("Archive me", null, ProjectStatus.ACTIVE, null, null, null)

        repo.archiveProject(id)

        val after = projectDao.projects.single { it.id == id }
        assertEquals("ARCHIVED", after.status)
        assertNotNull(after.archivedAt)
        assertNull(after.completedAt)
    }

    @Test
    fun reopenProject_clearsCompletedAndArchivedTimestamps() = runBlocking {
        val id = repo.addProject("Restart", null, ProjectStatus.ACTIVE, null, null, null)
        repo.completeProject(id)
        assertNotNull(projectDao.projects.single { it.id == id }.completedAt)

        repo.reopenProject(id)

        val after = projectDao.projects.single { it.id == id }
        assertEquals("ACTIVE", after.status)
        assertNull(after.completedAt)
        assertNull(after.archivedAt)
    }

    @Test
    fun addMilestone_appendsAtEndAndBumpsProjectUpdatedAt() = runBlocking {
        val projectId = repo.addProject("With milestones", null, ProjectStatus.ACTIVE, null, null, null)
        val beforeUpdatedAt = projectDao.projects.single { it.id == projectId }.updatedAt
        Thread.sleep(2) // ensure `now` advances past the insert timestamp

        val firstId = repo.addMilestone(projectId, "Outline")
        val secondId = repo.addMilestone(projectId, "Draft")
        val thirdId = repo.addMilestone(projectId, "Polish")

        val ms = milestoneDao.getMilestonesOnce(projectId)
        assertEquals(listOf(firstId, secondId, thirdId), ms.map { it.id })
        assertEquals(listOf(0, 1, 2), ms.map { it.orderIndex }) // `-1 + 1 = 0` for the first
        assertTrue(projectDao.projects.single { it.id == projectId }.updatedAt > beforeUpdatedAt)
    }

    @Test
    fun toggleMilestone_setsCompletedAtOnCompleteAndClearsOnUntoggle() = runBlocking {
        val projectId = repo.addProject("Toggleable", null, ProjectStatus.ACTIVE, null, null, null)
        val milestoneId = repo.addMilestone(projectId, "Ship it")
        val before = milestoneDao.getByIdOnce(milestoneId)!!
        assertFalse(before.isCompleted)
        assertNull(before.completedAt)

        repo.toggleMilestone(before, completed = true)
        val afterComplete = milestoneDao.getByIdOnce(milestoneId)!!
        assertTrue(afterComplete.isCompleted)
        assertNotNull(afterComplete.completedAt)

        repo.toggleMilestone(afterComplete, completed = false)
        val afterReopen = milestoneDao.getByIdOnce(milestoneId)!!
        assertFalse(afterReopen.isCompleted)
        assertNull(afterReopen.completedAt)
    }

    @Test
    fun reorderMilestones_reindexesInGivenOrderAndIgnoresUnknownIds() = runBlocking {
        val projectId = repo.addProject("Reorder me", null, ProjectStatus.ACTIVE, null, null, null)
        val aId = repo.addMilestone(projectId, "A")
        val bId = repo.addMilestone(projectId, "B")
        val cId = repo.addMilestone(projectId, "C")

        repo.reorderMilestones(projectId, listOf(cId, aId, 9999L, bId))

        val ms = milestoneDao.getMilestonesOnce(projectId)
        assertEquals(listOf(cId, aId, bId), ms.map { it.id })
        assertEquals(listOf(0, 1, 2), ms.map { it.orderIndex }) // unknown 9999 was skipped
    }

    @Test
    fun deleteMilestone_removesRowAndBumpsProject() = runBlocking {
        val projectId = repo.addProject("Delete me", null, ProjectStatus.ACTIVE, null, null, null)
        val ma = repo.addMilestone(projectId, "A")
        repo.addMilestone(projectId, "B")
        val aEntity = milestoneDao.getByIdOnce(ma)!!

        repo.deleteMilestone(aEntity)

        val remaining = milestoneDao.getMilestonesOnce(projectId)
        assertEquals(1, remaining.size)
        assertEquals("B", remaining.single().title)
    }

    @Test
    fun observeProjectsWithProgress_precomputesMilestoneAggregates() = runBlocking {
        val projectId = repo.addProject("Report", null, ProjectStatus.ACTIVE, null, null, null)
        val m1 = repo.addMilestone(projectId, "Sketch")
        repo.addMilestone(projectId, "Write")
        repo.addMilestone(projectId, "Submit")
        repo.toggleMilestone(milestoneDao.getByIdOnce(m1)!!, completed = true)

        val row = repo.observeProjectsWithProgress(ProjectStatus.ACTIVE).first().single()
        assertEquals(3, row.totalMilestones)
        assertEquals(1, row.completedMilestones)
        assertEquals("Write", row.upcomingMilestoneTitle)
        assertEquals(1f / 3f, row.milestoneProgress, 0.001f)
    }

    // ---------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------

    private class FakeProjectDao : ProjectDao {
        val projects = mutableListOf<ProjectEntity>()
        private var nextId = 1L

        override suspend fun insert(project: ProjectEntity): Long {
            val id = if (project.id == 0L) nextId++ else project.id.also { nextId = maxOf(nextId, it + 1) }
            projects.removeAll { it.id == id }
            projects.add(project.copy(id = id))
            return id
        }

        override suspend fun update(project: ProjectEntity) {
            val idx = projects.indexOfFirst { it.id == project.id }
            if (idx >= 0) projects[idx] = project
        }

        override suspend fun delete(project: ProjectEntity) {
            projects.removeAll { it.id == project.id }
        }

        override suspend fun deleteById(id: Long) {
            projects.removeAll { it.id == id }
        }

        override fun getAllProjects(): Flow<List<ProjectEntity>> = flowOf(projects.toList())

        override fun getProjectById(id: Long): Flow<ProjectEntity?> =
            flowOf(projects.firstOrNull { it.id == id })

        override suspend fun getAllProjectsOnce(): List<ProjectEntity> = projects.toList()

        override suspend fun getProjectByIdOnce(id: Long): ProjectEntity? =
            projects.firstOrNull { it.id == id }

        override suspend fun getProjectByNameOnce(name: String): ProjectEntity? =
            projects.firstOrNull { it.name.equals(name, ignoreCase = true) }

        override fun getProjectWithTaskCount(): Flow<List<ProjectWithCount>> = flowOf(
            projects.map {
                ProjectWithCount(
                    id = it.id,
                    name = it.name,
                    color = it.color,
                    icon = it.icon,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    taskCount = 0
                )
            }
        )

        override fun searchProjects(query: String): Flow<List<ProjectEntity>> =
            flowOf(projects.filter { it.name.contains(query, ignoreCase = true) })

        override suspend fun deleteAll() {
            projects.clear()
        }

        override fun observeByStatus(status: String): Flow<List<ProjectEntity>> =
            flowOf(projects.filter { it.status == status })

        override fun observeAll(): Flow<List<ProjectEntity>> = flowOf(projects.toList())

        // The fake can't observe milestones, so aggregate is computed against
        // the shared [FakeMilestoneDao] held by the surrounding test. Tests
        // that exercise aggregates install a surrounding reference via
        // [FakeMilestoneDao.bindToProjectDao].
        var milestoneSource: FakeMilestoneDao? = null

        override suspend fun getAggregateRow(projectId: Long): ProjectAggregateRow? {
            if (projects.none { it.id == projectId }) return null
            val ms = milestoneSource?.snapshot(projectId).orEmpty()
            val openMs = ms.filter { !it.isCompleted }.minByOrNull { it.orderIndex }
            return ProjectAggregateRow(
                id = projectId,
                totalMilestones = ms.size,
                completedMilestones = ms.count { it.isCompleted },
                upcomingMilestoneTitle = openMs?.title,
                totalTasks = 0,
                openTasks = 0
            )
        }

        override suspend fun getTaskActivityDates(projectId: Long): List<Long> = emptyList()
    }

    private class FakeMilestoneDao : MilestoneDao {
        val rows = mutableListOf<MilestoneEntity>()
        private var nextId = 1L

        fun snapshot(projectId: Long): List<MilestoneEntity> =
            rows.filter { it.projectId == projectId }.sortedBy { it.orderIndex }

        override fun observeMilestones(projectId: Long): Flow<List<MilestoneEntity>> =
            flowOf(snapshot(projectId))

        override suspend fun getMilestonesOnce(projectId: Long): List<MilestoneEntity> =
            snapshot(projectId)

        override suspend fun getAllMilestonesOnce(): List<MilestoneEntity> =
            rows.toList()

        override suspend fun getByIdOnce(id: Long): MilestoneEntity? =
            rows.firstOrNull { it.id == id }

        override suspend fun getCompletedTimestamps(projectId: Long): List<Long> =
            rows.filter { it.projectId == projectId }.mapNotNull { it.completedAt }

        override suspend fun getMaxOrderIndex(projectId: Long): Int =
            rows.filter { it.projectId == projectId }.maxOfOrNull { it.orderIndex } ?: -1

        override suspend fun insert(milestone: MilestoneEntity): Long {
            val id = if (milestone.id == 0L) nextId++ else milestone.id.also { nextId = maxOf(nextId, it + 1) }
            rows.removeAll { it.id == id }
            rows.add(milestone.copy(id = id))
            return id
        }

        override suspend fun update(milestone: MilestoneEntity) {
            val idx = rows.indexOfFirst { it.id == milestone.id }
            if (idx >= 0) rows[idx] = milestone
        }

        override suspend fun updateAll(milestones: List<MilestoneEntity>) {
            milestones.forEach { update(it) }
        }

        override suspend fun delete(milestone: MilestoneEntity) {
            rows.removeAll { it.id == milestone.id }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteForProject(projectId: Long) {
            rows.removeAll { it.projectId == projectId }
        }
    }
}
