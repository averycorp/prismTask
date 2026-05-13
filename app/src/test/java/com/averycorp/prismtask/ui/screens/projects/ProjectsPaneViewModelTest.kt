package com.averycorp.prismtask.ui.screens.projects

import androidx.lifecycle.SavedStateHandle
import com.averycorp.prismtask.data.local.dao.MilestoneDao
import com.averycorp.prismtask.data.local.dao.ProjectAggregateRow
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.ProjectPhaseDao
import com.averycorp.prismtask.data.local.dao.ProjectRiskDao
import com.averycorp.prismtask.data.local.dao.ProjectWithCount
import com.averycorp.prismtask.data.local.entity.MilestoneEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.domain.model.ProjectStatus
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit test for [ProjectsPaneViewModel].
 *
 * Verifies the two things the pane VM actually owns: status filter
 * persistence via [SavedStateHandle] and the delegation into the
 * repository's `observeProjectsWithProgress` stream. Everything else is
 * covered by `ProjectRepositoryTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsPaneViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `default status filter is ACTIVE when SavedStateHandle is empty`() = runTest {
        val vm = buildViewModel(FakeProjectDao(), FakeMilestoneDao(), SavedStateHandle())
        assertEquals(ProjectStatus.ACTIVE, vm.statusFilter.first())
    }

    @Test
    fun `setStatusFilter persists the choice into SavedStateHandle`() = runTest {
        val handle = SavedStateHandle()
        val vm = buildViewModel(FakeProjectDao(), FakeMilestoneDao(), handle)

        vm.setStatusFilter(ProjectStatus.COMPLETED)

        assertEquals("COMPLETED", handle.get<String?>(ProjectsPaneViewModel.KEY_STATUS_FILTER))
        assertEquals(ProjectStatus.COMPLETED, vm.statusFilter.first())
    }

    @Test
    fun `setStatusFilter rejects ARCHIVED since archived view is separate`() = runTest {
        val handle = SavedStateHandle()
        val vm = buildViewModel(FakeProjectDao(), FakeMilestoneDao(), handle)

        vm.setStatusFilter(ProjectStatus.ARCHIVED)

        assertEquals(null, handle.get<String?>(ProjectsPaneViewModel.KEY_STATUS_FILTER))
        assertEquals(null, vm.statusFilter.first())
    }

    @Test
    fun `SavedStateHandle with stale ARCHIVED filter falls back to ACTIVE`() = runTest {
        val handle = SavedStateHandle()
        handle[ProjectsPaneViewModel.KEY_STATUS_FILTER] = "ARCHIVED"

        val vm = buildViewModel(FakeProjectDao(), FakeMilestoneDao(), handle)

        assertEquals(ProjectStatus.ACTIVE, vm.statusFilter.first())
    }

    @Test
    fun `setStatusFilter null persists null (All view)`() = runTest {
        val handle = SavedStateHandle()
        val vm = buildViewModel(FakeProjectDao(), FakeMilestoneDao(), handle)

        vm.setStatusFilter(null)

        assertEquals(null, handle.get<String?>(ProjectsPaneViewModel.KEY_STATUS_FILTER))
        assertEquals(null, vm.statusFilter.first())
    }

    @Test
    fun `SavedStateHandle restores previous selection on VM recreation`() = runTest {
        val handle = SavedStateHandle()
        handle[ProjectsPaneViewModel.KEY_STATUS_FILTER] = "COMPLETED"

        val vm = buildViewModel(FakeProjectDao(), FakeMilestoneDao(), handle)

        assertEquals(ProjectStatus.COMPLETED, vm.statusFilter.first())
    }

    @Test
    fun `archiveProject delegates to the repository and removes from active stream`() = runTest {
        val projectDao = FakeProjectDao().apply {
            projects.add(ProjectEntity(id = 1, name = "Keep", status = "ACTIVE"))
            projects.add(ProjectEntity(id = 2, name = "Retire", status = "ACTIVE"))
        }
        val vm = buildViewModel(projectDao, FakeMilestoneDao(), SavedStateHandle())
        advanceUntilIdle()
        assertEquals(setOf("Keep", "Retire"), vm.projects.first().map { it.project.name }.toSet())

        vm.archiveProject(projectId = 2)
        advanceUntilIdle()

        assertEquals(listOf("Keep"), vm.projects.first().map { it.project.name })
        assertEquals("ARCHIVED", projectDao.projects.first { it.id == 2L }.status)
    }

    @Test
    fun `reopenProject moves an archived project back into the active stream`() = runTest {
        val projectDao = FakeProjectDao().apply {
            projects.add(ProjectEntity(id = 1, name = "Gone", status = "ARCHIVED"))
        }
        val vm = buildViewModel(projectDao, FakeMilestoneDao(), SavedStateHandle())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.projects.first().map { it.project.name })

        vm.reopenProject(projectId = 1)
        advanceUntilIdle()

        assertEquals(listOf("Gone"), vm.projects.first().map { it.project.name })
        assertEquals("ACTIVE", projectDao.projects.first { it.id == 1L }.status)
    }

    @Test
    fun `completeProject stamps COMPLETED status and removes from active stream`() = runTest {
        val projectDao = FakeProjectDao().apply {
            projects.add(ProjectEntity(id = 1, name = "Ship", status = "ACTIVE"))
        }
        val vm = buildViewModel(projectDao, FakeMilestoneDao(), SavedStateHandle())
        advanceUntilIdle()

        vm.completeProject(projectId = 1)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), vm.projects.first().map { it.project.name })
        assertEquals("COMPLETED", projectDao.projects.first { it.id == 1L }.status)
    }

    @Test
    fun `projects flow reflects the selected status filter and excludes ARCHIVED from All`() = runTest {
        val projectDao = FakeProjectDao().apply {
            projects.add(ProjectEntity(id = 1, name = "Act", status = "ACTIVE"))
            projects.add(ProjectEntity(id = 2, name = "Done", status = "COMPLETED"))
            projects.add(ProjectEntity(id = 3, name = "Gone", status = "ARCHIVED"))
        }
        val vm = buildViewModel(projectDao, FakeMilestoneDao(), SavedStateHandle())

        advanceUntilIdle()
        assertEquals(listOf("Act"), vm.projects.first().map { it.project.name })

        vm.setStatusFilter(ProjectStatus.COMPLETED)
        advanceUntilIdle()
        assertEquals(listOf("Done"), vm.projects.first().map { it.project.name })

        vm.setStatusFilter(null)
        advanceUntilIdle()
        // All-filter must drop archived rows — archived projects only show
        // up inside the separate archived view (see showArchived test).
        assertEquals(setOf("Act", "Done"), vm.projects.first().map { it.project.name }.toSet())
    }

    @Test
    fun `setShowArchived swaps projects flow to ARCHIVED-only and persists`() = runTest {
        val projectDao = FakeProjectDao().apply {
            projects.add(ProjectEntity(id = 1, name = "Act", status = "ACTIVE"))
            projects.add(ProjectEntity(id = 2, name = "Done", status = "COMPLETED"))
            projects.add(ProjectEntity(id = 3, name = "Gone", status = "ARCHIVED"))
            projects.add(ProjectEntity(id = 4, name = "AlsoGone", status = "ARCHIVED"))
        }
        val handle = SavedStateHandle()
        val vm = buildViewModel(projectDao, FakeMilestoneDao(), handle)
        advanceUntilIdle()
        assertEquals(setOf("Act"), vm.projects.first().map { it.project.name }.toSet())

        vm.setShowArchived(true)
        advanceUntilIdle()

        assertEquals(true, handle.get<Boolean?>(ProjectsPaneViewModel.KEY_SHOW_ARCHIVED))
        assertEquals(setOf("Gone", "AlsoGone"), vm.projects.first().map { it.project.name }.toSet())

        vm.setShowArchived(false)
        advanceUntilIdle()
        assertEquals(false, handle.get<Boolean?>(ProjectsPaneViewModel.KEY_SHOW_ARCHIVED))
        assertEquals(setOf("Act"), vm.projects.first().map { it.project.name }.toSet())
    }

    @Test
    fun `archivedCount reflects the number of ARCHIVED projects`() = runTest {
        val projectDao = FakeProjectDao().apply {
            projects.add(ProjectEntity(id = 1, name = "Act", status = "ACTIVE"))
            projects.add(ProjectEntity(id = 2, name = "Gone1", status = "ARCHIVED"))
            projects.add(ProjectEntity(id = 3, name = "Gone2", status = "ARCHIVED"))
        }
        val vm = buildViewModel(projectDao, FakeMilestoneDao(), SavedStateHandle())
        advanceUntilIdle()

        assertEquals(2, vm.archivedCount.first())
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun buildViewModel(
        projectDao: FakeProjectDao,
        milestoneDao: FakeMilestoneDao,
        savedStateHandle: SavedStateHandle
    ): ProjectsPaneViewModel {
        val syncTracker: SyncTracker = mockk(relaxed = true)
        // PR-1 (#1085) extended ProjectRepository with phase + risk
        // DAOs; this view-model test doesn't exercise either path.
        val repository = ProjectRepository(
            projectDao,
            syncTracker,
            milestoneDao,
            mockk<ProjectPhaseDao>(relaxed = true),
            mockk<ProjectRiskDao>(relaxed = true)
        )
        return ProjectsPaneViewModel(repository, savedStateHandle)
    }

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

        override fun getProjectWithTaskCount(): Flow<List<ProjectWithCount>> = flowOf(emptyList())

        override fun searchProjects(query: String): Flow<List<ProjectEntity>> = flowOf(emptyList())

        override suspend fun deleteAll() = projects.clear()

        override fun observeByStatus(status: String): Flow<List<ProjectEntity>> =
            flowOf(projects.filter { it.status == status })

        override fun observeAll(): Flow<List<ProjectEntity>> = flowOf(projects.toList())

        override suspend fun getAggregateRow(projectId: Long): ProjectAggregateRow? =
            if (projects.any { it.id == projectId }) {
                ProjectAggregateRow(projectId, 0, 0, null, 0, 0)
            } else {
                null
            }

        override suspend fun getTaskActivityDates(projectId: Long): List<Long> = emptyList()
    }

    private class FakeMilestoneDao : MilestoneDao {
        override fun observeMilestones(projectId: Long): Flow<List<MilestoneEntity>> = flowOf(emptyList())
        override suspend fun getMilestonesOnce(projectId: Long): List<MilestoneEntity> = emptyList()
        override suspend fun getAllMilestonesOnce(): List<MilestoneEntity> = emptyList()
        override suspend fun getByIdOnce(id: Long): MilestoneEntity? = null
        override suspend fun getCompletedTimestamps(projectId: Long): List<Long> = emptyList()
        override suspend fun getMaxOrderIndex(projectId: Long): Int = -1
        override suspend fun insert(milestone: MilestoneEntity): Long = 0
        override suspend fun update(milestone: MilestoneEntity) {}
        override suspend fun updateAll(milestones: List<MilestoneEntity>) {}
        override suspend fun delete(milestone: MilestoneEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteForProject(projectId: Long) {}
    }
}
