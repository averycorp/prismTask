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

        vm.setStatusFilter(ProjectStatus.ARCHIVED)

        assertEquals("ARCHIVED", handle.get<String?>(ProjectsPaneViewModel.KEY_STATUS_FILTER))
        assertEquals(ProjectStatus.ARCHIVED, vm.statusFilter.first())
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
    fun `projects flow reflects the selected status filter`() = runTest {
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
        assertEquals(setOf("Act", "Done", "Gone"), vm.projects.first().map { it.project.name }.toSet())
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
