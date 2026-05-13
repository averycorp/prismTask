package com.averycorp.prismtask.ui.screens.projects.roadmap

import androidx.lifecycle.SavedStateHandle
import com.averycorp.prismtask.data.local.entity.ExternalAnchorEntity
import com.averycorp.prismtask.data.local.entity.ProjectPhaseEntity
import com.averycorp.prismtask.data.local.entity.ProjectRiskEntity
import com.averycorp.prismtask.data.local.entity.TaskDependencyEntity
import com.averycorp.prismtask.data.repository.ExternalAnchorRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskDependencyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.ExternalAnchor
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProjectRoadmapViewModel] action methods. Each test
 * exercises one input-validation guard or one repo-delegation path; the
 * read-side `state` Flow is covered by the screen-level smoke tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRoadmapViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var taskRepository: TaskRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var externalAnchorRepository: ExternalAnchorRepository
    private lateinit var taskDependencyRepository: TaskDependencyRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        taskRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        externalAnchorRepository = mockk(relaxed = true)
        taskDependencyRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(projectId: Long = 1L): ProjectRoadmapViewModel =
        ProjectRoadmapViewModel(
            savedStateHandle = SavedStateHandle(mapOf("projectId" to projectId)),
            taskRepository = taskRepository,
            projectRepository = projectRepository,
            externalAnchorRepository = externalAnchorRepository,
            taskDependencyRepository = taskDependencyRepository
        )

    @Test
    fun `savePhase rejects blank title and surfaces an error`() = runTest {
        val vm = newViewModel()
        vm.savePhase(existing = null, title = "  ", description = null, startDate = null, endDate = null, versionAnchor = null)
        advanceUntilIdle()
        assertEquals("Phase title is required.", vm.error.first())
        coVerify(exactly = 0) { projectRepository.addPhase(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `savePhase trims title and delegates to addPhase when no existing row`() = runTest {
        coEvery { projectRepository.addPhase(any(), any(), any(), any(), any(), any(), any(), any()) } returns 42L
        val vm = newViewModel()
        vm.savePhase(
            existing = null,
            title = "  Phase One  ",
            description = "desc",
            startDate = 100L,
            endDate = 200L,
            versionAnchor = "v1.9.0"
        )
        advanceUntilIdle()
        coVerify {
            projectRepository.addPhase(
                projectId = 1L,
                title = "Phase One",
                description = "desc",
                startDate = 100L,
                endDate = 200L,
                versionAnchor = "v1.9.0"
            )
        }
    }

    @Test
    fun `savePhase routes to updatePhase when existing row provided`() = runTest {
        val existing = ProjectPhaseEntity(id = 7L, projectId = 1L, title = "Old")
        coEvery { projectRepository.updatePhase(any()) } just Runs
        val vm = newViewModel()
        vm.savePhase(
            existing = existing,
            title = "New Title",
            description = null,
            startDate = null,
            endDate = null,
            versionAnchor = null
        )
        advanceUntilIdle()
        coVerify { projectRepository.updatePhase(match { it.id == 7L && it.title == "New Title" }) }
    }

    @Test
    fun `saveRisk rejects blank title`() = runTest {
        val vm = newViewModel()
        vm.saveRisk(existing = null, title = "", level = "MEDIUM", mitigation = null)
        advanceUntilIdle()
        assertNotNull(vm.error.first())
        coVerify(exactly = 0) { projectRepository.addRisk(any(), any(), any(), any()) }
    }

    @Test
    fun `saveRisk delegates with trimmed title and chosen level`() = runTest {
        coEvery { projectRepository.addRisk(any(), any(), any(), any()) } returns 1L
        val vm = newViewModel()
        vm.saveRisk(existing = null, title = " Sev ", level = "HIGH", mitigation = "watch it")
        advanceUntilIdle()
        coVerify {
            projectRepository.addRisk(projectId = 1L, title = "Sev", level = "HIGH", mitigation = "watch it")
        }
    }

    @Test
    fun `saveRisk update path preserves id when existing row provided`() = runTest {
        val existing = ProjectRiskEntity(id = 4L, projectId = 1L, title = "Old", level = "LOW")
        coEvery { projectRepository.updateRisk(any()) } just Runs
        val vm = newViewModel()
        vm.saveRisk(existing = existing, title = "New", level = "HIGH", mitigation = null)
        advanceUntilIdle()
        coVerify {
            projectRepository.updateRisk(
                match { it.id == 4L && it.title == "New" && it.level == "HIGH" }
            )
        }
    }

    @Test
    fun `saveAnchor rejects blank label`() = runTest {
        val vm = newViewModel()
        vm.saveAnchor(existing = null, label = "  ", anchor = ExternalAnchor.CalendarDeadline(0L))
        advanceUntilIdle()
        assertNotNull(vm.error.first())
        coVerify(exactly = 0) { externalAnchorRepository.addAnchor(any(), any(), any(), any()) }
    }

    @Test
    fun `saveAnchor delegates new add to repo`() = runTest {
        coEvery { externalAnchorRepository.addAnchor(any(), any(), any(), any()) } returns 99L
        val vm = newViewModel()
        val anchor = ExternalAnchor.CalendarDeadline(1234L)
        vm.saveAnchor(existing = null, label = "Launch Day", anchor = anchor)
        advanceUntilIdle()
        coVerify {
            externalAnchorRepository.addAnchor(projectId = 1L, label = "Launch Day", anchor = anchor)
        }
    }

    @Test
    fun `saveAnchor routes update to repo when existing row provided`() = runTest {
        val existing = ExternalAnchorEntity(
            id = 11L,
            projectId = 1L,
            phaseId = null,
            label = "Old",
            anchorJson = "{}",
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { externalAnchorRepository.updateAnchor(any(), any(), any(), any()) } just Runs
        val vm = newViewModel()
        val newAnchor = ExternalAnchor.BooleanGate("kickoff", expectedState = true)
        vm.saveAnchor(existing = existing, label = "Renamed", anchor = newAnchor)
        advanceUntilIdle()
        coVerify {
            externalAnchorRepository.updateAnchor(
                existing = existing,
                label = "Renamed",
                anchor = newAnchor,
                phaseId = null
            )
        }
    }

    @Test
    fun `addDependency rejects self-edge before hitting repo`() = runTest {
        val vm = newViewModel()
        vm.addDependency(blockerTaskId = 5L, blockedTaskId = 5L)
        advanceUntilIdle()
        assertEquals("A task can't block itself.", vm.error.first())
        coVerify(exactly = 0) { taskDependencyRepository.addDependency(any(), any()) }
    }

    @Test
    fun `addDependency surfaces CycleRejected as a friendly error`() = runTest {
        coEvery { taskDependencyRepository.addDependency(2L, 3L) } returns
            Result.failure(TaskDependencyRepository.DependencyError.CycleRejected(2L, 3L))
        val vm = newViewModel()
        vm.addDependency(blockerTaskId = 2L, blockedTaskId = 3L)
        advanceUntilIdle()
        assertEquals("That edge would close a cycle.", vm.error.first())
    }

    @Test
    fun `addDependency happy-path delegates to repo and closes the editor`() = runTest {
        coEvery { taskDependencyRepository.addDependency(2L, 3L) } returns Result.success(99L)
        val vm = newViewModel()
        vm.openEditor(RoadmapEditor.DependencyEditor)
        vm.addDependency(blockerTaskId = 2L, blockedTaskId = 3L)
        advanceUntilIdle()
        assertEquals(null, vm.editor.first())
        coVerify { taskDependencyRepository.addDependency(2L, 3L) }
    }

    @Test
    fun `deleteDependency forwards to removeById`() = runTest {
        val edge = TaskDependencyEntity(id = 8L, blockerTaskId = 1L, blockedTaskId = 2L, createdAt = 0L)
        coEvery { taskDependencyRepository.removeById(any()) } just Runs
        val vm = newViewModel()
        vm.deleteDependency(edge)
        advanceUntilIdle()
        coVerify { taskDependencyRepository.removeById(8L) }
    }
}
